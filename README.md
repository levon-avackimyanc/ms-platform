# ms-platform

**Multiagent System Platform** — конфигурация Claude Code, оркестрирующая сквозной цикл *Analytic → Dev → Test* силами специализированных ИИ-агентов.

> **Подход:** Claude Code как backend. Вся работа агентов — через стандартные примитивы (skills, hooks, agents, commands, MCP, refs). Никакого собственного приложения / CLI / LLM-gateway.

Подробности по предметной модели — в дизайн-документах (см. ниже секцию ⚠️ **Outdated**).

---

## Что внутри

```
.claude/        # ядро — Claude Code конфигурация
  ├── agents/   # роли исполнителей (builder, plan-reviewer, validator, …)
  ├── commands/ # slash-команды (/plan_w_team, /smart_build, …)
  ├── hooks/    # lifecycle-хуки + валидаторы (Python)
  ├── refs/     # доменные референсы (java-patterns, java-testing, …)
  └── settings.json
docs/           # документация компонентов (context-routing, validators, …)
specs/          # research-материалы (SOTA multi-agent teams, …)
install.sh      # установка хуков в Claude Code
uninstall.sh    # удаление
ruff.toml       # Python-линтер для хуков
ty.toml         # type-checker для хуков
```

## Требования к окружению

- **Claude Code CLI** (`claude`) — основной runtime.
- **uv** — менеджер Python, нужен для запуска хуков и валидаторов.
- **Git** — артефакты живут в ветках инкрементов.

## Установка

```bash
./install.sh
```

Скрипт регистрирует хуки в локальной директории `.claude/` относительно проекта, на котором будет работать Claude Code.

## Использование

Стандартными slash-командами Claude Code:

```text
/plan_w_team   # планирование с командой
/smart_build   # сборка с роутингом контекста
```

(новые команды появляются по мере добавления `.claude/commands/*.md`)

## Состояние разработки

Идёт построение MVP. Базовая Claude Code-конфигурация подтянута из апстрима [`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery) (форк disler). Дальнейшая работа — расширение `.claude/` под наши Analytic / Test scope-ы.

## Лицензия

[Apache License 2.0](./LICENSE) для всего, что добавлено в этом проекте.
Содержимое `.claude/`, `docs/`, `specs/`, `install.sh`, `uninstall.sh`, `ruff.toml`, `ty.toml` — перенесено из апстрима; см. оригинальный [`UPSTREAM-README.md`](./UPSTREAM-README.md).

---

## ⚠️ Outdated дизайн-документы

Документы ниже были написаны под Spring Boot/Java реализацию. После pivot'а на Claude Code-only они частично устарели — содержимое о scope-ах (Analytic / Dev / Test) и потоках агентов остаётся актуальным, всё про Spring/JGit/LLM Gateway — нет.

- [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md)
- [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md)
- [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md)

Решение о pivot'е и что осталось актуальным — см. [`PIVOT.md`](./PIVOT.md).
