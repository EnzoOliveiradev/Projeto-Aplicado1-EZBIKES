#!/usr/bin/env python3
"""Gera observability/kibana/saved-objects.ndjson: buscas salvas, visualizações e o dashboard
de logs da EZBikes. O serviço kibana-setup importa o arquivo a cada `docker compose up`.

    python observability/kibana/build_saved_objects.py
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "saved-objects.ndjson"
INDEX_REF = {"name": "kibanaSavedObjectMeta.searchSourceJSON.index", "type": "index-pattern", "id": "app-logs"}


def search_source(query):
    return json.dumps({"query": {"query": query, "language": "kuery"}, "filter": [],
                       "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index"})


def saved_search(oid, title, description, query, columns):
    return {"type": "search", "id": oid, "references": [INDEX_REF],
            "attributes": {"title": title, "description": description, "columns": columns,
                           "sort": [["@timestamp", "desc"]], "grid": {}, "hideChart": False,
                           "kibanaSavedObjectMeta": {"searchSourceJSON": search_source(query)}}}


def visualization(oid, title, description, query, vis_state):
    vis_state = {"title": title, **vis_state}
    return {"type": "visualization", "id": oid, "references": [INDEX_REF],
            "attributes": {"title": title, "description": description, "version": 1, "uiStateJSON": "{}",
                           "visState": json.dumps(vis_state, ensure_ascii=False),
                           "kibanaSavedObjectMeta": {"searchSourceJSON": search_source(query)}}}


COUNT = {"id": "1", "enabled": True, "type": "count", "params": {}, "schema": "metric"}

TABLE_PARAMS = {"perPage": 10, "showPartialRows": False, "showMetricsAtAllLevels": False,
                "showTotal": False, "totalFunc": "sum", "percentageCol": ""}

HISTOGRAM_PARAMS = {
    "type": "histogram", "addTooltip": True, "legendPosition": "right", "times": [], "addTimeMarker": False,
    "grid": {"categoryLines": False},
    "categoryAxes": [{"id": "CategoryAxis-1", "type": "category", "position": "bottom", "show": True,
                      "scale": {"type": "linear"}, "labels": {"show": True, "filter": True, "truncate": 100},
                      "title": {}}],
    "valueAxes": [{"id": "ValueAxis-1", "name": "LeftAxis-1", "type": "value", "position": "left", "show": True,
                   "scale": {"type": "linear", "mode": "normal"},
                   "labels": {"show": True, "rotate": 0, "filter": False, "truncate": 100},
                   "title": {"text": "Eventos"}}],
    "seriesParams": [{"show": True, "type": "histogram", "mode": "stacked", "data": {"label": "Eventos", "id": "1"},
                      "valueAxis": "ValueAxis-1", "drawLinesBetweenPoints": True, "lineWidth": 2, "showCircles": True}],
    "labels": {"show": False},
    "thresholdLine": {"show": False, "value": 10, "width": 1, "style": "full", "color": "#E7664C"},
}

objects = [
    saved_search("ezbikes-busca-erros", "EZBikes · Erros", "Todos os logs de nível ERROR.",
                 'level: "ERROR"', ["level", "event.action", "message", "order_id", "trace_id"]),
    saved_search("ezbikes-busca-falhas-pagamento", "EZBikes · Falhas de pagamento",
                 "Pedidos cancelados porque o gateway de pagamento falhou.",
                 'event.action: "order.payment_failed"', ["order_id", "payment_reason", "order_total", "trace_id"]),
    saved_search("ezbikes-busca-pedidos", "EZBikes · Eventos de pedidos",
                 "Ciclo de vida dos pedidos. Filtre por order_id para ver a história de um pedido.",
                 'event.action: order.*', ["event.action", "order_id", "order_total", "customer_email", "trace_id"]),
    saved_search("ezbikes-busca-lentas", "EZBikes · Requisições lentas (> 300 ms)",
                 "Log de acesso das requisições acima do limite do SLO de latência.",
                 'event.action: "http.access" and http.duration_ms > 300',
                 ["http.method", "http.route", "http.status", "http.duration_ms", "trace_id"]),
    saved_search("ezbikes-busca-caos", "EZBikes · Experimentos de caos",
                 "Quando falhas foram injetadas e desligadas (contexto para os incidentes).",
                 'event.action: chaos.*', ["event.action", "message", "chaos_payment_failure_rate", "chaos_error_rate"]),

    visualization("ezbikes-vis-logs-por-nivel", "Logs por nível", "", "",
                  {"type": "pie", "aggs": [COUNT, {"id": "2", "enabled": True, "type": "terms", "schema": "segment",
                                                    "params": {"field": "level", "size": 5, "order": "desc", "orderBy": "1"}}],
                   "params": {"type": "pie", "addTooltip": True, "legendDisplay": "show", "legendPosition": "right",
                              "isDonut": True, "labels": {"show": True, "values": True, "last_level": True, "truncate": 100}}}),
    visualization("ezbikes-vis-eventos-pedidos", "Eventos de pedidos ao longo do tempo",
                  "Criados, pagos, falhas de pagamento e recusas por estoque.", "event.action: order.*",
                  {"type": "histogram", "params": HISTOGRAM_PARAMS, "aggs": [
                      COUNT,
                      {"id": "2", "enabled": True, "type": "date_histogram", "schema": "segment",
                       "params": {"field": "@timestamp", "interval": "auto", "min_doc_count": 1, "extended_bounds": {}}},
                      {"id": "3", "enabled": True, "type": "terms", "schema": "group",
                       "params": {"field": "event.action", "size": 6, "order": "desc", "orderBy": "1"}}]}),
    visualization("ezbikes-vis-top-erros", "Top 5 mensagens de erro", "", 'level: "ERROR"',
                  {"type": "table", "params": TABLE_PARAMS, "aggs": [
                      COUNT,
                      {"id": "2", "enabled": True, "type": "terms", "schema": "bucket",
                       "params": {"field": "message.keyword", "size": 5, "order": "desc", "orderBy": "1",
                                  "customLabel": "Mensagem"}}]}),
    visualization("ezbikes-vis-duracao-por-rota", "Duração por rota (p95 do log de acesso)",
                  "Calculado a partir de http.duration_ms nos logs; o Prometheus é a fonte oficial de latência.",
                  'event.action: "http.access"',
                  {"type": "table", "params": TABLE_PARAMS, "aggs": [
                      COUNT,
                      {"id": "2", "enabled": True, "type": "percentiles", "schema": "metric",
                       "params": {"field": "http.duration_ms", "percents": [95], "customLabel": "duração em ms"}},
                      {"id": "3", "enabled": True, "type": "terms", "schema": "bucket",
                       "params": {"field": "http.route", "size": 10, "order": "desc", "orderBy": "1",
                                  "customLabel": "Rota"}}]}),
]

# Dashboard: (id do objeto, tipo, x, y, largura, altura)
layout = [
    ("ezbikes-vis-logs-por-nivel", "visualization", 0, 0, 16, 14),
    ("ezbikes-vis-eventos-pedidos", "visualization", 16, 0, 32, 14),
    ("ezbikes-vis-top-erros", "visualization", 0, 14, 24, 13),
    ("ezbikes-vis-duracao-por-rota", "visualization", 24, 14, 24, 13),
    ("ezbikes-busca-falhas-pagamento", "search", 0, 27, 48, 15),
]
panels, references = [], []
for i, (oid, otype, x, y, w, h) in enumerate(layout, start=1):
    panels.append({"version": "8.15.0", "type": otype, "panelIndex": str(i),
                   "gridData": {"x": x, "y": y, "w": w, "h": h, "i": str(i)},
                   "embeddableConfig": {"enhancements": {}}, "panelRefName": f"panel_{i}"})
    references.append({"name": f"{i}:panel_{i}", "type": otype, "id": oid})

objects.append({"type": "dashboard", "id": "ezbikes-logs", "references": references, "attributes": {
    "title": "EZBikes · Logs", "version": 1, "timeRestore": True, "timeFrom": "now-1h", "timeTo": "now",
    "refreshInterval": {"pause": False, "value": 30000},
    "description": "Logs estruturados da loja: níveis, eventos de pedidos, erros, duração por rota e falhas de pagamento.",
    "optionsJSON": json.dumps({"useMargins": True, "syncColors": False, "syncCursor": True,
                               "syncTooltips": False, "hidePanelTitles": False}),
    "panelsJSON": json.dumps(panels),
    "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps({"query": {"query": "", "language": "kuery"}, "filter": []})},
}})

OUT.write_text("".join(json.dumps(o, ensure_ascii=False) + "\n" for o in objects), encoding="utf-8", newline="\n")
print(f"{OUT}: {len(objects)} objetos")
