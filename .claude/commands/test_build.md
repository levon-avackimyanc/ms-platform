---
allowed-tools: Agent, Read, Bash, Glob, Grep
description: Test scope authoring. Executes a test plan — deploys autotester agents per layer (semantic context routing) and runs code-review. Authoring only; the suite run + triage is the separate /test_run. Runs in parallel with Dev.
argument-hint: [test-plan file, e.g. test/test-plan.md]
model: opus
---

# Test Build (authoring)

Execute the **authoring half** of a Test-scope plan: deploy `autotester` agents to
write the autotests, then `code-review` the diff. This is **Flow A** — it runs
**in parallel with Dev** and **does not run the suite**. The Exec → Analyzer →
{fix | bug} run is the separate, human-launched **`/test_run`** (after dev is done).
See `.claude/TEST_SCOPE.md`.

You are the **orchestrator**: you never write test code yourself — you deploy
agents and **own the task ledger** (the sub-agents have no Task tools; they report
status in their Report).

## Step 0: Load the test plan

`$ARGUMENTS` is the plan path (default `test/test-plan.md`). Read it. It was already
validated by `/test_plan` (`validate_plan --scope test`) and reviewed. Read
`test/test-model.md` too — the `autotester`s author strictly against it.

## Step 1: Route context per task

For each autotest task, route on its `**Stack**` field (same deterministic router
as Dev):

```bash
echo 'Stack: <task Stack keywords>' | uv run --script .claude/hooks/context_router.py | \
  uv run --script .claude/hooks/section_loader.py
```

This loads only the relevant testing refs (e.g. `java-testing#integration`,
`load-testing#k6`) for that layer.

## Step 2: Deploy autotester(s)

For each autotest task (`integration-tests` / `sys-tests` / `e2e-tests` /
`ui-tests` / `load-tests`):

- Deploy `Agent({ subagent_type: "autotester", ... })`, passing the task(s), the
  routed context, and the matching `### <Layer> Layer` block from the plan.
- Tasks marked `**Parallel**: true` and not blocked → run concurrently
  (`run_in_background: true`); otherwise sequential per `Depends On`.
- The autotester's `validator_dispatcher` PostToolUse hook lints/auto-formats each
  Write. Test method names must match the declared happy-path scenarios so
  `check_test_layers.py` (in `/test_run`) can find them.

**Compile timing (parallel-with-Dev).** Higher-layer tests reference the service
contract; if Dev hasn't built it yet, compile-level validators may fail. That is
expected — authoring targets the planned contract, and full compile + run is
deferred to `/test_run` after dev is done. Don't block authoring on missing
service code; record such gaps in the report.

## Step 3: Code review

After all autotest tasks complete, deploy `Agent({ subagent_type: "code-reviewer" })`
for the `code-review` task: read-only review of the autotest diff (correctness,
layer fit, **real infra vs mocks**, scenario coverage vs the plan). On **FAIL**,
route by severity (the board's `review → Plan` loop):

- **Minor** (wrong assertions, naming, a missing scenario) → send an `autotester`
  to fix the flagged items → re-review.
- **Fundamental** (wrong layer, infra that doesn't match the test model, scenarios
  that don't trace to acceptance) → **stop and kick back to planning** — re-run
  `/test_plan` to amend `test/test-plan.md` / `test/test-model.md`. Re-authoring
  cannot fix a wrong plan.

On **PASS**, proceed.

## Step 4: Ledger

You update the shared task status from each agent's Report (sub-agents can't). If an
agent comes to rest mid-task, inspect files on disk before deploying a continuation
(a continuation is a fresh agent — no `SendMessage` context — working from on-disk
artifacts + a precise prompt).

## Report

```
✅ Test authoring complete — <plan>

Layers authored: <integration/e2e/load/…>
Test files: <count / paths>
Code review: <PASS | PASS after N fixes>
Service gaps (await Dev): <endpoints/contracts not yet built, if any>

Next (after development is done, launched by you):
/test_run <plan>
```
