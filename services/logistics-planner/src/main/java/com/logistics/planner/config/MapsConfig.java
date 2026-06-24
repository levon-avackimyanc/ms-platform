package com.logistics.planner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MapsConfig {

    @Value("${maps.api-key}")
    private String apiKey;

    @Bean
    @ConditionalOnProperty(name = "maps.provider", havingValue = "google")
    public RestClient mapsRestClient() {
        return RestClient.builder()
                .baseUrl("https://maps.googleapis.com")
                .build();
    }
}
