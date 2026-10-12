# Redis 高可用与生产实战面试知识点

## 一、Redis 事务、Lua 与原子性

### 1.1 Redis 事务

Redis 事务通过 `MULTI`、`EXEC`、`DISCARD`、`WATCH` 实现。

```bash
MULTI
SET a 1
INCR b
EXEC
```

**执行含义：**
1. `MULTI`：开启事务。
2. `SET a 1`：命令进入队列，不立即执行。
3. `INCR b`：命令进入队列，不立即执行。
4. `EXEC`：按顺序执行队列中的命令。

**特点：**
- 事务中的命令按顺序执行。
- 执行期间不会被其他客户端命令插入。
- Redis 事务不支持传统数据库意义上的自动回滚。

### 1.2 Redis 事务为什么不支持回滚？

Redis 认为命令错误通常是编程错误，应该在开发测试阶段发现。

如果事务中某条命令运行时失败，其他命令仍然可能继续执行。

示例：

```bash
SET name zhangsan
MULTI
INCR name
SET age 18
EXEC
```

`INCR name` 会失败，因为 `name` 不是整数，但 `SET age 18` 仍然可能执行成功。

### 1.3 WATCH 乐观锁

`WATCH` 用于监听 Key，如果事务执行前被其他客户端修改，则事务失败。

```bash
WATCH stock:1001
GET stock:1001
MULTI
DECR stock:1001
EXEC
```

**适用场景：**
- 库存扣减
- 防止并发覆盖
- CAS 更新

**局限：** 高并发下大量事务可能失败，需要客户端重试。

### 1.4 Lua 脚本

Lua 脚本可以把多个 Redis 命令组合成一个原子操作。

**典型场景：**
- 分布式锁释放
- 秒杀扣库存
- 限流
- 复杂缓存更新

扣库存示例：

```lua
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil then
    return -1
end
if stock <= 0 then
    return 0
end
redis.call('decr', KEYS[1])
return 1
```

**返回含义：**
- `-1`：库存 Key 不存在。
- `0`：库存不足。
- `1`：扣减成功。

### 1.5 Lua 脚本注意事项

- 不要执行耗时逻辑，否则会阻塞 Redis。
- 脚本访问的 Key 在 Cluster 模式下应位于同一个 Slot。
- Lua 保证的是 Redis 内部命令执行原子性，不等于数据库事务。

---

## 二、Redis 主从复制

### 2.1 主从复制是什么？

Redis 主从复制是指从节点复制主节点的数据，实现读写分离、容灾备份。

```text
        写请求
Client ───────▶ Master
                  │
                  │ 复制数据
        ┌─────────┴─────────┐
        ▼                   ▼
      Slave1              Slave2
        ▲                   ▲
        └────── 读请求 ──────┘
```

### 2.2 主从复制有什么用？

1. **读写分离**：主节点写，从节点读。
2. **数据备份**：从节点保存主节点副本。
3. **高可用基础**：哨兵和 Cluster 都依赖复制。
4. **故障恢复**：主节点故障时可提升从节点。

### 2.3 主从复制流程

1. 从节点执行 `replicaof masterHost masterPort`。
2. 从节点向主节点发送 `PSYNC`。
3. 如果可以增量复制，则主节点发送缺失命令。
4. 如果不能增量复制，则触发全量复制。
5. 主节点 `BGSAVE` 生成 RDB 文件发送给从节点。
6. 从节点加载 RDB。
7. 主节点继续发送复制缓冲区中的增量命令。

### 2.4 全量复制与增量复制

| 类型 | 触发场景 | 说明 |
|------|----------|------|
| 全量复制 | 第一次同步、复制积压缓冲区不足 | 主节点生成 RDB 发给从节点 |
| 增量复制 | 网络短暂断开后恢复 | 根据复制 offset 补发缺失命令 |

### 2.5 主从复制延迟原因

- 主节点写入压力大。
- 网络延迟或网络抖动。
- 从节点机器性能差。
- 大 Key 复制耗时。
- RDB/AOF 持久化影响。
- 从节点处理命令速度慢。

### 2.6 主从复制问题

- 主从复制是异步的，可能丢数据。
- 从节点可能读到旧数据。
- 主节点故障时，如果没有哨兵，需要人工切换。
- 写能力仍受单主节点限制。

### 2.7 主从复制是同步还是异步？以 `SET a 1` 为例

**一句话结论：Redis 主从复制默认是"完全异步"的。** 主节点写完自己的内存就立即返回给客户端，**不会等从节点确认**。这是 Redis 高性能的关键，但也意味着主从之间天然存在数据不一致窗口，主库宕机可能丢失最近还没同步过去的数据。

#### 2.7.1 `SET a 1` 的完整流程

假设是稳态运行（不是刚建立连接时的全量同步阶段）：

```text
              ┌────────────────────────────────────────────┐
              │                主节点 Master               │
              └────────────────────────────────────────────┘
                                │
   ① 客户端 SET a 1  ──────────▶│
                                │
                                │ ② 在内存中把 a 设为 1
                                │    并写入 AOF 缓冲区
                                │
   ④ 立即返回 +OK  ◀────────────│
                                │
                                │ ③ 把 "SET a 1" 追加进
                                │    "复制积压缓冲区 repl_backlog"
                                │    并推送到每个从节点的 replication buffer
                                │
                                ▼
                      通过 TCP 长连接异步发送
                                │
                                ▼
              ┌────────────────────────────────────────────┐
              │                从节点 Slave                │
              └────────────────────────────────────────────┘
                ⑤ 从节点收到命令 → 在自己内存中执行 SET a 1
                ⑥ 更新自己的 offset
```

**关键点：第 ④ 步（返回 OK）发生在第 ⑤/⑥ 步之前**——主节点根本没等从节点，就已经告诉客户端"写成功"了。这就是所谓的**异步复制（asynchronous replication）**。

#### 2.7.2 主从复制的三个阶段

Redis 的复制机制其实分三个阶段，稳态的"命令传播"只是其中之一：

**1. 全量同步（`psync ? -1`，首次连接或数据差距过大）**

```text
从节点 → 主节点:  PSYNC ? -1
主节点 fork 子进程 → 生成 RDB 快照 → 发给从节点
主节点在此期间新的写命令 → 缓存在"复制缓冲区"
从节点加载 RDB → 清空自身数据 → 追赶复制缓冲区
```

**2. 增量同步（`psync <replid> <offset>`，断线重连场景）**

- 每个主节点有 `master_replid`，每条写命令都有全局递增的 `offset`。
- 主节点维护一个**环形的复制积压缓冲区** `repl_backlog`（默认 1MB，可调 `repl-backlog-size`）。
- 从节点断线重连时携带自己的 `offset`，如果这个位置还在 `repl_backlog` 里，就只把中间差的那段命令补发过去。
- 如果 `offset` 已经被环形覆盖掉了 → 退化为**全量同步**。

**3. 命令传播（稳态）**

- 主节点每执行一条写命令，就把它 append 到每个从节点对应的 output buffer 里。
- 通过长连接**异步**推给从节点。
- 从节点会周期性用 `REPLCONF ACK <offset>` 上报自己已复制到的 offset。

#### 2.7.3 异步复制带来的问题：主库宕机会丢数据

用 `SET a 1` 举个极端例子：

```text
1. 客户端 SET a 1  → 主节点内存已改，返回 OK
2. 主节点还没把命令发给从节点，主节点宕机
3. 哨兵/Cluster 将从节点提升为新主
4. 新主里没有 a=1  → 数据丢失
```

这就是 Redis 的 **CAP 取舍**：默认走 AP（可用性 + 分区容忍性），牺牲强一致性来换取延迟和吞吐。

#### 2.7.4 能不能做成"同步"？—— `WAIT` 命令

Redis 提供了一个**半同步兜底**能力：`WAIT` 命令。

```bash
SET a 1
WAIT 1 100   # 等待至少 1 个从节点确认，最多等 100ms
```

- 第一个参数：需要多少个从节点确认已复制。
- 第二个参数：最长等待毫秒数，超时就返回**实际确认数**（不会报错）。

**注意：**
- `WAIT` **不是真正的同步复制**，它只是"事后等待"，主节点仍然是先写完自己再返回 OK 的。
- 就算 `WAIT` 返回 0（没人确认），主节点也**不会回滚**已写入的数据。
- 所以它只能"降低"丢数据概率，不能"消除"。

Redis 官方从未提供严格意义上的同步复制，这是设计取舍。

#### 2.7.5 如何降低主从不一致带来的丢数据风险

生产上常见的组合拳：

| 手段 | 作用 |
|------|------|
| `min-replicas-to-write N` + `min-replicas-max-lag M` | 当健康从节点少于 N，或延迟超过 M 秒时，**主节点拒绝写入**，避免继续制造"注定要丢"的数据 |
| 调大 `repl-backlog-size` | 增大复制积压缓冲区，减少断线后被迫全量同步的概率 |
| AOF `appendfsync everysec` + 混合持久化 | 主节点自身的数据尽量不丢 |
| 哨兵配置合理的 `down-after-milliseconds` 和 `failover-timeout` | 减少脑裂窗口 |
| 关键业务用 `WAIT` 兜底 | 强一致要求高的写，等一个副本确认后再返回 |
| 强一致场景不用 Redis | 用 MySQL / ZooKeeper / etcd 等 CP 系统 |

#### 2.7.6 面试三句话总结

1. Redis 主从复制**默认是完全异步**的：主节点写完内存就返回 OK，不等从节点确认。
2. 底层通过 **`repl_backlog` 环形缓冲区 + offset**，实现断线重连时的增量同步；缓冲区被覆盖或首次连接时退化为**全量同步**（RDB + 缓冲追赶）。
3. 异步复制带来主从不一致和主库宕机丢数据的风险，可以用 `WAIT` 命令、`min-replicas-to-write`、增大 `repl-backlog-size` 等手段缓解，但 Redis 不提供严格意义上的同步复制。

### 2.8 复制积压缓冲区 `repl_backlog` 详解

一句话答案：**它是主节点里的一个固定大小的环形缓冲区，专门存"最近发送给从节点的写命令流"，作用是让从节点短暂断线重连后能做增量同步，避免每次断线都触发昂贵的全量同步（RDB）。**

#### 2.8.1 没有 `repl_backlog` 会怎样？

Redis 主从复制在稳态时，就是主节点通过 TCP 长连接把写命令**边执行边推**给从节点。但网络抖动、从节点重启、GC 停顿等原因随时可能导致连接短暂中断：

```text
主 ─────命令流─────▶ 从
        ↑
      连接断了 3 秒
```

如果没有 `repl_backlog`，主节点根本"不记得"这 3 秒里发过什么命令。从节点重连后只能说："我不知道错过了什么，请从头再来一遍。"结果就是：

```text
主节点 fork 子进程 → 生成 RDB → 传输 RDB → 从节点清空 + 加载 RDB
```

这就是**全量同步**，代价极大：

- fork 子进程会阻塞主线程一小段
- 生成 RDB 消耗大量 CPU 和磁盘 IO
- 网络传输动辄几百 MB 甚至几十 GB
- 从节点要清空自身数据并重新加载
- 大实例上一次全量同步可能几十秒到几分钟

**只是断线 3 秒就要付这个代价，显然不能接受。**

#### 2.8.2 `repl_backlog` 到底存的是什么？

它存的**不是数据（k-v）**，而是**"发送给从节点的原始写命令字节流"**，本质是一段有序的、带全局 offset 编号的命令历史。

可以直观理解成一个"最近发出去的命令流水账"：

```text
offset:  10000  10007   10014        10024
         │      │       │            │
内容:    SET a 1 | SET b 2 | INCR counter | HSET u:1 name zs | ...
```

三个核心属性：

| 属性 | 说明 |
|------|------|
| **环形结构** | 固定大小（默认 1MB，由 `repl-backlog-size` 控制），写满后从头覆盖 |
| **全局 offset** | 主节点从启动/成为主起，每写入 1 字节命令流就 +1；从节点也维护自己复制到的 offset |
| **全实例共享** | 不管有几个从节点，主节点只维护**这一个** backlog，不是每个从节点一份 |

关键：**主节点发送给每个从节点的输出缓冲区（`client output buffer`）是每个从节点独立的**；而 `repl_backlog` 是**所有从节点共享的一份"历史命令回放带"**，专门用来在断线重连时"倒带"。

#### 2.8.3 `repl_backlog` 怎么发挥作用？（增量同步流程）

从节点断线重连时，会发送：

```text
PSYNC <master_replid> <offset>
```

主节点判断：

```text
if 从节点的 replid == 我的 replid
   and 从节点的 offset 还在 repl_backlog 覆盖的范围内:
       → 增量同步（CONTINUE），只补发缺失的那一段命令
else:
       → 全量同步（FULLRESYNC），重新走 RDB
```

举例：

```text
主节点当前 offset = 12000
repl_backlog 大小 = 1MB → 覆盖范围大约是 [11000, 12000]

场景 A:  从节点 offset = 11500  → 在范围内 → 增量同步，补发 500 字节
场景 B:  从节点 offset = 8000   → 已被环形覆盖 → 只能全量同步
```

这就是**为什么要有 `repl_backlog`**：**它是"增量同步"能够成立的前提**。

#### 2.8.4 `repl_backlog` 和几个易混淆缓冲区的对比

Redis 主从复制里其实有好几个缓冲区，面试很容易混：

| 缓冲区 | 位置 | 作用 | 一份还是多份 | 满了怎么办 |
|--------|------|------|--------------|-----------|
| **AOF 缓冲区** `aof_buf` | 主节点 | 攒 AOF 命令等待 fsync 落盘 | 一份 | 按 `appendfsync` 策略刷盘 |
| **AOF 重写缓冲区** `aof_rewrite_buf` | 主节点 | 重写期间新命令暂存，最后追加到新 AOF | 一份 | 无上限，可能撑爆内存 |
| **客户端输出缓冲区** `client output buffer` | 主节点 | 主节点向**每个从节点**推命令的独立发送队列 | **每个从节点一份** | 超过 `client-output-buffer-limit slave` 直接**断开该从节点** |
| **复制积压缓冲区** `repl_backlog` | 主节点 | 保存最近 N 字节命令流，供断线重连做增量同步 | **全实例一份** | 环形覆盖旧数据 |

一次 `SET a 1` 在主节点上大致会：

1. 执行命令，改内存
2. 写 AOF 缓冲区 `aof_buf`
3. 写 `repl_backlog`（环形，供未来断线重连回放）
4. 追加到每个从节点的 `client output buffer`（各自异步发出去）
5. 立即返回 OK 给客户端

#### 2.8.5 `repl_backlog` 大小怎么设置？

默认 `repl-backlog-size 1mb`，在生产上通常需要调大，原则是：

```text
repl_backlog_size ≥ 主节点平均写入速率 (bytes/s) × 从节点最长可能断线时间 (s)
```

举个例子：

- 主节点写入速率约 10 MB/s
- 网络抖动或从节点重启可能持续 60 秒
- 建议 `repl-backlog-size` ≥ `10MB × 60 = 600MB`

调大的收益：**大幅降低断线后被迫全量同步的概率**，避免全量同步引起的 fork 抖动、带宽抖动。代价：占用一份主节点内存，但一般都能接受。

相关配置：

```bash
repl-backlog-size 64mb          # 环形缓冲区大小
repl-backlog-ttl 3600           # 所有从节点都断开后，多久释放 repl_backlog
```

#### 2.8.6 面试三句话总结

1. `repl_backlog` 是**主节点的一个固定大小环形缓冲区**，存的是"最近发送给从节点的写命令字节流"，配合全局 offset 使用。
2. 它的**唯一目的是让从节点断线重连时能做增量同步（PSYNC）**，避免每次抖动都触发昂贵的全量同步（RDB fork + 传输 + 加载）。
3. 大小要根据"主节点写入速率 × 允许断线时长"来估算，生产上默认 1MB 通常太小，实例大、写入高时应调到几十 MB 甚至几百 MB。

---

## 三、Redis 哨兵 Sentinel

### 3.1 Sentinel 解决什么问题？

Sentinel 用于监控 Redis 主从集群，并在 Master 故障时自动故障转移。

**核心能力：**
- 监控 Master 和 Slave。
- 判断节点是否下线。
- 自动选举新的 Master。
- 通知客户端新的 Master 地址。

### 3.2 Sentinel 架构

```text
        Sentinel1   Sentinel2   Sentinel3
             \          |          /
              \         |         /
               ▼        ▼        ▼
                    Master
                   /      \
                Slave1   Slave2
```

### 3.3 主观下线与客观下线

| 概念 | 说明 |
|------|------|
| 主观下线 SDOWN | 单个 Sentinel 认为 Master 不可用 |
| 客观下线 ODOWN | 多个 Sentinel 达成一致认为 Master 不可用 |

### 3.4 Sentinel 故障转移流程

1. Sentinel 定期 ping Master。
2. 某个 Sentinel 发现 Master 超时，标记主观下线。
3. 多个 Sentinel 投票确认客观下线。
4. Sentinel 之间选举 Leader。
5. Leader 从 Slave 中选择一个提升为新 Master。
6. 其他 Slave 改为复制新 Master。
7. 通知客户端新 Master 地址。

### 3.5 新 Master 如何选择？

一般考虑：
- `slave-priority` 优先级。
- 复制偏移量 offset，越大说明数据越新。
- runid 字典序。

### 3.6 Sentinel 局限性

- 不支持自动数据分片。
- 写能力仍受单 Master 限制。
- 主从异步复制仍可能丢数据。

---

## 四、Redis Cluster 集群

### 4.1 Redis Cluster 解决什么问题？

Redis Cluster 用于解决单机容量和写入瓶颈，通过分片实现水平扩展。

**核心能力：**
- 数据自动分片。
- 节点间故障检测。
- 自动故障转移。
- 去中心化架构。

### 4.2 哈希槽机制

Redis Cluster 将整个 Key 空间划分为 16384 个 Slot。

```text
slot = CRC16(key) % 16384
```

每个 Master 负责一部分 Slot：

```text
Master1: slot 0 - 5460
Master2: slot 5461 - 10922
Master3: slot 10923 - 16383
```

### 4.3 Cluster 架构

```text
          ┌──────────────┐
          │  Client      │
          └──────┬───────┘
                 │
       ┌─────────┼─────────┐
       ▼         ▼         ▼
   Master1   Master2   Master3
     │         │         │
   Slave1    Slave2    Slave3
```

### 4.4 MOVED 与 ASK

| 类型 | 场景 | 说明 |
|------|------|------|
| MOVED | Slot 已迁移完成 | 客户端应更新 Slot 路由缓存 |
| ASK | Slot 正在迁移中 | 客户端临时访问目标节点 |

### 4.5 Hash Tag

Cluster 中多 Key 操作要求 Key 在同一个 Slot。

可以使用 `{}` 指定 Hash Tag：

```bash
user:{1001}:name
user:{1001}:age
```

这两个 Key 都会根据 `{1001}` 计算 Slot，保证落在同一个节点。

### 4.6 Cluster 的限制

- 多 Key 操作要求 Key 在同一个 Slot。
- 不支持跨 Slot 事务。
- 客户端需要支持 Cluster 路由。
- 扩缩容涉及 Slot 迁移。

### 4.7 Sentinel 和 Cluster 区别

| 对比项 | Sentinel | Cluster |
|--------|----------|---------|
| 核心目标 | 高可用故障转移 | 高可用 + 分片扩容 |
| 数据分片 | 不支持 | 支持 |
| 写入能力 | 单 Master | 多 Master |
| 客户端复杂度 | 较低 | 较高 |
| 适合场景 | 数据量不大但要求高可用 | 数据量大、写入压力大 |

---

## 五、Redis 大 Key、热 Key 与性能优化

### 5.1 什么是大 Key？

大 Key 通常指：
- String Value 很大，例如超过 1MB。
- Hash/List/Set/ZSet 元素数量很多，例如超过 1 万。

### 5.2 大 Key 的危害

- 网络传输耗时。
- 阻塞 Redis 主线程。
- 删除时阻塞。
- 迁移和复制慢。
- Cluster 数据倾斜。

### 5.3 如何提前发现大 Key？

发现大 Key 的关键是"**能发现、常态化、不影响线上**"。生产上通常同时用下面几种手段互补：

#### 5.3.1 `redis-cli --bigkeys`（采样扫描，最常用）

```bash
redis-cli -h <host> -p <port> --bigkeys
redis-cli -h <host> -p <port> --bigkeys -i 0.01   # 每次扫描后 sleep 10ms，减轻主线程压力
```

**原理：** 内部通过 `SCAN` 遍历所有 Key，对每种类型（String / Hash / List / Set / ZSet）分别记录"当前遇到过的最大 Key"。

**输出示例：**

```text
Biggest string found so far 'user:profile:1001' with 5242880 bytes
Biggest hash   found so far 'user:relations:1001' with 128000 fields
Biggest list   found so far 'msg:queue:im'      with 987654 items
```

**注意事项：**
- **只找每种类型的最大值**，不是"所有大 Key"（例如你有 100 个 1MB 的 String，它只会告诉你其中一个）。
- 对 Hash/List/Set/ZSet 显示的是**元素个数**，不是内存占用（元素多不一定内存大，反之也可能）。
- **务必在 slave 上跑**，避免影响 master 主线程。
- 用 `-i` 参数控制扫描节奏，防止占用带宽和 CPU。

#### 5.3.2 `MEMORY USAGE key`（单 Key 精确测内存）

```bash
MEMORY USAGE user:profile:1001            # 采样估算，默认采样 5 个元素
MEMORY USAGE user:profile:1001 SAMPLES 0  # 全量精确计算（大 Key 上很慢，慎用）
```

返回该 Key 及其 value 占用的字节数（含元数据开销）。适合**排查已经怀疑的具体 Key**。

#### 5.3.3 `SCAN` + `TYPE` + 长度命令（自定义扫描脚本）

自己写脚本兼容更多维度（比如同时看内存、元素数、TTL）：

```bash
SCAN 0 COUNT 100
# 对每个返回的 key 执行：
TYPE   key                # 判断类型
STRLEN key                # String 字节数
HLEN   key                # Hash 字段数
LLEN   key                # List 元素数
SCARD  key                # Set 元素数
ZCARD  key                # ZSet 元素数
MEMORY USAGE key          # 精确内存
```

- 生产必用 `SCAN`，**绝对不能用 `KEYS *`**（O(N) 会阻塞主线程）。
- `SCAN COUNT` 控制单次返回数量，一般 100-1000。
- 常见做法：用 Python / Go 写个巡检脚本，把超过阈值（例如 String > 1MB、集合 > 5000 元素、内存 > 512KB）的 Key 上报到监控系统。

#### 5.3.4 离线分析 RDB 文件（最全面）

在从节点或备份机上分析 RDB 文件，**完全不影响线上**，也不会漏 Key：

```bash
# 常用工具
pip install rdbtools python-lzf
rdb --command memory dump.rdb --bytes 10240 > big_keys.csv
# 输出示例：
# database,type,key,size_in_bytes,encoding,num_elements,len_largest_element
# 0,string,user:profile:1001,5242880,raw,,
```

- **推荐工具**：`redis-rdb-tools`（Python）、`rdr`（Go，性能更好）。
- **优点**：全量精确、可按内存排序、可按前缀聚合、可离线做数据分布分析。
- **缺点**：需要拿到 RDB 快照，数据可能不是最新。
- 生产建议：每天定时拉取从节点 RDB → 分析 → 输出 Top 100 大 Key 报表 → 推送到告警群。

#### 5.3.5 客户端埋点（开发期预防）

在业务代码封装的 Redis 工具类里加拦截，**写入前预检查**：

```java
public void set(String key, String value) {
    if (value.length() > 1024 * 1024) {
        log.warn("[BigKey] key={}, size={} bytes", key, value.length());
        bigKeyMetrics.record(key, value.length());
    }
    redisTemplate.opsForValue().set(key, value);
}

public void hset(String key, String field, String value) {
    Long size = redisTemplate.opsForHash().size(key);
    if (size != null && size > 5000) {
        log.warn("[BigKey] hash key={} already has {} fields", key, size);
    }
    redisTemplate.opsForHash().put(key, field, value);
}
```

**这是最有价值的手段**：在大 Key 产生的源头就发现，而不是等它长成"庞然大物"后再治理。

#### 5.3.6 云厂商 / 中间件平台的自动巡检

阿里云 / 腾讯云 / AWS ElastiCache 等托管版 Redis 一般都提供：
- 自动大 Key 巡检报告（每日/每周）
- 大 Key 告警（超阈值推送）
- 数据分布可视化

自建集群可以搭配 **Redis Exporter + Prometheus + Grafana**，或者用 **CacheCloud、Redis Manager** 这类平台。

#### 5.3.7 如何按阈值扫描"所有大于特定大小"的大 Key？

`redis-cli --bigkeys` 的最大局限是**只输出每种类型的 TOP 1**，如果你想捞出**所有大于阈值的 Key**（例如"所有 String > 1MB"、"所有 Hash 字段数 > 10000"），需要自己写扫描脚本。

##### 方案一：`redis-cli` + `SCAN` + shell 脚本（快速用）

```bash
#!/bin/bash
# scan_big_keys.sh —— 扫描所有超过阈值的大 Key
HOST="127.0.0.1"
PORT="6379"
STRING_THRESHOLD=$((1 * 1024 * 1024))    # String > 1MB
COLLECTION_THRESHOLD=10000                # 集合元素数 > 10000
MEMORY_THRESHOLD=$((512 * 1024))          # 内存 > 512KB

cursor=0
while : ; do
    result=$(redis-cli -h $HOST -p $PORT SCAN $cursor COUNT 500)
    cursor=$(echo "$result" | head -n 1)
    keys=$(echo "$result" | tail -n +2)

    for key in $keys; do
        type=$(redis-cli -h $HOST -p $PORT TYPE "$key")
        size=0
        case $type in
            string) size=$(redis-cli -h $HOST -p $PORT STRLEN "$key")
                    [ "$size" -gt "$STRING_THRESHOLD" ] && \
                        echo "[BIG-STRING] $key bytes=$size" ;;
            hash)   size=$(redis-cli -h $HOST -p $PORT HLEN "$key")
                    [ "$size" -gt "$COLLECTION_THRESHOLD" ] && \
                        echo "[BIG-HASH]   $key fields=$size" ;;
            list)   size=$(redis-cli -h $HOST -p $PORT LLEN "$key")
                    [ "$size" -gt "$COLLECTION_THRESHOLD" ] && \
                        echo "[BIG-LIST]   $key items=$size" ;;
            set)    size=$(redis-cli -h $HOST -p $PORT SCARD "$key")
                    [ "$size" -gt "$COLLECTION_THRESHOLD" ] && \
                        echo "[BIG-SET]    $key items=$size" ;;
            zset)   size=$(redis-cli -h $HOST -p $PORT ZCARD "$key")
                    [ "$size" -gt "$COLLECTION_THRESHOLD" ] && \
                        echo "[BIG-ZSET]   $key items=$size" ;;
        esac
        # 同时按内存过滤
        mem=$(redis-cli -h $HOST -p $PORT MEMORY USAGE "$key")
        [ "$mem" -gt "$MEMORY_THRESHOLD" ] && \
            echo "[BIG-MEM] $key type=$type bytes=$mem"
    done

    [ "$cursor" = "0" ] && break
    sleep 0.05    # 节流，避免影响主线程
done
```

要点：
- **必须在 slave 上跑**，减少对 master 的干扰
- `SCAN COUNT 500` 控制单次返回，配合 `sleep 0.05` 节流
- 对每种类型分别用对应的长度命令（`STRLEN` / `HLEN` / `LLEN` / `SCARD` / `ZCARD`）
- 需要精确内存则用 `MEMORY USAGE`（较慢，可选）

##### 方案二：Python 脚本（推荐生产使用）

```python
# scan_big_keys.py
import redis

r = redis.Redis(host='127.0.0.1', port=6379, decode_responses=True)

# 阈值配置
THRESHOLD = {
    'string': 1 * 1024 * 1024,   # 1MB
    'hash':   10000,             # 字段数
    'list':   10000,
    'set':    10000,
    'zset':   10000,
}

size_cmd = {
    'string': 'STRLEN',
    'hash':   'HLEN',
    'list':   'LLEN',
    'set':    'SCARD',
    'zset':   'ZCARD',
}

big_keys = []
for key in r.scan_iter(count=500):
    t = r.type(key)
    if t not in THRESHOLD:
        continue
    size = r.execute_command(size_cmd[t], key)
    if size > THRESHOLD[t]:
        mem = r.memory_usage(key) or 0
        big_keys.append((key, t, size, mem))

# 按内存排序，输出 TOP N
big_keys.sort(key=lambda x: x[3], reverse=True)
for k, t, s, m in big_keys[:100]:
    print(f'{t:6s} {k:40s} size={s:>10}  memory={m:>10}')
```

生产建议：
- 用 `redis-py` 的 `scan_iter`，自动处理游标翻页
- 每扫 N 个 Key `time.sleep(0.01)` 主动让出 CPU
- 支持按前缀过滤（例如只扫 `user:*`），加快速度
- 结果写入监控系统或告警群

##### 方案三：`redis-rdb-tools` 按阈值过滤（离线，最全）

```bash
# 只输出大于 10KB 的 Key
rdb --command memory dump.rdb --bytes 10240 > big_keys.csv

# 按前缀过滤
rdb --command memory dump.rdb --bytes 10240 --key '^user:' > user_big_keys.csv
```

`--bytes` 参数就是**阈值过滤**，非常适合"给我所有超过 X 字节的 Key"这种诉求。**离线跑不影响线上，且不会漏。**

##### 方案四：Lua 脚本（不推荐用于全库扫描）

理论上可以用 Lua 在服务端遍历，但：
- `SCAN` 不能在 Lua 里跨调用（Lua 单次调用是原子的）
- 一次 Lua 遍历大量 Key 会**长时间阻塞主线程**
- 只适合"给定一小批候选 Key，服务端一次性判断哪些超阈值"这种场景

**结论：全库阈值扫描一律用客户端 `SCAN` + 节流，禁止用 Lua 全遍历。**

#### 5.3.8 面试速记

> 发现大 Key 的思路是"**在线采样 + 离线全量 + 开发拦截**"三层：在线用 `redis-cli --bigkeys` 或自写 `SCAN` 脚本在 **slave** 上定期采样；离线用 `redis-rdb-tools --bytes` 按阈值过滤做全量精确统计；开发阶段在客户端工具类里做写入拦截，超阈值告警。`--bigkeys` 只找每种类型的 TOP 1，要捞"所有大于 X 的 Key"必须自己写 `SCAN + 长度命令 + 阈值判断`的扫描脚本。

### 5.4 如何处理大 Key？

核心思路：**拆散 + 异步删除 + 禁止全量读**。

#### 5.4.1 拆分 Key（首选方案）

按业务维度让一个大 Key 变成多个小 Key，自然分散到不同 slot：

```text
❌ 原：user:1001:friends           (Set，1000 万元素)
✅ 拆：user:1001:friends:00        ← 按 hash(friendId) % 100 分桶
       user:1001:friends:01
       ...
       user:1001:friends:99
```

或者按时间/分页拆：

```text
❌ 原：article:1001:comments               (List，几十万条)
✅ 拆：article:1001:comments:20260901
       article:1001:comments:20260902
```

#### 5.4.2 用合适的数据结构

- 大 JSON String（几 MB）→ 改成 Hash，按字段读写，避免一次拉全量。
- 大 List 做队列 → 改成 Stream，天然支持分片消费。

#### 5.4.3 删除必须用 `UNLINK`

`DEL` 一个几十万元素的集合会**阻塞主线程几百毫秒到几秒**，`UNLINK` 走后台线程释放内存：

```bash
UNLINK big:key                    # 异步释放，非阻塞
```

同时开启 `lazyfree-lazy-server-del yes`、`lazyfree-lazy-expire yes`、`lazyfree-lazy-eviction yes`，让被动删除也走异步。

#### 5.4.4 禁止全量读

**禁止**对大 Key 用：

```bash
HGETALL / SMEMBERS / LRANGE 0 -1 / ZRANGE 0 -1
```

**一律改用**：

```bash
HSCAN key cursor COUNT 100
SSCAN key cursor COUNT 100
ZSCAN key cursor COUNT 100
LRANGE key start end            # 分页
```

#### 5.4.5 从源头限制

- 业务侧约束单集合大小上限（例如"用户关注列表上限 5000"）。
- 客户端埋点告警（见 5.3.5）。
- Code Review 关注大字段写入路径。

### 5.5 什么是热 Key？

热 Key 是指被大量请求频繁访问的 Key。value 大小不重要，**关键是访问 QPS 极高**（明星、爆款商品、热门直播间、全局配置等）。

### 5.6 热 Key 的危害

- 单节点 CPU 或网络打满。
- Cluster 下请求集中到某一个 shard，无法通过扩容缓解。
- Redis 响应变慢，同 shard 其他 Key 也受牵连。
- 严重时触发主从切换，甚至连锁雪崩。

### 5.7 如何解决热 Key？

热 Key **拆不了**（就一个"iphone15 库存"），必须靠"**挡在 Redis 前面**"和"**把一个 Key 变成多个 Key**"两条思路。

#### 5.7.1 客户端本地缓存（首选，效果最好）

```text
读请求 → 本地 Caffeine 缓存（命中率 99%+）
         ├─ 命中：直接返回，请求根本不出 JVM
         └─ 未命中：查 Redis，回填本地
```

- Java 首选 `Caffeine`（W-TinyLFU 算法，命中率高）
- TTL 建议**很短**（几秒到几十秒），避免脏数据
- 配合 **Redis Pub/Sub 或 Stream** 广播失效事件，进一步降低不一致时长
- 效果：Redis 单 key QPS 可以直接下降 1-2 个数量级

#### 5.7.2 热 Key 多副本（打散到多个 shard）

给同一逻辑 key 加后缀，人为让它落到不同 slot：

```text
原 key:   hotkey:iphone15:info

副本:    hotkey:iphone15:info:{0}
         hotkey:iphone15:info:{1}
         hotkey:iphone15:info:{2}
         ...
         hotkey:iphone15:info:{N-1}
```

- **读**：客户端随机 pick 一个后缀 `hotkey:...:{ThreadLocalRandom.nextInt(N)}`
- **写**：需要**同步更新所有副本**（写放大 N 倍）
- 副本数 N 建议 ≥ shard 数，才能真正打散
- **只适合"读远大于写"** 的场景（商品详情、配置项、榜单快照）

#### 5.7.3 读写分离 + 从节点扩容

- Cluster 每个 shard 可挂多个 slave
- 客户端配置成 "读走 slave，写走 master"（Lettuce/Jedis 均支持 `READONLY`）
- 缺点：从节点异步复制，可能读到旧值

#### 5.7.4 库存分桶（写热场景）

秒杀这种"写热"用副本方案不行，改用**库存分桶**：

```text
原：stock:sku1001 = 10000

拆：stock:sku1001:{0} = 1000
    stock:sku1001:{1} = 1000
    ...
    stock:sku1001:{9} = 1000
```

- 请求进来随机选一个桶做 `DECR`
- 某个桶扣光了降级找其他桶
- 相当于把一个热 Key 分散成 N 个"温 Key"，每个桶落在不同 slot
- 配合 Lua 脚本保证扣减原子性

#### 5.7.5 限流 + 熔断兜底

即使做了以上所有优化，也**必须**在应用层配限流兜底：

- 单 key 维度限流（Sentinel、Guava RateLimiter、Redis 自身滑动窗口）
- 超过阈值直接返回默认值/兜底页
- 保护 Redis 不被打死

#### 5.7.6 提前预热

大促、直播、开售前，提前把热 key 加载到本地缓存和各副本，避免"冷启动瞬间雪崩"。

### 5.8 如何提前发现热 Key？

热 Key 比大 Key 更难发现，因为**"热"是动态的**，且 Redis 本身没有细粒度的 per-key QPS 统计。生产上通常用下面几种手段互补：

#### 5.8.1 `redis-cli --hotkeys`（官方内置，需 LFU）

```bash
# 前提：maxmemory-policy 必须是 allkeys-lfu 或 volatile-lfu
redis-cli -h <host> --hotkeys
```

**原理：** LFU 淘汰策略下，Redis 会为每个 Key 维护一个近似访问频率计数器 `LFU counter`，`--hotkeys` 通过 `SCAN` 遍历后按此计数器排序。

**输出示例：**

```text
hot key found with counter: 254   keyname: hotkey:iphone15:info
hot key found with counter: 189   keyname: hot:live:room:1001
```

**局限：**
- 必须开 LFU 策略（很多生产集群用的是 LRU 或 noeviction，就用不了）
- 计数器是**近似值**，会随时间衰减，反映的是"近期热"而非"实时热"
- 依然是**采样**，不精确

#### 5.8.2 `MONITOR`（危险，仅短时间使用）

```bash
redis-cli -h <host> MONITOR | head -n 100000 > monitor.log
# 再用脚本聚合统计 key 出现次数
awk '{print $4}' monitor.log | sort | uniq -c | sort -rn | head -20
```

**原理：** `MONITOR` 会实时打印所有命令。

**警告：**
- **会严重影响 Redis 性能**（每条命令都要额外发送给 monitor client）
- 高 QPS 场景下可能直接把 Redis 打挂
- **只能短时间（几秒）在低峰期用于应急排查**，绝对不能长时间开

#### 5.8.3 客户端埋点（最推荐，无侵入）

在业务代码封装的 Redis 工具类里，对每次调用做本地统计：

```java
public String get(String key) {
    hotKeyCounter.record(key);   // 本地 LongAdder / Micrometer
    return redisTemplate.opsForValue().get(key);
}
```

统计方案：
- **本地滑动窗口 + Top N 上报**：每台机器维护"最近 1 分钟访问最多的 Top 100 Key"，定时上报到 Kafka / Prometheus。
- 服务端聚合所有机器的 Top N，得到全局热 Key 榜单。
- 超过阈值（如单 Key > 5000 QPS）触发告警，甚至自动推送到本地缓存。

**优点：**
- 完全不影响 Redis 性能
- 可以看到"哪台机器在打"，便于定位业务方
- 天然支持"热 Key 自动降级"（发现后自动走本地缓存）

**开源方案：**
- **京东 JD-hotkey**：客户端 SDK + Worker + Dashboard 一整套热 Key 探测框架，支持毫秒级发现和自动本地缓存推送。
- **有赞 TMC**、**得物 Burning** 等，思路类似。

#### 5.8.4 代理层统计（Proxy 方案）

如果架构里有 Redis Proxy（Codis、Twemproxy、自研 Proxy），可以在 Proxy 层做 per-key 统计：

- Proxy 天然是所有请求的必经之路
- 用滑动窗口 + Top K 算法（Count-Min Sketch、HeavyKeeper）实时输出 Top 热 Key
- 对 Redis 完全无侵入

**HeavyKeeper 算法**是热 Key 检测的经典算法，内存占用小、精度高，很多热 Key 探测系统底层都用它。

#### 5.8.5 抓包分析（临时手段）

```bash
tcpdump -i eth0 -w redis.pcap port 6379
# 用 Wireshark 或自写脚本解析 RESP 协议，聚合 key 频次
```

- 完全不影响 Redis
- 但需要机器权限、抓包量大时分析耗时
- 一般只在紧急排查时用

#### 5.8.6 云厂商 / 中间件平台

阿里云 Tair、腾讯云 Redis、AWS ElastiCache 等一般都提供**热 Key 实时监控面板**，底层也是 Proxy 或客户端上报方案。自建集群优先考虑接入 JD-hotkey 之类的开源框架。

#### 5.8.7 关键监控指标（间接发现）

即使没做 per-key 统计，也可以从整体指标发现热 Key 苗头：

| 指标 | 异常信号 |
|------|----------|
| 单 shard `instantaneous_ops_per_sec` | 明显高于其他 shard 数倍 → 数据倾斜或热 Key |
| 单 shard 网卡带宽 | 突然打满，其他 shard 正常 → 热 Key |
| 单 shard CPU | 打满 → 热 Key 或大 Key |
| `SLOWLOG` | 突然出现同一个 Key 的慢查询 → 大 Key + 热访问 |
| 主从复制延迟 | 突增 → 大 Key 写入 |

#### 5.8.8 如何按阈值扫描"访问频次超过特定次数"的热 Key？

`redis-cli --hotkeys` 只输出 TOP 几个近似热 Key，如果想捞"**所有访问频次 > X 的 Key**"，思路要按数据来源分开：

##### 方案一：服务端侧 —— `OBJECT FREQ` + `SCAN`（需 LFU 策略）

前提：`maxmemory-policy` 必须是 `allkeys-lfu` 或 `volatile-lfu`。

`OBJECT FREQ <key>` 会返回该 Key 的 **LFU 计数器值**（0-255 的近似频率）：

```bash
CONFIG SET maxmemory-policy allkeys-lfu
OBJECT FREQ user:profile:1001
(integer) 128
```

自写 `SCAN` + `OBJECT FREQ` 阈值过滤：

```python
# scan_hot_keys.py
import redis

r = redis.Redis(host='127.0.0.1', port=6379, decode_responses=True)
FREQ_THRESHOLD = 100     # LFU counter 阈值（0-255）

hot_keys = []
for key in r.scan_iter(count=500):
    try:
        freq = r.execute_command('OBJECT', 'FREQ', key)
        if freq >= FREQ_THRESHOLD:
            hot_keys.append((key, freq))
    except redis.ResponseError:
        continue   # 有些 key 可能已过期

hot_keys.sort(key=lambda x: x[1], reverse=True)
for k, f in hot_keys[:100]:
    print(f'{f:>4}  {k}')
```

**局限：**
- **LFU counter 是对数近似值**（不是真实 QPS），且会随时间**衰减**（`lfu-decay-time` 控制）
- 只能反映"近期相对热度"，无法给出精确 QPS
- 需要全库开 LFU 策略
- 依然要在 **slave** 上跑，避免影响 master

##### 方案二：客户端埋点 —— 阈值告警（推荐，能拿到真实 QPS）

在客户端本地做 per-key 频次统计（`ConcurrentHashMap<String, LongAdder>` + 定时上报），按阈值筛选：

```java
// 简化示意：每秒统计一次，超过阈值的 key 打印/上报
public class HotKeyDetector {
    private final ConcurrentHashMap<String, LongAdder> counter = new ConcurrentHashMap<>();
    private final long qpsThreshold;   // 例如 5000

    public void record(String key) {
        counter.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    // 每秒调度一次
    public void tick() {
        counter.forEach((k, adder) -> {
            long qps = adder.sumThenReset();
            if (qps > qpsThreshold) {
                log.warn("[HotKey] key={} qps={}", k, qps);
                hotKeyReporter.report(k, qps);   // 上报到监控系统
            }
        });
    }
}
```

**生产实践：**
- 单机存储成本大时，用 **HeavyKeeper / Count-Min Sketch** 近似算法，只保留 Top N
- 定时上报到中心（Kafka / Prometheus / 自建 Dashboard），全局按阈值聚合
- **京东 JD-hotkey** 直接支持"配置 QPS 阈值 → 自动推送到本地缓存"，几乎是这类需求的标准方案

##### 方案三：Proxy / 抓包 —— 全局精确统计

- Proxy 层用 HeavyKeeper 算法统计每个 Key 的实时 QPS，直接输出所有 > 阈值的 Key
- 抓包（`tcpdump`）后离线用脚本解析 RESP 协议，按 Key 聚合计数并过滤阈值
- 全局精确，无需业务侧配合，但需要有 Proxy 或抓包能力

##### 三种方案对比

| 方案 | 数据来源 | 是否需要 LFU | 精度 | 侵入性 | 适用场景 |
|------|----------|--------------|------|--------|----------|
| `OBJECT FREQ` + `SCAN` | Redis 内部计数器 | **需要** | 近似（0-255 + 衰减） | 只影响扫描期 | 临时排查、已开 LFU 的集群 |
| 客户端埋点 + 阈值告警 | 业务侧调用日志 | 不需要 | **真实 QPS** | 需要 SDK 改造 | **生产推荐**，可自动降级 |
| Proxy / 抓包 | 网络层 | 不需要 | 真实 QPS | 无 | 有 Proxy 或需要事后分析 |

#### 5.8.9 面试速记

> 发现热 Key 有四层手段：Redis 自带的 `--hotkeys`（要求 LFU 策略，只能采样）；`MONITOR` 应急抓取（危险，只能短时间用）；**客户端埋点 + 本地 Top N 上报**（生产最推荐，无侵入且可自动降级，如京东 JD-hotkey）；Proxy 层用 HeavyKeeper 算法实时统计。同时结合"单 shard OPS/带宽/CPU 突高"等宏观指标做兜底告警。如果想按"访问频次 > X"这种阈值捞热 Key：LFU 集群可以 `SCAN` + `OBJECT FREQ` 过滤（近似值）；更精确的方式是客户端埋点统计真实 QPS 后按阈值告警，Proxy/抓包侧也可以做全局精确统计。

### 5.9 Cluster 下大/热 Key 打爆单 shard 怎么办？（综合场景）

这是面试的经典追问："Cluster 已经分片了，为什么大 Key / 热 Key 还会打爆某个 shard？"

#### 5.9.1 为什么 Cluster 救不了单个 Key？

```text
Cluster 有 16384 个 slot
一个 key 的 slot 由 CRC16(key) 决定，是固定值
不管集群扩到 3 主还是 30 主，该 key 永远只属于同一个 slot
                                       ↓
                              永远只有一个 shard 处理它
                                       ↓
                              QPS 越高，这个 shard 越先挂
```

**结论：** Redis Cluster 的水平扩展是"**key 空间维度**"的，不是"**单 key 维度**"的。要解决单个热 key 的问题，必须**让 key 不再是"同一个 key"**。

#### 5.9.2 三种典型症状对号入座

| 类型 | 定义 | 主要打爆什么 | 处理方向 |
|------|------|--------------|----------|
| **Big Key** | 单 value 体积大：String > 1MB，集合元素十万+ | 内存倾斜、单命令阻塞主线程、网卡带宽、复制/迁移慢 | **拆**（见 5.4） |
| **Hot Key** | value 不大但 QPS 极高 | 单 shard CPU / 网卡打满 | **散 + 挡**（见 5.7） |
| **又大又热** | 两者叠加（最致命） | 一次请求几十 KB × 十万 QPS，网卡直接爆 | 先拆大，再散热 |

#### 5.9.3 又大又热的处理组合拳

以"明星直播间礼物榜 ZSet（几十万元素 + 每秒几万次查询）"为例：

1. **先拆大**：ZSet 按分数区间拆分（TOP 100 一个 key、TOP 101-1000 一个 key……）
2. **再散热**：TOP 100 这个热 key 再做本地缓存 + 多副本
3. **写路径异步化**：礼物写入用 MQ 削峰，Redis 侧用 `ZADD` 批量更新
4. **读接口降级**：非核心场景直接读本地缓存的稍旧数据，允许几百 ms 延迟

#### 5.9.4 兜底：限流 + 熔断 + 降级

不管前面做得多好，都**必须**在应用层配兜底：

- 单 Key 维度限流（超过阈值直接拒绝或返回默认值）
- 熔断：Redis 慢/超时占比超阈值 → 直接读本地缓存或 DB
- 降级：核心功能保留，非核心功能返回兜底数据

#### 5.9.5 面试三句话总结

1. **Cluster 分片不能救单 key**：因为 slot 由 `CRC16(key)` 唯一决定，扩容 shard 对同一个 key 没用，大/热 Key 必然打爆所在 shard 的 CPU、带宽或内存。
2. **Big Key 靠"拆"**：按业务维度、分页、时间、Hash 分桶拆成多个 key，让它们自然分散到不同 slot；删除必用 `UNLINK`，禁止全量读。
3. **Hot Key 靠"散 + 挡"**：客户端本地缓存扛住绝大多数读，热 key 加后缀多副本打散到多 shard，写热场景用分桶库存 + Lua，最后配限流熔断兜底；同时通过 `--bigkeys` / `--hotkeys` / 客户端埋点提前发现。

### 5.10 慢查询

Redis 提供慢查询日志：

```bash
SLOWLOG GET 10
SLOWLOG LEN
SLOWLOG RESET
```

**常见慢查询原因：**
- 大 Key 操作。
- 使用 `KEYS`、`SMEMBERS` 等全量命令。
- Lua 脚本耗时。
- 网络阻塞。
- 持久化或 fork 开销。

---

## 六、Redis 常见命令风险

### 6.1 KEYS

```bash
KEYS *
```

会遍历所有 Key，生产环境禁止使用。

**替代方案：**

```bash
SCAN cursor MATCH pattern COUNT 100
```

### 6.2 FLUSHALL / FLUSHDB

`FLUSHALL` 会清空整个 Redis 实例，`FLUSHDB` 会清空当前库，风险极高，应限制权限。

### 6.3 HGETALL / SMEMBERS / LRANGE 0 -1

如果集合很大，会导致大量数据返回，阻塞 Redis。

**优化：**
- 分页读取。
- 使用 `HSCAN`、`SSCAN`、`ZSCAN`。
- 控制集合大小。

### 6.4 DEL 大 Key

删除大 Key 可能阻塞主线程。

**替代方案：**

```bash
UNLINK big:key
```

`UNLINK` 会异步释放内存。

---

## 七、Redis 限流设计

### 7.1 固定窗口限流

**思路：** 固定时间窗口内计数。

```bash
INCR rate:user:1001:202608251620
EXPIRE rate:user:1001:202608251620 60
```

**优点：** 简单。

**缺点：** 窗口边界可能出现流量突刺。

### 7.2 滑动窗口限流

使用 ZSet 保存请求时间戳。

**流程：**
1. 删除窗口外请求记录。
2. 统计当前窗口内请求数量。
3. 未超过阈值则加入当前请求。
4. 设置过期时间。

示例：

```bash
ZREMRANGEBYSCORE rate:user:1001 0 1797999990000
ZCARD rate:user:1001
ZADD rate:user:1001 1798000000000 requestId
EXPIRE rate:user:1001 60
```

**适合场景：** 登录限制、短信验证码、接口访问频控。

### 7.3 令牌桶限流

令牌按固定速率生成，请求需要消耗令牌。

**优点：** 允许一定突发流量。

**适合场景：** 网关限流、核心接口保护。

### 7.4 限流实现注意事项

- 多条命令需要 Lua 保证原子性。
- Key 要设置 TTL，避免内存泄漏。
- 用户维度、IP 维度、接口维度可组合。

---

## 八、Redis 消息队列方案

### 8.1 List 实现队列

```bash
LPUSH queue msg
BRPOP queue 5
```

**优点：** 简单。

**缺点：**
- 没有 ACK。
- 消费失败容易丢消息。
- 不支持消费组。

### 8.2 Pub/Sub

发布订阅适合实时广播。

```bash
PUBLISH channel message
SUBSCRIBE channel
```

**缺点：**
- 消息不持久化。
- 消费者不在线会丢消息。
- 不适合作为可靠消息队列。

### 8.3 Stream

Stream 更适合可靠消息队列。

**优势：**
- 支持持久化。
- 支持消费组。
- 支持 ACK。
- 支持 Pending List。

**局限：**
- 功能和生态不如 Kafka、RocketMQ。
- 大规模消息堆积场景不建议替代专业 MQ。

---

## 九、Redis 与数据库一致性实战

### 9.1 Cache Aside Pattern

最常见的缓存模式。

**读流程：**

```text
读请求 -> 查缓存
        ├─ 命中：返回
        └─ 未命中：查数据库 -> 写缓存 -> 返回
```

**写流程：**

```text
写请求 -> 更新数据库 -> 删除缓存
```

### 9.2 为什么是删除缓存而不是更新缓存？

- 更新缓存容易发生并发覆盖。
- 有些写操作不一定马上被读到，更新缓存浪费资源。
- 删除缓存后由下一次读请求加载最新数据，更简单可靠。

### 9.3 强一致性怎么做？

Redis 缓存一般难以做到强一致，更多是最终一致。

如果必须强一致：
- 直接读数据库。
- 使用数据库事务和锁。
- 使用 ZooKeeper、etcd 等 CP 系统协调。
- 缩短缓存时间并控制并发。

### 9.4 延迟双删适合什么场景？

适合读写并发高，并且允许短暂不一致的业务。

**注意：** 延迟时间需要结合业务读 DB 和写缓存耗时设置，不是万能方案。

---

## 十、Redis 生产实践

### 10.1 Key 设计规范

推荐格式：

```text
业务名:模块名:对象类型:对象ID:字段
```

示例：

```text
mall:user:profile:1001
mall:order:detail:202608250001
mall:product:stock:sku1001
```

**规范建议：**
- Key 不要太长，避免浪费内存。
- 命名要有业务前缀，避免冲突。
- 设置合理 TTL。
- Cluster 多 Key 操作用 Hash Tag。

### 10.2 TTL 设计

- 热点数据：较长 TTL 或逻辑过期。
- 非热点数据：较短 TTL。
- 大量 Key：TTL 增加随机值。
- 空值缓存：短 TTL。

### 10.3 连接池配置

Java 应用中 Redis 客户端通常使用连接池。

**关注参数：**
- 最大连接数。
- 最大空闲连接。
- 最小空闲连接。
- 连接超时时间。
- 读取超时时间。
- 最大等待时间。

### 10.4 Redis 安全

- 设置强密码。
- 不暴露公网。
- 使用安全组限制访问。
- 禁用危险命令或重命名命令。
- 开启 TLS，视环境而定。
- 最小权限原则。

### 10.5 监控指标

| 指标 | 含义 |
|------|------|
| used_memory | 当前内存使用 |
| connected_clients | 客户端连接数 |
| instantaneous_ops_per_sec | QPS |
| keyspace_hits / misses | 缓存命中/未命中 |
| evicted_keys | 被淘汰 Key 数 |
| expired_keys | 过期 Key 数 |
| blocked_clients | 阻塞客户端数 |
| master_link_status | 主从复制状态 |
| mem_fragmentation_ratio | 内存碎片率 |
| latest_fork_usec | 最近 fork 耗时 |

### 10.6 内存碎片

```text
mem_fragmentation_ratio = used_memory_rss / used_memory
```

一般：
- 接近 1：较健康。
- 大于 1.5：碎片较多。
- 小于 1：可能发生 swap，风险高。

### 10.7 避免阻塞的实践

- 禁用 `KEYS`，使用 `SCAN`。
- 大 Key 删除使用 `UNLINK`。
- 避免复杂 Lua。
- 避免一次性读取大集合。
- 控制 pipeline 批量大小。
- 合理配置慢查询阈值。

---

## 十一、Java 后端常用 Redis 客户端

### 11.1 Jedis

Jedis 是较早的 Redis Java 客户端，API 接近 Redis 命令。

**特点：**
- 使用简单。
- 早期版本连接非线程安全，需要连接池。

### 11.2 Lettuce

Lettuce 基于 Netty，支持同步、异步、响应式 API。

**特点：**
- 线程安全。
- Spring Boot 默认 Redis 客户端。
- 支持 Redis Cluster、Sentinel。

### 11.3 Redisson

Redisson 更像 Redis 分布式对象和分布式并发工具库。

**常用能力：**
- 分布式锁。
- 分布式集合。
- 分布式限流器。
- 延迟队列。
- 信号量。

### 11.4 Spring Cache + Redis

Spring Cache 可以通过注解简化缓存使用。

```java
@Cacheable(cacheNames = "user", key = "#id")
public User getUserById(Long id) {
    return userMapper.selectById(id);
}
```

**注意：**
- 注解缓存适合简单场景。
- 复杂一致性、锁、逻辑过期通常需要手写控制。

---

## 十二、高频面试题

### 12.1 Redis 主从复制全量复制和增量复制区别？

全量复制会生成 RDB 并传给从节点，适合首次同步或积压缓冲区不足；增量复制只补发断线期间缺失的写命令，适合短暂网络断开后恢复。

### 12.2 Sentinel 和 Cluster 区别？

Sentinel 主要解决高可用自动故障转移，不负责数据分片；Cluster 同时解决高可用和水平扩展问题，通过 16384 个 Slot 做数据分片。

### 12.3 Redis Cluster 为什么是 16384 个槽？

槽数量太少会导致分布不均，太多会增加节点间 Gossip 通信和路由管理成本。16384 是分布均匀性和管理成本之间的折中。

### 12.4 Redis 如何处理大 Key？

先通过 `redis-cli --bigkeys`、`MEMORY USAGE`、`SCAN` 发现大 Key，再通过拆分、分页读取、限制集合大小、`UNLINK` 异步删除等方式处理。

### 12.5 Redis 如何处理热 Key？

可以使用本地缓存、热 Key 多副本、读写分离、限流降级、热点预热等方式处理。

### 12.6 Redis 适合做消息队列吗？

轻量级场景可以使用 Redis Stream；如果要求高吞吐、强可靠、大规模堆积、复杂消费语义，建议使用 Kafka、RocketMQ 等专业 MQ。

### 12.7 Redis 如何做接口限流？

简单场景可用 `INCR + EXPIRE` 实现固定窗口；更平滑的限流可用 ZSet 实现滑动窗口；复杂场景使用 Lua 保证多命令原子性。

### 12.8 Redis 和数据库如何保证一致性？

常用方案是 Cache Aside：读时先查缓存，未命中查数据库并回填；写时先更新数据库，再删除缓存。删除失败通过重试、MQ、binlog 监听和 TTL 兜底保证最终一致。

---

## 十三、面试回答模板

### 13.1 问：如何设计一个高并发缓存系统？

可以这样回答：

> 我会从缓存模式、Key 设计、TTL、热点保护、一致性和高可用几个方面考虑。读请求采用 Cache Aside，先查缓存，未命中查数据库并回填；写请求先更新数据库再删除缓存。TTL 增加随机值避免雪崩，空值缓存或布隆过滤器防穿透，热点 Key 使用逻辑过期、互斥锁、本地缓存或多副本防击穿。Redis 层面使用主从、哨兵或 Cluster 保证高可用，并监控命中率、内存、慢查询、大 Key、热 Key 等指标。

### 13.2 问：Redis 和数据库如何保证强一致？

可以这样回答：

> 严格强一致很难依赖缓存实现，因为缓存和数据库是两个系统，天然存在双写不一致问题。常见做法是保证最终一致，比如先更新数据库再删除缓存，删除失败通过 MQ 或 binlog 监听重试，并设置 TTL 兜底。如果业务必须强一致，比如支付、扣款等核心链路，应该直接读写数据库或使用数据库事务、锁、ZooKeeper、etcd 等 CP 协调系统，而不是依赖 Redis 缓存。

### 13.3 问：线上 Redis 变慢怎么排查？

可以这样回答：

> 我会先看慢查询日志和监控指标，确认是否有大 Key、热 Key、危险命令、连接数过高、内存淘汰、CPU 打满、网络带宽打满、fork 耗时、AOF fsync 抖动、主从延迟等问题。如果是大 Key，需要拆分或异步删除；如果是热 Key，可以加本地缓存、多副本或限流；如果是命令问题，禁止 `KEYS`、大集合全量读取等操作。

---

## 十四、速记总结

```text
高可用：主从复制、Sentinel、Cluster

主从复制：全量复制 + 增量复制，异步复制可能丢数据

Sentinel：监控、主观下线、客观下线、Leader 选举、故障转移

Cluster：16384 槽，CRC16(key) % 16384，MOVED/ASK，Hash Tag

大 Key：传输慢、阻塞、删除慢、迁移慢；拆分、分页、UNLINK

热 Key：请求集中；本地缓存、多副本、限流、读写分离

危险命令：KEYS、FLUSHALL、HGETALL 大 Hash、SMEMBERS 大 Set、DEL 大 Key

限流：固定窗口、滑动窗口、令牌桶，Lua 保证原子性

消息队列：List 简单，Pub/Sub 易丢，Stream 支持 ACK 和消费组

一致性：Cache Aside，先更新 DB 再删除缓存，重试/MQ/binlog/TTL 兜底
```
