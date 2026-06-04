package dev.multiagent.ms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the full Spring context to verify the skeleton wiring (Spring Boot + Spring Shell
 * + Spring AI auto-config + JGit on classpath + custom configuration properties).
 *
 * <p>If this test passes, Day-1 deliverable (working skeleton) is acceptance-met.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "spring.shell.interactive.enabled=false",
        // dummy LLM creds so AutoConfiguration for spring-ai-openai does not fail
        "spring.ai.openai.api-key=test-key-placeholder",
        "spring.ai.openai.base-url=http://localhost",
})
class MsApplicationSmokeTest {

    @Test
    void contextLoads() {
        // intentionally empty — context bootstrap is the assertion
    }
}
