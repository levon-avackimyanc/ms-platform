# Dev Scope

Reference map of the **Dev scope** pipeline — every command, agent, hook,
validator, and how context routing flows. (Human-facing prompts in the agents
are Russian; this doc is English to match `CLAUDE.md` and `.claude/refs/`.)

## Where it sits

```
Analytic scope          Dev scope                              Test scope
/analyze        →   /plan_w_team → /smart_build → /merge_gate   ∥  parallel,
(increment.md)      (specs/*.md)   (code+unit tests) (git commit)    coupled via
                         │                                           specs/*.md
                         └──── frozen contract ───► /test_plan → /test_build → /test_run
                                                    (see TEST_SCOPE.md)
```

Boundaries:
- Dev scope writes **product code + unit tests only**. Integration/E2E are owned
  by Test scope.
- **Test scope is coupled, not independent**: it reads the Dev plan `specs/*.md`
  (the frozen technical contract) and authors higher-layer autotests **in parallel**
  with the Dev build. Test *execution* (`/test_run`) depends on Dev code being done.
  See [`TEST_SCOPE.md`](./TEST_SCOPE.md).
- **`/merge_gate` is the only command that runs `git commit` / `git merge`.**
  `/plan_w_team` and `/smart_build` never touch git history.
- Sub-agents have **no Task tools**; the orchestrator owns the task ledger.

## Commands (skills)

| Command | Model | Role | Output |
|---|---|---|---|
| `/plan_w_team` | opus | Plan from increment/prompt; interview, explore, decompose into tasks | `specs/<kebab>.md` |
| `/smart_build` | inherits | Orchestrate the team to build the plan | code + unit tests |
| `/merge_gate` | sonnet | HITL gate; commit + merge | git commit/merge |

## Agents

| Agent | Model | R/W | Used in | Per-agent hook |
|---|---|---|---|---|
| `explorer` | sonnet | writes only `explore/module-map.md` | plan Step 3.5 | — |
| `plan-reviewer` | opus | read-only | plan Step 12 | — |
| `context-router` | haiku | read-only | (helper) | — |
| `developer` | sonnet | product code | build | PostToolUse → `validator_dispatcher.py` |
| `unit-tester` | sonnet | unit tests only | build | PostToolUse → `validator_dispatcher.py` |
| `code-reviewer` | opus | read-only diff review | build | — |
| `validator` | sonnet | read-only validation | build | — |

Model policy is intentional: `developer`=sonnet (bulk code-writing),
`code-reviewer`=opus (critical diff-review gate). See plan-reviewer criterion 8.

## Hook layers

1. **Global** (`.claude/settings.json`, main thread) — telemetry/infra only:
   `pre_tool_use`, `post_tool_use` (logs to `logs/`), `user_prompt_submit`,
   `stop`, `subagent_stop`, `session_*`, etc. Not quality gates.
2. **Per-command** (command frontmatter) — `/plan_w_team` Stop hooks validate
   the saved plan (see Validators).
3. **Per-agent** (agent frontmatter) — `developer` & `unit-tester` run
   `validator_dispatcher.py` on every `Write|Edit`. **This is the code quality gate.**

## Validators

**Plan structure** (`/plan_w_team` Stop hooks):
- `validate_new_file.py` — a `.md` appeared in `specs/`.
- `validate_file_contains.py` — all required H2 sections present.
- `validate_plan.py` — 10 structural checks (unit-tests task required, Unit Layer
  mandatory, integration/e2e optional, Stack keywords route, agents exist, …).

**Code** (`validator_dispatcher.py`, by extension, blocks on first fail):

| Ext | Validators |
|---|---|
| `.py` | `ruff` → `ty` → `bandit` |
| `.java` | `spotless` → `maven_compile` (+`jacoco` for `*Test`/`*IT`, +`pmd` always) |
| `.ts/.tsx` | `eslint` → `tsc` |
| `.js/.jsx` | `eslint` → `prettier` |
| `.css/.scss/.json` | `prettier` |
| `pom.xml` | `maven_compile` → `ossindex` |

Formatters (`spotless`, `prettier`, `ruff`) **auto-apply** the fix and re-check;
they block only on real problems left after formatting — never on whitespace.

**Post-build** (orchestrator runs once):
- `check_test_layers.py` (`/smart_build` Step 4.5) — built tests match the plan's
  `## Test Infrastructure (User-Declared)`; Skipped integration/e2e not checked.
- `check_diff_scope.py` (Step 5; advisory) — git changes trace to declared
  `Relevant Files`/`New Files`. Uses `--untracked-files=all` (greenfield-safe).

## Context routing

```
echo 'Stack: <keywords>' | context_router.py | section_loader.py
```
- `context_router.py` (deterministic, no LLM) matches **trigger keywords** in a
  task's `**Stack**` field → returns relevant section ids; also accepts explicit
  section ids. ~75% token saving vs loading all refs.
- `section_loader.py` loads only those sections from `.claude/refs/*.md`
  (`java-patterns`, `java-testing`, `python-patterns`, `python-testing`,
  `react-patterns`, `rust-*`).
- `explorer` writes module→**trigger-keyword** tags into `explore/module-map.md`;
  the planner reuses them as each task's `**Stack**`. One shared vocabulary across
  explorer → planner → router.

## Task ledger

Dev-scope sub-agents have no Task tools. They receive tasks via the prompt and
report status in their **Report**. The `/smart_build` orchestrator owns the
shared ledger (and the OpenSpec `tasks.md`) and updates it from those Reports.
If an agent stalls mid-task, the orchestrator inspects on-disk files to decide
completion; a continuation is a fresh agent (no `SendMessage` context) working
from on-disk artifacts.

## End-to-end flow

```
increment.md
   │  /plan_w_team (opus)
   ├─ AskUserQuestion ×2 (interview, main thread)
   ├─ Agent: explorer ×N (parallel) → explore/module-map.md
   ├─ Write specs/plan.md ──Stop hooks──► validate_new_file + validate_file_contains + validate_plan(10)
   └─ validate_plan.py + Agent: plan-reviewer(10 criteria) ─► PASS
   │  /smart_build (orchestrator)
   ├─ context_router.py → section_loader.py → refs/*
   ├─ Agent: developer ──Write──► validator_dispatcher → {spotless→maven_compile→pmd | ruff→ty→bandit | …}
   ├─ Agent: unit-tester ──Write──► (same dispatcher)
   ├─ Agent: code-reviewer ─► PASS/FAIL (FAIL → developer fixes)
   ├─ check_test_layers.py (Step 4.5)
   ├─ Agent: validator ─► mvn test / pytest + acceptance trace
   └─ check_diff_scope.py (Step 5, advisory)
   │  /merge_gate (sonnet)
   ├─ git facts + check_diff_scope (lite)
   └─ HITL yes ─► git commit + git merge --no-ff   ← only git write
```

## Artifacts

| Path | Producer | Tracked |
|---|---|---|
| `analytic/*` | Analytic scope | gitignored |
| `explore/module-map.md` | explorer | gitignored |
| `specs/*.md` | `/plan_w_team` | committed |
| `openspec/changes/*` | OpenSpec (if installed) | committed |
