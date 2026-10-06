# Checkout load and correctness harness

Application-owned scenarios for issue #123. Cluster execution, fixture allocation,
resource preflight and evidence collection belong to configuration issue #46;
fault/event injection belongs to #47. No workload, cluster or provider mutation is
performed by CI. Executing the harness **creates real test orders and consumes seats**.

## Execution contract

Run `python3 load-tests/run.py` (k6 on PATH), `just load-checkout`, or use
`eurotransitregistry.azurecr.io/eurotransit/load-tests:<full application SHA>`.
Container default entrypoint runs the same wrapper; do not override it with `k6 run`.
The wrapper accepts an optional absolute script filename.

Supply `PUBLIC_URL`, `TOKEN_URL`, `CLIENT_ID`, `CLIENT_SECRET` via an existing secret,
unique safe `RUN_ID`, `FIXTURES_FILE`, and writable `OUTPUT_DIR` (default `/results`).
`SCENARIO` is `baseline` (default), `ramp`, `spike`, `contention` or `request-replay`.
`MAX_ATTEMPTS` defaults to 20 (hard bound 1–10000); `MAX_VUS` defaults to 2 (hard cap 4);
`POLL_TIMEOUT_SECONDS` defaults to 30 (hard bound 300). Optional test-provider
`PAYMENT_METHOD_TOKEN` defaults to `tok_visa`; this is never exported.
`ALLOW_INSECURE_TLS=true` is only for local certificates; TLS validation is on by default.

Fixtures use `{ "version": 1, "fixtures": [{ "routeId": "rt-to-mi-001",
"offerId": "of-001", "travelDate": "2099-01-01", "seatId": "1A", "unitPrice": 24.90 }] }`.
Fixture currency defaults to EUR; optional `currency` overrides it with an uppercase
ISO code and is checked against durable order/payment/provider evidence.
Choose **actual provisioned future dates**, not the illustrative date above.
Allocate at least MAX_ATTEMPTS distinct route/date/seat tuples except contention,
which intentionally competes for one fixture. Preflight checks current Catalog
price and available seats. Allocation never wraps or deletes business records.
Only one run may own a fixture block: #46 serializes allocation; a run ID is not a lock.

Baseline runs sequentially. Ramp/spike use bounded shared iterations and change
pacing from a low-rate phase to an unpaced bounded-concurrency phase, then a recovery
tail; these are resource-safe concurrency experiments, **not a capacity benchmark**.
Raise from 2 to 4 VUs only after #46's resource gate; do not increase cluster memory.
Catalog is probed throughout each checkout/poll cycle. HTTP timeout is 10s (poll 5s),
all polling has a finite deadline. Tokens refresh before expiry. No client retry loop
amplifies overload. Expected overload is only 429 in ramp/spike; baseline requires
CONFIRMED and fails on FAILED, malformed responses, lookup failure or timeout.
Ramp/spike require at least one confirmed checkout in the low-rate recovery tail;
all429 never passes. `overloadDemonstrated` reports whether a429 occurred; set
`EXPECT_OVERLOAD=true` for an overload experiment, so absence of controlled refusal
fails rather than silently claiming overload handling was demonstrated. Contention requires exactly one CONFIRMED; losers must report SEATS_UNAVAILABLE.
Request replay checks same payload/key gives the original ID and changed payload/key gives409.

## Results and evidence

Stdout events use `ET_RESULT ` followed by JSON with `type` and `runId`.
Events: `run_started`, `order_result`, `assertion_failure`, `run_summary`,
`run_finished`, `runner_finished`. `order_result` carries iteration, fixture,
idempotencyKey, correlationId, optional orderId/status/failureCode,
httpStatus, acceptanceMs, observedConfirmationMs and outcome.
The wrapper always writes `orders.jsonl` and `summary.json`; an incomplete ledger fails.
Missing/partial k6 summary fails. No token, JWT, secret, provider reference or email is emitted.

Acceptance latency and poll-observed time-to-confirmation are distinct metrics.
Polling measures an upper bound; it does **not** replace the server
`orders_checkout_e2e_duration_seconds` SLI, which survives Kafka redelivery.
Use the canonical team SLOs in `docs/operations/slo-observability.md`; a small
successful sample is not evidence of the 30-day SLO. Throughput and expected
refusals remain separate from terminal/technical failures and timeouts.

Run `python3 load-tests/verify.py --ledger <orders.jsonl> --evidence <evidence.json>
--output <verification.json>`. Configuration's read-only collector emits:
`{runId, extraction:"COMPLETE", orders:[], reservations:[], payments:[], inventory:[],
outboxEvents:[], providerEffects:{extraction:"COMPLETE",authorizations:[]}}`.
Rows use projected SQL snake_case names: Orders `id,status,route_id,travel_date,seats`; Inventory reservations
`order_id,reservation_id,status,route_id,travel_date`; seats `route_id,travel_date,seat_id,status,reservation_id`;
Payments `order_id,status,amount,currency,provider_reference_hash`.
Provider audit authorizations require `order_id,status:"SUCCEEDED",amount,currency,provider_reference_hash`.
Hash raw provider references with SHA256 consistently; raw references are accepted only
for trusted local test evidence. Audit evidence must exhaustively enumerate provider
effects for the run, not reconstruct them from Payments rows. If provider audit is
unavailable, export INCOMPLETE and report NOT_VERIFIED, never claim no double-charge.
The verifier fails on unresolved orders, duplicate authorizations/provider effects,
seat ownership conflicts, oversell, charged contention losers, missing or mismatched evidence.

For event replay, #47 reads the **exact original** `order-placed` outbox envelope
from restricted canonical-events.json and republishes it using aggregate_id as key.
Only run-owned accepted order IDs are allowed; preserve eventId, orderId and payload;
never create fake events or delete rows. Capture evidence before and after, prove no
additional successful authorization/reservation, and record consumption/duplicate
counter changes. Replaying a terminal order alone does not prove mid-stage crash safety.

## Admission and overload

Orders uses a nonwaiting local fresh-admission pool before Catalog resolution.
A persisted idempotency replay bypasses it. Under READ_COMMITTED a transaction obtains
fixed advisory lock 697430123, rechecks idempotency, counts outstanding checkout states,
and atomically persists order/idempotency/outbox. No remote call runs under the lock.
The counted states are ACCEPTED, RESERVING, PAYMENT_PENDING, CONFIRMING_INVENTORY,
COMPENSATING; post-sale refunds are excluded. This bounds checkout backlog across replicas.
All fresh admissions serialize even with the backlog cap disabled, fixing simultaneous
first-request key races. The lock is scoped to transaction rollback/commit.

`ORDERS_ADMISSION_MAX_OUTSTANDING=0` disables the backlog cap by default;
`ORDERS_ADMISSION_MAX_CONCURRENT=8` bounds fresh requests per pod;
`ORDERS_ADMISSION_LOCK_TIMEOUT_MS=100` bounds lock waiting (max1000ms); the transaction
has a 2s budget. Refusals are429 CHECKOUT_OVERLOADED with Retry-After1; metrics distinguish
backlog_full, admission_busy and request_pool_full. DB outages remain technical failures.
Enable a deliberately small cap only after all Orders replicas implement the gate;
old ungated replicas defeat the shared cap. No reset counter or permit cleanup is needed.
Use a low cap/slow downstream to demonstrate overload within Azure Student resource limits,
then lower input load and confirm recovery with fresh fixtures. Existing isolated
money-path/read bulkheads and bounded jittered downstream retries remain in place. Kafka pipeline delivery also retries
only three times with exponential jitter before dead-lettering.
Orders DB pool max10, acquisition1s/connection2s; HTTP pools per downstream max20,
waiting20/acquisition1s prevent unbounded connection acquisition queues. The separate
OAuth token-manager transport remains framework-managed and is not claimed here
as independently stress-tested.

`just load-checkout-unit` runs allocator/outcome/verifier tests;
`./gradlew :orders:test --tests '*CheckoutAdmissionIntegrationTest'` proves real
PostgreSQL cross-replica admission, replay, terminal release, lock timeout and cancellation.
