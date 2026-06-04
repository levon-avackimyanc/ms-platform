# Мультиагентная система оркестрации Analytic → Dev → Test

**Статус:** черновик архитектурного предложения для обсуждения с командой.
**Дата:** 2026-06-03.

> Документ собран на основе whiteboard-схем трёх scope-ов (Analytic / Dev / Test) и серии уточняющих вопросов. Содержит зафиксированные требования и предлагаемую архитектуру.

---

## Часть I. Сводка требований

### 1. Что строим
**Внутренний CLI-инструмент** для одной команды (с прицелом на масштабирование на другие команды), **сознательный аналог Claude Code для корпоративного контура**. Оркестрирует сквозной цикл *бизнес-аналитика → разработка → тестирование* силами специализированных ИИ-агентов с человеческими gate'ами в ключевых точках.

### 2. Что система производит
**Готовый Docker-образ продукта**, пригодный к ручному деплою на тестовый стенд. После деплоя — автоматический прогон автотестов из Test_scope.

### 3. Целевые проекты (которые система обрабатывает)
Набор **микросервисов Java + Spring**, ~десятки тысяч строк каждый, **greenfield**-разработка инкрементов.

### 4. Среда работы
Корпоративный контур, локальная установка. **CLI-first**. Внешние LLM доступны через **корп-прокси**.

### 5. Парадигма
Сознательная отсылка к Claude Code: **skills, hooks, агенты-делегаты, worktree-изоляция, артефакты в git, HITL-gate'ы**.

### 6. Три scope-а

#### Analytic_scope (бизнес-аналитика)
Чат-интервью с заказчиком → `increment.md` (ФТ/НФТ, business-flow, сценарии) → Py-валидатор (hook1) → LLM review (hook2) → **ручной gate валидации** → передача в Dev_scope.
- Loop при fail: правит сам агент-аналитик; к заказчику обращается только если нужны уточнения.

#### Dev_scope (разработка)
`increment.md` → системный аналитик-агент пишет `tech_spec.md` → explorer изучает кодбазу → Plan-агент (со скиллом `stackDefinition`) формирует `Plan.md` с разметкой тегами из tags registry → **team_lead** порождает N dev-агентов (и tester'ов для unit'ов) и завершается → dev-агенты работают **параллельно в собственных git worktree**, подгружая референсы по тегам → линтеры-хуки (Spotless, Spotbugs, JSpecify, jacoco, Sonar — все ошибки блокируют) → **per-agent reviewer (LLM по diff)** → build Docker-образа → **HITL merge-gate** → деплой вручную.
- Лимит итераций по линтерам/reviewer'у — **5**, потом эскалация человеку.

#### Test_scope (тестирование, зеркало Dev_scope)
Тот же `increment.md` → системный аналитик → explorer → структурированный список тест-сценариев → Plan → team_lead → N агентов-автотестировщиков (всё кроме unit) → reviewer → автотесты в **отдельном репо** → запуск (Exec) → **Analyzer (LLM)** анализирует падения:
- Если баг в автотесте — правит тот же агент-автотестировщик.
- Если автотест валиден, есть расхождение со спекой — **BUG** уходит **системному аналитику Dev_scope**, запускается **полный цикл Dev_scope**.

Триггер: автоматически после merge в Dev + вручную. Режим — инкрементальный (новые + регрессы).

### 7. Tags registry
Курируемый **человеком** md-файл с таблицей (3 поля: `имя | описание | ссылка на референс`). Референсы — отдельные md-файлы в директории проекта. Используется и dev-агентами, и автотестировщиками (теги для уровней пирамиды). На старте — без графа связей.

### 8. Кросс-функциональные решения
- **Dev_scope реализуется поверх Claude Code** (CLI от Anthropic) с конфигурацией из репозитория коллеги [`a-simeshin/claude-code-hooks-mastery`](https://github.com/a-simeshin/claude-code-hooks-mastery) (форк disler). Наша CLI `ms` вызывает `claude` как backend, передавая `increment.md` и контекст. Лицензия и согласие коллеги подтверждены.
- **LLM:**
  - Analytic_scope, Test_scope: DeepSeek v4 Pro (планировщик, $$$), GigaChat (исполнители).
  - Dev_scope: **Claude (через корп-прокси к Anthropic)** — натуральный для Claude Code.
- **Retry / fallback:** на 5xx от прокси — fallback на другую модель. Остальные ошибки (timeout, rate-limit, parse fail) — обычный retry; политику детализируем позже.
- **Observability — собственный упрощённый трейсинг.** Готовую корп-платформу не используем.
- **Версионирование:** 1 инкремент = 1 ветка/коммит. Автотесты в отдельном репо линкуются с версией продукта **через git-теги** (одинаковый тег на оба репо).
- **БД метаданных** — не нужна.
- **Бюджет / SLA** — отложено.

---

## Часть II. Архитектурное предложение

### A. Высокоуровневая схема

```
┌─────────────────────────────────────────────────────────────────┐
│            CLI (Spring Shell) — корневая команда: ms            │
│  ms init / ms analyze / ms dev / ms test / ms trace / ms status │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────────┐
│                  Orchestrator Core (Spring Boot)                │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Flow Engine (Spring StateMachine или DSL)              │    │
│  │  • Analytic Flow                                        │    │
│  │  • Dev Flow                                             │    │
│  │  • Test Flow                                            │    │
│  └─────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Agent Runtime                                          │    │
│  │  • Agent loader (читает .platform/agents/*.yaml)        │    │
│  │  • Skill loader (читает .platform/skills/*.md)          │    │
│  │  • Hook executor (детерминированные .py/.sh)            │    │
│  │  • LLM Gateway (Spring AI) ── retry/fallback ──         │    │
│  └─────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Workspace Manager                                      │    │
│  │  • JGit: worktree, branch, diff, merge                  │    │
│  │  • File I/O: increment.md / tech_spec.md / Plan.md      │    │
│  └─────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Tracing (собственный упрощённый)                       │    │
│  │  • span на каждый вызов агента / hook / LLM             │    │
│  │  • запись в локальные json/ndjson-файлы                 │    │
│  │  • CLI-команда `ms trace <run-id>` для просмотра        │    │
│  └─────────────────────────────────────────────────────────┘    │
└────────────────────┬────────────────────┬───────────────────────┘
                     │                    │
            ┌────────▼────────┐  ┌────────▼─────────┐
            │  LLM Proxy      │  │  Tools / Skills  │
            │  • DeepSeek v4  │  │  • Serena        │
            │  • GigaChat     │  │  • Context7      │
            │  • (etc)        │  │  • LSP           │
            └─────────────────┘  │  • CodeIndexer   │
                                 │  • linters       │
                                 │  • py-validator  │
                                 └──────────────────┘
```

### B. Структура конфигурации (по образцу `.claude/`)

В корне репо целевого продукта (или в `~/.platform/`):

```
.platform/
├── agents/                 # роли (по одному файлу на агента)
│   ├── business-analyst.yaml
│   ├── system-analyst.yaml
│   ├── explorer.yaml
│   ├── planner.yaml
│   ├── team-lead.yaml
│   ├── dev.yaml
│   ├── tester.yaml
│   ├── reviewer.yaml
│   ├── auto-tester.yaml
│   └── analyzer.yaml
├── skills/                 # переиспользуемые скиллы
│   ├── analytic.md
│   ├── explorer.md
│   └── stack-definition.md
├── hooks/                  # детерминированные точки расширения
│   ├── analytic/
│   │   └── md-validator.py        # hook1 в Analytic
│   ├── dev/
│   │   ├── spotless.sh
│   │   ├── spotbugs.sh
│   │   ├── jspecify.sh
│   │   └── sonar.sh
│   └── test/
│       └── result-analyzer.py
├── flows/                  # описания потоков (yaml/dsl)
│   ├── analytic.flow.yaml
│   ├── dev.flow.yaml
│   └── test.flow.yaml
├── tags-registry.md        # курируемая таблица тегов
└── refs/                   # референсы на теги
    ├── mcp.md
    ├── rag.md
    ├── liquibase.md
    ├── autotest-integration.md
    ├── autotest-e2e.md
    └── ...
```

### C. Карта ролей агентов

| Агент | Скиллы | Модель | Где живёт |
|---|---|---|---|
| **business-analyst** | analytic | GigaChat | Analytic_scope |
| **system-analyst** | analytic, explorer | DeepSeek (нужна точность) | Dev_scope + Test_scope |
| **explorer** | explorer (+ tools: Serena/Context7/CodeIndexer/LSP) | DeepSeek | Dev_scope + Test_scope |
| **planner** | stack-definition (+ tools), reads tags-registry | DeepSeek ($$$) | Dev_scope + Test_scope |
| **team-lead** | оркестрация без LLM-творчества | GigaChat (детерминированный шаблон) | Dev_scope + Test_scope |
| **dev** | подгружает референсы по тегам | GigaChat | Dev_scope |
| **tester** | референсы по unit-тестам | GigaChat | Dev_scope |
| **reviewer** | code-review по diff | DeepSeek | Dev_scope |
| **auto-tester** | референсы по integ/sys/E2E/load | GigaChat | Test_scope |
| **analyzer** | анализ логов прогонов | DeepSeek | Test_scope |

### D. E2E поток данных

```
[заказчик-человек]
       │ CLI chat-interview
       ▼
[business-analyst] ──► increment.md (git, ветка инкремента)
       │ ↻ Py-валидатор / LLM-review (loop until pass)
       │ ↻ HITL: "valid? ручная"
       ▼
[system-analyst]   ──► tech_spec.md
       │
[explorer (Dev)]   ──► (контекст по кодбазе)
       │
[planner]          ──► Plan.md  (задачи + теги из tags-registry)
       │
[team-lead]        ──► создаёт worktree'ы и запускает агентов:
       │                  ┌──► [dev #1] ──hook(линтеры)──► [reviewer]──┐
       │                  ├──► [dev #2] ──hook(линтеры)──► [reviewer]──┤
       │                  └──► [tester]                                │
       │                          (loop ≤ 5 итераций per agent)        │
       ▼                                                               ▼
HITL: merge-gate ◄────────────── merge worktree'ев ─────────────────────┘
       │
       ▼
[build] ──► Docker-образ
       │
       ▼  (manual deploy)
=======================================================================
       ▼ (после деплоя)
[system-analyst] ──► tech_spec(test).md
       │
[explorer (Test)]
       │
[planner]          ──► Plan.md (test cases + теги уровней пирамиды)
       │
[team-lead]        ──► [auto-tester #1..N], [reviewer]
       │
Exec → Analyzer
       │
       ├─ ошибка в тесте → правит автотестировщик
       └─ расхождение со спекой → BUG → возврат к [system-analyst] Dev_scope (полный цикл)
```

### E. Ключевые межсистемные интерфейсы

- **Артефакты как контракты** — `increment.md`, `tech_spec.md`, `Plan.md`, tags-registry — единственная «шина» между scope-ами. Никакой in-memory передачи через объекты — всё через git-файлы.
- **LLM Gateway** — единая точка обращения к моделям; здесь живут retry/fallback (на 5xx прокси → fallback на другую модель), выбор модели по роли агента, эмиссия трейсов в локальный трейсинг.
- **Workspace Manager** — единственный, кто пишет в git. Агенты получают «снапшот workspace» и возвращают diff/changeset; коммит и merge — централизованы.

### F. Что точно требует прототипирования (риски)

1. **Spring AI ↔ GigaChat-интеграция** — community-адаптер может потребовать доработки. Проверить раньше всего.
2. **worktree per agent + per-agent reviewer** на JGit — самая горячая зона по сложности. Нужен спайк на 1 инкременте с 2–3 параллельными dev'ами.
3. **DSL потоков (Analytic/Dev/Test).** Spring StateMachine vs самописное — выбрать после спайка.
4. **HITL-gate в CLI** — UX чат-интервью и финальные gate'ы (как `ExitPlanMode` в Claude Code). Может потребовать богатого Spring Shell.

### G. Anti-scope (что НЕ делаем на старте)

- Многопользовательский режим.
- Веб-UI и бот.
- Граф связей тегов (плоская таблица сначала).
- Локальная БД метаданных.
- Hot-fix flow при BUG (только полный цикл).
- Тонкая оптимизация бюджета.

### H. Решения по ранее открытым вопросам

1. **Версионирование автотестов ↔ версий продукта** — через **git-теги** (одинаковый тег на оба репо).
2. **Граница `system-analyst` vs `explorer`:**
   - `system-analyst` — читает `increment.md` и пишет **чёткое ТЗ** (`tech_spec.md`) с API-спецификациями и техническими деталями.
   - `explorer` — изучает существующий проект, чтобы потом **планировщик мог пометить тегами** задачи в Plan.md.
   - Эти артефакты дополняют друг друга, не пересекаются.
3. **Эскалация после 5 итераций — человек может:**
   - дописать уточнение для агента и перезапустить;
   - изменить `Plan.md` (отменить задачу, разбить её);
   - вернуть процесс на этап планирования.
4. **Observability — собственный упрощённый трейсинг** (от корп-наблюдаемости отказались). Локальные json/ndjson-файлы со span'ами; команда `ms trace <run-id>` для просмотра.
5. **Retry / fallback политика на старте:** при **5xx от прокси** — fallback на другую модель. Остальные классы ошибок (rate-limit, timeout, parse-fail) — детализируем позже по факту инцидентов.
