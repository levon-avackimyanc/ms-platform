package com.example.shortlink.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortlink.exception.CodeAlreadyExistsException;
import com.example.shortlink.exception.InvalidCustomCodeException;
import com.example.shortlink.exception.InvalidUrlException;
import com.example.shortlink.exception.LinkExpiredException;
import com.example.shortlink.exception.LinkNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit-тесты обработчика исключений {@link GlobalExceptionHandler}. */
class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
  }

  @Nested
  @DisplayName("handleInvalidUrl()")
  class HandleInvalidUrl {

    @Test
    @DisplayName("mapsInvalidUrlTo400")
    void handleInvalidUrl_returnsHttpStatus400WithNonEmptyMessage() {
      // given
      final InvalidUrlException exception = new InvalidUrlException("url is invalid");

      // when
      final ResponseEntity<ErrorResponse> response = handler.handleInvalidUrl(exception);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().message()).isNotBlank();
      assertThat(response.getBody().status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
  }

  @Nested
  @DisplayName("handleInvalidCustomCode()")
  class HandleInvalidCustomCode {

    @Test
    @DisplayName("mapsInvalidCustomCodeTo400")
    void handleInvalidCustomCode_returnsHttpStatus400WithNonEmptyMessage() {
      // given
      final InvalidCustomCodeException exception =
          new InvalidCustomCodeException("custom code is invalid");

      // when
      final ResponseEntity<ErrorResponse> response = handler.handleInvalidCustomCode(exception);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().message()).isNotBlank();
      assertThat(response.getBody().status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
  }

  @Nested
  @DisplayName("handleCodeAlreadyExists()")
  class HandleCodeAlreadyExists {

    @Test
    @DisplayName("mapsCodeAlreadyExistsTo409")
    void handleCodeAlreadyExists_returnsHttpStatus409WithNonEmptyMessage() {
      // given
      final CodeAlreadyExistsException exception = new CodeAlreadyExistsException("code taken");

      // when
      final ResponseEntity<ErrorResponse> response = handler.handleCodeAlreadyExists(exception);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().message()).isNotBlank();
      assertThat(response.getBody().status()).isEqualTo(HttpStatus.CONFLICT.value());
    }
  }

  @Nested
  @DisplayName("handleNotFound()")
  class HandleNotFound {

    @Test
    @DisplayName("mapsLinkNotFoundTo404")
    void handleNotFound_returnsHttpStatus404WithNonEmptyMessage() {
      // given
      final LinkNotFoundException exception = new LinkNotFoundException("link not found: abc");

      // when
      final ResponseEntity<ErrorResponse> response = handler.handleNotFound(exception);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().message()).isNotBlank();
      assertThat(response.getBody().status()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
  }

  @Nested
  @DisplayName("handleExpired()")
  class HandleExpired {

    @Test
    @DisplayName("mapsExpiredToGone")
    void mapsExpiredToGone() {
      // given
      final LinkExpiredException exception = new LinkExpiredException("link expired: old-code");

      // when
      final ResponseEntity<ErrorResponse> response = handler.handleExpired(exception);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().message()).isNotBlank();
      assertThat(response.getBody().status()).isEqualTo(HttpStatus.GONE.value());
      assertThat(response.getBody().error()).isEqualTo("Gone");
    }
  }
}
