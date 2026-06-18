# Shortlink Service

Внутренний бэкенд-сервис коротких ссылок для маркетинговой команды. Принимает длинный URL,
возвращает короткую ссылку вида `/r/{code}`, выполняет редирект и собирает статистику переходов.

- **Стек:** Java 21, Spring Boot 3.3.x, Maven.
- **Хранилище:** in-memory (`ConcurrentHashMap`). Данные **не переживают перезапуск** — это
  осознанное ограничение.
- **Безопасность:** отсутствует по дизайну (сетевая изоляция).
- Счётчик переходов потокобезопасен (`AtomicLong`). Любой некорректный запрос маппится в
  400/404/409/410, никогда — в необработанный 500.

## Сборка

```bash
./mvnw clean package
```

## Запуск

```bash
./mvnw spring-boot:run
```

Сервис стартует на `http://localhost:8080` (порт и `app.base-url` настраиваются в
`src/main/resources/application.yml`).

## API

### 1. Создание короткой ссылки — `POST /api/links`

Тело: `{ "url": "...", "customCode"?: "...", "expiresAt"?: "ISO-8601" }`.

Случайный код:

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/long-landing-page"}'
```

Кастомный код и срок истечения:

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/sale","customCode":"black-friday","expiresAt":"2030-01-01T00:00:00Z"}'
```

Ответ `201 Created`:

```json
{
  "code": "black-friday",
  "shortUrl": "http://localhost:8080/r/black-friday",
  "url": "https://example.com/sale",
  "createdAt": "2026-06-18T12:00:00Z",
  "expiresAt": "2030-01-01T00:00:00Z"
}
```

Коды ошибок: `400` — некорректный URL (пустой, не http/https, длиннее 2048) или кастомный код не
по паттерну `^[a-z0-9-]{3,50}$`; `409` — кастомный код уже занят.

### 2. Редирект — `GET /r/{code}`

```bash
curl -i http://localhost:8080/r/black-friday
```

Ответ `302 Found` с заголовком `Location: https://example.com/sale`. Каждый успешный переход
атомарно увеличивает счётчик. Коды ошибок: `404` — код не найден; `410` — ссылка просрочена
(переход не засчитывается).

### 3. Статистика — `GET /api/links/{code}`

```bash
curl -i http://localhost:8080/api/links/black-friday
```

Ответ `200 OK`:

```json
{
  "code": "black-friday",
  "url": "https://example.com/sale",
  "shortUrl": "http://localhost:8080/r/black-friday",
  "clickCount": 1,
  "createdAt": "2026-06-18T12:00:00Z",
  "lastClickedAt": "2026-06-18T12:01:00Z",
  "expiresAt": "2030-01-01T00:00:00Z"
}
```

`lastClickedAt` равно `null`, пока переходов не было; `expiresAt` опускается, если TTL не задан.

## Наблюдаемость

Actuator health:

```bash
curl -i http://localhost:8080/actuator/health
```

Ответ `200 OK` с телом `{"status":"UP"}`.

Структурированное (`key=value`) логирование ключевых событий:

- `event=link_created code=... url=... expiresAt=...`
- `event=redirect code=... target=...`
- `event=error status=... message=...` (для 400/404/409/410)

## Тесты

```bash
./mvnw test
```
