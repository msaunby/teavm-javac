 public class Main {
    public static void main(String[] args) {
        int a = 0;
        int b = 1;
        for (int i = 0; i <= 30; ++i) {
            int c = a + b;
            a = b;
            b = c;
            System.out.println("fib(" + i + ") = " + a);
        }
    }
}
