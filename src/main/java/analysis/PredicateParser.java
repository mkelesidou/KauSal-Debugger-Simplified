package analysis;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.logging.Logger;

public class PredicateParser {
    private final List<LogEntry> entries = new ArrayList<>();

    // Regex to parse PredicateTransformer logs
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "\\[(.*?)] Test: PredicateTransformerTest \\| Predicate: (.*?) \\| Source: (.*?) \\| Outcome: (true|false) \\| Variables: (.*)"
    );

    public void parseLogFile(String filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LOG_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String timestamp = matcher.group(1);
                    String predicateType = matcher.group(2);
                    String sourceCode = matcher.group(3);
                    boolean outcome = Boolean.parseBoolean(matcher.group(4));
                    String variableStates = matcher.group(5);

                    LogEntry entry = new LogEntry(timestamp, "PredicateTransformerTest", predicateType, sourceCode, outcome, variableStates);
                    entries.add(entry);
                } else {
                    System.err.println("Failed to parse log line: " + line);
                }
            }
        }
    }

    public List<LogEntry> getEntries() {
        return entries;
    }

    public void exportToCsv(String outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            writer.println("Timestamp,TestName,PredicateType,SourceCode,Outcome,VariableStates");
            for (LogEntry entry : entries) {
                writer.printf("%s,%s,%s,%s,%s,%s%n",
                        entry.getTimestamp(),
                        entry.getTestName(),
                        entry.getPredicateType(),
                        entry.getSourceCode().replace(",", ";"), // Escape commas
                        entry.isOutcome(),
                        entry.getVariableStates().replace(",", ";"));
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PredicateParser.class.getName());

    public static void main(String[] args) {

        PredicateParser parser = new PredicateParser();
        try {
            // Parse only the PredicateTransformer log file
            parser.parseLogFile("predicate_transformer_test_log.txt");

            // Export the parsed entries to a CSV file
            parser.exportToCsv("parsed_logs.csv");

            System.out.println("Log parsing and export completed.");
        } catch (IOException e) {
            LOGGER.severe("Error during preprocessing: " + e.getMessage());
        }
    }
}
