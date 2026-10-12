# JVM 面试知识点

## 一、JVM 运行时内存区域（必考）

### 1.1 整体结构（JDK 8）

```text
┌──────────────────────── JVM 运行时数据区 ─────────────────────────┐
│                                                                    │
│  线程私有                                                            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐              │
│  │ 程序计数器     │ │ 虚拟机栈       │ │ 本地方法栈     │              │
│  │ PC Register  │ │ JVM Stack    │ │ Native Stack │              │
│  │ （当前线程执行  │ │ （栈帧：局部变量 │ │ （native 方法） │              │
│  │  的字节码行号） │ │  表、操作数栈、 │ └──────────────┘              │
│  │  唯一不 OOM 区 │ │  动态链接、返回）│                             │
│  └──────────────┘ └──────────────┘                              │
│                                                                    │
│  线程共享                                                            │
│  ┌───────────────────────────┐  ┌───────────────────────────┐    │
│  │ 堆 Heap（GC 主战场，最大）    │  │ 方法区 Method Area          │    │
│  │  ├─ 新生代（Eden + S0 + S1）│  │ （JDK8 起由 Metaspace 实现， │    │
│  │  └─ 老年代                  │  │  使用本地内存：类元信息、     │    │
│  │ （对象实例、数组）           │  │  运行时常量池、JIT 代码缓存）│    │
│  └───────────────────────────┘  └───────────────────────────┘    │
│                                                                    │
│  堆外：直接内存 Direct Memory（NIO，-XX:MaxDirectMemorySize）        │
└────────────────────────────────────────────────────────────────────┘
```

### 1.2 各区域详解（高频）

| 区域 | 作用 | 异常 | 特点 |
|------|------|------|------|
| 程序计数器 | 记录当前线程执行的字节码行号，线程切换后恢复 | **唯一不会 OOM 的区域** | 执行 native 方法时值为 undefined |
| 虚拟机栈 | 方法调用压栈帧（局部变量表、操作数栈、动态链接、返回地址） | StackOverflowError（栈太深）/ OutOfMemoryError（栈无法扩展） | 栈帧大小编译期确定 |
| 本地方法栈 | native 方法（如 C 库） | 同上 | HotSpot 与虚拟机栈合并 |
| 堆 | 几乎所有对象实例 | OutOfMemoryError: Java heap space | GC 主战场，-Xms/-Xmx 设置 |
| 方法区/元空间 | 类元信息、静态变量、常量池、JIT 编译代码 | OutOfMemoryError: Metaspace | JDK8 用本地内存，默认只受物理内存限制 |

### 1.3 永久代 vs 元空间（JDK 8 为什么移除永久代？必考）

| 对比项 | 永久代（JDK7-） | 元空间（JDK8+） |
|--------|-----------------|------------------|
| 位置 | JVM 堆内存中 | **本地内存（Native Memory）** |
| 大小 | -XX:MaxPermSize 固定上限，默认小 | 默认无上限（受物理内存限制），-XX:MaxMetaspaceSize |
| OOM 场景 | 大量动态生成类（反射、CGLIB 代理、JSP）易 PermGen OOM | 元空间 OOM 概率大幅降低 |
| 字符串常量池 | JDK7 已移到堆中 | 仍在堆中 |
| 原因 | 永久代大小难估算、GC 效率低、与 JRockit 融合 | 类元数据生命周期难管理，交给本地内存更灵活 |

### 1.4 虚拟机栈与栈帧（深挖）

**栈帧四要素：**

```text
┌─ 栈帧 Frame ──────────────────────────┐
│ 局部变量表：this、方法参数、局部变量         │
│   （slot 复用；long/double 占 2 slot）     │
│ 操作数栈：计算的临时操作数                  │
│ 动态链接：指向运行时常量池的方法引用（多态分派）│
│ 方法返回地址：正常/异常退出的 PC 位置        │
└────────────────────────────────────────┘
```

**StackOverflowError 常见原因：** 无限递归、循环依赖的序列化（双向引用 JSON 化）、递归深度超过 -Xss（默认 512K~1M）。

### 1.5 直接内存（堆外内存）

- NIO 的 `ByteBuffer.allocateDirect()` 分配，通过 `Unsafe.allocateMemory()` 向 OS 申请。
- 优势：**避免堆内存与 native 堆之间来回复制**（Zero-Copy），Netty 大量使用。
- 回收：依赖 Cleaner（虚引用）触发 `System.gc()` 或 -XX:MaxDirectMemorySize 满时 Full GC 触发。
- **Netty 堆外内存泄漏排查**：-Dio.netty.leakDetectionLevel=PARANOID（偏执级检测）。

### 1.6 对象的创建过程与内存分配

**new 一个对象经历：**

```text
1. 类加载检查：常量池定位类符号引用 → 未加载则先类加载
2. 分配内存：
   ├─ 指针碰撞（Bump the Pointer）：内存规整（Serial/ParNew 带压缩）
   └─ 空闲列表（Free List）：内存不规整（CMS）
   并发安全：CAS + 本地线程分配缓冲 TLAB（每线程预分配一块 Eden，无竞争）
3. 零值初始化：字段设默认值（0/null/false）
4. 设置对象头：Mark Word（hash、GC 年龄）+ 类型指针
5. 执行 <init>：构造方法、实例代码块（真正的初始化）
```

**对象内存分配策略（高频）：**
1. **优先在 Eden 分配**。
2. **大对象直接进老年代**（-XX:PretenureSizeThreshold，避免 Eden 区来回拷贝，典型：大数组、长字符串）。
3. **长期存活对象进老年代**：Eden 出生年龄 0，每熬过一次 Minor GC 年龄 +1，到 -XX:MaxTenuringThreshold（默认 15，Mark Word 年龄只有 4bit）晋升。
4. **动态年龄判断**：Survivor 中同年龄对象总和超过 Survivor 一半，年龄 ≥ 该年龄的直接晋升。
5. **空间分配担保**：Minor GC 前检查老年代最大连续空间是否大于新生代所有对象总和，不够且不允许冒险则触发 Full GC。

**逃逸分析与栈上分配（JIT 优化，加分项）：**
- **逃逸分析**：分析对象作用域，若对象不会逃出方法/线程 → 可**栈上分配**（随栈帧销毁，无 GC 压力）+ **标量替换**（对象拆成成员变量放寄存器/栈上）+ **锁消除**。
- JDK 7+ 默认开启（-XX:+DoEscapeAnalysis）。

---

## 二、类加载机制

### 2.1 类的生命周期（必考）

```text
加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载
        └────── 连接（Linking） ──────┘
```

| 阶段 | 做什么 |
|------|--------|
| **加载** | 读 .class 字节流 → 生成 `java.lang.Class` 对象（方法区存类元数据） |
| **验证** | 文件格式、元数据、字节码、符号引用校验（保证不危害 JVM） |
| **准备** | 为**静态变量**分配内存并设**零值**（`static int a = 1` 此时 a=0）；static final 常量此时直接赋终值 |
| **解析** | 常量池符号引用 → 直接引用（内存地址） |
| **初始化** | 执行 `<clinit>()`：静态变量赋值 + 静态代码块（按书写顺序）；JVM 保证其线程安全（多线程同时初始化一个类只会执行一次 → 可利用此特性写**静态内部类单例**） |

**什么时候触发初始化（有且仅有 6 种，必背）：**
1. new、getstatic/putstatic、invokestatic（访问非常量静态字段/方法）。
2. 反射 `Class.forName()`（initialize=true）。
3. 初始化子类发现父类未初始化（先初始化父类）。
4. JVM 启动时的 main 方法类。
5. MethodHandle 解析结果对应的类。
6. 接口含 default 方法，其实现类初始化时接口先初始化。

**被动引用不触发初始化（经典题）：**
- 通过子类引用父类静态字段 → 只初始化父类。
- `new User[10]` 数组定义 → 不初始化 User。
- 引用 `static final` 常量（编译期已放入调用方常量池）→ 不初始化。

### 2.2 类加载器与双亲委派模型（必考）

```text
            Bootstrap ClassLoader（启动类加载器，C++）
                    ↑ 加载 JAVA_HOME/lib（rt.jar）
            Extension ClassLoader（扩展类加载器）
                    ↑ 加载 JAVA_HOME/lib/ext
            Application ClassLoader（应用类加载器）
                    ↑ 加载 classpath（我们的代码）
            自定义 ClassLoader（继承 ClassLoader，重写 findClass）
```

**双亲委派工作流程：**

```text
收到加载请求
   │
   ▼
先交给父加载器尝试 ──→ 父加载器一路向上委托 ──→ Bootstrap 尝试加载
   │                                              │
   │ 父加载器能加载 → 返回                          │ 失败
   ▼                                              ▼
  返回 Class                              子加载器自己 findClass()
```

**双亲委派的作用：**
1. **避免类重复加载**：父加载过子加载器不再加载。
2. **保证核心类安全**：防止自定义 `java.lang.String` 覆盖 JDK 核心类（沙箱安全）。

**面试必考：如何打破双亲委派？**

| 场景 | 机制 |
|------|------|
| **SPI（JDBC）** | Bootstrap 加载的 DriverManager 需要加载 classpath 下的 Driver 实现，父加载器看不到子类路径 → 用**线程上下文类加载器（TCCL）**反向委托：`Thread.currentThread().getContextClassLoader()` |
| **Tomcat** | 每个 WebApp 有自己的 WebappClassLoader，**优先自己加载**（Webapp 隔离，不同应用可共存不同版本 jar），只在本地找不到才委托父加载器；但核心类（java.*）仍走双亲委派 |
| **OSGi / JPMS** | 模块化，网状委派 |
| 自定义破坏 | 重写 `loadClass()` 改变委派逻辑（findClass 只是定义"如何找"，loadClass 定义"委派顺序"） |

**Tomcat 类加载器结构：**

```text
        Bootstrap / Extension / AppClassLoader（Common 继承体系）
                    │
              Common ClassLoader（tomcat/lib 共享）
               /            \
        Catalina（服务端）   Shared（所有 WebApp 共享）
                              │
                    ┌─────────┴─────────┐
                WebappClassLoader1   WebappClassLoader2
                (WEB-INF/classes,    （应用隔离：A应用 spring5
                 WEB-INF/lib)          B应用 spring4 共存）
                              │
                    JasperLoader（每个 JSP 一个，实现热加载）
```

### 2.3 自定义类加载器

```java
public class MyClassLoader extends ClassLoader {
    @Override
    protected Class<?> findClass(String name) {
        byte[] bytes = loadClassBytes(name);  // 从加密文件/网络/DB 读字节
        return defineClass(name, bytes, 0, bytes.length);
    }
}
```

应用场景：热部署、加密 class 解密、隔离不同版本依赖。
**判断两个类相同**：全限定名相同 **且** 类加载器实例相同。

---

## 三、垃圾回收（GC）—— JVM 面试核心

### 3.1 如何判断对象已死？

**引用计数法（Java 不用）：** 对象被引用 +1，失效 -1，为 0 回收。缺点：**无法解决循环引用**（A 引用 B，B 引用 A，都死了但计数不为 0）。Python 用（配合分代回收解循环引用）。

**可达性分析（Java 采用）：** 从 **GC Roots** 出发向下搜索，不可达的对象判定可回收。

**GC Roots 包括（必背）：**
1. 虚拟机栈中引用的对象（局部变量表）。
2. 方法区中**静态属性**引用的对象。
3. 方法区中**常量**引用的对象。
4. 本地方法栈 JNI 引用的对象。
5. 活跃线程、被 synchronized 持有的对象、JVM 内部引用（ClassLoader、异常对象）。

**finalize()：** 对象被标记为不可达后，若重写了 finalize() 且未执行过，会进入 F-Queue 由低优先级线程执行，期间"自救"（重新挂上引用链）可复活一次，只能救一次。**已废弃，不要用**。

### 3.2 四种引用类型（必考）

| 引用 | 类 | GC 时机 | 用途 |
|------|-----|---------|------|
| 强引用 | 普通 `new` | 永不回收（OOM 也不收） | 默认 |
| 软引用 | SoftReference | **内存不足时**才回收 | 内存敏感缓存（图片缓存：内存够就缓存，不够 GC 自动清） |
| 弱引用 | WeakReference | **下次 GC 就回收**（不管内存够不够） | WeakHashMap、ThreadLocal 的 key |
| 虚引用 | PhantomReference | 随时，get() 永远 null | 跟踪对象被回收（配合 ReferenceQueue），堆外内存管理（DirectByteBuffer 的 Cleaner） |

### 3.3 GC 算法（必考）

| 算法 | 过程 | 优点 | 缺点 | 应用 |
|------|------|------|------|------|
| **标记-清除** | 标记存活 → 清除死亡 | 简单 | **内存碎片**、效率双低 | CMS 老年代 |
| **标记-复制** | 存活对象复制到另一块 → 清空原块 | 无碎片、高效（对象存活率低时） | **浪费一半内存** | 新生代（Eden+Survivor 优化版） |
| **标记-整理** | 标记 → 存活对象向一端移动 → 清理边界外 | 无碎片、空间利用高 | 移动对象需 STW 更新引用，慢 | 老年代（Serial Old、Parallel Old） |

**新生代为什么用复制算法？** 新生代对象"朝生夕死"（98% 熬不过第一次 GC），存活极少，复制成本低。HotSpot 优化：**Eden : S0 : S1 = 8 : 1 : 1**，每次只用 Eden + 一块 Survivor，GC 时存活对象复制到另一块 Survivor，空间浪费只有 10%。

**分代收集理论：**
- 弱分代假说：绝大多数对象朝生夕死 → 新生代，复制算法，Minor GC 频繁但快。
- 强分代假说：熬过多次 GC 的对象难以消亡 → 老年代，标记-清除/整理，Major GC 低频但慢。
- **跨代引用问题**：老年代引用新生代对象，Minor GC 时无需扫描整个老年代 —— 用**记忆集（Remembered Set）/卡表（Card Table）** 记录跨代引用，只扫脏卡。

### 3.4 Minor GC / Major GC / Full GC / Mixed GC（辨析高频）

| 名称 | 范围 | 触发条件 |
|------|------|----------|
| Minor GC（Young GC） | 只收新生代 | Eden 区满；频率高、速度快 |
| Major GC | 只收老年代（CMS 特有行为） | 老年代空间不足；速度慢，常伴随至少一次 Minor GC |
| Full GC | 整堆 + 方法区 | 老年代不足、空间担保失败、System.gc()、元空间满、CMS 并发失败 |
| Mixed GC | 新生代 + 部分老年代 | G1 特有，按 Region 回收价值排序 |

### 3.5 垃圾收集器（必考图谱）

```text
新生代                          老年代

Serial（单线程）  ──────────  Serial Old（单线程，标记整理）
  │
ParNew（多线程并行）────────── CMS（并发，标记清除）★ 已废弃（JDK14移除）
  │
Parallel Scavenge ────────── Parallel Old（标记整理）
（吞吐量优先，JDK8 默认组合）

        G1（横跨新生代+老年代，JDK9+ 默认）★★
        ZGC（JDK15 正式，亚毫秒停顿，TB 级堆）★★★ 前沿考点
        Shenandoah（RedHat，低停顿）
```

**核心收集器对比：**

| 收集器 | 算法 | 特点 | 目标 | 适用 |
|--------|------|------|------|------|
| Serial | 复制 | 单线程 + STW | 简单高效（无线程交互开销） | 客户端模式、小内存、微服务小实例 |
| ParNew | 复制 | Serial 多线程版 | 配合 CMS | JDK8 前 Web 应用常见 |
| Parallel Scavenge | 复制 | 关注**吞吐量**（GC 时间/总时间） | 后台计算型任务 | JDK8 默认（+ Parallel Old） |
| CMS | 标记-清除 | **并发收集、低停顿** | 响应速度（Web 应用） | JDK8 及以前低延迟场景，已废弃 |
| G1 | 整体标记整理 + 局部复制 | **Region 化整堆、可预测停顿** | 吞吐与延迟平衡 | JDK9+ 默认，大堆（4G+） |
| ZGC | 染色指针 + 读屏障 | **停顿 < 1ms 不随堆增长** | 超低延迟 | TB 级堆、金融交易 |

### 3.6 CMS 详解（高频，虽废弃但面试仍考）

**四个阶段：**

```text
1. 初始标记（STW）：标记 GC Roots 直接关联对象 —— 很快
2. 并发标记：与用户线程并发，遍历整个引用链 —— 最耗时但不 STW
3. 重新标记（STW）：修正并发标记期间变动的引用（增量更新）—— 比 1 长比 2 短
4. 并发清除：与用户线程并发清理死亡对象
```

**CMS 三大缺点（必背）：**
1. **CPU 敏感**：并发阶段占用 CPU（默认 (CPU数+3)/4 个线程），CPU < 4 核时性能骤降。
2. **浮动垃圾（Floating Garbage）**：并发清理期间用户线程产生的新垃圾只能等下次 GC。
3. **Concurrent Mode Failure**：并发清理期间老年代不够放新对象 → 退化 Serial Old 串行 Full GC，停顿反而更长（所以 CMS 不能等堆满才触发，-XX:CMSInitiatingOccupancyFraction 默认 68% 启动）。
4. 标记-清除 → **内存碎片** → 大对象无法分配 → 提前 Full GC（-XX:+UseCMSCompactAtFullCollection 碎片整理，整理时 STW）。

**并发标记的写屏障（增量更新 vs 原始快照，加分项）：**
- CMS 用**增量更新（Incremental Update）**：黑色对象新增指向白色的引用时，记录这条引用，重新标记时以黑为根再扫（把插入的引用看作"新增待处理"）。
- G1 用**原始快照 SATB（Snapshot At The Beginning）**：灰色断开指向白色的引用时，记录被断开的旧引用，保证"开始时存活的对象一定被标记"。
- 两者解决的是并发标记中的**对象消失问题**（ Wilson 三色标记理论：白色对象同时满足 ①插入黑色对它的引用 ②灰色断开对它的全部引用 → 会漏标）。

### 3.7 G1 详解（JDK9+ 默认，必考）

**核心思想：Region 分区 + 价值优先回收**

```text
G1 把堆切成最多 2048 个等大 Region（1~32MB，2 的幂）
每个 Region 逻辑上分属：
  Eden / Survivor / Old / Humongous（巨型对象 ≥ Region 一半，直接进 H 区）
Region 身份动态变化，不再是物理连续分代
```

**特点：**
- **可预测的停顿模型**：-XX:MaxGCPauseMillis（默认 200ms），G1 维护每个 Region 的回收价值（可回收空间/耗时）优先级队列，**优先回收垃圾最多、收益最大的 Region**（Garbage First 名字由来）。
- **Mixed GC**：新生代 + 高价值老年代 Region 一起收。
- 跨 Region 引用：每个 Region 有 **RSet（记忆集）**，记录谁引用了我（point-in），写屏障维护。

**G1 GC 流程（Young GC 与 Mixed GC 并发标记周期）：**

```text
1. 初始标记（STW）：标记 GC Roots（借 Young GC 完成，无额外停顿）
2. 根区域扫描：扫 Survivor → 老年代的引用
3. 并发标记：SATB 遍历全堆
4. 最终标记（STW）：处理 SATB 缓冲
5. 筛选回收（STW）：Region 价值排序 → 选择收集集合（CSet）→ 复制存活对象到空 Region → 整体清空旧 Region（局部复制算法，基本无碎片）
```

**G1 vs CMS（必考对比）：**

| 对比项 | CMS | G1 |
|--------|-----|-----|
| 内存布局 | 物理分代（连续新生代/老年代） | Region 化逻辑分代 |
| 算法 | 标记-清除（**有碎片**） | 局部复制 + 整体标记整理（**基本无碎片**） |
| 停顿目标 | 尽量低，不可控 | **可预测**（MaxGCPauseMillis） |
| 并发标记修正 | 增量更新 | SATB 原始快照 |
| 大对象 | 可能因碎片提前 Full GC | Humongous Region 专管 |
| 适用堆 | 中小堆 | **大堆（4G~16G+）** |

**Full GC 兜底：** G1 回收速度跟不上分配速度 → 退化 Serial Old 单线程 Full GC（JDK10 起并行化）。常见原因：巨型对象过多、元空间不足、预留空间不足。

### 3.8 ZGC 与 Shenandoah（前沿加分项）

**ZGC 核心技术：**
- **染色指针（Colored Pointers）**：64 位指针中拿出 4 bit 做标志位（Marked0/Marked1/Remapped/Finalizable），GC 信息直接存在指针上，无需访问对象头。
- **读屏障（Load Barrier）**：读引用时检查染色位，对象已被移动则转发到新地址（自愈）。
- **并发转移**：对象移动不 STW，只修正访问它的指针。
- 结果：**STW 停顿 < 1ms 且与堆大小无关**，支持 8MB~16TB 堆。
- 代价：吞吐量略降（约 10~15%）、不支持分代（JDK21 引入分代 ZGC）。

**Shenandoah：** RedHat 主导，Brooks 转发指针（对象头加转发指针）实现并发转移，思路与 ZGC 类似但实现不同。

---

## 四、JVM 参数与调优实战

### 4.1 常用 JVM 参数（必背）

| 参数 | 说明 | 生产建议 |
|------|------|----------|
| -Xms4g -Xmx4g | 堆初始/最大 | **必须设为相等**，避免运行时扩缩抖动 |
| -Xmn2g | 新生代大小 | 或用 -XX:NewRatio=2（老:新=2:1） |
| -XX:SurvivorRatio=8 | Eden:S=8:2 | 默认 |
| -Xss512k | 线程栈大小 | 线程多可降到 256k~512k |
| -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m | 元空间 | 设初始值避免首次 Full GC 触发扩容 |
| -XX:+UseG1GC | 用 G1 | JDK8 大堆手动开 |
| -XX:MaxGCPauseMillis=200 | G1 停顿目标 | 不是越小越好（太小 GC 频繁吞吐降） |
| -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path | OOM 自动 dump | **生产必配** |
| -XX:+PrintGCDetails -Xloggc:/path/gc.log（JDK8）| GC 日志 | 生产必配 |
| -Xlog:gc*:file=gc.log（JDK9+ 统一日志） | GC 日志 | 新语法 |
| -XX:ErrorFile=/path/hs_err.log | JVM 崩溃日志 | 生产必配 |

**标准生产模板（4C8G 服务，JDK8，G1）：**

```bash
java -Xms6g -Xmx6g -Xmn3g
     -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m
     -XX:+UseG1GC -XX:MaxGCPauseMillis=200
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/logs/heapdump.hprof
     -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/data/logs/gc.log
     -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=100m
     -jar app.jar
```

### 4.2 JVM 监控与诊断工具（必考）

| 工具 | 用途 | 常用命令 |
|------|------|----------|
| jps | 查看 Java 进程 | `jps -lvm` |
| jstat | 统计信息（GC、类加载） | `jstat -gcutil pid 1000`（每秒看各区使用率+GC 次数耗时） |
| jmap | 堆转储/直方图 | `jmap -histo:live pid`（看对象实例数）、`jmap -dump:live,format=b,file=heap.hprof pid` |
| jstack | 线程栈 | `jstack pid > thread.log`（查死锁、CPU 飙高） |
| jinfo | 查看/修改 JVM 参数 | `jinfo -flags pid` |
| jconsole/jvisualvm | 图形化监控 | 本地/远程 JMX |
| **Arthas** | 阿里开源诊断神器 | `dashboard`、`thread -n 3`（最忙的 3 线程）、`watch`（方法观测）、`trace`（方法耗时）、`jad`（反编译）、`ognl`（调用任意方法）、`heapdump` |
| MAT | 分析堆 dump | 支配树（Dominator Tree）找大对象、泄漏嫌疑人报告 |
| GCeasy/GCViewer | GC 日志分析 | 在线/离线 |

### 4.3 线上问题排查套路（面试场景题）

**场景一：CPU 飙高**

```bash
1. top 找到 CPU 高的 Java 进程 PID
2. top -Hp PID 找到 CPU 高的线程 TID
3. printf '%x\n' TID 得到 16 进制 nid
4. jstack PID | grep -A 30 <nid> 定位到具体代码行
# Arthas 一步搞定：thread -n 3
```

常见根因：死循环、正则回溯、频繁 GC（GC 线程占 CPU）、序列化大对象、锁竞争激烈自旋。

**场景二：内存飙高 / OOM**

```bash
1. jstat -gcutil pid 1000 观察：老年代是否持续增长不回落
2. jmap -dump:live,format=b,file=heap.hprof pid（或用 OOM 自动 dump）
3. MAT 打开 dump → Leak Suspects → 支配树找 Retained Heap 最大的对象
4. 沿引用链（Path to GC Roots）找到谁持有它
```

常见根因：
- **静态集合只增不减**（static Map 当缓存没上限）。
- ThreadLocal 未 remove（线程池场景）。
- **连接/流未关闭**（池化资源泄漏）。
- 无界队列/无界缓存（LinkedBlockingQueue 默认无界）。
- 大对象（一次查几十万条 SQL 无分页）。
- 元空间 OOM：大量动态代理类（CGLIB、反射）、ClassLoader 泄漏。

**场景三：Full GC 频繁**

```bash
1. jstat -gcutil pid 1000 看 FGC/FGCT（Full GC 次数/总耗时）是否快速增长
2. 分析 GC 日志：Full GC 前后老年代回收效果
   - 回收后老年代仍很高 → 内存泄漏或大对象常驻 → dump 分析
   - 回收后老年代低 → 晋升过快/分配速率太高 → 调大新生代、降晋升阈值
3. 常见诱因：
   - 元空间不足（MetaspaceSize 太小，设 256m）
   - 大对象直接进老年代（排查大数组/大集合）
   - System.gc() 代码显式调用（-XX:+DisableExplicitGC）
   - 空间担保失败
```

**场景四：接口 RT 毛刺（偶发变慢）**

排查方向：Young GC 频繁（新生代太小，对象"朝生夕死"变"中年危机"）→ 调大新生代；STW 停顿 → 换 G1/ZGC；safepoint 进入慢（可数循环 JIT 优化问题）。

### 4.4 调优方法论（总结话术）

> JVM 调优不是背参数，而是：**① 明确目标**（吞吐量优先？延迟优先？）→ **② 采集数据**（GC 日志、jstat、dump）→ **③ 定位瓶颈**（GC 频繁？泄漏？停顿长？）→ **④ 针对性调整**（堆比例、收集器、代码层面减少对象分配）→ **⑤ 验证对比**。
> 大部分"JVM 问题"最终是**代码问题**（泄漏、大对象、无限创建线程），调参只是兜底。能用 G1 解决的不要硬上 ZGC，能在代码层解决的不要靠调参。

---

## 五、高频面试题速记

**Q1：JVM 内存区域划分？哪些线程共享哪些私有？**
> 私有：程序计数器（唯一不 OOM）、虚拟机栈、本地方法栈；共享：堆、方法区（JDK8 元空间，本地内存）。堆是 GC 主战场，分新生代（Eden+2 个 Survivor，8:1:1）和老年代。

**Q2：对象什么时候进老年代？**
> ①大对象直接进（PretenureSizeThreshold）②年龄到 15（MaxTenuringThreshold）③动态年龄判断（Survivor 同年龄对象过半）④空间担保失败时。

**Q3：双亲委派模型？为什么？怎么打破？**
> 类加载请求先委托父加载器逐层向上，父加载不了子加载器才加载。作用：避免重复加载 + 保护核心类库不被篡改。打破：SPI 用线程上下文类加载器（JDBC）、Tomcat WebappClassLoader 优先自加载实现应用隔离、重写 loadClass。

**Q4：怎么判断对象可以被回收？finalize 能救活几次？**
> 可达性分析：从 GC Roots（栈局部变量、静态变量、常量、JNI、活跃线程、锁持有对象）出发不可达即可回收。finalize 最多自救一次，已废弃。

**Q5：CMS 和 G1 的区别？**
> CMS 物理分代、标记-清除有碎片、停顿不可控、浮动垃圾 + Concurrent Mode Failure 风险；G1 Region 化逻辑分代、局部复制基本无碎片、MaxGCPauseMillis 可预测停顿、SATB 并发标记、价值优先回收，JDK9+ 默认。

**Q6：Full GC 频繁怎么排查？**
> jstat 看频率与回收效果 → GC 日志分析触发原因 → 老年代回收不掉则 dump + MAT 找泄漏；回收得掉但频繁则调大新生代/降晋升速度/查大对象/System.gc/元空间。

**Q7：四种引用？软引用和弱引用区别？**
> 强（默认，不收）、软（内存不足才收，做缓存）、弱（GC 就收，WeakHashMap/ThreadLocal key）、虚（跟踪回收，堆外内存 Cleaner）。

**Q8：ZGC 为什么停顿短？**
> 染色指针（GC 标志存在指针上）+ 读屏障（访问时自愈重定向）实现并发转移，STW < 1ms 且不随堆增长，支持 TB 级堆；代价是吞吐略降。
