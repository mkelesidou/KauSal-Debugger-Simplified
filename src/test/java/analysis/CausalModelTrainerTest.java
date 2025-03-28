package analysis;

import org.junit.jupiter.api.Test;

import smile.classification.LogisticRegression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;


public class CausalModelTrainerTest {

    /**
     * Test that the class can be instantiated and has the expected structure.
     * This test verifies the class has the expected fields and that they're properly initialized.
     */
    @Test
    void testClassStructure() throws Exception {
        // Verify we can access the class and its static fields
        Class<?> trainerClass = CausalModelTrainer.class;
        
        // Check for required fields
        Field csvField = trainerClass.getDeclaredField("PREPROCESSED_CSV");
        Field modelsField = trainerClass.getDeclaredField("MODELS_DIR");
        Field columnNamesField = trainerClass.getDeclaredField("COLUMN_NAMES");
        
        // Make them accessible
        csvField.setAccessible(true);
        modelsField.setAccessible(true);
        columnNamesField.setAccessible(true);
        
        // Verify they have non-null values
        assertNotNull(csvField.get(null));
        assertNotNull(modelsField.get(null));
        
        // Verify column names
        String[] columnNames = (String[])columnNamesField.get(null);
        assertEquals(8, columnNames.length);
        assertEquals("TestArgs", columnNames[0]);
        assertEquals("TreatmentVar", columnNames[5]);
        assertEquals("TreatmentVal", columnNames[6]);
        assertEquals("Outcome", columnNames[7]);
    }
    
    /**
     * Tests that LogisticRegression models can be trained on data similar to what 
     * the CausalModelTrainer processes.
     */
    @Test
    void testLogisticRegressionBehavior() throws Exception {
        // Prepare small test dataset similar to what the trainer would use
        double[][] features = {
            {6.5, 0.0, -1.0, 0.0, 2.0},  // cov_testAmount_1, cov_args_0, cov_data_1, cov_Integer, TreatmentVal 
            {6.5, 0.0, -1.0, 0.0, 6.0},
            {6.5, 0.0, -1.0, 0.0, 10.0}
        };
        
        int[] outcomes = {1, 0, 1};  // Outcome values
        
        // Train a logistic regression model
        LogisticRegression model = LogisticRegression.fit(features, outcomes);
        
        // Test the model works by making a prediction
        int prediction = model.predict(features[0]);
        
        // We just care that it returns a valid binary outcome
        assertTrue(prediction == 0 || prediction == 1);
        
        // Test the model can be serialised (as the CausalModelTrainer does)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(model);
        }
        
        // Verify we can deserialise it too
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            LogisticRegression deserializedModel = (LogisticRegression) ois.readObject();
            assertNotNull(deserializedModel);
            
            // Verify the deserialised model makes the same prediction
            assertEquals(prediction, deserializedModel.predict(features[0]));
        }
    }
    
    /**
     * Tests the behaviour of a constant predictor model similar to what the CausalModelTrainer
     * would create for single-class data.
     */
    @Test
    void testConstantPredictorBehavior() throws Exception {
        // Create a standalone constant predictor (final class to avoid serialization issues)
        final ConstantPredictor predictor = new ConstantPredictor(1);
        
        // Test it returns the constant value
        assertEquals(1, predictor.predict(new double[]{1.0, 2.0, 3.0}));
        
        // Test serialisation
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(predictor);
        }
        
        // Test deserialisation
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            ConstantPredictor deserializedPredictor = (ConstantPredictor) ois.readObject();
            assertNotNull(deserializedPredictor);
            
            // Verify the deserialised predictor makes the expected prediction
            assertEquals(1, deserializedPredictor.predict(new double[]{4.0, 5.0, 6.0}));
        }
    }
    
    /**
     * Test that the main method doesn't throw exceptions even with no valid input.
     * This is a basic test to ensure error handling is in place.
     */
    @Test
    void testNoExceptions() {
        // Simply run the main method - it should handle any errors without throwing exceptions
        assertDoesNotThrow(() -> CausalModelTrainer.main(new String[]{}));
    }
    
    /**
     * A simple serializable constant predictor class for testing.
     */
    private static class ConstantPredictor implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int constantValue;
        
        public ConstantPredictor(int value) {
            this.constantValue = value;
        }
        
        public int predict(double[] features) {
            return constantValue;
        }
    }
} 