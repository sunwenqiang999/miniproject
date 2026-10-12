# Go 并发编程面试知识点

## 一、goroutine 与 GMP 调度模型（必考，Go 面试第一核心）

### 1.1 goroutine vs 线程（必考对比）

| 对比项 | goroutine | OS 线程（Java Thread） |
|--------|-----------|----------------------|
| 栈内存 | 初始 **2KB**，动态伸缩（最大 1GB） | 固定 **MB 级**（Java 默认 512K~1M） |
| 调度者 | Go runtime **用户态**调度 | OS 内核调度 |
| 切换成本 | ~200ns（保存少量寄存器，纯用户态） | ~μs 级 + 内核态切换 |
| 创建成本 | 极低，可百万级 | 高，需线程池复用 |
| 通信 | channel（CSP） | 共享内存 + 锁 |
| 与内核线程关系 | **M:N 模型**（M 个 goroutine 跑在 N 个线程上） | 1:1 模型 |

**为什么 Go 用 M:N 而 Java 用 1:1？**
- 1:1 模型下高并发只能靠线程池限流 + 异步回调（CompletableFuture/响应式），编程心智负担重。
- M:N 模型下每个请求一个 goroutine，**同步写法获得异步性能**，代码可读性碾压回调地狱。
- Java 的应对：JDK 21 虚拟线程（Virtual Thread，Project Loom），向 Go 看齐。

### 1.2 GMP 三要素（必考）

```text
┌────────────────────────────────────────────────────┐
│                    GMP 模型                          │
│                                                     │
│   G（Goroutine）                                     │
│   ├─ 栈、指令指针、调度信息                            │
│   ├─ 全局队列 GRQ ──────────────────────┐           │
│   └─ P 的本地队列 LRQ（每 P 容量 256）     │           │
│                                          ▼           │
│   P（Processor，逻辑处理器）              G0 G1 G2...  │
│   ├─ 数量 = GOMAXPROCS（默认 CPU 核数）   ┌───────┐   │
│   ├─ 持有调度资源：LRQ、mcache、定时器      │   P   │   │
│   └─ 决定"同时并行"的 goroutine 上限       └───┬───┘   │
│                                               │绑定    │
│   M（Machine，OS 线程封装）                    ▼       │
│   ├─ 真正干活的载体，绑定 P 才能执行 G        ┌───┐    │
│   ├─ 数量默认上限 10000                      │ M │════ OS Thread
│   └─ M 阻塞时 P 可 Hand Off 给其他 M          └───┘    │
└────────────────────────────────────────────────────┘
```

| 组件 | 含义 | 关键点 |
|------|------|--------|
| G | Goroutine | 调度与执行的基本单位；g0 是每个 M 的调度栈 G |
| M | Machine | OS 线程封装，必须绑定 P 才能执行 G |
| P | Processor | 调度上下文，数量决定**并行度**（不是并发度）；M 与 P 数量无固定关系 |

### 1.3 调度策略（必考）

**① 本地优先 + 队列流转**

```text
go func() 创建 G
   │
   ▼
优先放入当前 P 的 LRQ（本地队列，无锁）── LRQ 满（256）──→ 一半移入 GRQ（全局队列，有锁）
   │
   ▼
M 从绑定的 P 的 LRQ 取 G 执行（每 61 次调度 tick 检查一次 GRQ，防全局队列饥饿）
```

**② 工作窃取（Work Stealing）**
P 的 LRQ 空了 → 先从 GRQ 拿一批 → 还没有则**从其他 P 偷一半 G**（保证负载均衡，所有 M 不闲着）。

**③ Hand Off（移交）**
M 因**系统调用阻塞**（如文件 IO、网络同步调用）时，P 与 M 分离，带着 LRQ 挂到**空闲 M 或新建 M** 上继续执行其他 G —— 一个 goroutine 阻塞不会拖垮整个 P 上的 goroutine。

**④ 抢占式调度**

| 版本 | 机制 | 局限 |
|------|------|------|
| Go 1.13 前 | **协作式抢占**：函数调用入口检查抢占标记 | **纯计算死循环**（无函数调用）无法抢占，饿死同 P 其他 G，甚至 GC 无法 STW |
| Go 1.14+ | **基于信号的异步抢占**：sysmon 发 SIGURG 信号打断长时间运行的 G（>10ms） | 彻底解决死循环问题 |

**⑤ sysmon 后台监控**
无 P 绑定的独立 M 上运行：抢占超长 G、强制 GC（2 分钟未 GC）、netpoll 就绪检查、retake 被阻塞的 P。

### 1.4 GOMAXPROCS 与常见追问

- `GOMAXPROCS` 默认 = CPU 核数，决定**同时运行的 P 数量**（并行上限）。
- 容器（K8s）环境注意：Go 1.20 前 runtime 读取的是宿主机核数而非 cgroup 限制，需用 `uber-go/automaxprocs` 自动适配，否则 P 过多导致频繁切换。
- 调大 GOMAXPROCS 不一定提速：超过核数后 P 竞争 M 反而增加切换开销。

### 1.5 GMP 下 IO 阻塞与唤醒全流程（必考深挖）

**前置：两种 IO 的本质区别（先分清这个，后面全通）**

| | 网络 IO | 文件 IO / 阻塞系统调用 / CGO |
|---|---|---|
| fd 模式 | non-blocking + epoll/kqueue/IOCP | 真正的阻塞式系统调用 |
| 阻塞对象 | **只有 G**，M 不阻塞 | **G 和 M 一起阻塞**（OS 线程陷入内核） |
| 对 P 的处理 | P 不动，M 拿着 P 继续调度其他 G | P 被剥离（handoff），否则 LRQ 中的 G 饿死 |
| 管理机制 | **netpoller** | **sysmon retake + handoff** |

**① 网络 IO：netpoller 路径（阻塞的是 G，不是线程）**

```text
【阻塞阶段】
conn.Read()
   │  （fd 在 dial/accept 时已设 non-blocking 并 epoll_ctl 注册进 netpoller，
   │    每个 fd 对应一个 pollDesc，pollDesc.rg / wg 记录读 / 写等待的 G）
   ▼
非阻塞 read 返回 EAGAIN（数据未就绪）
   ▼
gopark() 挂起：
   ├─ G 状态 _Grunning → _Gwaiting（waitReasonIOWait）
   ├─ G 指针存入 pollDesc.rg（"这个 G 在等该 fd 可读"）
   └─ mcall 切到 g0 栈，dropg() 解除 G 与 M 的绑定
   ▼
M 毫发无损，继续 schedule() 从 P 的 LRQ 取下一个 G 执行
—— socket 非阻塞，OS 线程根本没陷入内核，P 无需换手，并行度零损失
—— 这是 Go 用少量线程支撑百万连接的根本原因
```

```text
【唤醒阶段】
谁来轮询 epoll？两个触发点：
   ① sysmon：不需要 P 的独立 M，循环调 netpoll(0)（非阻塞 epoll_wait，10ms 节流）
   ② findRunnable：调度器在本地 / 全局队列都找不到 G 时，顺手调一次 netpoll(0)
   ▼
epoll_wait 返回就绪事件 → 通过 event.data 找到 pollDesc
   → netpollready() 从 pollDesc.rg 取出等待的 G
   ▼
injectglist()：G 状态 _Gwaiting → _Grunnable，批量追加到【全局队列 GRQ】队尾
   ▼
wakep()：有空闲 P 则唤醒 / 创建一个 M 接手调度
   ▼
调度循环从 GRQ 取出该 G → read 重试成功 → 返回用户代码
```

> **为什么唤醒后放全局队列而不是 P 本地队列？** netpoller 是全局组件，执行轮询的 sysmon **本身不持有 P**，没有本地队列可用；且唤醒时刻也无法确定该 G 原来属于哪个 P。

**② 文件 IO / 阻塞系统调用：handoff 路径（牺牲一个 M，保住 P）**

```text
【阻塞阶段】
G 执行 syscall.Read 前先 entersyscall()：
   ① 保存 G 现场（SP / PC 存入 g.sched）
   ② G → _Gsyscall；P → _Psyscall（P 暂时还挂在 M 上）
   ▼
M 带着 G 陷入内核，OS 线程真正阻塞在 read 上
（此时 P 名存实亡：LRQ 里的其他 G 拿不到执行机会）
   ▼
sysmon retake()：发现 P 处于 _Psyscall 超过一个 sysmon tick（约 20us）且 LRQ 非空
   ▼
handoffp()：把 P 从阻塞 M 上强行剥离
   → P 交给空闲 M（没有则 startm 新建）
   → P 连同整个 LRQ 一起"改嫁"，其他 G 继续执行
```

```text
【唤醒阶段】syscall 返回，M 从内核态回到用户态，执行 exitsyscall()，两条路径：
   ├─ 快路径 exitsyscallfast()：P 还没被抢走（仍是 _Psyscall，可重新绑定）
   │    → G 直接在原 M + 原 P 上无缝续跑
   │    （常见场景：页缓存命中，syscall < 20us，sysmon 还没来得及剥离）
   │
   └─ 慢路径 exitsyscall0()：P 已被 handoff
        ① G → _Grunnable，globrunqput 放入全局队列队尾
        ② M 与 P 解绑，stopm() 进入空闲线程列表（sched.midle）休眠
        ③ 后续调度器从 GRQ 取出该 G 恢复执行
```

**③ 两种路径对比总结**

| 维度 | 网络 IO | 文件 IO / 阻塞 syscall |
|---|---|---|
| 挂起函数 | gopark | entersyscall |
| M 是否受影响 | 否 | 是，M 随 G 一起阻塞 |
| P 是否受影响 | 否 | 是，超时被 handoff 剥离 |
| 唤醒者 | netpoller（sysmon / findRunnable 轮询 epoll） | 内核（syscall 返回） |
| 唤醒后去哪 | 全局队列 GRQ | 快路径原地续跑；慢路径 GRQ |
| 对 M 数量的影响 | 几乎不增加 | 高并发会暴涨（上限默认 10000，超过 crash: thread exhaustion） |

**高频追问：**
- **为什么网络 IO 不导致线程暴涨，文件 IO 会？** 网络 IO 阻塞时 M 毫发无损；文件 IO 每个并发调用都要独占一个 M，直到触及线程上限。
- **sysmon 为什么不需要 P 就能运行？** sysmon 是特殊 M，跑在 g0 栈上执行 runtime 内部逻辑（retake、netpoll、强制 GC 检查），不调度用户 G，因此不依赖 P——这也是它能"抢救"被阻塞 P 的前提。
- **为什么阻塞 syscall 必须剥离 P？** P 的数量 = GOMAXPROCS，是并行度天花板。不剥离的话，几个文件 IO 就能占满所有 P，整个程序并行度归零。
- **M 阻塞时 LRQ 里的 G 会丢吗？** 不会，P 带着整个本地队列一起 handoff 给新 M。

### 1.6 netpoller 详解（网络 IO 的多路复用引擎，深挖加分）

**① 是什么 / 解决什么问题**

netpoller 是 Go runtime 对 OS **IO 多路复用**的统一封装，核心使命一句话：**把"阻塞线程等 IO"变成"阻塞 G 等 IO"**，让业务用同步写法获得异步性能。

| 平台 | 底层机制 |
|---|---|
| Linux | **epoll** |
| macOS / BSD | kqueue |
| Windows | IOCP |
| Solaris | event port |

Go 的 net 包中所有 TCP/UDP 连接：在 `dial` / `accept` 时自动把 fd 设为 **non-blocking** 并注册进 netpoller，业务层的 `conn.Read()` 看起来是同步阻塞，底层早已多路复用化。

**② 全局单例架构（与 Netty 的关键区别）**

```text
Go：全局唯一 poller（一个 epoll fd 管理百万连接），没有独立 reactor 线程
    谁需要谁轮询：sysmon 兜底 + 找不到活干的 M 顺手轮询

Netty：Reactor 多线程模型，每个 EventLoop 一个 Selector，连接按 EventLoop 分组
    boss 线程 accept，worker 线程处理 IO，业务靠回调链驱动
```

| 对比项 | Go netpoller | Netty Reactor |
|---|---|---|
| poller 数量 | **全局一个** | 每个 EventLoop 一个 |
| 事件消费方式 | 唤醒挂起的 G，原地续跑 | 触发回调（ChannelFuture / Handler 链） |
| 编程模型 | **同步写法**（对业务完全透明） | 异步回调（回调地狱靠 CompletableFuture 缓解） |
| 线程占用 | 连接不占线程，只占一个 G（2KB 栈起步） | 连接绑定 EventLoop 线程 |

**③ 核心数据结构 pollDesc**

```text
每个 fd 对应一个 pollDesc（由 pollCache 对象池复用，避免反复分配）：
├─ rg（atomic uintptr）：读方向等待者，状态机
│     pdNil(0) → pdWait(2) → G 指针 → pdReady(1)
├─ wg（atomic uintptr）：写方向等待者，状态机同上
├─ closing：fd 关闭标记
└─ 关键约束：同一 fd 同一方向【只允许一个 G 等待】
    多个 G 并发 Read 同一个 conn → runtime throw("double wait")
    （一个 Read + 一个 Write 并发是合法的，rg / wg 各占一个坑位）
```

**④ 关键函数与一次 Read 的完整生命周期**

```text
【注册】dial / accept
   → netpollopen()：epoll_ctl(EPOLL_CTL_ADD)，epoll_event.data 存 pollDesc 指针
     （这就是 epoll_wait 返回后能直接找回 pollDesc 的原因）

【挂起】conn.Read() → 非阻塞 read 返回 EAGAIN
   → pd.waitRead() → netpollblock() → gopark()
     G 指针 CAS 进 pollDesc.rg，G → _Gwaiting，M 继续调度

【轮询】netpoll(0)
   → epoll_wait(timeout = 0)（纯查询，绝不阻塞 M！）
   → 遍历就绪事件，event.data → pollDesc → netpollready() 取出 rg 里的 G

【唤醒】injectglist() + wakep()
   → G：_Gwaiting → _Grunnable，批量入全局队列 GRQ 队尾
   → 唤醒 / 创建 M 消费 GRQ，G 被调度后重试 read 成功返回
```

**⑤ 谁驱动轮询？（Go 没有 reactor 线程，怎么保证事件被及时消费）**

| 触发点 | 说明 |
|---|---|
| **findRunnable**（主路径） | M 在本地队列、全局队列都找不到 G 时，顺手调 `netpoll(0)` —— "借调度器的腿跑 reactor 的活" |
| **sysmon**（兜底） | 后台循环定期 `netpoll(0)`，10ms 节流，防止所有 M 都忙着跑 G 时没人收事件 |

**设计精髓：`epoll_wait` 永远 `timeout=0` 纯查询**。M 绝不在 poller 上阻塞——没事件就立刻返回去干别的活（偷 G、休眠），有事件就顺带唤醒一批 G。这保证了任何 M 都不会被 reactor 职责拖累。

**⑥ 高频考点：epoll 用 LT（水平触发）而不是 ET（边缘触发）**

- Go 注册 epoll 时**不带 `EPOLLET` 标志**，即使用默认的**水平触发**。
- 原因：fd 本身是非阻塞的，配合 LT 语义最简单可靠——**只要数据没读完，epoll 就会持续通知**，不存在丢事件风险；而 ET 要求每次唤醒必须循环读到 EAGAIN 为止，编程复杂、稍有不慎就丢事件导致连接假死。
- Go 的取舍：宁可多收几次重复通知，也要保证正确性。反正 fd 非阻塞，重试 read 成本极低。

**高频追问：**
- **为什么单机百万连接撑得住？** 每个连接的成本 = 一个 fd + 一个挂起的 G（2KB 栈起步），不占任何线程；只有连接就绪时 G 才被唤醒消耗 CPU。百万连接 = 百万个睡眠的 G，调度器无压力。
- **多个 M 同时轮询 epoll 会不会重复唤醒同一个 G？** 不会。`sched.lastpoll` 用 CAS 保证同一时刻只有一个轮询者拿到事件批；pollDesc 的 rg/wg 状态机也是 CAS 操作，事件只会被一个轮询者认领。
- **netpoller 只管网络 IO 吗？** 是。文件 IO（常规文件 fd 不支持 epoll）走 1.5 节的 handoff 路径，这是 Go 网络编程高并发但文件 IO 需要额外注意线程膨胀的根本原因。

---

## 二、Channel 原理与语义（必考）

### 2.1 底层结构与收发流程

```text
hchan
├─ buf（环形队列，cap>0 时存在）
├─ sendx / recvx（环形索引）
├─ sendq（阻塞发送者 sudog 链表）
├─ recvq（阻塞接收者 sudog 链表）
└─ lock（所有操作先抢这把互斥锁）

发送流程（ch <- v）：
1. recvq 有等待接收者 → 直接把值拷贝给对方栈，唤醒它（跳过 buf，零拷贝直达）
2. buf 有空位 → 值拷入 buf[sendx]，sendx++
3. 否则 → 当前 G 打包 sudog 挂入 sendq，gopark 阻塞

接收流程（<-ch）：
1. buf 有值 → 取走 buf[recvx]；若 sendq 有等待者 → 把等待者的值拷入 buf 空位并唤醒
2. buf 无值但 sendq 有等待者（无缓冲 chan）→ 直接从发送者栈拷贝值，唤醒
3. 否则 → 挂入 recvq，gopark 阻塞
```

### 2.2 四种状态语义表（必背）

| 操作 | nil channel | 无缓冲 | 有缓冲 | 已关闭 |
|------|------------|--------|--------|--------|
| 发送 | 永久阻塞 | 无接收者阻塞 | 缓冲满才阻塞 | **panic** |
| 接收 | 永久阻塞 | 无发送者阻塞 | 缓冲空才阻塞 | **零值 + false** |
| close | panic | 唤醒所有阻塞者 | 唤醒所有阻塞者 | panic（重复关闭） |

**高频细节：**
- 关闭后的 channel **接收仍能拿到缓冲内剩余数据**，取空后才返回零值+false。
- `for v := range ch` 自动在 channel 关闭且取空后退出循环——这是优雅关闭消费端的标准模式。
- **谁发谁关**：由**发送方** close（接收方 close 会 panic 发送方）；多发送者场景不能简单 close，用额外的 done channel 或 sync.Once 保证只关一次。
- **nil channel 的妙用**：select 中把某个 case 的 channel 置 nil，该分支永久阻塞 = 动态禁用分支（如收到停止信号后禁用某数据源）。

### 2.3 channel 阻塞的本质：用户态阻塞（必考深挖）

**核心结论：channel 阻塞是 Go runtime 用户态的 G 级阻塞，不是 OS 线程阻塞，不发生内核态切换。**

```text
【阻塞阶段】
ch <- v（无接收者 / 缓冲满） 或  <-ch（无值可取）
   ▼
当前 G 打包成 sudog（G 的等待包装，记录 G、数据元素、所属 channel）
   ▼
持 hchan.lock 挂入 channel 的 sendq / recvq 等待链表，释放锁
   ▼
gopark()：
   ① G 状态 _Grunning → _Gwaiting
   ② mcall 切到 g0 栈，dropg() 解除 G 与 M 的绑定
   ▼
M 不阻塞！继续 schedule() 从 P 的 LRQ 取下一个 G 执行
—— 这就是"一个 goroutine 阻塞在 channel 上，不会拖累同 P 其他 goroutine"的原因
```

```text
【唤醒阶段】由对端 G 直接触发，不经过 netpoller、不经过全局队列
发送方 ch <- v 发现 recvq 有等待者：
   ① 值直接拷贝到接收 G 的栈上（零拷贝直达，跳过 buf）
   ② goready()：接收 G _Gwaiting → _Grunnable
   ③ 放入【当前 P 的本地队列】队尾（局部性好、缓存亲和）
   ▼
接收 G 很快被当前 M 调度执行
```

**为什么 channel 唤醒放 P 本地队列，而网络 IO 唤醒放全局队列？**
channel 的唤醒是"G 对 G 的直接唤醒"，唤醒方本身就持有 P，顺手放本地队列即可；而 netpoller 的轮询者 sysmon 没有 P，只能放全局队列。

**三种阻塞方式终极对比（面试一句话版）**

| | channel 阻塞 | 网络 IO 阻塞 | 文件 IO / 阻塞 syscall |
|---|---|---|---|
| 本质 | 用户态 G 阻塞（gopark + sudog 队列） | 用户态 G 阻塞（gopark + netpoller） | **OS 线程阻塞**（M 陷入内核） |
| M 是否受影响 | 否 | 否 | 是，M 一起阻塞 |
| P 是否受影响 | 否 | 否 | 是，超时 handoff |
| 唤醒者 | 对端 G（goready） | netpoller 轮询 epoll | 内核（syscall 返回） |
| 唤醒后去哪 | **当前 P 本地队列** | 全局队列 GRQ | 快路径原地续跑；慢路径 GRQ |
| 内核态切换 | 无 | 无（epoll_wait 仅查询） | 有 |

> **一句话定调：** channel 阻塞和网络 IO 阻塞都只是"G 让出执行权"，属于 runtime 调度层面的协作式挂起，M 和 P 毫发无损；只有文件 IO / CGO 这类无法 netpoll 化的系统调用，才会真正拖着一个 OS 线程下水。

### 2.4 select 语义（必考）

```go
select {
case v := <-ch1:     // case 就绪则执行
case ch2 <- x:
case <-time.After(3 * time.Second):   // 超时控制
default:             // 都未就绪立即执行（非阻塞）
}
```

| 规则 | 说明 |
|------|------|
| 多 case 同时就绪 | **随机**选一个（防饥饿，不是顺序优先） |
| 无 default 且都未就绪 | 当前 G 挂起，登记到所有相关 channel 的等待队列（任一就绪唤醒） |
| for+select 死循环 | 常配合 done channel 做 goroutine 退出控制 |

### 2.5 经典并发模式（手写题高频）

```go
// ① Worker Pool 工作池（控制并发数消费任务）
func workerPool(jobs <-chan Job, results chan<- Result, n int) {
    var wg sync.WaitGroup
    for i := 0; i < n; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for j := range jobs {        // jobs 关闭后自动退出
                results <- process(j)
            }
        }()
    }
    wg.Wait()
    close(results)                        // 全部 worker 结束后关结果通道
}

// ② Fan-out / Fan-in：多生产者多 channel 合并
func fanIn(chs ...<-chan int) <-chan int {
    out := make(chan int)
    var wg sync.WaitGroup
    for _, ch := range chs {
        wg.Add(1)
        go func(c <-chan int) {
            defer wg.Done()
            for v := range c { out <- v }
        }(ch)
    }
    go func() { wg.Wait(); close(out) }() // 等所有输入结束后关出口
    return out
}

// ③ 超时控制（最常用）
select {
case res := <-ch:
    return res, nil
case <-time.After(3 * time.Second):
    return nil, ErrTimeout
}

// ④ or-done 模式：防止 goroutine 泄漏
func process(done <-chan struct{}, stream <-chan int) {
    for {
        select {
        case <-done:            // 收到退出信号立即返回
            return
        case v, ok := <-stream:
            if !ok { return }
            handle(v)
        }
    }
}

// ⑤ Pipeline：阶段化处理（生成 → 处理 → 聚合，各阶段 channel 串联）
gen := generator(nums)      // chan int
sq := square(gen)           // chan int
for v := range sq { ... }
```

### 2.6 无缓冲 vs 有缓冲的选择

| 场景 | 选择 | 理由 |
|------|------|------|
| 需要严格同步（交接语义） | 无缓冲 | 发送方确认接收方已拿到（同步点） |
| 解耦生产消费速率、削峰 | 有缓冲 | 缓冲吸收瞬时峰值 |
| 缓冲设多大？ | 按吞吐和下游处理速度估算 | 缓冲不是越大越好：过大掩盖下游慢的问题，任务积压延迟高 |

---

## 三、sync 包全家桶（必考）

### 3.1 全家桶速览

| 工具 | 作用 | 对应 Java | 注意事项 |
|------|------|-----------|----------|
| `sync.Mutex` | 互斥锁 | ReentrantLock/synchronized | **不可重入**；不可拷贝已使用的锁 |
| `sync.RWMutex` | 读写锁 | ReentrantReadWriteLock | 读多写少；写等待防饥饿优化 |
| `sync.WaitGroup` | 等待一组 goroutine | CountDownLatch | Add 必须在 go 之前；计数不能为负 |
| `sync.Once` | 只执行一次 | — | f panic 也算已执行 |
| `sync.Map` | 并发安全 map | ConcurrentHashMap | 读多写少/键不相交 |
| `sync.Pool` | 对象池 | ThreadLocal 池化思想 | **GC 会清空**，不能当连接池 |
| `sync.Cond` | 条件变量 | Condition | 配合 Locker；Signal/Broadcast |
| `atomic` | 原子操作 | AtomicInteger 等 | 简单计数；复杂逻辑用锁 |

### 3.2 Mutex 要点深挖（高频）

- **不可重入**：同 goroutine 两次 Lock 直接死锁（与 Java ReentrantLock 可重入相反！）。Go 设计哲学：重入往往是代码结构问题，应显式分层。
- **两种模式**：
  - 正常模式：等待者 FIFO 排队，新来 goroutine 可直接抢（类似非公平锁，吞吐高）。
  - 饥饿模式：尾部队列等待超 1ms 转入，锁直接交给队首，新来者必须排队（防饥饿）。
- **不可拷贝**：Mutex 使用后拷贝会复制内部状态（锁计数、等待队列指针），导致锁失效——结构体含锁时**必须传指针**，`go vet` 可检测。

### 3.3 WaitGroup vs Java CountDownLatch

```go
var wg sync.WaitGroup
for i := 0; i < 10; i++ {
    wg.Add(1)                 // ✅ 必须在 go 之前 Add（否则 Wait 可能先返回）
    go func() {
        defer wg.Done()       // Done 即 Add(-1)，惯用 defer
        work()
    }()
}
wg.Wait()                     // 阻塞至计数归零
```

- Add 放在 goroutine 内部是经典 bug（Wait 可能在 Add 前执行）。
- 计数为负 panic；Wait 返回后 WaitGroup 可复用（与 CountDownLatch 一次性不同，类似 CyclicBarrier 的可复用性，但语义仍是"等一组任务"）。

### 3.4 sync.Once 与单例

```go
var (
    instance *DB
    once     sync.Once
)
func GetDB() *DB {
    once.Do(func() { instance = initDB() })   // 并发安全，保证只执行一次
    return instance
}
```

**坑：** f 内 panic 后 Once 仍标记已执行，后续 Do 不再执行（可能拿到半初始化的 instance）。需自行保证 f 不 panic。

### 3.5 sync.Pool（高频辨析）

- 每个 P 一个本地池（private + shared），Get 无锁快路径；本地没有则偷其他 P 的。
- **GC 时会清空池内对象**（两轮 GC 未复用则释放）——所以**绝对不能当连接池用**（连接可能被 GC 悄悄回收）。
- 正确用途：缓存**临时对象**降低 GC 压力（`bytes.Buffer`、Gin 的 Context 复用）。

```go
var bufPool = sync.Pool{ New: func() any { return new(bytes.Buffer) } }
buf := bufPool.Get().(*bytes.Buffer)
buf.Reset()
defer bufPool.Put(buf)
```

### 3.6 atomic 与锁的选择

- 简单计数/标志位：`atomic.AddInt64`、`CompareAndSwap`（无锁，快）。
- 保护多个字段的一致性或复杂临界区：用锁（atomic 只能保证单变量原子性）。
- Go 1.19+ 新增类型化原子 `atomic.Int64`（防误用，推荐替代函数式 API）。

---

## 四、Context 详解（必考）

### 4.1 四大类型与树形传播

```text
context.Background()（根，永不取消）
    │
    ├─ WithCancel（手动取消）
    ├─ WithTimeout / WithDeadline（超时自动取消）
    └─ WithValue（携带请求域数据：traceID 等）

取消传播：父 ctx cancel → 所有子孙 ctx 的 Done() channel 全部关闭 → 整棵 goroutine 树联动退出
```

### 4.2 底层原理

- **cancelCtx**：内嵌 Context，持有 `done chan struct{}` 和 `children map[canceler]struct{}`；cancel() 时关闭 done 并遍历 children 递归取消。
- **timerCtx**：内嵌 cancelCtx + time.Timer，到期自动 cancel。
- **valueCtx**：单 key 单 value 节点，查找沿父链**逐层向上 O(n)**（所以别滥用 WithValue 传大量数据，也别频繁嵌套）。

### 4.3 使用规范（官方 6 条，必背）

1. Context 作为函数**第一个参数**，命名 `ctx`。
2. 不要存入 struct（除非特殊情况如 gin.Context 本身是请求域），显式透传。
3. Context **并发安全**，可传给任意多个 goroutine。
4. **WithValue 只传请求域数据**（traceID、用户信息），禁止传业务参数/可选参数。
5. WithCancel/WithTimeout 返回的 cancel **必须调用**（通常 `defer cancel()`），否则父节点 children map 中残留，泄漏直到父取消。
6. 不确定用哪个时用 `context.TODO()` 占位。

### 4.4 Context vs Java（对比亮点）

| 能力 | Go Context | Java 对应方案 |
|------|-----------|--------------|
| 取消传播 | ctx.Done() 树形级联 | Future.cancel / Thread.interrupt（需手动层层传递） |
| 超时 | WithTimeout 内建 | Future.get(timeout) / CompletableFuture.orTimeout |
| 请求域数据 | WithValue | ThreadLocal（线程池场景要 TTL 且需手动 remove） |

**Context 的核心优势：** goroutine 是"任务即线程"，ctx 天然绑定任务生命周期沿调用链透传；Java 线程与任务分离（线程池复用），取消和上下文都要额外机制。

---

## 五、常见并发问题（生产高频）

### 5.1 goroutine 泄漏（必考场景题）

**泄漏 = goroutine 永远阻塞在 channel 收发或锁上，无法退出。**

```go
// 案例1：发送方阻塞在无接收者的 channel
func leak() {
    ch := make(chan int)
    go func() {
        v := <-ch          // 函数返回后 ch 无引用，永远阻塞 → 泄漏
        fmt.Println(v)
    }()
}

// 案例2：下游不消费导致上游全部阻塞
func query() chan int {
    ch := make(chan int)   // 无缓冲：调用方不接收则 goroutine 永久阻塞
    go func() { ch <- expensiveQuery() }()
    return ch
}
// 调用方 if err != nil 提前 return 没读 ch → 泄漏
// 修复：ch := make(chan int, 1)（缓冲 1 个，发送不阻塞）
```

**排查手段：**
- `runtime.NumGoroutine()` 持续上涨不回落。
- pprof goroutine profile 看堆栈聚类（`go tool pprof http://x/debug/pprof/goroutine?debug=1` 直接看文本堆栈）。
- 兜底原则：**每个 goroutine 都有明确退出路径**（ctx 取消、done channel、range 等待 channel 关闭）。

### 5.2 数据竞争（Data Race）

```go
// 经典 race：多 goroutine 读写同一变量
var count int
for i := 0; i < 100; i++ {
    go func() { count++ }()   // count++ 非原子（读-改-写）
}
```

- **检测**：`go test -race` / `go run -race`（编译插桩，运行时报 WARNING: DATA RACE，生产别开，有 5~10 倍性能损耗）。
- **修复**：Mutex / atomic / channel 传递所有权 / 不可变数据。

### 5.3 经典错误写法汇总

```go
// 错误1：循环变量捕获（Go 1.22 前）
for _, v := range list {
    go func() { fmt.Println(v) }()   // 全部打印最后一个值
}
// Go 1.22+ 已修复（每轮新变量）；旧版修复：go func(v int){...}(v) 传参

// 错误2：WaitGroup 在 goroutine 内 Add（Wait 可能先返回）
go func() { wg.Add(1); ... }()       // ❌
wg.Add(1); go func() { ... }()       // ✅

// 错误3：select 默认分支导致忙轮询空转 CPU
for {
    select {
    case v := <-ch: handle(v)
    default:                          // 无 default 才是阻塞等待；加 default 变死循环空转
    }
}

// 错误4：拷贝包含锁的结构体
type Counter struct { mu sync.Mutex; n int }
c1 := Counter{}
c2 := c1                             // 拷贝了 mu 内部状态，锁失效！
```

### 5.4 errgroup（生产必备）

```go
import "golang.org/x/sync/errgroup"

g, ctx := errgroup.WithContext(context.Background())
for _, item := range items {
    item := item
    g.Go(func() error {             // 任一任务出错 → 其他任务通过 ctx 收到取消
        return process(ctx, item)
    })
}
if err := g.Wait(); err != nil {    // 返回第一个错误
    return err
}
```

- 比裸 WaitGroup 多了**错误传递**和**联动取消**（WithContext 派生 ctx）。
- `g.SetLimit(n)` 限制并发数（Go 1.20+ 官方 errgroup 支持，替代手写信号量 channel）。

---

## 六、Go 并发 vs Java JUC 对比（加分项）

| 维度 | Go | Java |
|------|-----|------|
| 并发单元 | goroutine（M:N） | Thread（1:1）+ 虚拟线程（JDK21 追赶） |
| 通信哲学 | channel（CSP，通信共享内存） | 共享内存 + 锁（JUC） |
| 互斥锁 | sync.Mutex（不可重入） | synchronized/ReentrantLock（可重入） |
| 等待组 | WaitGroup | CountDownLatch/CyclicBarrier |
| 并发 Map | sync.Map / 分片锁 | ConcurrentHashMap |
| 原子类 | atomic 包 | AtomicInteger 等（CAS 更丰富：StampedReference 等） |
| 对象池 | sync.Pool（GC 会清） | 无内置（ThreadLocal/第三方） |
| 取消机制 | Context（树形级联，语言级） | interrupt/Future.cancel（需手动传递） |
| 线程池 | 不需要（goroutine 廉价）+ errgroup/ants 控并发 | ThreadPoolExecutor 必备 |

**面试话术：** Go 的并发设计是"简单工具组合复杂系统"——goroutine 廉价到可以一请求一协程，channel 把锁内聚到数据结构内部，Context 统一取消语义；Java JUC 是"精细控制"路线，工具更全（可重入锁、 stamped lock、各种阻塞队列），但组合复杂度高。Go 的 mutex 不可重入、没有内置线程池，都是刻意做减法逼你写出简单直接的并发代码。

---

## 七、高频面试题速记

**Q1：goroutine 和线程的区别？**
> ①栈：goroutine 初始 2KB 可伸缩，线程固定 MB 级；②调度：goroutine 用户态 GMP 调度切换 ~200ns，线程内核态切换 ~μs 级；③成本：goroutine 可百万级，线程需池化；④通信：channel CSP vs 共享内存+锁。

**Q2：GMP 模型讲一下？**
> G 是 goroutine（任务），M 是 OS 线程（执行载体），P 是逻辑处理器（调度上下文，数量=GOMAXPROCS 决定并行度）。G 优先入 P 本地队列（满则一半入全局队列）；P 空了先拿全局再从其他 P 偷一半（工作窃取）；M 系统调用阻塞时 P Hand Off 到其他 M；Go 1.14 起 SIGURG 信号异步抢占解决死循环问题；sysmon 后台监控长任务和 GC。

**Q3：channel 关闭后还能读写吗？**
> 写→panic；读→缓冲内剩余数据可取，取空后返回零值+false；重复 close→panic；close nil→panic。原则：发送方负责关闭；for range 自动在关闭且取空后退出。

**Q4：select 的语义？**
> 多 case 就绪随机选一个（防饥饿）；无 default 全未就绪则挂起；default 提供非阻塞。常见用法：超时控制（time.After）、done 退出、nil channel 动态禁用分支。

**Q5：WaitGroup 使用注意？**
> Add 必须在 go 关键字之前（goroutine 内 Add 是经典 bug）；Done 建议 defer；计数不能为负；Wait 返回后可复用。

**Q6：sync.Pool 能当连接池吗？**
> 不能。GC 会清空 Pool 中对象，连接可能被意外回收。Pool 适合缓存临时对象（bytes.Buffer、Gin Context）降低 GC 压力；连接池要用带生命周期管理的（database/sql、redis pool）。

**Q7：Context 的作用和原理？**
> 作用：取消传播（父取消级联所有子孙）、超时控制（WithTimeout/Deadline）、传请求域数据（traceID）。原理：cancelCtx 维护 children map + done channel，cancel 时关 done 并递归取消子节点；valueCtx 链式向上查找 O(n)。规范：首个参数显式传递、不存 struct、cancel 必须 defer 调用。

**Q8：Go 怎么防止 goroutine 泄漏？**
> 每个 goroutine 必须有退出路径：①ctx.Done() 监听取消；②range channel 等发送方关闭；③无缓冲 channel 改缓冲 1 防发送方阻塞；④select 超时兜底。排查：NumGoroutine 监控 + pprof goroutine profile。

**Q9：Mutex 和 RWMutex 怎么选？Go 锁可重入吗？**
> Go 锁**不可重入**（同 goroutine 二次 Lock 死锁），设计上刻意避免（重入说明结构有问题）。读写比悬殊（读多写极少）用 RWMutex；写较多或临界区极短用 Mutex 即可（RWMutex 本身有原子操作开销，低竞争下反而慢）。

**Q10：map 并发读写怎么办？（衔接集合篇）**
> 并发写 fatal error 不可 recover。方案：RWMutex+map（通用）、sync.Map（读多写少、键不相交）、分片锁（高并发读写，如本地缓存）。
