# Kafka 和 RocketMQ 高性能、高可用对比总结

## 一、整体结论

### 1.1 一句话概括

Kafka 和 RocketMQ 都是高性能、高可用的分布式消息中间件，但二者的设计侧重点不同：

| 中间件 | 高性能核心 | 高可用核心 | 典型优势场景 |
|--------|------------|------------|--------------|
| Kafka | Partition 并行、顺序写、PageCache、零拷贝、批处理 | Partition 副本、ISR、Leader 选举、acks 机制 | 日志采集、实时流处理、大数据管道 |
| RocketMQ | Broker 级 CommitLog 顺序写、ConsumeQueue 索引、批量发送、PageCache、异步刷盘 | Master-Slave、同步复制、同步刷盘、Dledger、Controller | 电商交易、金融消息、顺序消息、事务消息 |

简单理解：

```text
Kafka 更偏向“高吞吐流式日志系统”：通过 Partition 横向扩展和日志顺序读写获得极高吞吐。

RocketMQ 更偏向“业务消息中间件”：在保证高吞吐的同时，对事务消息、顺序消息、延迟消息等业务特性支持更完善。
```

---

## 二、Kafka 的高性能与高可用

## 2.1 Kafka 高性能概括

Kafka 的高性能主要来自：

```text
Partition 并行 + 顺序写磁盘 + PageCache + 零拷贝 + 批处理 + 压缩
```

也就是说，Kafka 不是靠单点能力强，而是靠 **分区并行、磁盘顺序 IO 和操作系统能力** 把吞吐量做上去。

### 2.1.1 Partition 并行提升吞吐

Kafka 的 Topic 会拆分成多个 Partition：

```text
Topic: order-log
  Partition-0 -> BrokerA
  Partition-1 -> BrokerB
  Partition-2 -> BrokerC
```

这样做的好处是：

| 优点 | 说明 |
|------|------|
| 写入并行 | Producer 可以把消息分散写入多个 Partition |
| 读取并行 | Consumer Group 内多个 Consumer 可以并行消费不同 Partition |
| 存储扩展 | Partition 可以分布在不同 Broker 上 |
| 横向扩容 | 增加 Broker 和 Partition 可以提高整体吞吐 |

面试回答：

```text
Kafka 的并发能力主要来自 Partition。一个 Topic 拆成多个 Partition 后，不同 Partition 可以分布在不同 Broker 上，Producer 和 Consumer 都可以并行读写，因此 Kafka 能够通过增加 Partition 和 Broker 横向扩展吞吐量。
```

### 2.1.2 顺序写磁盘

Kafka 的 Partition 本质是追加日志文件：

```text
消息1 -> 消息2 -> 消息3 -> 消息4 -> ...
```

Kafka 不会频繁随机写磁盘，而是把消息追加到日志末尾。

顺序写的优势：

| 写入方式 | 性能特点 |
|----------|----------|
| 随机写 | 磁盘寻址开销大，性能差 |
| 顺序写 | 连续追加，磁盘吞吐高 |

面试回答：

```text
Kafka 虽然使用磁盘存储消息，但它是顺序追加写日志，避免了大量随机 IO。顺序写磁盘的性能很高，配合 PageCache 后，很多时候写入先进入内存缓存，再由操作系统异步刷盘。
```

### 2.1.3 PageCache 提升读写性能

Kafka 不把大量消息缓存放在 JVM 堆里，而是依赖操作系统 PageCache。

写入时：

```text
Producer -> Broker -> Partition Log -> PageCache -> 磁盘
```

读取时：

```text
Consumer 拉取最近消息 -> 命中 PageCache -> 直接从内存返回
```

PageCache 的优势：

| 优点 | 说明 |
|------|------|
| 减少磁盘读 | 热数据直接从内存读取 |
| 降低 JVM GC | 消息数据不大量进入 JVM 堆 |
| 重启后仍可能有效 | PageCache 属于操作系统，进程重启后缓存不一定完全丢失 |
| 和零拷贝配合好 | 数据可以从 PageCache 直接发送到网卡 |

面试回答：

```text
Kafka 大量依赖 PageCache。消息写入日志文件时先进入 PageCache，消费者通常读取最近写入的数据，这些数据大概率还在 PageCache 中，所以读取很快。同时 Kafka 不把大量消息放在 JVM 堆里，减少了 GC 压力。
```

### 2.1.4 零拷贝减少数据复制

Kafka Consumer 拉取消息时，Broker 需要把磁盘日志发送到网络。

传统方式：

```text
磁盘 -> 内核缓冲区 -> 用户空间 -> Socket 缓冲区 -> 网卡
```

零拷贝方式：

```text
磁盘/PageCache -> Socket -> 网卡
```

零拷贝的优势：

| 优点 | 说明 |
|------|------|
| 减少 CPU 拷贝 | 避免数据在内核态和用户态来回复制 |
| 降低 GC 压力 | 消息内容不需要进入 JVM 堆 |
| 提高网络发送效率 | 适合大批量日志数据传输 |

面试回答：

```text
Kafka 使用零拷贝技术把日志文件中的数据直接从 PageCache 发送到 Socket，减少内核态和用户态之间的数据拷贝，降低 CPU 和 GC 压力，非常适合 Consumer 拉取消息和副本同步这类大批量数据传输场景。
```

### 2.1.5 批处理和压缩

Kafka 的 Producer 和 Consumer 都支持批量处理。

Producer 端：

```text
多条消息 -> 聚合成一个 batch -> 一次网络请求发送
```

Consumer 端：

```text
一次 poll 拉取一批消息
```

批处理优势：

| 优点 | 说明 |
|------|------|
| 减少网络请求次数 | 多条消息合并发送 |
| 提高磁盘写入效率 | 批量追加写日志 |
| 提高压缩率 | 一批消息一起压缩效果更好 |
| 提升整体吞吐 | 用少量请求处理更多消息 |

常见压缩算法：

```text
gzip、snappy、lz4、zstd
```

面试回答：

```text
Kafka 的 Producer 会把多条消息组成 batch 后发送，Consumer 也支持批量拉取。批处理减少了网络 IO 次数和系统调用次数，同时批量消息压缩率更高，因此能显著提升吞吐量。
```

---

## 2.2 Kafka 高可用概括

Kafka 的高可用主要来自：

```text
Partition 副本 + Leader/Follower + ISR + Controller 选举 + acks 机制
```

也就是说，Kafka 通过给每个 Partition 建立多个副本，并在 Leader 故障后从同步副本中选举新的 Leader 来保证可用性。

### 2.2.1 Partition 副本机制

Kafka 每个 Partition 可以配置多个副本：

```text
TopicA-Partition0:
  Broker1: Leader
  Broker2: Follower
  Broker3: Follower
```

角色说明：

| 角色 | 说明 |
|------|------|
| Leader | 处理客户端读写请求 |
| Follower | 从 Leader 拉取日志进行同步 |
| ISR | 与 Leader 保持同步的副本集合 |

面试回答：

```text
Kafka 的高可用是 Partition 级别的。每个 Partition 有多个副本，其中一个是 Leader，其余是 Follower。Producer 和 Consumer 默认访问 Leader，Follower 持续从 Leader 拉取数据。一旦 Leader 宕机，可以从 ISR 中选择新的 Leader。
```

### 2.2.2 ISR 保证副本同步质量

ISR 是 In-Sync Replicas，表示和 Leader 保持同步的副本集合。

```text
ISR = Leader + 跟得上的 Follower
```

如果某个 Follower 长时间落后，会被移出 ISR。

相关配置：

```properties
replica.lag.time.max.ms=30000
min.insync.replicas=2
```

ISR 的作用：

| 作用 | 说明 |
|------|------|
| 控制可靠副本集合 | 只有同步副本才算可靠 |
| Leader 选举依据 | 优先从 ISR 中选新 Leader |
| 配合 acks=all | 保证消息写入多个同步副本 |

面试回答：

```text
ISR 是 Kafka 判断副本是否可靠的核心机制。只有跟 Leader 保持同步的副本才会在 ISR 中，Leader 故障时优先从 ISR 中选新 Leader。配合 acks=all 和 min.insync.replicas，可以避免消息只写到单个副本就返回成功。
```

### 2.2.3 acks 机制控制写入可靠性

Producer 可以通过 acks 控制消息确认级别。

| acks | 含义 | 可靠性 | 性能 |
|------|------|--------|------|
| 0 | 不等待 Broker 响应 | 最低 | 最高 |
| 1 | Leader 写入成功即返回 | 中 | 高 |
| all / -1 | ISR 副本确认后返回 | 最高 | 较低 |

强可靠推荐配置：

```properties
acks=all
replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false
```

面试回答：

```text
Kafka 通过 acks 控制生产者写入可靠性。acks=all 时，Leader 需要等待 ISR 中足够副本确认后才返回成功。再配合 min.insync.replicas 和禁止 unclean leader election，可以显著降低消息丢失风险。
```

### 2.2.4 Controller 和 Leader 选举

Kafka 集群中会有一个 Controller，负责管理分区 Leader 选举、Broker 上下线、Topic 元数据变更等。

Leader 故障流程：

```text
1. Broker 宕机
2. Controller 感知到 Broker 下线
3. 找到受影响的 Partition
4. 从 ISR 中选择新的 Leader
5. 更新元数据并通知其他 Broker
6. Producer / Consumer 感知新 Leader 后继续读写
```

面试回答：

```text
Kafka 通过 Controller 管理集群元数据和 Leader 选举。当某个 Broker 宕机时，Controller 会将该 Broker 上的 Leader Partition 迁移到其他 ISR 副本上，从而实现故障转移。
```

---

## 三、RocketMQ 的高性能与高可用

## 3.1 RocketMQ 高性能概括

RocketMQ 的高性能主要来自：

```text
Broker 级 CommitLog 顺序写 + ConsumeQueue 轻量索引 + PageCache + 批量发送 + 异步刷盘
```

RocketMQ 的核心设计是：

```text
所有 Topic、所有 Queue 的消息先统一追加写入 Broker 的 CommitLog，
然后再异步构建每个 Queue 对应的 ConsumeQueue 索引。
```

### 3.1.1 CommitLog 统一顺序写

RocketMQ 不让每个 Queue 独立存完整消息，而是把消息统一写入 Broker 级别的 CommitLog。

```text
OrderTopic queue0 消息 -> CommitLog
PayTopic queue1 消息   -> CommitLog
LogTopic queue2 消息   -> CommitLog
```

写入效果：

```text
CommitLog:
offset=100  OrderTopic 消息
offset=200  PayTopic 消息
offset=300  LogTopic 消息
offset=400  OrderTopic 消息
```

优势：

| 优点 | 说明 |
|------|------|
| 顺序写 | 避免大量随机写 |
| 写入路径简单 | 所有消息统一追加到 CommitLog |
| 多 Topic/Queue 写入稳定 | 不会因为 Queue 很多导致大量独立文件随机写 |
| 适合业务消息混合写入 | 多业务消息统一进入 CommitLog |

面试回答：

```text
RocketMQ 的高性能核心是 CommitLog 统一顺序写。无论消息属于哪个 Topic、哪个 Queue，Broker 都先把完整消息追加到 CommitLog 中，再异步构建 ConsumeQueue 索引。这样可以把多个逻辑队列的写入合并成一个物理顺序写流。
```

### 3.1.2 ConsumeQueue 轻量索引提升读取效率

RocketMQ 的 ConsumeQueue 不存完整消息，只存索引：

```text
commitLogOffset + size + tagHashCode
```

读取流程：

```text
Consumer 拉取某个 Queue 的消息
        ↓
Broker 先查 ConsumeQueue
        ↓
拿到 commitLogOffset 和 size
        ↓
再去 CommitLog 读取完整消息
```

优势：

| 优点 | 说明 |
|------|------|
| 索引小 | ConsumeQueue 单条记录固定且很小 |
| 读取快 | 先定位索引，再读取 CommitLog |
| 支持按 Queue 消费 | 每个 MessageQueue 有独立消费进度 |
| 支持 Tag 过滤 | 可通过 tagHashCode 做初步过滤 |

面试回答：

```text
RocketMQ 用 ConsumeQueue 作为逻辑队列的物理索引。ConsumeQueue 不存完整消息，只记录消息在 CommitLog 中的位置和大小。消费者按 Queue 读取时，先查 ConsumeQueue，再根据 offset 回到 CommitLog 读取完整消息，这样兼顾了统一写入和按队列消费。
```

### 3.1.3 PageCache 和 mmap

RocketMQ 的 CommitLog、ConsumeQueue、IndexFile 都是磁盘文件，通常通过 MappedFile / mmap 方式访问。

写入流程：

```text
Producer -> Broker -> mmap 映射区 -> PageCache -> 磁盘
```

读取流程：

```text
Consumer -> ConsumeQueue -> CommitLog -> PageCache 命中则直接内存返回
```

优势：

| 优点 | 说明 |
|------|------|
| 写入快 | 写入先进入 PageCache |
| 读取快 | 热数据直接从内存读取 |
| 减少 JVM 堆压力 | 文件缓存由操作系统管理 |
| 适合顺序写 | CommitLog 追加写和 PageCache 配合好 |

面试回答：

```text
RocketMQ 也大量依赖 PageCache。消息写入 CommitLog 时，通常先写入 mmap 映射区域，也就是操作系统 PageCache，再根据刷盘策略同步或异步刷盘。读取时，如果 ConsumeQueue 和 CommitLog 命中 PageCache，性能会很高。
```

### 3.1.4 批量发送和异步刷盘

RocketMQ 支持批量发送、异步发送、单向发送等方式提高吞吐。

| 方式 | 说明 | 性能 | 可靠性 |
|------|------|------|--------|
| 同步发送 | 等待 Broker 响应 | 中 | 高 |
| 异步发送 | 回调通知结果 | 高 | 中高 |
| 单向发送 | 不等待响应 | 最高 | 低 |
| 批量发送 | 多条消息一次发送 | 高 | 取决于确认方式 |

刷盘策略：

| 策略 | 含义 | 性能 | 可靠性 |
|------|------|------|--------|
| SYNC_FLUSH | 同步刷盘，刷到磁盘后返回 | 低 | 高 |
| ASYNC_FLUSH | 异步刷盘，写入 PageCache 后返回 | 高 | 中 |

面试回答：

```text
RocketMQ 可以通过异步发送、批量发送、异步刷盘提升吞吐。异步刷盘下，消息写入 PageCache 后即可返回，由后台线程刷盘，性能更高；同步刷盘则可靠性更强，但吞吐会下降。
```

---

## 3.2 RocketMQ 高可用概括

RocketMQ 的高可用主要来自：

```text
Master-Slave 复制 + 同步复制 + 同步刷盘 + Dledger/Controller 自动切主
```

RocketMQ 的高可用重点是：

```text
通过主从复制保证消息有副本，
通过同步复制和同步刷盘提高可靠性，
通过 Dledger 或 Controller 实现自动故障转移。
```

### 3.2.1 Master-Slave 主从复制

传统 RocketMQ 中，一个 Broker Master 可以对应一个或多个 Slave。

```text
BrokerA-Master
      ↓ 同步/异步复制 CommitLog
BrokerA-Slave
```

复制策略：

| 策略 | 含义 | 性能 | 可靠性 |
|------|------|------|--------|
| SYNC_MASTER | Master 等 Slave 同步后再返回 | 低 | 高 |
| ASYNC_MASTER | Master 写成功后返回，Slave 异步同步 | 高 | 中 |

面试回答：

```text
RocketMQ 传统高可用依赖 Master-Slave 主从复制。Producer 写 Master，Slave 从 Master 复制 CommitLog。同步复制可靠性更高，异步复制性能更好。
```

### 3.2.2 同步刷盘和同步复制

RocketMQ 要做到强可靠，通常需要同时考虑：

```text
消息是否刷到磁盘
消息是否复制到 Slave
```

强可靠配置：

```properties
flushDiskType=SYNC_FLUSH
brokerRole=SYNC_MASTER
```

配置对比：

| 组合 | 性能 | 可靠性 | 说明 |
|------|------|--------|------|
| SYNC_FLUSH + SYNC_MASTER | 低 | 最高 | 核心交易、金融场景 |
| ASYNC_FLUSH + ASYNC_MASTER | 高 | 中 | 日志、监控、通知场景 |
| SYNC_FLUSH + ASYNC_MASTER | 中 | 较高 | 本机可靠，但主从可能落后 |
| ASYNC_FLUSH + SYNC_MASTER | 中 | 较高 | 有副本，但本机刷盘可能滞后 |

面试回答：

```text
RocketMQ 的可靠性由刷盘策略和复制策略共同决定。同步刷盘保证消息落盘，同步复制保证消息同步到 Slave。核心业务通常选择 SYNC_FLUSH + SYNC_MASTER，吞吐要求更高的场景可以选择异步刷盘和异步复制。
```

### 3.2.3 Dledger 和 Controller 自动切主

传统 Master-Slave 模式下，Master 宕机后 Slave 通常不能自动变成 Master，需要人工或额外机制处理。

为了解决自动故障转移，RocketMQ 提供了：

| 模式 | 说明 |
|------|------|
| Dledger | 基于 Raft 协议，多副本自动选主 |
| Controller | RocketMQ 5.x 引入，负责 Broker 主从切换管理 |

Dledger 模式：

```text
Broker Group:
  Leader  -> 处理读写
  Follower -> 复制日志
  Follower -> 复制日志

Leader 宕机后，Follower 通过 Raft 选出新 Leader
```

面试回答：

```text
RocketMQ 传统 Master-Slave 主要提供复制能力，但自动切主能力较弱。Dledger 基于 Raft 协议，可以在 Master 故障时自动选出新的 Leader。RocketMQ 5.x 的 Controller 模式也增强了 Broker 主从自动切换能力。
```

### 3.2.4 NameServer 高可用

RocketMQ 的 NameServer 是轻量级注册中心，多个 NameServer 之间互不通信。

```text
Broker -> 定期向所有 NameServer 上报路由
Producer/Consumer -> 从 NameServer 拉取 Topic 路由
```

NameServer 特点：

| 特点 | 说明 |
|------|------|
| 无状态 | 不保存强一致状态 |
| 可多节点部署 | 任意节点挂掉不影响整体 |
| 最终一致 | Broker 定期上报路由 |
| 运维简单 | 不需要 ZooKeeper 那样的强一致协调 |

面试回答：

```text
RocketMQ 的 NameServer 是无状态、轻量级的注册中心。多个 NameServer 之间不需要选主，也不互相同步，Broker 定期向所有 NameServer 上报路由。即使某个 NameServer 挂掉，Producer 和 Consumer 仍然可以从其他 NameServer 获取路由，因此可用性较高。
```

---

## 四、Kafka 和 RocketMQ 高性能对比

### 4.1 高性能对比表

| 对比项 | Kafka | RocketMQ |
|--------|-------|----------|
| 写入模型 | Partition 日志顺序写 | Broker 级 CommitLog 统一顺序写 |
| 并行能力 | Partition 天然并行 | 多 Queue、多 Broker 并行 |
| 索引结构 | OffsetIndex、TimeIndex | ConsumeQueue、IndexFile |
| PageCache | 大量依赖 | 大量依赖 |
| 零拷贝 | 典型优势，Consumer 拉取时使用 | 也可利用文件传输优化，但核心强调 mmap + PageCache |
| 批处理 | Producer batch、Consumer poll | 批量发送、批量拉取 |
| 适合场景 | 日志、大数据、流处理 | 交易、电商、业务消息 |

### 4.2 高性能一句话总结

```text
Kafka 的性能优势更偏向 Partition 并行和日志系统吞吐；RocketMQ 的性能优势更偏向 Broker 级 CommitLog 统一写入和业务消息场景下的稳定吞吐。
```

---

## 五、Kafka 和 RocketMQ 高可用对比

### 5.1 高可用对比表

| 对比项 | Kafka | RocketMQ |
|--------|-------|----------|
| 副本模型 | Partition 级副本 | Broker 主从复制 / Dledger / Controller |
| 写入节点 | Partition Leader | Broker Master / Dledger Leader |
| 同步机制 | Follower 拉取 Leader 日志 | Slave 复制 Master CommitLog |
| 自动故障转移 | ISR 中选新 Leader | Dledger / Controller 支持自动切主 |
| 可靠性配置 | acks=all、min.insync.replicas、ISR | SYNC_FLUSH、SYNC_MASTER、Dledger |
| 注册/协调 | ZooKeeper / KRaft | NameServer / Controller |
| 强可靠重点 | 多 ISR 副本确认 | 同步刷盘 + 同步复制 |

### 5.2 高可用一句话总结

```text
Kafka 通过 Partition 副本、ISR 和 Leader 选举实现高可用；RocketMQ 通过 Broker 主从复制、同步刷盘、同步复制以及 Dledger/Controller 实现高可用。
```

---

## 六、面试回答模板

### 6.1 如果问 Kafka 为什么高性能？

可以这样回答：

```text
Kafka 高性能主要来自几个方面：第一，Topic 拆分成多个 Partition，可以分布在不同 Broker 上并行读写；第二，Partition 日志采用顺序追加写，磁盘 IO 效率高；第三，Kafka 大量利用操作系统 PageCache，减少磁盘读写和 JVM GC 压力；第四，Consumer 拉取消息时可以使用零拷贝，减少内核态和用户态之间的数据复制；第五，Producer 和 Consumer 都支持批处理和压缩，减少网络 IO 次数。因此 Kafka 在日志采集和流处理场景下吞吐非常高。
```

### 6.2 如果问 Kafka 为什么高可用？

可以这样回答：

```text
Kafka 的高可用主要依赖 Partition 副本机制。每个 Partition 有一个 Leader 和多个 Follower，Follower 从 Leader 拉取数据同步。Kafka 使用 ISR 维护同步副本集合，Producer 配置 acks=all 时，需要 ISR 中足够副本确认后才返回成功。Leader 宕机时，Controller 会优先从 ISR 中选择新的 Leader，从而保证服务继续可用。生产中一般配置 replication.factor=3、min.insync.replicas=2、acks=all，并关闭 unclean leader election 来提高可靠性。
```

### 6.3 如果问 RocketMQ 为什么高性能？

可以这样回答：

```text
RocketMQ 的高性能核心是 Broker 级 CommitLog 统一顺序写。所有 Topic、Queue 的消息都先追加到 CommitLog，而不是每个 Queue 单独写完整消息，从而把多个逻辑队列的写入合并成一个物理顺序写流。之后 RocketMQ 再异步构建 ConsumeQueue 索引，消费者通过 ConsumeQueue 定位到 CommitLog 读取完整消息。同时 RocketMQ 依赖 PageCache 和 mmap 提升读写性能，并支持批量发送、异步发送和异步刷盘，因此在业务消息场景下吞吐也很高。
```

### 6.4 如果问 RocketMQ 为什么高可用？

可以这样回答：

```text
RocketMQ 的高可用主要依赖 Broker 主从复制、刷盘策略和自动切主机制。传统模式下，Producer 写 Master，Slave 从 Master 复制 CommitLog；同步复制可以保证消息写入 Master 后同步到 Slave，再返回成功。同步刷盘可以保证消息真正落盘。核心业务可以使用 SYNC_FLUSH + SYNC_MASTER 提高可靠性。RocketMQ 还支持 Dledger 和 Controller 模式，用于实现 Master 故障后的自动切主，从而提升整体可用性。
```

---

## 七、最终总结

### 7.1 Kafka 总结

```text
Kafka 的核心优势是高吞吐和流式处理能力。
它通过 Partition 并行、顺序写、PageCache、零拷贝和批处理实现高性能；
通过 Partition 副本、ISR、Leader 选举和 acks 机制实现高可用。
```

### 7.2 RocketMQ 总结

```text
RocketMQ 的核心优势是业务消息能力和可靠性。
它通过 CommitLog 统一顺序写、ConsumeQueue 索引、PageCache、批量发送实现高性能；
通过 Master-Slave、同步刷盘、同步复制、Dledger/Controller 实现高可用。
```

### 7.3 对比记忆

```text
Kafka：Partition 并行 + ISR 副本，适合日志和流处理。
RocketMQ：CommitLog 统一写 + 主从复制，适合交易和业务消息。
```
