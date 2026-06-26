# Test Scope — design / contract

> Status: **revised design (2026-06-26).** Test scope is **independent of and fully
> parallel with Dev**. Both scopes consume `analytic/increment.md` directly and run
> as **two independent pipelines with separate ledgers, from t=0** — there is **no
> contract-frozen gate** and Test does **not** bind to the Dev plan. Test may make
> **different technical decisions** than Dev; divergence between test assumptions and
> the built service is reconciled at `/test_run` by `failure-analyzer`.
>
> Already in place: commands `/test_plan`, `/test_build`, `/test_run`,
> `/test_gate`; agents `test-analyst`, `test-explorer`, `autotester`,
> `failure-analyzer`, `bug-reporter`.
>
> Note: this **supersedes** the 2026-06-24 "coupled to the Dev plan / contract-frozen"
> revision, which is no longer in effect.

Test scope owns **authoring + execution/analysis** of higher-layer autotests:
**Integration / Sys-test / E2E / UI / load**. **UNIT stays with Dev scope.**

## Relationship to Dev — independent, parallel, separately owned

Test scope is an **independent sibling** of Dev: **both read `analytic/increment.md`**
(the intent) and run **in parallel from t=0**. Test does **not** bind to the Dev plan
`specs/*.md` and may make **different technical decisions** than Dev. The genuine
coupling is only at **run time**: `/test_run` executes against the built service.

Principles:

- **Bound to the increment intent, not the Dev plan.** Primary input =
  `analytic/increment.md` (FR/NFR/acceptance — the *why*). Test derives its **own**
  technical approach from the intent. `specs/*.md`, if present, is a **non-binding
  cross-reference** only.
- **Own planner, own explorer.** Test does **not** fold into `/dev_plan`. It keeps
  its own planner (`/test_plan` + `test-analyst`) and its own **`test-explorer`**
  (test landscape, not product module map). Two focused planners, **two ledgers**.
- **Parallel from t=0.** `/test_plan` + `/test_build` run **concurrently with Dev**
  with no wait on a Dev milestone — there is no contract-frozen gate.
- **Real runtime dependency.** Test **execution** (`/test_run`) depends on Dev code
  being done — that is the only genuine Test→Dev dependency (human-launched, as before).
- **Divergence reconciled at run time.** If the built service differs from what the
  tests assumed, `/test_run`'s `failure-analyzer` classifies each failure as
  **test-side** (autotester fixes) or **service-side** (bug-reporter files a bug).
  No planning-time Dev-plan comparison.
- **`/test_gate` is the only Test command that touches git** (mirrors `/merge_gate`).

## Where it sits (two independent parallel pipelines)

```
                ┌─ Dev:  /dev_plan → /smart_build → /merge_gate ─► code + unit
analytic/       │
increment.md ──►┤   (same input, no cross-binding, both spawn at t=0)
                │
                └─ Test: /test_plan → /test_build ───────────────► higher-layer autotests
                         (test-explorer +              ∥ parallel with Dev (independent)
                          test-analyst)                          │
                                                                 ▼
                                       /test_run  (after Dev code is done)
                                       Exec → Analyzer → {fix test | file bug} → /test_gate
```

## Inputs

| Input | Role |
|---|---|
| `analytic/increment.md` | **primary input (intent)** — FR / NFR / acceptance (the *why*). Test binds here and derives its own technical approach. |
| `specs/*.md` (Dev plan) | **optional, non-binding cross-reference** if it happens to exist. Test may differ from it. Divergence reconciled at `/test_run`, not at planning. |
| `test/test-landscape.md` | from the dedicated `test-explorer` — existing suites, test infra, runners, coverage gaps. |
| Dev code | consumed at **compile / run** time only — authoring binds to the intent, compilation/execution bind to the code. |

## The pyramid = context-routing taxonomy

The test pyramid is **not** a coverage mandate — it is the **tag taxonomy for
context routing**, exactly like Dev scope:

```
        E2E / UI         ← top
      Integ / Sys-test
          UNIT           ← bottom (owned by Dev)      + loadTest (separate layer)
```

`test-explorer` tags autotest areas by **test type** using trigger keywords (same
mechanism as Dev), and `context_router.py` loads the matching testing refs.

- **Reused as-is** (already in `context_router.py` + `refs/*-testing.md`):
  `integration`, `http`/`mockmvc`, `kafka`, `jdbc`, `wiremock`, `e2e`/`selenide`,
  `fixtures`, `parametrize`, `test-data`, `async`, …
- **New tags/refs**: `load` (gatling / k6 / jmeter / locust → `refs/*-load.md`),
  and `sys-test` / a distinct `ui` if needed.

## Test model (intent-driven)

The artifact between exploration and planning. It captures **how autotests are
authored in this project**, per layer:

- testing **patterns / conventions** (naming, structure, AAA/GWT);
- **test-data strategy** (builders / factories / fixtures / fakes / seed data);
- **infra** per layer (Testcontainers / WireMock / EmbeddedKafka / Playwright / …);
- **runner command** per layer.

Built from `analytic/increment.md` (intent) + `test-explorer`'s
`test/test-landscape.md` + project testing refs (and the Dev plan only as an optional
cross-reference, if present). It is the contract the autotest authors follow.
→ `test/test-model.md`.

## Commands (skills)

| Command | Flow | Does | Output |
|---|---|---|---|
| `/build_scopes` | A | **Parallel front door** (shared with Dev) — thin Conductor spawns `test-conductor` ∥ `dev-conductor` from the increment, relays bubble-up HITL. | (delegates) |
| `/test_plan` | A | reads `increment.md` + `test-explorer` landscape → **test model** → plan (autotest tasks per layer) → plan-review. No Dev-plan binding, no contract-frozen gate. | `test/test-landscape.md`, `test/test-model.md`, `test/test-plan.md` |
| `/test_build` | A | `autotester` writes tests per layer (router → testing refs) → code-review (review→Plan loop). **Runs parallel with Dev from t=0.** Relaxed compile gate during authoring (see Orchestration). | test code |
| `/test_run` | B | **Human-launched after Dev is done.** Exec → Analyzer triage → {fix-test loop \| **Bug**} | run report, `test/bugs/*.md` |
| `/test_gate` | — | HITL: show run results + bugs + diff → on approve commit/merge | git commit |

## Agents

- **`test-conductor`** — Test scope conductor (opus). Runs the authoring pipeline
  autonomously in the background under `/build_scopes` — embeds `/test_plan` +
  `/test_build`, spawning `test-explorer`/`test-analyst`/`autotester`/`code-reviewer`
  as its own children. Reads `increment.md`; interviews **bubble up** to the main
  thread. Authoring only — `/test_run` stays human-launched.
- **`test-explorer`** — dedicated explorer for Test scope. Maps the existing
  **test landscape** (suites, infra, runners, coverage gaps) and emits
  `e2e`/`ui`/`load`/`integration` **test-type tags** → `test/test-landscape.md`.
- **`test-analyst`** — consumes `increment.md` as its primary input (the Dev plan
  only as an optional cross-reference).
- **`autotester`** — writes autotests for any higher layer (the layer is selected
  per task via its `**Stack**` tags; parallel instances are `autotest_1/_2`).
- **`failure-analyzer`** (the Analyzer), **`bug-reporter`** (the Bug node) — unchanged.
- **Reused:** `plan-reviewer` (criterion 10 inverts for Test — see its scope note),
  `code-reviewer` (reviews the test diff), `validator`.
- **Models** (mirror Dev policy): authors = sonnet, reviewers/analyst = opus.

## Orchestration & sync

- **Two ledgers.** Dev owns its task ledger, Test owns its own. No shared task-id
  space and no shared artifact contract — the two pipelines are independent.
- **Authoring parallelism.** Test planning + authoring start at **t=0**, in parallel
  with Dev, gated on nothing in Dev (no contract-frozen milestone).
- **Compile-coupling** (the real boundary). Higher-layer test code (`@SpringBootTest`,
  references to product classes/endpoints) needs product symbols to compile.
  Resolution:
  - **Relaxed compile gate** for test files during authoring: format/lint apply
    (`spotless`/`eslint`/`ruff`), but **full `maven_compile` is deferred** to the
    end of `/test_build` and to `/test_run`. So referencing not-yet-built product
    code never blocks authoring.
- **Execution dependency.** `/test_run` is human-launched once Dev code is done —
  no automatic Dev→Test signal.

## Hooks / validators

- **`check_test_layers.py` — reused.** In Test scope the Integration / Sys / E2E /
  load layers are **live** (not Skipped), so the generic checker verifies them
  directly.
- **`validate_test_plan.py`** — like `validate_plan.py`, but the layers the test
  model declares are mandatory; no `unit-tests` task (UNIT is Dev's).
- Autotest authors get the `validator_dispatcher.py` PostToolUse hook, with the
  **relaxed compile gate** above during authoring.
- Static analysis (`Spotbugs`, `Jacoco` coverage, `Sonar`) → inputs to
  `failure-analyzer`.

## Flow B — `/test_run`: Exec → Analyzer → {fix test | file bug}

**Human-launched** once development is done (the suite was authored in parallel).

- **Exec** — run the authored suite per layer (runner commands from the test model).
- **Analyzer** — on a failure, triage from the **test-run logs** (+ Jacoco coverage
  + static analysis; **prod logs + OBS** when a running/staging env exists —
  greenfield → skipped) and **classify the root cause**:
  - **test-side defect** → delegate the fix to `autotester` → **re-run** (loop until
    green or reclassified);
  - **service-side defect** → `bug-reporter` writes a markdown bug to `test/bugs/`;
  - **cannot classify confidently** → file a bug **and** flag for a human; never
    silently loop-fix.
- Filing a bug into a real tracker is a human decision at `/test_gate` (no
  auto-filing in v1).

## Artifacts

| Path | Producer | Tracked |
|---|---|---|
| `test/test-landscape.md` | `test-explorer` | gitignored (like `explore/module-map.md`) |
| `test/test-model.md` | `test-analyst` | committed |
| `test/test-plan.md` | `/test_plan` | committed |
| `test/bugs/*.md` | `bug-reporter` | committed |
| test code (`*IT`, `e2e/`, `load/`) | `autotester` | committed |

## Decisions (revised)

1. **Bound to `analytic/increment.md`** (intent), **not** to the Dev plan. Test
   derives its own technical approach; `specs/*.md` is an optional cross-reference.
2. **Separate Test planner + dedicated `test-explorer`** — Test is **not** folded
   into `/dev_plan`.
3. **Two independent ledgers**, no shared task space and **no contract-frozen
   milestone** — the pipelines do not synchronize on a Dev artifact.
4. **Test planning + authoring ∥ Dev from t=0**; **Test run depends on Dev code done**
   (human-launched `/test_run`, no automatic signal).
5. **Dev/Test divergence** is reconciled at `/test_run` by `failure-analyzer`
   (test-side vs service-side), **not** at planning time.
6. **Relaxed compile gate** for test files during authoring; full compile at
   end of `/test_build` / at `/test_run`.
7. **load** = model-driven layer (included when the increment has perf/NFR needs),
   not blanket-mandatory — like E2E is included only when there's UI.
8. **prod logs / OBS** = pluggable Analyzer inputs; absent on greenfield.
9. **Bug** = markdown reports in `test/bugs/`, human-filed into a tracker at `/test_gate`.
10. **Reuse** the existing `context_router.py` + `refs/*-testing.md`; add only the
    missing `load` / `sys-test` sections + refs.
11. **Triage ambiguity** — when the Analyzer can't confidently classify: file a bug
    **and** flag for a human; never silently loop-fix.
