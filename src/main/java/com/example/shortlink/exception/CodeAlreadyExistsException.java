package com.example.shortlink.exception;

/** Кастомный код синтаксически валиден, но уже занят → HTTP 409. */
public class CodeAlreadyExistsException extends RuntimeException {

  /**
   * @param message читаемое сообщение об ошибке
   */
  public CodeAlreadyExistsException(final String message) {
    super(message);
  }
}
