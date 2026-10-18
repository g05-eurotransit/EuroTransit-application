# Design

The [architecture](architecture.md) explains runtime responsibilities. Use the references below for the authoritative contract or rationale.

| Question | Reference |
| --- | --- |
| What may a caller send or receive? | [API and event contracts](api-design.md) |
| How do checkout, refund and recovery preserve correctness? | [Lifecycle and invariants](reservation-confirmation.md) |
| Why were these choices made? | [Architecture decisions](architecture-decisions.md) |
| How does the customer or operator UI behave? | [Frontend](frontend.md) |
| How do I authenticate locally? | [Local authentication](local-auth.md) |
| How are services configured? | [Runtime conventions](conventions.md) |
| What do services log, measure and propagate? | [Observability conventions](observability-conventions.md) |

Deployment and trust-boundary wiring are described in the [configuration repository](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md).
