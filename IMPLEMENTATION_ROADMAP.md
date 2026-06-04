# План внедрения MVP-0

**Статус:** черновик. План на первый рабочий контур системы (MVP-0).
**Дата:** 2026-06-04.
**Парные документы:** [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md), [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md).

---

## 1. Контекст и допущения

| Параметр | Значение |
|---|---|
| Срок MVP-0 | **2 недели (10 рабочих дней)** |
| Команда | **2–3 человека** из команды пользователя |
| Цель | **Реально используемый инструмент** — к концу 2-й недели бизнес-аналитик команды реально пишет осмысленный `increment.md` через `ms analyze`, далее проходит полный цикл Dev + Test |
| Целевой проект | **Учебный «toy»-микросервис на Spring Boot**, специально сделанный под MVP |
| LLM-доступ | DeepSeek + GigaChat **+ Claude через корп-прокси** — всё готово к старту |
| Стек платформы | Java + Spring Boot + Spring Shell + Spring AI + JGit |
| **Реализация Dev_scope** | **Claude Code** (CLI от Anthropic) + конфиг репо `a-simeshin/claude-code-hooks-mastery`. Лицензия и согласие подтверждены. |

---

## 2. Учебный toy-микросервис

Простой Spring Boot сервис, который имеет:
- 2–3 REST endpoint'а (CRUD над одной сущностью + поиск).
- Persistence-слой через Spring Data JPA + H2 in-memory (или PostgreSQL Testcontainers).
- Базовая структура модулей (api / service / repo / model).
- Уже существующий `README.md` и небольшой набор unit-тестов (≥ 60% coverage), чтобы выглядеть «нормально», а не «пустой шаблон».

**Бизнес-инкремент-кейс MVP-0**: «добавить новый endpoint + новое поле к сущности + бизнес-валидацию».

Создаётся в day 1 одним из разработчиков параллельно с инфра-задачами. **Это НЕ платформа, это объект, на котором мы её гоняем.**

---

## 3. Состав MVP-0

В MVP-0 входит **полный цикл** (Analytic → Dev → Test), но с упрощениями:

| Компонент | Что входит в MVP-0 | Что отложено |
|---|---|---|
| CLI `ms` | init / analyze / dev / test / status / trace (минимум) | Богатый UX, autocomplete, темы |
| Analytic_scope | business-analyst, md-validator, analytic-reviewer, manual-validation-gate | Сложная проверка GWT-сценариев — упрощённая |
| Dev_scope | Полностью через Claude Code + конфиг репо коллеги | Доработка JSpecify/Sonar — не входит, останавливаемся на наборе репо (Spotless / PMD / JaCoCo / Maven) |
| Test_scope | **Тоже через Claude Code** (новая custom-команда для написания integration-тестов). Только уровень integration. Exec — упрощённый запуск через Maven. Analyzer — LLM | Уровни system / e2e / load — после MVP-0. Selective regression — после. История падений для verdict-flip — после. |
| Tags-registry | Берём готовый `refs/` из репо коллеги (java-patterns + java-testing) | Свои теги добавляем по мере надобности |
| Параллельность worktree | НЕТ (Claude Code сам по себе sequential в MVP) | Worktree-параллелизм — после MVP-0 (соответствует тому, как работает Claude Code) |
| HITL gate'ы | Analytic gate + Dev merge gate — упрощённые подтверждения в CLI | Богатый diff-view, comments — после |
| Observability | Локальный JSON-трейсинг (span на агента/hook/LLM) + `ms trace <run-id>` | Метрики и дашборды — после |

---

## 4. Распределение работ

**Три workstream'а** (по одному на разработчика, если 3 человека; при 2 — A объединяется с B):

| Поток | Содержание | Кто |
|---|---|---|
| **A. Платформа (CLI/State/Trace)** | Spring Boot + Spring Shell skeleton, state-файл инкремента, локальный трейсинг, CLI команды | dev A |
| **B. LLM Gateway + Workspace** | Spring AI к DeepSeek / GigaChat / Claude (через прокси), retry/fallback, JGit (branch / commit / status / log) | dev B |
| **C. Domain (Scopes & Toy-сервис)** | Toy-микросервис, агенты Analytic_scope (промпты, hooks), адаптеры Dev/Test через Claude Code | dev C |

При двух разработчиках — A+B сливаются в один workstream «Платформа», C остаётся.

---

## 5. Расписание по дням

### Неделя 1 — фундамент + Analytic_scope

#### Day 1 — Setup
- **A**: создание Spring Boot проекта, Spring Shell, базовая структура пакетов, `ms --version` запускается.
- **B**: получение и проверка корп-прокси для DeepSeek / GigaChat / Claude (curl-пробники до каждой модели). Без интеграции с приложением — только проверка доступности.
- **C**: создание toy-микросервиса (репо, basic CRUD endpoint, H2, unit-тесты, README).

**Milestone day 1:** скелет CLI + подтверждённый доступ ко всем LLM + toy-микросервис.

#### Day 2 — LLM Gateway + State
- **A**: state-файл инкремента (json) — текущая фаза, лимиты итераций, артефакты; команды `ms init <task>` (создаёт ветку и `original_task.txt`) и `ms status`.
- **B**: LLM Gateway — Spring AI ChatClient'ы для DeepSeek и GigaChat; первичный retry/fallback (на 5xx).
- **C**: каркас агентов Analytic_scope — описания, директория `.platform/agents/`, `.platform/skills/analytic.md`, шаблон `increment.md`.

#### Day 3 — business-analyst + JGit
- **A**: локальный трейсинг (span на LLM-вызов / hook / agent); запись в `traces/<run-id>.ndjson`; команда `ms trace <run-id>`.
- **B**: JGit интеграция в Workspace Manager — create-branch, commit, status, diff.
- **C**: реализация `business-analyst` — chat-interview через CLI, write `increment.md` в ветку инкремента.

#### Day 4 — md-validator + analytic-reviewer
- **A**: интеграция CLI с Workspace Manager (где-то надо хранить state и артефакты).
- **B**: Spring AI ChatClient для Claude через прокси (для Dev_scope позже); end-to-end проверка вызова Claude (через `ms exec-test --model claude` для отладки).
- **C**: md-validator (Python скрипт), analytic-reviewer (LLM-агент), оба подключены к Workspace Manager.

#### Day 5 — manual-validation-gate + smoke Analytic
- **A** + **B** + **C** (объединение): manual-validation-gate в CLI, squash-on-approve коммитов, **end-to-end дрelle Analytic_scope** на toy-задаче. Smoke + bugfix.

**Milestone недели 1:** `ms analyze new "<задача>"` → chat-interview → `increment.md` → md-validator → analytic-reviewer → HITL approve → ветка готова к Dev. На toy-микросервисе.

### Неделя 2 — Dev (Claude Code) + Test (Claude Code) + полный цикл

#### Day 6 — Claude Code integration
- **A**: команда `ms dev <increment-id>` — формирует контекст для Claude Code (передача increment.md + указание целевого репо), вызывает `claude` как subprocess, ловит exit и логи.
- **B**: установка Claude Code локально, подключение конфига из репо коллеги (`install.sh`), верификация что `/plan_w_team` и `/smart_build` работают на toy-микросервисе вручную (без обёртки `ms`).
- **C**: HITL merge-gate для Dev_scope в CLI (показ изменений после Claude Code, approve / reject).

#### Day 7 — Dev_scope end-to-end + Docker build
- **A**: Docker-build hook после approve merge — простой shell-скрипт + verify image tag.
- **B**: интеграция выхода Claude Code обратно в нашу ветку инкремента, корректный squash на этапе merge-gate.
- **C**: end-to-end smoke Analytic → Dev на toy-микросервисе. Bugfix.

**Milestone day 7:** `ms analyze` → `ms dev` → Docker-образ готов. Полный путь до сборки на toy.

#### Day 8 — Test_scope (упрощённый): write tests
- **A**: команда `ms test new <increment-id>` — отдельный вызов Claude Code с новой custom command (например, `/write_integration_tests`) или с системным промптом «теперь напиши integration-тесты по этому increment.md».
- **B**: отдельный test-репозиторий, basic git tag matching (создание тега `inc-<id>` в обоих репо при approve в Test_scope).
- **C**: подготовка conf'ига Claude Code для test-режима (отдельный `.claude/commands/write_integration_tests.md`, или вариант системного промпта внутри `ms`).

#### Day 9 — Test_scope: exec + analyzer
- **A**: команда `ms test run <increment-id>` — запуск `mvn verify` (или `mvn integration-test`) против собранного Docker-образа + Testcontainers. Сбор логов в `test/runs/<timestamp>/`.
- **B**: упрощённый analyzer — LLM-агент, читает логи + increment.md + список упавших тестов → выносит verdict. На MVP-0 без счётчика повторов и history.
- **C**: BUG-маршрут в Dev_scope — если analyzer возвращает `bug_in_product`, формируется `bug.md` и запускается `ms dev` повторно с этим артефактом как доп-контекстом.

#### Day 10 — End-to-end + полировка + демо
- **All**: полный прогон Analytic → Dev → ручной деплой toy-образа → Test → (опционально) BUG → Dev.
- **All**: bugfix, README с короткой инструкцией «как запустить», подготовка демо-сценария.

**Milestone недели 2 (= MVP-0):** один реальный сценарий полного цикла на toy-микросервисе работает end-to-end. Бизнес-аналитик команды способен пройти Analytic_scope и получить `increment.md`.

---

## 6. Definition of Done — MVP-0

Конкретные критерии готовности (binary checks):

- [ ] `ms init`, `ms analyze`, `ms dev`, `ms test run`, `ms status`, `ms trace` — все команды существуют и не падают на стандартных входах.
- [ ] На toy-микросервисе пройден **полный цикл от заявки до Docker-образа и зелёных integration-тестов** хотя бы для одного бизнес-сценария.
- [ ] `increment.md` на этом сценарии написан реальным бизнес-аналитиком через chat-interview, без правки руками в редакторе.
- [ ] При намеренной поломке кода (например, dev-агент вносит баг или мы вручную ломаем продукт перед `ms test run`) — analyzer корректно выносит `bug_in_product`, формируется `bug.md`, повторный `ms dev` принимает его как входной артефакт.
- [ ] Все артефакты (`increment.md`, `tech_spec.md`, `Plan.md`, `dispatch-manifest.json`, `bug.md`) лежат в правильных директориях ветки инкремента.
- [ ] Локальный трейсинг работает: `ms trace <run-id>` показывает порядок вызовов агентов / hook'ов / LLM с длительностью.
- [ ] README инструмента и toy-микросервиса описывают, как запустить с нуля.

---

## 7. Что НЕ входит в MVP-0 (anti-scope)

Подтверждаем явно, чтобы не было ползучего scope:

- Worktree-параллелизм dev-агентов (Claude Code пока работает sequentially).
- Уровни тестирования выше integration (system, e2e/UI, load).
- Selective regression в `exec`.
- История падений analyzer'а с verdict-flip.
- Stop-loss analyzer'а (просто эскалируем после 5 итераций как в Dev).
- JSpecify, Sonar quality gate (только то, что есть в репо коллеги).
- Богатый UX manual-gate'ов (diff view, multi-line comments).
- Бюджет / SLA трекинг.
- Многопользовательский режим / web UI / IDE-плагин.
- Параллельные инкременты.
- Авто-наполнение tags-registry — пользуемся тем, что в `refs/` репо коллеги.

---

## 8. Технические риски и митигации

| Риск | Вероятность | Митигация |
|---|---|---|
| Spring AI ↔ GigaChat — community-адаптер криво работает | средняя | Дать день 2 на интеграцию; если не получается — временно fallback на DeepSeek для всех ролей Analytic |
| Claude Code не работает через корп-прокси с Anthropic | низкая (пользователь подтвердил доступ) | Спайк day 1 (curl до anthropic.com через прокси); если не работает — экстренный план: пишем builder/plan-reviewer на Spring AI ⇒ MVP-0 урезается до Analytic + минимального custom Dev |
| Учебный toy-микросервис «слишком тривиален» — Claude Code не показывает ценности | средняя | Сразу делать его близким к реальному — JPA, валидации, layered architecture, базовые тесты |
| Test_scope упрощение «всё через Claude Code» не даёт качественных integration-тестов | средняя | Если не выходит — на MVP-0 разрешаем мануальные правки/доводку тестов; фиксируем как технический долг |
| Bug.md → повторный Dev — может зациклиться без histo | низкая (контролируемая) | Жёсткий лимит на 1 BUG-итерацию в MVP-0; дальнейшее — эскалация |
| Конфликты между `.claude/` репо коллеги и наших агентов | низкая | Чёткое разделение: `.platform/` — наше; `.claude/` — Claude Code; не пересекается |

---

## 9. После MVP-0 — следующие 2 итерации

**MVP-1 (≈ 2–3 недели после MVP-0): полнота функциональности**
- Worktree-параллелизм dev-агентов.
- JSpecify, Sonar quality gate.
- Уровни тестирования system + e2e.
- Selective regression и история падений analyzer'а.
- Реальная (не toy) задача на одном из микросервисов команды.

**MVP-2 (≈ 1 месяц после MVP-1): постепенная замена Claude Code на свой стек**
- Перенос builder/plan-reviewer на Spring AI агенты (наш план B из развилки).
- Сохранение конфигов скиллов/хуков как они есть (markdown-ы).
- Возможность смешанного режима: часть агентов — Claude Code, часть — наши.

---

## 10. Открытые вопросы по плану

1. **Кто берёт какой workstream.** Конкретное распределение между 2–3 разработчиками — твоё решение, мы оперируем абстрактными A/B/C.
2. **Где живёт toy-микросервис.** Отдельный репо или директория в этом же `multiagent-system`? Предложу — **отдельный репо** `toy-product` (чище отделение «платформа vs объект»).
3. **Где живёт test-репо для MVP-0.** Аналогично — отдельный `toy-product-tests`.
4. **Готовность стенда для `ms test run`.** Под Testcontainers ничего не нужно, но если будут load/e2e (после MVP-0) — потребуется отдельный стенд.
5. **UX команд эскалации после 5 итераций** (старый Q6 Dev_scope) — на MVP-0 откладываем; в эскалацию просто выходим, человек правит руками.
