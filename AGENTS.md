# Agent Project Notes

- The product/system name is **EuroTransit** or **EuroTransit Marketplace**.
- Treat academic assignment wording as external context, not as product identity.
- Follow assignment-mandated deliverable names exactly when the specification names a required file or path, even if that filename contains academic wording. Do not generalize that wording into product names, service names, APIs, Docker images, Helm releases, or user-facing copy.
- Do not introduce code package names, service names, APIs, Docker images, Helm releases, or user-facing product copy that uses assignment wording as the application name.
- Keep implementation and documentation focused on the EuroTransit marketplace and its money path: catalog browsing, order placement, seat hold, payment authorization, confirmation, and notification.
- Agents expect `EuroTransit-application` and `EuroTransit-configuration` to be checked out in the same parent folder. If either repository is missing, ask the user to clone it into that same folder before making cross-repository documentation changes.
- For work large enough to warrant it — several files, a crossed boundary, a shared contract or
  invariant, concurrency/idempotency/persistence/security/error handling — follow
  `docs/agent-skills/eurotransit-plan-implement-review/SKILL.md`: a versioned plan, implementation
  delegated to a Sonnet agent, an adversarial review by a clean Opus agent, then a final check by the
  orchestrator. Deciding whether the change warrants it is itself part of the method, and reading the
  code to size it comes before that decision; small local work is done directly.
- When updating documentation, always evaluate both repositories and keep them aligned: application docs own behavior/contracts/team planning, while configuration docs own Kubernetes, GitOps, ingress, secrets, observability, and operational proof.
