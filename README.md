# EuroTransit Marketplace

EuroTransit sells train tickets through a React storefront and five Kotlin/Spring WebFlux services. Orders coordinates seat holds, sandbox payments, confirmation, refunds and asynchronous notifications.

[![Microservice architecture](docs/diagrams/microservices/architecture.svg)](https://g05-eurotransit.github.io/EuroTransit-application/microservices/architecture.html)

Start with the [system architecture](docs/design/architecture.md).

## Run locally

With Docker/Compose, JDK 21 and Just installed:

```bash
just compose-build
just compose-up
```

Open `http://localhost`. See [development](docs/development/README.md) for setup and test identities.

## Documentation

- [Design](docs/design/README.md): service contracts, checkout behavior and decisions.
- [Development](docs/development/README.md): local setup, conventions and instrumentation.
- [Orders recovery](docs/operations/orders-recovery-runbook.md): inspect incidents and recover unresolved work.
- [Payments latency postmortem](docs/operations/payments-latency-postmortem.md): why accepted checkouts waited on an operator, and the corrections.
- [Verification](docs/verification/README.md): local test results and fault strategy.
- [Acceptance](docs/capstone-dod.md): project criteria and remaining work.

Cluster deployment, operational procedures and cluster evidence live in [configuration](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/README.md).
