#!/bin/bash

# ==============================================================================
# EuroTransit - Local Debezium Initialization Script
# ==============================================================================
# This script registers the PostgreSQL Debezium connectors in the local Kafka Connect
# instance running via docker-compose. It enables the Outbox Pattern for the
# Inventory and Orders services.
#
# Requirements:
# - docker compose up -d (postgres, kafka, and debezium must be running)
# ==============================================================================

echo "Waiting for Debezium to be ready on localhost:8083..."
while ! curl -s http://localhost:8083/ > /dev/null; do
  sleep 2
  echo -n "."
done
echo -e "\nDebezium is up!"

echo "Registering 'inventory-outbox-connector'..."

curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors -d '{
  "name": "inventory-outbox-connector",
  "config": {
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
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.route.by.field": "type"
  }
}'

echo -e "\n\nRegistering 'orders-outbox-connector'..."

# Orders has its own database (orders_db) so it needs a separate connector with distinct
# replication slot/publication. The EventRouter routes each outbox row to the topic named by its
# `type` column: order-placed / order-confirmed / notification-requested.
curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors -d '{
  "name": "orders-outbox-connector",
  "config": {
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
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.route.by.field": "type"
  }
}'

echo -e "\n\nConnectors registered successfully! Debezium is now listening to the inventory_db and orders_db outbox_events tables."
