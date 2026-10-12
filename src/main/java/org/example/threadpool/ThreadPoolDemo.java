package org.example.threadpool;

public class ThreadPoolDemo {
    public static void main(String[] args) throws InterruptedException {
        SimpleThreadPool pool = new SimpleThreadPool(3);

        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            pool.execute(() -> {
                System.out.println("Task " + taskId + " running on " + Thread.currentThread().getName());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Thread.sleep(3000);
        pool.shutdown();
        System.out.println("Pool shutdown.");
    }
}