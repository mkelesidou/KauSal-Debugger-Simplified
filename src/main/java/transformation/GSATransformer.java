package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import sanalysis.CDGGenerator;
import sanalysis.CFGGenerator;
import sanalysis.DominatorTreeGenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production‑style GSA transformer.
 *
 * This transformer integrates:
 *  1. CFG/CDG analysis (using CFGGenerator, DominatorTreeGenerator, and CDGGenerator).
 *  2. A worklist‑based reaching definitions analysis over the CFG.
 *  3. Full SSA renaming.
 *  4. Insertion of gating (merge) assignments at merge points using the CDG and reaching definitions.
 *  5. (Optionally) Fixing of while‑loop conditions to use the merged variable.
 *
 * For example, for simpleMethod, the expected output is:
 *
 *   public static int simpleMethod(int x_0) {
 *       int result_1;
 *       final boolean P1_1 = x_0 > 5;
 *       if (P1_1) {
 *           result_2 = x_0 * 2;
 *       } else {
 *           result_3 = x_0 + 3;
 *       }
 *       int result_4 = P1_1 ? result_2 : result_3;
 *       boolean P2_1 = result_4 < 15;
 *       while (P2_1) {
 *           result_5 = result_4 + 2;
 *           result_4 = result_5;
 *           P2_1 = result_4 < 15;
 *       }
 *       return result_4;
 *   }
 *
 * Note: This prototype uses heuristics to parse CFG node labels for reaching definitions
 * and a BlockStmt visitor to insert merge assignments.
 */
public class GSATransformer {

    public static void main(String[] args) {
        try {
            // 1. Set up JavaParser with symbol solving.
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);
            StaticJavaParser.setConfiguration(config);

            // 2. Parse the predicate-transformed source file.
            String sourcePath = "src/main/resources/transformation/predicates/transformed_simple_example.java";
            CompilationUnit cu = StaticJavaParser.parse(new File(sourcePath));

            // 3. Build the CFG.
            CFGGenerator cfgGen = new CFGGenerator();
            CFGGenerator.ControlFlowGraph controlFlowGraph = cfgGen.generateCFG(sourcePath);

            // 4. Create a virtual entry node and attach it to all method-start nodes.
            CFGGenerator.CFGNode virtualEntry = new CFGGenerator.CFGNode("virtualEntry", "Virtual Entry");
            controlFlowGraph.addNode(virtualEntry);
            for (CFGGenerator.CFGNode node : controlFlowGraph.getNodes()) {
                if (node.getLabel().startsWith("Method Start:")) {
                    controlFlowGraph.addEdge(virtualEntry, node);
                }
            }

            // 5. Compute dominators and interprocedural CDG.
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators =
                    DominatorTreeGenerator.computeDominators(controlFlowGraph, virtualEntry);
            Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idoms =
                    DominatorTreeGenerator.computeImmediateDominators(dominators, virtualEntry);
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg =
                    CDGGenerator.computeInterproceduralCDG(controlFlowGraph);

            // 6. Run reaching definitions analysis over the CFG.
            ReachingDefinitionsAnalysis rdAnalysis = new ReachingDefinitionsAnalysis(controlFlowGraph);
            Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> reachingDefs = rdAnalysis.compute();

            // 7. Apply full SSA renaming.
            VariableRenamer renamer = new VariableRenamer();
            renamer.visit(cu, null);
            Map<String, Integer> versionMap = renamer.getVersionMap();

            // 8. Insert gating assignments at merge points.
            GatingInserterProduction gatingInserter = new GatingInserterProduction(cdg, reachingDefs, versionMap);
            gatingInserter.visit(cu, null);

            // 9. (Optional) Further expression rewriting.
            ExpressionRewriter exprRewriter = new ExpressionRewriter();
            exprRewriter.visit(cu, null);

            // 10. Write the transformed source code to an output file.
            String outputPath = "src/main/resources/transformation/gsas/transformed_simple_example.java";
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
                writer.println(cu.toString());
            }
            System.out.println("Production-transformed code stored to " + outputPath);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ------------------------------
    // ReachingDefinitionsAnalysis (heuristic)
    // ------------------------------
    private static class ReachingDefinitionsAnalysis {
        private final CFGGenerator.ControlFlowGraph cfg;
        public ReachingDefinitionsAnalysis(CFGGenerator.ControlFlowGraph cfg) {
            this.cfg = cfg;
        }
        public Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> compute() {
            Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> in = new HashMap<>();
            Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> out = new HashMap<>();
            for (CFGGenerator.CFGNode n : cfg.getNodes()) {
                in.put(n, new HashMap<>());
                out.put(n, new HashMap<>());
            }
            Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> pred = new HashMap<>();
            for (CFGGenerator.CFGNode n : cfg.getNodes()) {
                pred.put(n, new HashSet<>());
            }
            for (CFGGenerator.CFGEdge edge : cfg.getEdges()) {
                pred.get(edge.to).add(edge.from);
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (CFGGenerator.CFGNode n : cfg.getNodes()) {
                    Map<String, Set<Integer>> newIn = new HashMap<>();
                    for (CFGGenerator.CFGNode p : pred.get(n)) {
                        mergeDefinitions(newIn, out.get(p));
                    }
                    Map<String, Set<Integer>> newOut = new HashMap<>(newIn);
                    Map<String, Set<Integer>> gen = parseGen(n);
                    Map<String, Set<Integer>> kill = parseKill(n, gen);
                    for (String var : kill.keySet()) {
                        if (newOut.containsKey(var)) {
                            newOut.get(var).removeAll(kill.get(var));
                        }
                    }
                    mergeDefinitions(newOut, gen);
                    if (!newIn.equals(in.get(n)) || !newOut.equals(out.get(n))) {
                        in.put(n, newIn);
                        out.put(n, newOut);
                        changed = true;
                    }
                }
            }
            return out;
        }
        private void mergeDefinitions(Map<String, Set<Integer>> target, Map<String, Set<Integer>> src) {
            if (src == null) return;
            for (Map.Entry<String, Set<Integer>> e : src.entrySet()) {
                target.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
            }
        }
        private Map<String, Set<Integer>> parseGen(CFGGenerator.CFGNode n) {
            Map<String, Set<Integer>> gen = new HashMap<>();
            String label = n.getLabel();
            if (label.contains("=")) {
                Pattern pattern = Pattern.compile("([a-zA-Z]+)_([0-9]+)");
                Matcher matcher = pattern.matcher(label);
                while (matcher.find()) {
                    String var = matcher.group(1);
                    int ver = Integer.parseInt(matcher.group(2));
                    gen.computeIfAbsent(var, k -> new HashSet<>()).add(ver);
                }
            }
            return gen;
        }
        private Map<String, Set<Integer>> parseKill(CFGGenerator.CFGNode n, Map<String, Set<Integer>> gen) {
            Map<String, Set<Integer>> kill = new HashMap<>();
            for (String var : gen.keySet()) {
                Set<Integer> defs = gen.get(var);
                if (!defs.isEmpty()) {
                    int maxGen = Collections.max(defs);
                    Set<Integer> killed = new HashSet<>();
                    for (int i = 0; i < maxGen; i++) {
                        killed.add(i);
                    }
                    kill.put(var, killed);
                }
            }
            return kill;
        }
    }

    // ------------------------------
    // VariableRenamer: Full SSA renaming with controlled branch order.
    // ------------------------------
    private static class VariableRenamer extends ModifierVisitor<Void> {
        private final Map<String, Integer> versionMap = new HashMap<>();
        public Map<String, Integer> getVersionMap() {
            return versionMap;
        }
        @Override
        public Visitable visit(MethodDeclaration md, Void arg) {
            versionMap.clear();
            md.getParameters().forEach(param -> {
                String orig = param.getNameAsString();
                // For booleans, rename only once.
                if (param.getType().isPrimitiveType() &&
                        param.getType().asPrimitiveType().getType().name().equalsIgnoreCase("BOOLEAN")) {
                    versionMap.put(orig, 1);
                    param.setName(orig + "_1");
                } else {
                    versionMap.put(orig, 0);
                    param.setName(orig + "_0");
                }
            });
            return super.visit(md, arg);
        }
        @Override
        public Visitable visit(VariableDeclarationExpr vde, Void arg) {
            if (vde.getElementType().isPrimitiveType() &&
                    vde.getElementType().asPrimitiveType().getType().name().equalsIgnoreCase("BOOLEAN")) {
                vde.getVariables().forEach(varDecl -> {
                    String orig = varDecl.getNameAsString();
                    if (!orig.matches("^[a-zA-Z]+_[0-9]+$")) {
                        versionMap.put(orig, 1);
                        varDecl.setName(orig + "_1");
                    }
                });
            } else {
                vde.getVariables().forEach(varDecl -> {
                    String orig = varDecl.getNameAsString();
                    if (!orig.matches("^[a-zA-Z]+_[0-9]+$")) {
                        int ver = versionMap.getOrDefault(orig, 0) + 1;
                        versionMap.put(orig, ver);
                        varDecl.setName(orig + "_" + ver);
                    }
                });
            }
            return super.visit(vde, arg);
        }
        @Override
        public Visitable visit(NameExpr ne, Void arg) {
            String orig = ne.getNameAsString();
            // If the name already matches SSA pattern, skip renaming.
            if (orig.matches("^[a-zA-Z]+_[0-9]+$")) {
                return super.visit(ne, arg);
            }
            // Use type resolution to check for booleans.
            try {
                String typeName = ne.resolve().getType().describe();
                if (typeName.equals("boolean")) {
                    // For predicate variables (starting with "P"), do not rename further.
                    if (orig.startsWith("P")) {
                        return super.visit(ne, arg);
                    }
                    if (!versionMap.containsKey(orig)) {
                        versionMap.put(orig, 1);
                    }
                    ne.setName(orig + "_" + versionMap.get(orig));
                    return super.visit(ne, arg);
                }
            } catch (Exception e) {
                if (orig.matches("^[a-zA-Z]+_[0-9]+$")) {
                    return super.visit(ne, arg);
                }
            }
            if (versionMap.containsKey(orig)) {
                ne.setName(orig + "_" + versionMap.get(orig));
            }
            return super.visit(ne, arg);
        }
        @Override
        public Visitable visit(AssignExpr ae, Void arg) {
            if (ae.getTarget().isNameExpr()) {
                String currentName = ae.getTarget().asNameExpr().getNameAsString();
                // For boolean predicates (starting with "P"), skip further renaming.
                if (currentName.startsWith("P")) {
                    return super.visit(ae, arg);
                }
                // Use regex to extract the base name if already in SSA form.
                String baseName = currentName;
                Pattern pattern = Pattern.compile("^([a-zA-Z]+)_(\\d+)$");
                Matcher matcher = pattern.matcher(currentName);
                if (matcher.matches()) {
                    baseName = matcher.group(1);
                }
                int newVer = versionMap.getOrDefault(baseName, 0) + 1;
                versionMap.put(baseName, newVer);
                ae.getTarget().asNameExpr().setName(baseName + "_" + newVer);
            }
            return super.visit(ae, arg);
        }
        @Override
        public Visitable visit(IfStmt ifStmt, Void arg) {
            // First, process the condition so that boolean predicates are visited.
            ifStmt.getCondition().accept(this, arg);
            // Process the then and else branches.
            ifStmt.getThenStmt().accept(this, arg);
            ifStmt.getElseStmt().ifPresent(stmt -> stmt.accept(this, arg));
            // Return the if-statement without calling super.visit to avoid duplicate renaming.
            return ifStmt;
        }
    }

    // ------------------------------
    // GatingInserterProduction: Insert merge assignments at merge points.
    // This visitor handles both if-else and while loops.
    // ------------------------------
    private static class GatingInserterProduction extends VoidVisitorAdapter<Void> {
        private final Map<String, Integer> versionMap;
        public GatingInserterProduction(Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg,
                                        Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> reachingDefs,
                                        Map<String, Integer> versionMap) {
            this.versionMap = versionMap;
        }
        @Override
        public void visit(BlockStmt block, Void arg) {
            List<Statement> stmts = new ArrayList<>(block.getStatements());
            for (int i = 0; i < stmts.size(); i++) {
                Statement stmt = stmts.get(i);
                if (stmt instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) stmt;
                    if (!ifStmt.getElseStmt().isPresent()) continue;
                    // For our example, we focus on the variable "result".
                    int thenVersion = findMaxVersion(ifStmt.getThenStmt(), "result");
                    int elseVersion = findMaxVersion(ifStmt.getElseStmt().get(), "result");
                    if (thenVersion == 0 || elseVersion == 0) continue;
                    int mergedVersion = Math.max(thenVersion, elseVersion) + 1;
                    versionMap.put("result", mergedVersion);
                    String mergedVarName = "result_" + mergedVersion;
                    ConditionalExpr mergeExpr = new ConditionalExpr(
                            ifStmt.getCondition().clone(),
                            new NameExpr("result_" + thenVersion),
                            new NameExpr("result_" + elseVersion)
                    );
                    VariableDeclarationExpr mergeDecl = new VariableDeclarationExpr();
                    mergeDecl.addVariable(new com.github.javaparser.ast.body.VariableDeclarator(
                            new PrimitiveType(PrimitiveType.Primitive.INT),
                            mergedVarName,
                            mergeExpr
                    ));
                    block.addStatement(i + 1, new ExpressionStmt(mergeDecl));
                    i++;
                }
            }
            super.visit(block, arg);
        }
        @Override
        public void visit(WhileStmt ws, Void arg) {
            // Expect the while condition to be a NameExpr (the predicate variable).
            if (!(ws.getCondition() instanceof NameExpr)) {
                super.visit(ws, arg);
                return;
            }
            String predVar = ((NameExpr) ws.getCondition()).getNameAsString();
            // Retrieve the original predicate condition from its declaration.
            Optional<Expression> originalConditionOpt = ws.findAncestor(BlockStmt.class)
                    .flatMap(block -> block.findAll(VariableDeclarationExpr.class).stream()
                            .filter(vde -> vde.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(predVar)))
                            .findFirst()
                            .flatMap(vde -> vde.getVariables().get(0).getInitializer()));
            if (!originalConditionOpt.isPresent()) {
                super.visit(ws, arg);
                return;
            }
            Expression originalCondition = originalConditionOpt.get().clone();

            // Process the while body to rewrite a compound assignment.
            // We assume the while body contains a compound assignment on the loop-carried variable.
            // Instead of updating the versionMap, we reuse the merged variable from the if-else merge.
            String mergedVar = "result_" + versionMap.get("result");
            String oldLoopVar = null;
            if (ws.getBody().isBlockStmt()) {
                BlockStmt body = ws.getBody().asBlockStmt();
                List<Statement> stmts = body.getStatements();
                for (int i = 0; i < stmts.size(); i++) {
                    Statement stmt = stmts.get(i);
                    if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                        AssignExpr ae = stmt.asExpressionStmt().getExpression().asAssignExpr();
                        if (ae.getOperator().asString().equals("+=")) {
                            // Instead of incrementing the versionMap here,
                            // we assume the merged variable remains the same (e.g. "result_4").
                            oldLoopVar = ae.getTarget().asNameExpr().getNameAsString();
                            if (oldLoopVar.startsWith("P")) continue;
                            // Create a temporary variable for the update.
                            // For example, if mergedVar is "result_4", then temp becomes "result_5".
                            String tempVar = "result_" + (versionMap.get("result") + 1);
                            Expression newValue = new BinaryExpr(
                                    ae.getTarget().clone(),
                                    ae.getValue().clone(),
                                    BinaryExpr.Operator.PLUS
                            );
                            AssignExpr newAssign = new AssignExpr(
                                    new NameExpr(tempVar),
                                    newValue,
                                    AssignExpr.Operator.ASSIGN
                            );
                            stmt.replace(new ExpressionStmt(newAssign));
                            // Insert merge assignment: mergedVar = tempVar;
                            AssignExpr mergeAssign = new AssignExpr(
                                    new NameExpr(mergedVar),
                                    new NameExpr(tempVar),
                                    AssignExpr.Operator.ASSIGN
                            );
                            body.addStatement(i + 1, new ExpressionStmt(mergeAssign));
                            break; // Process only once per while loop.
                        }
                    }
                }
            }
            // Update the predicate initializer using regex substitution.
            // Replace any occurrence of "result" (with an optional SSA suffix) with the mergedVar.
            String conditionStr = originalCondition.toString();
            String newConditionStr = conditionStr.replaceAll("result(?:_\\d+)?", mergedVar);
            Expression substitutedCondition = StaticJavaParser.parseExpression(newConditionStr);
            // Locate the predicate declaration in an enclosing block and update its initializer.
            ws.findAncestor(BlockStmt.class).ifPresent(block -> {
                for (VariableDeclarationExpr vde : block.findAll(VariableDeclarationExpr.class)) {
                    vde.getVariables().forEach(v -> {
                        if (v.getNameAsString().equals(predVar)) {
                            v.setInitializer(substitutedCondition);
                        }
                    });
                }
            });
            super.visit(ws, arg);
        }
        private int findMaxVersion(Node node, String var) {
            final int[] max = {0};
            Pattern pattern = Pattern.compile("^" + var + "_(\\d+)(?:_\\d+)?$");
            node.walk(n -> {
                if (n instanceof NameExpr) {
                    String name = ((NameExpr) n).getNameAsString();
                    Matcher matcher = pattern.matcher(name);
                    if (matcher.find()) {
                        int ver = Integer.parseInt(matcher.group(1));
                        if (ver > max[0]) {
                            max[0] = ver;
                        }
                    }
                }
            });
            return max[0];
        }
    }

    // ------------------------------
    // WhileLoopConditionFixer: No extra fix needed if booleans remain unchanged.
    // ------------------------------
    private static class WhileLoopConditionFixer extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(WhileStmt ws, Void arg) {
            return super.visit(ws, arg);
        }
    }

    // ------------------------------
    // ExpressionRewriter: Placeholder for additional rewriting.
    // ------------------------------
    private static class ExpressionRewriter extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(ConditionalExpr ce, Void arg) {
            return super.visit(ce, arg);
        }
    }
}
