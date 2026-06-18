package com.example.shortlink.domain;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.Assert;

/**
 * Доменная сущность короткой ссылки.
 *
 * <p>Хранится in-memory. Счётчик переходов потокобезопасен ({@link AtomicLong}); время последнего
 * перехода обновляется по принципу last-writer-wins через volatile-поле.
 */
public final class ShortLink {

  /** Короткий код ссылки (path-сегмент после /r/). */
  private final String code;

  /** Оригинальный (длинный) URL, на который ведёт редирект. */
  private final String originalUrl;

  /** Момент создания ссылки. */
  private final Instant createdAt;

  /** Момент истечения срока действия (TTL); null — ссылка не истекает. */
  private final Instant expiresAt;

  /** Потокобезопасный счётчик переходов. */
  private final AtomicLong clickCount = new AtomicLong(0);

  /** Момент последнего успешного перехода; null — переходов ещё не было. */
  private volatile Instant lastClickedAt;

  /**
   * Создаёт короткую ссылку.
   *
   * @param code короткий код (не пустой)
   * @param originalUrl оригинальный URL (не пустой)
   * @param createdAt момент создания (не null)
   * @param expiresAt момент истечения или null, если ссылка бессрочная
   */
  public ShortLink(
      final String code,
      final String originalUrl,
      final Instant createdAt,
      final Instant expiresAt) {
    Assert.hasText(code, "code cannot be blank");
    Assert.hasText(originalUrl, "originalUrl cannot be blank");
    Assert.notNull(createdAt, "createdAt cannot be null");

    this.code = code;
    this.originalUrl = originalUrl;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  /**
   * Атомарно регистрирует переход: увеличивает счётчик и обновляет время последнего перехода.
   *
   * @param clickedAt момент перехода (не null)
   */
  public void recordClick(final Instant clickedAt) {
    Assert.notNull(clickedAt, "clickedAt cannot be null");

    clickCount.incrementAndGet();
    this.lastClickedAt = clickedAt;
  }

  /**
   * Проверяет, истёк ли срок действия ссылки на указанный момент времени.
   *
   * @param now момент, относительно которого проверяется истечение (не null)
   * @return true, если задан expiresAt и он не позже now; false, если ссылка бессрочная или ещё
   *     активна
   */
  public boolean isExpired(final Instant now) {
    Assert.notNull(now, "now cannot be null");

    return expiresAt != null && !now.isBefore(expiresAt);
  }

  /** Возвращает короткий код ссылки. */
  public String getCode() {
    return code;
  }

  /** Возвращает оригинальный URL. */
  public String getOriginalUrl() {
    return originalUrl;
  }

  /** Возвращает момент создания. */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Возвращает момент истечения (или null). */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  /** Возвращает текущее число переходов. */
  public long getClickCount() {
    return clickCount.get();
  }

  /** Возвращает момент последнего перехода (или null, если переходов не было). */
  public Instant getLastClickedAt() {
    return lastClickedAt;
  }
}
