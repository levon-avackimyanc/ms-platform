package com.example.shortlink.service;

import com.example.shortlink.config.AppProperties;
import com.example.shortlink.domain.ShortLink;
import com.example.shortlink.exception.CodeAlreadyExistsException;
import com.example.shortlink.exception.LinkExpiredException;
import com.example.shortlink.exception.LinkNotFoundException;
import com.example.shortlink.repository.LinkRepository;
import com.example.shortlink.web.dto.CreateLinkRequest;
import com.example.shortlink.web.dto.LinkResponse;
import com.example.shortlink.web.dto.LinkStatsResponse;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/** Бизнес-логика создания ссылок, редиректа и выдачи статистики. */
@Service
public class LinkService {

  /** Логгер. */
  private static final Logger log = LoggerFactory.getLogger(LinkService.class);

  /** Префикс пути редиректа. */
  private static final String REDIRECT_PREFIX = "/r/";

  /** Максимальное число попыток сгенерировать свободный случайный код. */
  private static final int MAX_GENERATION_ATTEMPTS = 5;

  /** Хранилище ссылок. */
  private final LinkRepository repository;

  /** Генератор случайных кодов. */
  private final CodeGenerator codeGenerator;

  /** Валидатор URL и кастомных кодов. */
  private final UrlValidator urlValidator;

  /** Конфигурация приложения (base-url). */
  private final AppProperties appProperties;

  /** Источник времени (для тестируемости). */
  private final Clock clock;

  /**
   * Создаёт сервис.
   *
   * @param repository хранилище ссылок
   * @param codeGenerator генератор случайных кодов
   * @param urlValidator валидатор URL и кодов
   * @param appProperties конфигурация (base-url)
   * @param clock источник времени
   */
  public LinkService(
      final LinkRepository repository,
      final CodeGenerator codeGenerator,
      final UrlValidator urlValidator,
      final AppProperties appProperties,
      final Clock clock) {
    this.repository = repository;
    this.codeGenerator = codeGenerator;
    this.urlValidator = urlValidator;
    this.appProperties = appProperties;
    this.clock = clock;
  }

  /**
   * Создаёт короткую ссылку: валидирует URL, выбирает или генерирует код, сохраняет атомарно.
   *
   * @param request запрос на создание
   * @return ответ с данными созданной ссылки
   * @throws com.example.shortlink.exception.InvalidUrlException если URL некорректен (400)
   * @throws com.example.shortlink.exception.InvalidCustomCodeException если код не валиден (400)
   * @throws CodeAlreadyExistsException если кастомный код уже занят (409)
   */
  public LinkResponse create(final CreateLinkRequest request) {
    Assert.notNull(request, "request cannot be null");
    urlValidator.validateUrl(request.url());

    final ShortLink saved =
        hasCustomCode(request) ? createWithCustomCode(request) : createWithGeneratedCode(request);

    log.info(
        "event=link_created code={} url={} expiresAt={}",
        saved.getCode(),
        saved.getOriginalUrl(),
        saved.getExpiresAt());
    return toLinkResponse(saved);
  }

  /**
   * Разрешает код для редиректа: проверяет существование и срок действия, атомарно учитывает
   * переход.
   *
   * @param code короткий код
   * @return оригинальный URL для заголовка Location
   * @throws LinkNotFoundException если код не найден (404)
   * @throws LinkExpiredException если ссылка просрочена (410) — переход не засчитывается
   */
  public String resolveForRedirect(final String code) {
    Assert.hasText(code, "code cannot be blank");

    final ShortLink link = findOrThrow(code);
    final Instant now = clock.instant();
    requireActive(link, now);

    link.recordClick(now);
    log.info("event=redirect code={} target={}", code, link.getOriginalUrl());
    return link.getOriginalUrl();
  }

  /**
   * Возвращает статистику по ссылке.
   *
   * @param code короткий код
   * @return статистика ссылки
   * @throws LinkNotFoundException если код не найден (404)
   */
  public LinkStatsResponse getStats(final String code) {
    Assert.hasText(code, "code cannot be blank");

    final ShortLink link = findOrThrow(code);
    return toStatsResponse(link);
  }

  /** Проверяет, передан ли непустой кастомный код. */
  private boolean hasCustomCode(final CreateLinkRequest request) {
    return request.customCode() != null && !request.customCode().isBlank();
  }

  /** Создаёт ссылку с кастомным кодом: валидирует код и сохраняет с проверкой занятости. */
  private ShortLink createWithCustomCode(final CreateLinkRequest request) {
    final String code = request.customCode();
    urlValidator.validateCustomCode(code);

    final ShortLink link = newLink(code, request);
    if (!repository.saveIfAbsent(link)) {
      throw new CodeAlreadyExistsException("code already exists: " + code);
    }
    return link;
  }

  /** Создаёт ссылку со случайным кодом, повторяя генерацию при коллизии. */
  private ShortLink createWithGeneratedCode(final CreateLinkRequest request) {
    for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
      final ShortLink link = newLink(codeGenerator.generate(), request);
      if (repository.saveIfAbsent(link)) {
        return link;
      }
      log.warn("event=code_collision attempt={} code={}", attempt, link.getCode());
    }
    throw new CodeAlreadyExistsException("failed to generate a free code");
  }

  /** Конструирует доменную ссылку из запроса. */
  private ShortLink newLink(final String code, final CreateLinkRequest request) {
    return new ShortLink(code, request.url(), clock.instant(), request.expiresAt());
  }

  /** Находит ссылку или бросает 404. */
  private ShortLink findOrThrow(final String code) {
    return repository
        .findByCode(code)
        .orElseThrow(() -> new LinkNotFoundException("link not found: " + code));
  }

  /** Проверяет, что ссылка не просрочена, иначе бросает 410. */
  private void requireActive(final ShortLink link, final Instant now) {
    if (link.isExpired(now)) {
      throw new LinkExpiredException("link expired: " + link.getCode());
    }
  }

  /** Строит полную короткую ссылку. */
  private String buildShortUrl(final String code) {
    return appProperties.baseUrl() + REDIRECT_PREFIX + code;
  }

  /** Маппит домен в ответ создания. */
  private LinkResponse toLinkResponse(final ShortLink link) {
    return new LinkResponse(
        link.getCode(),
        buildShortUrl(link.getCode()),
        link.getOriginalUrl(),
        link.getCreatedAt(),
        link.getExpiresAt());
  }

  /** Маппит домен в ответ статистики. */
  private LinkStatsResponse toStatsResponse(final ShortLink link) {
    return new LinkStatsResponse(
        link.getCode(),
        link.getOriginalUrl(),
        buildShortUrl(link.getCode()),
        link.getClickCount(),
        link.getCreatedAt(),
        link.getLastClickedAt(),
        link.getExpiresAt());
  }
}
