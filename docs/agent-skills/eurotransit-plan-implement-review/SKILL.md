---
name: eurotransit-plan-implement-review
description: Three-phase delivery for EuroTransit changes — a versioned plan written by the orchestrating model, implementation delegated to a Sonnet agent, and an adversarial review by a clean Opus agent with no conversation context. Use when a change spans several files, crosses a service/process/database/client boundary, touches a shared contract or invariant, or affects concurrency, idempotency, persistence, caching, security or error handling. Do NOT use it for small local work where the process costs more than it returns.
---

# Plan → Implement → Review

## Overview

Separates deciding from typing, and puts an uninvested reader between the two. The value is not
tidiness: on the EuroTransit money path the failures that matter pass CI, and this process exists to
catch the ones a green build does not prove.

**It is not the default for every request.** Applying it to small work costs more than it returns —
each agent re-reads the codebase from scratch, roughly 100–200k tokens of pure context acquisition.

## Step 0 — Decide whether the full workflow is warranted

**Read enough code to judge the real scope first.** Do not size the work from the request's wording.

Use all three phases when, for example:

- the change spans several files, modules or components;
- it crosses more than one boundary — service, process, database, client, API;
- it can alter a shared contract, invariant or behaviour;
- it introduces or changes concurrency, idempotency, persistence, caching, security or error handling;
- it is large enough that separating planning, implementation and review genuinely helps;
- the risk of being correct line by line while wrong overall is real.

Do it yourself, without the three phases, for:

- a change localised to one or a few files;
- an obvious bugfix whose correct behaviour is already settled;
- mechanical, bounded refactoring;
- an addition that plainly follows an existing pattern;
- text, configuration, or code with no architectural consequence.

A practical threshold: delegate when the work is large enough that doing it inline would force a
context compaction. This is judgement, not arithmetic — the goal is to apply the process where it
raises quality, not to apply it always.

**If you skip the phases, still verify with rigour proportionate to the change.** If you do not skip
them, skip none of the three and do not reorder them.

## Phase 1 — The plan (you, at high effort)

**Read the code before planning. Never plan on assumptions.** The plan is a versioned file in the
repo and contains, in this order:

1. **What is being built and why**, citing decisions already taken (ADR, issue, discussion) rather
   than restating them.
2. **"Verified before writing"** — facts read in the code, with file references. They exist so the
   implementer does not re-derive them, and so a wrong one is met as a contradiction rather than
   absorbed as a surprise. **Always include the caller-side contract: what the client actually sends,
   not what it ought to send.**
3. **"Settled / open / stop-and-ask"** — settled means implement and do not reopen; open means decide
   during the work and write down what you decided; stop-and-ask means do not decide alone (another
   owner's slice, a public contract, the meaning of something shared).
4. **The content itself**, in subsections.
5. **"Done when"** — verifiable criteria, not intentions.
6. **A standing check that does not depend on the plan being right.** For example: before closing,
   list every value the change causes to cross a boundary — service, process, database, client — and
   verify what the receiver does with it.

**Before launching any agent, ask the user the questions that remain open**, explained well and with
concrete examples, so they can answer without knowing the code.

## Phase 2 — Implementation (Sonnet agent, in the background)

The brief must contain:

- where the plan is and which sections to read **before** writing code;
- **explicit permission to stop** if a fact in the plan turns out to be wrong, rather than working
  around it silently;
- operating rules: branch, local commits, **no push, no PRs, no issue comments**, a green build, and
  the starting test count;
- an instruction to match the surrounding code and tests by reading neighbouring files;
- the list of ways to fail **despite** a green build;
- the required final report: what was implemented, the commits, the build result, every decision made
  among the "open" ones, and above all **everything it had to decide that the plan did not mention**.

## Phase 3 — Review (a clean Opus agent, with no conversation context)

**Do not review it yourself: you wrote the plan and you are invested in it.** The brief contains:

- the branch and the diff command, and that the build is green with N tests — so no time is spent on
  compilation or on tests that pass, but on what a green build does not prove;
- the **settled** decisions listed, with explicit permission to report that the code implements one
  badly, and a prohibition on re-litigating them;
- hunting directions, starting from what a plan cannot foresee: values crossing a boundary, shared
  predicates with several readers, invariants that live in the database, idempotency and redelivery,
  concurrency, tests that pass while proving nothing;
- **report only, do not touch the code**;
- verify claims instead of trusting them, including comments in the code;
- rank by real reachability and cost, not by quantity;
- say what was checked and found **correct** too, otherwise coverage is unknown.

## Then — the final check

**You do it.** Independently verify both agents' claims before reporting them to the user. Take no
report at face value.

## If a second round is needed

Corrections written against a brief deserve review as much as code written against a plan. Re-launch
the reviewer, telling it the branch has already been through one round and where the correction
commits are, and aim it at two things:

1. whether the corrections are complete;
2. whether the same species of mistake survives elsewhere.

## Why the shape is this way

Observed on this codebase, not theory:

- A review round found two high-severity defects the plan and the orchestrator had both missed — and
  the implementer had *seen* both areas and talked itself out of them. An uninvested reader is what
  catches that.
- One of three corrections written against a brief was incomplete, and the missing call site was
  named in the plan itself. Hence the second round.
- The standing check caught a real bug during implementation that no plan had anticipated.
- Total agent tokens exceed doing the work inline, but they are mostly Sonnet, and they never enter
  the orchestrator's context. What that buys is a decision history that survives the whole session.
