package analysis;

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

    @Override
    public String toString() {
        return String.format("LogEntry{timestamp='%s', testName='%s', predicateType='%s', sourceCode='%s', outcome=%s, variableStates='%s'}",
                timestamp, testName, predicateType, sourceCode, outcome, variableStates);
    }
}
