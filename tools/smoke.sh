#!/usr/bin/env bash
# A red gate must fail with the conditions named; a green one must not; auth errors must say so; and a gate read
# before this build's analysis finished must not be mistaken for the answer.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((22000 + RANDOM % 900))
start_sonar() { # start_sonar <gate> [status] [fake_sonar flags...]
  local gate="$1" status="${2:-}"
  shift 2 2>/dev/null || shift $#
  : >"$log"
  python3 tools/fake_sonar.py "$port" "$log" --gate "$gate" ${status:+--status "$status"} "$@" & server=$!
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done
}
stop_sonar() { kill $server 2>/dev/null; wait $server 2>/dev/null; }

start_sonar OK
trap 'stop_sonar' EXIT

# output() splits on dots, and the reserved build-result key has one, so read it directly
build_result() {
  python3 -c "import json,sys;print(json.load(open(sys.argv[1]+'/output.json'))['data'].get('build.result',{}).get('value',''))" "$1"
}

run_gate() { # run_gate <json-extra> [secrets]
  local dir; dir="$(mktemp -d)"
  cat >"$dir/input.json" <<EOF
{"sonarUrl": "http://127.0.0.1:$port", "projectKey": "acme:api" $1}
EOF
  [ -n "${2:-}" ] && printf '%s' "$2" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# --- the gate itself ---------------------------------------------------------------------------------------------

# green
dir=$(run_gate "" '{"token": "sqa_123"}')
status "$dir" | grep -q success || fail "green gate must pass: $(cat "$dir/output.json")"
[ "$(output "$dir" gateStatus)" = "OK" ] || fail "gate status: $(cat "$dir/output.json")"
grep -q '"path": "/api/qualitygates/project_status?projectKey=acme%3Aapi"' "$log" || fail "endpoint: $(tail -1 "$log")"
grep -q '"authorization": "Basic c3FhXzEyMzo="' "$log" || fail "token auth header: $(tail -1 "$log")"

# the branch is passed through
dir=$(run_gate ', "branch": "release/1.0"' '{"token": "t"}')
grep -q 'branch=release%2F1.0' "$log" || fail "branch not passed: $(tail -1 "$log")"

# red: fails, and names both failing conditions (not the one that is OK)
stop_sonar; start_sonar ERROR
dir=$(run_gate "" '{"token": "t"}')
status "$dir" | grep -q error || fail "a red gate must fail the step"
grep -q "new_coverage is 71.4 (required" "$dir/output.json" || fail "coverage condition missing: $(cat "$dir/output.json")"
grep -q "new_bugs is 3 (required " "$dir/output.json" || fail "bugs condition missing: $(cat "$dir/output.json")"
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

# --- waiting for this build's analysis ----------------------------------------------------------------------------

# The fake answers the gate with the PREVIOUS analysis (ERROR) until the CE task has been polled up to SUCCESS.
# Waiting must therefore be what turns this from a red gate into a green one.
stop_sonar; start_sonar OK "" --stale-gate ERROR --ce-steps 2

dir=$(run_gate ', "waitForAnalysis": "true", "pollIntervalSeconds": "1", "waitTimeoutSeconds": "30"' '{"token": "t"}')
status "$dir" | grep -q success || fail "waiting must reach the fresh gate: $(cat "$dir/output.json")"
[ "$(output "$dir" gateStatus)" = "OK" ] || fail "gate after wait: $(cat "$dir/output.json")"
[ "$(output "$dir" ceTaskStatus)" = "SUCCESS" ] || fail "ce status: $(cat "$dir/output.json")"
python3 - "$log" <<'PY2' || fail "the atom read the gate before the analysis finished"
import json, sys
rows = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
gate_rows = [r for r in rows if r["kind"] == "gate"]
assert gate_rows, "no gate request at all"
assert all(r["fresh"] for r in gate_rows), f"read a stale gate: {gate_rows}"
assert not any(r["kind"] == "gate" and not r["fresh"] for r in rows)
PY2
grep -q "1 analysis task(s) still queued" "$dir/atom.log" || fail "the queue was never observed: $(cat "$dir/atom.log")"

# the same fake, restarted so the analysis is pending again, without the wait: the stale gate is served and
# believed, which is exactly the bug the wait fixes
stop_sonar; start_sonar OK "" --stale-gate ERROR --ce-steps 2
dir=$(run_gate '' '{"token": "t"}')
status "$dir" | grep -q error || fail "without waiting the stale gate must be read (it is ERROR)"
grep -q "new_coverage is 71.4" "$dir/output.json" || fail "stale gate conditions: $(cat "$dir/output.json")"

# an analysis that never finishes
stop_sonar; start_sonar OK "" --ce never
dir=$(run_gate ', "waitForAnalysis": "true", "pollIntervalSeconds": "1", "waitTimeoutSeconds": "5"' '{"token": "t"}')
status "$dir" | grep -q error || fail "a queue that never drains must time out"
grep -q "did not finish within 5s" "$dir/output.json" || fail "timeout message: $(cat "$dir/output.json")"
grep -q '"errorType":2' "$dir/output.json" || fail "timeout should be THIRD_PART(2): $(cat "$dir/output.json")"

# an analysis that failed: say so at once instead of waiting out the timeout
stop_sonar; start_sonar OK "" --ce failed
dir=$(run_gate ', "waitForAnalysis": "true", "pollIntervalSeconds": "1", "waitTimeoutSeconds": "10"' '{"token": "t"}')
status "$dir" | grep -q error || fail "a failed analysis must not produce a gate"
grep -q "ended as FAILED" "$dir/output.json" || fail "failed-analysis message: $(cat "$dir/output.json")"
grep -q '"served": "SUCCESS"' "$log" && fail "a failed analysis must never be reported as SUCCESS"

# SonarQube 8.0 spells the parameter componentKey: the first 400 must switch the spelling, not fail the step
stop_sonar; start_sonar OK "" --param componentKey --ce-steps 1
dir=$(run_gate ', "waitForAnalysis": "true", "pollIntervalSeconds": "1", "waitTimeoutSeconds": "20"' '{"token": "t"}')
status "$dir" | grep -q success || fail "componentKey spelling must be retried: $(cat "$dir/output.json")"
grep -q '"served": "400-wrong-param"' "$log" || fail "the fake never rejected 'component=': $(cat "$log")"
grep -q 'componentKey=acme%3Aapi' "$log" || fail "no retry with componentKey=: $(cat "$log")"

# --- buildStatus=UNSTABLE ------------------------------------------------------------------------------------------

stop_sonar; start_sonar ERROR "" --ce-steps 0
dir=$(run_gate ', "buildStatus": "UNSTABLE"' '{"token": "t"}')
status "$dir" | grep -q success || fail "UNSTABLE must not fail the step: $(cat "$dir/output.json")"
[ "$(build_result "$dir")" = "UNSTABLE" ] || fail "build.result: $(cat "$dir/output.json")"
[ "$(output "$dir" passed)" = "false" ] || fail "passed: $(cat "$dir/output.json")"
[ "$(output "$dir" gateStatus)" = "ERROR" ] || fail "gate status: $(cat "$dir/output.json")"
grep -q "the quality gate is ERROR" "$dir/output.json" || fail "the reason must still be in the message"

# FAILURE stays the default, and an unknown value is refused instead of silently treated as FAILURE
dir=$(run_gate '' '{"token": "t"}')
status "$dir" | grep -q error || fail "FAILURE is the default"
dir=$(run_gate ', "buildStatus": "SOMETIMES"' '{"token": "t"}')
grep -q "buildStatus must be FAILURE or UNSTABLE" "$dir/output.json" || fail "unknown buildStatus: $(cat "$dir/output.json")"

# --- the fake is not just saying yes -------------------------------------------------------------------------------

# Swapping the gate the fake serves must change the atom's verdict: proof the happy path is not a rubber stamp.
stop_sonar; start_sonar OK "" --stale-gate OK
dir=$(run_gate ', "waitForAnalysis": "true", "pollIntervalSeconds": "1", "waitTimeoutSeconds": "10"' '{"token": "t"}')
status "$dir" | grep -q success || fail "green after waiting: $(cat "$dir/output.json")"

echo "smoke ok: gate read, named conditions, WARN policy, branch, 401, 404, bad input, CE wait (queue, timeout, failed,"
echo "          componentKey spelling), stale-gate contrast, UNSTABLE mapping, buildStatus validation"
