#!/usr/bin/env python3
"""A SonarQube-shaped endpoint for the quality gate calls, with a compute-engine queue.

    fake_sonar.py <port> <log> [--gate OK|WARN|ERROR] [--stale-gate OK|WARN|ERROR]
                  [--ce-steps N] [--ce never|failed] [--param component|componentKey]
                  [--status 200]

The point of the state machine: until the analysis task has been picked up and finished, the gate endpoint answers
with the *previous* analysis's gate (`--stale-gate`). An atom that reads the gate without waiting therefore gets the
stale answer, and the smoke test can tell the difference by outcome instead of by trusting a log line.

Every request is appended to <log> as one JSON object, including which gate was served, so the test can assert the
order independently of the atom's own logging.
"""
import http.server
import json
import sys
import threading

port, log_path = int(sys.argv[1]), sys.argv[2]
gate = "OK"
stale_gate = None
status = 200
ce_steps = 0
ce_mode = "normal"          # normal | never | failed
param = "component"         # which spelling this "SonarQube version" accepts

args = sys.argv[3:]
while args:
    flag = args.pop(0)
    if flag == "--gate":
        gate = args.pop(0)
    elif flag == "--stale-gate":
        stale_gate = args.pop(0)
    elif flag == "--ce-steps":
        ce_steps = int(args.pop(0))
    elif flag == "--ce":
        ce_mode = args.pop(0)
    elif flag == "--param":
        param = args.pop(0)
    elif flag == "--status":
        status = int(args.pop(0))
    else:
        raise SystemExit(f"unknown flag {flag}")

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

state = {"polls": 0, "finished": False, "current": None, "lock": threading.Lock()}
if stale_gate is None:
    stale_gate = gate


def log(entry):
    entry["seq"] = state.setdefault("seq", 0)
    state["seq"] += 1
    with open(log_path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def ce_answer():
    """Advance the fake queue by one poll and return the /api/ce/component body."""
    with state["lock"]:
        if ce_mode == "never":
            return {"queue": [{"id": "AXq", "status": "PENDING", "componentKey": state.get("key", "")}]}
        if state["polls"] < ce_steps:
            state["polls"] += 1
            return {"queue": [{"id": "AXq", "status": "PENDING", "componentKey": state.get("key", "")}]}
        state["polls"] += 1
        if ce_mode == "failed":
            state["current"] = {"id": "AXq", "status": "FAILED", "errorMessage": "not enough memory"}
        else:
            state["current"] = {"id": "AXq", "status": "SUCCESS"}
        state["finished"] = True
        return ({"queue": [], "current": state["current"]} if state["current"]["status"] == "SUCCESS"
                else {"queue": [], "current": state["current"]})


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, payload):
        body = json.dumps(payload).encode("utf-8") if not isinstance(payload, bytes) else payload
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        authorization = self.headers.get("Authorization")
        path = self.path
        if path.startswith("/api/ce/component"):
            given = path.split("?", 1)[1].split("=", 1)[0] if "?" in path else ""
            if status != 200:
                log({"path": path, "authorization": authorization, "kind": "ce", "served": str(status)})
                self.reply(status, {"errors": [{"msg": "unauthorized"}]})
                return
            if given != param:
                log({"path": path, "authorization": authorization, "kind": "ce", "served": "400-wrong-param"})
                self.reply(400, {"errors": [{"msg": f"The '{given}' parameter is missing"}]})
                return
            with state["lock"]:
                key = path.split("=", 1)[1] if "=" in path else ""
                if key:
                    state["key"] = key
            payload = ce_answer()
            served = (payload.get("current") or {}).get("status") or "QUEUED"
            log({"path": path, "authorization": authorization, "kind": "ce", "served": served})
            self.reply(200, payload)
            return

        if path.startswith("/api/qualitygates/project_status"):
            if status != 200:
                log({"path": path, "authorization": authorization, "kind": "gate", "served": str(status)})
                self.reply(status, {"errors": [{"msg": "unauthorized"}]})
                return
            with state["lock"]:
                fresh = state["finished"]
            served = gate if fresh else stale_gate
            log({"path": path, "authorization": authorization, "kind": "gate",
                 "served": served, "fresh": fresh})
            self.reply(200, {"projectStatus": {"status": served, "conditions": CONDITIONS[served]}})
            return

        log({"path": path, "authorization": authorization, "kind": "other", "served": "404"})
        self.reply(404, {"errors": [{"msg": "no such endpoint"}]})

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
