package com.example.shortlink.repository;

import com.example.shortlink.domain.ShortLink;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

/**
 * In-memory реализация хранилища на {@link ConcurrentHashMap}.
 *
 * <p>Данные не переживают перезапуск сервиса — это осознанное ограничение. Создание использует
 * {@code putIfAbsent} для атомарного обнаружения занятого кода без перезаписи.
 */
@Repository
public class InMemoryLinkRepository implements LinkRepository {

  /** Хранилище ссылок: код → ссылка. */
  private final ConcurrentMap<String, ShortLink> store = new ConcurrentHashMap<>();

  @Override
  public boolean saveIfAbsent(final ShortLink link) {
    Assert.notNull(link, "link cannot be null");

    return store.putIfAbsent(link.getCode(), link) == null;
  }

  @Override
  public Optional<ShortLink> findByCode(final String code) {
    Assert.hasText(code, "code cannot be blank");

    return Optional.ofNullable(store.get(code));
  }
}
