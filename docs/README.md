# EuroTransit Documentation

This folder contains the assignment requirements and the project-owned documents that answer them. The product/system name is **EuroTransit** or **EuroTransit Marketplace**.

## Required Reference

- [assignment-requirements.pdf](assignment-requirements.pdf): assignment requirements from the professors.
- [capstone-dod.md](capstone-dod.md): operational Definition of Done.

## Design

- [design/architecture-decisions.md](design/architecture-decisions.md): consistency, money-path, and progressive-delivery decisions with alternatives.
- [design/api-design.md](design/api-design.md): service boundaries, APIs, events, and sync/async communication shape.
- [design/reservation-confirmation.md](design/reservation-confirmation.md): Inventory confirmation protocol, HTTP/Kafka responsibilities, concurrency rules, retries, and compensation decisions.
- [design/observability-conventions.md](design/observability-conventions.md): correlation id, structured logging, RED metrics, tracing, and probe conventions every service adopts.
- [design/frontend.md](design/frontend.md): React customer SPA — OIDC/PKCE auth, in-memory token, SSE, and the frontend-security decisions (lecture 07b).

## Operations and Proof

- [operations/slo-observability.md](operations/slo-observability.md): checkout SLOs, SLIs, dashboards, alerts, and tracing expectations.
- [operations/chaos-experiments.md](operations/chaos-experiments.md): required hypothesis-driven chaos experiments.
- [operations/local-dev-setup.md](operations/local-dev-setup.md): documentation and scripts for running the local development environment.
- [operations/orders-shutdown-and-replay.md](operations/orders-shutdown-and-replay.md): Orders' failure domain, stop sequence, shutdown budgets, and offset/replay semantics.
- [operations/orders-recovery-runbook.md](operations/orders-recovery-runbook.md): what Orders recovers by itself, what needs an operator and how fast, and the redrive runbook.
- [operations/reports/](operations/reports/): drill reports — Orders shutdown, the Notifications outage and Orders recovery from a Payments outage on compose.

## Agent Governance

- [governance/agent-governance.md](governance/agent-governance.md): agent permissions, blast radius, and review gates.
- [agent-log.md](agent-log.md): concrete agent mistakes and team corrections.

## Supporting Material

- [planning/implementation-plan.md](planning/implementation-plan.md): phased work plan.
- [planning/team-agentic-implementation-plan.md](planning/team-agentic-implementation-plan.md): five-person parallel implementation plan with AI coding agents.
- [templates/postmortem-template.md](templates/postmortem-template.md): template for the required blameless postmortem.
