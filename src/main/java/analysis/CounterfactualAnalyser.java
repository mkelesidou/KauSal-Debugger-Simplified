package analysis;

import java.io.*;
import java.util.*;

public class CounterfactualAnalyser {
    private final RandomForestModel model;

    public CounterfactualAnalyser(RandomForestModel model) {
        this.model = model;
    }

    public void analyse(String inputCsv, String outputCsv) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(inputCsv));
        String header = reader.readLine(); // Skip the header
        List<List<Double>> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            List<Double> row = new ArrayList<>();
            for (String part : parts) {
                row.add(Double.parseDouble(part));
            }
            rows.add(row);
        }
        reader.close();

        // Extract unique treatment values (RepT)
        Set<Double> uniqueTValues = new HashSet<>();
        for (List<Double> row : rows) {
            uniqueTValues.add(row.get(0)); // T_value is the first column
        }
        List<Double> representatives = new ArrayList<>(uniqueTValues);
        System.out.println("Cluster Representatives (RepT): " + representatives);

        BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsv));
        writer.write("RowIndex,AFCE\n");

        for (int i = 0; i < rows.size(); i++) {
            List<Double> row = rows.get(i);
            double observedTValue = row.get(0);
            double observedOutcome = row.get(row.size() - 1);

            double maxEffect = 0.0;
            for (int m = 0; m < representatives.size(); m++) {
                for (int k = m + 1; k < representatives.size(); k++) {
                    double t_m = representatives.get(m);
                    double t_k = representatives.get(k);

                    // Replace T_value with t_m and t_k
                    row.set(0, t_m);
                    double y_t_m = model.classifyInstance(new ArrayList<>(row));

                    row.set(0, t_k);
                    double y_t_k = model.classifyInstance(new ArrayList<>(row));

                    double effect = Math.abs(y_t_m - y_t_k);
                    maxEffect = Math.max(maxEffect, effect);

                    // Debugging outputs
                    System.out.printf(
                            "Row %d: t_m=%.2f, t_k=%.2f, y_t_m=%.2f, y_t_k=%.2f, effect=%.2f\n",
                            i, t_m, t_k, y_t_m, y_t_k, effect
                    );
                }
            }

            writer.write(String.format("%d,%.5f\n", i, maxEffect));
        }

        writer.close();
        System.out.println("Counterfactual analysis completed. Results saved to: " + outputCsv);
    }
}
