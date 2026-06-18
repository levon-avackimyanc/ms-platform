package com.example.shortlink.web.dto;

import java.time.Instant;

/**
 * Статистика по короткой ссылке.
 *
 * @param code короткий код
 * @param url оригинальный URL
 * @param shortUrl полная короткая ссылка
 * @param clickCount число переходов
 * @param createdAt момент создания
 * @param lastClickedAt момент последнего перехода или null, если переходов ещё не было
 * @param expiresAt момент истечения или null, если не задан
 */
public record LinkStatsResponse(
    String code,
    String url,
    String shortUrl,
    long clickCount,
    Instant createdAt,
    Instant lastClickedAt,
    Instant expiresAt) {}
