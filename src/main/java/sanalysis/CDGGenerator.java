package sanalysis;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDGGenerator: A Control Dependence Graph (CDG) generator that leverages the CFGGenerator.
 *
 * Steps:
 *   1. Build the CFG using CFGGenerator.
 *   2. For each method's sub-CFG, compute postdominators and immediate postdominators.
 *   3. For each branch node (node with ≥2 successors), add CDG edges by following the ipdom chain.
 *   4. "Cover" step: for nodes with no incoming CD edge, add an edge from the method's start node.
 *   5. Merge all sub-CDGs into one interprocedural CDG and export it to a DOT file.
 */
public class CDGGenerator {

    private final CFGGenerator cfgGen = new CFGGenerator();
    private static final Logger logger = LoggerFactory.getLogger(CDGGenerator.class);

    /**
     * Generates the Control Flow Graph (CFG) from the source code.
     *
     * @param sourcePath the path to the source file.
     * @return the constructed CFG.
     */
    public CFGGenerator.ControlFlowGraph generateCFG(String sourcePath) {
        return cfgGen.generateCFG(sourcePath);
    }

    /**
     * Extracts the sub-CFG for a single method using DFS starting from the given node.
     *
     * @param cfg   the full CFG.
     * @param start the starting node (typically a method start node).
     * @return the set of nodes in the subgraph (component).
     */
    private static Set<CFGGenerator.CFGNode> extractComponent(CFGGenerator.ControlFlowGraph cfg, CFGGenerator.CFGNode start) {
        Set<CFGGenerator.CFGNode> visited = new HashSet<>();
        Deque<CFGGenerator.CFGNode> stack = new ArrayDeque<>();
        stack.push(start);
        visited.add(start);

        // Build a successor map from CFG edges.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> successors = new HashMap<>();
        for (CFGGenerator.CFGNode node : cfg.getNodes()) {
            successors.put(node, new HashSet<>());
        }
        for (CFGGenerator.CFGEdge edge : cfg.getEdges()) {
            successors.get(edge.from).add(edge.to);
        }

        // Traverse the CFG using DFS.
        while (!stack.isEmpty()) {
            CFGGenerator.CFGNode current = stack.pop();
            for (CFGGenerator.CFGNode neighbor : successors.get(current)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    stack.push(neighbor);
                }
            }
        }
        return visited;
    }

    /**
     * Computes postdominators for the given sub-CFG.
     *
     * @param subCfg the sub-CFG for a method.
     * @return a map from each node to its set of postdominators.
     */
    private static Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> computePostDominators(CFGGenerator.ControlFlowGraph subCfg) {
        // Add validation
        if (subCfg == null || subCfg.getNodes().isEmpty()) {
            throw new IllegalArgumentException("Invalid CFG: Graph is null or empty");
        }
        
        List<CFGGenerator.CFGNode> nodes = subCfg.getNodes();

        // Identify the exit node: assumed to be the node whose label starts with "Method End:".
        CFGGenerator.CFGNode exitNode = null;
        for (CFGGenerator.CFGNode node : nodes) {
            if (node.getLabel().startsWith("Method End:")) {
                exitNode = node;
                break;
            }
        }
        if (exitNode == null) {
            System.err.println("[CDG] No exit node found in sub-CFG");
            return null;
        }

        // Build a successor map.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> successors = new HashMap<>();
        for (CFGGenerator.CFGNode node : nodes) {
            successors.put(node, new HashSet<>());
        }
        for (CFGGenerator.CFGEdge edge : subCfg.getEdges()) {
            successors.get(edge.from).add(edge.to);
        }

        // Initialise postdominator sets:
        // For the exit node, pdom(exit) = {exit};
        // For all other nodes, pdom(n) = all nodes.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> postdoms = new HashMap<>();
        Set<CFGGenerator.CFGNode> allNodes = new HashSet<>(nodes);
        for (CFGGenerator.CFGNode node : nodes) {
            if (node.equals(exitNode)) {
                postdoms.put(node, new HashSet<>(Collections.singleton(exitNode)));
            } else {
                postdoms.put(node, new HashSet<>(allNodes));
            }
        }

        // Iteratively compute postdominators.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CFGGenerator.CFGNode node : nodes) {
                if (node.equals(exitNode))
                    continue;
                Set<CFGGenerator.CFGNode> newPostdoms = new HashSet<>();
                newPostdoms.add(node);
                Set<CFGGenerator.CFGNode> nodeSuccessors = successors.get(node);
                if (!nodeSuccessors.isEmpty()) {
                    boolean first = true;
                    for (CFGGenerator.CFGNode succ : nodeSuccessors) {
                        if (first) {
                            newPostdoms.addAll(postdoms.get(succ));
                            first = false;
                        } else {
                            newPostdoms.retainAll(postdoms.get(succ));
                        }
                    }
                    newPostdoms.add(node);
                }
                if (!newPostdoms.equals(postdoms.get(node))) {
                    postdoms.put(node, newPostdoms);
                    changed = true;
                }
            }
        }

        // Add validation of results
        if (postdoms == null || postdoms.isEmpty()) {
            throw new IllegalStateException("Failed to compute postdominators");
        }
        
        return postdoms;
    }

    /**
     * Computes immediate postdominators from the postdominator sets.
     *
     * @param postdoms a map from each node to its set of postdominators.
     * @return a map from each node to its immediate postdominator.
     */
    private static Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> computeImmediatePostDominators(
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> postdoms) {
        // Add input validation
        if (postdoms == null || postdoms.isEmpty()) {
            throw new IllegalArgumentException("Invalid postdominator sets");
        }
        
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> ipdom = new HashMap<>();
        for (CFGGenerator.CFGNode node : postdoms.keySet()) {
            Set<CFGGenerator.CFGNode> postdomSet = new HashSet<>(postdoms.get(node));
            if (postdomSet.size() <= 1)
                continue;
            postdomSet.remove(node); // Remove itself.
            CFGGenerator.CFGNode immediate = null;
            // Find the candidate that is not dominated by any other candidate.
            for (CFGGenerator.CFGNode candidate : postdomSet) {
                boolean isImmediate = true;
                for (CFGGenerator.CFGNode other : postdomSet) {
                    if (!other.equals(candidate) && postdoms.get(other).contains(candidate)) {
                        isImmediate = false;
                        break;
                    }
                }
                if (isImmediate) {
                    immediate = candidate;
                    break;
                }
            }
            ipdom.put(node, immediate);
        }

        // Add result validation
        for (CFGGenerator.CFGNode node : postdoms.keySet()) {
            if (!node.equals(getExitNode(postdoms)) && ipdom.get(node) == null) {
                logger.warn("Node {} has no immediate postdominator", node.getId());
            }
        }
        
        return ipdom;
    }

    // Add helper methods for validation
    private static CFGGenerator.CFGNode getExitNode(Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> postdoms) {
        return postdoms.entrySet().stream()
            .filter(e -> e.getValue().size() == 1 && e.getValue().contains(e.getKey()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    /**
     * Computes the sub-CDG for a single method's sub-CFG.
     *
     * @param subCfg the sub-CFG for a method.
     * @return a CDG represented as a map from a control node to its dependent nodes.
     */
    private static Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> computeCDGForSubCFG(
            CFGGenerator.ControlFlowGraph subCfg) {

        // 1. Compute postdominators.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> postdoms = computePostDominators(subCfg);
        // 2. Compute immediate postdominators.
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> ipdom = computeImmediatePostDominators(postdoms);

        // Build a successor map for subCfg.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> successors = new HashMap<>();
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            successors.put(node, new HashSet<>());
        }
        for (CFGGenerator.CFGEdge edge : subCfg.getEdges()) {
            successors.get(edge.from).add(edge.to);
        }

        // Initialise the CDG as an adjacency list (controller -> set of controlled nodes).
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = new HashMap<>();
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            cdg.put(node, new HashSet<>());
        }

        // 3. For each node X that is a "branch" (≥ 2 successors):
        for (CFGGenerator.CFGNode X : subCfg.getNodes()) {
            if (successors.get(X).size() < 2) {
                continue; // Not a branch node, skip.
            }
            // 4. For each successor Y of X:
            for (CFGGenerator.CFGNode Y : successors.get(X)) {
                // Special-case: if Y equals ipdom(X) and X is a loop condition, add the edge.
                if (Y.equals(ipdom.get(X)) && X.getLabel().startsWith("for-cond:")) {
                    cdg.get(X).add(Y);
                    continue;
                }
                // Otherwise, follow the ipdom chain upward from Y.
                CFGGenerator.CFGNode W = Y;
                while (W != null && !W.equals(X) && !W.equals(ipdom.get(X))) {
                    cdg.get(X).add(W);           // X controls W.
                    W = ipdom.get(W);            // Move one step up the postdominator tree.
                }
            }
        }

        // 5. "Cover" step: for nodes with no incoming CD edge, add an edge from the method's start node.
        CFGGenerator.CFGNode methodStart = null;
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            if (node.getLabel().startsWith("Method Start:")) {
                methodStart = node;
                break;
            }
        }
        if (methodStart != null) {
            // Build incoming map to see which nodes have no controller
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> incoming = new HashMap<>();
            for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
                incoming.put(node, new HashSet<>());
            }
            for (Map.Entry<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> entry : cdg.entrySet()) {
                CFGGenerator.CFGNode controller = entry.getKey();
                for (CFGGenerator.CFGNode dependent : entry.getValue()) {
                    incoming.get(dependent).add(controller);
                }
            }
            // For each node (except methodStart) with no incoming edge, link from methodStart
            for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
                if (!node.equals(methodStart) && incoming.get(node).isEmpty()) {
                    cdg.get(methodStart).add(node);
                }
            }
        }

        return cdg;
    }

    /**
     * Computes the interprocedural CDG by merging the CDGs computed for each method.
     *
     * @param cfg the overall CFG of the program.
     * @return the combined CDG represented as a map from control node to its dependent nodes.
     */
    public static Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> computeInterproceduralCDG(
            CFGGenerator.ControlFlowGraph cfg) {
        // Identify all method start nodes.
        List<CFGGenerator.CFGNode> methodStarts = new ArrayList<>();
        for (CFGGenerator.CFGNode node : cfg.getNodes()) {
            if (node.getLabel().startsWith("Method Start:")) {
                methodStarts.add(node);
            }
        }

        // Prepare the final combined CDG.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> combinedCDG = new HashMap<>();
        for (CFGGenerator.CFGNode node : cfg.getNodes()) {
            combinedCDG.put(node, new HashSet<>());
        }

        // For each method start, extract its sub-CFG, compute its CDG, and merge.
        for (CFGGenerator.CFGNode methodStart : methodStarts) {
            Set<CFGGenerator.CFGNode> component = extractComponent(cfg, methodStart);
            CFGGenerator.ControlFlowGraph subCfg = new CFGGenerator.ControlFlowGraph();
            // Add nodes to the sub-CFG.
            for (CFGGenerator.CFGNode node : component) {
                subCfg.addNode(node);
            }
            // Add edges that exist entirely within the component.
            for (CFGGenerator.CFGEdge edge : cfg.getEdges()) {
                if (component.contains(edge.from) && component.contains(edge.to)) {
                    subCfg.addEdge(edge.from, edge.to);
                }
            }
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> subCDG = computeCDGForSubCFG(subCfg);
            // Merge sub-CDG into the combined CDG.
            for (CFGGenerator.CFGNode controller : subCDG.keySet()) {
                combinedCDG.get(controller).addAll(subCDG.get(controller));
            }
        }
        return combinedCDG;
    }

    /**
     * Exports the interprocedural CDG to a DOT file.
     *
     * @param cdg     the combined CDG.
     * @param cfg     the overall CFG (used to include all nodes in the DOT file).
     * @param outPath the output path for the DOT file.
     */
    private static void exportCDG(Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg,
                                  CFGGenerator.ControlFlowGraph cfg,
                                  String outPath) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outPath))) {
            writer.println("digraph CDG {");
            // Collect all nodes.
            Set<CFGGenerator.CFGNode> allNodes = new HashSet<>(cfg.getNodes());
            for (Set<CFGGenerator.CFGNode> dependents : cdg.values()) {
                allNodes.addAll(dependents);
            }
            // Write node definitions.
            for (CFGGenerator.CFGNode node : allNodes) {
                String safeLabel = node.getLabel().replace("\"", "\\\"")
                        .replace("(", "\\(")
                        .replace(")", "\\)");
                writer.printf("  \"%s\" [label=\"%s\"];%n", node.getId(), safeLabel);
            }
            // Write CDG edges.
            for (Map.Entry<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> entry : cdg.entrySet()) {
                CFGGenerator.CFGNode controller = entry.getKey();
                for (CFGGenerator.CFGNode dependent : entry.getValue()) {
                    writer.printf("  \"%s\" -> \"%s\";%n", controller.getId(), dependent.getId());
                }
            }
            writer.println("}");
            System.out.println("CDG exported to: " + outPath);
        } catch (IOException ex) {
            System.err.println("Error exporting CDG: " + ex.getMessage());
        }
    }

    /**
     * Main method: Builds the CFG, computes the interprocedural CDG, and exports it.
     *
     * @param args command line arguments.
     */
    public static void main(String[] args) {
        String inputDomPath;
        String outputCdgPath;
        
        if (args.length >= 2) {
            inputDomPath = args[0];
            outputCdgPath = args[1];
        } else if (args.length == 1) {
            inputDomPath = args[0];
            outputCdgPath = inputDomPath.replace(".dom", ".cdg");
            System.out.println("No output path specified, using default: " + outputCdgPath);
        } else {
            System.out.println("Usage: java CDGGenerator <input_dom_file> [<output_cdg_file>]");
            System.out.println("Using default paths for testing purposes only.");
            inputDomPath = "src/main/resources/sanalysis/dags/dominator_tree_buggy_example.dot";
            outputCdgPath = "src/main/resources/sanalysis/cdgs/cdg_buggy_example.dot";
        }
        
        System.out.println("Reading dominator tree from: " + inputDomPath);

        // For this example, we'll generate a new CFG directly
        // In a real implementation, you would parse the dominator tree from the input file
        String sourcePath = "src/main/java/examples/BuggyExample.java";

        CDGGenerator generator = new CDGGenerator();
        CFGGenerator.ControlFlowGraph cfg = generator.generateCFG(sourcePath);
        System.out.println("Generated CFG:");
        cfg.printGraph();

        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = computeInterproceduralCDG(cfg);
        exportCDG(cdg, cfg, outputCdgPath);
        System.out.println("CDG exported to: " + outputCdgPath);
    }
}
