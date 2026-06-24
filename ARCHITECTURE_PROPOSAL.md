# ms-platform — Архитектурное предложение

**Статус:** v2, переписано 2026-06-11 после pivot на Claude Code-only backend.
**См. также:** [`PIVOT.md`](./PIVOT.md), [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md), [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md), [`UPSTREAM-README.md`](./UPSTREAM-README.md).

> Предыдущая версия документа описывала собственную Java/Spring платформу с LLM Gateway. Она устарела. См. `PIVOT.md` для истории решения и `git log` для содержания.

---

## Часть I. Сводка требований

### 1. Что строим

**Конфигурацию Claude Code** (`.claude/`), которая оркестрирует сквозной цикл *бизнес-аналитика → разработка → тестирование* силами специализированных ИИ-агентов с человеческими gate'ами в ключевых точках.

Мы НЕ строим:
- собственный CLI-инструмент,
- собственный LLM Gateway,
- собственный оркестратор фаз,
- backend-сервис.

Всё это уже даёт Claude Code как runtime; наша работа — наполнить его доменно-правильным набором `agents/`, `skills/`, `commands/`, `hooks/`, `refs/`, `mcp`-конфигов.

### 2. Что система производит

Итоговый артефакт сквозного цикла — **Docker-образ продукта**, пригодный к ручному деплою на тестовый стенд. Сборка — обычным `mvn package` / `Dockerfile` под управлением `validator`-агента; деплой на стенд — вручную, вне системы; после деплоя — прогон автотестов и (опционально) анализ падений → BUG-routing.

### 3. Целевые проекты (которые система обрабатывает)

Набор **микросервисов Java + Spring Boot 3.x**, ~десятки тысяч строк каждый, **greenfield**-разработка инкрементов (не legacy). Стек агентов мультиязычный из коробки (Java/React/TypeScript/Python/Rust в upstream-refs), наша команда фокусируется на Java/Spring.

### 4. Среда работы

Локально у разработчика. Запускается командой `claude` в каталоге **целевого микросервиса** при условии что в нём установлен наш `.claude/` (через `install.sh`) **или** при работе из каталога самого `ms-platform` для отладки конфигов.

### 5. Парадигма

Стандартные примитивы Claude Code:

| Примитив | Где живёт | Назначение в нашей системе |
|---|---|---|
| **agents** | `.claude/agents/*.md` | Роли: builder, plan-reviewer, validator (есть); business-analyst, analytic-reviewer, analyzer (добавляем) |
| **commands** | `.claude/commands/*.md` | Slash-команды: `/plan_w_team`, `/smart_build` (есть); `/analyze`, `/test_run` (добавляем) |
| **hooks** | `.claude/hooks/*.py` + `settings.json` | Lifecycle (Pre/PostToolUse, Stop, …) и валидаторы (spotless, jacoco, validate_plan, …) |
| **skills** | (формат скиллов Claude Code если применимо) | Переиспользуемые «как делать X», вызываемые агентами по матчингу keyword'ов |
| **refs** | `.claude/refs/*.md` | Бывший «tags registry» — готовые `java-patterns.md`, `java-testing.md`, и т. д. |
| **MCP** | `.claude/settings.json` + MCP-серверы | Внешние инструменты: Context7 (docs), Serena (semantic code), OpenSpec и др. |

### 6. Три scope-а

#### Analytic_scope (бизнес-аналитика) — **строим с нуля**

Команда `/analyze` запускает агента `business-analyst`. Он ведёт chat-interview с заказчиком (через UI Claude Code — встроенный чат), формирует `analytic/increment.md` со списком ФТ/НФТ, business-flow, сценариев, acceptance criteria.

После записи `increment.md`:
1. **`validate_increment.py`** (hook на Stop у `/analyze` или на Edit у файла) — детерминированная проверка структуры markdown'а.
2. **`analytic-reviewer`** агент — семантическое ревью (сверка с исходной задачей заказчика, поиск противоречий/пробелов).
3. **HITL gate** — заказчик/PO видит итог в чате, подтверждает (внутри сессии Claude Code, без отдельного приложения).

Loop при fail валидатора или ревьюера: `business-analyst` правит сам; к заказчику обращается только если нужны уточнения.

#### Dev_scope (разработка) — **используем upstream + добавляем HITL**

Готовые слои из upstream покрывают почти всё:

- **`/plan_w_team`** (Opus) — agent-orchestrator, создаёт `specs/<плана>.md` с 8 обязательными секциями (включая `Testing Strategy` 80/15/5 и `Test Infrastructure (User-Declared)`). Внутри проводит **Test Infra Interview** с пользователем для заполнения раннеров.
- **`plan-reviewer`** (Opus, read-only) — критический ревью плана по 10 критериям (Problem Alignment, Surgical Scope, Test Realism и т. д.) перед execution.
- **TaskCreate / TaskUpdate / TaskList / TaskGet** — встроенная оркестрация задач: planner создаёт задачи, назначает owner, расставляет `addBlockedBy`, builder-агенты берут свои.
- **`builder`** (Opus) — универсальный исполнитель (Java/React/Python). Auto-loads refs по стеку и keywords из `**Stack**` поля задачи.
- **`validator`** (Sonnet, read-only) — пост-execution верификация: запускает `mvn spotless:check` / `mvn test` / declared runner'ы из плана, проверяет actual-vs-declared scenarios count, диффовый scope через `check_diff_scope.py`.
- **Hooks:** PostToolUse → `validator_dispatcher.py` (запускает релевантный линтер по типу файла); Stop у `/plan_w_team` → `validate_plan.py` (проверка контракт-полноты плана).

Что **добавляем**:
- **`merge_gate`** команда или Stop-hook — финальное HITL-подтверждение перед коммитом/merge инкремента (аналог нашего «manual-merge-gate»).

Чего **сознательно не делаем на старте**:
- Параллельные worktree per dev-agent. `/plan_w_team` оркестрирует задачи последовательно через TaskList + owner — для MVP это достаточно. Worktree-параллелизм рассматриваем как будущую опцию.

#### Test_scope — **связан с Dev, идёт параллельно** (полный, не lite)

> Актуальная редакция (2026-06-24). Прежняя «lite»-концепция (Test = только
> `/test_run` + `analyzer`, а тесты пишутся внутри `/plan_w_team`) устарела.
> Полный контракт — в [`.claude/TEST_SCOPE.md`](./.claude/TEST_SCOPE.md).

Test_scope владеет **авторингом + прогоном/анализом** автотестов высоких слоёв
(Integration / Sys / E2E / UI / Load); **UNIT остаётся в Dev_scope**. Он **не
независим**: это **отдельная ветка, связанная с Dev через план** и идущая
**параллельно** разработке.

- **Связь = Dev-план `specs/*.md`.** Технический контракт (реальные эндпоинты,
  формы ошибок, отложенные AC) рождается в плане, не в `increment.md`. `/test_plan`
  и `test-analyst` читают план как первичный вход; `increment.md` — это intent.
  Расхождение increment↔plan ловится на `/test_plan` как **spec-divergence**, а не
  как ложный service-баг на прогоне.
- **Свой планировщик и свой explorer.** `/test_plan` + `test-analyst` +
  **`test-explorer`** (карта тест-ландшафта → `test/test-landscape.md`). Test **не**
  вливается в `/plan_w_team`; два планировщика, **два ledger'а**, синхронизация по
  milestone **«contract frozen»**.
- **Параллельный авторинг.** `/test_build` гоняет `autotester`'ов **одновременно с
  Dev-build**. Compile-гейт в этом режиме **смягчён** (`--authoring`: формат +
  статика; компиляция/покрытие отложены), поэтому ссылки на ещё не собранный код не
  блокируют авторинг.
- **Прогон/анализ (Flow B).** `/test_run` — **человеко-запускаемый после готовности
  кода**: Exec → `failure-analyzer` (test-side / service-side / unclear) →
  {фикс теста `autotester`'ом | баг `bug-reporter`'ом в `test/bugs/`}.
- **Гейт.** `/test_gate` — единственная Test-команда, трогающая git.

BUG-routing: service-side баг (`test/bugs/*.md`) становится входом для нового
`/plan_w_team` — полный Dev-цикл доработки.

### 7. Refs (бывший tags registry)

Курируемые человеком md-файлы в `.claude/refs/`. Из upstream уже есть:

- `java-patterns.md` (24 KB), `java-testing.md` (56 KB) — **наш стек**.
- `python-patterns.md` (95 KB), `python-testing.md` (66 KB).
- `react-patterns.md` (54 KB).
- `rust-patterns.md`, `rust-testing.md`.

Раньше предполагалась таблица из 3 полей (имя, описание, ссылка). В Claude Code-парадигме refs — это **сами markdown'ы с разделами** (`#section`); матчинг через keyword'ы → секции делает `context-router.md` агент и `context_router.py` hook (см. `Section Routing Catalog` в `/plan_w_team`).

Доменные refs нашей команды (по корпоративным библиотекам / внутренним MCP / RAG / liquibase-конвенциям и т. д.) **добавляем сюда же** новыми файлами или новыми секциями в существующих.

### 8. Кросс-функциональные решения

| Тема | Решение |
|---|---|
| **LLM-инфра** | Claude через сам Claude Code (Opus для планировщика/ревьюера/анализатора, Sonnet для верификатора, Haiku — если появятся быстрые / fan-out задачи). Своего матчинга «роль → модель» не делаем — Claude Code сам по `model:` в YAML-frontmatter агента. |
| **Артефакты — где живут** | В git, в ветке инкремента: `analytic/`, `specs/<plan>.md`, `test/runs/<ts>/`. Никакой собственной БД. |
| **Версионирование** | 1 инкремент = 1 ветка. Промежуточные коммиты по фазам (analytic / spec / build / test). Squash при approve — опция, не обязательная. |
| **HITL точки** | (a) approve `increment.md` в чате с заказчиком (Analytic gate); (b) approve плана `/plan_w_team` (встроено в Claude Code — exit plan mode); (c) approve merge перед коммитом инкремента (`merge_gate`). |
| **Failure-режимы** | Стандартные Claude Code: retry/exit при ошибках инструментов; наши hook-валидаторы возвращают exit≠0 → Claude Code сам интерпретирует и зовёт агента поправить. |
| **Observability** | Trace встроенный в Claude Code + Stop-hook'и которые пишут логи. Свой трейсинг не строим. |
| **Бюджет / SLA** | Не задаём; модель выбирается per-agent в YAML-frontmatter. Контроль расходов — через Claude Code (он показывает токены). |
| **Корп-контур** | Claude Code должен запускаться через корп-прокси к Anthropic API (вопрос вне нашей конфигурации; решается на уровне инсталляции). |

---

## Часть II. Архитектурное предложение

### A. Высокоуровневая схема

```
┌──────────────────────────────────────────────────────────────┐
│  Пользователь (бизнес-аналитик / разработчик / QA / PO)      │
└───────────────┬──────────────────────────────────────────────┘
                │ chat / slash-commands
                ▼
┌──────────────────────────────────────────────────────────────┐
│                       Claude Code (CLI)                      │
│  • orchestrator, LLM gateway, tool runner, lifecycle hooks   │
│  • Anthropic API (Opus / Sonnet / Haiku) ── через корп-прокси│
└───────────────┬──────────────────────────────────────────────┘
                │ читает .claude/ из cwd
                ▼
┌──────────────────────────────────────────────────────────────┐
│              .claude/  (наш репозиторий ms-platform)         │
│  ┌─ commands/   /plan_w_team  /smart_build  /analyze*  /test_run* │
│  ┌─ agents/     builder, plan-reviewer, validator,           │
│  │              business-analyst*, analytic-reviewer*, analyzer* │
│  ├─ hooks/      lifecycle (Pre/PostToolUse/Stop/…) +         │
│  │              validators/ (spotless, jacoco, validate_plan, │
│  │                            validate_increment*, …)        │
│  ├─ refs/       java-patterns, java-testing, …               │
│  └─ settings.json (hook wiring + permissions + MCP)          │
└───────────────┬──────────────────────────────────────────────┘
                │ оперирует артефактами в
                ▼
┌──────────────────────────────────────────────────────────────┐
│   Git-репо ЦЕЛЕВОГО продукта (или этот же — для дев-цикла)   │
│  branch: increment/<name>                                    │
│    ├── analytic/                                             │
│    │     ├── original_task.txt                               │
│    │     ├── increment.md            (бизнес-инкремент)      │
│    │     └── review-report.json                              │
│    ├── specs/                                                │
│    │     └── <plan-name>.md          (план из /plan_w_team)  │
│    ├── src/, pom.xml, …               (продукт сам)          │
│    └── test/runs/<timestamp>/         (логи прогонов)        │
└──────────────────────────────────────────────────────────────┘

* — добавляем мы; всё остальное — уже есть из upstream.
```

### B. Структура `.claude/`  — что есть и что добавляем

```
.claude/
├── commands/
│   ├── plan_w_team.md         ✓ upstream — центральный планировщик Dev_scope
│   ├── smart_build.md         ✓ upstream — сборка с роутингом контекста
│   ├── analyze.md             ✗ ДОБАВИТЬ — старт Analytic_scope (business-analyst)
│   └── test_run.md            ✗ ДОБАВИТЬ — запуск тестов + analyzer
├── agents/
│   ├── context-router.md      ✓ upstream — load-on-demand загрузчик refs
│   ├── meta-agent.md          ✓ upstream
│   ├── team/
│   │   ├── builder.md         ✓ upstream — наш «dev» + «tester» (универсальный)
│   │   ├── plan-reviewer.md   ✓ upstream — наш «plan-reviewer»
│   │   └── validator.md       ✓ upstream — наш acceptance-validator
│   ├── business-analyst.md    ✗ ДОБАВИТЬ — chat-interview с заказчиком
│   ├── analytic-reviewer.md   ✗ ДОБАВИТЬ — ревью increment.md
│   └── analyzer.md            ✗ ДОБАВИТЬ — анализ падений в тестах
├── hooks/
│   ├── pre_tool_use.py        ✓ upstream
│   ├── post_tool_use.py       ✓ upstream
│   ├── … (10 lifecycle skripтов)
│   └── validators/
│       ├── validate_plan.py            ✓ upstream
│       ├── validate_new_file.py        ✓ upstream
│       ├── validate_file_contains.py   ✓ upstream
│       ├── check_diff_scope.py         ✓ upstream
│       ├── check_test_layers.py        ✓ upstream
│       ├── validator_dispatcher.py     ✓ upstream
│       ├── spotless_validator.py       ✓ upstream
│       ├── pmd_validator.py            ✓ upstream
│       ├── jacoco_validator.py         ✓ upstream
│       ├── maven_compile_validator.py  ✓ upstream
│       ├── ruff_validator.py, …        ✓ upstream
│       └── validate_increment.py       ✗ ДОБАВИТЬ — структурная проверка increment.md
├── refs/
│   ├── java-patterns.md       ✓ upstream — 24 KB патернов Java/Spring
│   ├── java-testing.md        ✓ upstream — 56 KB патернов тестов
│   ├── python-*, react-*, rust-*  ✓ upstream
│   └── <domain refs>          ✗ ДОБАВИТЬ — корп-библиотеки, MCP, RAG, liquibase и т. д.
├── settings.json              ✓ upstream — hook wiring (расширим под наши hooks)
└── data/                      (runtime — в .gitignore)
```

Условные обозначения: ✓ — есть в upstream, готово к использованию; ✗ — нужно добавить.

### C. Карта агентов

| Агент | Откуда | Модель | Назначение | Используется в |
|---|---|---|---|---|
| `business-analyst` | **наш** | Sonnet | Chat-interview с заказчиком, пишет `analytic/increment.md`. | Analytic_scope |
| `analytic-reviewer` | **наш** | Opus | Сверка `increment.md` с исходной задачей, поиск пробелов/противоречий. | Analytic_scope |
| `/plan_w_team` (как агент-orchestrator) | upstream | Opus | Создаёт `specs/<plan>.md` с 8 секциями + Test Infra Interview + декомпозиция на TaskCreate. | Dev_scope |
| `plan-reviewer` | upstream | Opus | 10-критериальный ревью плана. | Dev_scope |
| `builder` | upstream | Opus | Реализует задачи плана (код + тесты). | Dev_scope |
| `validator` | upstream | Sonnet | Прогон declared runner'ов, scope-check, acceptance. | Dev_scope |
| `analyzer` | **наш** | Opus | Анализ упавших тестов → `bug_in_test` / `bug_in_product`, пишет `bug.md`. | Test_scope |

### D. E2E поток данных

```
[заказчик / pо]
   │ /analyze "<задача>"
   ▼
[business-analyst]  ◄── chat-interview ──►  [заказчик]
   │ write analytic/increment.md
   ▼
[validate_increment.py]  (Stop hook)
   │ ok? → continue   │ fail? → возврат к business-analyst
   ▼
[analytic-reviewer]   (subagent call)
   │ status=ok? → continue   │ needs_revision? → возврат к business-analyst
   ▼
HITL approve (в чате — пользователь подтверждает)
   │
   ▼
[/plan_w_team  "<контекст из increment.md>"]
   │ Test Infra Interview + plan write
   ▼
specs/<plan>.md  ← validate_plan.py (Stop hook) + plan-reviewer (subagent)
   │ verdict PASS / FAIL  │ FAIL → правки плана; PASS → exit plan mode (HITL approve)
   ▼
[builder]  по задачам из TaskList
   │ PostToolUse → validator_dispatcher.py (линтеры) на каждый Edit/Write
   ▼
[validator]  на финальном `validate-all`
   │ ok? → continue    │ fail? → возврат к builder'у
   ▼
[merge_gate]  ✗ ДОБАВИТЬ (либо simple `/merge` команда, либо Stop-hook на validator)
   │ approved? → commit + (опционально) tag + build Docker
   ▼
═══════════════════════════════════════════════════════════════════════════
   │ (ручной деплой Docker-образа на стенд)
   ▼
[/test_run]  ✗ ДОБАВИТЬ
   │ запуск declared runner'ов
   ▼
test/runs/<timestamp>/logs.ndjson + junit.xml
   │
   ▼
[analyzer]   (если есть упавшие тесты)
   │
   ├── bug_in_test  → возврат builder'у (правит тест)
   └── bug_in_product → bug.md → /plan_w_team полный цикл повторно
```

### E. Артефакты-контракты между фазами

| Артефакт | Создаёт | Читает | Где живёт |
|---|---|---|---|
| `analytic/original_task.txt` | пользователь (через `business-analyst`) | `analytic-reviewer` | ветка инкремента |
| `analytic/increment.md` | `business-analyst` | `/plan_w_team`, `analytic-reviewer`, `analyzer` | ветка инкремента |
| `analytic/review-report.json` | `analytic-reviewer` | `business-analyst` (на доработку) | ветка инкремента |
| `specs/<plan-name>.md` | `/plan_w_team` | `plan-reviewer`, `builder`, `validator`, `check_diff_scope.py` | ветка инкремента |
| `test/runs/<ts>/logs.ndjson` | `/test_run` | `analyzer` | ветка инкремента |
| `test/runs/<ts>/bug.md` | `analyzer` | `/plan_w_team` (повторный цикл) | ветка инкремента |

### F. Что точно требует прототипирования (риски)

1. **`/analyze` chat-interview flow** в Claude Code. Опыт показывает, что многошаговые диалоги внутри одной сессии Claude Code требуют аккуратного промпта (агент не должен сам себя останавливать). Спайк на 1 случае.
2. **`validate_increment.py`** — формат `increment.md` должен быть строго определён, иначе валидатор не сможет надёжно проверять структуру.
3. **`merge_gate`** — нет однозначного готового паттерна в Claude Code; решаем как simple `/merge` команду или как Stop-hook у `validator`.
4. **`analyzer` + bug-routing**. Семантика «bug_in_test vs bug_in_product» требует доступа к product code + test code одновременно; нужно проверить, не превышает ли контекстное окно при больших падениях.
5. **Корп-прокси для Anthropic API** — вне нашей конфигурации, но без неё всё не запустится. Зависимость от платформенной команды.

### G. Anti-scope (что НЕ делаем на старте)

- Собственный CLI / приложение / сервис (см. PIVOT.md).
- Параллельные worktree per dev-agent (последовательная оркестрация TaskList достаточна).
- Уровни тестирования выше integration (system / e2e / load) до явного запроса.
- Selective regression в `/test_run`.
- История падений analyzer'а с verdict-flip по порогам (пока — 1 прогон = 1 verdict без памяти).
- Stop-loss по analyzer'у — на старте просто эскалация человеку.
- Богатый UX HITL gate'ов — пока обычное «approve y/n» в чате.
- Бюджет / SLA трекинг.
- Многопользовательский режим / параллельные инкременты.

### H. Зафиксированные ранее решения, которые **остаются** в силе

| Тема | Решение | Источник |
|---|---|---|
| Целевые проекты | Java + Spring Boot микросервисы, ~десятки KLoC, greenfield | Блок 1 опроса |
| Артефакты как контракты | Все артефакты — файлы в git, не БД | Блок 5 опроса |
| HITL gate'ы | (a) approve increment, (b) approve plan, (c) approve merge | Блок 2–3 опроса |
| Версионирование | 1 инкремент = 1 ветка | Блок 5 опроса |
| Refs как human-curated knowledge | refs/ (бывший tags registry) | Блок 3 опроса |
| Цель MVP | Один реально используемый бизнес-аналитиком проход Analytic → Dev → Test | Блок K5 калибровки |

Изменилось: реализация (Java/Spring → Claude Code конфиг), LLM-стек (DeepSeek/GigaChat → Claude), CLI (`ms` → нет своего, всё через `claude`).
