package com.example.shortlink.web;

import com.example.shortlink.service.LinkService;
import com.example.shortlink.web.dto.CreateLinkRequest;
import com.example.shortlink.web.dto.LinkResponse;
import com.example.shortlink.web.dto.LinkStatsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST-контроллер управления короткими ссылками. */
@RestController
@RequestMapping("/api/links")
public class LinkController {

  /** Сервис бизнес-логики ссылок. */
  private final LinkService linkService;

  /**
   * @param linkService сервис бизнес-логики
   */
  public LinkController(final LinkService linkService) {
    this.linkService = linkService;
  }

  /**
   * Создаёт короткую ссылку.
   *
   * @param request тело запроса
   * @return 201 Created с данными созданной ссылки
   */
  @PostMapping
  public ResponseEntity<LinkResponse> create(@Valid @RequestBody final CreateLinkRequest request) {
    final LinkResponse response = linkService.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * Возвращает статистику по ссылке.
   *
   * @param code короткий код
   * @return 200 OK со статистикой
   */
  @GetMapping("/{code}")
  public ResponseEntity<LinkStatsResponse> stats(@PathVariable final String code) {
    return ResponseEntity.ok(linkService.getStats(code));
  }
}
