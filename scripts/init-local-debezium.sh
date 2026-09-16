#!/bin/bash

# ==============================================================================
# EuroTransit - Local Debezium Initialization Script
# ==============================================================================
# Registers the PostgreSQL Debezium connectors in the local Kafka Connect instance
# running via docker-compose, enabling the Outbox Pattern for Inventory and Orders.
#
# Requirements:
# - docker compose up -d (postgres, kafka, and debezium must be running)
#
# `just compose-up` runs this for you. It is idempotent — it uses PUT on the connector
# config rather than POST, so re-running it updates an existing connector instead of
# failing with 409.
#
# KEEP IN SYNC with the cluster connectors in the EuroTransit-configuration repository
# (platform/connectors/base/{orders,inventory}-outbox-connect.yaml). A local stack that
# routes the outbox differently from production is not a rehearsal for production, and
# the divergence does not announce itself: it surfaces as an order stuck in ACCEPTED.
# ==============================================================================

set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"

echo "Waiting for Debezium to be ready on ${CONNECT_URL}..."
while ! curl -s "${CONNECT_URL}/" > /dev/null; do
  sleep 2
  echo -n "."
done
echo -e "\nDebezium is up!"

# Debezium answering on 8083 is NOT enough, and getting this wrong fails in a way that looks
# like a different bug. `publication.autocreate.mode: filtered` makes the connector create a
# publication FOR the tables in `table.include.list`, so `outbox_events` must already exist —
# and on a fresh volume that table is created by Flyway when orders and inventory start, which
# is later. Registering too early leaves the task FAILED with "Unable to create filtered
# publication ... for" and an empty table list, no event ever reaches Kafka, and the symptom is
# an order stuck in ACCEPTED with no parse error anywhere.
wait_for_outbox() {
  local db="$1" user="$2" i
  echo -n "Waiting for ${db}.outbox_events to exist"
  for i in $(seq 1 90); do
    if docker compose exec -T postgres psql -U "${user}" -d "${db}" -tAc \
         "SELECT to_regclass('public.outbox_events')" 2>/dev/null | grep -q outbox_events; then
      echo " — ready"
      return 0
    fi
    sleep 2
    echo -n "."
  done
  echo " — TIMEOUT. Is the service running and has Flyway finished?" >&2
  return 1
}

wait_for_outbox "orders_db" "orders"
wait_for_outbox "inventory_db" "inventory"

# Registers one connector and fails loudly on anything other than 200/201. Without the
# status check a 4xx would scroll past and the script would still claim success.
register() {
  local name="$1" config="$2" status
  echo "Registering '${name}'..."
  status=$(curl -s -o /tmp/connect-response.json -w '%{http_code}' \
    -X PUT -H "Accept:application/json" -H "Content-Type:application/json" \
    "${CONNECT_URL}/connectors/${name}/config" -d "${config}")
  if [[ "${status}" != "200" && "${status}" != "201" ]]; then
    echo "  FAILED (HTTP ${status})" >&2
    cat /tmp/connect-response.json >&2
    echo >&2
    return 1
  fi
  echo "  OK (HTTP ${status})"
}

# `expand.json.payload` is what makes the payload usable, and leaving it out is the
# failure this script was fixed for. The outbox column is JSONB, so the EventRouter
# extracts it as a *string*; without expansion JsonConverter serializes that string
# again and the topic carries "{\"eventId\": ...}" — a JSON string, not an object.
# The consumers then fail to deserialize and the event dead-letters.
read -r -d '' INVENTORY_CONFIG <<'JSON' || true
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "tasks.max": "1",
  "database.hostname": "postgres",
  "database.port": "5432",
  "database.user": "inventory",
  "database.password": "password",
  "database.dbname": "inventory_db",
  "topic.prefix": "inventory",
  "plugin.name": "pgoutput",
  "slot.name": "inventory_outbox",
  "publication.name": "inventory_outbox_publication",
  "publication.autocreate.mode": "filtered",
  "table.include.list": "public.outbox_events",

  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.table.expand.json.payload": "true",
  "transforms.outbox.route.topic.replacement": "${routedByValue}",
  "transforms.outbox.route.by.field": "type"
}
JSON

# Orders has its own database (orders_db) so it needs a separate connector with distinct
# replication slot/publication. The EventRouter routes each outbox row to the topic named by its
# `type` column: order-placed / order-confirmed / notification-requested.
read -r -d '' ORDERS_CONFIG <<'JSON' || true
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "tasks.max": "1",
  "database.hostname": "postgres",
  "database.port": "5432",
  "database.user": "orders",
  "database.password": "password",
  "database.dbname": "orders_db",
  "topic.prefix": "orders",
  "plugin.name": "pgoutput",
  "slot.name": "orders_outbox",
  "publication.name": "orders_outbox_publication",
  "publication.autocreate.mode": "filtered",
  "table.include.list": "public.outbox_events",

  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.table.expand.json.payload": "true",
  "transforms.outbox.route.topic.replacement": "${routedByValue}",
  "transforms.outbox.route.by.field": "type"
}
JSON

register "inventory-outbox-connector" "${INVENTORY_CONFIG}"
register "orders-outbox-connector" "${ORDERS_CONFIG}"

# A connector whose task died on an earlier run stays FAILED even after a config update, so a
# re-run would report success over a dead task. Restarting is a no-op when the task is healthy.
for name in inventory-outbox-connector orders-outbox-connector; do
  curl -s -o /dev/null -X POST "${CONNECT_URL}/connectors/${name}/restart?includeTasks=true&onlyFailed=true"
done

echo "Connectors registered. Debezium is listening to the inventory_db and orders_db outbox_events tables."
