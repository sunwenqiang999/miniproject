# Java 集合面试知识点

## 一、集合框架总览

```text
                        ┌──────────┐
                        │ Iterable │
                        └────┬─────┘
                             │
                      ┌──────┴──────┐
                      │ Collection  │
                      └──┬───────┬──┘
                         │       │
            ┌─────┬──────┤       └────────┐
            │     │      │                │
          List   Set   Queue            Map（独立体系）
            │     │      │                │
     ┌──────┤     │      │           ┌────┤
     │      │     │      │           │    │
ArrayList HashSet ...  LinkedList  HashMap TreeMap
LinkedList TreeSet    PriorityQueue LinkedHashMap
Vector    LinkedHashSet ArrayDeque Hashtable
CopyOnWriteArrayList   阻塞队列      ConcurrentHashMap
```

**两大顶层接口体系：**
- `Collection`：单列集合，子接口 List（有序可重复）、Set（无序不重复）、Queue（队列）。
- `Map`：双列键值对集合，与 Collection 平级。

**面试常问的一句话总结：**

| 集合 | 底层结构 | 有序性 | 线程安全 | 允许 null |
|------|----------|--------|----------|-----------|
| ArrayList | 动态数组 | 插入序 | 否 | 是 |
| LinkedList | 双向链表 | 插入序 | 否 | 是 |
| Vector | 动态数组（方法加锁） | 插入序 | 是（粗粒度，已淘汰） | 是 |
| HashMap | 数组+链表+红黑树 | 无序 | 否 | key/value 均可一个 null |
| LinkedHashMap | HashMap + 双向链表 | 插入序/访问序 | 否 | 是 |
| TreeMap | 红黑树 | key 排序 | 否 | key 不可 null |
| Hashtable | 数组+链表（方法加锁） | 无序 | 是（已淘汰） | **均不可 null** |
| ConcurrentHashMap | 数组+链表+红黑树（CAS+synchronized） | 无序 | 是（细粒度） | **均不可 null** |
| HashSet | 包装 HashMap | 无序 | 否 | 是 |
| TreeSet | 包装 TreeMap | 排序 | 否 | 否 |

---

## 二、List

### 2.1 ArrayList 底层原理（高频）

**核心结构：** `transient Object[] elementData` 动态数组。

**扩容机制（必背）：**

```java
// 初始容量：无参构造是空数组，首次 add 时扩容到 10（懒初始化）
// 扩容规则：新容量 = 旧容量 + 旧容量 / 2（即 1.5 倍）
int newCapacity = oldCapacity + (oldCapacity >> 1);
// 扩容方式：Arrays.copyOf() 创建新数组并拷贝，O(n)
```

**要点：**
1. 无参构造初始是**空数组** `{}`，第一次 add 才扩到 10，避免空列表浪费内存。
2. 每次扩容 **1.5 倍**，通过 `Arrays.copyOf` 整体拷贝，代价高。
3. **生产建议：预知数据量时用 `new ArrayList<>(initialCapacity)` 指定容量**，避免多次扩容拷贝（如从 DB 查出 1000 条记录转换 DTO）。

**复杂度：**
- get(index)：O(1)（数组随机访问）。
- add(末尾)：均摊 O(1)（扩容时 O(n)）。
- add(index)/remove(index)：O(n)（System.arraycopy 移动元素）。

### 2.2 ArrayList vs LinkedList vs Vector（高频）

| 对比项 | ArrayList | LinkedList | Vector |
|--------|-----------|------------|--------|
| 底层 | 动态数组 | 双向链表 | 动态数组 |
| 随机访问 | O(1)，快 | O(n)，慢 | O(1) |
| 头尾增删 | 尾部 O(1)，头部 O(n) | O(1) | 同 ArrayList |
| 中间增删 | O(n)（数组拷贝） | O(n)（要遍历定位）但定位后 O(1) | O(n) |
| 内存占用 | 小（只存数据，可能有预留空间） | 大（每个节点多两个指针） | 小 |
| 扩容 | 1.5 倍 | 无需扩容 | 2 倍 |
| 线程安全 | 否 | 否 | 是（方法级 synchronized，性能差，已淘汰） |

**面试官陷阱："LinkedList 插入快"要纠正：**

> LinkedList 只有在**已经定位到节点**的前提下插入才是 O(1)；实际 `add(index, e)` 要先遍历到 index 位置，是 O(n)。真正优势是头尾操作 O(1)，适合做队列/栈（它实现了 Deque）。而 ArrayList 有 **CPU 缓存局部性**优势（数组内存连续），实践中绝大多数场景 ArrayList 更快。

**替代 Vector 的方案：**
- `Collections.synchronizedList(new ArrayList<>())`：同步包装器。
- `CopyOnWriteArrayList`：读多写少场景的并发 List。

### 2.3 CopyOnWriteArrayList（写时复制）

**原理：** 修改操作（add/set/remove）时，**加锁** → 复制原数组副本 → 在副本上修改 → 引用指向新数组。读操作完全无锁。

```java
// JDK 8 核心逻辑
public boolean add(E e) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        Object[] elements = getArray();
        int len = elements.length;
        Object[] newElements = Arrays.copyOf(elements, len + 1);
        newElements[len] = e;
        setArray(newElements);   // 数组引用是 volatile，保证可见性
        return true;
    } finally {
        lock.unlock();
    }
}
```

**特点与适用场景：**
- 读无锁、读性能高；写要复制整个数组，**写多内存开销大**。
- 迭代器遍历的是**快照**，不支持 remove，不抛 ConcurrentModificationException。
- **适用：读多写极少**，如监听器列表、配置项、黑白名单。写多场景禁用（GC 压力大）。

### 2.4 fail-fast 机制（快速失败）

**现象：**

```java
List<String> list = new ArrayList<>();
list.add("a"); list.add("b");
for (String s : list) {
    list.remove(s);   // 抛 ConcurrentModificationException
}
```

**原理：** ArrayList/HashMap 等维护 `modCount`（修改次数），迭代器创建时记录 `expectedModCount`，每次 next() 检查两者是否一致，不一致抛异常。

**注意点：**
- fail-fast 是**尽力而为**的保护，不保证一定抛出（并发修改时不做同步检测），不能依赖它做并发控制。
- 迭代中删除元素的正确姿势：用**迭代器自己的 `remove()`** 或 `removeIf()`。

**fail-safe（安全失败）：** CopyOnWriteArrayList、ConcurrentHashMap 的迭代器基于快照/弱一致，不抛异常。

---

## 三、HashMap（面试必考，源码级）

### 3.1 底层结构（JDK 8）

```text
┌─────┬─────┬─────┬─────┬─────┬─────┐
│  0  │  1  │  2  │  3  │ ... │ 15  │      Node[] table（数组/桶）
└──┬──┴─────┴──┬──┴─────┴─────┴─────┘
   │           │
   ▼           ▼
 Node         Node(红黑树节点 TreeNode)
   │           /    \
 Node      TreeNode TreeNode
（链表）
```

**结构 = 数组 + 链表 + 红黑树**
- 数组（桶）定位：`index = (n - 1) & hash`。
- 哈希冲突：挂链表。
- 链表长度 ≥ 8 且数组长度 ≥ 64 → 转红黑树；红黑树节点 ≤ 6 退化为链表。

### 3.2 哈希函数与容量设计（高频追问）

**hash 计算（扰动函数）：**

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

**为什么 hashCode 要右移 16 位再异或？**
- 数组长度通常很小（如 16），只用 hash 的**低几位**定位桶，高位完全没用上，容易冲突。
- `h ^ (h >>> 16)` 让**高 16 位也参与运算**，扰动后低位携带高位信息，减少哈希冲突。

**为什么容量必须是 2 的幂？**
- 定位桶：`index = (n - 1) & hash`，等价于 `hash % n`（仅当 n 是 2 的幂时成立），**位运算比取模快**。
- 扩容时元素新位置只有两种：**原索引** 或 **原索引 + 旧容量**（看 hash 新增的那一位是 0 还是 1），无需重新计算 hash，扩容高效。

**默认参数：**

| 参数 | 值 | 说明 |
|------|-----|------|
| 默认初始容量 | 16 | 首次 put 时初始化（懒加载） |
| 负载因子 | 0.75 | 时间与空间的权衡 |
| 扩容阈值 | 容量 × 0.75 | 超过则扩容为 2 倍 |
| 树化阈值 | 8 | 链表长度 ≥ 8 |
| 桶最小树化容量 | 64 | 数组 < 64 时优先扩容而非树化 |
| 退化阈值 | 6 | 树节点 ≤ 6 退化链表 |

**为什么负载因子是 0.75？** 统计学权衡：1.0 空间利用率高但冲突多查询慢；0.5 冲突少但浪费空间扩容频繁。0.75 是经验平衡值（配合泊松分布，链表长度达到 8 的概率约为 0.00000006，树化几乎是小概率兜底）。

**为什么树化阈值是 8 不是 6/10？** 理想哈希下链表长度符合泊松分布，长度 8 的概率约千万分之六，几乎不会发生；一旦发生说明 hash 函数有问题或被攻击（哈希碰撞攻击），用红黑树把查询从 O(n) 降到 O(log n)。退化 6 与树化 8 之间留出缓冲带，避免链表树频繁互转。

### 3.3 put 流程（JDK 8，口述必考）

```text
1. table 为空 → resize() 初始化（默认 16）
2. 计算 hash → (n-1) & hash 定位桶
3. 桶为空 → 直接放入新节点
4. 桶不为空：
   a. 头节点 key 相同（hash 相同 && equals）→ 覆盖 value
   b. 是红黑树 → 走红黑树插入逻辑
   c. 是链表 → 遍历：
      - 找到相同 key → 覆盖 value
      - 遍历到尾部 → 尾插新节点，链表长度 ≥ 8 → 树化判断（数组 < 64 先扩容）
5. size++，超过阈值（容量×0.75）→ resize() 扩容 2 倍
```

**JDK 8 用尾插法**（JDK 7 是头插法，多线程扩容会形成环形链表死循环）。

### 3.4 扩容 resize() 细节

- 容量翻倍（16→32→64…），阈值也翻倍。
- 元素重新分布：`e.hash & oldCap` 判断高位是 0 还是 1：
  - 为 0 → 索引不变（仍在原位置 j）。
  - 为 1 → 新索引 = j + oldCap。
- 无需重新计算 hash，只需检查一个 bit 位，高效。

### 3.5 HashMap 是线程安全的吗？（高频）

**不安全。** 典型问题：

| 版本 | 问题 |
|------|------|
| JDK 7 | 头插法 + 扩容 transfer 多线程并发 → **环形链表** → get 死循环 CPU 100% |
| JDK 8 | 尾插法解决了环，但仍有**数据覆盖**（两个线程同时 put 到同一空桶，后者覆盖前者）、size++ 非原子导致计数不准、扩容时数据丢失 |

**并发场景选择：**
- `ConcurrentHashMap`（推荐）。
- `Collections.synchronizedMap()`（包装器，方法级 synchronized，性能差）。
- `Hashtable`（淘汰，全表锁）。

### 3.6 JDK 7 vs JDK 8 HashMap 对比（必背）

| 对比项 | JDK 7 | JDK 8 |
|--------|-------|-------|
| 数据结构 | 数组 + 链表 | 数组 + 链表 + 红黑树 |
| 插入方式 | 头插法 | 尾插法 |
| 扩容重 hash | 全部重新计算 hash | 高位判断（原位 或 原位+旧容量） |
| 扩容并发问题 | 环形链表死循环 | 无环，但数据覆盖 |
| hash 扰动 | 4 次位运算 + 4 次异或 | 1 次右移 16 + 1 次异或（配合红黑树够用） |
| 初始化 | 构造时初始化数组 | 首次 put 时初始化（懒加载） |

### 3.7 LinkedHashMap 与 LRU 实现

**LinkedHashMap = HashMap + 双向链表**，维护插入顺序或访问顺序（accessOrder）。

```java
// 实现 LRU 缓存的经典面试题
class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    LRUCache(int capacity) {
        // accessOrder = true：每次 get/put 后节点移到链表尾部
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        // 最久未使用的元素在链表头部，超容量时移除
        return size() > capacity;
    }
}
```

### 3.8 TreeMap

- 底层**红黑树**，按 key 排序（自然排序 Comparable 或构造时传 Comparator）。
- 查询/插入/删除均 O(log n)。
- key 不能为 null（要比较大小）。
- 典型应用：按分数范围查询（`subMap`、`floorKey`、`ceilingKey`）。

---

## 四、ConcurrentHashMap（面试必考，源码级）

### 4.1 JDK 7：分段锁 Segment

```text
ConcurrentHashMap
├── Segment[0]（继承 ReentrantLock）
│     └── HashEntry[]（数组+链表）
├── Segment[1]
│     └── HashEntry[]
└── Segment[15]（默认并发度 16，最多 16 个线程同时写）
```

- 每个 Segment 是一把独立的锁（继承 ReentrantLock），锁住自己的 HashEntry 数组。
- 默认并发度 16，理论支持 16 个线程同时写。
- 读操作不加锁（value 用 volatile 修饰）。
- **缺点：** Segment 数组不能扩容、锁粒度仍较大、定位要两次 hash、链表长查询慢。

### 4.2 JDK 8：CAS + synchronized 锁桶头节点（必背）

```text
Node[] table
┌─────┬─────┬─────┬─────┐
│  0  │  1  │  2  │  3  │
└──┬──┴─────┴──┬──┴─────┘
   │           │
  空桶         链表/红黑树
   │           │
 CAS 无锁插入  synchronized 只锁这个桶的头节点
```

**put 核心流程：**

```java
1. key 计算 hash，定位桶
2. 桶为空 → CAS 插入新节点（无锁，失败自旋重试）
3. 桶不为空 → synchronized 锁住桶头节点（首节点）：
   - 链表：遍历找 key 或尾插，≥ 8 转红黑树
   - 红黑树：树化插入
4. 当前桶正在扩容（头节点 hash == MOVED）→ 帮助扩容（helpTransfer）
5. addCount() 计数，需要时触发扩容
```

**相比 JDK 7 的优化：**

| 优化点 | 说明 |
|--------|------|
| 锁粒度更细 | 锁单个桶头节点，而非整个 Segment，并发度 = 数组长度 |
| 无锁 CAS | 空桶插入直接 CAS，无锁竞争 |
| 红黑树 | 链表 ≥ 8 树化，冲突严重时查询从 O(n) → O(log n) |
| synchronized 优化 | JDK 6+ 锁升级（偏向锁→轻量级→重量级），竞争不激烈时开销小，无需 ReentrantLock 的 AQS 队列 |

**为什么用 synchronized 而不是 ReentrantLock？** JDK 8 时 synchronized 已有大量优化（锁消除、锁粗化、偏向锁、轻量级锁），在低竞争下性能不输 ReentrantLock；且锁桶头节点时竞争概率低；另外 synchronized 由 JVM 原生支持，内存占用更少（每个对象都可做锁，无需额外 AQS 节点对象）。

### 4.3 扩容 transfer 与多线程协助

- 扩容时创建 2 倍新数组，将桶**分片转移**（stride 步长分配任务）。
- 正在转移的桶头节点替换为 **ForwardingNode（hash = MOVED）**。
- 其他线程 put 遇到 MOVED → **helpTransfer() 帮助扩容**，加速迁移。
- get 遇到 ForwardingNode → 直接到新表查，**扩容期间读写不阻塞**。
- 元素迁移规则同 HashMap：`hash & oldCap` 判断原位 or 原位+oldCap。

### 4.4 size() 统计：CounterCell（类 LongAdder 思想）

- 低竞争：`CAS 更新 baseCount`。
- 高竞争：分散到 `CounterCell[]` 数组，各线程 CAS 更新不同 Cell（类似 LongAdder 分段），最后 baseCount + 所有 Cell 求和。
- **size() 是弱一致性的估值**，不保证实时精确（并发写入时），不建议用它做精确判断，精确判断用 `mappingCount()`（同样估值，只是返回 long）—— 精确计数需要自己加锁或用 AtomicLong。

### 4.5 为什么 ConcurrentHashMap 不允许 null key 和 null value？（高频）

**核心原因：二义性。**
- `map.get(key)` 返回 null 有两种含义：① key 不存在；② key 存在但 value 是 null。单线程的 HashMap 可以用 `containsKey()` 二次确认，但**并发环境下两个操作之间状态可能变化**（containsKey 返回 false 时别的线程刚好 put 了），无法消除二义性。
- 所以 CHM 干脆禁止 null key/value（put 时空指针检查直接 NPE）。
- Doug Lea 本人回答：并发容器中 null 无法容忍二义性，这是设计共识。

### 4.6 JDK 7 vs JDK 8 ConcurrentHashMap 对比（必背表格）

| 对比项 | JDK 7 | JDK 8 |
|--------|-------|-------|
| 锁机制 | Segment 分段锁（ReentrantLock） | CAS + synchronized 锁桶头节点 |
| 锁粒度 | 段（默认 16 段） | 单个桶（并发度更高） |
| 空桶插入 | 先获取段锁 | CAS 无锁 |
| 数据结构 | Segment 数组 + HashEntry 链表 | Node 数组 + 链表 + 红黑树 |
| 扩容 | 段内扩容，不支持并发 | 多线程协助扩容（helpTransfer），迁移期间可读写 |
| size 统计 | 先尝试无锁统计 2 次，不一致则锁所有段（开销大） | baseCount + CounterCell（类 LongAdder） |
| 定位 | 两次 hash（段 + 桶） | 一次 hash |

---

## 五、Set 与 Queue

### 5.1 HashSet / LinkedHashSet / TreeSet

Set 系列本质是**对 Map 的包装**（value 是同一个静态 Object 占位）：

```java
// HashSet 源码
private transient HashMap<E,Object> map;
private static final Object PRESENT = new Object();
public boolean add(E e) {
    return map.put(e, PRESENT) == null;
}
```

| 实现 | 底层 | 特点 |
|------|------|------|
| HashSet | HashMap | 无序、去重靠 equals+hashCode |
| LinkedHashSet | LinkedHashMap | 保持插入序 |
| TreeSet | TreeMap | 排序，需 Comparable/Comparator |

### 5.2 Queue / Deque 家族

| 实现 | 结构 | 特点 | 适用 |
|------|------|------|------|
| LinkedList | 双向链表 | 实现了 Deque，可当队列/栈 | 单线程 |
| ArrayDeque | 循环数组 | 比 LinkedList 更快的栈/队列 | 单线程栈、队列（官方推荐替代 Stack） |
| PriorityQueue | 数组小顶堆 | 出队取最小元素，非线程安全 | TopK、任务调度 |
| ArrayBlockingQueue | 数组 + 一把锁 ReentrantLock | **有界**，读写同一把锁 | 线程池 |
| LinkedBlockingQueue | 链表 + 读写两把锁 | 可无界（默认 Integer.MAX_VALUE，**有 OOM 风险**） | 线程池 |
| SynchronousQueue | 不存元素 | put 必须等 take，直接移交 | cachedThreadPool |
| DelayQueue | PriorityQueue + 时间 | 到期元素才能出队 | 延迟任务、缓存过期 |
| LinkedTransferQueue | CAS 无锁队列 | transfer 等待消费 | 高并发 |

**面试高频：ArrayBlockingQueue vs LinkedBlockingQueue**

| 对比项 | ArrayBlockingQueue | LinkedBlockingQueue |
|--------|--------------------|--------------------|
| 底层 | 数组（有界，必须指定容量） | 链表（默认无界 Integer.MAX_VALUE） |
| 锁 | 单锁（读写互斥） | 双锁（takeLock/putLock 分离，吞吐高） |
| 内存 | 预分配数组 | 每入队 new Node，GC 压力大 |
| 风险 | 可控 | 无界易 OOM（newFixedThreadPool 的坑） |

---

## 六、迭代器与比较器

### 6.1 Iterator 迭代器

- 统一遍历接口：`hasNext()`、`next()`、`remove()`。
- foreach 循环本质是编译器转换为迭代器调用（数组例外，走索引）。
- 迭代器 `remove()` 是遍历中删元素的唯一安全方式（会同步 modCount）。

### 6.2 Comparable vs Comparator

| 对比项 | Comparable | Comparator |
|--------|------------|------------|
| 位置 | 类内部实现（`implements Comparable`） | 类外部定义 |
| 方法 | `compareTo()` | `compare()` |
| 使用 | 自然排序（String、Integer） | 定制排序，可多种策略 |
| 例子 | `class User implements Comparable<User>` | `list.sort(Comparator.comparing(User::getAge).reversed().thenComparing(User::getName))` |

---

## 七、高频面试题速记

**Q1：HashMap 的容量为什么是 2 的幂？**
> 定位桶用 `(n-1) & hash` 位运算替代取模，速度快；扩容时元素新位置只有"原位"或"原位+旧容量"两种，重分布高效。

**Q2：HashMap 为什么线程不安全？**
> JDK 7 头插法扩容会成环死循环；JDK 8 改为尾插解决成环，但仍存在 put 覆盖、size 计数不准等问题。并发场景用 ConcurrentHashMap。

**Q3：ConcurrentHashMap 怎么保证线程安全？**
> JDK 8：空桶 CAS 插入；非空桶 synchronized 锁桶头节点；链表转红黑树优化查询；扩容时多线程协助迁移；size 用 CounterCell 分段统计降低竞争。

**Q4：HashMap 和 Hashtable 的区别？**
> Hashtable 方法级 synchronized 全表锁（性能差，淘汰）、不允许 null key/value、初始容量 11 扩容 2n+1；HashMap 线程不安全、允许 null、容量 16 扩容 2 倍。

**Q5：什么场景用 LinkedHashMap / TreeMap？**
> LinkedHashMap 保持插入序或访问序，典型应用 LRU 缓存；TreeMap 按 key 排序，适合范围查询（楼层价、分数段）。

**Q6：ArrayList 初始容量 10、1.5 倍扩容，为什么要指定初始容量？**
> 避免多次扩容时的数组拷贝开销。已知数据量（如 1000 条）直接 `new ArrayList<>(1000 / 0.75 + 1)` 一步到位，HashMap 同理（阿里规范推荐 `(int)(expectedSize / 0.75f) + 1`）。

**Q7：如何保证集合线程安全？（汇总）**
1. 并发容器：ConcurrentHashMap、CopyOnWriteArrayList、BlockingQueue。
2. 同步包装器：`Collections.synchronizedXxx()`（性能差）。
3. 线程封闭：每个线程用自己的集合（ThreadLocal）。
4. 不可变集合：`Collections.unmodifiableXxx()`、`List.of()`（JDK9）、Guava ImmutableList。
