# RabbitMQ 面试知识点总结

## 一、RabbitMQ 基础概念

### 1.1 什么是 RabbitMQ？
RabbitMQ 是一个由 Erlang 语言开发的开源消息中间件，实现了 AMQP（Advanced Message Queuing Protocol）协议。以高可靠性、丰富的路由机制和易用性著称。

**核心特性：**
- 高可靠性（消息确认、持久化）
- 丰富的路由模型
- 支持多种协议（AMQP、STOMP、MQTT）
- 插件生态丰富
- Web 管理界面
- 支持优先级队列、延迟队列等

### 1.2 核心组件

| 组件 | 说明 |
|------|------|
| **Producer** | 消息生产者 |
| **Consumer** | 消息消费者 |
| **Exchange** | 交换机，接收消息并路由到队列 |
| **Queue** | 队列，存储消息 |
| **Binding** | 绑定关系，Exchange 和 Queue 之间的路由规则 |
| **Routing Key** | 路由键，Exchange 根据它路由消息 |
| **Connection** | TCP 连接 |
| **Channel** | 信道，Connection 中的虚拟连接 |
| **Virtual Host** | 虚拟主机，逻辑隔离 |

### 1.3 架构图

```
    Producer
        |
        | Publish (with Routing Key)
        ↓
    +-----------+
    |  Exchange |  ← 绑定关系 (Binding)
    +-----------+
        |         |
        ↓         ↓
    +------+   +------+
    |Queue1|   |Queue2|
    +------+   +------+
        |         |
        ↓         ↓
    Consumer   Consumer
```

### 1.4 AMQP 协议

AMQP（Advanced Message Queuing Protocol）是一个高级消息队列协议，定义了消息的格式和交互流程：

```
AMQP 模型:
  Exchange Types: direct, fanout, topic, headers
  Message Format: Content-Header + Content-Body
  工作流程: Producer → Exchange → Queue → Consumer
```

---

## 二、RabbitMQ 核心机制

### 2.1 Exchange 类型（重点！）

#### 1) Direct Exchange（直连交换机）

**路由规则：** Routing Key 完全匹配 Binding Key

```
Producer (RK=order.create)  →  Direct Exchange
                                    |
                      +-------------+-------------+
                      |             |             |
              Binding(order.create) |     Binding(order.pay)
                      |             |             |
                   Queue-A       Queue-B       Queue-C

# 消息 RK=order.create 只会路由到 Queue-A
```

```java
// 声明 Direct Exchange
channel.exchangeDeclare("direct_exchange", "direct");
// 绑定
channel.queueBind("queueA", "direct_exchange", "order.create");
channel.queueBind("queueB", "direct_exchange", "order.pay");
// 发送
channel.basicPublish("direct_exchange", "order.create", null, msg.getBytes());
```

#### 2) Fanout Exchange（扇出交换机）

**路由规则：** 忽略 Routing Key，广播到所有绑定的 Queue

```
Producer  →  Fanout Exchange
                    |
          +---------+---------+
          |         |         |
       Queue-A   Queue-B   Queue-C

# 所有绑定的 Queue 都会收到消息
```

```java
channel.exchangeDeclare("fanout_exchange", "fanout");
channel.queueBind("queueA", "fanout_exchange", "");
channel.queueBind("queueB", "fanout_exchange", "");
channel.basicPublish("fanout_exchange", "", null, msg.getBytes());
```

#### 3) Topic Exchange（主题交换机）

**路由规则：** Routing Key 与 Binding Key 模式匹配

**通配符：**
- `*`：匹配一个单词
- `#`：匹配零个或多个单词

```
Binding Key 模式:
  order.*       → 匹配 order.create, order.pay（一个单词）
  order.#       → 匹配 order.create, order.create.success（零或多个单词）
  *.create      → 匹配 order.create, payment.create
  #             → 匹配所有

示例:
  Producer (RK=order.create.success)
    → 匹配 Binding: order.#
    → 匹配 Binding: order.create.*
    → 匹配 Binding: *.create.*
```

#### 4) Headers Exchange（头交换机）

**路由规则：** 根据 Message Header 匹配，忽略 Routing Key

```
匹配规则:
  x-match = all: 所有 header 匹配
  x-match = any: 任一 header 匹配
```

### 2.2 消息持久化

**三个层面持久化：**

```java
// 1. Exchange 持久化（durable=true）
channel.exchangeDeclare("my_exchange", "direct", true);

// 2. Queue 持久化（durable=true）
channel.queueDeclare("my_queue", true, false, false, null);

// 3. Message 持久化
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .deliveryMode(2)  // 2=持久化
    .build();
channel.basicPublish("", "my_queue", props, msg.getBytes());
```

| 持久化层 | 参数 | 说明 |
|----------|------|------|
| Exchange | durable=true | 交换机元数据持久化 |
| Queue | durable=true | 队列元数据持久化 |
| Message | deliveryMode=2 | 消息内容持久化 |

### 2.3 消息确认机制（ACK）

**生产者确认（Publisher Confirm）：**
```java
// 开启 Confirm
channel.confirmSelect();
// 发送消息
channel.basicPublish("", "queue", null, msg.getBytes());
// 等待确认
if (channel.waitForConfirms()) {
    System.out.println("消息发送成功");
} else {
    System.out.println("消息发送失败");
}
```

**生产者 Return 机制：**
- 消息不可路由时（找不到 Queue），触发 Return 回调

```java
channel.addReturnListener(returnMessage -> {
    System.out.println("消息路由失败: " + new String(returnMessage.getBody()));
});
channel.basicPublish("exchange", "routingKey", true, false, null, msg.getBytes());
```

**消费者 ACK：**
```java
// 自动 ACK（消息发出即确认）
channel.basicConsume("queue", true, callback);

// 手动 ACK（处理完成后确认）
channel.basicConsume("queue", false, new DefaultConsumer(channel) {
    @Override
    public void handleDelivery(String consumerTag, Envelope envelope,
                               AMQP.BasicProperties properties, byte[] body) {
        try {
            // 处理消息
            channel.basicAck(envelope.getDeliveryTag(), false);
        } catch (Exception e) {
            // nack 并重新入队
            channel.basicNack(envelope.getDeliveryTag(), false, true);
        }
    }
});
```

### 2.4 Channel 模型

**为什么用 Channel 而不是直接用 Connection？**

```
不使用 Channel:
  Producer → Connection (TCP) → Broker
  问题: 每次创建 TCP 连接开销大（三次握手）

使用 Channel:
  Producer → Connection (1个TCP) → 多个 Channel → Broker
  优势: 复用 TCP 连接，轻量级虚拟连接
```

**最佳实践：**
- 每个线程使用独立的 Channel
- 不要共享 Channel（非线程安全）
- Channel 非线程安全，Connection 是线程安全的

---

## 三、RabbitMQ 高级特性

### 3.1 死信队列（DLX）

**消息变为死信的条件：**
1. 消息被消费者拒绝（`basic.reject` / `basic.nack`）且 `requeue=false`
2. 消息 TTL 过期
3. 队列达到最大长度

**DLX 配置：**
```java
// 声明死信交换机
channel.exchangeDeclare("dlx_exchange", "direct");

// 普通队列绑定死信交换机
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "dlx_exchange");
args.put("x-dead-letter-routing-key", "dlx.routing.key");
channel.queueDeclare("normal_queue", false, false, false, args);

// 死信队列
channel.queueDeclare("dlx_queue", false, false, false, null);
channel.queueBind("dlx_queue", "dlx_exchange", "dlx.routing.key");
```

```
正常流程:  Producer → Normal Exchange → Normal Queue → Consumer
                                                         ↓ (reject/nack)
死信流程:                                      DLX Exchange → DLX Queue
```

### 3.2 延迟队列

RabbitMQ 本身不支持延迟队列，通过以下方式实现：

**方式1：TTL + DLX**

```java
// 设置消息 TTL
Map<String, Object> args = new HashMap<>();
args.put("x-message-ttl", 60000);  // 60秒
args.put("x-dead-letter-exchange", "dlx_exchange");
channel.queueDeclare("delay_queue", false, false, false, args);

// 消息进入 delay_queue，60s 后过期变成死信，转发到 dlx_exchange
```

```
Producer → delay_queue (TTL=60s)
                ↓ 60s后过期
          DLX Exchange → 实际消费 Queue → Consumer
```

**方式2：rabbitmq-delayed-message-exchange 插件**

```java
// 声明延迟交换机
Map<String, Object> args = new HashMap<>();
args.put("x-delayed-type", "direct");
channel.exchangeDeclare("delayed_exchange", "x-delayed-message", false, false, args);

// 发送延迟消息
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .headers(Collections.singletonMap("x-delay", 60000))  // 延迟60秒
    .build();
channel.basicPublish("delayed_exchange", "routingKey", props, msg.getBytes());
```

### 3.3 优先级队列

```java
Map<String, Object> args = new HashMap<>();
args.put("x-max-priority", 10);  // 最大优先级
channel.queueDeclare("priority_queue", false, false, false, args);

// 发送消息时设置优先级
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .priority(5)  // 优先级5
    .build();
channel.basicPublish("", "priority_queue", props, msg.getBytes());
```

### 3.4 惰性队列（Lazy Queue）

- 消息直接存入磁盘，不驻留内存
- 适用于百万级以上消息堆积场景
- 牺牲性能换取内存安全

```java
Map<String, Object> args = new HashMap<>();
args.put("x-queue-mode", "lazy");
channel.queueDeclare("lazy_queue", false, false, false, args);
```

### 3.5 消息 TTL

**队列级别 TTL：**
```java
Map<String, Object> args = new HashMap<>();
args.put("x-message-ttl", 3600000);  // 整个队列消息 1 小时后过期
channel.queueDeclare("ttl_queue", false, false, false, args);
```

**单条消息 TTL：**
```java
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .expiration("60000")  // 60秒后过期
    .build();
channel.basicPublish("", "queue", props, msg.getBytes());
```

---

## 四、RabbitMQ 高可用

### 4.1 集群模式

**普通集群模式：**
```
Node-A: Exchange + Queue元数据    ←─同步──→  Node-B: Exchange + Queue元数据
                                    ←同步──→  Node-C: Exchange + Queue元数据

问题: Queue 的实际数据只在一个节点上
```

- 元数据（Exchange/Binding）在所有节点同步
- Queue 数据只在一个节点（Queue 所属节点）
- 如果 Queue 所在节点宕机，Queue 不可用

**镜像队列集群模式：**
```
Node-A: Queue (Master)  ←─同步──→  Node-B: Queue (Mirror)
                                ←同步──→  Node-C: Queue (Mirror)

消息在多个节点间同步
```

- Queue 数据在多个节点同步
- Master 宕机后 Mirror 自动升级为 Master
- 保证数据不丢失
- 性能有损耗（同步开销）

### 4.2 RabbitMQ 集群更像 RocketMQ 主从，还是 Kafka Partition 副本？

**结论：RabbitMQ 的集群模式既不完全像 RocketMQ 的 Broker 主从，也不完全像 Kafka 的 Partition 副本，它是“队列级别”的复制/选主模型。**

三者的核心区别如下：

| 对比项 | RabbitMQ | RocketMQ | Kafka |
|--------|----------|----------|-------|
| 复制粒度 | Queue 级别 | Broker / CommitLog 级别 | Partition 级别 |
| 写入入口 | 某个 Queue 的主节点 / Leader | Broker Master | Partition Leader |
| 副本形态 | 镜像队列 Mirror / 仲裁队列副本 | Master-Slave / Dledger | Leader-Follower Replica |
| 普通集群是否复制消息 | 不复制，只同步元数据 | 主从复制 CommitLog | Partition 副本复制日志 |
| 高可用重点 | Queue 是否有副本 | Broker Master 是否有 Slave | Partition Leader 是否有 ISR 副本 |

#### 1）RabbitMQ 普通集群：既不是主从，也不是 Partition 副本

RabbitMQ 普通集群只同步元数据：

```text
Exchange、Queue 定义、Binding、用户、权限等元数据会在节点间同步
```

但消息数据仍然只在 Queue 所在节点上：

```text
Queue-A 位于 Node-1
消息也只在 Node-1

Node-2、Node-3 知道 Queue-A 的元数据
但没有 Queue-A 的完整消息数据
```

所以普通集群模式下：

```text
如果 Queue-A 所在的 Node-1 宕机，Queue-A 就不可用了。
```

这和 Kafka/RocketMQ 不一样：

```text
Kafka：Partition 数据通常有多个副本。
RocketMQ：Master 的 CommitLog 可以复制到 Slave。
RabbitMQ 普通集群：Queue 消息默认不复制，只复制元数据。
```

#### 2）RabbitMQ 镜像队列：更像“Queue 级主从复制”

镜像队列中，一个 Queue 会有一个 Master 和多个 Mirror：

```text
Queue-A:
  Node-1: Master
  Node-2: Mirror
  Node-3: Mirror
```

消息写入时：

```text
Producer -> Queue Master -> 同步到 Mirror
```

如果 Master 宕机：

```text
某个 Mirror 会提升为新的 Master
```

所以镜像队列更像：

```text
Queue 级别的主从复制
```

但它不是 RocketMQ 那种 Broker 级主从，因为 RocketMQ 复制的是整个 Broker 的 CommitLog，而 RabbitMQ 镜像的是某个 Queue 的消息。

#### 3）RabbitMQ 仲裁队列：更像“Queue 级 Raft 副本”

RabbitMQ 3.8 之后推荐使用 Quorum Queue（仲裁队列）。

仲裁队列基于 Raft：

```text
Queue-A:
  Node-1: Leader
  Node-2: Follower
  Node-3: Follower
```

写入流程：

```text
Producer -> Queue Leader
        -> 复制到多数派 Follower
        -> 多数派确认后写入成功
```

这和 Kafka 有点像，因为都有 Leader/Follower；但区别是：

```text
Kafka 的副本粒度是 Partition。
RabbitMQ 仲裁队列的副本粒度是 Queue。
```

#### 4）RabbitMQ 的 Queue 和 Kafka 的 Partition 像不像？

**结论：像，但只是在“并行消费单元/消息存储单元”这个角度像；从整体模型看，两者差别很大。**

可以先这么理解：

```text
RabbitMQ Queue：消息最终落到 Queue 中，Consumer 从 Queue 消费。
Kafka Partition：消息最终落到 Partition 中，Consumer 从 Partition 消费。
```

所以从这个角度看，它们确实有相似点：

| 相似点 | RabbitMQ Queue | Kafka Partition |
|--------|----------------|-----------------|
| 都能存消息 | Queue 保存待消费消息 | Partition 保存日志消息 |
| 都是消费单元 | Consumer 从 Queue 拉/收消息 | Consumer 从 Partition 拉消息 |
| 都能提升并行度 | 多 Queue 可以被多个 Consumer 消费 | 多 Partition 可以被多个 Consumer 消费 |
| 都有顺序性边界 | 单 Queue 内大体有序 | 单 Partition 内有序 |

例如：

```text
RabbitMQ:
OrderQueue-0 -> Consumer-A
OrderQueue-1 -> Consumer-B
OrderQueue-2 -> Consumer-C

Kafka:
OrderTopic-Partition-0 -> Consumer-A
OrderTopic-Partition-1 -> Consumer-B
OrderTopic-Partition-2 -> Consumer-C
```

从“并行消费”的角度看：

```text
RabbitMQ 的多个 Queue ≈ Kafka 的多个 Partition
```

但是不能完全等同，关键区别在这里：

| 区别 | RabbitMQ Queue | Kafka Partition |
|------|----------------|-----------------|
| 所属模型 | AMQP 队列模型 | 日志分区模型 |
| 前面是否有路由层 | 有 Exchange，消息先到 Exchange 再路由到 Queue | Producer 直接写 Topic 的某个 Partition |
| 消息消费后是否保留 | 默认 ACK 后消息从 Queue 删除 | 消息不会因消费而删除，按保留策略删除 |
| 多消费者消费同一份数据 | 同一个 Queue 下多个 Consumer 是竞争消费 | 不同 Consumer Group 可各自消费同一 Partition |
| Offset 模型 | 没有 Kafka 那种长期 Offset 日志回放模型 | 每个 Consumer Group 维护自己的 Offset |
| 消息回溯 | 默认不支持已 ACK 消息回溯 | 天然支持按 Offset 回溯 |
| 副本模型 | 普通集群不复制消息；镜像/仲裁是 Queue 级复制 | Partition 级副本，Leader/Follower/ISR |
| 典型用途 | 业务消息、任务分发、复杂路由 | 日志流、事件流、大数据管道 |

最关键的区别是：

```text
RabbitMQ Queue 更像“任务队列”：消息被某个消费者确认后，通常就从队列中删除。
Kafka Partition 更像“可回放日志”：消息被消费后仍然保留一段时间，不同消费者组可以反复读取。
```

举个例子：

```text
RabbitMQ:
一条消息进入 Queue-A
Consumer-1 消费并 ACK
这条消息通常就从 Queue-A 删除
Consumer-2 不能再消费这条已 ACK 消息

Kafka:
一条消息进入 Partition-0，offset=100
ConsumerGroup-A 可以消费 offset=100
ConsumerGroup-B 也可以消费 offset=100
只要消息还没过期，后续还可以按 offset 回放
```

所以面试可以这样回答：

```text
RabbitMQ 的 Queue 和 Kafka 的 Partition 在“消息落点”和“并行消费单元”上有相似性，多个 Queue 可以类似多个 Partition 一样提高消费并行度。但两者不是一个东西。RabbitMQ Queue 是 AMQP 模型下的任务队列，消息 ACK 后通常删除，默认不支持回溯；Kafka Partition 是持久化顺序日志，消息消费后仍按保留策略保存，不同消费者组可以维护各自 Offset 反复消费。因此 RabbitMQ Queue 更偏任务队列，Kafka Partition 更偏日志分区。
```

#### 5）一句话记忆

```text
RabbitMQ 普通集群：只同步元数据，不复制消息。
RabbitMQ 镜像队列：Queue 级主从复制。
RabbitMQ 仲裁队列：Queue 级 Raft 副本。
Kafka：Partition 级副本。
RocketMQ：Broker/CommitLog 级主从复制。
```

面试回答可以这样说：

```text
RabbitMQ 不能简单归类为 RocketMQ 模式或 Kafka 模式。普通 RabbitMQ 集群只同步元数据，消息仍在队列所在节点，不具备消息副本。镜像队列是 Queue 级主从复制，仲裁队列是 Queue 级 Raft 副本。Kafka 是 Partition 级副本，RocketMQ 传统上是 Broker Master-Slave 复制 CommitLog。所以 RabbitMQ 的高可用粒度主要是 Queue，而不是 Broker 或 Partition。
```

### 4.3 镜像队列配置

```bash
# RabbitMQ 管理命令：设置镜像策略
rabbitmqctl set_policy ha-mirror "^ha\." \
  '{"ha-mode":"exactly","ha-params":2,"ha-sync-mode":"automatic"}'

# 参数说明:
# ha-mode: all(所有节点) / exactly(指定数量) / nodes(指定节点)
# ha-params: 节点数量（ha-mode=exactly时有效）
# ha-sync-mode: automatic(自动同步) / manual(手动)
```

### 4.4 Quorum Queue（仲裁队列）（3.8+）

- 基于 Raft 协议的队列
- 替代镜像队列（官方推荐）
- 数据强一致
- 自动 Leader 选举

```java
Map<String, Object> args = new HashMap<>();
args.put("x-queue-type", "quorum");
channel.queueDeclare("quorum_queue", true, false, false, args);
```

| 特性 | 镜像队列 | 仲裁队列 |
|------|----------|----------|
| 一致性 | 最终一致 | 强一致（Raft） |
| 性能 | 较高 | 略低 |
| 故障恢复 | 可能丢数据 | 不丢数据 |
| 推荐 | 逐步淘汰 | 推荐 |

---

## 五、RabbitMQ 常见面试题

### Q1: RabbitMQ 的 Exchange 类型有哪些？
| 类型 | 路由规则 | 适用场景 |
|------|----------|----------|
| Direct | RK 完全匹配 BK | 点对点 |
| Fanout | 广播 | 广播通知 |
| Topic | 模式匹配（*和#） | 发布订阅 |
| Headers | Header 匹配 | 灵活路由 |

### Q2: RabbitMQ 如何保证消息不丢失？
| 环节 | 措施 |
|------|------|
| Producer | 开启 Confirm 模式，确认消息到达 Broker |
| Exchange | durable=true（持久化） |
| Queue | durable=true（持久化） |
| Message | deliveryMode=2（持久化） |
| Consumer | 手动 ACK，处理成功后确认 |

### Q3: RabbitMQ 如何保证消息有序性？
- **方案1**：一个 Queue，一个 Consumer（牺牲并发）
- **方案2**：同一业务消息发到同一 Queue，多个 Queue 并行
- **注意**：多个 Consumer 消费同一 Queue 会乱序

```
方案2:
  Producer → 按 orderId % N 分配 Queue
       → Queue-0 → Consumer-0
       → Queue-1 → Consumer-1
       → Queue-2 → Consumer-2
```

### Q4: RabbitMQ 如何实现延迟队列？
1. **TTL + DLX**：消息过期后进入死信队列
   - 注意：队头消息未过期，后面消息即使过期也无法进入DLX
2. **延迟插件**：`rabbitmq-delayed-message-exchange`（推荐）
   - 支持任意延迟时间
   - 消息在 Exchange 层延迟

### Q5: RabbitMQ 的 Channel 是什么？为什么用 Channel？
- Channel 是 Connection 中的虚拟连接
- TCP 连接创建开销大，Channel 复用 TCP 连接
- Channel 是非线程安全的，每个线程应使用独立 Channel
- 一个 Connection 可以创建多个 Channel

### Q6: RabbitMQ 消息积压怎么处理？
1. 增加 Prefetch Count 提高消费者吞吐
2. 增加消费者数量
3. 使用惰性队列处理大量积压
4. 临时增加 Queue 和消费者
5. 定位根因（消费者处理慢、网络等）

### Q7: RabbitMQ 和 Kafka 的区别？
| 特性 | RabbitMQ | Kafka |
|------|----------|-------|
| 语言 | Erlang | Scala/Java |
| 协议 | AMQP | 自定义 |
| 吞吐量 | 万级 | 百万级 |
| 延迟 | 微秒级 | 毫秒级 |
| 路由 | 丰富 | 简单 |
| 消息回溯 | 不支持 | 支持 |
| 适用场景 | 复杂路由 | 日志/流处理 |

### Q8: RabbitMQ 的 Prefetch 是什么？
- 限制消费者一次拉取的消息数量
- 类似 Kafka 的 `max.poll.records`
- 合理设置可以提高吞吐

```java
// QoS 设置
channel.basicQos(50);  // 最多预取 50 条
```

### Q9: RabbitMQ 如何实现广播？
1. **Fanout Exchange**：所有绑定的 Queue 都收到
2. **Topic Exchange + `#`**：效果类似广播
3. **多个消费者监听同一 Queue**：不推荐，只有其中一个能收到

### Q10: RabbitMQ 的消息幂等性如何保证？
RabbitMQ 本身不保证幂等性，需业务层处理：
1. 消息唯一 ID（messageId）
2. 消费端去重（Redis/数据库）
3. 业务幂等设计（状态机、唯一约束）

```java
// 消费端幂等示例
String messageId = properties.getMessageId();
if (redis.setnx(messageId, "1", 3600)) {
    // 没消费过，处理消息
    processMessage(body);
} else {
    // 已消费过，跳过
    channel.basicAck(envelope.getDeliveryTag(), false);
}
```

---

## 六、RabbitMQ 最佳实践

### 6.1 Producer 最佳实践
```java
// 开启 Confirm
channel.confirmSelect();
// 持久化消息
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .deliveryMode(2)
    .messageId(UUID.randomUUID().toString())
    .build();
// 发送并确认
channel.basicPublish("exchange", "routingKey", props, msg.getBytes());
channel.waitForConfirmsOrDie();
```

### 6.2 Consumer 最佳实践
```java
// 手动 ACK
channel.basicQos(50);  // 合理设置 Prefetch
channel.basicConsume("queue", false, new DefaultConsumer(channel) {
    @Override
    public void handleDelivery(String consumerTag, Envelope envelope,
                               AMQP.BasicProperties properties, byte[] body) {
        try {
            processMessage(body);
            channel.basicAck(envelope.getDeliveryTag(), false);
        } catch (Exception e) {
            // 重试或死信
            channel.basicNack(envelope.getDeliveryTag(), false, false);
        }
    }
});
```

### 6.3 Queue 设计
- 高吞吐场景使用多 Queue
- 重要业务配置镜像队列或仲裁队列
- 合理设置 TTL 避免消息积压
- 配置死信队列处理消费失败

### 6.4 Exchange 设计
- 一个业务一个 Exchange
- 合理使用 Exchange 类型
- 持久化 Exchange（durable=true）
- 使用 Topic Exchange 提高灵活性

### 6.5 Connection 和 Channel 管理
```java
// Connection 复用，Channel 线程私有
ConnectionFactory factory = new ConnectionFactory();
factory.setUri("amqp://guest:guest@localhost:5672");
Connection connection = factory.newConnection();
// 每个线程独立 Channel
Channel channel = connection.createChannel();
```
