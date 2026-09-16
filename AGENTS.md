# Agent Project Notes

- The product/system name is **EuroTransit** or **EuroTransit Marketplace**.
- Treat academic assignment wording as external context, not as product identity.
- Follow assignment-mandated deliverable names exactly when the specification names a required file or path, even if that filename contains academic wording. Do not generalize that wording into product names, service names, APIs, Docker images, Helm releases, or user-facing copy.
- Do not introduce code package names, service names, APIs, Docker images, Helm releases, or user-facing product copy that uses assignment wording as the application name.
- Keep implementation and documentation focused on the EuroTransit marketplace and its money path: catalog browsing, order placement, seat hold, payment authorization, confirmation, and notification.
- Agents expect `EuroTransit-application` and `EuroTransit-configuration` to be checked out in the same parent folder. If either repository is missing, ask the user to clone it into that same folder before making cross-repository documentation changes.
- When updating documentation, always evaluate both repositories and keep them aligned: application docs own behavior/contracts/team planning, while configuration docs own Kubernetes, GitOps, ingress, secrets, observability, and operational proof.
