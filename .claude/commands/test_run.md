---
allowed-tools: Agent, Read, Bash, Glob, Grep
description: Test scope run + triage (Flow B). Human-launched after development is done. Runs the authored suite per layer; on failure, failure-analyzer classifies test-side vs service-side, then test-side is fixed by autotester (re-run loop) and service-side/unclear become bug reports. Ends pointing at /test_gate.
argument-hint: [test-plan file, e.g. test/test-plan.md]
model: opus
---

# Test Run (Flow B)

Run the authored autotests and triage failures. **You launch this yourself, once
development is done** — there is no automatic Dev→Test trigger. The suite was
written in parallel by `/test_build`. See `.claude/TEST_SCOPE.md`.

You are the **orchestrator** and **own the task ledger**. You run the suite, then
delegate triage/fix/bug to sub-agents (they have no Task tools).

## Step 0: Load

`$ARGUMENTS` = plan path (default `test/test-plan.md`). Read it and
`test/test-model.md`. The runner commands are the `**Runner command**` of each
**live** layer in `## Test Infrastructure (User-Declared)`.

## Step 1: Exec

For each live layer, run its declared runner verbatim (`Bash`), capturing output:

```bash
<runner command from the layer block>   # e.g. mvn verify -Dsurefire.skip=true -P integration
```

Then confirm the tests actually ran (not silently skipped):

```bash
uv run --script .claude/hooks/validators/check_test_layers.py --plan <plan>
```

- **All green** and `check_test_layers` PASS → go to Step 4 (success).
- Any **failures** (or executed-count < declared scenarios) → Step 2.

## Step 2: Analyze

Deploy `Agent({ subagent_type: "failure-analyzer" })` with the run logs, the plan,
and `test/test-model.md`. It returns, per failed test, a verdict:
**service-side** | **test-side** | **unclear** (with evidence).

## Step 3: Route each verdict (the board's Analyzer → {fix | Bug})

- **test-side** → deploy `Agent({ subagent_type: "autotester" })` to fix the
  flagged tests → **re-run the affected layer** (Step 1). Bound the loop to **3
  iterations** per layer; if still failing as test-side, escalate to the human.
- **service-side** → deploy `Agent({ subagent_type: "bug-reporter" })` to write a
  markdown bug to `test/bugs/`. Do **not** touch product code (that is Dev's job).
- **unclear** → `bug-reporter` files a bug **and flags it for the human**; never
  silently loop-fix an unclassified failure.

A run can produce both fixed-and-green tests and filed bugs — that is expected when
some failures are test-side and others are service-side.

## Step 4: Report

```
## Test Run Complete — <plan>

Layers run: <layer: passed/failed, per runner>
check_test_layers: <PASS | FAIL>
Test-side fixes: <N tests fixed by autotester, re-run green>
Bugs filed: <test/bugs/*.md> (service-side: X, unclear/human: Y)
Escalations: <anything still failing after the loop bound>

Next:
- /test_gate <plan>   (HITL: review run + bugs, then commit/merge)
```

## Hard limits

- **No product-code edits.** Service defects become bugs, not fixes — fixing the
  service is Dev scope. Only `autotester` touches test code (test-side fixes).
- **No git.** Commit/merge is `/test_gate`.
- Don't mark a run green if `check_test_layers` failed or executed-count < declared
  scenarios — that means tests didn't really run.
