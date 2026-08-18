# Local Development Environment Setup

This document describes how to start and configure the local infrastructure required to develop and run the EuroTransit applications locally.

## Infrastructure Stack

The `docker-compose.yml` file in the root of the project provides the entire stack needed:
- PostgreSQL (with logical replication enabled)
- Apache Kafka (KRaft mode)
- Kafka Connect / Debezium (for the Transactional Outbox Pattern)
- Traefik API Gateway
- Keycloak (IAM)
- Observability Stack (Prometheus, Tempo, Grafana)

To start the infrastructure:
```bash
docker compose up -d
```

## Configuring the Transactional Outbox Pattern (Debezium)

EuroTransit relies on the Transactional Outbox Pattern to provide a durable outbox / at-least-once delivery without distributed transactions. Locally, we use Debezium running in Kafka Connect to read the `outbox_events` table and forward events to Kafka.

After starting the `docker-compose.yml` stack, Debezium must be explicitly configured to monitor the Inventory service outbox.

### 1. Wait for Debezium to be ready
Ensure that the `debezium` container is running and healthy on port 8083.

### 2. Run the Initialization Script
Execute the provided initialization script from the root of the project:

```bash
./scripts/init-local-debezium.sh
```

### 3. Verify Configuration
The script sends a configuration payload to Debezium's REST API. If successful, you will receive a `201 Created` HTTP response.
Debezium is now listening to `outbox_events` and automatically routing payloads (like `inventory-reserved` or `inventory-failed`) to their respective Kafka topics.
