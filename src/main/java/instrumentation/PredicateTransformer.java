package instrumentation;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import runtime.Logger;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.ForStmt;

public class PredicateTransformer {

    private final Logger logger;
    public PredicateTransformer(Logger logger){
        this.logger = logger;
    }
    public List<String> findPredicate(String sourceCode) {
        List<String> predicates = new ArrayList<>();

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            // Find all "if" statements
            cu.findAll(IfStmt.class).forEach(ifStmt -> {
                predicates.add("if-statement");
                logger.record("Found if-statement: " + ifStmt.toString());
            });

            // Find all "while" statements
            cu.findAll(WhileStmt.class).forEach(whileStmt -> {
                predicates.add("while-loop");
                logger.record("Found while-loop: " + whileStmt.toString());
            });

            // Find all "for" statements
            cu.findAll(ForStmt.class).forEach(forStmt -> {
                predicates.add("for-loop");
                logger.record("Found for-loop: " + forStmt.toString());
            });
        }catch (Exception e) {
            logger.record("Error during predicate parsing: " + e.getMessage());
        }

        return predicates;
    }
}
