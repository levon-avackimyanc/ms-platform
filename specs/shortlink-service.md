# Plan: Shortlink Service (Java/Spring Boot)

## Task Description

Build a new internal backend service for shortening URLs, used by the marketing team, as specified in `analytic/increment.md`. The service must:

- Accept a long URL and return a short link with a code in the path (`/r/{code}`), generating a random base62 code or accepting a marketer-supplied custom code.
- Redirect from a short link to the original URL.
- Count clicks per link and expose per-link statistics.
- Support an optional expiry date (TTL) per link.
- Be a standalone **Java 21 / Spring Boot 3.3.x** application, built with Maven, with no authentication (security via network isolation), **in-memory** storage (data is intentionally not persisted across restarts), robust error handling (a malformed request must never crash the service), a thread-safe exact click counter, Actuator `/health`, structured logging, a README, and proper tests.

This is a **Dev scope** plan: product code + **unit tests only**. Full HTTP-stack integration/E2E coverage is deferred to Test scope.

Task type: **feature**. Complexity: **medium**.

## Objective

A runnable Spring Boot service exposing `POST /api/links`, `GET /r/{code}`, `GET /api/links/{code}`, and `GET /actuator/health`, with all 18 increment acceptance criteria satisfied at the unit level (controller methods, service logic, validation, code generation, repository concurrency, and exception→status mapping all covered by unit tests), `mvn test` green, and a README explaining how to build, run, and call the API.

## Problem Statement

The marketing team needs to turn long campaign URLs into short, shareable links, optionally with vanity codes (e.g. `/r/black-friday`), and measure click-through during email blasts. Traffic is bursty on redirects (thousands of links, spikes during sends), so the redirect path must be cheap and the click counter must not lose increments under concurrency. The service is internal and unauthenticated, but a single malformed request must not bring it down — every predictable bad input must map to a clean HTTP error, never a 500 or a crash.

## Solution Approach

A conventional layered Spring Boot service:

- **Domain** `ShortLink` holds `code`, `originalUrl`, `createdAt`, nullable `expiresAt`, an `AtomicLong clickCount`, and a `volatile Instant lastClickedAt`. `recordClick()` does an atomic increment + last-writer-wins timestamp; `isExpired(now)` encapsulates TTL logic.
- **Repository** `LinkRepository` (interface) + `InMemoryLinkRepository` backed by a `ConcurrentHashMap<String, ShortLink>`. Creation uses `putIfAbsent` so a taken code is detected atomically (no lost-update / overwrite). Restart resets the map (criterion 11).
- **Validation lives in the service, not only in framework annotations**, so it is fully unit-testable without the servlet stack: `UrlValidator` (scheme ∈ {http, https}, parseable, length ≤ 2048) and a custom-code check (`^[a-z0-9-]{3,50}$`). Bean-validation annotations on the request DTO are kept as defense-in-depth, but the authoritative checks are service-side and throw typed exceptions.
- **Code generation** `CodeGenerator` produces a base62 (`[A-Za-z0-9]`) code of length 6–7 using `SecureRandom`, retrying via `putIfAbsent` on the rare collision. Generated codes never collide with system routes because redirects live under the distinct `/r/` prefix (vs `/api`, `/actuator`).
- **Service** `LinkService` orchestrates create (random vs custom → 409 on taken, 400 on invalid URL/code), resolve-for-redirect (404 unknown, 410 expired, atomic click increment on success), and stats projection (incl. `lastClickedAt` = null when never clicked).
- **Web** thin controllers: `LinkController` (`POST /api/links` → 201, `GET /api/links/{code}` → 200) and `RedirectController` (`GET /r/{code}` → 302 with `Location`). DTOs are Java records. A `@RestControllerAdvice` `GlobalExceptionHandler` maps typed exceptions to 400/404/409/410 with a consistent `ErrorResponse` body — this is what guarantees "a bad request never crashes the service".
- **Ops** `spring-boot-starter-actuator` exposes `/health`; SLF4J/Logback structured (`key=value`) logging on create / redirect / error; `app.base-url` config property builds `shortUrl`.

The counter accuracy requirement (criterion 7: 100 parallel hits → exactly 100) is met by `AtomicLong` and verified by a concurrency unit test.

## Relevant Files

This is a greenfield repository (no existing source). All files below are **new**. Use `analytic/increment.md` as the authoritative requirement source.

### New Files

- `pom.xml` — Maven project: Spring Boot 3.3.x parent, Java 21, starters: `web`, `actuator`, `validation`, `test`. Surefire for `mvn test`.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` — Maven wrapper (so `./mvnw test` is portable).
- `.mvn/jvm.config` — **added during build (justified discovery)**: the machine's default Maven JVM is JDK 26, on which google-java-format/Palantir crash; this file supplies the `--add-exports/--add-opens` for `jdk.compiler` that lets spotless run. The `pom.xml` correspondingly pins google-java-format 1.27.0 + spotless 2.44.5. Compilation still targets `release 21`.
- `src/main/java/com/example/shortlink/ShortLinkApplication.java` — Spring Boot entrypoint.
- `src/main/java/com/example/shortlink/config/AppProperties.java` — `@ConfigurationProperties("app")` holding `baseUrl`.
- `src/main/java/com/example/shortlink/domain/ShortLink.java` — domain entity; `AtomicLong` counter, `volatile` lastClickedAt, `recordClick()`, `isExpired(Instant)`.
- `src/main/java/com/example/shortlink/repository/LinkRepository.java` — interface (`save`/`putIfAbsent`, `findByCode`).
- `src/main/java/com/example/shortlink/repository/InMemoryLinkRepository.java` — `ConcurrentHashMap` impl using `putIfAbsent`.
- `src/main/java/com/example/shortlink/service/CodeGenerator.java` — base62 6–7 char generator (`SecureRandom`).
- `src/main/java/com/example/shortlink/service/UrlValidator.java` — http/https + length ≤ 2048 validation.
- `src/main/java/com/example/shortlink/service/LinkService.java` — create / resolve / stats orchestration.
- `src/main/java/com/example/shortlink/web/LinkController.java` — `POST /api/links`, `GET /api/links/{code}`.
- `src/main/java/com/example/shortlink/web/RedirectController.java` — `GET /r/{code}` → 302.
- `src/main/java/com/example/shortlink/web/dto/CreateLinkRequest.java` — record `{ url, customCode?, expiresAt? }` (+ defensive bean-validation annotations).
- `src/main/java/com/example/shortlink/web/dto/LinkResponse.java` — record `{ code, shortUrl, url, createdAt, expiresAt? }`.
- `src/main/java/com/example/shortlink/web/dto/LinkStatsResponse.java` — record incl. `clickCount`, `lastClickedAt`.
- `src/main/java/com/example/shortlink/web/error/ErrorResponse.java` — record `{ timestamp, status, error, message }`.
- `src/main/java/com/example/shortlink/web/error/GlobalExceptionHandler.java` — `@RestControllerAdvice` → 400/404/409/410.
- `src/main/java/com/example/shortlink/exception/{CodeAlreadyExistsException,LinkNotFoundException,LinkExpiredException,InvalidUrlException,InvalidCustomCodeException}.java` — typed exceptions.
- `src/main/resources/application.yml` — `server.port`, `app.base-url`, actuator health exposure, logging pattern.
- `src/test/java/com/example/shortlink/**/*Test.java` — unit tests (see Testing Strategy).
- `README.md` — build/run instructions + API examples for all three endpoints.

## Implementation Phases

### Phase 1: Foundation
Maven project + wrapper, Spring Boot app, `application.yml`, `AppProperties`, package layout. Domain `ShortLink` (atomic counter, expiry), `LinkRepository` + `InMemoryLinkRepository` (`putIfAbsent`), `CodeGenerator`, `UrlValidator`, typed exceptions.

### Phase 2: Core Implementation
`LinkService` (create random/custom with 409 + 400 paths, resolve with 404/410 + atomic increment, stats projection). Web layer: controllers (201/302/200), record DTOs, `GlobalExceptionHandler` mapping to 400/404/409/410.

### Phase 3: Integration & Polish
Actuator `/health`, structured logging on create/redirect/error, README. Then unit tests, code review, and final validation.

## Team Orchestration

- You operate as the team lead and orchestrate the team to execute the plan.
- You're responsible for deploying the right team members with the right context to execute the plan.
- IMPORTANT: You NEVER operate directly on the codebase. You use the `Agent` tool to deploy team members for building, validating, testing, and other tasks, and the `Task*` tools (TaskCreate/TaskUpdate/TaskList) to coordinate their work.
  - This is critical. Your job is to act as a high-level director of the team, not an implementer.
  - Your role is to validate all work is going well and make sure the team is on track to complete the plan.
  - You orchestrate by using the `Agent` tool to deploy team members and the `Task*` tools to manage coordination between them.
  - Communication is paramount. You'll use `SendMessage` to resume team members and the `Task*` tools to track their progress against the plan.
- Take note of the session id of each team member. This is how you'll reference them.

### Team Members

- Developer
  - Name: dev-shortlink
  - Role: All product code (foundation, service, web, ops) — single developer keeps context across the layered build via Resume.
  - Agent Type: developer
  - Resume: true
- Unit-Tester
  - Name: tester-shortlink
  - Role: Unit tests for all code dev-shortlink wrote (service, util, domain, repository, controllers via direct method calls, exception handler).
  - Agent Type: unit-tester
  - Resume: true
- Code-Reviewer
  - Name: reviewer-shortlink
  - Role: Per-developer diff review, PASS/FAIL verdict — read-only.
  - Agent Type: code-reviewer
  - Resume: true
- Validator
  - Name: validator-shortlink
  - Role: Final validation — runs the Unit Layer runner (`./mvnw test`) and checks acceptance criteria.
  - Agent Type: validator
  - Resume: true

## Testing Strategy

**Dev scope writes unit tests only.** Integration and E2E coverage are owned by Test scope (a separate phase) and are not planned here.

### Unit Tests

- `CodeGeneratorTest` — generated code length is 6–7, alphabet is strictly base62, successive codes differ.
- `UrlValidatorTest` — accepts `http://`/`https://`; rejects empty, garbage, `ftp://`/`javascript:`, and URLs longer than 2048 chars.
- `ShortLinkTest` — `recordClick()` increments count and updates `lastClickedAt`; `isExpired(now)` true only when `expiresAt` is in the past; null `expiresAt` never expires.
- `InMemoryLinkRepositoryTest` — `putIfAbsent` returns/saves on free code, signals conflict on a taken code without overwriting; `findByCode` hit/miss.
- `LinkServiceTest` — create with random code; create with free custom code (`code == given`); create with taken custom code → `CodeAlreadyExistsException` (409) and existing link unchanged; create with invalid URL → `InvalidUrlException` (400); create with invalid custom code (fails `^[a-z0-9-]{3,50}$`) → `InvalidCustomCodeException` (400); create with URL > 2048 → 400; resolve active code → 302 target + atomic increment; resolve unknown → `LinkNotFoundException` (404); resolve expired → `LinkExpiredException` (410) with **no** increment; stats projection includes `lastClickedAt == null` when never clicked.
- `LinkServiceTest#redirectIncrementsClickCountAtomically` — 100 concurrent resolves → `clickCount == 100` (thread-safety, criterion 7).
- `GlobalExceptionHandlerTest` — each typed exception maps to the correct HTTP status (400/404/409/410) and a non-empty `ErrorResponse` message.
- `RedirectControllerTest` — `GET /r/{code}` returns `302 FOUND` with `Location` header (direct controller call, mocked service).
- `LinkControllerTest` — create returns `201` with the expected body; stats returns `200` with the expected body (direct controller calls, mocked service).

### Deferred to Test scope

Full HTTP-stack flows via `@SpringBootTest`/`@WebMvcTest` + MockMvc — real routing, `@Valid` bean-validation triggering 400, the Actuator `/health` endpoint over HTTP, and end-to-end 201/302/400/404/409/410 through the servlet stack — are deferred to Test scope, not built in this Dev plan.

## Test Infrastructure (User-Declared)

### Unit Layer (Java) — MANDATORY, never Skipped
- **Status:** Active
- **Files glob:** `src/test/java/**/*Test.java`
- **Infra signature (regex, optional for unit):** `n/a`
- **Happy-path scenarios (≥1 named):**
  - `CodeGeneratorTest#generatesBase62CodeOfExpectedLength`
  - `UrlValidatorTest#rejectsNonHttpSchemeWith400`
  - `LinkServiceTest#createWithTakenCustomCodeThrowsConflict`
  - `LinkServiceTest#redirectIncrementsClickCountAtomically`
  - `RedirectControllerTest#returns302WithLocationHeader`
  - `GlobalExceptionHandlerTest#mapsExpiredToGone`
- **Runner command:** `./mvnw test`
- **Realism rationale:** Standard Spring Boot 3 unit setup via `spring-boot-starter-test` (JUnit 5 Jupiter, Mockito, AssertJ) with Surefire running `*Test.java`; pure-JVM unit tests need no containers since storage is in-memory.

### Integration Layer (Java)
- **Status:** Skipped — owned by Test scope

### E2E Layer (Java)
- **Status:** Skipped — owned by Test scope

## Step by Step Tasks

- IMPORTANT: Execute every step in order, top to bottom. Each task maps directly to a `TaskCreate` call.
- Before you start, run `TaskCreate` to create the initial task list that all team members can see and execute.

### 1. Scaffold Project
- **Task ID**: scaffold-project
- **Depends On**: none
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot maven
- **Parallel**: false
- **Tests**: None directly; enables `./mvnw test` to run (compilation must pass).
- Create `pom.xml` (Spring Boot 3.3.x parent, Java 21; starters web, actuator, validation, test).
- Generate the Maven wrapper (`mvn -N wrapper:wrapper`) so `./mvnw test` works.
- Create `ShortLinkApplication.java`, `config/AppProperties.java`, and `src/main/resources/application.yml` (`server.port`, `app.base-url`, expose actuator health, logging pattern).

### 2. Domain & Repository
- **Task ID**: domain-repository
- **Depends On**: scaffold-project
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot entity
- **Parallel**: false
- **Tests**: Unit: `ShortLinkTest` (atomic increment, expiry), `InMemoryLinkRepositoryTest` (putIfAbsent conflict, find).
- Implement `domain/ShortLink.java` with `AtomicLong clickCount`, `volatile Instant lastClickedAt`, `recordClick()`, `isExpired(Instant now)`.
- Implement `repository/LinkRepository.java` interface and `repository/InMemoryLinkRepository.java` over `ConcurrentHashMap` using `putIfAbsent` for conflict-safe creation.

### 3. Code Generation & URL Validation
- **Task ID**: codegen-validation
- **Depends On**: scaffold-project
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot
- **Parallel**: true
- **Tests**: Unit: `CodeGeneratorTest` (length/alphabet), `UrlValidatorTest` (scheme + 2048 length).
- Implement `service/CodeGenerator.java` — base62 `[A-Za-z0-9]`, length 6–7, `SecureRandom`.
- Implement `service/UrlValidator.java` — accept only parseable `http`/`https` URLs with length ≤ 2048; expose a custom-code check for `^[a-z0-9-]{3,50}$`.
- Create the typed exceptions under `exception/` (`InvalidUrlException`, `InvalidCustomCodeException`, `CodeAlreadyExistsException`, `LinkNotFoundException`, `LinkExpiredException`).

### 4. Service Layer
- **Task ID**: service-layer
- **Depends On**: domain-repository, codegen-validation
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot exception error handling
- **Parallel**: false
- **Tests**: Unit: `LinkServiceTest` (create random/custom/taken→409/invalid→400, resolve active/unknown→404/expired→410, stats, concurrency 100→100).
- Implement `service/LinkService.java`: create (validate URL → 400; validate or generate code; `putIfAbsent` → 409 on taken custom code), resolve (404 unknown, 410 expired, atomic increment + `lastClickedAt` on success), stats projection (`lastClickedAt` null when never clicked).
- Build `shortUrl` from `AppProperties.baseUrl` + `/r/` + code.

### 5. Web Layer
- **Task ID**: web-layer
- **Depends On**: service-layer
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot controller exception error handling 404 400
- **Parallel**: false
- **Tests**: Unit: `LinkControllerTest` (201/200 bodies), `RedirectControllerTest` (302 + Location), `GlobalExceptionHandlerTest` (400/404/409/410 mapping).
- Implement record DTOs (`CreateLinkRequest` with defensive bean-validation, `LinkResponse`, `LinkStatsResponse`, `ErrorResponse`).
- Implement `LinkController` (`POST /api/links` → 201, `GET /api/links/{code}` → 200) and `RedirectController` (`GET /r/{code}` → 302 `Location`).
- Implement `web/error/GlobalExceptionHandler.java` (`@RestControllerAdvice`) mapping typed + validation exceptions to 400/404/409/410 with `ErrorResponse`.

### 6. Observability & README
- **Task ID**: observability-readme
- **Depends On**: web-layer
- **Assigned To**: dev-shortlink
- **Agent Type**: developer
- **Stack**: Java Spring Boot
- **Parallel**: false
- **Tests**: None directly (covered indirectly; actuator-over-HTTP is Test scope).
- Confirm Actuator `/health` is exposed (UP) via `application.yml`.
- Add structured (`key=value`) SLF4J logging for link creation, each successful redirect, and each handled error (400/404/409/410).
- Write `README.md`: build (`./mvnw clean package`), run (`./mvnw spring-boot:run`), and `curl` examples for `POST /api/links`, `GET /r/{code}`, `GET /api/links/{code}`.

### 7. Write Unit Tests
- **Task ID**: unit-tests
- **Depends On**: domain-repository, codegen-validation, service-layer, web-layer, observability-readme
- **Assigned To**: tester-shortlink
- **Agent Type**: unit-tester
- **Stack**: Java JUnit Mockito assertj test structure
- **Parallel**: false
- Write unit tests as defined in Testing Strategy and the `### Unit Layer (Java)` block.
- Cover service logic, branches, edge cases, error paths; mock dependencies (Mockito); use AssertJ assertions; include the 100-thread concurrency test for counter atomicity.
- Test controllers by direct method invocation with a mocked service (assert `ResponseEntity` status/headers/body) — no MockMvc/servlet wiring here.
- NOTE: do NOT write integration/e2e tests here — those are Test scope's job.

### 8. Code Review
- **Task ID**: code-review
- **Depends On**: domain-repository, codegen-validation, service-layer, web-layer, observability-readme, unit-tests
- **Assigned To**: reviewer-shortlink
- **Agent Type**: code-reviewer
- **Stack**: Java Spring Boot controller exception error handling
- **Parallel**: false
- Read-only semantic review of the dev-shortlink diff (correctness, task alignment, tag/stack fit, quality, test sanity).
- Return a structured PASS/FAIL verdict; on FAIL dev-shortlink fixes the flagged issues and review re-runs.

### 9. Final Validation
- **Task ID**: validate-all
- **Depends On**: scaffold-project, domain-repository, codegen-validation, service-layer, web-layer, observability-readme, unit-tests, code-review
- **Assigned To**: validator-shortlink
- **Agent Type**: validator
- **Stack**: Java Spring Boot JUnit surefire
- **Parallel**: false
- Run all validation commands.
- For the (non-Skipped) Unit Layer in `## Test Infrastructure (User-Declared)`, execute the declared `Runner command` (`./mvnw test`) verbatim and verify that **tests actually ran** (parse Surefire output for "Tests run: N" — N must be ≥ the 6 declared unit scenarios).
- Run `check_test_layers.py` post-build hook (also covered by `/smart_build` Step 4.5, but verify here too).
- Verify acceptance criteria met.

## Acceptance Criteria

Traceability to the increment's 18 criteria is noted in parentheses.

1. `POST /api/links` with a valid `http`/`https` URL returns **201** with body `{ code, shortUrl, url, createdAt }` (incr. 1).
2. `POST /api/links` without `customCode` returns a base62 code of length 6–7 (incr. 2).
3. `POST /api/links` with a free, valid `customCode` returns **201** with `code` == the supplied value (incr. 3).
4. `POST /api/links` with a taken `customCode` returns **409**, leaving the existing link and its counter unchanged (incr. 4, 17).
5. `POST /api/links` with an invalid `customCode` (fails `^[a-z0-9-]{3,50}$`) returns **400** (incr. 16).
6. `POST /api/links` with an invalid URL (empty, garbage, non-http/https) returns **400**; the service stays up (incr. 5).
7. `POST /api/links` with a URL longer than 2048 chars returns **400** (incr. 18).
8. `GET /r/{code}` for an active link returns **302** with the correct `Location` header (incr. 6).
9. `GET /r/{code}` increments the click counter atomically — 100 concurrent requests yield exactly 100 (incr. 7).
10. `GET /r/{code}` for an unknown code returns **404** (incr. 8).
11. `GET /r/{code}` for an expired link returns **410** and does not increment the counter (incr. 9).
12. `GET /api/links/{code}` returns **200** with `{ code, url, shortUrl, clickCount, createdAt, lastClickedAt (null if never clicked), expiresAt (null if unset) }` (incr. 10).
13. Storage is in-memory; links/counters reset on restart (incr. 11) — verified by repository unit tests, no persistence dependency.
14. `GET /actuator/health` returns **200**/UP — actuator configured (incr. 12); endpoint-over-HTTP assertion deferred to Test scope.
15. Structured logs exist for link creation, redirects, and errors (incr. 13).
16. `README.md` documents build, run, and API examples for all three endpoints (incr. 14).
17. Unit tests cover service logic, validation, code generation, repository concurrency, and exception→status mapping; `./mvnw test` is green (unit portion of incr. 15; integration portion deferred to Test scope).
18. All typed error paths map to the correct status via `GlobalExceptionHandler`: 400/404/409/410, never an unhandled 500 (incr. 5, 8, 9, 16).

## Validation Commands

Execute these commands to validate the task is complete:

- `./mvnw -q -DskipTests compile` — product code compiles.
- `./mvnw test` — runs the unit suite; Surefire must report `Tests run: N, Failures: 0, Errors: 0` with N ≥ 6.
- `uv run --script .claude/hooks/validators/check_test_layers.py --plan specs/shortlink-service.md` — declared Unit Layer matches what was built.
- `./mvnw spring-boot:run` then `curl -i -X POST localhost:8080/api/links -H 'Content-Type: application/json' -d '{"url":"https://example.com"}'` — manual smoke (returns 201); `curl -i localhost:8080/r/<code>` returns 302; `curl -i localhost:8080/actuator/health` returns 200.

## Notes

- **Java 21 + Maven 3.9.16** are present on this machine; the plan targets Spring Boot 3.3.x. The Maven wrapper is generated in `scaffold-project` so the declared runner `./mvnw test` is portable.
- **Storage is intentionally in-memory** (`ConcurrentHashMap`) — the customer explicitly accepted data loss on restart. Do not add a database or persistence layer.
- **No authentication** by design — security is via network isolation. Do not add Spring Security.
- **No Dockerfile** in this increment (explicitly out of scope).
- Greenfield repo: `explorer`/`module-map.md` was skipped (no existing modules to tag); Stack fields use Java/Spring catalog keywords directly.
- Validation logic is deliberately service-side (not only DTO annotations) so every acceptance criterion is reachable by pure unit tests; the framework-level `@Valid` triggering of 400 is additionally exercised in Test scope.
