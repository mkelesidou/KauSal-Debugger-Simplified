package instrumentation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CDGGenerator {

    public static class ControlDependenceGraph {
        private final Map<String, Set<String>> dependencies = new HashMap<>();

        public void addDependency(String fromNode, String toNode) {
            dependencies.putIfAbsent(fromNode, new HashSet<>());
            dependencies.get(fromNode).add(toNode);
        }

        public Map<String, Set<String>> getDependencies() {
            return dependencies;
        }

        public void printGraph() {
            System.out.println("Control Dependence Graph:");
            dependencies.forEach((key, value) -> {
                System.out.println(key + " -> " + value);
            });
        }
    }

    public ControlDependenceGraph generateCDG(CFGGenerator.ControlFlowGraph cfg) {
        ControlDependenceGraph cdg = new ControlDependenceGraph();

        // Simple example logic: Create dependencies for all edges in the CFG
        for (String edge : cfg.getEdges()) {
            String[] nodes = edge.split(" -> ");
            if (nodes.length == 2) {
                String fromNode = nodes[0];
                String toNode = nodes[1];
                cdg.addDependency(fromNode, toNode);
            }
        }

        return cdg;
    }

    public static void main(String[] args) {
        try {
            // Step 1: Generate the CFG
            String sourceCodePath = "src/main/java/examples/Example3.java"; // Replace with your actual file path
            CFGGenerator cfgGenerator = new CFGGenerator();
            CFGGenerator.ControlFlowGraph cfg = cfgGenerator.generateCFG(sourceCodePath);

            // Step 2: Generate the CDG using the CFG
            CDGGenerator cdgGenerator = new CDGGenerator();
            ControlDependenceGraph cdg = cdgGenerator.generateCDG(cfg);

            // Step 3: Print the CDG
            cdg.printGraph();

        } catch (Exception e) {
            System.err.println("Error during CDG generation: " + e.getMessage());
        }
    }
}
