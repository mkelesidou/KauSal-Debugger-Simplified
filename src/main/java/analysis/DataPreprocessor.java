package analysis;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class DataPreprocessor {

    public static void preprocess(String inputCsv, String outputCsv, int numQuantiles) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(inputCsv));
        String header = reader.readLine();
        List<List<String>> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            rows.add(Arrays.asList(line.split(",")));
        }
        reader.close();

        // Split into columns
        List<String> timestamps = rows.stream().map(row -> row.get(0)).toList();
        List<String> testNames = rows.stream().map(row -> row.get(1)).toList();
        List<String> predicateTypes = rows.stream().map(row -> row.get(2)).toList();
        List<String> sourceCodes = rows.stream().map(row -> row.get(3)).toList();
        List<String> outcomes = rows.stream().map(row -> row.get(4)).toList();
        List<String> variableStates = rows.stream().map(row -> row.get(5)).toList();

        // Cluster outcomes (Y)
        List<Integer> clusteredOutcomes = ClusteringProcessor.clusterBool(
                outcomes.stream().map(outcome -> outcome.equals("true")).collect(Collectors.toList())
        );

        // Extract numeric T_values from VariableStates
        List<Double> numericTValues = variableStates.stream()
                .map(state -> Double.parseDouble(state.split("=")[1])) // Example: "x=10" → 10.0
                .collect(Collectors.toList());

        // Cluster T_values
        List<Integer> clusteredTValues = ClusteringProcessor.clusterNumeric(numericTValues, numQuantiles);

        // Write output CSV
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsv));
        writer.write("Clustered_T_value,Covariates,Y\n"); // Adjust based on covariates

        for (int i = 0; i < rows.size(); i++) {
            String covariate = variableStates.get(i).split("=")[1]; // Example: x=10 → 10

            writer.write(String.format(
                    "%d,%s,%d\n",
                    clusteredTValues.get(i),      // Clustered T_value
                    covariate,                    // Covariate value
                    clusteredOutcomes.get(i)      // Outcome (0 or 1)
            ));
        }
        writer.close();
    }
}
