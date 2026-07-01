---
description: Final HITL merge-gate for Dev scope. Gathers the diff and verdicts, shows the customer, on approve commits the increment and (with confirmation) merges into the base branch; on reject records the reason.
argument-hint: "[--message <commit-msg>] [--base <branch>]"
model: sonnet
disallowed-tools: EnterPlanMode
---

# /merge_gate

The closing HITL checkpoint of Dev scope. Runs **after** `/smart_build`, when
code is written, tests have passed, and `validator` has finished. The goal is to
give the human a deliberate opportunity to confirm merging the increment into the
base branch.

This is the **only place** where the system does `git commit` / `git merge`.
Neither `/analyze`, nor `/dev_plan`, nor `/smart_build` touch git history.

`push` and deploy — **outside this command's scope**. Those are done manually
by the human after approve.

## Variables

- **COMMIT_MSG** = `$1` (`--message`) — commit title. If not provided —
  propose one in conventional-commits style and ask for confirmation.
- **BASE_BRANCH** = `$2` (`--base`) — target branch for merge. If not provided —
  determine automatically (`main`, otherwise `master`) and **confirm with the
  customer** before merging.
- **INCREMENT_BRANCH** — current branch (`git branch --show-current`); this is the
  branch being merged.

## Workflow

### Step 1 — gather facts (do not take their word for it, verify yourself)

Via `Bash`, gather and show the customer the actual state:

1. `git branch --show-current` — the increment branch.
2. Determine `BASE_BRANCH` (if not provided): `git rev-parse --verify main` →
   otherwise `master`. Remember it; confirm at Step 3.
3. `git status --short` — any uncommitted changes.
4. `git diff --stat <BASE_BRANCH>...HEAD` and `git diff --stat` (working tree) —
   summary of changes against the base.
5. Read artifact verdicts if they exist (via `Read`, without re-running
   heavy builds):
   - `analytic/review-report.json` — analytics verdict;
   - the latest plan `specs/*.md` (by mtime) — which increment we are closing.
6. Run a lightweight scope check (scope only, no build):
   ```bash
   uv run --script .claude/hooks/validators/check_diff_scope.py \
     --plan <latest specs/*.md> --baseline <BASE_BRANCH>
   ```
   Include the result (PASS/FAIL + list of out-of-scope files) in the summary.

### Step 2 — show summary

Output to the customer in a single block:

```
## Merge-gate

**Increment branch**: <INCREMENT_BRANCH>
**Base branch**:      <BASE_BRANCH>
**Plan**:             specs/<file>.md
**Changes**:          <N files, +X/-Y> (git diff --stat)
**Uncommitted**:      <yes: list / no>
**Scope-check**:      <PASS | FAIL + files outside plan>
**Analytics**:        <review-report.json: ok | needs_revision | n/a>

Do you approve merge into <BASE_BRANCH>? (yes / reject + reason)
```

If `Scope-check = FAIL` — **do not hide it**: explicitly name the out-of-scope files
and suggest the customer decide (discovery or scope creep) before approving.

### Step 3 — HITL decision point

Wait for an explicit answer. **Do not approve on behalf of the user.**

**`yes` (or affirmative form):**

1. Confirm `BASE_BRANCH` if it was determined automatically:
   "Merging `<INCREMENT_BRANCH>` → `<BASE_BRANCH>`, correct? (yes / different branch)".
2. If there are uncommitted changes — commit them on the increment branch:
   - Title: `COMMIT_MSG`, otherwise your proposed conventional-commit
     (`feat:` / `fix:` / `refactor:` …), **brief, one line, no
     roadmap/Day-N references**. Body — only if needed (a few words).
   - Footer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
3. Merge into the base **without fast-forward** so the increment is readable in history:
   ```bash
   git checkout <BASE_BRANCH>
   git merge --no-ff <INCREMENT_BRANCH> -m "merge: <COMMIT_MSG>"
   ```
   On conflict — **stop**, show `git status`, hand conflict resolution to the
   customer (human resolution). Do not resolve conflicts yourself.
4. Output the final:
   ```
   ## Dev scope completed

   Merged: <INCREMENT_BRANCH> → <BASE_BRANCH>
   Commit: <sha> <COMMIT_MSG>

   Next steps (manual, outside the system):
   - git push (if publishing is needed),
   - deploy to the test stand.
   ```

**`reject + <reason>`:**

1. Save the reason to `analytic/rejection_comment.txt` (via `Write`).
2. **Do not commit or merge anything.**
3. Output:
   ```
   ## Merge rejected

   Reason recorded in analytic/rejection_comment.txt.
   Next options:
   - run /dev_plan with rejection_comment.txt as clarification,
   - or fix the code and re-run /smart_build, then /merge_gate.
   ```

## Hard limits for this command

- **NO `push`.** Publishing and deploy are manual, outside the system.
- **Do NOT resolve merge conflicts yourself** — this is a HITL point (human resolution).
- **Do NOT approve on behalf of the user** and do not merge without an explicit "yes".
- **Do NOT modify product code.** Your domain is git operations and `analytic/`.
- If the working tree is clean and the diff against the base is empty — report that
  there is nothing to merge and do not create an empty merge.
