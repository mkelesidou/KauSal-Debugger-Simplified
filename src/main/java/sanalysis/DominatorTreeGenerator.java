package sanalysis;

import java.util.*;

/**
 * DominatorTreeGenerator computes dominator sets for every node in a CFG,
 * extracts immediate dominators, and then builds the dominator tree.
 *
 * This version handles an entire class by creating a virtual entry node.
 * For the main method, the virtual entry remains its predecessor.
 * For other methods, if a call site can be identified (e.g., a statement
 * that calls the method), an edge is added from the call site to the method's start.
 */
public class DominatorTreeGenerator {

    /**
     * Computes the dominator sets for every node in the given CFG.
     * The algorithm uses an iterative fixed-point approach:
     *   - Initially, Dom(entry) = {entry} and for every other node, Dom(n) = all nodes.
     *   - Then, iteratively update Dom(n) = intersection of Dom(p) for each predecessor p, plus n itself.
     *
     * @param cfg   The control flow graph.
     * @param entry The entry node (virtual entry) of the CFG.
     * @return A map from each CFG node to its set of dominators.
     */
    public static Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> computeDominators(
            CFGGenerator.ControlFlowGraph cfg, CFGGenerator.CFGNode entry) {
        List<CFGGenerator.CFGNode> nodes = cfg.getNodes();
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators = new HashMap<>();

        // Initialize dominator sets: entry's dominators is only itself;
        // for all other nodes, start with the set of all nodes.
        Set<CFGGenerator.CFGNode> allNodes = new HashSet<>(nodes);
        for (CFGGenerator.CFGNode node : nodes) {
            if (node.equals(entry)) {
                dominators.put(node, new HashSet<>(Collections.singleton(entry)));
            } else {
                dominators.put(node, new HashSet<>(allNodes));
            }
        }

        // Build a map of predecessors for each node.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> predecessors = new HashMap<>();
        for (CFGGenerator.CFGNode node : nodes) {
            predecessors.put(node, new HashSet<>());
        }
        for (CFGGenerator.CFGEdge edge : cfg.getEdges()) {
            predecessors.get(edge.to).add(edge.from);
        }

        // Iteratively refine the dominators until a fixed point is reached.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CFGGenerator.CFGNode node : nodes) {
                if (node.equals(entry)) {
                    continue;
                }
                // Start with the universal set for this node.
                Set<CFGGenerator.CFGNode> newDoms = new HashSet<>(allNodes);
                // Intersect the dominators of all predecessor nodes.
                for (CFGGenerator.CFGNode pred : predecessors.get(node)) {
                    newDoms.retainAll(dominators.get(pred));
                }
                // A node always dominates itself.
                newDoms.add(node);
                // If the set has changed, update and mark as changed.
                if (!newDoms.equals(dominators.get(node))) {
                    dominators.put(node, newDoms);
                    changed = true;
                }
            }
        }
        return dominators;
    }

    /**
     * Computes the immediate dominators (idom) from the dominator sets.
     * For each node n (except the entry), the immediate dominator is the
     * unique dominator that does not dominate any other dominator of n.
     *
     * @param dominators The map from nodes to their dominator sets.
     * @param entry      The entry node.
     * @return A map from each node to its immediate dominator.
     */
    public static Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> computeImmediateDominators(
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators, CFGGenerator.CFGNode entry) {
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom = new HashMap<>();
        // Virtual entry has no immediate dominator.
        idom.put(entry, null);

        for (CFGGenerator.CFGNode node : dominators.keySet()) {
            if (node.equals(entry)) {
                continue;
            }
            // Candidate dominators for node n (excluding n itself)
            Set<CFGGenerator.CFGNode> candidates = new HashSet<>(dominators.get(node));
            candidates.remove(node);

            CFGGenerator.CFGNode immediate = null;
            // Find the candidate that is not dominated by any other candidate.
            for (CFGGenerator.CFGNode candidate : candidates) {
                boolean isImmediate = true;
                for (CFGGenerator.CFGNode other : candidates) {
                    if (other.equals(candidate)) {
                        continue;
                    }
                    // If some other candidate also dominates 'candidate', then candidate is not immediate.
                    if (dominators.get(other).contains(candidate)) {
                        isImmediate = false;
                        break;
                    }
                }
                if (isImmediate) {
                    immediate = candidate;
                    break;
                }
            }
            idom.put(node, immediate);
        }
        return idom;
    }

    /**
     * Generates a dominator tree represented as a CFG.
     * Each edge in the tree points from an immediate dominator to its dominated node.
     *
     * @param idom The map of immediate dominators.
     * @return A ControlFlowGraph representing the dominator tree.
     */
    public static CFGGenerator.ControlFlowGraph generateDominatorTree(
            Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom) {
        CFGGenerator.ControlFlowGraph domTree = new CFGGenerator.ControlFlowGraph();
        // Add all nodes to the dominator tree.
        for (CFGGenerator.CFGNode node : idom.keySet()) {
            domTree.addNode(node);
        }
        // Add an edge from the immediate dominator to each node.
        for (Map.Entry<CFGGenerator.CFGNode, CFGGenerator.CFGNode> entry : idom.entrySet()) {
            CFGGenerator.CFGNode node = entry.getKey();
            CFGGenerator.CFGNode immediateDom = entry.getValue();
            if (immediateDom != null) {
                domTree.addEdge(immediateDom, node);
            }
        }
        return domTree;
    }

    /**
     * Utility method to print the dominator sets for debugging.
     *
     * @param dominators A map from each CFG node to its set of dominators.
     */
    public static void printDominators(Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators) {
        for (Map.Entry<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> entry : dominators.entrySet()) {
            System.out.print(entry.getKey().getId() + " is dominated by: ");
            for (CFGGenerator.CFGNode d : entry.getValue()) {
                System.out.print(d.getId() + " ");
            }
            System.out.println();
        }
    }

    /**
     * Utility method to print immediate dominators for debugging.
     *
     * @param idom A map from each CFG node to its immediate dominator.
     */
    public static void printImmediateDominators(Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom) {
        for (Map.Entry<CFGGenerator.CFGNode, CFGGenerator.CFGNode> entry : idom.entrySet()) {
            CFGGenerator.CFGNode node = entry.getKey();
            CFGGenerator.CFGNode immediateDom = entry.getValue();
            if (immediateDom != null) {
                System.out.println("Immediate dominator of " + node.getId() + " is " + immediateDom.getId());
            } else {
                System.out.println("Immediate dominator of " + node.getId() + " is null (virtual entry)");
            }
        }
    }

    /**
     * Main method:
     * 1. Builds the CFG for the entire file.
     * 2. Creates a virtual entry node.
     * 3. Connects the virtual entry to the main method start.
     * 4. For other method start nodes, searches for a call site (based on the method name)
     *    and connects that call site to the method start node.
     * 5. Computes dominators and immediate dominators.
     * 6. Exports the dominator tree to a DOT file.
     */
    public static void main(String[] args) {
        String sourcePath = "src/main/java/examples/BuggyExample.java";
        CFGGenerator cfgGen = new CFGGenerator();
        CFGGenerator.ControlFlowGraph cfg = cfgGen.generateCFG(sourcePath);

        // Create a virtual entry node.
        CFGGenerator.CFGNode virtualEntry = new CFGGenerator.CFGNode("virtualEntry", "Virtual Entry");
        cfg.addNode(virtualEntry);

        // Connect virtual entry to method start nodes.
        for (CFGGenerator.CFGNode node : cfg.getNodes()) {
            if (node.getLabel().startsWith("Method Start:")) {
                String methodName = node.getLabel().substring("Method Start: ".length()).trim();
                if (methodName.equals("main")) {
                    cfg.addEdge(virtualEntry, node);
                } else {
                    // For non-main methods, try to identify a call site.
                    Optional<CFGGenerator.CFGNode> callSite = cfg.getNodes().stream()
                            .filter(n -> n.getLabel().contains(methodName + "("))
                            .findFirst();
                    if (callSite.isPresent()) {
                        cfg.addEdge(callSite.get(), node);
                    } else {
                        // Fallback to virtual entry if no call site is found.
                        cfg.addEdge(virtualEntry, node);
                    }
                }
            }
        }

        // For debugging: print the modified CFG.
        System.out.println("Modified CFG:");
        cfg.printGraph();

        // Use the virtual entry as the entry point.
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators = computeDominators(cfg, virtualEntry);
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom = computeImmediateDominators(dominators, virtualEntry);

        System.out.println("\nDominators:");
        printDominators(dominators);
        System.out.println("\nImmediate Dominators:");
        printImmediateDominators(idom);

        // Generate the dominator tree and export it to a DOT file.
        CFGGenerator.ControlFlowGraph domTree = generateDominatorTree(idom);
        domTree.exportToDotFile("src/main/resources/sanalysis/dags/dominator_tree_buggy_example.dot");
    }
}
