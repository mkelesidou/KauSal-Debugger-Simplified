package examples;

public class Example3 {
    public void exampleMethod3(int x, int y, String str) {
        if (x > 0) {
            System.out.println("Positive x");
            for (int i = 0; i < x; i++) {
                if (i % 2 == 0) {
                    System.out.println("Even number: " + i);
                } else {
                    System.out.println("Odd number: " + i);
                }

                // Nested loop with break and continue
                for (int j = i; j < x; j++) {
                    if (j > 5) {
                        break;
                    }
                    if (j % 3 == 0) {
                        continue;
                    }
                    System.out.println("Nested loop: " + j);
                }
            }
        } else {
            System.out.println("Non-positive x");
        }

        try {
            int result = y / x;
            System.out.println("Result: " + result);

            if (result > 10) {
                System.out.println("Large result!");
            }
        } catch (ArithmeticException e) {
            System.out.println("Cannot divide by zero!");
        }

        // Nested try-catch
        try {
            char firstChar = str.charAt(0);
            System.out.println("First character: " + firstChar);
        } catch (NullPointerException e) {
            System.out.println("String is null!");
        } catch (StringIndexOutOfBoundsException e) {
            System.out.println("String is empty!");
        }

        while (y > 0) {
            y--;
            if (y == 0) {
                System.out.println("y reached zero");
            } else {
                System.out.println("y is now: " + y);
            }
        }
    }
}
