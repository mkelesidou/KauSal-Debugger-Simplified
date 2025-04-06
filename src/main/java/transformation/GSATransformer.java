package transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;
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

public class GSATransformer {

    public static void main(String[] args) {
        try {
            String sourcePath;
            String outputPath;
            
            if (args.length >= 2) {
                sourcePath = args[0];
                outputPath = args[1];
            } else if (args.length == 1) {
                sourcePath = args[0];
                outputPath = sourcePath.replace(".java", "_gsa.java");
                System.out.println("No output path specified, using default: " + outputPath);
            } else {
                System.out.println("Usage: java GSATransformer <source_file> [<output_file>]");
                System.out.println("Using default paths for testing purposes only.");
                sourcePath = "src/main/resources/transformation/predicates/transformed_simple_example.java";
                outputPath = "src/main/resources/transformation/gsas/transformed_simple_example.java";
            }
            
            System.out.println("Transforming to GSA form: " + sourcePath);
            
            // 1. Set up JavaParser with symbol solving.
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);
            StaticJavaParser.setConfiguration(config);

            // 2. Parse the predicate-transformed source file.
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

            // 9. Update return statements to use the latest SSA version.
            ReturnUpdater returnUpdater = new ReturnUpdater(versionMap);
            returnUpdater.visit(cu, null);

            // 10. Convert each non-void method to single-exit form.
            SingleExitTransformer singleExitTransformer = new SingleExitTransformer();
            singleExitTransformer.visit(cu, null);

            // 11. Further expression rewriting.
            ExpressionRewriter exprRewriter = new ExpressionRewriter();
            exprRewriter.visit(cu, null);

            // 12. Write the transformed source code to an output file.
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
                writer.println(cu.toString());
            }
            System.out.println("GSA-transformed code stored to " + outputPath);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ReachingDefinitionsAnalysis (heuristic)
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

    // VariableRenamer: Full SSA renaming.
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
            if (orig.matches("^[a-zA-Z]+_[0-9]+$")) {
                return super.visit(ne, arg);
            }
            try {
                String typeName = ne.resolve().getType().describe();
                if (typeName.equals("boolean")) {
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
            // If this assignment is inside a while loop and is a compound "+=", skip version increment.
            boolean insideWhile = ae.findAncestor(WhileStmt.class).isPresent();
            if (ae.getTarget().isNameExpr()) {
                String currentName = ae.getTarget().asNameExpr().getNameAsString();
                if (currentName.startsWith("P")) {
                    return super.visit(ae, arg);
                }
                // If inside a while and this is a compound assignment, do not update the version.
                if (insideWhile && ae.getOperator() == AssignExpr.Operator.PLUS) {
                    return super.visit(ae, arg);
                }
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
            ifStmt.getCondition().accept(this, arg);
            ifStmt.getThenStmt().accept(this, arg);
            ifStmt.getElseStmt().ifPresent(stmt -> stmt.accept(this, arg));
            return ifStmt;
        }
    }

    // GatingInserterProduction: SSA/gating for merge points and while loops.
    private static class GatingInserterProduction extends VoidVisitorAdapter<Void> {
        private final Map<String, Integer> versionMap;
        public GatingInserterProduction(Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg,
                                        Map<CFGGenerator.CFGNode, Map<String, Set<Integer>>> reachingDefs,
                                        Map<String, Integer> versionMap) {
            this.versionMap = versionMap;
        }
        @Override
        public void visit(BlockStmt block, Void arg) {
            // For if-else merge on candidate "result"
            if (!versionMap.containsKey("result") || versionMap.get("result") == 0) {
                super.visit(block, arg);
                return;
            }
            List<Statement> stmts = new ArrayList<>(block.getStatements());
            for (int i = 0; i < stmts.size(); i++) {
                Statement stmt = stmts.get(i);
                if (stmt instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) stmt;
                    if (!ifStmt.getElseStmt().isPresent()) continue;
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
            if (!(ws.getCondition() instanceof NameExpr)) {
                super.visit(ws, arg);
                return;
            }
            // Detect the loop-carried variable via a compound "+=" assignment.
            String baseVar = null;
            if (ws.getBody().isBlockStmt()) {
                BlockStmt body = ws.getBody().asBlockStmt();
                for (Statement stmt : body.getStatements()) {
                    if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                        AssignExpr ae = stmt.asExpressionStmt().getExpression().asAssignExpr();
                        if (ae.getOperator() == AssignExpr.Operator.PLUS) {
                            String target = ae.getTarget().asNameExpr().getNameAsString();
                            baseVar = target.replaceAll("_\\d+$", "");
                            break;
                        }
                    }
                }
            }
            if (baseVar == null) {
                super.visit(ws, arg);
                return;
            }
            // Use the current SSA name without updating the versionMap.
            int currentVer = versionMap.getOrDefault(baseVar, 1);
            String currentSSA = baseVar + "_" + currentVer;
            // Use a temporary name based on the base variable.
            String tempVar = baseVar + "_temp";
            if (ws.getBody().isBlockStmt()) {
                BlockStmt body = ws.getBody().asBlockStmt();
                List<Statement> stmts = new ArrayList<>(body.getStatements());
                for (int i = 0; i < stmts.size(); i++) {
                    Statement stmt = stmts.get(i);
                    if (stmt.isExpressionStmt() &&
                            stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                        AssignExpr ae = stmt.asExpressionStmt().getExpression().asAssignExpr();
                        if (ae.getOperator() == AssignExpr.Operator.PLUS) {
                            String currentTarget = ae.getTarget().asNameExpr().getNameAsString();
                            String rawTarget = currentTarget.replaceAll("_\\d+$", "");
                            if (!rawTarget.equals(baseVar)) continue;
                            // Replace "sum_1 += expr;" with "int temp = sum_1 + expr;"
                            Expression newValue = new BinaryExpr(new NameExpr(currentSSA),
                                    ae.getValue().clone(), BinaryExpr.Operator.PLUS);
                            VariableDeclarationExpr tempDecl = new VariableDeclarationExpr();
                            tempDecl.addVariable(new com.github.javaparser.ast.body.VariableDeclarator(
                                    new PrimitiveType(PrimitiveType.Primitive.INT),
                                    tempVar,
                                    newValue
                            ));
                            body.getStatements().set(i, new ExpressionStmt(tempDecl));
                            // Immediately insert an assignment: currentSSA = temp;
                            AssignExpr mergeAssign = new AssignExpr(new NameExpr(currentSSA),
                                    new NameExpr(tempVar), AssignExpr.Operator.ASSIGN);
                            body.addStatement(i + 1, new ExpressionStmt(mergeAssign));
                            break;
                        }
                    }
                }
            }
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

    // ReturnUpdater: Replace return variable with its latest SSA version.
    private static class ReturnUpdater extends VoidVisitorAdapter<Void> {
        private final Map<String, Integer> versionMap;
        public ReturnUpdater(Map<String, Integer> versionMap) {
            this.versionMap = versionMap;
        }
        @Override
        public void visit(ReturnStmt rs, Void arg) {
            rs.getExpression().ifPresent(expr -> {
                if (expr instanceof NameExpr) {
                    NameExpr ne = (NameExpr) expr;
                    String base = ne.getNameAsString().replaceAll("_\\d+$", "");
                    int latest = versionMap.getOrDefault(base, 0);
                    ne.setName(base + "_" + latest);
                }
            });
            super.visit(rs, arg);
        }
    }

    // SingleExitTransformer: Convert non-void methods to single-exit form.
    private static class SingleExitTransformer extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(MethodDeclaration md, Void arg) {
            if (md.getType().isVoidType() || !md.getBody().isPresent()) {
                return super.visit(md, arg);
            }
            BlockStmt originalBody = md.getBody().get();
            // Create a new exit variable.
            String exitVar = "_exit";
            VariableDeclarationExpr exitDecl = new VariableDeclarationExpr(md.getType(), exitVar);
            // Wrap the original body in a labeled block.
            LabeledStmt labeledBody = new LabeledStmt("methodBody", originalBody);
            // Replace return statements inside the original body.
            originalBody.accept(new ReturnReplacer(exitVar, "methodBody"), null);
            // Build a new method body.
            BlockStmt newBody = new BlockStmt();
            newBody.addStatement(exitDecl);
            newBody.addStatement(labeledBody);
            newBody.addStatement(new ReturnStmt(new NameExpr(exitVar)));
            md.setBody(newBody);
            return md;
        }
    }

    private static class ReturnReplacer extends ModifierVisitor<Void> {
        private final String exitVar;
        private final String label;
        public ReturnReplacer(String exitVar, String label) {
            this.exitVar = exitVar;
            this.label = label;
        }
        @Override
        public Visitable visit(ReturnStmt rs, Void arg) {
            List<Statement> replacement = new ArrayList<>();
            if (rs.getExpression().isPresent()) {
                Expression expr = rs.getExpression().get();
                AssignExpr assign = new AssignExpr(new NameExpr(exitVar), expr, AssignExpr.Operator.ASSIGN);
                replacement.add(new ExpressionStmt(assign));
            }
            replacement.add(new BreakStmt(label));
            return new BlockStmt(new NodeList<>(replacement));
        }
    }

    // WhileLoopConditionFixer: (No changes)
    private static class WhileLoopConditionFixer extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(WhileStmt ws, Void arg) {
            return super.visit(ws, arg);
        }
    }

    // ExpressionRewriter: (Placeholder)
    private static class ExpressionRewriter extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(ConditionalExpr ce, Void arg) {
            return super.visit(ce, arg);
        }
    }
}
