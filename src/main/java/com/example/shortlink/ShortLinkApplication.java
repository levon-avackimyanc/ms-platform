package com.example.shortlink;

import com.example.shortlink.config.AppProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Точка входа Spring Boot приложения сервиса коротких ссылок. */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ShortLinkApplication {

  /**
   * Запускает приложение.
   *
   * @param args аргументы командной строки
   */
  public static void main(final String[] args) {
    SpringApplication.run(ShortLinkApplication.class, args);
  }

  /**
   * Системные часы (UTC) — единый источник времени, инъектируемый в сервис.
   *
   * @return системные UTC-часы
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
