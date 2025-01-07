package runtime;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private final PrintWriter writer;

    public Logger(String filename) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filename, true));
        System.out.println("Log file path: " + new java.io.File(filename).getAbsolutePath());
    }

    public void record(String testTag, String predicateType, String sourceCode, boolean outcome, String variableStates) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String message = String.format(
                    "[%s] Test: %s | Predicate: %s | Source: %s | Outcome: %s | Variables: %s",
                    timestamp,
                    testTag == null ? "unknown" : testTag,
                    predicateType == null ? "unknown" : predicateType,
                    sourceCode == null ? "unknown" : sourceCode.trim(),
                    outcome ? "true" : "false",
                    variableStates == null ? "none" : variableStates
            );
            writer.println(message);
            System.out.println("Writing to log: " + message);
            writer.flush();
        } catch (Exception e) {
            System.err.println("Failed to write to log: " + e.getMessage());
        }
    }

    public void close() {
        try {
            writer.close();
        } catch (Exception e) {
            System.err.println("Failed to close log writer: " + e.getMessage());
        }
    }
}
