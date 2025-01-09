package instrumentation;

import runtime.Logger;
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

    @Test
    public void testFindPredicate() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
            public class TestClass {
                public void testMethod() {
                    // Simple predicates
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

        assertEquals(3, predicates.size());
        assertTrue(predicates.contains("if-statement"));
        assertTrue(predicates.contains("while-loop"));
        assertTrue(predicates.contains("for-loop"));

        // Validate log output
        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            String thirdLine = reader.readLine();

            assertTrue(firstLine.contains("Predicate: if-statement"));
            assertTrue(secondLine.contains("Predicate: while-loop"));
            assertTrue(thirdLine.contains("Predicate: for-loop"));
        }
    }

    @Test
    public void testNestedConditionals() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
            public class TestClass {
                public void testMethod() {
                    if (x > 0) {
                        if (y < 10) {
                            System.out.println(x + y);
                        }
                    }
                }
            }
        """;

        List<String> predicates = transformer.findPredicate(sourceCode);

        assertEquals(2, predicates.size());
        assertTrue(predicates.contains("if-statement"));

        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();

            assertTrue(firstLine.contains("Predicate: if-statement"));
            assertTrue(secondLine.contains("Predicate: if-statement"));
        }
    }

    @Test
    public void testLogicalOperators() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
        public class TestClass {
            public void testMethod() {
                if (x > 0 && y < 10 || z == 5) {
                    System.out.println(x + y + z);
                }
            }
        }
    """;

        List<String> predicates = transformer.findPredicate(sourceCode);

        // Expect the main predicate type to be logged once
        assertEquals(1, predicates.size());
        assertTrue(predicates.contains("if-statement"));

        // Validate the log output for sub-conditions
        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            String thirdLine = reader.readLine();

            assertTrue(firstLine.contains("subCondition=x > 0"));
            assertTrue(secondLine.contains("subCondition=y < 10"));
            assertTrue(thirdLine.contains("subCondition=z == 5"));
        }
    }

    @Test
    public void testEmptyBlocks() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
            public class TestClass {
                public void testMethod() {
                    if (x > 0) {}
                }
            }
        """;

        List<String> predicates = transformer.findPredicate(sourceCode);

        assertEquals(1, predicates.size());
        assertTrue(predicates.contains("if-statement"));

        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();

            assertTrue(firstLine.contains("Predicate: if-statement"));
        }
    }

    @Test
    public void testGSAWithLogicalOperators() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String sourceCode = """
        public class TestClass {
            public void testMethod() {
                if (x > 0 && y < 10 || z == 5) {
                    System.out.println(x + y + z);
                }
            }
        }
    """;

        List<String> predicates = transformer.findPredicate(sourceCode);

        // Expect the main predicate type to be logged once
        assertEquals(1, predicates.size());
        assertTrue(predicates.contains("if-statement"));

        // Validate the log output for GSA
        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();
            String thirdLine = reader.readLine();

            assertTrue(firstLine.contains("subCondition=x > 0"));
            assertTrue(firstLine.contains("versionedVariable=x_v0"));

            assertTrue(secondLine.contains("subCondition=y < 10"));
            assertTrue(secondLine.contains("versionedVariable=y_v1"));

            assertTrue(thirdLine.contains("subCondition=z == 5"));
            assertTrue(thirdLine.contains("versionedVariable=z_v2"));
        }
    }
}
