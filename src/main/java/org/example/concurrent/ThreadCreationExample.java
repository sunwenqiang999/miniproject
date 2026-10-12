package org.example.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Java 创建线程的常见方式。
 */
public class ThreadCreationExample {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        // 方式 1：继承 Thread 类。
        Thread threadByInheritance = new PrintThread();
        threadByInheritance.setName("inherit-thread");
        threadByInheritance.start();

        // 方式 2：实现 Runnable 接口。适合没有返回值的任务。
        Runnable runnableTask = () ->
                System.out.println("Runnable 执行线程：" + Thread.currentThread().getName());
        Thread threadByRunnable = new Thread(runnableTask, "runnable-thread");
        threadByRunnable.start();

        // 方式 3：实现 Callable 接口，通过 FutureTask 获得返回值。
        Callable<String> callableTask = () ->
                "Callable 执行线程：" + Thread.currentThread().getName();
        FutureTask<String> futureTask = new FutureTask<>(callableTask);
        Thread threadByCallable = new Thread(futureTask, "callable-thread");
        threadByCallable.start();
        System.out.println(futureTask.get());

        // 方式 4：通过线程池创建和管理线程。生产环境优先使用。
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> future = executor.submit(() ->
                    "线程池执行线程：" + Thread.currentThread().getName()
            );
            System.out.println(future.get());
        } finally {
            executor.shutdown();
        }

        // 确保前两种方式的演示任务执行完成。
        threadByInheritance.join();
        threadByRunnable.join();
    }

    /**
     * 创建线程方式一：继承 Thread。
     */
    private static class PrintThread extends Thread {
        @Override
        public void run() {
            System.out.println("Thread 子类执行线程：" + Thread.currentThread().getName());
        }
    }
}
