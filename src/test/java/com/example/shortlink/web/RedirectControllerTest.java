package com.example.shortlink.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.shortlink.service.LinkService;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit-тесты контроллера редиректа {@link RedirectController}. */
@ExtendWith(MockitoExtension.class)
class RedirectControllerTest {

  @Mock private LinkService linkService;

  private RedirectController controller;

  @BeforeEach
  void setUp() {
    controller = new RedirectController(linkService);
  }

  @Nested
  @DisplayName("redirect()")
  class Redirect {

    @Test
    @DisplayName("returns302WithLocationHeader")
    void returns302WithLocationHeader() {
      // given
      final String code = "abc123";
      final String targetUrl = "https://example.com/target";
      when(linkService.resolveForRedirect(code)).thenReturn(targetUrl);

      // when
      final ResponseEntity<Void> response = controller.redirect(code);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
      assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(targetUrl));
    }

    @Test
    @DisplayName("redirectUsesExactTargetUrlInLocationHeader")
    void redirect_usesExactTargetUrlFromService() {
      // given
      final String code = "my-link";
      final String targetUrl = "https://some-long-url.example.com/path?query=value";
      when(linkService.resolveForRedirect(code)).thenReturn(targetUrl);

      // when
      final ResponseEntity<Void> response = controller.redirect(code);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
      assertThat(response.getHeaders().getLocation()).isNotNull();
      assertThat(response.getHeaders().getLocation()).hasToString(targetUrl);
    }

    @Test
    @DisplayName("redirectResponseHasNoBody")
    void redirect_responseBodyIsNull() {
      // given
      final String code = "nocode";
      when(linkService.resolveForRedirect(code)).thenReturn("https://example.com");

      // when
      final ResponseEntity<Void> response = controller.redirect(code);

      // then
      assertThat(response.getBody()).isNull();
    }
  }
}
