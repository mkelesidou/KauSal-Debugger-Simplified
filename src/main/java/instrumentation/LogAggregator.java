package instrumentation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * LogAggregator writes (X, T, Y) rows for UniVal analysis:
 *   - Covariates (X): the parent variables of T (loaded from a JSON file produced by ParentMapExtractor)
 *   - Treatment (T): the assigned variable name
 *   - TreatmentVal: the assigned variable's value
 *   - Outcome (Y): pass (1) or failure (0)
 *   - TestArgs: the test input used.
 *
 * Each row corresponds to one assignment.
 */
public class LogAggregator {
    private static final Logger logger = LoggerFactory.getLogger(LogAggregator.class);

    // Hardcoded CSV output location.
    private static final String CSV_FILE = "src/main/resources/logs/execution_data.csv";
    private static boolean headerWritten = false;

    // Hardcoded path to the parent map JSON produced by ParentMapExtractor.
    private static final String PARENT_MAP_JSON = "src/main/resources/transformation/gsas/parentMap_simple_example.json";
    private static Map<String, List<String>> parentMap = new HashMap<>();

    // Load the parent mapping when the class is loaded.
    static {
        try (Reader reader = new FileReader(PARENT_MAP_JSON)) {
            Gson gson = new Gson();
            parentMap = gson.fromJson(reader, new TypeToken<Map<String, List<String>>>() {}.getType());
            logger.info("Loaded parent map: {}", parentMap);
        } catch (Exception e) {
            logger.error("Error loading parent map JSON from {}: {}", PARENT_MAP_JSON, e.getMessage());
            parentMap = new HashMap<>();
        }
    }

    // Resets the aggregator by deleting the CSV file.
    public static synchronized void reset() {
        File csvFile = new File(CSV_FILE);
        if (csvFile.exists()) {
            if (!csvFile.delete()) {
                logger.error("Failed to delete existing CSV file: {}", CSV_FILE);
            }
        }
        headerWritten = false;
    }

    /**
     * Aggregates the log lines for one test run into multiple CSV rows.
     * Each row corresponds to one assignment.
     *
     * @param testArgs The test input(s) as a string.
     * @param logLines The list of log lines from CollectOut for this test.
     * @param outcome  1 for pass, 0 for failure.
     */
    public static synchronized void aggregateTest(String testArgs, List<String> logLines, int outcome) {
        // 1) Parse log lines into VarAssignment objects.
        List<VarAssignment> assignments = parseAssignments(logLines);

        // 2) For each assignment, build a CSV row.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VarAssignment va : assignments) {
            // Retrieve parent variables for va.varName.
            List<String> parents = getParentsOf(va.varName);

            // For each parent, get the most recent logged value from assignments.
            Map<String, Object> covariateMap = new LinkedHashMap<>();
            for (String p : parents) {
                String val = findMostRecentValue(assignments, p);
                // Fallback: if the parent's value is not logged and the parent's name is "x_0",
                // try to extract it from the test arguments.
                if (val == null && p.equals("x_0")) {
                    String clean = testArgs.replace("[", "").replace("]", "").trim();
                    if (!clean.isEmpty()) {
                        val = clean.split(",")[0].trim();
                    }
                }
                if (val == null) {
                    val = "N/A";
                }
                covariateMap.put(p, val);
            }

            // Build the CSV row.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("TestArgs", testArgs);

            // Format covariates as "name=value" pairs separated by semicolons.
            StringBuilder covSb = new StringBuilder();
            for (Map.Entry<String, Object> entry : covariateMap.entrySet()) {
                if (covSb.length() > 0) {
                    covSb.append(";");
                }
                covSb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            row.put("Covariates", covSb.toString());
            row.put("TreatmentVar", va.varName);
            row.put("TreatmentVal", va.value);
            row.put("Outcome", outcome);

            rows.add(row);
        }

        // 3) Write rows to CSV.
        writeRowsToCsv(rows);
    }

    // Data class for variable assignments.
    private static class VarAssignment {
        String varName;
        String value;
        VarAssignment(String n, String v) {
            this.varName = n;
            this.value = v;
        }
    }

    /**
     * Parses log lines (e.g., "result_3 = 5") into VarAssignment objects.
     */
    private static List<VarAssignment> parseAssignments(List<String> logLines) {
        List<VarAssignment> result = new ArrayList<>();
        for (String line : logLines) {
            if (line.trim().startsWith("-----")) continue; // Skip separator lines.
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                String varName = parts[0].trim();
                String value = parts[1].trim();
                result.add(new VarAssignment(varName, value));
            }
        }
        return result;
    }

    /**
     * Retrieves the parent variables for a given variable name using the loaded parent map.
     */
    private static List<String> getParentsOf(String varName) {
        return parentMap.getOrDefault(varName, new ArrayList<>());
    }

    /**
     * Finds the most recent assignment value for the given variable from the list.
     */
    private static String findMostRecentValue(List<VarAssignment> assignments, String varName) {
        String val = null;
        for (VarAssignment va : assignments) {
            if (va.varName.equals(varName)) {
                val = va.value;
            }
        }
        return val;
    }

    /**
     * Writes the given rows to the CSV file, writing the header only once.
     */
    private static void writeRowsToCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        File csvFile = new File(CSV_FILE);
        if (!csvFile.getParentFile().exists()) {
            csvFile.getParentFile().mkdirs();
        }
        try (FileWriter fw = new FileWriter(csvFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {

            if (!headerWritten) {
                Map<String, Object> firstRow = rows.get(0);
                String header = String.join(",", firstRow.keySet());
                out.println(header);
                headerWritten = true;
            }
            for (Map<String, Object> rowData : rows) {
                List<String> rowValues = new ArrayList<>();
                for (Object value : rowData.values()) {
                    rowValues.add(String.valueOf(value));
                }
                String row = String.join(",", rowValues);
                out.println(row);
            }
        } catch (IOException e) {
            logger.error("Error writing to CSV file", e);
        }
    }
}
