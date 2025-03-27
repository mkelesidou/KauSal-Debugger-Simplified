package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

class GSATransformerTest {

    private static final String PREDICATES_DIR = "src/main/resources/transformation/predicates";
    private static final String GSAS_DIR = "src/main/resources/transformation/gsas";
    private static final String TEST_FILE = "transformed_simple_example.java";

    @Test
    @DisplayName("Should transform predicate code to GSA form")
    void shouldTransformToGSAForm() throws IOException {
        // Arrange: Set up test paths
        String inputPath = Paths.get(PREDICATES_DIR, TEST_FILE).toString();
        String outputPath = Paths.get(GSAS_DIR, TEST_FILE).toString();
        
        // Make sure the output directory exists
        Files.createDirectories(Paths.get(GSAS_DIR));
        
        // Delete existing output file if it exists
        Files.deleteIfExists(Paths.get(outputPath));
        
        // Act: Run the transformation
        GSATransformer.main(new String[]{});
        
        // Assert: Check that the output file exists
        File outputFile = new File(outputPath);
        assertTrue(outputFile.exists(), "Output file should be created");
        
        // Read transformed content
        String transformedContent = Files.readString(outputFile.toPath());
        
        // Check SSA variable renaming
        assertTrue(transformedContent.contains("args_0"), "Should rename method parameters with SSA indexing");
        assertTrue(transformedContent.contains("x_0"), "Should rename method parameters with SSA indexing");
        
        // Check for expected SSA-specific constructs
        assertTrue(transformedContent.contains("int result_"), "Should rename variables with SSA indexing");
        assertTrue(transformedContent.contains("boolean P1_1"), "Should rename predicate variables with SSA indexing");
        
        // Check for phi-function/gating assignments
        assertTrue(transformedContent.contains("?"), "Should contain conditional expressions for gating assignments");
        
        // Check for single-exit transformation
        assertTrue(transformedContent.contains("int _exit;"), "Should introduce exit variable for single-exit form");
        assertTrue(transformedContent.contains("methodBody:"), "Should label method body for single-exit form");
        assertTrue(transformedContent.contains("break methodBody;"), "Should use labeled break for early returns");
    }

    @Test
    @DisplayName("Should handle while loops correctly in GSA form")
    void shouldHandleWhileLoopsCorrectly() throws IOException {
        // Arrange: Ensure transformation has run
        String outputPath = Paths.get(GSAS_DIR, TEST_FILE).toString();
        if (!Files.exists(Paths.get(outputPath))) {
            GSATransformer.main(new String[]{});
        }
        
        // Act: Read the transformed content
        String transformedContent = Files.readString(Paths.get(outputPath));
        
        // Assert: Check for proper while loop handling
        assertTrue(transformedContent.contains("while (P2_1)"), "Should preserve while loop structure");
        assertTrue(transformedContent.contains("int result_temp"), "Should introduce temporary variable for loop carried dependencies");
        assertTrue(transformedContent.contains("result_4 = result_temp"), "Should update SSA variable inside loop body");
    }

    @Test
    @DisplayName("Should handle if-else with phi-functions")
    void shouldHandleIfElseWithPhiFunctions() throws IOException {
        // Arrange: Ensure transformation has run
        String outputPath = Paths.get(GSAS_DIR, TEST_FILE).toString();
        if (!Files.exists(Paths.get(outputPath))) {
            GSATransformer.main(new String[]{});
        }
        
        // Act: Read the transformed content
        String transformedContent = Files.readString(Paths.get(outputPath));
        
        // Assert: Check for proper if-else and phi-function handling
        assertTrue(transformedContent.contains("result_2 = x_0 * 2"), "Should have then-branch assignment with SSA");
        assertTrue(transformedContent.contains("result_3 = x_0 + 3"), "Should have else-branch assignment with SSA");
        assertTrue(transformedContent.contains("int result_4 = P1_1 ? result_2 : result_3"), 
            "Should have phi-function using conditional expression for merging if-else paths");
    }

    @Test
    @DisplayName("Should maintain semantic correctness")
    void shouldMaintainSemanticCorrectness() throws IOException {
        // This is a higher-level test that could be expanded with actual execution
        // of the original and transformed code to verify equivalent behavior.
        
        // For now, we'll do some basic structural checks
        String outputPath = Paths.get(GSAS_DIR, TEST_FILE).toString();
        if (!Files.exists(Paths.get(outputPath))) {
            GSATransformer.main(new String[]{});
        }
        
        String transformedContent = Files.readString(Paths.get(outputPath));
        
        // Check basic structure is preserved
        assertTrue(transformedContent.contains("package examples"), 
            "Should preserve package declaration");
        assertTrue(transformedContent.contains("public class SimpleExample"), 
            "Should preserve class declaration");
        assertTrue(transformedContent.contains("public static int simpleMethod"), 
            "Should preserve method signature");
        assertTrue(transformedContent.contains("return _exit"), 
            "Should return the exit variable in single-exit form");
    }
} 