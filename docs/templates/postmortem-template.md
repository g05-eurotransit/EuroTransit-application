# [Failure scenario] postmortem

<!-- The team owns the causal analysis and conclusions. Describe system conditions without blame. -->
[User-visible impact, duration, and current recovery state in one short paragraph.]

## Cause and contributing conditions

[Injected fault or initiating failure, causal mechanism, and relevant environment/revisions. State uncertainty where evidence is incomplete.]

## Observed sequence

<!-- State the steady-state baseline and link the SLO/SLI definitions. Use relative elapsed times. -->
| Elapsed time | Event and evidence |
| --- | --- |
| Before injection | [Baseline health and measurements] |
| T+0 | [Fault or incident begins] |
| T+[duration] | [Detection, diagnosis, mitigation, or recovery, with evidence] |

## Controls and gaps

[What contained the failure, what failed or surprised the team, and why. Distinguish mitigation from a permanent fix.]

## Follow-up actions

| Action | Owner | Completion criterion / evidence | Status |
| --- | --- | --- | --- |
| [Concrete change arising from the cause or gap] | [Responsible role] | [Verifiable outcome] | [Open / complete] |

## Evidence

[Link the execution report and sanitized logs, traces, metrics, or events supporting the analysis.]
