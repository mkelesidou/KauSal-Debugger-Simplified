package examples;

public class Example1 {
    public void exampleMethod1(int x) {
        if (x > 0) {
            System.out.println("Positive");
        } else {
            System.out.println("Non-positive");
        }

        for (int i = 0; i < x; i++) {
            System.out.println(i);
        }
    }
}
