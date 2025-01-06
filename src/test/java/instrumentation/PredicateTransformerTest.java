package instrumentation;

import runtime.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PredicateTransformerTest {

    private Logger logger;
    private final String testLogFile = "test_log.txt";

    @BeforeEach
    public void setup() throws IOException {
        System.out.println("Current working directory: " + System.getProperty("user.dir"));
        logger = new Logger(testLogFile);
    }

    @AfterEach
    public void tearDown() throws IOException {
        logger.close();
        // Temporarily disable file deletion for debugging
        // Files.delete(Path.of(testLogFile));
    }

    @Test
    public void testFindPredicateWithLogging() throws IOException {
        // Arrange
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
            public class TestClass {
                public void testMethod() {
                    if (x > 0) {
                        System.out.println(x);
                    }
                    while (y < 10) {
                        y++;
                    }
                    for (int i = 0; i < 5; i++) {
                        System.out.println(i);
                    }
                }
            }
        """;

        // Act
        List<String> predicates = transformer.findPredicate(sourceCode);

        // Assert - Verify predicate detection
        assertEquals(3, predicates.size(), "Should find 3 predicates");
        assertTrue(predicates.contains("if-statement"), "Should detect 'if-statement'");
        assertTrue(predicates.contains("while-loop"), "Should detect 'while-loop'");
        assertTrue(predicates.contains("for-loop"), "Should detect 'for-loop'");

        // Assert - Verify logging
        String logContent = Files.readString(Path.of(testLogFile)); // Cleaner way to read file content
        assertTrue(logContent.contains("Found if-statement"), "Log should contain 'Found if-statement'");
        assertTrue(logContent.contains("Found while-loop"), "Log should contain 'Found while-loop'");
        assertTrue(logContent.contains("Found for-loop"), "Log should contain 'Found for-loop'");
    }
}
