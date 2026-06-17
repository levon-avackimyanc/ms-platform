---
description: Старт Analytic_scope. Запускает chat-interview с заказчиком через business-analyst, прогоняет валидацию analytic/increment.md и семантическое ревью через analytic-reviewer, заканчивается HITL approve.
argument-hint: "[краткая формулировка задачи от заказчика]"
model: sonnet
disallowed-tools: EnterPlanMode
hooks:
  Stop:
    - hooks:
        - type: command
          command: >-
            uv run --script $CLAUDE_PROJECT_DIR/.claude/hooks/validators/validate_increment.py
            --file analytic/increment.md
            --config $CLAUDE_PROJECT_DIR/.claude/config/increment_template.yaml
---

# /analyze

Старт Analytic_scope. Превращает свободно сформулированную задачу заказчика
в `analytic/increment.md` нужной структуры с подтверждением заказчика.

На выходе — папка `analytic/` в текущей ветке с тремя файлами:

- `analytic/original_task.txt` — исходная задача заказчика, 1-в-1.
- `analytic/increment.md` — спецификация инкремента по шаблону из
  `.claude/config/increment_template.yaml`.
- `analytic/review-report.json` — последний вердикт `analytic-reviewer`.

Следующий шаг после approve — `/plan_w_team`.

## Variables

- **USER_TASK** = `$1` — формулировка задачи от заказчика (свободный текст,
  одна-две фразы).  Если пользователь не передал — попроси его сделать это
  и не запускай interview, пока не будет содержательного текста.
- **ANALYTIC_DIR** = `analytic/` — относительно `$CLAUDE_PROJECT_DIR`.
- **MAX_ITERATIONS** = `5` — суммарный лимит на циклы валидатор/ревьюер/HITL.

## Workflow

### Шаг 1 — bootstrap

1. Создай каталог `analytic/` если его нет.
2. Сохрани `USER_TASK` **точно в том виде, в котором её передал
   пользователь, без редактуры**, в `analytic/original_task.txt`.
   Это нужно для последующего ревью: `analytic-reviewer` будет сверять
   `increment.md` именно с этим файлом, а не с твоим пересказом.

### Шаг 2 — делегировать chat-interview

Запусти субагента **`business-analyst`** через Task-инструмент.
В описании задачи для него передай:

- путь к `analytic/original_task.txt`,
- путь к `.claude/config/increment_template.yaml` (его агент должен
  прочитать сам как источник правды),
- инструкцию: «проведи chat-interview с заказчиком и запиши
  `analytic/increment.md`».

Пока агент работает — **ты ничего не пишешь в чате**. Все вопросы заказчику
формулирует `business-analyst`; ты лишь проксируешь сообщения заказчика
ему обратно.

### Шаг 3 — структурная валидация (автоматическая; ты её НЕ запускаешь сам)

После того как `business-analyst` сделает `Write analytic/increment.md`,
Claude Code сам запустит Stop-hook этой команды — он прописан в её
frontmatter и вызывает `validate_increment.py`. Ты увидишь его JSON-вывод
в чате. Stop-hook также сработает на промежуточных Stop event'ах
(например, после ответа субагента до Write); если файла ещё нет, валидатор
эмитит `{"result":"continue","status":"skipped"}` — это нормально, ты
проходишь дальше по workflow.

**ЗАПРЕЩЕНО ручное дублирование:** не запускай `validate_increment.py` сам
через `Bash`, ни через `python3`, ни через `uv run --script`. Stop-hook
делает это за тебя. Дубликаты дают шум в логах и могут мешать друг другу.

Возможные исходы (читай результат hook'а, не вызывай его сам):

- **`result: continue`** + `status: ok` — структура ок, файл есть, переходи к Шагу 4.
- **`result: continue`** + `status: skipped` — файла ещё нет; ждёшь Write от `business-analyst`.
- **`result: block`** — структура не прошла; в `reason` конкретные failed checks.
  Передай управление обратно `business-analyst`-субагенту с этим JSON как
  контекстом. Цикл идёт до тех пор, пока хук не вернёт `ok`,
  **в пределах `MAX_ITERATIONS`** общего лимита.

Если Stop-hook **не сработал** в течение разумного времени после Write —
**не обходи его руками**. Сообщи пользователю: «Stop-hook не запустился,
проверьте `hooks:` в frontmatter `commands/analyze.md`». Это сигнал бага
конфигурации, а не повод запускать валидатор через Bash.

### Шаг 4 — семантическое ревью

Когда структурная валидация прошла — запусти субагента
**`analytic-reviewer`**. В описании задачи передай ему:

- путь к `analytic/original_task.txt`,
- путь к `analytic/increment.md`,
- путь к `.claude/config/increment_template.yaml`.

Агент вернёт ответ, в конце которого будет **ровно один markdown-кодоблок
с языком `json`** — это его вердикт. Извлеки его, провалидируй, что внутри
есть поля `status` и `issues`, и запиши **как есть** в
`analytic/review-report.json`.

Возможные исходы:

- **`status: "ok"`** — переходи к Шагу 5 (HITL).
- **`status: "needs_revision"`** — передай управление обратно
  `business-analyst`, приложив `analytic/review-report.json` как контекст.
  Учитывается в общем `MAX_ITERATIONS`.

### Шаг 5 — HITL approve

Покажи заказчику в чате:

```
## Готов к approve

**Файл**: analytic/increment.md
**Структурная валидация**: ✅ pass
**Семантическое ревью**: ✅ ok
{если есть minor issues — перечисли их кратко}

Одобряете? (yes / reject + причина)
```

Дождись ответа.

- **`yes`** (или любая утвердительная форма) — выведи финальное:
  ```
  ## Analytic_scope completed

  Артефакты:
  - analytic/original_task.txt
  - analytic/increment.md
  - analytic/review-report.json

  **Next step**: запусти `/plan_w_team` с этим `increment.md` как
  основным контекстом.
  ```
- **`reject + <причина>`** — сохрани причину в
  `analytic/rejection_comment.txt`, передай управление `business-analyst`
  с этим файлом как контекстом. Учитывается в общем `MAX_ITERATIONS`.

### Шаг 6 — лимит итераций

Если суммарное число прохождений Шагов 3–5 достигло `MAX_ITERATIONS = 5`
и `status` всё ещё `needs_revision` или HITL-reject — **остановись** и
выдай пользователю:

```
## Лимит итераций исчерпан

Прошло 5 циклов validation / review / HITL-gate без approve.
Это сигнал, что задача нечётко поставлена или требует переформулировки.

Что можно сделать:
- переписать USER_TASK более конкретно и запустить /analyze заново;
- посмотреть последние review-report.json и rejection_comment.txt для
  понимания причин;
- созвониться с заказчиком вне процесса и снова стартовать /analyze.
```

## Hard limits для команды

- Не модифицируй ничего вне `analytic/`. `src/`, `specs/`, `pom.xml` и
  конфиги — не твоя зона.
- Не запускай git-команды. Коммит — забота `/merge_gate`.
- Не вызывай `/plan_w_team` сам. Это следующий шаг пользователя.

## Что делать, если `business-analyst` не справляется

Если агент ушёл в петлю или начал писать `increment.md` без интервью —
останови его (через ответ субагенту с requirement «верни управление, перезайди
с правильным workflow»). Если повторяется — отметь это в финальном Report,
чтобы пользователь поправил промпт агента в `.claude/agents/business-analyst.md`.

## Что делать, если `analytic-reviewer` отдаёт не-JSON

Если в его ответе нет ни одного блока ```json или JSON невалиден —
**не пиши `review-report.json`** (нечего писать). Сообщи пользователю
ошибку и предложи перезапустить ревью. Это сигнал что промпт агента
работает нестабильно.
