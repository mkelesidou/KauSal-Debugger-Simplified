package sanalysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.stmt.SwitchEntry;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.*;

public class CFGGenerator {

    public static class CFGNode {
        private static int counter = 0;
        private final String id;
        private String label;

        // Constructor: auto-generates the id.
        public CFGNode(String label) {
            this.id = "node" + (counter++);
            this.label = label == null ? "" : label.replace("\n", " ").trim();
        }

        // Constructor with explicit id.
        public CFGNode(String id, String label) {
            this.id = id;
            this.label = label == null ? "" : label.replace("\n", " ").trim();
            try {
                int num = Integer.parseInt(id.replace("node", ""));
                if (num >= counter) {
                    counter = num + 1;
                }
            } catch (NumberFormatException e) {
                // ignore format issues
            }
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
        public void setLabel(String newLabel) {
            this.label = newLabel == null ? "" : newLabel.replace("\n", " ").trim();
        }
        @Override
        public String toString() { return id; }
    }

    // CFG Edge Class (to avoid duplicate edges)
    public static class CFGEdge {
        public final CFGNode from;
        public final CFGNode to;
        public CFGEdge(CFGNode from, CFGNode to) {
            this.from = from;
            this.to = to;
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CFGEdge)) return false;
            CFGEdge other = (CFGEdge) obj;
            return from.getId().equals(other.from.getId()) && to.getId().equals(other.to.getId());
        }
        @Override
        public int hashCode() {
            return Objects.hash(from.getId(), to.getId());
        }
    }

    public static class ControlFlowGraph {
        private final List<CFGNode> nodes = new ArrayList<>();
        private final Set<CFGEdge> edges = new LinkedHashSet<>(); // use a set to avoid duplicates

        public void addNode(CFGNode node) {
            nodes.add(node);
        }

        public void addEdge(CFGNode from, CFGNode to) {
            edges.add(new CFGEdge(from, to));
        }

        public List<CFGNode> getNodes() {
            return nodes;
        }

        public Set<CFGEdge> getEdges() {
            return edges;
        }

        public void printGraph() {
            System.out.println("Nodes:");
            for (CFGNode node : nodes) {
                System.out.println("  " + node.getId() + ": " + node.getLabel());
            }
            System.out.println("Edges:");
            for (CFGEdge edge : edges) {
                System.out.println("  " + edge.from.getId() + " -> " + edge.to.getId());
            }
        }

        public void exportToDotFile(String filePath) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
                writer.println("digraph CFG {");
                for (CFGNode node : nodes) {
                    String safeLabel = node.getLabel()
                            .replace("\"", "\\\"")
                            .replace("(", "\\(")
                            .replace(")", "\\)");
                    writer.printf("  \"%s\" [label=\"%s\"];%n", node.getId(), safeLabel);
                }
                for (CFGEdge edge : edges) {
                    writer.printf("  \"%s\" -> \"%s\";%n", edge.from.getId(), edge.to.getId());
                }
                writer.println("}");
                System.out.println("CFG exported to: " + filePath);
            } catch (IOException e) {
                System.err.println("Failed to export CFG to DOT file: " + e.getMessage());
            }
        }
    }

    // Helper class for entry/exit of a CFG fragment
    public static class EntryExit {
        public CFGNode entry;
        public CFGNode exit;
        public EntryExit(CFGNode entry, CFGNode exit) {
            this.entry = entry;
            this.exit = exit;
        }
    }

    // For handling loop constructs (for break/continue)
    private static class LoopContext {
        public CFGNode condNode;
        public CFGNode exitNode;
        public LoopContext(CFGNode condNode, CFGNode exitNode) {
            this.condNode = condNode;
            this.exitNode = exitNode;
        }
    }
    private Deque<LoopContext> loopStack = new ArrayDeque<>();
    private CFGNode currentMethodEnd = null;

    // CFG Construction (using AST)
    public ControlFlowGraph generateCFG(String sourcePath) {
        ControlFlowGraph cfg = new ControlFlowGraph();
        try {
            CompilationUnit cu = StaticJavaParser.parse(Paths.get(sourcePath));
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                CFGNode startNode = new CFGNode("Method Start: " + method.getName());
                CFGNode endNode = new CFGNode("Method End: " + method.getName());
                cfg.addNode(startNode);
                cfg.addNode(endNode);
                if (method.getBody().isPresent()) {
                    currentMethodEnd = endNode;
                    EntryExit ee = processBlock(method.getBody().get(), cfg);
                    cfg.addEdge(startNode, ee.entry);
                    cfg.addEdge(ee.exit, endNode);
                } else {
                    cfg.addEdge(startNode, endNode);
                }
            });
            System.out.println("Generated CFG:");
            cfg.printGraph();
        } catch (IOException e) {
            System.err.println("Error parsing source code: " + e.getMessage());
        }
        return cfg;
    }

    public EntryExit processBlock(BlockStmt block, ControlFlowGraph cfg) {
        if (block == null || block.getStatements().isEmpty()) {
            CFGNode dummy = new CFGNode("empty block");
            cfg.addNode(dummy);
            return new EntryExit(dummy, dummy);
        }
        EntryExit result = null;
        for (Statement stmt : block.getStatements()) {
            EntryExit current = processStatement(stmt, cfg);
            if (current == null) continue;
            if (result == null) {
                result = current;
            } else {
                cfg.addEdge(result.exit, current.entry);
                result.exit = current.exit;
            }
        }
        if (result == null) {
            CFGNode dummy = new CFGNode("empty block");
            cfg.addNode(dummy);
            result = new EntryExit(dummy, dummy);
        }
        return result;
    }

    private EntryExit processStatement(Statement stmt, ControlFlowGraph cfg) {
        if (stmt.isBlockStmt()) {
            return processBlock(stmt.asBlockStmt(), cfg);
        } else if (stmt.isIfStmt()) {
            return processIfStmt(stmt.asIfStmt(), cfg);
        } else if (stmt.isWhileStmt()) {
            return processWhileStmt(stmt.asWhileStmt(), cfg);
        } else if (stmt.isForStmt()) {
            return processForStmt(stmt.asForStmt(), cfg);
        } else if (stmt.isForEachStmt()) {
            return processForEachStmt(stmt.asForEachStmt(), cfg);
        } else if (stmt.isDoStmt()) {
            return processDoStmt(stmt.asDoStmt(), cfg);
        } else if (stmt.isSwitchStmt()) {
            return processSwitchStmt(stmt.asSwitchStmt(), cfg);
        } else if (stmt.isReturnStmt()) {
            return processReturnStmt(stmt.asReturnStmt(), cfg);
        } else if (stmt.isBreakStmt()) {
            return processBreakStmt(stmt.asBreakStmt(), cfg);
        } else if (stmt.isContinueStmt()) {
            return processContinueStmt(stmt.asContinueStmt(), cfg);
        } else {
            CFGNode node = new CFGNode(stmt.toString());
            cfg.addNode(node);
            return new EntryExit(node, node);
        }
    }

    private EntryExit processIfStmt(IfStmt ifStmt, ControlFlowGraph cfg) {
        // 1. Create the condition node
        CFGNode condNode = new CFGNode("if " + ifStmt.getCondition().toString());
        cfg.addNode(condNode);

        // 2. Process the 'then' statement
        EntryExit thenEE = processStatement(ifStmt.getThenStmt(), cfg);
        cfg.addEdge(condNode, thenEE.entry);

        // 3. Process the 'else' statement (if present)
        EntryExit elseEE = null;
        if (ifStmt.getElseStmt().isPresent()) {
            elseEE = processStatement(ifStmt.getElseStmt().get(), cfg);
            cfg.addEdge(condNode, elseEE.entry);
        }

        // 4. Check if all branches are terminal
        boolean thenTerminal = isTerminalNode(thenEE.exit, cfg);
        boolean elseTerminal = elseEE != null && isTerminalNode(elseEE.exit, cfg);
        boolean elseExists = elseEE != null;
        
        // If both branches exist and both are terminal, or the then branch is terminal and there is no else
        if ((elseExists && thenTerminal && elseTerminal) || 
            (thenTerminal && !elseExists)) {
            // No merge node needed - both/all paths end in terminal statements
            return new EntryExit(condNode, thenEE.exit); // Return any exit, it doesn't matter
        }
        
        // 5. Otherwise, create a merge node for non-terminal paths
        CFGNode mergeNode = new CFGNode("if-merge");
        cfg.addNode(mergeNode);
        
        // Only connect non-terminal nodes to the merge
        if (!thenTerminal) {
            cfg.addEdge(thenEE.exit, mergeNode);
        }
        
        if (elseEE != null && !elseTerminal) {
            cfg.addEdge(elseEE.exit, mergeNode);  
        } else if (elseEE == null) {
            // Connect condition false path to merge when no else branch
            cfg.addEdge(condNode, mergeNode);
        }
        
        return new EntryExit(condNode, mergeNode);
    }


    private EntryExit processWhileStmt(WhileStmt whileStmt, ControlFlowGraph cfg) {
        CFGNode condNode = new CFGNode("while " + whileStmt.getCondition().toString());
        cfg.addNode(condNode);
        CFGNode exitNode = new CFGNode("while-exit");
        cfg.addNode(exitNode);
        loopStack.push(new LoopContext(condNode, exitNode));
        EntryExit bodyEE = processStatement(whileStmt.getBody(), cfg);
        loopStack.pop();
        cfg.addEdge(condNode, bodyEE.entry);
        cfg.addEdge(bodyEE.exit, condNode);
        cfg.addEdge(condNode, exitNode);
        return new EntryExit(condNode, exitNode);
    }

    private EntryExit processForStmt(ForStmt forStmt, ControlFlowGraph cfg) {
        // Process initialisers (if any)
        EntryExit initEE = null;
        if (!forStmt.getInitialization().isEmpty()) {
            String initStr = forStmt.getInitialization().toString();
            CFGNode initNode = new CFGNode("for-init: " + initStr);
            cfg.addNode(initNode);
            initEE = new EntryExit(initNode, initNode);
        }

        // Process the condition (or default to true if missing)
        String cond = forStmt.getCompare().isPresent() ? forStmt.getCompare().get().toString() : "true";
        CFGNode condNode = new CFGNode("for-cond: " + cond);
        cfg.addNode(condNode);

        // Process update expressions (if any)
        EntryExit updateEE = null;
        if (!forStmt.getUpdate().isEmpty()) {
            String updateStr = forStmt.getUpdate().toString();
            CFGNode updateNode = new CFGNode("for-update: " + updateStr);
            cfg.addNode(updateNode);
            updateEE = new EntryExit(updateNode, updateNode);
        }

        // Create an exit node for the loop
        CFGNode exitNode = new CFGNode("for-exit");
        cfg.addNode(exitNode);

        // Connect initialisation to condition if initialisation exists
        if (initEE != null) {
            cfg.addEdge(initEE.exit, condNode);
        }

        // Push loop context for break/continue handling
        loopStack.push(new LoopContext(condNode, exitNode));
        EntryExit bodyEE = processStatement(forStmt.getBody(), cfg);
        loopStack.pop();

        // From the condition, on true, go to the loop body
        cfg.addEdge(condNode, bodyEE.entry);

        // From the body exit, go to update (if any) or loop back to the condition
        if (updateEE != null) {
            cfg.addEdge(bodyEE.exit, updateEE.entry);
            cfg.addEdge(updateEE.exit, condNode);
        } else {
            cfg.addEdge(bodyEE.exit, condNode);
        }

        // From the condition, on false, exit the loop
        cfg.addEdge(condNode, exitNode);

        // Return the overall entry and exit for the loop
        if (initEE != null) {
            return new EntryExit(initEE.entry, exitNode);
        } else {
            return new EntryExit(condNode, exitNode);
        }
    }

    private EntryExit processForEachStmt(ForEachStmt forEachStmt, ControlFlowGraph cfg) {
        CFGNode condNode = new CFGNode("for-each (" + forEachStmt.getVariable().toString() + ")");
        cfg.addNode(condNode);
        CFGNode exitNode = new CFGNode("for-each-exit");
        cfg.addNode(exitNode);
        loopStack.push(new LoopContext(condNode, exitNode));
        EntryExit bodyEE = processStatement(forEachStmt.getBody(), cfg);
        loopStack.pop();
        cfg.addEdge(condNode, bodyEE.entry);
        cfg.addEdge(bodyEE.exit, condNode);
        cfg.addEdge(condNode, exitNode);
        return new EntryExit(condNode, exitNode);
    }

    private EntryExit processDoStmt(DoStmt doStmt, ControlFlowGraph cfg) {
        CFGNode condNode = new CFGNode("do-while " + doStmt.getCondition().toString());
        cfg.addNode(condNode);
        CFGNode exitNode = new CFGNode("do-while-exit");
        cfg.addNode(exitNode);
        EntryExit bodyEE = processStatement(doStmt.getBody(), cfg);
        cfg.addEdge(bodyEE.exit, condNode);
        cfg.addEdge(condNode, bodyEE.entry);
        cfg.addEdge(condNode, exitNode);
        return new EntryExit(bodyEE.entry, exitNode);
    }

    private EntryExit processSwitchStmt(SwitchStmt switchStmt, ControlFlowGraph cfg) {
        CFGNode switchNode = new CFGNode("switch(" + switchStmt.getSelector().toString() + ")");
        cfg.addNode(switchNode);
        CFGNode mergeNode = new CFGNode("switch-merge");
        cfg.addNode(mergeNode);
        for (SwitchEntry entry : switchStmt.getEntries()) {
            // Format case labels as "case X" without brackets for tests to pass
            String label;
            if (entry.getLabels().isEmpty()) {
                label = "default:";
            } else {
                // Extract just the number without brackets
                String labelText = entry.getLabels().toString();
                labelText = labelText.replace("[", "").replace("]", "");
                label = "case " + labelText;
            }
            CFGNode caseNode = new CFGNode(label);
            cfg.addNode(caseNode);
            cfg.addEdge(switchNode, caseNode);
            EntryExit entryEE = null;
            if (entry.getStatements().isEmpty()) {
                entryEE = new EntryExit(caseNode, caseNode);
            } else {
                for (Statement s : entry.getStatements()) {
                    EntryExit stmtEE = processStatement(s, cfg);
                    if (stmtEE == null) continue;
                    if (entryEE == null) {
                        entryEE = stmtEE;
                        cfg.addEdge(caseNode, stmtEE.entry);
                    } else {
                        cfg.addEdge(entryEE.exit, stmtEE.entry);
                        entryEE.exit = stmtEE.exit;
                    }
                }
            }
            if (entryEE != null) {
                cfg.addEdge(entryEE.exit, mergeNode);
            } else {
                cfg.addEdge(caseNode, mergeNode);
            }
        }
        return new EntryExit(switchNode, mergeNode);
    }

    private EntryExit processReturnStmt(ReturnStmt retStmt, ControlFlowGraph cfg) {
        CFGNode retNode = new CFGNode(retStmt.toString());
        cfg.addNode(retNode);
        if (currentMethodEnd != null) {
            cfg.addEdge(retNode, currentMethodEnd);
        }
        return new EntryExit(retNode, retNode);
    }

    private EntryExit processBreakStmt(BreakStmt bstmt, ControlFlowGraph cfg) {
        CFGNode brNode = new CFGNode(bstmt.toString());
        cfg.addNode(brNode);
        if (!loopStack.isEmpty()) {
            CFGNode exit = loopStack.peek().exitNode;
            cfg.addEdge(brNode, exit);
        }
        return new EntryExit(brNode, brNode);
    }

    private EntryExit processContinueStmt(ContinueStmt cstmt, ControlFlowGraph cfg) {
        CFGNode contNode = new CFGNode(cstmt.toString());
        cfg.addNode(contNode);
        if (!loopStack.isEmpty()) {
            CFGNode cond = loopStack.peek().condNode;
            cfg.addEdge(contNode, cond);
        }
        return new EntryExit(contNode, contNode);
    }

    private boolean isTerminalNode(CFGNode node, ControlFlowGraph cfg) {
        // Check the label for return/throw
        String label = node.getLabel().trim().toLowerCase();
        if (label.startsWith("return") || label.startsWith("throw")) {
            return true;
        }

        // Optionally, also check outgoing edges:
        long outEdges = cfg.getEdges().stream()
                .filter(e -> e.from.equals(node))
                .count();
        return (outEdges == 0);
    }


    public static void main(String[] args) {
        String sourcePath;
        String outputPath;
        
        if (args.length >= 2) {
            sourcePath = args[0];
            outputPath = args[1];
        } else if (args.length == 1) {
            sourcePath = args[0];
            outputPath = sourcePath + ".cfg";
            System.out.println("No output path specified, using default: " + outputPath);
        } else {
            System.out.println("Usage: java CFGGenerator <source_file> [<output_file>]");
            System.out.println("Using default paths for testing purposes only.");
            sourcePath = "src/main/java/examples/BuggyExample.java";
            outputPath = "src/main/resources/sanalysis/cfgs/cfg_buggy_example.dot";
        }
        
        CFGGenerator generator = new CFGGenerator();
        ControlFlowGraph cfg = generator.generateCFG(sourcePath);
        System.out.println("Generated CFG:");
        cfg.printGraph();
        cfg.exportToDotFile(outputPath);
    }
}
