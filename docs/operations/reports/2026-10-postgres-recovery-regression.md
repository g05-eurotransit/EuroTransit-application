# PostgreSQL recovery regression — 2026-10-17

## Diagnosis and scope

Baseline: application `origin/dev` at `e008cba`, including merged recovery-policy PR #139 and
expired-`PAYMENT_PENDING` safety PR #141. The October 12 AKS experiment ran older Orders `bad4c04`.
Its accepted order `63afb29d-70e4-4ed1-bf85-b35d33b5278e` remained `RESERVING` with a
`DELIVERY_EXHAUSTED` incident until the original event was replayed. PostgreSQL promotion took about
3.5 seconds and the order GET recovered in about 5.1 seconds. Retained artifacts and the recorded
run observation establish the state, the `dependency_error` classification and replay convergence;
they do not retain the application's concrete nested exception. Consequently an exact historical exception classification cannot be
reconstructed. Operational evidence belongs to the configuration repository:
[October 12 report](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/392690487ca60f25532f6823506cc95f77f3f38e/docs/reliability/postgres-failover-2026-10-12.md).

The current policy already gives recoverable database failures six redeliveries with exponential
backoff and jitter, instead of the former three fixed-delay retries. `waitingCanFix` includes Spring
transient/recoverable/resource failures and R2DBC transient/resource failures, while rejected SQL and
other permanent failures retain the short budget. No production policy change is proposed here.

## Regression method

`CheckoutPipelineIntegrationTest` now injects six real PostgreSQL statement failures with SQLSTATE
`08006` when Orders persists `PAYMENT_PENDING` after Inventory has returned its hold. A PostgreSQL
sequence survives statement rollback, making the fault end deterministically. This exercises the
PostgreSQL driver, Spring exception translation, the actual Kafka listener and its error handler.
The retry listener captures six `NonTransientDataAccessResourceException` failures and verifies the
nested R2DBC SQLSTATE `08006`; these receive the current dependency budget.
Only one outbox event is relayed; the test never manually invokes the pipeline or republishes it.

The assertions cover the persisted `RESERVING` state during failure, automatic convergence to
`CONFIRMED`, seven hold calls with the same `inv-{orderId}` key and one logical hold, one authorization
with `pay-{orderId}`, no compensation, no open incident or exhausted-delivery increment, and one
confirmation/notification outbox event pair. Inventory and Payments remain in-process doubles;
Inventory now remembers the original hold and expiry by idempotency key. Their downstream database
and provider uniqueness guarantees are outside this test.

The integration suite uses its existing accelerated dependency waits (50–200 ms, ten retries).
`CheckoutRetryBudgetTest` separately pins the production defaults: six waits, approximately 48–67
seconds total. An injected SQLSTATE is a statement failure; it does not reproduce a broken physical
connection, stale pool entries, DNS/service routing, Debezium reconnect or CloudNativePG promotion.

## Local validation

Initial focused checks passed: `CheckoutRetryBudgetTest` 7/7, `DeadLetterRetryTest` 4/4 and
`CheckoutOrchestratorTest` 62/62. The full checkout integration suite passed 53/53, including the new
regression. After adding the retry exception assertions, its targeted rerun also passed:

```sh
./gradlew :orders:test --tests '*CheckoutPipelineIntegrationTest.a PostgreSQL resource failure*' --offline --no-daemon
```

**Negative control:** temporarily removing only the handler's dependency-backoff selection restored
the former fixed three-retry budget. Running that exact targeted command failed as expected with a
30-second terminal-state timeout (one test, one failure). The real DLT consumer logged
`kind=DELIVERY_EXHAUSTED failureClass=dependency_error state=RESERVING`. This was a behavioral
regression result with working PostgreSQL/Kafka infrastructure, not an environment failure. The
production source was restored automatically in `finally`; it is absent from the final diff.

Final validation after restoring the current policy passed **126/126 tests**, with no failures,
errors or skips (7 retry-policy, 4 DLT-policy, 62 orchestrator and 53 checkout integration tests):

```sh
./gradlew :orders:test \
  --tests 'it.polito.cpo.config.CheckoutRetryBudgetTest' \
  --tests 'it.polito.cpo.config.DeadLetterRetryTest' \
  --tests 'it.polito.cpo.service.CheckoutOrchestratorTest' \
  --tests 'it.polito.cpo.integration.CheckoutPipelineIntegrationTest' \
  --offline --no-daemon
```

Tests use Docker Testcontainers PostgreSQL 16 and embedded Kafka, not the existing compose stack.
The initial sandbox could not access Docker or write Gradle cache locks; authorized local escalation
resolved both. No deployment, cluster fault, remote push or manual event replay was performed.

## Next AKS acceptance test

After deploying a reviewed image containing #139/#141, record its immutable image digest and Git
revision, then repeat a controlled primary failover with a small isolated fixture and bounded load.
Retain full Orders exception chains, Kafka delivery/DLT evidence, accepted order IDs, promotion and
GET recovery times, connector recovery, and before/after cross-service database/provider audits.
Success requires every accepted checkout to reach a correct terminal outcome without event replay
or redrive, no exhausted-delivery incident for the bounded outage, and one logical hold/payment per
order. Preserve expiry/refusal/unknown-payment safety and account for unrelated historical incidents.
This remains a separate operational gate; local regression success does not close it.
