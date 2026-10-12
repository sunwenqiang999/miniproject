# Go 语言后端开发知识点详解

## 阅读建议

Go 面试知识点已按复习模块拆分为五类文档（对标 Java 八股的语言/集合/JUC/JVM 拆分方式）：

| 文档 | 适合阶段 | 对应 Java 模块 | 内容重点 | 面试热度 |
|------|----------|---------------|----------|----------|
| [`go语言基础面试知识点.md`](go语言基础面试知识点.md) | 基础复习 | java语言基础 | 类型系统、值语义、defer、error 体系、interface、反射、泛型、nil 陷阱 | ★★★★ |
| [`go集合数据结构面试知识点.md`](go集合数据结构面试知识点.md) | 源码深入 | java集合 | slice 扩容、string、map 源码级原理（hmap/bmap/渐进式扩容）、sync.Map | ★★★★★ |
| [`go并发编程面试知识点.md`](go并发编程面试知识点.md) | 并发核心 | juc并发编程 | GMP 调度、channel、select、sync 全家桶、Context、并发模式、goroutine 泄漏 | ★★★★★ |
| [`go运行时与GC面试知识点.md`](go运行时与GC面试知识点.md) | 进阶调优 | jvm | 内存分配（TCMalloc）、逃逸分析、三色标记 GC、混合写屏障、GOGC/GOMEMLIMIT、pprof | ★★★★★ |
| [`go后端工程实战面试知识点.md`](go后端工程实战面试知识点.md) | 实战落地 | — | netpoll、HTTP Server/Client 调优、Gin、MySQL/Redis/Kafka、gRPC、微服务治理、工程化 | ★★★★ |

**推荐学习路径：**

```text
语言基础（defer/error/interface 的坑）
    │
    ▼
集合数据结构（slice/map 源码是 Go 面试敲门砖）
    │
    ▼
并发编程（GMP + channel 是 Go 面试第一核心，必拿下）
    │
    ▼
运行时与 GC（三色标记、混合写屏障，冲中大厂必备）
    │
    ▼
工程实战（netpoll、连接池、微服务治理，结合项目讲）
```

## 各文档与 Java 八股的对照关系

Java 转 Go 或双语言准备的面试者，可按此对照表迁移知识：

| Java | Go | 核心差异 |
|------|-----|----------|
| JVM 线程（1:1 内核线程） | goroutine（M:N，GMP 调度） | goroutine 栈 2KB 起、用户态调度、可百万级 |
| synchronized/ReentrantLock（可重入） | sync.Mutex（**不可重入**） | Go 刻意做减法，重入视为代码结构问题 |
| ConcurrentHashMap | sync.Map / 分片锁 | sync.Map 双 map 设计，仅适合读多写少 |
| HashMap（红黑树，一次性 2 倍扩容） | map（桶 8 槽位 + 溢出桶，**渐进式扩容**） | Go 扩容摊到每次读写，负载因子 6.5 |
| ThreadPoolExecutor | goroutine + errgroup（无需线程池） | goroutine 廉价，池化需求弱 |
| ThreadLocal | 无（用 Context 显式传递） | Go 拒绝隐式上下文，Context 沿调用链透传 |
| JVM 分代 GC（G1/ZGC） | 并发三色标记，**不分代不整理** | 逃逸分析消解大量堆分配，STW 亚毫秒 |
| CompletableFuture | channel + errgroup + Context | Go 用通信代替编排 |
| 异常体系（try-catch） | error 显式返回 + panic/recover | 错误即值，强制处理 |

## 面试重点分布

**必考题 TOP 10（按出现频率）：**

1. GMP 调度模型（[并发篇](go并发编程面试知识点.md)）
2. map 底层原理与扩容（[集合篇](go集合数据结构面试知识点.md)）
3. channel 底层与关闭语义（[并发篇](go并发编程面试知识点.md)）
4. GC 三色标记与混合写屏障（[运行时篇](go运行时与GC面试知识点.md)）
5. slice 扩容机制与共享底层数组的坑（[集合篇](go集合数据结构面试知识点.md)）
6. defer 执行规则（[语言基础篇](go语言基础面试知识点.md)）
7. goroutine 泄漏场景与排查（[并发篇](go并发编程面试知识点.md)、[运行时篇](go运行时与GC面试知识点.md)）
8. interface 判 nil 的坑（[语言基础篇](go语言基础面试知识点.md)）
9. Context 原理与使用规范（[并发篇](go并发编程面试知识点.md)）
10. 逃逸分析常见场景（[运行时篇](go运行时与GC面试知识点.md)）

## Go 后端学习路径建议

1. **入门**：语法基础 → 《Go 程序设计语言》→ 写 CLI 小工具
2. **进阶**：并发编程（GMP/Channel/sync/Context）→ 写并发爬虫、工作池
3. **实战**：Gin + MySQL + Redis 写一个完整 CRUD 服务（含鉴权、限流、优雅退出）
4. **深入**：阅读 runtime 源码（proc.go、chan.go、map.go、mgc.go）、pprof 性能调优实战
5. **体系化**：微服务（gRPC + 注册发现 + 链路追踪）→ K8s 部署 → CI/CD
6. **面试**：按上面五篇文档顺序准备，结合项目讲出"为什么这么设计"
