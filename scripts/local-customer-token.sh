#!/usr/bin/env bash
set -euo pipefail

base_url="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
realm="${KEYCLOAK_REALM:-eurotransit}"
client_id="${KEYCLOAK_CLIENT_ID:-eurotransit-frontend}"
username="${KEYCLOAK_USERNAME:-alice}"
password="${KEYCLOAK_PASSWORD:-alice}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to request a local customer token." >&2
  exit 127
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to parse the Keycloak token response." >&2
  exit 127
fi

response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

token_url="${base_url%/}/realms/${realm}/protocol/openid-connect/token"

curl_status=0
http_status="$(curl -sS -o "$response_file" -w "%{http_code}" \
  -X POST "$token_url" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=${username}" \
  -d "password=${password}" \
  -d "grant_type=password" \
  -d "client_id=${client_id}")" || curl_status=$?

if (( curl_status != 0 )); then
  echo "Failed to reach Keycloak token endpoint: ${token_url}" >&2
  if [ -s "$response_file" ]; then
    echo "Response body:" >&2
    cat "$response_file" >&2
    echo >&2
  fi
  exit "$curl_status"
fi

if ! [[ "$http_status" =~ ^[0-9]{3}$ ]]; then
  echo "Unexpected HTTP status from Keycloak token endpoint: ${http_status}" >&2
  cat "$response_file" >&2
  echo >&2
  exit 1
fi

if (( http_status < 200 || http_status >= 300 )); then
  echo "Keycloak token request failed with HTTP ${http_status}." >&2
  cat "$response_file" >&2
  echo >&2
  exit 1
fi

if ! token="$(jq -er '.access_token // empty' "$response_file")"; then
  echo "Keycloak token response did not contain access_token." >&2
  cat "$response_file" >&2
  echo >&2
  exit 1
fi

if [ -z "$token" ] || [ "$token" = "null" ]; then
  echo "Keycloak token response contained an empty access_token." >&2
  cat "$response_file" >&2
  echo >&2
  exit 1
fi

printf '%s\n' "$token"
