package com.example.shortlink.exception;

/** Некорректный URL при создании ссылки → HTTP 400. */
public class InvalidUrlException extends RuntimeException {

  /**
   * @param message читаемое сообщение об ошибке
   */
  public InvalidUrlException(final String message) {
    super(message);
  }
}
