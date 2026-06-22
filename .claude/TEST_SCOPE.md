# Test Scope — design / contract

> Status: **implemented**. Mirrors Dev scope conventions and reuses the context
> router + testing refs. All four commands (`/test_plan`, `/test_build`,
> `/test_run`, `/test_gate`) and the new agents (`test-analyst`, `autotester`,
> `failure-analyzer`, `bug-reporter`) exist; this doc is their reference.

Test scope owns **authoring + execution/analysis** of higher-layer autotests:
**Integration / Sys-test / E2E / UI / load**. **UNIT stays with Dev scope.**

## Where it sits

```
Analytic        Dev scope                     Test scope (this)
/analyze    →   /plan_w_team → /smart_build    /test_plan → /test_build (author)
increment.md    code + unit tests              test model + autotests
                     ▲                               │
                     └─────────── parallel ──────────┘
                                  (dev done, human-launched) ▼
                       /test_run: Exec → Analyzer → {fix test | file bug} → /test_gate
```

- **Input** = `analytic/increment.md` (the board's `func_req.md` = the same
  increment). **Dev code is NOT required to start** — autotests are authored **in
  parallel with development** from the increment + test model. Dev code is
  consumed only at **run time**.
- **Timing**: `/test_plan` and `/test_build` (authoring) run **concurrently with
  Dev scope**. The run is a **separate, human-launched command `/test_run`**
  (Exec → Analyzer → …), started once development is done.
- **`/test_gate` is the only command that touches git** (mirrors `/merge_gate`).

## The pyramid = context-routing taxonomy

The test pyramid is **not** a coverage mandate — it is the **tag taxonomy for
context routing**, exactly like Dev scope:

```
        E2E / UI         ← top
      Integ / Sys-test
          UNIT           ← bottom (owned by Dev)      + loadTest (separate layer)
```

`explorer` tags autotest areas by **test type** using trigger keywords (same
mechanism as Dev), and `context_router.py` loads the matching testing refs.

- **Reused as-is** (already in `context_router.py` + `refs/*-testing.md`):
  `integration`, `http`/`mockmvc`, `kafka`, `jdbc`, `wiremock`, `e2e`/`selenide`,
  `fixtures`, `parametrize`, `test-data`, `async`, …
- **New tags/refs to add**: `load` (gatling / k6 / jmeter / locust → new
  `refs/*-load.md`), and `sys-test` / a distinct `ui` if needed.

## Тестовая модель (test model)

The artifact between exploration and planning. It captures **how autotests are
authored in this project**, per layer:

- testing **patterns / conventions** (naming, structure, AAA/GWT);
- **test-data strategy** (builders / factories / fixtures / fakes / seed data);
- **infra** per layer (Testcontainers / WireMock / EmbeddedKafka / Playwright / …);
- **runner command** per layer.

Built from the increment + the Dev code + `explorer` tags + project testing refs.
It is the contract the autotest authors follow and the source of the plan's
`## Test Infrastructure (User-Declared)` section. → `test/test-model.md`.

## Commands (skills)

| Command | Flow | Does | Output |
|---|---|---|---|
| `/test_plan` | A | test-analyst → `explorer` (tag autotest areas) → **test model** → plan (autotest tasks per layer) → plan-review | `test/test-model.md`, `test/test-plan.md` |
| `/test_build` | A | `autotester` writes tests per layer (router → testing refs) → code-review (review→Plan loop: minor → re-author, fundamental → back to `/test_plan`). **Runs parallel with Dev.** | test code |
| `/test_run` | B | **Human-launched after dev is done.** Exec → Analyzer triage → {fix-test loop \| **Bug**} | run report, `test/bugs/*.md` |
| `/test_gate` | — | HITL: show run results + bugs + diff → on approve commit/merge | git commit |

## Agents

- **New:** `test-analyst` (test model), **`autotester`** (writes autotests for any
  higher layer — the layer is selected per task via its `**Stack**` tags; parallel
  instances are the board's `autotest_1/_2`), `failure-analyzer` (the Analyzer),
  `bug-reporter` (the Bug node).
- **Reused:** `explorer` (now also emits `e2e`/`ui`/`load` test-type tags),
  `plan-reviewer` (criterion 10 inverts for Test scope — see its scope note),
  `code-reviewer` (reviews the test diff), `validator`.
- **Models** (mirror Dev policy): authors = sonnet, reviewers/analyst = opus
  (TBD per agent).

## Hooks / validators

- **`check_test_layers.py` — reused.** In Test scope the Integration / Sys / E2E /
  load layers are **live** (not Skipped), so the existing generic checker verifies
  them directly. (This is the payoff for not hard-coding integration into Dev.)
- **New `validate_test_plan.py`** — like `validate_plan.py`, but the layers the
  test model declares are mandatory; no `unit-tests` task here (UNIT is Dev's).
- Autotest authors get the same `validator_dispatcher.py` PostToolUse hook (they
  write code: spotless/eslint/ruff auto-format, compile, etc.).
- Static analysis from the board (`Spotbugs`, `Jacoco` coverage, `Sonar`) →
  inputs to `failure-analyzer`.

## Flow B — `/test_run`: Exec → Analyzer → {fix test | file bug}

**Human-launched** once development is done (the suite was authored in parallel).

- **Exec** — run the authored suite per layer (runner commands from the test model).
- **Analyzer** — on a failure, triage from the **test-run logs** (+ Jacoco
  coverage + static analysis Spotbugs/Sonar; **prod logs + OBS** when a
  running/staging env exists — greenfield → skipped) and **classify the root cause**:
  - **defect on the test side** → delegate the fix to the `autotester` sub-agent
    → **re-run** (loop until green or reclassified as service-side);
  - **defect on the service side** → `bug-reporter` writes a markdown bug to
    `test/bugs/`;
  - **cannot classify confidently** → file a bug **and flag for a human**; never
    silently loop-fix.
- Filing a bug into a real tracker is a human decision at `/test_gate` (no
  auto-filing in v1).

## Artifacts

| Path | Producer | Tracked |
|---|---|---|
| `test/test-model.md` | test-analyst | committed |
| `test/test-plan.md` | `/test_plan` | committed |
| `test/bugs/*.md` | bug-reporter | committed (TBD) |
| test code (`*IT`, `e2e/`, `load/`) | autotest agents | committed |

## Decisions (resolved)

1. **load** = model-driven layer (included when the increment has perf/NFR needs),
   not blanket-mandatory — same way E2E is included only when there's UI.
2. **prod logs / OBS** = pluggable Analyzer inputs; absent on greenfield.
3. **Bug** = markdown reports in `test/bugs/`, human-filed into a tracker at `/test_gate`.
4. **Reuse** the existing `context_router.py` + `refs/*-testing.md`; add only the
   missing `load` / `sys-test` sections + refs.
5. **Four commands**: `/test_plan`, `/test_build` (authoring, parallel with Dev),
   `/test_run` (human-launched after dev done), `/test_gate`.
6. **"dev done" trigger** = the human launches `/test_run` explicitly. No automatic
   Dev→Test signal.
7. **Triage ambiguity** — when the Analyzer can't confidently classify test-side
   vs service-side: file a bug **and** flag for a human; never silently loop-fix.
