# План внедрения MVP-0

**Статус:** v2, переписан 2026-06-11 после pivot на Claude Code backend.
**Парные документы:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md), [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md), [`PIVOT.md`](./PIVOT.md).

> Старый roadmap описывал 2-недельный спринт под Java/Spring + 3 workstream'а (A/B/C — платформа/Gateway/domain). Он устарел. Объём работы сильно сократился — upstream-конфиг `.claude/` закрывает ~80% Dev_scope.

---

## 1. Контекст и допущения

| Параметр | Значение |
|---|---|
| Срок MVP-0 | **2 недели (10 рабочих дней)** |
| Команда | **1–2 человека** (объём сжался после pivot'а) |
| Цель | **Реально используемый инструмент** — к концу 2-й недели бизнес-аналитик команды реально пишет осмысленный `increment.md` через `/analyze`, далее проходит полный цикл Dev + Test на toy-сервисе |
| Целевой проект | **Учебный «toy»-микросервис на Spring Boot**, специально сделанный под MVP |
| Среда | **Claude Code CLI локально** + `uv` (для Python-хуков) |
| LLM-доступ | **Claude через корп-прокси к Anthropic API** — должен быть готов к старту (см. риски) |
| Что мы собираем | Не приложение, а **конфигурацию `.claude/`** для Claude Code. Никакого pom.xml / src/ / собственного CLI. |
| Состояние upstream | Подтянуто в репо `ms-platform`. Готов к использованию as-is. |

---

## 2. Toy-микросервис

Простой Spring Boot сервис под греенфилд-инкременты. Нужен для прогона полного цикла.

Минимум:
- 2–3 REST endpoint'а (CRUD над одной сущностью + поиск),
- Spring Data JPA + H2 (или Testcontainers Postgres),
- структура `api / service / repo / model`,
- стартовый набор unit-тестов (~60% coverage), краткий README.

**Кейс инкремента для MVP-0:** «добавить новый endpoint + новое поле к сущности + бизнес-валидацию».

Где живёт: **отдельный git-репо** (не внутри `ms-platform`), имя — на усмотрение пользователя (например, `ms-platform-toy`). `.claude/` ставится туда через `install.sh` нашего репозитория.

---

## 3. Состав MVP-0

| Компонент | Что входит | Что отложено |
|---|---|---|
| **Analytic_scope** (новый, наш) | `business-analyst.md`, `analytic-reviewer.md`, `commands/analyze.md`, `hooks/validators/validate_increment.py` | — (всё необходимое в MVP-0) |
| **Dev_scope** (upstream as-is + наш HITL) | `/dev_plan`, builder, plan-reviewer, validator, линтеры — БЕЗ ИЗМЕНЕНИЙ. Добавляем `commands/merge_gate.md`. | worktree-параллелизм, новые доменные refs (только если нужно) |
| **Test_scope (lite)** | `commands/test_run.md`, `agents/analyzer.md` | system / e2e UI / load тесты, selective regression, история падений analyzer'а с verdict-flip |
| **Refs** | upstream as-is (`java-patterns.md`, `java-testing.md`) | доменные refs (RAG/MCP/Liquibase) — только при явной необходимости |
| **HITL gate'ы** | (a) approve `increment.md` в чате с заказчиком; (b) ExitPlanMode после `/dev_plan`; (c) `merge_gate` перед коммитом | богатый diff-view, multi-line комментарии |
| **Observability** | встроенный Claude Code trace; Stop-hook'и в логах | свой трейсинг / метрики / дашборды |

---

## 4. Расписание по дням

Распределение работ — между 2 разработчиками. Если работает один — последовательно, общий срок может вырасти до 12–14 дней.

### Неделя 1 — Analytic_scope + первое прохождение в Dev

#### Day 1 — Setup
- **A** (или единственный исполнитель): убедиться что Claude Code установлен; пройти upstream `install.sh` локально; запустить `/dev_plan` без аргументов на любом toy-проекте — должен открыться шаблон. Это smoke `.claude/` интеграции.
- **B** (если есть): создать toy-микросервис в отдельном репо (CRUD + Spring Data + tests). Подключить к нему `.claude/` через `install.sh`.

**Milestone Day 1:** Claude Code в репозитории toy-проекта запускает `/dev_plan` без ошибок.

#### Day 2 — `validate_increment.py`
- Написать Python-скрипт `validate_increment.py` (4 проверки из AGENTS_SPECIFICATION п. 4.1).
- Тесты к нему — pytest по тестовым `increment.md` (валидный + 4 невалидных кейса под каждый чек).
- Не зацеплять пока в Claude Code Stop-hook; запускать вручную для отладки.

**Milestone Day 2:** `validate_increment.py` запускается, возвращает 0 / ≠ 0 + JSON-отчёт на тестовых вводных.

#### Day 3 — `business-analyst` агент
- Положить `agents/business-analyst.md` из чернового промпта (AGENTS_SPECIFICATION п. 2.1).
- Прогнать вручную: вызвать агента, сэмулировать заказчика; убедиться что записывает `analytic/increment.md` корректной структуры.
- Тонкая полировка промпта по результатам.

**Milestone Day 3:** в любом репо `business-analyst` пишет валидный `increment.md` (его прохождение `validate_increment.py` зелёное).

#### Day 4 — `analytic-reviewer` + `commands/analyze.md`
- Положить `agents/analytic-reviewer.md` + `commands/analyze.md` (с подключением `validate_increment.py` как Stop-hook).
- Прогнать `/analyze "<задача>"` end-to-end: chat-interview → write → validate → review → HITL approve.
- На намеренно противоречивой задаче — проверить что `analytic-reviewer` ловит и возвращает `needs_revision`.

**Milestone Day 4:** `/analyze` end-to-end работает: validate → review → approve.

#### Day 5 — Переход в Dev (упор на готовое из upstream)
- Никакого кода: на approved `increment.md` запустить `/dev_plan` (готовая команда из upstream).
- Пройти Test Infra Interview, дать plan-reviewer'у сделать критику, выбрать ExitPlanMode (HITL approve плана).
- Запустить builder; параллельно работают линтеры через PostToolUse hook.
- Дойти до момента «`validator` вынес PASS».

**Milestone недели 1:** `/analyze` → `/dev_plan` → builder → validator(PASS) на toy-микросервисе. Code+tests лежат в ветке `increment/<id>`.

### Неделя 2 — `merge_gate`, Test_scope (lite), полный цикл и демо

#### Day 6 — `commands/merge_gate.md`
- Реализовать `merge_gate` как команду (без своих hook'ов).
- Сценарии: approve → commit + tag; reject → сохранить `rejection_comment.txt` + сообщить о возможности перезапустить `/dev_plan`.

**Milestone Day 6:** `merge_gate` коммитит инкремент в основную ветку toy-репо.

#### Day 7 — `analyzer.md` агент
- Положить `agents/analyzer.md` (черновой промпт).
- Подготовить тестовые `test/runs/<ts>/` фикстуры (logs + junit) — намеренно два сценария: «баг в тесте» и «баг в продукте» (где продукт нарушает `increment.md`).
- Прогнать `analyzer` вручную; убедиться что verdict'ы корректные и `bug.md` пишется только во втором случае.

**Milestone Day 7:** `analyzer` правильно различает `bug_in_test` и `bug_in_product` на двух фикстурах.

#### Day 8 — `commands/test_run.md`
- Реализовать `test_run` как команду.
- На уже инкрементированном toy-сервисе запустить `/test_run` — он должен поднять declared runner из плана, при зелёных тестах сообщить успех, при намеренном падении (пользователь руками сломает один тест) — позвать analyzer.

**Milestone Day 8:** `/test_run` запускает реальный `mvn verify` и при провале запускает analyzer.

#### Day 9 — End-to-end + полировка
- **Реальный сквозной прогон**: бизнес-аналитик-человек (можно сам разработчик в этой роли) проходит `/analyze` → ExitPlanMode → `/merge_gate` → ручной деплой → `/test_run`.
- Намеренно сломать продукт (изменить логику валидации в одном из endpoint'ов) — убедиться, что `/test_run` → `analyzer` → `bug.md` → новый `/dev_plan` с этим `bug.md` как контекстом → builder фиксит.
- Полировка промптов агентов по результатам.

**Milestone Day 9:** полный bug-routing loop работает.

#### Day 10 — Демо + документация
- Записать READMEsection «как запустить»: установка Claude Code, `install.sh`, последовательность команд.
- Обновить `AGENTS_SPECIFICATION.md` по факту реализации (если черновики промптов сильно изменились).
- Прогнать демо-сценарий для коллег.

**Milestone Week 2 = MVP-0:** один сценарий полного цикла Analytic → Dev → Test → BUG → Dev на toy-микросервисе работает end-to-end силами реального бизнес-аналитика команды.

---

## 5. Definition of Done — MVP-0

Конкретные бинарные критерии:

- [ ] `install.sh` устанавливает `.claude/` в toy-репо без ошибок.
- [ ] `/analyze "<задача>"` запускает chat-interview, записывает `analytic/increment.md` валидной структуры, проходит `validate_increment.py` зелёным и `analytic-reviewer` с verdict `ok`, после approve — сообщает о готовности к `/dev_plan`.
- [ ] `/dev_plan` (upstream) принимает контекст из `increment.md`, проходит `plan-reviewer`, проходит ExitPlanMode approve, далее builder + validator завершаются PASS на toy.
- [ ] `/merge_gate` коммитит инкремент.
- [ ] `/test_run` запускает declared runner; на намеренной поломке продукта вызывает `analyzer`, который пишет `bug.md`.
- [ ] `/dev_plan` повторно запущенный с `bug.md` как контекстом доводит фикс до PASS.
- [ ] Реальный сотрудник (не разработчик из команды MVP) использует `/analyze` хотя бы для одного реального бизнес-кейса и получает осмысленный `increment.md` без правок руками.
- [ ] `README.md` содержит секцию «Запуск с нуля» с проверенными командами.

---

## 6. Anti-scope (что НЕ делаем в MVP-0)

Чтобы не было ползучего расширения:

- Параллельный запуск нескольких builder'ов в worktree.
- Уровни тестов выше integration (system / e2e / load).
- Selective regression в `/test_run` — всегда полный прогон.
- История падений analyzer'а с verdict-flip и stop-loss.
- Богатый UX HITL gate'ов (diff view, multi-line comments) — обычные текстовые ответы.
- Бюджет / SLA / cost trace.
- Свой LLM Gateway / CLI / сервис / БД (см. PIVOT.md).
- Корп-доменные refs «про запас» — добавляем только при появлении явной необходимости.
- Многопользовательский / параллельные инкременты.
- Свой mechanism для приёма сообщений из мессенджеров.

---

## 7. Технические риски и митигации

| Риск | Вероятность | Митигация |
|---|---|---|
| Claude Code не работает через корп-прокси к Anthropic | низкая (если уже работает у коллег) | Day 1 — спайк: убедиться что прокси настроен. Если нет — блокер; ждать платформенную команду. |
| `/analyze` chat-interview сваливается из-за многошаговой природы | средняя | Day 2–3 — отдельный спайк. Если не получается одним агентом — разбить на subagent calls внутри одной команды. |
| `validate_increment.py` слишком жёстко — отвергает осмысленные интервью | средняя | Начинаем с минимальных проверок; затем по факту добавляем строгости. |
| `analyzer` контекстное окно не вмещает (продукт + тесты + логи + спека) | средняя | Использовать Serena MCP для фокусной навигации, не загружать всё в контекст. |
| Toy-микросервис слишком тривиален — pipeline не показывает ценности | средняя | Сразу делать close-to-real: JPA + валидации + layered architecture + базовые тесты. |
| Конфликт между `.claude/` upstream-а и нашими новыми файлами | низкая | Наши файлы — отдельные имена (`business-analyst.md` ≠ `team/builder.md`); конфликтов не должно быть. |

---

## 8. После MVP-0 — естественные следующие итерации

**MVP-1 (~2–3 недели после MVP-0): полнота функциональности**
- Уровни тестирования system + e2e.
- Selective regression в `/test_run` (только тесты, релевантные изменённым файлам).
- История падений `analyzer` + verdict-flip после ≥ 2 повторов `bug_in_test`.
- Доменные refs первой волны (RAG-конвенции, MCP-конвенции, корп-Spring-стиль).
- Реальный (не toy) микросервис.

**MVP-2 (~1 месяц после MVP-1): масштабирование**
- Worktree-параллелизм (если станет узким местом).
- Параллельные инкременты (несколько веток в работе).
- Дашборд состояний пайплайнов.

**MVP-3 (по запросу платформенной команды):**
- Адаптер под корп-LLM-прокси (GigaChat / DeepSeek) — если решат, что Anthropic-only недопустимо в проде.

---

## 9. Открытые вопросы по плану

1. **Кто исполняет.** В команде 1 или 2 человека работают над MVP-0? Срок зависит.
2. **Toy-микросервис.** Создаём с нуля под MVP, или есть подходящий учебный сервис в команде, который можно адаптировать?
3. **Корп-прокси к Anthropic.** Готов ли уже? Если нет — сначала фиксим этот блокер, иначе MVP не запустится.
4. **Реальный бизнес-аналитик для финального DoD.** Кто из команды готов взять `/analyze` для своего реального инкремента? Желательно понять имя заранее — это влияет на формулировки `business-analyst.md`.
5. **Test-репо или общий.** Автотесты живут вместе с продуктом (минимальный путь) или в отдельном репо (как в старом дизайне)? Рекомендация: вместе с продуктом для MVP-0; отдельный — после.
