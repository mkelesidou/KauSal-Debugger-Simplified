package examples;

public class Example1 {
    public void exampleMethod1(int x, int y) {
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

        try {
            int result = y / x;
            System.out.println("Result: " + result);
        } catch (ArithmeticException e) {
            System.out.println("Cannot divide by zero!");
        }

        switch (y) {
            case 1:
                System.out.println("y is 1");
                break;
            case 2:
                System.out.println("y is 2");
                break;
            default:
                System.out.println("y is something else");
                break;
        }
    }
}
