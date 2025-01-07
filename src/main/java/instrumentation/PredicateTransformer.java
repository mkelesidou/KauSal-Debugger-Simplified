package instrumentation;

import runtime.Logger;
import java.util.ArrayList;
import java.util.List;

public class PredicateTransformer {
    private final Logger logger;

    public PredicateTransformer(Logger logger) {
        this.logger = logger;
    }

    public List<String> findPredicate(String sourceCode) {
        List<String> predicates = new ArrayList<>();

        // Mock dynamic data for demonstration
        boolean ifOutcome = true;
        String ifVariables = "x=10";

        boolean whileOutcome = false;
        String whileVariables = "y=15";

        boolean forOutcome = true;
        String forVariables = "i=0";

        // Find 'if' predicates
        if (sourceCode.contains("if")) {
            logger.record("PredicateTransformerTest", "if-statement", "if (x > 0)", ifOutcome, ifVariables);
            predicates.add("if-statement");
        }

        // Find 'while' predicates
        if (sourceCode.contains("while")) {
            logger.record("PredicateTransformerTest", "while-loop", "while (y < 10)", whileOutcome, whileVariables);
            predicates.add("while-loop");
        }

        // Find 'for' predicates
        if (sourceCode.contains("for")) {
            logger.record("PredicateTransformerTest", "for-loop", "for (int i = 0; i < 5; i++)", forOutcome, forVariables);
            predicates.add("for-loop");
        }

        return predicates;
    }
}
