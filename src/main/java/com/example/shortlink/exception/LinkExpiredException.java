package com.example.shortlink.exception;

/** Срок действия ссылки истёк → HTTP 410. */
public class LinkExpiredException extends RuntimeException {

  /**
   * @param message читаемое сообщение об ошибке
   */
  public LinkExpiredException(final String message) {
    super(message);
  }
}
