#!/bin/sh
# Configuração inicial do ELK (idempotente; roda a cada `docker compose up`).
#
#   setup.sh elasticsearch  -> antes do Logstash começar a gravar:
#     1. Política ILM: apaga índices de log com mais de 7 dias (retenção / LGPD / custo).
#     2. Index template para app-logs-*: tipos explícitos dos campos e 0 réplicas (nó único).
#   setup.sh kibana         -> depois que o Kibana sobe:
#     3. Data view "EZBikes - logs", pronta para o Discover.
#     4. Buscas salvas, visualizações e o dashboard "EZBikes · Logs"
#        (observability/kibana/saved-objects.ndjson, gerado por build_saved_objects.py).
set -eu

STEP="${1:-all}"

ES="${ES_URL:-http://elasticsearch:9200}"
KIBANA="${KIBANA_URL:-http://kibana:5601}"

wait_for() {
  name="$1"; url="$2"
  echo "Aguardando $name..."
  i=0
  until curl -fsS "$url" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 180 ]; then echo "$name não respondeu a tempo" >&2; exit 1; fi
    sleep 2
  done
}

setup_elasticsearch() {
wait_for "Elasticsearch" "$ES/_cluster/health?wait_for_status=yellow&timeout=5s"

echo "Criando política ILM app-logs-retention (delete após 7 dias)..."
curl -fsS -X PUT "$ES/_ilm/policy/app-logs-retention" -H 'Content-Type: application/json' -d '{
  "policy": {
    "phases": {
      "hot":    { "actions": {} },
      "delete": { "min_age": "7d", "actions": { "delete": {} } }
    }
  }
}'
echo

echo "Criando index template app-logs..."
curl -fsS -X PUT "$ES/_index_template/app-logs" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["app-logs-*"],
  "priority": 200,
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "index.lifecycle.name": "app-logs-retention"
    },
    "mappings": {
      "dynamic_templates": [
        { "strings_as_keyword": {
            "match_mapping_type": "string",
            "mapping": { "type": "keyword", "ignore_above": 1024 } } }
      ],
      "properties": {
        "@timestamp":     { "type": "date" },
        "level":          { "type": "keyword" },
        "service":        { "type": "keyword" },
        "environment":    { "type": "keyword" },
        "logger":         { "type": "keyword" },
        "thread":         { "type": "keyword" },
        "message":        { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 512 } } },
        "stack_trace":    { "type": "text" },
        "trace_id":       { "type": "keyword" },
        "order_id":       { "type": "keyword" },
        "customer_email": { "type": "keyword" },
        "sku":            { "type": "keyword" },
        "payment_reason": { "type": "keyword" },
        "error_type":     { "type": "keyword" },
        "order_total":    { "type": "scaled_float", "scaling_factor": 100 },
        "items_count":    { "type": "integer" },
        "requested_qty":  { "type": "integer" },
        "available_qty":  { "type": "integer" },
        "stock_remaining":{ "type": "integer" },
        "event": { "properties": { "action": { "type": "keyword" } } },
        "http": { "properties": {
          "method":      { "type": "keyword" },
          "path":        { "type": "keyword" },
          "route":       { "type": "keyword" },
          "status":      { "type": "integer" },
          "duration_ms": { "type": "long" }
        } }
      }
    }
  }
}'
echo
}

setup_kibana() {
wait_for "Kibana" "$KIBANA/api/status"

echo "Criando data view app-logs-* no Kibana..."
curl -fsS -X POST "$KIBANA/api/data_views/data_view" \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' -d '{
  "override": true,
  "data_view": {
    "id": "app-logs",
    "title": "app-logs-*",
    "name": "EZBikes - logs",
    "timeFieldName": "@timestamp"
  }
}' >/dev/null
echo "Data view criada."

curl -fsS -X POST "$KIBANA/api/data_views/default" \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
  -d '{"data_view_id": "app-logs", "force": true}' >/dev/null

echo "Importando buscas salvas, visualizações e dashboard..."
result=$(curl -fsS -X POST "$KIBANA/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' -F "file=@/kibana/saved-objects.ndjson")
echo "$result" | grep -q '"success":true' || { echo "Falha ao importar objetos: $result" >&2; exit 1; }
echo "Objetos importados."
}

case "$STEP" in
  elasticsearch) setup_elasticsearch ;;
  kibana)        setup_kibana ;;
  all)           setup_elasticsearch; setup_kibana ;;
  *) echo "Uso: setup.sh [elasticsearch|kibana|all]" >&2; exit 2 ;;
esac
echo "Setup ($STEP) concluído."
