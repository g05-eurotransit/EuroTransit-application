---
name: eurotransit-cross-repo-docs
description: Keep EuroTransit documentation, ADRs, delivery evidence, agent guidance, and specification-alignment material consistent across the sibling EuroTransit-application and EuroTransit-configuration repositories. Use when Codex updates docs, AGENTS.md, implementation plans, acceptance criteria, architecture decisions, operational proof, or assignment/PDF compliance material for EuroTransit.
---

# EuroTransit Cross-Repo Docs

## Overview

Use this skill as the guardrail for cross-repo documentation work. The application repo remains the canonical place for product, service, and architecture docs; the configuration repo should contain deployment, GitOps, operational proof, and environment evidence without duplicating canonical requirements.

## Workflow

1. Fetch remote `main` in both repositories and use those revisions as the documentation/code baseline. Locate both sibling repositories under the same parent folder: `EuroTransit-application` and `EuroTransit-configuration`. If either is missing, stop and ask the user to clone it in the same folder before changing docs.
2. Read both repos' `AGENTS.md` files before editing. Follow repo-specific instructions, especially product naming and assignment-mandated deliverable filenames.
3. Identify whether the change is canonical product/application content, configuration/evidence content, or both.
4. Update the canonical source first, then add a short pointer or operational mapping in the other repo when needed.
5. Avoid copying the same requirement text into both repos. Prefer links, mappings, and ownership notes.
6. After editing, run `rg -n "Capstone|capstone"` in touched docs. Product-facing wording must use EuroTransit or EuroTransit Marketplace, except for assignment-mandated filenames or explicit assignment/PDF references.
7. Check `git status --short --branch` in both repos before reporting or committing.

## Placement Rules

- Put business requirements, architecture decisions, service responsibilities, API contracts, auth/security decisions, resilience policy, SLO definitions, and implementation plans in `EuroTransit-application/docs`.
- Put Kubernetes manifests, Kustomize/Helm decisions, Argo CD/GitOps material, progressive delivery evidence, monitoring wiring, chaos execution evidence, and environment-specific proof in `EuroTransit-configuration/docs`.
- When a topic spans both repos, keep the requirement or decision in application docs and keep configuration docs focused on how that decision is deployed, verified, or evidenced.

## Document layout

- Application: `docs/design` owns behavior, contracts and SLOs; `docs/development` owns local setup and instrumentation; `docs/operations` owns API recovery; `docs/verification` owns local evidence.
- Configuration: `docs/platform` owns effective configuration; `docs/operations` owns repeatable procedures; `docs/verification` owns results and incident analysis. Readiness has one home at `docs/readiness.md`.
- Root READMEs provide navigation. Agent workflows live under `.agents/skills`, outside product documentation.
- Cross-repository links use GitHub `main`; local links remain relative. Verify moved-file anchors against both coordinated working trees before publication.

## Required Checks

- Authentication, authorization, secret handling, observability, resilience, and ownership must be represented when the doc affects enterprise-grade behavior.
- Production-facing docs must not describe mocks as production design. If local stubs exist, label them as local-only and name the intended production provider or integration.
- If the PDF specifies an exact filename or deliverable name, preserve that exact filename even if it contains assignment terminology.
