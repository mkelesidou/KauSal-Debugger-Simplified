package instrumentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectOutTest {

    @TempDir
    Path tempDir;
    
    private File logFile;
    private BufferedWriter originalWriter;
    
    @BeforeEach
    void setUp() throws Exception {
        // Save the original writer for restoration
        Field writerField = CollectOut.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        originalWriter = (BufferedWriter) writerField.get(null);
        
        // Create test log file
        logFile = tempDir.resolve("test_execution.log").toFile();
        
        // Use reflection to reset the writer with our test file
        BufferedWriter testWriter = new BufferedWriter(new FileWriter(logFile));
        writerField.set(null, testWriter);
        
        // Clear any existing log lines
        CollectOut.flushCurrentTestLog();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close the test writer
        CollectOut.close();
        
        // Restore the original writer
        Field writerField = CollectOut.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(null, originalWriter);
    }

    @Test
    void testLogVariable() throws IOException {
        // Log some variables
        CollectOut.logVariable("testVar1", 100);
        CollectOut.logVariable("testVar2", "test string");
        CollectOut.logVariable("testVar3", true);
        
        // Check in-memory logs
        List<String> logLines = CollectOut.flushCurrentTestLog();
        
        assertEquals(3, logLines.size(), "Should have 3 log entries");
        assertEquals("testVar1 = 100", logLines.get(0));
        assertEquals("testVar2 = test string", logLines.get(1));
        assertEquals("testVar3 = true", logLines.get(2));
        
        // Check file logs
        CollectOut.close(); // Ensure file is flushed and closed before reading
        List<String> fileLines = Files.readAllLines(logFile.toPath());
        assertEquals(3, fileLines.size(), "File should have 3 log entries");
        assertEquals("testVar1 = 100", fileLines.get(0));
    }

    @Test
    void testFlushCurrentTestLog() {
        // Log some variables
        CollectOut.logVariable("var1", 1);
        CollectOut.logVariable("var2", 2);
        
        // Get and check logs
        List<String> firstLogs = CollectOut.flushCurrentTestLog();
        assertEquals(2, firstLogs.size());
        
        // Logs should be empty after flush
        List<String> emptyLogs = CollectOut.flushCurrentTestLog();
        assertTrue(emptyLogs.isEmpty(), "Log should be empty after flush");
        
        // Add new logs
        CollectOut.logVariable("var3", 3);
        List<String> newLogs = CollectOut.flushCurrentTestLog();
        assertEquals(1, newLogs.size());
        assertEquals("var3 = 3", newLogs.get(0));
    }
    
    @Test
    void testLogWithNullValue() {
        // Test with null value
        CollectOut.logVariable("nullVar", null);
        
        List<String> logs = CollectOut.flushCurrentTestLog();
        assertEquals(1, logs.size());
        assertEquals("nullVar = null", logs.get(0));
    }
} 