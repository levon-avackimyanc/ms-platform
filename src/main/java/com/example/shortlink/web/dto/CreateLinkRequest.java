package com.example.shortlink.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Запрос на создание короткой ссылки.
 *
 * <p>Bean-validation аннотации — defense-in-depth; авторитетные проверки выполняются в сервисе
 * ({@code UrlValidator}).
 *
 * @param url оригинальный URL (обязателен, http/https, длина ≤ 2048)
 * @param customCode опциональный кастомный код ({@code ^[a-z0-9-]{3,50}$})
 * @param expiresAt опциональный момент истечения (TTL)
 */
public record CreateLinkRequest(
    @NotBlank @Size(max = 2048) String url,
    @Size(min = 3, max = 50) String customCode,
    Instant expiresAt) {}
