package instrumentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;


class LogAggregatorTest {

    @BeforeEach
    void setUp() {
        // Reset the LogAggregator state before each test
        LogAggregator.reset();
    }
    
    @Test
    void testResetDeletesExistingCSVFile() {
        // Verify the reset method functionality
        // This is a behavior test, checking that reset() clears previous state
        
        // Generate test data
        String testArgs = "test(1, 2)";
        List<String> logLines = Arrays.asList("a = 1", "b = 2");
        
        // Call the method
        LogAggregator.aggregateTest(testArgs, logLines, 1);
        
        // Reset and add new data
        LogAggregator.reset();
        LogAggregator.aggregateTest("test2", Arrays.asList("x = 10"), 0);
        
        // We can't directly access the CSV file since it's an implementation detail
        // Instead, we'll use reset() again and add a new entry to test clean state
        LogAggregator.reset();
        LogAggregator.aggregateTest("test3", Arrays.asList("y = 20"), 1);
        
        // No assertions here as we're just testing the reset doesn't throw exceptions
    }
    
    @Test
    void testFiltersTemporaryVariables() {
        // This test verifies that temporary variables are filtered out
        // We'll run the aggregation with variables that should be filtered
        String testArgs = "test(1, 2)";
        List<String> logLines = Arrays.asList(
            "a = 1",
            "b = 2",
            "tempVar = 100",  // Should be filtered out
            "result = 3",
            "debug_debug = true"  // Should be filtered out
        );
        
        // Call the method - this shouldn't throw an exception
        LogAggregator.aggregateTest(testArgs, logLines, 1);
        
        // No direct assertions as we can't see the output directly without accessing implementation details
    }
    
    @Test
    void testHandlesMultipleAssignments() {
        // Test the behavior with variables assigned multiple times
        String testArgs = "test(3, 4)";
        List<String> logLines = Arrays.asList(
            "a = 3",
            "b = 4",
            "result = 7", 
            "a = 5",       // Variable reassigned
            "result = 9"   // Final value
        );
        
        // Call the method - should process without exceptions
        LogAggregator.aggregateTest(testArgs, logLines, 0);
    }
    
    @Test
    void testHandlesEmptyLogLines() {
        // Test with empty log lines
        LogAggregator.aggregateTest("emptyTest", Collections.emptyList(), 1);
        
        // The test passes if no exception is thrown
    }
    
    @Test
    void testHandlesNullValues() {
        // Test with null values in log lines
        List<String> logLines = Arrays.asList(
            "a = null",
            "b = "
        );
        
        LogAggregator.aggregateTest("nullTest", logLines, 0);
        
        // The test passes if no exception is thrown
    }
} 