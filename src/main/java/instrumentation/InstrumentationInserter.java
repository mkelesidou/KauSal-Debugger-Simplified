package instrumentation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * InstrumentationInserter modifies a Java source file by:
 * 1) Forcing the final code into the 'instrumentation' package,
 * 2) Ensuring that each assignment’s target variable is declared in the enclosing method (with default initializer 0),
 * 3) Inserting logging calls via CollectOut immediately after each assignment,
 * 4) Lifting conditional expressions into temporary variables (with logging),
 * 5) Updating the main method so that input_1 is assigned from command-line arguments and the parameter is renamed to "args".
 *
 * This version uses hardcoded file paths.
 */
public class InstrumentationInserter extends ModifierVisitor<Void> {
    private static final Logger logger = LoggerFactory.getLogger(InstrumentationInserter.class);

    // Store generated temporary names to avoid collisions.
    private final Set<String> usedNames = new HashSet<>();

    public static void main(String[] args) {
        try {
            String sourcePath = "src/main/resources/transformation/gsas/transformed_simple_example.java";
            String sourceCode = new String(Files.readAllBytes(Paths.get(sourcePath)));
            String instrumentedSource = transformSource(sourceCode);
            String outputPath = "src/main/resources/instrumentation/SimpleExample.java";
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
                writer.println(instrumentedSource);
            }
            logger.info("Instrumentation complete. Instrumented file written to: {}", outputPath);
        } catch (Exception e) {
            logger.error("Instrumentation failed.", e);
        }
    }

    /**
     * transformSource: Parse, visit, and transform the code, and update the main method.
     * Forces the final code into the 'instrumentation' package.
     */
    public static String transformSource(String sourceCode) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        // Force package to 'instrumentation'
        cu.setPackageDeclaration("instrumentation");
        InstrumentationInserter inserter = new InstrumentationInserter();
        inserter.visit(cu, null);
        // Update the main method: rename parameter to "args" if needed, and update input_1 initializer.
        updateMainMethod(cu);
        return cu.toString();
    }

    /**
     * Updates the main method so that:
     * 1. The parameter is renamed to "args" (if it isn’t already), and
     * 2. The declaration for input_1 is replaced with:
     *    int input_1 = (args.length > 0) ? Integer.parseInt(args[0]) : 4;
     */
    private static void updateMainMethod(CompilationUnit cu) {
        cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getNameAsString().equals("main"))
                .forEach(md -> {
                    // Rename the first parameter to "args" if needed.
                    if (!md.getParameters().isEmpty() && !md.getParameter(0).getNameAsString().equals("args")) {
                        md.getParameter(0).setName("args");
                    }
                    // Update input_1 initializer.
                    md.getBody().ifPresent(body -> {
                        for (Statement stmt : body.getStatements()) {
                            if (stmt.isExpressionStmt() &&
                                    stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                                VariableDeclarationExpr vde = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                                vde.getVariables().forEach(vd -> {
                                    if (vd.getNameAsString().equals("input_1")) {
                                        vd.setInitializer(StaticJavaParser.parseExpression("(args.length > 0) ? Integer.parseInt(args[0]) : 4"));
                                    }
                                });
                            }
                        }
                    });
                });
    }

    /**
     * Generates a unique temporary variable name with the given prefix.
     */
    private String uniqueTemp(String prefix) {
        String candidate;
        do {
            candidate = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        } while (usedNames.contains(candidate));
        usedNames.add(candidate);
        return candidate;
    }

    /**
     * Checks if a variable with the given name is declared in the provided statements.
     */
    private boolean isDeclared(NodeList<Statement> statements, String varName) {
        for (Statement stmt : statements) {
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                if (exprStmt.getExpression().isVariableDeclarationExpr()) {
                    for (VariableDeclarator vd : exprStmt.getExpression().asVariableDeclarationExpr().getVariables()) {
                        if (vd.getNameAsString().equals(varName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Inserts a declaration for varName with type int and initializer 0 at the beginning
     * of the enclosing method body if not already declared.
     */
    private void ensureDeclarationInMethod(MethodDeclaration methodDecl, String varName) {
        methodDecl.getBody().ifPresent(body -> {
            if (!isDeclared(body.getStatements(), varName)) {
                VariableDeclarator vd = new VariableDeclarator(StaticJavaParser.parseType("int"), varName,
                        StaticJavaParser.parseExpression("0"));
                VariableDeclarationExpr decl = new VariableDeclarationExpr(vd);
                body.addStatement(0, new ExpressionStmt(decl));
            }
        });
    }

    /**
     * Overrides the visit for BlockStmt to insert logging calls after each assignment.
     */
    @Override
    public Visitable visit(BlockStmt block, Void arg) {
        super.visit(block, arg);
        NodeList<Statement> newStatements = new NodeList<>();
        MethodDeclaration methodDecl = block.findAncestor(MethodDeclaration.class).orElse(null);

        for (Statement stmt : block.getStatements()) {
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                AssignExpr ae = stmt.asExpressionStmt().getExpression().asAssignExpr();
                if (ae.getTarget().isNameExpr()) {
                    String varName = ae.getTarget().asNameExpr().getNameAsString();
                    if (methodDecl != null) {
                        ensureDeclarationInMethod(methodDecl, varName);
                    }
                    newStatements.add(stmt);
                    MethodCallExpr logCall = createLogCall(varName);
                    newStatements.add(new ExpressionStmt(logCall));
                    continue;
                }
            }
            newStatements.add(stmt);
        }
        block.setStatements(newStatements);
        return block;
    }

    /**
     * Overrides the visit for ConditionalExpr to lift subexpressions into temporary variables.
     */
    @Override
    public Visitable visit(ConditionalExpr ce, Void arg) {
        Expression newCond = (Expression) ce.getCondition().accept(this, arg);
        Expression newThen = (Expression) ce.getThenExpr().accept(this, arg);
        Expression newElse = (Expression) ce.getElseExpr().accept(this, arg);
        ce.setCondition(newCond);
        ce.setThenExpr(newThen);
        ce.setElseExpr(newElse);

        String tempCond = uniqueTemp("tempCond");
        String tempThen = uniqueTemp("tempThen");
        String tempElse = uniqueTemp("tempElse");
        String tempResult = uniqueTemp("tempRes");

        VariableDeclarationExpr condDecl = new VariableDeclarationExpr(
                new VariableDeclarator(StaticJavaParser.parseType("boolean"), tempCond, newCond.clone())
        );
        VariableDeclarationExpr thenDecl = new VariableDeclarationExpr(
                new VariableDeclarator(StaticJavaParser.parseType("int"), tempThen, newThen.clone())
        );
        VariableDeclarationExpr elseDecl = new VariableDeclarationExpr(
                new VariableDeclarator(StaticJavaParser.parseType("int"), tempElse, newElse.clone())
        );

        MethodCallExpr logCond = createLogCall(tempCond);
        MethodCallExpr logThen = createLogCall(tempThen);
        MethodCallExpr logElse = createLogCall(tempElse);

        ConditionalExpr newCondExpr = new ConditionalExpr(
                new NameExpr(tempCond),
                new NameExpr(tempThen),
                new NameExpr(tempElse)
        );

        VariableDeclarationExpr resultDecl = new VariableDeclarationExpr(
                new VariableDeclarator(new PrimitiveType(PrimitiveType.Primitive.INT), tempResult, newCondExpr)
        );
        MethodCallExpr logResult = createLogCall(tempResult);

        BlockStmt parentBlock = ce.findAncestor(BlockStmt.class).orElse(new BlockStmt());
        AtomicInteger indexHolder = new AtomicInteger(parentBlock.getStatements().size());
        ce.findAncestor(ExpressionStmt.class).ifPresent(exprStmt ->
                indexHolder.set(parentBlock.getStatements().indexOf(exprStmt))
        );
        parentBlock.addStatement(indexHolder.get(), new ExpressionStmt(condDecl));
        parentBlock.addStatement(indexHolder.get() + 1, new ExpressionStmt(logCond));
        parentBlock.addStatement(indexHolder.get() + 2, new ExpressionStmt(thenDecl));
        parentBlock.addStatement(indexHolder.get() + 3, new ExpressionStmt(logThen));
        parentBlock.addStatement(indexHolder.get() + 4, new ExpressionStmt(elseDecl));
        parentBlock.addStatement(indexHolder.get() + 5, new ExpressionStmt(logElse));
        parentBlock.addStatement(indexHolder.get() + 6, new ExpressionStmt(resultDecl));
        parentBlock.addStatement(indexHolder.get() + 7, new ExpressionStmt(logResult));

        return new NameExpr(tempResult);
    }

    /**
     * Creates a logging call expression: CollectOut.logVariable("varName", varName).
     */
    private MethodCallExpr createLogCall(String varName) {
        MethodCallExpr logCall = new MethodCallExpr(new NameExpr("CollectOut"), "logVariable");
        NodeList<Expression> args = new NodeList<>();
        args.add(new StringLiteralExpr(varName));
        args.add(new NameExpr(varName));
        logCall.setArguments(args);
        return logCall;
    }
}
