---
description: Test scope planning. Builds test/test-model.md from increment.md (intent — primary input) via test-analyst + test-explorer, and test/test-plan.md with autotest tasks per layer (integration/sys/e2e/ui/load), validates (validate_plan --scope test) and runs plan-reviewer. Runs fully in parallel with Dev from t=0, independently; specs/*.md — optional cross-reference, not a contract. UNIT — not here.
argument-hint: "[orchestration prompt]"
model: opus
disallowed-tools: EnterPlanMode
hooks:
  Stop:
    - hooks:
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_new_file.py
            --directory test
            --extension .md
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_file_contains.py
            --directory test
            --extension .md
            --contains '## Task Description'
            --contains '## Objective'
            --contains '## Relevant Files'
            --contains '## Step by Step Tasks'
            --contains '## Test Infrastructure (User-Declared)'
            --contains '## Acceptance Criteria'
            --contains '## Team Orchestration'
            --contains '### Team Members'
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_plan.py
            --directory test
            --extension .md
            --scope test
            --team-dir $CLAUDE_PROJECT_DIR/.claude/agents
---

# Test Plan

Build the **Test scope** plan: derive a *test model* (how we author autotests in
this project) and a plan of **autotest tasks per layer**. Test scope is
**independent of and fully parallel with Dev** — it binds to `analytic/increment.md`
(intent), not to the Dev plan, and runs from t=0. The run/triage half is the separate
`/test_run`. See `.claude/TEST_SCOPE.md` for the full contract.

## Variables

- **INCREMENT_FILE** = `analytic/increment.md` — the **intent** input (FR/NFR/
  acceptance — the *why*). If missing → stop and ask to run `/analyze` first.
- **DEV_PLAN** = `specs/*.md` — (optional) the Dev plan, if it happens to exist.
  **Not a binding contract** — Test scope plans independently from the increment and
  may make different technical decisions. Use it, if present, only as a non-binding
  cross-reference. Divergence between test assumptions and what Dev built is
  reconciled at `/test_run` by `failure-analyzer`, not here.
- **TEST_LANDSCAPE** = `test/test-landscape.md` — existing test landscape from
  `test-explorer` (suites, infra, runners, coverage gaps).
- **ORCHESTRATION_PROMPT** = `$1` — (optional) guidance for layer/team/task structure.
- **TEST_MODEL** = `test/test-model.md` — authoring model (produced in Step 1).
- **PLAN_OUTPUT** = `test/test-plan.md`.
- **TEAM_MEMBERS** = `.claude/agents/**/*.md`.

## Instructions

- **PLANNING ONLY** — no product code, no test code, no git. Read-only helper
  agents (`test-explorer`, `test-analyst`, `plan-reviewer`) may be spawned.
- **Bind to the increment intent.** Autotests bind to `analytic/increment.md` — its
  FR/NFR, acceptance criteria and behaviors. Test scope derives its **own** technical
  approach from that intent; it does **not** depend on, and may differ from, the Dev
  plan's endpoints/shapes. (The concrete service paths/shapes are resolved when the
  tests compile/run against real code at `/test_run`.)
- **Divergence is reconciled at run time, not at planning.** If the running service
  later differs from what the tests assumed, `/test_run`'s `failure-analyzer`
  classifies each failure as **test-side** (autotester fixes) or **service-side**
  (bug-reporter files a bug). Do not gate planning on a Dev-plan comparison.
- **UNIT is NOT in scope** — it is owned by Dev. This plan declares only the higher
  layers (**Integration / Sys / E2E / UI / Load**). Do not add a `unit-tests` task
  or a live Unit Layer (if you mention Unit at all, mark it `Skipped — owned by Dev`).
- **Layers come from the test model** — include a layer only if `test/test-model.md`
  marks it applicable (Integration/Sys almost always; E2E/UI when there's UI; Load
  when the increment has perf/NFR). Every applicable layer needs a live block in
  `## Test Infrastructure (User-Declared)` and a matching autotest task.
- **Tasks**: one autotest task per layer — `integration-tests` / `sys-tests` /
  `e2e-tests` / `ui-tests` / `load-tests` (only the applicable ones), each assigned
  to the **`autotester`** agent, then a `code-review` task (`code-reviewer`), then a
  final `validate-all` task (`validator`). The combined `write-tests` task is forbidden.
- **`**Stack**` field** drives context routing (same catalog as Dev). Use the
  trigger keywords for the layer, e.g. `Java testcontainers integration mockmvc`,
  `Playwright e2e ui`, `k6 load test`. Reuse the tags `test-explorer` wrote into
  `test/test-landscape.md`.
- **Relevant Files**: list only files that **already exist** (increment, test-model,
  existing test config/dirs). Source-under-test may not exist yet (parallel Dev) —
  reference it in task bodies, not in Relevant Files. New test files go under
  `### New Files`.

## Workflow

0. **Resolve inputs** — confirm `analytic/increment.md` exists (`ls`); read it
   (+ `original_task.txt`, `review-report.json` for context). If absent → stop and
   ask for `/analyze`. This is the **only required input** — Test scope plans from the
   increment intent and runs fully in parallel with Dev (no dependency on `specs/*.md`,
   no contract-frozen wait). A Dev plan, if present, may be skimmed as a non-binding
   cross-reference only. State you are in Test-scope planning.
1. **Test model (analytic) — first.** Spawn **`test-analyst`**
   (`subagent_type: "test-analyst"`, foreground): from the **increment** (+ build
   files + landscape if present) it decides the **applicable layers** and writes
   `test/test-model.md` (patterns/data/infra/runner per layer). If it returns Open
   questions, resolve them (`AskUserQuestion`) and re-run. *(Board order: analytic → Expl.)*
2. **Map the test landscape — then.** Spawn **`test-explorer`** agent(s)
   (`subagent_type: "test-explorer"`, parallel via `ASSIGNED_AREAS` for a large
   suite) to map the existing test landscape and tag autotest areas by test type for
   the layers the model declared → `test/test-landscape.md`; reuse if fresh. These
   tags become each autotest task's `**Stack**`, and its coverage gaps prioritise
   what to author.
3. **Design the plan** — from the test model, decide layers, the autotest tasks per
   layer, dependencies, and the `## Test Infrastructure (User-Declared)` blocks
   (live higher layers, each with Files glob / Infra signature / ≥1 named scenario /
   Runner command / Realism rationale, copied from the test model).
4. **Write `test/test-plan.md`** in the format below (triggers the Stop hooks).
5. **Validate + review** —
   ```bash
   uv run --script .claude/hooks/validators/validate_plan.py --file test/test-plan.md --scope test --team-dir .claude/agents
   ```
   then spawn `plan-reviewer` (`subagent_type: "plan-reviewer"`) with a prompt that
   **states the plan is Test scope** (so criterion 10 inverts — see plan-reviewer
   scope note). On structural fail or review FAIL → fix or stop. On pass → report.
6. **Report** (see below).

## Plan Format

Follow this EXACT structure (replace `<...>`):

```md
# Test Plan: <feature>

## Task Description
<what is being tested and why, from the increment>

## Objective
<what "tested" means when this plan is complete — which layers, which behaviors>

## Relevant Files
<existing files only: analytic/increment.md (primary), test/test-model.md, test/test-landscape.md, existing test config/dirs. specs/*.md only if present, as a non-binding cross-reference. Why each.>

### New Files
<the autotest files to create, per layer>

## Test Model
<one line: see test/test-model.md; list the applicable layers it declared>

## Test Infrastructure (User-Declared)
<one live block per applicable higher layer — NO live Unit layer>

### Integration Layer (<stack>)
- **Status:** Active
- **Files glob:** `<glob locating integration test files>`
- **Infra signature (regex, ≥1 match per file):** `<e.g. @Testcontainers|import org\.testcontainers>`
- **Happy-path scenarios (≥1 named):**
  - `<ClassName#method | describe>it | path::test_name — one per user-facing case>`
- **Runner command:** `<exact command, e.g. mvn verify -P integration>`
- **Realism rationale:** `<why this is the most realistic setup this repo can run>`

<!-- add ### E2E Layer / ### Load Layer / ### Sys Layer blocks only if applicable.
     Unit, if mentioned, must be: ### Unit Layer (<stack>) — Status: Skipped — owned by Dev -->

## Step by Step Tasks

### 1. <Integration Tests>
- **Task ID**: integration-tests
- **Depends On**: none
- **Assigned To**: <autotester member name>
- **Agent Type**: autotester
- **Stack**: <e.g. Java testcontainers integration mockmvc>
- **Parallel**: <true/false>
- Write integration tests per `test/test-model.md` and the Integration Layer block; use real infra; test names = declared scenarios.

<!-- repeat per applicable layer: e2e-tests / load-tests / sys-tests / ui-tests, Agent Type: autotester -->

### N-1. <Code Review>
- **Task ID**: code-review
- **Depends On**: <all autotest task IDs>
- **Assigned To**: <code-reviewer member name>
- **Agent Type**: code-reviewer
- **Stack**: <full stack keywords for the reviewed diff>
- **Parallel**: false
- Read-only review of the autotest diff (correctness, layer fit, real infra vs mocks, scenario coverage).

### N. <Final Validation>
- **Task ID**: validate-all
- **Depends On**: <all autotest + code-review IDs>
- **Assigned To**: <validator member name>
- **Agent Type**: validator
- **Stack**: <full stack keywords>
- **Parallel**: false
- For each live layer, run its declared Runner command and confirm tests actually ran (count ≥ declared scenarios); run `check_test_layers.py`; verify acceptance.

## Acceptance Criteria
<measurable: every increment acceptance criterion is covered by ≥1 autotest scenario at the right layer>

## Team Orchestration
<orchestrator deploys autotester(s)/code-reviewer/validator via the Agent tool; owns the task ledger; see dev_plan Team Orchestration for the shared mechanics>

### Team Members
- Autotester
  - Name: <unique>
  - Role: <layer(s) it authors>
  - Agent Type: autotester
- Code-Reviewer
  - Name: <unique>
  - Agent Type: code-reviewer
- Validator
  - Name: <unique>
  - Agent Type: validator

## Validation Commands
<commands to validate, e.g. the per-layer Runner commands>

## Notes
<optional>
```

## Report

```
✅ Test Plan Created

File: test/test-plan.md
Test model: test/test-model.md
Layers: <applicable layers>
Autotest tasks: <list>

Next: run the build in parallel with Dev:
/test_build test/test-plan.md
```
