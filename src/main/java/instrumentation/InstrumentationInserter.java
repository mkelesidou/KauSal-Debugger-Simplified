package instrumentation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class InstrumentationInserter extends ModifierVisitor<Void> {

    public static void main(String[] args) {
        try {
            // Specify the path to the GSA-transformed source file.
            String sourcePath = "src/main/resources/transformation/gsas/transformed_simple_example.java";
            // Read the source code.
            String sourceCode = new String(Files.readAllBytes(Paths.get(sourcePath)));
            // Instrument the source code.
            String instrumentedSource = transformSource(sourceCode);
            // Write the instrumented source into a new file.
            String outputPath = "src/main/resources/instrumentation/instrumented_output.java";
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
                writer.println(instrumentedSource);
            }
            System.out.println("Instrumentation complete. Instrumented file written to: " + outputPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Generate unique temporary variable names.
    private String uniqueTemp(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /**
     * Instead of instrumenting assignments in visit(AssignExpr),
     * we override visit(BlockStmt) to insert a logging call after every
     * stand-alone assignment statement.
     */
    @Override
    public Visitable visit(BlockStmt block, Void arg) {
        // First, visit child nodes.
        super.visit(block, arg);
        NodeList<Statement> newStatements = new NodeList<>();
        for (Statement stmt : block.getStatements()) {
            newStatements.add(stmt);
            // If this statement is an ExpressionStmt containing an assignment...
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                Expression expr = exprStmt.getExpression();
                if (expr.isAssignExpr()) {
                    AssignExpr ae = expr.asAssignExpr();
                    if (ae.getTarget().isNameExpr()) {
                        String varName = ae.getTarget().asNameExpr().getNameAsString();
                        // Insert a logging call after the assignment.
                        MethodCallExpr logCall = createLogCall(varName);
                        newStatements.add(new ExpressionStmt(logCall));
                    }
                }
            }
        }
        block.setStatements(newStatements);
        return block;
    }

    /**
     * Instrument conditional expressions by lifting subexpressions into temporaries.
     * (This part remains unchanged.)
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
        parentBlock.addStatement(indexHolder.get(), condDecl);
        parentBlock.addStatement(indexHolder.get() + 1, new ExpressionStmt(logCond));
        parentBlock.addStatement(indexHolder.get() + 2, thenDecl);
        parentBlock.addStatement(indexHolder.get() + 3, new ExpressionStmt(logThen));
        parentBlock.addStatement(indexHolder.get() + 4, elseDecl);
        parentBlock.addStatement(indexHolder.get() + 5, new ExpressionStmt(logElse));
        parentBlock.addStatement(indexHolder.get() + 6, resultDecl);
        parentBlock.addStatement(indexHolder.get() + 7, new ExpressionStmt(logResult));

        return new NameExpr(tempResult);
    }

    /**
     * Creates a logging call expression: CollectOut.logVariable("varName", varName);
     */
    private MethodCallExpr createLogCall(String varName) {
        MethodCallExpr logCall = new MethodCallExpr(new NameExpr("CollectOut"), "logVariable");
        NodeList<Expression> args = new NodeList<>();
        args.add(new StringLiteralExpr(varName));
        args.add(new NameExpr(varName));
        logCall.setArguments(args);
        return logCall;
    }

    /**
     * Parses and transforms the source code.
     */
    public static String transformSource(String sourceCode) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        InstrumentationInserter inserter = new InstrumentationInserter();
        inserter.visit(cu, null);
        return cu.toString();
    }
}
