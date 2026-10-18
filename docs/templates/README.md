# Documentation guide

Write in English. Give the answer first, followed by the information needed to use it and the evidence needed to check it. Choose a template below and remove its authoring comments and unused sections before publishing.

## Choose a template

| Reader's question | Template |
| --- | --- |
| Where should I start? | [Section index](section-index-template.md) |
| How does the system fit together? | [Architecture](architecture-template.md) |
| What behavior or contract must hold? | [Specification](specification-template.md) |
| Why was this approach chosen? | [Architecture decision](decision-template.md) |
| What runs in each environment? | [Deployment / platform reference](deployment-template.md) |
| How do I perform or recover an operation? | [Runbook](runbook-template.md) |
| What did this test demonstrate? | [Verification report](verification-report-template.md) |
| What failed and what needs to change? | [Postmortem](postmortem-template.md) |
| What prevents acceptance or release? | [Readiness / DoD](readiness-template.md) |
| What work remains and in what order? | [Work plan](work-plan-template.md) |
| What may an actor do, and how is it checked? | [Governance](governance-template.md) |

Completed examples: [microservice architecture](../design/architecture.md) and [Kind / AKS deployment](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md).

## Structure and prose

Open with one short paragraph stating the behavior, decision, outcome, or operation. Include a material limitation there if it changes the answer. Then explain the rules or steps; put source references and supporting detail last. A section should add information the reader needs.

Keep titles descriptive, paragraphs short, and terminology consistent with the code. Use tables for comparisons and mappings, numbered lists for procedures, and code blocks for exact commands. Split a document when readers need different tasks or levels of detail, rather than to meet a line count. Avoid a separate page that only repeats links already in its section index.

Use `humanizer` to review prose for filler, staged introductions, repeated conclusions, inflated claims, and mechanical formatting. Preserve technical conditions, uncertainty, commands, and link targets. Consolidation may remove duplicate or superseded claims; record where still-valid information moves before deleting its original home.

Omit editorial dates, decision timelines, session handovers, and historical test counts. Name documents by topic or scenario. Use relative incident times such as `T+45 s`; retain absolute timestamps in linked evidence when needed for correlation. Domain dates, durations, revisions, digests, and run identifiers remain when they affect behavior or reproducibility.

## Ownership and authoritative sources

| Information | Authoritative home | Other documents do |
| --- | --- | --- |
| HTTP/event contracts, authorization, idempotency | Application specifications | Link to the contract |
| Checkout states, invariants, compensation, recovery policy | Application domain specifications | Explain their own interaction with the policy |
| Architectural rationale and alternatives | Application decisions | Link to the rationale |
| SLO targets and SLI semantics | Application SLO specification | Map signals to implementation artifacts |
| Kubernetes, GitOps, ingress, secrets, monitoring wiring | Configuration platform/delivery references | Link to manifests and procedures |
| Cluster procedures and operational verification | Configuration runbooks/reports | Link to the procedure or bounded result |
| Local application procedures and code regression tests | Application operations/tests | Keep local results distinct from cluster proof |

This directory is the shared template collection for both sibling repositories. Do not copy it into configuration. Use repository-relative Markdown links within a repository and full GitHub links across repositories. A code-formatted path alone is not a navigable reference.

A specification defines a rule once. An overview describes responsibilities and links to that rule. A decision explains the choice without restating the contract. A plan records remaining work. A readiness document maps acceptance criteria to evidence; it does not redefine their thresholds.

Label claims precisely: **implemented** means supported by code, **configured** means declared by the effective manifests, **verified** means observed within a stated test scope, and **proposed** means not yet adopted. A manifest does not establish live health or availability. Record unresolved contradictions beside the affected claim and link to both sources.

## Specification variants

Use the specification template with the table that fits the subject; omit unrelated variants.

| Subject | Essential fields |
| --- | --- |
| HTTP | Method/path, caller and authorization, request/response, errors, idempotency |
| Event | Topic/type, producer/consumers, payload, key, ordering, redelivery and compatibility |
| Domain/state model | Invariant, permitted transitions, authority, concurrency, unknown-outcome handling |
| Security | Resource/action, allowed identity, enforcement point, ownership check, default denial |
| SLO | Objective, SLI numerator/denominator or duration, target, window, measurement source |

Security references name the actual enforcement point. Observability references link SLOs to metric names, queries, alerts, dashboards, and their deployment artifacts without repeating targets in several documents.

## Reports and evidence

Keep one current report per scenario and environment, with stable names such as `payments-latency-aks.md`. Replace repeated executions with the latest valid result and integrate older information only where it still applies. Different faults or environments remain separate reports or explicitly separated sections.

Preserve evidence strength: a newer manual provider check does not inherit an older automated audit. A final-state audit does not turn a failed client deadline into a pass. Keep compound faults, local regressions, cluster failover, and steady-state baselines distinguishable. Carry forward unresolved defects and qualify obsolete observations by the revision they concern.

Link sanitized, portable evidence when available. Mark restricted evidence as unavailable to repository readers; keep credentials, personal data, raw provider references, and workstation-only paths out of public documents. Use Git history for superseded prose rather than an archive of nearly identical reports.

## Technical diagrams

Use Archify for architecture, deployment, checkout sequences, state transitions, or GitOps flows when the picture answers a concrete technical question. A short checklist or a simple instruction does not need a diagram.

Keep editable JSON, standalone HTML, and its canonical SVG export together under `docs/diagrams/<topic>/`, with stable filenames and no dates. Embed the SVG as a Markdown image using a relative path so readers see it directly on GitHub. Provide a full-size SVG link and an optional HTML download for browser interaction; GitHub's file viewer does not run the HTML application. Export the SVG through Archify's canonical export, with automatic light/dark themes and no scripts or external dependencies.

Keep each diagram's abstraction level clear, label protocols and asynchronous relationships, and distinguish configured topology from a proposal or live observation. Verify components and relationships against code or manifests at the recorded Git revision. An architectural summary is a view of those sources, not a second contract.

Run Archify `finalize` with repository evidence and complete its browser checks. Send receipts and captures to a local review directory outside published docs. The tool requires delivery metadata beside the HTML; keep that local sidecar ignored by Git. Regenerate it before checking a fresh checkout. Inspect light/dark captures before claiming visual review, including the SVG embedded as an image at documentation width. Update the JSON, regenerate HTML, and re-export SVG together when the represented implementation changes.

## Before publishing

- The opening answers the reader's question and states material limits.
- Each rule has one authoritative home; all links resolve.
- Optional empty sections, placeholders, and authoring comments are removed.
- Facts, proposals, configured state, and verification results remain distinguishable.
- Diagrams agree with their sources and pass artifact, browser, and visual review.
- Required deliverables remain: the assignment names `docs/capstone-dod.md` and `docs/agent-log.md`. Preserve their required coverage and the team ownership of design judgments, SLOs, chaos hypotheses, and the postmortem.
