package com.example.shortlink.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortlink.domain.ShortLink;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit-тесты in-memory репозитория {@link InMemoryLinkRepository}. */
class InMemoryLinkRepositoryTest {

  /** Тестируемый репозиторий. */
  private InMemoryLinkRepository repository;

  @BeforeEach
  void setUp() {
    repository = new InMemoryLinkRepository();
  }

  /** Создаёт тестовую ссылку с заданным кодом. */
  private ShortLink link(final String code) {
    return new ShortLink(code, "https://example.com", Instant.now(), null);
  }

  @Nested
  @DisplayName("saveIfAbsent()")
  class SaveIfAbsent {

    @Test
    @DisplayName("returnsTrueAndSavesWhenCodeIsFree")
    void saveIfAbsent_whenCodeIsFree_returnsTrueAndSaves() {
      // given
      final ShortLink link = link("free-code");

      // when
      final boolean result = repository.saveIfAbsent(link);

      // then
      assertThat(result).isTrue();
      assertThat(repository.findByCode("free-code")).isPresent();
    }

    @Test
    @DisplayName("returnsFalseWhenCodeIsTaken")
    void saveIfAbsent_whenCodeIsTaken_returnsFalse() {
      // given
      final ShortLink original = link("taken-code");
      repository.saveIfAbsent(original);
      final ShortLink duplicate =
          new ShortLink("taken-code", "https://other.com", Instant.now(), null);

      // when
      final boolean result = repository.saveIfAbsent(duplicate);

      // then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("doesNotOverwriteExistingLinkWhenCodeIsTaken")
    void saveIfAbsent_whenCodeIsTaken_doesNotOverwriteExistingLink() {
      // given
      final ShortLink original = link("taken-code");
      repository.saveIfAbsent(original);
      final ShortLink duplicate =
          new ShortLink("taken-code", "https://different-url.com", Instant.now(), null);

      // when
      repository.saveIfAbsent(duplicate);

      // then — original URL is preserved
      final Optional<ShortLink> found = repository.findByCode("taken-code");
      assertThat(found).isPresent();
      assertThat(found.get().getOriginalUrl()).isEqualTo("https://example.com");
    }
  }

  @Nested
  @DisplayName("findByCode()")
  class FindByCode {

    @Test
    @DisplayName("returnsLinkWhenCodeExists")
    void findByCode_whenCodeExists_returnsLink() {
      // given
      final ShortLink link = link("existing-code");
      repository.saveIfAbsent(link);

      // when
      final Optional<ShortLink> found = repository.findByCode("existing-code");

      // then
      assertThat(found).isPresent();
      assertThat(found.get().getCode()).isEqualTo("existing-code");
    }

    @Test
    @DisplayName("returnsEmptyWhenCodeDoesNotExist")
    void findByCode_whenCodeDoesNotExist_returnsEmpty() {
      // when
      final Optional<ShortLink> found = repository.findByCode("nonexistent");

      // then
      assertThat(found).isEmpty();
    }
  }
}
