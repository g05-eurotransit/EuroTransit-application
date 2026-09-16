# Agent Skill Recommendations

These draft skills are reviewable team assets. They are intentionally stored in the application repo instead of a local `~/.codex/skills` directory so teammates can discuss them in PRs before installing or copying them into their own Codex setup.

## Drafted Now

- `eurotransit-cross-repo-docs`: use for documentation work that may affect both `EuroTransit-application` and `EuroTransit-configuration`.
- `kotlin-coroutines-webflux-pipeline`: use for Kotlin/Spring WebFlux async service flows, especially the order, inventory, payment, and Kafka money path.

## Recommended Next

- `eurotransit-gitops-k8s`: GitOps, Argo CD, Kustomize/Helm, Kubernetes manifests, probes, PDBs, secrets, progressive delivery, and config evidence.
- `eurotransit-resilience-observability-chaos`: SLOs, OpenTelemetry, Prometheus/Grafana, alerting, circuit breakers, bulkheads, retries, load shedding, and Chaos Mesh evidence.
- `eurotransit-security-payments`: OAuth/OIDC, Keycloak production assumptions, service-to-service auth, secret handling, Stripe/PayPal sandbox payments, PCI-sensitive boundaries, and audit events.
- `eurotransit-react-frontend`: React customer flow, authentication integration, catalog search, checkout, payment handoff, order status, error states, and API contract alignment.

Do not duplicate requirements across skills. Each skill should point agents toward the canonical docs and help them avoid recurring mistakes.
