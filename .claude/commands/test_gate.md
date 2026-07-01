---
description: Final HITL gate for Test scope. Gathers the test diff, run results, and filed bugs; shows the customer; on approve commits the autotests and artifacts (test-model/test-plan/bugs) and with confirmation merges into the base branch; on reject records the reason. Filing bugs in the tracker and push are manual steps.
argument-hint: "[--message <commit-msg>] [--base <branch>]"
model: sonnet
disallowed-tools: EnterPlanMode
---

# /test_gate

The closing HITL checkpoint of Test scope. Runs **after** `/test_run`, when
autotests have been executed and bugs (if any) have been filed. The goal is to
give the human a deliberate opportunity to confirm merging the autotests into
the base branch and decide the fate of the bugs.

This is the **only place in Test scope** where `git commit` / `git merge` are
performed. `/test_plan`, `/test_build`, `/test_run` do not touch git history.

Filing bugs in an external tracker and `push`/deploy — **outside this command's scope**
(manually after approve).

## Variables

- **COMMIT_MSG** = `$1` (`--message`) — commit title. If absent → propose in
  conventional-commits style (`test:` / `test(<scope>):`) and confirm.
- **BASE_BRANCH** = `$2` (`--base`) — target merge branch. If absent → determine
  (`main`, otherwise `master`) and confirm with the customer before merging.
- **TEST_BRANCH** — current branch (`git branch --show-current`).

## Workflow

### Step 1 — gather facts (verify yourself, don't take their word for it)

Via `Bash`/`Read`:

1. `git branch --show-current` — the branch with tests.
2. Determine `BASE_BRANCH` (if not provided); remember it, confirm at Step 3.
3. `git status --short` — uncommitted changes.
4. `git diff --stat <BASE_BRANCH>...HEAD` and `git diff --stat` — test diff summary.
5. Read artifacts (without re-running a heavy test run):
   - `test/test-plan.md` (by mtime) — which test increment we are closing, which layers;
   - `test/test-model.md` — authoring model;
   - `test/bugs/*.md` — filed bugs (count, severity, service-side vs unclear).
6. Lightweight scope check on the test diff (scope only, no build):
   ```bash
   uv run --script .claude/hooks/validators/check_diff_scope.py \
     --plan <latest test/test-plan.md> --baseline <BASE_BRANCH>
   ```

> The test run itself was done in `/test_run` — **do not repeat it here**. If no run
> was done (no result / `test/bugs` is empty and autotests were not executed) — say so
> and suggest running `/test_run` first.

### Step 2 — show summary

```
## Test-gate

**Branch:**          <TEST_BRANCH>
**Base branch:**     <BASE_BRANCH>
**Test plan:**       test/<file>.md  (layers: <integration/e2e/load…>)
**Test diff:**       <N files, +X/-Y>
**Uncommitted:**     <yes: list / no>
**Run (/test_run):  <green | N tests failed and triaged>
**Bugs:**           <K in test/bugs/: service-side S, unclear U> | none
**Scope-check:**    <PASS | FAIL + files outside plan>

Do you approve committing the autotests and merging into <BASE_BRANCH>? (yes / reject + reason)
```

If there are **unclear** bugs — highlight them: they require manual triage by the human.
If `Scope-check = FAIL` — explicitly list the out-of-scope files (discovery vs scope creep).

### Step 3 — HITL decision point

Wait for an explicit answer. **Do not approve on behalf of the user.**

**`yes`:**

1. Confirm `BASE_BRANCH` if it was determined automatically.
2. Commit on the branch: autotests (`src/test/**`, `e2e/`, `load/` …) **and** artifacts
   `test/test-model.md`, `test/test-plan.md`, `test/bugs/*.md`.
   - Title: `COMMIT_MSG`, otherwise the proposed `test:` commit (brief, one line).
     Footer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
3. Merge into the base without fast-forward:
   ```bash
   git checkout <BASE_BRANCH>
   git merge --no-ff <TEST_BRANCH> -m "merge: <COMMIT_MSG>"
   ```
   On conflict — **stop**, show `git status`, hand resolution to the human.
4. Output the final:
   ```
   ## Test scope completed

   Merged: <TEST_BRANCH> → <BASE_BRANCH>
   Commit: <sha> <COMMIT_MSG>
   Bugs: <K in test/bugs/> — file in tracker manually (service-side: S, unclear: U).

   Next steps (manual, outside the system):
   - git push (if needed),
   - file bugs in the tracker,
   - re-run /test_run after service fixes (Dev scope).
   ```

**`reject + <reason>`:**

1. Save the reason to `test/rejection_comment.txt` (`Write`).
2. **Do not commit or merge anything.**
3. Output: reason recorded; next — fix the plan (`/test_plan`),
   tests (`/test_build`) or re-run (`/test_run`), then `/test_gate` again.

## Hard limits for this command

- **NO `push`**, **do NOT** file bugs in the tracker — these are manual steps.
- **Do NOT resolve merge conflicts yourself** (human resolution).
- **Do NOT approve on behalf of the user**; without an explicit "yes" — do not merge.
- **Do NOT modify product code or tests.** Your domain is git operations and `test/`.
- Clean tree and empty diff against the base → report that there is nothing to merge.
