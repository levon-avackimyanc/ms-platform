package com.example.shortlink.web.error;

import java.time.Instant;

/**
 * Единый формат тела ответа об ошибке.
 *
 * @param timestamp момент возникновения ошибки
 * @param status HTTP-статус
 * @param error человекочитаемое название статуса (reason phrase)
 * @param message детальное сообщение об ошибке
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message) {}
