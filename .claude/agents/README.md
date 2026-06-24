# Agents

Sub-agents organized by **scope** (matches the pipeline: Analytic → Dev → Test).
Claude Code discovers agents recursively; an agent's id comes from its `name:`
frontmatter, not its path — so subfolders are purely organizational. The plan
validator (`validate_plan.py --team-dir .claude/agents`) also scans this tree
recursively.

```
agents/
├── analytic/   Analytic scope — produce analytic/increment.md
│   ├── business-analyst.md     writes increment.md from the collected digest
│   └── analytic-reviewer.md    semantic review of the increment
├── dev/        Dev scope — product code + unit tests
│   ├── developer.md            product code (sonnet)
│   └── unit-tester.md          unit tests only (sonnet)
├── test/       Test scope — higher-layer autotests + run/triage
│   ├── test-explorer.md        maps test landscape → test/test-landscape.md
│   ├── test-analyst.md         writes test/test-model.md
│   ├── autotester.md           integration/sys/e2e/ui/load tests (layer per task tags)
│   ├── failure-analyzer.md     triage failures: test-side vs service-side (read-only)
│   └── bug-reporter.md         writes test/bugs/*.md
├── shared/     cross-scope (used by Dev and Test)
│   ├── explorer.md             tags modules → explore/module-map.md
│   ├── plan-reviewer.md        critical plan review (scope-aware criterion 10)
│   ├── code-reviewer.md        read-only diff review, PASS/FAIL
│   ├── validator.md            final validation: runs the suite, checks acceptance
│   └── context-router.md       (helper) keyword → ref sections
└── meta-agent.md   standalone — generates new agent definitions
```

Reference docs: `.claude/DEV_SCOPE.md`, `.claude/TEST_SCOPE.md`.
