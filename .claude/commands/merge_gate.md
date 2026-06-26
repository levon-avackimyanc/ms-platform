---
description: Финальный HITL merge-gate Dev scope. Собирает diff и вердикты, показывает заказчику, по approve коммитит инкремент и (по подтверждению) сливает в базовую ветку, по reject фиксирует причину.
argument-hint: "[--message <commit-msg>] [--base <branch>]"
model: sonnet
disallowed-tools: EnterPlanMode
---

# /merge_gate

Закрывающая HITL-точка Dev scope. Запускается **после** `/smart_build`, когда
код написан, тесты прошли и `validator` отработал. Цель — дать человеку
осознанно подтвердить вливание инкремента в базовую ветку.

Это **единственное место**, где система делает `git commit` / `git merge`.
Ни `/analyze`, ни `/dev_plan`, ни `/smart_build` git-историю не трогают.

`push` и деплой — **вне зоны команды**. Их делает человек вручную после approve.

## Variables

- **COMMIT_MSG** = `$1` (`--message`) — заголовок коммита. Если не передан —
  предложи свой в conventional-commits-стиле и спроси подтверждение.
- **BASE_BRANCH** = `$2` (`--base`) — целевая ветка для merge. Если не передан —
  определи автоматически (`main`, иначе `master`) и **обязательно подтверди у
  заказчика** перед merge.
- **INCREMENT_BRANCH** — текущая ветка (`git branch --show-current`), её и сливаем.

## Workflow

### Шаг 1 — собрать факты (не доверяй на слово, проверь сам)

Через `Bash` собери и покажи заказчику фактическое состояние:

1. `git branch --show-current` — ветка инкремента.
2. Определи `BASE_BRANCH` (если не передан): `git rev-parse --verify main` →
   иначе `master`. Запомни, подтвердишь на Шаге 3.
3. `git status --short` — есть ли незакоммиченные изменения.
4. `git diff --stat <BASE_BRANCH>...HEAD` и `git diff --stat` (рабочее дерево) —
   сводка изменений против базы.
5. Прочитай артефакты-вердикты, если они есть (через `Read`, без перезапуска
   тяжёлых сборок):
   - `analytic/review-report.json` — вердикт аналитики;
   - последний план `specs/*.md` (по mtime) — какой инкремент закрываем.
6. Прогони лёгкую проверку охвата (только scope, без билда):
   ```bash
   uv run --script .claude/hooks/validators/check_diff_scope.py \
     --plan <последний specs/*.md> --baseline <BASE_BRANCH>
   ```
   Результат (PASS/FAIL + список out-of-scope файлов) включи в сводку.

### Шаг 2 — показать сводку

Выведи заказчику единым блоком:

```
## Merge-gate

**Ветка инкремента**: <INCREMENT_BRANCH>
**Базовая ветка**:    <BASE_BRANCH>
**План**:             specs/<file>.md
**Изменения**:        <N файлов, +X/-Y> (git diff --stat)
**Незакоммичено**:    <да: список / нет>
**Scope-check**:      <PASS | FAIL + файлы вне плана>
**Аналитика**:        <review-report.json: ok | needs_revision | n/a>

Одобряете merge в <BASE_BRANCH>? (yes / reject + причина)
```

Если `Scope-check = FAIL` — **не скрывай**: явно скажи, какие файлы вне плана, и
предложи заказчику решить (это discovery или scope creep) до approve.

### Шаг 3 — развилка HITL

Дождись явного ответа. **Не принимай approve за пользователя.**

**`yes` (или утвердительная форма):**

1. Подтверди `BASE_BRANCH`, если он определялся автоматически:
   «Сливаю `<INCREMENT_BRANCH>` → `<BASE_BRANCH>`, верно? (yes / другая ветка)».
2. Если есть незакоммиченные изменения — закоммить их на ветке инкремента:
   - Заголовок: `COMMIT_MSG`, иначе предложенный тобой conventional-commit
     (`feat:` / `fix:` / `refactor:` …), **краткий, одной строкой, без
     привязок к roadmap / Day N**. Тело — только если нужно (несколько слов).
   - Footer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
3. Слей в базу **без fast-forward**, чтобы инкремент читался в истории:
   ```bash
   git checkout <BASE_BRANCH>
   git merge --no-ff <INCREMENT_BRANCH> -m "merge: <COMMIT_MSG>"
   ```
   При конфликте — **остановись**, покажи `git status`, передай разрешение
   конфликта заказчику (human resolution). Не разрешай конфликты сам.
4. Выведи финал:
   ```
   ## Dev scope completed

   Влито: <INCREMENT_BRANCH> → <BASE_BRANCH>
   Коммит: <sha> <COMMIT_MSG>

   Следующие шаги (вручную, вне системы):
   - git push (если нужно опубликовать),
   - деплой на стенд.
   ```

**`reject + <причина>`:**

1. Сохрани причину в `analytic/rejection_comment.txt` (через `Write`).
2. **Ничего не коммить и не сливай.**
3. Выведи:
   ```
   ## Merge отклонён

   Причина зафиксирована в analytic/rejection_comment.txt.
   Дальше можно:
   - запустить /dev_plan с rejection_comment.txt как уточнением,
   - или поправить код и повторно прогнать /smart_build, затем /merge_gate.
   ```

## Hard limits для команды

- **НЕ `push`.** Публикация и деплой — ручные, вне системы.
- **НЕ разрешай merge-конфликты сам** — это HITL-точка (human resolution).
- **НЕ принимай approve за пользователя** и не сливай без явного «yes».
- **НЕ модифицируй продукт-код.** Твоя зона — git-операции и `analytic/`.
- Если рабочее дерево чистое и diff против базы пуст — сообщи, что вливать
  нечего, и не делай пустой merge.
