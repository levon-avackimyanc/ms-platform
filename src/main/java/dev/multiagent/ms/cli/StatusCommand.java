package dev.multiagent.ms.cli;

import dev.multiagent.ms.core.config.MsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

/**
 * {@code ms status} — minimal sanity-check command for the platform CLI.
 *
 * <p>Reports the running application name, Java/Spring runtime info, and the
 * configured workspace/traces directories. The presence of this command is
 * the Day-1 acceptance signal: the CLI loads, Spring context boots, command
 * is dispatched.
 */
@ShellComponent
@RequiredArgsConstructor
public class StatusCommand {

    private final Environment environment;
    private final MsProperties properties;

    @ShellMethod(key = "status", value = "Show platform status (application name, workspace, traces dir).")
    public String status() {
        return """
                application:   %s
                java:          %s (%s)
                spring-boot:   %s
                workspace:     %s
                traces (dir):  %s
                tracing on:    %s
                """.formatted(
                environment.getProperty("spring.application.name", "<unset>"),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                SpringBootVersion.getVersion(),
                properties.workspace().root(),
                properties.tracing().outputDir(),
                properties.tracing().enabled()
        );
    }
}
