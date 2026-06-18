package com.example.shortlink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация приложения сервиса коротких ссылок.
 *
 * <p>Пример application.yml:
 *
 * <pre>{@code
 * app:
 *   base-url: http://localhost:8080
 * }</pre>
 *
 * @param baseUrl базовый адрес сервиса для построения короткой ссылки (baseUrl + "/r/" + code)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl) {

  /** Значение base-url по умолчанию, если не переопределено в конфигурации. */
  private static final String DEFAULT_BASE_URL = "http://localhost:8080";

  /** Подставляет значение по умолчанию для baseUrl, если оно не задано. */
  public AppProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = DEFAULT_BASE_URL;
    }
  }
}
