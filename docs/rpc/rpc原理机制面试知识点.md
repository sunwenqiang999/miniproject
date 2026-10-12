# RPC 原理机制面试知识点

## 一、动态代理原理

### 1.1 为什么 RPC 需要动态代理？

RPC 的目标是让调用方像调用本地方法一样调用远程服务。调用方只依赖接口，真正的网络请求逻辑由框架在**运行时生成的代理对象**中完成，因此必须用动态代理。

```java
// 业务代码只看到接口调用
UserService userService = reference.get();
UserDTO user = userService.getUserById(1001L);
// 实际上 userService 是框架生成的代理，内部完成序列化 + 网络发送
```

### 1.2 JDK 动态代理 vs CGLIB/Javassist

| 对比项 | JDK 动态代理 | CGLIB/Javassist |
|--------|-------------|-----------------|
| 代理方式 | 实现接口 | 生成子类/字节码 |
| 要求 | 必须有接口 | 可以代理普通类 |
| 性能 | 反射调用，略慢 | 字节码直接调用，较快 |
| 典型使用 | Spring AOP 默认（有接口时） | Dubbo 默认用 Javassist |

JDK 动态代理示例：

```java
public class RpcInvokerProxy implements InvocationHandler {
    private final String interfaceName;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        RpcRequest request = new RpcRequest();
        request.setInterfaceName(interfaceName);
        request.setMethodName(method.getName());
        request.setParamTypes(method.getParameterTypes());
        request.setArgs(args);
        request.setRequestId(IdGenerator.nextId());
        // 序列化 + 通过 Netty 发送，同步等待响应
        return transport.send(request);
    }
}

UserService proxy = (UserService) Proxy.newProxyInstance(
        UserService.class.getClassLoader(),
        new Class<?>[]{UserService.class},
        new RpcInvokerProxy("org.example.UserService"));
```

**面试回答：**

> RPC 框架通过动态代理生成接口的代理对象，把方法调用拦截下来，封装成包含接口名、方法名、参数类型、参数值的请求对象，再交给序列化和网络模块发送。Dubbo 默认用 Javassist 生成字节码代理，性能比 JDK 反射好；生成代理时还会带上版本、分组、超时等配置信息。

### 1.3 为什么不能直接代理类而通常要求接口？

- 接口是服务契约，天然适合定义"远程能提供什么能力"。
- 调用方只需要依赖接口包，不需要依赖实现类，实现了解耦。
- JDK 动态代理要求接口，成本最低；Dubbo 这类框架也约定以接口为服务单元做注册和路由。

---

## 二、网络通信模型

### 2.1 BIO / NIO / AIO 对比

| 模型 | 特点 | 问题/优势 | RPC 是否使用 |
|------|------|-----------|--------------|
| BIO | 一个连接一个线程，读写阻塞 | 连接数高时线程爆炸 | 否 |
| NIO | 单线程轮询多个 Channel，事件驱动 | 支撑海量连接，编程复杂 | 是（Netty 封装） |
| AIO | 操作系统完成 IO 后回调 | Linux 下底层仍是 epoll，优势不明显 | 较少 |

### 2.2 IO 多路复用

NIO 的核心是一个线程通过 Selector 同时监听大量连接事件：

```text
连接 1 ─┐
连接 2 ─┤
连接 3 ─┤      epoll/kqueue
连接 N ─┘         │
                   ▼
              Selector 轮询就绪事件
                   │
                   ▼
        OP_ACCEPT / OP_READ / OP_WRITE
```

**核心理解：**
- 一个 IO 线程可以管理成千上万的连接。
- 只有真正就绪的连接才会被处理，不空耗 CPU。
- Linux 下用 epoll，相比 select/poll 没有文件描述符数量限制，且不需要每次全量遍历。

### 2.3 为什么 RPC 框架都用 Netty？

Netty 是基于 NIO 的高性能网络框架，Dubbo、gRPC、RocketMQ 等都基于它。

**选择 Netty 的原因：**

1. **封装 NIO 复杂性**：不用手写 Selector、Channel、ByteBuffer 细节。
2. **Reactor 线程模型成熟**：Boss 线程接收连接，Worker 线程处理 IO，天然分离。
3. **解决粘包拆包**：内置 `LengthFieldBasedFrameDecoder` 等解码器。
4. **内存优化**：支持零拷贝、池化 ByteBuf，减少 GC。
5. **心跳与空闲检测**：`IdleStateHandler` 天然支持连接保活。
6. **生态成熟**：稳定、社区活跃、被大量中间件验证。

### 2.4 Netty Reactor 线程模型

```text
              ┌─────────────────────────────┐
 客户端连接 ──▶│  BossGroup（1个线程）        │
              │  只处理 OP_ACCEPT，接收连接   │
              └──────────────┬──────────────┘
                             │ 注册连接
              ┌──────────────▼──────────────┐
              │  WorkerGroup（N个线程）      │
              │  处理 OP_READ / OP_WRITE    │
              └──────────────┬──────────────┘
                             │ 解码后投递
              ┌──────────────▼──────────────┐
              │  业务线程池                  │
              │  执行真正的业务逻辑           │
              └─────────────────────────────┘
```

**面试高频：为什么业务逻辑不能在 IO 线程执行？**

> Netty 的 IO 线程负责网络读写和编解码，如果在 IO 线程里执行耗时业务逻辑（比如查数据库），会阻塞该线程上其他所有连接的读写，导致大量请求超时。所以 Dubbo 等框架收到请求解码后，会把任务投递到独立的业务线程池执行，IO 线程立刻释放。

### 2.5 长连接与连接管理

| 机制 | 说明 |
|------|------|
| TCP 长连接 | 一次建立连接，多次复用收发请求，避免三次握手开销 |
| 连接复用/共享 | Dubbo 默认消费方与每个提供者共享少量连接，多路复用 |
| 心跳保活 | 定时发送心跳包，防止连接被防火墙/LB 断掉，及时发现死连接 |
| 连接重建 | 心跳失败或断线后自动重连，注册中心变更后增删连接 |

**多路复用的关键：请求 ID。**

多个线程在同一连接上并发发请求，靠协议中的 `requestId` 把响应和请求对应起来：

```java
// 发送时记录 Future
Future<RpcResponse> future = new DefaultFuture(request);
pendingRequests.put(request.getRequestId(), future);
channel.writeAndFlush(request);

// 收到响应时按 requestId 找到对应 Future 并唤醒
long requestId = response.getRequestId();
DefaultFuture future = pendingRequests.remove(requestId);
future.complete(response);
```

---

## 三、序列化与反序列化

### 3.1 常见序列化方式对比

| 序列化方式 | 优点 | 缺点 | 适用场景 |
|-----------|------|------|----------|
| JDK 序列化 | 无需额外依赖 | 体积大、性能差、只能 Java、有安全漏洞 | 基本不推荐 |
| JSON（Jackson/Fastjson） | 可读性好、跨语言 | 体积大、性能一般 | 调试、对外接口 |
| Protobuf | 体积小、性能高、跨语言、强 schema | 需要 .proto 文件和代码生成 | gRPC、跨语言内部调用 |
| Kryo | 性能高、体积小 | 只能 Java、兼容性需注意 | Dubbo 高频内部调用 |
| Hessian2 | Dubbo 默认、跨语言、无需 IDL | 性能略逊于 Protobuf/Kryo | Dubbo 默认场景 |
| Thrift | IDL + 代码生成、跨语言 | 需要 IDL 维护 | Thrift 框架体系 |

### 3.2 为什么不用 JDK 序列化？

面试高频，回答四点：

1. **体积大**：会序列化类的完整描述信息（类名、字段描述等），字节数远超 JSON/Protobuf。
2. **性能差**：反射机制 + 大量元数据写入，序列化耗时高。
3. **无法跨语言**：字节格式是 Java 私有协议。
4. **安全性差**：反序列化漏洞是 Java 经典攻击面（如 Commons-Collections 链）。

### 3.3 Protobuf 为什么快且小？

```protobuf
syntax = "proto3";

message User {
  int64 id = 1;
  string name = 2;
  int32 age = 3;
}
```

- **Tag-Length-Value 编码**：字段用编号（1、2、3）而不是字段名标识，省去名字长度。
- **Varint 变长编码**：小整数只占 1 个字节，不固定 4/8 字节。
- **无自描述信息**：schema 在编译期确定，字节流里只有数据。
- **生成代码直接读写**：不走反射。

**代价：** 字段顺序和编号不能随意变更，只能加字段不能改编号，否则新老版本不兼容。

### 3.4 序列化选型面试回答模板

> 内部 Java 服务高频调用、追求极致性能，可以选 Kryo；跨语言场景选 Protobuf；Dubbo 默认 Hessian2 在易用性和性能间比较平衡；需要人眼可读、方便调试的对外接口用 JSON。核心权衡是：性能与体积、跨语言能力、兼容性维护成本、可读性。

---

## 四、RPC 协议设计

### 4.1 为什么需要自定义协议？

TCP 是字节流协议，没有消息边界。直接把序列化后的字节往连接上写，接收方不知道一条消息从哪到哪，因此需要在应用层定义协议格式。

### 4.2 典型协议结构

以 Dubbo 协议风格为例：

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├───────────────┼───────────────┼───────────────┼───────────────┤
│      魔数 Magic (2B)          │ 标志位(1B)     │ 状态码(1B)     │
├───────────────────────────────┴───────────────┴───────────────┤
│                    请求 ID Request ID (8B)                     │
├───────────────────────────────┬───────────────────────────────┤
│       数据长度 Body Length (4B)                                │
├───────────────────────────────┴───────────────────────────────┤
│                    消息体 Body（变长）                          │
│              接口名/方法名/参数类型/参数值/附件                  │
└───────────────────────────────────────────────────────────────┘
```

各字段作用：

| 字段 | 作用 |
|------|------|
| 魔数 | 快速识别协议、过滤脏数据（如 Dubbo 的 `0xdabb`） |
| 标志位 | 标识请求/响应、序列化方式、是否心跳、是否双向 |
| 请求 ID | 多路复用下关联请求与响应 |
| 数据长度 | 解决粘包拆包，接收方知道读多少字节算一条完整消息 |
| 消息体 | 序列化后的调用信息 |

### 4.3 粘包与拆包

**产生原因：**
- TCP 是流式协议，发送方多次 `write` 的数据可能被合并成一个包发送（粘包）。
- 一个大数据包可能被 MSS 拆成多个包传输（拆包）。
- 接收方一次 `read` 可能读到半条、一条或多条消息。

**解决方案对比：**

| 方案 | 说明 | 缺点 |
|------|------|------|
| 定长消息 | 每条消息固定字节数，不足补齐 | 浪费带宽 |
| 分隔符 | 用特殊字符（如换行）分隔 | 内容包含分隔符需转义 |
| 长度字段 | 协议头带 body 长度，先读头再读体 | 最常用，无明显缺点 |

Netty 对应实现：`FixedLengthFrameDecoder`、`DelimiterBasedFrameDecoder`、`LengthFieldBasedFrameDecoder`。

### 4.4 编解码流程

```text
发送：RpcRequest -> 编码器(Encoder) -> [协议头 + 序列化体] -> ByteBuf -> Socket
接收：Socket -> ByteBuf -> 解码器(Decoder，按长度字段切分) -> 反序列化 -> RpcRequest
```

---

## 五、服务注册与发现原理

### 5.1 注册中心的角色

```text
        ┌──────────────────┐
        │   注册中心         │
        │  (ZK/Nacos/Etcd)  │
        └───▲─────────▲────┘
   ① 注册    │         │    ② 订阅
            │         │
┌───────────┴──┐   ┌──┴────────────┐
│   Provider    │   │   Consumer     │
│  服务提供方    │──▶│  服务消费方     │
└──────────────┘   └───────────────┘
                     ③ 拿到地址列表后直接 RPC 调用
```

**流程：**
1. Provider 启动时把自己的接口名、IP、端口、元数据注册到注册中心。
2. Consumer 启动时订阅所需接口，拉取提供者列表并缓存到本地。
3. 提供者列表变更时，注册中心推送给 Consumer 更新本地缓存。
4. Consumer 基于本地列表做负载均衡，**直连** Provider，不经过注册中心转发。

### 5.2 常见注册中心对比

| 对比项 | ZooKeeper | Nacos | Etcd |
|--------|-----------|-------|------|
| 一致性协议 | ZAB（CP） | 服务注册走 AP（Raft 用于配置） | Raft（CP） |
| 数据模型 | 树形 ZNode | 服务/实例模型 + 配置 | KV |
| 变更通知 | Watch | UDP/HTTP 推送 + 长轮询 | Watch |
| 临时节点 | 支持，会话断开自动删 | 临时实例心跳保活 | Lease 租约 |
| 额外能力 | 仅协调 | 配置中心 + 服务发现一体 | 简单 KV 与选主 |
| 典型搭配 | 老版 Dubbo | Spring Cloud Alibaba / Dubbo | Kubernetes |

### 5.3 注册中心挂了怎么办？

**面试高频：**

> 注册中心挂了不影响存量调用。Consumer 本地缓存了提供者列表，只要列表里的实例还活着，RPC 调用可以正常进行；受影响的是新实例无法注册、新 Consumer 无法首次拉取列表、列表变更无法推送。这体现了"注册中心只负责寻址、不参与数据面转发"的设计，注册中心的可用性要求低于数据链路。

### 5.4 临时节点与心跳检测

- **ZooKeeper**：Provider 创建临时节点，与 ZK 的会话断开（宕机、网络分区超时）后节点自动删除，Consumer 收到 Watch 通知剔除。
- **Nacos**：临时实例由 Provider 定期发心跳，超过阈值未心跳被标记不健康/剔除；持久实例则由 Nacos 主动探测。

> 追问：网络抖动导致 ZK 会话超时会怎样？Provider 节点被误删，Consumer 列表抖动，可能出现瞬时无提供者。缓解方式：Consumer 列表缓存降级策略、ZK 会话超时时间调大、Provider 断线重连后重新注册。

---

## 六、负载均衡原理

### 6.1 常见负载均衡策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| 随机（Random） | 按权重随机选择 | 默认策略，列表较均匀时 |
| 轮询（RoundRobin） | 按权重依次轮询 | 各机器性能接近 |
| 最少活跃（LeastActive） | 选当前处理中请求数最少的 | 机器性能不均、请求耗时不均 |
| 一致性 Hash（ConsistentHash） | 相同参数的请求落到同一台 | 需要会话/缓存亲和性场景 |

### 6.2 最少活跃数为什么更合理？

每个 Provider 维护"活跃调用数"：请求进来 +1，完成 -1。选择活跃数最小的机器。

- 响应慢的机器活跃数自然累积变高，新请求就少发给它。
- 相当于一种基于实时状态的自适应调度，比静态随机/轮询更聪明。

### 6.3 一致性 Hash 解决什么问题？

普通取模 Hash 在节点增减时会导致大量映射失效（缓存雪崩式 miss）。一致性 Hash 把节点和请求都映射到一个 Hash 环上，请求顺时针找最近节点：

```text
                  ┌── NodeA
        Hash 环 ──┤         (请求 key 顺时针落到最近节点)
                  └── NodeB   NodeC
```

- 节点增减只影响环上相邻一小段，大部分映射不变。
- 引入虚拟节点解决数据倾斜：一个物理节点映射为环上多个虚拟点。

**RPC 场景用途**：同一用户的请求路由到同一台机器，命中本地缓存；典型如带状态的会话服务。

---

## 七、RPC 调用方式

### 7.1 四种调用模式

| 模式 | 说明 | 使用场景 |
|------|------|----------|
| 同步调用 | 发出请求后阻塞等结果 | 最常见，业务流程强依赖返回值 |
| Future 异步 | 发出后立即返回 Future，之后 `get()` 取结果 | 并行调用多个无依赖服务聚合结果 |
| Callback 回调 | 注册回调，响应到达后异步通知 | 不关心即时结果、事件驱动 |
| 单向（Oneway） | 只发不收，不等待任何响应 | 日志上报、埋点等允许丢失场景 |

### 7.2 异步并行聚合示例

```java
// 串行：总耗时 = A + B + C
UserDTO user = userService.getUser(userId);
OrderDTO order = orderService.getOrder(userId);
CreditDTO credit = creditService.getCredit(userId);

// 异步并行：总耗时 ≈ max(A, B, C)
Future<UserDTO> userFuture = RpcContext.getContext().asyncCall(
        () -> userService.getUser(userId));
Future<OrderDTO> orderFuture = RpcContext.getContext().asyncCall(
        () -> orderService.getOrder(userId));
Future<CreditDTO> creditFuture = RpcContext.getContext().asyncCall(
        () -> creditService.getCredit(userId));

UserDTO user = userFuture.get();
OrderDTO order = orderFuture.get();
CreditDTO credit = creditFuture.get();
```

### 7.3 同步调用的底层实现：Future + 阻塞等待

```java
public Object send(RpcRequest request, long timeout) throws Exception {
    DefaultFuture future = new DefaultFuture(request);
    pending.put(request.getId(), future);
    channel.writeAndFlush(request);          // Netty 异步发送
    return future.get(timeout, MILLISECONDS); // 业务线程阻塞等待，超时抛出
}
```

**面试回答：**

> RPC 同步调用本质是"异步 IO + 阻塞等待"：Netty 发送本身是异步的，框架发送前把 requestId 和一个 Future 放入 pending 映射，业务线程在 Future 上带超时地 await；IO 线程收到响应后按 requestId 找到 Future 填入结果并 countDown 唤醒业务线程。超时控制和异步转同步都在这一层完成。

---

## 八、高频面试速记

1. **代理方式**：JDK 动态代理（需接口）/ Javassist 字节码（Dubbo 默认，更快）。
2. **网络模型**：NIO + epoll 多路复用，Netty Reactor 模型，Boss 接连接、Worker 管 IO、业务线程池跑逻辑。
3. **多路复用关键**：协议里的 requestId 关联请求响应。
4. **序列化选型**：跨语言 Protobuf、纯 Java 高性能 Kryo、Dubbo 默认 Hessian2、可读性 JSON；不用 JDK 序列化因为大、慢、不安全、不跨语言。
5. **粘包拆包**：TCP 流式无边界，用"长度字段"方案解决，Netty 对应 `LengthFieldBasedFrameDecoder`。
6. **协议必备字段**：魔数、序列化类型、requestId、body 长度。
7. **注册中心**：Provider 注册、Consumer 订阅并本地缓存、变更推送；注册中心挂了存量调用不受影响。
8. **负载均衡在客户端**：随机/轮询/最少活跃/一致性 Hash；一致性 Hash 用虚拟节点防倾斜。
9. **调用方式**：同步（异步 IO + Future 阻塞）、Future 异步、Callback、Oneway。
