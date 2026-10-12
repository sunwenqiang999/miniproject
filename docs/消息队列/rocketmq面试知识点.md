# RocketMQ 面试知识点总结

## 一、RocketMQ 基础概念

### 1.1 什么是 RocketMQ？
RocketMQ 是阿里巴巴开源的分布式消息中间件，后捐赠给 Apache 基金会成为顶级项目。设计参考了 Kafka，但在可靠性、事务消息、消息过滤等方面做了增强。

**核心特性：**
- 高吞吐、低延迟
- 事务消息支持
- 消息顺序保证
- 消息过滤（Tag/SQL92）
- 延迟消息
- 死信队列
- 消息回溯

### 1.2 核心组件

| 组件 | 说明 |
|------|------|
| **NameServer** | 注册中心，轻量级，存储路由信息 |
| **Broker** | 消息存储和转发服务器 |
| **Producer** | 消息生产者 |
| **Consumer** | 消息消费者 |
| **Topic** | 消息主题，一级分类 |
| **Tag** | 消息标签，二级分类 |
| **Message Queue** | 队列，类似 Kafka 的 Partition |
| **Consumer Group** | 消费者组 |
| **Producer Group** | 生产者组（用于事务回查） |

### 1.3 架构图

```
    +-----------------+       +-----------------+
    |  NameServer-1   |       |  NameServer-2   |    (无状态，互相独立)
    +-----------------+       +-----------------+
            ↑                       ↑
            | 心跳上报/路由查询        |
            |                        |
    +-------+---+    +-------+------+
    |  Broker-A |    |  Broker-B     |
    |  (Master) |    |  (Master)     |
    +-------+---+    +-------+------+
            |                |
    +-------+---+    +-------+------+
    |  Broker-A |    |  Broker-B     |
    |  (Slave)  |    |  (Slave)      |
    +-----------+    +---------------+

    Producer --> 从 NameServer 获取路由 --> 发送到 Broker Master
    Consumer --> 从 NameServer 获取路由 --> 从 Broker Master/Slave 拉取
```

### 1.4 NameServer vs ZooKeeper（Kafka 用 ZK）

| 对比项 | NameServer | ZooKeeper |
|--------|------------|-----------|
| 复杂度 | 轻量 | 重量 |
| 一致性 | AP（最终一致） | CP（强一致） |
| 部署 | 独立无状态 | 需要集群选举 |
| 功能 | 仅路由信息 | 通用协调服务 |

---

## 二、RocketMQ 核心机制

### 2.1 消息存储机制

**CommitLog（统一存储）：**
- 所有 Topic 的消息都写入同一个 CommitLog 文件
- 文件大小固定 1GB，写满后创建新文件
- 顺序写，性能高

**ConsumeQueue（消费队列）：**
- 每个 Topic 的每个 Queue 对应一个 ConsumeQueue
- 存储消息在 CommitLog 中的偏移量
- 类似索引文件，消费者通过 ConsumeQueue 查找消息

**IndexFile（索引文件）：**
- 支持按 Key 和时间范围查询消息

```
/store/
  ├── commitlog/
  │   ├── 00000000000000000000000000000000000000000000000000000000000000
  │   └── 00000000000001073741824  (1GB后的下一个文件)
  ├── consumequeue/
  │   ├── TopicA/queue0/
  │   │   ├── 00000000000000000000
  │   │   └── 00000000000006000000
  │   └── TopicA/queue1/
  └── index/
      └── 20240101120000000
```

**写入流程：**
```
Producer 发送消息
    ↓
Broker 写入 CommitLog（顺序写）
    ↓
异步构建 ConsumeQueue（ReputMessageService）
    ↓
Consumer 通过 ConsumeQueue 查找消息偏移量 → 到 CommitLog 读取消息
```

### 2.2 消息发送模式

**同步发送：**
```java
SendResult result = producer.send(msg);
System.out.println(result.getSendStatus());
```

**异步发送：**
```java
producer.send(msg, new SendCallback() {
    @Override
    public void onSuccess(SendResult result) { }
    @Override
    public void onException(Throwable e) { }
});
```

**单向发送（One-way）：**
```java
producer.sendOneway(msg);  // 不等待应答，不回调
```

| 模式 | 可靠性 | 吞吐量 | 延迟 |
|------|--------|--------|------|
| 同步 | 最高 | 最低 | 高 |
| 异步 | 中等 | 中等 | 低 |
| 单向 | 最低 | 最高 | 最低 |

### 2.3 消费模式

**集群消费（CLUSTERING）：**
- 同一消费者组中，每条消息只会被一个消费者消费
- 消费进度存储在 Broker 端
- 最常用的消费模式

**广播消费（BROADCASTING）：**
- 每条消息会被消费者组中所有消费者消费
- 消费进度存储在消费者本地

```java
consumer.setMessageModel(MessageModel.CLUSTERING);  // 集群模式
consumer.setMessageModel(MessageModel.BROADCASTING); // 广播模式
```

### 2.4 消费方式

**拉取消费（Pull）：**
- 消费者主动从 Broker 拉取消息
- 需要自己维护 Offset
- 灵活度高，但使用复杂

**推送消费（Push）：**
- 本质也是拉取，但封装了长轮询机制
- 使用简单，用户体验好
- 默认消费方式

```java
// Push 消费（本质是封装的 Pull）
consumer.registerMessageListener(new MessageListenerConcurrently() {
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        // 处理消息
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
});
```

---

## 三、RocketMQ 高级特性

### 3.1 顺序消息

**全局顺序：**
- 整个 Topic 下所有消息严格有序
- 只能使用一个 MessageQueue
- 牺牲并发，极少使用

**分区顺序（Partition Order）：**
- 同一业务 Key 的消息发送到同一 Queue
- 消费时同一 Queue 由一个线程消费
- 实际场景推荐

```java
// 生产者：按 Key 选择 Queue
producer.send(msg, new MessageQueueSelector() {
    @Override
    public MessageQueue select(List<MessageExt> mqs, Message msg, Object arg) {
        Integer orderId = (Integer) arg;
        int index = orderId % mqs.size();
        return mqs.get(index);
    }
}, orderId);

// 消费者：使用顺序消费监听器
consumer.registerMessageListener(new MessageListenerOrderly() {
    @Override
    public ConsumeOrderlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeOrderlyContext context) {
        // 顺序处理
        return ConsumeOrderlyStatus.SUCCESS;
    }
});
```

### 3.2 延迟消息

**RocketMQ 延迟级别（开源版固定级别）：**
```
Level: 1   2   3   4    5    6    7    8    9    10   11   12   13   14   15   16   17   18
Delay: 1s  5s  10s 30s  1m   2m   3m   4m   5m   6m   7m   8m   9m   10m  20m  30m  1h   2h
```

```java
Message msg = new Message("Topic", "Tag", "body".getBytes());
msg.setDelayTimeLevel(3);  // 延迟 10 秒
producer.send(msg);
```

**实现原理：**
```
1. Producer 发送延迟消息
2. Broker 收到后，将 Topic 替换为 SCHEDULE_TOPIC_XXXX
3. 后台定时任务扫描到达延迟时间的消息
4. 恢复原始 Topic，消费者可以消费
```

**RocketMQ 5.x 支持任意延迟时间：**
```java
msg.setDeliverTimeMs(System.currentTimeMillis() + 60000); // 60秒后投递
```

### 3.3 事务消息

**事务消息流程：**
```
1. Producer 发送"半消息"（Half Message）到 Broker
2. Broker 存储半消息，但不投递给消费者
3. 执行本地事务
4. 根据本地事务结果：
   - Commit：将半消息变为可消费消息
   - Rollback：删除半消息
   - Unknown：Broker 回查 Producer 的事务状态
5. 如果回查：Broker 定期检查 Producer 获取事务状态
```

**代码示例：**
```java
TransactionMQProducer producer = new TransactionMQProducer("group");
producer.setTransactionListener(new TransactionListener() {
    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // 执行本地事务
        try {
            // 业务操作，如数据库写入
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (Exception e) {
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        // 事务回查：检查本地事务是否成功
        // 返回 COMMIT / ROLLBACK / UNKNOWN
        return LocalTransactionState.COMMIT_MESSAGE;
    }
});

// 发送事务消息
TransactionSendResult result = producer.sendMessageInTransaction(msg, null);
```

### 3.4 消息过滤

**Tag 过滤：**
```java
// 生产者设置 Tag
Message msg = new Message("Topic", "TagA", "body".getBytes());

// 消费者订阅指定 Tag
consumer.subscribe("Topic", "TagA || TagB");
```

**SQL92 过滤：**
```java
// 生产者设置属性
msg.putUserProperty("age", "25");
msg.putUserProperty("sex", "male");

// 消费者使用 SQL92 语法过滤
consumer.subscribe("Topic",
    MessageSelector.bySql("age > 18 AND sex = 'male'"));
```

### 3.5 死信队列（DLQ）

当消息消费失败达到最大重试次数（默认 16 次），消息会被放入死信队列：

```
%DLQ%ConsumerGroup  // 死信队列 Topic 命名规则
```

**死信队列特点：**
- 每个消费者组对应一个死信队列
- 死信消息不再被正常消费
- 可通过控制台或 API 查看
- 需要人工介入处理

### 3.6 消息重试

**重试机制：**
- 消费失败后自动重试
- 默认最大重试 16 次
- 重试间隔递增：10s, 30s, 1m, 2m, 3m, 4m, 5m, 6m, 7m, 8m, 9m, 10m, 20m, 30m, 1h, 2h

```java
// 设置最大重试次数
consumer.setMaxReconsumeTimes(16);

// 返回 RECONSUME_LATER 触发重试
return ConsumeConcurrentlyStatus.RECONSUME_LATER;
```

---

## 四、RocketMQ 高可用

### 4.1 集群部署模式

**Master-Slave 模式：**
```
Broker-A (Master)  ←→  Broker-A' (Slave)
Broker-B (Master)  ←→  Broker-B' (Slave)
```
- Slave 从 Master 同步数据
- Slave 可提供读服务
- Master 宕机后不能自动切换（Slave 只读不写）

**Dledger 模式（基于 Raft）：**
```
Dledger Group:
  - Leader: 处理读写
  - Follower: 同步数据
  - 自动 Leader 选举
```
- 基于 Raft 协议
- 支持 Master 自动故障转移
- 数据一致性更强

**Controller 模式（5.x 新特性）：**
- 引入 Controller 组件
- 兼容 Master-Slave 模式
- 支持 Master 自动切换

### 4.2 高可用配置

RocketMQ 的高可用主要由两个维度决定：**刷盘策略**和**主从复制策略**。

| 配置维度 | 配置项 | 含义 | 性能 | 可靠性 | 适用场景 |
|----------|--------|------|------|--------|----------|
| 刷盘策略 | `SYNC_FLUSH` | 同步刷盘，消息写入内存后必须刷到磁盘才返回成功 | 低 | 高 | 金融、交易、订单等强可靠场景 |
| 刷盘策略 | `ASYNC_FLUSH` | 异步刷盘，消息写入内存后即可返回成功，由后台线程异步刷盘 | 高 | 中 | 日志、埋点、通知等吞吐优先场景 |
| 复制策略 | `SYNC_MASTER` | 同步复制，Master 等待 Slave 同步完成后再返回成功 | 低 | 高 | 核心业务、不能接受消息丢失的场景 |
| 复制策略 | `ASYNC_MASTER` | 异步复制，Master 写入成功即可返回，Slave 后台异步同步 | 高 | 中 | 高吞吐、允许极小概率丢失的场景 |

**强可靠推荐配置：**

```properties
# Broker 配置
brokerRoleName=SYNC_MASTER
flushDiskType=SYNC_FLUSH
```

**高吞吐推荐配置：**

```properties
# Broker 配置
brokerRoleName=ASYNC_MASTER
flushDiskType=ASYNC_FLUSH
```

**面试回答总结：**
- 要可靠性：选择 `SYNC_FLUSH + SYNC_MASTER`，性能较低，但消息丢失风险最低。
- 要吞吐量：选择 `ASYNC_FLUSH + ASYNC_MASTER`，性能更高，但 Broker 宕机时可能丢失少量消息。
- 实际生产中，需要根据业务重要性选择：订单、支付等核心链路优先可靠性；日志、监控等场景优先吞吐量。

---

## 五、RocketMQ 常见面试题

### Q1: RocketMQ 和 Kafka 的区别？
| 特性 | Kafka | RocketMQ |
|------|-------|----------|
| 存储模型 | 每个 Partition 独立文件 | CommitLog 统一存储 |
| 顺序消息 | 支持但较弱 | 原生支持，成熟 |
| 事务消息 | 事务 API | 本地事务+回查机制 |
| 延迟消息 | 不原生支持 | 原生支持 |
| 消息过滤 | 不支持 | Tag + SQL92 |
| 消息回溯 | 不支持 | 支持按时间回溯 |
| 可用性 | ISR 选举 | Dledger/Controller |
| 运维 | 复杂（ZK/KRaft） | 简单（NameServer） |

### Q2: RocketMQ 为什么用 CommitLog 统一存储？
**Kafka 模式的问题：**
- 每个 Partition 一个文件
- Partition 数量多时，磁盘随机写严重
- 如果 Partition 减少（如 100 → 10），吞吐量反而提高（这是不合理的）

**RocketMQ 的优势：**
- 所有消息写入一个 CommitLog，是纯顺序写
- 无论多少 Topic/Queue，写入性能稳定
- ConsumeQueue 作为索引，异步构建

### Q3: RocketMQ 如何保证消息不丢失？
| 环节 | 措施 |
|------|------|
| Producer | 同步发送 + 重试 |
| Broker 存储 | 同步刷盘 + 同步复制 |
| Broker 主备 | Master-Slave 同步复制 |
| Consumer | 手动 ACK，消费成功后才返回 |

### Q4: RocketMQ 事务消息原理？
```
1. 发送 Half 消息（半消息）→ Broker 存储但不投递
2. 执行本地事务
3. 本地事务成功 → Commit（半消息变为可消费）
4. 本地事务失败 → Rollback（删除半消息）
5. 如果步骤3/4失败 → Broker 回查 Producer
6. 回查接口返回 Commit 或 Rollback
```

### Q5: RocketMQ 如何实现延迟消息？
- Broker 收到延迟消息后，替换 Topic 为 `SCHEDULE_TOPIC_XXXX`
- 按 Delay Level 存入对应 Queue
- 定时任务（ScheduleMessageService）扫描
- 到达延迟时间后，恢复原始 Topic 和 Queue
- Consumer 正常消费

### Q6: RocketMQ 如何实现顺序消息？
**发送端：**
- 同一业务 Key 的消息发送到同一 MessageQueue

**消费端：**
- 使用 `MessageListenerOrderly`（顺序消费监听器）
- 同一 Queue 由一个线程消费
- 使用锁机制保证消费顺序

```
发送端：orderId % queueSize → 同一 Queue
消费端：MessageListenerOrderly → 同一 Queue 单线程消费
```

### Q7: RocketMQ 的 NameServer 为什么不需要强一致？
- NameServer 存储的是路由信息（Broker 地址）
- 每隔 30s Broker 上报心跳
- 路由信息短暂不一致不影响功能
- Consumer/Producer 定期拉取路由
- 最终一致性即可，牺牲强一致换取可用性（AP）

### Q8: RocketMQ 消息积压怎么处理？
1. 扩容消费者实例（不超 Queue 数量）
2. 如果 Queue 数不够，临时创建新 Topic + 更多 Queue
3. 消费者先跳过积压消息，转存后异步处理
4. 调大 `pullBatchSize` 和 `consumeThreadMax`

### Q9: RocketMQ 的 Push 消费是真正的 Push 吗？
- **不是真正的 Push**
- 本质是 Pull 模式 + 长轮询
- Consumer 发送拉取请求到 Broker
- 如果没有消息，Broker hold 住请求（默认 15s）
- 有消息或超时后返回
- 对用户表现为 Push 模式

### Q10: RocketMQ 如何实现消息轨迹？
- 开启 MessageTrace 功能
- 消息发送、存储、消费各环节记录轨迹
- 轨迹信息存储在 `RMQ_SYS_TRACE_TOPIC`
- 可通过控制台查看消息全链路

### Q11: MessageQueue、ConsumeQueue、CommitLog、消费模式等易混淆问题详解

#### Q1：MessageQueue 和 ConsumeQueue 的区别是什么？只有 ConsumeQueue 不就行了吗？

**结论：不行。MessageQueue 是逻辑队列，ConsumeQueue 是 Broker 本地的物理索引文件，二者不是同一层概念。**

| 对比项 | MessageQueue | ConsumeQueue |
|--------|--------------|--------------|
| 所属层次 | 逻辑概念 | 物理存储文件 |
| 面向对象 | Producer、Consumer、负载均衡、顺序消息 | Broker 内部存储与读取 |
| 主要作用 | 表示一个可投递、可消费、可分配的逻辑队列 | 保存消息在 CommitLog 中的位置索引 |
| 标识方式 | Topic + BrokerName + QueueId | `/store/consumequeue/{topic}/{queueId}/` |
| 是否存完整消息 | 不存 | 不存，只存索引 |
| 是否暴露给客户端 | 暴露 | 不直接暴露 |

可以这样理解：

```text
MessageQueue：告诉客户端“这条消息应该投递到哪个逻辑队列”。
ConsumeQueue：告诉 Broker“这个逻辑队列里的第 N 条消息在 CommitLog 的哪个位置”。
```

**为什么不能只有 ConsumeQueue？**

因为 RocketMQ 需要一个对客户端可见的逻辑队列，用来完成：

1. **消息路由**：Producer 需要知道消息发到哪个 Broker 的哪个 Queue。
2. **消费负载均衡**：Consumer Group 需要把不同 MessageQueue 分配给不同 Consumer。
3. **顺序消息**：同一个业务 Key 的消息要固定投递到同一个 MessageQueue。
4. **消费进度管理**：Consumer 的 Offset 是基于 MessageQueue 维度维护的。

ConsumeQueue 只是 Broker 内部的索引文件，不能承担这些逻辑职责。

**消息投递例子：**

假设 Topic 为 `OrderTopic`，有 4 个 MessageQueue：

| MessageQueue | 所属 Broker | QueueId |
|--------------|-------------|---------|
| MQ-0 | BrokerA | 0 |
| MQ-1 | BrokerA | 1 |
| MQ-2 | BrokerB | 0 |
| MQ-3 | BrokerB | 1 |

现在发送一条订单消息：

```text
消息内容：orderId=1001，状态=已创建
Topic：OrderTopic
业务 Key：1001
```

投递流程：

```text
1. Producer 根据 orderId=1001 选择一个 MessageQueue
   假设选中：MessageQueue(OrderTopic, BrokerA, queueId=1)

2. Producer 把消息发送给 BrokerA

3. BrokerA 将完整消息顺序追加到自己的 CommitLog
   假设写入位置为：commitLogOffset=123456，消息大小 size=200

4. BrokerA 后台线程构建 ConsumeQueue 索引
   文件路径：/store/consumequeue/OrderTopic/1/
   新增一条索引：
   commitLogOffset=123456, size=200, tagHashCode=xxx

5. Consumer Group 中某个 Consumer 被分配到：
   MessageQueue(OrderTopic, BrokerA, queueId=1)

6. Consumer 按逻辑 offset 读取 ConsumeQueue
   例如读取 queueOffset=0 对应的索引

7. Broker 根据索引中的 commitLogOffset=123456
   回到 CommitLog 读取完整消息并返回给 Consumer
```

所以：

```text
MessageQueue 决定消息投递到哪里、由谁消费。
ConsumeQueue 决定如何从 CommitLog 快速找到消息。
```

#### Q2：不同 Broker 的 CommitLog 一样吗？

**结论：不同 Broker 的 CommitLog 不一样。**

RocketMQ 中每个 Broker 都有自己独立的 CommitLog，只保存发送到当前 Broker 上的消息。

例如：

```text
BrokerA:
  CommitLog-A：保存发送到 BrokerA 上所有 Queue 的消息

BrokerB:
  CommitLog-B：保存发送到 BrokerB 上所有 Queue 的消息
```

如果 `OrderTopic` 同时分布在 BrokerA 和 BrokerB 上：

```text
OrderTopic queue0 -> BrokerA -> 写入 BrokerA 的 CommitLog
OrderTopic queue1 -> BrokerA -> 写入 BrokerA 的 CommitLog
OrderTopic queue2 -> BrokerB -> 写入 BrokerB 的 CommitLog
OrderTopic queue3 -> BrokerB -> 写入 BrokerB 的 CommitLog
```

只有在 **同一个 Broker 组的 Master-Slave 复制** 中，Slave 才会复制 Master 的 CommitLog。

```text
BrokerA-Master 的 CommitLog
        ↓ 主从复制
BrokerA-Slave 的 CommitLog
```

因此要区分：

| 场景 | CommitLog 是否一样 |
|------|--------------------|
| 不同 Master Broker，例如 BrokerA 和 BrokerB | 不一样 |
| 同一个 Broker 组的 Master 和 Slave | Slave 复制 Master，数据应保持一致 |
| 不同 Topic 在同一个 Broker 上 | 写入同一个 Broker 的 CommitLog |
| 同一个 Topic 分布在不同 Broker 上 | 分别写入各自 Broker 的 CommitLog |

#### Q3：一个 Topic 可以在不同 Broker 上，那一个 Queue 可以在不同 Broker 上吗？一个 Broker 不是可以全局有序吗？

**结论：一个 Topic 可以分布在多个 Broker 上，但一个 MessageQueue 只能属于一个 Broker，不能跨 Broker。**

一个 MessageQueue 的标识是：

```text
Topic + BrokerName + QueueId
```

例如：

```text
MessageQueue(OrderTopic, BrokerA, queueId=0)
MessageQueue(OrderTopic, BrokerB, queueId=0)
```

注意：这两个 QueueId 都是 `0`，但它们属于不同 Broker，所以是两个不同的 MessageQueue。

| 问题 | 答案 |
|------|------|
| 一个 Topic 可以在多个 Broker 上吗？ | 可以 |
| 一个 Topic 可以有多个 MessageQueue 吗？ | 可以 |
| 一个 MessageQueue 可以跨多个 Broker 吗？ | 不可以 |
| 一个 MessageQueue 内部能保证顺序吗？ | 可以，配合顺序发送和顺序消费 |
| 一个 Topic 多个 MessageQueue 时能全局有序吗？ | 默认不能 |

**为什么一个 Broker 上不等于全局有序？**

Broker 的 CommitLog 确实是顺序追加的，但这个顺序是 **Broker 物理写入顺序**，不是某个 Topic 的消费顺序。

CommitLog 中可能混合写入多个 Topic、多个 Queue 的消息：

```text
BrokerA CommitLog 物理顺序：
offset=100：TopicA queue0 消息A1
offset=200：TopicB queue1 消息B1
offset=300：TopicA queue1 消息A2
offset=400：TopicA queue0 消息A3
offset=500：TopicC queue0 消息C1
```

Consumer 消费时不是直接按 CommitLog 全局顺序消费，而是按 **MessageQueue 对应的 ConsumeQueue** 消费。

例如 `TopicA queue0` 的 ConsumeQueue 可能是：

```text
TopicA queue0 ConsumeQueue：
queueOffset=0 -> commitLogOffset=100 -> 消息A1
queueOffset=1 -> commitLogOffset=400 -> 消息A3
```

`TopicA queue1` 的 ConsumeQueue 可能是：

```text
TopicA queue1 ConsumeQueue：
queueOffset=0 -> commitLogOffset=300 -> 消息A2
```

所以即使都在一个 Broker 上，只要一个 Topic 有多个 Queue，消费者也是按多个 Queue 并行消费，不能天然保证 Topic 级别全局有序。

**RocketMQ 要实现全局有序，需要满足：**

```text
一个 Topic 只设置 1 个 MessageQueue
+ 该 MessageQueue 只在 1 个 Broker 上
+ 使用顺序发送
+ 使用顺序消费 MessageListenerOrderly
+ 同一时刻只有一个线程消费该 Queue
```

如果一个 Topic 有多个 Queue，只能保证 **单个 Queue 内有序**，也就是常说的 **分区顺序**。

#### Q4：如果每个队列存完整消息，就变成 Kafka 模式；RocketMQ 为什么说全局顺序写 CommitLog 性能更好？

**结论：不是说 RocketMQ 在所有场景都一定比 Kafka 快，而是说在 Topic/Queue 数量很多、并发写入分散时，RocketMQ 的统一 CommitLog 更容易保持顺序写优势。**

Kafka 的存储模型是：

```text
Topic-Partition-0 -> 独立日志文件
Topic-Partition-1 -> 独立日志文件
Topic-Partition-2 -> 独立日志文件
...
```

RocketMQ 的存储模型是：

```text
Broker 级别统一 CommitLog
所有 Topic、所有 Queue 的消息都追加到同一个 CommitLog
ConsumeQueue 只保存索引
```

如果每个 Queue 都存完整消息，就会变成：

```text
OrderTopic queue0 -> 独立文件
OrderTopic queue1 -> 独立文件
PayTopic queue0   -> 独立文件
PayTopic queue1   -> 独立文件
...
```

这样当 Topic/Queue 很多时，Broker 同时写很多文件，容易出现：

1. **活跃文件数量多**：每个 Queue/Partition 都有独立日志文件。
2. **PageCache 分散**：缓存命中和刷盘效率下降。
3. **磁盘写入更分散**：多个文件交替追加，顺序写优势被削弱。
4. **文件句柄和索引管理成本上升**。
5. **小批量写入变多**：不同队列各写各的，难以聚合成一个大的连续写入流。

RocketMQ 统一 CommitLog 的优势是：

```text
不管消息属于哪个 Topic、哪个 Queue，Broker 写入时都是追加到同一个 CommitLog。
```

这样可以让 Broker 的写入路径更简单：

```text
多个 Topic/Queue 的随机逻辑写入
        ↓
Broker 合并成 CommitLog 的顺序物理写入
```

举例：

```text
生产者并发发送：
OrderTopic queue0：消息1
PayTopic queue2：消息2
LogTopic queue5：消息3
OrderTopic queue1：消息4

RocketMQ Broker 写入 CommitLog：
offset=100：消息1
offset=200：消息2
offset=300：消息3
offset=400：消息4
```

写入时是连续追加，之后再异步构建不同 ConsumeQueue 的索引。

**和 Kafka 的区别要客观看：**

| 对比点 | Kafka | RocketMQ |
|--------|-------|----------|
| 写入单位 | Partition 日志 | Broker 级 CommitLog |
| 多 Topic/Partition 场景 | 多个日志文件并发写 | 统一 CommitLog 顺序写 |
| 写入性能优势 | 分区并行能力强 | 单 Broker 内聚合顺序写更稳定 |
| 读取模型 | 直接从 Partition 日志读 | 先查 ConsumeQueue，再读 CommitLog |
| 是否绝对谁更快 | 不绝对，取决于场景、磁盘、批量、分区数、刷盘策略 | 不绝对 |

面试回答可以这样说：

```text
Kafka 也是顺序写，并不慢。
RocketMQ 强调的是：当 Topic/Queue 很多时，如果每个 Queue 都独立保存完整消息，会导致大量文件并发写入，顺序写优势下降。RocketMQ 用 Broker 级统一 CommitLog 把多个逻辑队列的写入合并成一个物理追加流，再用 ConsumeQueue 做索引，因此在多队列写入场景下更容易保持稳定的顺序写性能。
```

#### Q5：是一个消费者可以消费多条消息，还是一条消息可以被多个消费者消费？

**两种说法都可能成立，要看消费模式和消费者组。**

##### 1）在同一个 Consumer Group 内：一条消息只会被一个消费者消费

这是 **集群消费模式（CLUSTERING）**，也是最常用模式。

```text
ConsumerGroup = order-consumer-group

MessageQueue-0 -> Consumer-A
MessageQueue-1 -> Consumer-B
MessageQueue-2 -> Consumer-C
```

此时：

```text
同一条消息只会被 order-consumer-group 中的一个 Consumer 消费。
```

也就是说，在同一个消费者组内，是 **竞争消费**。

##### 2）不同 Consumer Group 之间：同一条消息可以被多个消费者组各消费一次

例如：

```text
Topic：OrderTopic

ConsumerGroup-A：订单系统
ConsumerGroup-B：积分系统
ConsumerGroup-C：风控系统
```

同一条订单消息可以被三个消费者组分别消费：

```text
订单系统消费一次
积分系统消费一次
风控系统消费一次
```

这就是发布订阅效果。

##### 3）广播消费模式：同一个 Consumer Group 内的每个消费者都会消费一遍

如果设置为 **BROADCASTING**：

```text
ConsumerGroup = config-refresh-group

Consumer-A 收到消息
Consumer-B 收到消息
Consumer-C 收到消息
```

此时同一个消费者组里的每个 Consumer 都会收到同一条消息。

##### 4）一个消费者当然可以消费多条消息

无论哪种模式，一个 Consumer 实例都会持续消费多条消息，并且一次拉取也可能是一批消息。

```java
public ConsumeConcurrentlyStatus consumeMessage(
        List<MessageExt> msgs,
        ConsumeConcurrentlyContext context) {
    // msgs 是本次批量拉取到的一批消息
    for (MessageExt msg : msgs) {
        // 逐条处理
    }
    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
}
```

总结：

| 场景 | 一条消息会被几个消费者消费 |
|------|----------------------------|
| 同一个 Consumer Group，集群消费 | 只被一个 Consumer 消费 |
| 不同 Consumer Group | 每个 Consumer Group 都可以消费一次 |
| 同一个 Consumer Group，广播消费 | 组内每个 Consumer 都会消费一次 |
| 一个 Consumer 实例 | 可以连续消费很多条消息，也可以批量消费多条消息 |

### Q12: RocketMQ 5.x 如何支持任意投递时间？为什么默认最大范围是 24 小时？消息存放在磁盘还是内存？

**结论：RocketMQ 5.x 通过定时消息能力支持指定投递时间，消息本体仍然持久化在磁盘中，不是长期放在内存里。**

RocketMQ 4.x 的延迟消息主要依赖固定延迟级别：

```text
1s、5s、10s、30s、1m、2m、3m、...、1h、2h
```

RocketMQ 5.x 增强了定时/延时消息能力，可以指定一个具体的投递时间戳，例如：

```java
// 60 秒后投递
message.setDeliverTimeMs(System.currentTimeMillis() + 60_000);
```

也就是说，5.x 不再只依赖固定 Delay Level，而是可以表达：

```text
在某个具体时间点之后投递这条消息
```

#### 1）大致实现思路

RocketMQ 5.x 的定时消息可以简单理解为：

```text
Producer 发送定时消息
        ↓
Broker 接收消息，并识别 deliverTimeMs
        ↓
消息本体持久化到 CommitLog
        ↓
Broker 维护定时消息索引/时间轮结构
        ↓
后台线程扫描即将到期的消息
        ↓
到期后重新投递到真实 Topic
        ↓
Consumer 正常消费
```

可以用更面试化的话说：

```text
RocketMQ 5.x 不是给每条消息创建一个内存定时器，而是把消息持久化到磁盘，并通过时间轮/定时索引等结构管理到期时间。后台扫描线程发现消息到期后，再把消息恢复到原始 Topic 和 Queue 中，让消费者正常拉取消费。
```

#### 2）Broker 维护的定时消息索引/时间轮结构是什么？

可以把 RocketMQ 5.x 的定时消息存储拆成三类结构理解：

| 结构 | 作用 | 类比 |
|------|------|------|
| CommitLog | 存储完整消息内容 | 消息本体仓库 |
| TimerLog / 定时索引 | 记录“某条定时消息在 CommitLog 的哪个位置、什么时候到期” | 到期任务清单 |
| TimerWheel / 时间轮 | 按时间槽组织 TimerLog 位置，方便快速找到某个时间点附近到期的消息 | 日历/时间分桶 |
| TimerCheckpoint | 记录定时消息扫描、投递进度 | 恢复点 |

注意：不同版本内部类名和实现细节可能有差异，但面试理解重点是：

```text
完整消息仍然在 CommitLog；
定时索引只保存到期时间 + CommitLog 位置等元信息；
时间轮按时间槽管理这些定时索引，避免为每条消息创建独立定时器。
```

举例：

```text
原始定时消息：
Topic = OrderTimeoutTopic
deliverTime = 21:00:00
body = orderId=1001

写入 CommitLog 后：
commitLogOffset = 123456
size = 300
```

定时索引里大致保存：

```text
deliverTime = 21:00:00
commitLogOffset = 123456
size = 300
realTopic = OrderTimeoutTopic
realQueueId = 2
```

时间轮做的事情是把它放到某个时间槽：

```text
TimerWheel:
20:59:58 slot -> [...]
20:59:59 slot -> [...]
21:00:00 slot -> [timerLogOffset1, timerLogOffset2, timerLogOffset3, ...]
21:00:01 slot -> [...]
```

这样到 `21:00:00` 附近时，Broker 不需要全量扫描所有定时消息，只需要扫描对应时间槽关联的 TimerLog 记录。

#### 3）“到期后投递到真实 Topic”具体是怎么做的？不是都在 CommitLog 吗？

**关键点：虽然消息本体都在 CommitLog，但 Consumer 不是直接扫 CommitLog 消费，而是根据真实 Topic 的 ConsumeQueue 来消费。**

定时消息在未到期前，不能让消费者看到，所以 Broker 不会提前把它构建到真实 Topic 的 ConsumeQueue 中。

更准确的流程是：

```text
1. Producer 发送定时消息，真实 Topic = OrderTimeoutTopic

2. Broker 接收后，为了防止提前被消费，会将它作为“定时消息”存储
   通常会保存真实 Topic、真实 QueueId 到消息属性中
   同时写入 CommitLog

3. Broker 构建定时索引/时间轮
   索引指向这条消息在 CommitLog 中的位置

4. 消息未到期前
   Consumer 拉取 OrderTimeoutTopic 时看不到这条消息
   因为真实 Topic 的 ConsumeQueue 里还没有这条可消费记录

5. 到期后
   Broker 根据定时索引找到原 CommitLog 中的定时消息
   读取消息内容和真实 Topic 信息

6. Broker 将消息恢复成普通消息
   realTopic = OrderTimeoutTopic
   realQueueId = 原目标队列或重新选择的队列

7. Broker 重新写入 CommitLog，形成一条新的普通消息记录

8. CommitLog 分发线程为真实 Topic 构建 ConsumeQueue 索引
   /store/consumequeue/OrderTimeoutTopic/{queueId}/ 新增一条索引

9. Consumer 拉取 OrderTimeoutTopic 时，才能读到这条消息
```

所以，“投递到真实 Topic”不是把原 CommitLog 里的那一段物理数据原地移动到另一个地方，而是：

```text
读取原定时消息
    ↓
恢复真实 Topic / Queue 属性
    ↓
作为普通消息重新追加写入 CommitLog
    ↓
重新为真实 Topic 构建 ConsumeQueue 索引
    ↓
Consumer 可以正常消费
```

#### 4）为什么要重新写 CommitLog、重新构建 ConsumeQueue？

因为 RocketMQ 的消费路径是：

```text
Consumer -> Topic + Queue -> ConsumeQueue -> CommitLog -> 完整消息
```

如果未到期时就把消息放进真实 Topic 的 ConsumeQueue：

```text
Consumer 拉取真实 Topic
    ↓
看到这条消息
    ↓
可能提前消费
```

这就破坏了定时投递语义。

因此定时消息一般要经历两个阶段：

| 阶段 | CommitLog 中的状态 | ConsumeQueue 中的状态 | Consumer 是否可见 |
|------|--------------------|------------------------|-------------------|
| 未到期 | 定时消息已持久化 | 不进入真实 Topic 的 ConsumeQueue | 不可见 |
| 到期后 | 恢复为普通消息并重新追加 | 构建真实 Topic 的 ConsumeQueue 索引 | 可见 |

用一个具体例子看：

```text
10:00:00 发送定时消息，要求 10:05:00 投递

10:00:00：
CommitLog offset=1000：保存一条定时消息
TimerLog：记录 deliverTime=10:05:00, commitLogOffset=1000
OrderTimeoutTopic 的 ConsumeQueue：没有这条消息
Consumer：看不到

10:05:00：
Broker 扫描 TimerWheel，发现这条消息到期
读取 CommitLog offset=1000 的消息
恢复真实 Topic=OrderTimeoutTopic
重新追加到 CommitLog offset=5000
OrderTimeoutTopic 的 ConsumeQueue 新增索引：queueOffset=0 -> commitLogOffset=5000
Consumer：可以消费
```

#### 5）这里的“重新投递”会不会复制一份消息？

从逻辑上看，是将定时消息“恢复”为普通消息；从存储路径看，通常会有一次新的 CommitLog 追加写入。

这意味着：

```text
原始 CommitLog 记录：用于保存定时消息本体和定时属性
到期后的 CommitLog 记录：用于作为真实 Topic 下的普通可消费消息
```

这样做的好处是：

1. **复用普通消息消费链路**：到期后就和普通消息一样被 ConsumeQueue 索引、被 Consumer 拉取。
2. **保证未到期不可见**：未到期前不会出现在真实 Topic 的 ConsumeQueue 中。
3. **方便故障恢复**：原始定时消息、定时索引、投递进度都可以通过持久化结构恢复。
4. **降低 Consumer 复杂度**：Consumer 不需要感知这是定时消息，只按普通消息消费。

面试可以这样总结：

```text
RocketMQ 5.x 定时消息不是靠内存 Timer 保存，也不是让 Consumer 直接扫描 CommitLog。Broker 会把定时消息持久化到 CommitLog，同时用 TimerLog/TimerWheel 记录到期时间和 CommitLog 位置。未到期前，这条消息不会进入真实 Topic 的 ConsumeQueue，所以消费者不可见。到期后，Broker 根据定时索引读取原消息，恢复真实 Topic 和 Queue，再作为普通消息重新追加到 CommitLog，由正常的 CommitLog 分发流程重新构建真实 Topic 的 ConsumeQueue 索引，之后 Consumer 才能拉取到。
```

#### 6）恢复为普通消息后，之前的定时消息会删除吗？

**结论：不会立即从 CommitLog 中物理删除。**

RocketMQ 的 CommitLog 是追加写文件，不适合对单条消息做原地删除。定时消息到期后，Broker 会读取原来的定时消息，再恢复成普通消息重新写入 CommitLog。原来的那条定时消息记录通常不会立刻被物理删除，而是随着 CommitLog 文件的过期清理机制统一删除。

可以这样理解：

```text
10:00:00：发送定时消息，要求 10:05:00 投递
CommitLog offset=1000：定时消息记录
TimerLog：记录 offset=1000，deliverTime=10:05:00
真实 Topic 的 ConsumeQueue：没有索引，消费者不可见

10:05:00：消息到期
Broker 读取 CommitLog offset=1000 的定时消息
恢复真实 Topic 和 Queue
重新追加普通消息到 CommitLog offset=5000
真实 Topic 的 ConsumeQueue：新增 offset=5000 的索引，消费者可见

之后：
CommitLog offset=1000 的旧定时消息不会马上物理删除
它会等所在 CommitLog 文件满足清理条件后，随文件一起删除
```

为什么不直接删掉原来的消息？

| 原因 | 说明 |
|------|------|
| CommitLog 是顺序追加文件 | 单条物理删除会破坏顺序写模型，成本高 |
| 删除粒度是文件级 | RocketMQ 通常按 CommitLog 文件过期时间、磁盘水位等条件清理 |
| 原定时消息已不可消费 | 它不在真实 Topic 的 ConsumeQueue 中，对消费者不可见 |
| 需要支持恢复 | 定时投递进度和定时索引需要保证异常情况下可恢复 |

所以到期投递后，会存在一段时间内：

```text
旧的定时消息记录：还在 CommitLog 中，但不会被消费者消费
新的普通消息记录：在 CommitLog 中，并且有真实 Topic 的 ConsumeQueue 索引，可以被消费者消费
```

面试可以这样回答：

```text
恢复为普通消息后，原来的定时消息不会立即从 CommitLog 物理删除。RocketMQ 的 CommitLog 是追加写结构，删除通常是文件级清理，不会针对单条消息原地删除。到期时 Broker 会读取原定时消息，恢复真实 Topic 后重新追加一条普通消息，并为真实 Topic 构建 ConsumeQueue 索引。原定时消息因为没有进入真实 Topic 的 ConsumeQueue，所以消费者看不到，后续会随着 CommitLog 过期清理一起删除。
```

#### 7）为什么默认最大范围是 24 小时？

默认最大支持范围通常是为了保护 Broker，而不是因为技术上只能做到 24 小时。

主要原因包括：

| 原因 | 说明 |
|------|------|
| 控制存储成本 | 定时消息在到期前不能被消费，会长期占用 CommitLog、定时索引等存储资源 |
| 控制定时索引规模 | 投递时间越远，Broker 需要维护的时间跨度越大，索引和恢复成本越高 |
| 控制故障恢复成本 | Broker 重启后需要恢复定时消息状态，跨度越大恢复压力越大 |
| 避免把 MQ 当任务调度系统 | MQ 更适合短中期延时投递，不适合承载长期任务调度 |
| 降低时间误差风险 | 时间跨度越长，业务状态、系统时间、消息有效性变化越大 |

所以面试可以这样回答：

```text
RocketMQ 5.x 支持指定投递时间，但默认最大延迟范围一般限制为 24 小时，主要是为了控制 Broker 的存储、索引、扫描和故障恢复成本，避免大量长期定时消息把 MQ 变成任务调度数据库。超过这个范围的场景，通常建议使用专门的调度系统或业务表轮询，再在临近投递时发送 MQ。
```

> 说明：具体最大范围可能与版本和 Broker 配置有关，部分版本可以通过 Broker 参数调整，但生产中不建议无限扩大。

#### 8）消息存放在磁盘还是内存？

**消息本体存放在磁盘。**

RocketMQ 的核心原则仍然是消息可靠存储：

```text
完整消息：写入 CommitLog，持久化在磁盘
定时信息：通过定时索引/时间轮等结构管理
内存：主要用于缓存、索引加载、扫描加速，不是消息唯一存储位置
```

也就是说：

| 内容 | 存储位置 |
|------|----------|
| 消息完整内容 | CommitLog，磁盘持久化 |
| 定时投递时间 | 消息属性 + 定时索引 |
| 定时索引/时间轮 | 可持久化，并会在内存中加载部分结构用于扫描 |
| 到期后的消息 | 重新进入真实 Topic/Queue，消费者正常消费 |

因此，如果 Broker 重启，定时消息不会因为只在内存中而丢失。

### Q13: 如果未来五秒或五分钟内有一万条消息同时到期，RocketMQ 如何处理？

**结论：RocketMQ 不会为每条消息创建一个独立线程或独立定时器，而是通过时间轮/定时索引批量扫描，到期后分批重新投递到真实 Topic。**

假设现在有 1 万条消息都设置为 5 分钟后投递：

```text
msg-1    deliverTime = 21:00:00
msg-2    deliverTime = 21:00:00
msg-3    deliverTime = 21:00:00
...
msg-10000 deliverTime = 21:00:00
```

Broker 的处理方式可以理解为：

```text
1. 这 1 万条消息先写入 CommitLog，保证可靠存储
2. Broker 为这些消息建立定时索引，归入对应时间槽
3. 到达 21:00:00 附近时，后台扫描线程扫描该时间槽
4. 扫描到这批到期消息后，批量/分批取出
5. 将消息恢复或重新投递到真实 Topic、Queue
6. Consumer 按正常消息消费流程拉取这些消息
```

#### 1）会不会同时瞬间全部推给消费者？

通常不会理解成“瞬间开 1 万个线程推送”。RocketMQ 消费本质是拉模式，Push Consumer 也是长轮询封装。

到期后更准确的过程是：

```text
定时消息到期
    ↓
Broker 将消息转为可消费状态
    ↓
消息进入真实 Topic/Queue
    ↓
Consumer 按队列、批次、线程数、拉取参数逐批消费
```

所以 1 万条消息同时到期时，可能表现为：

| 环节 | 现象 |
|------|------|
| Broker 定时扫描 | 该时间槽待处理消息较多 |
| Broker 重新投递 | 分批写入或恢复到真实 Topic |
| Consumer 拉取 | 按批次拉取，不是无限并发 |
| 消费端处理 | 取决于消费者线程数、队列数、业务处理速度 |

#### 2）会不会有延迟抖动？

**会有可能。**

定时消息的投递时间通常表示“到达该时间后可以被投递”，不是严格实时调度系统的毫秒级保证。

如果同一时间点有大量消息到期：

```text
到期消息太多
    ↓
Broker 扫描和重新投递需要时间
    ↓
真实 Topic 出现瞬时堆积
    ↓
Consumer 也需要逐批消费
    ↓
部分消息实际消费时间可能晚于预期投递时间
```

所以面试回答要强调：

```text
RocketMQ 定时消息保证的是到期后可投递，不保证所有消息在到期毫秒级同时被消费完成。大量消息同一时刻到期时，Broker 会批量扫描、分批投递，消费者再批量拉取处理，因此可能产生一定延迟和堆积。
```

#### 3）如何优化大量定时消息同时到期？

| 优化方式 | 说明 |
|----------|------|
| 打散投递时间 | 不要全部设置成同一秒，可以随机打散到几秒或几十秒窗口内 |
| 增加 Topic 队列数 | 提高 Consumer 并行消费能力 |
| 扩容 Consumer | 增加消费者实例，但不要超过 Queue 并行上限太多 |
| 提高消费线程数 | 合理调整 `consumeThreadMin`、`consumeThreadMax` |
| 批量处理业务逻辑 | 减少单条消息处理的数据库/接口开销 |
| 监控定时消息堆积 | 关注 Broker 定时消息延迟、真实 Topic 堆积、消费 TPS |
| 避免超长期定时 | 长期任务用调度系统，临近执行再投递 MQ |

#### 4）一句话总结

```text
RocketMQ 5.x 定时消息依靠持久化存储 + 时间轮/定时索引 + 后台扫描来实现。大量消息同时到期时，Broker 会批量扫描并分批恢复到真实 Topic，Consumer 再按正常拉取模型消费，因此不会创建大量独立定时器，但可能出现短时间堆积和投递延迟，需要通过打散时间、扩容队列和消费者来优化。
```

---

## 六、RocketMQ 最佳实践

### 6.1 Producer 最佳实践
```java
// 使用 Producer Group
producer.setGroup("producer_group");
// 发送失败重试
producer.setRetryTimesWhenSendFailed(3);
// 异步发送重试
producer.setRetryTimesWhenSendAsyncFailed(3);
// 选择合适的发送方式
// - 重要消息：同步发送
// - 非重要消息：异步或 Oneway
```

### 6.2 Consumer 最佳实践
```java
// 使用 Consumer Group
consumer.setGroup("consumer_group");
// 顺序消费设为 1
consumer.setConsumeThreadMin(1);
consumer.setConsumeThreadMax(1);
// 批量拉取大小
consumer.setPullBatchSize(32);
// 合理设置最大重试
consumer.setMaxReconsumeTimes(16);
```

### 6.3 Topic 设计
- 一个应用使用一个 Topic，通过 Tag 区分
- Queue 数量根据吞吐量设置
- 重要业务 Topic 使用单独 Broker

### 6.4 JVM 配置
```bash
# 推荐 8G 以上
-Xms8g -Xmx8g -Xmn4g
# 使用 G1
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m
-XX:InitiatingHeapOccupancyPercent=45
```
