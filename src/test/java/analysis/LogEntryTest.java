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
                "x=10, y=15"
        );

        Map<String, String> variables = entry.parseVariableStates();

        assertEquals(2, variables.size());
        assertEquals("10", variables.get("x"));
        assertEquals("15", variables.get("y"));
    }

    @Test
    public void testIsValid() {
        LogEntry validEntry = new LogEntry(
                "2025-01-09 10:00:00",
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10"
        );

        LogEntry invalidEntry = new LogEntry(
                null,
                "PredicateTransformerTest",
                "if-statement",
                "if (x > 0)",
                true,
                "x=10"
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
                "x=10, y=15"
        );

        String entryString = entry.toString();
        assertTrue(entryString.contains("timestamp='2025-01-09 10:00:00'"));
        assertTrue(entryString.contains("predicateType='if-statement'"));
        assertTrue(entryString.contains("parsedVariables={x=10, y=15}"));
    }
}
