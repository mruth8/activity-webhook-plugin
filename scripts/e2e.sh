#!/usr/bin/env bash
# End-to-end: start the real relay (osrs-companion/relay/server.js) on a free loopback port with a shared secret,
# then run RelayE2ETest, which posts plugin-built events and expects the relay to accept (validate) them.
# Usage: scripts/e2e.sh [path-to-osrs-companion]   (default ../osrs-companion)
set -euo pipefail
cd "$(dirname "$0")/.."
COMP="${1:-../osrs-companion}"
PORT="${PPP_RELAY_PORT:-$(node -e 'const s=require("net").createServer().listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')}"
SECRET="e2e-$RANDOM-$RANDOM"
LOG="$(mktemp)"
PORT="$PORT" PET_SECRET="$SECRET" node "$COMP/relay/server.js" >"$LOG" 2>&1 &
RELAY=$!
trap 'kill $RELAY 2>/dev/null || true; rm -f "$LOG"' EXIT INT TERM
for i in $(seq 50); do curl -fs "http://127.0.0.1:$PORT/widget/" >/dev/null 2>&1 && break; sleep 0.1; done
PPP_RELAY_URL="http://127.0.0.1:$PORT/events" PPP_RELAY_SECRET="$SECRET" ./gradlew test --tests com.pixelpetpals.RelayE2ETest --rerun-tasks -i | grep -E 'relay [0-9]{3} <-|RelayE2ETest|BUILD' || true
echo "--- relay log"; cat "$LOG"
