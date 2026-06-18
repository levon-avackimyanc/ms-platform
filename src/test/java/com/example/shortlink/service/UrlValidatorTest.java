package com.example.shortlink.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortlink.exception.InvalidCustomCodeException;
import com.example.shortlink.exception.InvalidUrlException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit-тесты валидатора URL и кастомных кодов {@link UrlValidator}. */
class UrlValidatorTest {

  /** Тестируемый валидатор. */
  private final UrlValidator validator = new UrlValidator();

  @Nested
  @DisplayName("validateUrl()")
  class ValidateUrl {

    @Test
    @DisplayName("acceptsValidHttpUrl")
    void validateUrl_withValidHttpUrl_doesNotThrow() {
      // given
      final String url = "http://example.com/path?q=1";

      // when / then
      assertThatCode(() -> validator.validateUrl(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("acceptsValidHttpsUrl")
    void validateUrl_withValidHttpsUrl_doesNotThrow() {
      // given
      final String url = "https://example.com";

      // when / then
      assertThatCode(() -> validator.validateUrl(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejectsNonHttpSchemeWith400")
    void rejectsNonHttpSchemeWith400() {
      // given — ftp scheme
      final String ftpUrl = "ftp://files.example.com/file.txt";

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(ftpUrl))
          .isInstanceOf(InvalidUrlException.class)
          .hasMessageContaining("http");
    }

    @Test
    @DisplayName("rejectsJavascriptScheme")
    void validateUrl_withJavascriptScheme_throwsInvalidUrlException() {
      // given
      final String jsUrl = "javascript:alert('xss')";

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(jsUrl))
          .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejectsBlankUrl")
    void validateUrl_withBlankUrl_throwsInvalidUrlException() {
      // given
      final String blank = "   ";

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(blank))
          .isInstanceOf(InvalidUrlException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejectsNullUrl")
    void validateUrl_withNullUrl_throwsInvalidUrlException() {
      // given
      final String nullUrl = null;

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(nullUrl))
          .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejectsGarbageString")
    void validateUrl_withGarbageString_throwsInvalidUrlException() {
      // given
      final String garbage = "not-a-url-at-all$$$$";

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(garbage))
          .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejectsUrlLongerThan2048Characters")
    void validateUrl_withUrlLongerThan2048Chars_throwsInvalidUrlException() {
      // given — build a URL that exceeds 2048 chars
      final String longUrl = "https://example.com/" + "a".repeat(2048);

      // when / then
      assertThatThrownBy(() -> validator.validateUrl(longUrl))
          .isInstanceOf(InvalidUrlException.class)
          .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("acceptsUrlOfExactly2048Characters")
    void validateUrl_withUrlOfExactly2048Chars_doesNotThrow() {
      // given — "https://x.com/" is 15 chars; pad path to reach exactly 2048
      final String base = "https://x.com/";
      final String url = base + "a".repeat(2048 - base.length());

      // when / then
      assertThatCode(() -> validator.validateUrl(url)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("validateCustomCode()")
  class ValidateCustomCode {

    @Test
    @DisplayName("acceptsValidKebabCode")
    void validateCustomCode_withValidKebabCode_doesNotThrow() {
      // given
      final String code = "black-friday";

      // when / then
      assertThatCode(() -> validator.validateCustomCode(code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("acceptsLowercaseAlphanumericCode")
    void validateCustomCode_withLowercaseAlphanumeric_doesNotThrow() {
      // given
      final String code = "sale2024";

      // when / then
      assertThatCode(() -> validator.validateCustomCode(code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejectsCodeWithUppercase")
    void validateCustomCode_withUppercaseLetter_throwsInvalidCustomCodeException() {
      // given
      final String code = "Black-Friday";

      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(code))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("rejectsCodeWithSpaces")
    void validateCustomCode_withSpaces_throwsInvalidCustomCodeException() {
      // given
      final String code = "black friday";

      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(code))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("rejectsCodeWithSlash")
    void validateCustomCode_withSlash_throwsInvalidCustomCodeException() {
      // given
      final String code = "black/friday";

      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(code))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("rejectsTooShortCode")
    void validateCustomCode_withTwoCharCode_throwsInvalidCustomCodeException() {
      // given — length 2, below minimum of 3
      final String code = "ab";

      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(code))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("rejectsTooLongCode")
    void validateCustomCode_with51CharCode_throwsInvalidCustomCodeException() {
      // given — length 51, above maximum of 50
      final String code = "a".repeat(51);

      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(code))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("rejectsNullCode")
    void validateCustomCode_withNull_throwsInvalidCustomCodeException() {
      // when / then
      assertThatThrownBy(() -> validator.validateCustomCode(null))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("acceptsMinimumLengthCode")
    void validateCustomCode_withThreeCharCode_doesNotThrow() {
      // given — exactly at minimum length 3
      final String code = "abc";

      // when / then
      assertThatCode(() -> validator.validateCustomCode(code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("acceptsMaximumLengthCode")
    void validateCustomCode_with50CharCode_doesNotThrow() {
      // given — exactly at maximum length 50
      final String code = "a".repeat(50);

      // when / then
      assertThatCode(() -> validator.validateCustomCode(code)).doesNotThrowAnyException();
    }
  }
}
