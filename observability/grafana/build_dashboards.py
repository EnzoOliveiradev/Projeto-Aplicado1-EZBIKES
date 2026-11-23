#!/usr/bin/env python3
"""Gera os dashboards do Grafana da EZBikes (observability/grafana/dashboards/*.json).

Os dashboards são código: este script é a fonte e os JSON gerados são versionados.
Depois de alterar algo aqui, rode:

    python observability/grafana/build_dashboards.py

Convenções visuais:
  - números principais (SLI, budget, burn rate) em painéis "stat"; tendências em séries no tempo;
  - nunca dois eixos Y no mesmo gráfico: medidas de escalas diferentes ficam em painéis separados;
  - cores categóricas em ordem fixa (CAT); cores de status (STATUS) só para estados
    (bom/atenção/grave/crítico), sempre acompanhadas do nome no título ou na legenda.
"""
import json
from pathlib import Path

OUT = Path(__file__).parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}

# Paleta categórica (tema escuro do Grafana), em ordem fixa.
CAT = ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#0ca30c", "#9085e9", "#e66767"]
# Paleta de status: reservada para estados.
GOOD, WARNING, SERIOUS, CRITICAL = "#0ca30c", "#fab219", "#ec835a", "#d03b3b"

APP = 'job="ezbikes-api"'
NOT_ACTUATOR = 'uri!~"/actuator.*"'


class Layout:
    """Posiciona painéis numa grade de 24 colunas, linha a linha."""

    def __init__(self):
        self.y = 0
        self.x = 0
        self.row_h = 0
        self.next_id = 1

    def place(self, w, h):
        if self.x + w > 24:
            self.newline()
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def newline(self):
        if self.x:
            self.y += self.row_h
        self.x, self.row_h = 0, 0

    def id(self):
        i = self.next_id
        self.next_id += 1
        return i


def target(expr, legend="", ref="A", instant=False):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref,
            "instant": instant, "range": not instant}


def thresholds(*steps):
    """steps: (cor, valor_inicial) com o primeiro valor None (base)."""
    return {"mode": "absolute", "steps": [{"color": c, "value": v} for c, v in steps]}


def fixed_color(color):
    return {"mode": "fixed", "fixedColor": color}


def override_color(name, color):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": fixed_color(color)}]}


NO_TRAFFIC = [{"type": "special", "options": {"match": "null+nan", "result": {"text": "sem tráfego"}}}]


def row(lay, title):
    lay.newline()
    panel = {"type": "row", "title": title, "id": lay.id(), "collapsed": False, "panels": [],
             "gridPos": {"x": 0, "y": lay.y, "w": 24, "h": 1}}
    lay.y += 1
    return panel


def text(lay, content, w=24, h=3):
    return {"type": "text", "id": lay.id(), "title": "", "gridPos": lay.place(w, h),
            "options": {"mode": "markdown", "content": content}}


def stat(lay, title, expr, unit, desc="", w=6, h=4, th=None, decimals=None, mappings=None,
         color_mode="background", text_mode="value"):
    defaults = {"unit": unit, "mappings": mappings or NO_TRAFFIC,
                "thresholds": th or thresholds(("#3987e5", None)),
                "color": {"mode": "thresholds"}}
    if decimals is not None:
        defaults["decimals"] = decimals
    return {"type": "stat", "id": lay.id(), "title": title, "description": desc,
            "datasource": DS, "gridPos": lay.place(w, h),
            "targets": [target(expr, instant=True)],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": color_mode, "graphMode": "none", "justifyMode": "center",
                        "textMode": text_mode, "orientation": "auto", "showPercentChange": False,
                        "wideLayout": True}}


def timeseries(lay, title, targets, unit, desc="", w=12, h=8, overrides=None, th=None,
               th_style="off", stack=False, bars=False, minv=None, maxv=None, decimals=None,
               colors=True):
    custom = {"drawStyle": "bars" if bars else "line", "lineWidth": 2,
              "fillOpacity": 80 if bars else 0, "showPoints": "never", "pointSize": 8,
              "spanNulls": False, "axisPlacement": "auto", "axisSoftMin": 0,
              "stacking": {"mode": "normal" if stack else "none", "group": "A"},
              "thresholdsStyle": {"mode": th_style}, "barAlignment": 0, "gradientMode": "none"}
    defaults = {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"},
                "thresholds": th or thresholds(("transparent", None))}
    if minv is not None:
        defaults["min"] = minv
    if maxv is not None:
        defaults["max"] = maxv
    if decimals is not None:
        defaults["decimals"] = decimals
    ovs = list(overrides or [])
    if colors:
        # Cor fixa por série na ordem declarada: a cor segue a série, não a posição.
        named = {o["matcher"]["options"] for o in ovs}
        for i, t in enumerate(targets):
            if t["legendFormat"] and "{{" not in t["legendFormat"] and t["legendFormat"] not in named:
                ovs.append(override_color(t["legendFormat"], CAT[i % len(CAT)]))
    return {"type": "timeseries", "id": lay.id(), "title": title, "description": desc,
            "datasource": DS, "gridPos": lay.place(w, h), "targets": targets,
            "fieldConfig": {"defaults": defaults, "overrides": ovs},
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def bargauge(lay, title, expr, legend, unit, desc="", w=12, h=8, th=None, color=None, instant=True):
    defaults = {"unit": unit, "min": 0,
                "thresholds": th or thresholds((color or CAT[0], None)),
                "color": {"mode": "thresholds"}}
    return {"type": "bargauge", "id": lay.id(), "title": title, "description": desc,
            "datasource": DS, "gridPos": lay.place(w, h),
            "targets": [target(expr, legend, instant=instant)],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"orientation": "horizontal", "displayMode": "basic", "showUnfilled": True,
                        "valueMode": "text", "namePlacement": "left", "sizing": "auto",
                        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}}}


def alertlist(lay, title, w=24, h=6):
    return {"type": "alertlist", "id": lay.id(), "title": title, "gridPos": lay.place(w, h),
            "options": {"viewMode": "list", "groupMode": "default", "maxItems": 20, "sortOrder": 1,
                        "dashboardAlerts": False, "alertName": "", "alertInstanceLabelFilter": "",
                        "folder": None,
                        "stateFilter": {"firing": True, "pending": True, "noData": False,
                                        "normal": True, "error": True},
                        "showInactiveAlerts": True}}


def dashboard(uid, title, description, panels, templating=None, time_from="now-30m"):
    return {
        "uid": uid, "title": title, "description": description, "tags": ["ezbikes"],
        "timezone": "browser", "editable": False, "graphTooltip": 1, "refresh": "10s",
        "schemaVersion": 39, "version": 1, "time": {"from": time_from, "to": "now"},
        "links": [{"type": "dashboards", "tags": ["ezbikes"], "asDropdown": False,
                   "title": "EZBikes", "includeVars": False, "keepTime": True}],
        "templating": {"list": templating or []}, "annotations": {"list": []},
        "panels": panels,
    }


# --------------------------------------------------------------------------- Golden Signals
def golden_signals():
    lay, p = Layout(), []
    p.append(stat(lay, "Tráfego (req/s)", f"sum(rate(http_server_requests_seconds_count{{{APP},{NOT_ACTUATOR}}}[1m]))",
                  "reqps", "Requisições por segundo na API da loja (sem health check e scraping).", decimals=2,
                  color_mode="none"))
    p.append(stat(lay, "Erros 5xx (%)", "100 * ezbikes:http_server_errors:ratio_rate5m", "percent",
                  "Proporção de respostas 5xx nos últimos 5 minutos.", decimals=2,
                  th=thresholds((GOOD, None), (WARNING, 0.1), (CRITICAL, 1))))
    p.append(stat(lay, "Latência p99 (geral)",
                  f"histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{NOT_ACTUATOR}}}[5m])))",
                  "s", "Percentil 99 de todas as rotas nos últimos 5 minutos.",
                  th=thresholds((GOOD, None), (WARNING, 0.3), (CRITICAL, 0.5))))
    p.append(stat(lay, "CPU do processo", "100 * max(process_cpu_usage{job=\"ezbikes-api\"})", "percent",
                  "Uso de CPU da JVM da API.", decimals=1,
                  th=thresholds((GOOD, None), (WARNING, 70), (CRITICAL, 90))))

    p.append(row(lay, "Latência"))
    p.append(timeseries(lay, "Latência geral: p50, p95 e p99", [
        target(f"histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{NOT_ACTUATOR}}}[$__rate_interval])))", "p50"),
        target(f"histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{NOT_ACTUATOR}}}[$__rate_interval])))", "p95", "B"),
        target(f"histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{NOT_ACTUATOR}}}[$__rate_interval])))", "p99", "C"),
    ], "s", "Percentis calculados no servidor a partir do histograma. Médias escondem os clientes lentos.",
        th=thresholds(("transparent", None), (CRITICAL, 0.3)), th_style="dashed"))
    p.append(timeseries(lay, "p99 por rota", [
        target("ezbikes:http_latency_seconds:p99_5m", "{{uri}}"),
    ], "s", "Recording rule ezbikes:http_latency_seconds:p99_5m. A linha tracejada marca 300 ms (SLO do catálogo).",
        th=thresholds(("transparent", None), (CRITICAL, 0.3)), th_style="dashed"))

    p.append(row(lay, "Tráfego"))
    p.append(timeseries(lay, "Requisições por segundo, por rota", [
        target("ezbikes:http_requests:rate1m", "{{method}} {{uri}}"),
    ], "reqps", "Recording rule ezbikes:http_requests:rate1m.", w=24))

    p.append(row(lay, "Erros"))
    p.append(timeseries(lay, "Taxa de erros 5xx (%)", [
        target("100 * ezbikes:http_server_errors:ratio_rate5m", "5xx"),
    ], "percent", "Falhas do serviço. São as que consomem o error budget.",
        overrides=[override_color("5xx", CRITICAL)], decimals=2))
    p.append(timeseries(lay, "Respostas de erro por classe (req/s)", [
        target(f'sum(rate(http_server_requests_seconds_count{{{APP},{NOT_ACTUATOR},outcome="CLIENT_ERROR"}}[$__rate_interval]))', "4xx (cliente / negócio)"),
        target(f'sum(rate(http_server_requests_seconds_count{{{APP},{NOT_ACTUATOR},outcome="SERVER_ERROR"}}[$__rate_interval]))', "5xx (serviço)", "B"),
    ], "reqps", "4xx (dados inválidos, estoque insuficiente) não consomem o error budget; 5xx consomem."))

    p.append(row(lay, "Saturação"))
    p.append(timeseries(lay, "CPU do processo (%)", [
        target('100 * process_cpu_usage{job="ezbikes-api"}', "CPU"),
    ], "percent", w=6, maxv=100))
    p.append(timeseries(lay, "Heap da JVM (% do máximo)", [
        target('100 * sum(jvm_memory_used_bytes{job="ezbikes-api",area="heap"}) / sum(jvm_memory_max_bytes{job="ezbikes-api",area="heap"} > 0)', "heap"),
    ], "percent", w=6, maxv=100))
    p.append(timeseries(lay, "Pool de conexões do banco (% em uso)", [
        target('100 * sum(hikaricp_connections_active{job="ezbikes-api"}) / sum(hikaricp_connections_max{job="ezbikes-api"})', "conexões em uso"),
    ], "percent", "HikariCP: conexões ativas sobre o máximo (10). A latência sobe antes de o pool esgotar.",
        w=6, maxv=100, th=thresholds(("transparent", None), (CRITICAL, 80)), th_style="dashed"))
    p.append(timeseries(lay, "Threads do Tomcat (% ocupadas)", [
        target('100 * sum(tomcat_threads_busy_threads{job="ezbikes-api"}) / sum(tomcat_threads_config_max_threads{job="ezbikes-api"})', "threads ocupadas"),
    ], "percent", w=6, maxv=100))

    return dashboard("ezbikes-golden-signals", "EZBikes · Golden Signals",
                     "Latência, tráfego, erros e saturação da API da loja.", p)


# --------------------------------------------------------------------------- SLO & Error Budget
def slo():
    lay, p = Layout(), []
    janela = {"type": "custom", "name": "janela", "label": "Janela do SLO",
              "query": "30d,1d,6h,1h", "current": {"text": "30d", "value": "30d"},
              "options": [{"text": v, "value": v, "selected": v == "30d"} for v in ["30d", "1d", "6h", "1h"]],
              "description": "30d é a janela oficial. 1h serve para a demonstração ao vivo."}

    p.append(text(lay, (
        "**Error budget = 100% − SLO.** Burn rate = taxa de erro observada ÷ taxa permitida "
        "(1x esgota o budget em exatamente 30 dias; 14,4x consome 2% dele em 1 hora). "
        "**Política:** budget acima de 50% → deploys liberados · entre 20% e 50% → só correções · "
        "abaixo de 20% → congelar deploys e priorizar confiabilidade. "
        "A janela oficial é 30 dias; use a variável *Janela do SLO* = 1h para ver o consumo ao vivo."), h=3))

    budget_th = thresholds((CRITICAL, None), (WARNING, 20), (GOOD, 50))
    burn_th = thresholds((GOOD, None), (WARNING, 1), (SERIOUS, 6), (CRITICAL, 14.4))

    # Checkout
    p.append(row(lay, "Disponibilidade do checkout · SLO 99,9% (POST /api/orders sem 5xx)"))
    p.append(stat(lay, "SLI disponibilidade ($janela)", "100 * (1 - ezbikes:checkout_error_ratio:rate$janela)", "percent",
                  "Proporção de checkouts sem erro 5xx na janela selecionada. Verde quando cumpre o SLO.",
                  w=5, decimals=3, th=thresholds((CRITICAL, None), (GOOD, 99.9))))
    p.append(stat(lay, "Error budget restante ($janela)", "100 * (1 - ezbikes:checkout_error_ratio:rate$janela / 0.001)",
                  "percent", "100% = nenhum erro; 0% = budget esgotado; negativo = SLO violado.",
                  w=5, decimals=1, th=budget_th))
    burn_stat_1h = stat(lay, "Burn rate · 1h", "ezbikes:checkout_burn_rate:rate1h", "x",
                        "Crítico acima de 14,4x (consome 2% do budget por hora).", w=4, decimals=1, th=burn_th)
    p.append(burn_stat_1h)
    p.append(stat(lay, "Burn rate · 6h", "ezbikes:checkout_burn_rate:rate6h", "x",
                  "Alerta acima de 6x (consome 5% do budget em 6 horas).", w=4, decimals=1, th=burn_th))
    p.append(stat(lay, "Horas até esgotar o budget", (
        "clamp_max(clamp_min(1 - ezbikes:checkout_error_ratio:rate30d / 0.001, 0) * 720"
        " / ezbikes:checkout_burn_rate:rate1h, 8760)"), "h",
        "Budget restante (30d) dividido pelo ritmo de consumo da última hora.", w=6, decimals=1,
        th=thresholds((CRITICAL, None), (WARNING, 24), (GOOD, 168)),
        mappings=NO_TRAFFIC + [{"type": "range", "options": {"from": 8760, "to": 1e15,
                                                               "result": {"text": "sem consumo"}}}]))
    checkout_burn = timeseries(lay, "Burn rate do checkout (janelas de 5m, 1h e 6h)", [
        target("ezbikes:checkout_burn_rate:rate5m", "5m"),
        target("ezbikes:checkout_burn_rate:rate1h", "1h", "B"),
        target("ezbikes:checkout_burn_rate:rate6h", "6h", "C"),
    ], "x", "O alerta crítico exige 1h E 5m acima de 14,4x: a janela longa evita alarme por pico curto "
            "e a curta faz o alerta se resolver logo que o problema acaba. Linhas tracejadas: 6x e 14,4x.",
        th=thresholds(("transparent", None), (SERIOUS, 6), (CRITICAL, 14.4)), th_style="dashed")
    p.append(checkout_burn)
    p.append(timeseries(lay, "Error budget restante ($janela)", [
        target("100 * (1 - ezbikes:checkout_error_ratio:rate$janela / 0.001)", "budget restante"),
    ], "percent", "Linhas tracejadas: 50% e 20%, limites da política de error budget.",
        th=thresholds(("transparent", None), (WARNING, 20), (GOOD, 50)), th_style="dashed", decimals=1))

    # Catálogo
    p.append(row(lay, "Latência do catálogo · SLO 99% das requisições em até 300 ms (GET /api/products*)"))
    p.append(stat(lay, "SLI latência ($janela)", "100 * ezbikes:catalog_latency_good_ratio:rate$janela", "percent",
                  "Proporção de requisições ao catálogo respondidas em até 300 ms.",
                  w=5, decimals=2, th=thresholds((CRITICAL, None), (GOOD, 99))))
    p.append(stat(lay, "Error budget restante ($janela)",
                  "100 * (1 - (1 - ezbikes:catalog_latency_good_ratio:rate$janela) / 0.01)", "percent",
                  w=5, decimals=1, th=budget_th))
    p.append(stat(lay, "Burn rate · 1h", "ezbikes:catalog_latency_burn_rate:rate1h", "x", w=4, decimals=1, th=burn_th))
    p.append(stat(lay, "Burn rate · 6h", "ezbikes:catalog_latency_burn_rate:rate6h", "x", w=4, decimals=1, th=burn_th))
    p.append(stat(lay, "p99 do catálogo (5m)", 'max(ezbikes:http_latency_seconds:p99_5m{uri=~"/api/products.*"})', "s",
                  "Alerta se passar de 500 ms por 5 minutos.", w=6,
                  th=thresholds((GOOD, None), (WARNING, 0.3), (CRITICAL, 0.5))))
    p.append(timeseries(lay, "Burn rate do catálogo (janelas de 5m, 1h e 6h)", [
        target("ezbikes:catalog_latency_burn_rate:rate5m", "5m"),
        target("ezbikes:catalog_latency_burn_rate:rate1h", "1h", "B"),
        target("ezbikes:catalog_latency_burn_rate:rate6h", "6h", "C"),
    ], "x", th=thresholds(("transparent", None), (SERIOUS, 6), (CRITICAL, 14.4)), th_style="dashed"))
    p.append(timeseries(lay, "SLI de latência do catálogo (%)", [
        target("100 * ezbikes:catalog_latency_good_ratio:rate5m", "requisições em até 300 ms (5m)"),
    ], "percent", "Linha tracejada: 99%, o objetivo.", th=thresholds(("transparent", None), (CRITICAL, 99)),
        th_style="dashed", decimals=2, maxv=100))

    p.append(row(lay, "Alertas"))
    p.append(alertlist(lay, "Alertas de SLO (Grafana Alerting)"))

    return dashboard("ezbikes-slo", "EZBikes · SLO e Error Budget",
                     "SLIs, error budget e burn rate dos SLOs de disponibilidade do checkout e latência do catálogo.",
                     p, templating=[janela], time_from="now-1h"), checkout_burn["id"]


# --------------------------------------------------------------------------- Negócio
def business():
    lay, p = Layout(), []
    p.append(stat(lay, "Pedidos pagos (1h)", 'sum(increase(ezbikes_orders_total{status="paid"}[1h]))', "short",
                  decimals=0, color_mode="none"))
    p.append(stat(lay, "Receita (1h)", "sum(increase(ezbikes_order_value_reais_sum[1h]))", "currencyBRL",
                  decimals=2, color_mode="none"))
    p.append(stat(lay, "Ticket médio (1h)",
                  "sum(increase(ezbikes_order_value_reais_sum[1h])) / sum(increase(ezbikes_order_value_reais_count[1h]))",
                  "currencyBRL", decimals=2, color_mode="none"))
    p.append(stat(lay, "Aprovação do checkout (1h)", (
        '100 * sum(increase(ezbikes_orders_total{status="paid"}[1h]))'
        ' / sum(increase(ezbikes_orders_total{status=~"paid|payment_failed|out_of_stock"}[1h]))'), "percent",
        "Pedidos pagos sobre os que chegaram ao checkout (pagos, falha no pagamento e sem estoque).",
        decimals=1, th=thresholds((CRITICAL, None), (WARNING, 95), (GOOD, 99))))

    p.append(row(lay, "Pedidos"))
    p.append(timeseries(lay, "Pedidos por minuto, por resultado", [
        target('sum(rate(ezbikes_orders_total{status="paid"}[$__rate_interval])) * 60', "pago"),
        target('sum(rate(ezbikes_orders_total{status="payment_failed"}[$__rate_interval])) * 60', "falha no pagamento", "B"),
        target('sum(rate(ezbikes_orders_total{status="out_of_stock"}[$__rate_interval])) * 60', "sem estoque", "C"),
        target('sum(rate(ezbikes_orders_total{status=~"rejected|error"}[$__rate_interval])) * 60', "rejeitado / erro", "D"),
    ], "short", "Resultado de cada tentativa de checkout. Cores de status: verde = pago, vermelho = falha.",
        overrides=[override_color("pago", GOOD), override_color("falha no pagamento", CRITICAL),
                   override_color("sem estoque", WARNING), override_color("rejeitado / erro", SERIOUS)],
        stack=True, bars=True, colors=False))
    p.append(timeseries(lay, "Receita por minuto (R$)", [
        target("sum(rate(ezbikes_order_value_reais_sum[$__rate_interval])) * 60", "receita"),
    ], "currencyBRL"))

    p.append(row(lay, "Checkout e pagamento"))
    p.append(timeseries(lay, "Duração do checkout: p95 por resultado", [
        target('histogram_quantile(0.95, sum by (le, outcome) (rate(ezbikes_checkout_duration_seconds_bucket[$__rate_interval])))',
               "{{outcome}}"),
    ], "s", "Reserva de estoque + pagamento + conclusão."))
    p.append(timeseries(lay, "Gateway de pagamento: latência p95", [
        target('histogram_quantile(0.95, sum by (le) (rate(ezbikes_payment_gateway_duration_seconds_bucket[$__rate_interval])))',
               "latência p95"),
    ], "s", "Latência da dependência externa. As falhas por motivo estão no painel ao lado.", w=6))
    p.append(timeseries(lay, "Falhas do gateway por minuto", [
        target("sum by (reason) (rate(ezbikes_payment_gateway_failures_total[$__rate_interval])) * 60", "{{reason}}"),
    ], "short", w=6, overrides=[{"matcher": {"id": "byRegexp", "options": ".*"},
                                 "properties": [{"id": "color", "value": fixed_color(CRITICAL)}]}], colors=False))

    p.append(row(lay, "Catálogo e estoque"))
    p.append(bargauge(lay, "Unidades adicionadas ao carrinho por categoria (1h)",
                      "sum by (category) (increase(ezbikes_cart_items_added_total[1h]))", "{{category}}", "short"))
    p.append(bargauge(lay, "Produtos com menor estoque",
                      "bottomk(8, ezbikes_product_stock)", "{{sku}}", "short",
                      "Vermelho: abaixo de 3 unidades (evento stock.low nos logs). Amarelo: abaixo de 10.",
                      th=thresholds((CRITICAL, None), (WARNING, 3), (GOOD, 10))))

    return dashboard("ezbikes-business", "EZBikes · Negócio",
                     "Pedidos, receita, ticket médio, pagamento e estoque.", p, time_from="now-1h")


def main():
    OUT.mkdir(exist_ok=True)
    slo_dash, burn_panel_id = slo()
    for name, dash in [("golden-signals", golden_signals()), ("slo-error-budget", slo_dash),
                       ("negocio", business())]:
        path = OUT / f"{name}.json"
        path.write_text(json.dumps(dash, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{path} ({len(dash['panels'])} painéis)")
    print(f"Painel de burn rate do checkout no dashboard de SLO: id {burn_panel_id}")


if __name__ == "__main__":
    main()
