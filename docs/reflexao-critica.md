# Reflexão crítica

Respostas às perguntas de discussão das aulas de **Logs Estruturados e Stack ELK** e de **Métricas, Dashboards e Alertas com Prometheus e Grafana**, ancoradas no que a EZBikes mostrou na prática. No fim, as lições que não estavam previstas no planejamento.

Referências às evidências: [testes de carga](evidencias/testes-de-carga.md) · [experimentos de caos](evidencias/experimento-caos.md) · [SLOs](slo.md).

---

## Parte 1 · Logs estruturados e ELK

### 1. ELK ou PLG (Fluent Bit + Loki + Grafana)? Quais critérios de negócio pesam?

A escolha técnica (indexação completa × indexação só de labels) tem consequências de negócio:

| Critério | ELK | PLG |
|---|---|---|
| **Custo de armazenamento e infraestrutura** | alto: indexa todo o conteúdo. Na EZBikes, o Elasticsearch sozinho usa ~1,1 GB de memória, mais que API, PostgreSQL, Prometheus e Grafana juntos (~0,7 GB); com Logstash e Kibana, a stack de logs usa ~2,4 GB, mais de três vezes o resto | baixo: guarda o log comprimido em object storage e indexa só os labels |
| **Perfil de uso** | investigação exploratória ("o que aconteceu com o pedido X?"), busca por qualquer campo, agregações (o dashboard de logs da EZBikes agrega `http.duration_ms` por rota) | consultas por serviço/label e depois filtro de texto; ótimo quando já se sabe onde procurar |
| **Ferramenta única** | Kibana separado do Grafana | um só lugar (Grafana) para métricas, logs e alertas, o que reduz troca de contexto no incidente e treinamento |
| **Licença e fornecedor** | licenciamento da Elastic mudou ao longo dos anos; recursos como segurança avançada e relatórios dependem de assinatura | AGPL/Apache, com opção gerenciada (Grafana Cloud) |
| **Competência do time** | Query DSL/KQL, gestão de índices, shards e ILM | LogQL; menos operação de cluster |
| **Compliance** | ILM, exclusão por documento (direito ao esquecimento) | retenção por stream; excluir os dados de um titular é mais difícil |

Para a EZBikes como produto real, pequeno e com time enxuto, o **PLG** provavelmente venceria por custo e por unificar tudo no Grafana, que já é a ferramenta de métricas e alertas. O ELK se justifica quando a busca livre e as agregações sobre o conteúdo dos logs são parte do dia a dia (suporte, auditoria, segurança), ou quando já existe investimento na stack.

### 2. `user_email` é útil para debugging, mas é PII. Como desenhar o pipeline sem violar a LGPD?

O princípio é **minimização na origem e defesa em profundidade**, que é o que a EZBikes implementa:

1. **Não registrar o que não é necessário:** o nome do cliente nunca vai para o log. A resposta da API também não devolve o e-mail.
2. **Mascarar na aplicação:** o `PiiMasker` registra `m***@example.com`, o suficiente para o suporte confirmar "é o cliente que ligou?" sem expor o dado.
3. **Segunda camada no pipeline:** o Logstash mascara qualquer e-mail que escape em texto livre (`mutate gsub` no `message`) e descarta o `event.original`, que guardaria a linha bruta. Testado enviando uma linha com e-mail direto ao Logstash.
4. **Correlacionar por identificadores opacos:** para diagnosticar não é preciso o e-mail, e sim o `order_id` e o `trace_id`. A vitrine mostra ao cliente um **código de atendimento** (o `X-Request-Id`), que leva o suporte aos logs exatos sem nenhum dado pessoal.
5. **Retenção mínima:** índices apagados após 7 dias (ILM). Isso limita a exposição e simplifica o direito ao esquecimento.
6. **Testes automatizados:** o `LoggingIT` falha se o e-mail aparecer em texto claro em qualquer log do checkout.

Se o e-mail completo fosse realmente necessário (por exemplo, auditoria), a alternativa seria **pseudonimização** com hash com chave (HMAC): permite buscar "todos os eventos deste cliente" sem armazenar o e-mail, e a chave fica fora do sistema de logs.

### 3. Por que `order_id` é adequado no Elasticsearch e problemático em Prometheus/Loki?

Porque os modelos de dados são opostos:

- No **Elasticsearch**, cada log é um **documento**; `order_id` é só um campo indexado (`keyword`). Um milhão de pedidos são um milhão de documentos, que é o uso normal do índice invertido. Buscar `order_id: "..."` é exatamente o que ele faz bem: a história de um pedido na EZBikes aparece em milissegundos.
- No **Prometheus** (e nos labels do Loki), cada combinação de labels é uma **série temporal** em memória, com índice próprio. Com `order_id` como label, cada pedido cria uma série que recebe uma amostra e nunca mais é usada: a memória e o índice crescem sem limite (explosão de cardinalidade).

Na EZBikes, as métricas usam só labels de conjunto pequeno e fechado (`status`, `outcome`, `category`, `reason`, ~20 SKUs) e a URI usa o template (`/api/products/{id}`), nunca o caminho real. O detalhe por pedido fica nos logs. Até o `sku` foi uma decisão consciente: aceitável com 20 produtos, mas num catálogo de milhares o correto seria expor só agregados.

### 4. Como a falta de um esquema comum (ECS/OTel) compromete incidentes multi-serviço?

Num incidente, a pergunta é "o que aconteceu com esta requisição em **todos** os serviços?". Sem esquema comum:

- um serviço grava `trace_id`, outro `traceId`, outro `x-correlation-id`: a correlação vira um conjunto de consultas manuais, uma por time;
- `status` é número num serviço e texto em outro; `duration` em ms num e em segundos noutro. Dashboards e alertas transversais ficam errados ou impossíveis;
- mapeamentos conflitantes no mesmo índice (o mesmo nome de campo com tipos diferentes) fazem documentos serem rejeitados, e os logs somem justamente no incidente.

A EZBikes viu isso em miniatura: o Logstash 8 cria por padrão um objeto `event` (`event.original`), e um campo de negócio chamado só `event` (texto) entraria em conflito. A solução foi adotar o nome do ECS, `event.action`. O projeto usa campos simplificados como na aula (`order_id`, `trace_id`), mas com **index template explícito** (tipos definidos para cada campo), o que já evita o problema de tipos. Numa empresa com vários times, o passo seguinte seria adotar ECS ou o modelo do OpenTelemetry como contrato obrigatório, validado no pipeline.

### 5. Riscos de DEBUG em produção, além de performance?

- **Vazamento de dados:** DEBUG costuma registrar payloads completos, cabeçalhos (tokens, cookies), parâmetros SQL. É o caminho mais comum de PII e credenciais irem parar no log, fora do alcance das máscaras pensadas para INFO.
- **Custo e retenção:** volume 10 a 100 vezes maior, o que significa mais armazenamento, mais custo e retenção menor para caber no orçamento. Os logs importantes acabam apagados mais cedo.
- **Sinal afogado em ruído:** no incidente, as linhas relevantes ficam enterradas; dashboards e buscas ficam lentos justamente quando mais se precisa.
- **Risco operacional:** disco cheio ou pipeline saturado (back-pressure no Logstash) podem derrubar a coleta ou, com appenders síncronos, a própria aplicação. Na EZBikes o appender é assíncrono e a API não depende do Logstash para funcionar, uma decisão tomada por esse motivo.
- **Superfície de ataque:** logs detalhados ajudam quem obtiver acesso indevido a entender o sistema.

A prática recomendada é DEBUG desligado por padrão, ativado de forma pontual e temporária (por logger ou por requisição), e sempre passando pelas mesmas máscaras.

---

## Parte 2 · Métricas, dashboards e alertas

### 1. Um alerta de burn rate de janela única disparou três vezes sem incidente real. Como o MWMBR resolve, e qual o trade-off?

A janela única obriga a escolher entre dois defeitos. Curta (5m): qualquer pico de segundos dispara, gerando falsos positivos. Longa (1h): demora a disparar e continua disparando por até uma hora depois de o problema acabar.

O MWMBR exige **as duas janelas acima do limiar**: a longa garante que o consumo é relevante, a curta confirma que o problema está acontecendo **agora**. A EZBikes mostrou os dois lados ao vivo:

- **Filtra o irrelevante:** com 400 ms de latência no catálogo, o burn rate de 5m foi a 100x, mas o de 1h ficou em 5,6x porque a hora tinha ~200 mil acessos rápidos. Nenhum alerta, e corretamente: 10 minutos de lentidão numa hora de alto volume consomem pouco do budget mensal.
- **Reseta rápido:** no experimento de falha no pagamento, o alerta crítico resolveu 4 minutos depois do fim da falha, com a janela de 1h ainda em 18x.

**Trade-offs:**
- **Detecção um pouco mais lenta** que a janela curta sozinha: ~5 minutos do início da falha até a notificação no experimento de 5%.
- **Mais complexidade:** duas janelas por severidade, recording rules em várias janelas e dois níveis (14,4x e 6x).
- **Dependência do histórico:** como a janela longa carrega a última hora, o mesmo incidente pode ou não disparar conforme o tráfego anterior. Minha hipótese no primeiro experimento errou justamente por isso.
- **Não resolve sozinho o caso de pouco tráfego**, que exigiu uma correção adicional (ver "Lições" abaixo).

### 2. Por que `user_id` é bom em log e péssimo como label de métrica?

Pelo mesmo motivo da pergunta 3 da Parte 1. No **log**, `user_id` é um campo de um documento e permite responder "o que aconteceu com este usuário?". No **Prometheus**, cada valor de label cria uma série temporal: com 100 mil usuários, uma única métrica vira 100 mil séries, multiplicadas por cada outro label (rota × método × status × buckets do histograma). Só o `http_server_requests_seconds` da EZBikes tem dezenas de buckets por combinação de rota e status; com `user_id`, isso explodiria a memória do Prometheus e tornaria as consultas lentas.

A regra prática: **métricas respondem "quanto e com que frequência" para grupos; logs respondem "o que aconteceu com este caso específico"**. Na EZBikes o `trace_id` liga os dois: o dashboard mostra que há erros, e o log mostra qual requisição falhou e por quê.

### 3. Quando migrar de Prometheus standalone para Thanos/Cortex/Mimir?

Sinais concretos:

- **Retenção longa** além do que um disco local suporta bem: SLOs trimestrais, planejamento de capacidade com dados de um ano. A EZBikes usa 30 dias, que cabem no standalone.
- **Alta disponibilidade:** quando perder o Prometheus durante uma janela de manutenção significa ficar sem alertas. A solução simples (dois Prometheus idênticos) duplica alertas e não consolida dados.
- **Visão global:** vários clusters ou regiões, e a necessidade de consultar e agregar tudo num só lugar (por exemplo, um SLO calculado sobre todas as regiões).
- **Escala:** milhões de séries ativas, consultas pesadas competindo com a ingestão, memória do nó no limite.
- **Multi-tenancy:** vários times precisando de isolamento e limites próprios.

Antes de migrar, vale esgotar o mais barato: reduzir cardinalidade, usar recording rules e federar. Para a EZBikes (um serviço, um nó, 30 dias), o standalone é o correto; migrar agora seria complexidade sem benefício.

### 4. Recording rules: quando compensam e quando são desperdício?

**Compensam quando** a mesma expressão cara é consultada muitas vezes ou precisa de janelas longas:

- **SLOs e error budget:** `rate()` sobre 30 dias de dados brutos a cada atualização de dashboard seria pesado. Na EZBikes, as 51 regras pré-calculam os SLIs em seis janelas.
- **Alertas:** são avaliados a cada minuto; uma série pronta deixa a avaliação barata e previsível.
- **Consistência:** dashboard, alerta e teste usam exatamente a mesma definição do SLI. Isso permitiu testar as regras com `promtool` (por exemplo, provar que o 409 não conta como erro).

**São desperdício quando:**

- a expressão só é usada em investigações ocasionais (ad hoc);
- a janela ou o recorte mudam com frequência; a regra vira dívida;
- a regra **preserva labels de alta cardinalidade**, criando mais séries que a métrica original;
- as janelas são pré-calculadas "por via das dúvidas". Na EZBikes, o burn rate de 1d (`ezbikes:checkout_burn_rate:rate1d` e o equivalente do catálogo) não é usado por nenhum alerta nem painel, e poderia ser removido.

Há também um custo menos óbvio: a regra é avaliada mesmo quando ninguém olha, e um erro nela (como no caso do NaN, abaixo) se propaga para todos os consumidores.

### 5. Como equilibrar métricas de negócio (receita) com o risco de exposição em `/actuator/prometheus`?

A EZBikes aplicou camadas:

1. **Porta separada:** o Actuator roda na **8081**, publicada só em `127.0.0.1`; a porta pública da loja (8080) responde 404 para `/actuator/prometheus`. O Prometheus acessa a 8081 pela rede interna do Docker. Em Kubernetes, o equivalente é não expor a porta de gestão no Service/Ingress e restringir com NetworkPolicy.
2. **Granularidade adequada:** expor `ezbikes_order_value_reais` agregado (soma e contagem), nunca valor por pedido ou por cliente. A métrica responde "quanto vendemos por minuto", não "quanto o cliente X gastou".
3. **Autenticação no acesso humano:** o Grafana exige login (o acesso anônimo existe, mas desligado por padrão).
4. **Princípio do menor privilégio no que é exposto:** o Actuator expõe só `health`, `info` e `prometheus`; endpoints como `env`, `heapdump` e `configprops`, que vazariam segredos, não estão habilitados.

Em produção, os passos seguintes seriam: autenticação ou mTLS no scrape, TLS na rede interna e uma revisão periódica de quais métricas de negócio realmente precisam existir.

---

## Lições que não estavam no plano

1. **O sistema não caiu, ficou lento.** No teste de estresse, por volta de 1.100 req/s, o pool de 10 conexões do banco esgotou, as 200 threads do Tomcat foram ocupadas e o p99 do catálogo saltou de 5 ms para 355 ms, **com CPU em 22% e nenhum erro 5xx**. Escalar CPU não resolveria. O SLO de disponibilidade continuaria verde; só o SLO de latência mostrou o problema. Ter os dois SLOs não foi redundância.

2. **Uma decisão de arquitetura apareceu como capacidade.** O pagamento (30 a 120 ms) é chamado fora da transação do banco. Se estivesse dentro, cada checkout seguraria uma conexão durante o pagamento, e o pool de 10 conexões limitaria a loja a poucas dezenas de checkouts por segundo.

3. **A janela longa carrega o passado.** Errei duas previsões pelo mesmo motivo: achei que a janela de 1h estaria diluída e ela ainda continha um incidente anterior; depois, num experimento de latência, ela estava diluída pelos testes de carga. Ler o burn rate exige saber o que aconteceu na última hora, e o dashboard de SLO precisa mostrar as janelas lado a lado, como faz.

4. **Proporções mentem com pouco tráfego.** Um único checkout com falha, num momento sem outros pedidos, virou 50% de erro e burn rate de 500x, e disparou o alerta de 6x. A correção foi exigir um volume mínimo (10 requisições) na janela curta, com teste no `promtool`. O custo dessa correção é que, com tráfego quase nulo, nem uma queda total dispara o alerta; isso pediria monitoramento sintético.

5. **"Sem dados" precisa de uma decisão explícita.** Sem tráfego, a taxa de erro é 0 ÷ 0 = NaN. O Prometheus tratou o NaN como "não dispara", e o Grafana manteve o alerta ativo por 16 minutos a mais. As consultas do Grafana agora convertem NaN em 0. Cada ferramenta tem sua semântica para ausência de dados, e ela precisa ser definida e testada, não presumida.

6. **Latência sem saturação é outro diagnóstico.** Com latência injetada, CPU, pool e threads ficaram baixos; no estresse, subiram junto com a latência. Os painéis de saturação dos Golden Signals são o que separa "uma dependência está lenta" de "nós estamos sem recurso".

7. **Observabilidade também precisa de testes.** Regras de alerta, máscaras de PII e formato de logs foram testados como código (`promtool`, `LoggingIT`, `MetricsIT`) e verificados no CI. O `LoggingIT` pegou um problema que passaria despercebido: o Logback descartava a configuração inteira por causa de um `springProfile` mal posicionado, e a API simplesmente não geraria nenhum log. O falso positivo de baixo volume, ao contrário, foi encontrado ao vivo, e virou um teste para não voltar.

8. **Os limites de memória também são observabilidade.** Na revisão final, o Logstash estava usando 788 MB de um limite de 800 MB, perto de ser encerrado pelo Docker, o que interromperia a coleta de logs sem que nenhum alerta avisasse. O limite subiu para 1 GB. Em produção, a saturação da própria stack de observabilidade também precisa ser monitorada.

## O que eu faria a seguir

- **Tracing distribuído** com OpenTelemetry e Jaeger/Tempo: o `trace_id` dos logs já está pronto para virar um trace de verdade quando houver mais de um serviço.
- **Monitoramento sintético** do checkout, para cobrir o caso de pouco tráfego que a guarda de volume deixa descoberto.
- **Cache do catálogo**, a alavanca indicada pelo teste de estresse, e então repetir o teste para medir o ganho.
- **Kubernetes** (retomando o trabalho de EKS da disciplina anterior), com o Actuator protegido por NetworkPolicy e o Prometheus descobrindo os pods automaticamente.
