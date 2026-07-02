# ms-platform — Architecture Proposal

**Status:** v2, rewritten 2026-06-11 after pivot to Claude Code-only backend.
**See also:** [`PIVOT.md`](./PIVOT.md), [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md), [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md), [`UPSTREAM-README.md`](./UPSTREAM-README.md).

> The previous version of this document described a custom Java/Spring platform with an LLM Gateway. It is obsolete. See `PIVOT.md` for the decision history and `git log` for the content.

---

## Part I. Requirements Summary

### 1. What We Are Building

**A Claude Code configuration** (`.claude/`), which orchestrates the end-to-end cycle of *business analysis → development → testing* by means of specialized AI agents with human gates at key checkpoints.

We are NOT building:
- a custom CLI tool,
- a custom LLM Gateway,
- a custom phase orchestrator,
- a backend service.

All of this is already provided by Claude Code as a runtime; our work is to fill it with a domain-correct set of `agents/`, `skills/`, `commands/`, `hooks/`, `refs/`, and `mcp` configs.

### 2. What the System Produces

The end-to-end cycle's final artifact is a **Docker image of the product**, ready for manual deployment to a test environment. The build is done by a standard `mvn package` / `Dockerfile` under the control of the `validator` agent; deployment to the environment is done manually, outside the system; after deployment — running autotests and (optionally) analyzing failures → BUG-routing.

### 3. Target Projects (processed by the system)

A set of **Java + Spring Boot 3.x microservices**, ~tens of thousands of lines each, **greenfield** increment development (not legacy). The agent stack is multilingual out of the box (Java/React/TypeScript/Python/Rust in upstream refs); our team focuses on Java/Spring.

### 4. Operating Environment

Locally at the developer's machine. Launched with the `claude` command in the **target microservice directory**, provided our `.claude/` is installed there (via `install.sh`) **or** when working from the `ms-platform` directory itself for config debugging.

### 5. Paradigm

Standard Claude Code primitives:

| Primitive | Location | Role in our system |
|---|---|---|
| **agents** | `.claude/agents/*.md` | Roles: builder, plan-reviewer, validator (existing); business-analyst, analytic-reviewer, analyzer (being added) |
| **commands** | `.claude/commands/*.md` | Slash commands: `/dev_plan`, `/smart_build` (existing); `/analyze`, `/test_run` (being added) |
| **hooks** | `.claude/hooks/*.py` + `settings.json` | Lifecycle (Pre/PostToolUse, Stop, …) and validators (spotless, jacoco, validate_plan, …) |
| **skills** | (Claude Code skill format if applicable) | Reusable "how to do X", invoked by agents via keyword matching |
| **refs** | `.claude/refs/*.md` | Former "tags registry" — ready-made `java-patterns.md`, `java-testing.md`, etc. |
| **MCP** | `.claude/settings.json` + MCP servers | External tools: Context7 (docs), Serena (semantic code), OpenSpec, and others |

### 6. Three Scopes

#### Analytic_scope (business analysis) — **built from scratch**

The `/analyze` command launches the `business-analyst` agent. It conducts a chat-interview with the customer (via the Claude Code UI — built-in chat), and produces `analytic/increment.md` with a list of FR/NFR, business flow, scenarios, and acceptance criteria.

After writing `increment.md`:
1. **`validate_increment.py`** (hook on Stop for `/analyze` or on Edit for the file) — deterministic check of the markdown structure.
2. **`analytic-reviewer`** agent — semantic review (cross-checking against the customer's original task, finding contradictions/gaps).
3. **HITL gate** — the customer/PO sees the result in the chat and confirms it (inside the Claude Code session, no separate application needed).

Loop on validator or reviewer failure: `business-analyst` fixes it itself; consults the customer only if clarification is needed.

#### Dev_scope (development) — **using upstream + adding HITL**

Ready-made layers from upstream cover almost everything:

- **`/dev_plan`** (Opus) — agent-orchestrator, creates `specs/<plan>.md` with 8 required sections (including `Testing Strategy` 80/15/5 and `Test Infrastructure (User-Declared)`). Internally conducts a **Test Infra Interview** with the user to fill in the runners.
- **`plan-reviewer`** (Opus, read-only) — critical review of the plan across 10 criteria (Problem Alignment, Surgical Scope, Test Realism, etc.) before execution.
- **TaskCreate / TaskUpdate / TaskList / TaskGet** — built-in task orchestration: planner creates tasks, assigns owners, sets `addBlockedBy`, builder agents pick theirs up.
- **`builder`** (Opus) — universal executor (Java/React/Python). Auto-loads refs by stack and keywords from the `**Stack**` field of the task.
- **`validator`** (Sonnet, read-only) — post-execution verification: runs `mvn spotless:check` / `mvn test` / declared runners from the plan, checks actual-vs-declared scenarios count, diff scope via `check_diff_scope.py`.
- **Hooks:** PostToolUse → `validator_dispatcher.py` (runs the relevant linter by file type); Stop at `/dev_plan` → `validate_plan.py` (checks the plan's contractual completeness).

What **we add**:
- **`merge_gate`** command or Stop-hook — final HITL confirmation before committing/merging the increment (analogous to our "manual-merge-gate").

What **we consciously do not do at the start**:
- Parallel worktrees per dev-agent. `/dev_plan` orchestrates tasks sequentially via TaskList + owner — this is sufficient for MVP. Worktree parallelism is considered a future option.

#### Test_scope — **independent of Dev, runs in parallel** (full, not lite)

> Current revision (2026-06-26). The previous "lite" concept (Test = only
> `/test_run` + `analyzer`, and tests are written inside `/dev_plan`) is obsolete; **also
> cancelled** is the 2026-06-24 revision ("Test is bound to the Dev plan / contract-frozen").
> The full contract is in [`.claude/TEST_SCOPE.md`](./.claude/TEST_SCOPE.md).

Test_scope owns **authoring + running/analysis** of autotests for higher layers
(Integration / Sys / E2E / UI / Load); **UNIT stays in Dev_scope**. This is
an **independent parallel pipeline**: both Dev and Test take `analytic/increment.md` as
input and start **simultaneously at t=0**.

- **Input = `increment.md` (intent), not the Dev plan.** `/test_plan` and `test-analyst`
  read the increment as the primary input and derive their **own** technical approach —
  independently of Dev. `specs/*.md`, if present, is an optional cross-reference, not a contract.
  Dev and Test technical decisions **may differ**.
- **Own planner and own explorer.** `/test_plan` + `test-analyst` +
  **`test-explorer`** (test landscape map → `test/test-landscape.md`). Test does **not**
  merge into `/dev_plan`; two planners, **two independent ledgers**, no
  contract-frozen and no synchronization via Dev artifacts.
- **Parallel authoring from t=0.** `/test_build` runs `autotester`s
  **simultaneously with Dev** without waiting for Dev milestones. The compile gate in this mode
  is **relaxed** (`--authoring`: format + statics; compilation/coverage deferred),
  so references to code not yet built do not block authoring.
- **Dev/Test divergence** is resolved at `/test_run` (`failure-analyzer`:
  test-side / service-side), not at the planning stage.
- **Run/analysis (Flow B).** `/test_run` is **human-launched after code is ready**:
  Exec → `failure-analyzer` (test-side / service-side / unclear) →
  {test fix by `autotester` | bug by `bug-reporter` in `test/bugs/`}.
- **Gate.** `/test_gate` — the only Test command that touches git.

BUG-routing: a service-side bug (`test/bugs/*.md`) becomes the input for a new
`/dev_plan` — a full Dev cycle of rework.

#### Parallel Execution via Conductors

Dev and Test run **truly in parallel** through a thin main Conductor and two
background conductor agents:

```
main thread = /build_scopes (thin Conductor — only relays HITL, does not touch git)
 ├─► dev-conductor  [bg]: explorer → plan specs/*.md → developer/unit-tester/
 │                        code-reviewer/validator   (inside /dev_plan + /smart_build)
 └─► test-conductor [bg]: test-explorer/test-analyst → plan test/*.md → autotester/
                          code-reviewer              (inside /test_plan + /test_build)
 both read increment.md · independent · Dev-build ∥ Test-authoring
```

- **Bubble-up HITL.** Sub-agents cannot call `AskUserQuestion`. The planner inside
  the conductor hands questions up as a `HITL_QUESTIONS` block (pause); `/build_scopes`
  asks the user and resumes the conductor via `SendMessage` (round-trip
  empirically verified).
- **Two independent ledgers**, no contract-frozen. Gates `/merge_gate` and
  `/test_run` → `/test_gate` remain human-launched after the conductors.
- Standalone commands (`/dev_plan`, `/smart_build`, `/test_plan`, `/test_build`)
  are preserved for manual single-scope runs.

### 7. Refs (former tags registry)

Human-curated md files in `.claude/refs/`. From upstream, these already exist:

- `java-patterns.md` (24 KB), `java-testing.md` (56 KB) — **our stack**.
- `python-patterns.md` (95 KB), `python-testing.md` (66 KB).
- `react-patterns.md` (54 KB).
- `rust-patterns.md`, `rust-testing.md`.

Previously, a table with 3 fields was envisioned (name, description, link). In the Claude Code paradigm, refs are **the markdowns themselves with sections** (`#section`); matching via keywords → sections is done by the `context-router.md` agent and the `context_router.py` hook (see the `Section Routing Catalog` in `/dev_plan`).

Our team's domain refs (corporate libraries / internal MCPs / RAG / liquibase conventions, etc.) are **added here** as new files or new sections in existing ones.

### 8. Cross-Functional Decisions

| Topic | Decision |
|---|---|
| **LLM infrastructure** | Claude via Claude Code itself (Opus for planner/reviewer/analyzer, Sonnet for verifier, Haiku — if fast / fan-out tasks appear). We do not build our own role-to-model matching — Claude Code handles it via `model:` in the agent's YAML frontmatter. |
| **Where artifacts live** | In git, in the increment branch: `analytic/`, `specs/<plan>.md`, `test/runs/<ts>/`. No custom database. |
| **Versioning** | 1 increment = 1 branch. Intermediate commits per phase (analytic / spec / build / test). Squash on approve — optional, not required. |
| **HITL checkpoints** | (a) approve `increment.md` in the chat with the customer (Analytic gate); (b) approve plan in `/dev_plan` (built into Claude Code — exit plan mode); (c) approve merge before committing the increment (`merge_gate`). |
| **Failure modes** | Standard Claude Code: retry/exit on tool errors; our hook validators return exit≠0 → Claude Code interprets and calls the agent to fix. |
| **Observability** | Trace built into Claude Code + Stop-hooks that write logs. We do not build custom tracing. |
| **Budget / SLA** | Not defined; model is chosen per-agent in YAML frontmatter. Cost control — via Claude Code (it shows tokens). |
| **Corporate perimeter** | Claude Code must be launched via a corporate proxy to the Anthropic API (a question outside our configuration; resolved at the installation level). |

---

## Part II. Architecture Proposal

### A. High-Level Diagram

```
┌──────────────────────────────────────────────────────────────┐
│  User (business analyst / developer / QA / PO)               │
└───────────────┬──────────────────────────────────────────────┘
                │ chat / slash-commands
                ▼
┌──────────────────────────────────────────────────────────────┐
│                       Claude Code (CLI)                      │
│  • orchestrator, LLM gateway, tool runner, lifecycle hooks   │
│  • Anthropic API (Opus / Sonnet / Haiku) ── via corp proxy   │
└───────────────┬──────────────────────────────────────────────┘
                │ reads .claude/ from cwd
                ▼
┌──────────────────────────────────────────────────────────────┐
│              .claude/  (our ms-platform repository)          │
│  ┌─ commands/   /dev_plan  /smart_build  /analyze*  /test_run* │
│  ┌─ agents/     builder, plan-reviewer, validator,           │
│  │              business-analyst*, analytic-reviewer*, analyzer* │
│  ├─ hooks/      lifecycle (Pre/PostToolUse/Stop/…) +         │
│  │              validators/ (spotless, jacoco, validate_plan, │
│  │                            validate_increment*, …)        │
│  ├─ refs/       java-patterns, java-testing, …               │
│  └─ settings.json (hook wiring + permissions + MCP)          │
└───────────────┬──────────────────────────────────────────────┘
                │ operates on artifacts in
                ▼
┌──────────────────────────────────────────────────────────────┐
│   Git repo of the TARGET product (or this one — for dev cycle)│
│  branch: increment/<name>                                    │
│    ├── analytic/                                             │
│    │     ├── original_task.txt                               │
│    │     ├── increment.md            (business increment)    │
│    │     └── review-report.json                              │
│    ├── specs/                                                │
│    │     └── <plan-name>.md          (plan from /dev_plan)   │
│    ├── src/, pom.xml, …               (the product itself)   │
│    └── test/runs/<timestamp>/         (run logs)             │
└──────────────────────────────────────────────────────────────┘

* — added by us; everything else — already present from upstream.
```

### B. `.claude/` Structure — What Exists and What We Add

```
.claude/
├── commands/
│   ├── dev_plan.md         ✓ upstream — central Dev_scope planner
│   ├── smart_build.md         ✓ upstream — build with context routing
│   ├── analyze.md             ✗ TO ADD — start of Analytic_scope (business-analyst)
│   └── test_run.md            ✗ TO ADD — run tests + analyzer
├── agents/
│   ├── context-router.md      ✓ upstream — load-on-demand refs loader
│   ├── meta-agent.md          ✓ upstream
│   ├── team/
│   │   ├── builder.md         ✓ upstream — our "dev" + "tester" (universal)
│   │   ├── plan-reviewer.md   ✓ upstream — our "plan-reviewer"
│   │   └── validator.md       ✓ upstream — our acceptance-validator
│   ├── business-analyst.md    ✗ TO ADD — chat-interview with the customer
│   ├── analytic-reviewer.md   ✗ TO ADD — review of increment.md
│   └── analyzer.md            ✗ TO ADD — analysis of test failures
├── hooks/
│   ├── pre_tool_use.py        ✓ upstream
│   ├── post_tool_use.py       ✓ upstream
│   ├── … (10 lifecycle scripts)
│   └── validators/
│       ├── validate_plan.py            ✓ upstream
│       ├── validate_new_file.py        ✓ upstream
│       ├── validate_file_contains.py   ✓ upstream
│       ├── check_diff_scope.py         ✓ upstream
│       ├── check_test_layers.py        ✓ upstream
│       ├── validator_dispatcher.py     ✓ upstream
│       ├── spotless_validator.py       ✓ upstream
│       ├── pmd_validator.py            ✓ upstream
│       ├── jacoco_validator.py         ✓ upstream
│       ├── maven_compile_validator.py  ✓ upstream
│       ├── ruff_validator.py, …        ✓ upstream
│       └── validate_increment.py       ✗ TO ADD — structural check of increment.md
├── refs/
│   ├── java-patterns.md       ✓ upstream — 24 KB of Java/Spring patterns
│   ├── java-testing.md        ✓ upstream — 56 KB of test patterns
│   ├── python-*, react-*, rust-*  ✓ upstream
│   └── <domain refs>          ✗ TO ADD — corporate libraries, MCP, RAG, liquibase, etc.
├── settings.json              ✓ upstream — hook wiring (will be extended for our hooks)
└── data/                      (runtime — in .gitignore)
```

Legend: ✓ — present in upstream, ready to use; ✗ — needs to be added.

### C. Agent Map

| Agent | Source | Model | Purpose | Used in |
|---|---|---|---|---|
| `business-analyst` | **ours** | Sonnet | Chat-interview with the customer, writes `analytic/increment.md`. | Analytic_scope |
| `analytic-reviewer` | **ours** | Opus | Cross-checks `increment.md` against the original task, finds gaps/contradictions. | Analytic_scope |
| `/dev_plan` (as agent-orchestrator) | upstream | Opus | Creates `specs/<plan>.md` with 8 sections + Test Infra Interview + decomposition via TaskCreate. | Dev_scope |
| `plan-reviewer` | upstream | Opus | 10-criterion plan review. | Dev_scope |
| `builder` | upstream | Opus | Implements plan tasks (code + tests). | Dev_scope |
| `validator` | upstream | Sonnet | Runs declared runners, scope-check, acceptance. | Dev_scope |
| `analyzer` | **ours** | Opus | Analysis of failed tests → `bug_in_test` / `bug_in_product`, writes `bug.md`. | Test_scope |

### D. E2E Data Flow

```
[customer / PO]
   │ /analyze "<task>"
   ▼
[business-analyst]  ◄── chat-interview ──►  [customer]
   │ write analytic/increment.md
   ▼
[validate_increment.py]  (Stop hook)
   │ ok? → continue   │ fail? → return to business-analyst
   ▼
[analytic-reviewer]   (subagent call)
   │ status=ok? → continue   │ needs_revision? → return to business-analyst
   ▼
HITL approve (in chat — user confirms)
   │
   ▼
[/dev_plan  "<context from increment.md>"]
   │ Test Infra Interview + plan write
   ▼
specs/<plan>.md  ← validate_plan.py (Stop hook) + plan-reviewer (subagent)
   │ verdict PASS / FAIL  │ FAIL → plan edits; PASS → exit plan mode (HITL approve)
   ▼
[builder]  per tasks from TaskList
   │ PostToolUse → validator_dispatcher.py (linters) on every Edit/Write
   ▼
[validator]  on final `validate-all`
   │ ok? → continue    │ fail? → return to builder
   ▼
[merge_gate]  ✗ TO ADD (either simple `/merge` command or Stop-hook on validator)
   │ approved? → commit + (optionally) tag + build Docker
   ▼
═══════════════════════════════════════════════════════════════════════════
   │ (manual Docker image deployment to the environment)
   ▼
[/test_run]  ✗ TO ADD
   │ running declared runners
   ▼
test/runs/<timestamp>/logs.ndjson + junit.xml
   │
   ▼
[analyzer]   (if there are failed tests)
   │
   ├── bug_in_test  → return to builder (fixes test)
   └── bug_in_product → bug.md → /dev_plan full cycle repeated
```

### E. Phase-to-Phase Contract Artifacts

| Artifact | Created by | Read by | Location |
|---|---|---|---|
| `analytic/original_task.txt` | user (via `business-analyst`) | `analytic-reviewer` | increment branch |
| `analytic/increment.md` | `business-analyst` | `/dev_plan`, `analytic-reviewer`, `analyzer` | increment branch |
| `analytic/review-report.json` | `analytic-reviewer` | `business-analyst` (for rework) | increment branch |
| `specs/<plan-name>.md` | `/dev_plan` | `plan-reviewer`, `builder`, `validator`, `check_diff_scope.py` | increment branch |
| `test/runs/<ts>/logs.ndjson` | `/test_run` | `analyzer` | increment branch |
| `test/runs/<ts>/bug.md` | `analyzer` | `/dev_plan` (repeat cycle) | increment branch |

### F. What Definitely Requires Prototyping (risks)

1. **`/analyze` chat-interview flow** in Claude Code. Experience shows that multi-step dialogues within a single Claude Code session require careful prompting (the agent must not stop itself prematurely). One-case spike needed.
2. **`validate_increment.py`** — the `increment.md` format must be strictly defined, otherwise the validator cannot reliably check the structure.
3. **`merge_gate`** — no clear ready-made pattern in Claude Code; we resolve it as a simple `/merge` command or a Stop-hook on `validator`.
4. **`analyzer` + bug-routing**. The semantics of "bug_in_test vs bug_in_product" requires access to product code + test code simultaneously; need to verify the context window is not exceeded for large failure sets.
5. **Corporate proxy for Anthropic API** — outside our configuration, but without it nothing will run. Dependency on the platform team.

### G. Anti-Scope (what we do NOT do at the start)

- Custom CLI / application / service (see PIVOT.md).
- Parallel worktrees per dev-agent (sequential TaskList orchestration is sufficient).
- Test layers above integration (system / e2e / load) until explicitly requested.
- Selective regression in `/test_run`.
- Analyzer failure history with verdict-flip based on thresholds (for now — 1 run = 1 verdict, no memory).
- Stop-loss on the analyzer — at the start, simply escalate to the human.
- Rich HITL gate UX — for now, ordinary "approve y/n" in the chat.
- Budget / SLA tracking.
- Multi-user mode / parallel increments.

### H. Previously Fixed Decisions That **Remain** in Effect

| Topic | Decision | Source |
|---|---|---|
| Target projects | Java + Spring Boot microservices, ~tens of KLoC, greenfield | Survey block 1 |
| Artifacts as contracts | All artifacts — files in git, not a database | Survey block 5 |
| HITL gates | (a) approve increment, (b) approve plan, (c) approve merge | Survey blocks 2–3 |
| Versioning | 1 increment = 1 branch | Survey block 5 |
| Refs as human-curated knowledge | refs/ (former tags registry) | Survey block 3 |
| MVP goal | One fully usable pass of Analytic → Dev → Test by a real business analyst | Calibration block K5 |

Changed: implementation (Java/Spring → Claude Code config), LLM stack (DeepSeek/GigaChat → Claude), CLI (`ms` → none, everything through `claude`).
