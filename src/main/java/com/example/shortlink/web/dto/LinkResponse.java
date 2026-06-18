package com.example.shortlink.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Ответ на создание короткой ссылки.
 *
 * @param code короткий код
 * @param shortUrl полная короткая ссылка (base-url + /r/ + code)
 * @param url оригинальный URL
 * @param createdAt момент создания
 * @param expiresAt момент истечения или null, если не задан (поле опускается в JSON, если null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkResponse(
    String code, String shortUrl, String url, Instant createdAt, Instant expiresAt) {}
