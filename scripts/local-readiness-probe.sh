#!/usr/bin/env bash
# Polls a service's readiness from inside the compose network and logs each change:
#   <UTC time> <HTTP status> <body>
# The service images ship neither curl nor wget, so the probe runs in a throwaway curl container.
#
# Usage: scripts/local-readiness-probe.sh [SERVICE_URL] [INTERVAL_SECONDS]
#   default: http://orders:8080/actuator/health/readiness every 0.2s; stop with Ctrl-C.
set -euo pipefail

url="${1:-http://orders:8080/actuator/health/readiness}"
interval="${2:-0.2}"
network="${COMPOSE_NETWORK:-eurotransit-application_default}"

docker run --rm --network "$network" --entrypoint sh curlimages/curl:8.10.1 -c '
  last=""
  while true; do
    line="$(curl -s -m 1 -w " %{http_code}" "$0" 2>&1 || true)"
    if [ "$line" != "$last" ]; then
      echo "$(date -u +%H:%M:%S) $line"
      last="$line"
    fi
    sleep "$1"
  done
' "$url" "$interval"
