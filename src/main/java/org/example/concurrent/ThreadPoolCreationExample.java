package org.example.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Java 中常见的线程池创建方式示例。
 */
public class ThreadPoolCreationExample {

    public static void main(String[] args) {
        // 方式 1：固定大小线程池。适合任务数量可控、并发数固定的场景。
        ExecutorService fixedThreadPool = Executors.newFixedThreadPool(10);

        // 方式 2：单线程线程池。所有任务按提交顺序串行执行。
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

        // 方式 3：缓存线程池。按需创建线程，空闲 60 秒后回收；不适合大量阻塞任务。
        ExecutorService cachedThreadPool = Executors.newCachedThreadPool();

        // 方式 4：推荐生产环境显式创建 ThreadPoolExecutor。
        // 核心线程数 10，最大线程数 20，队列最多容纳 100 个任务。
        ThreadFactory namedThreadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("http-worker-" + thread.getId());
            return thread;
        };

        ExecutorService customThreadPool = new ThreadPoolExecutor(
                10,
                20,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                namedThreadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        try {
            customThreadPool.submit(() ->
                    System.out.println("执行任务，线程名：" + Thread.currentThread().getName())
            );
        } finally {
            fixedThreadPool.shutdown();
            singleThreadExecutor.shutdown();
            cachedThreadPool.shutdown();
            customThreadPool.shutdown();
        }
    }
}
