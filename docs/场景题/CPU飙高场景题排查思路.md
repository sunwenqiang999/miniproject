# CPU 飙高场景题排查思路（通用 + Java + Go）

## 阅读建议

CPU 飙高是生产环境最高频的场景题之一，面试官通常按"**原因分类 → 排查步骤 → 工具使用 → 定位到代码 → 解决方案**"的链路考察。

| 视角 | 内容重点 |
|------|----------|
| 通用（Linux/系统） | 负载、死循环、正则回溯、上下文切换、容器 throttling、挖矿进程 |
| Java | GC 风暴、死循环、序列化/加密、线程爆炸、JIT、jstack/Arthas/async-profiler |
| Go | goroutine 泄漏、GOMAXPROCS 与容器配额、GC 频繁、pprof/trace |

---

## 一、通用视角：CPU 飙高的可能原因

### 1.1 原因总览

| 类别 | 具体原因 | 典型特征 |
|------|----------|----------|
| 业务负载 | 流量突增、大促、热点 Key、批量任务 | CPU 与 QPS 同步上涨，属"正常"飙高 |
| 代码缺陷 | 死循环、忙等待（busy-wait）、自旋不退出 | 单线程 CPU 100%，QPS 不涨 |
| 算法问题 | 正则灾难性回溯、O(n²) 算法、深递归 | 特定输入触发，CPU 单核打满 |
| 计算密集 | 加密解密、序列化/反序列化、压缩、图片处理 | 短时尖刺，与请求量正相关 |
| 系统层面 | 中断过多（网卡打满）、上下文切换频繁、IO wait 高 | `%si`/`%cs`/`%wa` 指标异常 |
| 外部因素 | 病毒/挖矿程序、宿主机超卖、被其他进程挤占 | 业务无变化但 CPU 高 |
| 容器环境 | CPU limit 触发 throttling、cgroup 限制 | 容器内 top 正常但服务 RT 上升 |

### 1.2 通用排查步骤（五步定位法）

```bash
# 第一步：确认是哪个进程
top -c
# 按 P（按 CPU 排序），找到 CPU 最高的进程 PID

# 第二步：确认是进程内哪个线程
top -Hp <pid>
# 找到 CPU 最高的线程 TID

# 第三步：线程 TID 转十六进制
printf "%x\n" <tid>

# 第四步：查看该线程在做什么
# Java：jstack <pid> | grep -A 30 <hex_tid>
# 通用：cat /proc/<pid>/task/<tid>/stack（需 root）
#       perf top -p <pid>（看热点函数）

# 第五步：结合业务日志、监控（QPS/RT/GC）判断根因
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `top -c` | `-c` | 显示完整命令行（command line），区分多个同名 java 进程；交互界面中按 `c` 键可切换 |
| `top -Hp <pid>` | `-H` | 线程模式（Threads），把进程内每个线程显示为独立条目 |
| | `-p <pid>` | 只监控指定 PID 的进程（可跟多个，逗号分隔，最多 20 个） |
| `printf "%x\n" <tid>` | `%x` | 以十六进制输出；`\n` 换行。jstack 中线程 nid 是十六进制，必须转换后才能 grep |
| `jstack <pid>` | 无参 | dump 目标 JVM 所有线程的堆栈快照 |
| `grep -A 30` | `-A 30` | 显示匹配行及其后（After）30 行，即匹配到 nid 后输出完整堆栈 |
| `perf top -p <pid>` | `-p <pid>` | 只对指定进程做实时采样，按热点函数排序（含内核态函数） |

> 注意：`top -c`、`top -Hp` 是 **Linux** 用法。macOS 的 BSD top 不支持，按 CPU 排序用 `top -o cpu`，查线程用 `ps -M <pid>`。

### 1.3 关键指标解读

```bash
# us  : 用户态 CPU —— 通常是业务代码问题（死循环、计算密集）
# sy  : 内核态 CPU —— 系统调用频繁、上下文切换、锁竞争（自旋）
# wa  : IO 等待    —— 磁盘 IO 瓶颈（严格说不算 CPU 消耗，但会拉高 load）
# si  : 软中断     —— 网络包处理（网卡打满、SYN 洪泛）
# st  : 偷取时间   —— 虚拟化/容器环境宿主机超卖
vmstat 1
# cs 列：上下文切换次数，超过百万级需关注
# r 列：运行队列长度，长期大于 CPU 核数说明 CPU 不足

pidstat -w -p <pid> 1
# cswch/s 自愿切换（等锁/IO）、nvcswch/s 非自愿切换（时间片耗尽，CPU 争抢）
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `vmstat 1` | `1` | 采样间隔 1 秒，持续输出（无次数限制，Ctrl+C 停止） |
| `pidstat -w -p <pid> 1` | `-w` | 输出上下文切换统计（switch） |
| | `-p <pid>` | 只统计指定进程 |
| | `1` | 每 1 秒刷新一次；pidstat 属 sysstat 包，需 `yum install sysstat` |

### 1.4 容器环境特有问题（K8s）

- **CPU Throttling**：容器设置了 CPU limit，即使宿主机空闲，超配额也会被 CFS 限流，表现为 RT 毛刺。
  ```bash
  # 查看 throttling 情况
  cat /sys/fs/cgroup/cpu/cpu.stat
  # nr_throttled / throttled_time 持续增长说明被限流
  ```
- **宿主机超卖**：`%st`（steal time）高，说明宿主机资源被其他租户抢占。
- **Java/Go 默认感知不到容器 limit**（老版本 JDK8u191 前、Go 1.25 前），导致线程数/GOMAXPROCS 配置错误。

---

## 二、Java 视角：CPU 飙高的可能原因

### 2.1 原因分类（按概率排序）

| 排名 | 原因 | 说明 |
|------|------|------|
| 1 | **GC 风暴** | 频繁 Young GC / 持续 Full GC，GC 线程占满 CPU |
| 2 | **死循环 / 空转** | while 条件永真、并发修改集合导致迭代异常后重试死循环 |
| 3 | **正则回溯** | 有缺陷的正则 + 特定输入 → 灾难性回溯 |
| 4 | **序列化/加密计算** | 大 JSON 解析、RSA 加密、图片缩放等 CPU 密集操作 |
| 5 | **线程数爆炸** | 线程创建失控（未用线程池），上下文切换开销剧增 |
| 6 | **锁自旋** | 高并发下 synchronized/cas 自旋等待（表现为 %sy 高） |
| 7 | **JIT 编译** | 应用刚启动时 C2 编译消耗 CPU（属正常现象） |
| 8 | **类加载/反射** | 动态生成类（CGLIB）、大量反射调用 |

### 2.2 GC 风暴（最高频原因）

**判断依据：**

```bash
# 1. top -Hp 发现 CPU 高的线程是 "GC task thread" / "G1 Young RemSet"
# 2. GC 日志疯狂输出
jstat -gcutil <pid> 1000
#  FGC 列持续增长、O 列（老年代）接近 100% → 持续 Full GC
```

**命令参数说明：**

| 参数 | 含义 |
|------|------|
| `-gcutil` | 输出各代使用率汇总（百分比形式）：S0/S1（幸存区）、E（Eden）、O（老年代）、M（元空间）、YGC/YGCT（Young GC 次数/耗时）、FGC/FGCT（Full GC 次数/耗时）、GCT（总耗时） |
| `<pid>` | 目标 JVM 进程号 |
| `1000` | 采样间隔 1000 毫秒（1 秒），持续输出；可再追加次数，如 `jstat -gcutil <pid> 1000 10` 采 10 次后退出 |
| 其他常用选项 | `-gc`（容量+次数详细版，单位 KB）、`-gccause`（额外输出最近一次 GC 原因） |

**jstat -gcutil 每一列的含义详解：**

```
 S0     S1     E      O      M     YGC    YGCT    FGC    FGCT     GCT
 0.00 100.00  85.32  99.87  96.20   4521   88.12   892   512.33   600.45
```

列 | 全称 | 含义 | 怎么看 |
|----|------|------|--------|
S0 | Survivor 0 | 幸存区 0 的使用率（%） | 与 S1 交替使用，**同一时刻只有一个非 0**（复制算法） |
S1 | Survivor 1 | 幸存区 1 的使用率（%） | S0=0 时 S1 应有值，反之亦然；两个都非 0 说明刚发生 Minor GC 切换 |
E | Eden | 新生代 Eden 区使用率（%） | 持续上涨 → Minor GC 后回落，呈锯齿状是**正常**的 |
O | Old | **老年代使用率（%）** | **最关键的一列**：持续 >90% 且 Full GC 后不回落 → 泄漏；锯齿回落 → 容量不足 |
M | Metaspace | 元空间使用率（%） | 持续上涨不回落 → 类加载泄漏（动态代理/CGLIB/热部署） |
YGC | Young GC Count | 程序启动以来 Minor GC **总次数** | 两次采样间的差值 ÷ 间隔秒数 = GC 频率（如 1 秒差 5 → 每秒 5 次） |
YGCT | Young GC Time | Minor GC **总耗时**（秒） | YGCT 差值 ÷ YGC 差值 = 单次 Minor GC 平均耗时（正常 <50ms） |
FGC | Full GC Count | Full GC **总次数** | **第二关键列**：持续增长 = Full GC 风暴；理想状态是长时间为 0 或极少 |
FGCT | Full GC Time | Full GC **总耗时**（秒） | FGCT 差值 ÷ FGC 差值 = 单次 Full GC 平均耗时（正常 <1s，>3s 说明堆太大或回收不动） |
GCT | GC Time | 所有 GC 总耗时（秒） | GCT / 程序运行时长 = GC 时间占比；>10% 说明 GC 严重影响吞吐 |

**典型组合判读（面试高频）：**

现象组合 | 诊断 |
|---------|------|
O 99% + FGC 每秒 +1 + Full GC 后 O 仍 99% | **内存泄漏**（回收不掉）→ dump + MAT |
O 99% + FGC 涨 + Full GC 后 O 降到 50% | **容量不足**（能回收）→ 调大堆或优化缓存 |
E 快速打满 + YGC 每秒多次 + O 正常 | **分配速率过高**（大对象/短命对象多）→ 查分配热点 |
M 持续上涨 + FGC 涨 | **类加载泄漏** → 查动态代理、热部署 |
全部正常但 CPU 高 | 问题不在 GC → 转业务代码排查（jstack） |

**常见触发点：**
- 内存泄漏导致老年代打满 → 频繁 Full GC（此时 CPU 高的根因是**内存问题**）
- 大对象直接进老年代（大 JSON、大集合）
- 缓存无界增长
- promotion failed / concurrent mode failure

> 面试加分点：CPU 飙高排查时**必须先看 GC**，很多"CPU 问题"本质是"内存问题"，这是最常见的陷阱。

### 2.3 死循环与正则回溯

```java
// 死循环典型场景 1：并发修改集合
while (true) {
    for (String s : list) {  // ConcurrentModificationException 后外层 while 重试
        ...
    }
}

// 死循环典型场景 2：HashMap 并发扩容成环（JDK7）
// 多线程 put 触发扩容，链表成环，get 时死循环

// 正则回溯典型场景
// (a+)+$ 匹配 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaab"
// 回溯次数指数级增长，单线程 CPU 100%
```

### 2.4 Java 排查工具链

#### 方式一：原生工具（面试必背）

```bash
# 1. 找到进程
top -c                          # 假设 PID = 12345

# 2. 找到最耗 CPU 的线程
top -Hp 12345                   # 假设 TID = 12378

# 3. 线程号转 16 进制
printf "%x\n" 12378             # → 306a

# 4. dump 线程栈并定位
jstack 12345 | grep -A 30 "0x306a"
# 看 "GC task thread" → GC 问题
# 看业务栈 → 直接定位到类和行号
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `jstack <pid>` | 无参 | dump 全部线程栈；常用扩展：`-l` 附加锁信息（查死锁持有者）、`-F` 进程无响应时强制 dump |
| `grep -A 30 "0x306a"` | `-A 30` | 匹配行后再输出 30 行（After）；堆栈一般 20~40 行，30 足够覆盖 |
| | `"0x306a"` | jstack 输出中线程 nid 带 `0x` 前缀，所以搜索时要加上 |

**jstack 输出怎么读、怎么判断问题（逐行解读）：**

```java
"http-nio-8080-exec-5"          // ① 线程名：判断线程身份的第一线索
    prio=5                       // ② 线程优先级（很少用）
    tid=0x00007f8e04123800       // ③ Java 线程 id（JVM 内部，十六进制）
    nid=0x306a                   // ④ native 线程 id = 内核线程号（十六进制）
                                 //    ★ 就是 top -Hp 拿到的 TID 转十六进制后的值，grep 就搜它
    runnable                     // ⑤ 线程当前状态（见下表）
    [0x00007f8d9c1f6000]         // ⑥ 线程栈的起始地址（不用管）
   java.lang.Thread.State: RUNNABLE    // ⑦ Java 层线程状态
        at com.order.service.OrderService.create(OrderService.java:87)   // ⑧ 业务堆栈
        at com.order.controller.OrderController.submit(OrderController.java:45)
        ...
   Locked ownable synchronizers:        // ⑨ 持有的锁（-l 参数才有）
    - <0x0000000780a12345> locked by ...
```

**① 线程名速查表（看名字就知道是谁）：**

| 线程名前缀 | 身份 | CPU 高时的含义 |
|-----------|------|---------------|
| `GC task thread` / `G1 Young RemSet` / `G1 Conc` | GC 线程 | GC 风暴 → 转内存排查 |
| `http-nio-8080-exec-N` | Tomcat 工作线程 | 业务代码问题，看堆栈 |
| `pool-N-thread-M` | 自定义线程池 | 看是哪个业务创建的池 |
| `main` | 主线程 | 启动逻辑死循环 |
| `VM Thread` / `VM Periodic` | JVM 内部线程 | 频繁活动说明 GC/类加载频繁 |
| `Finalizer` | 终结器线程 | 队列堆积说明 finalize() 滥用 |
| `C2 CompilerThrea` | JIT 编译线程 | 刚启动时高属正常 |

**⑤ 线程状态判读（关键）：**

| 状态 | 含义 | CPU 高时 |
|------|------|---------|
| `RUNNABLE` | 正在 CPU 上执行**或**等待 CPU | 死循环/密集计算就是它；注意 IO 阻塞也显示 RUNNABLE（见下方陷阱） |
| `BLOCKED` | 等待获取 synchronized 锁 | 多个线程 BLOCKED + 一个持有锁不释放 → 锁竞争/死锁 |
| `WAITING` | 无限期等待（wait/join/park） | 线程池空闲线程的正常状态 |
| `TIMED_WAITING` | 限时等待（sleep/wait(timeout)/parkNanos） | 正常的定时等待 |
| `NEW` / `TERMINATED` | 未启动/已结束 | 不参与排查 |

**判断问题的三板斧：**

1. **看线程名**：GC 线程 → 内存问题；业务线程 → 看堆栈；线程总数异常多（几千个）→ 线程池失控
2. **看状态**：高 CPU 线程应是 RUNNABLE；大量 BLOCKED 在同一把锁 → 锁竞争；`Found one Java-level deadlock` 字样 → jstack 已自动检出死锁
3. **看堆栈顶**：
   - 顶是业务类（`com.xxx.XxxService.method:87`）→ 直接定位代码行
   - 顶是 `Pattern$Curly.match0` 重复几百层 → 正则回溯
   - 顶是 `java.util.HashMap.get` 且 JDK7 → HashMap 成环死循环
   - 顶是 socket read/write → 不是 CPU 问题，是 IO 等待（RUNNABLE 陷阱）

> **RUNNABLE 陷阱**：Java 的 RUNNABLE 状态包含"阻塞在 native IO"的情况（如 socket read），因为内核层面线程在等数据，JVM 层面仍标记 RUNNABLE。所以看到 RUNNABLE + 堆栈顶是 IO 调用，实际 CPU 并不高——**判断 CPU 消耗要以 top -Hp 为准，jstack 只负责回答"它在干什么"**。

> **单次 jstack 的局限**：线程栈是瞬时快照，死循环每次抓都在同一行 → 实锤；偶发卡顿需要连抓 3~5 次（`jstack pid >> dump.txt` 间隔几秒）对比，多次出现在同一堆栈的才是热点。

#### 方式二：Arthas（生产推荐）

```bash
# 一键定位最耗 CPU 的线程
thread -n 3                     # 展示 CPU 最高的 3 个线程及堆栈

# 找出死锁
thread --deadlock

# 方法级耗时分析
profiler start                  # async-profiler 生成火焰图
profiler stop --format html

# 监控方法执行耗时
trace com.xxx.Service method -n 5 --skipJDKMethod false
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `thread -n 3` | `-n 3` | 展示 CPU 占用 top N 的线程及完整堆栈（n = number） |
| `thread --deadlock` | `--deadlock` | 只输出存在死锁的线程组 |
| `profiler start/stop` | `--format html` | 采样结果输出格式，html 即火焰图（默认 svg）；基于 async-profiler，低开销可生产用 |
| `trace <类> <方法>` | `-n 5` | 捕获 5 次调用后自动退出（防止一直刷屏） |
| | `--skipJDKMethod false` | 不跳过 JDK 内部方法，展示完整调用链每跳耗时（默认 true 只看业务代码） |

#### 方式三：async-profiler / JFR（火焰图）

```bash
# async-profiler 采样 CPU 火焰图
./asprof -d 30 -f cpu.html <pid>

# JDK 自带 JFR（JDK11+）
jcmd <pid> JFR.start duration=60s filename=recording.jfr
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `./asprof -d 30 -f cpu.html <pid>` | `-d 30` | 采样时长（duration）30 秒 |
| | `-f cpu.html` | 输出文件名（file），`.html` 后缀自动生成火焰图 |
| | `<pid>` | 目标 JVM 进程号 |
| `jcmd <pid> JFR.start` | `duration=60s` | 录制 60 秒后自动停止 |
| | `filename=recording.jfr` | 输出文件，用 JDK Mission Control（jmc）打开分析 |
| | 配套命令 | `JFR.stop`/`JFR.dump`（手动停止/转储）、`JFR.check`（查看录制状态） |

### 2.5 Java 场景题标准回答模板

> **第一步**：`top` 确认进程，排除是否流量上涨（看 QPS 监控）。
> **第二步**：`top -Hp` 找到高 CPU 线程，`printf %x` 转十六进制。
> **第三步**：`jstack` 抓栈（或 Arthas `thread -n 3`）：
> - 如果是 **GC 线程** → 转入内存排查（jstat -gcutil、jmap dump、MAT 分析）
> - 如果是**业务线程** → 看堆栈定位代码：死循环 / 正则 / 大计算
> **第四步**：验证修复后持续观察 GC 频率和 CPU 曲线。

### 2.6 Java 实战排查案例（完整走一遍）

**背景**：订单服务（4C8G 容器，JDK8）突然 CPU 飙到 400%，RT 从 50ms 涨到 2s，QPS 无明显变化。

**第一步：top 确认进程**

```bash
top -c
#   PID USER      %CPU  %MEM     TIME+ COMMAND
#  12345 app      399.7  25.1  120:33 java -Xmx4g -jar order-service.jar
# QPS 没涨但 CPU 400%（4 核全满）→ 排除流量问题，是代码或 GC 问题
```

**第二步：top -Hp 定位线程**

```bash
top -Hp 12345
#   PID USER      %CPU  %MEM     TIME+ COMMAND
#  12378 app       98.9  25.1   60:12 java   ← 线程 12378 吃满一个核
#  12379 app       98.9  25.1   60:08 java   ← 线程 12379 也吃满一个核
#  12380 app       98.7  25.1   60:15 java   ← 线程 12380
#  12381 app       98.5  25.1   59:58 java   ← 线程 12381
# 4 个线程各占 100%，加起来正好 400% → 高度怀疑 GC 线程（GC 线程数 = CPU 核数）
```

**第三步：转十六进制 + jstack 定位**

```bash
printf "%x\n" 12378
# 306a

jstack 12345 | grep -A 30 "0x306a"
# "GC task thread#0 (ParallelGC)" prio=5 tid=0x00007f8e0408e000 nid=0x306a runnable
# → 果然是 GC 线程！CPU 高的根因是 GC 风暴，转入内存排查
```

**第四步：jstat 确认 GC 风暴**

```bash
jstat -gcutil 12345 1000
#  S0     S1     E      O      M     YGC    YGCT    FGC    FGCT     GCT
#  0.00 100.00 100.00  99.98  95.12   8921  120.334  1567   890.221 1010.555
#  0.00 100.00 100.00  99.99  95.12   8922  120.456  1568   890.876 1011.332
# O 列 99.98% 且 FGC 每秒 +1 → 老年代打满，持续 Full GC，且每次都回收不掉
# Full GC 后 O 仍是 99% → 老年代里有回收不掉的对象 → 内存泄漏！
```

**第五步：jmap + MAT 找泄漏源**

```bash
jmap -histo 12345 | head -10
#  num     #instances         #bytes  class name
#     1:      15234567      1096872864  [Lcom.order.dto.OrderItemDTO;
#     2:      15234567      365630008  com.order.dto.OrderItemDTO
# OrderItemDTO 实例 1500 万个，占 1GB+ → 明显异常

jmap -dump:live,format=b,file=/tmp/heap.hprof 12345
# 下载到本地，MAT 打开 → Leak Suspects 报告：
# "One instance of com.order.cache.OrderLocalCache occupies 2.8GB"
# → Path to GC Roots 显示：OrderLocalCache.orderMap (静态 HashMap) 持有全部 OrderItemDTO
```

**第六步：定位代码并修复**

```java
// 问题代码：本地缓存只写不删，订单状态更新后旧对象永不清理
public class OrderLocalCache {
    private static final Map<Long, List<OrderItemDTO>> orderMap = new HashMap<>();
    public static void put(Long orderId, List<OrderItemDTO> items) {
        orderMap.put(orderId, items); // 无上限、无过期 → 无限膨胀
    }
}

// 修复：换成有界的 Caffeine 缓存
private static final Cache<Long, List<OrderItemDTO>> orderCache = Caffeine.newBuilder()
        .maximumSize(100_000)                    // 上限 10 万条
        .expireAfterWrite(10, TimeUnit.MINUTES)  // 10 分钟过期
        .build();
```

**第七步：验证**

```bash
# 上线后观察：FGC 归零，O 列稳定在 60%，CPU 回落到 30%
jstat -gcutil 12345 5000
#  S0     S1     E      O      M     YGC    YGCT   FGC    FGCT     GCT
#  0.00  45.23  62.11  61.35  95.12   9102  122.101   1567   890.221 1012.322
# FGC 不再增长 → 修复成功
```

**案例复盘**：CPU 400% → 4 个 GC 线程各占一核 → 根因是静态 HashMap 缓存泄漏 → Full GC 回收不掉 → GC 风暴。**"CPU 问题"最终查成了"内存问题"**，这正是 2.2 节说的最高频陷阱。

### 2.7 Java 实战排查案例二：业务代码死循环（非 GC 风暴）

**背景**：营销服务（4C8G，JDK8）某天 14:00 上线新活动后，单台机器 CPU 飙到 105%（单核打满），其余机器正常。RT 无明显上涨（死循环不处理请求，不占接口耗时），但该机器 load 持续 4.0。

**第一步：top 确认进程**

```bash
top -c
#   PID USER      %CPU  %MEM     TIME+ COMMAND
#  23456 app      103.2  18.3   45:12 java -Xmx4g -jar marketing-service.jar
# 注意：是 105% 而不是 400% → 单线程死循环的典型特征（一个线程只能烧一个核）
```

**第二步：top -Hp 定位线程**

```bash
top -Hp 23456
#   PID USER      %CPU  %MEM     TIME+ COMMAND
#  23489 app       99.8  18.3   45:01 java   ← 线程 23489 独占一个核
#  23500 app        0.3  18.3    0:01 java
#  23501 app        0.2  18.3    0:00 java
# 只有一个线程 100%，其余线程几乎空闲 → 业务线程死循环嫌疑最大
# （对比：GC 风暴是 N 个 GC 线程各占一核，N = CPU 核数）
```

**第三步：转十六进制 + jstack 定位**

```bash
printf "%x\n" 23489
# 5bd1

jstack 23456 | grep -A 30 "0x5bd1"
# "pool-3-thread-5" prio=5 tid=0x00007f8e04123800 nid=0x5bd1 runnable [0x00007f8d9c1f6000]
#    java.lang.Thread.State: RUNNABLE
#         at java.util.regex.Pattern$Curly.match0(Pattern.java:4224)
#         at java.util.regex.Pattern$GroupCurly.match0(Pattern.java:4478)
#         at java.util.regex.Pattern$GroupCurly.match0(Pattern.java:4478)   ← 递归回溯！
#         at java.util.regex.Pattern$GroupCurly.match0(Pattern.java:4478)
#         ...（重复几百行）
#         at java.util.regex.Pattern.matcher(Pattern.java:1741)
#         at com.marketing.activity.ActivityValidator.checkNickname(ActivityValidator.java:87)
#         at com.marketing.activity.JoinActivityService.join(JoinActivityService.java:45)
# → 不是 GC 线程！是业务线程卡在正则回溯，直接定位到类和行号：
#   ActivityValidator.java:87 的正则 + GroupCurly 递归 = 灾难性回溯
```

**第四步：jstat 顺手排除 GC 风暴（验证判断）**

```bash
jstat -gcutil 23456 1000
#  S0     S1     E      O      M     YGC    YGCT    FGC    FGCT     GCT
#  0.00  55.20  33.11  42.35  96.12    120   1.334     0     0.000    1.334
# O 列 42%、FGC = 0 → GC 完全正常，排除 GC 风暴，确认是纯业务代码问题
```

**第五步：定位问题代码**

```java
// ActivityValidator.java:87 —— 校验用户昵称是否含违规词
private static final Pattern NICKNAME_PATTERN =
        Pattern.compile("(a+)+$");   // 问题正则：嵌套量词

public boolean checkNickname(String nickname) {
    return NICKNAME_PATTERN.matcher(nickname).matches();  // 第 87 行
}

// 攻击/巧合输入："aaaaaaaaaaaaaaaaaaaaaaaaaaaaab"（30 个 a + b）
// (a+)+ 的嵌套量词导致回溯路径指数级增长（2^n），单次 matches() 卡死数分钟
// 特征：只有特定输入触发 → 所以只有部分用户请求卡死，且该机器恰好处理了这些请求
```

**第六步：修复**

```java
// 修复方案一：消除嵌套量词（本例语义等价）
private static final Pattern NICKNAME_PATTERN = Pattern.compile("a+$");

// 修复方案二：使用占有优先量词（possessive quantifier），匹配失败不回溯
private static final Pattern NICKNAME_PATTERN = Pattern.compile("(?>(a+)b)");

// 修复方案三（兜底防御）：设置正则匹配超时，避免任何正则卡死线程
// JDK 自带 Pattern 无超时参数，可用第三方库或线程池 + Future.get(timeout) 包裹
```

**第七步：验证**

```bash
# 上线后观察：CPU 回落到 25%，load 1.0
top -Hp 23456
#   PID USER      %CPU  %MEM     TIME+ COMMAND
#  23489 app        2.1  18.3    0:00 java   ← 原死循环线程恢复正常
# 用问题输入 "aaaa...ab" 回归测试：matches() 毫秒级返回
```

**案例复盘**：单核 105%（单线程特征）→ top -Hp 只有一个线程 100% → jstack 堆栈直接看到 `GroupCurly.match0` 递归几百层 → 正则灾难性回溯。**与 GC 风暴案例的对比**：

| 对比项 | GC 风暴（案例一） | 正则回溯（案例二） |
|--------|------------------|-------------------|
| CPU 特征 | N 核全满（N = GC 线程数） | 单核打满 |
| 高 CPU 线程 | GC task thread | 业务线程（线程池命名） |
| jstat 表现 | O 列 99%+，FGC 狂涨 | 完全正常 |
| jstack 堆栈 | GC 线程栈 | Pattern$Curly/GroupCurly 递归栈 |
| 触发条件 | 运行一段时间后 | 特定输入 + 特定正则 |

**面试话术**：这两个案例合起来就是完整的回答——"先 top -Hp 看高 CPU 线程的**数量和名字**：多个 GC 线程各占一核 → 查 GC；单个业务线程占一核 → jstack 看堆栈直接定位代码行"。线程数量和线程名就是分叉路口。

---

## 三、Go 视角：CPU 飙高的可能原因

### 3.1 原因分类

| 排名 | 原因 | 说明 |
|------|------|------|
| 1 | **goroutine 泄漏 / 暴涨** | goroutine 无限创建或阻塞不退出，调度开销剧增 |
| 2 | **死循环** | for 循环无退出条件、channel 消费逻辑空转 |
| 3 | **GC 频繁** | 内存分配速率过高，GC 触发间隔过短 |
| 4 | **GOMAXPROCS 配置问题** | 容器 limit 4 核但 GOMAXPROCS=宿主机核数，调度竞争 |
| 5 | **计算密集** | JSON 序列化、加密、压缩（Go 的 json 包较慢） |
| 6 | **锁竞争** | sync.Mutex 高竞争，runtime futex 调用频繁 |
| 7 | **CGO 调用** | cgo 调用开销大且涉及线程切换 |

### 3.2 goroutine 泄漏（Go 特有的高频考点）

```go
// 典型泄漏 1：channel 无消费者，goroutine 永久阻塞
func leak() {
    ch := make(chan int) // 无缓冲
    go func() {
        ch <- 1 // 没人接收，永久阻塞
    }()
}

// 典型泄漏 2：for select 忘记 return
for {
    select {
    case <-ctx.Done():
        return // 忘记写这行就泄漏了
    case msg := <-ch:
        handle(msg)
    }
}

// 典型泄漏 3：http resp.Body 未关闭，连接池耗尽
resp, _ := http.Get(url)
// defer resp.Body.Close() 忘记写
```

**泄漏后果**：goroutine 数量持续增长 → 调度器开销增大 + 每个 goroutine 栈内存累积 → CPU 和内存双高。

### 3.3 GOMAXPROCS 与容器配额（重要陷阱）

- Go runtime 默认 `GOMAXPROCS = 宿主机 CPU 核数`。
- 容器 limit 为 2 核时，Go 仍会按宿主机（如 64 核）创建 P，导致大量线程被 CFS throttling，**表现为 RT 毛刺 + CPU 利用率虚高**。
- 解决方案：
  ```go
  import _ "go.uber.org/automaxprocs" // 自动读取 cgroup limit 设置 GOMAXPROCS
  ```

### 3.4 Go 排查工具链（pprof 全家桶）

#### 方式一：runtime pprof（代码内置）

```go
import _ "net/http/pprof"

func main() {
    go func() {
        http.ListenAndServe("0.0.0.0:6060", nil)
    }()
    // ... 业务代码
}
```

#### 方式二：命令行分析

```bash
# 1. CPU profile 采样 30 秒
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30

# 2. 常用命令
(pprof) top 10          # CPU 消耗最高的 10 个函数
(pprof) list funcName   # 看函数内部哪一行最耗 CPU
(pprof) web             # 生成调用图（需 graphviz）

# 3. 火焰图
go tool pprof -http=:8080 profile.pb.gz
# 浏览器打开 → View → Flame Graph
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `go tool pprof <url>` | `?seconds=30` | 采样 30 秒后返回 profile 文件（CPU profile 默认 30s，可调整） |
| `(pprof) top 10` | `10` | 按 flat（自身耗时）排序展示前 10 个函数；`top -cum` 按累计耗时排序 |
| `(pprof) list <func>` | 函数名 | 逐行展示该函数各行的 CPU 消耗，定位到具体代码行 |
| `(pprof) web` | 无参 | 生成调用关系图（需安装 graphviz） |
| `-http=:8080` | `:8080` | 启动本地 web 服务展示火焰图/调用图，浏览器访问 localhost:8080 |

#### 方式三：goroutine 数量检查

```bash
# 查看 goroutine 数量（泄漏判断第一步）
curl http://localhost:6060/debug/pprof/goroutine?debug=1 | head -5

# 数量持续增长不回落 → 泄漏
# dump 所有 goroutine 栈，找阻塞在 chan send/receive 的
```

**命令参数说明：**

| 参数 | 含义 |
|------|------|
| `?debug=1` | 以文本格式输出 goroutine 汇总：第一行是总数，下面按"创建来源函数"分组统计（泄漏排查最常用） |
| `?debug=2` | 输出**每个 goroutine 的完整堆栈**（类似 jstack），用于定位具体阻塞点 |
| `?debug=0` | 默认值，输出 protobuf 格式（供 `go tool pprof` 分析） |

#### 方式四：trace（调度级分析）

```bash
# 采集执行 trace
curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=10
go tool trace trace.out
# 可看：goroutine 调度延迟、GC 停顿、syscall 阻塞、网络等待
```

**命令参数说明：**

| 命令 | 参数 | 含义 |
|------|------|------|
| `curl -o trace.out` | `-o` | 把响应写入指定文件（output）；trace 是二进制格式必须落盘 |
| | `?seconds=10` | 采集 10 秒执行轨迹；trace 数据量大，不宜过长（一般 5~10s） |
| `go tool trace trace.out` | 无参 | 解析 trace 文件并启动 web 界面：View trace（时间线）、Goroutine analysis（调度分析）等 |

### 3.5 Go 场景题标准回答模板

> **第一步**：看监控确认 goroutine 数量是否暴涨（`/debug/pprof/goroutine`）。
> **第二步**：抓 CPU profile（30 秒采样），`top` 看热点函数：
> - `runtime.mallocgc` / `runtime.gcBgMarkWorker` 占比高 → GC 频繁，查内存分配热点（`alloc_space`）
> - 业务函数占比高 → 直接定位代码：死循环 / 密集计算
> - `runtime.futex` / `lock2` 高 → 锁竞争
> **第三步**：goroutine dump 找阻塞点（channel、锁、IO）。
> **第四步**：检查 GOMAXPROCS 与容器 limit 是否匹配。
> **第五步**：必要时用 trace 看调度延迟和 GC 停顿。

### 3.6 Go 实战排查案例（完整走一遍）

**背景**：网关服务（Go 1.21，容器 limit 4C）运行 3 天后 CPU 从 20% 缓慢爬升到 380%，重启后恢复正常，几天后又复现。

**第一步：先看 goroutine 数量（Go 排查第一反应）**

```bash
curl http://localhost:6060/debug/pprof/goroutine?debug=1 | head -5
# goroutine profile: total 185342
# 90001 @ 0x43f2c1 0x44c817 0x44c7f0 0x47e2d1 0x47e2a4
# 90001 @ 0x43f2c1 0x44c817 0x44c7f0 0x47e2d1 0x47e2a4
# goroutine 总数 18.5 万！正常应该只有几千 → goroutine 泄漏实锤
# 且 9 万个 goroutine 来自同一个调用栈 → 同一处代码泄漏
```

**第二步：dump 堆栈定位泄漏点**

```bash
curl "http://localhost:6060/debug/pprof/goroutine?debug=2" > goroutine.txt
grep -c "chan receive" goroutine.txt
# 180001 → 18 万个 goroutine 阻塞在 channel 接收上

# 看具体堆栈：
# goroutine 90001 [chan receive, 300 hours]:
# gateway/backend.(*Pool).WatchConfig(0xc000120000)
#         /app/src/gateway/backend/pool.go:87 +0x104
# → 9 万个 goroutine 卡在 pool.go:87 的 channel 接收，已阻塞 300 小时
```

**第三步：CPU profile 确认 CPU 消耗构成**

```bash
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30
(pprof) top 5
# Showing nodes accounting for 3.6s, 95.2% of 3.78s total
# flat  flat%   sum%      cum   cum%
# 1.9s 50.2%   50.2%     1.9s 50.2%  runtime.findrunnable
# 0.9s 23.8%   74.0%     0.9s 23.8%  runtime.schedule
# 0.4s 10.5%   84.5%     0.4s 10.5%  runtime.mallocgc
# findrunnable + schedule 占 74% → 调度器在 18 万个 goroutine 之间疲于奔命
# 这就是 CPU 高的主因：goroutine 太多，调度开销爆炸
```

**第四步：定位问题代码**

```go
// pool.go —— 每次配置刷新都启动一个新的 WatchConfig goroutine，旧的永不退出
func (p *Pool) Refresh() {
    p.reload()                          // 重新加载配置
    go p.WatchConfig()                  // 每次刷新再起一个 watcher，旧 watcher 没被通知退出
}

func (p *Pool) WatchConfig() {
    for {
        select {
        case <-p.configCh:              // pool.go:87 —— 旧 watcher 的 configCh 已被替换
            p.reload()                  //         永远等不到消息，永久阻塞
        }
    }
}

// 修复：加 context 退出机制，Refresh 时取消旧 watcher
func (p *Pool) Refresh() {
    p.reload()
    p.cancel()                          // 取消旧 watcher
    p.ctx, p.cancel = context.WithCancel(context.Background())
    go p.WatchConfig(p.ctx)
}

func (p *Pool) WatchConfig(ctx context.Context) {
    for {
        select {
        case <-ctx.Done():              // 被取消时退出，不再泄漏
            return
        case <-p.configCh:
            p.reload()
        }
    }
}
```

**第五步：验证**

```bash
# 上线后观察 3 天：
curl http://localhost:6060/debug/pprof/goroutine?debug=1 | head -1
# goroutine profile: total 3210        ← 从 18.5 万降到 3 千，恢复正常
# CPU 稳定在 25%，不再爬升
```

**案例复盘**：CPU 缓慢爬升 + 重启恢复 → goroutine 泄漏 → 18 万 goroutine 阻塞在旧 channel 上 → 调度开销（findrunnable/schedule）吃掉 74% CPU。**Go 的 CPU 问题第一反应先查 goroutine 数量**，这是与 Java 排查思路最大的差异。

---

## 四、三视角对比总结

| 维度 | 通用 | Java | Go |
|------|------|------|-----|
| 最高频原因 | 死循环、流量上涨 | GC 风暴、死循环 | goroutine 泄漏、GC 频繁 |
| 线程模型 | OS 线程 | JVM 线程（1:1 映射内核线程） | M:N 调度（G-P-M） |
| 核心工具 | top/vmstat/perf | jstack/jstat/Arthas/async-profiler | pprof/trace |
| 火焰图 | perf + FlameGraph | async-profiler / JFR | go tool pprof 原生支持 |
| 容器陷阱 | throttling、超卖 | JDK8u191 前不感知 limit | GOMAXPROCS 不感知 limit |
| 特有陷阱 | 挖矿、中断风暴 | HashMap 成环、正则回溯 | channel 泄漏、cgo 开销 |

## 五、面试回答要点（30 秒版本）

1. **先分类**：CPU 高 = 用户态（业务代码）还是内核态（系统调用/锁）？
2. **先看 GC**：Java/Go 中 CPU 高的第一嫌疑人是 GC，先排除内存问题。
3. **定位到线程/goroutine**：top -Hp + jstack / pprof goroutine。
4. **火焰图收尾**：复杂问题用 async-profiler / pprof 火焰图定位热点函数。
5. **容器环境**：记得检查 throttling 和 GOMAXPROCS/JVM 参数是否感知 limit。
