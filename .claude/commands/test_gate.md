---
description: Финальный HITL gate Test scope. Собирает тест-дифф, результат прогона и заведённые баги, показывает заказчику; по approve коммитит автотесты и артефакты (test-model/test-plan/bugs) и по подтверждению сливает в базовую ветку; по reject фиксирует причину. Заведение багов в трекер и push — вручную.
argument-hint: "[--message <commit-msg>] [--base <branch>]"
model: sonnet
disallowed-tools: EnterPlanMode
---

# /test_gate

Закрывающая HITL-точка Test scope. Запускается **после** `/test_run`, когда
автотесты прогнаны и баги (если были) заведены. Цель — дать человеку осознанно
подтвердить вливание автотестов в базовую ветку и решить судьбу багов.

Это **единственное место Test scope**, где делается `git commit` / `git merge`.
`/test_plan`, `/test_build`, `/test_run` git-историю не трогают.

Заведение багов во внешний трекер и `push`/деплой — **вне зоны команды** (вручную
после approve).

## Variables

- **COMMIT_MSG** = `$1` (`--message`) — заголовок коммита. Нет → предложи в
  conventional-commits стиле (`test:` / `test(<scope>):`) и подтверди.
- **BASE_BRANCH** = `$2` (`--base`) — целевая ветка merge. Нет → определи
  (`main`, иначе `master`) и подтверди у заказчика перед merge.
- **TEST_BRANCH** — текущая ветка (`git branch --show-current`).

## Workflow

### Шаг 1 — собрать факты (проверь сам, не на слово)

Через `Bash`/`Read`:

1. `git branch --show-current` — ветка с тестами.
2. Определи `BASE_BRANCH` (если не передан); запомни, подтвердишь на Шаге 3.
3. `git status --short` — незакоммиченное.
4. `git diff --stat <BASE_BRANCH>...HEAD` и `git diff --stat` — сводка тест-диффа.
5. Прочитай артефакты (без перезапуска тяжёлого прогона):
   - `test/test-plan.md` (по mtime) — какой тест-инкремент закрываем, какие слои;
   - `test/test-model.md` — модель авторинга;
   - `test/bugs/*.md` — заведённые баги (сколько, severity, service-side vs unclear).
6. Лёгкая проверка охвата тест-диффа (только scope, без билда):
   ```bash
   uv run --script .claude/hooks/validators/check_diff_scope.py \
     --plan <последний test/test-plan.md> --baseline <BASE_BRANCH>
   ```

> Сам прогон тестов сделан в `/test_run` — здесь его **не повторяем**. Если прогона
> не было (нет результата/`test/bugs` пуст и автотесты не запускались) — скажи об
> этом и предложи сначала `/test_run`.

### Шаг 2 — показать сводку

```
## Test-gate

**Ветка:**          <TEST_BRANCH>
**Базовая ветка:**  <BASE_BRANCH>
**Тест-план:**      test/<file>.md  (слои: <integration/e2e/load…>)
**Тест-дифф:**      <N файлов, +X/-Y>
**Незакоммичено:**  <да: список / нет>
**Прогон (/test_run):** <green | N тестов упали и разобраны>
**Баги:**           <K в test/bugs/: service-side S, unclear U> | нет
**Scope-check:**    <PASS | FAIL + файлы вне плана>

Одобряете коммит автотестов и merge в <BASE_BRANCH>? (yes / reject + причина)
```

Если есть **unclear**-баги — подсветь: они требуют ручной триажировки человеком.
Если `Scope-check = FAIL` — явно перечисли файлы вне плана (discovery vs creep).

### Шаг 3 — развилка HITL

Дождись явного ответа. **Approve за пользователя не принимай.**

**`yes`:**

1. Подтверди `BASE_BRANCH`, если он определялся автоматически.
2. Закоммить на ветке: автотесты (`src/test/**`, `e2e/`, `load/` …) **и** артефакты
   `test/test-model.md`, `test/test-plan.md`, `test/bugs/*.md`.
   - Заголовок: `COMMIT_MSG`, иначе предложенный `test:`-коммит (кратко, одной
     строкой). Footer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
3. Слей в базу без fast-forward:
   ```bash
   git checkout <BASE_BRANCH>
   git merge --no-ff <TEST_BRANCH> -m "merge: <COMMIT_MSG>"
   ```
   При конфликте — **стоп**, покажи `git status`, отдай разрешение человеку.
4. Выведи финал:
   ```
   ## Test scope completed

   Влито: <TEST_BRANCH> → <BASE_BRANCH>
   Коммит: <sha> <COMMIT_MSG>
   Баги: <K в test/bugs/> — заведи в трекер вручную (service-side: S, unclear: U).

   Следующие шаги (вручную, вне системы):
   - git push (если нужно),
   - завести баги в трекер,
   - повторный /test_run после фиксов сервиса (Dev scope).
   ```

**`reject + <причина>`:**

1. Сохрани причину в `test/rejection_comment.txt` (`Write`).
2. **Ничего не коммить и не сливай.**
3. Выведи: причина зафиксирована; дальше — поправить план (`/test_plan`),
   тесты (`/test_build`) или перепрогнать (`/test_run`), затем снова `/test_gate`.

## Hard limits для команды

- **НЕ `push`**, **НЕ** заводи баги в трекер — это ручные шаги.
- **НЕ разрешай merge-конфликты сам** (human resolution).
- **НЕ принимай approve за пользователя**; без явного «yes» — не сливай.
- **НЕ правь продукт-код и тесты.** Твоя зона — git-операции и `test/`.
- Чистое дерево и пустой diff против базы → сообщи, что вливать нечего.
