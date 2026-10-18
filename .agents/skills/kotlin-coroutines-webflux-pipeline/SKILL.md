---
name: kotlin-coroutines-webflux-pipeline
description: Implement or review Kotlin coroutine, Spring WebFlux, Reactor interop, Kafka, and asynchronous order/inventory/payment pipeline code for EuroTransit services. Use when Codex changes service flows, suspending APIs, event consumers/producers, timeout/retry logic, idempotency, backpressure, cancellation, or coroutine tests in the EuroTransit Kotlin/Spring modules.
---

# Kotlin Coroutines WebFlux Pipeline

## Overview

Use this skill for EuroTransit's async money path: order placement, seat holds, inventory reservation, payment authorization, notification dispatch, and event-driven recovery. It is not a generic Kotlin style guide.

## First Pass

1. Read the touched service's existing package structure and tests before adding abstractions.
2. Identify the consistency boundary: command, event, idempotency key, database transaction, and retry behavior.
3. Keep public APIs explicit about authenticated user identity and authorization context.
4. Prefer suspending service APIs for asynchronous I/O. Keep Reactor types at WebFlux/Kafka integration boundaries unless the module already standardizes on Reactor internally.
5. Verify that blocking clients, JDBC calls, file I/O, sleeps, and CPU-heavy work do not run on event-loop threads.

## Coroutine Rules

- Use structured concurrency. Avoid `GlobalScope`, fire-and-forget jobs, and untracked background work.
- Propagate cancellation. Do not swallow `CancellationException`.
- Bound fan-out with explicit concurrency limits when processing seats, reservations, payments, or outbound events.
- Put timeouts at integration boundaries and make retry policies idempotency-aware.
- Prefer immutable request/result models and explicit state transitions over shared mutable coroutine state.
- Bridge Reactor and coroutines deliberately with the existing project dependencies; do not mix styles inside one flow without a clear boundary.

## Pipeline Rules

- Model every externally visible command with an idempotency key or deterministic duplicate handling.
- Emit Kafka events only after the local state transition is durable, or document the compensating/outbox approach used by the module.
- Treat payment authorization and inventory reservation as real integrations. Test providers may be sandbox APIs, but production docs and code paths must not rely on mocks.
- Add dead-letter, retry, or recovery behavior for consumer failures that affect order completion.
- Preserve service ownership: Orders owns order state, Inventory owns reservation/no-oversell state, Payments owns payment authorization state, Notifications owns delivery state, Catalog owns route/offer data.

## Tests

- Add focused tests for cancellation, timeout, duplicate command handling, and retry behavior when those paths change.
- Use coroutine-aware test utilities such as `runTest` where applicable.
- Avoid tests that pass only because they sleep for timing. Prefer virtual time, explicit synchronization, or deterministic fakes.
- For WebFlux endpoints, verify status codes and user-context propagation, not just happy-path payloads.
