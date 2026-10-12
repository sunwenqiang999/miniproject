# Redis 面试知识点总结

## 阅读建议

Redis 面试知识点已按复习层次拆分为三类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`redis面试知识点.md`](redis面试知识点.md) | 总览复习 | Redis 基础、数据类型、命令示例、整体知识框架 |
| [`redis原理机制面试知识点.md`](redis原理机制面试知识点.md) | 原理深入 | 单线程模型、IO 多路复用、持久化、过期删除、缓存问题、分布式锁、事务 Lua |
| [`redis高可用与生产实战面试知识点.md`](redis高可用与生产实战面试知识点.md) | 进阶实战 | 主从复制、哨兵、Cluster、大 Key、热 Key、限流、消息队列、一致性、生产实践 |

建议先掌握第二章 Redis 数据类型和命令，再学习原理机制，最后背诵高可用与生产实战的面试回答模板。

## 一、Redis 基础概念

### 1.1 什么是 Redis？

Redis（Remote Dictionary Server）是一个基于内存的高性能 Key-Value 数据库，常用于缓存、分布式锁、计数器、排行榜、限流、消息队列、会话存储等场景。

**核心特点：**
- 基于内存，读写性能高
- 单线程执行命令，避免多线程竞争和锁开销
- 支持丰富的数据结构
- 支持持久化：RDB、AOF、混合持久化
- 支持主从复制、哨兵、Cluster 集群
- 支持 Lua 脚本，保证复杂操作原子性
- 支持过期删除、内存淘汰策略

### 1.2 Redis 为什么快？

Redis 快主要不是因为“单线程”，而是多个因素共同作用：

1. **基于内存操作**：数据主要存储在内存中，避免磁盘随机 IO。
2. **高效数据结构**：底层使用 SDS、Dict、SkipList、ListPack、QuickList 等优化结构。
3. **单线程命令执行**：避免线程切换、锁竞争、死锁等问题。
4. **IO 多路复用**：通过 epoll/kqueue/select 同时处理大量连接。
5. **协议简单**：RESP 协议解析成本低。
6. **大量操作时间复杂度低**：如 String 读写 O(1)、Hash 字段读写平均 O(1)。
7. **渐进式 rehash、惰性删除等优化机制**：避免一次性阻塞过久。

> 注意：Redis 6.0 之后引入了多线程 IO，但命令执行仍然主要是单线程。多线程主要用于网络数据读写和协议解析。

### 1.3 Redis 常见使用场景

| 场景 | 使用方式 | 常用命令/结构 |
|------|----------|---------------|
| 缓存 | 缓存热点数据，降低 DB 压力 | String、Hash |
| 分布式锁 | 控制分布式环境下的并发访问 | SET NX EX、Lua |
| 计数器 | 浏览量、点赞数、库存扣减 | INCR、DECR |
| 排行榜 | 分数排名、热门榜单 | ZSet |
| 限流 | 固定窗口、滑动窗口、令牌桶 | String、ZSet、Lua |
| 会话存储 | 存储用户登录态 | String、Hash |
| 延迟队列 | 通过时间戳排序延迟执行 | ZSet |
| 消息队列 | 简单队列或消费组 | List、Stream |
| 布隆过滤器 | 防缓存穿透 | RedisBloom 模块 |
| 地理位置 | 附近的人、门店距离 | GEO |

### 1.4 Redis 与 Memcached 对比

| 对比项 | Redis | Memcached |
|--------|-------|-----------|
| 数据结构 | String、Hash、List、Set、ZSet、Stream 等 | 只支持简单 Key-Value |
| 持久化 | 支持 RDB/AOF | 不支持持久化 |
| 高可用 | 主从、哨兵、Cluster | 依赖客户端分片 |
| 分布式锁 | 支持原子命令和 Lua | 支持较弱 |
| 内存管理 | 支持多种淘汰策略 | LRU 为主 |
| 性能 | 很高 | 很高，简单 KV 场景也很快 |
| 适用场景 | 缓存 + 数据结构服务 | 纯缓存 |

---

## 二、Redis 数据类型与应用场景

> 面试重点：Redis 2.0 及以前最核心的数据类型主要是 String、Hash、List、Set、ZSet。后续版本新增或增强的 Bitmap、HyperLogLog、GEO、Stream 等可以放到进阶文档中了解，总文档只保留最常见、最基础、面试最高频的命令。

### 2.1 String

String 是 Redis 最基础的数据类型，可以存储字符串、整数、JSON 字符串、二进制数据，单个 Value 最大 512MB。

#### 2.1.1 常用命令

```bash
SET key value
GET key
MSET k1 v1 k2 v2
MGET k1 k2
INCR counter
DECR counter
INCRBY counter 10
DECRBY counter 5
SETEX key seconds value
SETNX key value
```

#### 2.1.2 SET / GET：设置和读取字符串

```bash
SET user:1:name zhangsan
GET user:1:name
```

**含义：**
- `SET user:1:name zhangsan`：把 `user:1:name` 的值设置为 `zhangsan`。
- `GET user:1:name`：读取 `user:1:name` 对应的值。

**返回示例：**

```bash
"zhangsan"
```

**适用场景：**
- 缓存单个字段
- 缓存 JSON 字符串
- 存储 Token、Session、验证码

#### 2.1.3 MSET / MGET：批量设置和批量读取

```bash
MSET user:1:name zhangsan user:1:age 18 user:1:city shanghai
MGET user:1:name user:1:age user:1:city
```

**含义：**
- `MSET`：一次设置多个 Key。
- `MGET`：一次读取多个 Key。

**返回示例：**

```bash
1) "zhangsan"
2) "18"
3) "shanghai"
```

**适用场景：** 页面聚合数据读取，减少多次网络请求。

#### 2.1.4 INCR / DECR：整数自增自减

```bash
SET article:1001:view 10
INCR article:1001:view
DECR article:1001:view
```

**含义：**
- `INCR article:1001:view`：将 Value 当作整数并加 1。
- `DECR article:1001:view`：将 Value 当作整数并减 1。

**返回示例：**

```bash
INCR article:1001:view   # 返回 11
DECR article:1001:view   # 返回 10
```

**适用场景：**
- 浏览量
- 点赞数
- 库存扣减
- 接口访问次数
- 固定窗口限流

#### 2.1.5 String 类型加减时，Redis 怎么知道 Value 是整数？

Redis 的 String 本质上存的是字节数组，不会在写入时强制声明“这是整数类型”。当执行 `INCR`、`DECR`、`INCRBY`、`DECRBY` 这类命令时，Redis 会在命令执行阶段尝试把当前 Value 解析成整数。

**情况一：Value 是整数格式字符串，可以加减。**

```bash
SET counter 100
INCR counter
```

返回：

```bash
101
```

虽然 `100` 存进去是 String，但它的内容符合整数格式，所以 Redis 可以把它解析为整数。

**情况二：Key 不存在，Redis 会当作 0 再执行。**

```bash
DEL counter
INCR counter
```

返回：

```bash
1
```

等价于先把 `counter` 当作 `0`，再加 1。

**情况三：Value 不是整数格式，会报错。**

```bash
SET counter abc
INCR counter
```

返回：

```bash
(error) ERR value is not an integer or out of range
```

**面试回答：**

> Redis String 不需要提前声明整数类型。执行 `INCR`、`DECR` 等命令时，Redis 会尝试把 Value 按整数解析；如果 Key 不存在就按 0 处理；如果 Value 不是合法整数，就返回 `ERR value is not an integer or out of range`。所以能不能加减取决于 Value 的内容是否符合整数格式，而不是取决于 Redis 是否提前记录了类型。

#### 2.1.6 INCRBY / DECRBY：指定步长加减

```bash
SET stock:sku1001 100
DECRBY stock:sku1001 3
INCRBY stock:sku1001 10
```

**含义：**
- `DECRBY stock:sku1001 3`：库存减 3。
- `INCRBY stock:sku1001 10`：库存加 10。

**适用场景：** 商品库存、用户积分、计数器。

#### 2.1.7 SETEX：设置值并指定过期时间

```bash
SETEX verify:code:phone:13800000000 300 9527
```

**含义：** 设置短信验证码为 `9527`，300 秒后自动过期。

**适用场景：** 验证码、登录 Token、临时缓存。

#### 2.1.8 SETNX：只在 Key 不存在时设置

```bash
SETNX lock:order:1001 requestId
```

**含义：**
- 如果 `lock:order:1001` 不存在，设置成功。
- 如果已经存在，设置失败。

**适用场景：** 分布式锁基础、防重复提交、幂等控制。

> 注意：生产环境实现分布式锁时，还需要保证加锁和设置过期时间的原子性，详细内容放在 [`redis原理机制面试知识点.md`](redis原理机制面试知识点.md) 中学习。

### 2.2 Hash

Hash 适合存储对象结构，例如用户信息、商品信息。可以把一个对象的多个字段放在同一个 Redis Key 下。

#### 2.2.1 常用命令

```bash
HSET user:1 name zhangsan age 18 score 90
HGET user:1 name
HMGET user:1 name age
HGETALL user:1
HINCRBY user:1 score 10
HDEL user:1 age
HEXISTS user:1 name
HLEN user:1
```

#### 2.2.2 HSET：设置 Hash 字段

```bash
HSET user:1 name zhangsan age 18 score 90
```

**含义：**
- `user:1` 是 Redis Key。
- `name`、`age`、`score` 是 Hash 内部字段。
- `zhangsan`、`18`、`90` 是字段对应的值。

可以理解为存了一个对象：

```json
{
  "name": "zhangsan",
  "age": "18",
  "score": "90"
}
```

**适用场景：** 用户信息缓存、商品详情缓存、购物车条目。

#### 2.2.3 HGET / HMGET：读取字段

```bash
HGET user:1 name
HMGET user:1 name age
```

**含义：**
- `HGET user:1 name`：读取 `user:1` 中的 `name` 字段。
- `HMGET user:1 name age`：一次读取多个字段。

**返回示例：**

```bash
HGET user:1 name
# "zhangsan"

HMGET user:1 name age
# 1) "zhangsan"
# 2) "18"
```

#### 2.2.4 HGETALL user:1 是什么意思？

```bash
HGETALL user:1
```

**含义：** 获取 `user:1` 这个 Hash 中的所有字段和值。

假设先执行：

```bash
HSET user:1 name zhangsan age 18 score 90
```

返回可能是：

```bash
1) "name"
2) "zhangsan"
3) "age"
4) "18"
5) "score"
6) "90"
```

**适用场景：** 需要完整用户对象、完整商品对象。

**注意：** 如果 Hash 字段很多，`HGETALL` 会一次性返回所有字段，可能造成阻塞或网络大包。

#### 2.2.5 HINCRBY user:1 score 10 是什么意思？

```bash
HINCRBY user:1 score 10
```

**含义：** 将 `user:1` 这个 Hash 中的 `score` 字段按整数加 10。

如果原数据是：

```bash
HSET user:1 score 90
```

执行后：

```bash
HGET user:1 score
# "100"
```

**适用场景：** 用户积分增加、商品销量增加、购物车商品数量增加。

**注意：** `score` 字段的值必须能被解析成整数，否则会报错。

#### 2.2.6 HDEL / HEXISTS / HLEN

```bash
HDEL user:1 age
HEXISTS user:1 name
HLEN user:1
```

**含义：**
- `HDEL user:1 age`：删除 `age` 字段。
- `HEXISTS user:1 name`：判断 `name` 字段是否存在。
- `HLEN user:1`：统计 Hash 中字段数量。

#### 2.2.7 String JSON vs Hash

| 方式 | 优点 | 缺点 |
|------|------|------|
| String + JSON | 结构简单，序列化方便，适合整体读写 | 更新单个字段需要读写整个对象 |
| Hash | 可单独更新字段，节省部分网络传输 | 字段较多时管理复杂，大 Hash 有阻塞风险 |

### 2.3 List

List 是有序列表结构，可以从左右两端插入和弹出元素，适合队列、栈、最新列表等场景。

#### 2.3.1 常用命令

```bash
LPUSH queue a
RPUSH queue b
LPOP queue
RPOP queue
BRPOP queue 5
LRANGE list 0 9
LLEN list
LTRIM list 0 99
```

#### 2.3.2 LPUSH / RPUSH：从左侧或右侧插入

```bash
LPUSH queue task1
LPUSH queue task2
RPUSH queue task3
```

**含义：**
- `LPUSH`：从列表左侧插入。
- `RPUSH`：从列表右侧插入。

执行后列表大致为：

```text
task2, task1, task3
```

#### 2.3.3 LPOP / RPOP：从左侧或右侧弹出

```bash
LPOP queue
RPOP queue
```

**含义：**
- `LPOP queue`：从左侧弹出一个元素。
- `RPOP queue`：从右侧弹出一个元素。

**适用场景：**
- `LPUSH + RPOP`：先进先出队列。
- `LPUSH + LPOP`：后进先出栈。

#### 2.3.4 BRPOP queue 5 是什么意思？

```bash
BRPOP queue 5
```

**含义：**
- `B` 表示 blocking，阻塞式。
- `RPOP` 表示从右侧弹出。
- `queue` 是列表 Key。
- `5` 表示最多阻塞等待 5 秒。

如果 `queue` 中有数据，会立即从右侧弹出；如果没有数据，客户端最多等待 5 秒。

**返回示例：**

```bash
1) "queue"
2) "task1"
```

**适用场景：** 简单任务队列消费者，避免消费者一直空轮询 Redis。

#### 2.3.5 LRANGE list 0 9 是什么意思？

```bash
LRANGE list 0 9
```

**含义：** 获取 `list` 这个列表中下标从 0 到 9 的元素，包含 0 和 9，一共最多 10 个元素。

Redis List 下标规则：
- `0` 表示第一个元素。
- `1` 表示第二个元素。
- `-1` 表示最后一个元素。
- `-2` 表示倒数第二个元素。

示例：

```bash
RPUSH list a b c d e f g h i j k
LRANGE list 0 9
```

返回：

```bash
1) "a"
2) "b"
3) "c"
4) "d"
5) "e"
6) "f"
7) "g"
8) "h"
9) "i"
10) "j"
```

**适用场景：** 分页获取最新动态、消息列表、评论列表。

**注意：** 不建议对大 List 使用 `LRANGE list 0 -1`，可能一次性返回大量数据。

#### 2.3.6 LLEN / LTRIM

```bash
LLEN list
LTRIM list 0 99
```

**含义：**
- `LLEN list`：获取列表长度。
- `LTRIM list 0 99`：只保留列表前 100 个元素，其他删除。

### 2.4 Set

Set 是无序去重集合，适合存储“不重复”的元素。

#### 2.4.1 常用命令

```bash
SADD tags java redis mysql
SISMEMBER tags redis
SMEMBERS tags
SINTER set1 set2
SUNION set1 set2
SDIFF set1 set2
SREM tags mysql
SCARD tags
```

#### 2.4.2 SADD tags java redis mysql 是什么意思？

```bash
SADD tags java redis mysql
```

**含义：** 向 `tags` 这个 Set 集合中添加 `java`、`redis`、`mysql` 三个元素。如果元素已经存在，不会重复添加。

**返回示例：**

```bash
3
```

**适用场景：** 文章标签、用户兴趣标签、黑白名单、抽奖去重。

#### 2.4.3 SISMEMBER tags redis 是什么意思？

```bash
SISMEMBER tags redis
```

**含义：** 判断 `redis` 是否在 `tags` 这个集合中。

**返回示例：**

```bash
1
```

返回 `1` 表示存在，返回 `0` 表示不存在。

#### 2.4.4 SMEMBERS tags 是什么意思？

```bash
SMEMBERS tags
```

**含义：** 返回 `tags` 集合中的所有元素。

**返回示例：**

```bash
1) "java"
2) "redis"
3) "mysql"
```

**注意：** `SMEMBERS` 会一次性返回整个集合，大 Set 不建议使用。

#### 2.4.5 SINTER / SUNION / SDIFF：交并差集

```bash
SADD user:1:follows user2 user3 user4
SADD user:2:follows user3 user4 user5

SINTER user:1:follows user:2:follows
SUNION user:1:follows user:2:follows
SDIFF user:1:follows user:2:follows
```

**含义：**
- `SINTER`：交集，共同关注。
- `SUNION`：并集，两个集合全部成员去重合并。
- `SDIFF`：差集，存在于第一个集合但不存在于第二个集合的成员。

**返回示例：**

```bash
SINTER -> user3, user4
SUNION -> user2, user3, user4, user5
SDIFF  -> user2
```

#### 2.4.6 SREM / SCARD

```bash
SREM tags mysql
SCARD tags
```

**含义：**
- `SREM tags mysql`：从集合中删除 `mysql`。
- `SCARD tags`：获取集合元素数量。

### 2.5 ZSet

ZSet 是有序集合，每个元素关联一个 score，根据 score 排序。元素唯一，但 score 可以重复。

#### 2.5.1 常用命令

```bash
ZADD rank 100 user1 90 user2
ZRANGE rank 0 9 WITHSCORES
ZREVRANGE rank 0 9 WITHSCORES
ZRANK rank user1
ZSCORE rank user1
ZINCRBY rank 10 user1
ZRANGEBYSCORE rank 80 100
ZREM rank user2
ZCARD rank
```

#### 2.5.2 ZADD rank 100 user1 90 user2 是什么意思？

```bash
ZADD rank 100 user1 90 user2
```

**含义：** 向 `rank` 这个有序集合中添加：
- `user1`，分数 `100`
- `user2`，分数 `90`

ZSet 会按照 score 排序。

**适用场景：** 排行榜、热搜榜、权重排序。

#### 2.5.3 ZRANGE rank 0 9 WITHSCORES 是什么意思？

```bash
ZRANGE rank 0 9 WITHSCORES
```

**含义：** 按 score 从小到大，获取排名下标 0 到 9 的成员，并携带分数。

示例：

```bash
ZADD rank 100 user1 90 user2 120 user3
ZRANGE rank 0 9 WITHSCORES
```

返回：

```bash
1) "user2"
2) "90"
3) "user1"
4) "100"
5) "user3"
6) "120"
```

#### 2.5.4 ZREVRANGE rank 0 9 WITHSCORES 是什么意思？

```bash
ZREVRANGE rank 0 9 WITHSCORES
```

**含义：** 按 score 从大到小，获取排名下标 0 到 9 的成员，并携带分数。

**适用场景：** 游戏积分榜 Top 10、热搜榜 Top 10、销量榜 Top 10。

#### 2.5.5 ZRANK rank user1 是什么意思？

```bash
ZRANK rank user1
```

**含义：** 查询 `user1` 在 `rank` 中的正序排名，下标从 0 开始，score 越小排名越靠前。

如果数据为：

```text
user2: 90
user1: 100
user3: 120
```

则返回：

```bash
1
```

表示 `user1` 正序排名第 2 名。

#### 2.5.6 ZINCRBY rank 10 user1 是什么意思？

```bash
ZINCRBY rank 10 user1
```

**含义：** 给 `rank` 中的 `user1` 分数增加 10。

如果原来：

```text
user1: 100
```

执行后：

```text
user1: 110
```

**适用场景：** 用户积分增加、文章热度增加、商品销量增加。

#### 2.5.7 ZRANGEBYSCORE rank 80 100 是什么意思？

```bash
ZRANGEBYSCORE rank 80 100
```

**含义：** 查询 `rank` 中 score 在 `[80, 100]` 范围内的成员，默认按 score 从小到大返回。

示例：

```bash
ZADD rank 100 user1 90 user2 120 user3 70 user4
ZRANGEBYSCORE rank 80 100
```

返回：

```bash
1) "user2"
2) "user1"
```

**适用场景：** 查询某个分数段的用户、按时间戳范围查询任务。

#### 2.5.8 ZSCORE / ZREM / ZCARD

```bash
ZSCORE rank user1
ZREM rank user2
ZCARD rank
```

**含义：**
- `ZSCORE rank user1`：查询 `user1` 的分数。
- `ZREM rank user2`：删除 `user2`。
- `ZCARD rank`：统计有序集合元素数量。

---

## 三、Redis 底层数据结构

> 注意：Redis 的“数据类型”和“底层编码结构”不是一回事。用户看到的是 String、Hash、List、Set、ZSet；Redis 内部会根据数据规模、元素类型、Redis 版本选择不同的底层编码来节省内存和提升性能。

### 3.1 Redis 对象模型：一个 Key-Value 到底怎么存？

Redis 中每个 Value 都会被包装成一个 Redis Object。可以简化理解为：

```c
typedef struct redisObject {
    unsigned type;      // 对外暴露的数据类型：string、hash、list、set、zset
    unsigned encoding;  // 底层编码：int、embstr、raw、ziplist/listpack、hashtable、skiplist 等
    void *ptr;          // 指向真正的数据结构
    int refcount;       // 引用计数
    unsigned lru;       // LRU/LFU 相关信息
} robj;
```

也就是说：

```text
Redis Key -> redisObject -> 具体底层结构
```

例如：

```text
SET name zhangsan
```

逻辑上是一个 String，但内部可能是：

```text
type = string
encoding = embstr 或 raw
ptr = SDS 字符串
```

再例如：

```text
HSET user:1 name zhangsan age 18
```

逻辑上是一个 Hash，但内部可能是：

```text
type = hash
encoding = ziplist/listpack 或 hashtable
ptr = 压缩结构 或 字典结构
```

**面试重点：**

> Redis 的一种数据类型，底层不一定只有一种结构。Redis 会根据元素数量、元素大小、是否为整数等条件选择不同编码，核心目标是在“小数据量时节省内存，大数据量时保证查询性能”。

---

### 3.2 String 底层结构

String 是 Redis 最基础的数据类型，底层主要涉及三种编码：

| 编码 | 使用场景 | 说明 |
|------|----------|------|
| int | 可以表示为整数的字符串 | 直接用整数保存，节省内存 |
| embstr | 较短字符串 | Redis Object 和 SDS 连续分配内存 |
| raw | 较长字符串 | Redis Object 和 SDS 分开分配内存 |

#### 3.2.1 SDS 是什么？

Redis 没有直接使用 C 语言字符串，而是自己实现了 SDS（Simple Dynamic String）。

你图里展示的结构是 Redis 5.0 这类新版本中更准确的 SDS 结构：

```c
struct sdshdr8 {
    uint8_t len;     // 字符串实际使用长度
    uint8_t alloc;   // buf 数组总共分配的空间长度，不包含 header 和结尾的 \0
    unsigned char flags; // SDS 类型，用来区分 sdshdr5、sdshdr8、sdshdr16、sdshdr32、sdshdr64
    char buf[];      // 真正存储字符串内容的字节数组
};
```

也可以抽象成：

```text
SDS = len + alloc + flags + buf[]
```

含义：

| 字段 | 含义 | 作用 |
|------|------|------|
| `len` | 字符串实际长度 | O(1) 获取字符串长度 |
| `alloc` | 已分配的 buf 空间长度 | 判断剩余空间，支持扩容优化 |
| `flags` | SDS header 类型 | 区分当前 SDS 使用哪种长度规格 |
| `buf[]` | 字节数组 | 保存真实字符串内容 |

我前面写的：

```c
struct sdshdr {
    int len;
    int free;
    char buf[];
}
```

是 Redis 3.0 之前或很多资料中为了方便理解给出的老版本/简化版 SDS 表达。其中：

```text
free = alloc - len
```

也就是说，老结构中的 `free` 表示剩余空间；新结构中的 `alloc` 表示总分配空间。两者表达方式不同，但核心目的类似：都用于记录字符串长度和容量，避免像 C 字符串一那样每次都遍历和频繁扩容。

Redis 新版本会根据字符串长度使用不同 header：

| SDS 类型 | 适合长度范围 | 目的 |
|----------|--------------|------|
| `sdshdr5` | 极短字符串 | 进一步节省 header 空间 |
| `sdshdr8` | 较短字符串 | 用 8 位记录长度和容量 |
| `sdshdr16` | 中等字符串 | 用 16 位记录长度和容量 |
| `sdshdr32` | 较长字符串 | 用 32 位记录长度和容量 |
| `sdshdr64` | 超长字符串 | 用 64 位记录长度和容量 |

**结论：**

> 你图里的 `len + alloc + flags + buf[]` 是 Redis 5.0 版本更准确的 SDS 结构；我之前写的 `len + free + buf[]` 是老版本/简化讲法。面试时建议按你图里的新结构回答，同时补一句老版本中 `free` 的含义相当于 `alloc - len`。

#### 3.2.2 SDS 相比 C 字符串有什么优势？

| 对比项 | C 字符串 | SDS |
|--------|----------|-----|
| 获取长度 | 需要遍历，O(n) | 直接读 len，O(1) |
| 二进制安全 | 遇到 `\0` 会结束 | 根据 len 判断长度，可存任意二进制 |
| 拼接安全性 | 容易缓冲区溢出 | 自动扩容，避免溢出 |
| 扩容成本 | 每次可能重新分配 | 有空间预分配策略 |
| 缩容成本 | 频繁释放可能抖动 | 惰性空间释放 |

#### 3.2.3 int 编码

如果 String 的值可以表示为整数，Redis 可能使用 `int` 编码。

```bash
SET counter 100
```

此时 Redis 可以直接把 `100` 当整数保存，而不是保存成普通字符串。

当执行：

```bash
INCR counter
```

Redis 可以快速对整数做加法。

**注意：**
如果后续把它改成非整数，编码会转换为字符串编码。

```bash
SET counter abc
```

#### 3.2.4 embstr 编码

较短字符串通常使用 `embstr` 编码。

特点：
- Redis Object 和 SDS 一次内存分配。
- 内存连续，缓存友好。
- 创建和释放只需要一次内存操作。

适合：短字符串、短 token、小字段值。

#### 3.2.5 raw 编码

较长字符串通常使用 `raw` 编码。

特点：
- Redis Object 和 SDS 分开分配。
- 更适合大字符串扩容。
- 内存管理更灵活。

适合：较长 JSON、大文本、二进制数据。

#### 3.2.6 String 编码转换

```text
整数值 -> int
短字符串 -> embstr
长字符串 -> raw
```

当数据发生变化时，Redis 可能自动转换编码。例如：

```bash
SET k 100       # 可能是 int
APPEND k abc    # 变成字符串，可能转为 raw
```

#### 3.2.7 String 高频面试题

**问题 1：Redis String 为什么不用 C 字符串？**

答：因为 C 字符串获取长度是 O(n)，且不是二进制安全，拼接时容易缓冲区溢出。Redis 使用 SDS，可以 O(1) 获取长度，支持二进制安全，并通过空间预分配和惰性释放减少内存重分配。

**问题 2：String 的 int、embstr、raw 有什么区别？**

答：`int` 用于能表示为整数的字符串；`embstr` 用于短字符串，Redis Object 和 SDS 连续分配；`raw` 用于长字符串，Object 和 SDS 分开分配。三者的区别主要在内存布局和使用场景。

**问题 3：String 最大能存多大？**

答：单个 String 最大可以存 512MB，但实际生产不建议存很大的 Value，否则会造成网络传输慢、阻塞、复制慢、删除慢等大 Key 问题。

---

### 3.3 Hash 底层结构

Hash 用于存储对象字段，底层常见两种编码：

| 编码 | 使用场景 | 特点 |
|------|----------|------|
| ziplist / listpack | 小 Hash | 内存紧凑，节省空间 |
| hashtable | 大 Hash | 查询、更新平均 O(1) |

> 早期 Redis 使用 ziplist，新版本逐渐使用 listpack 替代 ziplist。面试中说“小 Hash 用压缩列表/紧凑列表，大 Hash 用哈希表”即可。

#### 3.3.1 小 Hash：ziplist/listpack

当 Hash 字段数量较少、字段和值都比较短时，Redis 会使用紧凑结构存储。

逻辑结构：

```text
HSET user:1 name zhangsan age 18
```

底层可以近似理解为连续内存：

```text
[name][zhangsan][age][18]
```

也就是说，field 和 value 是挨着存放的。

**优点：**
- 内存连续，节省指针开销。
- 小对象非常省内存。
- 适合字段少、字段短的对象。

**缺点：**
- 查找字段需要遍历，时间复杂度接近 O(n)。
- 数据量大时性能下降。
- 插入、删除可能触发内存移动。

#### 3.3.2 大 Hash：hashtable

当 Hash 字段数量变多，或 field/value 变长时，Redis 会转成 hashtable。

结构可以理解为：

```text
user:1
  ├── name  -> zhangsan
  ├── age   -> 18
  └── score -> 90
```

底层是字典结构：

```text
hash table
  bucket[0] -> entry -> field/value
  bucket[1] -> entry -> field/value
  bucket[2] -> entry -> field/value
```

**优点：**
- 查询、插入、删除平均 O(1)。
- 适合字段很多的对象。

**缺点：**
- 额外指针、哈希表桶数组带来更多内存开销。
- 可能发生哈希冲突。
- 扩容时需要 rehash。

#### 3.3.3 hashtable 和 rehash 的关系

Hash 转成 hashtable 后，本质上用的是 Redis Dict。Dict 在扩容或缩容时会触发渐进式 rehash。

这里先记住一句话：**Hash 里的 rehash 不是单独的一套机制，而是 Dict 通用 rehash 机制在 Hash 类型中的具体应用。**

完整 rehash 流程统一放到文档末尾的「Dict 字典与渐进式 rehash」中讲解。

#### 3.3.4 Hash 什么时候从紧凑结构转 hashtable？

Redis 会根据配置阈值判断，例如老版本常见配置：

```bash
hash-max-ziplist-entries 512
hash-max-ziplist-value 64
```

含义：
- Hash 元素数量不超过 512。
- 每个 field/value 长度不超过 64 字节。

满足条件时使用 ziplist/listpack；超过阈值就转为 hashtable。

> 不同 Redis 版本配置名和底层结构可能有所变化，面试重点不是死记配置名，而是理解“数据小用紧凑结构，数据大用哈希表”。

#### 3.3.5 Hash 高频面试题

**问题 1：Hash 底层结构是什么？**

答：小 Hash 使用 ziplist/listpack 这类紧凑结构，节省内存；当字段数量或字段长度超过阈值后，会转换成 hashtable，保证查询和更新性能。

**问题 2：为什么小 Hash 不直接用 hashtable？**

答：因为 hashtable 有桶数组、entry、指针等额外开销。小对象字段少，用紧凑结构虽然查找是 O(n)，但 n 很小，实际更省内存，性能也足够。

**问题 3：Hash 适合缓存对象吗？**

答：适合字段需要单独读写的对象，例如用户资料、商品信息。如果对象总是整体读写，用 String + JSON 更简单；如果字段很多且经常 HGETALL，则要注意大 Key 风险。

---

### 3.4 List 底层结构

List 是有序列表，支持从两端插入和弹出。

Redis List 底层结构随版本变化较大：

| Redis 版本/阶段 | 底层结构 | 说明 |
|-----------------|----------|------|
| 早期 | linkedlist 或 ziplist | 元素多用链表，元素少用压缩列表 |
| Redis 3.2 之后 | quicklist | 双向链表 + 压缩列表 |
| 新版本 | quicklist + listpack | 用 listpack 替代 ziplist 的紧凑节点 |

#### 3.4.1 linkedlist 为什么不够好？

普通双向链表结构：

```text
prev <- node1 <-> node2 <-> node3 -> next
```

**优点：**
- 两端插入、删除 O(1)。
- 不需要连续内存。

**缺点：**
- 每个节点都有前后指针，内存开销大。
- 每个节点单独分配内存，内存碎片多。
- 缓存局部性差，CPU 访问不友好。

#### 3.4.2 ziplist/listpack 为什么也不够好？

压缩列表/listpack 是连续内存结构：

```text
[element1][element2][element3][element4]
```

**优点：**
- 内存连续。
- 节省指针开销。
- 小列表非常省内存。

**缺点：**
- 中间插入删除需要移动内存。
- 数据很大时扩容成本高。
- 早期 ziplist 可能有连锁更新问题。

#### 3.4.3 quicklist 是什么？

quicklist 可以理解为：**双向链表 + 每个节点内部存一个紧凑列表**。

结构如下：

```text
quicklist
  ┌────────────┐    ┌────────────┐    ┌────────────┐
  │ listpack   │ -> │ listpack   │ -> │ listpack   │
  │ a,b,c      │    │ d,e,f      │    │ g,h,i      │
  └────────────┘    └────────────┘    └────────────┘
```

它结合了两者优点：
- 外层是双向链表，两端插入删除快。
- 内层是紧凑连续结构，节省内存。
- 每个节点不会太大，避免大块连续内存移动。

#### 3.4.4 List 操作复杂度

| 操作 | 复杂度 | 说明 |
|------|--------|------|
| LPUSH/RPUSH | O(1) | 两端插入 |
| LPOP/RPOP | O(1) | 两端弹出 |
| LINDEX | O(n) | 按下标查找需要遍历 |
| LRANGE | O(s+n) | 找起点 + 返回 n 个元素 |
| LTRIM | O(n) | 需要裁剪元素 |

#### 3.4.5 List 高频面试题

**问题 1：Redis List 底层结构是什么？**

答：早期 List 底层可能是 ziplist 或 linkedlist；Redis 3.2 后主要使用 quicklist。quicklist 是双向链表加压缩列表/listpack 的组合，兼顾两端操作性能和内存利用率。

**问题 2：为什么 Redis List 不直接用普通链表？**

答：普通链表每个节点都有前后指针，内存开销大，内存碎片多，缓存局部性差。quicklist 把多个元素放在一个紧凑节点里，可以显著减少指针开销。

**问题 3：为什么 `LRANGE list 0 -1` 有风险？**

答：它会一次性读取整个列表。如果 List 很大，会造成 Redis 主线程阻塞和网络大包，影响其他请求。

---

### 3.5 Set 底层结构

Set 是无序去重集合，底层常见两种编码：

| 编码 | 使用场景 | 特点 |
|------|----------|------|
| intset | 集合元素都是整数且数量较少 | 内存紧凑 |
| hashtable | 元素不是纯整数或数量较多 | 查询平均 O(1) |

#### 3.5.1 intset 是什么？

intset 是整数集合，适合存储少量整数元素。

例如：

```bash
SADD nums 1 2 3 4
```

底层可以用 intset 保存：

```text
[1][2][3][4]
```

intset 内部是有序数组，元素按从小到大排列。

**优点：**
- 连续内存，节省空间。
- 适合小规模整数集合。

**缺点：**
- 插入时需要保持有序，可能移动元素。
- 查找通常是二分查找，复杂度 O(logN)。
- 元素很多时性能不如 hashtable。

#### 3.5.2 intset 编码升级

intset 会根据元素大小自动升级编码。

例如一开始存的是 16 位整数：

```text
[1][2][3]
```

如果插入一个很大的整数：

```bash
SADD nums 10000000000
```

intset 可能需要升级为 64 位编码。

**特点：**
- 升级是单向的，只升级不降级。
- 升级后所有元素都会用更大的整数宽度保存。

#### 3.5.3 hashtable 编码

当 Set 元素不是整数，或者元素数量超过阈值时，会转为 hashtable。

例如：

```bash
SADD tags java redis mysql
```

底层类似：

```text
hash table
  java  -> null
  redis -> null
  mysql -> null
```

Set 只关心元素是否存在，所以 value 通常可以理解为空。

**优点：**
- 添加、删除、判断成员是否存在平均 O(1)。
- 适合大集合和字符串集合。

**缺点：**
- 比 intset 更耗内存。
- 可能有哈希冲突和 rehash 开销。

#### 3.5.4 Set 高频面试题

**问题 1：Set 底层结构是什么？**

答：如果 Set 中全是整数且数量较少，会使用 intset；如果元素不是整数或元素数量较多，会转成 hashtable。

**问题 2：为什么 Set 判断元素是否存在很快？**

答：大多数普通 Set 会使用 hashtable，`SISMEMBER` 可以通过哈希定位元素，平均时间复杂度 O(1)。

**问题 3：Set 的交集、并集、差集有什么性能风险？**

答：集合运算需要遍历集合，如果集合很大，会阻塞 Redis 主线程。生产中要避免对超大 Set 做 `SINTER`、`SUNION`、`SDIFF`。

---

### 3.6 ZSet 底层结构

ZSet 是有序集合，每个元素有一个 score，元素唯一，score 可以重复。

底层常见两种编码：

| 编码 | 使用场景 | 特点 |
|------|----------|------|
| ziplist/listpack | 元素少且 member 较短 | 节省内存 |
| skiplist + dict | 元素多或 member 较长 | 查询、排序、范围查找性能好 |

#### 3.6.1 小 ZSet：ziplist/listpack

小 ZSet 使用紧凑结构时，member 和 score 会紧挨着存放：

```text
[user2][90][user1][100][user3][120]
```

通常会按 score 排序。

**优点：**
- 内存占用低。
- 小集合查询性能足够。

**缺点：**
- 查找、插入、删除需要遍历和移动数据。
- 数据量大时性能明显下降。

#### 3.6.2 大 ZSet：skiplist + dict

当 ZSet 元素较多或 member 较长时，会使用 `skiplist + dict`。

它同时维护两套结构：

```text
ZSet
 ├── dict：member -> score
 └── skiplist：按照 score 排序
```

为什么需要两套结构？

| 结构 | 作用 |
|------|------|
| dict | 根据 member 快速查 score，支持 O(1) 查找 |
| skiplist | 根据 score 排序，支持范围查询和排名 |

例如：

```bash
ZADD rank 100 user1 90 user2 120 user3
```

内部大致是：

```text
dict:
  user1 -> 100
  user2 -> 90
  user3 -> 120

skiplist:
  user2(90) -> user1(100) -> user3(120)
```

#### 3.6.3 跳表 SkipList 是什么？

跳表是多层有序链表。

普通有序链表查找：

```text
1 -> 3 -> 5 -> 7 -> 9 -> 11 -> 13
```

要找 11 可能要一个个遍历。

跳表增加了多级索引：

```text
Level 3: 1 ----------------> 9
Level 2: 1 --------> 5 ----> 9 ----> 13
Level 1: 1 -> 3 -> 5 -> 7 -> 9 -> 11 -> 13
```

查找时从高层开始跳跃，接近目标后再下沉到底层，所以平均查找复杂度是 O(logN)。

#### 3.6.4 为什么 Redis ZSet 用跳表而不是红黑树？

常见原因：

1. **范围查询实现简单**：找到起点后，沿底层链表往后遍历即可。
2. **实现比红黑树简单**：代码更容易维护。
3. **性能足够好**：平均 O(logN)。
4. **天然有序遍历方便**：非常适合 `ZRANGE`、`ZRANGEBYSCORE`。
5. **插入删除局部调整即可**：不需要复杂旋转。

#### 3.6.5 ZSet 操作复杂度

| 操作 | 复杂度 | 原因 |
|------|--------|------|
| ZADD | O(logN) | 插入跳表并更新 dict |
| ZREM | O(logN) | 从跳表删除并更新 dict |
| ZSCORE | O(1) | 直接查 dict |
| ZRANK | O(logN) | 通过跳表跨度计算排名 |
| ZRANGE | O(logN + M) | 定位起点 + 返回 M 个元素 |
| ZRANGEBYSCORE | O(logN + M) | 定位 score 起点 + 返回 M 个元素 |
| ZINCRBY | O(logN) | 更新 score 后调整跳表位置 |

#### 3.6.6 ZSet 高频面试题

**问题 1：ZSet 底层结构是什么？**

答：小 ZSet 使用 ziplist/listpack，节省内存；大 ZSet 使用 dict + skiplist。dict 用于根据 member 快速查 score，skiplist 用于按 score 排序、范围查询和排名计算。

**问题 2：为什么 ZSet 同时需要 dict 和 skiplist？**

答：如果只有 skiplist，根据 member 查 score 不方便；如果只有 dict，无法按 score 排序和范围查询。dict 负责 O(1) 查 member，skiplist 负责 O(logN) 排名和范围查询。

**问题 3：为什么不用红黑树？**

答：跳表实现简单，范围查询方便，性能平均 O(logN)，非常适合 Redis 的有序集合场景。红黑树虽然也能做有序查询，但实现复杂，范围遍历没有跳表直观。

---

### 3.7 各数据类型底层结构总结

| Redis 类型 | 小数据量底层结构 | 大数据量底层结构 | 典型命令 |
|------------|------------------|------------------|----------|
| String | int / embstr | raw | SET、GET、INCR |
| Hash | ziplist/listpack | hashtable | HSET、HGET、HGETALL |
| List | ziplist/listpack / quicklist 节点 | quicklist | LPUSH、RPOP、LRANGE |
| Set | intset | hashtable | SADD、SISMEMBER、SINTER |
| ZSet | ziplist/listpack | skiplist + dict | ZADD、ZRANGE、ZRANK |

**核心规律：**

```text
数据少、元素短：优先使用紧凑结构，节省内存
数据多、元素长：转换为哈希表、跳表等结构，保证性能
```

---

### 3.8 底层结构高频面试题

#### 3.8.1 Redis 五种基础类型底层分别是什么？

可以这样回答：

> String 底层是 SDS，并根据内容使用 int、embstr、raw 编码；Hash 小对象用 ziplist/listpack，大对象用 hashtable；List 早期是 ziplist 或 linkedlist，新版本主要是 quicklist；Set 小整数集合用 intset，大集合或字符串集合用 hashtable；ZSet 小集合用 ziplist/listpack，大集合用 skiplist + dict。

#### 3.8.2 Redis 为什么大量使用紧凑结构？

因为 Redis 是内存数据库，内存成本非常关键。对于小对象来说，使用 hashtable、linkedlist 会带来大量指针和对象头开销；紧凑结构用连续内存存储，可以显著减少内存占用，并提升缓存局部性。

#### 3.8.3 什么情况下紧凑结构会变成 hashtable 或 skiplist？

当元素数量超过阈值，或者单个元素长度超过阈值时，Redis 会把紧凑结构转换成更适合大数据量的结构。核心原因是小数据量更关注内存，大数据量更关注查询、插入、删除性能。

#### 3.8.4 Redis 为什么需要渐进式 rehash？

Redis 是单线程执行命令，如果一次性 rehash 大量 Key，会阻塞所有请求。渐进式 rehash 把迁移过程拆散到多次增删改查和定时任务中完成，从而降低阻塞时间。

#### 3.8.5 为什么 ZSet 用跳表？

跳表实现简单，范围查询非常方便，平均查找、插入、删除复杂度是 O(logN)。对于排行榜、分数范围查询这类场景，跳表找到起点后可以沿底层链表顺序遍历，效率高且实现简单。

#### 3.8.6 Hash 和 String JSON 缓存对象怎么选？

- 如果对象经常整体读写，优先 String + JSON，简单直接。
- 如果对象字段经常局部更新或局部读取，优先 Hash。
- 如果字段非常多，避免频繁 `HGETALL`，否则可能形成大 Key。

#### 3.8.7 List 为什么用 quicklist？

quicklist 结合了链表和紧凑列表的优点：外层链表保证两端插入删除效率，内层 listpack/ziplist 减少指针开销和内存碎片，因此比普通 linkedlist 更节省内存，比单个巨大 ziplist 更适合大列表。

#### 3.8.8 Set 为什么有 intset？

如果集合中都是少量整数，用 hashtable 会浪费大量指针和桶数组内存。intset 使用连续数组保存整数，更紧凑。等元素变多或出现非整数元素时，再转成 hashtable。

#### 3.8.9 哪些命令可能因为底层结构导致阻塞？

- `HGETALL`：Hash 很大时会一次性返回所有字段。
- `SMEMBERS`：Set 很大时会一次性返回所有成员。
- `LRANGE list 0 -1`：List 很大时会返回整个列表。
- `ZRANGE` 大范围查询：返回元素过多会阻塞。
- 大 Key 删除：释放内存可能阻塞。

#### 3.8.10 面试中如何一句话总结 Redis 底层结构？

可以这样说：

> Redis 对外提供五种基础数据类型，但内部会根据数据规模和元素特征选择不同编码。小数据量时倾向使用 ziplist/listpack、intset 等紧凑结构节省内存；大数据量时转换为 hashtable、quicklist、skiplist + dict 等结构保证查询和更新性能。这也是 Redis 既省内存又高性能的重要原因。

---

## 四、补充：Dict 字典与渐进式 rehash

### 4.1 Dict 是什么？

Dict 是 Redis 内部非常核心的哈希表实现，不只用于 Hash 类型。

Redis 中很多地方都会用到 Dict：

| 使用位置 | 作用 |
|----------|------|
| Redis 全局 Key 空间 | 保存 `key -> value` |
| 大 Hash | 保存 `field -> value` |
| 大 Set | 保存 `member -> null` |
| ZSet | 保存 `member -> score` |
| 过期字典 | 保存 `key -> expireTime` |

所以，Hash 里面提到的 rehash，本质就是 Dict 的 rehash 在 Hash 类型中的具体应用。

### 4.2 Dict 基本结构

Redis Dict 可以简化理解为：

```text
dict
 ├── ht[0]：当前正在使用的哈希表
 ├── ht[1]：rehash 时新建的哈希表
 └── rehashidx：当前 rehash 进度，-1 表示没有进行 rehash
```

哈希表内部通过桶数组保存数据：

```text
hash table
  bucket[0] -> entry -> entry
  bucket[1] -> null
  bucket[2] -> entry
  bucket[3] -> entry -> entry
```

Redis 使用链地址法解决哈希冲突。

### 4.3 为什么需要 rehash？

随着数据越来越多，哈希表中的冲突会越来越多，链表变长后查询性能会下降，所以需要扩容。

当大量数据被删除后，哈希表可能变得很稀疏，浪费内存，所以可能需要缩容。

可以简单理解为：

```text
数据太多 -> 扩容 rehash -> 减少冲突，提高查询性能
数据太少 -> 缩容 rehash -> 减少内存浪费
```

### 4.4 渐进式 rehash 完整流程

Redis 不会一次性把旧表所有数据迁移到新表，而是分多次迁移。

完整流程如下：

```text
1. 判断是否需要扩容或缩容
   - 数据越来越多，负载因子过高，需要扩容
   - 数据大量删除，哈希表太稀疏，需要缩容

2. 给 ht[1] 分配新哈希表空间
   - 扩容时，ht[1] 通常比分配前更大
   - 缩容时，ht[1] 会更小，减少内存浪费

3. 设置 rehashidx = 0
   - 表示 rehash 正式开始
   - rehashidx 记录当前迁移到 ht[0] 的哪个 bucket

4. 渐进式迁移 bucket
   - 每次对 Dict 执行增删改查时，顺便迁移一个或多个 bucket
   - Redis 后台定时任务也会辅助迁移
   - 每迁移一个 bucket，rehashidx 向后推进

5. rehash 期间同时维护 ht[0] 和 ht[1]
   - 查询：先查 ht[0]，再查 ht[1]
   - 新增：直接写入 ht[1]
   - 修改/删除：两张表都可能查找

6. 迁移完成
   - ht[0] 中所有 bucket 都迁移到 ht[1]
   - 释放旧 ht[0]
   - ht[1] 变成新的 ht[0]
   - rehashidx 重新设置为 -1
```

### 4.5 rehash 图解

```text
开始 rehash 前：

ht[0]: bucket0 bucket1 bucket2 bucket3
ht[1]: 空
rehashidx = -1

开始 rehash：

ht[0]: bucket0 bucket1 bucket2 bucket3
ht[1]: newBucket0 newBucket1 newBucket2 newBucket3 newBucket4 newBucket5 ...
rehashidx = 0

渐进迁移中：

ht[0]: bucket0(已迁移) bucket1(已迁移) bucket2(未迁移) bucket3(未迁移)
ht[1]: 已接收 bucket0、bucket1 中的数据
rehashidx = 2

迁移完成：

释放旧 ht[0]
ht[1] 变成新的 ht[0]
rehashidx = -1
```

### 4.6 rehash 期间读写怎么处理？

| 操作 | 处理方式 |
|------|----------|
| 查询 | 先查 `ht[0]`，再查 `ht[1]` |
| 新增 | 直接写入 `ht[1]`，避免 `ht[0]` 继续变大 |
| 修改 | 两张表都可能查找，找到后修改 |
| 删除 | 两张表都可能查找，找到后删除 |

### 4.7 为什么不能一次性 rehash？

因为 Redis 命令执行主要是单线程，如果一次性迁移百万级 Key，会长时间阻塞所有请求。

渐进式 rehash 把一次大迁移拆成很多小迁移，分摊到后续命令执行过程中，从而降低延迟尖刺。

### 4.8 Hash 里的 rehash 和 Dict 里的 rehash 有什么区别？

两者本质是同一个机制，只是角度不同：

| 对比项 | Hash 里的 rehash | Dict 里的 rehash |
|--------|------------------|------------------|
| 讲解角度 | Redis 数据类型角度 | Redis 底层结构角度 |
| 发生对象 | 某个大 Hash 的 field-value 表 | 所有使用 Dict 的结构 |
| 本质机制 | Dict 渐进式 rehash | Redis 通用哈希表 rehash |
| 适用范围 | Hash 类型内部 | 全局 Key 空间、Hash、Set、ZSet、过期字典等 |

面试可以这样说：

> Hash 类型在字段少时可能用 ziplist/listpack，字段多时会转成 hashtable，而这个 hashtable 底层就是 Redis Dict。Dict 是 Redis 通用哈希表实现，所以 Hash 里的 rehash 本质上就是 Dict 渐进式 rehash 在 Hash 类型中的具体应用。

