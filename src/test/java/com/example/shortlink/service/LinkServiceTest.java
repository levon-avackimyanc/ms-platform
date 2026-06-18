package com.example.shortlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.example.shortlink.config.AppProperties;
import com.example.shortlink.domain.ShortLink;
import com.example.shortlink.exception.CodeAlreadyExistsException;
import com.example.shortlink.exception.InvalidCustomCodeException;
import com.example.shortlink.exception.InvalidUrlException;
import com.example.shortlink.exception.LinkExpiredException;
import com.example.shortlink.exception.LinkNotFoundException;
import com.example.shortlink.repository.LinkRepository;
import com.example.shortlink.web.dto.CreateLinkRequest;
import com.example.shortlink.web.dto.LinkResponse;
import com.example.shortlink.web.dto.LinkStatsResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit-тесты сервиса бизнес-логики {@link LinkService}. */
@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-06-18T10:00:00Z");
  private static final String BASE_URL = "http://localhost:8080";

  @Mock private LinkRepository repository;
  @Mock private CodeGenerator codeGenerator;
  @Mock private UrlValidator urlValidator;

  private Clock fixedClock;
  private AppProperties appProperties;
  private LinkService service;

  @BeforeEach
  void setUp() {
    fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    appProperties = new AppProperties(BASE_URL);
    service = new LinkService(repository, codeGenerator, urlValidator, appProperties, fixedClock);
  }

  @Nested
  @DisplayName("create() — random code")
  class CreateWithRandomCode {

    @Test
    @DisplayName("createsLinkWithGeneratedCode")
    void create_withoutCustomCode_returnsLinkWithGeneratedCode() {
      // given
      final String generatedCode = "abc123";
      final CreateLinkRequest request = new CreateLinkRequest("https://example.com", null, null);
      when(codeGenerator.generate()).thenReturn(generatedCode);
      when(repository.saveIfAbsent(any())).thenReturn(true);

      // when
      final LinkResponse response = service.create(request);

      // then
      assertThat(response.code()).isEqualTo(generatedCode);
      assertThat(response.url()).isEqualTo("https://example.com");
      assertThat(response.shortUrl()).isEqualTo(BASE_URL + "/r/" + generatedCode);
      assertThat(response.createdAt()).isEqualTo(FIXED_NOW);
    }
  }

  @Nested
  @DisplayName("create() — custom code")
  class CreateWithCustomCode {

    @Test
    @DisplayName("createsFreeCustomCode")
    void create_withFreeCustomCode_returnsLinkWithCustomCode() {
      // given
      final String customCode = "black-friday";
      final CreateLinkRequest request =
          new CreateLinkRequest("https://example.com", customCode, null);
      when(repository.saveIfAbsent(any())).thenReturn(true);

      // when
      final LinkResponse response = service.create(request);

      // then
      assertThat(response.code()).isEqualTo(customCode);
    }

    @Test
    @DisplayName("createWithTakenCustomCodeThrowsConflict")
    void createWithTakenCustomCodeThrowsConflict() {
      // given — repository signals the code is already taken
      final String customCode = "taken-code";
      final CreateLinkRequest request =
          new CreateLinkRequest("https://example.com", customCode, null);
      when(repository.saveIfAbsent(any())).thenReturn(false);

      // when / then
      assertThatThrownBy(() -> service.create(request))
          .isInstanceOf(CodeAlreadyExistsException.class)
          .hasMessageContaining(customCode);
    }

    @Test
    @DisplayName("takenCustomCodeLeavesExistingLinkUnchanged")
    void create_withTakenCustomCode_doesNotOverwriteExistingLink() {
      // given
      final String customCode = "taken-code";
      final ShortLink existingLink =
          new ShortLink(customCode, "https://original.com", FIXED_NOW, null);
      final CreateLinkRequest request = new CreateLinkRequest("https://new.com", customCode, null);
      when(repository.saveIfAbsent(any())).thenReturn(false);
      when(repository.findByCode(customCode)).thenReturn(Optional.of(existingLink));

      // when — create throws
      assertThatThrownBy(() -> service.create(request))
          .isInstanceOf(CodeAlreadyExistsException.class);

      // then — the original link is unchanged
      final Optional<ShortLink> found = repository.findByCode(customCode);
      assertThat(found).isPresent();
      assertThat(found.get().getOriginalUrl()).isEqualTo("https://original.com");
    }
  }

  @Nested
  @DisplayName("create() — validation errors")
  class CreateValidationErrors {

    @Test
    @DisplayName("invalidUrlThrows400")
    void create_withInvalidUrl_throwsInvalidUrlException() {
      // given
      final CreateLinkRequest request = new CreateLinkRequest("not-a-url", null, null);
      doThrow(new InvalidUrlException("bad url")).when(urlValidator).validateUrl("not-a-url");

      // when / then
      assertThatThrownBy(() -> service.create(request)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("invalidCustomCodeThrows400")
    void create_withInvalidCustomCode_throwsInvalidCustomCodeException() {
      // given
      final CreateLinkRequest request =
          new CreateLinkRequest("https://example.com", "INVALID CODE", null);
      doThrow(new InvalidCustomCodeException("bad code"))
          .when(urlValidator)
          .validateCustomCode("INVALID CODE");

      // when / then
      assertThatThrownBy(() -> service.create(request))
          .isInstanceOf(InvalidCustomCodeException.class);
    }

    @Test
    @DisplayName("urlLongerThan2048Throws400")
    void create_withUrlLongerThan2048_throwsInvalidUrlException() {
      // given — validator is the authoritative check; it throws when URL is too long
      final String longUrl = "https://example.com/" + "x".repeat(2048);
      final CreateLinkRequest request = new CreateLinkRequest(longUrl, null, null);
      doThrow(new InvalidUrlException("url too long")).when(urlValidator).validateUrl(longUrl);

      // when / then
      assertThatThrownBy(() -> service.create(request)).isInstanceOf(InvalidUrlException.class);
    }
  }

  @Nested
  @DisplayName("resolveForRedirect()")
  class ResolveForRedirect {

    @Test
    @DisplayName("resolveActiveReturnsTargetAndRecordsClick")
    void resolveForRedirect_withActiveCode_returnsTargetUrlAndRecordsClick() {
      // given
      final ShortLink link = new ShortLink("abc123", "https://target.com", FIXED_NOW, null);
      when(repository.findByCode("abc123")).thenReturn(Optional.of(link));

      // when
      final String target = service.resolveForRedirect("abc123");

      // then
      assertThat(target).isEqualTo("https://target.com");
      assertThat(link.getClickCount()).isEqualTo(1L);
      assertThat(link.getLastClickedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("resolveUnknownCodeThrows404")
    void resolveForRedirect_withUnknownCode_throwsLinkNotFoundException() {
      // given
      when(repository.findByCode("unknown")).thenReturn(Optional.empty());

      // when / then
      assertThatThrownBy(() -> service.resolveForRedirect("unknown"))
          .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    @DisplayName("resolveExpiredCodeThrows410WithNoIncrement")
    void resolveForRedirect_withExpiredCode_throwsLinkExpiredExceptionAndNoIncrement() {
      // given — link expired in the past
      final Instant pastExpiry = Instant.parse("2020-01-01T00:00:00Z");
      final ShortLink expiredLink =
          new ShortLink("old-code", "https://expired.com", FIXED_NOW, pastExpiry);
      when(repository.findByCode("old-code")).thenReturn(Optional.of(expiredLink));

      // when / then
      assertThatThrownBy(() -> service.resolveForRedirect("old-code"))
          .isInstanceOf(LinkExpiredException.class);

      // counter must NOT have been incremented
      assertThat(expiredLink.getClickCount()).isZero();
    }

    @Test
    @DisplayName("redirectIncrementsClickCountAtomically")
    void redirectIncrementsClickCountAtomically() throws InterruptedException {
      // given — a real ShortLink so recordClick hits the AtomicLong
      final ShortLink link = new ShortLink("concurrent", "https://target.com", FIXED_NOW, null);
      when(repository.findByCode("concurrent")).thenReturn(Optional.of(link));

      final int threads = 100;
      final CountDownLatch ready = new CountDownLatch(threads);
      final CountDownLatch start = new CountDownLatch(1);
      final ExecutorService pool = Executors.newFixedThreadPool(threads);

      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                ready.countDown();
                start.await();
                service.resolveForRedirect("concurrent");
              } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
            });
      }

      // release all threads simultaneously
      ready.await();
      start.countDown();
      pool.shutdown();
      pool.awaitTermination(10, TimeUnit.SECONDS);

      // then — exactly 100 increments, no losses
      assertThat(link.getClickCount()).isEqualTo(100L);
    }
  }

  @Nested
  @DisplayName("getStats()")
  class GetStats {

    @Test
    @DisplayName("statsProjectionHasNullLastClickedAtWhenNeverClicked")
    void getStats_whenNeverClicked_lastClickedAtIsNull() {
      // given
      final ShortLink link = new ShortLink("stats-code", "https://example.com", FIXED_NOW, null);
      when(repository.findByCode("stats-code")).thenReturn(Optional.of(link));

      // when
      final LinkStatsResponse stats = service.getStats("stats-code");

      // then
      assertThat(stats.lastClickedAt()).isNull();
      assertThat(stats.clickCount()).isZero();
      assertThat(stats.code()).isEqualTo("stats-code");
      assertThat(stats.url()).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("statsProjectionReturnsClickCountAndLastClickedAt")
    void getStats_afterClicks_returnsClickCountAndLastClickedAt() {
      // given
      final ShortLink link = new ShortLink("stats-code", "https://example.com", FIXED_NOW, null);
      link.recordClick(FIXED_NOW);
      link.recordClick(FIXED_NOW.plusSeconds(1));
      when(repository.findByCode("stats-code")).thenReturn(Optional.of(link));

      // when
      final LinkStatsResponse stats = service.getStats("stats-code");

      // then
      assertThat(stats.clickCount()).isEqualTo(2L);
      assertThat(stats.lastClickedAt()).isNotNull();
    }

    @Test
    @DisplayName("statsForUnknownCodeThrows404")
    void getStats_withUnknownCode_throwsLinkNotFoundException() {
      // given
      when(repository.findByCode("missing")).thenReturn(Optional.empty());

      // when / then
      assertThatThrownBy(() -> service.getStats("missing"))
          .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    @DisplayName("statsProjectionIncludesCorrectShortUrl")
    void getStats_returnsCorrectShortUrl() {
      // given
      final ShortLink link = new ShortLink("mycode", "https://example.com", FIXED_NOW, null);
      when(repository.findByCode("mycode")).thenReturn(Optional.of(link));

      // when
      final LinkStatsResponse stats = service.getStats("mycode");

      // then
      assertThat(stats.shortUrl()).isEqualTo(BASE_URL + "/r/mycode");
    }
  }
}
