---
description: Start of Analytic_scope. The orchestrator conducts the customer interview in the main thread, delegates writing analytic/increment.md to the business-analyst agent, runs validation and semantic review via analytic-reviewer, and ends with HITL approve.
argument-hint: "[brief task statement from the customer]"
model: sonnet
disallowed-tools: EnterPlanMode
---

# /analyze

Start of Analytic_scope. Converts a freely formulated customer task
into `analytic/increment.md` with the required structure, with customer confirmation.

Output — the `analytic/` directory in the current branch with three files:

- `analytic/original_task.txt` — the customer's original task, verbatim.
- `analytic/increment.md` — increment specification per the template from
  `.claude/config/increment_template.yaml`.
- `analytic/review-report.json` — the last verdict from `analytic-reviewer`.

Next step after approve — `/build_scopes` (parallel Dev + Test).

## Variables

- **USER_TASK** = `$1` — customer's task statement (free text, one or two sentences).
  If the user did not provide it — ask them to do so and do not start the interview
  until meaningful text is provided.
- **ANALYTIC_DIR** = `analytic/` — relative to `$CLAUDE_PROJECT_DIR`.
- **MAX_ITERATIONS** = `5` — total limit on validator/reviewer/HITL cycles.

## Workflow

### Step 1 — bootstrap

1. Create the `analytic/` directory if it does not exist.
2. Save `USER_TASK` to `analytic/original_task.txt`, preserving the customer's
   meaning and wording **without editing the content**. The only permitted
   cleanup is removing terminal rendering artifacts from multi-line input
   (gutter characters `▎`/`│`, leading `> `, excess common indentation).
   Do not alter the content (the customer's words) — this is the reference
   against which `analytic-reviewer` will validate `increment.md`.

### Step 2 — interview (conducted by the orchestrator, in the main thread)

**YOU conduct the customer interview YOURSELF (main thread), not a sub-agent.**
A sub-agent does not have access to interactive dialogue with the user — only
the main thread can communicate with the customer. (The old design of proxying
messages to a background `business-analyst` does not work: there is no feedback
channel.)

1. Read `.claude/config/increment_template.yaml` (`Read`) and
   `analytic/original_task.txt`. The template defines the sections for which
   requirements must be gathered.
2. Go through the sections and ask the customer questions via `AskUserQuestion`
   (1–4 questions per call; call multiple times). Ask only about things that
   **cannot be unambiguously derived** from the task; for each question propose
   reasonable answer options. Do not go into architecture/technology (DB,
   libraries, versions) — that is the domain of Dev planning (inside
   `/build_scopes`); if such questions
   arise, redirect the customer to the business level.
3. Once the requirements are gathered — show the customer a **section plan**
   (one annotation phrase per section) and ask a **blocking HITL confirmation**
   literally: **"Have we covered everything? Ready to write?"**. Wait for an
   explicit "yes" (yes / approve / write). An ambiguous answer ("seems so",
   "maybe") — ask again with an explicit choice "yes / no, let's clarify".
   If new requirements are named — add to the plan and ask for confirmation again.

Only after an explicit "yes" proceed to Step 2.5.

### Step 2.5 — delegate writing `increment.md`

Launch the **`business-analyst`** sub-agent via the `Agent` tool (not in the
background — it only writes the file and returns control; it does not need
dialogue). Pass in the prompt a **digest of the gathered requirements** — the
questions and agreed answers per each section — and the instruction:

```
Based on the provided requirements digest and `analytic/original_task.txt`, write
`analytic/increment.md` strictly following the sections from
`.claude/config/increment_template.yaml`. The interview has already been conducted
and agreed with the customer (see digest) — do NOT invent new requirements and
do NOT attempt to hold dialogue; simply structure the agreed content correctly.
If the digest lacks something for a section — note it in the Report
(Open questions), do not invent.
```

`business-analyst` will do a `Write`, and its `PostToolUse` hook
`validate_increment.py` will automatically run structural validation
(see Step 3). The digest from Step 2 is the sole source of requirements for
the agent; `original_task.txt` is only read (not overwritten).

### Step 3 — structural validation (automatic; do NOT run it yourself)

`business-analyst` is configured with a `PostToolUse` hook on `Write|Edit` —
it automatically runs `validate_increment.py` immediately after any
Write/Edit performed by the sub-agent. You will see the hook's JSON output
in the chat **immediately after the sub-agent's Write**, before it returns control.

**Manual duplication is FORBIDDEN:** do not run `validate_increment.py`
yourself — not via `Bash`, not via `python3`, not via `uv run --script`.
The hook does this for you.

Possible hook outcomes (read the result, do not call the hook yourself):

- **`result: continue`** + `status: ok` — `increment.md` structure passed; proceed to Step 4.
- **`result: continue`** + `status: skipped` — the `Write/Edit` was not on `increment.md`
  (e.g., on `original_task.txt`); this is normal, continue the workflow.
- **`result: block`** — structure failed; `reason` contains specific failed checks.
  Return control to the `business-analyst` sub-agent with this JSON as context.
  The cycle continues until the hook returns `ok`,
  **within the `MAX_ITERATIONS`** total limit.

If the hook **did not fire** within a reasonable time after Write — **do not
bypass it manually**. Report to the user: "The PostToolUse hook did not fire,
check `hooks:` in the frontmatter of `.claude/agents/business-analyst.md`".
This is a configuration bug signal, not a reason to run the validator via Bash.

### Step 4 — semantic review

Once structural validation has passed — launch the
**`analytic-reviewer`** sub-agent. Pass in the task description:

- path to `analytic/original_task.txt`,
- path to `analytic/increment.md`,
- path to `.claude/config/increment_template.yaml`.

The agent will return a response containing **exactly one markdown code block
with language `json`** at the end — this is its verdict. Extract it, validate
that it has `status` and `issues` fields, and write it **as-is** to
`analytic/review-report.json`.

Possible outcomes:

- **`status: "ok"`** — proceed to Step 5 (HITL).
- **`status: "needs_revision"`** — return control to
  `business-analyst`, attaching `analytic/review-report.json` as context.
  Counted toward the overall `MAX_ITERATIONS`.

### Step 5 — HITL approve

Show the customer in the chat:

```
## Ready for approve

**File**: analytic/increment.md
**Structural validation**: ✅ pass
**Semantic review**: ✅ ok
{if there are minor issues — list them briefly}

Do you approve? (yes / reject + reason)
```

Wait for the response.

- **`yes`** (or any affirmative form) — output the final:
  ```
  ## Analytic_scope completed

  Artifacts:
  - analytic/original_task.txt
  - analytic/increment.md
  - analytic/review-report.json

  **Next step**: run `/build_scopes` with this `increment.md` as
  the primary context — it spawns Dev and Test in parallel.
  ```
- **`reject + <reason>`** — save the reason to
  `analytic/rejection_comment.txt`, return control to `business-analyst`
  with this file as context. Counted toward the overall `MAX_ITERATIONS`.

### Step 6 — iteration limit

If the total number of passes through Steps 3–5 has reached `MAX_ITERATIONS = 5`
and `status` is still `needs_revision` or HITL-reject — **stop** and
output to the user:

```
## Iteration limit reached

5 validation / review / HITL-gate cycles have passed without approve.
This signals that the task is formulated unclearly or needs to be restated.

Options:
- rewrite USER_TASK more specifically and rerun /analyze;
- look at the latest review-report.json and rejection_comment.txt to
  understand the reasons;
- discuss with the customer outside the process and restart /analyze.
```

## Hard limits for this command

- Do not modify anything outside `analytic/`. `src/`, `specs/`, `pom.xml` and
  configs are not your domain.
- Do not run git commands. Committing is the responsibility of `/merge_gate`.
- Do not invoke `/build_scopes` yourself. That is the user's next step.

## What to do if `business-analyst` fails

The agent writes `increment.md` from **the digest you already gathered** — it does not
conduct an interview. If it invents requirements not in the digest/`original_task.txt`,
or returns `Open questions` for an unfilled section — this means the digest is
incomplete: gather the missing information from the customer (`AskUserQuestion`, Step 2) and
re-launch the agent with the completed digest. If the agent consistently distorts
the agreed content — note this in the final Report so the user can fix the
prompt in `.claude/agents/business-analyst.md`.

## What to do if `analytic-reviewer` returns non-JSON

If its response contains no ```json block at all, or the JSON is invalid —
**do not write `review-report.json`** (there is nothing to write). Report the error
to the user and suggest re-running the review. This signals that the agent's prompt
is unstable.
