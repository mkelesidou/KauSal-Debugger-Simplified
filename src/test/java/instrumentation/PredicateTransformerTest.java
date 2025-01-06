package instrumentation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PredicateTransformerTest {

    @Test
    public void testFindPredicate() {
        PredicateTransformer transformer = new PredicateTransformer();

        String sourceCode = """
    public class TestClass {
        public void testMethod() {
            if (x > 0) {
                System.out.println(x);
            }
            while (y < 10) {
                y++;
            }
            for (int i = 0; i < 5; i++) {
                System.out.println(i);
            }
        }
    }
""";

        List<String> predicates = transformer.findPredicate(sourceCode);
        assertEquals(3, predicates.size());
        assertTrue(predicates.contains("if-statement"));
        assertTrue(predicates.contains("while-loop"));
        assertTrue(predicates.contains("for-loop"));
    }
}
