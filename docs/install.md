# Install / Uninstall

One-line installer that copies the `.claude/` directory into your project.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/levon-avackimyanc/ms-platform/main/install.sh | bash
```

To install from a specific branch (e.g. while testing an unmerged PR), set `MSP_BRANCH`:

```bash
MSP_BRANCH=feat/my-branch bash install.sh
```

Interactive prompts:
1. **TTS** — enable/disable text-to-speech notifications

## Non-Interactive Install

For CI pipelines or Claude Code automation:

```bash
bash install.sh --force
```

With custom options:

```bash
TTS_ENABLED=y bash install.sh --force
```

| Variable | Values | Default |
|----------|--------|---------|
| `TTS_ENABLED` | `y` / `n` | `n` |

## What Gets Installed

```
.claude/
├── commands/          — slash commands (/analyze, /build_scopes, /dev_plan, /smart_build, /test_*, /merge_gate)
├── agents/            — agents by scope (analytic/ dev/ test/ shared/), incl. dev-conductor & test-conductor
├── hooks/             — context router, section loader, validators
├── refs/              — coding standards (Java, React, Python, Rust)
├── config/            — increment template
├── DEV_SCOPE.md, TEST_SCOPE.md — pipeline contracts
├── settings.json      — hook configuration
└── CLAUDE.md          — project instructions
```

## Uninstall

```bash
curl -fsSL https://raw.githubusercontent.com/levon-avackimyanc/ms-platform/main/uninstall.sh | bash
```

Removes the `.claude/` directory and all installed files.

## Prerequisites

- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) — CLI tool
- [Astral UV](https://docs.astral.sh/uv/) — auto-installed by the script if missing

## Optional Dependencies

| Tool | What for | Install |
|------|----------|---------|
| [Context7](context7.md) | Live documentation lookup | Add MCP server to config |
| [Serena](serena.md) | Semantic code navigation | Add MCP server to config |
| [OpenSpec](openspec.md) | Living specs & delta tracking | `npm i -g @fission-ai/openspec && openspec init --tools claude` |
| [claude-hud](status-line.md) | Rich status line (context, limits, tools, agents, todos) | `/plugin marketplace add jarrodwatts/claude-hud && /plugin install claude-hud` |
