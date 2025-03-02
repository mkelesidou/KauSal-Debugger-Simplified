package sanalysis;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * CDGGenerator: A production-ready Control Dependence Graph (CDG) generator that leverages the CFGGenerator.
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

        // Initialize postdominator sets:
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
        return ipdom;
    }

    /**
     * Computes the sub-CDG for a single method's sub-CFG.
     *
     * @param subCfg the sub-CFG for a method.
     * @return a CDG represented as a map from a control node to its dependent nodes.
     */
    private static Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> computeCDGForSubCFG(CFGGenerator.ControlFlowGraph subCfg) {
        // Step 1: Compute postdominators.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> postdoms = computePostDominators(subCfg);
        // Step 2: Compute immediate postdominators.
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> ipdom = computeImmediatePostDominators(postdoms);

        // Build a successor map for subCfg.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> successors = new HashMap<>();
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            successors.put(node, new HashSet<>());
        }
        for (CFGGenerator.CFGEdge edge : subCfg.getEdges()) {
            successors.get(edge.from).add(edge.to);
        }

        // Initialize the CDG as an adjacency list.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = new HashMap<>();
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            cdg.put(node, new HashSet<>());
        }

        // For each branch node (node with ≥2 successors), add CDG edges by following the ipdom chain.
        for (CFGGenerator.CFGNode branchNode : subCfg.getNodes()) {
            if (successors.get(branchNode).size() < 2)
                continue; // Not a branch node.

            for (CFGGenerator.CFGNode successor : successors.get(branchNode)) {
                // Skip if the branch node postdominates this successor.
                if (postdoms.get(branchNode).contains(successor))
                    continue;

                CFGGenerator.CFGNode currentRunner = successor;
                // Follow the ipdom chain until reaching ipdom(branchNode) (if defined).
                while (currentRunner != null && (ipdom.get(branchNode) == null || !currentRunner.equals(ipdom.get(branchNode)))) {
                    if (currentRunner.equals(branchNode))
                        break; // Avoid self-dependency.
                    cdg.get(branchNode).add(currentRunner);
                    CFGGenerator.CFGNode nextRunner = ipdom.get(currentRunner);
                    if (nextRunner == null || nextRunner.equals(currentRunner))
                        break;
                    currentRunner = nextRunner;
                }
            }
        }

        // "Cover" step: for nodes with no incoming CD edge, add an edge from the method's start node.
        CFGGenerator.CFGNode methodStart = null;
        for (CFGGenerator.CFGNode node : subCfg.getNodes()) {
            if (node.getLabel().startsWith("Method Start:")) {
                methodStart = node;
                break;
            }
        }
        if (methodStart != null) {
            // Build incoming CD edge map.
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
            // For each node (except methodStart) with no incoming CD edge, add an edge from methodStart.
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
        // Adjust the source path if necessary.
        String sourcePath = "src/main/java/examples/SimpleExample.java";

        CDGGenerator generator = new CDGGenerator();
        CFGGenerator.ControlFlowGraph cfg = generator.generateCFG(sourcePath);
        System.out.println("Generated CFG:");
        cfg.printGraph();

        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = computeInterproceduralCDG(cfg);
        exportCDG(cdg, cfg, "src/main/resources/sanalysis/cdgs/cdg_simple_example.dot");
    }
}
