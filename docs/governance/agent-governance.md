# Agentic Coding Governance

Agentic coding tools may generate artifacts, but the team owns the decisions and the operational outcome.

## Allowed Agent Work

- Service scaffolding.
- Helm/manifests and CI drafts.
- Dashboard and alert rule drafts.
- Test harnesses and chaos experiment templates.
- Documentation drafts.

## Decisions Agents Must Not Own

- Service decomposition.
- Inventory consistency model.
- SLO definitions.
- Failure-mode mapping.
- Chaos hypotheses and conclusions.
- Postmortem analysis.
- Security and delivery-loop governance.

## Credentials

What an agent may hold, and the reasoning behind each line.

- **Git over HTTPS with the operator's own account.** Everything an agent commits, pushes, comments
  or opens on GitHub is attributable to a person, not to a bot identity, so authorship and
  accountability stay with the human who ran it. Nothing is signed or attributed to the tool.
- **GitHub token: the operator's, for the duration of the session.** The practical consequence is
  that the token carries write scope because GitHub's classic scopes have no read-only tier for
  private repositories — so the limit on writing is procedural, not technical, and that asymmetry is
  the reason the confirmation rule below exists rather than being optional.
- **No cluster credentials.** No kubeconfig, no service principal, no `az` login performed on the
  agent's initiative. Where a cluster read is genuinely needed, it runs through the operator's
  already-authenticated CLI and is limited to reads; on this project that boundary held, and an
  attempt to read the AKS managed node resource group correctly returned `AuthorizationFailed`.
- **No secret material.** Not the Stripe secret key, not database passwords, not the SealedSecrets
  private key. Sandbox and placeholder values only, and a `.env` that stays untracked.
- **Confirmation before every write.** Each file edit, commit, push, GitHub comment, package install
  and container start is described and approved first. Reads — `git fetch`, `log`, `diff`, tests,
  builds, `kubectl kustomize` — need no approval. Case 17 in `agent-log.md` is why the description
  itself is part of the control: the gate is only as good as the summary presented at it, and the
  agent is the only source of that summary.

## Permissions and Blast Radius

- Application repository access should be limited to pull-request creation.
- Configuration repository changes must require human review or policy-as-code checks before merge.
- Agents must not hold direct cluster credentials.
- Agents must not be able to bypass ArgoCD, SealedSecrets, or CI policy gates.

## Worst-Case Failure

**The scenario.** An agent proposes a configuration change that looks correct, no one reviews it,
and ArgoCD reconciles it into the cluster. Delivery is the shortest path from a generated artifact
to production: CI writes an image tag into this repository and ArgoCD applies it with
`prune: true` and `selfHeal: true`, so a manifest that removes a resource removes it in the cluster
too, and self-heal reverts a manual repair.

**The intended control** is mandatory human review plus policy checks for privileged workloads,
secrets, broad RBAC, unsafe probes and destructive manifests.

**Measured on 2026-10-13, that control is not in force.** Of the last ten merged pull requests in
the application repository, nine had zero reviews and zero approvals; only one had any review at
all. `planning/team-agentic-implementation-plan.md` requires a second human reviewer for changes
touching payments or inventory consistency, and #126 — which added a column and a migration to the
Orders outbox — merged with none. The gap is not that the rule is wrong; it is that it is written
and not practised, which is worse than not having it, because the DoD item it backs would otherwise
be ticked on the strength of the text alone.

**What would actually hold.** A branch protection rule requiring one approving review on `dev` and
`main` in both repositories costs nothing and converts the written rule into an enforced one. Until
that exists, the honest statement is the one in `capstone-dod.md`: the review gate is documented,
not enforced.

## Required Review Checks

- Probes must not depend on transient downstream dependencies.
- Alerts must be symptom-based and tied to SLOs, not just CPU or cause metrics.
- Handlers that process Kafka events must be idempotent.
- ServiceAccounts and RBAC must use least privilege.
- Retry settings must be bounded and include backoff/jitter.
- Generated manifests must not include plaintext secrets.
- Evidence cited in a team artifact must be resolvable by the reader: a commit hash, file path or
  line reference has to exist on a branch the recipient can see. An issue draft on this project once
  cited three commits from the pre-transfer history, which the squashed remote no longer contains —
  caught before posting, and the claim they supported was dropped rather than reworded.
- A statement about a shared branch must not be made from an unreconciled local environment
  (`agent-log.md`, Case 15).
- Where the repository ships a task runner, use it rather than reconstructing its steps
  (`agent-log.md`, Case 16).
