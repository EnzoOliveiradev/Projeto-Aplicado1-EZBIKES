# Roteiro da demonstração (~15 minutos)

Objetivo: mostrar que a EZBikes não só funciona, mas que é possível **identificar um problema, medir seu impacto e decidir com base em dados**.

Os comandos abaixo são para **PowerShell**, na pasta do projeto. No PowerShell 5.1, `curl` é outro comando: use `curl.exe`.

---

## Antes da apresentação (30 minutos antes)

1. Subir a stack (a primeira subida leva alguns minutos, o Kibana é o mais lento):

   ```powershell
   $env:GRAFANA_ANONYMOUS_ENABLED="true"; docker compose up -d --build
   ```

   `GRAFANA_ANONYMOUS_ENABLED=true` deixa os dashboards visíveis sem login, só leitura (opcional).

2. Conferir que tudo está saudável (todos `healthy`, e `elk-setup` e `kibana-setup` com `Exited (0)`):

   ```powershell
   docker compose ps -a
   ```

3. Rodar o smoke test, que também repõe o estoque:

   ```powershell
   docker compose run --rm k6 run /scripts/smoke.js
   ```

4. Abrir as abas, nesta ordem:

   | Aba | URL |
   |---|---|
   | Loja | http://localhost:8080 |
   | Grafana · Golden Signals | http://localhost:3000/d/ezbikes-golden-signals?from=now-30m&to=now |
   | Grafana · SLO (janela 1h) | http://localhost:3000/d/ezbikes-slo?var-janela=1h&from=now-1h&to=now |
   | Grafana · Negócio | http://localhost:3000/d/ezbikes-business |
   | Kibana · Logs | http://localhost:5601/app/dashboards#/view/ezbikes-logs |
   | Prometheus · Alertas | http://localhost:9090/alerts |

5. Deixar um terminal aberto mostrando as notificações dos alertas:

   ```powershell
   docker compose logs -f --no-log-prefix alert-receiver
   ```

> ⚠️ **Não rode `load.js`, `spike.js` ou `stress.js` na hora anterior à apresentação.** Eles geram centenas de milhares de requisições saudáveis que ficam na janela de 1h do SLO e diluem o experimento: o burn rate de 5m vai a 50x, mas o de 1h não passa de 14,4x e o alerta crítico **corretamente** não dispara. Isso aconteceu nos testes (ver [evidências](evidencias/experimento-caos.md)). Se acontecer na hora, é um ótimo ponto para explicar o MWMBR, e o plano B está no passo 6.

---

## 1. O que é a EZBikes (1 min)

- README: diagrama de arquitetura e a tabela **conteúdo da pós → implementação**.
- Mensagem: uma loja simples, mas **observável**: logs estruturados, métricas de negócio, SLOs com error budget e alertas de burn rate, tudo como código e testado no CI.

## 2. Uma compra de verdade (2 min)

1. Na **Loja**: filtrar *Mountain bike* + *Caloi*, abrir a **Caloi Explorer Comp 29**, quantidade 2, *Adicionar ao carrinho*.
2. *Carrinho*: nome e e-mail fictícios, *Finalizar compra*.
3. Na confirmação, copiar a busca mostrada: `order_id: "..."`.

## 3. A história do pedido nos logs (1 min)

1. **Kibana** → Discover, data view *EZBikes - logs*, colar a busca `order_id: "..."`.
2. Mostrar: `order.created` → `order.paid`, o mesmo `trace_id` e o e-mail **mascarado** (`c***@example.com`).
3. Mensagem: o e-mail completo nunca entra nos logs (LGPD), e mesmo assim dá para reconstruir tudo pelo `order_id` e pelo `trace_id`.

## 4. A loja vista por métricas (2 min)

1. **Golden Signals**: latência (p50/p95/p99), tráfego, erros e saturação.
2. **Negócio**: pedidos, receita, ticket médio, falhas do gateway.
3. Mensagem: métricas mostram **quanto** e **com que frequência**; os logs mostram **o que** aconteceu com um caso específico. `order_id` nunca vira label de métrica (cardinalidade).

## 5. Os SLOs e o error budget (2 min)

**Grafana · SLO** com *Janela do SLO* = 1h:

- **Disponibilidade do checkout: 99,9%**. Só 5xx contam; 409 (sem estoque) e 400 são respostas corretas.
- **Latência do catálogo: 99% em até 300 ms.**
- Error budget = 100% − SLO; burn rate = erro observado ÷ erro permitido.
- **Política:** budget acima de 50% → deploys liberados · entre 20% e 50% → só correções · abaixo de 20% → congelar deploys.

## 6. O incidente: o gateway de pagamento começa a falhar (5 min)

1. Iniciar o experimento: tráfego constante de 8 jornadas/s e 5% dos pagamentos falhando por 10 minutos.

   ```powershell
   docker compose run --rm k6 run /scripts/chaos.js
   ```

2. Enquanto o burn rate sobe, explicar no **SLO**:
   - burn rate de 5m indo a ~50x (5% ÷ 0,1%);
   - por que o alerta exige **1h e 5m** acima de 14,4x: a janela longa evita alarme por pico; a curta faz o alerta resolver logo depois da correção;
   - a guarda de volume (≥ 10 checkouts na janela curta), que evita o falso positivo observado nos testes.

3. Em **2 a 5 minutos** (no ensaio a partir do zero: 2 min 47 s):
   - o alerta **Checkout · burn rate crítico** passa para *Firing* (Prometheus · Alertas e a lista no dashboard de SLO);
   - a notificação aparece no terminal do `alert-receiver`.

4. Investigar pelo **Kibana**: busca salva *EZBikes · Falhas de pagamento*, ou `event.action: "order.payment_failed"` → `payment_reason: gateway_unavailable`.

5. Opcional: fazer uma compra na loja durante o incidente. Se cair nos 5%, a tela mostra o **código de atendimento**; colar `trace_id: "<código>"` no Kibana leva às três linhas daquela tentativa.

**Plano B (o alerta não disparou em 6 minutos):** aumentar a falha para 20% sem parar o k6:

```powershell
curl.exe -X PUT http://localhost:8080/admin/chaos -H "X-Admin-Token: demo-admin-token-troque" -H "Content-Type: application/json" -d '{\"paymentFailureRate\": 0.2}'
```

## 7. Recuperação e decisão (2 min)

1. Encerrar o experimento: esperar o k6 terminar ou apertar **Ctrl+C**. O `teardown` desliga a falha.
   Para garantir:

   ```powershell
   curl.exe -X DELETE http://localhost:8080/admin/chaos -H "X-Admin-Token: demo-admin-token-troque"
   ```

2. Para ver a recuperação **com clientes**, rodar tráfego saudável:

   ```powershell
   docker compose run --rm -e RATE=0 -e DURATION=6m k6 run /scripts/chaos.js
   ```

3. Em ~4 a 5 minutos, o alerta crítico resolve (notificação `resolved`), **mesmo com o burn rate de 1h ainda alto**. É o reset rápido da janela curta.
4. O alerta **alto (6x em 6h e 30m)** pode disparar e ficar ativo por até 30 minutos: o budget sofreu e o incidente merece análise, mas sem acordar ninguém.
5. Decisão pela política: olhar o error budget restante e dizer em qual faixa estamos.

## 8. Fechamento (1 min)

- Testes automatizados: 31 de integração + 13 unitários na API; testes das regras de SLO e de alertas com `promtool`; tudo no CI do GitHub Actions.
- Resultados dos testes de carga: ruptura em ~1.100 req/s pelo **pool do banco**, com CPU a 22% e nenhum 5xx: a loja ficou lenta, não caiu ([evidências](evidencias/testes-de-carga.md)).
- Lições: [docs/reflexao-critica.md](reflexao-critica.md).

---

## Se algo der errado

| Sintoma | Ação |
|---|---|
| Kibana mostra "Kibana server is not ready yet" | esperar 1 a 2 minutos; `docker compose ps kibana` até ficar `healthy` |
| Kibana sem a data view ou o dashboard | `docker compose up -d --force-recreate kibana-setup` |
| Nenhum log novo no Kibana | `docker compose ps logstash`; a loja continua funcionando sem ele e o envio reconecta sozinho |
| Grafana pede login | usuário `admin`, senha `ezbikes` (ou subir com `GRAFANA_ANONYMOUS_ENABLED=true`) |
| Pedidos respondendo 409 (sem estoque) | `curl.exe -X POST http://localhost:8080/admin/stock/replenish -H "X-Admin-Token: demo-admin-token-troque" -H "Content-Type: application/json" -d '{\"quantity\": 1000}'` |
| Falha de pagamento "presa" ligada | `curl.exe -X DELETE http://localhost:8080/admin/chaos -H "X-Admin-Token: demo-admin-token-troque"` |
| Docker sem memória | a stack completa usa ~3,5 GB; fechar outros programas ou aumentar a memória do Docker Desktop |
