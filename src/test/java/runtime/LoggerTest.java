package runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import java.io.BufferedReader;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoggerTest {

    private Logger logger;
    private final String testLogFile = "test_log.txt";

    @BeforeEach
    public void setup() throws IOException {
        // Clear the file before each test
        new PrintWriter(testLogFile).close();
        logger = new Logger(testLogFile);    }

    @AfterEach
    public void teardown() throws IOException {
        logger.close();
        new java.io.File(testLogFile).delete();
    }

    @Test
    public void testRecord() throws IOException{
        logger.record("Test message");
        logger.record("Another one");

        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(testLogFile))) {
            String firstLine = reader.readLine();
            String secondLine = reader.readLine();

            assertEquals("Test message", firstLine);
            assertEquals("Another one", secondLine);
        }
    }
}
