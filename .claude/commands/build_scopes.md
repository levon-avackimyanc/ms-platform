---
description: Parallel Dev+Test conductor. From analytic/increment.md spawns dev-conductor and test-conductor in the background AT THE SAME TIME (t=0), relays their bubble-up interview questions to the user via AskUserQuestion, and resumes them via SendMessage. Thin orchestrator — it never plans, builds, or touches git itself. Ends pointing at /merge_gate (Dev) and /test_run → /test_gate (Test).
argument-hint: "[optional orchestration note]"
model: opus
disallowed-tools: EnterPlanMode
---

# /build_scopes

The **thin main-thread Conductor**. It runs Dev scope and Test scope **truly in
parallel** by spawning two background conductor agents from the same
`analytic/increment.md` and doing nothing else but **relaying HITL**.

> Naming: `/build_scopes` is the working name — rename freely; it's the only
> user-facing entry point added by the parallel design.

You do **not** plan, write code, write tests, or run git. The conductors own all of
that. Your entire job is: spawn, relay questions, report.

## Step 0 — Precondition

Assert the increment exists:
```bash
ls analytic/increment.md
```
If it does not exist → stop and tell the user to run `/analyze` first. Do not spawn
anything.

## Step 1 — Spawn both conductors at t=0 (parallel)

In a **single message**, spawn both `run_in_background: true` so they start together:

- `Agent({ subagent_type: "dev-conductor",  description: "Dev scope pipeline",  prompt: <see below>, run_in_background: true })`
- `Agent({ subagent_type: "test-conductor", description: "Test scope authoring", prompt: <see below>, run_in_background: true })`

Prompt for each: tell it to run its full pipeline from `analytic/increment.md`,
that it is independent of the other scope (no contract-frozen handshake), and that
**interview questions must bubble up** as a `HITL_QUESTIONS` block per its agent spec.
Pass through any orchestration note from `$ARGUMENTS`. Record each returned `agentId`.

## Step 2 — Relay loop (this is your whole job)

You'll get a task-notification each time a conductor stops. For each:

- **`STATUS: PAUSED_AWAITING_ANSWER`** (its result ends with a `HITL_QUESTIONS` block):
  parse the questions and call **`AskUserQuestion`** (1–4 per call; the block carries
  `question` / `header` / `options`, tagged `scope: dev` or `scope: test` so you label
  which conductor is asking). Then send the answers back to **that** conductor:
  `SendMessage({ to: <its agentId>, message: "<id>=<answer>; …" })`. It resumes in the
  background. Keep the other conductor running untouched.
- **`DONE`**: record its report; if the other is still running, keep waiting.
- **`BLOCKED`**: surface the reason to the user (e.g. missing increment) and stop that
  branch.

Do not poll or `Read` the conductor output files — wait for notifications. Never
answer a conductor's question yourself; always ask the user. Batch a conductor's
questions into as few `AskUserQuestion` calls as the block allows.

## Step 3 — Final report

When **both** conductors have reached `DONE` (or `BLOCKED`), summarise:

```
✅ Parallel build complete

Dev:  <dev-conductor report summary — plan + build verdicts>
Test: <test-conductor report summary — model/plan + layers authored>

Next:
- Dev:  /merge_gate            (HITL commit/merge — only git step)
- Test: /test_run → /test_gate (human-launched after Dev code is done)
```

If either is still `PAUSED_AWAITING_ANSWER`, you are not done — return to Step 2.

## Notes

- True parallelism: both conductors are `[bg]` and active simultaneously; Dev build
  overlaps Test authoring.
- Decoupled: the two never read each other's artifacts. Dev↔Test divergence is
  reconciled at `/test_run`, not here.
- The standalone `/dev_plan`, `/smart_build`, `/test_plan`, `/test_build` commands
  still exist for running one scope by hand; `/build_scopes` is the parallel front door.
