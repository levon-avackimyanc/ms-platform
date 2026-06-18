package com.example.shortlink.service;

import com.example.shortlink.exception.InvalidCustomCodeException;
import com.example.shortlink.exception.InvalidUrlException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Валидатор входных данных создания ссылки.
 *
 * <p>Проверки авторитетны и выполняются на уровне сервиса (а не только bean-validation), чтобы быть
 * полностью покрываемыми unit-тестами без сервлет-стека. При нарушении бросаются типизированные
 * исключения, которые маппятся в HTTP 400.
 */
@Component
public class UrlValidator {

  /** Допустимые схемы URL. */
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  /** Максимально допустимая длина URL. */
  private static final int MAX_URL_LENGTH = 2048;

  /** Паттерн допустимого кастомного кода: строчные латинские буквы, цифры, дефис; длина 3–50. */
  private static final Pattern CUSTOM_CODE_PATTERN = Pattern.compile("^[a-z0-9-]{3,50}$");

  /**
   * Проверяет, что URL — корректный http/https и не длиннее 2048 символов.
   *
   * @param url проверяемый URL
   * @throws InvalidUrlException если URL пустой, нечитаемый, не http/https или слишком длинный
   */
  public void validateUrl(final String url) {
    requireNonBlankUrl(url);
    requireWithinMaxLength(url);
    requireHttpScheme(url);
  }

  /**
   * Проверяет кастомный код по паттерну {@code ^[a-z0-9-]{3,50}$}.
   *
   * @param code проверяемый код
   * @throws InvalidCustomCodeException если код не соответствует паттерну
   */
  public void validateCustomCode(final String code) {
    if (code == null || !CUSTOM_CODE_PATTERN.matcher(code).matches()) {
      throw new InvalidCustomCodeException(
          "customCode must match pattern ^[a-z0-9-]{3,50}$: " + code);
    }
  }

  /** Проверяет, что URL не пустой. */
  private void requireNonBlankUrl(final String url) {
    if (url == null || url.isBlank()) {
      throw new InvalidUrlException("url must not be blank");
    }
  }

  /** Проверяет, что URL не превышает максимальную длину. */
  private void requireWithinMaxLength(final String url) {
    if (url.length() > MAX_URL_LENGTH) {
      throw new InvalidUrlException("url must not exceed " + MAX_URL_LENGTH + " characters");
    }
  }

  /** Проверяет, что URL парсится и использует схему http/https. */
  private void requireHttpScheme(final String url) {
    final String scheme = parseScheme(url);
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new InvalidUrlException("url must use http or https scheme: " + url);
    }
  }

  /** Извлекает схему URL; возвращает null, если URL не парсится или схема отсутствует. */
  private String parseScheme(final String url) {
    try {
      final URI uri = new URI(url);
      return uri.getScheme();
    } catch (final URISyntaxException e) {
      throw new InvalidUrlException("url is not a valid URI: " + url);
    }
  }
}
