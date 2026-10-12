# Go 后端工程实战面试知识点

## 一、网络编程与 HTTP 服务

### 1.1 Go 网络模型：netpoll（必考）

Go 的 net 包基于 **I/O 多路复用（epoll/kqueue）+ 非阻塞 I/O + goroutine 阻塞模拟同步**：

```text
业务 goroutine                     runtime netpoller
     │                                   │
     ▼  conn.Read() 读不到数据             │
  gopark 挂起（不占线程）                  │
     │  fd 注册到 ──────→            epoll_wait
     │                                   │ 数据到达
     ▼  就绪后唤醒 ◄──────────────────────┘
  继续执行（同步写法）
```

**精髓：** 每个连接一个 goroutine，代码是**同步阻塞写法**（可读性好），底层却是**异步非阻塞**（goroutine 挂起不占用 OS 线程）——兼得 Node.js 的并发能力和同步代码的可维护性，无需回调地狱。

### 1.2 HTTP Server 生产级配置

```go
srv := &http.Server{
    Addr:              ":8080",
    Handler:           router,
    ReadTimeout:       5 * time.Second,   // 读整个请求（含 body）超时
    WriteTimeout:      10 * time.Second,  // 写响应超时
    IdleTimeout:       60 * time.Second,  // keep-alive 空闲连接超时
    ReadHeaderTimeout: 2 * time.Second,   // 防 Slowloris 慢速攻击（默认无限制！）
    MaxHeaderBytes:    1 << 20,
}
```

**必考点：默认 `http.ListenAndServe` 没有任何超时**，生产必须显式配置，否则慢攻击/慢客户端会耗尽 goroutine。

**优雅退出（Graceful Shutdown，必考手写）：**

```go
go func() {
    if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
        log.Fatalf("listen: %v", err)
    }
}()
quit := make(chan os.Signal, 1)
signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)   // K8s 滚动更新发 SIGTERM
<-quit
ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
defer cancel()
srv.Shutdown(ctx)   // 停止接收新连接，等待存量请求处理完
```

**注意：** Shutdown 不等待 hijacked 连接（WebSocket），长连接服务需自行管理关闭。

### 1.3 HTTP Client 调优（高频坑）

```go
client := &http.Client{
    Timeout: 10 * time.Second,   // 整个请求总超时（含连接+读写，默认无超时！）
    Transport: &http.Transport{
        MaxIdleConns:        100,             // 全局空闲连接池
        MaxIdleConnsPerHost: 20,              // 每 host 空闲连接（默认仅 2！高频调用下游必调）
        MaxConnsPerHost:     100,             // 每 host 最大连接（默认无限制）
        IdleConnTimeout:     90 * time.Second,
        DisableKeepAlives:   false,
    },
}
```

**三大生产坑：**

| 坑 | 后果 | 解决 |
|----|------|------|
| 不读 Body 直接 Close | 连接无法复用，每次新建 TCP（TIME_WAIT 暴涨） | `io.Copy(io.Discard, resp.Body)` 读完再 Close |
| 忘记 Close Body | 连接泄漏，goroutine 与 fd 耗尽 | `defer resp.Body.Close()` 紧跟 err 判断后 |
| MaxIdleConnsPerHost 默认 2 | 高并发下连接反复建拆，TIME_WAIT 堆积 | 按下游 QPS 调大 |

**TIME_WAIT 过多排查：** keep-alive 未生效（检查响应头 Connection: close？）→ 空闲连接池太小 → 下游短连接配置。

### 1.4 TCP 粘包与 WebSocket 要点

**粘包/拆包：** TCP 是字节流，应用层必须自己定边界：
1. 固定长度；2. 分隔符（\n）；3. **长度字段**（最常用：4 字节 header 长度 + body）。

**WebSocket：**
- 库：`gorilla/websocket`（经典）或 `coder/websocket`（维护活跃）。
- 单连接**同时只能一个 reader 一个 writer**（并发写要加锁或单写 goroutine + channel）。
- 心跳保活：服务端定时 ping，客户端 pong 响应，读超时检测死连接。
- 关闭握手：发 close frame 等对方响应，避免硬断导致丢消息。

---

## 二、Web 框架（以 Gin 为例）

### 2.1 Gin 核心原理（高频）

**路由：Radix Tree（压缩前缀树）**
- 每种 HTTP 方法一棵树（GET/POST 各自独立）。
- 支持 `:param` 路径参数、`*wildcard` 通配符。
- 查找复杂度 O(k)（k 为路径长度），碾压遍历式路由表。

**Context 与中间件洋葱模型：**

```go
func Logger() gin.HandlerFunc {
    return func(c *gin.Context) {
        start := time.Now()
        c.Next()                                  // 执行后续 handler（递归向下）
        log.Printf("耗时: %v", time.Since(start))  // 后置逻辑（回溯向上）
    }
}
// 执行顺序：Logger前置 → Auth前置 → handler → Auth后置 → Logger后置
```

- `gin.Context` 贯穿请求生命周期，通过 **sync.Pool 复用**减少 GC。
- 中间件本质是 handler 数组 + index 游标，`c.Next()` 推进执行。
- `c.Abort()` 阻止后续 handler（鉴权失败场景）。

**常用能力：** 参数绑定 `c.ShouldBindJSON`（binding tag 走 validator 校验）、分组路由 `router.Group("/api/v1", auth)`、统一错误处理 `c.Error(err)` + 中间件集中处理。

### 2.2 框架对比选型

| 框架 | 特点 | 适用场景 |
|------|------|----------|
| Gin | 性能高、生态成熟、文档全 | 通用 Web 首选 |
| Echo | API 优雅、中间件丰富 | 与 Gin 类似 |
| Fiber | 基于 fasthttp（非 net/http）性能极致 | 超高并发且不需要 HTTP/2 |
| go-zero | 微服务全家桶（rpc+限流+熔断+代码生成） | 微服务体系 |
| Kratos | B 站开源，protobuf 优先、工程规范强 | 大型微服务项目 |
| Hertz | 字节开源，自研网络库 | 高并发/字节系 |

---

## 三、存储与中间件

### 3.1 MySQL：database/sql 连接池（必考）

**四大参数（必背）：**

```go
db, _ := sql.Open("mysql", dsn)
db.SetMaxOpenConns(50)                  // 最大连接数（默认无限！生产必设）
db.SetMaxIdleConns(50)                  // 最大空闲连接（默认 2！建议 = MaxOpenConns 避免连接抖动）
db.SetConnMaxLifetime(time.Hour)        // 连接最大存活时间（必须 < MySQL wait_timeout 8h）
db.SetConnMaxIdleTime(10 * time.Minute) // 空闲连接存活时间（Go 1.15+）
```

**常见坑：**

| 坑 | 后果 |
|----|------|
| `rows` 未 Close | 连接泄漏 → 池耗尽 → 服务 hang 死（`sql: database is closed`） |
| 死连接 | MySQL wait_timeout 断开空闲连接，Go 侧拿着查询报 `invalid connection`（driver 自动重试一次） |
| 事务 | `defer tx.Rollback()` 是安全写法（Commit 后 Rollback 是 no-op） |
| prepared statement | 高频 SQL 用 `db.Prepare` 预编译，防注入且复用执行计划 |

**GORM 要点：** `First` 主键查询（无记录返回 ErrRecordNotFound，Find 不返回）；`Preload` 解决 N+1；软删除 `gorm.DeletedAt`；大结果集用 `FindInBatches` 分批防 OOM。

### 3.2 Redis：go-redis 生产要点

```go
rdb := redis.NewClient(&redis.Options{
    Addr:         "localhost:6379",
    PoolSize:     100,               // 连接池大小（建议 10 * 并发度）
    MinIdleConns: 10,
    DialTimeout:  5 * time.Second,
    ReadTimeout:  3 * time.Second,   // 必设！默认 3s 已是兜底
})
```

**生产要点：**
- 所有命令带 ctx，超时由 ctx 控制。
- Pipeline 批量命令减 RTT；Lua 脚本保原子性（扣库存、限流）。
- `rdb.Get` 返回 `redis.Nil` 表示 key 不存在，**必须特判**（不是真错误）。
- Cluster 用 `NewClusterClient`；自定义分片用 `NewRing`（一致性哈希）。

### 3.3 Kafka：sarama / kafka-go

**生产者：** acks=all 保不丢（配合 min.insync.replicas=2）；幂等生产者防重试重复；linger.ms+batch.size 攒批提吞吐。

**消费者：**
- rebalance 期间暂停消费（处理慢超 max.poll.interval.ms 会被踢出组）。
- 手动提交 offset：先处理消息后提交（at-least-once），**消费必须幂等**。
- lag 堆积治理：加分区（提升并行上限）、批量消费、异步处理（注意保序）。

---

## 四、微服务与 RPC

### 4.1 gRPC 核心知识

- **基于 HTTP/2**：多路复用（单连接并发多请求）、HPACK 头部压缩、双向流。
- **序列化 Protocol Buffers**：二进制高效、schema 强约束、跨语言。
- **四种调用模式**：Unary、Server Streaming、Client Streaming、Bidirectional Streaming。
- **拦截器 Interceptor**：日志、鉴权、限流、tracing、recover（类似 Gin 中间件）。

**生产实践（高频）：**

| 问题 | 方案 |
|------|------|
| K8s 下负载不均 | gRPC 长连接复用导致请求打到固定 pod → headless service + 客户端 round_robin，或 L7 代理（Envoy/Linkerd） |
| 超时传递 | Deadline 沿调用链传播，ctx 超时自动级联 |
| 重试 | 仅幂等接口可重试；gRPC retry policy 配 maxAttempts + backoff |
| Metadata | traceID、token 通过 metadata 透传 |

### 4.2 服务治理组件选型

| 能力 | 常用方案 | 要点 |
|------|----------|------|
| 注册发现 | Nacos、Consul、Etcd、K8s Service | 心跳保活、watch 推送 |
| 配置中心 | Nacos、Apollo、Etcd | 动态刷新、灰度、回滚 |
| 限流 | uber-go/ratelimit（漏桶）、golang/time（令牌桶）、sentinel-golang | 单机 vs 分布式（Redis+Lua） |
| 熔断 | sony/gobreaker、sentinel-golang | 错误率/慢调用比例触发，半开恢复 |
| 链路追踪 | OpenTelemetry + Jaeger/Tempo | TraceID 透传、Span 模型 |
| 指标 | Prometheus + client_golang | RED（Rate/Errors/Duration）四大黄金指标 |
| 日志 | zap / slog（Go 1.21+） | 结构化、采样、异步写 |

**限流算法对比（高频）：**

| 算法 | 特点 | 突发流量 |
|------|------|----------|
| 计数器（固定窗口） | 简单，窗口边界有 2 倍突刺 | 边界突刺 |
| 滑动窗口 | 分桶平滑，无边界问题 | 较平滑 |
| 漏桶 | 恒定速率流出，强行平滑 | 不允许突发 |
| 令牌桶 | 恒定速率发令牌，可攒令牌 | **允许可控突发**（最常用） |

### 4.3 分布式锁（Go 实现）

```go
// 推荐 redsync（对应 Java Redisson）
lock, err := redsync.New(rdb).NewMutex("lock:order",
    redsync.WithExpiry(10*time.Second),   // 锁自动续约（看门狗）防业务未完锁过期
    redsync.WithTries(3),
)
if err := lock.Lock(); err != nil { return err }
defer lock.Unlock()   // Lua 脚本保证"自己加的锁自己解"
```

**要点：** SET NX EX 原子加锁、Lua 校验 value 释放（防误删）、看门狗续约、fencing token（防锁过期后的并发写）、RedLock 多节点方案及其争议（Martin Kleppmann 的时钟漂移质疑）。

---

## 五、工程化实践

### 5.1 项目结构（Standard Go Project Layout）

```text
project/
├── cmd/server/main.go     # 入口：每个可执行程序一个目录
├── internal/              # 私有代码（编译器强制外部不可导入）
│   ├── handler/           # Transport 层：参数解析、响应封装
│   ├── service/           # 业务逻辑层：事务编排
│   ├── repository/        # 持久层：DB/缓存，向上屏蔽存储细节
│   └── model/             # 领域模型
├── pkg/                   # 可被外部引用的公共库（谨慎放）
├── api/                   # OpenAPI/proto 定义
├── configs/               # 配置
└── go.mod
```

**分层规范：** Handler 不写业务逻辑；Service 不感知 HTTP（可复用于 RPC/Cron）；Repository 返回领域对象不返回 sql.Row。

### 5.2 依赖与配置管理

- `go mod tidy` 清理依赖；MVS（最小版本选择）算法；`go mod vendor` 可重复构建。
- 国内代理：`GOPROXY=https://goproxy.cn,direct`；私有仓库：`GOPRIVATE=*.corp.com`。
- 配置用 viper：多来源（YAML/ENV/远程）+ WatchConfig 热更新；敏感信息走 KMS/Secret；12-Factor：环境相关配置走环境变量。

### 5.3 日志规范（zap / slog）

```go
logger.Info("user login",
    zap.Int64("uid", uid),
    zap.String("ip", ip),
    zap.Duration("cost", cost),
)
```

- 结构化 KV，禁止 Sprintf 拼接；zap 零反射高性能；日志带 traceID 串联链路。
- 分级：Debug/Info/Warn/Error，Error 才告警；Go 1.21+ 标准库 `log/slog` 可作统一门面。

### 5.4 测试体系

```go
// 表驱动测试（Go 特色，必考）
tests := []struct{ name string; in, want int }{
    {"正数", 5, 10},
    {"负数", -5, 0},
}
for _, tt := range tests {
    t.Run(tt.name, func(t *testing.T) {
        assert.Equal(t, tt.want, Add(tt.in, 5))
    })
}
```

**工具链：** testify（断言）、gomock/mockery（接口 mock）、miniredis（Redis mock）、sqlmock、testcontainers-go（集成测试起真实中间件）、`go test -race`（竞态）、`go test -coverprofile` + `go tool cover -html`（覆盖率）、Benchmark（`testing.B`）。

---

## 六、性能优化与线上排查

### 6.1 常见优化手段速查表

| 问题 | 优化方案 |
|------|----------|
| CPU 高 | 减反射、字符串拼接用 strings.Builder、热路径避免 interface 装箱、算法优化 |
| 内存高 | sync.Pool 复用、减少指针（扁平结构）、slice 预分配 cap、大对象拆分 |
| GC 频繁 | 减少堆分配（逃逸分析 -m）、GOGC 调大、GOMEMLIMIT |
| 锁竞争 | 缩小临界区、读写锁、分片锁、atomic |
| goroutine 泄漏 | 所有 goroutine 有退出路径、ctx 超时、done channel |
| IO 密集 | 连接池调优、批量操作、pipeline、异步化 |
| 接口慢 | pprof 定位 → 缓存（本地/Redis）→ 并行化（errgroup）→ 降级 |

### 6.2 线上问题排查 SOP

```text
1. 看监控：QPS、P99 延迟、错误率、CPU、内存、goroutine 数、GC 耗时、连接数
2. 抓现场：
   ├─ CPU 高      → pprof profile 30s 看热点
   ├─ 内存涨      → pprof heap 两次采样 diff
   ├─ goroutine 涨 → goroutine profile 找阻塞聚类
   ├─ 偶发慢      → go tool trace + traceID 日志定位
   └─ GC 问题     → GODEBUG=gctrace=1
3. 定假设 → 验证 → 修复 → 复盘
```

---

## 七、高频面试题速记

**Q1：Go 的 HTTP 服务为什么高并发下表现好？**
> netpoll 基于 epoll + 非阻塞 IO，goroutine 读不到数据 gopark 挂起不占线程，fd 就绪后唤醒。同步写法获得异步性能，一连接一 goroutine 轻松支撑十万级连接。

**Q2：HTTP Client 有哪些生产坑？**
> ①默认无超时（必设 Client.Timeout）；②Body 必须读完再 Close 才能复用连接（io.Copy(io.Discard, resp.Body)）；③MaxIdleConnsPerHost 默认仅 2，高频下游要调大，否则 TIME_WAIT 堆积。

**Q3：Gin 的路由为什么快？**
> Radix Tree 压缩前缀树，每种 HTTP 方法一棵树，查找 O(k)；配合 sync.Pool 复用 Context 减少 GC；中间件洋葱模型通过 c.Next() 递归推进。

**Q4：MySQL 连接池四大参数怎么设？**
> MaxOpenConns（最大连接，必设防打爆 DB）、MaxIdleConns（建议=MaxOpenConns 防抖动）、ConnMaxLifetime（< MySQL wait_timeout）、ConnMaxIdleTime。rows 必须 Close 否则连接泄漏耗尽。

**Q5：gRPC 在 K8s 下为什么负载不均？怎么解决？**
> gRPC 基于 HTTP/2 长连接多路复用，客户端连上某 pod 后所有请求都走这条连接，K8s Service 的 L4 负载均衡失效。方案：headless service + 客户端负载均衡（round_robin），或引入 L7 代理（Envoy）。

**Q6：限流算法有哪些？令牌桶和漏桶区别？**
> 固定窗口（边界突刺）、滑动窗口（平滑）、漏桶（恒速流出，不允许突发）、令牌桶（恒速发令牌，攒令牌允许可控突发，最常用）。分布式限流用 Redis+Lua 实现令牌桶。

**Q7：Go 服务怎么优雅退出？**
> signal.Notify 监听 SIGINT/SIGTERM → srv.Shutdown(ctx) 停止收新连接并等待存量请求完成（带超时）；同时停掉 cron/消费者，flush 日志和监控数据。K8s 场景注意 preStop 与 terminationGracePeriodSeconds 配合。

**Q8：线上接口突然变慢怎么排查？**
> 看监控定位范围（全局 or 单接口）→ pprof profile 抓 CPU 热点 → 慢则查下游（trace/日志看 RT 分布）→ 查 GC（gctrace 是否频繁）→ 查连接池（DB/Redis 是否打满等待）。
