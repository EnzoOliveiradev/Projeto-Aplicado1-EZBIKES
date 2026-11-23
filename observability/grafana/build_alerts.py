#!/usr/bin/env python3
"""Gera o provisionamento do Grafana Alerting a partir de observability/prometheus/rules/alerts.yml.

Uma única fonte para limiares, labels e textos: os alertas testados com `promtool`
são os mesmos que o Grafana avalia e notifica. Depois de alterar alerts.yml, rode:

    python observability/grafana/build_alerts.py

A saída é JSON (YAML válido), aceito pelo provisionamento do Grafana.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "observability/prometheus/rules/alerts.yml"
OUT = ROOT / "observability/grafana/provisioning/alerting/ezbikes-alerts.yml"
SLO_DASH, BURN_PANEL = "ezbikes-slo", "8"  # painel "Burn rate do checkout" (build_dashboards.py)
MIN_REQUESTS = 10  # mesma guarda de volume das regras em alerts.yml


def zero_if_nan(expr):
    """Sem tráfego a razão vira NaN (0/0). Para o Grafana decidir igual ao Prometheus,
    NaN (ou ausência de série) vira 0: "sem tráfego" nunca é "disparando"."""
    return f"({expr} >= 0) or vector(0)"


def parse_alerts(text):
    """Lê labels e annotations de cada alerta (formato simples usado em alerts.yml)."""
    alerts, current, section = {}, None, None
    for line in text.splitlines():
        if m := re.match(r"\s+- alert: (\S+)", line):
            current = alerts.setdefault(m.group(1), {"labels": {}, "annotations": {}})
            section = None
        elif current is not None and (m := re.match(r"\s{8}(labels|annotations):\s*$", line)):
            section = m.group(1)
        elif current is not None and section and (m := re.match(r"\s{10}(\w+): (.+)$", line)):
            current[section][m.group(1)] = m.group(2).strip()
        elif re.match(r"\s{8}\w", line):
            section = None
    return alerts


def query(ref, expr):
    return {"refId": ref, "relativeTimeRange": {"from": 600, "to": 0}, "datasourceUid": "prometheus",
            "model": {"refId": ref, "expr": zero_if_nan(expr), "instant": True, "range": False,
                      "intervalMs": 1000, "maxDataPoints": 43200}}


def expression(ref, model):
    return {"refId": ref, "datasourceUid": "__expr__",
            "model": {"refId": ref, "datasource": {"type": "__expr__", "uid": "__expr__"}, **model}}


def rule(uid, title, src, data, for_, panel=None):
    annotations = dict(src["annotations"])
    if panel:
        annotations.update({"__dashboardUid__": SLO_DASH, "__panelId__": panel})
    return {"uid": uid, "title": title, "condition": "C", "data": data, "for": for_,
            "noDataState": "OK", "execErrState": "Error", "isPaused": False,
            "labels": src["labels"], "annotations": annotations}


def mwmbr(uid, title, src, long_q, short_q, volume_q, threshold, for_, panel=None):
    """Janela longa E janela curta acima do limiar E volume mínimo na janela curta
    (expressão math: 1 = disparando)."""
    data = [query("A", long_q), query("B", short_q), query("V", volume_q),
            expression("C", {"type": "math",
                             "expression": f"$A > {threshold} && $B > {threshold} && $V >= {MIN_REQUESTS}"})]
    return rule(uid, title, src, data, for_, panel)


def single(uid, title, src, q, threshold, for_):
    data = [query("A", q),
            expression("C", {"type": "threshold", "expression": "A",
                             "conditions": [{"evaluator": {"type": "gt", "params": [threshold]}}]})]
    return rule(uid, title, src, data, for_)


def main():
    a = parse_alerts(SRC.read_text(encoding="utf-8"))
    doc = {
        "apiVersion": 1,
        "contactPoints": [{"orgId": 1, "name": "ezbikes-webhook", "receivers": [{
            "uid": "ezbikes-webhook", "type": "webhook", "disableResolveMessage": False,
            "settings": {"url": "http://alert-receiver:8000/grafana", "httpMethod": "POST"}}]}],
        "policies": [{"orgId": 1, "receiver": "ezbikes-webhook", "group_by": ["alertname", "severity"],
                      "group_wait": "10s", "group_interval": "1m", "repeat_interval": "1h"}],
        "groups": [{"orgId": 1, "name": "ezbikes-slo", "folder": "EZBikes", "interval": "1m", "rules": [
            mwmbr("ezbikes-checkout-burn-critical", "Checkout · burn rate crítico (14,4x em 1h e 5m)",
                  a["CheckoutErrorBudgetBurnCritical"], "ezbikes:checkout_burn_rate:rate1h",
                  "ezbikes:checkout_burn_rate:rate5m", "ezbikes:checkout_requests:rate5m * 300",
                  14.4, "1m", BURN_PANEL),
            mwmbr("ezbikes-checkout-burn-high", "Checkout · burn rate alto (6x em 6h e 30m)",
                  a["CheckoutErrorBudgetBurnHigh"], "ezbikes:checkout_burn_rate:rate6h",
                  "ezbikes:checkout_burn_rate:rate30m", "ezbikes:checkout_requests:rate30m * 1800",
                  6, "5m", BURN_PANEL),
            mwmbr("ezbikes-catalog-burn-critical", "Catálogo · burn rate de latência crítico (14,4x em 1h e 5m)",
                  a["CatalogLatencyBudgetBurnCritical"], "ezbikes:catalog_latency_burn_rate:rate1h",
                  "ezbikes:catalog_latency_burn_rate:rate5m", "ezbikes:catalog_requests:rate5m * 300",
                  14.4, "1m"),
            single("ezbikes-catalog-p99", "Catálogo · p99 acima de 500 ms", a["CatalogLatencyP99High"],
                   'max(ezbikes:http_latency_seconds:p99_5m{uri=~"/api/products.*"})', 0.5, "5m"),
        ]}],
    }
    header = ("# GERADO por observability/grafana/build_alerts.py a partir de\n"
              "# observability/prometheus/rules/alerts.yml (mesmos limiares, labels e textos). Não edite à mão.\n")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(header + json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"{OUT}: {len(doc['groups'][0]['rules'])} regras")
    for name, src in a.items():
        assert src["labels"] and src["annotations"], name


if __name__ == "__main__":
    main()
