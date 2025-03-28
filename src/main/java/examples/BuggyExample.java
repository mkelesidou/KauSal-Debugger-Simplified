package examples;

/**
 * BuggyExample.java
 *
 * A larger example for testing the UniVal pipeline with known bugs.
 * This example includes:
 *   - A main method that drives several functions.
 *   - Several methods with intentional bugs to simulate faulty behavior.
 *
 * Known Bugs:
 *   1. calculateDiscount: Discount rates are reversed.
 *      - For amounts > 100, it returns 5% discount instead of 10%.
 *      - For amounts ≤ 100, it returns 10% discount instead of 5%.
 *
 *   2. formatMessage: The greeting and target are swapped.
 *
 *   3. processData: Instead of summing the array elements,
 *      it multiplies each element by 2 before adding.
 *
 * Use this class with multiple test inputs so that your causal model can
 * (hopefully) detect that changes in these treatment variables correlate with
 * the program’s failure (or unexpected outputs).
 */
public class BuggyExample {

    public static void main(String[] args) {
        // Use a command-line argument as the test amount, default to 50 if none provided.
        int testAmount = (args.length > 0) ? Integer.parseInt(args[0]) : 50;
        double discount = calculateDiscount(testAmount);
        System.out.println("Discount for amount " + testAmount + " = " + discount);

        String message = formatMessage("Hello", "World");
        System.out.println("Formatted message: " + message);

        int[] data = {1, 2, 3, 4, 5};
        int processed = processData(data);
        System.out.println("Processed data sum: " + processed);
    }

    /**
     * BUG: Discount rates are reversed.
     * Intended: For amounts > 100, a 10% discount; for amounts ≤ 100, a 5% discount.
     * Actual: For amounts > 100, a 5% discount; for amounts ≤ 100, a 10% discount.
     */
    public static double calculateDiscount(int amount) {
        if (amount > 100) {
            return amount * 0.05;
        } else {
            return amount * 0.10;
        }
    }

    /**
     * BUG: The formatted message swaps the greeting and the target.
     * Intended: "Hello, World!"
     * Actual: "World, Hello!"
     */
    public static String formatMessage(String greeting, String target) {
        return target + ", " + greeting + "!";
    }

    /**
     * BUG: Instead of simply summing the elements of the array, it multiplies each element by 2 before summing.
     * Intended: Return the sum of the array.
     * Actual: Return the sum of each element multiplied by 2.
     */
    public static int processData(int[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i] * 2;
        }
        return sum;
    }
}
