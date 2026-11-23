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
