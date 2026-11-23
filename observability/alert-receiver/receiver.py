"""Receptor de webhooks do Grafana Alerting para a demonstração.

Substitui um canal real (Slack, e-mail, PagerDuty): registra cada notificação recebida em
uma linha JSON no stdout, consultável com `docker compose logs alert-receiver`. Serve de
evidência de que o alerta não só mudou de estado, mas foi de fato notificado.
"""
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

MAX_BODY = 1_000_000


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = min(int(self.headers.get("Content-Length", 0)), MAX_BODY)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self.send_response(400)
            self.end_headers()
            return

        for alert in payload.get("alerts", []):
            labels = alert.get("labels", {})
            print(json.dumps({
                "received_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "status": alert.get("status"),
                "alertname": labels.get("alertname"),
                "severity": labels.get("severity"),
                "slo": labels.get("slo"),
                "starts_at": alert.get("startsAt"),
                "ends_at": alert.get("endsAt"),
                "summary": alert.get("annotations", {}).get("summary"),
                "values": alert.get("values"),
            }, ensure_ascii=False), flush=True)

        self.send_response(200)
        self.end_headers()

    def do_GET(self):  # health check
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):  # silencia o log de acesso padrão
        pass


if __name__ == "__main__":
    print("alert-receiver ouvindo em :8000", flush=True)
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
