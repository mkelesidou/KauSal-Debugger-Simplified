package instrumentation;

import runtime.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PredicateTransformerTest {

    private Logger logger;
    private final String testLogFile = "predicate_transformer_test_log.txt";

    @BeforeEach
    public void setup() throws IOException {
        java.io.File logFile = new java.io.File(testLogFile);
        if (logFile.exists()) {
            logFile.delete();
        }
        logger = new Logger(testLogFile);
    }

//    @AfterEach
//    public void teardown() throws IOException {
//        logger.close();
//        java.io.File logFile = new java.io.File(testLogFile);
//        if (logFile.exists()) {
//            boolean deleted = logFile.delete();
//            if (!deleted) {
//                System.err.println("Failed to delete log file: " + testLogFile);
//            }
//        }
//    }

    @Test
    public void testFindPredicate() throws IOException {
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

        List<String> predicates = transformer.findPredicate(sourceCode);

        // Assertions
        assertEquals(3, predicates.size());
        assertTrue(predicates.contains("if-statement"));
        assertTrue(predicates.contains("while-loop"));
        assertTrue(predicates.contains("for-loop"));

        // Log Validation
        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            String thirdLine = reader.readLine();

            assertTrue(firstLine.contains("Predicate: if-statement"));
            assertTrue(secondLine.contains("Predicate: while-loop"));
            assertTrue(thirdLine.contains("Predicate: for-loop"));
        }
    }
}
