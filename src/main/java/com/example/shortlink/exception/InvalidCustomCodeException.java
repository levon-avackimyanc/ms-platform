package com.example.shortlink.exception;

/** Кастомный код не соответствует паттерну {@code ^[a-z0-9-]{3,50}$} → HTTP 400. */
public class InvalidCustomCodeException extends RuntimeException {

  /**
   * @param message читаемое сообщение об ошибке
   */
  public InvalidCustomCodeException(final String message) {
    super(message);
  }
}
