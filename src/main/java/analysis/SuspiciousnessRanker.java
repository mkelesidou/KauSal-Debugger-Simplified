package analysis;

import java.io.*;
import java.util.*;

public class SuspiciousnessRanker {

    public void rankSuspiciousness(String counterfactualCsv, String outputCsv, Map<String, String> variableMappings) throws IOException {
        List<SuspiciousVariable> variables = new ArrayList<>();

        // Read the counterfactual CSV
        try (BufferedReader reader = new BufferedReader(new FileReader(counterfactualCsv))) {
            String header = reader.readLine(); // Skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                int rowIndex = Integer.parseInt(parts[0]);
                double afce = Double.parseDouble(parts[1]);
                String variable = "Variable_" + rowIndex; // Placeholder variable name

                // Map variable back to source info, if mapping exists
                String sourceInfo = variableMappings.getOrDefault(variable, "Unknown Source");

                variables.add(new SuspiciousVariable(variable, afce, sourceInfo));
            }
        }

        // Sort by AFCE in descending order
        variables.sort((v1, v2) -> Double.compare(v2.getAfce(), v1.getAfce()));

        // Write the ranked results to the output CSV
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputCsv))) {
            writer.println("Rank,Variable,Source,AFCE");
            int rank = 1;
            for (SuspiciousVariable variable : variables) {
                writer.printf("%d,%s,%s,%.5f%n", rank++, variable.getName(), variable.getSourceInfo(), variable.getAfce());
            }
        }

        System.out.println("Suspiciousness ranking completed. Results saved to: " + outputCsv);
    }

    private static class SuspiciousVariable {
        private final String name;
        private final double afce;
        private final String sourceInfo;

        public SuspiciousVariable(String name, double afce, String sourceInfo) {
            this.name = name;
            this.afce = afce;
            this.sourceInfo = sourceInfo;
        }

        public String getName() {
            return name;
        }

        public double getAfce() {
            return afce;
        }

        public String getSourceInfo() {
            return sourceInfo;
        }
    }

    public static void main(String[] args) {
        try {
            String counterfactualCsv = "counterfactual_results.csv"; // Input file
            String outputCsv = "suspiciousness_ranking.csv"; // Output file

            // Example variable mappings (replace with actual mappings)
            Map<String, String> variableMappings = Map.of(
                    "Variable_0", "Line 10, File A.java",
                    "Variable_1", "Line 20, File B.java",
                    "Variable_2", "Line 30, File C.java"
            );

            SuspiciousnessRanker ranker = new SuspiciousnessRanker();
            ranker.rankSuspiciousness(counterfactualCsv, outputCsv, variableMappings);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
