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
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class InstrumentationInserter extends ModifierVisitor<Void> {
    private static final Logger logger = LoggerFactory.getLogger(InstrumentationInserter.class);

    // Default paths used only if no command-line arguments are provided
    private static final String DEFAULT_INPUT_FILE_PATH = "src/main/resources/transformation/gsas/transformed_buggy_example.java";
    private static final String DEFAULT_OUTPUT_FILE_PATH = "src/main/resources/instrumentation/BuggyExample.java";

    // Set to track unique temporary names.
    private final Set<String> usedNames = new HashSet<>();

    // Marker string to indicate that a node has been instrumented.
    private static final String INSTRUMENTATION_MARKER = "//[instrumented]";

    // Map to keep track of instrumented variable names per method.
    private final Map<MethodDeclaration, Set<String>> instrumentedVars = new HashMap<>();

    public static void main(String[] args) {
        try {
            String sourcePath;
            String outputPath;
            
            if (args.length >= 2) {
                sourcePath = args[0];
                outputPath = args[1];
            } else if (args.length == 1) {
                sourcePath = args[0];
                outputPath = sourcePath.replace(".java", "_instrumented.java");
                logger.info("No output path specified, using default: {}", outputPath);
            } else {
                logger.info("Usage: java InstrumentationInserter <source_file> [<output_file>]");
                logger.info("Using default paths for testing purposes only.");
                sourcePath = DEFAULT_INPUT_FILE_PATH;
                outputPath = DEFAULT_OUTPUT_FILE_PATH;
            }
            
            logger.info("Reading source from: {}", sourcePath);
            String sourceCode = new String(Files.readAllBytes(Paths.get(sourcePath)));
            
            // If source already appears instrumented, skip instrumentation.
            if (sourceCode.contains("CollectOut.logVariable(")) {
                logger.info("Source already instrumented. Skipping instrumentation.");
                Files.write(Paths.get(outputPath), sourceCode.getBytes());
                return;
            }
            
            String instrumentedSource = transformSource(sourceCode);
            Files.write(Paths.get(outputPath), instrumentedSource.getBytes());
            logger.info("Instrumentation complete. Instrumented file written to: {}", outputPath);
        } catch (Exception e) {
            logger.error("Instrumentation failed.", e);
        }
    }

    /**
     * Parses and instruments the source code.
     */
    public static String transformSource(String sourceCode) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        // Force package to "instrumentation"
        cu.setPackageDeclaration("instrumentation");
        InstrumentationInserter inserter = new InstrumentationInserter();
        inserter.visit(cu, null);
        // Optionally add a marker comment at the beginning.
        cu.addOrphanComment(new com.github.javaparser.ast.comments.LineComment(INSTRUMENTATION_MARKER));
        return cu.toString();
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
     * Returns true if the given string already contains the instrumentation marker.
     */
    private boolean alreadyInstrumented(String nodeString) {
        return nodeString.contains(INSTRUMENTATION_MARKER) || nodeString.contains("CollectOut.logVariable(");
    }

    /**
     * Checks if a variable with the given name is declared in the provided statements.
     */
    private boolean isDeclared(NodeList<Statement> statements, String varName) {
        for (Statement stmt : statements) {
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                VariableDeclarationExpr vde = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getNameAsString().equals(varName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Inserts a declaration for varName (of type int with initialiser 0) at the beginning of the enclosing method,
     * if not already declared.
     */
    private void ensureDeclarationInMethod(MethodDeclaration methodDecl, String varName) {
        Set<String> declared = instrumentedVars.computeIfAbsent(methodDecl, k -> new HashSet<>());
        if (declared.contains(varName)) {
            return;
        }
        methodDecl.getBody().ifPresent(body -> {
            if (!isDeclared(body.getStatements(), varName)) {
                VariableDeclarator vd = new VariableDeclarator(
                        StaticJavaParser.parseType("int"), varName, StaticJavaParser.parseExpression("0")
                );
                VariableDeclarationExpr decl = new VariableDeclarationExpr(vd);
                body.addStatement(0, new ExpressionStmt(decl));
            }
        });
        declared.add(varName);
    }

    /**
     * Override visit for MethodDeclaration.
     * If the method body already contains a labeled statement with label "methodBody",
     * then we assume this method is already instrumented and skip its children.
     */
    @Override
    public Visitable visit(MethodDeclaration md, Void arg) {
        if (md.getBody().isPresent()) {
            boolean alreadyInMethodBody = md.getBody().get().findFirst(LabeledStmt.class,
                    ls -> "methodBody".equals(ls.getLabel().asString())).isPresent();
            if (alreadyInMethodBody) {
                return md;
            }
        }
        return super.visit(md, arg);
    }

    /**
     * Visitor for variable declarations: Inserts a logging call after each declaration,
     * unless the node already appears instrumented.
     */
    @Override
    public Visitable visit(VariableDeclarationExpr vde, Void arg) {
        if (alreadyInstrumented(vde.toString())) {
            return vde;
        }
        super.visit(vde, arg);
        Statement parentStmt = vde.findAncestor(Statement.class).orElse(null);
        if (parentStmt != null) {
            BlockStmt block = parentStmt.findAncestor(BlockStmt.class).orElse(null);
            if (block != null) {
                int idx = block.getStatements().indexOf(parentStmt);
                NodeList<Statement> newStatements = new NodeList<>();
                newStatements.add(parentStmt);
                MethodDeclaration methodDecl = block.findAncestor(MethodDeclaration.class).orElse(null);
                for (VariableDeclarator varDecl : vde.getVariables()) {
                    String varName = varDecl.getNameAsString();
                    if (methodDecl != null) {
                        ensureDeclarationInMethod(methodDecl, varName);
                    }
                    MethodCallExpr logCall = createLogCall(varName);
                    newStatements.add(new ExpressionStmt(logCall));
                }
                newStatements.get(0).setLineComment(INSTRUMENTATION_MARKER);
                
                // Clone the original statements to avoid concurrent modification
                NodeList<Statement> blockStatements = new NodeList<>(block.getStatements());
                
                // Create a new list to replace the original statements
                NodeList<Statement> replacementStatements = new NodeList<>();
                
                // Add all statements before the current one
                for (int i = 0; i < idx; i++) {
                    replacementStatements.add(blockStatements.get(i));
                }
                
                // Add our new statements
                replacementStatements.addAll(newStatements);
                
                // Add all statements after the current one
                for (int i = idx + 1; i < blockStatements.size(); i++) {
                    replacementStatements.add(blockStatements.get(i));
                }
                
                // Set the modified statements all at once
                block.setStatements(replacementStatements);
            }
        }
        return vde;
    }

    /**
     * Visitor for blocks: Inserts logging calls after assignment statements,
     * unless they already appear instrumented.
     */
    @Override
    public Visitable visit(BlockStmt block, Void arg) {
        if (block.findAncestor(LabeledStmt.class)
                .filter(ls -> "methodBody".equals(ls.getLabel().asString())).isPresent()) {
            return block;
        }
        
        // First visit all children
        super.visit(block, arg);
        
        // Create a new list to avoid concurrent modification
        NodeList<Statement> oldStatements = new NodeList<>(block.getStatements());
        NodeList<Statement> newStatements = new NodeList<>();
        MethodDeclaration methodDecl = block.findAncestor(MethodDeclaration.class).orElse(null);
        
        // Process all statements first and build the new list
        for (Statement stmt : oldStatements) {
            newStatements.add(stmt);
            
            // Check if we need to add instrumentation after this statement
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                String stmtStr = stmt.toString();
                if (!alreadyInstrumented(stmtStr)) {
                    AssignExpr ae = stmt.asExpressionStmt().getExpression().asAssignExpr();
                    if (ae.getTarget().isNameExpr()) {
                        String varName = ae.getTarget().asNameExpr().getNameAsString();
                        if (methodDecl != null) {
                            ensureDeclarationInMethod(methodDecl, varName);
                        }
                        MethodCallExpr logCall = createLogCall(varName);
                        newStatements.add(new ExpressionStmt(logCall));
                    }
                }
            }
        }
        
        // Set the new list of statements all at once
        block.setStatements(newStatements);
        return block;
    }

    /**
     * Visitor for conditional expressions: Lifts subexpressions into temporary variables and inserts logging.
     */
    @Override
    public Visitable visit(ConditionalExpr ce, Void arg) {
        if (alreadyInstrumented(ce.toString())) {
            return ce;
        }
        
        // First visit and update children
        Expression newCond = (Expression) ce.getCondition().accept(this, arg);
        Expression newThen = (Expression) ce.getThenExpr().accept(this, arg);
        Expression newElse = (Expression) ce.getElseExpr().accept(this, arg);
        ce.setCondition(newCond);
        ce.setThenExpr(newThen);
        ce.setElseExpr(newElse);

        // Create temporary variables
        String tempCond = uniqueTemp("tempCond");
        String tempThen = uniqueTemp("tempThen");
        String tempElse = uniqueTemp("tempElse");
        String tempRes = uniqueTemp("tempRes");

        // Create variable declarations and logging calls
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
                new NameExpr(tempCond), new NameExpr(tempThen), new NameExpr(tempElse)
        );
        VariableDeclarationExpr resultDecl = new VariableDeclarationExpr(
                new VariableDeclarator(new PrimitiveType(PrimitiveType.Primitive.INT), tempRes, newCondExpr)
        );
        MethodCallExpr logResult = createLogCall(tempRes);

        BlockStmt parentBlock = ce.findAncestor(BlockStmt.class).orElse(null);
        if (parentBlock != null) {
            // Find the insertion index
            int insertIndex = 0;
            if (ce.findAncestor(ExpressionStmt.class).isPresent()) {
                ExpressionStmt parent = ce.findAncestor(ExpressionStmt.class).get();
                insertIndex = parentBlock.getStatements().indexOf(parent);
                if (insertIndex < 0) insertIndex = 0;
            }
            
            // Create a new list of statements to avoid concurrent modification
            NodeList<Statement> oldStatements = new NodeList<>(parentBlock.getStatements());
            NodeList<Statement> newStatements = new NodeList<>();
            
            // Add statements before the insertion point
            for (int i = 0; i < insertIndex; i++) {
                newStatements.add(oldStatements.get(i));
            }
            
            // Add our new instrumentation statements
            ExpressionStmt condStmt = new ExpressionStmt(condDecl);
            condStmt.setLineComment(INSTRUMENTATION_MARKER);
            newStatements.add(condStmt);
            newStatements.add(new ExpressionStmt(logCond));
            newStatements.add(new ExpressionStmt(thenDecl));
            newStatements.add(new ExpressionStmt(logThen));
            newStatements.add(new ExpressionStmt(elseDecl));
            newStatements.add(new ExpressionStmt(logElse));
            newStatements.add(new ExpressionStmt(resultDecl));
            newStatements.add(new ExpressionStmt(logResult));
            
            // Add remaining statements
            for (int i = insertIndex; i < oldStatements.size(); i++) {
                newStatements.add(oldStatements.get(i));
            }
            
            // Update the block with the new statements
            parentBlock.setStatements(newStatements);
        }

        return new NameExpr(tempRes);
    }

    /**
     * Creates a logging call: CollectOut.logVariable("varName", varName).
     */
    private MethodCallExpr createLogCall(String varName) {
        MethodCallExpr logCall = new MethodCallExpr(new NameExpr("CollectOut"), "logVariable");
        NodeList<Expression> args = new NodeList<>();
        args.add(new StringLiteralExpr(varName));
        args.add(new NameExpr(varName));
        logCall.setArguments(args);
        return logCall;
    }

    // SingleExitTransformer: Convert non-void methods to single-exit form.
    public static class SingleExitTransformer extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(MethodDeclaration md, Void arg) {
            if (md.getType().isVoidType() || !md.getBody().isPresent()) {
                return super.visit(md, arg);
            }
            BlockStmt originalBody = md.getBody().get();
            String exitVar = "_exit";
            VariableDeclarationExpr exitDecl = new VariableDeclarationExpr(md.getType(), exitVar);
            LabeledStmt labeledBody = new LabeledStmt("methodBody", originalBody);
            originalBody.accept(new ReturnReplacer(exitVar, "methodBody"), null);
            BlockStmt newBody = new BlockStmt();
            newBody.addStatement(exitDecl);
            newBody.addStatement(labeledBody);
            newBody.addStatement(new ReturnStmt(new NameExpr(exitVar)));
            md.setBody(newBody);
            return md;
        }
    }

    private static class ReturnReplacer extends ModifierVisitor<Void> {
        private final String exitVar;
        private final String label;
        public ReturnReplacer(String exitVar, String label) {
            this.exitVar = exitVar;
            this.label = label;
        }
        @Override
        public Visitable visit(ReturnStmt rs, Void arg) {
            List<Statement> replacement = new ArrayList<>();
            if (rs.getExpression().isPresent()) {
                Expression expr = rs.getExpression().get();
                AssignExpr assign = new AssignExpr(new NameExpr(exitVar), expr, AssignExpr.Operator.ASSIGN);
                replacement.add(new ExpressionStmt(assign));
            }
            replacement.add(new BreakStmt(label));
            return new BlockStmt(new NodeList<>(replacement));
        }
    }

    public static class WhileLoopConditionFixer extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(WhileStmt ws, Void arg) {
            return super.visit(ws, arg);
        }
    }

    public static class ExpressionRewriter extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(ConditionalExpr ce, Void arg) {
            // Visit the children first
            super.visit(ce, arg);
            
            // Create a unique temporary variable to hold the condition
            String tempCond = "tempCond_" + System.currentTimeMillis();
            
            // Get the closest block statement
            BlockStmt parentBlock = ce.findAncestor(BlockStmt.class).orElse(null);
            
            if (parentBlock != null) {
                // Create a variable declaration for the condition
                VariableDeclarationExpr condDecl = new VariableDeclarationExpr(
                    new VariableDeclarator(
                        StaticJavaParser.parseType("boolean"), 
                        tempCond, 
                        ce.getCondition().clone()
                    )
                );
                
                // Find an insertion point
                int insertIndex = 0;
                if (ce.findAncestor(ExpressionStmt.class).isPresent()) {
                    ExpressionStmt parent = ce.findAncestor(ExpressionStmt.class).get();
                    insertIndex = parentBlock.getStatements().indexOf(parent);
                    if (insertIndex < 0) insertIndex = 0;
                }
                
                // Insert the temporary variable declaration
                parentBlock.addStatement(insertIndex, new ExpressionStmt(condDecl));
                
                // Replace the original condition with our temporary variable
                ce.setCondition(new NameExpr(tempCond));
            }
            
            return ce;
        }
    }
}
