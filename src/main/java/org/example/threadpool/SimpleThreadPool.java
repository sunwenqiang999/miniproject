package org.example.threadpool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SimpleThreadPool {

    private final BlockingQueue<Runnable> taskQueue;
    private final List<Worker> workers;
    private volatile boolean shutdown = false;

    public SimpleThreadPool(int poolSize) {
        this.taskQueue = new LinkedBlockingQueue<>();
        this.workers = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            Worker worker = new Worker();
            workers.add(worker);
            worker.start();
        }
    }

    public void execute(Runnable task) {
        if (shutdown) {
            throw new IllegalStateException("Thread pool is shutdown");
        }
        taskQueue.offer(task);
    }

    public void shutdown() {
        shutdown = true;
        for (Worker worker : workers) {
            worker.interrupt();
        }
    }

    private class Worker extends Thread {
        @Override
        public void run() {
            while (!shutdown || !taskQueue.isEmpty()) {
                try {
                    Runnable task = taskQueue.poll(1, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (InterruptedException e) {
                    if (shutdown && taskQueue.isEmpty()) {
                        break;
                    }
                }
            }
        }
    }
}