# MVP-0 Implementation Plan

**Status:** v2, rewritten 2026-06-11 after pivot to Claude Code backend.
**Paired documents:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md), [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md), [`PIVOT.md`](./PIVOT.md).

> The old roadmap described a 2-week sprint for Java/Spring + 3 workstreams (A/B/C — platform/Gateway/domain). It is obsolete. The scope of work has shrunk significantly — the upstream `.claude/` config covers ~80% of Dev_scope.

---

## 1. Context and Assumptions

| Parameter | Value |
|---|---|
| MVP-0 timeline | **2 weeks (10 working days)** |
| Team | **1–2 people** (scope shrank after the pivot) |
| Goal | **A genuinely usable tool** — by the end of week 2, the team's business analyst actually writes a meaningful `increment.md` via `/analyze`, then goes through the full Dev + Test cycle on a toy service |
| Target project | **A "toy" tutorial microservice on Spring Boot**, purpose-built for the MVP |
| Environment | **Claude Code CLI locally** + `uv` (for Python hooks) |
| LLM access | **Claude via corporate proxy to the Anthropic API** — must be ready before the start (see risks) |
| What we are building | Not an application, but **a `.claude/` configuration** for Claude Code. No pom.xml / src/ / custom CLI. |
| Upstream state | Pulled into the `ms-platform` repo. Ready to use as-is. |

---

## 2. Toy Microservice

A simple Spring Boot service for greenfield increments. Needed to run the full cycle.

Minimum:
- 2–3 REST endpoints (CRUD over one entity + search),
- Spring Data JPA + H2 (or Testcontainers Postgres),
- structure `api / service / repo / model`,
- starter set of unit tests (~60% coverage), brief README.

**MVP-0 increment case:** "add a new endpoint + a new field to the entity + business validation".

Where it lives: **a separate git repo** (not inside `ms-platform`), name — at the user's discretion (e.g., `ms-platform-toy`). `.claude/` is installed there via the `install.sh` from our repository.

---

## 3. MVP-0 Composition

| Component | What is included | What is deferred |
|---|---|---|
| **Analytic_scope** (new, ours) | `business-analyst.md`, `analytic-reviewer.md`, `commands/analyze.md`, `hooks/validators/validate_increment.py` | — (everything necessary is in MVP-0) |
| **Dev_scope** (upstream as-is + our HITL) | `/dev_plan`, builder, plan-reviewer, validator, linters — WITHOUT CHANGES. Adding `commands/merge_gate.md`. | worktree parallelism, new domain refs (only if needed) |
| **Test_scope (lite)** | `commands/test_run.md`, `agents/analyzer.md` | system / e2e UI / load tests, selective regression, analyzer failure history with verdict-flip |
| **Refs** | upstream as-is (`java-patterns.md`, `java-testing.md`) | domain refs (RAG/MCP/Liquibase) — only when explicitly needed |
| **HITL gates** | (a) approve `increment.md` in the chat with the customer; (b) ExitPlanMode after `/dev_plan`; (c) `merge_gate` before commit | rich diff-view, multi-line comments |
| **Observability** | built-in Claude Code trace; Stop-hooks in logs | custom tracing / metrics / dashboards |

---

## 4. Day-by-Day Schedule

Work distribution — between 2 developers. If only one is working — sequentially, the total timeline may grow to 12–14 days.

### Week 1 — Analytic_scope + First Pass Through Dev

#### Day 1 — Setup
- **A** (or the sole contributor): verify that Claude Code is installed; run upstream `install.sh` locally; run `/dev_plan` with no arguments on any toy project — the template should open. This is the `.claude/` integration smoke test.
- **B** (if present): create the toy microservice in a separate repo (CRUD + Spring Data + tests). Connect `.claude/` to it via `install.sh`.

**Day 1 Milestone:** Claude Code in the toy project repository runs `/dev_plan` without errors.

#### Day 2 — `validate_increment.py`
- Write the Python script `validate_increment.py` (4 checks from AGENTS_SPECIFICATION section 4.1).
- Tests for it — pytest with test `increment.md` files (one valid + 4 invalid cases for each check).
- Do not wire it into the Claude Code Stop-hook yet; run manually for debugging.

**Day 2 Milestone:** `validate_increment.py` runs, returns 0 / ≠ 0 + JSON report on test inputs.

#### Day 3 — `business-analyst` agent
- Place `agents/business-analyst.md` from the draft prompt (AGENTS_SPECIFICATION section 2.1).
- Run manually: call the agent, simulate the customer; verify it writes `analytic/increment.md` with the correct structure.
- Fine-tune the prompt based on the results.

**Day 3 Milestone:** in any repo, `business-analyst` writes a valid `increment.md` (its run through `validate_increment.py` is green).

#### Day 4 — `analytic-reviewer` + `commands/analyze.md`
- Place `agents/analytic-reviewer.md` + `commands/analyze.md` (with `validate_increment.py` connected as a Stop-hook).
- Run `/analyze "<task>"` end-to-end: chat-interview → write → validate → review → HITL approve.
- On an intentionally contradictory task — verify that `analytic-reviewer` catches it and returns `needs_revision`.

**Day 4 Milestone:** `/analyze` works end-to-end: validate → review → approve.

#### Day 5 — Transition to Dev (focus on the ready-made upstream)
- No code: on the approved `increment.md`, run `/dev_plan` (the ready-made upstream command).
- Go through the Test Infra Interview, let plan-reviewer make its critique, choose ExitPlanMode (HITL plan approval).
- Run builder; linters run in parallel via the PostToolUse hook.
- Reach the point where `validator` issues PASS.

**Week 1 Milestone:** `/analyze` → `/dev_plan` → builder → validator(PASS) on the toy microservice. Code + tests are in branch `increment/<id>`.

### Week 2 — `merge_gate`, Test_scope (lite), Full Cycle and Demo

#### Day 6 — `commands/merge_gate.md`
- Implement `merge_gate` as a command (no custom hooks).
- Scenarios: approve → commit + tag; reject → save `rejection_comment.txt` + notify about the option to restart `/dev_plan`.

**Day 6 Milestone:** `merge_gate` commits the increment to the main branch of the toy repo.

#### Day 7 — `analyzer.md` agent
- Place `agents/analyzer.md` (draft prompt).
- Prepare test `test/runs/<ts>/` fixtures (logs + junit) — intentionally two scenarios: "bug in test" and "bug in product" (where the product violates `increment.md`).
- Run `analyzer` manually; verify that the verdicts are correct and `bug.md` is written only in the second case.

**Day 7 Milestone:** `analyzer` correctly distinguishes `bug_in_test` and `bug_in_product` on two fixtures.

#### Day 8 — `commands/test_run.md`
- Implement `test_run` as a command.
- On the already-incremented toy service, run `/test_run` — it should pick up the declared runner from the plan, report success on green tests, and call analyzer on an intentional failure (the user manually breaks one test).

**Day 8 Milestone:** `/test_run` runs real `mvn verify` and on failure launches analyzer.

#### Day 9 — End-to-End + Polish
- **Real end-to-end run**: a human business analyst (the developer can play this role) goes through `/analyze` → ExitPlanMode → `/merge_gate` → manual deployment → `/test_run`.
- Intentionally break the product (change validation logic in one of the endpoints) — verify that `/test_run` → `analyzer` → `bug.md` → new `/dev_plan` with this `bug.md` as context → builder fixes it.
- Polish agent prompts based on the results.

**Day 9 Milestone:** full bug-routing loop works.

#### Day 10 — Demo + Documentation
- Write the README section "How to Run": installing Claude Code, `install.sh`, command sequence.
- Update `AGENTS_SPECIFICATION.md` based on the actual implementation (if the draft prompts changed significantly).
- Run the demo scenario for colleagues.

**Week 2 Milestone = MVP-0:** one full-cycle scenario of Analytic → Dev → Test → BUG → Dev on a toy microservice works end-to-end, operated by a real team business analyst.

---

## 5. Definition of Done — MVP-0

Concrete binary criteria:

- [ ] `install.sh` installs `.claude/` into the toy repo without errors.
- [ ] `/analyze "<task>"` starts a chat-interview, writes `analytic/increment.md` with valid structure, passes `validate_increment.py` green and `analytic-reviewer` with verdict `ok`, and after approval — reports readiness for `/dev_plan`.
- [ ] `/dev_plan` (upstream) accepts context from `increment.md`, passes `plan-reviewer`, passes ExitPlanMode approval, then builder + validator complete with PASS on the toy.
- [ ] `/merge_gate` commits the increment.
- [ ] `/test_run` runs the declared runner; on intentional product breakage calls `analyzer`, which writes `bug.md`.
- [ ] `/dev_plan` re-run with `bug.md` as context brings the fix to PASS.
- [ ] A real team member (not a developer from the MVP team) uses `/analyze` for at least one real business case and gets a meaningful `increment.md` without manual edits.
- [ ] `README.md` contains a "Start from scratch" section with verified commands.

---

## 6. Anti-Scope (what we do NOT do in MVP-0)

To prevent scope creep:

- Parallel execution of multiple builders in worktrees.
- Test levels above integration (system / e2e / load).
- Selective regression in `/test_run` — always a full run.
- Analyzer failure history with verdict-flip and stop-loss.
- Rich HITL gate UX (diff view, multi-line comments) — plain text responses.
- Budget / SLA / cost tracing.
- Custom LLM Gateway / CLI / service / database (see PIVOT.md).
- Corporate domain refs "just in case" — only added when explicitly needed.
- Multi-user / parallel increments.
- Custom mechanism for receiving messages from messengers.

---

## 7. Technical Risks and Mitigations

| Risk | Probability | Mitigation |
|---|---|---|
| Claude Code does not work through the corporate proxy to Anthropic | low (if already working for colleagues) | Day 1 — spike: verify that the proxy is configured. If not — blocker; wait for the platform team. |
| `/analyze` chat-interview fails due to its multi-step nature | medium | Days 2–3 — separate spike. If it doesn't work with a single agent — split into subagent calls inside one command. |
| `validate_increment.py` is too strict — rejects meaningful interviews | medium | Start with minimal checks; then add strictness based on real experience. |
| `analyzer` context window cannot fit (product + tests + logs + spec) | medium | Use Serena MCP for focused navigation, do not load everything into context. |
| Toy microservice is too trivial — the pipeline shows no value | medium | Build it close-to-real from the start: JPA + validations + layered architecture + baseline tests. |
| Conflict between upstream `.claude/` and our new files | low | Our files have separate names (`business-analyst.md` ≠ `team/builder.md`); no conflicts expected. |

---

## 8. After MVP-0 — Natural Next Iterations

**MVP-1 (~2–3 weeks after MVP-0): feature completeness**
- Test levels: system + e2e.
- Selective regression in `/test_run` (only tests relevant to changed files).
- Analyzer failure history + verdict-flip after ≥ 2 repetitions of `bug_in_test`.
- First-wave domain refs (RAG conventions, MCP conventions, corporate Spring style).
- A real (non-toy) microservice.

**MVP-2 (~1 month after MVP-1): scaling**
- Worktree parallelism (if it becomes a bottleneck).
- Parallel increments (multiple branches in progress).
- Pipeline state dashboard.

**MVP-3 (on request from the platform team):**
- Adapter for a corporate LLM proxy (GigaChat / DeepSeek) — if Anthropic-only is deemed unacceptable in production.

---

## 9. Open Questions on the Plan

1. **Who executes.** Is the team working on MVP-0 with 1 or 2 people? The timeline depends.
2. **Toy microservice.** Do we build it from scratch for the MVP, or is there a suitable tutorial service in the team that can be adapted?
3. **Corporate proxy to Anthropic.** Is it ready? If not — fix this blocker first, otherwise the MVP won't run.
4. **Real business analyst for the final DoD.** Who on the team is ready to use `/analyze` for their real increment? It is desirable to know the name in advance — it influences the wording of `business-analyst.md`.
5. **Test repo or shared.** Do autotests live alongside the product (minimal path) or in a separate repo (as in the old design)? Recommendation: alongside the product for MVP-0; separate — later.
