package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

class PredicateTransformerTest {
    
    private PredicateTransformer transformer;
    
    @BeforeEach
    void setUp() {
        transformer = new PredicateTransformer();
    }
    
    @Test
    @DisplayName("Should transform if statement condition into predicate variable")
    void shouldTransformIfStatement() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    if (x > 0 && y < 10) {" +
            "        System.out.println(x);" +
            "    }" +
            "}}";
        CompilationUnit cu = StaticJavaParser.parse(code);
        
        // Act
        transformer.visit(cu, null);
        
        // Assert
        String transformed = cu.toString();
        assertTrue(transformed.contains("final boolean P1 = x > 0 && y < 10"),
            "Should create final boolean predicate");
        assertTrue(transformed.contains("if (P1)"),
            "Should use predicate in if condition");
    }
    
    @Test
    @DisplayName("Should transform while loop condition into updatable predicate")
    void shouldTransformWhileLoop() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    while (x < 10) {" +
            "        x++;" +
            "    }" +
            "}}";
        CompilationUnit cu = StaticJavaParser.parse(code);
        
        // Act
        transformer.visit(cu, null);
        
        // Assert
        String transformed = cu.toString();
        assertTrue(transformed.contains("boolean P1 = x < 10"),
            "Should create non-final predicate");
        assertTrue(transformed.contains("while (P1)"),
            "Should use predicate in while condition");
        assertTrue(transformed.contains("P1 = x < 10;"),
            "Should update predicate at end of loop body");
    }
    
    @Test
    @DisplayName("Should transform for loop into while loop with predicate")
    void shouldTransformForLoop() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    for (int i = 0; i < 10; i++) {" +
            "        System.out.println(i);" +
            "    }" +
            "}}";
        
        // Act
        CompilationUnit cu = StaticJavaParser.parse(code);
        transformer.visit(cu, null);
        String transformed = cu.toString();
        
        // Debug print
        System.out.println("Transformed code:\n" + transformed);
        
        // Assert
        assertTrue(transformed.contains("boolean P1 = i < 10"), 
            "Should create predicate for condition");
        assertTrue(transformed.contains("while (P1)"), 
            "Should use predicate in while condition");
    }
    
    @Test
    @DisplayName("Should transform switch selector into final variable")
    void shouldTransformSwitchStatement() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    int x = 1, y = 2;" +
            "    switch (x + y) {" +
            "        case 1: break;" +
            "        case 2: break;" +
            "    }" +
            "}}";
        
        // Act
        CompilationUnit cu = StaticJavaParser.parse(code);
        transformer.visit(cu, null);
        String transformed = cu.toString();
        
        // Debug print
        System.out.println("Transformed code:\n" + transformed);
        
        // Assert
        assertTrue(transformed.contains("final int S1 = x + y"), 
            "Should create selector variable");
        assertTrue(transformed.contains("switch(S1)") || transformed.contains("switch (S1)"), 
            "Should use selector variable in switch");
    }
    
    @Test
    @DisplayName("Should handle nested control structures correctly")
    void shouldHandleNestedControlStructures() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    if (x > 0) {" +
            "        while (y < 10) {" +
            "            if (z == 0) {" +
            "                y++;" +
            "            }" +
            "        }" +
            "    }" +
            "}}";
        
        // Act
        CompilationUnit cu = StaticJavaParser.parse(code);
        transformer.visit(cu, null);
        String transformed = cu.toString();
        
        // Debug print
        System.out.println("Transformed code:\n" + transformed);
        
        // Assert
        assertTrue(transformed.contains("final boolean P1 = x > 0"), 
            "Should transform outer if condition");
        assertTrue(transformed.contains("boolean P2 = y < 10"), 
            "Should transform while condition");
        assertTrue(transformed.contains("final boolean P3 = z == 0"), 
            "Should transform inner if condition");
    }
    
    @Test
    @DisplayName("Should transform if condition into predicate")
    void shouldPreserveSemantics() {
        // Arrange
        String code = 
            "class Test { void test() { " +
            "    if (x > 0) {" +
            "        System.out.println(x);" +
            "    }" +
            "}}";
        
        // Act
        CompilationUnit cu = StaticJavaParser.parse(code);
        transformer.visit(cu, null);
        String transformed = cu.toString();
        
        // Debug print
        System.out.println("Transformed code:\n" + transformed);
        
        // Assert
        assertTrue(transformed.contains("final boolean P1 = x > 0"), 
            "Should create if predicate");
        assertTrue(transformed.contains("if (P1)"), 
            "Should use predicate in if condition");
    }
} 