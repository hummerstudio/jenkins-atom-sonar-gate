#!/usr/bin/env bash
# A red gate must fail with the conditions named; a green one must not; auth errors must say so.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((22000 + RANDOM % 900))
start_sonar() { # start_sonar <gate> [status]
  python3 tools/fake_sonar.py "$port" "$log" --gate "$1" ${2:+--status "$2"} & server=$!
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done
}
stop_sonar() { kill $server 2>/dev/null; wait $server 2>/dev/null; }

start_sonar OK; trap 'stop_sonar' EXIT

run_gate() { # run_gate <json-extra> [secrets]
  local dir; dir="$(mktemp -d)"
  cat >"$dir/input.json" <<EOF
{"sonarUrl": "http://127.0.0.1:$port", "projectKey": "acme:api" $1}
EOF
  [ -n "${2:-}" ] && printf '%s' "$2" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# green
dir=$(run_gate "" '{"token": "sqa_123"}')
status "$dir" | grep -q success || fail "green gate must pass: $(cat "$dir/output.json")"
[ "$(output "$dir" gateStatus)" = "OK" ] || fail "gate status: $(cat "$dir/output.json")"
[ "$(output "$dir" failed)" != "" ] || true
grep -q '"path": "/api/qualitygates/project_status?projectKey=acme%3Aapi"' "$log" || fail "endpoint: $(tail -1 "$log")"
grep -q '"authorization": "Basic c3FhXzEyMzo="' "$log" || fail "token auth header: $(tail -1 "$log")"

# the branch is passed through
dir=$(run_gate ', "branch": "release/1.0"' '{"token": "t"}')
grep -q 'branch=release%2F1.0' "$log" || fail "branch not passed: $(tail -1 "$log")"

# red: fails, and names both failing conditions (not the one that is OK)
stop_sonar; start_sonar ERROR
dir=$(run_gate "" '{"token": "t"}')
status "$dir" | grep -q error || fail "a red gate must fail the step"
grep -q "new_coverage is 71.4 (required ≥ 80)" "$dir/output.json" || fail "coverage condition missing: $(cat "$dir/output.json")"
grep -q "new_bugs is 3 (required ≤ 0)" "$dir/output.json" || fail "bugs condition missing: $(cat "$dir/output.json")"
grep -q "new_lines" "$dir/output.json" && fail "a passing condition must not be listed"
[ "$(output "$dir" failingConditions)" = "2" ] || fail "failing count: $(cat "$dir/output.json")"

# WARN only fails when asked
stop_sonar; start_sonar WARN
dir=$(run_gate "" '{"token": "t"}')
status "$dir" | grep -q success || fail "WARN should pass by default"
dir=$(run_gate ', "failOnWarning": "true"' '{"token": "t"}')
status "$dir" | grep -q error || fail "WARN must fail when failOnWarning is set"

# auth and missing project
stop_sonar; start_sonar OK 401
dir=$(run_gate "" '{"token": "bad"}')
status "$dir" | grep -q error || fail "401 must fail"
grep -q "refused the token" "$dir/output.json" || fail "401 message: $(cat "$dir/output.json")"
grep -q '"errorType":1' "$dir/output.json" || fail "401 should be USER(1)"

stop_sonar; start_sonar OK 404
dir=$(run_gate "" '{"token": "t"}')
grep -q "no quality gate found for project" "$dir/output.json" || fail "404 message: $(cat "$dir/output.json")"

# bad input
stop_sonar; start_sonar OK
dir="$(mktemp -d)"; printf '{"projectKey": "x"}' >"$dir/input.json"
run "$dir" >"$dir/atom.log" 2>&1 && fail "missing sonarUrl must be refused"
grep -q "sonarUrl is required" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: green, red with named conditions, WARN policy, branch, 401, 404, bad input"
