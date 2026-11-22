# Projeto-Aplicado1-EZBIKES

**EZBikes** é uma loja virtual de bicicletas construída como um sistema **observável**: além de vender, ela produz logs estruturados, métricas de negócio e de desempenho, SLOs com error budget e alertas de burn rate. O projeto foi desenvolvido para o Projeto Aplicado I da pós-graduação (disciplina *Observabilidade, SRE e Engenharia de Confiabilidade de Sistemas*).

> 🚧 Em construção. Esta página será ampliada a cada etapa do cronograma até a entrega.

## Status do cronograma

| Etapa | Situação |
|---|---|
| Esqueleto Spring Boot, Docker Compose, PostgreSQL, Flyway e catálogo inicial | ✅ |
| Carrinho, pedidos, estoque transacional e tratamento de erros | ✅ |
| Logs JSON + ELK (Elasticsearch, Logstash, Kibana) | ✅ |
| Métricas (Actuator, Micrometer, Prometheus) e recording rules | ✅ |
| Dashboards Grafana (Golden Signals, SLO/Error Budget, Negócio) e alertas de burn rate | ⏳ |
| Injeção de falhas e testes de carga com k6 | ⏳ |
| Vitrine HTML/JS e CI no GitHub Actions | ⏳ |
| Evidências, SLOs documentados e reflexão crítica | ⏳ |

## Mapeamento: conteúdo da pós → implementação

| Conteúdo | Aplicação na EZBikes |
|---|---|
| Logs estruturados | Pedidos, erros e consultas ao estoque registrados em JSON |
| ELK Stack | Investigação de falhas no checkout pelo Kibana |
| Prometheus | Coleta de métricas da API e de negócio |
| Grafana | Dashboards operacionais e de negócio |
| Golden Signals | Latência, tráfego, erros e saturação |
| SLI e SLO | Metas de disponibilidade do checkout e latência do catálogo |
| Error Budget | Margem de falhas permitidas no período |
| Burn Rate | Alertas multi-janela sobre consumo acelerado do budget |
| k6 | Simulação de múltiplos clientes acessando a loja |

## Arquitetura (estado atual)

```mermaid
flowchart LR
    U[Cliente] --> API[EZBikes API<br/>Spring Boot 3 · Java 21]
    API --> DB[(PostgreSQL 16)]
    API -- "logs JSON (TCP 50000)" --> LS[Logstash]
    LS --> ES[(Elasticsearch)]
    ES --> KB[Kibana]
    PR[Prometheus] -- "scrape :8081/actuator/prometheus" --> API
    GF[Grafana] --> PR
```

## Como executar

Pré-requisitos: Docker com Docker Compose e **pelo menos 4 GB de memória** disponíveis para o Docker (o Elasticsearch, o Logstash e o Kibana são os serviços mais pesados). Não é preciso ter Java nem Maven instalados, porque a API é compilada dentro do container.

A primeira subida leva alguns minutos: o Kibana demora a ficar disponível.

```bash
docker compose up -d --build
```

| Recurso | URL |
|---|---|
| Catálogo (API) | http://localhost:8080/api/products |
| Filtro por categoria/marca | http://localhost:8080/api/products?category=MTB&brand=Caloi |
| Health check | http://127.0.0.1:8081/actuator/health |
| Métricas (formato Prometheus) | http://127.0.0.1:8081/actuator/prometheus |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (usuário `admin`, senha `ezbikes`, configuráveis no `.env`) |
| Kibana (logs) | http://localhost:5601 → Discover → data view **EZBikes - logs** |
| Elasticsearch | http://localhost:9200 |

Para parar e apagar os dados: `docker compose down -v`.

## API

| Método | Rota | Descrição |
|---|---|---|
| GET | `/api/products?category=&brand=` | Catálogo com filtros opcionais |
| GET | `/api/products/{id}` | Detalhe do produto |
| GET | `/api/carts/{cartId}` | Carrinho (o `cartId` é gerado pelo cliente, por exemplo um UUID) |
| POST | `/api/carts/{cartId}/items` | Adiciona item: `{"productId": 1, "quantity": 2}` |
| DELETE | `/api/carts/{cartId}/items/{productId}` | Remove item |
| DELETE | `/api/carts/{cartId}` | Esvazia o carrinho |
| POST | `/api/orders` | Checkout a partir do carrinho (`cartId`) ou de uma lista de `items` |
| GET | `/api/orders/{id}` | Consulta o pedido |

Exemplo de checkout direto:

```bash
curl -X POST http://localhost:8080/api/orders -H "Content-Type: application/json" \
  -d '{"customerName":"Ana","customerEmail":"ana@example.com","items":[{"productId":1,"quantity":1}]}'
```

### Fluxo do checkout

1. **Reserva:** baixa o estoque com um `UPDATE ... WHERE stock >= qty` atômico e grava o pedido como `CREATED`. Se faltar estoque em qualquer item, a transação inteira é desfeita.
2. **Pagamento:** chama o gateway simulado *fora* de transação, para não prender conexões do pool enquanto espera uma dependência externa.
3. **Conclusão:** marca `PAID`; se o gateway falhar, marca `PAYMENT_FAILED` e devolve o estoque.

O pagamento é **simulado** (não há integração financeira). A taxa de falha do gateway é configurável por `EZBIKES_PAYMENT_FAILURE_RATE` (0 a 1) e servirá para os experimentos de confiabilidade.

### Erros e o error budget

Os erros seguem o formato RFC 7807 (`application/problem+json`). A classificação do status define o que conta como falha do serviço no SLI de disponibilidade:

| Situação | Status | Consome o error budget? |
|---|---|---|
| Dados inválidos, carrinho vazio | 400 | Não (erro do cliente) |
| Produto ou pedido inexistente | 404 | Não |
| Estoque insuficiente | 409 | Não (regra de negócio) |
| Falha do gateway de pagamento | 502 | **Sim** |
| Erro inesperado | 500 | **Sim** |

## Logs estruturados e ELK

Todos os logs saem em **JSON** (`logstash-logback-encoder`), no console e, no profile `demo` do compose, também via TCP para o Logstash. O Logstash grava em índices diários `app-logs-AAAA.MM.DD`.

**Campos principais**

| Campo | Origem | Exemplo |
|---|---|---|
| `@timestamp`, `level`, `message`, `logger` | Logback | `ERROR` |
| `service`, `environment` | fixos | `ezbikes-api`, `demo` |
| `trace_id` | `X-Request-Id` recebido ou gerado (MDC) | `demo-order-0004` |
| `http.method`, `http.path` | MDC | `POST`, `/api/orders` |
| `http.status`, `http.duration_ms` | log de acesso (numéricos) | `502`, `87` |
| `event.action` | eventos de negócio (nome do ECS) | `order.payment_failed` |
| `order_id` | MDC durante o checkout | UUID do pedido |
| `order_total`, `items_count`, `sku`, `payment_reason`, `stock_remaining` | argumentos estruturados | `5999.00` |
| `customer_email` | **mascarado** | `m***@example.com` |

**Eventos de negócio:** `order.created`, `order.paid`, `order.payment_failed` (ERROR), `order.rejected_out_of_stock` (WARN), `stock.low` (WARN, estoque abaixo de 3), `error.unexpected` (ERROR) e `http.access` (um por requisição; WARN para 5xx).

A API devolve o `trace_id` no cabeçalho `X-Request-Id`, para o cliente informar ao suporte. Requisições a `/actuator/**` (health check e scraping) ficam fora do log de acesso para não gerar ruído.

**Consultas KQL úteis no Kibana**

```
level: "ERROR"
event.action: "order.payment_failed"
order_id: "<id do pedido>"                 # história completa de um pedido
trace_id: "<X-Request-Id>"                  # todos os logs de uma requisição
http.status >= 500
http.duration_ms > 100 and http.path: "/api/orders"
event.action: "stock.low"
```

**Privacidade (LGPD):** o e-mail é mascarado na aplicação (`PiiMasker`) e, como segunda camada, o Logstash mascara qualquer e-mail que chegue em texto claro no campo `message` e descarta o `event.original` (a linha bruta). O nome do cliente nunca é registrado nos logs. A resposta da API também não devolve o e-mail.

**Retenção e custo:** o serviço `elk-setup` cria uma política ILM que **apaga índices com mais de 7 dias** e um index template com os tipos de cada campo (`http.status` numérico, IDs como `keyword`), 1 shard e 0 réplicas (nó único). O `kibana-setup` cria a data view. Os dois rodam uma vez a cada `docker compose up` e são idempotentes.

**Resiliência:** a API não depende do Logstash para subir. Se o pipeline de logs cair, a loja continua atendendo e o appender reconecta sozinho.

## Métricas (Prometheus)

A API expõe métricas pelo Actuator + Micrometer em `/actuator/prometheus`. O Prometheus coleta a cada 15 s (modelo *pull*) e guarda 30 dias, a mesma janela dos SLOs.

**Segurança:** o Actuator roda numa **porta de gestão separada (8081)**, publicada só em `127.0.0.1`. A porta pública da loja (8080) responde 404 para `/actuator/prometheus`, porque as métricas de negócio (receita, volume de pedidos) não devem ficar expostas a clientes. O Prometheus acessa a 8081 pela rede interna do Docker.

### Métricas de negócio

| Métrica (Prometheus) | Tipo | Labels | Uso |
|---|---|---|---|
| `ezbikes_orders_total` | Counter | `status`: paid, payment_failed, out_of_stock, rejected, error | volume e resultado dos pedidos |
| `ezbikes_checkout_duration_seconds` | Timer (histograma) | `outcome` | latência do checkout de ponta a ponta |
| `ezbikes_order_value_reais` | DistributionSummary | — | receita (`_sum`) e ticket médio (`_sum / _count`) |
| `ezbikes_cart_items_added_total` | Counter | `category` | interesse por categoria, conversão |
| `ezbikes_payment_gateway_duration_seconds` | Timer (histograma) | `outcome`: success, failure | latência da dependência externa |
| `ezbikes_payment_gateway_failures_total` | Counter | `reason` | falhas do gateway por motivo |
| `ezbikes_product_stock` | Gauge | `sku`, `category` | estoque por produto (atualizado a cada 15 s, sem consultar o banco no scrape) |

Além delas, o Spring Boot publica as métricas técnicas usadas nas Golden Signals: `http_server_requests_seconds` (histograma com buckets exatos em 100 ms, 300 ms, 500 ms e 1 s), `process_cpu_usage`, `jvm_memory_used_bytes`, `hikaricp_connections_active` e `tomcat_threads_busy_threads`. Todas as séries levam o label `application="ezbikes-api"`.

**Cardinalidade:** os labels têm conjuntos pequenos e fechados. Identificadores como `order_id`, cliente ou e-mail **nunca** viram label, porque cada valor distinto criaria uma série temporal nova. Eles ficam nos logs, onde a busca por valor único é barata. A URI das requisições usa o *template* (`/api/products/{id}`), não o caminho real.

**Correspondência com a aula:** o material usa nomes genéricos; no Spring Boot eles são:

| Aula | EZBikes (Micrometer) |
|---|---|
| `http_requests_total` | `http_server_requests_seconds_count` |
| `http_request_duration_bucket` | `http_server_requests_seconds_bucket` |
| label `status` | `status` (também há `outcome`: SUCCESS, CLIENT_ERROR, SERVER_ERROR) |

### Recording rules e SLIs

As regras em [`observability/prometheus/rules/recording.yml`](observability/prometheus/rules/recording.yml) pré-calculam os SLIs nas janelas de 5m, 30m, 1h, 6h, 1d e 30d:

| SLI | Série gravada | Definição |
|---|---|---|
| Disponibilidade do checkout | `ezbikes:checkout_error_ratio:rate<janela>` | `POST /api/orders` com 5xx ÷ total (4xx não contam) |
| Latência do catálogo | `ezbikes:catalog_latency_good_ratio:rate<janela>` | `GET /api/products*` em até 300 ms ÷ total |
| Burn rate | `ezbikes:checkout_burn_rate:rate<janela>`, `ezbikes:catalog_latency_burn_rate:rate<janela>` | taxa de erro ÷ taxa permitida pelo SLO |
| Golden Signals | `ezbikes:http_requests:rate1m`, `ezbikes:http_server_errors:ratio_rate5m`, `ezbikes:http_latency_seconds:p50_5m`/`p95_5m`/`p99_5m` | por URI |

As regras têm **testes unitários** ([`recording_test.yml`](observability/prometheus/rules/recording_test.yml)) que provam, com séries sintéticas, que 409 não entra na taxa de erro, que a taxa é 0 (e não vazia) quando ainda não houve nenhum 5xx e que o SLI de latência usa só o bucket de 300 ms:

```bash
docker run --rm -v "$PWD/observability/prometheus/rules:/rules" --entrypoint promtool   prom/prometheus:v3.1.0 test rules /rules/recording_test.yml
```

PromQL de exemplo (Prometheus → Graph):

```
ezbikes:checkout_error_ratio:rate5m                          # taxa de erro do checkout
ezbikes:checkout_burn_rate:rate1h                            # burn rate da última hora
ezbikes:http_latency_seconds:p99_5m                          # p99 por rota
sum by (status) (rate(ezbikes_orders_total[5m]))             # pedidos por resultado
increase(ezbikes_order_value_reais_sum[1h])                  # receita da última hora
```

O Grafana já sobe com o datasource do Prometheus provisionado (`uid: prometheus`) e a pasta **EZBikes**, onde ficarão os dashboards versionados.

## Testes

```bash
cd api
./mvnw verify
```

Os testes de integração usam **Testcontainers** com PostgreSQL 16 real. Eles verificam o formato dos logs (JSON válido, `trace_id` em todos os logs da requisição, `order_id` nos eventos, status numérico e nenhum e-mail em texto claro), as métricas de negócio (cada resultado do checkout incrementa a série certa e os nomes expostos são os usados pelas recording rules) e cobrem: pedido pago e baixa de estoque, rollback quando falta estoque, devolução do estoque quando o pagamento falha, checkout pelo carrinho, mapeamento dos status HTTP e **20 compras concorrentes** de um produto com 5 unidades (exatamente 5 são vendidas).

Sem Java instalado, rode os testes dentro de um container Maven:

```bash
cd api
docker run --rm -v "$PWD":/w -v ezbikes-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /w maven:3.9-eclipse-temurin-21 mvn -B verify
```

## Estrutura

```
api/                        API Spring Boot (código, migrações Flyway, Dockerfile)
observability/logstash/     Pipeline do Logstash
observability/elk/setup.sh  Retenção (ILM), index template e data view do Kibana
observability/prometheus/   Configuração de scrape e recording rules (com testes)
observability/grafana/      Provisionamento do Grafana e dashboards (JSON)
docker-compose.yml          Orquestração local
```
