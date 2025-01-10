package instrumentation;

import runtime.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PredicateTransformer {
    private final Logger logger;
    private int variableVersion = 0; // To track unique variable versions

    public PredicateTransformer(Logger logger) {
        this.logger = logger;
    }

    public List<String> findPredicate(String sourceCode) {
        List<String> predicates = new ArrayList<>();

        String[] lines = sourceCode.split("\\n");
        // Pattern to extract conditions within parentheses
        Pattern conditionPattern = Pattern.compile("\\(([^)]+)\\)");

        for (String line : lines) {
            line = line.trim();

            // Handle if, while, and do-while predicates
            if (line.startsWith("if (") || line.startsWith("while (") || line.startsWith("do {")) {
                Matcher matcher = conditionPattern.matcher(line);
                if (matcher.find()) {
                    String condition = matcher.group(1); // Extract condition inside parentheses
                    String predicateType = line.startsWith("if (") ? "if-statement" :
                            line.startsWith("while (") ? "while-loop" : "do-while-loop";
                    processCondition(predicateType, condition, line);
                }
                predicates.add(line.startsWith("if (") ? "if-statement" : "while-loop");
            }
            // Handle for loops
            else if (line.startsWith("for (")) {
                logger.record("PredicateTransformerTest", "for-loop", line, true, "variables=unknown");
                predicates.add("for-loop");
            }
            // Handle ternary operators
            else if (line.contains("?") && line.contains(":")) {
                logger.record("PredicateTransformerTest", "ternary-operator", line, true, "condition=unknown");
                predicates.add("ternary-operator");
            }
            // Handle assertions
            else if (line.startsWith("assert ")) {
                Matcher matcher = conditionPattern.matcher(line);
                if (matcher.find()) {
                    String condition = matcher.group(1);
                    processCondition("assert-statement", condition, line);
                }
                predicates.add("assert-statement");
            }
        }
        return predicates;
    }

    private void processCondition(String predicateType, String condition, String sourceCode) {
        // Split conditions by logical operators
        String[] subConditions = condition.split("&&|\\|\\|");

        for (String subCondition : subConditions) {
            subCondition = subCondition.trim();
            String variableName = extractVariableName(subCondition);
            String versionedVariable = variableName + "_v" + variableVersion++;

            logger.record("PredicateTransformerTest", predicateType, sourceCode, true,
                    "subCondition=" + subCondition + ", versionedVariable=" + versionedVariable);
        }
    }

    private String extractVariableName(String condition) {
        // Extract variable name from a simple condition (e.g., x > 0 or y < 10)
        String[] parts = condition.split("[><=!]");
        return parts.length > 0 ? parts[0].trim() : "unknown";
    }
}
