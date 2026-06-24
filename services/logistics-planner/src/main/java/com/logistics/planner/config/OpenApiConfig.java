package com.logistics.planner.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Logistics Planner API")
                .description("CVRPTW route optimization service — manages locations, orders, vehicles, and builds optimal delivery plans using Google OR-Tools.")
                .version("0.1.0")
                .contact(new Contact()
                    .name("Logistics Platform Team")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local / Docker")
            ));
    }
}
