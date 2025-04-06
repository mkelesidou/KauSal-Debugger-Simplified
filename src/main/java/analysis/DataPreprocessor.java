package analysis;

import org.apache.commons.csv.CSVFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.data.DataFrame;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.type.DataTypes;
import smile.data.vector.BaseVector;
import smile.data.vector.StringVector;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.io.CSV;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DataPreprocessor.
 *
 * Reads raw execution logs with header:
 *   TestArgs,Covariates,TreatmentVar,TreatmentVal,Outcome
 *
 * For each test instance (grouped by TestArgs):
 *   - Finds the Outcome value from the first row with TreatmentVar "Outcome" (defaulting to 0 if none).
 *   - Collects all candidate rows (where TreatmentVar != "Outcome").
 *   - Computes a union of all candidate covariate values (across the test instance).
 *   - Then, for each distinct candidate treatment variable, it:
 *       • Averages all its TreatmentVal values.
 *       • Computes its own covariate set (from rows with that treatment variable) and subtracts it from the overall union
 *         to get aggregated covariates from other candidate rows.
 *   - Outputs one record per candidate treatment variable with columns:
 *       TestArgs, aggregated Covariates, TreatmentVar, averaged TreatmentVal, Outcome.
 *
 * The final CSV is written with proper quoting.
 */
public class DataPreprocessor {
    private static final Logger logger = LoggerFactory.getLogger(DataPreprocessor.class);

    // Default file paths to use if no command-line arguments are provided
    private static final String DEFAULT_INPUT_CSV  = "src/main/resources/logs/execution_data_buggy_example.csv";
    private static final String DEFAULT_OUTPUT_CSV = "src/main/resources/datasets/preprocessed_data_buggy_example.csv";

    // Raw log row
    private static class RowData {
        String testArgs;
        String covariates;
        String treatmentVar;
        String treatmentVal;
        String outcome; // may be empty if not an Outcome row

        RowData(String testArgs, String covariates, String treatmentVar, String treatmentVal, String outcome) {
            this.testArgs = testArgs;
            this.covariates = covariates;
            this.treatmentVar = treatmentVar;
            this.treatmentVal = treatmentVal;
            this.outcome = outcome;
        }
    }

    // Final aggregated record
    private static class AggregatedRecord {
        String testArgs;
        String covariates; // aggregated covariates from other candidate rows
        String treatmentVar;
        double treatmentVal; // averaged value
        int outcome;

        AggregatedRecord(String testArgs, String covariates, String treatmentVar, double treatmentVal, int outcome) {
            this.testArgs = testArgs;
            this.covariates = covariates;
            this.treatmentVar = treatmentVar;
            this.treatmentVal = treatmentVal;
            this.outcome = outcome;
        }
    }

    /**
     * Helper class to parse and process covariates
     */
    private static class CovariateParser {
        private Map<String, double[]> numericCovariates = new HashMap<>();
        private Map<String, String[]> categoricalCovariates = new HashMap<>();
        private Set<String> covariateKeys = new LinkedHashSet<>();
        private int numRows;

        public CovariateParser(int numRows) {
            this.numRows = numRows;
        }

        public void parseCovariates(String[] covariateStrings) {
            // First pass: collect all unique keys and determine if numeric or categorical
            Map<String, Boolean> isNumeric = new HashMap<>();

            for (String covStr : covariateStrings) {
                if (covStr == null || covStr.trim().isEmpty()) continue;

                String[] pairs = covStr.split(";");
                for (String pair : pairs) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length != 2) continue;

                    String key = "cov_" + kv[0].trim();
                    String value = kv[1].trim();
                    covariateKeys.add(key);

                    // Try parsing as number to determine type
                    if (!isNumeric.containsKey(key)) {
                        try {
                            Double.parseDouble(value);
                            isNumeric.put(key, true);
                        } catch (NumberFormatException e) {
                            isNumeric.put(key, false);
                        }
                    }
                }
            }

            // Initialise arrays
            for (String key : covariateKeys) {
                if (isNumeric.get(key)) {
                    double[] values = new double[numRows];
                    Arrays.fill(values, Double.NaN);
                    numericCovariates.put(key, values);
                } else {
                    String[] values = new String[numRows];
                    Arrays.fill(values, null);
                    categoricalCovariates.put(key, values);
                }
            }

            // Second pass: fill arrays
            for (int i = 0; i < covariateStrings.length; i++) {
                String covStr = covariateStrings[i];
                if (covStr == null || covStr.trim().isEmpty()) continue;

                String[] pairs = covStr.split(";");
                for (String pair : pairs) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length != 2) continue;

                    String key = "cov_" + kv[0].trim();
                    String value = kv[1].trim();

                    if (isNumeric.get(key)) {
                        try {
                            numericCovariates.get(key)[i] = Double.parseDouble(value);
                        } catch (NumberFormatException e) {
                            numericCovariates.get(key)[i] = Double.NaN;
                        }
                    } else {
                        categoricalCovariates.get(key)[i] = value;
                    }
                }
            }
        }

        public Map<String, double[]> getNumericCovariates() {
            return numericCovariates;
        }

        public Map<String, String[]> getCategoricalCovariates() {
            return categoricalCovariates;
        }

        public Set<String> getCovariateKeys() {
            return covariateKeys;
        }
    }

    /**
     * Helper class to handle label encoding of categorical variables
     */
    private static class CategoricalEncoder {
        private Map<String, Map<String, Integer>> encodings = new HashMap<>();
        private Map<String, int[]> encodedValues = new HashMap<>();

        public void fitTransform(String columnName, String[] values) {
            // Build encoding map
            Map<String, Integer> encoding = new HashMap<>();
            Set<String> uniqueValues = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null) {
                    uniqueValues.add(value);
                }
            }
            int idx = 0;
            for (String value : uniqueValues) {
                encoding.put(value, idx++);
            }
            encodings.put(columnName, encoding);

            // Transform values
            int[] encoded = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                encoded[i] = (value != null) ? encoding.get(value) : -1;
            }
            encodedValues.put(columnName, encoded);
        }

        public Map<String, int[]> getEncodedValues() {
            return encodedValues;
        }

        public Map<String, Map<String, Integer>> getEncodings() {
            return encodings;
        }
    }

    public static void main(String[] args) {
        try {
            String inputCsvPath;
            String outputCsvPath;
            
            if (args.length >= 2) {
                inputCsvPath = args[0];
                outputCsvPath = args[1];
            } else if (args.length == 1) {
                inputCsvPath = args[0];
                outputCsvPath = inputCsvPath.replace(".csv", "_preprocessed.csv")
                        .replace(".log", "_preprocessed.csv");
                logger.info("No output path specified, using default: {}", outputCsvPath);
            } else {
                logger.info("Usage: java DataPreprocessor <input_file> [<output_file>]");
                logger.info("Using default paths for testing purposes only.");
                inputCsvPath = DEFAULT_INPUT_CSV;
                outputCsvPath = DEFAULT_OUTPUT_CSV;
            }
            
            logger.info("Reading from: {}", inputCsvPath);
            logger.info("Writing to: {}", outputCsvPath);
            
            // Check if input file exists, create it if not
            File inputFile = new File(inputCsvPath);
            if (!inputFile.exists()) {
                createSampleFile(inputFile);
                logger.info("Created sample input file at: {}", inputFile.getAbsolutePath());
            }
            
            // 1) Read the raw CSV file with explicit 5-column header
            CSVFormat format = CSVFormat.DEFAULT
                    .withHeader("TestArgs", "Covariates", "TreatmentVar", "TreatmentVal", "Outcome")
                    .withSkipHeaderRecord(true)
                    .withDelimiter(',')
                    .withQuote('"')
                    .withIgnoreEmptyLines()
                    .withTrim(true);
            CSV csv = new CSV(format);
            StructType schema = DataTypes.struct(
                    new StructField("TestArgs", DataTypes.StringType),
                    new StructField("Covariates", DataTypes.StringType),
                    new StructField("TreatmentVar", DataTypes.StringType),
                    new StructField("TreatmentVal", DataTypes.StringType),
                    new StructField("Outcome", DataTypes.StringType)
            );
            csv.schema(schema);
            DataFrame df = csv.read(inputFile.getAbsolutePath());
            if (df.schema().length() > 5) {
                df = df.select("TestArgs", "Covariates", "TreatmentVar", "TreatmentVal", "Outcome");
            }

            // 2) Extract arrays from DataFrame
            int n = df.size();
            String[] testArgsArr = new String[n];
            String[] covariatesArr = new String[n];
            String[] treatmentVarArr = new String[n];
            String[] treatmentValArr = new String[n];
            String[] outcomeArr = new String[n];

            for (int i = 0; i < n; i++) {
                testArgsArr[i] = df.getString(i, 0);
                covariatesArr[i] = df.getString(i, 1);
                treatmentVarArr[i] = df.getString(i, 2);
                treatmentValArr[i] = df.getString(i, 3);
                outcomeArr[i] = df.getString(i, 4);
            }

            // 3) Parse and expand covariates
            CovariateParser covParser = new CovariateParser(n);
            covParser.parseCovariates(covariatesArr);
            Map<String, double[]> numericCovariates = covParser.getNumericCovariates();
            Map<String, String[]> categoricalCovariates = covParser.getCategoricalCovariates();

            // 4) Encode categorical covariates
            CategoricalEncoder covEncoder = new CategoricalEncoder();
            Map<String, int[]> encodedCovariates = new HashMap<>();
            for (Map.Entry<String, String[]> entry : categoricalCovariates.entrySet()) {
                covEncoder.fitTransform(entry.getKey(), entry.getValue());
            }
            encodedCovariates.putAll(covEncoder.getEncodedValues());

            // 5) Process treatment values based on TreatmentVar type
            Map<String, double[]> numericTreatments = new HashMap<>();
            Map<String, String[]> categoricalTreatments = new HashMap<>();
            Map<String, Boolean> isTreatmentNumeric = new HashMap<>();

            // First, group by TreatmentVar
            Map<String, List<Integer>> treatmentGroups = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String treatVar = treatmentVarArr[i];
                treatmentGroups.computeIfAbsent(treatVar, k -> new ArrayList<>()).add(i);
            }

            // For each treatment variable, determine if it's numeric and process accordingly
            for (String treatVar : treatmentGroups.keySet()) {
                List<Integer> indices = treatmentGroups.get(treatVar);
                boolean isNumeric = true;
                double[] numericVals = new double[n];
                Arrays.fill(numericVals, Double.NaN);

                // Try parsing all values for this treatment variable as numeric
                for (int idx : indices) {
                    String val = treatmentValArr[idx];
                    try {
                        numericVals[idx] = Double.parseDouble(val.trim());
                    } catch (NumberFormatException e) {
                        isNumeric = false;
                        break;
                    }
                }

                if (isNumeric) {
                    // Keep numeric values as is, just impute missing ones
                    numericTreatments.put(treatVar, numericVals);
                } else {
                    // For categorical treatments, collect all values first
                    String[] categoricalVals = new String[n];
                    for (int idx : indices) {
                        categoricalVals[idx] = treatmentValArr[idx];
                    }
                    categoricalTreatments.put(treatVar, categoricalVals);
                }
                isTreatmentNumeric.put(treatVar, isNumeric);
            }

            // Encode categorical treatments
            CategoricalEncoder treatmentEncoder = new CategoricalEncoder();
            Map<String, int[]> encodedTreatments = new HashMap<>();
            for (Map.Entry<String, String[]> entry : categoricalTreatments.entrySet()) {
                treatmentEncoder.fitTransform(entry.getKey(), entry.getValue());
                encodedTreatments.put(entry.getKey(), treatmentEncoder.getEncodedValues().get(entry.getKey()));
            }

            // 6) Convert outcome to binary (0/1)
            // In raw logs: 1/true/pass indicates failure, 0/false/fail indicates success
            int[] binaryOutcome = new int[n];
            for (int i = 0; i < n; i++) {
                String outcomeStr = outcomeArr[i].toLowerCase().trim();
                // Preserve original meaning: 1 indicates failure
                if (outcomeStr.equals("1") || outcomeStr.equals("true") || outcomeStr.equals("pass")) {
                    binaryOutcome[i] = 1;  // Failure (matches raw logs)
                } else {
                    binaryOutcome[i] = 0;  // Success (matches raw logs)
                }
            }

            // 7) Impute missing values in numeric columns using median
            // This follows UniVal's approach where missing/unparseable values are imputed
            // to allow the random forest to handle the data
            for (double[] values : numericCovariates.values()) {
                medianImpute(values);
            }
            for (double[] values : numericTreatments.values()) {
                medianImpute(values);
            }

            // 8) Build final DataFrame columns
            List<BaseVector> finalCols = new ArrayList<>();
            finalCols.add(StringVector.of("TestArgs", testArgsArr));

            // Add expanded covariates
            for (Map.Entry<String, double[]> entry : numericCovariates.entrySet()) {
                finalCols.add(DoubleVector.of(entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<String, int[]> entry : encodedCovariates.entrySet()) {
                finalCols.add(IntVector.of(entry.getKey(), entry.getValue()));
            }

            // Add treatment columns - preserve original TreatmentVar and add appropriate TreatmentVal
            finalCols.add(StringVector.of("TreatmentVar", treatmentVarArr));
            
            // Create treatment value column based on type
            double[] finalTreatmentVals = new double[n];
            Arrays.fill(finalTreatmentVals, Double.NaN);
            
            for (int i = 0; i < n; i++) {
                String treatVar = treatmentVarArr[i];
                if (isTreatmentNumeric.get(treatVar)) {
                    // Use raw numeric value
                    finalTreatmentVals[i] = numericTreatments.get(treatVar)[i];
                } else {
                    // Use encoded categorical value
                    finalTreatmentVals[i] = encodedTreatments.get(treatVar)[i];
                }
            }
            finalCols.add(DoubleVector.of("TreatmentVal", finalTreatmentVals));

            // Add outcome
            finalCols.add(IntVector.of("Outcome", binaryOutcome));

            // Create and write final DataFrame
            DataFrame finalDf = DataFrame.of(finalCols.toArray(new BaseVector[0]));
            writeCsvManually(finalDf, outputCsvPath);

            // Log summary of processing
            logger.info("Processing complete:");
            logger.info("  - Rows processed: " + n);
            logger.info("  - Numeric covariates: " + numericCovariates.keySet());
            logger.info("  - Categorical covariates: " + encodedCovariates.keySet());
            logger.info("  - Treatment variables: " + treatmentGroups.keySet());
            logger.info("    - Numeric treatments: " + 
                       treatmentGroups.keySet().stream()
                           .filter(isTreatmentNumeric::get)
                           .collect(Collectors.toList()));
            logger.info("    - Categorical treatments: " + 
                       treatmentGroups.keySet().stream()
                           .filter(k -> !isTreatmentNumeric.get(k))
                           .collect(Collectors.toList()));
            logger.info("  - Output written to: " + outputCsvPath);

        } catch (IOException | URISyntaxException e) {
            logger.error("Error: ", e);
        }
    }

    /**
     * Median-impute a double array in place.
     */
    private static void medianImpute(double[] arr) {
        List<Double> values = new ArrayList<>();
        for (double v : arr) {
            if (!Double.isNaN(v)) {
                values.add(v);
            }
        }
        if (values.isEmpty()) return;
        Collections.sort(values);
        double median;
        int size = values.size();
        if (size % 2 == 0) {
            median = (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        } else {
            median = values.get(size / 2);
        }
        for (int i = 0; i < arr.length; i++) {
            if (Double.isNaN(arr[i])) {
                arr[i] = median;
            }
        }
    }

    /**
     * Write the DataFrame to CSV by hand. If a field contains a comma, wrap it in quotes.
     */
    private static void writeCsvManually(DataFrame df, String outPath) throws IOException {
        StructField[] fields = df.schema().fields();
        String[] colNames = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            colNames[i] = fields[i].name;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(outPath))) {
            pw.println(String.join(",", colNames));
            int nrows = df.size();
            int ncols = colNames.length;
            for (int i = 0; i < nrows; i++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < ncols; c++) {
                    if (c > 0) sb.append(",");
                    String val = df.getString(i, c);
                    if (val == null) {
                        val = "";
                    }
                    if (val.contains(",")) {
                        val = "\"" + val + "\"";
                    }
                    sb.append(val);
                }
                pw.println(sb.toString());
            }
        }
    }

    /**
     * Creates a sample input file with valid data for testing
     */
    private static void createSampleFile(File file) throws IOException {
        // Make sure the directory exists
        file.getParentFile().mkdirs();
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("TestArgs,Covariates,TreatmentVar,TreatmentVal,Outcome");
            // Ensure all rows have non-null Outcome values
            writer.println("\"test1\",\"mem=1024;cpu=2\",\"Outcome\",\"1\",\"1\"");
            writer.println("\"test1\",\"mem=1024;cpu=2\",\"heapSize\",\"512\",\"1\"");
            writer.println("\"test1\",\"mem=1024;cpu=2\",\"optimizer\",\"aggressive\",\"1\"");
            writer.println("\"test2\",\"threads=4;mode=parallel\",\"Outcome\",\"0\",\"0\"");
            writer.println("\"test2\",\"threads=4;mode=parallel\",\"timeout\",\"30\",\"0\"");
            writer.println("\"test2\",\"threads=4\",\"logging\",\"verbose\",\"0\"");
        }
    }
}