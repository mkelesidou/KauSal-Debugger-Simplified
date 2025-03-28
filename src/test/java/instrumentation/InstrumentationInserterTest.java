package instrumentation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import instrumentation.InstrumentationInserter;

public class InstrumentationInserterTest {

    @Test
    public void testTransformSource_InstrumentsVariableDeclaration() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int a = 5;\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        // Check that the instrumentation marker is present.
        assertTrue(instrumented.contains("//[instrumented]"), "Marker comment should be inserted.");
        // Check that the package declaration is changed to 'instrumentation'.
        assertTrue(instrumented.contains("package instrumentation"), "Package should be set to 'instrumentation'.");
        // Check that a log call is inserted after the variable declaration.
        assertTrue(instrumented.contains("CollectOut.logVariable(\"a\""),
                "Log call for variable 'a' should be inserted.");
    }

    @Test
    public void testTransformSource_InstrumentsAssignment() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int a;\n" +
                        "    a = 10;\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        // Ensure the assignment statement remains.
        assertTrue(instrumented.contains("a = 10"), "The assignment statement should remain.");
        // Check that a log call is inserted after the assignment.
        assertTrue(instrumented.contains("CollectOut.logVariable(\"a\""),
                "Log call for assignment of variable 'a' should be inserted.");
    }

    @Test
    public void testTransformSource_InstrumentsConditionalExpression() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int x = (a > b) ? 1 : 2;\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        // Check that temporary variable names (e.g., tempRes) are created.
        assertTrue(instrumented.contains("tempRes"), "Temporary variable for conditional result should be created.");
        // Verify that logging calls are added for the temporary variables.
        assertTrue(instrumented.contains("CollectOut.logVariable"), "Logging calls should be inserted for conditional parts.");
    }

    @Test
    public void testTransformSource_DoesNotReturnNull() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int a = 5;\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        assertNotNull(instrumented, "The transformed source should not be null.");
    }

    @Test
    public void testAlreadyInstrumentedSourceIsNotReInstrumented() {
        String source =
                "package instrumentation;\n" + // Include package declaration
                        "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    //[instrumented] CollectOut.logVariable(\"a\", a);\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        // Since the source already appears instrumented, it should not add new instrumentation beyond ensuring the package declaration.
        assertTrue(instrumented.contains("CollectOut.logVariable(\"a\", a)"),
                "Already instrumented source should remain unchanged.");
        // The marker comment should still appear.
        assertTrue(instrumented.contains("//[instrumented]"), "Marker comment should be present.");
    }

    @Test
    public void testMethodWithExistingLabeledStmtNotReInstrumented() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    methodBody: { int a = 5; }\n" +
                        "  }\n" +
                        "}\n";
        String instrumented = InstrumentationInserter.transformSource(source);
        // Expect only one occurrence of "methodBody" label.
        int count = instrumented.split("methodBody").length - 1;
        assertEquals(1, count, "Should not re-instrument a method with existing 'methodBody' label.");
    }

    @Test
    public void testWhileLoopConditionFixer() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int a = 0;\n" +
                        "    while(a < 10) { a++; }\n" +
                        "  }\n" +
                        "}\n";
        CompilationUnit cu = StaticJavaParser.parse(source);
        InstrumentationInserter.WhileLoopConditionFixer fixer = new InstrumentationInserter.WhileLoopConditionFixer();
        fixer.visit(cu, null);
        String transformed = cu.toString();
        assertTrue(transformed.contains("while (a < 10)"), "While loop condition should remain unchanged.");
    }

    @Test
    public void testExpressionRewriter() {
        String source =
                "public class TestClass {\n" +
                        "  public void testMethod() {\n" +
                        "    int x = (a > b) ? 1 : 2;\n" +
                        "  }\n" +
                        "}\n";
        CompilationUnit cu = StaticJavaParser.parse(source);
        InstrumentationInserter.ExpressionRewriter rewriter = new InstrumentationInserter.ExpressionRewriter();
        rewriter.visit(cu, null);
        String transformed = cu.toString();
        
        // Check that the original conditional operator "?:"
        // is rewritten, so the output should not contain "?:"
        assertFalse(transformed.contains("?:"),
                "Conditional operator should be rewritten by ExpressionRewriter.");
        
        // Check for the presence of a temporary variable (e.g., "tempCond").
        assertTrue(transformed.contains("tempCond"),
                "Temporary variable for the condition should be introduced.");
    }

    @Test
    public void testSingleExitTransformer() {
        String source =
                "public class TestClass {\n" +
                        "  public int testMethod() {\n" +
                        "    return 42;\n" +
                        "  }\n" +
                        "}\n";
        CompilationUnit cu = StaticJavaParser.parse(source);
        // Get the method declaration.
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class).orElseThrow();
        // Apply the SingleExitTransformer.
        InstrumentationInserter.SingleExitTransformer transformer = new InstrumentationInserter.SingleExitTransformer();
        transformer.visit(md, null);
        String transformed = cu.toString();
        // Check that an _exit variable declaration is added.
        assertTrue(transformed.contains("_exit"), "Transformed method should contain _exit variable declaration.");
        // Check that the original return is replaced by a break statement and a final return using _exit.
        assertTrue(transformed.contains("break methodBody"), "Original return should be replaced with a break statement.");
        assertTrue(transformed.contains("return _exit"), "Method should return _exit at the end.");
    }
}
