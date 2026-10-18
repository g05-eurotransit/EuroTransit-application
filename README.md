# EuroTransit Marketplace

EuroTransit sells train tickets through a React storefront and five Kotlin/Spring WebFlux services. Orders coordinates seat holds, sandbox payments, confirmation, refunds, and asynchronous notifications.

![Microservice architecture](docs/diagrams/microservices/architecture.svg)

## Run locally

Prerequisites: Docker with Compose, JDK 21, and `just`. The Gradle wrapper builds the backend; frontend tooling is declared in [frontend/package.json](frontend/package.json).

```bash
just compose-build
just compose-up
just build
```

`compose-up` starts the stack and registers all three Debezium outbox connectors. Open the storefront at `http://localhost`. Follow [local setup](docs/operations/local-dev-setup.md) for identity, configuration, diagnostics, and shutdown.

## Find the right document

| Need | Start here |
| --- | --- |
| Understand the services | [Architecture](docs/design/architecture.md) |
| Integrate an API or event | [Contracts](docs/design/api-design.md) |
| Understand checkout correctness | [Lifecycle and recovery](docs/design/reservation-confirmation.md) |
| Run or recover an order | [Operations](docs/operations/README.md) |
| Review acceptance and evidence | [Definition of Done](docs/capstone-dod.md) |
| Navigate all documentation | [Documentation](docs/README.md) |

Kubernetes, GitOps, cluster procedures, and operational experiments live in [EuroTransit Configuration](https://github.com/g05-eurotransit/EuroTransit-configuration). Documentation follows the [shared writing guide](docs/templates/README.md).
