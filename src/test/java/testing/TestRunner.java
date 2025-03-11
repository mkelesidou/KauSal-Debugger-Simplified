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
import java.util.Arrays;
import java.util.List;

public class TestRunner {
    private static final Logger logger = LoggerFactory.getLogger(TestRunner.class);

    private static final String SOURCE_FILE_PATH = "src/main/resources/instrumentation/SimpleExample.java";
    private static final String OUTPUT_DIR = "src/main/resources/";
    private static final String CLASS_NAME = "instrumentation.SimpleExample";

    public static void main(String[] args) {
        try {
            // Reset CSV aggregator.
            LogAggregator.reset();

            // Compile the instrumented source.
            compileSource();

            // Define multiple test inputs.
            List<String[]> testArguments = Arrays.asList(
                    new String[] {"2"},
                    new String[] {"6"},
                    new String[] {"10"},
                    new String[] {"0"},
                    new String[] {"abc"},   // triggers exception
                    new String[] {"-1"},
                    new String[] {"5"},
                    new String[] {"1000"}
            );

            // Run each test input.
            for (String[] testArgs : testArguments) {
                boolean outcome = true;
                try {
                    logger.info("===== Running with input: {} =====", Arrays.toString(testArgs));
                    runInstrumentedClass(testArgs);
                } catch (Exception e) {
                    outcome = false;
                    logger.error("Test execution failed for input " + Arrays.toString(testArgs), e);
                }
                // Log outcome.
                CollectOut.logVariable("Outcome", outcome ? 1 : 0);

                // Retrieve log lines for this test.
                List<String> logLines = CollectOut.flushCurrentTestLog();

                // Aggregate test results into CSV.
                String testArgsStr = Arrays.toString(testArgs);
                LogAggregator.aggregateTest(testArgsStr, logLines, outcome ? 1 : 0);

                // Optionally log a separator.
                CollectOut.logVariable("-----", "-----");
            }
        } catch (Exception e) {
            logger.error("Compilation or test-run process encountered an error", e);
        } finally {
            CollectOut.close();
        }
    }

    private static void compileSource() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java Compiler not available. Are you running on a JDK?");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            File sourceFile = new File(SOURCE_FILE_PATH);
            if (!sourceFile.exists()) {
                throw new Exception("Source file not found at: " + sourceFile.getAbsolutePath());
            }
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));
            File outputDir = new File(OUTPUT_DIR);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new Exception("Could not create output directory: " + OUTPUT_DIR);
            }
            List<String> optionList = Arrays.asList("-d", OUTPUT_DIR);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, optionList, null, compilationUnits);
            boolean success = task.call();
            if (!success) {
                StringWriter diagnosticWriter = new StringWriter();
                for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                    diagnosticWriter.append(diagnostic.toString()).append("\n");
                }
                throw new Exception("Compilation failed:\n" + diagnosticWriter.toString());
            } else {
                logger.info("Compilation successful. Classes output to: {}", OUTPUT_DIR);
            }
        }
    }

    private static void runInstrumentedClass(String[] argsForMain) throws Exception {
        File outputDir = new File(OUTPUT_DIR);
        URL[] urls = new URL[]{outputDir.toURI().toURL()};
        try (URLClassLoader classLoader = new URLClassLoader(urls)) {
            Class<?> clazz = classLoader.loadClass(CLASS_NAME);
            Method mainMethod = clazz.getMethod("main", String[].class);
            logger.info("Running {}.main with arguments {}", CLASS_NAME, Arrays.toString(argsForMain));
            mainMethod.invoke(null, (Object) argsForMain);
        }
    }
}
