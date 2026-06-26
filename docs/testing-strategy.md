# Testing Strategy

Every plan created by `/dev_plan` includes a mandatory testing strategy following the **80/15/5 test pyramid**, plus a machine-verifiable `## Test Infrastructure (User-Declared)` section that downstream gates enforce.

## Test Pyramid

```
         ┌─────────┐
         │  UI E2E │  5%   — critical user flows only (optional)
         │  (5%)   │
         ├─────────┤
         │  Integ  │  15%  — happy-path mandatory, no opt-out
         │  (15%)  │
         ├─────────┤
         │         │
         │  Unit   │  80%  — service logic, utils, components
         │  (80%)  │
         └─────────┘
```

| Layer | Ratio | Mandatory? | What to test | Tools (project chooses) |
|-------|-------|------------|-------------|------------------------|
| **Unit** | 80% | Yes | Service logic, utility functions, component rendering, hooks | JUnit + AssertJ, Jest, Pytest |
| **Integration / API** | 15% | **Yes — never opted out** | Controller endpoints, repository tests, API contracts (≥1 happy-path per affected user-facing endpoint or use-case) | Project's choice — Testcontainers, EmbeddedKafka, H2, MockMvc, httpx + asgitransport, fakeredis, etc. |
| **UI E2E** | 5% | Only when frontend is detected in the project | Login + action, full CRUD flow, cross-page navigation | Project's choice — Selenide, Playwright, Cypress |

**Hard rules:**
- **Integration happy-path is mandatory** for every plan. There is no "internal-only refactor" opt-out — if the change preserves a behavior, that behavior is the integration scenario.
- **E2E is the only optional layer**, and only when the project does not have a frontend.
- The single combined `write-tests` task is forbidden — test work is split into per-layer tasks (`unit-tests`, `integration-tests`, optional `e2e-tests`).

## Plan as a Self-Checking Contract

Each plan ships with a `## Test Infrastructure (User-Declared)` section — filled in during the **Test Infra Interview** (`/dev_plan` Workflow Step 4.5) — that names, per stack and per layer, exactly:

- **Files glob** — where the tests live in this repo.
- **Infra signature (regex)** — what proves the chosen infrastructure is actually used (e.g., `@Testcontainers`, `@EmbeddedKafka`, `import.*playwright`).
- **Happy-path scenarios** — named test methods/descriptions that must exist.
- **Runner command** — the exact command this repo uses to run the most realistic tests for this layer.
- **Realism rationale** — one sentence on why this is the highest realism tier this repo offers today.

This section is the contract. Three gates enforce it:

| Gate | When | What it verifies |
|------|------|------------------|
| `validate_plan.py` Check 9 + 10 | At plan save (`/dev_plan` Stop hook) | Section present, every non-Skipped layer has all required fields, Integration is never Skipped |
| `check_test_layers.py` | After test tasks complete (`/smart_build` Step 4.5) | `Files glob` resolves to ≥1 file; `Infra signature` regex matches in every file; declared scenarios exist as test methods (fuzzy grep); anti-mock heuristic for integration files (WARN-only) |
| `validator` agent | Final `validate-all` task | Executes each layer's declared `Runner command` verbatim; parses output for executed-tests count; FAILs if executed count is less than declared scenarios count |

## How "Smart Infra" Works

The framework does **not** keep a hardcoded list of test libraries. It cannot tell you "use Testcontainers" — but the planner (Opus) can:

1. **Step 4 of `/dev_plan`** — planner reads `pom.xml` / `pyproject.toml` / `package.json` and figures out what test infrastructure the repo already supports and **how the most realistic tests are actually run today** (Surefire vs. Failsafe vs. a custom Maven profile vs. `pytest -m integration` vs. a Gradle task vs. a CI script vs. anything else).
2. **Step 4.5 of `/dev_plan`** — planner asks the user (`AskUserQuestion`):
   - Q1 (always): which integration happy-path scenarios, on which infra (suggesting what was observed)?
   - Q2 (only if frontend detected): include E2E? Which runner?
   - Q3 (only if Q1 was vague): which exact scenarios?
3. **Plan generation** — planner translates user answers into a machine-verifiable `## Test Infrastructure (User-Declared)` section. If the user said "Testcontainers PostgreSQL" the planner writes `Infra signature: @Testcontainers|import org\.testcontainers`. If the user said "EmbeddedKafka", the planner writes `Infra signature: @EmbeddedKafka`. The framework knows nothing about either library — Opus does.

This means a project on Go, Rust, .NET, Erlang, or anything else works without code changes here. Just describe it in the plan.

## Mandatory Per-Layer Tasks

Every plan splits test work into per-layer tasks. The single `write-tests` task is rejected by `validate_plan.py` Check 8.

```
unit-tests          (mandatory)
integration-tests   (mandatory — covers happy-path scenarios)
e2e-tests           (only if E2E layer is Enabled)
validate-all        (depends on all of the above)
```

Each test task gets:
- Its own focused context (Stack keywords pulled from the layer block).
- Its own builder agent.
- A direct mapping to one layer block in `## Test Infrastructure (User-Declared)`.

## Examples per Stack

### Java + Maven + Testcontainers + Failsafe

```md
### Integration Layer (Java)  — MANDATORY, never Skipped
- **Files glob:** `src/test/java/**/*IT.java`
- **Infra signature:** `@Testcontainers|import org\.testcontainers`
- **Happy-path scenarios:**
  - `AddFavoriteIT#shouldCreateFavoriteRow`
  - `RemoveFavoriteIT#shouldDeleteFavoriteRow`
- **Runner command:** `mvn verify -DskipITs=false`
- **Realism rationale:** Failsafe wired in pom; Testcontainers PostgreSQL container matches prod DB; this is the highest realism tier this repo offers.
```

### Java + Maven + H2 (no Testcontainers)

```md
### Integration Layer (Java)  — MANDATORY, never Skipped
- **Files glob:** `src/test/java/**/*IntegrationTest.java`
- **Infra signature:** `@SpringBootTest|@DataJpaTest`
- **Happy-path scenarios:**
  - `FavoriteRepositoryIntegrationTest#shouldPersistFavorite`
- **Runner command:** `mvn test -Dtest=*IntegrationTest`
- **Realism rationale:** No Docker available in this repo; H2 + Spring Boot context is the most realistic tier currently feasible — Surefire runs everything in one phase.
```

### Java + Maven + EmbeddedKafka

```md
### Integration Layer (Java)  — MANDATORY, never Skipped
- **Files glob:** `src/test/java/**/messaging/*IT.java`
- **Infra signature:** `@EmbeddedKafka`
- **Happy-path scenarios:**
  - `OrderProducerIT#publishesOrderEventToKafka`
- **Runner command:** `mvn verify -P integration`
- **Realism rationale:** Project uses spring-kafka-test EmbeddedKafka broker; the `integration` profile is the canonical IT runner here.
```

### Python + pytest + Testcontainers

```md
### Integration Layer (Python)  — MANDATORY, never Skipped
- **Files glob:** `tests/integration/**/*.py`
- **Infra signature:** `from testcontainers|@pytest\.mark\.integration`
- **Happy-path scenarios:**
  - `tests/integration/test_orders.py::test_create_order_persists_row`
- **Runner command:** `pytest -m integration`
- **Realism rationale:** `[tool.pytest.ini_options].markers` includes `integration`; Testcontainers PostgreSQL matches prod; marker-based runs are the established convention here.
```

### Python + pytest + httpx asgitransport (no containers)

```md
### Integration Layer (Python)  — MANDATORY, never Skipped
- **Files glob:** `tests/integration/**/*.py`
- **Infra signature:** `AsyncClient|ASGITransport`
- **Happy-path scenarios:**
  - `tests/integration/test_api.py::test_post_users_returns_201`
- **Runner command:** `pytest tests/integration/`
- **Realism rationale:** No container infra in this repo; httpx + ASGITransport drives the real FastAPI app in-process — the most realistic tier feasible here.
```

### React + Playwright

```md
### E2E Layer (React)
- **Status:** Enabled
- **Files glob:** `e2e/**/*.spec.ts`
- **Infra signature:** `from\s+["']@playwright/test["']`
- **Happy-path scenarios:**
  - `e2e/favorites.spec.ts > adds a favorite from the catalog and sees it in the list`
- **Runner command:** `npx playwright test`
- **Realism rationale:** `@playwright/test` is in devDependencies; this is the only browser-level tier the project ships.
```

## Migrating Old Plans

A plan without `## Test Infrastructure (User-Declared)` will **fail** `validate_plan.py` Check 9 with a message pointing at the migration path. Two options:

1. Re-run `/dev_plan` — it now performs the Test Infra Interview at Step 4.5 and fills in the section automatically.
2. Add the section by hand using the templates above.

There is no soft-warning grace period — the gate is hard FAIL. Old plans without the contract cannot be verified, and unverified test realism is exactly the gap we're closing.

## Key Files

- `.claude/commands/dev_plan.md` — Test Infra Interview (Step 4.5), Plan Format includes `## Test Infrastructure (User-Declared)`
- `.claude/hooks/validators/validate_plan.py` — Stop hook with Checks 8 (per-layer test tasks), 9 (section present), 10 (fields populated, Integration not Skipped)
- `.claude/hooks/validators/check_test_layers.py` — post-build generic assertion checker invoked by `/smart_build`
- `.claude/commands/smart_build.md` — runs `check_test_layers.py` at Step 4.5, before final validation
- `.claude/agents/team/plan-reviewer.md` — criterion 10 "Test Realism"
- `.claude/agents/team/validator.md` — declared runner execution + executed-tests-count check
