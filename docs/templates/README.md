# Documentation guide

Write in English. Give the answer first, followed by the information needed to use it and the evidence needed to check it. Choose a template below and remove its authoring comments and unused sections before publishing.

## Choose a structure

| Reader needs | Template |
| --- | --- |
| Behavior, architecture or platform reference | [Specification/reference](specification-template.md) |
| Rationale and alternatives | [Decision](decision-template.md) |
| A repeatable operation | [Runbook](runbook-template.md) |
| A bounded verification result | [Report](verification-report-template.md) |
| Incident analysis and corrective work | [Postmortem](postmortem-template.md) |

Navigation belongs in the root README; keep a dedicated results index when it adds verdicts and scope. Readiness maps criteria to evidence and open work. Governance names actors, permissions, enforcement and failure impact. A short work plan can be a table in the document that owns its decisions; do not create a page for every authoring variant.

## Navigation

Keep root READMEs to an introduction, a quick start and links to the main reference and macrosections. Section indexes own the detailed document lists. Group related verification reports by environment and theme, retaining separate methods, verdicts and evidence for each experiment.

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
| Kubernetes, GitOps, ingress, secrets, monitoring wiring | Configuration platform references | Link to manifests and procedures |
| Cluster procedures and operational verification | Configuration runbooks/reports | Link to the procedure or bounded result |
| Local application procedures and code regression tests | Application development/verification | Keep local results distinct from cluster proof |

This directory is the shared template collection for both sibling repositories. Do not copy it into configuration. Use repository-relative Markdown links within a repository and full GitHub links to `main` across repositories. Validate the coordinated working trees when a cross-repository destination is introduced in the same change; those links become live after both changes merge. Immutable evidence links retain their recorded revision. A code-formatted path alone is not a navigable reference.

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
| Architecture | Components, responsibilities, interactions, trust/persistence boundaries, diagram and source revision |
| Deployment | Effective environments, controller ownership, topology, constraints and source manifests |

Security references name the actual enforcement point. Observability references link SLOs to metric names, queries, alerts, dashboards, and their deployment artifacts without repeating targets in several documents.

## Reports and evidence

Keep one current report per scenario and environment, with stable names such as `payments-latency-aks.md`. Replace repeated executions with the latest valid result and integrate older information only where it still applies. Different faults or environments remain separate reports or explicitly separated sections.

Preserve evidence strength: a newer manual provider check does not inherit an older automated audit. A final-state audit does not turn a failed client deadline into a pass. Keep compound faults, local regressions, cluster failover, and steady-state baselines distinguishable. Carry forward unresolved defects and qualify obsolete observations by the revision they concern.

Link sanitized, portable evidence when available. Mark restricted evidence as unavailable to repository readers; keep credentials, personal data, raw provider references, and workstation-only paths out of public documents. Use Git history for superseded prose rather than an archive of nearly identical reports.

## Technical diagrams

Use Archify for architecture, deployment, checkout sequences, state transitions, or GitOps flows when the picture answers a concrete technical question. A short checklist or a simple instruction does not need a diagram.

Version editable JSON, its standalone HTML and canonical SVG export together under `docs/diagrams/<topic>/`, using stable names. Embed the SVG as a relative Markdown image wrapped in a link to its hosted interactive viewer: `[![Microservice architecture](../diagrams/microservices/architecture.svg)](https://g05-eurotransit.github.io/EuroTransit-application/microservices/architecture.html)`. Clicking the preview must open the interactive diagram. The Pages workflow uploads committed HTML; removing viewers requires a separate replacement generation/publishing pipeline.

Keep the abstraction level clear, protocols labelled and configured topology separate from live evidence. Verify relationships at the recorded Git revision. An architecture view is not a second contract. When a diagram changes, run Archify `finalize` with repository evidence and browser checks, export its canonical SVG and inspect light/dark captures at documentation width. Keep delivery sidecars, receipts and captures outside published documentation. Update JSON, HTML and SVG together.

## Publishing interactive diagrams

Each repository publishes its HTML diagrams from `main` with `.github/workflows/diagrams-pages.yml`. The workflow stages only HTML files from `docs/diagrams/`, preserving their relative paths, and serves `docs/diagrams/index.html` at the site root. Add new diagrams to that index and make each SVG preview link directly to its hosted viewer.

The sites are [application diagrams](https://g05-eurotransit.github.io/EuroTransit-application/) and [configuration diagrams](https://g05-eurotransit.github.io/EuroTransit-configuration/).

To enable hosting in each repository:

1. Open **Settings > Pages** and set **Build and deployment > Source** to **GitHub Actions**.
2. Merge the workflow and index into `main`.
3. In **Settings > Environments > github-pages**, check that deployment branch rules allow `main`.
4. Open **Actions > Publish diagrams** and check the deployment result. To retry without changing files, use **Run workflow** with branch `main`.

Subsequent HTML or workflow changes on `main` publish automatically. The workflow uploads the committed HTML directly; diagram generation and verification remain part of authoring. See [GitHub's custom workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Before publishing

- The opening answers the reader's question and states material limits.
- Each rule has one authoritative home; all links resolve.
- Optional empty sections, placeholders, and authoring comments are removed.
- Facts, proposals, configured state, and verification results remain distinguishable.
- Diagrams agree with their sources and pass artifact, browser, and visual review.
- Required deliverables remain: the assignment names `docs/capstone-dod.md` and `docs/agent-log.md`. Preserve their required coverage and the team ownership of design judgments, SLOs, chaos hypotheses, and the postmortem.
