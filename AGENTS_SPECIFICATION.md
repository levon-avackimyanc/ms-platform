# Спецификация агентов и хуков

**Статус:** черновик. Развёрнутое описание участников каждого scope-а: агентов, hook'ов, HITL-gate'ов.
**Парный документ:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md) — общая архитектура.

## Формат спецификации

Каждый участник scope-а описывается YAML-блоком со следующими ключами (часть полей опциональна и зависит от типа):

```yaml
id: <kebab-case>
type: agent | hook | gate
scope: analytic | dev | test
purpose: <одно-два предложения о назначении>

input:                  # артефакты/параметры на вход
  - artifact: <name>
    when: <условие подачи>           # опционально
output:                 # артефакты/параметры на выход
  - artifact: <name>
    action: write_or_update | read_only | …

triggers:
  start: [<события, запускающие участника>]
  complete: <условие завершения>

# Только для agent
skills: [<переиспользуемые скиллы>]
tools:  [<инструменты — chat, file:read/write, exec, git, …>]
model:
  primary: <модель>
  fallback: <модель>
  fallback_on: [<условия — http_5xx, invalid_output, …>]
system_prompt: |
  <черновик системных инструкций>

# Только для hook
runtime: python | shell | …
location: <путь к скрипту>
checks:           # для валидаторов
  - name: <id проверки>
    description: <что делает>

# Только для gate
ui:
  on_first_iteration: <поведение CLI>
  on_subsequent_iterations: <поведение CLI>
  prompt: <текст>
  on_approve: <действие>
  on_reject: <действие>

failure_modes: [<режимы — retry, fallback, escalate>]
hitl: [<HITL-точки>]
```

---

## Analytic_scope

### Параметры scope (решения по Q1–Q4)

```yaml
scope: analytic
iteration_limit: 5             # лимит на циклы valid↔review↔HITL-gate; chat-interview не лимитируется
chat_interview: unlimited      # ведём пока заказчик не скажет «достаточно»
review_policy: always_run      # analytic-reviewer всегда запускается после md-validator (на старте; оптимизируем позже)
commit_strategy: squash_on_approve   # отдельные коммиты во время работы → squash в финальный при HITL approve
artifacts_layout:
  branch: <increment-branch>
  paths:
    - analytic/original_task.txt   # исходная задача от заказчика; в Dev_scope не передаётся
    - analytic/increment.md        # текущая спека
    - analytic/review-report.json  # последний отчёт reviewer'а (для контекста business-analyst'а на следующей итерации)
```

### Поток

```
[заказчик]
   ↓ CLI: ms analyze new "<задача>"
   ↓ original_task.txt сохраняется в analytic/
   ↓
[business-analyst]  ─── chat-interview ───  [заказчик]
   ↓ write increment.md
   ↓
[md-validator]  (hook, Python)
   ↓ ok? → continue
   └ fail? → возврат к business-analyst (loop, лимит 5)
   ↓
[analytic-reviewer]  (LLM)
   ↓ status == "ok"? → continue
   └ needs_revision? → возврат к business-analyst (loop, лимит 5)
   ↓
[manual-validation-gate]  (HITL)
   ↓ approved? → squash коммитов → передача в Dev_scope
   └ rejected? → возврат к business-analyst (loop, лимит 5)
```

---

### Участник 1 — `business-analyst`

```yaml
id: business-analyst
type: agent
scope: analytic
purpose: >
  Ведёт интервью с заказчиком, на основе диалога формирует increment.md
  (ФТ/НФТ, business-flow, сценарии, acceptance criteria).

input:
  - artifact: analytic/original_task.txt
    when: first_run
  - artifact: analytic/increment.md
    when: rerun
  - artifact: analytic/review-report.json
    when: rerun_after_reviewer
  - artifact: validation_report.json
    when: rerun_after_validator
  - artifact: rejection_comment.txt
    when: rerun_after_hitl_reject

output:
  - artifact: analytic/increment.md
    action: write_or_update

triggers:
  start:
    - cli: 'ms analyze new "<задача>"'
    - return_from: md-validator
      condition: status == "fail"
    - return_from: analytic-reviewer
      condition: status == "needs_revision"
    - return_from: manual-validation-gate
      condition: decision == "rejected"
  complete: |
    Агент посчитал, что данных достаточно (чек-лист скилла analytic
    + явное подтверждение заказчика "всё ли учли?").

skills: [analytic]
tools:  [chat, file:read, file:write]

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]

system_prompt: |
  Ты — старший бизнес-аналитик. Твоя задача — провести интервью с заказчиком
  и сформировать спецификацию инкремента в файле increment.md.

  ОБЯЗАТЕЛЬНЫЕ СЕКЦИИ increment.md:
  1. Цель инкремента — 1–2 абзаца, что и зачем меняем.
  2. Функциональные требования — нумерованный список.
  3. Нефункциональные требования — производительность, безопасность, надёжность.
  4. Business-flow — текстовое описание + последовательность шагов.
  5. Сценарии использования — в формате Given-When-Then.
  6. Acceptance criteria — измеримые критерии приёмки.

  ПРАВИЛА ДИАЛОГА:
  - Веди интервью последовательно, по одной теме за раз.
  - Задавай уточняющие вопросы, если ответ заказчика неполный.
  - НЕ пиши increment.md, пока не считаешь, что собрал всё необходимое.
  - Перед записью покажи план секций и спроси подтверждение у заказчика.

  ПРИ ИТЕРАЦИИ (получены замечания от валидатора/ревьюера/HITL-rejection):
  - Прочитай отчёт / комментарий.
  - Каждое замечание оцени: знаешь ли точно, как исправить?
    • ДА → правь increment.md самостоятельно.
    • НЕТ → задай заказчику уточняющий вопрос.
  - После правок сохрани новую версию increment.md.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl:
  - chat_interview_loop
  - manual_validation_gate_after_review
```

---

### Участник 2 — `md-validator`

```yaml
id: md-validator
type: hook
scope: analytic
runtime: python
location: .platform/hooks/analytic/md-validator.py
purpose: >
  Детерминированная проверка формальной структуры increment.md.
  Не делает семантического анализа.

input:
  - artifact: analytic/increment.md
    as: file_path

output:
  - field: exit_code
    values: [0 = ok, "≠ 0 = fail"]
  - field: stdout
    format: json
    schema: |
      {
        "status": "ok" | "fail",
        "checks": [
          { "name": <string>, "passed": <bool>, "details": <string?> }
        ]
      }

triggers:
  start:
    - return_from: business-analyst
      condition: increment.md_written
  complete: process_exits

checks:
  - name: has_required_sections
    description: Все обязательные секции (H2) присутствуют — Цель / ФТ / НФТ / Business-flow / Сценарии / Acceptance criteria
  - name: minimum_length_per_section
    description: Каждая секция содержит минимум N символов (порог настраивается)
  - name: scenarios_parseable_as_gwt
    description: Каждый сценарий распознаётся как Given-When-Then блок
  - name: acceptance_criteria_format
    description: Acceptance criteria — нумерованный список с минимум одним пунктом

failure_modes:
  - on_exception: escalate_to_human   # отдельно от fail-валидации; включает stacktrace
```

---

### Участник 3 — `analytic-reviewer`

```yaml
id: analytic-reviewer
type: agent
scope: analytic
purpose: >
  Семантическое ревью increment.md — сверка с исходной задачей заказчика,
  поиск противоречий и пробелов. Помечен как «дорогой» ($$$ на whiteboard).

input:
  - artifact: analytic/original_task.txt
  - artifact: analytic/increment.md

output:
  - artifact: analytic/review-report.json
    action: write
    schema: |
      {
        "status": "ok" | "needs_revision",
        "issues": [
          {
            "section": <string>,
            "severity": "critical" | "major" | "minor",
            "axis": "completeness" | "excess" | "contradiction" | "readiness",
            "comment": <string>
          }
        ]
      }

triggers:
  start:
    - return_from: md-validator
      condition: status == "ok"
  complete: single_pass

skills: [analytic]
tools:  [file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]
  retry_on_invalid_json: 2

system_prompt: |
  Ты — старший аналитик, проводящий ревью спецификации инкремента.

  ТЕБЕ ПЕРЕДАНЫ:
  - original_task.txt — исходная задача от заказчика (как он её сформулировал).
  - increment.md — текущая версия спецификации, написанная бизнес-аналитиком.

  ТВОЯ ЗАДАЧА — найти расхождения по четырём осям:
  1. completeness — полнота: всё ли из исходной задачи отражено в спеке?
  2. excess — излишество: нет ли в спеке того, чего заказчик не просил?
  3. contradiction — противоречия: нет ли внутренней непротиворечивости?
  4. readiness — готовность: достаточна ли детализация для технической разработки?

  ФОРМАТ ВЫВОДА — СТРОГО JSON, без свободного текста:
  {
    "status": "ok" | "needs_revision",
    "issues": [
      { "section": "<имя>", "severity": "critical|major|minor",
        "axis": "completeness|excess|contradiction|readiness",
        "comment": "<человекочитаемое описание>" }
    ]
  }

  ПРАВИЛА СТАТУСА:
  - issues пустой → status = "ok".
  - есть хотя бы одна critical или major → status = "needs_revision".
  - только minor → status = "ok" (минорные просто фиксируем в отчёте).

failure_modes:
  - on_5xx: fallback_to_secondary_model
  - on_invalid_json: retry_x2_then_escalate

hitl: []
```

---

### Участник 4 — `manual-validation-gate`

```yaml
id: manual-validation-gate
type: gate
scope: analytic
purpose: >
  Финальная человеческая валидация increment.md перед squash и передачей в Dev_scope.

input:
  - artifact: analytic/increment.md
  - artifact: analytic/review-report.json
    optional: true

output:
  - field: decision
    values: [approved, rejected]
  - field: comment
    required_if: decision == "rejected"
    storage: analytic/rejection_comment.txt

triggers:
  start:
    - return_from: analytic-reviewer
      condition: status == "ok"
  complete: user_decision_recorded

ui:
  on_first_iteration: show_full_file
  on_subsequent_iterations: show_diff_against_previous_approved_state
  prompt: "approve / reject?"
  on_reject: open_editor_for_comment
  on_approve:
    - squash_iteration_commits_into_single_commit
    - transition_to: dev_scope
  on_reject_action: return_to: business-analyst

failure_modes:
  - on_exit_without_decision: keep_state_for_resume
```

---

### Решения по Analytic_scope (закрытые вопросы)

| Вопрос | Решение |
|---|---|
| Q1. Где хранится `original_task.txt`? | В `analytic/` в той же ветке инкремента, рядом с `increment.md`. В Dev_scope не передаётся. |
| Q2. Лимит итераций в Analytic_scope? | 5 на циклы validator/reviewer/HITL-gate (как в Dev_scope). Chat-interview не лимитируется. |
| Q3. Экономия на дорогом analytic-reviewer'е? | На старте — всегда запускаем после валидатора. Оптимизация по статистике, потом. |
| Q4. Гранулярность коммитов? | Отдельные коммиты во время работы → **squash в один коммит при HITL approve**. |

---

## Dev_scope

### Параметры scope

```yaml
scope: dev
input_artifact: analytic/increment.md     # read-only вход из Analytic_scope
iteration_limit_per_agent: 5              # цикл dev/tester ↔ линтеры ↔ reviewer; после — эскалация человеку
escalation_actions:                       # действия человека при достижении лимита
  - add_clarification_for_agent_and_retry
  - edit_Plan.md (отмена/разбиение задачи)
  - return_to_planning
parallel_workers:
  isolation: git_worktree                 # каждый dev в собственном worktree
  spawn_by: team-lead
  one_shot: team-lead                     # team-lead завершается сразу после spawn
  max_concurrent: 5                       # лимит одновременно работающих worker'ов (локальный ресурс)
tester_mode: sequential_after_dev         # tester работает в том же worktree после dev (не TDD-параллель)
linters:
  blocking: all                           # любая ошибка любого линтера блокирует следующий шаг
  set: [spotless, spotbugs, jspecify, jacoco, sonar]
  thresholds:
    jacoco_coverage_min: 90               # покрытие < 90% блокирует merge
    sonar_gate: builtin                   # встроенного quality gate Sonar достаточно
reviewer:
  granularity: per_agent                  # отдельный ревью на каждого dev/tester после линтеров
build:
  produces: docker_image
  after: hitl_merge                       # сборка запускается после approved merge в основную ветку
commit_strategy: per_task_inside_worktree → merge_into_main_branch
artifacts_layout:
  branch: <increment-branch> (та же что в Analytic, но Dev пишет в dev/)
  paths:
    - dev/tech_spec.md                    # от system-analyst
    - dev/exploration-report.md           # от explorer (опционально)
    - dev/Plan.md                         # от planner, задачи + теги
    - .worktrees/<task-or-group>/         # рабочие ветки dev/tester (вспомогательное)
```

### Поток

```
[analytic/increment.md] (read-only)
   ↓
[system-analyst]   ──► dev/tech_spec.md
   ↓
[explorer]         ──► dev/exploration-report.md  +  накопленный контекст по кодбазе
   ↓
[planner]          ──► dev/Plan.md  (задачи + теги из tags-registry; стоит скилл stack-definition)
   ↓
[team-lead]        ──► создаёт worktree'ы, spawn'ит dev/tester по группам задач, завершается
   │
   ├── worktree A ──► [dev #1] ──► [linter-hooks] ──► [reviewer] ─┐
   │                       ↻ fail → возврат к dev (лимит 5)       │
   │                  ──► [tester #A] ──► [linter-hooks] ──► [reviewer] ─┤
   │                       ↻ fail → возврат к tester (лимит 5)    │
   ├── worktree B ──► [dev #2] ──► [linter-hooks] ──► [reviewer] ─┤
   │                  ──► [tester #B] ──► …                       │
   └── …                                                          │
                                                                  ▼
                                            [manual-merge-gate] (HITL)
                                                                  ↓
                                            merge worktree'ев в основную ветку
                                                                  ↓
                                                              [build]
                                                                  ↓
                                                         Docker-образ
                                                                  ↓
                                                    (manual deploy на стенд)
```

---

### Участник 1 — `system-analyst`

```yaml
id: system-analyst
type: agent
scope: dev
purpose: >
  Переводит бизнес-инкремент (increment.md) в техническое ТЗ (tech_spec.md):
  API-спецификации, контракты данных, граничные условия, технические ограничения.

input:
  - artifact: analytic/increment.md
    mode: read_only

output:
  - artifact: dev/tech_spec.md
    action: write_or_update

triggers:
  start:
    - phase_transition: from_analytic_scope_completed
    - return_from: bug-from-test-scope     # при BUG из Test_scope запускаем полный цикл (см. Test_scope)
  complete: single_pass (или итерация после уточнения, если эскалация назад)

skills: [analytic, explorer]   # analytic — общая методология; explorer — доступ к контексту кодбазы
tools:  [file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — старший системный аналитик. Тебе передан бизнес-инкремент в файле
  increment.md, написанный бизнес-аналитиком.

  ТВОЯ ЗАДАЧА — написать tech_spec.md — техническую спецификацию, по которой
  планировщик сможет составить план разработки, а dev-агенты — корректно реализовать.

  ОБЯЗАТЕЛЬНЫЕ СЕКЦИИ tech_spec.md:
  1. Технические цели — переформулировка бизнес-целей на инженерном языке.
  2. API-контракты — endpoints / schemas / коды ошибок / форматы данных.
  3. Модель данных — сущности, поля, отношения, инварианты.
  4. Интеграции — внешние системы и протоколы взаимодействия.
  5. Нефункциональные характеристики — performance, security, observability requirements.
  6. Граничные условия — edge cases, ошибки, отказоустойчивость.
  7. Технические ограничения / предположения.

  ПРАВИЛА:
  - НЕ изобретай требований, которых нет в increment.md.
  - Если в increment.md есть пробел или противоречие — НЕ замазывай его, явно отметь.
  - Не закладывай конкретные библиотеки/фреймворки — это задача планировщика и stack-definition.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_increment_md_gaps: escalate_to_analytic_scope   # запрос исправления спеки

hitl: []
```

---

### Участник 2 — `explorer`

```yaml
id: explorer
type: agent
scope: dev   # тот же агент используется и в test_scope
purpose: >
  Изучает существующий многомодульный проект — структуру, стек, точки расширения,
  существующие интеграции — чтобы планировщик мог корректно расставить теги в Plan.md.

input:
  - artifact: dev/tech_spec.md
    mode: read_only
  - target: target_project_repo

output:
  - artifact: dev/exploration-report.md
    action: write
    content: |
      Описание модулей, стека, релевантных точек кода, использованных в проекте
      инструментов (mcp/rag/liquibase/…) — то, что важно для планировщика.

triggers:
  start:
    - return_from: system-analyst
  complete: single_pass

skills: [explorer]
tools:  [serena, context7, code-indexer, lsp, file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — старший разработчик-исследователь. Изучи переданный многомодульный проект
  и составь exploration-report.md так, чтобы планировщик мог принять решение
  о структуре Plan.md и расстановке тегов.

  ИНСТРУМЕНТЫ:
  - serena    — семантический поиск по коду.
  - context7  — документация подключенных библиотек.
  - code-indexer — собственный индекс репозитория.
  - lsp       — точные ссылки и навигация.

  ЧТО ВКЛЮЧИТЬ В ОТЧЁТ:
  1. Карта модулей с однострочным описанием назначения каждого.
  2. Стек: языки, ключевые библиотеки, инфраструктурные компоненты.
  3. Инструменты, используемые в проекте (mcp/rag/liquibase/spring data/…),
     с указанием в каком модуле и как.
  4. Точки расширения, релевантные текущему tech_spec.md
     (где, скорее всего, будут вноситься изменения).
  5. Существующие тесты — где и какого типа.

  НЕ описывай весь проект — фокус на том, что нужно планировщику и dev-агентам
  для этого конкретного инкремента.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl: []
```

---

### Участник 3 — `planner`

```yaml
id: planner
type: agent
scope: dev
purpose: >
  Формирует Plan.md — упорядоченный список задач для dev-агентов, с разметкой
  каждой задачи тегами из tags-registry (через скилл stack-definition).

input:
  - artifact: dev/tech_spec.md
  - artifact: dev/exploration-report.md
  - artifact: .platform/tags-registry.md

output:
  - artifact: dev/Plan.md
    action: write
    schema_hint: |
      # Plan
      ## Задача 1
      - описание
      - acceptance criteria
      - теги: [tag-1, tag-2]
      - оценка сложности (опционально)
      ## Задача 2
      …

triggers:
  start:
    - return_from: explorer
  complete: single_pass

skills: [stack-definition, explorer]
tools:  [file:read, file:write, serena, context7, code-indexer, lsp]

model:
  primary: deepseek-v4-pro        # $$$, самая дорогая роль
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — техлид-планировщик. По tech_spec.md и exploration-report.md составь
  Plan.md так, чтобы команда dev-агентов могла его реализовать параллельно.

  ШАГИ:
  1. Разбей работу на минимальное число связных задач (избегай "1 строка кода = 1 задача").
  2. Для каждой задачи через скилл stack-definition подбери теги из tags-registry,
     соответствующие используемым инструментам (mcp/rag/liquibase/…).
     Тег должен совпадать или быть синонимом известного в реестре.
  3. Укажи зависимости между задачами явно (если есть).
  4. Для каждой задачи запиши acceptance criteria, по которым tester сможет
     понять, что unit-тесты покрывают именно её.

  ПРАВИЛА:
  - НЕ дублируй задачи.
  - Если для задачи не нашлось подходящего тега в реестре — явно отметь
    "тег отсутствует, требуется внести в registry" (это сигнал куратору).
  - Не больше 10 задач в плане; если больше — попробуй сгруппировать.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_missing_tags: warn_in_Plan.md_and_escalate

hitl: []
```

---

### Участник 4 — `team-lead`

```yaml
id: team-lead
type: agent
scope: dev
purpose: >
  Одноразовый агент-оркестратор: читает Plan.md, создаёт worktree'ы,
  spawn'ит N dev-агентов (и tester'ов), завершается.
  Никакой работы после spawn не делает — pipeline идёт параллельно сам.

input:
  - artifact: dev/Plan.md

output:
  - effect: created_worktrees
  - effect: spawned_agents [dev × N, tester × M]
  - artifact: dev/dispatch-manifest.json
    schema: |
      {
        "workers": [
          {
            "worktree": "<path>",
            "branch": "<name>",
            "tasks": ["task-id-1", "task-id-2"],
            "dev_agent_id": "<id>",
            "tester_agent_id": "<id>"
          }
        ]
      }

triggers:
  start:
    - return_from: planner
  complete: after_spawning_all_workers (одноразовый, не ждёт их)

skills: []
tools:  [file:read, file:write, git:create-worktree, agent:spawn]

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]
  mode: deterministic   # низкая температура — творчество не нужно

system_prompt: |
  Ты — тимлид. Прочитай Plan.md и распредели задачи на параллельных исполнителей.

  ПРАВИЛА:
  - Один dev-агент может взять НЕСКОЛЬКО связанных задач. Цель — минимизировать
    количество worker'ов при сохранении параллелизма.
  - Задачи с зависимостями НЕ должны попасть в параллельные worktree'ы.
  - Для каждой группы задач создай отдельный worktree и ветку.
  - Запиши результат в dispatch-manifest.json и spawn'и dev/tester для каждой группы.

  ОГРАНИЧЕНИЯ:
  - Не пиши код сам.
  - Не общайся с заказчиком.
  - После spawn — завершайся.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl: []
```

---

### Участник 5 — `dev`

```yaml
id: dev
type: agent
scope: dev
purpose: >
  Реализует выделенные ему задачи из Plan.md внутри собственного worktree.
  Подгружает референсы по тегам своих задач из tags-registry.

input:
  - artifact: dev/Plan.md
    mode: full_file_with_selector       # передаём весь Plan.md + список assigned_task_ids
    selector: assigned_task_ids
  - artifact: tags-registry references
    selector: tags_of_assigned_tasks
  - artifact: dev/tech_spec.md
    mode: read_only

output:
  - effect: code_changes_in_worktree
  - effect: commits_in_worker_branch

triggers:
  start:
    - spawned_by: team-lead
    - return_from: linter-hooks
      condition: status == "fail"
    - return_from: reviewer
      condition: status == "needs_changes"
  complete: |
    Все назначенные задачи реализованы и acceptance criteria выполнены
    (по самооценке dev'а).

skills: []          # dev'ам общие скиллы НЕ нужны
tools:  [file:read, file:write, file:edit, exec, git:commit (внутри своего worktree)]
context_includes:
  - tag_references_loaded_dynamically   # подгружается из tags-registry по тегам задач

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]

system_prompt: |
  Ты — dev-агент. Тебе назначены задачи из Plan.md и теги, по которым подгружены
  референсы — это инструкции как ПРАВИЛЬНО использовать конкретные инструменты
  (mcp/rag/liquibase/…).

  ПРАВИЛА:
  - Работай ТОЛЬКО в назначенном worktree.
  - Используй референсы по тегам как обязательную инструкцию, не отклоняйся.
  - Закрой все назначенные задачи; для каждой проверь acceptance criteria.
  - Делай атомарные коммиты — один коммит = один логически связный кусок работы.
  - НЕ пиши unit-тесты — это работа tester'а.

  ПРИ ИТЕРАЦИИ (вернулся отчёт линтера или reviewer'а):
  - Прочитай отчёт.
  - Исправь все указанные ошибки.
  - Не вноси изменения за пределами замечаний.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_iteration_limit_exceeded: escalate_to_human   # лимит 5

hitl:
  - escalation_after_5_iterations
```

---

### Участник 6 — `tester`

```yaml
id: tester
type: agent
scope: dev
purpose: >
  Пишет unit-тесты на код, который произвёл dev-агент в том же worktree.
  Уровни выше unit (integration/system/E2E/load) — ответственность Test_scope.

input:
  - artifact: dev/Plan.md
    mode: full_file_with_selector
    selector: assigned_task_ids
  - artifact: code_produced_by_dev
    mode: read_only
  - artifact: tags-registry references
    selector: tags_of_assigned_tasks   # для тега "unit-testing-style" и т.п., если есть

output:
  - effect: unit_test_files_in_worktree
  - effect: commits_in_worker_branch

triggers:
  start:
    - dev_completed_assigned_tasks (в том же worktree)
    - return_from: linter-hooks
      condition: status == "fail"
    - return_from: reviewer
      condition: status == "needs_changes"
  complete: |
    Unit-тесты покрывают acceptance criteria всех назначенных задач
    (по самооценке tester'а + успешный прогон unit-уровня).

skills: []
tools:  [file:read, file:write, file:edit, exec (запуск unit-тестов), git:commit]
context_includes:
  - tag_references_loaded_dynamically

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]

system_prompt: |
  Ты — tester. Тебе доступен код, написанный dev-агентом, и acceptance criteria
  задач из Plan.md.

  ТВОЯ ЗАДАЧА — написать unit-тесты, которые:
  1. Покрывают acceptance criteria каждой назначенной задачи.
  2. Тестируют граничные условия (null/empty/limit values).
  3. Изолированы — не зависят от внешних сервисов (мокай зависимости).

  ПРАВИЛА:
  - Прогоняй тесты после написания. Если падают — правь тесты, не код.
  - НЕ модифицируй продуктовый код. Если видишь баг — отметь его в комментарии
    к коммиту, дальше разберётся reviewer/dev на следующей итерации.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_iteration_limit_exceeded: escalate_to_human

hitl:
  - escalation_after_5_iterations
```

---

### Участник 7 — `linter-hooks`

```yaml
id: linter-hooks
type: hook
scope: dev
purpose: >
  Запуск статических анализаторов и проверок покрытия на изменённом коде.
  Любая ошибка любого из линтеров — блокирует переход к reviewer'у.

input:
  - target: worker_branch_diff

output:
  - field: exit_code
    values: [0 = all_ok, "≠ 0 = some_failed"]
  - artifact: linter-report.json
    schema: |
      {
        "spotless":  { "status": "ok|fail", "details": "…" },
        "spotbugs":  { "status": "ok|fail", "details": "…" },
        "jspecify":  { "status": "ok|fail", "details": "…" },
        "jacoco":    { "status": "ok|fail", "details": "…" },
        "sonar":     { "status": "ok|fail", "details": "…" }
      }

triggers:
  start:
    - return_from: dev (commit_made)
    - return_from: tester (commit_made)
  complete: all_linters_finished

runtime: shell
location: .platform/hooks/dev/
checks:
  - name: spotless
    description: format/style
  - name: spotbugs
    description: статический анализ ошибок
  - name: jspecify
    description: nullness-аннотации, контракты
  - name: jacoco
    description: покрытие
    threshold: ≥ 90% (меньше — fail)
  - name: sonar
    description: code quality / security smells
    gate: builtin (используем встроенный Sonar quality gate)

failure_modes:
  - on_linter_tool_crash: escalate_to_human (отдельно от fail-валидации)
  - on_blocking_fail: return_to_originating_agent (dev или tester)
```

---

### Участник 8 — `reviewer`

```yaml
id: reviewer
type: agent
scope: dev
purpose: >
  LLM code-review на diff одного worker'а (dev или tester). Гранулярность — per-agent.

input:
  - artifact: dev/Plan.md
    selector: tasks_of_reviewed_worker
  - artifact: dev/tech_spec.md
    mode: read_only
  - target: worker_branch_diff_since_base
  - artifact: linter-report.json

output:
  - artifact: review-report.json
    action: write
    schema: |
      {
        "status": "ok" | "needs_changes",
        "findings": [
          {
            "file": "<path>",
            "line": <int>,
            "severity": "critical|major|minor",
            "category": "correctness|design|readability|security|tests",
            "comment": "<суть>"
          }
        ]
      }

triggers:
  start:
    - return_from: linter-hooks
      condition: status == "ok"
  complete: single_pass

skills: []
tools:  [file:read, git:diff, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]
  retry_on_invalid_json: 2

system_prompt: |
  Ты — старший reviewer. Изучи diff worker'а и отчёт линтеров и сделай review.

  ОСИ ОЦЕНКИ:
  1. correctness — соответствует ли реализация задаче и acceptance criteria?
  2. design — соответствует ли решение архитектурным паттернам проекта (по tech_spec.md)?
  3. readability — читаемо ли?
  4. security — есть ли явные уязвимости?
  5. tests — если это tester'овский diff: покрытие, изоляция, граничные случаи.

  СТАТУС:
  - findings пустой → status = "ok".
  - хотя бы одна critical или major → status = "needs_changes".

failure_modes:
  - on_5xx: fallback_to_secondary_model
  - on_invalid_json: retry_x2_then_escalate

hitl: []
```

---

### Участник 9 — `manual-merge-gate`

```yaml
id: manual-merge-gate
type: gate
scope: dev
purpose: >
  Финальная человеческая проверка перед merge всех worker-веток в основную ветку
  инкремента и запуском build.

input:
  - target: all_worker_branches
  - artifact: dev/dispatch-manifest.json
  - artifact: review-report.json (последний для каждого worker'а)

output:
  - field: decision
    values: [approved, rejected]
  - field: comment
    required_if: decision == "rejected"

triggers:
  start:
    - all_workers_completed (reviewer status == "ok" по каждому)
  complete: user_decision_recorded

ui:
  show:
    - workers_summary (worktree → tasks → reviewer status)
    - cumulative_diff_against_base
  prompt: "approve merge / reject?"
  on_approve:
    - merge_strategy: sequential   # одну ветку за другой; при конфликте — human resolution
    - merge_worker_branches_into_increment_branch
    - trigger: build
  on_reject:
    - capture_rejection_target (какой именно worker не устраивает)
    - return_to: matching worker (dev or tester)

failure_modes:
  - on_merge_conflict: pause_and_request_human_resolution
  - on_exit_without_decision: keep_state_for_resume
```

---

### Участник 10 — `build`

```yaml
id: build
type: hook
scope: dev
purpose: >
  Сборка Docker-образа продукта по объединённому коду в основной ветке инкремента.
  Результат — артефакт для ручного деплоя.

input:
  - target: increment_branch_head

output:
  - artifact: docker_image
    tag: <product>:<increment-id>

triggers:
  start:
    - return_from: manual-merge-gate
      condition: decision == "approved"
  complete: build_succeeded_or_failed

runtime: shell
location: .platform/hooks/dev/build.sh

failure_modes:
  - on_build_failure: escalate_to_human (откат merge не делаем автоматически)
```

---

### Решения по Dev_scope

Большинство решений уже было зафиксировано в архитектурной фазе. Здесь — то, что вытекает при детализации:

| Тема | Решение |
|---|---|
| `system-analyst` запускается повторно при BUG из Test_scope? | Да, идём по полному циклу (см. Test_scope spec). |
| Связь explorer ↔ planner | exploration-report.md — артефакт-мост, читается планировщиком. |
| Один dev может взять несколько связных задач | Да; team-lead решает группировку и фиксирует в dispatch-manifest.json. |
| Гранулярность reviewer | per-agent (не один большой ревью на весь Plan). |
| Build после HITL approve | Да; до approve не собираем (экономим время и регистры образов). |

---

### Закрытые вопросы по Dev_scope (Q1, Q2, Q3, Q4, Q5, Q7)

| Вопрос | Решение |
|---|---|
| Q1. tester relative to dev | **sequential-after-dev** в том же worktree (MVP). TDD-вариант откладываем. |
| Q2. merge стратегия | **sequential merge** worker-веток; при конфликте — human resolution. |
| Q3. как dev/tester получают Plan.md | **весь файл + selector** assigned_task_ids (агент видит контекст соседних задач). |
| Q4. порог jacoco | **≥ 90%** покрытия. Меньше — fail линтера, блокирует merge. |
| Q5. Sonar gate | **встроенного quality gate Sonar достаточно**. |
| Q7. max параллельных worktree'ев | **5** одновременно (локальный ресурс). |

### Остался открытым

- **Q6. UX команд эскалации после 5 итераций.** Зафиксированы три действия (clarification / edit Plan.md / return to planning) — нужен формат CLI-команд (`ms dev clarify <agent-id> --comment "..."`, `ms dev edit-plan`, `ms dev replan`). Прорабатываем отдельно как UX-сессию.

---

## Test_scope

Зеркалит Dev_scope в части «sys-analyst → explorer → planner → team-lead → workers → reviewer → HITL → …», добавляя:
- **test-modeler** (новый шаг между explorer и planner — строит «тестовую модель»);
- **runtime-цикл `exec → analyzer`** с роутингом BUG обратно в Dev_scope.

### Параметры scope

```yaml
scope: test
input_artifact: analytic/increment.md       # тот же артефакт, что в Dev (read-only)
                                            # whiteboard называл его func_req.md — это синоним
target_repos:
  product_repo:                             # репозиторий продукта (откуда деплоится Docker)
    mode: read_only                         # Test_scope не модифицирует продуктовый код
  test_repo:                                # отдельный репозиторий автотестов
    mode: read_write
versioning:
  strategy: matching_git_tag                # git tag на оба репо с одинаковым именем
  tagged_by: manual-merge-gate (test)
iteration_limit_per_agent: 5
escalation_actions: same_as_dev_scope       # clarification / edit Plan.md / replan
parallel_workers:
  isolation: git_worktree (within test_repo)
  max_concurrent: 5
linters:
  blocking: all
  set: [spotless, spotbugs, jspecify, sonar]   # БЕЗ jacoco — покрытие тестового кода не измеряем
  thresholds:
    sonar_gate: builtin
test_levels:                                # уровни пирамиды, покрываемые scope (unit пропущен)
  - integration
  - system
  - e2e_ui
  - load
trigger_modes:
  cued_by_dev_approve: |
    После Dev approve система НЕ запускает Test_scope автоматически (деплой ручной).
    В state-файле инкремента выставляется статус ready_for_test
    и в CLI печатается подсказка: "запусти `ms test new <id>` после деплоя".
  cli:
    - "ms test new <increment-id>"            # запустить написание автотестов до exec
    - "ms test run <increment-id> --stand <url>"  # запустить exec против развёрнутого стенда
regression_strategy: run_all_existing_plus_new   # MVP; оптимизацию (selective regression) отложили
analyzer_verdict_thresholds:
  bug_in_test_repeats_before_flip: 2     # после 2 повторных bug_in_test по одному test_id → verdict flip в bug_in_product
  stop_loss_bug_in_test_cycles: 5        # 5 последовательных bug_in_test подряд → эскалация человеку
on_bug_routing: full_cycle_dev_scope (sys-analyst → explorer → planner → …)
bug_report_aggregation: single_per_run   # один сводный bug.md за прогон, с разделом "Затронутые тесты"
testcontainers_responsibility: auto-tester  # тестер сам поднимает Testcontainers через @Testcontainers; e2e/load локально не гонят
artifacts_layout:
  in_product_repo (та же ветка инкремента, что в Dev):
    - test/test-spec.md            # от system-analyst
    - test/exploration-report.md   # от explorer (test mode)
    - test/test-model.md           # от test-modeler
    - test/Plan.md                 # от planner
    - test/dispatch-manifest.json  # от team-lead
    - test/runs/<timestamp>/       # один прогон exec → analyzer:
        - logs.ndjson              #   входные логи
        - analyzer-report.json     #   решение analyzer'а
        - bug.md (опц.)            #   если расхождение со спекой — обращение в Dev_scope
  in_test_repo (отдельный):
    - source code автотестов (Java/Spring; JUnit/Spring Test/Testcontainers/JMeter/…)
```

### Поток

```
[Dev_scope: HITL approve + build → Docker-образ]
   ↓
[ручной деплой Docker-образа на стенд]
   ↓
(триггер Test_scope: auto или manual)
   ↓
[analytic/increment.md] (read-only, тот же что в Dev)
   ↓
[system-analyst] ──► test/test-spec.md
   ↓
[explorer (test mode)] ──► test/exploration-report.md  (код продукта + существующий test-репо)
   ↓
[test-modeler] ──► test/test-model.md  (структурированный список тест-сценариев по уровням)
   ↓
[planner] ──► test/Plan.md  (задачи + теги уровней пирамиды + теги техник)
   ↓
[team-lead (test)] ──► создаёт worktree'ы в test-репо, spawn'ит auto-tester'ов, завершается
   │
   ├── worktree integ ──► [auto-tester #1] ─► [linter-hooks] ─► [reviewer] ─┐
   ├── worktree sys   ──► [auto-tester #2] ─► [linter-hooks] ─► [reviewer] ─┤
   ├── worktree e2e   ──► [auto-tester #3] ─► [linter-hooks] ─► [reviewer] ─┤
   └── worktree load  ──► [auto-tester #4] ─► [linter-hooks] ─► [reviewer] ─┤
                                                                            ▼
                                                       [manual-merge-gate (test)] (HITL)
                                                                            ↓
                                              merge worker-веток + git tag (matching product)
                                                                            ↓
                                                                       [exec]
                                                                            ↓ (новые + все регрессы)
                                                                    [analyzer] (LLM)
                                                                            │
                                                                            ├── баг в тесте → возврат к соответствующему auto-tester (лимит 5)
                                                                            └── расхождение со спекой → bug.md → BUG в Dev_scope (полный цикл от sys-analyst)
```

---

### Участник 1 — `system-analyst` (test mode)

```yaml
id: system-analyst
type: agent
scope: test                          # тот же агент, что в Dev_scope, но запускается с test-промптом
mode: test                           # переключатель ролевого контекста
purpose: >
  Переводит increment.md в test-spec.md — список того, что и какими уровнями
  пирамиды нужно проверить.

input:
  - artifact: analytic/increment.md
    mode: read_only
  - artifact: bug.md
    when: rerun_after_bug   # при возврате BUG из analyzer'а в Dev_scope sys-analyst тоже видит этот BUG

output:
  - artifact: test/test-spec.md
    action: write_or_update
    schema_hint: |
      # Test Spec
      ## Покрытие требований
        - таблица: requirement_id → планируемые уровни проверки
      ## Acceptance критерии для тестов
      ## Особые сценарии (failover, perf, security)
      ## Ограничения окружения

triggers:
  start:
    - phase_transition: from_dev_scope_completed_and_deployed
    - cli: "ms test new <increment-id>"
  complete: single_pass

skills: [analytic, explorer]
tools:  [file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — старший системный аналитик в тест-режиме. По increment.md составь
  test-spec.md — план проверки соответствия продукта спецификации.

  ПРАВИЛА:
  - НЕ изобретай требований, которых нет в increment.md.
  - Для каждого функционального требования укажи, на каких уровнях пирамиды
    его нужно проверить (integration / system / e2e / load).
  - Помечай acceptance criteria, по которым tester'ы будут писать тесты.
  - Для НФТ (performance, security) — отдельная секция с конкретными порогами.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_increment_md_gaps: escalate_to_analytic_scope

hitl: []
```

---

### Участник 2 — `explorer` (test mode)

```yaml
id: explorer
type: agent
scope: test
mode: test
purpose: >
  В test-режиме изучает (а) кодбазу продукта — точки расширения, эндпоинты,
  модель данных; (б) существующий test-репо — структуру, фреймворки,
  имеющиеся тесты для регрессионного учёта.

input:
  - artifact: test/test-spec.md
    mode: read_only
  - target: product_repo (read_only)
  - target: test_repo    (read_only at this stage)

output:
  - artifact: test/exploration-report.md
    action: write

triggers:
  start:
    - return_from: system-analyst (test mode)
  complete: single_pass

skills: [explorer]
tools:  [serena, context7, code-indexer, lsp, file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — исследователь в test-режиме. Составь exploration-report.md, в котором:

  ПО ПРОДУКТУ:
  - Эндпоинты / интерфейсы, упомянутые в test-spec.md (точные ссылки на код).
  - Модель данных и точки её мутации.
  - Внешние зависимости (БД, очереди, сторонние API) — что мокать, что поднимать.

  ПО ТЕСТ-РЕПО:
  - Используемые фреймворки на каждом уровне пирамиды.
  - Структура папок / конвенции именования тестов.
  - Существующие тесты по областям test-spec (для регрессионного учёта).

  Цель — дать planner'у достаточно контекста, чтобы расставить теги
  уровней пирамиды и техник тестирования.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl: []
```

---

### Участник 3 — `test-modeler`

```yaml
id: test-modeler
type: agent
scope: test
purpose: >
  Строит структурированный список тест-сценариев — «тестовую модель» —
  по уровням пирамиды (integration / system / e2e / load).
  Это НЕ формальная state-machine или decision-table; это аккуратно сгруппированный
  набор сценариев с входами, ожиданиями и привязкой к acceptance criteria.

input:
  - artifact: test/test-spec.md
  - artifact: test/exploration-report.md

output:
  - artifact: test/test-model.md
    action: write
    schema_hint: |
      # Test Model
      ## Integration
        - scenario: ...
          inputs: ...
          expected: ...
          covers: [requirement-id, acceptance-criterion-id]
      ## System
        ...
      ## E2E / UI
        ...
      ## Load
        ...

triggers:
  start:
    - return_from: explorer (test mode)
  complete: single_pass

skills: [analytic]
tools:  [file:read, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — техлид-тестировщик. По test-spec.md и exploration-report.md составь
  test-model.md.

  ПРАВИЛА:
  - Группируй сценарии по уровням пирамиды.
  - Для каждого сценария укажи: входы, ожидаемый результат, что именно покрывает.
  - Избегай дублирования сценариев между уровнями (один и тот же бизнес-шаг
    обычно тестируется на одном уровне; кросс-уровневое перекрытие — только
    там, где это намеренная защита).
  - Покрой граничные случаи и негативные сценарии.
  - Не предлагай конкретные библиотеки или код — это задача планировщика
    и auto-tester'ов.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl: []
```

---

### Участник 4 — `planner` (test mode)

```yaml
id: planner
type: agent
scope: test
mode: test                          # повторно используем агент, тот же id
purpose: >
  Превращает test-model.md в Plan.md — упорядоченный список тест-задач
  для auto-tester'ов с разметкой тегами (уровень пирамиды + техника).

input:
  - artifact: test/test-spec.md
  - artifact: test/exploration-report.md
  - artifact: test/test-model.md
  - artifact: .platform/tags-registry.md

output:
  - artifact: test/Plan.md
    action: write
    schema_hint: |
      ## Задача 1
      - описание
      - acceptance criteria
      - level: integration | system | e2e | load
      - теги: [autotest-integration, testcontainers, …]

triggers:
  start:
    - return_from: test-modeler
  complete: single_pass

skills: [stack-definition, explorer]
tools:  [file:read, file:write, serena, context7, code-indexer, lsp]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — техлид-планировщик в тест-режиме. По test-model.md составь Plan.md
  для команды автотестировщиков.

  ШАГИ:
  1. Сгруппируй сценарии в задачи (1 задача = связный кусок работы, не строка).
  2. Каждой задаче назначь level (один уровень пирамиды на задачу) и теги
     через скилл stack-definition (тег уровня + теги техник: testcontainers,
     wiremock, restassured, selenium, jmeter и т.п.).
  3. Не больше 10 задач — иначе сгруппируй.
  4. Если для задачи не нашлось подходящего тега — явно отметь
     "тег отсутствует, требуется внести в registry".

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_missing_tags: warn_in_Plan.md_and_escalate

hitl: []
```

---

### Участник 5 — `team-lead` (test mode)

```yaml
id: team-lead
type: agent
scope: test
mode: test
purpose: >
  Одноразовый: читает Plan.md, создаёт worktree'ы в test_repo, spawn'ит auto-tester'ов
  и reviewer'а под каждого. Сразу завершается.

input:
  - artifact: test/Plan.md

output:
  - effect: created_worktrees_in_test_repo
  - effect: spawned_agents [auto-tester × N]
  - artifact: test/dispatch-manifest.json

triggers:
  start:
    - return_from: planner (test mode)
  complete: after_spawning_all_workers

skills: []
tools:  [file:read, file:write, git:create-worktree, agent:spawn]

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]
  mode: deterministic

system_prompt: |
  Ты — тимлид тест-команды. Распредели задачи Plan.md на параллельных
  auto-tester'ов в test-репозитории.

  ПРАВИЛА:
  - Один auto-tester может взять несколько задач одного уровня пирамиды.
  - Задачи разных уровней — в разные worktree'ы (разные стек, разные таймауты).
  - Запиши результат в dispatch-manifest.json и spawn'и worker'ов.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model

hitl: []
```

---

### Участник 6 — `auto-tester`

```yaml
id: auto-tester
type: agent
scope: test
purpose: >
  Пишет автотесты назначенного уровня пирамиды (integration / system / e2e / load)
  в собственном worktree test-репо. Подгружает референсы по тегам.

input:
  - artifact: test/Plan.md
    mode: full_file_with_selector
    selector: assigned_task_ids
  - artifact: tags-registry references
    selector: tags_of_assigned_tasks
  - artifact: test/test-model.md
    mode: read_only
  - artifact: test/test-spec.md
    mode: read_only
  - target: product_repo (read_only)

output:
  - effect: test_code_in_worktree (in test_repo)
  - effect: commits_in_worker_branch

triggers:
  start:
    - spawned_by: team-lead (test mode)
    - return_from: linter-hooks
      condition: status == "fail"
    - return_from: reviewer (test mode)
      condition: status == "needs_changes"
    - return_from: analyzer
      condition: bug_in_test
  complete: |
    Все назначенные задачи покрыты автотестами, локальный прогон зелёный
    (по самооценке auto-tester'а).

skills: []
tools:  [file:read, file:write, file:edit, exec (запуск назначенных тестов локально), git:commit]
context_includes:
  - tag_references_loaded_dynamically

model:
  primary: gigachat
  fallback: deepseek-v4-pro
  fallback_on: [http_5xx]

system_prompt: |
  Ты — автотестировщик. Тебе назначены задачи Plan.md одного уровня пирамиды
  и теги с референсами (как правильно писать тесты на этом уровне в этом проекте).

  ПРАВИЛА:
  - Работай ТОЛЬКО в назначенном worktree.
  - НЕ модифицируй продуктовый код (даже если видишь баг — отметь в комментарии).
  - Используй референсы по тегам как обязательную инструкцию.
  - Покрой acceptance criteria каждой задачи.
  - Прогоняй тесты после написания. Если падают — правь тесты, не код.

  ПРИ ИТЕРАЦИИ:
  - Линтер вернул fail → исправь только указанные ошибки.
  - Reviewer вернул needs_changes → исправь только указанные замечания.
  - Analyzer вернул "bug_in_test" → исправь только указанный тест.

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_iteration_limit_exceeded: escalate_to_human

hitl:
  - escalation_after_5_iterations
```

---

### Участник 7 — `linter-hooks` (test mode)

```yaml
id: linter-hooks
type: hook
scope: test
purpose: >
  Линтеры для тестового репо. Набор тот же, что в Dev_scope, БЕЗ jacoco
  (покрытие тестового кода не измеряем).

input:
  - target: worker_branch_diff_in_test_repo

output:
  - field: exit_code
  - artifact: linter-report.json

triggers:
  start:
    - return_from: auto-tester (commit_made)
  complete: all_linters_finished

runtime: shell
location: .platform/hooks/test/
checks:
  - name: spotless
  - name: spotbugs
  - name: jspecify
  - name: sonar
    gate: builtin

failure_modes:
  - on_blocking_fail: return_to_originating_agent
  - on_linter_tool_crash: escalate_to_human
```

---

### Участник 8 — `reviewer` (test mode)

```yaml
id: reviewer
type: agent
scope: test
mode: test
purpose: >
  LLM ревью diff'а одного auto-tester'а в test-репо. Per-agent гранулярность.

input:
  - artifact: test/Plan.md
    selector: tasks_of_reviewed_worker
  - artifact: test/test-spec.md
    mode: read_only
  - target: worker_branch_diff_in_test_repo
  - artifact: linter-report.json

output:
  - artifact: review-report.json
    action: write

triggers:
  start:
    - return_from: linter-hooks
      condition: status == "ok"
  complete: single_pass

skills: []
tools:  [file:read, git:diff, file:write]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]
  retry_on_invalid_json: 2

system_prompt: |
  Ты — старший reviewer тестов. Изучи diff worker'а в test-репо.

  ОСИ ОЦЕНКИ:
  1. покрытие — соответствует ли тест acceptance criteria назначенных задач?
  2. изоляция — нет ли зависимости от внешних сервисов без моков/контейнеров?
  3. устойчивость — нет ли flaky-конструкций (Thread.sleep без причины, race conditions)?
  4. читаемость и поддерживаемость.
  5. соответствие уровня — действительно ли тест относится к назначенному уровню пирамиды?

  СТАТУС:
  - findings пустой → status = "ok".
  - есть critical или major → status = "needs_changes".

failure_modes:
  - on_5xx: fallback_to_secondary_model
  - on_invalid_json: retry_x2_then_escalate

hitl: []
```

---

### Участник 9 — `manual-merge-gate` (test mode)

```yaml
id: manual-merge-gate
type: gate
scope: test
mode: test
purpose: >
  Финальная проверка человеком перед merge worker-веток в основную ветку test-репо.
  При approve — создаёт git tag, совпадающий с тегом продуктового репо.

input:
  - target: all_worker_branches_in_test_repo
  - artifact: test/dispatch-manifest.json
  - artifact: review-report.json (последний для каждого worker'а)

output:
  - field: decision
    values: [approved, rejected]
  - effect: git_tag_in_test_repo (matching product_repo tag)

triggers:
  start:
    - all_workers_completed (reviewer status == "ok" по каждому)
  complete: user_decision_recorded

ui:
  show:
    - workers_summary
    - cumulative_diff_against_test_repo_base
    - planned_git_tag_name (matching product)
  prompt: "approve merge & tag / reject?"
  on_approve:
    - merge_strategy: sequential
    - merge_worker_branches
    - create_git_tag (matching product_repo)
    - trigger: exec
  on_reject:
    - capture_rejection_target
    - return_to: matching auto-tester

failure_modes:
  - on_merge_conflict: pause_and_request_human_resolution
```

---

### Участник 10 — `exec`

```yaml
id: exec
type: hook
scope: test
purpose: >
  Запуск автотестов на стенде. Запускает (а) только что добавленные тесты
  + (б) все существующие как регресс. На MVP — без selective regression.

input:
  - target: test_repo @ matching_git_tag
  - target: deployed_product_at_stand (URL/credentials)

output:
  - artifact: test/runs/<timestamp>/logs.ndjson
  - artifact: test/runs/<timestamp>/junit.xml (или эквивалент)
  - field: exit_code
    values: [0 = all_pass, "≠ 0 = some_failed"]

triggers:
  start:
    - return_from: manual-merge-gate (test)
      condition: decision == "approved"
    - cli: "ms test run <increment-id>"
  complete: all_levels_finished

runtime: shell
location: .platform/hooks/test/exec.sh

execution_order:                 # поэтапно с fail-fast по умолчанию
  - integration
  - system
  - e2e_ui
  - load
fail_fast: true                  # при падении уровня — следующие уровни не запускаем (экономия)
intra_level_parallelism: true    # внутри уровня тесты идут параллельно (стандартное поведение JUnit/TestNG/JMeter)

failure_modes:
  - on_environment_unavailable: pause_and_alert_human   # стенд недоступен ≠ баг в тесте/коде
  - on_test_runner_crash: escalate_to_human
```

---

### Участник 11 — `analyzer`

```yaml
id: analyzer
type: agent
scope: test
purpose: >
  Анализирует логи прогона. Решает: баг в тесте или расхождение со спекой
  (т.е. баг в продукте). В первом случае возвращает auto-tester'у; во втором
  оформляет bug.md и роутит в Dev_scope (полный цикл от sys-analyst).

input:
  - artifact: test/runs/<timestamp>/logs.ndjson
  - artifact: test/runs/<timestamp>/junit.xml
  - artifact: analytic/increment.md (read-only)
  - artifact: test/test-spec.md (read-only)
  - artifact: test/Plan.md (read-only)
  - target: product_repo (read_only, для соотнесения)
  - target: test_repo    (read_only, для соотнесения)

output:
  - artifact: test/runs/<timestamp>/analyzer-report.json
    schema: |
      {
        "verdicts": [
          {
            "failed_test_id": "<id>",
            "verdict": "bug_in_test" | "bug_in_product",
            "confidence": "high|medium|low",
            "rationale": "<кратко>",
            "evidence_refs": [{"file": "...", "lines": "..."}]
          }
        ]
      }
  - artifact: test/runs/<timestamp>/bug.md      # ТОЛЬКО если есть хотя бы один "bug_in_product"
    schema_hint: |
      # BUG
      ## Сводка
      ## Воспроизведение (по логам)
      ## Что нарушено в increment.md (ссылка на пункт)
      ## Затронутые тесты
      ## Предлагаемая зона исправления (модули, эндпоинты)

triggers:
  start:
    - return_from: exec
      condition: exit_code ≠ 0
    - cli: "ms test analyze <run-id>"
  complete: single_pass

skills: [analytic, explorer]
tools:  [file:read, file:write, git:diff (history), code-indexer, lsp]

model:
  primary: deepseek-v4-pro
  fallback: gigachat
  fallback_on: [http_5xx]

system_prompt: |
  Ты — старший QA-инженер. Тебе переданы логи прогона тестов, описание тестов
  и спецификация продукта. Дополнительно — история падений в текущем инкременте
  (сколько раз каждый test_id уже разбирался как bug_in_test).

  ДЛЯ КАЖДОГО упавшего теста реши:
  - bug_in_test — ошибка в самом тесте (плохая сборка моков, неверные ожидания,
    flakiness). Никакого расхождения со спекой нет.
  - bug_in_product — продукт нарушает требования из increment.md. Тест корректен.

  АГРЕГАЦИЯ:
  - Если хотя бы один verdict == "bug_in_product" — оформи ОДИН сводный bug.md
    за весь прогон, с разделом "Затронутые тесты" и общим блоком "Что нарушено".
  - Если все verdicts == "bug_in_test" — bug.md НЕ создавать.

  ПРАВИЛА УВЕРЕННОСТИ И ИСТОРИИ:
  - Низкая уверенность (confidence = "low") → склоняйся к "bug_in_test".
  - ЕСЛИ у того же test_id уже было ≥ 2 повторных bug_in_test в этом инкременте
    и тест снова падает по той же причине — переключи verdict на bug_in_product
    (порог повышения уверенности).

failure_modes:
  - retry: standard
  - on_5xx: fallback_to_secondary_model
  - on_low_confidence_default: prefer "bug_in_test" verdict
  - on_stop_loss: 5 циклов bug_in_test подряд по одному test_id → эскалация человеку (clarify / edit Plan.md / replan)

routing:
  - on bug_in_test:
      - route_to: auto-tester (matching test)
      - action: rerun_iteration (в рамках лимита 5)
  - on bug_in_product:
      - route_to: Dev_scope.system-analyst
      - input_artifact: test/runs/<timestamp>/bug.md
      - trigger: full_cycle_dev_scope

hitl: []
```

---

### Решения по Test_scope (зафиксированы по ходу)

| Тема | Решение |
|---|---|
| `func_req.md` vs `increment.md` | Один и тот же файл — оставляем `increment.md`. |
| Уровни покрытия | Test_scope покрывает integration / system / e2e_ui / load. Unit'ы — в Dev_scope. |
| Расположение тест-кода | Отдельный test-репозиторий. |
| Расположение тест-артефактов (md) | В **репо продукта**, в ветке инкремента, в директории `test/`. |
| Версионирование тест-репо | Через **git-теги**, совпадающие с тегами продукта; тег создаётся `manual-merge-gate (test)`. |
| Триггер | Автоматически после Dev approve + build; и вручную через CLI. |
| Регрессы | На MVP — все существующие + новые. Selective regression — отложено. |
| Линтеры в тест-репо | Spotless / Spotbugs / JSpecify / Sonar (BUILTIN gate). БЕЗ jacoco. |
| Гранулярность reviewer | per-agent. |
| Анализ падений | `analyzer` (LLM) выносит verdict `bug_in_test` или `bug_in_product` с confidence. |
| BUG-маршрут | `bug.md` → полный цикл Dev_scope, начиная с `system-analyst`. |
| Поведение `exec` | Поэтапно по пирамиде с **fail-fast**. |
| Лимит worker'ов | 5 (как в Dev_scope). |
| Поведение analyzer'а при низкой уверенности | По умолчанию `bug_in_test` (более лёгкий путь правки), чтобы не запускать дорогой полный цикл Dev_scope зря. |

---

### Закрытые вопросы по Test_scope

| Вопрос | Решение |
|---|---|
| Q1. Testcontainers в локальном прогоне | `auto-tester` поднимает их сам через `@Testcontainers`; load/e2e локально не гонит — только в `exec` на стенде. |
| Q2. Авто-трigger Test_scope после Dev approve | Не auto, а **cued**: после Dev approve система проставляет `ready_for_test` и подсказывает в CLI запустить `ms test new <id>` после деплоя. |
| Q3. Порог «повышения уверенности» analyzer'а | После **2 повторных `bug_in_test`** по одному `test_id` — следующий verdict переключается на `bug_in_product`. |
| Q4. Один `bug.md` или несколько за прогон | **Один сводный** `bug.md`, с разделом «Затронутые тесты» внутри. |
| Q5. Параллельность `exec` | Уровни — последовательно с fail-fast; внутри уровня — параллельно (стандартное поведение JUnit/TestNG/JMeter). |
| Q6. Stop-loss по analyzer'у | 5 циклов `bug_in_test` подряд по одному `test_id` → эскалация человеку (clarify / edit Plan.md / replan). |
