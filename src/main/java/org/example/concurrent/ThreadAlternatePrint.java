package org.example.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Semaphore;

/**
 * Java 线程交替打印 1 到 100 的多种实现方案
 */
public class ThreadAlternatePrint {

    // ==================== 方案一：synchronized + wait/notify ====================

    static class SynchronizedSolution {
        private final Object lock = new Object();
        private int number = 1;
        private boolean turn = true; // true: 线程A打印, false: 线程B打印

        public void print() {
            Thread t1 = new Thread(() -> {
                while (true) {
                    synchronized (lock) {
                        while (!turn) { // 不是自己的回合就等待
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (number > 100) {
                            lock.notify();
                            break;
                        }
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        turn = false;
                        lock.notify();
                    }
                }
            }, "线程-A");

            Thread t2 = new Thread(() -> {
                while (true) {
                    synchronized (lock) {
                        while (turn) { // 不是自己的回合就等待
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (number > 100) {
                            lock.notify();
                            break;
                        }
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        turn = true;
                        lock.notify();
                    }
                }
            }, "线程-B");

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 方案二：ReentrantLock + Condition ====================

    static class ReentrantLockSolution {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition conditionA = lock.newCondition();
        private final Condition conditionB = lock.newCondition();
        private int number = 1;
        private volatile boolean isATurn = true;

        public void print() {
            Thread t1 = new Thread(() -> {
                while (true) {
                    lock.lock();
                    try {
                        while (!isATurn) {
                            conditionA.await();
                        }
                        if (number > 100) {
                            conditionB.signal();
                            break;
                        }
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        isATurn = false;
                        conditionB.signal();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        lock.unlock();
                    }
                }
            }, "线程-A");

            Thread t2 = new Thread(() -> {
                while (true) {
                    lock.lock();
                    try {
                        while (isATurn) {
                            conditionB.await();
                        }
                        if (number > 100) {
                            conditionA.signal();
                            break;
                        }
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        isATurn = true;
                        conditionA.signal();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        lock.unlock();
                    }
                }
            }, "线程-B");

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 方案三：Semaphore 信号量 ====================

    static class SemaphoreSolution {
        private final Semaphore semaphoreA = new Semaphore(1); // A 先执行
        private final Semaphore semaphoreB = new Semaphore(0);
        private volatile int number = 1;

        public void print() {
            Thread t1 = new Thread(() -> {
                while (number <= 100) {
                    try {
                        semaphoreA.acquire();
                        if (number > 100) break;
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        semaphoreB.release();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "线程-A");

            Thread t2 = new Thread(() -> {
                while (number <= 100) {
                    try {
                        semaphoreB.acquire();
                        if (number > 100) break;
                        System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                        number++;
                        semaphoreA.release();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "线程-B");

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 方案四：更通用的多线程方案（n个线程交替）====================

    static class MultiThreadSolution {
        private final Object lock = new Object();
        private int number = 1;
        private int currentThread = 0; // 当前应该执行的线程索引
        private final int threadCount;

        public MultiThreadSolution(int threadCount) {
            this.threadCount = threadCount;
        }

        public void print() {
            Thread[] threads = new Thread[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                threads[i] = new Thread(() -> {
                    while (true) {
                        synchronized (lock) {
                            while (currentThread != threadIndex) {
                                try {
                                    lock.wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            if (number > 100) {
                                lock.notifyAll();
                                break;
                            }
                            System.out.println(Thread.currentThread().getName() + " 打印: " + number);
                            number++;
                            currentThread = (threadIndex + 1) % threadCount;
                            lock.notifyAll();
                        }
                    }
                }, "线程-" + (char) ('A' + i));
            }

            for (Thread t : threads) {
                t.start();
            }
            try {
                for (Thread t : threads) {
                    t.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 方案五：AtomicInteger + CAS（无锁方案）====================

    static class CasSolution {
        private java.util.concurrent.atomic.AtomicInteger number =
                new java.util.concurrent.atomic.AtomicInteger(1);
        private volatile int turn = 0; // 0: 线程A, 1: 线程B

        public void print() {
            Thread t1 = new Thread(() -> {
                while (true) {
                    if (number.get() > 100) break;
                    if (turn == 0) {
                        int current = number.getAndIncrement();
                        if (current > 100) break;
                        System.out.println(Thread.currentThread().getName() + " 打印: " + current);
                        turn = 1;
                    }
                    Thread.yield(); // 让出CPU，避免忙等待
                }
            }, "线程-A");

            Thread t2 = new Thread(() -> {
                while (true) {
                    if (number.get() > 100) break;
                    if (turn == 1) {
                        int current = number.getAndIncrement();
                        if (current > 100) break;
                        System.out.println(Thread.currentThread().getName() + " 打印: " + current);
                        turn = 0;
                    }
                    Thread.yield(); // 让出CPU，避免忙等待
                }
            }, "线程-B");

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 主程序 ====================

    public static void main(String[] args) {
        System.out.println("========== 方案一：synchronized + wait/notify ==========");
        new SynchronizedSolution().print();

        System.out.println("\n========== 方案二：ReentrantLock + Condition ==========");
        new ReentrantLockSolution().print();

        System.out.println("\n========== 方案三：Semaphore 信号量 ==========");
        new SemaphoreSolution().print();

        System.out.println("\n========== 方案四：多线程通用方案（3个线程） ==========");
        new MultiThreadSolution(3).print();

        System.out.println("\n========== 方案五：AtomicInteger + CAS ==========");
        new CasSolution().print();
    }
}
