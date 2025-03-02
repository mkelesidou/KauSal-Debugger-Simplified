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
 * condition expressions into dedicated predicate variables. This is useful for instrumenting
 * or analyzing control flow in a program.
 *
 * The transformations include:
 *  - If-statements: The condition is evaluated into a final boolean predicate variable,
 *    which replaces the original condition.
 *  - While-loops: The condition is stored in a non-final boolean predicate variable, which is updated
 *    at the end of each loop iteration.
 *  - For-loops: Converted into equivalent while-loops so that the loop condition is stored
 *    and updated via a predicate variable.
 *  - Switch-statements: The selector expression is hoisted into a final variable.
 */
public class PredicateTransformer extends ModifierVisitor<Void> {

    // Counter for generating unique predicate variable names.
    private AtomicInteger predicateCounter = new AtomicInteger(1);

    /**
     * Transforms an if-statement by hoisting its condition into a new final boolean variable.
     * The variable is declared immediately before the if-statement and then used in place of the original condition.
     *
     * @param ifStmt the if-statement to transform.
     * @param arg    not used.
     * @return the transformed if-statement.
     */
    @Override
    public Visitable visit(IfStmt ifStmt, Void arg) {
        Expression originalCondition = ifStmt.getCondition();
        String predicateName = "P" + predicateCounter.getAndIncrement();

        // Create a final boolean variable to hold the condition.
        VariableDeclarationExpr predicateDeclaration = new VariableDeclarationExpr(
                StaticJavaParser.parseType("boolean"), predicateName);
        predicateDeclaration.addModifier(Modifier.Keyword.FINAL);
        predicateDeclaration.getVariables().get(0).setInitializer(originalCondition.clone());

        // Insert the declaration immediately before the if-statement if the parent is a block.
        if (ifStmt.getParentNode().isPresent() && ifStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) ifStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(ifStmt);
            parentBlock.addStatement(index, predicateDeclaration);
        }

        // Replace the if-statement's condition with the predicate variable.
        ifStmt.setCondition(new NameExpr(predicateName));
        System.out.println("Transformed if-condition at line " +
                ifStmt.getBegin().map(p -> p.line).orElse(-1) + " into boolean variable: " + predicateName);

        return super.visit(ifStmt, arg);
    }

    /**
     * Transforms a while-loop so that its condition is stored in a non-final predicate variable.
     * The variable is declared before the loop, replaces the original condition, and is updated at the end of each iteration.
     *
     * @param whileStmt the while-loop to transform.
     * @param arg       not used.
     * @return the transformed while-loop.
     */
    @Override
    public Visitable visit(WhileStmt whileStmt, Void arg) {
        Expression originalCondition = whileStmt.getCondition();
        String predicateName = "P" + predicateCounter.getAndIncrement();

        // Create a non-final boolean variable to hold the condition.
        VariableDeclarationExpr predicateDeclaration = new VariableDeclarationExpr(
                StaticJavaParser.parseType("boolean"), predicateName);
        predicateDeclaration.getVariables().get(0).setInitializer(originalCondition.clone());

        // Insert the declaration before the while-loop if the parent is a block.
        if (whileStmt.getParentNode().isPresent() && whileStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) whileStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(whileStmt);
            parentBlock.addStatement(index, predicateDeclaration);
        }

        // Replace the while-loop's condition with the predicate variable.
        whileStmt.setCondition(new NameExpr(predicateName));

        // Ensure the loop body is a block, then add an update statement at the end to reassign the predicate.
        Statement originalBody = whileStmt.getBody();
        BlockStmt loopBodyBlock;
        if (originalBody.isBlockStmt()) {
            loopBodyBlock = originalBody.asBlockStmt();
        } else {
            loopBodyBlock = new BlockStmt();
            loopBodyBlock.addStatement(originalBody);
        }
        ExpressionStmt updatePredicate = new ExpressionStmt(
                new AssignExpr(new NameExpr(predicateName), originalCondition.clone(), AssignExpr.Operator.ASSIGN));
        loopBodyBlock.addStatement(updatePredicate);
        whileStmt.setBody(loopBodyBlock);

        System.out.println("Transformed while-condition at line " +
                whileStmt.getBegin().map(p -> p.line).orElse(-1) + " into non-final variable: " + predicateName);

        return super.visit(whileStmt, arg);
    }

    /**
     * Transforms a for-loop by converting it into an equivalent while-loop.
     * The loop's initialization remains in place, and the condition is hoisted into a predicate variable
     * that is updated at each iteration.
     *
     * @param forStmt the for-loop to transform.
     * @param arg     not used.
     * @return a block statement containing the transformed loop.
     */
    @Override
    public Visitable visit(ForStmt forStmt, Void arg) {
        if (forStmt.getCompare().isPresent()) {
            NodeList<Expression> initializations = forStmt.getInitialization();
            Expression originalCondition = forStmt.getCompare().get();
            NodeList<Expression> updates = forStmt.getUpdate();
            Statement originalBody = forStmt.getBody();

            String predicateName = "P" + predicateCounter.getAndIncrement();

            // Create a non-final boolean variable to hold the for-loop condition.
            VariableDeclarationExpr predicateDeclaration = new VariableDeclarationExpr(
                    StaticJavaParser.parseType("boolean"), predicateName);
            predicateDeclaration.getVariables().get(0).setInitializer(originalCondition.clone());

            // Prepare the loop body: ensure it's a block and add update expressions.
            BlockStmt newLoopBody;
            if (originalBody.isBlockStmt()) {
                newLoopBody = originalBody.asBlockStmt();
            } else {
                newLoopBody = new BlockStmt();
                newLoopBody.addStatement(originalBody);
            }
            // Append the original update expressions.
            for (Expression update : updates) {
                newLoopBody.addStatement(new ExpressionStmt(update));
            }
            // Append the update for the predicate variable.
            ExpressionStmt updatePredicate = new ExpressionStmt(
                    new AssignExpr(new NameExpr(predicateName), originalCondition.clone(), AssignExpr.Operator.ASSIGN));
            newLoopBody.addStatement(updatePredicate);

            // Construct the while-loop using the predicate variable.
            WhileStmt transformedWhile = new WhileStmt(new NameExpr(predicateName), newLoopBody);

            // Create a new block to contain initializations, the predicate declaration, and the while-loop.
            BlockStmt newBlock = new BlockStmt();
            for (Expression init : initializations) {
                newBlock.addStatement(init);
            }
            newBlock.addStatement(predicateDeclaration);
            newBlock.addStatement(transformedWhile);

            System.out.println("Transformed for-loop at line " +
                    forStmt.getBegin().map(p -> p.line).orElse(-1) +
                    " into while-loop with predicate variable: " + predicateName);
            return newBlock;
        }
        return super.visit(forStmt, arg);
    }

    /**
     * Transforms a switch statement by hoisting the selector expression into a final variable.
     * Assumes the selector is of type int (adjust the type if needed).
     *
     * @param switchStmt the switch statement to transform.
     * @param arg        not used.
     * @return the transformed switch statement.
     */
    @Override
    public Visitable visit(SwitchStmt switchStmt, Void arg) {
        Expression originalSelector = switchStmt.getSelector();
        String predicateName = "S" + predicateCounter.getAndIncrement();

        // Create a final int variable for the switch selector.
        VariableDeclarationExpr selectorDeclaration = new VariableDeclarationExpr(
                StaticJavaParser.parseType("int"), predicateName);
        selectorDeclaration.addModifier(Modifier.Keyword.FINAL);
        selectorDeclaration.getVariables().get(0).setInitializer(originalSelector.clone());

        // Insert the declaration before the switch statement if the parent is a block.
        if (switchStmt.getParentNode().isPresent() && switchStmt.getParentNode().get() instanceof BlockStmt) {
            BlockStmt parentBlock = (BlockStmt) switchStmt.getParentNode().get();
            int index = parentBlock.getStatements().indexOf(switchStmt);
            parentBlock.addStatement(index, selectorDeclaration);
        }

        // Replace the selector with the newly declared variable.
        switchStmt.setSelector(new NameExpr(predicateName));
        System.out.println("Transformed switch selector at line " +
                switchStmt.getBegin().map(p -> p.line).orElse(-1) + " into variable: " + predicateName);
        return super.visit(switchStmt, arg);
    }

    /**
     * Transforms the given source file by applying the predicate transformations and saves the output.
     *
     * @param sourceFile the original source file.
     * @param outputFile the file where the transformed source will be saved.
     * @throws IOException if file reading or writing fails.
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
     *
     * @param args command-line arguments.
     * @throws Exception if an error occurs during transformation.
     */
    public static void main(String[] args) throws Exception {
        // Define the input source file and the output destination.
        File sourceFile = new File("src/main/java/examples/SimpleExample.java");
        File outputFile = new File("src/main/resources/sanalysis/predicate_transformation/transformed_simple_example.java");
        transformFile(sourceFile, outputFile);
    }
}
