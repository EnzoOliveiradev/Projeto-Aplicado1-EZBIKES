# Projeto-Aplicado1-EZBIKES

**EZBikes** é uma loja virtual de bicicletas construída como um sistema **observável**: além de vender, ela produz logs estruturados, métricas de negócio e de desempenho, SLOs com error budget e alertas de burn rate. O projeto foi desenvolvido para o Projeto Aplicado I da pós-graduação (disciplina *Observabilidade, SRE e Engenharia de Confiabilidade de Sistemas*).

> 🚧 Em construção. Esta página será ampliada a cada etapa do cronograma até a entrega.

## Status do cronograma

| Etapa | Situação |
|---|---|
| Esqueleto Spring Boot, Docker Compose, PostgreSQL, Flyway e catálogo inicial | ✅ |
| Carrinho, pedidos, estoque transacional e tratamento de erros | ✅ |
| Logs JSON + ELK (Elasticsearch, Logstash, Kibana) | ✅ |
| Métricas (Actuator, Micrometer, Prometheus) e recording rules | ⏳ |
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
| Health check | http://localhost:8080/actuator/health |
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

## Testes

```bash
cd api
./mvnw verify
```

Os testes de integração usam **Testcontainers** com PostgreSQL 16 real. Eles verificam o formato dos logs (JSON válido, `trace_id` em todos os logs da requisição, `order_id` nos eventos, status numérico e nenhum e-mail em texto claro) e cobrem: pedido pago e baixa de estoque, rollback quando falta estoque, devolução do estoque quando o pagamento falha, checkout pelo carrinho, mapeamento dos status HTTP e **20 compras concorrentes** de um produto com 5 unidades (exatamente 5 são vendidas).

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
docker-compose.yml          Orquestração local
```
