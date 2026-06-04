package dev.multiagent.ms.core.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Strongly-typed configuration of the ms-platform itself.
 *
 * <p>Bound from {@code ms.*} keys in {@code application.yml}. Validated at startup —
 * a missing/blank required value fails the context boot fast.
 *
 * @param workspace settings for git-based artifact storage
 * @param tracing   settings for local lightweight tracing
 */
@Validated
@ConfigurationProperties(prefix = "ms")
public record MsProperties(

        @NotNull Workspace workspace,
        @NotNull Tracing tracing

) {

    /**
     * @param root root directory under which increment branches of target projects live
     */
    public record Workspace(@NotBlank String root) {

        public Path rootPath() {
            return Path.of(root);
        }
    }

    /**
     * @param enabled   global toggle for the simplified local tracer
     * @param outputDir directory where {@code <run-id>.ndjson} trace files are written
     */
    public record Tracing(boolean enabled, @NotBlank String outputDir) {

        public Path outputDirPath() {
            return Path.of(outputDir);
        }
    }
}
