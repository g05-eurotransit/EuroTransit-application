# Deployment and Secret Conventions

This document outlines the standard deployment and configuration conventions for all EuroTransit Marketplace services. Adhering to these rules ensures that our GitOps delivery, observability, and local development environments remain consistent and secure.

## 1. Network and Ports

- **Container Port:** Every Spring Boot service must expose its HTTP API on port `8080` internally.
- **Routing:** No service exposes ports directly to the host network in production. All external traffic must route through the API Gateway (Traefik).
- **Inter-Service Calls:** Services communicate with each other using internal Kubernetes DNS names (e.g., `http://inventory:8080`) or Docker Compose service names locally.

## 2. Health Probes (Liveness & Readiness)

We use Spring Boot Actuator for health endpoints.

- **Paths:**
  - Readiness: `/actuator/health/readiness`
  - Liveness: `/actuator/health/liveness`
- **Liveness Rule:** Liveness probes **must only** check if the JVM process is responsive. They **must not** check downstream dependencies (e.g., PostgreSQL or Kafka). Failing a liveness probe causes a pod restart, which can worsen an outage if the root cause is a database overload.
- **Readiness Rule:** Readiness probes check if the service is fully booted and connected to its dependencies. Failing a readiness probe removes the pod from the routing pool without restarting it.

## 3. Environment Variables Naming

Use the following standard environment variable names across all services to maintain consistency:

### Spring Profiles
- `SPRING_PROFILES_ACTIVE`: Use `local` for Docker Compose, `cluster` for Kubernetes.

### Database (PostgreSQL)

Provide the connection as discrete parts, not as a full URL. A service that persists data needs
two connection strings for the same database — the reactive runtime URL (R2DBC) and, where Flyway
manages the schema, a migration URL (JDBC) — and both are composed from these shared parts. The
parts also map 1:1 to the keys of the CloudNativePG-generated app secret (`host`, `port`, `dbname`,
`username`, `password`), so they can be wired straight from `secretKeyRef`.

- `DB_HOST`: database host (in cluster, the CloudNativePG read-write service, e.g. `orders-db-rw`)
- `DB_PORT`: database port (default `5432`)
- `DB_NAME`: logical database name
- `DB_USER`: database username
- `DB_PASSWORD`: database password

The application composes the URLs from these parts, for example:
- R2DBC (runtime): `r2dbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
- Flyway (startup, JDBC): `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`

### Kafka
- `KAFKA_BOOTSTRAP_SERVERS`

### Security (OIDC)
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`

## 4. Secret Management

Secrets must **never** be committed in plaintext to any repository (application or configuration).

- **Local Development:** Use standard environment variables in `docker-compose.yml` or a `.env` file that is ignored by Git.
- **Cluster Deployment:** We use GitOps via ArgoCD. All secrets must be encrypted using **SealedSecrets**.
  - Service Owners (e.g., Payments Slice Owner) are responsible for defining the required secret keys.
  - Operations will encrypt the raw values into a `SealedSecret` manifest, which is safe to commit to the configuration repository.
  - The Payments adapter (Stripe/PayPal Sandbox) must read its API keys via environment variables mounted from these secrets.

## 5. Container Images

- **Immutability:** Docker images published to our registry must be tagged with a unique identifier (e.g., the Git commit SHA).
- **No `latest`:** Do not use the `latest` tag in Kubernetes deployment manifests. This breaks ArgoCD's ability to track actual changes and rollback reliably.
- **Base Images:** Use minimal, secure base images to reduce attack surface and download size.
