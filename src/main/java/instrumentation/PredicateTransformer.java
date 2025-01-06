package instrumentation;

import java.util.List;
import java.util.ArrayList;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.ForStmt;

public class PredicateTransformer {

    public List<String> findPredicate(String sourceCode) {
        List<String> predicates = new ArrayList<>();

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            cu.findAll(IfStmt.class).forEach(ifStmt -> predicates.add("if-statement"));
            cu.findAll(WhileStmt.class).forEach(whileStmt -> predicates.add("while-loop"));
            cu.findAll(ForStmt.class).forEach(forStmt -> predicates.add("for-loop"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return predicates;
    }
}
