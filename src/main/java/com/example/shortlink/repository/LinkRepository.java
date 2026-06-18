package com.example.shortlink.repository;

import com.example.shortlink.domain.ShortLink;
import java.util.Optional;

/** Хранилище коротких ссылок. */
public interface LinkRepository {

  /**
   * Сохраняет ссылку, только если её код ещё не занят (атомарно).
   *
   * @param link ссылка для сохранения
   * @return true, если ссылка сохранена; false, если код уже был занят (существующая ссылка не
   *     перезаписывается)
   */
  boolean saveIfAbsent(ShortLink link);

  /**
   * Находит ссылку по коду.
   *
   * @param code короткий код
   * @return ссылка, если найдена
   */
  Optional<ShortLink> findByCode(String code);
}
