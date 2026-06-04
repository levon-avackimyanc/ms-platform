# ms-platform

**Multiagent System Platform** — внутренний CLI-инструмент команды для оркестрации сквозного цикла *Analytic → Dev → Test* силами специализированных ИИ-агентов.

> **Концепция:** внутренний аналог Claude Code для корпоративного контура (skills, hooks, агенты, HITL-gate'ы, артефакты в git).

Подробности — в проектных документах:
- [`ARCHITECTURE_PROPOSAL.md`](./ARCHITECTURE_PROPOSAL.md) — архитектура целиком.
- [`AGENTS_SPECIFICATION.md`](./AGENTS_SPECIFICATION.md) — YAML-спецификации агентов всех scope'ов.
- [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md) — план MVP-0 на 2 недели.

---

## Технический стек

| Слой | Технология | Версия |
|---|---|---|
| Язык | Java | 21 LTS (Amazon Corretto) |
| Платформа | Spring Boot | 3.5.14 |
| CLI | Spring Shell | 3.4.0 |
| LLM | Spring AI (OpenAI-compatible) | 1.0.0 |
| Git workspace | Eclipse JGit | 7.3.0 |
| Сборка | Maven | 3.9+ |
| Тесты | JUnit 5, Mockito, Spring Shell test | — |

## Требования к окружению

- **JDK 21** (Corretto 21 / Temurin 21 / любой совместимый).
- Maven 3.9+.
- Доступ к LLM через корп-прокси (DeepSeek / GigaChat / Anthropic) — настраивается через env-переменные (см. ниже).

Проверь окружение:

```bash
java -version       # должно быть 21.x
mvn -version
```

Если активный JDK не 21, выстави его:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

## Сборка и запуск

```bash
mvn clean verify                 # сборка + прогон тестов
java -jar target/ms-platform.jar status
```

Ожидаемый вывод `status`:

```
application:   ms-platform
java:          21.x.x (Amazon.com Inc.)
spring-boot:   3.5.14
workspace:     /path/to/your/cwd
traces (dir):  /path/to/your/cwd/.platform-state/traces
tracing on:    true
```

## Конфигурация

Конфигурация подхватывается из `application.yml` + env-переменных.

| Env | Назначение | По умолчанию |
|---|---|---|
| `MS_LLM_BASE_URL` | base URL OpenAI-compatible прокси | `https://api.openai.com` |
| `MS_LLM_API_KEY` | API-ключ LLM | `placeholder-replace-via-env` |
| `MS_LLM_MODEL` | имя модели по умолчанию | `gpt-4o-mini` |
| `MS_WORKSPACE_ROOT` | корневая директория для веток инкрементов | `pwd` |
| `MS_TRACES_DIR` | директория для локального трейсинга | `<cwd>/.platform-state/traces` |

## Состояние разработки

MVP-0 в активной разработке по [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md). Текущая фаза — Day 1 (skeleton).

## Лицензия

[Apache License 2.0](./LICENSE).
