package com.example.shortlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Unit-тесты генератора случайных кодов {@link CodeGenerator}. */
class CodeGeneratorTest {

  /** Тестируемый генератор. */
  private final CodeGenerator generator = new CodeGenerator();

  /** Алфавит base62: цифры + заглавные + строчные буквы. */
  private static final String BASE62_ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  @Nested
  @DisplayName("generate()")
  class Generate {

    @Test
    @DisplayName("generatesBase62CodeOfExpectedLength")
    void generatesBase62CodeOfExpectedLength() {
      // when
      final String code = generator.generate();

      // then
      assertThat(code).hasSizeBetween(6, 7);
    }

    @RepeatedTest(20)
    @DisplayName("generatedCodeContainsOnlyBase62Characters")
    void generate_repeatedly_producesOnlyBase62Alphabet() {
      // when
      final String code = generator.generate();

      // then
      assertThat(code).isNotBlank().matches("[0-9A-Za-z]+");
    }

    @RepeatedTest(20)
    @DisplayName("eachCharacterIsInBase62Alphabet")
    void generate_eachCharacter_isInBase62Alphabet() {
      // when
      final String code = generator.generate();

      // then
      for (final char ch : code.toCharArray()) {
        assertThat(BASE62_ALPHABET).contains(String.valueOf(ch));
      }
    }

    @Test
    @DisplayName("successiveCodesAreDifferent")
    void generate_calledTwice_producesDifferentCodes() {
      // when
      final String first = generator.generate();
      final String second = generator.generate();

      // then — extremely unlikely to be equal with 62^6 ≈ 56 billion possibilities
      assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("codeLengthIsEitherSixOrSeven")
    void generate_multipleTimes_producesLengthInRange() {
      // when / then — run 50 times to exercise both length paths
      for (int i = 0; i < 50; i++) {
        final String code = generator.generate();
        assertThat(code.length()).isBetween(6, 7);
      }
    }
  }
}
