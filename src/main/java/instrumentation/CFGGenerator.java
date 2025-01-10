package instrumentation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class CFGGenerator {

    public static class ControlFlowGraph {
        private final List<String> nodes = new ArrayList<>();
        private final List<String> edges = new ArrayList<>();

        public void addNode(String node) {
            System.out.println("Adding node: " + node);
            nodes.add(node);
        }

        public void addEdge(String fromNode, String toNode) {
            System.out.println("Adding edge: " + fromNode + " -> " + toNode);
            edges.add(fromNode + " -> " + toNode);
        }

        public List<String> getNodes() {
            return nodes;
        }

        public List<String> getEdges() {
            return edges;
        }

        public void printGraph() {
            System.out.println("Nodes:");
            nodes.forEach(System.out::println);
            System.out.println("Edges:");
            edges.forEach(System.out::println);
        }
    }

    public ControlFlowGraph generateCFG(String sourceCodePath) {
        ControlFlowGraph cfg = new ControlFlowGraph();

        try {
            // Parse the source code file
            CompilationUnit compilationUnit = StaticJavaParser.parse(Paths.get(sourceCodePath));

            // Traverse methods in the source code
            compilationUnit.findAll(MethodDeclaration.class).forEach(method -> {
                cfg.addNode("Method: " + method.getName());

                if (method.getBody().isPresent()) {
                    List<Statement> statements = method.getBody().get().getStatements();

                    // Add statements as nodes
                    for (int i = 0; i < statements.size(); i++) {
                        Statement current = statements.get(i);
                        cfg.addNode(current.toString().trim());

                        // Add edges between consecutive statements
                        if (i + 1 < statements.size()) {
                            Statement next = statements.get(i + 1);
                            cfg.addEdge(current.toString().trim(), next.toString().trim());
                        }
                    }
                } else {
                    System.out.println("Skipping method without a body: " + method.getName());
                }
            });

            System.out.println("Generated Control Flow Graph:");
            cfg.printGraph();

        } catch (Exception e) {
            System.err.println("Error parsing the source code: " + e.getMessage());
        }

        return cfg;
    }
}
