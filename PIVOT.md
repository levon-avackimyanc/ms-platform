# Pivot: Claude Code as backend

**Дата:** 2026-06-05.
**Статус:** принято, действует.

## Что менялось

| Было | Стало |
|---|---|
| Своё CLI-приложение `ms-platform` на **Java 21 + Spring Boot 3.5 + Spring Shell + Spring AI + JGit**, оркестрирующее агентов через свой `LlmGateway` поверх DeepSeek / GigaChat / Claude. | **Claude Code как единственный runtime** для агентов и пайплайнов. Вся работа — через стандартные примитивы: **agents, skills, hooks, tools, MCPs, references**. |
| Контракты: артефакты в git + локальный трейсинг + state-файл инкремента + HITL-gate'ы в CLI. | Контракты прежние, но реализуются стандартными механизмами Claude Code и `.claude/`-конфигом. |
| Дев-сторона: дублирование части возможностей Claude Code на Java (LLM-провайдеры, retry, fallback, role-routing). | Дублирование убрано. Claude Code сам делает то, что мы пытались переизобрести. |

## Почему

1. **YAGNI.** Дублировать функции Claude Code на Spring AI в MVP — расход без отдачи.
2. **Команда.** Внутри банка идёт дистилляция агентов в skills и refs (по образцу GigaCode/Claude Code). Наш проект встраивается в этот же паттерн.
3. **Скорость.** Готовая Claude Code-конфигурация апстрима ([`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery)) уже закрывает большую часть Dev_scope.

## Что удалено

- `pom.xml`
- `src/`
- `target/`
- Все классы `dev.multiagent.ms.*` (Java skeleton: `MsApplication`, `StatusCommand`, `MsProperties`, всё в `llm/`).
- Зависимости Spring Boot 3.5.14 / Spring Shell 3.4.0 / Spring AI 1.0.0 / JGit 7.3.0 / Lombok.
- `.gitignore` упрощён — убраны Java/Maven секции.

## Что осталось актуальным из дизайна

Из ранее написанных документов сохраняют смысл:
- **Структура трёх scope-ов** (Analytic / Dev / Test) — концептуальная модель не меняется.
- **Артефакты** (`increment.md`, `tech_spec.md`, `Plan.md`, `bug.md`) и контракты между фазами.
- **HITL-точки** (Analytic gate, Dev merge-gate, Test reject-loop).
- **Иерархия ролей** (system-analyst, explorer, planner, team-lead, dev/auto-tester, reviewer, analyzer, business-analyst) — переезжает в `.claude/agents/`.
- **Tags registry** — переезжает в `.claude/refs/` (плоская структура с тремя полями уже соответствует тому, как живут refs у Claude Code).

## Что устарело и требует пересборки

- Главы про Spring AI / Spring Shell / JGit / LLM Gateway / `application.yml` — выкидываем целиком.
- IMPLEMENTATION_ROADMAP под Java workstreams (A/B/C) — устарел; нужен новый roadmap под Claude Code (где работаем со skills/agents/hooks/commands).
- YAML-спеки агентов в AGENTS_SPECIFICATION — частично переиспользуем (роли, скиллы, входы/выходы остаются), но формат будет ближе к markdown-формату агентов Claude Code (см. `.claude/agents/*.md`).

## Что дальше

1. Изучить, что уже даёт апстрим в `.claude/`:
   - какие агенты есть (`builder`, `plan-reviewer`, `validator`, `context-router`, `meta-agent`);
   - какие slash-команды есть (`/plan_w_team`, `/smart_build`);
   - какие хуки и валидаторы (Java/Python/TS);
   - какие refs.
2. Сопоставить с нашими scope-ами (Analytic / Dev / Test): что закрывается as-is, что надо добавлять.
3. Добавлять новые `.claude/agents/`, `.claude/commands/`, `.claude/refs/`, `.claude/hooks/` под Analytic_scope и Test_scope.
4. Когда понадобится оркестрация фаз и параллельных worktree'ев (когда дойдём до dev-workers в Dev_scope) — пересмотрим, нужен ли тонкий внешний CLI-wrapper.

## Ссылки

- Апстрим: [`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery) (форк `disler/claude-code-hooks-mastery`).
- Текущий репо платформы: [`levon-avackimyanc/ms-platform`](https://github.com/levon-avackimyanc/ms-platform).
- Старая Spring-разработка: [`levon-avackimyanc/multiagent-system`](https://github.com/levon-avackimyanc/multiagent-system) — заархивировать после подтверждения, что всё нужное унесли.
