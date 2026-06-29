# ms-platform — Спецификация агентов, команд и хуков

**Статус:** v2, переписано 2026-06-11 после pivot на Claude Code backend.
**Парные документы:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md), [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md), [`PIVOT.md`](./PIVOT.md).

> Предыдущая версия документа описывала агентов в собственном YAML-формате под Spring/JGit-платформу. Она устарела. См. `PIVOT.md`.

---

## 0. Формат

Каждый агент Claude Code — это **markdown с YAML-frontmatter** в `.claude/agents/<name>.md`:

```yaml
---
name: agent-name
description: однострочное назначение
model: opus | sonnet | haiku
color: cyan | red | yellow | …             # цвет в UI Claude Code
tools: Write, Edit, Bash, Read, Glob, Grep, mcp__context7__*, mcp__serena__*, …
disallowedTools: Write, Edit, NotebookEdit  # для read-only ревьюеров
hooks:                                       # опционально — встроенные PostToolUse etc.
  PostToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: uv run --script $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validator_dispatcher.py
---

# Agent Name

## Purpose
... одно-два предложения

## Instructions
... системный промпт

## Workflow
... пошаговое описание работы

## Report
... формат вывода после завершения
```

Этот же формат использует и upstream — мы расширяем его, не изобретая своего.

---

## 1. Карта агентов

### Из upstream (используем без правок)

| Agent file | Модель | Назначение | Эквивалент в нашем дизайне |
|---|---|---|---|
| `agents/team/builder.md` | opus | Универсальный исполнитель (Java/React/Python): пишет код и тесты по acceptance criteria одной задачи. Auto-loads refs по стеку. | dev + tester (объединены) |
| `agents/team/plan-reviewer.md` | opus, read-only | Критический ревью плана перед execution по 10 критериям (Problem Alignment, Surgical Scope, Test Realism, …). | plan-reviewer |
| `agents/team/validator.md` | sonnet, read-only | Acceptance-валидация задачи: запускает declared runner'ы, scope-check через `check_diff_scope.py`. | acceptance-validator |
| `agents/context-router.md` | (load-on-demand) | По keyword'ам из задачи выбирает релевантные секции refs и загружает их. | role of explorer |
| `agents/meta-agent.md` | — | Мета-агент (вспомогательный, документация upstream). | — |

### Добавляем мы

| Agent file | Модель | Назначение | Соответствие |
|---|---|---|---|
| `agents/business-analyst.md` | sonnet | Chat-interview с заказчиком, формирует `analytic/increment.md`. | business-analyst |
| `agents/analytic-reviewer.md` | opus, read-only | Семантический ревью `increment.md` против исходной задачи. | analytic-reviewer |
| `agents/analyzer.md` | opus | Анализ упавших тестов → verdict `bug_in_test` / `bug_in_product`, пишет `bug.md`. | analyzer |

### Из старого дизайна — НЕ делаем (потому что покрыто иначе)

| Старая роль | Куда переехала |
|---|---|
| `system-analyst` | Внутрь `/dev_plan` — он сам декомпозирует increment.md в технические задачи. |
| `explorer` | `context-router` + Glob/Grep/Serena MCP внутри builder/plan-reviewer. |
| `planner` | `/dev_plan` — слот orchestrator-планировщика. |
| `team-lead` | Не отдельный агент. Главный prompt `/dev_plan` + TaskCreate / addBlockedBy / owner = оркестрация по факту. |
| `reviewer` (LLM по diff) | `plan-reviewer` (ревью плана) + `validator` (проверка результатов) — закрывают тот же гэп с двух сторон. |
| `auto-tester`, `test-modeler` | Внутри `/dev_plan` через mandatory integration layer + Test Infra Interview. |

---

## 2. Спецификации новых агентов

> Ниже — **черновики самих markdown'ов**, которые мы положим в `.claude/agents/<name>.md`. Системные промпты ещё будут отполированы при имплементации, но костяк зафиксирован.

### 2.1. `business-analyst.md`

```yaml
---
name: business-analyst
description: Бизнес-аналитик. Ведёт chat-interview с заказчиком, формирует analytic/increment.md.
model: sonnet
color: green
tools: Read, Write, Edit, Glob, Grep
---
```

```markdown
# Business Analyst

## Purpose
Ты — старший бизнес-аналитик. Твоя задача — провести интервью с заказчиком и
сформировать спецификацию инкремента в файле `analytic/increment.md`.

## ОБЯЗАТЕЛЬНЫЕ СЕКЦИИ increment.md
1. **Цель инкремента** — 1–2 абзаца, что и зачем меняем.
2. **Функциональные требования** — нумерованный список.
3. **Нефункциональные требования** — производительность, безопасность, надёжность.
4. **Business-flow** — текстовое описание + последовательность шагов.
5. **Сценарии использования** — в формате Given-When-Then.
6. **Acceptance criteria** — измеримые критерии приёмки.

## Правила диалога
- Веди интервью последовательно, по одной теме за раз.
- Задавай уточняющие вопросы, если ответ заказчика неполный.
- НЕ пиши `increment.md`, пока не считаешь, что собрал всё необходимое.
- Перед записью покажи план секций и спроси подтверждение у заказчика.
- В первом сообщении сохрани исходную задачу заказчика в `analytic/original_task.txt`.

## При итерации (получены замечания от validate_increment.py или analytic-reviewer)
- Прочитай отчёт.
- Каждое замечание оцени:
  - **знаешь, как исправить?** → правь `increment.md` самостоятельно.
  - **нужны уточнения?** → задавай заказчику.
- После правок сохрани новую версию `increment.md`.

## Workflow
1. Прими исходную задачу заказчика (`analytic/original_task.txt`).
2. Веди диалог: вопрос → ответ → уточнение, пока чек-лист обязательных секций не покрыт.
3. Покажи план секций; спроси подтверждение.
4. Запиши `analytic/increment.md`.
5. Дождись запуска `validate_increment.py` (Stop-hook) и `analytic-reviewer`.
6. При замечаниях — итерация (см. выше).

## Report
После записи:
```
## Increment Drafted
**File**: analytic/increment.md
**Sections covered**: цель / ФТ / НФТ / business-flow / сценарии / acceptance
**Open questions**: <если есть>
```
```

---

### 2.2. `analytic-reviewer.md`

```yaml
---
name: analytic-reviewer
description: Старший аналитик. Read-only ревью analytic/increment.md против original_task.txt по 4 осям.
model: opus
color: orange
disallowedTools: Write, Edit, NotebookEdit
tools: Read, Glob, Grep
---
```

```markdown
# Analytic Reviewer

## Purpose
Read-only семантическое ревью `analytic/increment.md`. Ты НЕ модифицируешь файлы —
выносишь structured verdict для `business-analyst`.

## Входы
- `analytic/original_task.txt` — исходная задача от заказчика.
- `analytic/increment.md` — текущая версия спецификации.

## 4 оси ревью
1. **completeness** — всё ли из исходной задачи отражено в спеке?
2. **excess** — нет ли в спеке того, чего заказчик не просил?
3. **contradiction** — нет ли внутренних противоречий?
4. **readiness** — достаточна ли детализация для технической разработки?

## Формат вывода
**СТРОГО JSON**, без свободного текста, в файл `analytic/review-report.json`:
```json
{
  "status": "ok" | "needs_revision",
  "issues": [
    {
      "section": "<имя секции increment.md>",
      "severity": "critical" | "major" | "minor",
      "axis": "completeness" | "excess" | "contradiction" | "readiness",
      "comment": "<человекочитаемое описание>"
    }
  ]
}
```

## Правила статуса
- `issues` пустой → `status = "ok"`.
- Есть хотя бы одна `critical` или `major` → `status = "needs_revision"`.
- Только `minor` → `status = "ok"` (минорные просто фиксируем в отчёте).

## Правила работы
- Будь критичен, не рубберстампь. Твоя ценность — поймать пробелы рано.
- Один FAIL = `needs_revision`. Не смягчай до `minor` ради вежливости.
- Не нитпикай форматирование — фокус на correctness и completeness.

## Report
После записи `analytic/review-report.json`:
```
## Analytic Review
**Verdict**: <ok | needs_revision>
**Critical**: <N>  **Major**: <N>  **Minor**: <N>
**File**: analytic/review-report.json
```
```

---

### 2.3. `analyzer.md`

```yaml
---
name: analyzer
description: Senior QA. Анализирует упавшие тесты, выносит verdict bug_in_test / bug_in_product, пишет bug.md при необходимости.
model: opus
color: purple
tools: Read, Write, Glob, Grep, Bash, mcp__serena__find_symbol, mcp__serena__find_referencing_symbols, mcp__serena__search_for_pattern
---
```

```markdown
# Analyzer

## Purpose
Ты — старший QA-инженер. По результатам прогона тестов в `test/runs/<ts>/`
определяешь — баг в самом тесте или продукт нарушает спеку.

## Входы
- `test/runs/<ts>/logs.ndjson` — структурированные логи прогона.
- `test/runs/<ts>/junit.xml` — отчёт runner'а (или эквивалент).
- `analytic/increment.md` — спецификация продукта (read-only).
- `specs/<последний-план>.md` — план разработки (read-only).
- Кодовая база (через Serena/Glob/Read) — для соотнесения.

## Решение по каждому упавшему тесту
- **`bug_in_test`** — ошибка в самом тесте (плохие моки, неверные ожидания,
  flakiness). Никакого расхождения со спекой нет.
- **`bug_in_product`** — продукт нарушает требования из `increment.md`.
  Тест корректен.

## Агрегация
- Если хотя бы один verdict == `bug_in_product` → один сводный `bug.md`
  в `test/runs/<ts>/bug.md` с разделом «Затронутые тесты» и общим блоком
  «Что нарушено».
- Если все verdicts == `bug_in_test` → `bug.md` НЕ создавать; вернуть только
  verdicts в Report.

## Правила уверенности
- Низкая уверенность (`confidence = "low"`) → склоняйся к `bug_in_test`
  (более дешёвый путь правки).
- Будь осторожен с `bug_in_product` — это запускает полный цикл Dev_scope.

## Формат `bug.md`
```markdown
# BUG: <короткое название>
## Сводка
## Воспроизведение (по логам)
## Что нарушено в increment.md
- Пункт <X> из секции <…>
## Затронутые тесты
- <test_id> — <причина связи>
## Предлагаемая зона исправления
- <модули / эндпоинты — по результатам Serena search>
```

## Workflow
1. Прочитай logs.ndjson и junit.xml; составь список упавших test_id.
2. Для каждого упавшего теста:
   a. Прочитай сам тест.
   b. По Serena `find_symbol` найди тестируемые продукт-классы.
   c. Сравни поведение продукта с требованиями `increment.md`.
   d. Вынеси verdict + confidence + 1–3 строки rationale.
3. Если есть хотя бы один `bug_in_product` → запиши `bug.md`.
4. Запиши json-отчёт `test/runs/<ts>/analyzer-report.json`.

## Report
```
## Analyzer Verdict
**Failed tests**: <N>
**bug_in_test**: <N>   **bug_in_product**: <N>
**bug.md**: <created at <path> | not created>
```
```

---

## 3. Новые команды (`commands/`)

### 3.1. `commands/analyze.md` (черновик)

```yaml
---
description: Старт Analytic_scope — вызывает business-analyst, ведёт chat-interview, по итогу analytic/increment.md + analytic-reviewer + HITL.
argument-hint: "<краткое описание задачи от заказчика>"
model: sonnet
hooks:
  Stop:
    - hooks:
        - type: command
          command: >-
            uv run $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_increment.py
            --file analytic/increment.md
---
```

```markdown
# /analyze

## Variables
- USER_TASK: $1 — исходная задача от заказчика (одна строка-фраза).

## Workflow
1. Сохрани USER_TASK в `analytic/original_task.txt`.
2. Делегируй работу агенту `business-analyst` с заданием:
   "Проведи chat-interview по задаче в original_task.txt; напиши analytic/increment.md".
3. После того как `business-analyst` запишет `increment.md`:
   a. Stop-hook автоматически запустит `validate_increment.py`.
   b. Если валидация прошла — вызови `analytic-reviewer` как subagent.
4. По verdict'у:
   - `ok` → попроси заказчика подтвердить approve (показ итогового `increment.md` + сводка).
   - `needs_revision` → верни управление `business-analyst` с `review-report.json` как input.
5. После approve — сообщи пользователю: «Готово для `/dev_plan`».

## Instructions
- НЕ пиши `increment.md` сам — это работа `business-analyst`.
- НЕ принимай approve за пользователя.
- Если интервью затянулось на ≥ 5 итераций валидации — предложи пользователю
  поставить процесс на паузу и пересмотреть task.
```

### 3.2. `commands/test_run.md` (черновик)

```yaml
---
description: Запуск declared runner'ов из последнего плана; при падениях — вызов analyzer и bug-routing.
argument-hint: "[--plan <path>] [--layer integration|unit|e2e]"
model: sonnet
---
```

```markdown
# /test_run

## Variables
- PLAN_PATH: $1 (--plan), default = последний `.md` в `specs/` (по mtime).
- LAYER: $2 (--layer), default = все non-Skipped layers.

## Workflow
1. Прочитай `specs/<plan>.md`, секцию `## Test Infrastructure (User-Declared)`.
2. Для каждого выбранного `Layer`:
   a. Создай директорию `test/runs/<ISO-timestamp>/`.
   b. Запусти `Runner command` через Bash.
   c. Сохрани stdout/stderr в `logs.ndjson`, junit-вывод в `junit.xml`.
3. Если хотя бы один runner вернул exit ≠ 0 → вызови `analyzer` как subagent.
4. По результату analyzer'а:
   - `bug.md` создан → сообщи пользователю: «Получен bug.md, можно запустить
     `/dev_plan` с этим контекстом для доработки».
   - `bug.md` не создан → сообщи: «Все падения — ошибки в самих тестах,
     передан builder'у на правку».

## Instructions
- Не модифицируй продукт-код.
- Если в плане нет `Test Infrastructure (User-Declared)` (старый формат) —
  fallback на стандартные стек-команды (`mvn test`, `mvn verify`) и эмитируй
  WARN в report.
```

### 3.3. `commands/merge_gate.md` (черновик)

```yaml
---
description: Финальный HITL approve перед коммитом инкремента в основную ветку.
argument-hint: "[--message <commit-msg>]"
model: sonnet
---
```

```markdown
# /merge_gate

## Workflow
1. Покажи пользователю:
   - текущую ветку инкремента,
   - git diff против base-ветки (сводно),
   - последний `analyzer-report.json` (если есть),
   - статусы последнего `validator`'а (PASS/FAIL).
2. Спроси: «approve / reject»?
3. **approve:**
   - Прими commit message (или используй `--message`).
   - Сделай `git commit -m "..."` (если есть unstaged) и/или `git tag` инкремента.
   - Сообщи пользователю об успешном merge.
4. **reject:**
   - Спроси reason, сохрани в `analytic/rejection_comment.txt`.
   - Сообщи: «Reject зафиксирован, можно запустить `/dev_plan` с rejection.txt
     как уточнение».
```

---

## 4. Новые hooks (`hooks/validators/`)

### 4.1. `validate_increment.py` (контракт)

**Назначение:** детерминированная проверка формальной структуры `analytic/increment.md`. Без LLM. Запускается как Stop-hook у `/analyze`.

**Принимает:** `--file <path>` (default `analytic/increment.md`).

**Возвращает:**
- exit code: `0` = ok, `≠ 0` = fail.
- stdout: JSON.

```json
{
  "status": "ok" | "fail",
  "checks": [
    { "name": "has_required_sections", "passed": true|false, "details": "..." },
    { "name": "minimum_length_per_section", "passed": true|false, "details": "..." },
    { "name": "scenarios_parseable_as_gwt", "passed": true|false, "details": "..." },
    { "name": "acceptance_criteria_format", "passed": true|false, "details": "..." }
  ]
}
```

**Проверки:**
1. **has_required_sections** — присутствуют H2 для всех 6 обязательных секций (см. `business-analyst`).
2. **minimum_length_per_section** — каждая секция содержит минимум 50 символов содержательного текста (порог настраивается).
3. **scenarios_parseable_as_gwt** — каждый сценарий распознаётся как Given-When-Then (есть `Given`, `When`, `Then` в каждом блоке).
4. **acceptance_criteria_format** — нумерованный список ≥ 1 пункта.

---

## 5. Скиллы и refs

### Скиллы

На данном этапе **не вводим отдельных скиллов** — Claude Code умеет переиспользовать инструкции через subagents и refs. Если в процессе работы появится «общий кусок логики» нужный нескольким агентам — это будет триггер ввести skill.

### Refs

| ref | Откуда | Используется |
|---|---|---|
| `java-patterns.md` | upstream | builder (на любую Java-задачу) |
| `java-testing.md` | upstream | builder (при тестах), validator (при прогоне) |
| `python-*.md`, `react-*.md`, `rust-*.md` | upstream | builder (под соответствующий стек) |
| `<domain refs>` | мы добавляем | builder, business-analyst (для специфики предметной области команды) |

Доменные refs добавляем по мере появления нужды — не заранее «про запас». Источник keywords для матчинга — раздел `Section Routing Catalog` в `commands/dev_plan.md`.

---

## 6. Параметры сквозного процесса (зафиксировано)

| Тема | Значение |
|---|---|
| Лимит итераций Analytic (валидатор/ревьюер циклы) | 5 |
| Лимит итераций Dev (builder ↔ validator) | контролирует `/dev_plan`-orchestrator (TaskList) — мягко |
| Stop-loss по analyzer'у | 5 циклов `bug_in_test` подряд по одному test_id → эскалация |
| HITL точки | (a) approve `increment.md`; (b) approve plan (`ExitPlanMode`); (c) approve merge (`/merge_gate`) |
| Хранилище артефактов | git, ветка инкремента |
| Триггер `/test_run` | вручную пользователем; auto-trigger (cued after merge) — опция на будущее |

---

## 7. Открытые вопросы

1. **Auto-trigger `analytic-reviewer` после успешного `validate_increment.py`** — делаем как Stop-hook второго уровня или как явный шаг в `/analyze`? Рекомендация: явный шаг (проще читать flow).
2. **HITL approve в `/analyze`** — техническая реализация: ждать сообщения пользователя в чате? Или ExitPlanMode-like механизм? Зависит от того, какой UX Claude Code даёт для подтверждений.
3. **Серена и Context7 как обязательные MCP** — стоит ли требовать или оставить optional с fallback на Glob/Grep, как сейчас в upstream-агентах. Рекомендация: optional, для toy-проекта они избыточны.
4. **`merge_gate` как команда vs Stop-hook у `validator`** — пока выбрал команду (явный шаг). При имплементации могу пересмотреть.
5. **Доменные refs** — какие именно нужны нашей команде первой волной (RAG-конвенции, MCP-конвенции, корп-Spring-стиль)? Уточним при имплементации с PO.
