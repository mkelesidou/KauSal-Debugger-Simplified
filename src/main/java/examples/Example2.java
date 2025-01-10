package examples;

public class Example2 {
    public void exampleMethod2(int x, int y, String str) {
        if (x > 0) {
            System.out.println("Positive x");
            for (int i = 0; i < x; i++) {
                if (i % 2 == 0) {
                    System.out.println("Even number: " + i);
                } else {
                    System.out.println("Odd number: " + i);
                }
            }
        } else {
            System.out.println("Non-positive x");
        }

        int counter = y;
        while (counter > 0) {
            if (counter % 2 == 0) {
                System.out.println("Even counter: " + counter);
            } else {
                System.out.println("Odd counter: " + counter);
            }
            counter--;
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

        for (char c : str.toCharArray()) {
            if (Character.isUpperCase(c)) {
                System.out.println("Uppercase letter: " + c);
            } else if (Character.isLowerCase(c)) {
                System.out.println("Lowercase letter: " + c);
            } else {
                System.out.println("Other character: " + c);
            }
        }
    }
}
