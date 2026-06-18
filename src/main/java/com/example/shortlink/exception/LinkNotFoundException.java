package com.example.shortlink.exception;

/** Ссылка с указанным кодом не найдена → HTTP 404. */
public class LinkNotFoundException extends RuntimeException {

  /**
   * @param message читаемое сообщение об ошибке
   */
  public LinkNotFoundException(final String message) {
    super(message);
  }
}
