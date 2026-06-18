# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo currently is

There is **no application source code yet**. `short-link-service` is the *target* product, but today the repository contains only the `.claude/` multi-agent SDLC framework that will be used to build it (plus IDE/git config). The framework — a chain of slash commands, sub-agents, Python hooks, and validators — *is* the codebase right now, and the thing you will most often work on or operate through.

The framework's human-facing interaction (interview questions, HITL prompts, agent reasoning) is conducted in **Russian**. Code, identifiers, and these docs are in English.

## The pipeline (Analytic scope → Dev scope)

Four slash commands run in sequence, each gated by a human-in-the-loop (HITL) approval. Working artifacts live in gitignored directories (`analytic/`, `explore/`, `specs/`, `.claude/data/`, `logs/`).

1. **`/analyze "<task>"`** — *Analytic scope*. Delegates a chat-interview to the `business-analyst` agent, which writes `analytic/increment.md` (a **business** spec) to the shape defined in `.claude/config/increment_template.yaml`. Validated structurally by `validate_increment.py` and reviewed semantically by `analytic-reviewer` (verdict → `analytic/review-report.json`). Also writes `analytic/original_task.txt` (the customer ask, verbatim — reviewers diff against this, never a paraphrase). Ends on HITL approve.
2. **`/plan_w_team`** — *Dev scope planning*. Reads `analytic/increment.md` as the **primary input** and produces a **technical** plan in `specs/<name>.md`. Spawns the `explorer` agent to (re)generate `explore/module-map.md` and `plan-reviewer` to review. Planning-only: writes a plan, never product code. Every acceptance criterion in the increment must trace to a task.
3. **`/smart_build <specs/plan.md>`** — *Dev scope execution*. Orchestrates `developer` / `unit-tester` / `code-reviewer` / `validator` agents to implement the plan. The orchestrator never writes code itself — it deploys agents and coordinates via the Task tools.
4. **`/merge_gate`** — *the only place the system runs `git commit` / `git merge`*. None of the earlier commands touch git history. On HITL approve it commits the increment branch and merges `--no-ff` into the base branch. It never `push`es, never resolves merge conflicts itself (human resolution), and never accepts approval on the user's behalf.

Key scope boundary: **Dev scope writes product code + unit tests only.** Integration and E2E tests are explicitly deferred to a separate *Test scope* and must not be planned or built in `/plan_w_team` / `/smart_build`.

## Agents

- Top-level agents in `.claude/agents/`: `business-analyst`, `analytic-reviewer`, `context-router`, `meta-agent`.
- Dev-scope team in `.claude/agents/team/`: `explorer`, `developer`, `unit-tester`, `code-reviewer`, `plan-reviewer`, `validator`.
- **Writing vs. read-only**: only `business-analyst`, `developer`, and `unit-tester` write files (and `developer` writes product code, `unit-tester` writes only tests — they never cross). All reviewers/validators (`*-reviewer`, `validator`, `explorer`) are read-only or write only their own report/map.
- Reviewers return a verdict as a single fenced ```json block (or structured PASS/FAIL); the orchestrator extracts it. If a reviewer emits no valid JSON, do not fabricate a report file.

## Context routing (how agents get coding standards)

Reference standards live in `.claude/refs/*.md` (`java-patterns`, `java-testing`, `python-patterns`, `python-testing`, `react-patterns`, `rust-*`), split into `#`-anchored sections. Instead of loading all of them (~20k tokens), tasks are routed to only the relevant sections (~5k):

- Each plan task carries a `**Stack**` field of keywords. `context_router.py` does **deterministic keyword matching** (no LLM) → a list of section IDs; `section_loader.py` loads those sections' text.
- The keyword→section map is the **Section Routing Catalog** in `.claude/commands/plan_w_team.md`. The same tags are written into `explore/module-map.md` by `explorer` and reused across the pipeline. When adding a task, pick at least one stack keyword (Java/React/Python) plus section keywords for what the task does.

```bash
echo 'Stack: Java Spring Boot JPA. Task: add /users endpoint' \
  | uv run --script .claude/hooks/context_router.py \
  | uv run --script .claude/hooks/section_loader.py
```

## Hooks & validators

Hooks run on the Python runtime via `uv run --no-project` (configured in `.claude/settings.json`). All lifecycle events (PreToolUse, PostToolUse, Stop, SubagentStop, SessionStart/End, etc.) are wired to scripts in `.claude/hooks/`.

The important one for code changes is the **validator dispatcher** (`.claude/hooks/validators/validator_dispatcher.py`), attached as a `PostToolUse: Write|Edit` hook on the `developer`/`unit-tester` agents. It maps file extension → linters and runs only the matching ones, **blocking the write on first failure** (`decision: "block"`):

- `.py` → ruff, ty, bandit
- `.java` → spotless, maven_compile, pmd (+ jacoco for `*Test.java` / `*IT.java`)
- `.ts/.tsx` → eslint, tsc · `.js/.jsx` → eslint, prettier · `.css/.scss/.json` → prettier
- `pom.xml` → maven_compile, ossindex

**Do not run these validators manually to "double-check"** — the hooks fire automatically after each Write/Edit, and the command docs treat manual duplication as a configuration smell. If a hook does not fire, that's a config bug (check the agent's `hooks:` frontmatter), not a reason to invoke it by hand.

Plan/increment quality is enforced by standalone validators (run by command Stop-hooks, but invokable directly):

```bash
# Plan structure + team-agent existence
uv run --script .claude/hooks/validators/validate_plan.py --file specs/<plan>.md --team-dir .claude/agents/team

# Post-build: do the built tests match the plan's "Test Infrastructure (User-Declared)" contract?
uv run --script .claude/hooks/validators/check_test_layers.py --plan specs/<plan>.md

# Did the diff stay within the plan's declared Relevant Files / New Files?
uv run --script .claude/hooks/validators/check_diff_scope.py --plan specs/<plan>.md --baseline main
```

## Running the framework's own tests

The hooks have a pytest suite:

```bash
uv run --no-project -m pytest .claude/hooks/validators/tests/            # all hook tests
uv run --no-project -m pytest .claude/hooks/validators/tests/test_validate_plan.py::<test_name>   # single test
```

## When real application code lands

Once the short-link-service source exists, its build/lint/test commands and architecture belong in this file — add them then. The plan that creates it will declare its stack, unit-test runner, and layout in the `## Test Infrastructure (User-Declared)` section of `specs/<plan>.md`; that section is the authoritative source for how to run the new code's tests.
