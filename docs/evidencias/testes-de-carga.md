# Testes de carga (k6): resultados

Execução em 23/11/2026 (horários em UTC), com toda a stack do `docker-compose.yml` e o k6 rodando no mesmo computador (Windows 11, Docker Desktop com 12 CPUs e 8 GB). Resumos completos do k6 em `load/results/*.json` (gerados localmente, fora do git).

## Resumo

| Teste | Carga | Requisições | Falhas | Catálogo | Checkout | Resultado |
|---|---|---|---|---|---|---|
| `smoke.js` | 2 usuários, 1 min | 223 | 0% | p95 13,9 ms | p95 140 ms | todos os thresholds aprovados |
| `load.js` | 10 → 50 → 100 usuários, 10 min | 48.915 (81 req/s em média, pico de 170) | 0% | p95 5,7 ms | p95 130 ms | todos os SLOs cumpridos; nenhuma degradação |
| `spike.js` | 0 → 150 usuários em 10 s | 57.095 (710 req/s) | 0% | p95 3,0 ms | p95 125 ms | nenhuma degradação |
| `stress.js` | taxa crescente até violar o SLO | 139.876 (pico de ~1.140 req/s) | 0% | p99 > 300 ms | p95 352 ms | **interrompido** ao violar o SLO de latência do catálogo |

## Ponto de ruptura

O `stress.js` aumenta a taxa de chegada a cada minuto e para sozinho quando o p99 do catálogo passa de 300 ms. Métricas do Prometheus, minuto a minuto:

| hora (UTC) | req/s | p99 catálogo | catálogo ≤ 300 ms | p95 checkout | 5xx | conexões do BD em uso (máx. 10) | requisições esperando conexão | threads do Tomcat ocupadas (máx. 200) | CPU |
|---|---|---|---|---|---|---|---|---|---|
| 00:51 | 556 | 5 ms | 100% | 128 ms | 0% | 0 | 0 | 9 | 6% |
| 00:53 | 500 | 5 ms | 100% | 128 ms | 0% | 1 | 0 | 9 | 4% |
| 00:54 | 1.144 | 6 ms | 100% | 130 ms | 0% | 9 | 0 | 19 | 17% |
| 00:55 | 780 | **355 ms** | 98% | **467 ms** | 0% | **10** | **174** | **200** | 22% |

**Leitura:**

1. O recurso que esgota primeiro é o **pool de conexões do banco** (HikariCP, 10 conexões): com ele cheio, 174 requisições ficaram na fila esperando conexão.
2. As requisições na fila prendem threads do Tomcat, que chegam ao máximo (200). A partir daí até o catálogo, que é rápido, espera na fila: o p99 saltou de 5 ms para 355 ms em um minuto.
3. A **CPU estava em 22%**: escalar CPU não resolveria. As alavancas são o tamanho do pool, cache do catálogo (que hoje consulta o banco a cada requisição) ou réplicas de leitura.
4. **Nenhum erro 5xx**: a loja não caiu, ficou lenta. O SLO de disponibilidade seguiria verde enquanto o de latência estoura. Por isso os dois SLOs existem.
5. É o comportamento previsto na aula: a latência sobe antes da saturação total.

**Limite desta máquina:** cerca de **1.100 req/s** (~370 jornadas de compra por segundo) dentro do SLO. Como o k6 disputa CPU com a loja no mesmo computador, o número é do conjunto e serve de ordem de grandeza, não de capacidade de produção.

### Por que o checkout não esgotou o pool antes

O pagamento (30 a 120 ms de latência simulada) é chamado **fora** da transação. Se a chamada ao gateway ficasse dentro da transação, cada checkout seguraria uma conexão do banco durante o pagamento inteiro, e o pool de 10 conexões esgotaria com poucas dezenas de checkouts por segundo. A decisão de arquitetura do dia 2 (três transações curtas) aparece aqui como capacidade.

## Load test por minuto (`load.js`)

| hora (UTC) | usuários | req/s | p99 catálogo | p95 checkout | 5xx | conexões do BD | threads do Tomcat | CPU |
|---|---|---|---|---|---|---|---|---|
| 00:42 | 10 | 17 | 9 ms | 142 ms | 0% | 1 | 1 | 1,5% |
| 00:44 | 10 → 50 | 45 | 7 ms | 132 ms | 0% | 1 | 2 | 2,1% |
| 00:46 | 50 | 86 | 5 ms | 130 ms | 0% | 0 | 4 | 2,4% |
| 00:48 | 50 → 100 | 169 | 5 ms | 131 ms | 0% | 0 | 5 | 4,0% |
| 00:49 | 100 | 170 | 5 ms | 130 ms | 0% | 3 | 3 | 3,8% |

O p95 do checkout fica estável em ~130 ms porque é dominado pela latência simulada do gateway de pagamento (30 a 120 ms); o catálogo responde em poucos milissegundos. As colunas de conexões e threads são valores máximos amostrados a cada 15 s.
