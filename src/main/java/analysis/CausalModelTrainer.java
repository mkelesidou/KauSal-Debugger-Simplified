package analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.classification.LogisticRegression;
import smile.data.DataFrame;
import smile.io.CSV;
import smile.data.type.DataTypes;
import smile.data.type.StructField;
import org.apache.commons.csv.CSVFormat;


import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import smile.data.formula.Term;

public class CausalModelTrainer {
    private static final Logger logger = LoggerFactory.getLogger(CausalModelTrainer.class);

    // Default file paths to use if no command-line arguments are provided
    private static final String DEFAULT_PREPROCESSED_CSV = "src/main/resources/datasets/preprocessed_data_buggy_example.csv";
    private static final String DEFAULT_MODELS_DIR = "src/main/resources/models/";

    // Column names in the CSV file
    private static final String[] COLUMN_NAMES = {
        "TestArgs",           // V1
        "cov_testAmount_1",   // V2
        "cov_args_0",         // V3
        "cov_data_1",         // V4
        "cov_Integer",        // V5
        "TreatmentVar",       // V6
        "TreatmentVal",       // V7
        "Outcome"            // V8
    };

    public static void main(String[] args) {
        try {
            String preprocessedCsvPath;
            String modelsDirPath;
            
            if (args.length >= 2) {
                preprocessedCsvPath = args[0];
                modelsDirPath = args[1];
            } else if (args.length == 1) {
                preprocessedCsvPath = args[0];
                modelsDirPath = new File(preprocessedCsvPath).getParent() + "/models/";
                logger.info("No models directory specified, using default: {}", modelsDirPath);
            } else {
                logger.info("Usage: java CausalModelTrainer <preprocessed_csv_file> [<models_directory>]");
                logger.info("Using default paths for testing purposes only.");
                preprocessedCsvPath = DEFAULT_PREPROCESSED_CSV;
                modelsDirPath = DEFAULT_MODELS_DIR;
            }
            
            logger.info("Reading preprocessed data from: {}", preprocessedCsvPath);
            logger.info("Saving models to: {}", modelsDirPath);
            
            // Get the absolute path to the CSV file
            File csvFile = new File(preprocessedCsvPath);
            if (!csvFile.exists()) {
                logger.error("CSV file not found at: {}", csvFile.getAbsolutePath());
                return;
            }
            
            // Read the CSV file with explicit schema and header handling
            CSVFormat format = CSVFormat.DEFAULT
                .withHeader(COLUMN_NAMES)
                .withSkipHeaderRecord(true)
                .withDelimiter(',')
                .withQuote('"')
                .withIgnoreEmptyLines()
                .withTrim(true);
            
            CSV csv = new CSV(format);
            csv.schema(DataTypes.struct(
                new StructField(COLUMN_NAMES[0], DataTypes.StringType),
                new StructField(COLUMN_NAMES[1], DataTypes.DoubleType),
                new StructField(COLUMN_NAMES[2], DataTypes.DoubleType),
                new StructField(COLUMN_NAMES[3], DataTypes.DoubleType),
                new StructField(COLUMN_NAMES[4], DataTypes.DoubleType),
                new StructField(COLUMN_NAMES[5], DataTypes.StringType),
                new StructField(COLUMN_NAMES[6], DataTypes.DoubleType),
                new StructField(COLUMN_NAMES[7], DataTypes.IntegerType)
            ));
            
            DataFrame df = csv.read(csvFile.getAbsolutePath());
            
            // Debug logging to understand the data structure
            logger.info("Loaded DataFrame with {} rows and {} columns", df.size(), df.names().length);
            logger.info("Column names: {}", String.join(", ", df.names()));
            
            // Get unique treatment variables
            List<String> treatmentVars = df.stringVector(COLUMN_NAMES[5]).stream()
                .distinct()
                .collect(Collectors.toList());
            logger.info("Found {} distinct treatment variables: {}", treatmentVars.size(), treatmentVars);
            
            // Group data by treatment variable
            Map<String, DataFrame> groupedData = new HashMap<>();
            for (String treatmentVar : treatmentVars) {
                try {
                    // Get indices for this treatment variable
                    List<Integer> indices = IntStream.range(0, df.size())
                        .filter(i -> {
                            try {
                                String value = df.getString(i, COLUMN_NAMES[5]);
                                return value != null && value.equals(treatmentVar);
                            } catch (Exception e) {
                                logger.warn("Error accessing row {} for treatment variable {}: {}", i, treatmentVar, e.getMessage());
                                return false;
                            }
                        })
                        .boxed()
                        .collect(Collectors.toList());
                    
                    if (!indices.isEmpty()) {
                        logger.info("Found {} rows for treatment variable {}", indices.size(), treatmentVar);
                        logger.debug("Indices for {}: {}", treatmentVar, indices);
                        
                        // Filter indices that are within bounds
                        List<Integer> validIndices = indices.stream()
                            .filter(i -> i < df.size())
                            .collect(Collectors.toList());
                        
                        if (validIndices.isEmpty()) {
                            logger.warn("No valid indices found for treatment variable {}", treatmentVar);
                            continue;
                        }
                        
                        // Create a new DataFrame with only the selected rows, maintaining the schema
                        DataFrame groupSubset = DataFrame.of(validIndices.stream()
                            .map(i -> df.get(i))
                            .collect(Collectors.toList()), df.schema());
                            
                        logger.debug("Group subset columns: {}", String.join(", ", groupSubset.names()));
                        logger.debug("Group subset schema: {}", groupSubset.schema());
                        
                        // Select only numeric columns for training
                        String[] numericColumns = {
                            "cov_testAmount_1", "cov_args_0", "cov_data_1", 
                            "cov_Integer", "TreatmentVal", "Outcome"
                        };
                        
                        DataFrame numericGroupData = groupSubset.select(numericColumns);
                        logger.debug("Numeric DataFrame columns: {}", String.join(", ", numericGroupData.names()));
                        logger.debug("Numeric DataFrame schema: {}", numericGroupData.schema());
                        
                        logger.info("Training model for treatment variable {}", treatmentVar);
                        
                        try {
                            // Extract data into arrays manually
                            int n = numericGroupData.size();
                            double[][] x = new double[n][5]; // 5 features
                            int[] y = new int[n];
                            
                            // Extract feature columns directly - we know the order from the select() call above
                            for (int i = 0; i < n; i++) {
                                x[i][0] = numericGroupData.getDouble(i, 0); // cov_testAmount_1
                                x[i][1] = numericGroupData.getDouble(i, 1); // cov_args_0
                                x[i][2] = numericGroupData.getDouble(i, 2); // cov_data_1
                                x[i][3] = numericGroupData.getDouble(i, 3); // cov_Integer
                                x[i][4] = numericGroupData.getDouble(i, 4); // TreatmentVal
                                y[i] = numericGroupData.getInt(i, 5); // Outcome
                            }
                            
                            logger.info("Training model with {} samples, {} features", n, 5);
                            
                            // Check for the number of unique classes
                            int[] uniqueClasses = IntStream.of(y).distinct().toArray();
                            if (uniqueClasses.length == 1) {
                                logger.info("Only one class ({}). Creating constant predictor.", uniqueClasses[0]);
                                
                                // Create a serialisable object that will always predict the constant class
                                final int constantClass = uniqueClasses[0];
                                
                                // Create a simple serialisable classifier
                                Object model = new java.io.Serializable() {
                                    private static final long serialVersionUID = 1L;
                                    
                                    // This method will be called when using the model to predict
                                    public int predict(double[] features) {
                                        return constantClass;
                                    }
                                };
                                
                                // Save the model using Java serialisation
                                String modelFile = modelsDirPath + "model_" + treatmentVar + ".model";
                                File modelDir = new File(modelsDirPath);
                                if (!modelDir.exists()) {
                                    modelDir.mkdirs();
                                }
                                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(modelFile))) {
                                    oos.writeObject(model);
                                }
                                logger.info("Saved constant model for {} to {}", treatmentVar, modelFile);
                            } else {
                                // Train a Logistic Regression model directly with the feature arrays
                                logger.info("Training Logistic Regression model with {} classes", uniqueClasses.length);
                                LogisticRegression model = LogisticRegression.fit(x, y);
                                
                                // Save the model using Java serialisation
                                String modelFile = modelsDirPath + "model_" + treatmentVar + ".model";
                                File modelDir = new File(modelsDirPath);
                                if (!modelDir.exists()) {
                                    modelDir.mkdirs();
                                }
                                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(modelFile))) {
                                    oos.writeObject(model);
                                }
                                logger.info("Saved Logistic Regression model for {} to {}", treatmentVar, modelFile);
                            }
                        } catch (Exception e) {
                            logger.error("Error training model for {}: {}", treatmentVar, e.getMessage());
                            logger.error("Stack trace: ", e);
                        }
                    } else {
                        logger.warn("No rows found for treatment variable {}", treatmentVar);
                    }
                } catch (Exception e) {
                    logger.error("Error processing treatment variable {}: {}", treatmentVar, e.getMessage());
                    logger.error("Stack trace: ", e);
                }
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("Error in CausalModelTrainer: {}", e.getMessage(), e);
        }
    }
}
