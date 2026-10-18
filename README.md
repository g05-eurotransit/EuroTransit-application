# EuroTransit Marketplace

EuroTransit sells train tickets through a React storefront and five Kotlin/Spring WebFlux services. Orders coordinates seat holds, sandbox payments, confirmation, refunds and asynchronous notifications.

![Microservice architecture](docs/diagrams/microservices/architecture.svg)

[Interactive diagrams](https://g05-eurotransit.github.io/EuroTransit-application/) are published from `main` through GitHub Pages.

## Run locally

With Docker/Compose, JDK 21 and Just installed:

```bash
just compose-build
just compose-up
```

Open `http://localhost`. [Local development](docs/development/local.md) covers test identities, tokens, outbox diagnostics and shutdown. [Contribution conventions](docs/development/contributing.md) and [instrumentation](docs/development/instrumentation.md) describe service configuration and telemetry.

## Design and contracts

| Question | Reference |
| --- | --- |
| How do services interact? | [Architecture](docs/design/architecture.md) |
| What APIs, identities and events are supported? | [API contracts](docs/design/api-design.md) |
| How do holds, payment, refunds and recovery remain safe? | [Checkout lifecycle](docs/design/reservation-confirmation.md) |
| Why were these approaches chosen? | [Architecture decisions](docs/design/architecture-decisions.md) |
| What does the browser do? | [Frontend](docs/design/frontend.md) |
| How do shutdown and Kafka replay work? | [Runtime lifecycle](docs/design/runtime-lifecycle.md) |
| What are the checkout objectives and SLIs? | [SLOs](docs/design/slo.md) |

## Operations and verification

Use [Orders recovery](docs/operations/orders-recovery-runbook.md) to inspect incidents, redrive or settle unresolved work. [Local verification](docs/verification/README.md) records tested behavior; [fault strategy](docs/verification/strategy.md) defines hypotheses. Kubernetes deployment, GitOps and cluster evidence belong to [configuration](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/README.md).

## Acceptance and collaboration

[Definition of Done](docs/capstone-dod.md) records acceptance and remaining work against the [assignment](docs/assignment-requirements.pdf). [Agent governance](docs/governance/agent-governance.md) owns permissions/review; [agent log](docs/agent-log.md) preserves concrete mistakes and corrections. Use the shared [documentation guide](docs/templates/README.md) for both repositories.
