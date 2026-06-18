package com.example.shortlink.web;

import com.example.shortlink.service.LinkService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Контроллер редиректа по короткому коду. */
@RestController
public class RedirectController {

  /** Сервис бизнес-логики ссылок. */
  private final LinkService linkService;

  /**
   * @param linkService сервис бизнес-логики
   */
  public RedirectController(final LinkService linkService) {
    this.linkService = linkService;
  }

  /**
   * Выполняет редирект на оригинальный URL и учитывает переход.
   *
   * @param code короткий код
   * @return 302 Found с заголовком Location
   */
  @GetMapping("/r/{code}")
  public ResponseEntity<Void> redirect(@PathVariable final String code) {
    final String target = linkService.resolveForRedirect(code);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
  }
}
