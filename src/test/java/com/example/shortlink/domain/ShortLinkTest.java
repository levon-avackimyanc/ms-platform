package com.example.shortlink.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit-тесты доменной сущности {@link ShortLink}. */
class ShortLinkTest {

  /** Вспомогательный метод создания ссылки с заданным expiresAt. */
  private ShortLink link(final Instant expiresAt) {
    return new ShortLink("abc123", "https://example.com", Instant.now(), expiresAt);
  }

  @Nested
  @DisplayName("recordClick()")
  class RecordClick {

    @Test
    @DisplayName("incrementsClickCount")
    void recordClick_withValidInstant_incrementsClickCount() {
      // given
      final ShortLink shortLink = link(null);
      final Instant clickedAt = Instant.now();

      // when
      shortLink.recordClick(clickedAt);

      // then
      assertThat(shortLink.getClickCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("setsLastClickedAt")
    void recordClick_withValidInstant_setsLastClickedAt() {
      // given
      final ShortLink shortLink = link(null);
      final Instant clickedAt = Instant.parse("2026-01-01T12:00:00Z");

      // when
      shortLink.recordClick(clickedAt);

      // then
      assertThat(shortLink.getLastClickedAt()).isEqualTo(clickedAt);
    }

    @Test
    @DisplayName("incrementsCountMultipleTimes")
    void recordClick_calledMultipleTimes_accumulatesCount() {
      // given
      final ShortLink shortLink = link(null);

      // when
      shortLink.recordClick(Instant.now());
      shortLink.recordClick(Instant.now());
      shortLink.recordClick(Instant.now());

      // then
      assertThat(shortLink.getClickCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("lastClickedAtIsNullBeforeAnyClick")
    void recordClick_whenNeverCalled_lastClickedAtIsNull() {
      // given
      final ShortLink shortLink = link(null);

      // when / then
      assertThat(shortLink.getLastClickedAt()).isNull();
    }
  }

  @Nested
  @DisplayName("isExpired()")
  class IsExpired {

    @Test
    @DisplayName("returnsFalseWhenExpiresAtIsNull")
    void isExpired_whenNullExpiresAt_returnsFalse() {
      // given
      final ShortLink shortLink = link(null);
      final Instant now = Instant.now();

      // when
      final boolean expired = shortLink.isExpired(now);

      // then
      assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("returnsTrueWhenExpiresAtIsInThePast")
    void isExpired_whenExpiresAtIsInThePast_returnsTrue() {
      // given
      final Instant pastExpiry = Instant.parse("2020-01-01T00:00:00Z");
      final ShortLink shortLink = link(pastExpiry);
      final Instant now = Instant.parse("2026-06-18T00:00:00Z");

      // when
      final boolean expired = shortLink.isExpired(now);

      // then
      assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("returnsTrueWhenNowEqualsExpiresAt")
    void isExpired_whenNowEqualsExpiresAt_returnsTrue() {
      // given
      final Instant expiry = Instant.parse("2026-01-01T00:00:00Z");
      final ShortLink shortLink = link(expiry);

      // when
      final boolean expired = shortLink.isExpired(expiry);

      // then
      assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("returnsFalseWhenExpiresAtIsInTheFuture")
    void isExpired_whenExpiresAtIsInTheFuture_returnsFalse() {
      // given
      final Instant futureExpiry = Instant.parse("2099-01-01T00:00:00Z");
      final ShortLink shortLink = link(futureExpiry);
      final Instant now = Instant.parse("2026-06-18T00:00:00Z");

      // when
      final boolean expired = shortLink.isExpired(now);

      // then
      assertThat(expired).isFalse();
    }
  }
}
