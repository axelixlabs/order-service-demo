#!/usr/bin/env bash
# Run the 5-minute staged order-flow k6 test against a running order-service instance.
#
# Usage (from repo root):
#   ./load-tests/run-order-flow.sh
#
# Environment:
#   BASE_URL=http://localhost:8080   API base URL
#   CUSTOMER_COUNT=100000            customers from bulk load (ignored when SEED_MODE=true)
#   SEED_MODE=true                   use demo seed customer (id 1) instead of bulk mapping
#
# Prerequisites: k6 installed, app running, test data loaded.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/load-tests"

if ! curl -sf "${BASE_URL:-http://localhost:8080}/actuator/health" >/dev/null 2>&1; then
  echo "order-service is not reachable at ${BASE_URL:-http://localhost:8080}" >&2
  echo "Start the stack first: docker compose up -d" >&2
  exit 1
fi

exec k6 run order-flow.ts "$@"
