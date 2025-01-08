package analysis;

public class Main {

    public static void main(String[] args) {
        String inputCsv = "parsed_logs.csv"; // Replace with your log file name
        String outputCsv = "clustered_data.csv"; // File to save the clustered data
        int numQuantiles = 5; // Adjust based on your clustering requirements

        try {
            DataPreprocessor.preprocess(inputCsv, outputCsv, numQuantiles);
            System.out.println("Data preprocessing complete. Output saved to: " + outputCsv);
        } catch (Exception e) {
            System.err.println("Error during preprocessing: " + e.getMessage());
        }
    }
}
