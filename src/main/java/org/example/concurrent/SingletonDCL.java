package org.example.concurrent;

/**
 * 单例模式 - 双重检查锁定（Double-Checked Locking, DCL）
 *
 * <p>核心要点：
 * <ol>
 *   <li>使用 {@code volatile} 修饰实例引用，禁止指令重排序，避免其他线程拿到"半初始化"的对象。</li>
 *   <li>第一次检查（无锁）：绝大多数场景下实例已存在，直接返回，避免每次都加锁带来的性能开销。</li>
 *   <li>加锁：只有在实例为 null 时才进入同步块，保证只有一个线程能创建对象。</li>
 *   <li>第二次检查（有锁）：防止多个线程同时通过第一次检查后，重复创建对象。</li>
 * </ol>
 *
 * <p>为什么必须使用 volatile？
 * <p>{@code instance = new SingletonDCL()} 并非原子操作，JVM 可能会将其重排序为：
 * <pre>
 *   1. 分配内存空间
 *   2. 将 instance 引用指向内存地址（此时 instance != null，但对象尚未初始化）
 *   3. 执行构造函数初始化对象
 * </pre>
 * 若发生 2、3 重排序，其他线程在第一次检查时可能拿到未初始化完成的对象，导致 NPE 或字段异常。
 * volatile 通过内存屏障禁止此类重排序，同时保证多线程间的可见性。
 */
public class SingletonDCL {

    /** volatile 保证可见性 + 禁止指令重排序 */
    private static volatile SingletonDCL instance;

    /** 私有构造器，禁止外部通过 new 创建实例 */
    private SingletonDCL() {
        // 防御反射攻击：若已存在实例，抛出异常
        if (instance != null) {
            throw new IllegalStateException("SingletonDCL 已经被实例化，请通过 getInstance() 获取");
        }
    }

    /**
     * 获取单例实例（线程安全）
     *
     * @return 全局唯一的 SingletonDCL 实例
     */
    public static SingletonDCL getInstance() {
        // 第一次检查：无锁，快速返回已存在的实例
        if (instance == null) {
            // 加锁：仅在实例未创建时进入
            synchronized (SingletonDCL.class) {
                // 第二次检查：防止多个线程并发通过第一次检查后重复创建
                if (instance == null) {
                    instance = new SingletonDCL();
                }
            }
        }
        return instance;
    }

    // ============================== 简单验证 ==============================
    public static void main(String[] args) throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        SingletonDCL[] results = new SingletonDCL[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> results[idx] = SingletonDCL.getInstance());
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // 校验所有线程拿到的都是同一个实例
        SingletonDCL first = results[0];
        for (int i = 1; i < threadCount; i++) {
            if (results[i] != first) {
                throw new AssertionError("单例失效！线程 " + i + " 拿到了不同的实例");
            }
        }
        System.out.println("单例校验通过，实例 hashCode = " + System.identityHashCode(first));
    }
}
