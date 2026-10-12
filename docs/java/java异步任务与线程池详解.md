# Callable / Runnable / Future / FutureTask / CompletableFuture 与线程池配合详解

## 一、为什么需要这些抽象？—— 一条演进主线

```text
Thread（直接继承，任务和线程耦合，无法复用线程）
   │  问题：每次 new Thread，创建销毁开销大，任务和线程机制混在一起
   ▼
Runnable（任务与线程解耦：定义"做什么"，Thread 负责"怎么跑"）
   │  问题：run() 没有返回值，也不能抛检查异常，拿不到执行结果
   ▼
Callable + Future（call() 有返回值可抛异常，Future 作为异步结果的"提货单"）
   │  问题：Callable 不能直接给 Thread 执行，需要一座桥
   ▼
FutureTask（同时实现 Runnable 和 Future：既是任务又是结果凭证）
   │  问题：Future.get() 只能阻塞傻等，多个任务之间无法编排组合
   ▼
CompletableFuture（JDK8：函数式异步编排，thenApply/thenCombine/allOf）
```

**一句话记住各自角色：**

| 接口/类 | 角色 | 一句话定位 |
|---------|------|-----------|
| Runnable | 任务 | 无返回值的"活" |
| Callable | 任务 | 有返回值、可抛异常的"活" |
| Future | 结果凭证 | 异步任务的"提货单"，凭它取结果/取消任务 |
| FutureTask | 任务+凭证 | Runnable 和 Future 的双实现，连接两者的桥 |
| CompletableFuture | 编排器 | 提货单 + 流水线，任务完成后自动触发下一步 |
| ExecutorService | 执行者 | 线程池，负责真正干活 |

---

## 二、Runnable：最基础的任务抽象

```java
@FunctionalInterface
public interface Runnable {
    void run();
}
```

**两个硬限制：**
1. **没有返回值**（void）。
2. **不能抛出检查异常**（签名没有 throws，只能 try-catch 包 RuntimeException）。

**两种使用方式：**

```java
// 方式1：交给 Thread 执行
new Thread(() -> System.out.println("run")).start();

// 方式2：交给线程池执行（生产推荐）
executor.execute(() -> System.out.println("run"));
```

**与 Thread 的关系（高频）：**
> Thread 实现了 Runnable。`new Thread(runnable)` 是把 runnable 存到 Thread 的 target 字段，线程启动后 native 层回调 `Thread.run()`，`Thread.run()` 内部再调 `target.run()`。所以**继承 Thread 重写 run 与传入 Runnable 是二选一**——若继承了 Thread 又传了 Runnable，实际执行的是 Thread 重写的 run（target 被忽略），这是经典陷阱题。

---

## 三、Callable：能返回值、能抛异常的任务

```java
@FunctionalInterface
public interface Callable<V> {
    V call() throws Exception;
}
```

### 3.1 Runnable vs Callable（必考对比）

| 对比项 | Runnable | Callable |
|--------|----------|----------|
| 方法 | `void run()` | `V call()` |
| 返回值 | 无 | 有（泛型 V） |
| 异常 | 不能抛检查异常 | 可 `throws Exception` |
| 引入版本 | JDK 1.0 | JDK 1.5（随 JUC 一起） |
| 执行方式 | Thread 直接执行 / execute() | **必须**通过线程池 submit() 或包装成 FutureTask |
| 结果获取 | 无 | Future.get() |

### 3.2 Callable 不能直接被 Thread 执行

```java
Callable<Integer> task = () -> 1 + 1;
// new Thread(task);   ❌ 编译错误：Thread 构造器只认 Runnable

// 正确姿势 1：FutureTask 桥接
FutureTask<Integer> ft = new FutureTask<>(task);
new Thread(ft).start();
Integer r = ft.get();

// 正确姿势 2：线程池 submit（生产推荐）
Future<Integer> future = executor.submit(task);
Integer r2 = future.get();
```

**为什么 Thread 不支持 Callable？** Thread 诞生于 JDK 1.0，Callable 是 JDK 1.5 才加入的，为了不破坏既有 API，选择用 FutureTask 做适配（适配器模式），而不是修改 Thread。

---

## 四、Future：异步结果的"提货单"

```java
public interface Future<V> {
    boolean cancel(boolean mayInterruptIfRunning);  // 尝试取消
    boolean isCancelled();                          // 是否已取消
    boolean isDone();                               // 是否完成（正常/异常/取消都算）
    V get() throws InterruptedException, ExecutionException;         // 阻塞等结果
    V get(long timeout, TimeUnit unit)              // 限时阻塞，超时抛 TimeoutException
        throws InterruptedException, ExecutionException, TimeoutException;
}
```

### 4.1 核心方法详解

**get()：阻塞等待**

```text
提交任务                       get() 调用
   │                              │
   ▼                              ▼
┌─────────┐   任务未完成    ┌──────────┐
│ 执行中   │ ───────────→ │ 当前线程  │
│         │              │ 阻塞等待   │
└────┬────┘              └────┬─────┘
     │ 完成                     │ 被唤醒
     ▼                         ▼
  结果就绪 ←──────────── 返回结果/抛异常
```

- 任务正常完成 → 返回结果。
- 任务抛异常 → `get()` 抛 **ExecutionException**（包装原始异常）。
- 任务被取消 → `get()` 抛 **CancellationException**。
- 等待中被中断 → 抛 InterruptedException。

**get(timeout)：限时等待** —— 生产**必用**，防止下游任务卡死导致线程永久阻塞：

```java
try {
    Result r = future.get(500, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);   // 超时后取消任务，释放资源
    // 降级逻辑
}
```

**cancel(mayInterruptIfRunning) 的两档语义（高频）：**

| 参数 | 任务还没开始 | 任务正在执行 |
|------|-------------|-------------|
| false | 直接取消（不再执行） | **不打扰**，让它跑完，但 get() 抛 CancellationException |
| true | 直接取消 | 给执行线程发 **interrupt() 中断信号**（协作式，任务代码需响应中断才真正停止） |

> 注意：`cancel(true)` 只是发中断信号，如果任务代码不检查中断标志、也不调用可中断的阻塞方法，任务照样跑完。取消的本质是协作式的，与 `Thread.stop()` 的暴力强杀不同。

### 4.2 Future 的局限性（引出 CompletableFuture 的必考点）

1. **只能被动等待**：想知道任务完成只能 get() 阻塞或 isDone() 轮询，无法注册"完成后自动回调"。
2. **无法手动完成**：不能主动 set 一个结果（FutureTask 的 set 是 protected 的）。
3. **无法编排组合**：任务 A 的结果传给任务 B、多个任务全部完成后再聚合，Future 都做不到，只能写大量 get() 阻塞代码，退化成串行。
4. **异常处理链式化缺失**：异常只能在 get() 时捕获，不能在任务链中统一兜底。

---

## 五、FutureTask：连接任务与结果的桥（源码级）

```java
public class FutureTask<V> implements RunnableFuture<V> { }
public interface RunnableFuture<V> extends Runnable, Future<V> { }
```

```text
            Runnable                Future
              │                       │
              └────────┬──────────────┘
                       ▼
               RunnableFuture
                       ▼
                 FutureTask  ── 内部包装一个 Callable
              （既是任务又能取结果）
   ┌──────────────────┴──────────────────┐
   ▼                                      ▼
new Thread(futureTask).start()     executor.submit(callable)
（Thread 当 Runnable 跑）           （池内线程跑，返回它当 Future 用）
```

### 5.1 状态机（7 种状态，必考）

```java
private volatile int state;
private static final int NEW          = 0;   // 初始
private static final int COMPLETING   = 1;   // 完成中（结果正在赋值，瞬态）
private static final int NORMAL       = 2;   // 正常完成
private static final int EXCEPTIONAL  = 3;   // 异常完成
private static final int CANCELLED    = 4;   // 已取消（未中断）
private static final int INTERRUPTING = 5;   // 中断中（瞬态）
private static final int INTERRUPTED  = 6;   // 已中断
```

```text
                        ┌──> COMPLETING ──> NORMAL        （call() 正常返回）
NEW ────────────────────┼──> COMPLETING ──> EXCEPTIONAL   （call() 抛异常）
  │                     ├──> CANCELLED                    （cancel(false)，未执行）
  │                     └──> INTERRUPTING ──> INTERRUPTED （cancel(true)）
  │
  └─ state 从 NEW 出发的 4 条路径，终态后不可逆
```

**关键设计：** COMPLETING 和 INTERRUPTING 是**瞬态**（中间态），`get()` 在 COMPLETING 时会短暂自旋等待结果赋值完成，保证可见性。

### 5.2 run() 源码解析（任务如何执行并保存结果）

```java
public void run() {
    // ① 只有 NEW 状态能执行；CAS 设置 runner 线程，防止重复执行
    if (state != NEW ||
        !RUNNER.compareAndSet(this, null, Thread.currentThread()))
        return;
    try {
        Callable<V> c = callable;
        if (c != null && state == NEW) {
            V result;
            boolean ran;
            try {
                result = c.call();      // ② 真正执行用户的任务
                ran = true;
            } catch (Throwable ex) {
                result = null;
                ran = false;
                setException(ex);       // ③ 异常：存 outcome，state → EXCEPTIONAL
            }
            if (ran)
                set(result);            // ④ 正常：存 outcome，state → NORMAL
        }
    } finally {
        runner = null;
        int s = state;
        if (s >= INTERRUPTING)
            handlePossibleCancellationInterrupt(s);  // ⑤ 配合 cancel(true) 的中断
    }
}
```

**set(result) 内部：**

```java
protected void set(V v) {
    if (STATE.compareAndSet(this, NEW, COMPLETING)) {  // CAS 进入"完成中"
        outcome = v;                                    // 赋值结果
        STATE.setRelease(this, NORMAL);                 // 发布终态（保证 outcome 可见）
        finishCompletion();                             // 唤醒所有 get() 等待的线程
    }
}
```

**这就是"submit 会吞异常"的根源：** `call()` 抛出的异常被 `catch (Throwable)` 捕获后存进 `outcome`，**不会往外抛**，执行线程不受影响继续复用；只有 `get()` 时才会以 `ExecutionException` 重新抛出。

### 5.3 get() 源码解析（如何阻塞等待）

```java
public V get() throws InterruptedException, ExecutionException {
    int s = state;
    if (s <= COMPLETING)              // 未完成
        s = awaitDone(false, 0L);     // 进入等待
    return report(s);
}

private int awaitDone(boolean timed, long nanos) {
    // 自旋 + LockSupport.park 阻塞
    // 等待节点组成 Treiber stack（无锁单向栈，CAS 压栈）
    for (;;) {
        int s = state;
        if (s > COMPLETING) return s;          // 已完成，返回状态
        if (s == COMPLETING) Thread.yield();   // 结果赋值中，让出 CPU 稍等
        else LockSupport.parkNanos/park();     // 阻塞等待 finishCompletion 唤醒
    }
}
```

### 5.4 FutureTask 两种使用方式对比

```java
// 方式 1：手动 new Thread（了解原理即可，生产不用）
FutureTask<Integer> ft = new FutureTask<>(() -> expensiveCall());
new Thread(ft, "task-thread").start();
Integer r = ft.get();

// 方式 2：线程池 submit（生产标准用法）
// AbstractExecutorService.submit 源码：
public <T> Future<T> submit(Callable<T> task) {
    RunnableFuture<T> ftask = newTaskFor(task);   // 包装成 FutureTask
    execute(ftask);                                // 走线程池标准流程
    return ftask;                                  // 返回给你当提货单
}
```

**要点：** `submit()` 内部就是 `newTaskFor()` 把 Callable 包成 FutureTask 再 `execute()`。所以**线程池里跑的永远是 Runnable（FutureTask 是 Runnable）**，Callable 只是被包装的一层皮。

---

## 六、ExecutorService：execute 与 submit（必考）

### 6.1 方法族谱

```java
// Executor 接口（顶层）
void execute(Runnable command);                          // 无返回、异常直接抛

// ExecutorService 接口
Future<?> submit(Runnable task);                         // 返回 Future，get() 为 null
<T> Future<T> submit(Runnable task, T result);           // 带预置结果，get() 返回 result
<T> Future<T> submit(Callable<T> task);                  // get() 返回 call() 的结果
<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks);          // 批量提交，全部完成才返回
<T> T invokeAny(Collection<? extends Callable<T>> tasks);                        // 任一成功即返回，其余取消
```

### 6.2 execute vs submit（必考对比）

| 对比项 | execute(Runnable) | submit(Callable/Runnable) |
|--------|-------------------|---------------------------|
| 返回值 | void | Future（可 get 结果、可 cancel） |
| 参数 | 只能 Runnable | Runnable 或 Callable |
| **异常行为** | 任务异常**直接抛出**，线程打印堆栈后**终止**，线程池**补充新线程** | 异常被 FutureTask **捕获封存**，线程**不终止**，不 get() 则**永远静默** |
| 底层 | 直接调度 | 包装 FutureTask 后走 execute |

**execute 的异常为什么不会搞垮线程池？** 任务抛异常后 Worker 线程退出，ThreadPoolExecutor 会在 `processWorkerExit` 中补一个新线程，保证池内线程数稳定。但任务本身中断了。

**submit 吞异常的生产事故案例：**

```java
// ❌ 错误示范：任务里 NPE 了，但日志干干净净，业务无声失败
executor.submit(() -> {
    userService.doSomething();   // 抛 NullPointerException
    return true;
});
// 没人 get()，异常石沉大海

// ✅ 正确姿势 1：拿 Future 并 get（或至少异步检查）
Future<Boolean> f = executor.submit(() -> userService.doSomething());
try { f.get(); } catch (ExecutionException e) { log.error("任务失败", e.getCause()); }

// ✅ 正确姿势 2：任务内部自己兜底（推荐）
executor.submit(() -> {
    try { return userService.doSomething(); }
    catch (Exception e) { log.error("任务失败", e); throw e; }
});
```

### 6.3 submit(Runnable, result) 的妙用

```java
AtomicInteger counter = new AtomicInteger();
Future<AtomicInteger> f = executor.submit(() -> counter.incrementAndGet(), counter);
// Runnable 无返回值，但可以预传一个"结果载体"，任务内修改它，get() 拿到同一个对象
```

---

## 七、Future 与各种线程池的配合

### 7.1 ThreadPoolExecutor（标准池）

```text
submit(callable)
   │
   ▼
newTaskFor() 包装成 FutureTask
   │
   ▼
execute(ftask) ──→ 核心流程（核心线程→队列→非核心→拒绝）
   │
   ▼
Worker 线程执行 ftask.run()
   ├─ call() 正常返回 → set(result) → 唤醒 get()
   └─ call() 抛异常  → setException(ex) → get() 时抛 ExecutionException
```

**拒绝策略对 submit 的影响：** 队列满且线程数达 max 时触发拒绝策略，**FutureTask 根本不会被提交执行**。此时对它的 `get()` 会一直阻塞吗？——不会，AbortPolicy 直接抛 RejectedExecutionException 给**提交线程**；但若用 DiscardPolicy 静默丢弃，Future 将**永远卡在 NEW 状态**，`get()` 永久阻塞（除非带超时）。**生产必须 get(timeout) 兜底。**

### 7.2 ScheduledThreadPoolExecutor（定时池）

```java
ScheduledFuture<?> schedule(Runnable cmd, long delay, TimeUnit unit);                 // 延迟执行一次
ScheduledFuture<?> scheduleAtFixedRate(Runnable cmd, long init, long period, unit);   // 固定速率（以上次开始时间为基准）
ScheduledFuture<?> scheduleWithFixedDelay(Runnable cmd, long init, long delay, unit); // 固定延迟（以上次结束时间为基准）
```

**内部实现：** 返回的是 `ScheduledFutureTask`（继承 FutureTask，实现 Delayed 接口），按触发时间在 `DelayedWorkQueue`（优先级堆队列）中排序，池内线程取最早到期的任务执行。

**高频陷阱：定时任务异常会导致后续不再执行！**

```java
// ❌ 任务抛 RuntimeException → 本次执行中断，且后续周期执行全部终止（ScheduledFutureTask 设计如此），异常还被吞
scheduler.scheduleAtFixedRate(() -> {
    int i = 1 / 0;   // 第二次起永远不会再跑
}, 0, 1, TimeUnit.SECONDS);

// ✅ 必须 try-catch 包住整个任务体
scheduler.scheduleAtFixedRate(() -> {
    try { business(); } catch (Throwable t) { log.error("周期任务异常", t); }
}, 0, 1, TimeUnit.SECONDS);
```

### 7.3 invokeAll / invokeAny：批量任务

```java
// invokeAll：全部完成才返回（主线程阻塞到最后一个完成）
List<Callable<Price>> tasks = shops.stream().map(s -> (Callable<Price>) () -> queryPrice(s)).toList();
List<Future<Price>> futures = executor.invokeAll(tasks, 2, TimeUnit.SECONDS);  // 可带总超时
for (Future<Price> f : futures) f.get();

// invokeAny：谁先成功用谁（典型：多机房/多副本调用取最快响应）
String result = executor.invokeAny(List.of(
    () -> call机房A(), () -> call机房B(), () -> call机房C()
));
// 有一个成功返回后，其余任务被 cancel(true) 中断
```

### 7.4 CompletionService：谁先完成先处理谁（高频亮点）

**invokeAll 的痛点：** 返回的 List 按提交顺序排列，第一个任务最慢时，即使后面的任务早完成了，`futures.get(0).get()` 也要干等。

**ExecutorCompletionService：按完成顺序取结果**

```java
CompletionService<Price> cs = new ExecutorCompletionService<>(executor);
for (Shop s : shops) cs.submit(() -> queryPrice(s));   // 提交顺序无所谓

for (int i = 0; i < shops.size(); i++) {
    Future<Price> f = cs.take();        // 阻塞，但拿到的是"最先完成"的那个
    render(f.get());                    // 先完成的先渲染，体验更好
}
```

**原理：** 内部一个 `BlockingQueue<Future<V>>`，每个任务包装成 QueueingFuture（FutureTask 子类），`done()` 回调时把自己塞进队列。take() 就是队列出队。

```text
submit ──→ QueueingFuture ──执行──→ done() ──→ 塞入 BlockingQueue
                                                   │
用户线程 ←── take() 按完成先后出队 ←────────────────┘
```

**典型场景：** 向多个渠道询报价，谁先回来先处理；批量下载，先下完的先入库。

### 7.5 ForkJoinPool：分治型任务（RecursiveTask）

```java
// RecursiveTask 是分治版的 Callable：compute() 有返回值
class SumTask extends RecursiveTask<Long> {
    private final long[] arr; private final int lo, hi;
    protected Long compute() {
        if (hi - lo <= 1000) return 直接求和();              // 小任务直接算
        int mid = (lo + hi) >>> 1;
        SumTask left = new SumTask(arr, lo, mid);
        SumTask right = new SumTask(arr, mid, hi);
        left.fork();                          // 异步拆子任务
        return right.compute() + left.join(); // 右递归 + 左等结果（join 类似 get）
    }
}
long sum = ForkJoinPool.commonPool().invoke(new SumTask(arr, 0, arr.length));
```

**配套关系：**
- RecursiveTask（有返回）↔ Callable；RecursiveAction（无返回）↔ Runnable。
- `join()` 与 `get()` 类似，但 ForkJoinPool 内部做**工作窃取**，join 的等待开销远小于 Future.get。
- 线程池选型：**CPU 密集分治**用 ForkJoinPool；**IO 密集阻塞任务**用 ThreadPoolExecutor（ForkJoinPool 线程数少且默认为 CPU 核数-1，阻塞会把池拖死）。

---

## 八、CompletableFuture：异步编排的完全体（必考）

### 8.1 定位

```java
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> { }
```

- **Future**：兼容老 API，可 get/cancel。
- **CompletionStage**：定义了约 40 个编排方法（thenApply/thenCombine/…），代表"异步计算流水线中的一个阶段"。
- **可手动完成**：`complete(value)` / `completeExceptionally(ex)`，这是它也叫"可完成的 Future"的原因。

### 8.2 创建任务的四种方式

```java
// 有返回值（对应 Callable）
CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> query());

// 无返回值（对应 Runnable）
CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> log());

// 带自定义线程池（生产必须！）
CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> query(), bizExecutor);

// 手动完成（用于把回调式 API 改造成 Future 风格，如 Netty 回调）
CompletableFuture<String> f4 = new CompletableFuture<>();
new Thread(() -> f4.complete("callback result")).start();
```

**默认线程池的坑（必考）：**

> 不指定 executor 时，Async 后缀方法使用 **ForkJoinPool.commonPool()**——它是**全局共享**的（parallelStream 也用）、线程数为 `CPU核数-1`、且是**守护线程**（JVM 退出不等任务跑完）。IO 密集任务会瞬间占满公共池，拖垮整个应用所有并行流任务。
> **生产铁律：CompletableFuture 一律传入业务自定义线程池，且不同业务域池子隔离。**

### 8.3 编排方法全景（按功能分类记忆）

**① 一元转换/消费（串联流水线）**

```java
CompletableFuture.supplyAsync(() -> queryUser(id), executor)
    .thenApply(user -> buildDTO(user))      // Function：转换结果 T→U（类似 map）
    .thenAccept(dto -> cache(dto))          // Consumer：消费结果，无返回
    .thenRun(() -> metrics());              // Runnable：只关心"完成了"，不接收结果
```

**② Async 后缀的含义（高频辨析）**

| 方法 | 执行线程 |
|------|----------|
| thenApply(fn) | **沿用上一个任务的线程**（谁算完谁接着跑，不切换） |
| thenApplyAsync(fn) | 提交到**默认 ForkJoinPool.commonPool** |
| thenApplyAsync(fn, executor) | 提交到**指定线程池** |

**③ 二元组合：thenCompose vs thenCombine（必考）**

```java
// thenCompose：后一个任务依赖前一个的结果，"串联 + 扁平化"
// 类似 Stream 的 flatMap，避免 CompletableFuture<CompletableFuture<X>> 嵌套
CompletableFuture<Order> orderFuture = CompletableFuture
    .supplyAsync(() -> queryUser(id), executor)
    .thenCompose(user -> CompletableFuture.supplyAsync(() -> queryOrder(user.getVip()), executor));

// thenCombine：两个互不依赖的任务并行，最后"合并"结果
CompletableFuture<User> userF = CompletableFuture.supplyAsync(() -> queryUser(id), executor);
CompletableFuture<List<Coupon>> couponF = CompletableFuture.supplyAsync(() -> queryCoupons(id), executor);
CompletableFuture<DetailPage> page = userF.thenCombine(couponF,
    (user, coupons) -> new DetailPage(user, coupons));
```

**④ 多任务聚合：allOf / anyOf**

```java
CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);  // 全部完成（无返回值）
all.join();   // 等全部完成

// allOf 收集结果的惯用写法
List<CompletableFuture<String>> futures = ...;
List<String> results = futures.stream().map(CompletableFuture::join).toList();

CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2);    // 任一完成即返回（多副本取最快）
```

**⑤ 异常处理三兄弟（必考）**

```java
future
    .exceptionally(ex -> {                       // 链中任一环节异常 → 走这里，给兜底返回值（类似 catch）
        log.error("失败", ex);
        return DEFAULT_VALUE;
    })
    .handle((result, ex) -> {                    // 不管成功失败都执行（类似 finally+catch 合体）
        return ex != null ? DEFAULT : result;
    })
    .whenComplete((result, ex) -> {              // 完成后回调（类似 finally），不改变结果
        metrics.record(ex == null);
    });
```

**要点：** 链条中间某环节抛异常后，**后续所有 thenXxx 都被跳过**，直达最近的 exceptionally/handle。建议在链尾统一兜底，且**每个环节的超时独立设置**。

**⑥ 超时控制（JDK 9+，生产必用）**

```java
CompletableFuture<String> f = CompletableFuture
    .supplyAsync(() -> slowRpc(), executor)
    .orTimeout(500, TimeUnit.MILLISECONDS)               // 超时未完成 → 异常完成（TimeoutException）
    .exceptionally(ex -> "fallback");                    // 兜底降级

// 或者：超时给默认值而不是异常
.completeOnTimeout("default", 500, TimeUnit.MILLISECONDS);
```

**JDK 8 没有 orTimeout 的变通方案：** 单独起一个 ScheduledExecutorService，延迟触发 `future.completeExceptionally(new TimeoutException())`。

### 8.4 join() vs get()

| 对比项 | get() | join() |
|--------|-------|--------|
| 检查异常 | 抛 ExecutionException/InterruptedException，**必须 try-catch** | 包装成 CompletionException（非检查异常），**无需 catch** |
| 场景 | 需要区分中断/超时 | Lambda 链中（不能抛检查异常） |

**编排陷阱：** `join()` 也是阻塞的！在 thenApply 里 join 另一个 future 等于把异步又做回了同步，还会占着线程池线程（线程池小时可能**死锁**：所有线程都在等，没有线程去执行被等的任务）。正确做法是用 thenCompose/thenCombine 声明依赖，让框架自己衔接。

### 8.5 实战模板：详情页并行聚合（面试讲故事素材）

```java
public DetailVO detail(long itemId) {
    StopWatch sw = new StopWatch();
    sw.start();

    CompletableFuture<Item> itemF = CompletableFuture.supplyAsync(() -> itemService.get(itemId), ioPool)
            .orTimeout(300, MILLISECONDS).exceptionally(ex -> Item.EMPTY);
    CompletableFuture<Stock> stockF = CompletableFuture.supplyAsync(() -> stockService.get(itemId), ioPool)
            .orTimeout(200, MILLISECONDS).exceptionally(ex -> Stock.UNKNOWN);
    CompletableFuture<List<Comment>> commentF = CompletableFuture.supplyAsync(() -> commentService.list(itemId), ioPool)
            .orTimeout(300, MILLISECONDS).exceptionally(ex -> List.of());

    CompletableFuture.allOf(itemF, stockF, commentF).join();   // 等待全部

    sw.stop();
    log.info("详情聚合耗时 {}ms（串行需 {}ms）", sw.getTotalTimeMillis(), "300+200+300=800");
    return new DetailVO(itemF.join(), stockF.join(), commentF.join());
}
// 效果：总耗时 ≈ max(300, 200, 300) = 300ms，相比串行 800ms 下降 60%+
// 每个下游独立超时 + 降级，单点抖动不拖垮整页
```

---

## 九、全家桶对比总结表

| 维度 | Runnable | Callable | Future | FutureTask | CompletableFuture |
|------|----------|----------|--------|------------|-------------------|
| 本质 | 任务接口 | 任务接口 | 结果接口 | 任务+结果实现类 | 任务编排实现类 |
| 返回值 | 无 | 有 V | 定义 get() | 实现 get() | 实现且增强 |
| 抛异常 | 不可 | 可 | 包 ExecutionException | 同左 | join() 包 CompletionException |
| 主动完成 | — | — | 不支持 | protected set | **complete() 公开** |
| 取消 | — | — | cancel | cancel | cancel |
| 回调编排 | — | — | ❌ | ❌ | ✅ thenXxx 全家桶 |
| 多任务组合 | — | — | ❌ | ❌ | ✅ allOf/anyOf |
| 超时控制 | — | — | get(timeout) | get(timeout) | orTimeout（JDK9） |
| 典型执行者 | Thread/execute | submit | — | Thread/submit | supplyAsync + 自定义池 |

---

## 十、高频面试题速记

**Q1：Runnable 和 Callable 的区别？**
> Runnable 的 run() 无返回值不能抛检查异常，JDK 1.0 就有，可直接给 Thread 或 execute() 执行；Callable 的 call() 有返回值可抛异常，JDK 1.5 引入，必须通过线程池 submit() 或 FutureTask 包装执行，结果通过 Future.get() 获取。

**Q2：FutureTask 的原理？**
> FutureTask 同时实现 Runnable 和 Future，是连接两者的桥。内部 volatile state 状态机（NEW→COMPLETING→NORMAL/EXCEPTIONAL/CANCELLED/INTERRUPTING→INTERRUPTED）控制生命周期；run() 中执行包装的 Callable，结果 CAS 写入 outcome 并唤醒等待线程；get() 通过 Treiber stack 排队 + LockSupport.park 阻塞等待完成。submit() 本质就是 newTaskFor 包成 FutureTask 再 execute。

**Q3：execute 和 submit 的区别？**
> execute 无返回值，任务异常直接抛出导致线程终止（池会补新线程）；submit 返回 Future，异常被 FutureTask 捕获封存到 outcome，线程不终止，不调用 get() 异常永远静默——这是生产上的经典坑，必须 get() 处理异常或任务内 try-catch。

**Q4：Future 有什么缺陷？CompletableFuture 怎么解决的？**
> Future 只能 get() 阻塞等待，无法注册完成回调、无法手动完成、无法链式编排多任务、异常处理割裂。CompletableFuture 实现 Future + CompletionStage，支持 complete() 手动完成、thenApply/thenCompose/thenCombine 链式编排、allOf/anyOf 多任务聚合、exceptionally 统一异常兜底、orTimeout 超时控制。

**Q5：CompletableFuture 用默认线程池有什么问题？**
> 不指定 executor 时 Async 方法用 ForkJoinPool.commonPool：全局共享（与 parallelStream 抢资源）、线程数只有 CPU 核数-1、是守护线程。IO 任务会打满公共池拖垮整个应用。生产必须传业务自定义线程池并做池隔离。

**Q6：thenApply、thenCompose、thenCombine 的区别？**
> thenApply 是转换（map，T→U）；thenCompose 是串联扁平化（flatMap，T→CompletableFuture\<U\>，后任务依赖前任务结果，避免嵌套）；thenCombine 是两个独立并行任务的合并（T,U→R）。

**Q7：定时线程池的任务异常会怎样？**
> ScheduledThreadPoolExecutor 中周期任务一旦抛出未捕获异常，本次执行中断且**后续所有周期调度全部终止**，异常还被 FutureTask 吞掉无日志。必须 try-catch 包裹整个任务体。

**Q8：批量任务想"谁先完成先处理"怎么做？**
> invokeAll 返回结果按提交顺序，最慢的任务会阻塞后续结果的消费。用 ExecutorCompletionService：内部维护 BlockingQueue，任务 done() 时入队，take() 按完成顺序取，实现"先完成先消费"。

**Q9：cancel(true) 一定能停掉任务吗？**
> 不一定。cancel(true) 只是给执行线程发 interrupt() 中断信号，属于协作式停止：若任务代码不响应中断（不检查标志、不调用可中断方法），任务会继续跑完。未开始的任务无论 true/false 都直接取消不再执行。

**Q10：线程池拒绝策略触发后，submit 返回的 Future 会怎样？**
> AbortPolicy 直接给提交线程抛 RejectedExecutionException；但 DiscardPolicy 静默丢弃后，Future 永远停在 NEW 状态，无超时的 get() 会**永久阻塞**。所以生产必须 get(timeout) 兜底或自定义拒绝策略（落库重试+告警）。
