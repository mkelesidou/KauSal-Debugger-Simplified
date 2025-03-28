package testing;

import instrumentation.CollectOut;
import instrumentation.LogAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;

public class TestRunner {
    private static final Logger logger = LoggerFactory.getLogger(TestRunner.class);

    // Default settings (hardcoded)
    private static final String SOURCE_FILE_PATH = "src/main/resources/instrumentation/BuggyExample.java";
    private static final String OUTPUT_DIR = "src/main/resources/";
    private static final String CLASS_NAME = "instrumentation.BuggyExample";

    public static void main(String[] args) {
        try {
            // Reset aggregator before running tests.
            LogAggregator.reset();

            // First compile the instrumented source.
            compileSource(SOURCE_FILE_PATH, OUTPUT_DIR);

            // Fix the main method's parameter references and remove duplicate declarations.
            fixMainMethodArgs(SOURCE_FILE_PATH);
            removeDuplicateDeclarations(SOURCE_FILE_PATH);

            // Recompile after fixes.
            compileSource(SOURCE_FILE_PATH, OUTPUT_DIR);

            // Define a set of test inputs to capture varied behavior.
            List<String[]> testArguments = new ArrayList<>(Arrays.asList(
                    new String[]{"2"},
                    new String[]{"6"},
                    new String[]{"10"},
                    new String[]{"0"},
                    new String[]{"-1"},
                    new String[]{"5"},
                    new String[]{"-10"},
                    new String[]{"10000"},
                    new String[]{"50"},
                    new String[]{"20"},
                    new String[]{"9999"},
                    new String[]{"-9999"},
                    new String[]{"100"},
                    new String[]{"-50"},
                    new String[]{"7", "15"}, // multiple arguments example
                    new String[]{"0", "0"},
                    new String[]{"123"},
                    new String[]{"456"},
                    new String[]{"789"},
                    new String[]{"-456"},
                    new String[]{"10.5"}
            ));
            // Generate additional failing test cases (non-numeric strings)
            List<String[]> additionalFailing = generateAdditionalFailingTestInputs(50);
            testArguments.addAll(additionalFailing);

            // Run each test input.
            for (String[] testArgs : testArguments) {
                boolean outcome = true;
                try {
                    logger.info("===== Running with input: {} =====", Arrays.toString(testArgs));
                    runInstrumentedClass(testArgs, CLASS_NAME, OUTPUT_DIR);
                } catch (Exception e) {
                    outcome = false;
                    logger.error("Test execution failed for input " + Arrays.toString(testArgs), e);
                    // Log a failure outcome explicitly.
                    CollectOut.logVariable("Outcome", 0);
                }
                if (outcome) {
                    CollectOut.logVariable("Outcome", 1);
                }
                // Retrieve log lines for this test.
                List<String> logLines = CollectOut.flushCurrentTestLog();

                // Determine outcome based on the "Outcome" log entry.
                int extractedOutcome = extractOutcome(logLines);

                // Aggregate test results into CSV.
                String testArgsStr = Arrays.toString(testArgs);
                LogAggregator.aggregateTest(testArgsStr, logLines, extractedOutcome);

                // Log a separator to mark the end of a test run.
                CollectOut.logVariable("-----", "-----");
            }
        } catch (Exception e) {
            logger.error("Compilation or test-run process encountered an error", e);
        } finally {
            CollectOut.close();
        }
    }

    /**
     * Extracts the outcome from the log lines by searching for the "Outcome" log entry.
     * Expected log format: "Outcome = <value>"
     */
    private static int extractOutcome(List<String> logLines) {
        for (String line : logLines) {
            if (line.startsWith("Outcome")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    try {
                        return Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException nfe) {
                        logger.error("Unable to parse Outcome value: {}", parts[1].trim());
                        return 0;
                    }
                }
            }
        }
        return 0; // Treat missing Outcome as failure.
    }

    /**
     * Compiles the instrumented Java source file.
     */
    private static void compileSource(String sourceFilePath, String outputDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java Compiler not available. Are you running on a JDK?");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            File sourceFile = new File(sourceFilePath);
            if (!sourceFile.exists()) {
                throw new Exception("Source file not found at: " + sourceFile.getAbsolutePath());
            }
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile));
            File outDir = new File(outputDir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new Exception("Could not create output directory: " + outputDir);
            }
            List<String> optionList = Arrays.asList("-d", outputDir);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, optionList, null, compilationUnits);
            boolean success = task.call();
            if (!success) {
                StringWriter diagnosticWriter = new StringWriter();
                for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                    diagnosticWriter.append(diagnostic.toString()).append("\n");
                }
                throw new Exception("Compilation failed:\n" + diagnosticWriter.toString());
            } else {
                logger.info("Compilation successful. Classes output to: {}", outputDir);
            }
        }
    }

    /**
     * Loads and runs the specified instrumented class using reflection.
     */
    private static void runInstrumentedClass(String[] argsForMain, String className, String outputDir) throws Exception {
        File outDir = new File(outputDir);
        URL[] urls = new URL[]{outDir.toURI().toURL()};
        try (URLClassLoader classLoader = new URLClassLoader(urls)) {
            Class<?> clazz = classLoader.loadClass(className);
            Method mainMethod = clazz.getMethod("main", String[].class);
            logger.info("Running {}.main with arguments {}", className, Arrays.toString(argsForMain));
            mainMethod.invoke(null, (Object) argsForMain);
        }
    }

    /**
     * Fixes the main method's parameter references by replacing any occurrence of "args_0" with "args"
     * using JavaParser.
     */
    private static void fixMainMethodArgs(String sourceFilePath) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(new File(sourceFilePath));
        cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> "main".equals(md.getNameAsString()))
                .forEach(md -> {
                    md.getParameters().forEach(param -> {
                        if ("args_0".equals(param.getNameAsString())) {
                            param.setName("args");
                        }
                    });
                    md.getBody().ifPresent(body -> {
                        body.findAll(NameExpr.class).forEach(ne -> {
                            if ("args_0".equals(ne.getNameAsString())) {
                                ne.setName("args");
                            }
                        });
                    });
                });
        Files.write(Paths.get(sourceFilePath), cu.toString().getBytes());
        logger.info("Fixed main method parameter references in {}", sourceFilePath);
    }

    /**
     * Removes duplicate variable declarations in each method of the source file using JavaParser.
     */
    private static void removeDuplicateDeclarations(String sourceFilePath) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(new File(sourceFilePath));
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Set<String> declaredVars = new HashSet<>();
            method.getBody().ifPresent(body -> {
                body.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class).forEach(vde -> {
                    List<com.github.javaparser.ast.body.VariableDeclarator> toRemove = new ArrayList<>();
                    vde.getVariables().forEach(vd -> {
                        String varName = vd.getNameAsString();
                        if (declaredVars.contains(varName)) {
                            toRemove.add(vd);
                        } else {
                            declaredVars.add(varName);
                        }
                    });
                    vde.getVariables().removeAll(toRemove);
                    if (vde.getVariables().isEmpty()) {
                        vde.remove();
                    }
                });
            });
        });
        Files.write(Paths.get(sourceFilePath), cu.toString().getBytes());
        logger.info("Removed duplicate variable declarations in {}", sourceFilePath);
    }

    /**
     * Generates additional failing test inputs by creating random non-numeric strings.
     *
     * @param count the number of failing test cases to generate.
     * @return a list of test case arrays.
     */
    private static List<String[]> generateAdditionalFailingTestInputs(int count) {
        List<String[]> failingInputs = new ArrayList<>();
        Random random = new Random();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            // Generate a random string of length between 3 and 7.
            int len = 3 + random.nextInt(5);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            failingInputs.add(new String[]{ sb.toString() });
        }
        logger.info("Generated {} additional failing test inputs.", count);
        return failingInputs;
    }
}
