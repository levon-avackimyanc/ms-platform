package dev.multiagent.ms;

import dev.multiagent.ms.core.config.MsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the ms-platform CLI orchestrator.
 *
 * <p>Spring Shell is configured non-interactive by default (see application.yml).
 * Commands are dispatched by argv passed to {@code java -jar ms-platform.jar ...}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = MsProperties.class)
public class MsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsApplication.class, args);
    }
}
