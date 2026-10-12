# fastutil Object2ByteOpenHashMap 对比 HashMap 面试知识点

## 一、fastutil 基础概念

### 1.1 fastutil 是什么？

fastutil 是一个扩展 Java 集合框架（Java Collections Framework）的开源类库，由意大利米兰大学的 Sebastiano Vigna 维护。它的核心定位是**类型特化（type-specific）集合**。

Java 泛型是**类型擦除**实现的，泛型容器无法直接持有原始类型（`int`、`byte`、`long` 等），只能使用对应的包装类（`Integer`、`Byte`、`Long`），由此带来两个问题：

1. **装箱/拆箱开销**：每次写入都要 `valueOf` 创建包装对象，读取要拆箱。
2. **内存放大**：一个 `byte` 只需 1 字节，但一个 `Byte` 对象在 64 位 JVM 上占 16 字节。

fastutil 的做法是：为每一种「key 类型 → value 类型」组合**单独生成一个类**，内部直接用原始类型数组存储，彻底绕开泛型和装箱。

一句话概括：

> fastutil 通过"为每种类型组合生成专属实现 + 原始类型数组存储"，把集合的内存占用和读写开销压缩到 JDK 集合的几分之一，代价是类库体积大、类数量爆炸。

### 1.2 Object2ByteOpenHashMap 命名解读

类名本身就是 fastutil 的命名规则，拆开看：

| 组成部分 | 含义 |
|----------|------|
| `Object2Byte` | key 是 `Object` 类型，value 是 `byte` 原始类型 |
| `Open` | 使用**开放寻址法**（Open Addressing）解决哈希冲突 |
| `HashMap` | 哈希表语义，对外行为类似 `Map` |

所以 `Object2ByteOpenHashMap<String>` 等价于一个 `Map<String, Byte>`，但 value 以原始 `byte` 形式存储，**没有任何装箱**。

### 1.3 fastutil 集合家族速览

fastutil 的命名规则是 `KeyType2ValueType + 实现变体`：

| 类型后缀 | 含义 |
|----------|------|
| `Object` | 对象类型 |
| `Int` / `Long` / `Byte` / `Short` / `Double` / `Float` / `Char` / `Boolean` | 对应原始类型 |

常见组合示例：

| 类名 | 等价的 JDK 语义 |
|------|-----------------|
| `Object2ByteOpenHashMap` | `Map<Object, Byte>` |
| `Int2ObjectOpenHashMap` | `Map<Integer, Object>` |
| `Long2LongOpenHashMap` | `Map<Long, Long>` |
| `Int2IntOpenHashMap` | `Map<Integer, Integer>` |
| `Object2ObjectOpenHashMap` | `Map<K, V>`（key/value 都是对象，但仍是开放寻址） |

实现变体：

| 变体 | 特点 |
|------|------|
| `OpenHashMap` | 开放寻址，最快、最省内存，**不保证迭代顺序** |
| `LinkedOpenHashMap` | 开放寻址 + 双向链表，保持插入/访问顺序 |
| `ArrayMap` | 线性数组实现，适合几十条以内的小数据量 |
| `AVLTreeMap` / `RBTreeMap` | 平衡树实现，有序遍历 |

---

## 二、底层实现原理对比

### 2.1 HashMap 的底层结构（JDK 8+）

```text
table[]（Node 数组）
  ┌────┐
  │ 0  │ → Node(hash, key, value, next) → Node → ...（链表）
  ├────┤
  │ 1  │ → null
  ├────┤
  │ 2  │ → Node → Node → Node → ...
  ├────┤
  │... │        （链表长度 ≥ 8 且表长 ≥ 64 时转红黑树 TreeNode）
  └────┘
```

核心要点：

- 数组 + 链表 + 红黑树（链地址法解决冲突）。
- 每个条目是一个 `Node` 对象，包含 `hash`、`key` 引用、`value` 引用、`next` 引用。
- value 是 `Byte` 时，实际存的是指向 `Byte` 对象的**引用**。
- 默认容量 16，负载因子 0.75，容量始终为 2 的幂。

### 2.2 Object2ByteOpenHashMap 的底层结构

```text
key[]（Object 数组）        value[]（byte 数组）      两数组平行，下标一一对应
  ┌────┬──────┐              ┌────┬──────┐
  │ 0  │ null │              │ 0  │  0   │   ← key 为 null 表示空槽
  ├────┼──────┤              ├────┼──────┤
  │ 1  │ "a"  │              │ 1  │  10  │   ← map: "a" -> 10
  ├────┼──────┤              ├────┼──────┤
  │ 2  │ "b"  │              │ 2  │  20  │   ← map: "b" -> 20
  ├────┼──────┤              ├────┼──────┤
  │ 3  │ null │              │ 3  │  0   │   ← 空槽
  └────┴──────┘              └────┴──────┘
```

核心要点：

- **两个平行数组**：`Object[] key` + `byte[] value`，下标对齐。
- **没有 Entry/Node 对象**：条目信息完全内联在数组里。
- **开放寻址 + 线性探测**：冲突时向后找下一个空槽（`slot = (slot + 1) & mask`）。
- 表大小为 2 的幂，用位运算 `& mask` 取模；对 `hashCode()` 做了 murmur 风格的最终扰动（`HashCommon.mix`），降低线性探测的聚集概率。
- 数组额外多一个槽位（`key[n]`）专门存放 **null key**，用 `containsNullKey` 标志区分——所以它其实支持 null key，这点和 HashMap 一致。

### 2.3 哈希冲突解决：链地址法 vs 线性探测

| 对比项 | HashMap（链地址法） | Object2ByteOpenHashMap（线性探测） |
|--------|---------------------|-----------------------------------|
| 冲突处理 | 同槽条目串成链表，超阈值转红黑树 | 顺次探测下一个槽位 |
| 内存布局 | Node 对象散落在堆中，指针跳转 | 数据连续存放在数组中，**CPU 缓存友好** |
| 最坏复杂度 | O(log n)（树化后） | O(n)（哈希极度不均时退化） |
| 删除 | 直接从链/树上摘除节点 | 需要修复探测链（见 2.5） |
| 对哈希函数质量 | 相对宽容 | 更敏感（fastutil 用 mix 扰动缓解） |

面试可以这样回答：

> 链地址法把冲突元素挂到链表上，删除简单、对坏哈希不敏感，但每个条目都是独立对象，内存碎片化、缓存不友好。线性探测把所有数据压在数组里，冲突了就往后挪一格，内存连续、缓存命中率高、无对象分配，但删除要做探测链修复，且对哈希质量要求更高。fastutil 选线性探测，本质是用实现复杂度换内存和速度。

### 2.4 扩容机制对比

| 对比项 | HashMap | Object2ByteOpenHashMap |
|--------|---------|------------------------|
| 触发条件 | size > capacity × 0.75 | size > n × loadFactor（默认 0.75） |
| 扩容方式 | 容量翻倍，JDK 8 按 hash 高低位拆分链表 | 容量翻倍，全量 rehash 重新探测插入 |
| 扩容成本 | 均摊 O(1)，单次 O(n) | 均摊 O(1)，单次 O(n) |
| 预防手段 | 指定初始容量 | 构造时传 `expectedSize`；还提供 `trim()` 主动收缩 |

```java
// 预估 100 万条，避免反复 rehash
Object2ByteOpenHashMap<String> map =
        new Object2ByteOpenHashMap<>(1_000_000, 0.75f);
```

### 2.5 删除操作对比（高频考点）

**HashMap**：找到 Node 后直接从链表/红黑树上摘除即可，不影响其他条目的定位。

**Object2ByteOpenHashMap**：**不能简单地把槽位置 null**。因为线性探测的查找依赖"冲突链上没有空洞"——如果中间挖了个洞，后面同链的元素就永远找不到了。

fastutil 的做法是 `shiftKeys`（Knuth《计算机程序设计艺术》卷三算法 6.4 R 的变体）：删除后，把该槽位之后**同一条探测链上的元素逐个重新前移**，填补空洞，保证探测链完整。

```text
删除前（"b" 与 "c" 都哈希到槽 1）：
  [1]: "b"   [2]: "c"   [3]: null

删除 "b"：
  错误做法：[1] = null  →  查 "c" 时从槽 1 探测到 null，误判不存在！
  正确做法：把 "c" 从槽 2 前移到槽 1 →  [1]: "c"   [2]: null
```

面试可以这样回答：

> 开放寻址的删除不能直接置空，否则会截断探测链，导致后续元素"丢失"。标准做法有两种：墓碑标记（tombstone）或删除后局部重排。fastutil 用的是后者——shiftKeys 把被删槽位后面同链元素逐个前移，这是 Knuth 算法 6.4 R 的经典实现。代价是删除操作比 HashMap 重，所以 fastutil 更适合写少读多或删除不频繁的场景。

---

## 三、内存占用深度对比（核心卖点）

### 3.1 先说一个诚实的坑：Byte 有缓存

`Byte.valueOf()` 对 **-128 ~ 127 全范围有缓存**（`ByteCache`），而 `byte` 的取值恰好就是 -128 ~ 127。所以 `HashMap<String, Byte>` 装箱时**并不会为每个条目新建 Byte 对象**，value 侧的装箱分配开销其实趋近于零。

这一点如果主动说出来，会显得理解很深。但即便如此，HashMap 依然输得很惨——因为**真正的内存大头是 Node 对象本身**。

### 3.2 Node 对象 vs 平行数组

以 64 位 JVM、开启压缩指针（默认）计算：

**HashMap<String, Byte> 每条目的开销：**

```text
Node 对象：
  对象头 12B + int hash 4B + key 引用 4B + value 引用 4B + next 引用 4B
  = 28B，对齐后 32 字节/条目

table 数组槽位摊销：
  负载因子 0.75，每条目摊销 4B ÷ 0.75 ≈ 5.3 字节

合计：约 37 ~ 40 字节/条目（不含 key 字符串本身）
```

**Object2ByteOpenHashMap<String> 每条目的开销：**

```text
key[] 数组槽位摊销：  4B ÷ 0.75 ≈ 5.3 字节
value[] 数组槽位摊销：1B ÷ 0.75 ≈ 1.3 字节

合计：约 6.7 ~ 10.5 字节/条目（不含 key 字符串本身）
```

**没有任何堆对象分配**——条目就是数组里的一个格子。

### 3.3 百万级数据估算实例

场景：100 万条 `String → byte` 映射，负载因子均取 0.75，表容量取 2^21 = 2,097,152 槽：

| 内存项 | HashMap<String, Byte> | Object2ByteOpenHashMap<String> |
|--------|----------------------|-------------------------------|
| 容器数组 | table：2^21 × 4B ≈ 8 MB | key[]：8 MB + value[]：2 MB |
| 条目对象 | Node：100 万 × 32B ≈ 30.5 MB | 无 |
| value 装箱 | 0（Byte 缓存全命中） | 无（原始类型） |
| **合计** | **≈ 38.5 MB** | **≈ 10 MB** |

**节省约 74%，单条目开销约为 HashMap 的 1/4。**

如果 value 换成 `Integer`（超出 -128~127 缓存范围），HashMap 还要额外为每条目分配 16B 的 Integer 对象，差距会进一步拉大到 3~4 倍以上。

### 3.4 别忘了：key 才是真正的大头

一个 10 字符的 `String` 在 64 位 JVM 上约占 24（对象头+字段）+ 2×10（char 数组，JDK 9+ 压缩后可能 1B/字符）≈ 40~56 字节，**远超容器本身的开销**。

所以严谨的说法是：

> fastutil 优化的是「容器开销」，即每条目从 ~40B 降到 ~7B；key 对象本身的内存两边完全一样。如果 key 是长字符串，容器开销占比小，收益会被稀释；如果 key 是短对象或本身就在别处持有（只是引用复用），收益非常可观。

---

## 四、性能对比

### 4.1 读写性能

| 操作 | HashMap<String, Byte> | Object2ByteOpenHashMap<String> | 差异来源 |
|------|----------------------|-------------------------------|----------|
| `put` | 装箱（缓存命中）+ 可能分配 Node | 直接写数组，零分配 | 无对象分配 |
| `get` | 定位槽 → 遍历链表比对 | 线性探测，数组连续访问 | 缓存友好 |
| `containsKey` | 同 get | 同 get | — |

量级参考（具体以实测为准）：

- 纯 get/put 吞吐：fastutil 通常快 **1.2 ~ 2 倍**（数据越密集、哈希越均匀，优势越明显）。
- 遍历：fastutil 通常快 **3 ~ 5 倍**（直接扫数组 vs 逐个解引用 Node）。

### 4.2 遍历性能与 fastIterator

HashMap 遍历要沿着 table → Node → next 逐个解引用，指针跳转破坏缓存局部性。

fastutil 直接顺序扫描两个平行数组，且提供 `fastIterator()`，迭代过程中**不创建任何 Entry 对象**：

```java
Object2ByteOpenHashMap<String> map = new Object2ByteOpenHashMap<>();
map.put("a", (byte) 1);
map.put("b", (byte) 2);

// 推荐：fast 遍历，零对象分配
for (Object2ByteMap.Entry<String> e : map.object2ByteEntrySet()) {
    // e 是复用的对象，不要持有引用
    String k = e.getKey();
    byte v = e.getByteValue();
}
```

注意：fast 迭代器返回的 Entry 是**内部复用对象**，循环里用完即弃，不能存到别处。

### 4.3 GC 压力

| 维度 | HashMap | Object2ByteOpenHashMap |
|------|---------|------------------------|
| put 时分配 | Node 对象（每次扩容/新增都产生） | 零分配（除扩容时换数组） |
| 年轻代压力 | 高频 put 下大量短命 Node | 几乎没有 |
| 大对象 | 扩容时旧 table/旧 Node 链整体变垃圾 | 扩容时旧的两个数组变垃圾 |

在 GC 敏感的服务（低延迟网关、实时风控、高频交易）里，「put 零分配」往往比吞吐数字更有价值——它直接减少了 young GC 频率，降低尾延迟。

---

## 五、功能与语义差异（易踩的坑）

### 5.1 null 语义

| 项 | HashMap | Object2ByteOpenHashMap |
|----|---------|------------------------|
| null key | 支持（固定落到槽 0） | 支持（固定存于 key[n] 特殊槽） |
| null value | 支持 | **不支持**（value 是原始 byte，没有 null） |

### 5.2 get 的返回值语义（最大的坑）

`byte` 无法表达"不存在"，所以 fastutil 的 `getByte(key)` 在 key 不存在时返回**默认返回值**（初始为 0）：

```java
Object2ByteOpenHashMap<String> map = new Object2ByteOpenHashMap<>();
map.put("a", (byte) 0);

map.getByte("a");        // 0（存在，值就是 0）
map.getByte("not-exist"); // 也是 0！无法区分

// 正确姿势一：containsKey 判断
if (map.containsKey("not-exist")) { ... }

// 正确姿势二：自定义默认返回值，用哨兵值
map.defaultReturnValue((byte) -1);
map.getByte("not-exist"); // -1
```

另外注意接口方法的选择：

```java
map.get(key);      // 返回 Byte（装箱），来自 Map 接口，别用
map.getByte(key);  // 返回 byte（原始类型），fastutil 特化方法，用这个
```

### 5.3 迭代顺序

- 两者都**不保证迭代顺序**。
- 需要插入序时：JDK 用 `LinkedHashMap`，fastutil 用 `Object2ByteLinkedOpenHashMap`。

### 5.4 线程安全

两者都**线程不安全**。fastutil 没有提供并发版本，需要并发时：

- 低并发：`Collections.synchronizedMap` 包装（性能差）。
- 高并发：退回 `ConcurrentHashMap<String, Byte>`，或按 key 分片后每片一个 fastutil map（类似分段锁思路）。

### 5.5 Maven 依赖与体积

```xml
<dependency>
    <groupId>it.unimi.dsi</groupId>
    <artifactId>fastutil</artifactId>
    <version>8.5.13</version>
</dependency>
```

注意：fastutil 完整 jar 约 **20+ MB**（类太多了）。对体积敏感的服务可以：

- 改用 `fastutil-core`（只含核心集合，体积小很多）；
- 或用 Maven Shade + 最小化 jar，只保留实际用到的类。

---

## 六、适用场景与选型建议

### 6.1 适合的场景

| 场景 | 说明 |
|------|------|
| value 是原始类型 | 如 `String → byte`（标签位、状态位、等级）、`long → long`（uid → 特征值） |
| 百万级以上条目 | 容器开销被放大，节省的绝对内存可观 |
| 内存敏感 | 缓存、索引、字典编码（字符串 → 短 id） |
| GC 敏感 / 低延迟 | put 零分配，减少 young GC |
| 高频遍历/全量扫描 | 数组连续访问，遍历快数倍 |

典型落地案例：

- **字典编码**：把字符串维度值编码成 byte/short id，`Object2ByteOpenHashMap` 做正向字典。
- **风控/画像标签**：uid → 标签位（byte 每位一个布尔语义，一个 byte 存 8 个标签）。
- **搜索引擎/大数据**：Lucene、Elasticsearch 内部大量使用 fastutil 存 doc id 映射。

### 6.2 不适合的场景

| 场景 | 原因 |
|------|------|
| value 是对象 | 用 `Object2ObjectOpenHashMap`（仍有开放寻址收益）或普通 HashMap |
| 需要并发 | fastutil 无并发实现 |
| 数据量小（几百条以内） | 收益微小，不值得引入 20MB 依赖 |
| 删除极频繁 | shiftKeys 修复探测链，删除成本高于 HashMap |
| 需要有序遍历 | 用 `AVLTreeMap`/`RBTreeMap` 变体或 `TreeMap` |

### 6.3 同类库对比

| 库 | 特点 | 现状 |
|----|------|------|
| fastutil | 类型最全、性能标杆、社区活跃 | 推荐 |
| Eclipse Collections | 功能更全（含原始类型 List/Map/Bag），API 更现代 | 推荐 |
| HPPC | 轻量，只有原始类型容器 | 维护少 |
| Trove | 曾经流行 | 基本停更，不建议新项目使用 |

---

## 七、面试高频问题与参考回答

### Q1：Object2ByteOpenHashMap 为什么比 HashMap 省内存？

> 三层原因：第一，value 用原始 byte 数组存储，每条目 1 字节，而 HashMap 存的是 Byte 引用；第二，没有 Node 对象——HashMap 每个条目是一个 32 字节的堆对象，fastutil 的条目就是数组里的格子；第三，开放寻址让所有数据压缩在两个平行数组里，没有链表指针。综合下来单条目容器开销从约 40 字节降到约 7 字节，百万级数据能省 70% 以上。另外要补充一点：Byte 有 -128~127 的全量缓存，所以 HashMap 的装箱分配其实不多，真正的差距来自 Node 对象本身。

### Q2：开放寻址和链地址法（HashMap 的方式）有什么区别？

> 链地址法把冲突元素挂链表，JDK 8 后超 8 个节点还会转红黑树；开放寻址把冲突元素顺次放到后面第一个空槽。开放寻址数据全在数组里，缓存局部性好、无对象分配、内存密度高；但删除要做探测链修复（fastutil 的 shiftKeys），哈希质量差时容易聚集退化。fastutil 用 2 的幂表长 + murmur 风格扰动函数来缓解聚集。

### Q3：fastutil 的删除为什么不能直接把槽位置 null？

> 线性探测的查找逻辑是：从哈希槽开始向后扫，遇到 null 才停。如果删除时把中间槽置 null，会提前终止扫描，导致同链后面的元素查不到。fastutil 的 shiftKeys 会在删除后把同链元素逐个前移补洞，保证探测链完整，这是 Knuth 算法 6.4 R 的标准做法。

### Q4：getByte 返回 0，怎么区分「不存在」和「值就是 0」？

> 三种方式：containsKey 先判再取（两次探测，稍慢）；defaultReturnValue 设置哨兵默认值，比如业务上 byte 永远取正数时设 -1；或者业务设计上保证 0 是合法的"默认态"，不需要区分。这也是所有 primitive-value map 的通病——原始类型没有 null 语义。

### Q5：什么时候不该用 fastutil？

> 四种情况：value 是对象类型（收益只剩开放寻址那部分）；需要并发访问（没有并发实现）；数据量小，几百条的收益抵不过引入 20MB 依赖的复杂度；删除操作极频繁（shiftKeys 成本高）。另外如果 key 本身是长字符串，key 内存占大头，容器优化的相对收益会被稀释，要先做内存构成分析再决定。

### Q6：fastutil 的 jar 很大，生产上怎么处理？

> 完整 jar 20 多 MB，因为每种类型组合都有独立类。方案：用 fastutil-core 只引核心集合；或 Maven Shade 打包时做最小化裁剪，只保留实际引用到的类；对体积要求极端苛刻的场景可以评估 Eclipse Collections 或 HPPC。

---

## 八、总结速记表

| 维度 | HashMap<String, Byte> | Object2ByteOpenHashMap<String> |
|------|----------------------|-------------------------------|
| 冲突解决 | 链地址法（链表 + 红黑树） | 开放寻址（线性探测） |
| 存储 | Node 对象 + Byte 引用 | key[] + value[] 平行数组 |
| 单条目容器开销 | ~40 字节 | ~7 字节 |
| put 分配 | Node 对象 | 零分配 |
| value 装箱 | 有（Byte 缓存可命中） | 无 |
| null key / null value | 支持 / 支持 | 支持 / 不支持 |
| key 不存在时 get | 返回 null | 返回默认值（默认 0） |
| 删除 | 摘链，O(1)~O(log n) | shiftKeys 修复探测链 |
| 迭代顺序 | 不保证 | 不保证（Linked 变体可保序） |
| 线程安全 | 否 | 否 |
| 遍历性能 | 指针跳转，慢 | 数组顺序扫描，快 3~5 倍 |
| 适用 | 通用 | 原始类型 value + 大数据量 + 内存/GC 敏感 |

一句话总结：

> Object2ByteOpenHashMap 用「平行数组 + 线性探测 + 原始类型存储」三板斧，把 Map 的容器开销压缩到 HashMap 的四分之一，代价是失去 null value 语义、删除变重、以及引入一个 20MB 的依赖——它是内存/GC 敏感场景下对 HashMap 的精准特化，而不是全场景替代品。
