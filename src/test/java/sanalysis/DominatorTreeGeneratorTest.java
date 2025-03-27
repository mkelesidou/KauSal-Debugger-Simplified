package sanalysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class DominatorTreeGeneratorTest {

    @Test
    void shouldCorrectlyIdentifyDominatorsInSimpleLinearGraph() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        CFGGenerator.CFGNode entry = new CFGGenerator.CFGNode("1", "entry");
        CFGGenerator.CFGNode node2 = new CFGGenerator.CFGNode("2", "statement");
        CFGGenerator.CFGNode node3 = new CFGGenerator.CFGNode("3", "statement");

        cfg.addNode(entry);
        cfg.addNode(node2);
        cfg.addNode(node3);
        cfg.addEdge(entry, node2);
        cfg.addEdge(node2, node3);

        // Act
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators = 
            DominatorTreeGenerator.computeDominators(cfg, entry);

        // Assert
        // Entry should dominate all nodes
        assertTrue(dominators.get(node2).contains(entry));
        assertTrue(dominators.get(node3).contains(entry));
        // Node2 should dominate node3 and itself
        assertTrue(dominators.get(node3).contains(node2));
        assertTrue(dominators.get(node2).contains(node2));
    }

    @Test
    void shouldCorrectlyIdentifyDominatorsInGraphWithBranches() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        CFGGenerator.CFGNode entry = new CFGGenerator.CFGNode("1", "entry");
        CFGGenerator.CFGNode ifNode = new CFGGenerator.CFGNode("2", "if");
        CFGGenerator.CFGNode thenNode = new CFGGenerator.CFGNode("3", "then");
        CFGGenerator.CFGNode elseNode = new CFGGenerator.CFGNode("4", "else");
        CFGGenerator.CFGNode exitNode = new CFGGenerator.CFGNode("5", "exit");

        cfg.addNode(entry);
        cfg.addNode(ifNode);
        cfg.addNode(thenNode);
        cfg.addNode(elseNode);
        cfg.addNode(exitNode);

        // Create diamond shape
        cfg.addEdge(entry, ifNode);
        cfg.addEdge(ifNode, thenNode);
        cfg.addEdge(ifNode, elseNode);
        cfg.addEdge(thenNode, exitNode);
        cfg.addEdge(elseNode, exitNode);

        // Act
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators = 
            DominatorTreeGenerator.computeDominators(cfg, entry);
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom = 
            DominatorTreeGenerator.computeImmediateDominators(dominators, entry);

        // Assert
        // Entry and ifNode should dominate all nodes
        for (CFGGenerator.CFGNode node : cfg.getNodes()) {
            assertTrue(dominators.get(node).contains(entry));
            if (!node.equals(entry)) {
                assertTrue(dominators.get(node).contains(ifNode));
            }
        }

        // Then and else nodes should only dominate themselves
        assertEquals(1, dominators.get(thenNode).stream()
            .filter(n -> n.equals(thenNode)).count());
        assertEquals(1, dominators.get(elseNode).stream()
            .filter(n -> n.equals(elseNode)).count());

        // Check immediate dominators
        assertEquals(ifNode, idom.get(thenNode));
        assertEquals(ifNode, idom.get(elseNode));
        assertEquals(ifNode, idom.get(exitNode));
        assertEquals(entry, idom.get(ifNode));
    }

    @Test
    void shouldGenerateCorrectDominatorTree() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        CFGGenerator.CFGNode entry = new CFGGenerator.CFGNode("1", "entry");
        CFGGenerator.CFGNode node2 = new CFGGenerator.CFGNode("2", "statement");
        CFGGenerator.CFGNode node3 = new CFGGenerator.CFGNode("3", "statement");

        // Create linear graph
        cfg.addNode(entry);
        cfg.addNode(node2);
        cfg.addNode(node3);
        cfg.addEdge(entry, node2);
        cfg.addEdge(node2, node3);

        // Act
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> dominators = 
            DominatorTreeGenerator.computeDominators(cfg, entry);
        Map<CFGGenerator.CFGNode, CFGGenerator.CFGNode> idom = 
            DominatorTreeGenerator.computeImmediateDominators(dominators, entry);
        CFGGenerator.ControlFlowGraph domTree = 
            DominatorTreeGenerator.generateDominatorTree(idom);

        // Assert
        // Check tree structure
        assertEquals(3, domTree.getNodes().size());
        assertTrue(domTree.getEdges().stream()
            .anyMatch(e -> e.from.equals(entry) && e.to.equals(node2)));
        assertTrue(domTree.getEdges().stream()
            .anyMatch(e -> e.from.equals(node2) && e.to.equals(node3)));
    }
}
