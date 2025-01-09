package runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoggerTest {

    private Logger logger;
    private final String testLogFile = "logger_test_log.txt";

    @BeforeEach
    public void setup() throws IOException {
        java.io.File logFile = new java.io.File(testLogFile);
        if (logFile.exists()) {
            logFile.delete();
        }
        logger = new Logger(testLogFile);
    }

    @AfterEach
    public void teardown() throws IOException {
        logger.close();
        java.io.File logFile = new java.io.File(testLogFile);
        if (logFile.exists()) {
            boolean deleted = logFile.delete();
            if (!deleted) {
                System.err.println("Failed to delete log file: " + testLogFile);
            }
        }
    }

    @Test
    public void testLoggerWritesCorrectly() throws IOException {
        logger.record("LoggerTest", "if-statement", "if (x > 0)", true, "x=10");
        logger.record("LoggerTest", "while-loop", "while (y < 10)", false, "y=15");

        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();

            assertTrue(firstLine.contains("Test: LoggerTest"));
            assertTrue(firstLine.contains("Predicate: if-statement"));
            assertTrue(firstLine.contains("Source: if (x > 0)"));
            assertTrue(firstLine.contains("Outcome: true"));
            assertTrue(firstLine.contains("Variables: x=10"));

            assertTrue(secondLine.contains("Test: LoggerTest"));
            assertTrue(secondLine.contains("Predicate: while-loop"));
            assertTrue(secondLine.contains("Source: while (y < 10)"));
            assertTrue(secondLine.contains("Outcome: false"));
            assertTrue(secondLine.contains("Variables: y=15"));
        }
    }

    @Test
    public void testLoggerHandlesNullValues() throws IOException {
        logger.record(null, null, null, false, null);

        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String line = reader.readLine();

            assertTrue(line.contains("Test: unknown"));
            assertTrue(line.contains("Predicate: unknown"));
            assertTrue(line.contains("Source: unknown"));
            assertTrue(line.contains("Outcome: false"));
            assertTrue(line.contains("Variables: none"));
        }
    }

    @Test
    public void testLoggerWithAdditionalInfo() throws IOException {
        logger.record("LoggerTest", "if-statement", "if (x > 0)", true, "x=10", "LineNumber=5");

        try (BufferedReader reader = new BufferedReader(new FileReader(testLogFile))) {
            String line = reader.readLine();

            assertTrue(line.contains("Test: LoggerTest"));
            assertTrue(line.contains("Predicate: if-statement"));
            assertTrue(line.contains("Source: if (x > 0)"));
            assertTrue(line.contains("Outcome: true"));
            assertTrue(line.contains("Variables: x=10"));
            assertTrue(line.contains("Info: LineNumber=5"));
        }
    }
}
