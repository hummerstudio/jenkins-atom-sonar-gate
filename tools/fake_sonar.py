#!/usr/bin/env python3
"""A SonarQube-shaped endpoint for the quality gate call.

    fake_sonar.py <port> <log> [--gate OK|WARN|ERROR] [--status 200]
"""
import http.server
import json
import sys

port, log_path = int(sys.argv[1]), sys.argv[2]
gate = "OK"
status = 200
args = sys.argv[3:]
while args:
    flag = args.pop(0)
    if flag == "--gate":
        gate = args.pop(0)
    elif flag == "--status":
        status = int(args.pop(0))

CONDITIONS = {
    "OK": [
        {"metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "91.2", "status": "OK"},
    ],
    "WARN": [
        {"metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "79.0", "status": "WARN"},
    ],
    "ERROR": [
        {"metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "71.4", "status": "ERROR"},
        {"metricKey": "new_bugs", "comparator": "GT", "errorThreshold": "0", "actualValue": "3", "status": "ERROR"},
        {"metricKey": "new_lines", "comparator": "GT", "errorThreshold": "1000", "actualValue": "420", "status": "OK"},
    ],
}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "authorization": self.headers.get("Authorization")}) + "\n")
        if status != 200:
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b'{"errors":[{"msg":"unauthorized"}]}')
            return
        body = json.dumps({"projectStatus": {"status": gate, "conditions": CONDITIONS[gate]}}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
