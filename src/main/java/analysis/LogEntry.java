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

    public LogEntry(String timestamp, String testName, String predicateType, String sourceCode, boolean outcome, String variableStates) {
        this.timestamp = timestamp;
        this.testName = testName;
        this.predicateType = predicateType;
        this.sourceCode = sourceCode;
        this.outcome = outcome;
        this.variableStates = variableStates;
    }

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

    public Map<String, String> parseVariableStates() {
        Map<String, String> variables = new HashMap<>();
        if (variableStates != null && !variableStates.isEmpty()) {
            String[] pairs = variableStates.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
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
        return String.format("LogEntry{timestamp='%s', testName='%s', predicateType='%s', sourceCode='%s', outcome=%s, variableStates='%s', parsedVariables=%s}",
                timestamp, testName, predicateType, sourceCode, outcome, variableStates, parseVariableStates());
    }
}
