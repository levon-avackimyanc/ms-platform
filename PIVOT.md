# Pivot: Claude Code as backend

**Date:** 2026-06-05.
**Status:** Accepted, in effect.

## What Changed

| Was | Became |
|---|---|
| Own CLI application `ms-platform` on **Java 21 + Spring Boot 3.5 + Spring Shell + Spring AI + JGit**, orchestrating agents through its own `LlmGateway` on top of DeepSeek / GigaChat / Claude. | **Claude Code as the sole runtime** for agents and pipelines. All work — through standard primitives: **agents, skills, hooks, tools, MCPs, references**. |
| Contracts: artifacts in git + local tracing + increment state file + HITL gates in CLI. | Contracts are the same, but implemented via standard Claude Code mechanisms and `.claude/` config. |
| Dev side: duplicating some Claude Code capabilities in Java (LLM providers, retry, fallback, role-routing). | Duplication removed. Claude Code already does what we were trying to reinvent. |

## Why

1. **YAGNI.** Duplicating Claude Code features in Spring AI for MVP — cost without return.
2. **Team.** Inside the bank, agents are being distilled into skills and refs (modeled after GigaCode/Claude Code). Our project fits into this same pattern.
3. **Speed.** The ready Claude Code configuration from upstream ([`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery)) already covers most of Dev_scope.

## What Was Removed

- `pom.xml`
- `src/`
- `target/`
- All `dev.multiagent.ms.*` classes (Java skeleton: `MsApplication`, `StatusCommand`, `MsProperties`, everything in `llm/`).
- Dependencies Spring Boot 3.5.14 / Spring Shell 3.4.0 / Spring AI 1.0.0 / JGit 7.3.0 / Lombok.
- `.gitignore` simplified — Java/Maven sections removed.

## What Remains Relevant from the Design

From previously written documents the following remain meaningful:
- **Three-scope structure** (Analytic / Dev / Test) — the conceptual model doesn't change.
- **Artifacts** (`increment.md`, `tech_spec.md`, `Plan.md`, `bug.md`) and inter-phase contracts.
- **HITL checkpoints** (Analytic gate, Dev merge-gate, Test reject-loop).
- **Role hierarchy** (system-analyst, explorer, planner, team-lead, dev/auto-tester, reviewer, analyzer, business-analyst) — moves to `.claude/agents/`.
- **Tags registry** — moves to `.claude/refs/` (flat structure with three fields already matches how refs live in Claude Code).

## What Is Outdated and Needs Rebuilding

- Chapters on Spring AI / Spring Shell / JGit / LLM Gateway / `application.yml` — discarded entirely.
- IMPLEMENTATION_ROADMAP under Java workstreams (A/B/C) — outdated; a new roadmap is needed for Claude Code (where we work with skills/agents/hooks/commands).
- YAML agent specs in AGENTS_SPECIFICATION — partially reusable (roles, skills, inputs/outputs remain), but the format will be closer to the markdown format of Claude Code agents (see `.claude/agents/*.md`).

## What's Next

1. Study what the upstream already provides in `.claude/`:
   - which agents exist (`builder`, `plan-reviewer`, `validator`, `context-router`, `meta-agent`);
   - which slash-commands exist (`/plan_w_team`, `/smart_build`);
   - which hooks and validators (Java/Python/TS);
   - which refs.
2. Map against our scopes (Analytic / Dev / Test): what's covered as-is, what needs to be added.
3. Add new `.claude/agents/`, `.claude/commands/`, `.claude/refs/`, `.claude/hooks/` for Analytic_scope and Test_scope.
4. When phase orchestration and parallel worktrees are needed (when we reach dev-workers in Dev_scope) — revisit whether a thin external CLI-wrapper is required.

## Links

- Upstream: [`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery) (fork of `disler/claude-code-hooks-mastery`).
- Current platform repo: [`levon-avackimyanc/ms-platform`](https://github.com/levon-avackimyanc/ms-platform).
- Old Spring development: [`levon-avackimyanc/multiagent-system`](https://github.com/levon-avackimyanc/multiagent-system) — archive after confirming everything needed has been migrated.
