public class Main {
    public static void main(String[] args) {
        for (int i = 0; i < 10; ++i) {
            int threadId = i;
            new Thread(() -> {
                try {
                	for (int j = 0; j < 10; ++j) {
                        Thread.sleep(1000 + threadId * 50);
                        System.out.println("Thread " + threadId + ": " + j);
                    }
                } catch (InterruptedException e) {
                    // Do nothing
                }
            }).start();
        }
    }
} 
