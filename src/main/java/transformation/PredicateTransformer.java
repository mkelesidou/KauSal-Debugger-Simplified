package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * PredicateTransformer transforms control statements in Java source code by hoisting their
 * condition expressions into dedicated predicate variables.
 */
public class PredicateTransformer extends ModifierVisitor<Void> {

    // Counter for generating unique predicate variable names.
    private AtomicInteger predicateCounter = new AtomicInteger(1);

    /**
     * Transforms an if-statement by hoisting its condition into a new final boolean variable.
     */
    @Override
    public Visitable visit(IfStmt ifStmt, Void arg) {
        // Skip if already transformed
        if (ifStmt.getCondition() instanceof NameExpr && 
            ((NameExpr)ifStmt.getCondition()).getNameAsString().startsWith("P")) {
            return super.visit(ifStmt, arg);
        }

        // Create a final boolean predicate for the condition
        Expression condition = ifStmt.getCondition().clone();
        String predicateName = "P" + predicateCounter.getAndIncrement();
        
        // Create variable declaration with final modifier
        VariableDeclarationExpr predicateDecl = new VariableDeclarationExpr(
            StaticJavaParser.parseType("boolean"), predicateName);
        predicateDecl.addModifier(Modifier.Keyword.FINAL);
        predicateDecl.getVariables().get(0).setInitializer(condition);
        
        // Add declaration before if statement
        if (ifStmt.getParentNode().isPresent() && ifStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) ifStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(ifStmt);
            if (index >= 0) {
                parentBlock.getStatements().add(index, new ExpressionStmt(predicateDecl));
            }
        }
        
        // Replace condition with predicate variable
        ifStmt.setCondition(new NameExpr(predicateName));
        
        // Continue normal traversal
        return super.visit(ifStmt, arg);
    }

    /**
     * Transforms a while-loop by hoisting its condition into a non-final boolean variable.
     */
    @Override
    public Visitable visit(WhileStmt whileStmt, Void arg) {
        // Skip if already transformed
        if (whileStmt.getCondition() instanceof NameExpr && 
            ((NameExpr)whileStmt.getCondition()).getNameAsString().startsWith("P")) {
            return super.visit(whileStmt, arg);
        }
        
        // Create a non-final boolean predicate for the condition
        Expression condition = whileStmt.getCondition().clone();
        String predicateName = "P" + predicateCounter.getAndIncrement();
        
        // Create variable declaration without final modifier
        VariableDeclarationExpr predicateDecl = new VariableDeclarationExpr(
            StaticJavaParser.parseType("boolean"), predicateName);
        predicateDecl.getVariables().get(0).setInitializer(condition);
        
        // Add declaration before while loop
        if (whileStmt.getParentNode().isPresent() && whileStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) whileStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(whileStmt);
            if (index >= 0) {
                parentBlock.getStatements().add(index, new ExpressionStmt(predicateDecl));
            }
        }
        
        // Replace condition with predicate variable
        whileStmt.setCondition(new NameExpr(predicateName));
        
        // Make sure the body is a block statement
        Statement body = whileStmt.getBody();
        BlockStmt blockBody;
        if (!(body instanceof BlockStmt)) {
            blockBody = new BlockStmt();
            blockBody.addStatement(body);
            whileStmt.setBody(blockBody);
        } else {
            blockBody = (BlockStmt) body;
        }
        
        // Add assignment to update predicate at end of loop
        AssignExpr updateExpr = new AssignExpr(
            new NameExpr(predicateName), 
            condition.clone(), 
            AssignExpr.Operator.ASSIGN);
        blockBody.addStatement(new ExpressionStmt(updateExpr));
        
        // Continue normal traversal
        return super.visit(whileStmt, arg);
    }

    /**
     * Transforms a for-loop into a while loop by hoisting its condition.
     */
    @Override
    public Visitable visit(ForStmt forStmt, Void arg) {
        if (!forStmt.getCompare().isPresent()) {
            return super.visit(forStmt, arg);
        }
        
        if (forStmt.getCompare().get() instanceof NameExpr && 
            ((NameExpr)forStmt.getCompare().get()).getNameAsString().startsWith("P")) {
            // If already transformed, don't transform again
            return super.visit(forStmt, arg);
        }
        
        // Extract components of the for loop
        NodeList<Expression> initialization = forStmt.getInitialization();
        Expression condition = forStmt.getCompare().get().clone();
        NodeList<Expression> update = forStmt.getUpdate();
        Statement body = forStmt.getBody();
        
        // Create a new block to hold everything
        BlockStmt newBlock = new BlockStmt();
        
        // Add initializations first
        for (Expression init : initialization) {
            newBlock.addStatement(new ExpressionStmt(init.clone()));
        }
        
        // Create predicate variable
        String predicateName = "P" + predicateCounter.getAndIncrement();
        VariableDeclarationExpr predicateDecl = new VariableDeclarationExpr(
            StaticJavaParser.parseType("boolean"), predicateName);
        predicateDecl.getVariables().get(0).setInitializer(condition);
        newBlock.addStatement(new ExpressionStmt(predicateDecl));
        
        // Create while loop body
        BlockStmt whileBody;
        if (body instanceof BlockStmt) {
            whileBody = (BlockStmt) body.clone();
        } else {
            whileBody = new BlockStmt();
            whileBody.addStatement(body.clone());
        }
        
        // Add update expressions to end of while loop body
        for (Expression expr : update) {
            whileBody.addStatement(new ExpressionStmt(expr.clone()));
        }
        
        // Add predicate update at end of loop body
        AssignExpr updateExpr = new AssignExpr(
            new NameExpr(predicateName), 
            condition.clone(), 
            AssignExpr.Operator.ASSIGN);
        whileBody.addStatement(new ExpressionStmt(updateExpr));
        
        // Create while statement with predicate as condition
        WhileStmt whileStmt = new WhileStmt(new NameExpr(predicateName), whileBody);
        newBlock.addStatement(whileStmt);
        
        // Let the visitor framework handle the rest of the traversal
        return super.visit(newBlock, arg);
    }

    /**
     * Transforms a switch statement by hoisting its selector into a final variable.
     */
    @Override
    public Visitable visit(SwitchStmt switchStmt, Void arg) {
        // Skip if already transformed
        if (switchStmt.getSelector() instanceof NameExpr && 
            ((NameExpr)switchStmt.getSelector()).getNameAsString().startsWith("S")) {
            return super.visit(switchStmt, arg);
        }
        
        // Create a final selector variable
        Expression selector = switchStmt.getSelector().clone();
        String selectorName = "S" + predicateCounter.getAndIncrement();
        
        // Create variable declaration with final modifier
        VariableDeclarationExpr selectorDecl = new VariableDeclarationExpr(
            StaticJavaParser.parseType("int"), selectorName);
        selectorDecl.addModifier(Modifier.Keyword.FINAL);
        selectorDecl.getVariables().get(0).setInitializer(selector);
        
        // Add declaration before switch statement
        if (switchStmt.getParentNode().isPresent() && switchStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) switchStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(switchStmt);
            if (index >= 0) {
                parentBlock.getStatements().add(index, new ExpressionStmt(selectorDecl));
            }
        }
        
        // Replace selector with variable
        switchStmt.setSelector(new NameExpr(selectorName));
        
        // Continue normal traversal
        return super.visit(switchStmt, arg);
    }

    /**
     * Visit a block statement and process all its statements.
     */
    @Override
    public Visitable visit(BlockStmt block, Void arg) {
        // Let the visitor framework handle the traversal
        return super.visit(block, arg);
    }

    /**
     * Transforms the given source file by applying the predicate transformations.
     */
    public static void transformFile(File sourceFile, File outputFile) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(sourceFile);
        PredicateTransformer transformer = new PredicateTransformer();
        transformer.visit(cu, null);
        Files.write(outputFile.toPath(), cu.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Transformed source saved to: " + outputFile.getAbsolutePath());
    }

    /**
     * Main method to perform the predicate transformation on a sample source file.
     */
    public static void main(String[] args) throws Exception {
        File sourceFile = new File("src/main/java/examples/BuggyExample.java");
        File outputFile = new File("src/main/resources/transformation/predicates/transformed_buggy_example.java");
        transformFile(sourceFile, outputFile);
    }
}
