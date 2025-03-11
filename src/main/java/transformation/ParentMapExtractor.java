package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * ParentMapExtractor parses the final GSA‑transformed Java source file,
 * extracts each assignment’s left‑hand side (the treatment variable) and all variable
 * references on its right‑hand side (the parents/covariates), and saves the resulting
 * mapping as a JSON file.
 *
 * This version uses hardcoded file paths and selects the earliest occurrence of each variable
 * based on source positions.
 */
public class ParentMapExtractor {

    // Hardcoded input and output file locations.
    private static final String INPUT_FILE_PATH = "src/main/resources/transformation/gsas/transformed_simple_example.java";
    private static final String OUTPUT_JSON_PATH = "src/main/resources/transformation/gsas/parentMap_simple_example.json";

    // Inner class to hold an occurrence of an assignment.
    private static class Occurrence {
        int line;
        int column;
        List<String> parents;
        Occurrence(int line, int column, List<String> parents) {
            this.line = line;
            this.column = column;
            this.parents = parents;
        }
    }

    // Map from variable name to its earliest Occurrence.
    private final Map<String, Occurrence> occurrenceMap = new LinkedHashMap<>();

    /**
     * Extracts all assignment occurrences from the GSA‑transformed file.
     */
    public void extractParentMap() {
        try {
            // Set up JavaParser with symbol solving.
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver()));
            StaticJavaParser.setConfiguration(config);

            File sourceFile = new File(INPUT_FILE_PATH);
            CompilationUnit cu = StaticJavaParser.parse(sourceFile);

            // Visit assignment expressions.
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(AssignExpr assignExpr, Void arg) {
                    super.visit(assignExpr, arg);
                    if (assignExpr.getTarget() instanceof NameExpr) {
                        String lhsVar = ((NameExpr) assignExpr.getTarget()).getNameAsString();
                        Optional<com.github.javaparser.Position> posOpt = assignExpr.getBegin();
                        if (!posOpt.isPresent()) return;
                        int line = posOpt.get().line;
                        int column = posOpt.get().column;
                        List<NameExpr> rhsRefs = assignExpr.getValue().findAll(NameExpr.class);
                        Set<String> parentVars = new LinkedHashSet<>();
                        for (NameExpr ne : rhsRefs) {
                            String refName = ne.getNameAsString();
                            if (!refName.equals(lhsVar)) {
                                parentVars.add(refName);
                            }
                        }
                        List<String> parentsList = new ArrayList<>(parentVars);
                        // Record the occurrence if it's the first or earlier than the current one.
                        if (!occurrenceMap.containsKey(lhsVar)) {
                            occurrenceMap.put(lhsVar, new Occurrence(line, column, parentsList));
                        } else {
                            Occurrence existing = occurrenceMap.get(lhsVar);
                            if (line < existing.line || (line == existing.line && column < existing.column)) {
                                occurrenceMap.put(lhsVar, new Occurrence(line, column, parentsList));
                            }
                        }
                    }
                }
            }, null);

            // Visit variable declarations with initializers.
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(VariableDeclarationExpr vde, Void arg) {
                    super.visit(vde, arg);
                    vde.getVariables().forEach(varDecl -> {
                        if (varDecl.getInitializer().isPresent()) {
                            String varName = varDecl.getNameAsString();
                            Optional<com.github.javaparser.Position> posOpt = varDecl.getBegin();
                            if (!posOpt.isPresent()) return;
                            int line = posOpt.get().line;
                            int column = posOpt.get().column;
                            List<NameExpr> refs = varDecl.getInitializer().get().findAll(NameExpr.class);
                            Set<String> parentVars = new LinkedHashSet<>();
                            for (NameExpr ne : refs) {
                                String refName = ne.getNameAsString();
                                if (!refName.equals(varName)) {
                                    parentVars.add(refName);
                                }
                            }
                            List<String> parentsList = new ArrayList<>(parentVars);
                            if (!occurrenceMap.containsKey(varName)) {
                                occurrenceMap.put(varName, new Occurrence(line, column, parentsList));
                            } else {
                                Occurrence existing = occurrenceMap.get(varName);
                                if (line < existing.line || (line == existing.line && column < existing.column)) {
                                    occurrenceMap.put(varName, new Occurrence(line, column, parentsList));
                                }
                            }
                        }
                    });
                }
            }, null);

        } catch (Exception e) {
            System.err.println("Error parsing file " + INPUT_FILE_PATH + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Builds the final parent map from the recorded earliest occurrences.
     *
     * @return A map from variable name to its list of parent variable names.
     */
    public Map<String, List<String>> buildParentMap() {
        Map<String, List<String>> finalMap = new LinkedHashMap<>();
        for (Map.Entry<String, Occurrence> entry : occurrenceMap.entrySet()) {
            finalMap.put(entry.getKey(), entry.getValue().parents);
        }
        return finalMap;
    }

    /**
     * Saves the given parent map to a JSON file.
     */
    public void saveParentMapToJson(Map<String, List<String>> map) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(map);
        try (FileWriter writer = new FileWriter(new File(OUTPUT_JSON_PATH))) {
            writer.write(json);
        } catch (IOException e) {
            System.err.println("Error writing JSON to " + OUTPUT_JSON_PATH + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        ParentMapExtractor extractor = new ParentMapExtractor();
        extractor.extractParentMap();
        Map<String, List<String>> finalMap = extractor.buildParentMap();
        extractor.saveParentMapToJson(finalMap);
        System.out.println("Parent map saved to: " + OUTPUT_JSON_PATH);
    }
}
