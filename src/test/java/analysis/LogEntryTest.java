package analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LogEntryTest {

    @Test
    public void testParseVariableStates() {
        LogEntry entry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10, y=15",
                "Example1.java"
        );

        Map<String, String> variables = entry.parseVariableStates();

        assertEquals(2, variables.size());
        assertEquals("10", variables.get("x"));
        assertEquals("15", variables.get("y"));
    }

    @Test
    public void testParseVariableStatesWithMalformedInput() {
        LogEntry entry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10,=15,z=",
                "Example1.java"
        );

        Map<String, String> variables = entry.parseVariableStates();

        assertEquals(1, variables.size());
        assertEquals("10", variables.get("x"));
        assertNull(variables.get("")); // Malformed entries should be ignored
        assertNull(variables.get("z")); // Empty values should be ignored
    }

    @Test
    public void testIsValid() {
        LogEntry validEntry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10",
                "Example1.java"
        );

        LogEntry invalidEntry = new LogEntry(
                null,
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10",
                "Example1.java"
        );

        assertTrue(validEntry.isValid());
        assertFalse(invalidEntry.isValid());
    }

    @Test
    public void testToString() {
        LogEntry entry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10, y=15",
                "Example1.java"
        );

        String entryString = entry.toString();
        assertTrue(entryString.contains("timestamp='2025-01-09 10:00:00'"));
        assertTrue(entryString.contains("predicateType='if-statement'"));
        assertTrue(entryString.contains("parsedVariables={x=10, y=15}"));
        assertTrue(entryString.contains("fileName='Example1.java'"));
    }

    @Test
    public void testFileNameField() {
        LogEntry entry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10, y=15",
                "Example1.java"
        );

        assertEquals("Example1.java", entry.getFileName());
    }

    @Test
    public void testDefaultFileName() {
        LogEntry entry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10, y=15"
        );

        assertEquals("unknown", entry.getFileName());
    }
}
