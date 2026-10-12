# MySQL 面试知识点总结

## 阅读建议

MySQL 面试知识点已按复习层次拆分为三类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`mysql面试知识点.md`](mysql面试知识点.md) | 总览复习 | MySQL 基础、数据类型、SQL 语句、整体知识框架 |
| [`mysql原理机制面试知识点.md`](mysql原理机制面试知识点.md) | 原理深入 | InnoDB 架构、B+ 树索引、事务与 MVCC、锁机制、三大日志 |
| [`mysql高可用与生产实战面试知识点.md`](mysql高可用与生产实战面试知识点.md) | 进阶实战 | 主从复制、读写分离、分库分表、SQL 优化、生产实践 |

建议先掌握第一章 MySQL 基础和第二章数据类型，再学习原理机制，最后背诵高可用与生产实战的面试回答模板。

## 一、MySQL 基础概念

### 1.1 什么是 MySQL？

MySQL 是一个开源的关系型数据库管理系统（RDBMS），使用 SQL 语言进行数据操作，广泛应用于 Web 应用、互联网业务后台存储等场景。

**核心特点：**
- 关系型模型：数据以表（行和列）形式存储，支持表间关联
- 支持 ACID 事务（InnoDB 引擎）
- 支持多种存储引擎，可插拔架构
- 攘持主从复制、读写分离、分库分表等扩展方案
- 生态成熟：备份恢复、监控、中间件工具丰富

### 1.2 MySQL 一条 SQL 的执行流程

```text
客户端（JDBC/驱动）
      │
      ▼
连接器（建立连接、鉴权、管理连接）
      │
      ▼
查询缓存（MySQL 8.0 已移除）
      │
      ▼
分析器（词法分析、语法分析）
      │
      ▼
优化器（选择索引、生成执行计划）
      │
      ▼
执行器（调用存储引擎接口执行）
      │
      ▼
存储引擎（InnoDB / MyISAM 等，操作磁盘数据）
```

**各阶段说明：**

| 阶段 | 职责 |
|------|------|
| 连接器 | 建立 TCP 连接、校验账号密码、获取权限 |
| 查询缓存 | 以 SQL 为 Key 缓存结果，命中率低，8.0 移除 |
| 分析器 | 词法分析识别关键字，语法分析生成解析树 |
| 优化器 | 决定用哪个索引、多表 join 顺序，生成执行计划 |
| 执行器 | 校验权限后调用存储引擎 API 执行，返回结果 |

**面试回答：**

> 一条 SQL 到达 MySQL 后，先经过连接器建立连接和鉴权，然后经过分析器做词法语法分析，再由优化器选择索引和 join 顺序生成执行计划，最后由执行器调用存储引擎接口真正读写数据。MySQL 8.0 之前还有查询缓存，但因为命中率太低已被移除。

### 1.3 MySQL 存储引擎

MySQL 采用可插拔存储引擎架构，最常用的是 InnoDB 和 MyISAM。

| 对比项 | InnoDB | MyISAM |
|--------|--------|--------|
| 事务 | 支持 ACID 事务 | 不支持 |
| 锁粒度 | 行级锁 | 表级锁 |
| 外键 | 支持 | 不支持 |
| 聚簇索引 | 是（数据和主键索引在一起） | 否（索引和数据分离） |
| 崩溃恢复 | 支持 redo log 崩溃恢复 | 不支持 |
| 全文索引 | 5.6+ 支持 | 支持 |
| COUNT(*) 无 WHERE | 需要扫描（MVCC 下不同事务看到的行数不同） | 有专门变量存总数，O(1) |
| 适用场景 | 绝大多数业务场景 | 只读、统计类场景（已逐渐被淘汰） |

**面试回答：**

> InnoDB 支持事务、行级锁和崩溃恢复，是 MySQL 5.5 之后的默认引擎，适合绝大多数业务场景。MyISAM 不支持事务和行锁，只支持表锁，崩溃后数据容易损坏，现在基本被淘汰。两者索引结构也不同：InnoDB 是聚簇索引，MyISAM 是非聚簇索引。

### 1.4 InnoDB 和 MyISAM 的区别？

见 1.3 表格，核心记住三点：**事务、行锁、聚簇索引**。

补充一个高频细节：

**为什么 MyISAM 的 COUNT(*) 快，InnoDB 慢？**

> MyISAM 维护了一个变量记录表总行数，无 WHERE 条件的 COUNT(*) 直接返回。InnoDB 因为 MVCC 的存在，不同事务在同一时刻看到的行数可能不同，所以必须扫描（通常走最小的索引）来统计当前事务可见的行数。

### 1.5 MySQL 默认隔离级别

InnoDB 默认隔离级别是 **可重复读（REPEATABLE READ）**。

| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|----------|------|------------|------|
| 读未提交（READ UNCOMMITTED） | 可能 | 可能 | 可能 |
| 读已提交（READ COMMITTED） | 不可能 | 可能 | 可能 |
| 可重复读（REPEATABLE READ） | 不可能 | 不可能 | 基本避免（间隙锁 + MVCC） |
| 串行化（SERIALIZABLE） | 不可能 | 不可能 | 不可能 |

**为什么 MySQL 默认 RR 而不是 RC？**

> 历史原因是早期 binlog 只有 STATEMENT 格式，在 RC 级别下主从复制会产生数据不一致（语句执行顺序不同），RR + 间隙锁可以保证 STATEMENT 格式的语句在主从上执行结果一致。另外 RR 下一个事务内多次读取结果一致，对业务更友好。现在如果 binlog 使用 ROW 格式，很多互联网公司会把隔离级别改成 RC，减少锁范围。

### 1.6 char 和 varchar 的区别？

| 对比项 | char | varchar |
|--------|------|---------|
| 存储方式 | 定长，不足补空格 | 变长，按实际长度存储 |
| 长度范围 | 0~255 字符 | 0~65535 字节（受行大小限制） |
| 尾部空格 | 检索时去除（SQL_MODE 而定） | 保留 |
| 性能 | 定长存取快 | 需要记录长度，稍慢 |
| 适用场景 | 长度固定的字段（手机号、MD5、状态码） | 长度不固定的字段（用户名、地址） |

### 1.7 MySQL 支持的数据类型

**整数类型：**

| 类型 | 字节 | 有符号范围 | 无符号范围 |
|------|------|-----------|-----------|
| TINYINT | 1 | -128 ~ 127 | 0 ~ 255 |
| SMALLINT | 2 | -32768 ~ 32767 | 0 ~ 65535 |
| MEDIUMINT | 3 | 约 ±838 万 | 0 ~ 1677 万 |
| INT | 4 | 约 ±21.47 亿 | 0 ~ 42.94 亿 |
| BIGINT | 8 | 约 ±9.22 × 10^18 | 0 ~ 1.84 × 10^19 |

**浮点与定点：**
- FLOAT（4 字节）、DOUBLE（8 字节）：浮点数，有精度损失
- DECIMAL(M,D)：定点数，精确存储，金额场景必须使用

**字符串：**
- CHAR(N)：定长
- VARCHAR(N)：变长
- TEXT：长文本（TINYTEXT/TEXT/MEDIUMTEXT/LONGTEXT）
- BLOB：二进制大对象

**时间类型：**

| 类型 | 字节 | 格式 | 范围 |
|------|------|------|------|
| DATE | 3 | YYYY-MM-DD | 1000-01-01 ~ 9999-12-31 |
| TIME | 3 | HH:MM:SS | -838:59:59 ~ 838:59:59 |
| DATETIME | 8 | YYYY-MM-DD HH:MM:SS | 1000 ~ 9999 年 |
| TIMESTAMP | 4 | YYYY-MM-DD HH:MM:SS | 1970 ~ 2038 年，带时区转换 |

**面试高频：DATETIME 和 TIMESTAMP 的区别？**

> DATETIME 占 8 字节，存储日期字符串，范围到 9999 年，与时区无关；TIMESTAMP 占 4 字节，存储 UTC 时间戳，范围只到 2038 年，会随时区自动转换。需要时区感知用 TIMESTAMP，需要大范围或存历史日期用 DATETIME。

### 1.8 NULL 的注意事项

- NULL 与任何值比较结果都是 NULL（未知），必须用 `IS NULL` / `IS NOT NULL` 判断
- `COUNT(字段)` 不统计 NULL，`COUNT(*)` 统计所有行
- NULL 参与聚合函数（SUM、AVG 等）会被忽略
- 索引列上可以有 NULL，但 `!=`、`NOT IN` 对 NULL 的判断容易踩坑
- 建议字段尽量 NOT NULL DEFAULT，减少 NULL 带来的语义歧义

### 1.9 drop、truncate、delete 的区别？

| 对比项 | delete | truncate | drop |
|--------|--------|----------|------|
| 类型 | DML | DDL | DDL |
| 条件删除 | 支持 WHERE | 不支持 | 不支持 |
| 事务回滚 | 可以回滚 | 不可回滚（隐式提交） | 不可回滚 |
| 触发器 | 触发 DELETE 触发器 | 不触发 | 不触发 |
| 速度 | 逐行删除，慢 | 清空表空间，快 | 直接删表，最快 |
| 自增值 | 不重置 | 重置为 1 | 表都没了 |
| 本质 | 逐行打删除标记 | 删除并重建表 | 删除表定义和数据 |

**面试回答：**

> delete 是 DML，逐行删除并记录 undo log，可以带 WHERE、可以回滚；truncate 是 DDL，直接清空表重建，速度快但不能回滚，会重置自增值；drop 连表结构一起删除。生产中删全表数据优先 truncate，误删数据要恢复只能依赖备份或 binlog。

### 1.10 MySQL 内置函数高频题

**1. now() 和 sysdate() 的区别？**

> now() 在语句开始执行时取值，同一条语句内多次调用结果相同；sysdate() 在函数执行时实时取值，同一条语句内可能不同。主从复制场景下 sysdate() 可能导致主从数据不一致，建议用 now()。

**2. 字符串函数：**

```sql
-- 拼接
CONCAT('a', 'b', 'c')          -- abc
-- 包含 NULL 则结果为 NULL
CONCAT('a', NULL)               -- NULL
-- NULL 安全拼接
CONCAT_WS('-', 'a', NULL, 'c')  -- a-c
-- 长度
LENGTH('你好')                  -- 6，字节数（UTF8 一个汉字 3 字节）
CHAR_LENGTH('你好')             -- 2，字符数
-- 截取
SUBSTRING('hello', 2, 3)        -- ell
-- 大小写
UPPER('abc') / LOWER('ABC')
```

**3. 日期函数：**

```sql
-- 当前时间
NOW()                           -- 2026-08-27 15:00:00
CURDATE()                       -- 2026-08-27
CURTIME()                       -- 15:00:00
-- 日期格式化
DATE_FORMAT(NOW(), '%Y-%m-%d')  -- 2026-08-27
-- 日期计算
DATE_ADD(NOW(), INTERVAL 1 DAY)
DATE_SUB(NOW(), INTERVAL 1 HOUR)
DATEDIFF('2026-08-27', '2026-08-01')  -- 26
```

**4. 条件函数：**

```sql
IFNULL(name, '默认')            -- NULL 时取默认值
IF(cond, a, b)                  -- 条件表达式
CASE WHEN score >= 90 THEN 'A'
     WHEN score >= 80 THEN 'B'
     ELSE 'C' END
```

---

## 二、SQL 语句与查询

### 2.1 SQL 语句分类

| 分类 | 全称 | 说明 | 典型命令 |
|------|------|------|----------|
| DDL | Data Definition Language | 定义表结构 | CREATE、ALTER、DROP、TRUNCATE |
| DML | Data Manipulation Language | 操作数据 | INSERT、UPDATE、DELETE |
| DQL | Data Query Language | 查询数据 | SELECT |
| DCL | Data Control Language | 控制权限 | GRANT、REVOKE |
| TCL | Transaction Control Language | 事务控制 | BEGIN、COMMIT、ROLLBACK |

### 2.2 SQL 执行顺序

书写顺序与执行顺序不同，这是面试常考题：

```sql
SELECT DISTINCT column, COUNT(*)      -- 7. SELECT
FROM table                            -- 1. FROM
JOIN another_table                    -- 2. ON / JOIN
ON table.id = another_table.id
WHERE condition                       -- 3. WHERE
GROUP BY column                       -- 4. GROUP BY
HAVING count_condition                -- 5. HAVING
ORDER BY column                       -- 6. ORDER BY
LIMIT 10;                             -- 8. LIMIT
```

**执行顺序：**

```text
FROM -> ON -> JOIN -> WHERE -> GROUP BY -> HAVING -> SELECT -> DISTINCT -> ORDER BY -> LIMIT
```

**面试回答：**

> SQL 的书写顺序是 SELECT 在前，但执行顺序是先 FROM 确定数据来源，JOIN 关联表，然后 WHERE 过滤行，再 GROUP BY 分组，HAVING 过滤分组，之后才执行 SELECT 计算列，DISTINCT 去重，最后 ORDER BY 排序、LIMIT 分页。这也解释了为什么 WHERE 里不能用 SELECT 里定义的别名，而 ORDER BY 可以。

### 2.3 WHERE 和 HAVING 的区别？

| 对比项 | WHERE | HAVING |
|--------|-------|--------|
| 过滤对象 | 行（分组前过滤） | 分组（分组后过滤） |
| 能否用聚合函数 | 不能 | 能 |
| 执行时机 | GROUP BY 之前 | GROUP BY 之后 |
| 是否走索引 | 可能走 | 基本不走 |

**面试回答：**

> WHERE 在分组前过滤每一行，不能使用聚合函数，可能用到索引；HAVING 在分组后过滤分组结果，可以使用聚合函数。执行顺序上 WHERE 先于 GROUP BY，HAVING 后于 GROUP BY。应该尽量把过滤条件放到 WHERE 中，减少参与分组的数据量。

### 2.4 内连接、左连接、右连接、全连接

```sql
-- 内连接：只返回两表匹配的行
SELECT * FROM a INNER JOIN b ON a.id = b.aid;

-- 左连接：返回左表全部行，右表无匹配则填 NULL
SELECT * FROM a LEFT JOIN b ON a.id = b.aid;

-- 右连接：返回右表全部行，左表无匹配则填 NULL
SELECT * FROM a RIGHT JOIN b ON a.id = b.aid;

-- MySQL 不直接支持 FULL JOIN，用 LEFT JOIN UNION RIGHT JOIN 模拟
SELECT * FROM a LEFT JOIN b ON a.id = b.aid
UNION
SELECT * FROM a RIGHT JOIN b ON a.id = b.aid;
```

**图示：**

```text
内连接：        左连接：        右连接：        全连接：
  A ∩ B          A 全部          B 全部          A ∪ B
 ┌───┬───┐     ┌───┬───┐     ┌───┬───┐     ┌───┬───┐
 │ A │ B │     │ A │ B │     │ A │ B │     │ A │ B │
 │███│███│     │███│███│     │███│███│     │███│███│
 │███│███│     │███│   │     │   │███│     │███│   │
 └───┴───┘     └───┴───┘     └───┴───┘     │   │███│
                                            └───┴───┘
```

### 2.5 UNION 和 UNION ALL 的区别？

| 对比项 | UNION | UNION ALL |
|--------|-------|-----------|
| 去重 | 对结果去重 | 不去重 |
| 性能 | 需要排序/哈希去重，慢 | 直接合并，快 |
| 结果集 | 无重复行 | 可能包含重复行 |

**面试回答：**

> UNION 会把两个结果集合并后去重，需要额外的排序或哈希操作；UNION ALL 只合并不去重，性能更好。如果确认两个结果集没有重复或业务上允许重复，优先使用 UNION ALL。

### 2.6 聚合函数与 GROUP BY

```sql
SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_salary
FROM employee
WHERE status = 1
GROUP BY department
HAVING COUNT(*) > 5
ORDER BY avg_salary DESC;
```

**注意事项：**
- `COUNT(*)` 统计所有行，`COUNT(1)` 等价，`COUNT(字段)` 跳过 NULL
- `AVG`、`SUM`、`MAX`、`MIN` 自动忽略 NULL
- GROUP BY 后 SELECT 的非聚合列必须在 GROUP BY 中出现（ONLY_FULL_GROUP_BY 模式）
- 分组统计慢的常见原因：分组列无索引、临时表、文件排序

### 2.7 子查询与 EXISTS

```sql
-- 相关子查询：外表每行都执行一次内层查询
SELECT * FROM orders o
WHERE EXISTS (
    SELECT 1 FROM user u WHERE u.id = o.user_id AND u.vip = 1
);

-- IN 子查询：先执行内层，结果集用于外表过滤
SELECT * FROM orders
WHERE user_id IN (SELECT id FROM user WHERE vip = 1);
```

**IN 和 EXISTS 怎么选？**

| 场景 | 推荐 | 原因 |
|------|------|------|
| 子查询表小，外表大 | IN | 内层结果集小，外表走索引 |
| 子查询表大，外表小 | EXISTS | 外层驱动表小，内层走索引 |

**面试回答：**

> 小表驱动大表的原则：外表小用 EXISTS，子查询结果集小用 IN。MySQL 5.6+ 优化器对 IN 子查询做了很多改写（半连接优化），很多场景下两者性能差异已经不大，但理解原理有助于写出更好的 SQL。

### 2.8 LIMIT 深分页问题

```sql
-- 慢：需要扫描 1000010 行，丢弃前 100 万行
SELECT * FROM orders ORDER BY id LIMIT 1000000, 10;

-- 优化方案一：子查询定位起始 id（要求 id 连续或有序）
SELECT * FROM orders
WHERE id > (SELECT id FROM orders ORDER BY id LIMIT 1000000, 1)
LIMIT 10;

-- 优化方案二：游标/滚动分页，记录上一页最大 id
SELECT * FROM orders WHERE id > #{lastMaxId} ORDER BY id LIMIT 10;

-- 优化方案三：延迟关联，先查主键再回表
SELECT o.* FROM orders o
INNER JOIN (SELECT id FROM orders ORDER BY id LIMIT 1000000, 10) t
ON o.id = t.id;
```

**面试回答：**

> LIMIT 1000000, 10 需要扫描并丢弃前 100 万行，深分页越往后越慢。常见优化有三种：一是子查询先定位起始主键再回表；二是游标分页，记住上一页最大 id，用 WHERE id > lastId 代替 OFFSET；三是延迟关联，先用覆盖索引查出目标主键，再 JOIN 回表取整行。产品允许的话，游标分页是体验和性能最好的方案。

### 2.9 INSERT 相关高频题

**1. 批量插入优化：**

```sql
-- 慢：逐条插入，每次都有网络往返和事务开销
INSERT INTO t(a) VALUES(1);
INSERT INTO t(a) VALUES(2);

-- 快：批量插入
INSERT INTO t(a) VALUES(1), (2), (3), ...;

-- 更快：关闭自动提交，手动控制事务
SET autocommit = 0;
INSERT INTO t(a) VALUES(1), (2), (3);
COMMIT;
```

**2. INSERT ... ON DUPLICATE KEY UPDATE：**

```sql
-- 存在则更新，不存在则插入（依赖唯一键冲突）
INSERT INTO user_stats(user_id, login_count)
VALUES(1001, 1)
ON DUPLICATE KEY UPDATE login_count = login_count + 1;
```

**3. REPLACE INTO 的坑：**

```sql
-- 冲突时先 DELETE 再 INSERT，会重置自增 id、触发删除触发器
REPLACE INTO user(id, name) VALUES(1, 'zhangsan');
```

**面试回答：**

> 批量插入优先用多值 INSERT 减少网络往返；存在则更新用 ON DUPLICATE KEY UPDATE，它基于唯一键冲突做原地更新；REPLACE INTO 冲突时是先删后插，会改变主键、触发删除触发器，可能产生死锁，一般不推荐。

### 2.10 UPDATE 与 DELETE 的安全实践

```sql
-- 危险：忘记 WHERE 全表更新
UPDATE user SET status = 0;

-- 安全实践一：先 SELECT 确认影响范围
SELECT * FROM user WHERE last_login < '2025-01-01';

-- 安全实践二：UPDATE/DELETE 带 LIMIT 控制影响行数
UPDATE user SET status = 0 WHERE last_login < '2025-01-01' LIMIT 100;

-- 安全实践三：开事务，确认后再提交
BEGIN;
UPDATE user SET status = 0 WHERE last_login < '2025-01-01';
SELECT ROW_COUNT();  -- 确认影响行数
COMMIT;  -- 或 ROLLBACK;
```

---

## 三、数据库与表设计

### 3.1 三大范式

| 范式 | 核心要求 | 通俗理解 |
|------|----------|----------|
| 第一范式（1NF） | 每列不可再分 | 字段都是原子的，不要在一个列里存 "1,2,3" |
| 第二范式（2NF） | 非主键列完全依赖主键 | 消除部分依赖，不能只依赖联合主键的一部分 |
| 第三范式（3NF） | 非主键列不传递依赖主键 | 消除传递依赖，非主键列之间不要互相依赖 |

**面试回答：**

> 三大范式是设计指导，核心目的是减少数据冗余和更新异常。但互联网业务中经常做反范式设计，用冗余换查询性能，比如订单表冗余用户昵称，避免 join。范式和性能之间要权衡，不要为了范式而范式。

### 3.2 主键设计

**为什么推荐自增主键？**

> InnoDB 是聚簇索引，数据按主键顺序存储。自增主键每次追加写入页尾部，页写满自然分裂，写入顺序性好；随机主键（如 UUID）会导致频繁的页分裂和碎片，写入性能差且占用更多空间。分布式场景下可以用雪花 ID 等趋势递增的分布式 ID。

**主键设计原则：**
- 业务无关：不要用手机号、身份证做主键（业务变更会灾难）
- 趋势递增：保证聚簇索引写入顺序性
- 不要频繁修改：修改主键代价极大
- 分布式场景：雪花算法、号段模式、UUID（不推荐做聚簇主键）

### 3.3 建表规范（阿里规范精简版）

```sql
CREATE TABLE `order_info` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`       VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '业务订单号',
  `user_id`        BIGINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '用户id',
  `amount`         DECIMAL(16, 2) NOT NULL DEFAULT '0.00' COMMENT '金额',
  `status`         TINYINT         NOT NULL DEFAULT '0' COMMENT '状态:0待支付,1已支付',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id_created` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';
```

**核心规范：**
- 必有字段：id、created_at、updated_at
- 字段 NOT NULL 并给默认值
- 金额用 DECIMAL，禁止 FLOAT/DOUBLE
- 表名、字段名小写，禁用保留字
- 布尔语义用 TINYINT 而不是 CHAR(1)
- 索引命名：主键 pk_、唯一 uk_、普通 idx_
- 单表索引建议不超过 5 个，单索引字段不超过 5 个

### 3.4 大字段处理

- TEXT/BLOB 大字段会拖慢查询（行溢出、额外 IO）
- 建议大字段拆分到扩展表，主表保持精简
- 查询时避免 `SELECT *`，按需取列
- 超大内容考虑存对象存储（OSS），表里只存 URL

---

## 四、索引与事务基础（概览）

> 本章节只做概览性介绍，深入原理（B+ 树、页分裂、MVCC、锁细节）请看 [`mysql原理机制面试知识点.md`](mysql原理机制面试知识点.md)。

### 4.1 什么是索引？

索引是帮助 MySQL 高效获取数据的**排好序的数据结构**。没有索引时查询需要全表扫描 O(n)，有了索引可以类似二分查找快速定位 O(logN)。

**索引的代价：**
- 空间代价：每个索引都是一棵 B+ 树
- 写入代价：INSERT/UPDATE/DELETE 都要维护索引
- 优化器代价：索引太多优化器选择成本上升

### 4.2 索引分类

| 分类维度 | 类型 | 说明 |
|----------|------|------|
| 数据结构 | B+ 树索引、Hash 索引、Full-text 索引 | InnoDB 主流是 B+ 树 |
| 物理 | 聚簇索引、二级索引（辅助索引） | 聚簇索引叶子存整行，二级索引叶子存主键 |
| 字段特性 | 主键索引、唯一索引、普通索引、前缀索引 | |
| 字段数量 | 单列索引、联合索引 | 联合索引遵循最左前缀原则 |

### 4.3 最左前缀原则

联合索引 `(a, b, c)` 相当于建了 `(a)`、`(a, b)`、`(a, b, c)` 三个索引。

```sql
-- 能走索引
WHERE a = 1
WHERE a = 1 AND b = 2
WHERE a = 1 AND b = 2 AND c = 3
WHERE a = 1 AND b > 2          -- a 走索引，b 用索引下推

-- 不能走索引（缺少最左列 a）
WHERE b = 2
WHERE b = 2 AND c = 3

-- 部分走索引（a 走索引，c 不能跳过 b）
WHERE a = 1 AND c = 3
```

**面试回答：**

> 联合索引是按定义顺序从左到右排序的，先按 a 排，a 相同再按 b 排。所以查询条件必须从最左列开始连续命中，才能利用索引的有序性。遇到范围查询后，后面的列无法继续走索引的精确定位。这也是建联合索引时要把等值条件列放前面、范围条件列放最后、区分度高的列放前面的原因。

### 4.4 索引失效的常见场景

```sql
-- 1. 对索引列做函数或运算
WHERE YEAR(created_at) = 2026      -- 失效
WHERE created_at >= '2026-01-01'   -- 改写后有效

-- 2. 隐式类型转换
WHERE phone = 13800001111          -- phone 是 VARCHAR，传数字失效
WHERE phone = '13800001111'        -- 正确

-- 3. 前导模糊查询
WHERE name LIKE '%abc'             -- 失效
WHERE name LIKE 'abc%'             -- 有效

-- 4. 联合索引不满足最左前缀
-- 5. OR 连接非索引列
WHERE a = 1 OR b = 2               -- b 无索引则全表扫描

-- 6. NOT IN、!= 在特定场景下优化器放弃索引
```

### 4.5 什么是事务？

事务是一组 SQL 操作的逻辑单元，满足 ACID 四大特性：

| 特性 | 英文 | 含义 | 实现手段 |
|------|------|------|----------|
| 原子性 | Atomicity | 全部成功或全部回滚 | undo log |
| 一致性 | Consistency | 数据从一个一致状态到另一个一致状态 | 其他三者共同保证 |
| 隔离性 | Isolation | 事务之间互不干扰 | MVCC + 锁 |
| 持久性 | Durability | 提交后永久生效 | redo log |

**面试回答：**

> ACID 中，原子性靠 undo log 实现，回滚时根据 undo log 反向补偿；持久性靠 redo log 实现，WAL 机制先写日志再刷数据页；隔离性靠 MVCC 和锁实现，读用快照、写加锁；一致性是最终目的，由前三个特性共同保证。

### 4.6 事务的基本使用

```sql
BEGIN;                                -- 开启事务
UPDATE account SET balance = balance - 100 WHERE id = 1;
UPDATE account SET balance = balance + 100 WHERE id = 2;
COMMIT;                               -- 提交
-- ROLLBACK;                          -- 回滚
```

**并发事务问题：**

| 问题 | 含义 |
|------|------|
| 脏读 | 读到其他事务未提交的数据 |
| 不可重复读 | 同一事务内两次读同一行结果不同（别人 UPDATE 了） |
| 幻读 | 同一事务内两次范围查询行数不同（别人 INSERT 了） |

### 4.7 EXPLAIN 执行计划

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 1001;
```

**重点列：**

| 列 | 关注点 |
|----|--------|
| type | 访问类型：system > const > eq_ref > ref > range > index > ALL |
| key | 实际使用的索引 |
| key_len | 索引使用长度，判断联合索引用了几列 |
| rows | 预估扫描行数 |
| Extra | Using index（覆盖索引）、Using filesort（额外排序）、Using temporary（临时表） |

**type 等级说明：**

| 等级 | 含义 |
|------|------|
| const | 主键或唯一索引等值查询，最多一行 |
| eq_ref | join 时被驱动表走主键/唯一索引 |
| ref | 普通索引等值查询 |
| range | 索引范围扫描（BETWEEN、>、<、IN） |
| index | 扫描整棵索引树，比 ALL 略好 |
| ALL | 全表扫描，必须优化 |

---

## 五、高频面试题速答

### 5.1 MySQL 一条 SQL 是怎么执行的？

连接器 -> 分析器 -> 优化器 -> 执行器 -> 存储引擎，8.0 前还有查询缓存（已移除）。

### 5.2 InnoDB 和 MyISAM 的区别？

事务、行级锁、聚簇索引、崩溃恢复是 InnoDB 的核心优势；MyISAM 只适合读多写少且不要事务的场景。

### 5.3 char 和 varchar 怎么选？

定长用 char（手机号、MD5），变长用 varchar（用户名、地址），注意 varchar(N) 的 N 是字符数。

### 5.4 drop、truncate、delete 区别？

delete 是 DML 可回滚可带条件；truncate 是 DDL 清空重建不可回滚；drop 连表结构删除。

### 5.5 WHERE 和 HAVING 区别？

WHERE 分组前过滤行、不能用聚合函数；HAVING 分组后过滤、可以用聚合函数。

### 5.6 UNION 和 UNION ALL 区别？

UNION 去重有排序开销；UNION ALL 不去重性能好，无重复需求时优先用。

### 5.7 深分页怎么优化？

子查询定位起始 id、游标分页（WHERE id > lastId）、延迟关联三种方案。

### 5.8 最左前缀原则是什么？

联合索引按定义顺序排序，查询条件必须从最左列开始连续命中；范围查询之后的列失效。

### 5.9 索引失效场景有哪些？

函数运算、隐式类型转换、前导模糊、不满足最左前缀、OR 连接无索引列等。

### 5.10 ACID 靠什么实现？

原子性 undo log、持久性 redo log、隔离性 MVCC + 锁、一致性是前三者共同的目标。
