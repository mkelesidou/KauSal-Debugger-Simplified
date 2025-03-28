package analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.classification.RandomForest;
import smile.classification.LogisticRegression;
import smile.classification.Classifier;
import smile.data.DataFrame;
import smile.io.CSV;
import smile.data.type.DataTypes;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.Tuple;


import java.io.*;
import java.util.*;


public class CounterfactualPredictor {
    private static final Logger logger = LoggerFactory.getLogger(CounterfactualPredictor.class);

    // File paths
    private static String PROCESSED_DATASET = "src/main/resources/datasets/preprocessed_data_buggy_example.csv";
    private static String MODELS_DIR = "src/main/resources/models/";
    private static String OUTPUT_RANKING = "src/main/resources/results/suspiciousness_scores_buggy_example.csv";

    // Column names - updated to match generic CSV column names (V1, V2, etc.)
    // Based on the CausalModelTrainer logs, the expected mapping is:
    // V1 = TestArgs, V2-V5 = covariates, V6 = TreatmentVar, V7 = TreatmentVal, V8 = Outcome
    private static String COL_TEST_ARGS = "V1";
    private static String COL_TREATMENT_VAR = "V6";
    private static String COL_TREATMENT_VAL = "V7";
    private static String COL_OUTCOME = "V8";
    
    // Flag to track if we need to skip the header row
    private static boolean SKIP_HEADER = false;

    /**
     * A simple wrapper for constant predictors
     */
    private static class ConstantPredictor implements Classifier<double[]> {
        private final int constantClass;
        
        public ConstantPredictor(int constantClass) {
            this.constantClass = constantClass;
        }
        
        @Override
        public int predict(double[] x) {
            return constantClass;
        }
    }

    public static void main(String[] args) {
        if (args.length >= 3) {
            PROCESSED_DATASET = args[0];
            MODELS_DIR = args[1];
            OUTPUT_RANKING = args[2];
        } else {
            logger.info("Using default file paths:\nProcessed Dataset: {}\nModels Dir: {}\nOutput Ranking: {}",
                    PROCESSED_DATASET, MODELS_DIR, OUTPUT_RANKING);
        }

        try {
            // Load the preprocessed dataset
            CSV csv = new CSV();
            DataFrame df = csv.read(new File(PROCESSED_DATASET).getAbsolutePath());
            
            // Check DataFrame structure
            logger.info("DataFrame has {} rows and {} columns", df.size(), df.ncols());
            String[] columnNames = df.names();
            logger.info("Available columns: {}", Arrays.toString(columnNames));
            
            // Print first few rows to see data format
            logger.info("Printing first 5 rows for debugging:");
            for (int i = 0; i < Math.min(5, df.size()); i++) {
                StringBuilder row = new StringBuilder();
                for (String col : columnNames) {
                    row.append(col).append("=");
                    if (col.equals(COL_TREATMENT_VAR) || col.equals(COL_TEST_ARGS)) {
                        row.append(df.getString(i, col));
                    } else {
                        row.append(df.get(i, col));
                    }
                    row.append(", ");
                }
                logger.info("Row {}: {}", i, row.toString());
            }
            
            // Verify required columns exist
            if (!hasColumn(df, COL_TREATMENT_VAR)) {
                logger.error("Required column '{}' not found in dataset. Available columns: {}", 
                             COL_TREATMENT_VAR, Arrays.toString(columnNames));
                return;
            }
            
            if (!hasColumn(df, COL_TREATMENT_VAL)) {
                logger.error("Required column '{}' not found in dataset", COL_TREATMENT_VAL);
                return;
            }
            
            if (!hasColumn(df, COL_OUTCOME)) {
                logger.error("Required column '{}' not found in dataset", COL_OUTCOME);
                return;
            }
            
            // Check if first row is a header
            if (df.size() > 0 && "TestArgs".equals(df.getString(0, COL_TEST_ARGS)) &&
                "TreatmentVar".equals(df.getString(0, COL_TREATMENT_VAR))) {
                logger.info("First row appears to be a header row, will skip it in processing");
                SKIP_HEADER = true;
            }
            
            // Manually collect treatment variables and related rows
            Map<String, List<Integer>> treatmentToRows = new HashMap<>();
            int startRow = SKIP_HEADER ? 1 : 0;
            for (int i = startRow; i < df.size(); i++) {
                String treatmentVar = df.getString(i, COL_TREATMENT_VAR);
                treatmentToRows.computeIfAbsent(treatmentVar, k -> new ArrayList<>()).add(i);
            }
            
            logger.info("Found {} distinct treatment variables: {}", 
                       treatmentToRows.size(), treatmentToRows.keySet());
            
            // Now create separate model analysis for each treatment variable
            Map<String, Double> suspiciousnessMap = new HashMap<>();
            
            for (Map.Entry<String, List<Integer>> entry : treatmentToRows.entrySet()) {
                String treatmentVar = entry.getKey();
                List<Integer> rowIndices = entry.getValue();
                
                if (rowIndices.isEmpty()) {
                    logger.warn("No rows found for treatment variable '{}'", treatmentVar);
                    continue;
                }
                
                logger.info("Processing {} rows for treatment variable '{}'", rowIndices.size(), treatmentVar);
                
                // Collect data for this treatment variable
                List<Double> treatmentVals = new ArrayList<>();
                List<Double> outcomeVals = new ArrayList<>();
                Map<String, List<Double>> covariateVals = new HashMap<>();
                
                // Initialise covariate lists
                for (String col : df.names()) {
                    if (!col.equals(COL_TEST_ARGS) && !col.equals(COL_TREATMENT_VAR) && 
                        !col.equals(COL_TREATMENT_VAL) && !col.equals(COL_OUTCOME)) {
                        covariateVals.put(col, new ArrayList<>());
                    }
                }
                
                // Collect all values
                for (int rowIdx : rowIndices) {
                    try {
                        // Get treatment value - handle potential string values
                        double treatmentVal;
                        try {
                            treatmentVal = df.getDouble(rowIdx, COL_TREATMENT_VAL);
                        } catch (ClassCastException e) {
                            // If it's a string, try to parse it
                            String valStr = df.getString(rowIdx, COL_TREATMENT_VAL);
                            treatmentVal = parseNumeric(valStr);
                        }
                        treatmentVals.add(treatmentVal);
                        
                        // Get outcome value
                        double outcomeVal;
                        try {
                            outcomeVal = df.getInt(rowIdx, COL_OUTCOME);
                        } catch (ClassCastException e) {
                            String valStr = df.getString(rowIdx, COL_OUTCOME);
                            outcomeVal = parseNumeric(valStr);
                        }
                        outcomeVals.add(outcomeVal);
                        
                        // Get covariates
                        for (String col : covariateVals.keySet()) {
                            double value;
                            try {
                                value = df.getDouble(rowIdx, col);
                            } catch (ClassCastException e) {
                                // Try to get as string and convert
                                String valStr = df.getString(rowIdx, col);
                                value = parseNumeric(valStr);
                            }
                            covariateVals.get(col).add(value);
                        }
                    } catch (Exception e) {
                        logger.error("Error reading row {}: {}", rowIdx, e.getMessage());
                    }
                }
                
                // Create a simple DataFrame-like structure to work with
                Map<String, Object> dataMap = new HashMap<>();
                dataMap.put(COL_TREATMENT_VAL, treatmentVals);
                dataMap.put(COL_OUTCOME, outcomeVals);
                for (Map.Entry<String, List<Double>> covEntry : covariateVals.entrySet()) {
                    dataMap.put(covEntry.getKey(), covEntry.getValue());
                }
                
                // Load the model
                String modelPath = MODELS_DIR + "model_" + treatmentVar + ".model";
                File modelFile = new File(modelPath);
                
                if (!modelFile.exists()) {
                    logger.error("Model file not found: {}", modelPath);
                    continue;
                }
                
                Object modelObj;
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(modelFile))) {
                    modelObj = ois.readObject();
                    
                    if (modelObj instanceof Integer) {
                        int constantClass = (Integer) modelObj;
                        modelObj = new ConstantPredictor(constantClass);
                        logger.info("Loaded constant predictor (class={}) for treatment variable '{}'", 
                                constantClass, treatmentVar);
                    } else if (modelObj instanceof RandomForest) {
                        logger.info("Loaded RandomForest model for treatment variable '{}'", treatmentVar);
                    } else if (modelObj instanceof LogisticRegression) {
                        logger.info("Loaded LogisticRegression model for treatment variable '{}'", treatmentVar);
                    } else if (modelObj.getClass().getName().contains("CausalModelTrainer$")) {
                        // Handle the anonymous inner class from CausalModelTrainer
                        // It's likely a constant predictor - look for a field that gives the constant value
                        try {
                            // Try to check if it has a predict method
                            java.lang.reflect.Method predictMethod = modelObj.getClass().getMethod("predict", double[].class);
                            if (predictMethod != null) {
                                logger.info("Found custom predictor with predict method for treatment variable '{}'", treatmentVar);
                                // Keep the object as is - we'll use reflection to call predict later
                            }
                        } catch (NoSuchMethodException e) {
                            logger.warn("Custom model for '{}' doesn't have predict method, using default constant (1)", treatmentVar);
                            // Default to a constant predictor with outcome 1
                            modelObj = new ConstantPredictor(1);
                        }
                    } else {
                        logger.error("Unknown model type for '{}': {}", treatmentVar, modelObj.getClass().getName());
                        continue;
                    }
                } catch (Exception e) {
                    logger.error("Could not load model for '{}': {}", treatmentVar, e.getMessage());
                    continue;
                }
                
                // Find representative values
                List<Double> repValues = findRepresentativeValues(treatmentVals);
                logger.info("Treatment variable '{}': Representative values: {}", treatmentVar, repValues);
                
                // Compute suspiciousness
                double suspScore = computeSuspiciousnessWithMap(dataMap, repValues, modelObj);
                suspiciousnessMap.put(treatmentVar, suspScore);
                logger.info("Treatment variable '{}': Suspiciousness Score = {}", treatmentVar, suspScore);
            }
            
            // Sort and write suspiciousness scores
            List<Map.Entry<String, Double>> sortedScores = new ArrayList<>(suspiciousnessMap.entrySet());
            sortedScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            writeSuspiciousnessScores(sortedScores, OUTPUT_RANKING);
            logger.info("Wrote suspiciousness ranking to {}", OUTPUT_RANKING);
            
        } catch (Exception e) {
            logger.error("Error in CounterfactualPredictor: {}", e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * Check if a column exists in the DataFrame
     */
    private static boolean hasColumn(DataFrame df, String columnName) {
        for (String name : df.names()) {
            if (name.equals(columnName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute suspiciousness using the provided data map instead of DataFrame
     */
    private static double computeSuspiciousnessWithMap(Map<String, Object> dataMap, List<Double> repValues, Object model) {
        // Get covariate names
        List<String> covariateNames = new ArrayList<>();
        for (String col : dataMap.keySet()) {
            if (!col.equals(COL_OUTCOME) && !col.equals(COL_TEST_ARGS) && 
                !col.equals(COL_TREATMENT_VAR) && !col.equals(COL_TREATMENT_VAL)) {
                covariateNames.add(col);
            }
        }
        logger.info("Using covariates: {}", covariateNames);
        
        // Compute means for each covariate
        Map<String, Double> covMeans = new HashMap<>();
        for (String cov : covariateNames) {
            List<Double> values = (List<Double>) dataMap.get(cov);
            double sum = 0.0;
            int count = 0;
            for (Double val : values) {
                if (val != null && !Double.isNaN(val)) {
                    sum += val;
                    count++;
                }
            }
            covMeans.put(cov, count > 0 ? sum / count : 0.0);
        }
        logger.info("Computed covariate means: {}", covMeans);
        
        // Prepare for predictions
        Map<Double, List<Double>> predictionsByRep = new HashMap<>();
        for (Double rep : repValues) {
            predictionsByRep.put(rep, new ArrayList<>());
        }
        
        // Get data
        List<Double> treatmentVals = (List<Double>) dataMap.get(COL_TREATMENT_VAL);
        
        // For each data point
        for (int i = 0; i < treatmentVals.size(); i++) {
            // Create feature vector
            double[] features = new double[covariateNames.size() + 1];
            features[0] = treatmentVals.get(i);
            
            int idx = 1;
            for (String cov : covariateNames) {
                List<Double> covValues = (List<Double>) dataMap.get(cov);
                double value = i < covValues.size() ? covValues.get(i) : Double.NaN;
                features[idx++] = Double.isNaN(value) ? covMeans.getOrDefault(cov, 0.0) : value;
            }
            
            // Create schema
            StructField[] fields = new StructField[covariateNames.size() + 1];
            fields[0] = new StructField(COL_TREATMENT_VAL, DataTypes.DoubleType);
            for (int f = 0; f < covariateNames.size(); f++) {
                fields[f + 1] = new StructField(covariateNames.get(f), DataTypes.DoubleType);
            }
            StructType schema = DataTypes.struct(fields);
            
            // Predict for each representative value
            for (Double repVal : repValues) {
                features[0] = repVal;
                
                int prediction;
                try {
                    if (model instanceof RandomForest) {
                        Tuple tuple = Tuple.of(features, schema);
                        prediction = ((RandomForest) model).predict(tuple);
                    } else if (model instanceof LogisticRegression) {
                        prediction = ((LogisticRegression) model).predict(features);
                    } else if (model instanceof ConstantPredictor) {
                        prediction = ((ConstantPredictor) model).predict(features);
                    } else if (model.getClass().getName().contains("CausalModelTrainer$")) {
                        // Use reflection to call predict on the custom model
                        try {
                            java.lang.reflect.Method predictMethod = model.getClass().getMethod("predict", double[].class);
                            Object result = predictMethod.invoke(model, features);
                            if (result instanceof Integer) {
                                prediction = (Integer) result;
                            } else {
                                logger.error("Unexpected prediction result type: {}", result.getClass().getName());
                                prediction = 0;
                            }
                        } catch (Exception e) {
                            logger.error("Error calling predict on custom model: {}", e.getMessage());
                            prediction = 0;
                        }
                    } else {
                        logger.error("Unknown model type: {}", model.getClass().getName());
                        prediction = 0;
                    }
                    predictionsByRep.get(repVal).add(prediction == 1 ? 1.0 : 0.0);
                } catch (Exception e) {
                    logger.error("Prediction failed: {}", e.getMessage());
                    predictionsByRep.get(repVal).add(0.5); // Default to uncertainty
                }
            }
        }
        
        // Compute min and max averages
        double minAvg = Double.POSITIVE_INFINITY;
        double maxAvg = Double.NEGATIVE_INFINITY;
        for (Double rep : repValues) {
            List<Double> preds = predictionsByRep.get(rep);
            double sum = 0.0;
            for (Double p : preds) {
                sum += p;
            }
            double avg = preds.isEmpty() ? 0.0 : sum / preds.size();
            logger.info("Representative treatment value {}: Average predicted outcome = {}", rep, avg);
            minAvg = Math.min(minAvg, avg);
            maxAvg = Math.max(maxAvg, avg);
        }
        
        double suspScore = maxAvg - minAvg;
        logger.info("Suspiciousness score: {} (max={}, min={})", suspScore, maxAvg, minAvg);
        return suspScore;
    }
    
    /**
     * Find representative values from a list of treatment values
     */
    private static List<Double> findRepresentativeValues(List<Double> treatmentVals) {
        Set<Double> uniqueVals = new HashSet<>(treatmentVals);
        boolean onlyBoolean = true;
        
        for (double val : uniqueVals) {
            if (val != 0.0 && val != 1.0) {
                onlyBoolean = false;
                break;
            }
        }
        
        if (onlyBoolean && uniqueVals.size() <= 2) {
            return Arrays.asList(0.0, 1.0);
        }
        
        List<Double> sorted = new ArrayList<>(uniqueVals);
        Collections.sort(sorted);
        
        if (sorted.size() >= 3) {
            double min = sorted.get(0);
            double median = sorted.get(sorted.size() / 2);
            double max = sorted.get(sorted.size() - 1);
            return Arrays.asList(min, median, max);
        } else {
            return sorted;
        }
    }

    private static void writeSuspiciousnessScores(List<Map.Entry<String, Double>> sortedScores, String outputPath) {
        File outFile = new File(outputPath);
        if (!outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(outFile))) {
            writer.println("TreatmentVar,Suspiciousness Score");
            for (Map.Entry<String, Double> entry : sortedScores) {
                writer.printf("%s,%.5f%n", entry.getKey(), entry.getValue());
            }
            logger.info("Wrote suspiciousness scores to {}", outputPath);
        } catch (IOException e) {
            logger.error("Error writing suspiciousness scores: {}", e.getMessage());
        }
    }

    /**
     * Parse a value to a numeric type, handling various formats
     */
    private static double parseNumeric(String s) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.equalsIgnoreCase("true") || s.equals("1"))
            return 1.0;
        if (s.equalsIgnoreCase("false") || s.equals("0"))
            return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

