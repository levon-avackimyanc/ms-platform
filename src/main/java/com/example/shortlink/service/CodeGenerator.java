package com.example.shortlink.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Генератор случайных base62-кодов для коротких ссылок.
 *
 * <p>Алфавит — {@code [A-Za-z0-9]}; длина 6–7 символов; источник случайности — {@link
 * SecureRandom}.
 */
@Component
public class CodeGenerator {

  /** Алфавит base62: цифры, заглавные и строчные латинские буквы. */
  private static final char[] ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

  /** Минимальная длина генерируемого кода. */
  private static final int MIN_LENGTH = 6;

  /** Максимальная длина генерируемого кода. */
  private static final int MAX_LENGTH = 7;

  /** Источник криптографически стойкой случайности. */
  private final SecureRandom random = new SecureRandom();

  /**
   * Генерирует случайный base62-код длиной 6 или 7 символов.
   *
   * @return сгенерированный код
   */
  public String generate() {
    final int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
    return buildCode(length);
  }

  /** Собирает код заданной длины из символов алфавита. */
  private String buildCode(final int length) {
    Assert.isTrue(length > 0, "length must be positive");

    final StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return builder.toString();
  }
}
