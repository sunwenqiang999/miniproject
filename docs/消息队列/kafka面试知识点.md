# Kafka 面试知识点总结

## 一、Kafka 基础概念

### 1.1 什么是 Kafka？
Kafka 是一个分布式流处理平台，最初由 LinkedIn 开发，后来成为 Apache 顶级项目。它具有高吞吐、低延迟、可水平扩展、高可用等特性。

**核心能力：**
- 发布/订阅消息流
- 容错的持久化存储
- 流处理

### 1.2 核心组件

| 组件 | 说明 |
|------|------|
| **Producer（生产者）** | 消息的生产者，向 Kafka Topic 发送消息 |
| **Consumer（消费者）** | 消息的消费者，从 Topic 读取消息 |
| **Broker（代理）** | Kafka 集群中的一个节点 |
| **Topic（主题）** | 消息的分类/类别名 |
| **Partition（分区）** | Topic 的物理分区，是 Kafka 扩展性和高并发的核心 |
| **Replica（副本）** | 分区的副本，提供数据冗余 |
| **Consumer Group（消费者组）** | 消费者组成的逻辑组，同一组内共享消费进度 |
| **ZooKeeper / KRaft** | 集群协调服务（新版用 KRaft 替代 ZK） |

### 1.3 架构图

```
                    +-------------------+
                    |    ZooKeeper      |
                    |   (集群协调)       |
                    +-------------------+
                           |
        +------------------+------------------+
        |                  |                  |
   +---------+        +---------+        +---------+
   | Broker0 |        | Broker1 |        | Broker2 |
   +---------+        +---------+        +---------+
        |                  |                  |
   +----+----+        +----+----+        +----+----+
   |Topic-A  |        |Topic-A  |        |Topic-A  |
   |Part-0   |        |Part-1   |        |Part-2   |
   |Leader   |        |Leader   |        |Leader   |
   +---------+        +---------+        +---------+

   Producer ---> 写入到对应 Partition Leader
   Consumer Group ---> 各消费者消费不同 Partition
```

---

## 二、Kafka 核心机制

### 2.1 分区（Partition）机制

**为什么分区？**
- 提高并发：多个分区可以并行读写
- 提高扩展性：分区可以分布在不同 Broker 上

**分区策略：**
1. **指定 Partition**：直接发送到指定分区
2. **有 Key**：对 Key 进行 Hash 取模选择分区：`hash(key) % partitionCount`
3. **无 Key**：轮询（Round Robin）或 Sticky（粘性）

**消息分配示例：**
```
Topic: order-events (3 Partitions)

Producer 发送 key=order_1001 -> hash(1001) % 3 = 1 -> Partition 1
Producer 发送 key=order_1002 -> hash(1002) % 3 = 2 -> Partition 2
```

### 2.2 副本（Replica）机制

- **Leader Replica**：处理该分区所有读写请求
- **Follower Replica**：从 Leader 拉取数据进行同步，不处理客户端请求
- **ISR（In-Sync Replicas）**：与 Leader 保持同步的副本集合
- **OSR（Out-Sync Replicas）**：滞后过多的副本

**关键配置：**
```
replication.factor = 3           # 副本总数
min.insync.replicas = 2         # ISR 最少副本数
unclean.leader.election.enable = false  # 是否允许非 ISR 副本成为 Leader
```

### 2.3 消息存储机制

**Log Segment（日志分段）：**
- 每个 Partition 是一个目录
- 每个 Partition 由多个 Segment 组成
- 每个 Segment 包含：
  - `.log`：实际消息数据
  - `.index`：偏移量索引文件
  - `.timeIndex`：时间戳索引文件

```
/var/kafka-logs/topic-0/
  ├── 00000000000000000000.log
  ├── 00000000000000000000.index
  ├── 00000000000000000000.timeIndex
  ├── 00000000000000368769.log
  ├── 00000000000000368769.index
  └── 00000000000000368769.timeIndex
```

**写入流程：**
1. Producer 发送消息到 Leader
2. Leader 将消息追加到本地 Log Segment
3. Follower 从 Leader 拉取消息
4. 根据 acks 策略确认

### 2.4 ACK 应答机制

| acks 值 | 说明 | 可靠性 | 吞吐量 |
|---------|------|--------|--------|
| 0 | 生产者不等待确认 | 最低 | 最高 |
| 1 | 等待 Leader 确认 | 中等 | 中等 |
| -1 (all) | 等待所有 ISR 确认 | 最高 | 最低 |

---

## 三、Kafka 高级特性

### 3.1 消费者组与 Rebalance

**消费者组特点：**
- 一个 Partition 在一个消费者组内只能被一个消费者消费
- 一个消费者可以消费多个分区
- 不同消费者组之间互不影响（广播模式）

**Rebalance（再均衡）触发条件：**
1. 消费者加入组
2. 消费者离开组（崩溃或主动退出）
3. Topic 分区数变化
4. 订阅的 Topic 列表变化

**Rebalance 策略：**
- **Range**：按主题范围分配
- **RoundRobin**：轮询分配
- **Sticky**：粘性分配（尽量保持原分配）

### 3.2 Offset 管理

**Offset 存储位置：**
- 老版本（< 0.9）：ZooKeeper
- 新版本：Kafka 内部 Topic `__consumer_offsets`

**自动提交 vs 手动提交：**
```java
// 自动提交
props.put("enable.auto.commit", "true");
props.put("auto.commit.interval.ms", "5000");

// 手动提交
props.put("enable.auto.commit", "false");
consumer.commitSync();  // 同步提交
consumer.commitAsync(); // 异步提交
```

**重复消费和消息丢失问题：**
- 自动提交可能在处理未完成时就提交了 -> 消息丢失
- 手动提交需要处理完再提交，但如果处理失败 -> 重复消费

### 3.3 Exactly-Once 语义

**三种消息投递语义：**
1. **At Most Once**：最多一次（可能丢失）
2. **At Least Once**：至少一次（可能重复）
3. **Exactly Once**：精确一次（不丢失不重复）

**Kafka 实现 Exactly-Once 的方式：**
- 幂等性 Producer（PID + SeqNum）
- 事务（Transaction）
- 消费端 `read_process_commit` 模式

```
# 启用幂等性
enable.idempotence=true

# 事务配置
transactional.id=my-transactional-id
```

### 3.4 高性能设计

**顺序写磁盘：**
- Kafka 追加写入，利用磁盘顺序写，性能远高于随机写
- 顺序写速度可达 600MB/s

**PageCache：**
- Kafka 不在 JVM 堆中维护缓存，依赖操作系统的 PageCache
- 减少 GC 影响，重启后缓存仍有效

**零拷贝（Zero Copy）：**
- 使用 `sendfile()` 系统调用
- 数据直接从内核空间传输到网卡，无需经过用户空间

```
传统方式：
  磁盘 -> 内核缓冲区 -> 用户空间 -> Socket缓冲区 -> 网卡

零拷贝：
  磁盘 -> 内核缓冲区 -> 网卡
```

**Batch 批处理：**
- 生产者批量发送
- 消费者批量拉取
- 减少网络 IO 次数

---

## 四、Kafka 高可用

### 4.1 集群架构

```
               +-----------+
               | Controller| (某个Broker被选为Controller)
               +-----------+
                     |
         +-----------+-----------+
         |           |           |
     Broker-0   Broker-1   Broker-2
         |           |           |
      [P0:L]      [P1:L]      [P2:L]
      [P1:F]     [P2:F]      [P0:F]
```

### 4.2 Leader 选举

**Controller 选举：**
1. 第一个注册到 ZooKeeper 的 Broker 成为 Controller
2. Controller 负责分区 Leader 选举和副本分配

**Partition Leader 选举：**
- 优先从 ISR 列表中选择第一个存活的副本
- 如果 `unclean.leader.election.enable=true`，允许非 ISR 副本成为 Leader（有数据丢失风险）

### 4.3 生产端高可用配置

```properties
# acks=all：所有 ISR 副本确认
acks=all
# 重试次数
retries=2147483647
# 重试间隔
retry.backoff.ms=100
# 开启幂等性
enable.idempotence=true
```

### 4.4 消费端高可用配置

```properties
# 关闭自动提交
enable.auto.commit=false
# 手动提交 offset
```

---

## 五、Kafka 常见面试题

### Q1: Kafka 为什么这么快？
1. **顺序写磁盘**：追加写入，避免随机寻道
2. **PageCache**：依赖操作系统缓存，减少 GC
3. **零拷贝**：sendfile 减少 CPU 拷贝
4. **批处理**：批量发送和拉取
5. **分区并行**：多分区并行读写

### Q2: Kafka 如何保证消息不丢失？
| 环节 | 措施 |
|------|------|
| Producer 端 | acks=all + retries + 幂等性 |
| Broker 端 | replication.factor≥3 + min.insync.replicas≥2 + unclean.leader.election.enable=false |
| Consumer 端 | 关闭自动提交，手动提交 offset |

### Q3: Kafka 如何保证消息有序性？
- **全局有序**：只设置 1 个 Partition（牺牲并发）
- **局部有序**：同一 Key 的消息发到同一 Partition（推荐）
- **注意事项**：开启重试后，如果消息发送失败重试，可能导致乱序。解决方案：
  - 设置 `max.in.flight.requests.per.connection=1`（降低吞吐）
  - 开启幂等性后可放宽到 5

### Q4: Kafka 如何实现消息去重？
1. **消费端去重**：维护已消费消息的 ID 集合（如 Redis）
2. **幂等性 Producer**：PID + SeqNum，单分区单会话内去重
3. **事务**：跨分区跨会话去重
4. **业务层幂等**：数据库唯一约束、状态机等

### Q5: Kafka 的 Rebalance 问题如何解决？
**问题：** Rebalance 期间消费者无法消费，导致短暂停服

**优化方案：**
1. 增加 session timeout 和 heartbeat
2. 合理设置 `max.poll.interval.ms`
3. 使用 Sticky 分配策略
4. 使用 Cooperative Rebalance（增量协作式再均衡）

### Q6: Kafka 和其他 MQ 的区别？
| 特性 | Kafka | RocketMQ | RabbitMQ |
|------|-------|----------|----------|
| 语言 | Scala/Java | Java | Erlang |
| 吞吐量 | 百万级 | 十万级 | 万级 |
| 延迟 | 毫秒级 | 毫秒级 | 微秒级 |
| 可用性 | 非常高 | 非常高 | 高 |
| 消息可靠性 | 高 | 高 | 非常高 |
| 适用场景 | 日志/流处理 |金融/电商| 复杂路由 |

### Q7: Kafka 的 ISR 机制是什么？
ISR（In-Sync Replicas）是与 Leader 保持同步的副本集合。维护方式：
- Follower 周期性从 Leader 拉取数据
- 如果 Follower 滞后超过 `replica.lag.time.max.ms`，被移出 ISR
- 只有 ISR 中的副本才有资格被选为 Leader

### Q8: Kafka 事务机制？
```
// 事务示例
Producer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();

try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>(topic, key, value));
    // 提交消费者 offset
    producer.sendOffsetsToTransaction(offsets, consumerGroupId);
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
```

### Q9: Kafka 消息积压怎么处理？
1. **临时扩容**：增加消费者（如果分区数允许）
2. **增加分区**：需要评估是否可以修改
3. **快速消费**：将消息先转存，后处理
4. **定位根因**：消费者慢的原因（GC、数据库慢等）

### Q10: Kafka 的 Controller 是什么？
- Controller 是一个特殊的 Broker，负责管理集群状态
- 职责：分区 Leader 选举、副本状态管理、Topic 创建/删除
- 选举：通过 ZooKeeper 临时节点竞争
- 新版本使用 KRaft 替代 ZooKeeper

### Q11: RocketMQ 没有副本机制吗？和 Kafka 的副本机制有什么区别？

**结论：RocketMQ 有副本/复制机制，但它的副本模型和 Kafka 不一样。**

Kafka 的副本是 **Partition 级别副本**：

```text
TopicA-Partition0:
  Broker1: Leader
  Broker2: Follower
  Broker3: Follower
```

Kafka 中每个 Partition 都有多个 Replica，其中一个是 Leader，其余是 Follower。Producer 和 Consumer 默认都和 Leader 交互，Follower 从 Leader 拉取数据。Kafka 通过 ISR（In-Sync Replicas）维护同步副本集合。

RocketMQ 的复制更偏向 **Broker 级主从复制**：

```text
BrokerA-Master
      ↓ 复制 CommitLog
BrokerA-Slave
```

RocketMQ 的传统部署中，一个 Broker Master 可以有一个或多个 Slave，Slave 复制 Master 的 CommitLog。

| 对比项 | Kafka | RocketMQ |
|--------|-------|----------|
| 副本粒度 | Partition 级别 | Broker 级别 / Broker 组级别 |
| 写入入口 | Partition Leader | Broker Master |
| 副本同步对象 | 某个 Partition 的日志 | Master Broker 的 CommitLog |
| 副本集合 | ISR / OSR | Master-Slave、Dledger、Controller 模式 |
| 自动故障切换 | 支持，Leader 可在 ISR 中切换 | 传统 Master-Slave 不自动切主；Dledger / Controller 支持自动切主 |
| 消费读副本 | 默认从 Leader 读 | 可从 Master 或 Slave 读，取决于配置和场景 |

RocketMQ 常见高可用模式：

| 模式 | 是否有副本 | 是否自动切主 | 说明 |
|------|------------|--------------|------|
| Master-Slave | 有 | 传统模式下不自动 | Slave 复制 Master，Master 挂了需要人工或额外机制切换 |
| Dledger | 有 | 支持 | 基于 Raft，多副本选主，可靠性更强 |
| Controller 模式 | 有 | 支持 | RocketMQ 5.x 引入 Controller 管理主从切换 |

面试回答：

```text
RocketMQ 不是没有副本机制，而是副本模型和 Kafka 不同。Kafka 是 Partition 级副本，通过 ISR 保证分区高可用；RocketMQ 传统上是 Broker Master-Slave 复制，Slave 复制 Master 的 CommitLog。传统 Master-Slave 主要解决数据备份和读扩展，但 Master 宕机后不一定自动切主；RocketMQ 后续通过 Dledger 和 Controller 模式支持自动故障转移。
```

### Q12: Kafka 的 Segment 是什么概念？

**结论：Segment 是 Kafka 中 Partition 日志的物理分段文件。**

Kafka 的 Topic 会被拆成多个 Partition，每个 Partition 本质上是一个不断追加的日志文件。但如果一个 Partition 永远只有一个大文件，会带来很多问题：

1. 文件太大，查找和维护成本高。
2. 删除历史消息不方便。
3. 索引文件过大。
4. 日志清理只能操作巨大文件。

所以 Kafka 把每个 Partition 的日志拆成多个 Segment：

```text
Topic: order-topic
Partition: 0

/order-topic-0/
  00000000000000000000.log
  00000000000000000000.index
  00000000000000000000.timeindex

  00000000000000100000.log
  00000000000000100000.index
  00000000000000100000.timeindex

  00000000000000200000.log
  00000000000000200000.index
  00000000000000200000.timeindex
```

每个 Segment 通常包含三类文件：

| 文件 | 作用 |
|------|------|
| `.log` | 保存消息数据 |
| `.index` | Offset 到物理位置的稀疏索引 |
| `.timeindex` | 时间戳到 Offset 的索引 |

Segment 文件名中的数字是这个 Segment 的 **base offset**。

例如：

```text
00000000000000100000.log
```

表示这个 Segment 中第一条消息的 offset 大约从 `100000` 开始。

Kafka 查找消息时大致流程：

```text
1. Consumer 要读取 offset=120000
2. Kafka 根据 Segment 的 base offset 找到对应 Segment
3. 通过 .index 找到 offset=120000 在 .log 文件中的物理位置
4. 从 .log 文件读取消息返回给 Consumer
```

Segment 的好处：

| 好处 | 说明 |
|------|------|
| 便于删除 | 过期数据可以直接删除整个 Segment 文件 |
| 便于索引 | 每个 Segment 有独立索引，索引更小 |
| 便于顺序写 | 当前活跃 Segment 追加写，旧 Segment 只读 |
| 便于恢复 | Broker 重启时按 Segment 恢复日志和索引 |

面试回答：

```text
Kafka 的 Partition 是逻辑上的顺序日志，但物理上会被切分成多个 Segment。每个 Segment 包含 .log、.index、.timeindex 文件。.log 存消息，.index 存 offset 到文件位置的索引，.timeindex 存时间戳索引。Segment 的主要作用是让 Kafka 更方便地查找、清理和维护日志文件。
```

### Q13: Kafka 和 RocketMQ 的 Offset 是怎么更新的？多个消费者组消费同一个 Partition/Queue 时 Offset 怎么保存？消息什么时候删除？

**结论：Offset 是按 Consumer Group 维度独立保存的；消息是否删除不取决于所有消费者组是否消费完成，而主要取决于消息保留策略。**

#### 1）Kafka 的 Offset 保存方式

Kafka 的消费位点是按下面这个维度保存：

```text
ConsumerGroup + Topic + Partition -> Offset
```

例如：

```text
groupA + order-topic + partition0 -> offset=100
组 groupB + order-topic + partition0 -> offset=20
组 groupC + order-topic + partition0 -> offset=500
```

同一个 Partition 可以被多个 Consumer Group 消费，每个 Consumer Group 都有自己的 Offset，互不影响。

Kafka 新版本把 Offset 保存在内部 Topic 中：

```text
__consumer_offsets
```

Offset 提交方式：

| 方式 | 说明 | 风险 |
|------|------|------|
| 自动提交 | Consumer 定期提交当前消费位点 | 可能消息还没处理完就提交，导致丢失 |
| 手动同步提交 | 处理完成后调用 `commitSync()` | 可靠但性能略低 |
| 手动异步提交 | 处理完成后调用 `commitAsync()` | 性能好，但失败处理复杂 |

典型流程：

```text
1. Consumer 拉取 offset=100 的消息
2. 业务处理成功
3. Consumer 提交 offset=101
4. 下次从 offset=101 开始消费
```

注意：Kafka 提交的是 **下一条要消费的 Offset**。

#### 2）RocketMQ 的 Offset 保存方式

RocketMQ 的消费位点也是按消费者组维度保存：

```text
ConsumerGroup + Topic + MessageQueue -> Offset
```

例如：

```text
groupA + OrderTopic + queue0 -> offset=100
groupB + OrderTopic + queue0 -> offset=20
groupC + OrderTopic + queue0 -> offset=500
```

在集群消费模式下，RocketMQ 的消费进度通常保存在 Broker 端；在广播消费模式下，消费进度通常保存在 Consumer 本地。

| 消费模式 | Offset 保存位置 | 说明 |
|----------|----------------|------|
| 集群消费 CLUSTERING | Broker 端 | 最常用，同组内多个消费者共享消费进度 |
| 广播消费 BROADCASTING | Consumer 本地 | 同组内每个消费者都消费一遍 |

RocketMQ Push Consumer 底层也是拉取消息，消费成功后会更新本地消费进度，并定期/异步持久化到 Broker。

#### 3）一个 Partition/Queue 可以被多个消费者组消费，Offset 怎么办？

以 Kafka 为例：

```text
order-topic partition0 中有消息 offset=0 到 offset=999

groupA 消费到 offset=900
groupB 消费到 offset=100
groupC 消费到 offset=500
```

这三个组互不影响：

```text
groupA 的下次消费位置是 900
groupB 的下次消费位置是 100
groupC 的下次消费位置是 500
```

RocketMQ 同理：

```text
OrderTopic queue0 中有 queueOffset=0 到 queueOffset=999

groupA 消费到 queueOffset=900
groupB 消费到 queueOffset=100
groupC 消费到 queueOffset=500
```

每个 Consumer Group 保存自己的消费进度。

#### 4）消息什么时候删除？是不是所有消费者都消费完才删除？

**不是。Kafka 和 RocketMQ 都不是等所有消费者组消费完成后才删除消息。**

Kafka 删除消息主要依据 Topic 的保留策略：

| 策略 | 说明 |
|------|------|
| 时间保留 | `retention.ms`，消息保留多久 |
| 大小保留 | `retention.bytes`，日志最多保留多大 |
| 日志压缩 | `cleanup.policy=compact`，按 Key 保留最新值 |

Kafka 删除通常以 Segment 为单位：

```text
某个 Segment 中的消息全部过期
        ↓
删除整个 Segment 文件
```

RocketMQ 删除消息主要依据 CommitLog 文件清理策略：

| 策略 | 说明 |
|------|------|
| 时间清理 | 默认保留一段时间后清理旧 CommitLog 文件 |
| 磁盘水位清理 | 磁盘空间紧张时触发清理 |
| 文件级删除 | 通常按 CommitLog 文件维度删除，不是单条删除 |

因此：

```text
即使某个消费者组还没消费到很老的消息，只要消息所在 Segment / CommitLog 文件过期并被清理，也可能再也消费不到。
```

面试回答：

```text
Kafka 的 Offset 按 ConsumerGroup + Topic + Partition 保存，存储在 __consumer_offsets 中。RocketMQ 的 Offset 按 ConsumerGroup + Topic + MessageQueue 保存，集群模式下保存在 Broker，广播模式下保存在本地。多个消费者组消费同一个 Partition 或 Queue 时，各自维护独立 Offset。消息删除不依赖所有消费者组消费完成，而是由 Kafka 的 retention/compact 策略或 RocketMQ 的 CommitLog 过期清理、磁盘水位决定。
```

### Q14: Exactly-Once 语义在 Kafka 和 RocketMQ 中具体怎么实现？

**结论：Kafka 对“Kafka 内部的消费-处理-生产”提供较完整的 Exactly-Once 支持；RocketMQ 更常见的是通过事务消息 + 消费端幂等实现业务上的最终一致，不能简单等同于 Kafka 的 Exactly-Once。**

#### 1）先理解三种语义

| 语义 | 含义 | 可能问题 |
|------|------|----------|
| At Most Once | 最多一次 | 可能丢消息 |
| At Least Once | 至少一次 | 可能重复消费 |
| Exactly Once | 精确一次 | 不丢、不重，或效果上只生效一次 |

严格来说，分布式系统中谈 Exactly-Once 通常要说清楚边界：

```text
是 MQ 内部 Exactly-Once？
还是 MQ + 数据库 + 下游接口一起 Exactly-Once？
```

MQ 能控制自己的日志和 Offset，但很难直接控制外部数据库、RPC 接口、第三方系统。

#### 2）Kafka 普通消费一般也是 At Least Once 吗？

**是的。Kafka 在普通消费模式下，最常见、最推荐的语义也是 At Least Once。**

原因是 Kafka 的消费流程通常是：

```text
1. Consumer 拉取消息
2. 业务处理消息
3. Consumer 提交 Offset
```

如果按照推荐方式：**业务处理成功后再提交 Offset**，那么就能保证消息不会轻易丢失，但可能重复消费。

例如：

```text
1. Consumer 拉取 order-topic partition0 offset=100
2. 业务处理成功，比如完成加积分
3. 准备提交 offset=101
4. 提交 Offset 前 Consumer 宕机
```

此时 Kafka 中保存的 Offset 仍然是 `100`，Consumer 重启后会重新从 `offset=100` 开始消费。

结果：

```text
同一条消息会被再次投递给 Consumer
```

所以 Kafka 普通消费默认更接近：

```text
At Least Once：消息至少被处理一次，可能重复处理。
```

如果反过来，先提交 Offset 再处理业务：

```text
1. 拉取 offset=100
2. 先提交 offset=101
3. 准备处理业务
4. Consumer 宕机
```

那么重启后 Kafka 会从 `offset=101` 开始消费，`offset=100` 不会再投递，导致消息丢失。这就是：

```text
At Most Once：最多处理一次，可能丢消息。
```

因此实际项目里 Kafka 消费端通常采用：

```text
业务处理成功后再提交 Offset
+ 消费端幂等
```

也就是：

```text
用 At Least Once 保证不丢消息，
用幂等设计解决重复消费。
```

常见幂等方式：

| 方式 | 说明 |
|------|------|
| 数据库唯一约束 | 以业务单号或消息 ID 建唯一索引 |
| Redis SETNX | 已处理消息写入 Redis，重复消息直接跳过 |
| 状态机 | 只允许状态从 A -> B，重复请求不改变结果 |
| 去重表 | 消费前插入消息 ID，插入成功才执行业务 |

面试回答可以这样说：

```text
Kafka 普通消费一般是 At Least Once。因为推荐做法是先处理业务，再提交 Offset。如果业务处理成功但提交 Offset 前宕机，重启后会从旧 Offset 重新消费，导致重复投递。所以 Kafka 消费端通常必须做幂等。Kafka 的 Exactly-Once 不是普通消费天然具备的，而是需要幂等 Producer + 事务 + read_committed，并且主要限定在 Kafka 到 Kafka 的处理链路里。
```

#### 3）Kafka 如何实现 Exactly-Once？

Kafka 的 Exactly-Once 主要依赖两个能力：

```text
幂等 Producer + 事务 Transaction
```

##### 幂等 Producer：解决生产端重复写入

开启：

```properties
enable.idempotence=true
```

Kafka 会给 Producer 分配：

```text
ProducerId（PID）
Producer Epoch
每个 Partition 上的 Sequence Number
```

Producer 向同一个 Partition 发送消息时，每条消息都有递增序号：

```text
PID=1001, Partition=0, Seq=0
PID=1001, Partition=0, Seq=1
PID=1001, Partition=0, Seq=2
```

如果网络超时导致 Producer 重试，Broker 可以根据 `PID + Partition + Seq` 判断这是不是重复消息：

```text
第一次写入 Seq=1 成功，但响应丢失
Producer 重试 Seq=1
Broker 发现 Seq=1 已写入
        ↓
不重复追加
```

幂等 Producer 能保证：

```text
单 Producer 会话内、单 Partition 上的重复发送不会导致重复写入。
```

##### Kafka 事务：解决跨 Partition、消费 Offset 与生产结果的原子提交

开启事务需要配置：

```properties
transactional.id=order-tx-producer-1
```

典型消费-处理-生产流程：

```java
producer.initTransactions();

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

    producer.beginTransaction();
    try {
        // 1. 消费上游消息并处理
        for (ConsumerRecord<String, String> record : records) {
            ProducerRecord<String, String> output = process(record);
            producer.send(output);
        }

        // 2. 把消费 Offset 也提交到本事务中
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

        // 3. 原子提交：输出消息 + Offset 一起成功
        producer.commitTransaction();
    } catch (Exception e) {
        producer.abortTransaction();
    }
}
```

Kafka 事务保证：

```text
下游输出消息写入成功
        和
上游消费 Offset 提交成功

这两件事要么都成功，要么都失败。
```

#### 一个具体例子：订单消息转积分消息

假设有两个 Topic：

```text
上游 Topic：order-paid-topic
  保存订单支付成功消息

下游 Topic：point-add-topic
  保存给用户加积分的消息
```

有一个消费者程序 `point-service`，它做的事情是：

```text
1. 从 order-paid-topic 消费一条订单支付成功消息
2. 根据订单金额计算积分
3. 向 point-add-topic 发送一条“加积分消息”
4. 提交 order-paid-topic 的消费 Offset
```

例如：

```text
输入消息：
order-paid-topic partition0 offset=100
内容：用户 U1 支付订单 O1001，金额 100 元

处理结果：
向 point-add-topic 发送消息：给用户 U1 增加 100 积分

然后提交 Offset：
order-paid-topic partition0 offset=101
```

这里有两个关键动作：

```text
动作 A：把“加积分消息”写入 point-add-topic
动作 B：提交 order-paid-topic 的 Offset=101
```

这就是文档里说的：

```text
下游输出消息写入成功 = 动作 A 成功
上游消费 Offset 提交成功 = 动作 B 成功
```

为什么这两个动作必须放在一个事务里？因为只成功一个就会出问题。

##### 情况 1：先提交 Offset，再发送下游消息，可能丢业务结果

```text
1. 消费 order-paid-topic offset=100
2. 先提交 Offset=101
3. 准备发送 point-add-topic 加积分消息
4. 服务宕机
```

结果：

```text
order-paid-topic 的 Offset 已经是 101
Kafka 认为 offset=100 已经处理完
重启后不会再消费 offset=100
但 point-add-topic 的加积分消息没有发出去
```

问题：

```text
订单已支付，但积分永远没加。
这就是“丢业务结果”。
```

##### 情况 2：先发送下游消息，再提交 Offset，可能重复业务结果

```text
1. 消费 order-paid-topic offset=100
2. 发送 point-add-topic 加积分消息成功
3. 准备提交 Offset=101
4. 服务宕机
```

结果：

```text
point-add-topic 已经有一条加积分消息
但 order-paid-topic 的 Offset 还停留在 100
重启后会重新消费 offset=100
然后再次发送一条加积分消息
```

问题：

```text
同一笔订单可能产生两条加积分消息。
如果积分服务不幂等，用户会被重复加积分。
```

##### Kafka 事务怎么解决？

Kafka 把这两个动作放到同一个事务里：

```text
事务开始
    ↓
发送 point-add-topic 加积分消息
    ↓
把 order-paid-topic 的 Offset=101 加入事务
    ↓
提交事务
```

对应代码就是：

```java
producer.beginTransaction();

// 动作 A：发送下游输出消息
producer.send(new ProducerRecord<>("point-add-topic", userId, pointMessage));

// 动作 B：把上游 Offset 放入同一个事务
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

// 动作 A + 动作 B 原子提交
producer.commitTransaction();
```

如果事务提交成功：

```text
point-add-topic 的加积分消息对下游可见
order-paid-topic 的 Offset=101 也提交成功
```

如果事务提交失败或服务宕机：

```text
point-add-topic 的加积分消息不可见，或者被标记为 abort
order-paid-topic 的 Offset 不会提交
重启后重新消费 offset=100
```

这样就不会出现：

```text
Offset 提交了，但下游消息没写入  -> 丢业务结果
下游消息写入了，但 Offset 没提交  -> 重复业务结果
```

这就是 Kafka 事务的核心价值：

```text
保证“消费一条上游消息”和“生产对应下游消息”这两个动作在 Kafka 内部要么同时生效，要么同时不生效。
```

注意：这里的 Exactly-Once 主要适用于 **Kafka Topic -> Kafka Topic** 的链路。
如果中间还写数据库、调用 HTTP 接口，Kafka 事务不能直接保证外部系统也 Exactly-Once，仍然需要业务幂等或本地事务。

如果事务提交前宕机：

```text
输出消息不可见 / 被 abort
Offset 不提交
重启后重新消费
```

如果事务提交成功：

```text
输出消息可见
Offset 已提交
不会重复处理这一批输入
```

Kafka 还通过事务协调器维护事务状态：

| 组件/机制 | 作用 |
|-----------|------|
| Transaction Coordinator | 管理事务状态 |
| `__transaction_state` | 保存事务元数据 |
| Control Marker | 在日志中标记事务 Commit / Abort |
| `isolation.level=read_committed` | Consumer 只读取已提交事务消息 |

Kafka Exactly-Once 的边界：

| 场景 | 是否能靠 Kafka 保证 Exactly-Once |
|------|----------------------------------|
| Kafka Topic -> 处理 -> Kafka Topic | 可以，使用幂等 Producer + 事务 |
| Kafka Topic -> 数据库 | Kafka 不能单独保证，需要数据库事务/幂等 |
| Kafka Topic -> 外部 HTTP 接口 | Kafka 不能单独保证，需要业务幂等 |
| 数据库 -> Kafka | 需要 Outbox、CDC 等模式 |

#### 4）RocketMQ 如何实现类似 Exactly-Once 的效果？

RocketMQ 更常见的能力是：

```text
事务消息保证“本地事务和发消息”的一致性
消费端幂等保证“重复消费时业务只生效一次”
```

##### RocketMQ 事务消息：解决本地事务和消息发送一致性

流程：

```text
1. Producer 发送 Half Message（半消息）到 Broker
2. Broker 保存半消息，但不投递给 Consumer
3. Producer 执行本地事务，例如写数据库订单表
4. 本地事务成功 -> Commit 半消息
5. 本地事务失败 -> Rollback 半消息
6. 如果 Broker 没收到 Commit/Rollback -> 回查 Producer 本地事务状态
```

事务消息解决的是：

```text
本地数据库事务成功后，消息最终也能发出去；
本地数据库事务失败后，消息不会被消费者看到。
```

它不直接解决消费者重复消费问题。

##### RocketMQ 消费端为什么仍然需要幂等？

RocketMQ 消费通常是 At Least Once：

```text
Consumer 处理成功，但 ACK 返回 Broker 失败
        ↓
Broker 认为没成功
        ↓
稍后重新投递
        ↓
Consumer 可能重复消费
```

所以业务侧需要幂等：

```text
唯一消息 ID / 业务单号
        ↓
数据库唯一约束 / Redis SETNX / 状态机
        ↓
重复消息不重复执行业务效果
```

例如：

```sql
CREATE UNIQUE INDEX uniq_msg_id ON consume_log(message_id);
```

消费时：

```text
1. 收到消息 messageId=abc
2. 插入消费记录 abc
3. 插入成功 -> 执行业务
4. 插入失败 -> 说明处理过，直接 ACK
```

##### RocketMQ 的 Exactly-Once 面试回答

```text
RocketMQ 通常不提供像 Kafka 那样完整的“消费上游消息、处理、生产下游消息、提交 Offset”一体化事务语义。RocketMQ 的事务消息主要保证本地事务和消息发送的一致性；消费侧仍然可能重复投递，所以需要业务幂等。实际项目中会用事务消息 + 消费幂等 + 本地事务表 / Outbox 等方式实现业务效果上的 Exactly-Once。
```

#### 5）Kafka 和 RocketMQ 对比总结

| 对比项 | Kafka | RocketMQ |
|--------|-------|----------|
| 生产端去重 | 幂等 Producer，PID + Seq | 主要依赖重试和业务幂等，部分场景支持去重但不等同 Kafka EOS |
| 事务能力 | 支持跨 Partition 事务，Offset 和输出消息原子提交 | 支持事务消息，本地事务 + 半消息 + 回查 |
| 消费语义 | 配合事务可实现 Kafka 内部 EOS | 通常 At Least Once，需要消费幂等 |
| 适用边界 | Kafka -> Kafka 链路效果最好 | 本地事务 -> MQ 一致性效果好 |
| 外部系统 Exactly-Once | 仍需业务幂等/事务 | 仍需业务幂等/事务 |

### Q15: 零拷贝（Zero Copy）是用来干嘛的？什么场景需要？

**结论：零拷贝是为了减少数据在内核态和用户态之间的拷贝次数，降低 CPU 消耗和上下文切换，提高大文件/大批量消息传输性能。**

传统读取文件并发送到网络的大致过程：

```text
磁盘
  ↓ DMA
内核 PageCache
  ↓ CPU 拷贝
用户空间应用缓冲区
  ↓ CPU 拷贝
内核 Socket 缓冲区
  ↓ DMA
网卡
```

这里有多次数据拷贝，尤其是：

```text
内核空间 -> 用户空间
用户空间 -> 内核 Socket 缓冲区
```

零拷贝优化后，例如使用 `sendfile()` / `transferTo()`：

```text
磁盘
  ↓ DMA
内核 PageCache
  ↓ 直接关联到 Socket
网卡
```

应用程序不需要把文件内容完整读到 JVM 堆内存中再写出去。

适用场景：

| 场景 | 为什么适合零拷贝 |
|------|------------------|
| Kafka Consumer 拉取消息 | Broker 要把日志文件大量发送给消费者 |
| Broker 主从复制 | 大量日志数据要从 Leader/Master 传给 Follower/Slave |
| 文件服务器下载 | 文件从磁盘直接发到网络 |
| 视频/图片分发 | 大文件高吞吐传输 |
| 日志系统 | 大量顺序日志读写和网络传输 |

Kafka 为什么适合零拷贝？

```text
Kafka 的消息本来就以日志文件形式存储在磁盘上，Consumer 拉取时，Broker 可以把日志文件中的数据直接通过 sendfile 从 PageCache 发送到 Socket，减少 JVM 堆内存拷贝和 GC 压力。
```

面试回答：

```text
零拷贝主要用于 Broker 把磁盘日志数据发送给消费者或副本节点的场景。它减少了内核态和用户态之间的数据拷贝，避免消息数据进入 JVM 堆，降低 CPU 和 GC 压力，提升高吞吐文件传输性能。Kafka 的 Consumer 拉取消息、Follower 复制日志都非常适合使用零拷贝。
```

### Q16: PageCache 是什么？Kafka 在什么场景用到？RocketMQ 有吗？

**结论：PageCache 是操作系统对磁盘文件的内存缓存。Kafka 和 RocketMQ 都大量依赖 PageCache 来提升读写性能。**

#### 1）PageCache 是什么？

PageCache 是 Linux 内核用内存缓存磁盘文件内容的机制。

当应用写文件时：

```text
应用写入文件
    ↓
先写入 PageCache
    ↓
由操作系统异步刷盘到磁盘
```

当应用读文件时：

```text
先查 PageCache
    ↓ 命中
直接从内存返回
    ↓ 未命中
从磁盘读取，再放入 PageCache
```

所以 PageCache 的作用是：

| 作用 | 说明 |
|------|------|
| 加速写入 | 写入先进入内存缓存，再异步刷盘 |
| 加速读取 | 热数据直接从内存读取 |
| 合并 IO | 操作系统可以合并小 IO，提升磁盘效率 |
| 降低 JVM 压力 | 消息数据不必大量进入 JVM 堆 |

#### 2）Kafka 在哪些场景用到 PageCache？

Kafka 非常依赖 PageCache。

**生产者写入消息：**

```text
Producer -> Broker -> 写入 Partition Log
                         ↓
                     先进入 PageCache
                         ↓
                     OS 异步刷盘
```

**消费者读取消息：**

```text
Consumer 拉取最近消息
        ↓
这些消息刚被写入，通常还在 PageCache
        ↓
Broker 直接从 PageCache 读取并通过零拷贝发送
```

Kafka 适合 PageCache 的原因：

1. 顺序追加写日志。
2. 消费者通常读取最近写入的数据。
3. 消息文件是磁盘文件，天然被 OS 缓存。
4. Kafka 不把大量消息缓存在 JVM 堆，减少 GC。

这也是为什么 Kafka JVM 堆通常不建议设置过大：

```text
机器内存应该更多留给 OS PageCache，而不是全部给 JVM Heap。
```

#### 3）RocketMQ 有 PageCache 吗？

**有。RocketMQ 同样大量依赖 PageCache。**

RocketMQ 的 CommitLog、ConsumeQueue、IndexFile 都是磁盘文件，通常通过 MappedFile / mmap 等方式映射和访问。

RocketMQ 写入消息大致是：

```text
Producer -> Broker
        ↓
写入 CommitLog 对应的内存映射区域
        ↓
数据进入 OS PageCache
        ↓
根据 SYNC_FLUSH / ASYNC_FLUSH 策略刷盘
```

RocketMQ 读取消息大致是：

```text
Consumer 拉取消息
        ↓
先读 ConsumeQueue 索引
        ↓
根据 commitLogOffset 读 CommitLog
        ↓
如果数据在 PageCache 中，直接内存命中
```

RocketMQ 中 PageCache 的典型场景：

| 场景 | PageCache 的作用 |
|------|------------------|
| CommitLog 顺序写 | 写入先进入 PageCache，提高写入吞吐 |
| ConsumeQueue 读取 | 索引文件小且热，容易命中 PageCache |
| Consumer 读取近期消息 | 最近写入的数据通常仍在 PageCache |
| 主从复制 | Slave 拉取 Master CommitLog 时可命中缓存 |
| 定时消息扫描 | TimerLog / CommitLog 读取可能利用 PageCache |

#### 4）Kafka 和 RocketMQ 使用 PageCache 的对比

| 对比项 | Kafka | RocketMQ |
|--------|-------|----------|
| 主要文件 | Partition Log、Index、TimeIndex | CommitLog、ConsumeQueue、IndexFile、TimerLog |
| 写入方式 | Partition 日志追加写 | Broker 级 CommitLog 追加写 |
| 读取方式 | 直接读 Partition Log | 先读 ConsumeQueue，再读 CommitLog |
| 是否依赖 PageCache | 非常依赖 | 非常依赖 |
| JVM 堆建议 | 不宜过大，留内存给 PageCache | 不宜过大，留内存给 PageCache |

面试回答：

```text
PageCache 是操作系统对磁盘文件的内存缓存。Kafka 写 Partition Log、读最近消息、零拷贝发送数据时都依赖 PageCache。RocketMQ 也有 PageCache，它的 CommitLog、ConsumeQueue、IndexFile 都是磁盘文件，写入 CommitLog 和读取消息时都会利用 OS PageCache。两者都不建议把 JVM 堆设置得过大，因为要给操作系统留下足够内存做 PageCache。
```

### Q17: Kafka 的 Leader 和 Follower 如何同步？

**结论：Kafka 中每个 Partition 有一个 Leader 和多个 Follower。Producer 写 Leader，Follower 主动从 Leader 拉取数据进行同步，Consumer 默认也从 Leader 读取。**

#### 1）基本同步模型

Kafka 的副本同步不是 Leader 主动推给 Follower，而是 **Follower 主动拉取 Leader 的日志**。

```text
Producer
   ↓ 写入
Partition Leader
   ↑
   | FollowerFetchRequest 拉取
   |
Follower Replica
```

流程如下：

```text
1. Producer 向 Partition Leader 发送消息
2. Leader 将消息追加到自己的本地日志
3. Follower 周期性向 Leader 发送 Fetch 请求
4. Leader 返回 Follower 缺失的日志数据
5. Follower 将数据追加到自己的本地日志
6. Follower 拉取进度追上 Leader 后，继续保持同步
```

#### 2）为什么是 Follower 拉取，而不是 Leader 推送？

| 方式 | 优点 |
|------|------|
| Follower 拉取 | Follower 可以控制自己的拉取节奏，避免 Leader 被慢副本拖垮 |
| Leader 推送 | Leader 需要维护每个 Follower 的推送状态，复杂度更高 |

Kafka 使用拉模式的好处：

```text
Follower 根据自己的能力向 Leader 拉数据，Leader 只需要响应 Fetch 请求。
如果某个 Follower 慢，不会阻塞 Leader 给其他 Follower 或 Producer 服务。
```

#### 3）什么是 ISR？

ISR 全称是 **In-Sync Replicas**，表示和 Leader 保持同步的副本集合。

例如：

```text
Partition-0:
  Leader: Broker1
  Follower: Broker2
  Follower: Broker3

ISR = [Broker1, Broker2, Broker3]
```

如果 Broker3 长时间拉不到最新数据，落后太多：

```text
ISR = [Broker1, Broker2]
Broker3 被移出 ISR
```

Kafka 判断 Follower 是否同步，主要看它是否在规定时间内持续向 Leader 拉取并追上进度。

常见相关配置：

| 配置 | 说明 |
|------|------|
| `replica.lag.time.max.ms` | Follower 超过这个时间没有追上 Leader，会被移出 ISR |
| `min.insync.replicas` | ISR 中至少需要多少副本，配合 `acks=all` 使用 |

#### 4）Producer 的 acks 和副本同步有什么关系？

Producer 的 `acks` 决定消息写入到什么程度才算成功。

| acks | 成功条件 | 风险 |
|------|----------|------|
| `acks=0` | Producer 不等 Broker 响应 | 可能丢消息 |
| `acks=1` | Leader 写入成功就返回 | Leader 宕机且 Follower 未同步时可能丢消息 |
| `acks=all` / `-1` | Leader 等 ISR 中副本同步后再返回 | 可靠性最高，吞吐略低 |

例如配置：

```properties
acks=all
min.insync.replicas=2
replication.factor=3
```

表示：

```text
一个 Partition 总共有 3 个副本；
Producer 发送消息时，Leader 写入后，还需要至少 ISR 中满足要求的副本确认；
只要 ISR 副本数不足 min.insync.replicas，写入就会失败，避免只有一个副本写成功就返回。
```

#### 5）HW 和 LEO 是什么？

理解 Leader-Follower 同步必须知道两个概念：

| 概念 | 全称 | 含义 |
|------|------|------|
| LEO | Log End Offset | 某个副本本地日志的末尾 Offset，即下一条要写入的位置 |
| HW | High Watermark | 高水位，表示消费者能看到的最大已提交 Offset |

举例：

```text
Leader LEO = 10
Follower1 LEO = 10
Follower2 LEO = 8
```

如果 ISR 是：

```text
Leader, Follower1, Follower2
```

那么 HW 通常取 ISR 中最小的 LEO：

```text
HW = 8
```

这表示：

```text
offset < 8 的消息对 Consumer 可见；
offset >= 8 的消息虽然 Leader 上可能有，但还没有被所有 ISR 副本同步完成，所以暂时不对消费者暴露。
```

这样做的目的是：

```text
只让已经被 ISR 副本确认的消息对 Consumer 可见，降低 Leader 宕机时消息丢失和不一致风险。
```

#### 6）Leader 宕机后如何处理？

如果 Leader 所在 Broker 宕机，Kafka Controller 会从 ISR 中选择一个 Follower 成为新的 Leader。

```text
原来：
Broker1 = Leader
Broker2 = Follower，ISR 中
Broker3 = Follower，ISR 中

Broker1 宕机后：
Broker2 被选为新 Leader
Broker3 继续作为 Follower 同步 Broker2
```

如果允许非 ISR 副本成为 Leader：

```properties
unclean.leader.election.enable=true
```

那么即使某个副本数据落后，也可能被选为 Leader，这会提高可用性，但可能丢数据。

生产中强可靠场景通常设置：

```properties
unclean.leader.election.enable=false
```

#### 7）一句话面试总结

```text
Kafka 的 Leader-Follower 同步是 Follower 主动向 Leader 拉取日志。Producer 只写 Leader，Leader 追加日志后，Follower 通过 Fetch 请求同步数据。Kafka 用 ISR 维护和 Leader 保持同步的副本集合，用 HW 控制哪些消息对消费者可见。acks=all 配合 min.insync.replicas 可以保证消息至少被多个 ISR 副本确认后才算写入成功。Leader 宕机后，Controller 会优先从 ISR 中选新 Leader。
```

---

## 六、Kafka 版本演进

| 版本 | 重要特性 |
|------|---------|
| 0.8 | 副本机制，真正的高可用 |
| 0.9 | 新版 Consumer，安全认证 |
| 0.10 | 流处理 API (Kafka Streams) |
| 0.11 | 事务和幂等性 Producer |
| 1.0 | KSQL 连接器优化 |
| 2.0 | Connect 增强，弃用旧 Consumer API |
| 2.4 | Sticky Assignor， cooperative rebalance |
| 2.8 | KRaft 模式（去 ZooKeeper）开始引入 |
| 3.0 | KRaft 正式生产可用 |
| 3.3+ | KRaft 成为默认模式 |

---

## 七、Kafka 最佳实践

### 7.1 Topic 配置建议
```properties
# 副本数至少 3
replication.factor=3
# ISR 最少副本数
min.insync.replicas=2
# 不允许非 ISR 成为 Leader
unclean.leader.election.enable=false
```

### 7.2 生产者配置建议
```properties
acks=all
enable.idempotence=true
retries=2147483647
max.in.flight.requests.per.connection=5
compression.type=lz4
batch.size=16384
linger.ms=10
```

### 7.3 消费者配置建议
```properties
enable.auto.commit=false
max.poll.records=500
max.poll.interval.ms=300000
session.timeout.ms=30000
heartbeat.interval.ms=10000
```

### 7.4 JVM 配置建议
```bash
# 堆内存不宜过大（依赖 PageCache）
-Xms6g -Xmx6g
# 使用 G1 垃圾收集器
-XX:+UseG1GC
-XX:MaxGCPauseMillis=20
```
