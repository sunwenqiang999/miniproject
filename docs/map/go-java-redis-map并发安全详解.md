# Go / Java / Redis Map 与并发安全 Map 插入、删除、扩容机制详解

> 面试高频点：三者底层结构、插入删除流程、扩容触发与迁移机制、并发安全方案，以及横向对比。

---

## 目录

- [一、Go map](#一go-map)
- [二、Java HashMap](#二java-hashmap)
- [三、Redis dict](#三redis-dict)
- [四、横向对比总表](#四横向对比总表)

---

## 一、Go map

### 1. 底层结构

```go
// runtime/map.go 中的核心结构（简化）
type hmap struct {
    count      int            // 元素个数（len 的来源）
    flags      uint8          // 状态标记（是否正在写入/扩容/等量扩容等）
    B          uint8          // bucket 数量的对数，bucket 数 = 2^B
    noverflow  uint16         // 溢出桶近似数量
    hash0      uint32         // 哈希种子（防哈希碰撞攻击）
    buckets    unsafe.Pointer // 当前桶数组
    oldbuckets unsafe.Pointer // 扩容前的旧桶数组（渐进式搬迁用）
    nevacuate  uintptr        // 搬迁进度计数器
    extra      *mapextra      // 溢出桶相关信息
}

type bmap struct {
    tophash [8]uint8 // 每个桶存 8 个 key/value，tophash 存 key 哈希的高 8 位
    // 之后依次是 8 个 key、8 个 value、溢出桶指针（编译期决定布局）
}
```

- **一个 bucket 固定存 8 个键值对**，超过则挂在溢出桶（overflow bucket）后面形成链。
- `tophash` 同时承担槽位状态标记：`emptyOne`（本格空）、`emptyRest`（本格及之后全空，加速查找终止）、`evacuatedX/Y`（搬迁标记）。

### 2. 插入流程（`mapassign`）

1. **并发写检测**：检查 `flags & hashWriting`，已被其他协程持有写锁则直接 `fatal error: concurrent map writes`（不可 recover）。
2. 用 `hash0` 计算 key 的哈希值。
3. **低位定位桶**：`bucket = hash & (2^B - 1)`；**高位定位槽**：`tophash = hash >> 56`（高 8 位）。
4. 遍历该桶及其溢出桶链：
   - 找到相同 key → 更新 value 返回；
   - 否则记住第一个空槽位。
5. 若无空位，分配溢出桶追加。
6. **插入前触发扩容判断**（见下文），若正在扩容则先帮当前桶完成搬迁（`growWork`），再在搬迁后的桶上插入。

### 3. 扩容机制（Go 最经典的点）

**触发条件（`overLoadFactor` / `tooManyOverflowBuckets`）**：

| 条件 | 阈值 | 扩容类型 |
|---|---|---|
| 负载因子过高 | `count / 2^B > 6.5` | **翻倍扩容**（B+1） |
| 溢出桶过多 | B < 16 时 `noverflow >= 2^B`；B ≥ 16 时 `noverflow >= 2^15` | **等量扩容**（same-size，不重排只压缩碎片） |

> 负载因子 6.5 是空间与查找效率的平衡点：太小浪费内存，太大溢出桶链变长。

**渐进式搬迁（Incremental Evacuation）**：
- 扩容不是一次性拷贝，而是新桶数组就绪后挂到 `buckets`，旧数组挂到 `oldbuckets`；
- 之后**每次写操作（插入/修改/删除）最多搬迁 2 个桶**：当前访问的桶 + `nevacuate` 指向的顺序桶；
- 每个桶的搬迁粒度为整个桶链（含溢出桶），键值对按新 `B` 的低位重新分配——**注意 Go 的搬迁会重新 hash 分配，不是 Java 那种「i 或 i+oldCap」的巧算**；
- 全部搬迁完成才释放 `oldbuckets`。
- 副作用：扩容期间 map 内存约为 2 倍，且**扩容只涨不缩**——删除大量元素后内存不会自动归还（需要重新 new 一个 map 拷贝来"瘦身"）。

### 4. 删除流程（`mapdelete`）

1. 定位桶与槽位（同样会触发搬迁协助）；
2. 清空 tophash（标记 `emptyOne`）、清 key/value 指针（利于 GC）；
3. 向后探测：若后续全空，则把本格及前面连续的 `emptyOne` 都改成 `emptyRest`，缩短查找探测链；
4. `count--`，**不触发缩容**。

### 5. 并发安全：sync.Map

原生 map 并发读写会 panic，Go 提供 `sync.Map`，**空间换时间的"读写分离"结构**：

```go
type Map struct {
    mu     Mutex
    read   atomic.Value // readOnly 结构，含只读 map[any]*entry + amended 标记
    dirty  map[any]*entry // 加锁可读写的全量/增量数据
    misses int            // read miss 计数
}
```

**核心机制**：
- **读**：先无锁读 `read`；命中直接返回（**完全无锁，O(1)**）；未命中加锁读 `dirty`，`misses++`。
- **写**：命中 read 中的 entry 直接 CAS 改值；否则加锁写 dirty。若 dirty 为 nil，先从 read 复制（跳过已删除项）。
- **read↔dirty 晋升**：`misses >= len(dirty)` 时把 dirty 整体提升为 read（O(1) 指针交换），重建 dirty；反之 dirty 为 nil 时从 read 重建 dirty（O(n) 拷贝）。
- **删除**：read 中的 entry 打 `expunged` 标记（惰性删除），dirty 中真删。

**适用场景**：读多写少、key 集合相对固定（如连接池、缓存）。写多或频繁增删时，反复 read↔dirty 晋升的拷贝代价不如 `RWMutex + map`。

---

## 二、Java HashMap

### 1. 底层结构（JDK 8）

```java
transient Node<K,V>[] table;   // 桶数组，长度恒为 2 的幂
int threshold;                 // 扩容阈值 = capacity * loadFactor(0.75)
static final int TREEIFY_THRESHOLD   = 8;  // 链表→红黑树阈值
static final int UNTREEIFY_THRESHOLD = 6;  // 树→链表阈值
static final int MIN_TREEIFY_CAPACITY = 64; // 低于此容量只扩容不树化
```

- **数组 + 链表 + 红黑树**三段结构：桶内链表长度 ≥ 8 **且**数组长度 ≥ 64 才树化（否则优先扩容，避免小表树化的浪费）；
- 树节点退化阈值是 6 而不是 8——中间留一个缓冲带，防止在 8 附近频繁增删导致反复树化/退化抖动。

### 2. hash 扰动与定位

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
// 桶下标
i = (n - 1) & hash;
```

- 高 16 位异或低 16 位：**让高位也参与低位运算**，因为 `(n-1) & hash` 只用了低位，扰动能显著降低小表时的碰撞率；
- 容量取 2 的幂：把取模运算变成位与，且为扩容的"高低位拆分"埋下伏笔。

### 3. 插入流程（`putVal`）

1. table 为空先 `resize()` 初始化（懒加载）；
2. 桶位为空 → 直接放入新节点；
3. 桶头 key 相同 → 覆盖；
4. 树节点 → `putTreeVal` 走红黑树插入（含旋转平衡）；
5. 链表 → **尾插法**遍历（key 相同则覆盖），插入后链表长度达到 8 调 `treeifyBin`（容量 < 64 时改为扩容）；
6. `++size > threshold` → `resize()`；
7. `modCount++` 支持 fail-fast（迭代中结构性修改抛 `ConcurrentModificationException`）。

### 4. 扩容（`resize`，JDK 8 的精髓）

- 新容量 = 旧容量 × 2，新阈值也 ×2（上限 2^30）；
- **关键优化——高低位拆分**：容量翻倍后 `n-1` 多出一个二进制位参与 `&` 运算，所以原桶 i 上的节点在新表中**只会出现在 `i` 或 `i + oldCap`** 两个位置：

```java
if ((e.hash & oldCap) == 0)  → 留在低位 loChain（下标 i）
else                          → 进入高位 hiChain（下标 i + oldCap）
```

- 无需重新计算 hash，也**不需要像 JDK 7 那样对所有 key 重新取模**，且尾插保持原有相对顺序。

**JDK 7 vs JDK 8 的并发坑**：
- JDK 7 头插法 + 多线程并发 resize，两个线程可能形成**环形链表**，下次 get 触发 CPU 100% 死循环；
- JDK 8 改尾插法消除了环，但并发 put 仍会**丢数据/覆盖**（两个线程同时写同一空桶），`modCount` 也帮不了你——所以 HashMap 永远不要在并发场景裸用。

### 5. 删除（`removeNode`）

- 链表：常规 unlink；树节点：红黑树删除，节点数 ≤ 6 退化回链表；
- 同样**不缩容**，删除后空间不回收。

### 6. 并发安全：ConcurrentHashMap

#### JDK 7：Segment 分段锁
- `Segment[]`（默认 16 段，即并发度 16），每个 Segment 继承 `ReentrantLock`，内部是 `HashEntry[] + 链表`；
- 写锁段，读大部分无锁（value/next 用 volatile）；`size()` 最多尝试 3 次无锁统计，失败则全段加锁；
- 缺点：锁粒度仍是"段"，内存占用大，扩容锁整段。

#### JDK 8：CAS + synchronized 桶锁（当前主流）

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    ...
    if ((f = tabAt(tab, i)) == null) {
        if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value))) // 空桶 CAS 插入
            break;
    } else if ((fh = f.hash) == MOVED) {
        tab = helpTransfer(tab, f);   // 发现正在扩容，先帮忙搬
    } else {
        synchronized (f) {            // 只锁桶头节点
            // 链表尾插或红黑树插入
        }
    }
    ...
    addCount(1L, binCount);           // 计数并可能触发扩容
}
```

- **读完全无锁**：Node 的 `val`、`next` 均为 volatile，配合 `Unsafe.getObjectVolatile`；
- **写锁粒度 = 单个桶的头节点**，不同桶可真正并行写，并发度 = 表长度；
- **size 统计**：`baseCount + CounterCell[]` 分段计数（思想同 LongAdder），无竞争时 CAS 累加 baseCount，有竞争则落到 CounterCell 分散热点，`size()` 累加两者——**高并发下 size() 是近似值**（统计期间可能有并发写入）。

#### JDK 8 的多线程协作扩容（transfer）

1. 触发：put 后 `addCount` 发现超阈值，或树化时表长 < 64；
2. 分配 `nextTable`（2 倍长），`sizeCtl` 设为 `-(resizeStamp << 16 + 2)`（负数 = 扩容中）；
3. 发起线程按 **stride（步长，单核最少 16 个桶，多核按 CPU 数划分）** 认领桶区间，从高位向低位迁移；
4. **其他线程 put/get 时若发现桶头是 `ForwardingNode`（hash = MOVED = -1），会调用 `helpTransfer` 主动帮忙迁移**——把昂贵的 O(n) 迁移摊到所有访问线程上，类似 Go 的渐进式思想，但是"众包"而非"写时顺手搬两个"；
5. 迁移算法与 HashMap 一致：`(e.hash & n) == 0` 分高低位两条链，加 `lastRun` 优化复用尾部连续同位置节点；
6. 每个线程完成自己的 stride 后 `sizeCtl` 计数减一，最后一个线程负责收尾校验，`table = nextTable`；
7. 扩容期间**读不受影响**：已迁移的桶通过 ForwardingNode 转发到新表读，未迁移的读旧表。

#### 其他并发 Map 对比

| 方案 | 锁粒度 | 评价 |
|---|---|---|
| `Hashtable` | 全表 synchronized | 上古遗留，淘汰 |
| `Collections.synchronizedMap` | 全表对象锁 | 简单包装，并发度 1 |
| `ConcurrentHashMap` | 桶头节点 | 读无锁写细粒度，首选 |

---

## 三、Redis dict

### 1. 底层结构

```c
typedef struct dictEntry {
    void *key;
    union { void *val; uint64_t u64; int64_t s64; double d; } v;
    struct dictEntry *next;   // 链地址法解决冲突
} dictEntry;

typedef struct dictht {
    dictEntry **table;
    unsigned long size;      // 桶数，恒为 2 的幂
    unsigned long sizemask;  // = size - 1
    unsigned long used;      // 已有节点数
} dictht;

typedef struct dict {
    dictType *type;
    dictht ht[2];            // 双哈希表：ht[0] 主表，ht[1] rehash 备用
    long rehashidx;          // -1 = 未在 rehash；>=0 = 搬迁进度（桶下标）
    int16_t pauserehash;     // >0 暂停 rehash（如子进程 fork 期间）
} dict;
```

- 哈希算法：Redis ≥ 4.0 用 **SipHash-1-2**（抗哈希洪水攻击），早期用 MurmurHash2；
- 冲突解决：纯链地址法，**头插法**（O(1) 插入，无需遍历到尾）；
- **没有红黑树**：但 Redis 7 对小 hash（field 少且短）默认用 `listpack` 紧凑编码，不走 dict。

### 2. 插入流程

1. `dictSdsHash`（SipHash）算 key 的哈希；
2. `_dictExpandIfNeeded` 检查是否需要扩容；
3. **若正在 rehash（rehashidx != -1），新节点一律插入 ht[1]**，否则插 ht[0]——保证 ht[0] 只减不增，rehash 必然收敛；
4. 索引 `hash & sizemask`，头插法挂链；
5. `used++`。

### 3. 扩容/收缩条件

| 场景 | 条件 | 动作 |
|---|---|---|
| 常规扩容 | `used / size >= 1` 且**无** BGSAVE/BGREWRITEAOF | 扩到 ≥ used×2 的最小 2^n |
| 强制扩容 | `used / size >= 5`（dict_force_resize_ratio） | 无论是否有子进程，立即扩 |
| 暂停扩容 | 正在执行 BGSAVE/BGREWRITEAOF | 阈值升到 5（避免 COW 内存翻倍） |
| 收缩 | `used / size < 10%`（HASHTABLE_MIN_FILL） | 缩到 ≥ used 的最小 2^n（Redis 7 默认开启非空 dict 收缩） |

> 为什么持久化期间不轻易扩容？扩容搬迁会大量写内存页，fork 出的子进程依赖 **Copy-On-Write**，父进程写得越多，复制页越多，内存可能翻倍。

### 4. 渐进式 rehash（Redis 的灵魂设计）

一次性 rehash 一个百万 key 的 hash 会导致毫秒级卡顿，Redis 用**渐进式 rehash** 把成本均摊：

1. **开始**：为 ht[1] 分配空间，`rehashidx = 0`；
2. **均摊搬迁**：每次对该 dict 的增删改查（`_dictRehashStep`），顺带把 ht[0] 上 `rehashidx` 下标处的**整条桶链**迁到 ht[1]，`rehashidx++`；
3. **定时兜底**：若 dict 长期不被访问，`serverCron` → `databasesCron` → `incrementallyRehash` 每 100ms 花 1ms 迁移约 100 个非空桶，保证 rehash 最终完成；
4. **双表读写规则**：
   - 查询：先查 ht[0]，未命中再查 ht[1]；
   - 插入：只进 ht[1]；
   - 删除：两表都要找；
5. **完成**：释放 ht[0] 旧表，ht[1] 转正为 ht[0]，`rehashidx = -1`。

**生产注意点**：
- rehash 期间内存持有两份表（新旧），大 hash（百万级 field）扩容瞬间内存接近翻倍，需监控；
- bigkey 的删除用 `UNLINK`（异步线程惰性释放）代替 `DEL`，避免主线程卡顿；
- 正在 fork（RDB/AOF 重写）时 `pauserehash > 0`，搬迁暂停。

### 5. 并发安全

- Redis 命令执行是**单线程**（I/O 多线程从 6.0 开始只负责读写解析，命令执行仍串行），dict 天然无并发问题；
- 这正是 Redis 设计 dict 时可以肆无忌惮用渐进式 rehash 的原因——不用担心搬迁过程中另一个线程插进来。

---

## 四、横向对比总表

| 维度 | Go map | Java HashMap(8) | Java ConcurrentHashMap(8) | Redis dict |
|---|---|---|---|---|
| 冲突解决 | 桶(8槽) + 溢出桶链 | 链表 + 红黑树(≥8) | 链表 + 红黑树(≥8) | 纯链表（头插） |
| 负载因子阈值 | 6.5 | 0.75 | ~0.75 | 1（无持久化）/ 5（有持久化） |
| 扩容倍数 | 2 倍或等量 | 2 倍 | 2 倍 | ≥ used×2 的 2^n |
| 迁移方式 | **渐进式**：写操作顺手搬 1~2 桶 | **一次性**全量迁移 | **多线程协作**：访问线程 helpTransfer | **渐进式**：操作时搬 1 桶 + 定时器兜底 |
| 扩容期读行为 | 新旧桶都查 | 读旧表（迁移期间不可写） | ForwardingNode 转发新表 | 先 ht[0] 后 ht[1] |
| 定位技巧 | 低位定桶+高位定槽(tophash) | 高16位扰动 + 位与 | 同左 | SipHash + sizemask |
| 删除后缩容 | ❌ 永不收缩 | ❌ 不收缩 | ❌ 不收缩 | ✅ 负载<10% 自动缩 |
| 并发安全 | ❌（panic 检测） | ❌（会丢数据） | ✅ 读无锁 + 桶级锁 | ✅ 单线程天然安全 |
| 并发方案 | sync.Map（read/dirty 读写分离） | — | CAS + synchronized + 协作扩容 | 主线程串行 + lazyfree |

**记忆主线**：三者面对"扩容卡顿"这个共同敌人给出了三种答案——HashMap 选择硬扛（一次性），Go 和 Redis 选择**写操作均摊搬迁**（渐进式），ConcurrentHashMap 选择**众包搬迁**（谁来访问谁帮忙搬）。而并发安全上，Go 用读写分离空间换时间，Java 用细粒度锁，Redis 直接消灭并发。
