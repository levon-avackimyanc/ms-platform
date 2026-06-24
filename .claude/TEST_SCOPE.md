# Test Scope — design / contract

> Status: **revised design (2026-06-24).** Test scope is now **coupled to and
> parallel with Dev**, not an independent fragment. Coupling is via the **Dev plan
> (`specs/*.md`)** as the authoritative technical contract; Dev and Test run as
> **two parallel pipelines with separate ledgers**, synchronized by the plan + a
> **"contract frozen"** milestone.
>
> Already in place: commands `/test_plan`, `/test_build`, `/test_run`,
> `/test_gate`; agents `test-analyst`, `autotester`, `failure-analyzer`,
> `bug-reporter`. **Changes introduced by this revision** (pending implementation)
> are marked **⟳**.

Test scope owns **authoring + execution/analysis** of higher-layer autotests:
**Integration / Sys-test / E2E / UI / load**. **UNIT stays with Dev scope.**

## Relationship to Dev — coupled, parallel, separately owned

The previous design made Test an independent sibling of Dev: both read only
`analytic/increment.md`, blind to each other. That produced false service-side
bugs when the increment diverged from what Dev actually built (e.g. a deferred
acceptance criterion, or API paths refined during planning). This revision makes
Test **dependent on Dev's plan** while keeping the two pipelines **parallel and
separately owned**.

Principles:

- **Dependent on the Dev plan, not independent.** ⟳ Primary **technical** input =
  `specs/*.md` — the agreed contract: real endpoints, error shapes, DTOs, and
  **deferral decisions**. `analytic/increment.md` stays the **intent / why**; the
  Dev plan is the **what/how** the autotests bind to.
- **Own planner, own explorer.** ⟳ Test does **not** fold into `/plan_w_team`. It
  keeps its own planner (`/test_plan` + `test-analyst`) and its own
  **`test-explorer`** (test landscape, not product module map). Two focused
  planners, **two ledgers**.
- **Parallel authoring.** `/test_plan` + `/test_build` run **concurrently with the
  Dev build**, reading the plan as it stabilizes.
- **Real runtime dependency.** Test **execution** (`/test_run`) depends on Dev code
  being done — that is the genuine Test→Dev dependency (human-launched, as before).
- **Sync = "contract frozen" milestone.** ⟳ Test authoring tasks are gated on the
  Dev plan's contract being **frozen** (plan passed `plan-reviewer`), **not** on
  individual Dev task-ids. If the contract changes afterward, Test re-plans only
  the affected slice.
- **Divergence caught early.** Because Test binds to the plan, an
  **increment ↔ plan** mismatch (e.g. a deferred AC) surfaces at `/test_plan` as a
  **spec-divergence to reconcile** — not as a false service-side bug at `/test_run`.
- **`/test_gate` is the only Test command that touches git** (mirrors `/merge_gate`).

## Where it sits (two coupled pipelines)

```
                ┌─ Dev:  /plan_w_team → /smart_build → /merge_gate ─► code + unit
analytic/       │                          │
increment.md ──►┤                          │ specs/*.md  (frozen contract)
                │                          ▼  (read, not duplicated)
                └─ Test: /test_plan → /test_build ───────────────► higher-layer autotests
                         (test-explorer +              ∥ parallel with Dev build
                          test-analyst)                          │
                                                                 ▼
                                       /test_run  (after Dev code is done)
                                       Exec → Analyzer → {fix test | file bug} → /test_gate
```

## Inputs ⟳

| Input | Role |
|---|---|
| `specs/*.md` (Dev plan) | **primary technical contract** — endpoints, error shapes, DTOs, deferrals. Autotests bind here. |
| `analytic/increment.md` | **intent** — FR / NFR / acceptance (the *why*). Cross-checked vs the plan; mismatch → spec-divergence, surfaced at `/test_plan`. |
| `test/test-landscape.md` ⟳ | from the dedicated `test-explorer` — existing suites, test infra, runners, coverage gaps. |
| Dev code | consumed at **compile / run** time only — authoring binds to the contract, compilation/execution bind to the code. |

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

## Test model (now plan-aware) ⟳

The artifact between exploration and planning. It captures **how autotests are
authored in this project**, per layer:

- testing **patterns / conventions** (naming, structure, AAA/GWT);
- **test-data strategy** (builders / factories / fixtures / fakes / seed data);
- **infra** per layer (Testcontainers / WireMock / EmbeddedKafka / Playwright / …);
- **runner command** per layer.

⟳ Built from `analytic/increment.md` (intent) + **`specs/*.md` (the frozen
contract)** + `test-explorer`'s `test/test-landscape.md` + project testing refs.
It is the contract the autotest authors follow. → `test/test-model.md`.

## Commands (skills)

| Command | Flow | Does | Output |
|---|---|---|---|
| `/test_plan` | A | ⟳ reads `specs/*.md` + `increment.md` + `test-explorer` landscape → **test model** → plan (autotest tasks per layer, `blockedBy` = **contract-frozen**) → plan-review. Flags increment↔plan divergence. | `test/test-landscape.md`, `test/test-model.md`, `test/test-plan.md` |
| `/test_build` | A | `autotester` writes tests per layer (router → testing refs) → code-review (review→Plan loop). **Runs parallel with the Dev build.** ⟳ relaxed compile gate during authoring (see Orchestration). | test code |
| `/test_run` | B | **Human-launched after Dev is done.** Exec → Analyzer triage → {fix-test loop \| **Bug**} | run report, `test/bugs/*.md` |
| `/test_gate` | — | HITL: show run results + bugs + diff → on approve commit/merge | git commit |

## Agents

- **⟳ New: `test-explorer`** — dedicated explorer for Test scope. Maps the existing
  **test landscape** (suites, infra, runners, coverage gaps) and emits
  `e2e`/`ui`/`load`/`integration` **test-type tags** → `test/test-landscape.md`.
  Replaces the previous reuse of the Dev `explorer` for Test.
- **⟳ `test-analyst`** — now consumes **`specs/*.md`** alongside `increment.md`.
- **`autotester`** — writes autotests for any higher layer (the layer is selected
  per task via its `**Stack**` tags; parallel instances are `autotest_1/_2`).
- **`failure-analyzer`** (the Analyzer), **`bug-reporter`** (the Bug node) — unchanged.
- **Reused:** `plan-reviewer` (criterion 10 inverts for Test — see its scope note),
  `code-reviewer` (reviews the test diff), `validator`.
- **Models** (mirror Dev policy): authors = sonnet, reviewers/analyst = opus.

## Orchestration & sync ⟳

- **Two ledgers.** Dev owns its task ledger, Test owns its own. No shared task-id
  space; the coupling is the **plan artifact**, not a merged ledger.
- **Authoring parallelism.** Test authoring tasks are `blockedBy` the
  **contract-frozen** milestone (Dev plan passed `plan-reviewer`), not by Dev code
  tasks — so Test authoring proceeds concurrently with the Dev build.
- **Compile-coupling** (the real boundary). Higher-layer test code (`@SpringBootTest`,
  references to product classes/endpoints) needs product symbols to compile.
  Resolution:
  - ⟳ **Relaxed compile gate** for test files during authoring: format/lint apply
    (`spotless`/`eslint`/`ruff`), but **full `maven_compile` is deferred** to the
    end of `/test_build` and to `/test_run`.
  - **Fine-grained:** a test slice whose contract is stable can be authored even
    before its product code lands; it compiles once the matching Dev task is done.
- **Execution dependency.** `/test_run` is human-launched once Dev code is done —
  no automatic Dev→Test signal.
- **Re-plan on contract change.** If the Dev plan changes after freeze, `/test_plan`
  re-plans only the affected autotest slice (the rest stands).

## Hooks / validators

- **`check_test_layers.py` — reused.** In Test scope the Integration / Sys / E2E /
  load layers are **live** (not Skipped), so the generic checker verifies them
  directly.
- **`validate_test_plan.py`** — like `validate_plan.py`, but the layers the test
  model declares are mandatory; no `unit-tests` task (UNIT is Dev's).
- Autotest authors get the `validator_dispatcher.py` PostToolUse hook, ⟳ with the
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
| `test/test-landscape.md` ⟳ | `test-explorer` | gitignored (like `explore/module-map.md`) |
| `test/test-model.md` | `test-analyst` | committed |
| `test/test-plan.md` | `/test_plan` | committed |
| `test/bugs/*.md` | `bug-reporter` | committed |
| test code (`*IT`, `e2e/`, `load/`) | `autotester` | committed |

## Decisions (revised)

1. ⟳ **Coupling via the Dev plan** `specs/*.md` (not increment-only). `increment.md`
   stays the intent; the plan is the technical contract autotests bind to.
2. ⟳ **Separate Test planner + dedicated `test-explorer`** — Test is **not** folded
   into `/plan_w_team`.
3. ⟳ **Two ledgers**, synchronized by the plan + a **"contract frozen"** milestone
   (not a shared ledger / merged task space).
4. ⟳ **Test authoring ∥ Dev build**; **Test run depends on Dev code done**
   (human-launched `/test_run`, no automatic signal).
5. ⟳ **increment ↔ plan divergence** is handled at `/test_plan` as a
   **spec-reconcile** signal, not as a service-side bug at run time.
6. ⟳ **Relaxed compile gate** for test files during authoring; full compile at
   end of `/test_build` / at `/test_run`.
7. **load** = model-driven layer (included when the increment has perf/NFR needs),
   not blanket-mandatory — like E2E is included only when there's UI.
8. **prod logs / OBS** = pluggable Analyzer inputs; absent on greenfield.
9. **Bug** = markdown reports in `test/bugs/`, human-filed into a tracker at `/test_gate`.
10. **Reuse** the existing `context_router.py` + `refs/*-testing.md`; add only the
    missing `load` / `sys-test` sections + refs.
11. **Triage ambiguity** — when the Analyzer can't confidently classify: file a bug
    **and** flag for a human; never silently loop-fix.
