#!/usr/bin/env bash
# End-to-end: start the real relay (osrs-companion/relay/server.js) on a free loopback port with a random shared
# secret, verify it is up and that it is OUR relay process answering on that port, then run RelayE2ETest, which posts
# plugin-built events and expects the relay to accept (validate) them. Exits nonzero on any failure.
# Usage: [PPP_RELAY_PORT=8799] scripts/e2e.sh [path-to-osrs-companion]   (default ../osrs-companion)
# Self-test: PPP_E2E_KILL_RELAY=1 stops the relay before the test runs; the script must then fail.
set -euo pipefail
cd "$(dirname "$0")/.."
COMP="${1:-../osrs-companion}"
[ -f "$COMP/relay/server.js" ] || { echo "e2e: no relay at $COMP/relay/server.js" >&2; exit 2; }
# PPP_RELAY_PORT pins the port (still verified to be held by our relay pid); otherwise a random free port.
if [ -n "${PPP_RELAY_PORT:-}" ]; then
  [[ "$PPP_RELAY_PORT" =~ ^[0-9]+$ ]] && [ "$PPP_RELAY_PORT" -ge 1 ] && [ "$PPP_RELAY_PORT" -le 65535 ] \
    || { echo "e2e: bad PPP_RELAY_PORT=$PPP_RELAY_PORT" >&2; exit 2; }
  PORT="$PPP_RELAY_PORT"
else
  PORT="$(node -e 'const s=require("net").createServer().listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
fi
SECRET="e2e-$(node -e 'console.log(require("crypto").randomBytes(8).toString("hex"))')"
LOG="$(mktemp)"; OUT="$(mktemp)"
PORT="$PORT" PET_SECRET="$SECRET" node "$COMP/relay/server.js" >"$LOG" 2>&1 &
RELAY=$!
cleanup() { local rc=$?; set +e; kill "$RELAY" 2>/dev/null; wait "$RELAY" 2>/dev/null; rm -f "$LOG" "$OUT"; exit "$rc"; }
trap cleanup EXIT
trap 'exit 130' INT TERM

# Health check: our pid alive, it announced this port, and the port answers like the relay
# (GET /widget/ 200, and POST /events without the secret -> 401, which only a relay with OUR secret set returns).
up=""
for _ in $(seq 100); do
  kill -0 "$RELAY" 2>/dev/null || { echo "e2e: relay exited early:" >&2; cat "$LOG" >&2; exit 1; }
  if grep -q "relay on http://127.0.0.1:$PORT " "$LOG" && curl -fs -o /dev/null "http://127.0.0.1:$PORT/widget/"; then up=1; break; fi
  sleep 0.1
done
[ -n "$up" ] || { echo "e2e: relay not up on 127.0.0.1:$PORT" >&2; cat "$LOG" >&2; exit 1; }
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:$PORT/events")"
[ "$code" = 401 ] || { echo "e2e: port $PORT answered $code to an unauthenticated POST, expected 401 from our relay" >&2; exit 1; }
if command -v ss >/dev/null; then
  ss -ltnpH "sport = :$PORT" 2>/dev/null | grep -q "pid=$RELAY," || { echo "e2e: port $PORT is not held by our relay pid $RELAY" >&2; exit 1; }
fi
echo "e2e: relay pid $RELAY up on 127.0.0.1:$PORT"

if [ "${PPP_E2E_KILL_RELAY:-}" = 1 ]; then
  kill "$RELAY"; set +e; wait "$RELAY" 2>/dev/null; set -e
  echo "e2e: (self-test) relay stopped before the test"
fi

set +e
PPP_RELAY_URL="http://127.0.0.1:$PORT/events" PPP_RELAY_SECRET="$SECRET" \
  ./gradlew test --tests com.activitywebhook.RelayE2ETest --rerun-tasks -i >"$OUT" 2>&1
rc=$?
set -e
sed -n -E '/relay [0-9]{3} <-|RelayE2ETest >|BUILD/p' "$OUT"
if [ "$rc" -ne 0 ]; then echo "e2e: FAIL (gradle exit $rc)" >&2; sed -n -E '/Exception|expected|rejected/{p;}' "$OUT" | sed -n 1,5p >&2; exit "$rc"; fi
XML=build/test-results/test/TEST-com.activitywebhook.RelayE2ETest.xml
grep -q 'tests="1" skipped="0" failures="0" errors="0"' "$XML" || { echo "e2e: FAIL (RelayE2ETest did not run and pass, e.g. skipped): $(sed -n 2p "$XML")" >&2; exit 1; }
n="$(grep -c 'relay 204 <-' "$OUT")"
echo "--- relay log"; cat "$LOG"
echo "e2e: PASS ($n events accepted)"
