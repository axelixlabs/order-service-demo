#!/usr/bin/env bash
# Load large-scale test data into the Docker Compose PostgreSQL database.
#
# Usage (from repo root):
#   ./scripts/load-test-data.sh
#
# Options are forwarded to load_test_data.py (e.g. --seed 123).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose)
if ! docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
fi

echo "Starting PostgreSQL (if not already running) ..."
"${COMPOSE[@]}" up -d postgres

echo "Waiting for PostgreSQL to become ready ..."
for _ in $(seq 1 60); do
  if "${COMPOSE[@]}" exec -T postgres pg_isready -U orderuser -d orderdb >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! "${COMPOSE[@]}" exec -T postgres pg_isready -U orderuser -d orderdb >/dev/null 2>&1; then
  echo "PostgreSQL did not become ready in time." >&2
  exit 1
fi

# Ensure schema exists via a one-shot app start (Flyway runs V1 migration).
# Skip if tables already present.
TABLE_COUNT="$("${COMPOSE[@]}" exec -T postgres psql -U orderuser -d orderdb -tAc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'customers';" \
  | tr -d '[:space:]')"

if [[ "${TABLE_COUNT:-0}" == "0" ]]; then
  echo "Schema not found — starting app briefly to create tables ..."
  APP_SEED_ENABLED=false "${COMPOSE[@]}" up -d app
  for _ in $(seq 1 120); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  if ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "App did not become healthy; Flyway migrations could not be applied." >&2
    echo "Check app logs: docker compose logs app" >&2
    exit 1
  fi
  echo "Stopping app before bulk load ..."
  "${COMPOSE[@]}" stop app
fi

echo "Installing Python dependencies (if needed) ..."
VENV="$ROOT/scripts/.venv"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
"$VENV/bin/pip" install -q -r scripts/requirements.txt

echo "Loading test data ..."
PGHOST=localhost PGPORT=5432 PGDATABASE=orderdb PGUSER=orderuser PGPASSWORD=orderpass \
  "$VENV/bin/python" scripts/load_test_data.py "$@"

echo ""
echo "Data load complete. Start the stack with: docker compose up -d"
