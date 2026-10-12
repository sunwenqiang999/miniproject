package org.example.singleton;

/**
 * 使用双重检查锁实现的线程安全懒加载单例示例。
 */
public class Main {
    private static volatile Object a;

    private Main() {
    }

    /**
     * 双重检查锁：仅在首次初始化时进入同步块。
     *
     * @return 全局唯一的对象实例
     */
    public static Object get() {
        if (a == null) {
            synchronized (Main.class) {
                if (a == null) {
                    a = new Object();
                }
            }
        }
        return a;
    }

    /**
     * 方法级同步：实现简单，但每次调用都会获取类锁。
     *
     * @return 全局唯一的对象实例
     */
    public static synchronized Object get2() {
        if (a == null) {
            a = new Object();
        }
        return a;
    }
}
