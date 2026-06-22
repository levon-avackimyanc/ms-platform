---
description: Creates a concise engineering implementation plan based on user requirements and saves it to specs directory
argument-hint: [user prompt] [orchestration prompt]
model: opus
disallowed-tools: EnterPlanMode
hooks:
  Stop:
    - hooks:
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_new_file.py
            --directory specs
            --extension .md
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_file_contains.py
            --directory specs
            --extension .md
            --contains '## Task Description'
            --contains '## Objective'
            --contains '## Relevant Files'
            --contains '## Step by Step Tasks'
            --contains '## Testing Strategy'
            --contains '## Test Infrastructure (User-Declared)'
            --contains '## Acceptance Criteria'
            --contains '## Team Orchestration'
            --contains '### Team Members'
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_plan.py
            --directory specs
            --extension .md
            --team-dir $CLAUDE_PROJECT_DIR/.claude/agents/team
---

# Plan With Team

Create a detailed implementation plan based on the user's requirements provided through the `USER_PROMPT` variable. Analyze the request, think through the implementation approach, and save a comprehensive specification document to `PLAN_OUTPUT_DIRECTORY/<name-of-plan>.md` that can be used as a blueprint for actual development work. Follow the `Instructions` and work through the `Workflow` to create the plan.

## Variables

USER_PROMPT: $1 - (Optional if INCREMENT_FILE exists) Free-text task from the user. When `analytic/increment.md` is present, USER_PROMPT is treated as an optional *refinement* on top of the increment, not the primary input.
ORCHESTRATION_PROMPT: $2 - (Optional) Guidance for team assembly, task structure, and execution strategy
INCREMENT_FILE: `analytic/increment.md` - The business increment produced by `/analyze`. When present, this is the **primary planning input**. Its companions `analytic/original_task.txt` and `analytic/review-report.json` give context.
PLAN_OUTPUT_DIRECTORY: `specs/`
TEAM_MEMBERS: `.claude/agents/team/*.md`
GENERAL_PURPOSE_AGENT: `general-purpose`

## Instructions

- **PLANNING ONLY**: Do NOT build product code or execute the plan. Your only durable output is a plan document saved to `PLAN_OUTPUT_DIRECTORY`. Spawning **read-only helper agents** is allowed and expected — the `explorer` (produces `explore/module-map.md`) and the `plan-reviewer` (reviews the plan); neither writes product code.
- **Resolve the planning input first (Analytic → Dev bridge):**
  - If `INCREMENT_FILE` (`analytic/increment.md`) exists → it is the **primary input**. Read it in full, plus `analytic/original_task.txt` (the customer's verbatim ask) and `analytic/review-report.json` (the analytic verdict) for context. A `USER_PROMPT`, if also given, is an additional refinement layered on top — never a replacement for the increment.
  - If `INCREMENT_FILE` does NOT exist and `USER_PROMPT` is provided → plan from `USER_PROMPT` as before (direct dev task, no analytic phase).
  - If neither exists → stop and ask the user to either run `/analyze` first or provide a `USER_PROMPT`.
- The increment is a **business** spec (goals, FR/NFR, Given-When-Then, acceptance). Your job is the **technical** decomposition: map its acceptance criteria onto concrete tasks, files, and unit-test coverage (integration/e2e are Test scope's job). Do not lose any acceptance criterion — every one must trace to at least one task.
- If `ORCHESTRATION_PROMPT` is provided, use it to guide team composition, task granularity, dependency structure, and parallel/sequential decisions.
- Carefully analyze the user's requirements provided in the USER_PROMPT variable
- Determine the task type (chore|feature|refactor|fix|enhancement) and complexity (simple|medium|complex)
- Think deeply (ultrathink) about the best approach to implement the requested functionality or solve the problem
- Understand the codebase directly without subagents to understand existing patterns and architecture
- Follow the Plan Format below to create a comprehensive implementation plan
- Include all required sections and conditional sections based on task type and complexity
- Generate a descriptive, kebab-case filename based on the main topic of the plan
- Save the complete implementation plan to `PLAN_OUTPUT_DIRECTORY/<descriptive-name>.md`
- Ensure the plan is detailed enough that another developer could follow it to implement the solution
- Include code examples or pseudo-code where appropriate to clarify complex concepts
- Consider edge cases, error handling, and scalability concerns
- Understand your role as the team lead. Refer to the `Team Orchestration` section for more details.
- **CRITICAL — Dev scope = product code + unit tests only**: This is a **Dev scope** plan. Its agents (`developer`, `unit-tester`, `code-reviewer`) write product code and **unit tests only**. Integration and E2E tests are owned by **Test scope** (a separate phase) and MUST NOT be planned, mandated, or assigned here. Do not create `integration-tests` / `e2e-tests` tasks in a Dev plan.
- **CRITICAL — Testing Strategy (unit)**: Every plan MUST include a `## Testing Strategy` section describing the **unit-test coverage** for this change (service logic, branches, edge cases, error paths). A dedicated `unit-tests` task MUST exist before the final validation task. Each implementation task notes what unit coverage it requires in the `**Tests**` field. Integration/E2E are explicitly deferred to Test scope.
- **CRITICAL — Plan-as-Contract**: Every plan MUST include a `## Test Infrastructure (User-Declared)` section. For a Dev plan the **Unit Layer is mandatory** and records machine-verifiable assertions: `Files glob`, `Happy-path scenarios` (named), `Runner command`, `Realism rationale` (`Infra signature` is optional for unit). The post-build hook `check_test_layers.py` and the `validator` agent rely on this section to verify what was actually built. Fill it from the codebase you observed in Workflow Step 4 (and the optional Step 4.5 clarification) — never invent it from thin air.
- **CRITICAL — Mandatory Unit Layer**: The `### Unit Layer (<stack>)` block is **mandatory and cannot be Skipped** in a Dev plan. `### Integration Layer` / `### E2E Layer` blocks are optional here; if you include them, mark them `Skipped — owned by Test scope` (the `validate_plan.py` hook accepts that). Do not declare a live Integration/E2E layer in a Dev plan.
- **CRITICAL — Unit-Tests Task**: The single combined `write-tests` task is forbidden. The plan MUST include a dedicated `unit-tests` task (assigned to the `unit-tester` agent). It gets its own context, its own `**Stack**`, and its own `**Tests**` field. Do not add `integration-tests` / `e2e-tests` tasks — those belong to Test scope.
- **CRITICAL — Context Routing**: Every task MUST include a `**Stack**` field with keywords from the **Section Routing Catalog** below. The `developer` and `unit-tester` agents use keyword-based context routing to load coding standards. Without correct keywords, they work without project standards.
  - Always include at least one **stack keyword** (Java/React/Python) to select the correct stack
  - Then add **section keywords** matching what the task actually does (error handling, testing, etc.)
  - Example: a task creating a Spring controller with error handling → `Stack: Java Spring Boot controller exception error handling`
  - Example: a `unit-tests` task with Mockito → `Stack: Java JUnit Mockito assertj test structure`
  - The Stop hook validator will reject plans where Stack keywords don't route to any section

#### Section Routing Catalog

Pick keywords from the **Trigger keywords** column. Each keyword you include loads the corresponding section into the `developer`/`unit-tester` context. These are the same tags the `explorer` agent writes into `explore/module-map.md`, so prefer reusing the tags already attached to the modules a task touches.

| Section | Trigger keywords | Add when task involves |
|---------|-----------------|----------------------|
| **Java** | | |
| `java-patterns#basics` | `java`, `spring`, `controller`, `entity`, `jpa`, `maven`, `lombok` | Any Java/Spring Boot code |
| `java-patterns#errors` | `exception`, `error handling`, `controlleradvice`, `404`, `400`, `500` | Exception classes, @ControllerAdvice, HTTP error responses |
| `java-patterns#java17` | `record`, `pattern matching`, `switch expression`, `text block`, `sealed` | Java 17 language features |
| `java-patterns#java21` | `virtual thread`, `sequenced collection` | Java 21 language features |
| **Java Testing** | | |
| `java-testing#structure` | `assertj`, `allure`, `test naming`, `test structure` | Test organization, naming, Allure annotations |
| `java-testing#integration` | `testcontainers`, `integration test`, `podman` | Integration tests with containers |
| `java-testing#http` | `mockmvc`, `resttemplate`, `http test` | HTTP/REST endpoint testing |
| `java-testing#kafka` | `kafka test`, `consumer test`, `producer test` | Kafka integration testing |
| `java-testing#jdbc` | `database test`, `repository test`, `jdbc test` | Database/repository testing |
| `java-testing#mockito` | `mockito`, `spy` | Unit tests with mocking |
| `java-testing#e2e` | `selenide`, `e2e`, `page object` | End-to-end browser testing |
| `java-testing#maven` | `surefire`, `failsafe`, `jacoco` | Maven test plugins, coverage |
| **React** | | |
| `react-patterns#core` | `react`, `component`, `hook`, `useState`, `useEffect`, `tsx` | Any React code |
| `react-patterns#nextjs` | `next.js`, `server component`, `app router`, `server action` | Next.js App Router features |
| `react-patterns#vite` | `vite`, `react-router`, `code splitting` | Vite bundler, React Router |
| **Python Patterns** | | |
| `python-patterns#layout` | `pyproject`, `ruff`, `pyright`, `pre-commit`, `src layout` | Project layout, toolchain |
| `python-patterns#typing` | `python`, `.py`, `typing`, `Protocol`, `Final`, `Literal`, `NewType`, `@override` | Type system baseline (companion auto) |
| `python-patterns#data` | `dataclass`, `frozen=True`, `slots`, `Pydantic`, `Enum`, `StrEnum` | Data modeling, value objects |
| `python-patterns#errors` | `python exception`, `ExceptionGroup`, `raise from`, `custom exception` | Error handling, exception hierarchy |
| `python-patterns#logging` | `structlog`, `python logging`, `logger.bind` | Structured logging |
| `python-patterns#io` | `pathlib`, `Path`, `context manager`, `aiofiles` | I/O, resources |
| `python-patterns#idiom` | `comprehension`, `match/case`, `functools`, `lru_cache`, `singledispatch` | Functional idioms |
| `python-patterns#fastapi` | `fastapi`, `apirouter`, `depends`, `lifespan`, `pydantic-settings`, `field_validator` | FastAPI endpoints, Pydantic |
| `python-patterns#concurrency` | `asyncio`, `gather`, `taskgroup`, `connection pool`, `cancellation`, `httpx`, `streamingresponse`, `sync клиент` | Async/concurrency for FastAPI |
| **Python Testing** | | |
| `python-testing#structure` | `pytest`, `pytest.raises`, `arrange act assert` | Pytest baseline (companion auto) |
| `python-testing#config` | `asyncio_mode`, `strict-markers`, `xfail_strict`, `conftest hierarchy` | Pytest configuration |
| `python-testing#fixtures` | `fixture`, `scope`, `factory fixture`, `polyfactory` | Fixtures, factories |
| `python-testing#parametrize` | `parametrize`, `pytest.param`, `data-driven` | Parametrized tests |
| `python-testing#integration` | `testcontainers`, `httpx asyncclient`, `respx`, `asgitransport` | Integration tests |
| `python-testing#unit` | `pytest-mock`, `mocker`, `autospec`, `freezegun` | Unit tests with mocks |
| `python-testing#property` | `hypothesis`, `property-based`, `@given` | Property-based testing |
| `python-testing#snapshot` | `syrupy`, `inline-snapshot` | Snapshot testing |
| `python-testing#async` | `pytest-asyncio`, `anyio backend` | Async tests |
| `python-testing#test-data` | `polyfactory`, `faker`, `test data builder` | Test data generation |
| `python-testing#ci` | `coverage`, `pytest-xdist`, `pytest-randomly`, `pytest-timeout`, `diff-cover` | CI gates, coverage |

### Team Orchestration

As the team lead, you have access to powerful tools for coordinating work across multiple agents. You NEVER write code directly - you orchestrate team members using these tools.

#### Task Management Tools

**TaskCreate** - Create tasks in the shared task list:
```typescript
TaskCreate({
  subject: "Implement user authentication",
  description: "Create login/logout endpoints with JWT tokens. See specs/auth-plan.md for details.",
  activeForm: "Implementing authentication"  // Shows in UI spinner when in_progress
})
// Returns: taskId (e.g., "1")
```

**TaskUpdate** - Update task status, assignment, or dependencies:
```typescript
TaskUpdate({
  taskId: "1",
  status: "in_progress",  // pending → in_progress → completed
  owner: "developer-auth"   // Assign to specific team member
})
```

**TaskList** - View all tasks and their status:
```typescript
TaskList({})
// Returns: Array of tasks with id, subject, status, owner, blockedBy
```

**TaskGet** - Get full details of a specific task:
```typescript
TaskGet({ taskId: "1" })
// Returns: Full task including description
```

#### Task Dependencies

Use `addBlockedBy` to create sequential dependencies - blocked tasks cannot start until dependencies complete:

```typescript
// Task 2 depends on Task 1
TaskUpdate({
  taskId: "2",
  addBlockedBy: ["1"]  // Task 2 blocked until Task 1 completes
})

// Task 3 depends on both Task 1 and Task 2
TaskUpdate({
  taskId: "3",
  addBlockedBy: ["1", "2"]
})
```

Dependency chain example:
```
Task 1: Setup foundation     → no dependencies
Task 2: Implement feature    → blockedBy: ["1"]
Task 3: Write tests          → blockedBy: ["2"]
Task 4: Final validation     → blockedBy: ["1", "2", "3"]
```

#### Owner Assignment

Assign tasks to specific team members for clear accountability:

```typescript
// Assign task to a specific developer
TaskUpdate({
  taskId: "1",
  owner: "developer-api"
})

// Team members check for their assignments
TaskList({})  // Filter by owner to find assigned work
```

#### Agent Deployment with Agent Tool

**Agent** — spawn a subagent to do work (formerly named `Task`; renamed in Claude Code 2.x):
```typescript
Agent({
  description: "Implement auth endpoints",
  prompt: "Implement the authentication endpoints as specified in Task 1...",
  subagent_type: "general-purpose",
  model: "opus",            // "opus" for complex reasoning, "sonnet" balanced, "haiku" for very simple
  run_in_background: false  // true for parallel execution
})
// Returns: agentId (e.g., "a1b2c3")
```

#### Resume Pattern (continue an existing agent)

The `Agent` tool no longer accepts a `resume` parameter. To continue a previously spawned agent with preserved context, use the `SendMessage` tool, passing the agent's id or name as `to`:

```typescript
// First deployment — fresh agent
Agent({
  description: "Build user service",
  prompt: "Create the user service with CRUD operations...",
  subagent_type: "general-purpose"
})
// Returns: agentId: "abc123"

// Later — resume SAME agent with full context preserved
SendMessage({
  to: "abc123",
  message: "Now add input validation to the endpoints you created..."
})
```

When to resume vs start fresh:
- **Resume (`SendMessage`)**: continuing related work, agent needs prior context
- **Fresh (`Agent`)**: unrelated task, clean slate preferred

#### Parallel Execution

Run multiple agents simultaneously with `run_in_background: true`:

```typescript
// Launch multiple agents in parallel
Agent({
  description: "Build API endpoints",
  prompt: "...",
  subagent_type: "general-purpose",
  run_in_background: true
})
// Returns immediately with agentId and an output_file path

Agent({
  description: "Build frontend components",
  prompt: "...",
  subagent_type: "general-purpose",
  run_in_background: true
})
// Both agents now working simultaneously
```

To monitor a background agent, prefer `Read` on the returned `output_file` path, or stream events with `Monitor`:

```typescript
// Non-blocking check — read accumulated output so far
Read({ file_path: "<output_file from Agent return>" })

// Stream events as they arrive (blocks per-event, exits on stop)
Monitor({ file_path: "<output_file>", pattern: "..." })
```

**Note:** `TaskOutput` is deprecated (Claude Code 2.1.91+) in favor of the Read/Monitor pattern above.

#### Orchestration Workflow

1. **Create tasks** with `TaskCreate` for each step in the plan
2. **Set dependencies** with `TaskUpdate` + `addBlockedBy`
3. **Assign owners** with `TaskUpdate` + `owner`
4. **Deploy agents** with `Agent` to execute assigned work
5. **Monitor progress** with `TaskList` (status) and `Read` on each agent's `output_file` (or `Monitor` to stream)
6. **Resume agents** with `SendMessage` + `to: <agentId>` for follow-up work
7. **Mark complete** with `TaskUpdate` + `status: "completed"`

## Workflow

IMPORTANT: **PLANNING ONLY** - Do not build product code or execute the plan. Output is a plan document. (Read-only helper agents — `explorer`, `plan-reviewer` — may be spawned; they don't write product code.)

0. Resolve Input - Check whether `analytic/increment.md` exists (`ls analytic/increment.md`). If it does, read `analytic/increment.md`, `analytic/original_task.txt`, and `analytic/review-report.json`, and treat the increment as the primary requirement source per the Instructions above. If it does not, fall back to `USER_PROMPT`. State in one line which input mode you are in before proceeding.
1. Analyze Requirements - Parse the resolved input (increment + optional USER_PROMPT, or USER_PROMPT alone) to understand the core problem and desired outcome. When planning from an increment, extract its **acceptance criteria** into an explicit checklist — each must map to a task later (Step 9). If Serena MCP tools are available, call `read_memory` and `list_memories` to check for existing knowledge about related features or past decisions.
2. **Explore OpenSpec (if available)** — Check if OpenSpec is initialized by running:
   ```bash
   openspec list --specs --json 2>/dev/null
   ```
   - If the command fails or returns empty → OpenSpec not installed/initialized. Skip with note: "OpenSpec not available — skipping spec exploration." Proceed directly to Interview Round 1.
   - If specs exist, extract keywords from USER_PROMPT and search for related specifications:
     ```bash
     openspec show <matching-spec> --json --requirements
     ```
   - Also check for active changes that might overlap:
     ```bash
     openspec list --changes --json 2>/dev/null
     ```
   - Summarize findings: related requirements (MUST/SHOULD/MAY), scenarios (Given/When/Then), active changes, and carry these into Interview Round 1. If existing specs define requirements for this domain, prepare questions about whether the new feature should MODIFY existing requirements or ADD alongside them.
3. **Clarify Requirements (Interview Round 1)** — If OpenSpec findings were produced in Step 2, incorporate them into your questions — ask about conflicts with existing requirements, whether to extend or modify existing specs, and whether historical design decisions still apply. Analyze the USER_PROMPT for ambiguities before reading the codebase. Ask when:
   - **Contradiction detected** — the prompt contains two statements that conflict or imply mutually exclusive approaches (e.g., "return 409" and "silently succeed" for the same case)
   - **Underspecified behavior** — the prompt describes a feature but not what happens in key user states (unauthorized, empty data, error). If the prompt says "user clicks heart" but doesn't say what unauthorized user sees — ask.
   - **Multiple valid approaches** — you see two or more reasonable ways to implement something, each with different tradeoffs. Present both with pros/cons and ask which one.
   - **Design/UX choices** — visual placement, copy text, interaction details that are matters of taste, not engineering (e.g., "badge next to text or on icon?", "what message for empty state?")
   - **Scope ambiguity** — it's unclear whether adjacent features are in or out of scope (e.g., "also update admin panel?", "include tests in this task?")
   - **Spec conflict** — OpenSpec findings reveal that the requested feature would modify, contradict, or overlap with existing living requirements. Ask the user how to reconcile.
   - Do NOT ask about things that have exactly one obvious answer from the prompt.
   - Do NOT ask about implementation details you can determine from the codebase — save those for step 5.
   - Use `AskUserQuestion` (supports 1-4 questions per call, call multiple times if needed).
3.5. **Explore & Tag Modules (module-map)** — The `**Stack**` routing and the dev subagents (`developer`/`code-reviewer`) consume `explore/module-map.md` (module → tags). Make sure it exists and is current before Step 4:
   - Check `ls explore/module-map.md`. If it exists and the repo has not materially changed since it was written, **reuse it** and proceed to Step 4 (the map is reusable across plans — don't regenerate needlessly).
   - If missing or stale: identify the project's module boundaries — each non-aggregator `pom.xml`/`build.gradle`, each `package.json`/workspace, each Python package with `pyproject.toml` or a top-level `src` package.
   - Spawn the `explorer` agent (`subagent_type: "explorer"`) to map them. For a multi-module repo, **partition the modules and spawn several `explorer` agents in parallel** (`run_in_background: true`), giving each its module subset via an `ASSIGNED_MODULES` line in the prompt; for a single/small project, one explorer covering the whole repo is enough. Wait for all explorers to finish before Step 4.
   - Each explorer appends its rows to `explore/module-map.md` (one row per module) using tags from the **Section Routing Catalog**. This is planning input only — explorer is read-mostly and writes nothing but the map.
4. Understand Codebase - Without subagents, directly understand existing patterns, architecture, and relevant files. **Read `explore/module-map.md` first** (produced/refreshed in Step 3.5) — it maps each module to its stack and tags; reuse those tags when assigning each task's `**Stack**` field. **In addition to architecture, analyze the unit-test landscape of the project**: which test frameworks/libraries are present (read `pom.xml`/`build.gradle`/`pyproject.toml`/`package.json`); the naming/layout convention for unit tests in this repo (`*Test.java`, `tests/unit/`, `*.test.ts`, etc.); **which command actually runs the unit tests today** (Surefire `mvn test` / `pytest tests/unit` / `npm test` / a Gradle task). This is the input to the Unit Layer block (Step 7) and the optional Step 4.5 clarification. Integration/E2E infrastructure is Test scope's concern — note it only in passing. If Serena MCP tools are available, prefer `find_symbol` and `get_symbols_overview` for navigating classes, methods, and dependencies instead of manual Glob/Grep. If Serena is not available, use Glob/Grep/Read as usual.
4.5. **Unit Layer Clarification (conditional)** — Dev scope declares only the **Unit Layer**. From what you observed in Step 4 you can usually fill it yourself (glob, runner command, scenarios, rationale) without asking. **Only run an `AskUserQuestion` interview when the unit-test toolchain is genuinely ambiguous**, e.g.:
   - More than one plausible unit-test runner/profile exists and it's unclear which the repo treats as the unit suite.
   - The repo has no unit tests yet, so the convention (framework, directory, runner) must be chosen with the user.
   - The set of happy-path unit scenarios for this change is unclear and you'd otherwise guess.
   - If none of these apply, **skip the interview** and declare the Unit Layer directly from your Step 4 observations.
   - Do NOT ask about integration/e2e infrastructure here — those layers belong to Test scope and are out of this plan's scope.
   - Record any answers verbatim; they (plus your Step 4 observations) become the `### Unit Layer (<stack>)` block: Files glob, Happy-path scenarios, Runner command, Realism rationale.
5. **Clarify Implementation (Interview Round 2)** — Now that you know the codebase, check for implementation-specific ambiguities. Ask when:
   - **Multiple patterns exist** — the codebase has more than one way to solve this type of problem, and it's not clear which fits better (e.g., "CartService uses optimistic UI, OrderService uses server-confirmed — which pattern for favorites?"). Present both with pros/cons.
   - **Technical tradeoff with no clear winner** — both options are valid and the choice depends on priorities the user hasn't stated (e.g., "denormalized counter is faster but can drift vs. COUNT query is accurate but slower")
   - **Integration ambiguity** — the existing code can accommodate the new feature in more than one place or way (e.g., "add to existing DTO or create a new one?", "extend current controller or create separate?")
   - **Discovered edge case** — reading the code revealed a scenario the prompt didn't address (e.g., "the material can be soft-deleted — should favorites to deleted materials auto-remove?")
   - Do NOT ask about things where the codebase has exactly one established pattern — just follow it.
   - Skip this step entirely if every implementation choice has a single obvious answer from the code.
6. Design Solution - Develop technical approach including architecture decisions and implementation strategy
7. Define Testing Strategy + Test Infrastructure (User-Declared) - Plan the **unit-test** coverage for this change: service logic, branches, edge cases, error paths. Map each unit test to the source code it validates and reference existing unit-test patterns from the codebase. **Then fill in `## Test Infrastructure (User-Declared)`**: per stack, write the mandatory `### Unit Layer (<stack>)` block with its machine-verifiable fields (Files glob, Happy-path scenarios, Runner command, Realism rationale; Infra signature optional for unit). If you include `### Integration Layer` / `### E2E Layer` blocks at all, mark them `Skipped — owned by Test scope`. Multi-stack projects produce one Unit Layer block per stack.
8. Define Team Members - Use `ORCHESTRATION_PROMPT` (if provided) to guide team composition. Identify from `.claude/agents/team/*.md` or use `general-purpose`. A Dev scope team is: one or more `developer` members (product code), a paired `unit-tester` per developer (unit tests), a `code-reviewer` (per-developer diff review), and a `validator` for final validation. Document in plan.
9. Define Step by Step Tasks - Use `ORCHESTRATION_PROMPT` (if provided) to guide task granularity and parallel/sequential structure. Write out tasks with IDs, dependencies, assignments, and `**Tests**` field. **The plan MUST include a dedicated `unit-tests` task (assigned to `unit-tester`), then a `code-review` task (assigned to `code-reviewer`), then a final `validate-all` task (assigned to `validator`).** The single combined `write-tests` task is forbidden, and `integration-tests` / `e2e-tests` tasks MUST NOT appear — those belong to Test scope. **Greenfield projects:** the first scaffold/setup task MUST create a `.gitignore` covering build output (`target/`, `build/`, `dist/`, `node_modules/`, `__pycache__/`) so generated artifacts are not later flagged by the Surgical Scope check (`check_diff_scope.py`); list `.gitignore` under `### New Files`. Document in plan.
10. Generate Filename - Create a descriptive kebab-case filename based on the plan's main topic
11. Save Plan - Write the plan to `PLAN_OUTPUT_DIRECTORY/<filename>.md`
12. **Plan Review** — Run structural validation and architectural review on the saved plan. This ensures plan quality BEFORE OpenSpec artifacts are generated.

    **Structural check:**
    ```bash
    uv run --script .claude/hooks/validators/validate_plan.py --file <plan-path> --team-dir .claude/agents/team
    ```

    **Content review** (spawn plan-reviewer agent):
    ```
    Agent({
      subagent_type: "plan-reviewer",
      description: "Review plan before OpenSpec propose",
      prompt: "Review the plan at <plan-path>. Check all 10 criteria and return a structured verdict."
    })
    ```

    - If structural check fails or review verdict is **FAIL** → show issues, ask user to fix or abort. Do NOT proceed to Step 13.
    - If both pass → proceed to OpenSpec Propose.
13. **OpenSpec Propose (if available)** — If OpenSpec is initialized (Step 2 succeeded), create OpenSpec change artifacts from the reviewed plan.

    Run the following to check availability:
    ```bash
    openspec list --specs --json 2>/dev/null
    ```

    If available, provide the plan context to OpenSpec by referencing the saved plan:
    - The change name should match the plan filename (kebab-case)
    - Tell the user: "Plan review passed. Creating OpenSpec change artifacts..."
    - Execute `/openspec-propose` with context from the plan: task description, objective, solution approach, implementation phases, and step by step tasks
    - OpenSpec will create: `openspec/changes/<name>/` with proposal.md, specs/, design.md, tasks.md

    If OpenSpec is not available, skip with note: "OpenSpec not initialized — skipping artifact generation."
14. Report - Follow the `Report` section to provide a summary of key components
15. Record Knowledge (Serena only) - If Serena MCP tools are available, call `write_memory` with a summary of: what was planned, key architectural decisions, patterns chosen, and any tradeoffs resolved during interviews. Use the plan filename as memory name. If Serena is not available, skip this step.

## Plan Format

- IMPORTANT: Replace <requested content> with the requested content. It's been templated for you to replace. Consider it a micro prompt to replace the requested content.
- IMPORTANT: Anything that's NOT in <requested content> should be written EXACTLY as it appears in the format below.
- IMPORTANT: Follow this EXACT format when creating implementation plans:

```md
# Plan: <task name>

## Task Description
<describe the task in detail based on the prompt>

## Objective
<clearly state what will be accomplished when this plan is complete>

<if task_type is feature or complexity is medium/complex, include these sections:>
## Problem Statement
<clearly define the specific problem or opportunity this task addresses>

## Solution Approach
<describe the proposed solution approach and how it addresses the objective>
</if>

## Relevant Files
Use these files to complete the task:

<list files relevant to the task with bullet points explaining why. Include new files to be created under an h3 'New Files' section if needed>

<if complexity is medium/complex, include this section:>
## Implementation Phases
### Phase 1: Foundation
<describe any foundational work needed>

### Phase 2: Core Implementation
<describe the main implementation work>

### Phase 3: Integration & Polish
<describe integration, testing, and final touches>
</if>

## Team Orchestration

- You operate as the team lead and orchestrate the team to execute the plan.
- You're responsible for deploying the right team members with the right context to execute the plan.
- IMPORTANT: You NEVER operate directly on the codebase. You use the `Agent` tool to deploy team members for building, validating, testing, and other tasks, and the `Task*` tools (TaskCreate/TaskUpdate/TaskList) to coordinate their work.
  - This is critical. Your job is to act as a high-level director of the team, not an implementer.
  - Your role is to validate all work is going well and make sure the team is on track to complete the plan.
  - You orchestrate by using the `Agent` tool to deploy team members and the `Task*` tools to manage coordination between them.
  - Communication is paramount. You'll use `SendMessage` to resume team members and the `Task*` tools to track their progress against the plan.
- Take note of the session id of each team member. This is how you'll reference them.

### Team Members
<list the team members you'll use to execute the plan>

- Developer
  - Name: <unique name for this developer — lets you and other members reference THIS developer. There may be several; the name makes them unique.>
  - Role: <the module(s)/feature this developer owns — product code only>
  - Agent Type: developer
  - Resume: <default true. Lets the agent continue with the same context. Pass false to start fresh.>
- Unit-Tester
  - Name: <unique name, typically paired to a specific developer>
  - Role: <unit tests for the code its paired developer wrote>
  - Agent Type: unit-tester
  - Resume: <default true>
- Code-Reviewer
  - Name: <unique name>
  - Role: <per-developer diff review, PASS/FAIL verdict — read-only>
  - Agent Type: code-reviewer
  - Resume: <default true>
- Validator
  - Name: <unique name>
  - Role: <final validation: runs the Unit Layer runner and checks acceptance criteria>
  - Agent Type: validator
  - Resume: <default true>
- <continue with additional team members as needed in the same format above — Dev scope uses developer / unit-tester / code-reviewer / validator (or general-purpose); integration/e2e test members belong to Test scope>

## Testing Strategy

**Dev scope writes unit tests only.** Integration and E2E coverage are owned by Test scope (a separate phase) and are not planned here.

### Unit Tests
<list unit tests to write: service logic, utility functions, component rendering, hooks, branches, edge cases, error paths. Each test class/module mirroring a source class/module.>

### Deferred to Test scope
<one line noting which integration/API and UI E2E flows this change will need later — for the Test scope hand-off, NOT planned or built in this Dev plan.>

## Test Infrastructure (User-Declared)

This section is the machine-verifiable contract that `check_test_layers.py` and the `validator` agent enforce after build. For a Dev plan, fill the **Unit Layer** from your Step 4 codebase observations (and the optional Step 4.5 clarification). Multi-stack projects produce one Unit Layer block per stack.

### Unit Layer (<stack name, e.g. Java | Python | React>)  — MANDATORY, never Skipped
- **Status:** Active
- **Files glob:** `<glob pattern locating unit test files in this repo, e.g. src/test/java/**/*Test.java | tests/unit/**/*.py>`
- **Infra signature (regex, optional for unit):** `<regex that proves these are unit tests, or "n/a">`
- **Happy-path scenarios (≥1 named):**
  - `<ClassName#methodName or describe>it or path/to/test::test_name>`
- **Runner command:** `<exact command this repo uses to run these unit tests, e.g. mvn test | pytest tests/unit>`
- **Realism rationale:** `<one sentence: why this is the appropriate unit-level setup for this repo>`

<!--
Integration / E2E layers are OWNED BY TEST SCOPE and are NOT part of a Dev plan.
Do not declare a live Integration/E2E layer here. If you include the blocks at all,
mark them `Skipped — owned by Test scope`, e.g.:

### Integration Layer (<stack name>)
- **Status:** Skipped — owned by Test scope

### E2E Layer (<stack name>)
- **Status:** Skipped — owned by Test scope
-->


## Step by Step Tasks

- IMPORTANT: Execute every step in order, top to bottom. Each task maps directly to a `TaskCreate` call.
- Before you start, run `TaskCreate` to create the initial task list that all team members can see and execute.

<list step by step tasks as h3 headers. Start with foundational work, then core implementation, then testing, then validation.>

### 1. <First Task Name>
- **Task ID**: <unique kebab-case identifier, e.g., "setup-database">
- **Depends On**: <Task ID(s) this depends on, or "none" if no dependencies>
- **Assigned To**: <developer member name from Team Members section>
- **Agent Type**: developer
- **Stack**: <technology keywords for context routing, e.g., "Java Spring Boot JPA", "React Next.js", "Python FastAPI">
- **Parallel**: <true if can run alongside other tasks, false if must be sequential>
- **Tests**: <what unit coverage this task's code needs from Testing Strategy, e.g., "Unit: FavoriteServiceTest — add/remove/check branches.">
- <specific action to complete>
- <specific action to complete>

### 2. <Second Task Name>
- **Task ID**: <unique-id>
- **Depends On**: <previous Task ID, e.g., "setup-database">
- **Assigned To**: <developer member name>
- **Agent Type**: developer
- **Stack**: <technology keywords for context routing>
- **Parallel**: <true/false>
- **Tests**: <what unit coverage this task's code needs>
- <specific action>
- <specific action>

### 3. <Continue Pattern>

### N-2. <Write Unit Tests>
- **Task ID**: unit-tests
- **Depends On**: <all implementation task IDs>
- **Assigned To**: <unit-tester member name>
- **Agent Type**: unit-tester
- **Stack**: <unit-testing keywords, e.g., "Java JUnit Mockito assertj test structure" or "Python pytest pytest-mock unit" or "React jest testing-library tsx unit">
- **Parallel**: <true if independent of other unit batches>
- Write unit tests as defined in Testing Strategy and the `### Unit Layer (<stack>)` block
- Cover service logic, branches, edge cases, error paths; mock external dependencies
- Follow project test patterns (reference existing test files from Relevant Files)
- NOTE: do NOT write integration/e2e tests here — those are Test scope's job

### N-1. <Code Review>
- **Task ID**: code-review
- **Depends On**: <all implementation + unit-tests task IDs>
- **Assigned To**: <code-reviewer member name>
- **Agent Type**: code-reviewer
- **Stack**: <full stack keywords for the reviewed diff>
- **Parallel**: false
- Read-only semantic review of the developer diff (correctness, task alignment, tag/stack fit, quality, test sanity)
- Return a structured PASS/FAIL verdict; on FAIL the developer fixes the flagged issues and review re-runs

### N. <Final Validation Task>
- **Task ID**: validate-all
- **Depends On**: <all previous Task IDs including unit-tests and code-review>
- **Assigned To**: <validator member name>
- **Agent Type**: validator
- **Stack**: <full stack keywords for validation>
- **Parallel**: false
- Run all validation commands
- For the (non-Skipped) Unit Layer in `## Test Infrastructure (User-Declared)`, execute the declared `Runner command` verbatim and verify that **tests actually ran** (parse runner output for "Tests run: N" / "N passed" — N must be ≥ number of declared unit scenarios)
- Run `check_test_layers.py` post-build hook (already covered by `/smart_build` Step 4.5, but verify here too)
- Verify acceptance criteria met

<continue with additional tasks as needed. Agent types must exist in .claude/agents/team/*.md>

## Acceptance Criteria
<list specific, measurable criteria that must be met for the task to be considered complete>

## Validation Commands
Execute these commands to validate the task is complete:

<list specific commands to validate the work. Be precise about what to run>
- Example: `uv run python -m py_compile apps/*.py` - Test to ensure the code compiles

## Notes
<optional additional context, considerations, or dependencies. If new libraries are needed, specify using `uv add`>
```

## Report

After creating and saving the implementation plan, provide a concise report with the following format:

```
✅ Implementation Plan Created

File: PLAN_OUTPUT_DIRECTORY/<filename>.md
Topic: <brief description of what the plan covers>
Key Components:
- <main component 1>
- <main component 2>
- <main component 3>

Team Task List:
- <list of tasks, and owner (concise)>

Team members:
- <list of team members and their roles (concise)>

OpenSpec Change: openspec/changes/<name>/ (if created)

When you're ready, you can execute the plan in a new agent by running:
/smart_build <replace with path to plan>
```
