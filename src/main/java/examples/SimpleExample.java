package examples;

/**
 * SimpleExample.java
 *
 * A simplified test case for the UniVal pipeline.
 * This example includes:
 *   - A main method to set up a basic test.
 *   - A single method (simpleMethod) that demonstrates:
 *       * An if-else condition.
 *       * A while loop.
 *
 * The purpose is to verify that the pipeline components (AST, CFG, CDG, etc.)
 * work correctly on a minimal codebase.
 */
public class SimpleExample {

    public static void main(String[] args) {
        int input = 4;
        int output = simpleMethod(input);
        System.out.println("Output: " + output);
    }

    /**
     * Performs a simple transformation:
     *   - If the input is greater than 5, it doubles the input.
     *   - Otherwise, it increments the input by 3.
     * Then, it repeatedly adds 2 until the result reaches at least 15.
     *
     * @param x the input integer.
     * @return the transformed value.
     */
    public static int simpleMethod(int x) {
        int result;
        if (x > 5) {
            result = x * 2;
        } else {
            result = x + 3;
        }

        while (result < 15) {
            result += 2;
        }

        return result;
    }
}
