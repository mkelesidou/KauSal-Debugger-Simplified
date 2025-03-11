package instrumentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class CollectOut {
    private static final Logger logger = LoggerFactory.getLogger(CollectOut.class);

    private static final String DEFAULT_LOG_DIR = "src/main/resources/logs";
    private static final String DEFAULT_LOG_FILE = "execution.log";

    // In-memory store: Each test’s log lines are grouped together.
    // We'll reset this store after each test so we can parse them as needed.
    private static final List<String> currentTestLogLines = new ArrayList<>();

    private static BufferedWriter writer;

    static {
        Properties props = new Properties();
        try (InputStream input = CollectOut.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                props.load(input);
            } else {
                logger.warn("config.properties not found; using default settings.");
            }
        } catch (IOException e) {
            logger.error("Error loading config.properties", e);
        }

        String logDir = props.getProperty("log.dir", DEFAULT_LOG_DIR);
        String logFileName = props.getProperty("log.file", DEFAULT_LOG_FILE);

        try {
            File dir = new File(logDir);
            if (!dir.exists() && !dir.mkdirs()) {
                logger.error("Failed to create log directory: {}", logDir);
            }
            File logFile = new File(dir, logFileName);
            // Overwrite mode (append = false)
            writer = new BufferedWriter(new FileWriter(logFile, false));
        } catch (IOException e) {
            logger.error("Failed to initialize log file: {}", e);
        }
    }

    public static synchronized void logVariable(String varName, Object value) {
        String line = varName + " = " + value;
        // Write to execution.log
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            logger.error("Error writing to log file", e);
        }
        // Also store in memory
        currentTestLogLines.add(line);
    }

    /**
     * Call this when one test (one set of inputs) is finished,
     * so we can retrieve the lines for that test.
     */
    public static synchronized List<String> flushCurrentTestLog() {
        List<String> copy = new ArrayList<>(currentTestLogLines);
        currentTestLogLines.clear();
        return copy;
    }

    public static synchronized void close() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            logger.error("Error closing log writer", e);
        }
    }
}
