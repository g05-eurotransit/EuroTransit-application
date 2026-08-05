# Agent Mistake Log

This log is intentionally required by the assignment. It records concrete cases where an agent-generated artifact was wrong, unsafe, or incomplete, and how the team corrected it.

## Case 1: Liveness Probe Checked Downstream Dependency

- **Agent output:** A liveness probe failed when Kafka or Postgres was unavailable.
- **Why it was wrong:** Liveness should indicate whether the process is stuck, not whether a dependency is transiently down. This could cause restart loops during dependency incidents.
- **How the team caught it:** Review against the resilience requirements and probe design checklist.
- **Correction:** Move dependency checks to readiness or custom health indicators. Keep liveness local to process health.

## Case 2: Cause-Based CPU Alert Instead of User Symptom Alert

- **Agent output:** An alert fired on high CPU usage for Orders.
- **Why it was wrong:** The assignment requires symptom-based alerts tied to SLOs. CPU alone does not prove user-visible failure.
- **How the team caught it:** SLO review against `operations/slo-observability.md`.
- **Correction:** Replace with checkout success-rate burn and checkout latency alerts. Keep CPU on dashboards for diagnosis.

## Case 3: Non-Idempotent Kafka Consumer

- **Agent output:** A consumer applied reservation/payment side effects every time an event was delivered.
- **Why it was wrong:** Kafka delivery may duplicate messages. The money path must not double-reserve or double-charge.
- **How the team caught it:** Chaos experiment design for duplicate events and pod kill mid-reservation.
- **Correction:** Add idempotency records keyed by order/reservation/payment attempt and return the original result for duplicate deliveries.

## Case 4: Over-Permissive Delivery ServiceAccount

- **Agent output:** A generated ServiceAccount had broad namespace write privileges.
- **Why it was wrong:** The agent is inside the delivery loop and must have limited blast radius.
- **How the team caught it:** RBAC review in `governance/agent-governance.md`.
- **Correction:** Scope permissions to the minimum resources required and require human review before config repo merge.

## Case 5: Wrong Package for WebClientCustomizer (Spring Boot 4)

- **Date:** 2026-07-06
- **Owner:** Person 5 (Customer Experience + Observability)
- **Prompt/task:** Implement the shared `:observability` module, including a `WebClientCustomizer` that propagates `X-Correlation-Id`/`X-Service-Name` on outgoing calls.
- **Agent output:** Imported `org.springframework.boot.web.reactive.function.client.WebClientCustomizer`, the Spring Boot 3 location, without checking the actual Boot version in use.
- **Why it was wrong:** The project runs Spring Boot 4.0.7, which moved the class into a separate `spring-boot-webclient` artifact not pulled in transitively by `spring-boot-starter-webflux`. The build failed with "Unresolved reference".
- **How the team detected it:** `./gradlew :observability:compileKotlin` failed immediately; grepped the Gradle module cache for the class to find its real Boot 4 location and artifact.
- **Correction:** Added `implementation("org.springframework.boot:spring-boot-webclient")` to `observability/build.gradle.kts` and fixed the import.

## Case 6: Test Resource Shadowed Main Configuration

- **Date:** 2026-07-06
- **Owner:** Person 5
- **Prompt/task:** Add a `src/test/resources/application.yaml` override to disable tracing export noise during Catalog tests.
- **Agent output:** Created `catalog/src/test/resources/application.yaml` as a full config file.
- **Why it was wrong:** Gradle puts test resources ahead of main resources on the test classpath, so this file completely replaced (not merged with) `src/main/resources/application.yaml` — `spring.application.name` and `server.port` disappeared, and Spring failed to resolve `${spring.application.name}` in a metrics tag, throwing `PlaceholderResolutionException` during context startup.
- **How the team detected it:** `./gradlew :catalog:test` failed to load the Spring context; stack trace pointed at placeholder resolution, not at the test logic.
- **Correction:** Renamed the file to `application-test.yaml` and activated it explicitly via `@ActiveProfiles("test")`, so it layers on top of the main config instead of replacing it. Documented the pitfall in `docs/design/observability-conventions.md` so other owners don't repeat it.

## Case 7: Missing @AutoConfigureMetrics in Prometheus Endpoint Test

- **Date:** 2026-07-06
- **Owner:** Person 5
- **Prompt/task:** Write a Catalog test asserting `/actuator/prometheus` exposes `http_server_requests` after an API call.
- **Agent output:** A plain `@SpringBootTest` calling `/actuator/prometheus` and expecting 200.
- **Why it was wrong:** Spring Boot 4 test support disables metrics export by default in test contexts (`DisableMetricsExportContextCustomizer`), so the endpoint returned 404 even though the code was correct — a silent test gap that would have passed review as "prometheus endpoint exists" while never actually exercising it.
- **How the team detected it:** Ran the test, got `404 NOT_FOUND` instead of the expected assertion failure; had to trace it to the Boot 4 test auto-configuration rather than application code.
- **Correction:** Added `@AutoConfigureMetrics` (and `@AutoConfigureWebTestClient` for the constructor-injected `WebTestClient`, which needed the same explicit opt-in) to the test class.

## Case 8: Overlapping Night Route Produced Negative Travel Time

- **Date:** 2026-07-06
- **Owner:** Person 5
- **Prompt/task:** Seed the in-memory Catalog repository with example routes, including an overnight sleeper train.
- **Agent output:** A `Route.travelTime` computed as `Duration.between(departureTime, arrivalTime)` using only `LocalTime` (no date), which is negative whenever arrival is earlier in the clock than departure (e.g. a 19:20 departure arriving 08:42 the next day).
- **Why it was wrong:** Not caught by any test (no test asserted travel time), and would have shipped a negative duration to the frontend for every overnight route — a subtly wrong artifact of exactly the kind this log exists to catch.
- **How the team detected it:** Manual read-through of the seed data while reviewing the diff before running tests, not by a failing assertion — flagging that travel-time correctness has no test coverage yet.
- **Correction:** Added `.plusDays(1)` when the computed duration is negative. Follow-up: add a unit test for `Route.travelTime` on overnight routes before Catalog is considered done.

## Case 9: Missing Versioned Database Migrations Strategy

- **Owner:** Person 2 (Orders Checkout)
- **Agent output:** A custom `R2dbcConfiguration` that ran a plain, unversioned `schema.sql` using Spring's `ConnectionFactoryInitializer`.
- **Why it was wrong:** A single idempotent `schema.sql` cannot handle ordered, incremental schema evolutions (e.g., `V1`, `V2`) needed for long-term production maintenance.
- **How the team caught it:** The developer realized that maintaining 10+ sequential migrations with a single file technique was unscalable.
- **Correction:** Dropped the custom R2DBC initializer and integrated embedded Flyway with the PostgreSQL JDBC driver. Externalized DB credentials via environment variables so both Flyway and R2DBC can connect seamlessly.

## Case 10: Superficial Code Review on Transactional Outbox and Idempotency

- **Date:** 2026-07-08
- **Owner:** Person 3 (Inventory)
- **Prompt/task:** Review the `feature/inventory-reservation-logic` branch to verify the implementation of core business logic, Transactional Outbox, and idempotency.
- **Agent output:** An extremely positive code review approving the PR "with flying colors", praising the Kafka integration as a solid Transactional Outbox and the idempotency logic as "extreme".
- **Why it was wrong:** The review was technically superficial and missed critical distributed systems flaws. The Kafka publisher executed a `CompletableFuture` fire-and-forget after the DB commit, risking silent event loss (dual-write failure, NOT an Outbox pattern). Furthermore, the idempotency check used a naive check-then-act approach susceptible to TOCTOU race conditions, returning HTTP 500 on concurrent requests instead of returning cached responses. Finally, business failures (no seats) incorrectly returned HTTP 201.
- **How the team detected it:** A secondary, deeper independent review exposed the severe flaws and the architectural misunderstanding of the initial review.
- **Correction:** The agent recognized the mistake, abandoned the superficial approach, and planned fixes to implement a proper Outbox/event failure handling, an insert-first idempotency strategy, and correct HTTP semantics.

## Case 11: Racy Idempotency Check (TOCTOU) and Dual-Write Vulnerability

- **Date:** 2026-07-09
- **Owner:** Person 3 (Inventory)
- **Prompt/task:** Implement reservation logic with idempotency and Kafka event publishing.
- **Agent output:** An implementation that checked the idempotency table before executing the business logic (check-then-act) and published events to Kafka directly after the DB transaction committed.
- **Why it was wrong:** The check-then-act approach suffers from a Time-Of-Check to Time-Of-Use (TOCTOU) vulnerability where concurrent identical requests could both see `null` and crash with a `DuplicateKeyException` (500 Server Error). Furthermore, publishing to Kafka outside the DB transaction created a dual-write vulnerability where the DB could commit but the Kafka publish could fail, leaving the system in an inconsistent state. The `generateFingerprint` also didn't sort the seats list, producing false 409 conflicts.
- **How the team caught it:** Code review flagged the TOCTOU idempotency flow, the false conflicts on arrays, and the risky dual-write pattern, mandating the Outbox pattern.
- **Correction:** Refactored the idempotency logic to use an "insert-first" (catch `DuplicateKeyException`) fallback to safely handle concurrency. Replaced direct Kafka publishing with the Transactional Outbox pattern, inserting events into an `outbox_events` table within the same R2DBC transaction to be processed by Debezium. Also updated the controller to return a `422 Unprocessable Entity` instead of `201 Created` for failed reservations due to oversell, and sorted the seats array before hashing.


## Case 12: Entity Mapping Trap Silently Routed Reconciliation Failures to the Dead-Letter Topic

- **Date:** 2026-08-05
- **Owner:** Person 2 (Orders)
- **Prompt/task:** Implement the Orders side of issue #53 — synchronous Inventory reservation confirmation, the new durable checkout phases, and the typed reconciliation records.
- **Agent output:** A `ReconciliationRecord` R2DBC entity modelled on the existing `Order`, carrying a `@Transient private val isNewRecord: Boolean = false` constructor parameter to control insert-versus-update, plus a repository whose `upsertOpen` uses `INSERT ... ON CONFLICT ... RETURNING *`.
- **Why it was wrong:** Spring Data cannot bind a `@Transient` constructor parameter when materialising a row, so every `upsertOpen` threw `MappingException: No property isNewRecord found on entity class`. The failure mode was the dangerous part, not the exception: the write happens inside the orchestrator's compensation path, so the exception propagated out of the pipeline, Kafka retried three times, the record went to the dead-letter topic, and the order was left stranded in `CONFIRMING_INVENTORY`. Nothing was logged at ERROR by Orders itself. A reviewer reading the code would see a correct-looking upsert; the copied pattern from `Order` was the trap, because `Order` declares the flag as a body property rather than a constructor parameter.
- **How the team caught it:** Two integration tests failed with unhelpful symptoms — one timed out waiting for a terminal state, the other simply saw zero records. Neither pointed at the entity. A dedicated `ReconciliationRecordRepositoryIntegrationTest` written to isolate the SQL surfaced the real `MappingException` immediately on all ten cases.
- **Correction:** Dropped `Persistable` from `ReconciliationRecord` entirely. Every write already goes through explicit SQL in the repository, so nothing needs Spring Data to choose between insert and update, and the flag had no reason to exist. Lesson recorded in the entity's KDoc so the pattern is not copied back.

## Case 13: Kotlin SAM Conversion Produced a WebClient Filter Spring Could Not Compose

- **Date:** 2026-08-05
- **Owner:** Person 2 (Orders)
- **Prompt/task:** Test the Inventory client's HTTP error decoding without adding a mock HTTP server dependency.
- **Agent output:** First a Mockito mock of `ServerOAuth2AuthorizedClientExchangeFilterFunction`, then — after that class turned out to be `final` — a Kotlin SAM lambda: `ExchangeFilterFunction { request, next -> next.exchange(request) }`.
- **Why it was wrong:** Both produced a filter that `DefaultWebClient` could not reduce, failing every request with `NullPointerException: ... "finalFilterFunction" is null`. The misleading part is that the error names an internal Spring field and points at the request line, so it reads as a WebClient or connector problem rather than as a problem with the argument the test supplied.
- **How the team caught it:** Eight of nine client tests failed with the same NPE while one passed, which is the signature of a harness fault rather than a logic fault.
- **Correction:** Used Spring's own factory, `ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) }`. Separately, changed both WebClient clients to depend on the `ExchangeFilterFunction` interface instead of the final Spring Security implementation — a one-line production change that makes the clients substitutable at all, and the reason the wire-level error-decoding tests can exist without WireMock.

## Case 14: Test Assertions Scoped Globally in a Shared Spring Context

- **Date:** 2026-08-05
- **Owner:** Person 2 (Orders)
- **Prompt/task:** Add integration tests for the reconciliation gauges and for the dead-letter consumer.
- **Agent output:** Assertions written against absolute counts of shared state — `assertEquals(1, countOpenByKind(...))` for the gauge, and `assertEquals(0, reconciliationRepository.findAll().count())` to prove that a poison record opens no incident.
- **Why it was wrong:** Both tests run in a `@SpringBootTest` context shared with siblings that create their own rows, so the absolute count depends on execution order. The gauge queries are intentionally global because they feed an "any open incident" alert, so they cannot be scoped away. The failure mode is the problem: the test passes or fails depending on which tests ran before it, which reads as flakiness and erodes trust in the whole suite rather than pointing at the mistake.
- **How the team caught it:** Both failed on the first full-suite run after passing in isolation — the signature of order dependence rather than of a logic fault.
- **Correction:** Two different fixes for two different situations. For the gauge test, a `@BeforeEach` truncation, because the query under test is legitimately global and the test needs a known starting point. For the dead-letter test, an assertion on the *delta* rather than the absolute value, because truncating there would have destroyed state the surrounding checkout scenarios depend on. Recorded because the instinct to reach for the same fix twice was itself the trap.
