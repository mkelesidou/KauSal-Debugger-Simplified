package testing;

import instrumentation.CollectOut;
import instrumentation.LogAggregator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRunnerTest {

    @TempDir
    Path tempDir;

    // Test files
    private File testSourceFile;
    private Path testSourcePath;
    private Path testOutputDir;
    
    // Values from TestRunner
    private String sourceFilePath;
    private String outputDir;
    private String className;

    @BeforeEach
    void setUp() throws Exception {
        // Create test directories
        testOutputDir = tempDir.resolve("output");
        Files.createDirectories(testOutputDir);
        
        // Get original values (just to read them, not modify)
        sourceFilePath = getStaticFieldValue(TestRunner.class, "SOURCE_FILE_PATH");
        outputDir = getStaticFieldValue(TestRunner.class, "OUTPUT_DIR");
        className = getStaticFieldValue(TestRunner.class, "CLASS_NAME");
        
        // Create a simple test Java file
        testSourcePath = tempDir.resolve("TestClass.java");
        String testSourceCode = 
                "package instrumentation;\n\n" +
                "public class TestClass {\n" +
                "    public static void main(String[] args) {\n" +
                "        int value = 0;\n" + 
                "        try {\n" +
                "            if (args.length > 0) {\n" +
                "                value = Integer.parseInt(args[0]);\n" +
                "                CollectOut.logVariable(\"value\", value);\n" +
                "                if (value > 0) {\n" +
                "                    CollectOut.logVariable(\"result\", \"positive\");\n" +
                "                } else {\n" +
                "                    CollectOut.logVariable(\"result\", \"zero_or_negative\");\n" +
                "                }\n" +
                "                CollectOut.logVariable(\"Outcome\", 1);\n" +
                "            } else {\n" +
                "                CollectOut.logVariable(\"error\", \"No arguments provided\");\n" +
                "                CollectOut.logVariable(\"Outcome\", 0);\n" +
                "            }\n" +
                "        } catch (NumberFormatException e) {\n" +
                "            CollectOut.logVariable(\"error\", \"Invalid number: \" + args[0]);\n" +
                "            CollectOut.logVariable(\"Outcome\", 0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        
        Files.writeString(testSourcePath, testSourceCode);
        testSourceFile = testSourcePath.toFile();
        
        // Reset LogAggregator for tests
        Method resetMethod = LogAggregator.class.getDeclaredMethod("reset");
        resetMethod.invoke(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close CollectOut
        CollectOut.close();
    }

    @Test
    void testCompileSource() throws Exception {
        // Access private static method using reflection
        Method compileMethod = TestRunner.class.getDeclaredMethod("compileSource", String.class, String.class);
        compileMethod.setAccessible(true);
        
        // Compile the test source
        compileMethod.invoke(null, testSourcePath.toString(), testOutputDir.toString());
        
        // Verify compilation succeeded by checking for class file
        Path classFilePath = testOutputDir.resolve("instrumentation/TestClass.class");
        assertTrue(Files.exists(classFilePath), "Compiled class file should exist");
    }

    @Test
    void testRunInstrumentedClass() throws Exception {
        // First compile
        Method compileMethod = TestRunner.class.getDeclaredMethod("compileSource", String.class, String.class);
        compileMethod.setAccessible(true);
        compileMethod.invoke(null, testSourcePath.toString(), testOutputDir.toString());
        
        // Access runInstrumentedClass method
        Method runMethod = TestRunner.class.getDeclaredMethod("runInstrumentedClass", 
                String[].class, String.class, String.class);
        runMethod.setAccessible(true);
        
        // Run with positive input
        String[] positiveArgs = new String[]{"5"};
        runMethod.invoke(null, positiveArgs, "instrumentation.TestClass", testOutputDir.toString());
        
        // Check logs
        List<String> logs = CollectOut.flushCurrentTestLog();
        assertTrue(logs.stream().anyMatch(log -> log.contains("value = 5")), 
                "Should log the input value");
        assertTrue(logs.stream().anyMatch(log -> log.contains("result = positive")), 
                "Should log positive result");
        assertTrue(logs.stream().anyMatch(log -> log.contains("Outcome = 1")), 
                "Should log success outcome");
        
        // Run with negative input
        String[] negativeArgs = new String[]{"-5"};
        runMethod.invoke(null, negativeArgs, "instrumentation.TestClass", testOutputDir.toString());
        
        // Check logs
        logs = CollectOut.flushCurrentTestLog();
        assertTrue(logs.stream().anyMatch(log -> log.contains("value = -5")), 
                "Should log the negative input value");
        assertTrue(logs.stream().anyMatch(log -> log.contains("result = zero_or_negative")), 
                "Should log zero_or_negative result");
    }

    @Test
    void testExtractOutcome() throws Exception {
        // Access private static method using reflection
        Method extractMethod = TestRunner.class.getDeclaredMethod("extractOutcome", List.class);
        extractMethod.setAccessible(true);
        
        // Test with success outcome
        List<String> successLogs = List.of("value = 5", "result = positive", "Outcome = 1");
        int successOutcome = (int) extractMethod.invoke(null, successLogs);
        assertEquals(1, successOutcome, "Should extract success outcome (1)");
        
        // Test with failure outcome
        List<String> failureLogs = List.of("error = Invalid number: abc", "Outcome = 0");
        int failureOutcome = (int) extractMethod.invoke(null, failureLogs);
        assertEquals(0, failureOutcome, "Should extract failure outcome (0)");
        
        // Test with missing outcome
        List<String> missingOutcomeLogs = List.of("value = 5", "result = positive");
        int missingOutcome = (int) extractMethod.invoke(null, missingOutcomeLogs);
        assertEquals(0, missingOutcome, "Should default to 0 for missing outcome");
    }

    @Test
    void testFixMainMethodArgs() throws Exception {
        // Create a file with args_0 references
        String codeWithArgsZero = 
                "package instrumentation;\n\n" +
                "public class TestClass {\n" +
                "    public static void main(String[] args_0) {\n" +
                "        if (args_0.length > 0) {\n" +
                "            int value = Integer.parseInt(args_0[0]);\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        
        Path fileWithArgsZero = tempDir.resolve("TestClassArgsZero.java");
        Files.writeString(fileWithArgsZero, codeWithArgsZero);
        
        // Call the fixMainMethodArgs method
        Method fixArgsMethod = TestRunner.class.getDeclaredMethod("fixMainMethodArgs", String.class);
        fixArgsMethod.setAccessible(true);
        fixArgsMethod.invoke(null, fileWithArgsZero.toString());
        
        // Read the file after fixing
        String fixedCode = Files.readString(fileWithArgsZero);
        
        // Verify args_0 was replaced with args
        assertTrue(fixedCode.contains("public static void main(String[] args)"), 
                "Should replace args_0 with args in parameter");
        assertTrue(fixedCode.contains("if (args.length > 0)"), 
                "Should replace args_0 with args in usage");
        assertTrue(fixedCode.contains("int value = Integer.parseInt(args[0])"), 
                "Should replace args_0 with args in array access");
    }

    @Test
    void testRemoveDuplicateDeclarations() throws Exception {
        // Create a file with duplicate declarations
        String codeWithDuplicates = 
                "package instrumentation;\n\n" +
                "public class TestClass {\n" +
                "    public static void main(String[] args) {\n" +
                "        int x = 5;\n" +
                "        int x = 10; // Duplicate declaration\n" +
                "        System.out.println(x);\n" +
                "    }\n" +
                "}\n";
        
        Path fileWithDuplicates = tempDir.resolve("TestClassDuplicates.java");
        Files.writeString(fileWithDuplicates, codeWithDuplicates);
        
        // Call the removeDuplicateDeclarations method
        Method removeMethod = TestRunner.class.getDeclaredMethod("removeDuplicateDeclarations", String.class);
        removeMethod.setAccessible(true);
        removeMethod.invoke(null, fileWithDuplicates.toString());
        
        // Read the file after fixing
        String fixedCode = Files.readString(fileWithDuplicates);
        
        // Count occurrences of "int x ="
        long count = fixedCode.lines()
                .filter(line -> line.trim().startsWith("int x ="))
                .count();
        
        assertEquals(1, count, "Should have removed duplicate declaration of x");
    }

    @Test
    void testGenerateAdditionalFailingTestInputs() throws Exception {
        // Access private static method using reflection
        Method generateMethod = TestRunner.class.getDeclaredMethod("generateAdditionalFailingTestInputs", int.class);
        generateMethod.setAccessible(true);
        
        // Generate 10 failing inputs
        @SuppressWarnings("unchecked")
        List<String[]> failingInputs = (List<String[]>) generateMethod.invoke(null, 10);
        
        // Verify
        assertEquals(10, failingInputs.size(), "Should generate 10 failing inputs");
        
        // Verify each input is a non-numeric string
        for (String[] input : failingInputs) {
            assertEquals(1, input.length, "Each input should have one element");
            assertThrows(NumberFormatException.class, () -> Integer.parseInt(input[0]),
                    "Each input should be a non-numeric string");
        }
    }

    /**
     * Tests individual methods rather than the main method to avoid
     * hardcoded final fields issue.
     */
    @Test
    void testIntegrationOfComponents() throws Exception {
        // We'll test the individual components work together
        
        // 1. Compile the source
        Method compileMethod = TestRunner.class.getDeclaredMethod("compileSource", String.class, String.class);
        compileMethod.setAccessible(true);
        compileMethod.invoke(null, testSourcePath.toString(), testOutputDir.toString());
        
        // 2. Fix args and declarations if needed
        Method fixArgsMethod = TestRunner.class.getDeclaredMethod("fixMainMethodArgs", String.class);
        fixArgsMethod.setAccessible(true);
        fixArgsMethod.invoke(null, testSourcePath.toString());
        
        Method removeMethod = TestRunner.class.getDeclaredMethod("removeDuplicateDeclarations", String.class);
        removeMethod.setAccessible(true);
        removeMethod.invoke(null, testSourcePath.toString());
        
        // 3. Run the class with test arguments
        Method runMethod = TestRunner.class.getDeclaredMethod("runInstrumentedClass", 
                String[].class, String.class, String.class);
        runMethod.setAccessible(true);
        
        String[] testArgs = new String[]{"42"};
        runMethod.invoke(null, testArgs, "instrumentation.TestClass", testOutputDir.toString());
        
        // 4. Verify the outcome is extracted properly
        Method extractMethod = TestRunner.class.getDeclaredMethod("extractOutcome", List.class);
        extractMethod.setAccessible(true);
        
        List<String> logs = CollectOut.flushCurrentTestLog();
        int outcome = (int) extractMethod.invoke(null, logs);
        
        // 5. Assert the test ran successfully
        assertEquals(1, outcome, "The test should have succeeded with a positive number");
    }

    // Helper method to get (but not set) static field values
    private <T> T getStaticFieldValue(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        T value = (T) field.get(null);
        return value;
    }
} 