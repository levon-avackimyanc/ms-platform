package com.example.shortlink.web.error;

import com.example.shortlink.exception.CodeAlreadyExistsException;
import com.example.shortlink.exception.InvalidCustomCodeException;
import com.example.shortlink.exception.InvalidUrlException;
import com.example.shortlink.exception.LinkExpiredException;
import com.example.shortlink.exception.LinkNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Глобальный обработчик исключений.
 *
 * <p>Гарантирует, что любой предсказуемый некорректный запрос маппится в 400/404/409/410 с единым
 * телом {@link ErrorResponse} и никогда не приводит к необработанному 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** Логгер. */
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Некорректный URL → 400. */
  @ExceptionHandler(InvalidUrlException.class)
  public ResponseEntity<ErrorResponse> handleInvalidUrl(final InvalidUrlException e) {
    return build(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** Некорректный кастомный код → 400. */
  @ExceptionHandler(InvalidCustomCodeException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCustomCode(final InvalidCustomCodeException e) {
    return build(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** Кастомный код уже занят → 409. */
  @ExceptionHandler(CodeAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleCodeAlreadyExists(final CodeAlreadyExistsException e) {
    return build(HttpStatus.CONFLICT, e.getMessage());
  }

  /** Ссылка не найдена → 404. */
  @ExceptionHandler(LinkNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(final LinkNotFoundException e) {
    return build(HttpStatus.NOT_FOUND, e.getMessage());
  }

  /** Ссылка просрочена → 410. */
  @ExceptionHandler(LinkExpiredException.class)
  public ResponseEntity<ErrorResponse> handleExpired(final LinkExpiredException e) {
    return build(HttpStatus.GONE, e.getMessage());
  }

  /** Bean-validation тела запроса (@Valid) → 400. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
      final MethodArgumentNotValidException e) {
    final String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::formatFieldError)
            .collect(Collectors.joining(", "));
    return build(HttpStatus.BAD_REQUEST, message.isBlank() ? "validation failed" : message);
  }

  /** Bean-validation параметров (@Validated) → 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      final ConstraintViolationException e) {
    return build(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** Невозможность прочитать тело запроса (битый JSON и т.п.) → 400. */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(
      final org.springframework.http.converter.HttpMessageNotReadableException e) {
    return build(HttpStatus.BAD_REQUEST, "malformed request body");
  }

  /** Форматирует ошибку поля как "field: message". */
  private static String formatFieldError(final FieldError fieldError) {
    return fieldError.getField() + ": " + fieldError.getDefaultMessage();
  }

  /** Логирует и собирает единый ответ об ошибке. */
  private ResponseEntity<ErrorResponse> build(final HttpStatus status, final String message) {
    log.warn("event=error status={} message={}", status.value(), message);
    final ErrorResponse body =
        new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message);
    return ResponseEntity.status(status).body(body);
  }
}
