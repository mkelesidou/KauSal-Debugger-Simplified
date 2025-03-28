package instrumentation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class LogAggregator {
    private static final Logger logger = LoggerFactory.getLogger(LogAggregator.class);

    // Hardcoded CSV output location.
    private static final String CSV_FILE = "src/main/resources/logs/execution_data_buggy_example.csv";
    private static boolean headerWritten = false;

    // Hardcoded path to the parent map JSON produced by ParentMapExtractor.
    private static final String PARENT_MAP_JSON = "src/main/resources/transformation/gsas/parentMap_buggy_example.json";
    private static Map<String, List<String>> parentMap = new HashMap<>();

    // Fixed CSV header order.
    private static final String[] CSV_HEADERS = {"TestArgs", "Covariates", "TreatmentVar", "TreatmentVal", "Outcome"};

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
        if (csvFile.exists() && !csvFile.delete()) {
            logger.error("Failed to delete existing CSV file: {}", CSV_FILE);
        }
        headerWritten = false;
    }

    /**
     * Aggregates the log lines for one test run into one CSV row per core treatment variable.
     * Each row corresponds to the final occurrence (i.e. final assignment) of a treatment variable.
     *
     * @param testArgs The test input(s) as a string.
     * @param logLines The list of log lines from CollectOut for this test.
     * @param outcome  1 for pass, 0 for failure.
     */
    public static synchronized void aggregateTest(String testArgs, List<String> logLines, int outcome) {
        // 1) Parse log lines into VarAssignment objects.
        List<VarAssignment> assignments = parseAssignments(logLines);

        // 2) Filter out temporary instrumentation variables and debug log entries.
        List<VarAssignment> coreAssignments = new ArrayList<>();
        for (VarAssignment va : assignments) {
            if (va.varName.startsWith("temp") || va.varName.endsWith("_debug"))
                continue;
            coreAssignments.add(va);
        }

        // 3) Group by treatment variable and take the final occurrence (assuming log order is chronological).
        Map<String, VarAssignment> finalAssignments = new LinkedHashMap<>();
        for (VarAssignment va : coreAssignments) {
            finalAssignments.put(va.varName, va);
        }

        // 4) Build rows using fixed columns.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VarAssignment va : finalAssignments.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("TestArgs", testArgs);
            // Build covariates string from parent map.
            List<String> parents = getParentsOf(va.varName);
            StringBuilder covSb = new StringBuilder();
            for (String p : parents) {
                String val = findMostRecentValue(assignments, p);
                if (val == null) {
                    val = "N/A";
                }
                if (covSb.length() > 0) {
                    covSb.append(";");
                }
                covSb.append(p).append("=").append(val);
            }
            row.put("Covariates", covSb.toString());
            row.put("TreatmentVar", va.varName);
            row.put("TreatmentVal", va.value);
            row.put("Outcome", outcome);
            rows.add(row);
        }

        // 5) Write rows to CSV.
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
     * Escapes a field for CSV output. Encloses the field in double quotes if it contains a comma or a double quote.
     */
    private static String escapeCSV(String field) {
        if (field == null) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            field = field.replace("\"", "\"\"");
            return "\"" + field + "\"";
        }
        return field;
    }

    /**
     * Writes the given rows to the CSV file using a fixed header order.
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
                out.println(String.join(",", CSV_HEADERS));
                headerWritten = true;
            }
            for (Map<String, Object> row : rows) {
                List<String> rowValues = new ArrayList<>();
                for (String key : CSV_HEADERS) {
                    Object value = row.get(key);
                    rowValues.add(escapeCSV(value != null ? String.valueOf(value) : ""));
                }
                out.println(String.join(",", rowValues));
            }
        } catch (IOException e) {
            logger.error("Error writing to CSV file", e);
        }
    }
}
