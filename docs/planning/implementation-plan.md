# EuroTransit Marketplace Work Plan

This plan turns the assignment requirements into implementation work. The target is an enterprise-grade EuroTransit Marketplace implementation ready by **2026-07-14**. It assumes the two-repository GitOps model required by the assignment:

- Application repository: service source code, tests, CI workflows, and application-facing docs.
- Configuration repository: Helm/manifests, ArgoCD applications, platform bootstrap, SLO/alert definitions, dashboards, sealed secrets, and chaos experiment manifests.

For the detailed five-person parallel execution model with AI coding agents, see [team-agentic-implementation-plan.md](team-agentic-implementation-plan.md). The team model uses vertical slice ownership: every person owns application code plus the related Kubernetes/GitOps, test, observability, and proof work for their slice.

## Resolved Decisions

- **Framework:** Kotlin with Spring Boot WebFlux.
- **Application layout:** Gradle multi-module monorepo with five services.
- **Frontend:** A minimal React application is required for the customer journey: catalog browsing, checkout submission, and order status display.
- **Local development:** Docker Compose is used for local infrastructure such as Postgres, Kafka, and Traefik. Application services may run from the IDE or be added later as built service images.
- **Cluster delivery:** GitHub Actions builds immutable images and updates the configuration repository. ArgoCD reconciles the cluster. CI must not hold cluster credentials.
- **Inventory consistency:** Strong CP / PC-EC model using PostgreSQL atomic reservation updates or an equivalent reservation state machine.
- **Database ownership:** Orders owns the required PostgreSQL order-state database managed through CloudNativePG. Inventory owns seat availability/reservation state needed for the no-oversell invariant.
- **Money path:** Hybrid sync+async. Orders accepts checkout quickly, then runs a Kafka-visible pipeline while using synchronous idempotent decisions for Inventory and Payments.
- **Payment provider:** Payments integrates with a provider sandbox/test API such as Stripe test mode or PayPal Sandbox through a provider adapter. A pure internal mock is not sufficient for the final implementation.
- **Progressive delivery:** Orders uses canary. Catalog uses blue/green.
- **Authentication boundary:** Traefik enforces OIDC/JWT authentication for protected external APIs before traffic reaches services.
- **Service authorization:** Services perform local authorization using propagated principal, scopes, order ownership, and service credentials for internal calls.
- **Identity provider:** The cluster/proof target uses Keycloak as the OIDC provider. Local development can start with a mock OIDC issuer or fixed development JWTs, but API contracts must match the production security model.
- **No production mocks:** Final cluster/demo proof must not depend on mocked services. Development mocks are allowed only as temporary local substitutes while slices are being integrated.
- **Notifications degradation:** Notifications is fully asynchronous. Checkout succeeds when reservation and payment succeed, even if Notifications is down.

## Decision Summary

The selected decisions are documented in [architecture-decisions.md](../design/architecture-decisions.md). The alternatives are retained there because the team must be able to defend why they were rejected.

- Strong CP inventory is preferred over eventual reservation because the domain invariant "never oversell a seat" is more important than accepting every order during a partition.
- Hybrid sync+async checkout is preferred over fully async checkout because Inventory and Payments require immediate, idempotent decisions, while Kafka still carries the durable workflow and notification stages.
- Orders canary plus Catalog blue/green is preferred because Orders is the critical path that needs metric-gated rollout, while Catalog is stateless/read-heavy and safer to switch as a whole.

## Enterprise-Grade Readiness Timeline

### Week 1: Foundation, GitOps, and Walking Skeleton

- Scaffold the five Spring Boot WebFlux services and their health endpoints.
- Add local Docker Compose for infrastructure and document which services are intentionally not containerized there yet.
- Add CI that builds/tests all modules and publishes immutable service images.
- Create the configuration repository with ArgoCD applications and base Helm/manifests.
- Install or reference the shared platform components: Traefik, cert-manager, CloudNativePG, Strimzi, SealedSecrets, ArgoCD, kube-prometheus-stack, and Chaos Mesh.

### Week 2: Money Path and Async Execution

- Implement Catalog read endpoints and Orders checkout entry API.
- Implement a minimal React frontend that uses the gateway APIs rather than bypassing services.
- Implement Orders pipeline with Kotlin coroutines/flows and Kafka-visible events.
- Implement Orders order-state persistence on PostgreSQL managed through CloudNativePG.
- Add gateway authentication and service-level authorization for customer and operations flows.
- Define principal propagation for synchronous calls and Kafka events without leaking secrets or payment data.
- Implement Inventory reservation and final confirmation using the selected strong consistency model, including atomic `HELD -> CONFIRMED` / `HELD -> SOLD` transitions serialized against cleanup and release.
- Implement Payments authorization with strict idempotency and sandbox provider integration.
- Implement Orders `CONFIRMING_INVENTORY` and `COMPENSATING` resume states plus DLT-backed reconciliation and idempotent outbox redrive.
- Implement Notifications as an asynchronous event consumer.
- Add graceful shutdown behavior: readiness refusal, coroutine cancellation, and in-flight draining.

### Week 3: Resilience and Observability

- Add idempotency keys and deduplication tables/records across Orders, Inventory, and Payments.
- Add authorization checks for order ownership and operational/admin access.
- Add Resilience4j circuit breakers, bulkheads, timeouts, and bounded retries with jitter.
- Add Kubernetes startup, readiness, and liveness probes; liveness must not depend on downstream services.
- Define checkout SLOs, SLIs, burn-rate alerts, dashboards, and traces as described in [slo-observability.md](../operations/slo-observability.md).
- Add trace propagation across gateway, Orders, Inventory, Payments, and Kafka stages.

### Week 4: Progressive Delivery, Chaos, and Presentation Proof

- Complete security acceptance checks: unauthenticated requests rejected, unauthorized order reads denied, internal-only APIs inaccessible from outside, and secrets absent from logs/config.
- Implement Orders canary with TraefikService and SLO-gated promotion/abort.
- Implement Catalog blue/green with fast rollback.
- Run and document the chaos experiments in [chaos-experiments.md](../operations/chaos-experiments.md).
- Complete [agent-governance.md](../governance/agent-governance.md), [agent-log.md](../agent-log.md), and a blameless postmortem using [postmortem-template.md](../templates/postmortem-template.md).
- Record the 5-minute demo showing the React customer flow, running system, dashboards, canary, blue/green, one injected failure, and one alert firing.

## Verification Plan

- The DoD in [capstone-dod.md](../capstone-dod.md) is the release gate.
- Every chaos experiment must include steady state, hypothesis, injected failure, observed dashboards/traces/logs, conclusion, and follow-up change.
- The final demo must prove at least one live incident from injection to alert to diagnosis to recovery.
