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
    public void testPredicateTransformerWithExampleClass2() throws IOException {
        PredicateTransformer transformer = new PredicateTransformer(logger);

        String exampleClass1Path = Paths.get("src/main/java/examples/Example1.java").toAbsolutePath().toString();

        // Read the file using BufferedReader
        StringBuilder sourceCodeBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(exampleClass1Path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceCodeBuilder.append(line).append("\n");
            }
        }
        String exampleClass1Code = sourceCodeBuilder.toString();
        List<String> predicates = transformer.findPredicate(exampleClass1Code);

        // Assertions based on ExampleClass1's expected predicates
        assertEquals(2, predicates.size()); // Adjust based on the actual logic in ExampleClass1
        assertTrue(predicates.contains("if-statement")); // ExampleClass1 should have an 'if-statement'
        assertTrue(predicates.contains("for-loop")); // ExampleClass1 should have a 'for-loop'

        // Assertions based on ExampleClass2's expected predicates
//        assertEquals(1, predicates.size()); // Adjust based on the actual logic in ExampleClass2
//        assertTrue(predicates.contains("while-loop")); // ExampleClass2 should have a 'while-loop'


        // Print a message indicating success
        System.out.println("Log file successfully generated for Example1.");
    }
}
