package analysis;

import java.util.HashMap;
import java.util.Map;

public class LogEntry {
    private final String timestamp;
    private final String testName;
    private final String predicateType;
    private final String sourceCode;
    private final boolean outcome;
    private final String variableStates;
    private final String fileName; // New field to track the source file

    // Full constructor
    public LogEntry(String timestamp, String testName, String predicateType, String sourceCode, boolean outcome, String variableStates, String fileName) {
        this.timestamp = timestamp;
        this.testName = testName;
        this.predicateType = predicateType;
        this.sourceCode = sourceCode;
        this.outcome = outcome;
        this.variableStates = variableStates;
        this.fileName = fileName == null ? "unknown" : fileName;
    }

    // Optional constructor without fileName
    public LogEntry(String timestamp, String testName, String predicateType, String sourceCode, boolean outcome, String variableStates) {
        this(timestamp, testName, predicateType, sourceCode, outcome, variableStates, "unknown");
    }

    // Getters
    public String getTimestamp() {
        return timestamp;
    }

    public String getTestName() {
        return testName;
    }

    public String getPredicateType() {
        return predicateType;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public boolean isOutcome() {
        return outcome;
    }

    public String getVariableStates() {
        return variableStates;
    }

    public String getFileName() {
        return fileName;
    }

    public Map<String, String> parseVariableStates() {
        Map<String, String> variables = new HashMap<>();
        if (variableStates != null && !variableStates.isEmpty()) {
            String[] pairs = variableStates.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2 && !keyValue[0].isEmpty() && !keyValue[1].isEmpty()) {
                    variables.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        return variables;
    }

    public boolean isValid() {
        return timestamp != null && !timestamp.isEmpty() &&
                testName != null && !testName.isEmpty() &&
                predicateType != null && !predicateType.isEmpty();
    }

    @Override
    public String toString() {
        String truncatedSourceCode = sourceCode.length() > 50 ? sourceCode.substring(0, 47) + "..." : sourceCode;
        String truncatedVariableStates = variableStates.length() > 50 ? variableStates.substring(0, 47) + "..." : variableStates;
        return String.format(
                "LogEntry{timestamp='%s', testName='%s', predicateType='%s', sourceCode='%s', outcome=%s, variableStates='%s', fileName='%s', parsedVariables=%s}",
                timestamp, testName, predicateType, truncatedSourceCode, outcome, truncatedVariableStates, fileName, parseVariableStates()
        );
    }
}
