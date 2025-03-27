package sanalysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class CDGGeneratorTest {
    
    private CDGGenerator cdgGenerator;
    
    @BeforeEach
    void setUp() {
        cdgGenerator = new CDGGenerator();
    }
    
    @Test
    @DisplayName("Should correctly identify control dependencies in if-then structure")
    void shouldIdentifyControlDependenciesInIfThenStructure() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        
        // Create nodes for if-then structure
        CFGGenerator.CFGNode methodStart = new CFGGenerator.CFGNode("1", "Method Start: test");
        CFGGenerator.CFGNode ifCondition = new CFGGenerator.CFGNode("2", "if x > 0");
        CFGGenerator.CFGNode thenBlock = new CFGGenerator.CFGNode("3", "System.out.println(x)");
        CFGGenerator.CFGNode methodEnd = new CFGGenerator.CFGNode("4", "Method End: test");
        
        // Add nodes and edges to create if-then structure
        cfg.addNode(methodStart);
        cfg.addNode(ifCondition);
        cfg.addNode(thenBlock);
        cfg.addNode(methodEnd);
        
        cfg.addEdge(methodStart, ifCondition);
        cfg.addEdge(ifCondition, thenBlock);
        cfg.addEdge(thenBlock, methodEnd);
        cfg.addEdge(ifCondition, methodEnd);  // false branch
        
        // Act - use the public method instead of private ones
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = 
            CDGGenerator.computeInterproceduralCDG(cfg);
            
        // Assert
        assertTrue(cdg.containsKey(ifCondition), "If condition should be a control node");
        assertTrue(cdg.get(ifCondition).contains(thenBlock), 
            "Then block should be control dependent on if condition");
        assertFalse(cdg.get(ifCondition).contains(methodEnd), 
            "Method end should not be control dependent on if condition");
    }
    
    @Test
    @DisplayName("Should correctly handle while loop control dependencies")
    void shouldHandleWhileLoopControlDependencies() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        
        // Create nodes for while loop structure
        CFGGenerator.CFGNode methodStart = new CFGGenerator.CFGNode("1", "Method Start: test");
        CFGGenerator.CFGNode whileCondition = new CFGGenerator.CFGNode("2", "while i < 10");
        CFGGenerator.CFGNode loopBody = new CFGGenerator.CFGNode("3", "i++");
        CFGGenerator.CFGNode methodEnd = new CFGGenerator.CFGNode("4", "Method End: test");
        
        // Add nodes and edges to create while loop structure
        cfg.addNode(methodStart);
        cfg.addNode(whileCondition);
        cfg.addNode(loopBody);
        cfg.addNode(methodEnd);
        
        cfg.addEdge(methodStart, whileCondition);
        cfg.addEdge(whileCondition, loopBody);
        cfg.addEdge(loopBody, whileCondition);  // loop back
        cfg.addEdge(whileCondition, methodEnd);  // exit loop
        
        // Act - use the public method
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = 
            CDGGenerator.computeInterproceduralCDG(cfg);
            
        // Assert
        assertTrue(cdg.containsKey(whileCondition), "While condition should be a control node");
        assertTrue(cdg.get(whileCondition).contains(loopBody), 
            "Loop body should be control dependent on while condition");
    }
    
    @Test
    @DisplayName("Should correctly handle nested control structures")
    void shouldHandleNestedControlStructures() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        
        // Create nodes for nested if-while structure
        CFGGenerator.CFGNode methodStart = new CFGGenerator.CFGNode("1", "Method Start: test");
        CFGGenerator.CFGNode ifCondition = new CFGGenerator.CFGNode("2", "if x > 0");
        CFGGenerator.CFGNode whileCondition = new CFGGenerator.CFGNode("3", "while i < 10");
        CFGGenerator.CFGNode whileBody = new CFGGenerator.CFGNode("4", "i++");
        CFGGenerator.CFGNode methodEnd = new CFGGenerator.CFGNode("5", "Method End: test");
        
        // Add nodes and create nested structure
        cfg.addNode(methodStart);
        cfg.addNode(ifCondition);
        cfg.addNode(whileCondition);
        cfg.addNode(whileBody);
        cfg.addNode(methodEnd);
        
        cfg.addEdge(methodStart, ifCondition);
        cfg.addEdge(ifCondition, whileCondition);
        cfg.addEdge(whileCondition, whileBody);
        cfg.addEdge(whileBody, whileCondition);
        cfg.addEdge(whileCondition, methodEnd);
        cfg.addEdge(ifCondition, methodEnd);
        
        // Act - use the public method
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = 
            CDGGenerator.computeInterproceduralCDG(cfg);
            
        // Assert
        assertTrue(cdg.get(ifCondition).contains(whileCondition), 
            "While condition should be control dependent on if condition");
        assertTrue(cdg.get(whileCondition).contains(whileBody), 
            "While body should be control dependent on while condition");
    }
    
    // The test for empty input might fail because we need to modify how computeInterproceduralCDG handles empty graphs
    // You can modify this test based on the expected behavior
    @Test
    @DisplayName("Should handle empty CFG appropriately")
    void shouldHandleEmptyCfg() {
        // Arrange
        CFGGenerator.ControlFlowGraph emptyCfg = new CFGGenerator.ControlFlowGraph();
        
        // Act
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = 
            CDGGenerator.computeInterproceduralCDG(emptyCfg);
            
        // Assert
        assertTrue(cdg.isEmpty(), "CDG of empty CFG should be empty");
    }
    
    @Test
    @DisplayName("Should correctly identify method-level control dependencies")
    void shouldIdentifyMethodLevelControlDependencies() {
        // Arrange
        CFGGenerator.ControlFlowGraph cfg = new CFGGenerator.ControlFlowGraph();
        
        // Create simple method structure
        CFGGenerator.CFGNode methodStart = new CFGGenerator.CFGNode("1", "Method Start: test");
        CFGGenerator.CFGNode statement = new CFGGenerator.CFGNode("2", "x = 5");
        CFGGenerator.CFGNode methodEnd = new CFGGenerator.CFGNode("3", "Method End: test");
        
        cfg.addNode(methodStart);
        cfg.addNode(statement);
        cfg.addNode(methodEnd);
        
        cfg.addEdge(methodStart, statement);
        cfg.addEdge(statement, methodEnd);
        
        // Act - use the public method
        Map<CFGGenerator.CFGNode, Set<CFGGenerator.CFGNode>> cdg = 
            CDGGenerator.computeInterproceduralCDG(cfg);
            
        // Assert
        assertTrue(cdg.get(methodStart).contains(statement), 
            "Statement should be control dependent on method start");
    }
} 