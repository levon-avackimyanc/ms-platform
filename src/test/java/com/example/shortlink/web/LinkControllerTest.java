package com.example.shortlink.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.shortlink.service.LinkService;
import com.example.shortlink.web.dto.CreateLinkRequest;
import com.example.shortlink.web.dto.LinkResponse;
import com.example.shortlink.web.dto.LinkStatsResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit-тесты контроллера ссылок {@link LinkController}. */
@ExtendWith(MockitoExtension.class)
class LinkControllerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-06-18T10:00:00Z");

  @Mock private LinkService linkService;

  private LinkController controller;

  @BeforeEach
  void setUp() {
    controller = new LinkController(linkService);
  }

  @Nested
  @DisplayName("create()")
  class Create {

    @Test
    @DisplayName("create_withValidRequest_returns201WithBody")
    void create_withValidRequest_returns201WithBody() {
      // given
      final CreateLinkRequest request = new CreateLinkRequest("https://example.com", null, null);
      final LinkResponse serviceResponse =
          new LinkResponse(
              "abc123", "http://localhost:8080/r/abc123", "https://example.com", FIXED_NOW, null);
      when(linkService.create(request)).thenReturn(serviceResponse);

      // when
      final ResponseEntity<LinkResponse> response = controller.create(request);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo("abc123");
      assertThat(response.getBody().url()).isEqualTo("https://example.com");
      assertThat(response.getBody().shortUrl()).isEqualTo("http://localhost:8080/r/abc123");
      assertThat(response.getBody().createdAt()).isEqualTo(FIXED_NOW);
      assertThat(response.getBody().expiresAt()).isNull();
    }

    @Test
    @DisplayName("create_withCustomCode_returns201WithCustomCodeInBody")
    void create_withCustomCode_returns201WithCustomCodeInBody() {
      // given
      final CreateLinkRequest request =
          new CreateLinkRequest("https://example.com", "my-code", null);
      final LinkResponse serviceResponse =
          new LinkResponse(
              "my-code", "http://localhost:8080/r/my-code", "https://example.com", FIXED_NOW, null);
      when(linkService.create(request)).thenReturn(serviceResponse);

      // when
      final ResponseEntity<LinkResponse> response = controller.create(request);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo("my-code");
    }

    @Test
    @DisplayName("create_withExpiresAt_returns201WithExpiresAtInBody")
    void create_withExpiresAt_returns201WithExpiresAtInBody() {
      // given
      final Instant expiresAt = FIXED_NOW.plusSeconds(3600);
      final CreateLinkRequest request =
          new CreateLinkRequest("https://example.com", null, expiresAt);
      final LinkResponse serviceResponse =
          new LinkResponse(
              "xyz789",
              "http://localhost:8080/r/xyz789",
              "https://example.com",
              FIXED_NOW,
              expiresAt);
      when(linkService.create(request)).thenReturn(serviceResponse);

      // when
      final ResponseEntity<LinkResponse> response = controller.create(request);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().expiresAt()).isEqualTo(expiresAt);
    }
  }

  @Nested
  @DisplayName("stats()")
  class Stats {

    @Test
    @DisplayName("stats_withExistingCode_returns200WithStatsBody")
    void stats_withExistingCode_returns200WithStatsBody() {
      // given
      final String code = "abc123";
      final LinkStatsResponse statsResponse =
          new LinkStatsResponse(
              code,
              "https://example.com",
              "http://localhost:8080/r/abc123",
              42L,
              FIXED_NOW,
              FIXED_NOW.plusSeconds(10),
              null);
      when(linkService.getStats(code)).thenReturn(statsResponse);

      // when
      final ResponseEntity<LinkStatsResponse> response = controller.stats(code);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(code);
      assertThat(response.getBody().url()).isEqualTo("https://example.com");
      assertThat(response.getBody().shortUrl()).isEqualTo("http://localhost:8080/r/abc123");
      assertThat(response.getBody().clickCount()).isEqualTo(42L);
      assertThat(response.getBody().createdAt()).isEqualTo(FIXED_NOW);
      assertThat(response.getBody().lastClickedAt()).isEqualTo(FIXED_NOW.plusSeconds(10));
      assertThat(response.getBody().expiresAt()).isNull();
    }

    @Test
    @DisplayName("stats_withNeverClickedLink_returns200WithZeroClickCount")
    void stats_withNeverClickedLink_returns200WithZeroClickCount() {
      // given
      final String code = "fresh-code";
      final LinkStatsResponse statsResponse =
          new LinkStatsResponse(
              code,
              "https://example.com",
              "http://localhost:8080/r/fresh-code",
              0L,
              FIXED_NOW,
              null,
              null);
      when(linkService.getStats(code)).thenReturn(statsResponse);

      // when
      final ResponseEntity<LinkStatsResponse> response = controller.stats(code);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().clickCount()).isZero();
      assertThat(response.getBody().lastClickedAt()).isNull();
    }
  }
}
