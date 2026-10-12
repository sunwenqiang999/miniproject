# JUC 并发编程面试知识点

## 一、线程基础

### 1.1 进程与线程的区别？

| 对比项 | 进程 | 线程 |
|--------|------|------|
| 资源开销 | 独立内存空间，切换开销大 | 共享进程内存，开销小 |
| 通信方式 | IPC（管道、消息队列、共享内存） | 共享内存直接读写（需同步） |
| 崩溃影响 | 一个进程崩溃不影响其他进程 | 线程崩溃（OOM 等）可能拖垮整个进程 |
| 调度 | 资源分配的最小单位 | CPU 调度的最小单位 |

**延伸：** Java 线程与操作系统线程是 **1:1 模型**（HotSpot 中 Java 线程直接映射为内核线程，KLT），所以线程创建/切换成本高 → 引出**线程池**。JDK 21 引入**虚拟线程（Virtual Thread，Loom 项目）**，M:N 模型，一个内核线程可承载成千上万个虚拟线程，彻底改写高并发 IO 编程。

### 1.2 创建线程的方式（高频）

| 方式 | 说明 | 能否返回值 |
|------|------|-----------|
| 继承 Thread | 重写 run() | 否 |
| 实现 Runnable | 解耦任务与线程 | 否 |
| 实现 Callable + FutureTask | `call()` 有返回值、可抛异常 | **是** |
| 线程池 | 统一管理线程（生产唯一推荐） | 是（submit 返回 Future） |

```java
// Callable 示例
FutureTask<Integer> task = new FutureTask<>(() -> 1 + 1);
new Thread(task).start();
Integer result = task.get();  // 阻塞等待结果
```

**注意：**
- 启动线程用 `start()` 不是 `run()`。`run()` 只是普通方法调用，不会开启新线程。
- 阿里规范：**生产环境禁止 `new Thread()`，必须用线程池**，便于资源管控和复用。

### 1.3 线程的生命周期（六种状态，必背）

```text
                        ┌─────────┐
                        │   NEW   │（new Thread() 后未 start）
                        └────┬────┘
                             │ start()
                             ▼
        ┌────────── wait() ────┴────────────────────────────┐
        │                    RUNNABLE                        │
        │      （可运行：就绪 Ready + 运行中 Running）          │
        └───┬──────────┬──────────────────┬─────────────┬───┘
            │wait()    │synchronized       │sleep()       │任务结束
            │join()    │未抢到锁           │wait(ms)      │
            ▼          ▼                  ▼             ▼
        ┌────────┐ ┌─────────┐     ┌────────────┐  ┌──────────┐
        │WAITING │ │ BLOCKED │     │TIMED_WAITING│  │TERMINATED│
        └────────┘ └─────────┘     └────────────┘  └──────────┘
```

**区分高频点：**
- **BLOCKED**：只发生在**等待进入 synchronized**（没抢到锁），被 jstack 打出来时状态为 BLOCKED，排查死锁看的就是它。
- **WAITING**：无限期等待，需其他线程显式唤醒（Object.wait()、Thread.join()、LockSupport.park()）。
- **TIMED_WAITING**：限期等待，超时自动醒（sleep(ms)、wait(ms)、join(ms)、parkNanos()）。

**wait() vs sleep()（必考）：**

| 对比项 | wait() | sleep() |
|--------|--------|---------|
| 所属类 | Object | Thread |
| 锁 | **释放锁**（必须在 synchronized 内调用，否则 IllegalMonitorStateException） | **不释放锁** |
| 唤醒 | notify/notifyAll 或中断 | 时间到自动醒 |
| 用途 | 线程间通信（等待-通知） | 暂停执行 |

### 1.4 如何正确停止线程？

**已废弃：** `stop()`（暴力停止，不释放锁，可能破坏数据一致性）、`suspend()/resume()`（易死锁）。

**正确姿势：中断（interrupt）机制 —— 协作式停止**

```java
Thread t = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {   // 检查中断标志
        try {
            Thread.sleep(1000);   // sleep/wait/join 遇中断抛 InterruptedException 并清除标志
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();  // 重新设置中断标志，向上传递
            break;
        }
    }
});
t.start();
t.interrupt();   // 只是设置标志位，线程自己决定何时停止
```

**要点：**
- `interrupt()` 只**设置标志位**，不真正中断；阻塞方法（sleep/wait）被中断会抛异常并**清除标志**，catch 后需重新 `interrupt()` 传递。
- `isInterrupted()` 不清除标志；`Thread.interrupted()` 会清除标志。
- 也可自定义 volatile 标志位做优雅停止。

---

## 二、JMM（Java 内存模型）——并发理论基石

### 2.1 JMM 是什么？解决什么问题？

**问题背景：** CPU 有多级缓存（L1/L2/L3），每个线程的工作内存中存了主内存变量的副本，导致：
1. **可见性问题**：线程 A 改了变量，线程 B 可能还读到旧值。
2. **有序性问题**：编译器/CPU 指令重排序，多线程下执行顺序与代码顺序不一致。
3. **原子性问题**：`i++` 不是原子操作（读-改-写三步）。

**JMM 抽象：**

```text
线程 A                    线程 B
┌─────────┐              ┌─────────┐
│工作内存  │              │工作内存  │
│ (副本)   │              │ (副本)   │
└────┬─────┘              └────┬────┘
     │  read/load        store/write │
     └──────────┬─────────────┘
                ▼
        ┌───────────────┐
        │    主内存      │（共享变量）
        └───────────────┘
```

JMM 定义了工作内存与主内存交互的 8 种操作（read/load/use/assign/store/write/lock/unlock）及规则，通过 **volatile、synchronized、final** 等关键字提供可见性、有序性、原子性保证。

### 2.2 happens-before 原则（先行发生）

**定义：** 如果操作 A happens-before 操作 B，则 A 的结果对 B 可见，且 A 按顺序排在 B 之前。JMM 保证的规则，程序员无需记忆所有细节，常用规则：

1. **程序次序规则**：单线程内，前面的代码 happens-before 后面的代码。
2. **锁规则**：解锁 happens-before 后续对同一锁的加锁。
3. **volatile 规则**：对 volatile 变量的写 happens-before 后续对该变量的读。
4. **线程启动规则**：Thread.start() happens-before 新线程的所有操作。
5. **线程终止规则**：线程内所有操作 happens-before 其他线程检测到该线程终止（join 返回）。
6. **传递性**：A hb B，B hb C，则 A hb C。

### 2.3 volatile 关键字（必考）

**两大语义：**
1. **保证可见性**：写 volatile 变量立即刷新到主内存；读时从主内存重新加载。
2. **禁止指令重排序**：通过内存屏障（Memory Barrier）实现。

**内存屏障（写读四种组合）：**

| 屏障 | 作用 | 插入位置 |
|------|------|----------|
| StoreStore | 禁止上面的普通写与 volatile 写重排 | volatile 写之前 |
| StoreLoad | 禁止上面的 volatile 写与下面可能的读/写重排 | volatile 写之后 |
| LoadLoad | 禁止下面的普通读与 volatile 读重排 | volatile 读之后 |
| LoadStore | 禁止下面的普通写与 volatile 读重排 | volatile 读之后 |

**不保证原子性（必考陷阱）：**

```java
private volatile int count = 0;
public void incr() {
    count++;   // 复合操作：读 count、+1、写 count，多线程并发会丢失更新
}
// 解决：AtomicInteger 或 synchronized
```

**volatile 经典应用：双重检查锁（DCL）单例（必考）**

```java
public class Singleton {
    private static volatile Singleton instance;   // volatile 必须！

    public static Singleton getInstance() {
        if (instance == null) {                    // 第一次检查（无锁，高性能）
            synchronized (Singleton.class) {
                if (instance == null) {            // 第二次检查
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**为什么 DCL 必须加 volatile？**
`new Singleton()` 不是原子操作，分三步：
1. 分配内存空间。
2. 初始化对象。
3. 将引用指向内存地址。

若无 volatile，指令重排可能使执行顺序变为 1→3→2：线程 A 执行到 3（引用已非 null 但对象未初始化），线程 B 第一次检查发现 `instance != null` 直接返回**半初始化对象** → NPE。volatile 禁止了 2、3 的重排序。

---

## 三、synchronized 原理（必考，要求源码级）

### 3.1 三种使用方式

```java
synchronized void method1() { }              // 实例方法：锁当前实例 this
static synchronized void method2() { }       // 静态方法：锁 Class 对象（全局锁）
void method3() {
    synchronized (lockObject) { }            // 代码块：锁指定对象
}
```

**高频辨析：** 锁实例方法 vs 锁静态方法互不互斥？—— **不互斥**！一个锁 this，一个锁 Class，是两把不同的锁。

### 3.2 底层实现：Monitor（监视器锁）

- 代码块：字节码中 `monitorenter`（进入）和 `monitorexit`（退出，正常/异常各一个）指令。
- 方法：方法 flags 中的 `ACC_SYNCHRONIZED` 标志，执行时隐式获取 Monitor。
- Monitor 由 **ObjectMonitor**（C++）实现，核心字段：`_owner`（持有线程）、`_EntryList`（阻塞等待队列）、`_WaitSet`（wait 等待集合）、`_recursions`（重入次数）。
- **可重入**：同一线程可重复获取同一把锁，计数 +1，退出 -1 直到 0 释放。

### 3.3 对象头与 Mark Word（必考）

```text
Java 对象内存布局（64 位 JVM）：
┌──────────────────────────────────────────────┐
│ Mark Word（8 字节）                            │ ← 锁信息存储地
│  hashCode / GC 分代年龄 / 锁标志位 / 偏向线程ID  │
├──────────────────────────────────────────────┤
│ Klass Pointer（类型指针，压缩后 4 字节）          │ ← 指向 Class 元数据
├──────────────────────────────────────────────┤
│ 实例数据（字段）                                  │
├──────────────────────────────────────────────┤
│ 对齐填充（8 字节倍数）                            │
└──────────────────────────────────────────────┘
```

**Mark Word 不同锁状态下的内容：**

| 锁状态 | 25bit | 31bit | 1bit 偏向 | 2bit 锁标志 |
|--------|-------|-------|-----------|-------------|
| 无锁 | unused | hashCode | 0 | 01 |
| 偏向锁 | 线程ID+epoch（54bit） | unused | 1 | 01 |
| 轻量级锁 | 指向栈中锁记录的指针（62bit） | — | — | 00 |
| 重量级锁 | 指向 ObjectMonitor 的指针（62bit） | — | — | 10 |
| GC 标记 | — | — | — | 11 |

### 3.4 锁升级过程（JDK 6 优化，必背）

```text
无锁 → 偏向锁 → 轻量级锁 → 重量级锁
（只能升级不能降级，方向固定）
```

| 阶段 | 触发条件 | 原理 | 特点 |
|------|----------|------|------|
| 无锁 | 无竞争 | — | — |
| **偏向锁** | 只有一个线程反复进入同步块 | Mark Word 记录线程 ID，该线程再次进入只需比较 ID，**无 CAS** | 几乎零开销；其他线程出现时撤销（需等到全局安全点） |
| **轻量级锁** | 第二个线程来竞争（交替执行） | 在当前线程栈帧建 Lock Record，**CAS** 把 Mark Word 指向它；失败则自旋重试（默认 10 次或自适应自旋） | 适合**短临界区、低竞争**；自旋不阻塞（不切换内核态） |
| **重量级锁** | 自旋失败/竞争加剧 | Mark Word 指向 ObjectMonitor，未抢到锁的线程进入 `_EntryList` **阻塞**（操作系统互斥量 mutex） | 涉及用户态↔内核态切换，开销大 |

**为什么锁升级是单向的？** 简化状态机实现，避免抖动。一旦升级到重量级说明竞争激烈，降级回轻量级收益不大。

**JDK 15+：** 偏向锁被标记为废弃（JDK 18 默认禁用），因为维护成本高而现代应用多为多线程场景，收益低。

**其他锁优化（JIT 层面）：**
- **锁消除**：逃逸分析发现锁对象不会逃出线程（如方法内 new 的对象、局部 StringBuffer），直接去掉锁。
- **锁粗化**：连续多次加锁同一对象（如循环内 append），合并为一个大锁。

### 3.5 synchronized vs ReentrantLock（必考对比）

| 对比项 | synchronized | ReentrantLock |
|--------|--------------|---------------|
| 实现层 | JVM 内置（Monitor） | JDK 层面（AQS） |
| 锁获取 | 自动获取/释放 | 手动 lock/unlock（必须 finally 释放） |
| 可中断 | 不可中断 | `lockInterruptibly()` 可响应中断 |
| 超时 | 不支持 | `tryLock(timeout)` 支持 |
| 公平性 | 只支持非公平 | 可选公平/非公平 |
| 条件队列 | 一个（wait/notify） | 多个 Condition（精确唤醒） |
| 性能 | JDK 6 优化后低竞争下相当，高竞争略优 | 灵活，高竞争下曾更优 |

**选择建议：** 普通场景用 synchronized（简单、JVM 优化）；需要可中断、超时、公平锁、多条件队列时用 ReentrantLock。

---

## 四、CAS 与原子类

### 4.1 CAS 是什么？

**Compare And Swap（比较并交换）**：一条 CPU 原子指令（x86: `cmpxchg`），语义：

```java
// 伪代码：内存值 V、预期值 A、新值 B
if (V == A) { V = B; return true; } else { return false; }
```

**应用：** AtomicInteger 的 incrementAndGet：

```java
public final int incrementAndGet() {
    return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
}
// getAndAddInt 内部：自旋 + CAS
do {
    varInt = getIntVolatile(o, offset);
} while (!compareAndSwapInt(o, offset, varInt, varInt + delta));
```

### 4.2 CAS 的三大问题

| 问题 | 说明 | 解决方案 |
|------|------|----------|
| **ABA 问题** | 值 A→B→A，CAS 检查时以为没变过，但实际中间被改过 | `AtomicStampedReference`（版本号戳）/`AtomicMarkableReference`（布尔标记） |
| 自旋开销 | 竞争激烈时大量自旋空转，CPU 飙升 | LongAdder 分散热点；JVM 可配置自旋 |
| 单变量局限 | 只能保证单个变量的原子性 | `AtomicReference` 引用原子更新；多个变量封装成对象 |

**ABA 经典场景：** 链表的 CAS 删除操作中，节点被删除又插入相同地址的新节点（内存复用），CAS 误判。版本号方案：`AtomicStampedReference` 每次修改版本号 +1，比较时同时检查值和版本号。

### 4.3 原子类家族（java.util.concurrent.atomic）

| 类别 | 类 |
|------|-----|
| 基本类型 | AtomicInteger、AtomicLong、AtomicBoolean |
| 数组 | AtomicIntegerArray、AtomicLongArray |
| 引用 | AtomicReference、AtomicStampedReference（ABA）、AtomicMarkableReference |
| 字段更新器 | AtomicIntegerFieldUpdater、AtomicReferenceFieldUpdater |
| 高性能累加器 | **LongAdder**、DoubleAdder |

### 4.4 LongAdder 为什么比 AtomicLong 快？（高频）

**AtomicLong 的瓶颈：** 所有线程 CAS 竞争同一个变量 value，高并发下大量失败自旋。

**LongAdder 原理（热点分散）：**

```text
        线程1    线程2    线程3    线程4
          │       │       │       │
          ▼       ▼       ▼       ▼
      ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
      │Cell0│ │Cell1│ │Cell2│ │Cell3│     Cell[]（初始 2，扩容 2 倍，≤ CPU 核数）
      │ = 3 │ │ = 5 │ │ = 2 │ │ = 4 │
      └─────┘ └─────┘ └─────┘ └─────┘
                  +
              base = 1
                  │
                  ▼
            sum() = 3+5+2+4+1 = 15
```

- 低竞争：直接 CAS 累加 `base`。
- 高竞争：线程按 hash 分散到不同的 `Cell` 上 CAS 累加（类似 ConcurrentHashMap 的 CounterCell，异曲同工）。
- `sum()` = base + 各 Cell 之和（**弱一致性**，不加锁，并发累加时结果可能不准）。
- Cell 用 `@sun.misc.Contended` 注解防止**伪共享（False Sharing）**。

**伪共享：** CPU 缓存以缓存行（64 字节）为单位加载，两个不相干变量在同一缓存行，一个变量被修改会使整个缓存行失效，导致另一个变量的读取也要重新加载。`@Contended` 通过前后填充 128 字节让 Cell 独占缓存行。

---

## 五、AQS（抽象队列同步器）—— JUC 的灵魂（必考）

### 5.1 AQS 是什么？

`AbstractQueuedSynchronizer`：一个构建锁和同步器的**框架基类**。ReentrantLock、Semaphore、CountDownLatch、ReentrantReadWriteLock、ThreadPoolExecutor.Worker 都基于它。

**核心思想：** 用一个 **volatile int state** 表示同步状态 + 一个 **FIFO 双向链表（CLH 变体）** 管理排队线程。

```text
                AQS
┌─────────────────────────────────────┐
│  volatile int state（同步状态）        │
│  ReentrantLock: 0=未锁, n=重入次数     │
│  Semaphore: 剩余许可数                │
│  CountDownLatch: 剩余计数             │
├─────────────────────────────────────┤
│  CLH 同步队列（FIFO 双向链表）          │
│  Head(哨兵) ←→ Node ←→ Node ←→ Tail  │
│   （已获取）      (等待中)   (等待中)    │
├─────────────────────────────────────┤
│  ConditionObject 条件队列（可多个）     │
│  await() 进入，signal() 转回同步队列    │
└─────────────────────────────────────┘
```

### 5.2 独占锁获取流程（以 ReentrantLock 非公平锁为例）

```text
1. lock() → CAS 尝试把 state 从 0 改 1（非公平：先抢一次）
2. 成功 → 设置 exclusiveOwnerThread = 当前线程，返回
3. 失败 → acquire(1)：
   a. tryAcquire() 再试一次（含重入判断：owner == 自己 → state++）
   b. 失败 → 封装 Node（EXCLUSIVE 模式）加入 CLH 队尾
   c. 自旋检查：前驱是 head 则再试 tryAcquire；否则 park() 阻塞
   d. 被前驱节点 release 时 unpark 唤醒 → 继续自旋抢锁
4. unlock() → tryRelease()：state-1，减到 0 时清空 owner，唤醒 head 后继
```

**关键点：**
- **非公平锁**：新线程进来先 CAS 抢一次，抢不到才排队（吞吐高，可能饥饿）。
- **公平锁**：先检查队列是否有等待者（`hasQueuedPredecessors()`），有则直接排队。
- 唤醒是**从 head 向后**找最近的正常节点（跳过取消节点）。

### 5.3 模板方法模式

子类只需实现（保护方法，AQS 提供骨架）：

| 方法 | 用途 | 实现类示例 |
|------|------|------------|
| tryAcquire / tryRelease | 独占模式获取/释放 | ReentrantLock.Sync |
| tryAcquireShared / tryReleaseShared | 共享模式获取/释放 | Semaphore、CountDownLatch |
| isHeldExclusively | 是否独占持有 | Condition 支持需要 |

### 5.4 基于 AQS 的同步工具对比（必考）

| 工具 | state 语义 | 模式 | 典型场景 |
|------|-----------|------|----------|
| ReentrantLock | 重入次数 | 独占 | 互斥锁 |
| Semaphore | 剩余许可数 | 共享 | 限流（控制同时访问数量，如数据库连接数） |
| CountDownLatch | 剩余计数（不可重置） | 共享 | 等待 N 个任务完成（主线程 await，子任务 countDown） |
| ReentrantReadWriteLock | 高16位读锁、低16位写锁 | 独占+共享 | 读写分离锁 |

### 5.5 CountDownLatch vs CyclicBarrier vs Semaphore（必考）

| 对比项 | CountDownLatch | CyclicBarrier | Semaphore |
|--------|----------------|---------------|-----------|
| 作用 | 一个/多个线程**等待**另外 N 个线程完成 | N 个线程**互相等待**到达屏障点再一起继续 | 控制并发访问的资源数量 |
| 重置 | **一次性**，计数到 0 不能复用 | **可循环**使用（reset） | 许可可释放复用 |
| 底层 | AQS 共享锁 | ReentrantLock + Condition（没用 AQS 的 state 计数） | AQS 共享锁 |
| 回调 | 无 | 支持 barrierAction（最后一个到达的线程执行） | 无 |
| 场景 | 启动前等待初始化完成、并发测试齐发 | 多线程分阶段协同（并行计算合并） | 限流、连接池 |

---

## 六、ReentrantReadWriteLock 与 StampedLock

### 6.1 读写锁设计

- **读锁（共享锁）**：多个线程可同时持有；**写锁（独占锁）**：独占，与读写都互斥。
- state 高 16 位记录读锁数量，低 16 位记录写锁重入次数。
- 支持**锁降级**：写锁 → 读锁（持有写锁时获取读锁再释放写锁）；不支持锁升级（读→写会死锁）。

**锁降级代码模板：**

```java
rwLock.writeLock().lock();
// 修改数据
rwLock.readLock().lock();      // 先获取读锁
rwLock.writeLock().unlock();   // 再释放写锁 —— 完成降级
// 现在持有读锁，保证修改对其他线程可见
rwLock.readLock().unlock();
```

### 6.2 StampedLock（JDK 8）

在读多写少且读操作耗时的场景优化 ReentrantReadWriteLock：
- **乐观读（tryOptimisticRead）**：不加锁，读前拿 stamp，读完后 `validate(stamp)` 校验期间有无写锁，被写过则降级为悲观读锁重读。
- 缺点：不可重入、不支持 Condition、使用复杂易错。

---

## 七、ThreadLocal（必考）

### 7.1 原理：不是共享变量的副本，而是线程私有

```text
Thread（线程）
  └─ threadLocals: ThreadLocalMap（每个线程一份）
        └─ Entry[]（数组，开放寻址法解决冲突）
              └─ Entry extends WeakReference<ThreadLocal<?>>
                    ├─ key: ThreadLocal 对象（弱引用）
                    └─ value: 实际存储的值（强引用）
```

- 每个 Thread 内部持有一个 `ThreadLocalMap`。
- ThreadLocal 本身不存值，只是 **key**；真正的 value 存在线程自己的 Map 里。
- 冲突解决：**开放寻址法**（线性探测，而非 HashMap 的链地址法）。

```java
public void set(T value) {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);   // 拿当前线程的 map
    if (map != null) map.set(this, value);   // this（ThreadLocal）做 key
    else createMap(t, value);
}
```

### 7.2 内存泄漏问题（必考）

**为什么 key 用弱引用？** 若 ThreadLocal 对象在外部不再被引用（`tl = null`），弱引用 key 会在 GC 时被回收，Entry 变为 `key=null` 的"脏 Entry"，value 理论上可被后续操作清理。

**为什么还会内存泄漏？** value 是**强引用**！即使 key 被回收，value 仍通过 `线程 → ThreadLocalMap → Entry[] → value` 的强引用链存活。如果线程是**线程池中的长生命周期线程**（核心线程不销毁），value 永远无法回收 → 泄漏 + 脏数据。

**正确姿势（必背）：**

```java
private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

try {
    CONTEXT.set(context);
    // 业务逻辑
} finally {
    CONTEXT.remove();   // 必须！尤其是线程池场景
}
```

**进阶追问：**
- `set()`/`get()`/`remove()` 时会**启发式清理** key 为 null 的脏 Entry（expungeStaleEntry），但这是被动兜底，不能替代手动 remove。
- **InheritableThreadLocal**：子线程创建时拷贝父线程的 ThreadLocal 值（仅创建时刻快照）。线程池场景失效（线程复用），用阿里的 **TransmittableThreadLocal（TTL）** 解决——通过包装 Runnable 在任务提交/执行时传递上下文。
- 典型应用：SimpleDateFormat 线程安全封装、Spring 事务上下文（TransactionSynchronizationManager）、MDC 日志 traceId、数据库连接绑定。

---

## 八、线程池（必考中的必考）

### 8.1 为什么用线程池？

1. **降低资源消耗**：复用线程，避免频繁创建销毁。
2. **提高响应速度**：任务来了直接执行，不用等建线程。
3. **提高线程可管理性**：统一分配、监控、调优。

### 8.2 七大核心参数（必背）

```java
new ThreadPoolExecutor(
    int corePoolSize,        // ① 核心线程数：常驻，默认不超时回收
    int maximumPoolSize,     // ② 最大线程数：核心 + 临时
    long keepAliveTime,      // ③ 临时线程空闲存活时间
    TimeUnit unit,           // ④ 时间单位
    BlockingQueue<Runnable> workQueue,   // ⑤ 任务队列
    ThreadFactory threadFactory,         // ⑥ 线程工厂（自定义线程名、守护状态）
    RejectedExecutionHandler handler     // ⑦ 拒绝策略
);
```

### 8.3 任务执行流程（必背，画图题）

```text
提交任务
   │
   ▼
┌──────────────────┐   否
│ 当前线程数 < core？│──────→ 加入任务队列 workQueue
└──────┬───────────┘          │
       │是                     ▼
       ▼                ┌───────────────┐   否
  创建核心线程执行         │ 队列是否已满？  │──────→ 创建非核心线程执行
                        └───────┬───────┘        │
                                │是               │（线程数 < max）
                                ▼                 ▼
                        ┌───────────────┐    （线程数 = max 时）
                        │ 执行拒绝策略    │←──── 队列满且线程数=max
                        └───────────────┘
```

**口诀：先核心，再队列，后非核心，都不行则拒绝。**

**高频陷阱：** 线程数达到 core 后**优先入队**而不是创建非核心线程——这是与直觉相反的设计，目的是先靠队列缓冲。

### 8.4 四种拒绝策略（必背）

| 策略 | 行为 | 适用 |
|------|------|------|
| AbortPolicy（默认） | 抛 RejectedExecutionException | 任务不能丢，需要感知 |
| CallerRunsPolicy | **调用者线程自己执行**（如 Tomcat 线程） | 提供负反馈减缓提交速度，上游降级 |
| DiscardPolicy | 静默丢弃 | 可容忍丢失（日志、监控） |
| DiscardOldestPolicy | 丢弃队列中最老的任务，重试当前任务 | 消息时效性强的场景 |

生产上通常**自定义拒绝策略**：落库/MQ 持久化后异步重试 + 告警。

### 8.5 Executors 内置线程池的坑（阿里规范明令禁止）

| 工厂方法 | 参数特征 | 风险 |
|----------|----------|------|
| newFixedThreadPool | core=max=n，**LinkedBlockingQueue 无界队列** | 任务堆积 → **OOM** |
| newSingleThreadExecutor | core=max=1，**无界队列** | 同上 OOM |
| newCachedThreadPool | core=0，max=**Integer.MAX_VALUE**，SynchronousQueue | 无限创建线程 → **OOM** |
| newScheduledThreadPool | max=Integer.MAX_VALUE，DelayedWorkQueue | 同上 |

**阿里规范：线程池不允许使用 Executors 创建，必须通过 ThreadPoolExecutor 手动创建**，让开发者明确每个参数的含义与风险。

**生产推荐：**

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    8, 16, 60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(2000),              // 有界队列！
    new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build(),  // 有意义的线程名，便于 jstack 排查
    new ThreadPoolExecutor.CallerRunsPolicy()     // 或自定义策略
);
// 允许核心线程超时（任务波谷时释放资源）
executor.allowCoreThreadTimeOut(true);
```

### 8.6 线程池大小怎么设置？（高频）

| 任务类型 | 公式 | 说明 |
|----------|------|------|
| CPU 密集型 | N_cpu + 1 | 线程多了反而频繁切换 |
| IO 密集型 | N_cpu × (1 + 等待时间/计算时间) ≈ 2 × N_cpu 起步 | 线程等待 IO 时 CPU 可调度其他线程 |

- N_cpu = `Runtime.getRuntime().availableProcessors()`。
- 实际生产：**压测 + 监控动态调整**（美团动态线程池 DynamicTp，接入配置中心实时调参 + 告警）。
- 注意：**同一应用内多个线程池要隔离**（如订单池、通知池分开），避免一个业务打满线程池拖垮全局；也避免与 `parallelStream` 共用 ForkJoinPool.commonPool。

### 8.7 线程池状态（5 种）

```text
RUNNING → SHUTDOWN → STOP → TIDYING → TERMINATED
  │         │          │
  │    shutdown()  shutdownNow()
  │    拒新任务    拒新任务+中断worker
  │    消化队列    丢弃队列任务
```

### 8.8 submit() vs execute()

| 对比项 | execute(Runnable) | submit(Callable/Runnable) |
|--------|-------------------|---------------------------|
| 返回值 | void | Future |
| 异常 | 直接抛出，线程可能终止 | **被吞掉**，需 `future.get()` 时才抛 ExecutionException |

**坑：** submit 后不 get()，任务异常就静默消失了。

---

## 九、并发容器与工具

### 9.1 CompletableFuture（JDK 8 异步编排）

```java
// 串行 + 并行 + 聚合
CompletableFuture<User> userFuture = CompletableFuture.supplyAsync(() -> queryUser(id), executor);
CompletableFuture<List<Order>> orderFuture = CompletableFuture.supplyAsync(() -> queryOrders(id), executor);

CompletableFuture<Void> all = CompletableFuture.allOf(userFuture, orderFuture);  // 全部完成
CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2);                  // 任一完成

userFuture
    .thenApply(user -> buildDTO(user))        // 转换
    .thenCombine(orderFuture, this::merge)     // 两个结果合并
    .thenAccept(dto -> send(dto))              // 消费
    .exceptionally(ex -> { log.error("", ex); return null; })  // 异常兜底
    .orTimeout(1, TimeUnit.SECONDS);           // JDK9 超时
```

**要点：**
- **务必传自定义 executor**！默认用 ForkJoinPool.commonPool，与 parallelStream 共用，IO 任务会把公共池打满。
- thenApply（转换）/thenAccept（消费）/thenRun（不关心结果）；带 Async 后缀的可换线程池执行。
- 典型场景：聚合查询（详情页并行查用户、订单、优惠券，总耗时从串行 300ms 降到 max(100ms)）。

### 9.2 Fork/Join 框架

分治思想：大任务递归拆分（fork）成子任务并行执行，最后合并（join）结果。核心是**工作窃取（Work Stealing）**：每个线程有双端队列，空闲线程从其他线程队列**尾部**偷任务，减少竞争。`parallelStream` 和 CompletableFuture 默认 executor 都基于它。

---

## 十、死锁与并发问题排查

### 10.1 死锁的四个必要条件（缺一不可）

1. **互斥**：资源同一时刻只能一个线程持有。
2. **占有且等待**：持有资源同时等待新资源。
3. **不可剥夺**：资源只能由持有者主动释放。
4. **循环等待**：A 等 B 的资源，B 等 A 的资源，形成环。

**破坏死锁：** 破环任一条件。最常用——**固定加锁顺序**（破坏循环等待）；tryLock 超时放弃（破坏占有且等待）。

### 10.2 手写死锁与排查

```java
// 经典死锁：加锁顺序不一致
// 线程1: lock A -> lock B
// 线程2: lock B -> lock A   ← 改成与线程1相同顺序即可解决
```

**线上排查步骤：**

```bash
1. top 找到高 CPU 进程
2. top -Hp <pid> 找到高 CPU 线程
3. printf '%x' <tid> 转十六进制
4. jstack <pid> | grep -A 20 <nid>   # 查看线程栈
5. jstack 输出中搜索 "Found one Java-level deadlock" 直接定位死锁
# 也可 jconsole / jvisualvm / Arthas（thread -b 一键找死锁）
```

### 10.3 活锁、饥饿、线程泄漏

- **活锁**：线程不断重试互相让步，都无法前进（解决：随机退避时间）。
- **饥饿**：低优先级线程长期得不到执行（解决：公平锁）。
- **线程泄漏**：线程池核心线程未关闭、忘记 shutdown → 线程数只增不减（jstack 可见大量相同栈的线程）。

---

## 十一、高频面试题速记

**Q1：volatile 能保证原子性吗？举例说明。**
> 不能。`volatile int i; i++` 是读-改-写三步复合操作，多线程并发会丢失更新。volatile 只保证可见性和有序性（通过内存屏障禁止重排、写后立即刷主存）。原子性需要 synchronized 或 AtomicInteger（CAS）。

**Q2：synchronized 锁升级过程？**
> 无锁 → 偏向锁（单线程重入，Mark Word 记录线程 ID，无 CAS）→ 轻量级锁（CAS 把 Mark Word 指向栈中锁记录，失败自旋）→ 重量级锁（ObjectMonitor，未抢到锁的线程阻塞在 EntryList，涉及内核态切换）。只能升级不能降级。

**Q3：AQS 的原理？**
> AQS 用 volatile int state 表示同步状态，配合 CAS 修改；获取失败的线程封装成 Node 进入 CLH 双向队列 park 阻塞；释放时唤醒 head 后继节点。子类通过 tryAcquire/tryRelease 等模板方法定义 state 语义，ReentrantLock（重入次数）、Semaphore（许可数）、CountDownLatch（倒数计数）都基于它。

**Q4：ThreadLocal 为什么会内存泄漏？怎么解决？**
> ThreadLocalMap 的 Entry 中 key 是弱引用（GC 可回收），value 是强引用。key 被回收后 value 仍通过线程强引用链存活，线程池场景线程长期不销毁导致 value 永久泄漏。解决：用完必须 `finally { remove(); }`。

**Q5：线程池核心参数和执行流程？**
> 7 参数：核心数、最大数、空闲存活、单位、队列、工厂、拒绝策略。流程：先核心线程 → 队列排队 → 非核心线程 → 拒绝策略。拒绝策略四种：抛异常（默认）、调用者执行、丢弃、丢弃最老。生产禁用 Executors（无界队列/无限线程 OOM），手动 new ThreadPoolExecutor + 有界队列 + 命名线程工厂。

**Q6：CountDownLatch 和 CyclicBarrier 区别？**
> CountDownLatch 是一方等待 N 个任务完成（倒计数一次性，AQS 共享锁）；CyclicBarrier 是 N 方互相等待到齐后一起继续（可循环使用，ReentrantLock+Condition）。

**Q7：CAS 的 ABA 问题怎么解决？**
> 值从 A→B→A 变化时 CAS 无法感知中间过程。用 AtomicStampedReference 加版本号（值+版本一起比较）解决。

**Q8：LongAdder 为什么高并发下优于 AtomicLong？**
> AtomicLong 所有线程竞争同一个变量的 CAS，高并发大量自旋失败；LongAdder 把热点分散到 base + Cell 数组，不同线程更新不同 Cell 降低竞争，sum() 汇总。Cell 用 @Contended 防伪共享。
