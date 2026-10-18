#!/usr/bin/env bash
# Sends checkouts through the local gateway at a steady pace and logs one line per request:
#   <UTC time> <HTTP status> <orderId or error code> <seat> <travel date>
#
# Every request books a different seat: seats 1A..6D of one travel date, then the next date, so a
# run does not fail on seat conflicts. Pick a START_DATE no earlier run has used (seats are
# provisioned on rt-to-mi-001 up to 2027-03-16).
#
# Usage: scripts/local-checkout-load.sh COUNT START_DATE [INTERVAL_SECONDS]
#   e.g. scripts/local-checkout-load.sh 40 2026-12-01 0.2 | tee run.log
set -euo pipefail

count="${1:?usage: $0 COUNT START_DATE [INTERVAL_SECONDS]}"
start_date="${2:?usage: $0 COUNT START_DATE [INTERVAL_SECONDS]}"
interval="${3:-0.2}"
base_url="${GATEWAY_URL:-http://localhost}"
route="${ROUTE_ID:-rt-to-mi-001}"
offer="${OFFER_ID:-of-002}"
price="${OFFER_PRICE:-49.90}"

token="$("$(dirname "$0")/local-customer-token.sh")"
seats=(1A 1B 1C 1D 2A 2B 2C 2D 3A 3B 3C 3D 4A 4B 4C 4D 5A 5B 5C 5D 6A 6B 6C 6D)
run="$(date +%s)"

for ((i = 0; i < count; i++)); do
  seat="${seats[i % ${#seats[@]}]}"
  travel_date="$(python3 -c "import datetime,sys; print(datetime.date.fromisoformat(sys.argv[1]) + datetime.timedelta(days=int(sys.argv[2])))" "$start_date" "$((i / ${#seats[@]}))")"
  body="{\"routeId\":\"$route\",\"seats\":[\"$seat\"],\"travelDate\":\"$travel_date\",\"totalAmount\":$price,\"paymentMethodToken\":\"tok_visa\",\"offerId\":\"$offer\"}"
  response="$(curl -s -m 10 -w $'\n%{http_code}' -X POST "$base_url/api/orders" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -H "Idempotency-Key: load-$run-$i" -H "X-Correlation-Id: load-$run-$i" \
    -d "$body" || true)"
  status="$(tail -n1 <<<"$response")"
  payload="$(sed '$d' <<<"$response")"
  outcome="$(jq -r '.orderId // .code // "-"' <<<"$payload" 2>/dev/null || echo "-")"
  echo "$(python3 -c "import datetime; print(datetime.datetime.now(datetime.timezone.utc).strftime('%H:%M:%S.%f')[:-3])") ${status:-000} $outcome $seat $travel_date"
  sleep "$interval"
done
