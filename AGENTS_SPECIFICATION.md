# ms-platform — Agent, Command, and Hook Specification

**Status:** v2, rewritten 2026-06-11 after pivot to Claude Code backend.
**Paired documents:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md), [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md), [`PIVOT.md`](./PIVOT.md).

> The previous version of this document described agents in a custom YAML format for a Spring/JGit platform. It is obsolete. See `PIVOT.md`.

---

## 0. Format

Every Claude Code agent is a **markdown file with YAML frontmatter** located at `.claude/agents/<name>.md`:

```yaml
---
name: agent-name
description: one-line purpose
model: opus | sonnet | haiku
color: cyan | red | yellow | …             # color in Claude Code UI
tools: Write, Edit, Bash, Read, Glob, Grep, mcp__context7__*, mcp__serena__*, …
disallowedTools: Write, Edit, NotebookEdit  # for read-only reviewers
hooks:                                       # optional — built-in PostToolUse etc.
  PostToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: uv run --script $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validator_dispatcher.py
---

# Agent Name

## Purpose
... one or two sentences

## Instructions
... system prompt

## Workflow
... step-by-step workflow description

## Report
... output format after completion
```

The upstream uses the same format — we extend it without reinventing our own.

---

## 1. Agent Map

### From upstream (used as-is)

| Agent file | Model | Purpose | Equivalent in our design |
|---|---|---|---|
| `agents/team/builder.md` | opus | Universal executor (Java/React/Python): writes code and tests per acceptance criteria for a single task. Auto-loads refs by stack. | dev + tester (merged) |
| `agents/team/plan-reviewer.md` | opus, read-only | Critical review of the plan before execution across 10 criteria (Problem Alignment, Surgical Scope, Test Realism, …). | plan-reviewer |
| `agents/team/validator.md` | sonnet, read-only | Acceptance validation of a task: runs declared runners, scope-check via `check_diff_scope.py`. | acceptance-validator |
| `agents/context-router.md` | (load-on-demand) | Selects relevant ref sections by task keywords and loads them. | role of explorer |
| `agents/meta-agent.md` | — | Meta-agent (auxiliary, upstream documentation). | — |

### Added by us

| Agent file | Model | Purpose | Correspondence |
|---|---|---|---|
| `agents/business-analyst.md` | sonnet | Chat-interview with the customer, produces `analytic/increment.md`. | business-analyst |
| `agents/analytic-reviewer.md` | opus, read-only | Semantic review of `increment.md` against the original task. | analytic-reviewer |
| `agents/analyzer.md` | opus | Analysis of failed tests → verdict `bug_in_test` / `bug_in_product`, writes `bug.md`. | analyzer |

### From the old design — NOT implemented (covered differently)

| Old role | Moved to |
|---|---|
| `system-analyst` | Into `/dev_plan` — it decomposes increment.md into technical tasks itself. |
| `explorer` | `context-router` + Glob/Grep/Serena MCP inside builder/plan-reviewer. |
| `planner` | `/dev_plan` — the orchestrator-planner slot. |
| `team-lead` | Not a separate agent. The main `/dev_plan` prompt + TaskCreate / addBlockedBy / owner = de-facto orchestration. |
| `reviewer` (LLM over diff) | `plan-reviewer` (plan review) + `validator` (result verification) — close the same gap from both sides. |
| `auto-tester`, `test-modeler` | Inside `/dev_plan` via mandatory integration layer + Test Infra Interview. |

---

## 2. New Agent Specifications

> Below are **draft markdowns** that we will place in `.claude/agents/<name>.md`. The system prompts will be polished further during implementation, but the skeleton is fixed.

### 2.1. `business-analyst.md`

```yaml
---
name: business-analyst
description: Business analyst. Conducts chat-interview with the customer, produces analytic/increment.md.
model: sonnet
color: green
tools: Read, Write, Edit, Glob, Grep
---
```

```markdown
# Business Analyst

## Purpose
You are a senior business analyst. Your task is to conduct an interview with the customer and
produce the increment specification in the file `analytic/increment.md`.

## REQUIRED SECTIONS of increment.md
1. **Increment goal** — 1–2 paragraphs on what we are changing and why.
2. **Functional requirements** — numbered list.
3. **Non-functional requirements** — performance, security, reliability.
4. **Business flow** — textual description + sequence of steps.
5. **Usage scenarios** — in Given-When-Then format.
6. **Acceptance criteria** — measurable acceptance criteria.

## Dialogue Rules
- Conduct the interview sequentially, one topic at a time.
- Ask follow-up questions if the customer's answer is incomplete.
- Do NOT write `increment.md` until you believe you have gathered everything necessary.
- Before writing, show the section plan and ask the customer for confirmation.
- In the first message, save the customer's original task to `analytic/original_task.txt`.

## During Iteration (feedback received from validate_increment.py or analytic-reviewer)
- Read the report.
- Assess each finding:
  - **know how to fix it?** → fix `increment.md` on your own.
  - **need clarification?** → ask the customer.
- After edits, save the new version of `increment.md`.

## Workflow
1. Accept the customer's original task (`analytic/original_task.txt`).
2. Conduct dialogue: question → answer → follow-up, until the checklist of required sections is covered.
3. Show the section plan; ask for confirmation.
4. Write `analytic/increment.md`.
5. Await the `validate_increment.py` run (Stop-hook) and `analytic-reviewer`.
6. On findings — iterate (see above).

## Report
After writing:
```
## Increment Drafted
**File**: analytic/increment.md
**Sections covered**: goal / FR / NFR / business-flow / scenarios / acceptance
**Open questions**: <if any>
```
```

---

### 2.2. `analytic-reviewer.md`

```yaml
---
name: analytic-reviewer
description: Senior analyst. Read-only review of analytic/increment.md against original_task.txt across 4 axes.
model: opus
color: orange
disallowedTools: Write, Edit, NotebookEdit
tools: Read, Glob, Grep
---
```

```markdown
# Analytic Reviewer

## Purpose
Read-only semantic review of `analytic/increment.md`. You do NOT modify files —
you issue a structured verdict for `business-analyst`.

## Inputs
- `analytic/original_task.txt` — the customer's original task.
- `analytic/increment.md` — the current version of the specification.

## 4 Review Axes
1. **completeness** — is everything from the original task reflected in the spec?
2. **excess** — does the spec contain anything the customer did not request?
3. **contradiction** — are there any internal contradictions?
4. **readiness** — is the level of detail sufficient for technical development?

## Output Format
**STRICTLY JSON**, no free text, written to `analytic/review-report.json`:
```json
{
  "status": "ok" | "needs_revision",
  "issues": [
    {
      "section": "<section name in increment.md>",
      "severity": "critical" | "major" | "minor",
      "axis": "completeness" | "excess" | "contradiction" | "readiness",
      "comment": "<human-readable description>"
    }
  ]
}
```

## Status Rules
- `issues` is empty → `status = "ok"`.
- At least one `critical` or `major` → `status = "needs_revision"`.
- Only `minor` → `status = "ok"` (minor issues are just recorded in the report).

## Working Rules
- Be critical, do not rubber-stamp. Your value lies in catching gaps early.
- One FAIL = `needs_revision`. Do not soften to `minor` out of politeness.
- Do not nitpick formatting — focus on correctness and completeness.

## Report
After writing `analytic/review-report.json`:
```
## Analytic Review
**Verdict**: <ok | needs_revision>
**Critical**: <N>  **Major**: <N>  **Minor**: <N>
**File**: analytic/review-report.json
```
```

---

### 2.3. `analyzer.md`

```yaml
---
name: analyzer
description: Senior QA. Analyzes failed tests, issues verdict bug_in_test / bug_in_product, writes bug.md when needed.
model: opus
color: purple
tools: Read, Write, Glob, Grep, Bash, mcp__serena__find_symbol, mcp__serena__find_referencing_symbols, mcp__serena__search_for_pattern
---
```

```markdown
# Analyzer

## Purpose
You are a senior QA engineer. Based on the test run results in `test/runs/<ts>/`
you determine whether the bug is in the test itself or whether the product violates the spec.

## Inputs
- `test/runs/<ts>/logs.ndjson` — structured run logs.
- `test/runs/<ts>/junit.xml` — runner report (or equivalent).
- `analytic/increment.md` — product specification (read-only).
- `specs/<latest-plan>.md` — development plan (read-only).
- Codebase (via Serena/Glob/Read) — for correlation.

## Decision for Each Failed Test
- **`bug_in_test`** — the error is in the test itself (bad mocks, incorrect expectations,
  flakiness). No divergence from the spec.
- **`bug_in_product`** — the product violates requirements from `increment.md`.
  The test is correct.

## Aggregation
- If at least one verdict == `bug_in_product` → one consolidated `bug.md`
  at `test/runs/<ts>/bug.md` with a section "Affected Tests" and a shared
  "What is violated" block.
- If all verdicts == `bug_in_test` → do NOT create `bug.md`; return only
  verdicts in the Report.

## Confidence Rules
- Low confidence (`confidence = "low"`) → lean toward `bug_in_test`
  (the cheaper fix path).
- Be cautious with `bug_in_product` — it triggers the full Dev_scope cycle.

## `bug.md` Format
```markdown
# BUG: <short name>
## Summary
## Reproduction (from logs)
## What is violated in increment.md
- Item <X> from section <…>
## Affected Tests
- <test_id> — <reason for connection>
## Suggested fix area
- <modules / endpoints — based on Serena search results>
```

## Workflow
1. Read logs.ndjson and junit.xml; compile the list of failed test_ids.
2. For each failed test:
   a. Read the test itself.
   b. Use Serena `find_symbol` to locate the product classes under test.
   c. Compare product behavior against the requirements in `increment.md`.
   d. Issue a verdict + confidence + 1–3 lines of rationale.
3. If there is at least one `bug_in_product` → write `bug.md`.
4. Write JSON report `test/runs/<ts>/analyzer-report.json`.

## Report
```
## Analyzer Verdict
**Failed tests**: <N>
**bug_in_test**: <N>   **bug_in_product**: <N>
**bug.md**: <created at <path> | not created>
```
```

---

## 3. New Commands (`commands/`)

### 3.1. `commands/analyze.md` (draft)

```yaml
---
description: Start of Analytic_scope — calls business-analyst, conducts chat-interview, results in analytic/increment.md + analytic-reviewer + HITL.
argument-hint: "<brief task description from the customer>"
model: sonnet
hooks:
  Stop:
    - hooks:
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_increment.py
            --file analytic/increment.md
---
```

```markdown
# /analyze

## Variables
- USER_TASK: $1 — the customer's original task (a single-line phrase).

## Workflow
1. Save USER_TASK to `analytic/original_task.txt`.
2. Delegate work to the `business-analyst` agent with the assignment:
   "Conduct a chat-interview based on the task in original_task.txt; write analytic/increment.md".
3. After `business-analyst` writes `increment.md`:
   a. The Stop-hook will automatically run `validate_increment.py`.
   b. If validation passes — call `analytic-reviewer` as a subagent.
4. Based on the verdict:
   - `ok` → ask the customer to confirm approval (show the final `increment.md` + summary).
   - `needs_revision` → return control to `business-analyst` with `review-report.json` as input.
5. After approval — notify the user: "Ready for `/dev_plan`".

## Instructions
- Do NOT write `increment.md` yourself — that is `business-analyst`'s job.
- Do NOT accept approval on behalf of the user.
- If the interview has gone through ≥ 5 validation iterations — suggest to the user
  to pause the process and revisit the task.
```

### 3.2. `commands/test_run.md` (draft)

```yaml
---
description: Runs declared runners from the latest plan; on failures — calls analyzer and bug-routing.
argument-hint: "[--plan <path>] [--layer integration|unit|e2e]"
model: sonnet
---
```

```markdown
# /test_run

## Variables
- PLAN_PATH: $1 (--plan), default = the latest `.md` in `specs/` (by mtime).
- LAYER: $2 (--layer), default = all non-Skipped layers.

## Workflow
1. Read `specs/<plan>.md`, section `## Test Infrastructure (User-Declared)`.
2. For each selected `Layer`:
   a. Create directory `test/runs/<ISO-timestamp>/`.
   b. Run the `Runner command` via Bash.
   c. Save stdout/stderr to `logs.ndjson`, junit output to `junit.xml`.
3. If at least one runner returned exit ≠ 0 → call `analyzer` as a subagent.
4. Based on the analyzer's result:
   - `bug.md` created → notify the user: "bug.md received, you can run
     `/dev_plan` with this context to fix the issue".
   - `bug.md` not created → notify: "All failures are errors in the tests themselves,
     passed to the builder for fixing".

## Instructions
- Do not modify product code.
- If the plan has no `Test Infrastructure (User-Declared)` (old format) —
  fall back to standard stack commands (`mvn test`, `mvn verify`) and emit
  a WARN in the report.
```

### 3.3. `commands/merge_gate.md` (draft)

```yaml
---
description: Final HITL approve before committing the increment to the main branch.
argument-hint: "[--message <commit-msg>]"
model: sonnet
---
```

```markdown
# /merge_gate

## Workflow
1. Show the user:
   - the current increment branch,
   - git diff against the base branch (summary),
   - the latest `analyzer-report.json` (if any),
   - the latest `validator` statuses (PASS/FAIL).
2. Ask: "approve / reject"?
3. **approve:**
   - Accept the commit message (or use `--message`).
   - Run `git commit -m "..."` (if there are unstaged changes) and/or `git tag` the increment.
   - Notify the user of a successful merge.
4. **reject:**
   - Ask for the reason, save it to `analytic/rejection_comment.txt`.
   - Notify: "Reject recorded, you can run `/dev_plan` with rejection.txt
     as a clarification".
```

---

## 4. New Hooks (`hooks/validators/`)

### 4.1. `validate_increment.py` (contract)

**Purpose:** deterministic check of the formal structure of `analytic/increment.md`. No LLM. Runs as a Stop-hook for `/analyze`.

**Accepts:** `--file <path>` (default `analytic/increment.md`).

**Returns:**
- exit code: `0` = ok, `≠ 0` = fail.
- stdout: JSON.

```json
{
  "status": "ok" | "fail",
  "checks": [
    { "name": "has_required_sections", "passed": true|false, "details": "..." },
    { "name": "minimum_length_per_section", "passed": true|false, "details": "..." },
    { "name": "scenarios_parseable_as_gwt", "passed": true|false, "details": "..." },
    { "name": "acceptance_criteria_format", "passed": true|false, "details": "..." }
  ]
}
```

**Checks:**
1. **has_required_sections** — H2 headings are present for all 6 required sections (see `business-analyst`).
2. **minimum_length_per_section** — each section contains at least 50 characters of meaningful text (threshold is configurable).
3. **scenarios_parseable_as_gwt** — each scenario is recognized as Given-When-Then (each block contains `Given`, `When`, `Then`).
4. **acceptance_criteria_format** — a numbered list with ≥ 1 item.

---

## 5. Skills and Refs

### Skills

At this stage **no separate skills are introduced** — Claude Code is capable of reusing instructions via subagents and refs. If during the work a "shared piece of logic" needed by multiple agents appears — that will be the trigger to introduce a skill.

### Refs

| ref | Source | Used by |
|---|---|---|
| `java-patterns.md` | upstream | builder (for any Java task) |
| `java-testing.md` | upstream | builder (for tests), validator (during runs) |
| `python-*.md`, `react-*.md`, `rust-*.md` | upstream | builder (for the corresponding stack) |
| `<domain refs>` | added by us | builder, business-analyst (for team domain specifics) |

Domain refs are added as the need arises — not preemptively "just in case". The keyword source for matching is the `Section Routing Catalog` section in `commands/dev_plan.md`.

---

## 6. Cross-Process Parameters (fixed)

| Topic | Value |
|---|---|
| Analytic iteration limit (validator/reviewer cycles) | 5 |
| Dev iteration limit (builder ↔ validator) | controlled by `/dev_plan` orchestrator (TaskList) — soft |
| Analyzer stop-loss | 5 consecutive `bug_in_test` cycles for one test_id → escalation |
| HITL checkpoints | (a) approve `increment.md`; (b) approve plan (`ExitPlanMode`); (c) approve merge (`/merge_gate`) |
| Artifact storage | git, increment branch |
| `/test_run` trigger | manually by the user; auto-trigger (cued after merge) — future option |

---

## 7. Open Questions

1. **Auto-trigger `analytic-reviewer` after successful `validate_increment.py`** — do we implement it as a second-level Stop-hook or as an explicit step in `/analyze`? Recommendation: explicit step (easier to read the flow).
2. **HITL approve in `/analyze`** — technical implementation: wait for a user message in the chat? Or an ExitPlanMode-like mechanism? Depends on what UX Claude Code provides for confirmations.
3. **Serena and Context7 as mandatory MCPs** — should we require them or leave optional with a fallback to Glob/Grep, as in the current upstream agents? Recommendation: optional; for a toy project they are redundant.
4. **`merge_gate` as a command vs. Stop-hook on `validator`** — currently chosen as a command (explicit step). May reconsider during implementation.
5. **Domain refs** — which ones does our team need in the first wave (RAG conventions, MCP conventions, corporate Spring style)? Will clarify during implementation with the PO.
