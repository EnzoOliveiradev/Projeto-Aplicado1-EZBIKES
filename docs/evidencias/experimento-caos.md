# Experimento de caos: alerta de burn rate validado com k6

Execução em 23/11/2026 (horários em UTC). Critério da aula: *regra de alerta configurada e validada com teste de carga (k6)*.

## Hipótese

Com 5% dos pagamentos falhando (502) sob tráfego constante, o burn rate do SLO de disponibilidade do checkout (99,9%) deve chegar a cerca de **50x** (5% ÷ 0,1%). O alerta crítico (burn > 14,4x em 1h **e** em 5m) deve disparar em poucos minutos e voltar ao normal poucos minutos depois de a falha ser removida, mesmo com a janela de 1h ainda alta.

**Correção feita durante o experimento:** a previsão inicial era que a janela de 1h ficaria diluída pelos ~28 mil checkouts saudáveis dos testes de carga anteriores e não passaria de 14,4x. Ao iniciar, o burn rate de 1h já estava em 14,6x, porque a janela ainda continha o incidente manual do dia anterior (00:16 a 00:20, com 20% de falha). A lição: o burn rate de uma janela longa carrega o histórico recente, e a janela curta é o que indica se o problema está acontecendo agora.

## Execução

```bash
docker compose run --rm k6 run --summary-export /scripts/results/chaos-payment.json /scripts/chaos.js
# EXPERIMENT=payment (padrão), RATE=0.05, 8 iterações/s por 10 minutos
```

O `setup()` do k6 repõe o estoque e ativa a falha (`PUT /admin/chaos {"paymentFailureRate":0.05}`); o `teardown()` desliga a falha ao final. Em seguida rodou-se 7 minutos de tráfego saudável no mesmo ritmo (`-e RATE=0`), para observar a recuperação com clientes ativos.

## Linha do tempo

| Hora | Evento | Burn rate 5m | Burn rate 1h |
|---|---|---|---|
| 00:55:52 | k6 ativa 5% de falha no gateway | 0x | 14,6x |
| 01:00:05 | alerta crítico em **Pending** | 50,2x | 16,1x |
| 01:00:55 | alerta crítico em **Firing**; notificação recebida pelo `alert-receiver` | 51,3x | 16,4x |
| 01:04:52 | alerta alto (6x em 6h e 30m) em Pending | 52,6x | 18,1x |
| 01:05:52 | k6 desliga a falha (`teardown`) e o tráfego saudável continua | — | — |
| 01:09:55 | alerta crítico **resolvido**; notificação de resolução recebida | 14,3x | 18,0x |
| 01:09:57 | alerta alto em **Firing** (janela de 30m em 7,1x) | 9,9x | 17,9x |

Notificações registradas pelo `alert-receiver` (`docker compose logs alert-receiver`):

```
01:00:55 firing   Checkout · burn rate crítico (14,4x em 1h e 5m)  A(1h)=16.4  B(5m)=51.3
01:09:55 resolved Checkout · burn rate crítico (14,4x em 1h e 5m)  A(1h)=18.0  B(5m)=14.3
01:09:57 firing   Checkout · burn rate alto (6x em 6h e 30m)       A(6h)=18.0  B(30m)=7.1
```

## Resultado do k6

| Métrica | Valor |
|---|---|
| Checkouts | 4.801 |
| Checkouts com falha (502) | 249 (**5,19%**, conforme os 5% injetados) |
| `http_req_failed` | 1,72% (inclui as requisições de catálogo, que não falharam) |
| p95 do checkout | 125 ms (a falha do gateway não aumentou a latência) |

## Conclusões

1. **Detecção:** ~5 minutos do início da falha até a notificação (01:00:55). O tempo é dominado pela janela de 5m precisar acumular erros suficientes e pelo `for: 1m`. Nesse intervalo, cerca de 0,6% do error budget mensal foi consumido.
2. **Reset rápido:** o alerta crítico resolveu 4 minutos depois do fim da falha, embora o burn rate de 1h continuasse em 18x. Um alerta só com a janela de 1h ficaria disparando por quase uma hora depois do problema resolvido.
3. **Escalonamento correto:** o alerta alto (6x) só disparou depois de a janela de 30m acumular o incidente. Ele permanece ativo enquanto o consumo de 30m estiver acima de 6x, sinalizando que o budget sofreu e merece análise, sem acordar ninguém de madrugada.
4. **Decisão pela política:** o budget de 30 dias continua positivo, mas incidentes como este, repetidos, levariam o time para a faixa de "só correções". O caminho de investigação é o Kibana: `event.action:"order.payment_failed"` mostra o motivo (`payment_reason: gateway_unavailable`) e os pedidos afetados.

---

# Experimento 2: latência no catálogo

Execução em 23/11/2026 (UTC). Critério: validar os alertas do SLO de latência do catálogo (99% das requisições em até 300 ms) com o k6.

```bash
docker compose run --rm -e EXPERIMENT=latency k6 run /scripts/chaos.js                              # 400 ms, 10 min
docker compose run --rm -e EXPERIMENT=latency -e LATENCY_MS=600 -e DURATION=15m k6 run /scripts/chaos.js
```

O experimento aplica latência extra só em `GET /api/products*` (`latencyScope: CATALOG`), com 16 requisições por segundo ao catálogo.

## Rodada A: 400 ms extras por 10 minutos (01:40 a 01:50)

| Métrica | Valor |
|---|---|
| Requisições ao catálogo acima de 300 ms | ~100% (p95 de 407 ms no k6) |
| Burn rate 5m | **100x** |
| Burn rate 1h | subiu de 0,3x para **5,6x** ao final |
| p99 do catálogo | 446 ms |
| Alertas | **nenhum disparou** |

**Por que nenhum alerta disparou (e por que isso está correto):** a janela de 1h ainda continha os ~200 mil acessos rápidos ao catálogo dos testes de carga de 00:40 a 00:55. Os 9.600 acessos lentos representavam ~5% da hora, ou seja, burn rate de ~5x, abaixo de 14,4x. O alerta de p99 exige mais de 500 ms, e o p99 ficou em 446 ms. Em termos de budget, 10 minutos de lentidão total numa hora de alto volume consomem pouco do mês: é exatamente o caso que a janela longa existe para filtrar.

## Rodada B: 600 ms extras por 15 minutos (01:50 a 02:05)

| Hora | Evento | Burn 5m | Burn 1h | p99 |
|---|---|---|---|---|
| 01:50:50 | k6 ativa 600 ms de latência no catálogo | 100x | ~6x | — |
| 01:52:02 | alerta de **p99 > 500 ms** em Pending | 100x | 7,8x | 621 ms |
| 01:54:55 | o pico do teste de estresse sai da janela de 1h; alerta crítico de latência em Pending | 100x | 19,1x | 625 ms |
| 01:55:50 | **alerta crítico de latência disparado** e notificado | 100x | 45,6x | 625 ms |
| 01:56:52 | **alerta de p99 disparado** e notificado | 100x | 49,6x | 625 ms |
| 02:05:52 | k6 desliga a latência | — | — | — |
| 02:12:42 | alerta de p99 resolvido (5 min de janela + avaliação) | — | — | — |
| 02:13:40 | alerta crítico de latência resolvido (*) | 0x | — | — |

(*) O Grafana foi reiniciado às 02:12 para carregar as regras revisadas descritas abaixo, então este horário de resolução não é uma medida limpa.

**Diagnóstico pelos dashboards:** durante o experimento, CPU, pool de conexões e threads ficaram baixos. A lentidão não vinha de saturação, e sim de "fora" (aqui, a latência injetada; na vida real, uma dependência lenta). No teste de estresse, ao contrário, a latência subiu junto com o pool e as threads esgotados. Os painéis de saturação são o que separa os dois diagnósticos.

---

# Achado: falso positivo com pouco tráfego

Ao testar a mensagem de falha da vitrine às 01:26, foi feito **um** checkout com 100% de falha, num período quase sem tráfego de checkout. Resultado:

| Hora | Janela de 30m do checkout | Burn rate 30m |
|---|---|---|
| até 01:42 | ainda continha o tráfego saudável do experimento anterior | < 2x |
| 01:44 a 01:52 | só restavam 2 checkouts (1 pago, 1 falha) = 50% de erro | **500x** |
| a partir de 01:54 | nenhum checkout na janela (0 ÷ 0) | NaN |

O alerta **alto (6x em 6h e 30m) disparou às 01:48:57** por causa de **uma única falha**. Além disso, quando a razão virou NaN (sem tráfego), o Prometheus considerou o alerta inativo, mas o Grafana o manteve ativo até 02:09:57, 16 minutos a mais.

**Correções aplicadas (com teste):**

1. **Volume mínimo:** os alertas de burn rate agora exigem pelo menos **10 requisições na janela curta** (`and ezbikes:checkout_requests:rate5m * 300 >= 10`). Uma proporção calculada sobre 2 pedidos não é evidência de perda para os clientes. O caso entrou em `alerts_test.yml` ("uma falha isolada com pouco tráfego não dispara alerta"), que também prova que, **sem** a guarda, as duas janelas estariam acima de 14,4x (a de 5m em 1.000x).
2. **Sem tráfego = 0, não NaN:** as consultas do Grafana usam `(expr >= 0) or vector(0)`. Assim o Grafana e o Prometheus decidem da mesma forma quando não há requisições.

A guarda tem um custo, que é a principal limitação dela: num serviço com pouquíssimo tráfego, uma falha total também não dispara alerta de burn rate. Para esse cenário, o recomendado é monitoramento sintético (requisições periódicas de teste), que fica como evolução do projeto.

**Validação das regras revisadas (02:14 a 02:21):** nova rodada de `chaos.js` (5% de falha no pagamento, 8 jornadas/s). Os alertas de checkout entraram em Pending às 02:15:56 e o crítico foi **disparado e notificado às 02:16:55** (burn rate de 46x em 1h e 43x em 5m, com 930 checkouts na janela de 5m, muito acima do mínimo de 10). A guarda de volume não atrasa a detecção quando há tráfego real.
