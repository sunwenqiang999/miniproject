# MySQL 高可用与生产实战面试知识点

## 一、主从复制

### 1.1 为什么要主从复制？

1. **读写分离**：主库写，从库读，分摊读压力
2. **数据容灾**：从库是主库的数据副本，主库故障可切换
3. **备份隔离**：备份在从库执行，不影响主库
4. **就近访问**：异地部署从库，降低访问延迟

### 1.2 主从复制原理（必考）

```text
主库                                    从库
┌──────────────────┐          ┌──────────────────────┐
│ 客户端写入         │          │                      │
│      │            │          │                      │
│      ▼            │          │                      │
│ binlog dump 线程   │  TCP     │ IO 线程               │
│ 读取 binlog ──────────────────▶ 接收 binlog          │
│                  │          │      │                │
│                  │          │      ▼                │
│                  │          │ relay log（中继日志）   │
│                  │          │      │                │
│                  │          │      ▼                │
│                  │          │ SQL 线程（应用线程）     │
│                  │          │  重放 relay log        │
└──────────────────┘          │      │                │
                              │      ▼                │
                              │  从库数据更新           │
                              └──────────────────────┘
```

**三个线程：**

| 线程 | 位置 | 职责 |
|------|------|------|
| dump 线程 | 主库 | 把 binlog 事件发送给从库 |
| IO 线程 | 从库 | 连接主库，拉取 binlog 写入 relay log |
| SQL 线程 | 从库 | 读取 relay log 并重放，更新从库数据 |

**面试回答：**

> 主从复制依赖 binlog。主库提交事务时写 binlog，从库的 IO 线程连接主库，主库的 dump 线程把 binlog 推送过来，从库把日志写入本地 relay log；从库的 SQL 线程读取 relay log 重放，使从库数据追上主库。整个链路是异步的，这也是主从延迟的根源。

### 1.3 复制的三种模式

| 模式 | 说明 | 一致性 | 性能 |
|------|------|--------|------|
| 异步复制 | 主库提交即返回，不等从库 | 最弱，可能丢数据 | 最好（默认） |
| 半同步复制 | 至少 1 个从库收到 binlog 才返回 | 较强 | 略降 |
| 全同步复制 | 所有从库都执行完才返回 | 最强 | 最差 |

**半同步复制（semi-sync）：**

> 主库提交事务后等待至少一个从库 ACK 确认收到 binlog 才向客户端返回成功。如果超时（rpl_semi_sync_master_timeout），会自动降级为异步复制。它是性能和数据安全的折中，生产常用。

### 1.4 GTID 复制

传统复制基于 binlog 文件名 + 位点（file + position），切换主库时需要人工找位点，容易出错。

**GTID（Global Transaction Identifier）格式：** `server_uuid:transaction_id`，如 `3E11FA47-71CA-11E1-9E33-C80AA9429562:1-100`

**优势：**
- 每个事务全局唯一，从库自动定位复制位点
- 主从切换时自动 failover，无需人工指定位点
- 幂等：已执行过的事务不会重复执行

### 1.5 主从延迟

**产生原因：**

1. **SQL 线程单线程重放**（5.7 之前），主库并发写、从库串行回放
2. 大事务：一条事务重放期间无法并行
3. 从库机器规格差、磁盘 IO 慢
4. 从库承担大量读查询，资源被占用
5. 网络抖动

**解决方案：**

| 方案 | 说明 |
|------|------|
| 并行复制 | 5.6 按库并行、5.7 基于 GROUP_COMMIT 并行（LOGICAL_CLOCK）、8.0 基于 writeset 并行 |
| 避免大事务 | 拆分批量任务，单事务控制在合理大小 |
| 半同步 + 强读走主库 | 对一致性要求高的读强制走主库 |
| 读从库判断延迟 | 客户端检查 Seconds_Behind_Master，延迟超阈值降级读主库 |
| 升级从库硬件 | SSD、更高规格 |

**面试回答：**

> 主从延迟的根本原因是复制链路异步，且早期 SQL 线程单线程重放跟不上主库并发写。优化方向：一是开启并行复制，5.7 用 LOGICAL_CLOCK、8.0 用 writeset 提升并行度；二是避免大事务；三是业务上对强一致读强制走主库，或者客户端感知延迟（Seconds_Behind_Master）动态路由；四是提升从库硬件配置。

### 1.6 主从延迟下的读写分离方案

```text
方案一：关键读走主库
写后立即读（下单后跳订单详情）-> 强制路由主库

方案二：会话粘性
同一会话写后 N 秒内读主库，之后读从库

方案三：等待追平
读从库前检查 GTID 是否已执行（WAIT_FOR_EXECUTED_GTID_SET）

方案四：中间件路由
ShardingSphere / MyCat 等根据 SQL 语义和延迟情况动态路由
```

---

## 二、高可用架构

### 2.1 常见高可用方案对比

| 方案 | 原理 | 切换时间 | 复杂度 |
|------|------|----------|--------|
| MHA | 监控主库，故障时提升最新从库 | 10~30 秒 | 中，需要额外 manager 节点 |
| Orchestrator | 拓扑感知的复制管理，自动 failover | 秒级 | 中高 |
| MGR（组复制） | 基于 Paxos 的多节点强一致 | 秒级自动 | 高，要求严格 |
| 半同步 + 手动切换 | 半同步保证不丢，人工决策切换 | 分钟级 | 低 |

### 2.2 MGR（MySQL Group Replication）

**核心思想：** 基于 Paxos 协议（类共识协议）让多个节点对事务达成共识后再提交。

**单主模式（推荐）：**
- 集群只有一个节点可写，其余只读
- 主故障时自动选举新主，秒级完成
- 事务需要过半数节点认证才提交，保证不丢数据

**要求：**
- 必须有 GTID、binlog 为 ROW 格式
- 每个表必须有主键
- 推荐奇数节点（至少 3 个），过半数存活才能工作

**面试回答：**

> MGR 是官方基于共识协议的高可用方案，事务提交需要集群过半数节点认证确认，主库故障时自动选举切换，数据不丢。相比传统主从 + MHA 的异步复制方案，MGR 牺牲一定写入性能换取强一致性。适合对数据安全要求高、规模不大的核心业务库。

### 2.3 主从切换的坑

1. **数据丢失**：异步复制下主库宕机，未同步的 binlog 丢失
2. **复制过滤**：从库配置了 replicate-ignore-db，切换后缺表
3. **自增 ID 冲突**：双主架构下自增值可能冲突，需设置 auto_increment_offset 错开
4. **客户端连接串**：切换后需要更新连接地址（配合 VIP / DNS / 代理层）
5. **旧主回归**：旧主恢复后必须以从库身份重新加入，避免脑裂

---

## 三、分库分表

### 3.1 什么时候需要分库分表？

**判断信号：**
- 单表数据量过大（经验值：MySQL 单表 2000 万行或 20GB 以上，B+ 树层级变深、查询明显变慢）
- 单库写入 QPS 过高，主库 CPU / IO / 连接数成为瓶颈
- 单实例磁盘容量不足

**先尝试的替代方案（面试加分）：**

> 分库分表是"最后的手段"，代价很大（跨库 join、分布式事务、扩容迁移）。先考虑：读写分离分摊读压力、缓存抗热点、归档历史数据（冷热分离）、升级硬件。这些手段用尽仍然撑不住，才做分库分表。

### 3.2 垂直拆分与水平拆分

**垂直分库：** 按业务域拆分，订单库、用户库、商品库各自独立。

**垂直分表：** 把一张宽表按列拆分，高频小字段放主表，大字段低频字段放扩展表。

**水平分库分表：** 把同一张表的数据按规则分散到多个库多个表。

```text
垂直分库：                          水平分表：
┌────────┐    ┌─────┐ ┌─────┐      order_0: id 1~1000
│ 大而全库 │ -> │用户库│ │订单库│      order_1: id 1001~2000
└────────┘    └─────┘ └─────┘      order_2: id 2001~3000
```

### 3.3 分片键与分片算法

**分片键选择原则：**
- 绝大多数查询都能带上它（避免全路由）
- 分布均匀，避免数据倾斜
- 常用：用户 ID、订单 ID

**常见分片算法：**

| 算法 | 规则 | 优缺点 |
|------|------|--------|
| hash 取模 | `user_id % N` | 均匀，但扩容需要迁移数据 |
| range 范围 | 按时间/ID 区间 | 扩容方便，但热点集中（新数据都在最后一片） |
| 一致性哈希 | 哈希环 | 扩容只影响相邻节点，实现复杂 |
| 查表法 | 维护映射表 | 灵活，映射表本身是瓶颈和单点 |

**面试回答：**

> 分片算法常用 hash 取模，数据分布均匀，但扩容翻倍时需要迁移大量数据；range 按范围分片扩容方便但容易有热点；一致性哈希扩容只影响相邻节点。实践中常用"基因法"把用户 ID 嵌入订单号，让按用户查和按订单查都能路由到同一个分片，避免全路由扫描。

### 3.4 分库分表带来的问题

**1. 跨库 join：**

```text
解决：
- 冗余字段（订单表冗余用户昵称）
- 全局表/字典表（每个库都放一份小表）
- 应用层组装（分别查询后内存 join）
- 数据异构到 ES / 宽表
```

**2. 分布式事务：**

```text
解决：
- 尽量避免跨库事务（按分片键设计业务）
- 最终一致性：本地消息表 + MQ
- Seata AT / TCC / SAGA 模式
```

**3. 跨库分页与排序：**

```text
订单按时间排序分页，每个分片取前 N 条 -> 归并排序
问题：深分页时每个分片都要返回大量数据
解决：
- 禁止跳页，只允许下一页（游标）
- 二次查询法
- 数据异构到 ES 处理复杂查询
```

**4. 全局唯一 ID：**

| 方案 | 原理 | 优缺点 |
|------|------|--------|
| UUID | 本地生成 | 无序，做聚簇主键性能差 |
| 雪花算法 | 时间戳+机器ID+序列号 | 趋势递增，时钟回拨问题 |
| 号段模式 | 数据库批量发号 | 简单可靠，有短暂空洞 |
| Redis INCR | 原子自增 | 依赖 Redis 可用性 |

**5. 扩容与数据迁移：**

```text
双写迁移方案：
1. 上线双写（旧库 + 新库），读旧库
2. 存量数据迁移 + 增量同步校验
3. 灰度切读新库
4. 观察稳定后停写旧库
```

### 3.5 分库分表中间件

| 中间件 | 类型 | 特点 |
|--------|------|------|
| ShardingSphere-JDBC | 客户端 | 无代理层，性能好，应用耦合 |
| MyCat | 代理 | 独立部署，跨语言，多一跳网络 |
| ShardingSphere-Proxy | 代理 | 独立部署，对应用透明 |

**面试回答：**

> 客户端方案 ShardingSphere-JDBC 以 jar 包形式嵌入应用，没有额外网络开销，性能好，但每个应用都要维护分片配置；代理方案如 MyCat、ShardingSphere-Proxy 独立部署，对应用透明、跨语言支持好，但多一跳网络、代理本身要高可用。Java 单一技术栈推荐 JDBC 方案，多语言异构推荐 Proxy。

---

## 四、SQL 优化实战

### 4.1 慢 SQL 排查流程

```text
1. 开启慢查询日志（slow_query_log，long_query_time）
2. 用 mysqldumpslow / pt-query-digest 分析 TOP N 慢 SQL
3. EXPLAIN 分析执行计划
   - type 是 ALL（全表扫描）？
   - key 是 NULL（没走索引）？
   - rows 扫描行数过大？
   - Extra 有 Using filesort / Using temporary？
4. 针对性优化：加索引、改写 SQL、调整数据模型
5. 验证优化效果（对比执行时间、执行计划）
```

### 4.2 EXPLAIN 核心字段实战

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 1001 ORDER BY created_at DESC LIMIT 10;
```

| 字段 | 值示例 | 解读 |
|------|--------|------|
| type | ref | 走了非唯一索引等值查询，可以接受 |
| key | idx_user_id | 实际使用的索引 |
| rows | 1000 | 预估扫描行数 |
| Extra | Using filesort | 有额外排序，需要优化 |

**Extra 常见值：**

| 值 | 含义 | 处理 |
|----|------|------|
| Using index | 覆盖索引 | 好 |
| Using index condition | 索引下推 | 好 |
| Using where | Server 层过滤 | 正常 |
| Using filesort | 额外排序 | 优化排序字段索引 |
| Using temporary | 临时表 | GROUP BY / DISTINCT 优化 |

### 4.3 深分页优化（实战版）

```sql
-- 原始：扫描 1000010 行
SELECT * FROM orders ORDER BY id LIMIT 1000000, 10;

-- 方案一：延迟关联（覆盖索引先定位主键）
SELECT o.* FROM orders o
INNER JOIN (
    SELECT id FROM orders ORDER BY id LIMIT 1000000, 10
) t ON o.id = t.id;

-- 方案二：游标分页（记录上一页末尾 id，产品限制跳页）
SELECT * FROM orders WHERE id > #{lastId} ORDER BY id LIMIT 10;
```

### 4.4 count 优化

```sql
-- MyISAM 有总行数缓存，InnoDB 需要扫描
-- 优化方案：

-- 1. count(字段) < count(主键) < count(1) ≈ count(*)，直接用 count(*)

-- 2. 近似值：系统表统计信息（不精确但 O(1)）
SELECT table_rows FROM information_schema.tables
WHERE table_name = 'orders';

-- 3. 精确值：Redis 计数器维护（注意与 DB 一致性）

-- 4. 带条件 count：无法避免扫描，考虑异构到 ES / 预聚合表
```

### 4.5 order by 优化

```sql
-- 联合索引 (a, b)
SELECT * FROM t WHERE a = 1 ORDER BY b;      -- 索引有序，无需 filesort
SELECT * FROM t WHERE a > 1 ORDER BY b;      -- a 是范围，b 无法利用索引顺序 -> filesort

-- 混合排序方向需要 8.0 支持降序索引
SELECT * FROM t ORDER BY a ASC, b DESC;      -- 8.0 前无法完全用索引
```

**原则：** WHERE 等值列 + ORDER BY 列组成联合索引，可以同时满足过滤和排序。

### 4.6 join 优化

```sql
-- 小表驱动大表：straight_join 强制驱动顺序
SELECT * FROM small_table STRAIGHT_JOIN big_table ON ...;

-- 被驱动表关联字段必须有索引（eq_ref / ref）
-- join_buffer：被驱动表无索引时使用 Block Nested-Loop / hash join（8.0.18+）
```

**8.0 的 hash join：**

> MySQL 8.0.18 引入 hash join，被驱动表关联列没索引时，把小表构建哈希表，大表探测，替代低效的 Block Nested-Loop。但最优解仍然是给关联列建索引。

### 4.7 在线 DDL（大表加字段/索引）

```sql
-- 8.0 默认 INSTANT 算法，加列秒级完成（只改元数据）
ALTER TABLE t ADD COLUMN c INT, ALGORITHM=INSTANT;

-- 加索引用 INPLACE，不锁表（允许 DML）
ALTER TABLE t ADD INDEX idx_c(c), ALGORITHM=INPLACE, LOCK=NONE;

-- 变更列类型等只能 COPY，锁表
```

**大表 DDL 注意事项：**
- 优先 INSTANT / INPLACE，避免 COPY
- 低峰期执行，控制 MDL 锁等待时间（lock_wait_timeout）
- 超大表用 gh-ost / pt-online-schema-change（触发器/ binlog 同步 + 影子表切换）

---

## 五、生产实践

### 5.1 大表删除数据

```sql
-- 危险：一次性 DELETE 千万行
-- 问题：大事务（undo 膨胀、锁持有久、主从延迟）、binlog 巨大

-- 正确：分批删除
DELETE FROM logs WHERE created_at < '2025-01-01' LIMIT 1000;
-- 循环执行，每批之间 sleep，直到影响行数为 0

-- 或者：新建表 + 重命名（适合删大部分数据）
CREATE TABLE logs_new LIKE logs;
INSERT INTO logs_new SELECT * FROM logs WHERE created_at >= '2025-01-01';
RENAME TABLE logs TO logs_old, logs_new TO logs;
DROP TABLE logs_old;
```

### 5.2 大表加字段/加索引

见 4.7，核心：INSTANT > INPLACE > gh-ost/pt-osc > COPY。

### 5.3 连接池配置

```text
max_connections：实例最大连接数（默认 151，生产通常 1000~3000）
wait_timeout：空闲连接超时（默认 8 小时，建议缩短）
应用侧连接池（HikariCP）：
- maximumPoolSize = CPU 核数 * 2 + 磁盘数（经验起点）
- maxLifetime 小于 DB 的 wait_timeout
- 连接获取超时要有监控告警
```

**连接数打满排查：**

```sql
SHOW PROCESSLIST;                          -- 查看当前连接
SELECT * FROM information_schema.processlist
WHERE command != 'Sleep' ORDER BY time DESC;  -- 找长执行 SQL
KILL <id>;                                 -- 杀掉问题连接
```

### 5.4 误操作数据恢复

```text
场景：UPDATE/DELETE 忘带 WHERE，或 DROP 了表

恢复手段（按优先级）：
1. 延迟从库：专门挂一个延迟 1 小时的从库，误操作后立即停掉复制，从库数据还在
2. binlog 回放：用 binlog2sql / MyFlash 解析 binlog，生成反向 SQL 恢复
3. 备份恢复 + binlog 增量：全量备份恢复到临时实例，再重放备份点之后的 binlog
4. 预防：生产高危 SQL走审核流程（如 Yearning / Archery SQL 审核平台）
```

### 5.5 冷热分离与归档

```text
问题：订单表持续增长，历史订单访问极少，拖累整体性能

方案：
1. 归档表：历史数据迁移到 order_archive 表/库，主表只留近期数据
2. 分区表：按时间 RANGE 分区，旧分区直接 DROP（注意分区键限制）
3. 异构存储：历史数据同步到 HBase / OSS + 查询服务

注意：分区表对业务透明但限制多（分区键必须包含在主键/唯一键中），
跨分区查询、分区维护都有坑，大厂更多用业务层归档而不是分区表。
```

### 5.6 数据库选型对比（面试发散题）

| 场景 | 推荐 | 原因 |
|------|------|------|
| 强事务核心业务 | MySQL（InnoDB） | ACID、生态成熟 |
| 海量 KV、高并发缓存 | Redis | 内存性能 |
| 复杂搜索、多维查询 | Elasticsearch | 倒排索引 |
| 海量日志、宽表分析 | ClickHouse / Doris | 列存、向量化 |
| 文档型灵活 schema | MongoDB | JSON 文档模型 |
| 时序数据 | InfluxDB / TDengine | 时间分区压缩 |

**面试回答：**

> 没有银弹，按场景选型。核心交易链路用 MySQL 保证事务；热点读用 Redis；搜索和多维筛选异构到 ES；报表分析走 ClickHouse 或 Doris；数据同步用 Canal 监听 binlog + MQ 分发。关键是"在线库保持精简，复杂查询异构出去"。

### 5.7 MySQL 8.0 新特性（加分项）

| 特性 | 说明 |
|------|------|
| 移除查询缓存 | 命中率低，得不偿失 |
| 默认 utf8mb4 | 完整 UTF-8 支持 |
| 降序索引 | ORDER BY a ASC, b DESC 可完全走索引 |
| 窗口函数 | ROW_NUMBER、RANK、移动聚合 |
| CTE 公用表表达式 | 递归查询、可读性 |
| hash join | 无索引 join 的救星 |
| DDL INSTANT | 加列秒级 |
| 隐藏索引 | 索引先隐藏观察再删除，可回滚 |
| 原子 DDL | DDL 成功或失败，不再有中间态 |

---

## 六、高频面试题

### 6.1 主从复制原理？

主库写 binlog -> dump 线程推送 -> 从库 IO 线程写 relay log -> SQL 线程重放。异步复制是默认模式，延迟不可避免。

### 6.2 主从延迟怎么解决？

并行复制（8.0 writeset）、避免大事务、强一致读走主库、客户端感知延迟动态路由、提升从库硬件。

### 6.3 什么时候分库分表？

单表超千万行/20GB、单库写入 QPS 到瓶颈、磁盘不足。但先试读写分离、缓存、归档，分库分表是最后手段。

### 6.4 分片键怎么选？

选绝大多数查询都携带、分布均匀的字段（通常是 user_id）；用基因法把分片键嵌入其他业务 ID，避免全路由。

### 6.5 分库分表后怎么做分布式 ID？

雪花算法（趋势递增、注意时钟回拨）、号段模式（DB 批量发号）、Redis INCR。避免 UUID 做聚簇主键。

### 6.6 慢 SQL 怎么排查？

慢日志定位 -> EXPLAIN 看 type/key/rows/Extra -> 加索引或改写 SQL -> 覆盖索引、避免 filesort、深分页用延迟关联或游标。

### 6.7 大表怎么安全删除数据？

分批 DELETE（LIMIT + 循环 + sleep），或新建表导入保留数据后 RENAME 切换。禁止一次性大事务删除。

### 6.8 误删数据怎么恢复？

延迟从库直接找回；binlog2sql 解析 binlog 生成反向 SQL；全量备份 + binlog 增量重放。预防靠 SQL 审核流程。

### 6.9 MGR 和主从 + MHA 怎么选？

MGR 强一致、自动切换，但写入有共识开销、运维要求高；主从 + MHA 简单成熟，但异步可能丢数据。核心资金类业务倾向 MGR 或半同步，一般业务主从 + 自动切换足够。

### 6.10 MySQL 和 Redis 怎么配合？

Redis 做缓存抗读热点，MySQL 是持久化底座。注意缓存一致性（先更新 DB 再删缓存 + 延迟双删/binlog 异步删除），大 Key、热 Key 治理，缓存穿透/击穿/雪崩防护（详见 Redis 系列文档）。
