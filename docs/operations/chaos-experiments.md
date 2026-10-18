# Fault experiment design

Use bounded faults to test a stated hypothesis, measure customer impact and recovery, and independently check business effects. Record a failed hypothesis as a finding with corrective work. The [report template](../templates/verification-report-template.md) defines the report shape; configuration owns [execution procedures and results](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/chaos-experiment-reports.md).

## Required fault families

| Fault | Hypothesis | Observe |
| --- | --- | --- |
| Payments latency | Orders bounds waiting and opens its breaker while Catalog remains usable; accepted work resumes or leaves actionable reconciliation | Client deadlines, breaker state, Catalog RED, automatic versus operator convergence, independent provider effects |
| Inventory crash during hold | An unknown hold outcome preserves the durable phase; retries cannot oversell or double-charge | An observed in-flight SQL barrier, duplicate event/HTTP delivery, reservations/seats, authorization count, final order state and incidents |
| Node / failure-domain disruption | Reviewed placement and redundancy preserve or restore the critical path within the applicable objective | Actual affected replicas, scheduling/headroom, endpoint withdrawal, client errors, data quorum, recovery and PDB behavior where the fault uses voluntary eviction |
| Kafka disruption | Durable publication and consumer replay preserve accepted work without repeated business effects | Relay/task health, backlog and committed lag, convergence, DLT incidents and safe redrive after healing |
| PostgreSQL primary failover | Primary promotion restores database access and checkout without oversell | Promotion time, application write/read recovery, connector/slot recovery, checkout convergence, client impact and independently audited effects |

For each run, declare steady state, injection duration, workload, cleanup and evidence scope before injecting. A worker-container stop and a process freeze are different faults; a local multi-node cluster does not establish physical-host or AZ tolerance. Distinguish failed client deadlines from eventual correctness and distinguish promotion/read recovery from checkout recovery.

[SLOs and recovery objectives](slo-observability.md) own targets and their adoption status. Compare observed recovery with an applicable team-declared RTO when claiming compliance; do not choose a retrospective target to make a run pass. Missing RTO/SLO assessment does not erase an experiment's measured impact or recovery.

## Additional refund diagnostic

A definitive Inventory seat-return refusal after successful payment reversal must leave the order `REFUNDING` with `SEAT_RETURN_FAILURE`, not falsely complete it. Verify seat state, incident visibility and safe redrive after correction. A Pod kill produces an unknown outcome and does not test this refusal. This is a separate post-sale diagnostic, not an additional fault family required by the base assignment.

The [lifecycle specification](../design/reservation-confirmation.md) owns invariants, retry budgets, DLT behavior and reversal-key semantics. Use the [operator runbook](orders-recovery-runbook.md) for recovery actions and `GET /api/orders/reconciliation` for incident discovery. No injected fault authorizes arbitrary business-row repair.
