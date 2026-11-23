# SLOs da EZBikes

Este documento define o que "funcionar bem" significa para a loja, como isso é medido e o que o time faz quando a meta está em risco.

## Hierarquia

| Conceito | Definição | Na EZBikes |
|---|---|---|
| **SLI** (indicador) | Medida quantitativa do serviço, do ponto de vista do cliente | Proporção de checkouts sem erro 5xx; proporção de consultas ao catálogo em até 300 ms |
| **SLO** (objetivo) | Meta interna para o SLI num período | 99,9% e 99%, em 30 dias corridos |
| **SLA** (acordo) | Compromisso contratual, com penalidade | Não há: é uma loja de demonstração. Um SLA, se existisse, seria mais frouxo que o SLO (ex.: 99,5%), para o time ser alertado antes de violar o contrato |
| **Error budget** | 100% − SLO: quanto de falha é aceitável | 0,1% dos checkouts; 1% das consultas ao catálogo |

## SLO 1 — Disponibilidade do checkout

O checkout é a jornada que gera receita. Se ele falha, o cliente desiste da compra.

| Item | Valor |
|---|---|
| **SLI** | `POST /api/orders` com status diferente de 5xx ÷ total de `POST /api/orders` |
| **SLO** | **99,9%** em 30 dias |
| **Error budget** | 0,1% das requisições (1 falha a cada 1.000 checkouts) |
| **Fonte** | `http_server_requests_seconds_count{uri="/api/orders",method="POST"}` (Micrometer) |
| **Série gravada** | `ezbikes:checkout_error_ratio:rate<janela>` (5m, 30m, 1h, 6h, 1d, 30d) |

**O que conta como falha:** apenas 5xx. Falha do gateway de pagamento (502) e erro inesperado (500) são falhas do serviço.

**O que não conta:** 400 (dados inválidos, carrinho vazio), 404 (produto inexistente) e 409 (estoque insuficiente). São respostas corretas a pedidos que não podem ser atendidos; contá-las puniria o serviço por comportamento do cliente ou do negócio. Essa separação é garantida pelo `ApiExceptionHandler` e coberta por testes (`OrderApiIT`, `recording_test.yml`).

## SLO 2 — Latência do catálogo

O catálogo é a porta de entrada da loja; lentidão nele reduz a conversão antes mesmo do carrinho.

| Item | Valor |
|---|---|
| **SLI** | `GET /api/products*` respondidos em até 300 ms ÷ total |
| **SLO** | **99%** em 30 dias |
| **Error budget** | 1% das requisições pode passar de 300 ms |
| **Fonte** | `http_server_requests_seconds_bucket{le="0.3"}`: bucket exato configurado em `management.metrics.distribution.slo` |
| **Série gravada** | `ezbikes:catalog_latency_good_ratio:rate<janela>` |

Medir a **proporção abaixo do limite** (e não "o p99 está abaixo de 300 ms") torna o SLI agregável e permite calcular o error budget da mesma forma que no SLO de disponibilidade. O p99 continua no dashboard e tem um alerta próprio (acima de 500 ms por 5 minutos).

## Burn rate e alertas

**Burn rate** = taxa de erro observada ÷ taxa de erro permitida pelo SLO. Com burn rate 1x o budget acaba exatamente no fim dos 30 dias; com 14,4x, 2% do budget mensal é consumido em 1 hora.

Os alertas seguem o padrão **multi-window, multi-burn-rate** (Google SRE Workbook, cap. 5):

| Alerta | Condição | `for` | Severidade | Budget consumido |
|---|---|---|---|---|
| `CheckoutErrorBudgetBurnCritical` | burn > 14,4x em **1h e 5m**, com ≥ 10 checkouts em 5m | 1m | crítico (acionar agora) | 2% em 1 hora |
| `CheckoutErrorBudgetBurnHigh` | burn > 6x em **6h e 30m**, com ≥ 10 checkouts em 30m | 5m | alto (chamado) | 5% em 6 horas |
| `CatalogLatencyBudgetBurnCritical` | burn de latência > 14,4x em 1h e 5m, com ≥ 10 requisições em 5m | 1m | crítico | 2% em 1 hora |
| `CatalogLatencyP99High` | p99 do catálogo > 500 ms | 5m | alto | — |

**Por que duas janelas:**
- só a janela longa (1h) demoraria a disparar e continuaria disparando por até 1 hora depois de o problema ser resolvido;
- só a janela curta (5m) dispararia com qualquer pico de segundos (falso positivo);
- exigir as duas dá detecção relevante **e** reset rápido. O teste `alerts_test.yml` prova os dois lados: o alerta dispara durante o incidente e já está inativo 10 minutos depois do fim das falhas, embora a janela de 1h ainda esteja alta; uma taxa de 0,5% (5x) não dispara nada; 1% persistente (10x) dispara só o alerta de 6x.

**Volume mínimo:** com pouco tráfego, uma única falha vira uma proporção enorme (1 erro em 2 pedidos = 50%, burn rate de 500x). Isso aconteceu de verdade durante os testes: um único checkout com falha disparou o alerta de 6x (ver [evidências](evidencias/experimento-caos.md#achado-falso-positivo-com-pouco-tráfego)). Por isso os alertas de burn rate exigem pelo menos 10 requisições na janela curta. O custo: num período com pouquíssimo tráfego, nem uma falha total dispara o alerta. A cobertura desse caso seria monitoramento sintético, que fica como evolução.

**Sem tráfego:** a razão erros ÷ requisições vira NaN (0 ÷ 0). As consultas do Grafana convertem NaN em 0 (`(expr >= 0) or vector(0)`), para decidir igual ao Prometheus: ausência de tráfego nunca é "disparando".

**Onde estão:**
- Grafana Alerting (quem notifica): `observability/grafana/provisioning/alerting/ezbikes-alerts.yml`, gerado por `build_alerts.py` a partir de
- regras do Prometheus (fonte, testadas com `promtool`): `observability/prometheus/rules/alerts.yml`.

As notificações vão para o contact point `ezbikes-webhook`, que na demonstração é um receptor simples (`alert-receiver`) que registra cada notificação no log. Em produção seria Slack, e-mail ou PagerDuty.

## Política de error budget

| Budget restante (30d) | Situação | Decisão |
|---|---|---|
| **> 50%** | saudável | Deploys liberados; espaço para experimentar |
| **20% a 50%** | atenção | Só correções e melhorias de confiabilidade; revisar incidentes recentes |
| **< 20%** | crítico | Congelar deploys de funcionalidade; prioridade total em confiabilidade até o budget se recuperar |
| **< 0%** | SLO violado | Post-mortem obrigatório; ações corretivas entram no planejamento |

Os limites de 50% e 20% aparecem como linhas tracejadas no painel *Error budget restante* do dashboard **EZBikes · SLO e Error Budget**.

## Simplificações desta demonstração

- **Janela de 30 dias:** o projeto foi construído em menos de 30 dias, então a janela oficial ainda não acumula dados suficientes. O dashboard tem a variável *Janela do SLO* (30d, 1d, 6h, 1h); com **1h** dá para ver o budget sendo consumido ao vivo durante a demonstração. Os cálculos e os alertas são os mesmos.
- **`for` curto:** os alertas usam `for: 1m` (crítico) para a demonstração caber em poucos minutos. Em produção, 2 minutos é um valor comum.
- **Um único serviço:** com mais réplicas, as recording rules já agregam com `sum()`, então os SLIs continuariam corretos.
