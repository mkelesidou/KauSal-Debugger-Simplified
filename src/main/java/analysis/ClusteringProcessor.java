package analysis;

import java.util.*;
import java.util.stream.Collectors;

public class ClusteringProcessor {

    // Method for quantile-based clustering for numeric values
    public static List<Integer> clusterNumeric(List<Double> values, int numQuantiles) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        // Assign cluster IDs based on quantiles
        List<Integer> clusterIDs = new ArrayList<>();
        for (double value : values) {
            int cluster = (int) Math.floor((sorted.indexOf(value) / (double) sorted.size()) * numQuantiles);
            clusterIDs.add(cluster);
        }
        return clusterIDs;
    }

    // Bool vals are discrete
    public static List<Integer> clusterBool(List<Boolean> values) {
        return values.stream().map(b -> b ? 1 : 0).collect(Collectors.toList());
    }

    // Placeholder for string clustering
    public static List<Integer> clusterString(List<String> values) {
        Map<String, Integer> stringToCluster = new HashMap<>();
        int clusterID = 0;
        for (String value : values) {
            if (!stringToCluster.containsKey(value)) {
                stringToCluster.put(value, clusterID++);
            }
        }
        return values.stream().map(stringToCluster::get).collect(Collectors.toList());
    }
}
