package instrumentation;

import runtime.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PredicateTransformerTest {

    private Logger logger;
    private final String testLogFile = "predicate_transformer_test_log.txt";

    @BeforeEach
    public void setup() throws IOException {
        // Clean up any existing log file
        java.io.File logFile = new java.io.File(testLogFile);
        if (logFile.exists()) {
            logFile.delete();
        }
        logger = new Logger(testLogFile);
    }

    @Test
    public void testPredicateTransformerWithExample1() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        // Path to Example1 source code
        String exampleClass1Path = Paths.get("src/main/java/examples/Example1.java").toAbsolutePath().toString();
        String exampleClass1Code = Files.readString(Paths.get(exampleClass1Path));

        // Find predicates in Example1
        List<String> predicates = transformer.findPredicate(exampleClass1Code);

        // Assertions based on Example1's logic
        // Example1 contains:
        // 1. 'if-statement' for "if (x > 0)"
        // 2. 'for-loop' for "for (int i = 0; i < x; i++)"
        // 3. 'if-statement' for "if (i % 2 == 0)"
        assertEquals(3, predicates.size()); // 2 'if-statements' + 1 'for-loop'
        assertTrue(predicates.contains("if-statement")); // Check for 'if'
        assertTrue(predicates.contains("for-loop")); // Check for 'for'

        // Verify the log file contains entries for predicates in Example1
        List<String> logEntries = Files.readAllLines(Paths.get(testLogFile));
        assertTrue(logEntries.size() > 0); // Ensure the log file is not empty

        // Optionally print the predicates and log entries for debugging
        System.out.println("Predicates found in Example1:");
        predicates.forEach(System.out::println);

        System.out.println("Log file entries:");
        logEntries.forEach(System.out::println);

        System.out.println("Log file successfully generated for Example1.");
    }

}
