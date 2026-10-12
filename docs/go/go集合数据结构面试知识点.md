# Go 集合与数据结构面试知识点

## 一、Slice 切片（必考，源码级）

### 1.1 底层结构

```go
type slice struct {
    array unsafe.Pointer // 指向底层数组
    len   int            // 当前长度（可访问元素数）
    cap   int            // 容量（底层数组从 ptr 起可用元素数）
}
```

```text
slice := make([]int, 3, 6)
┌─────────────────┐
│  array ─────────┼──→ [ _ _ _ _ _ _ ]  底层数组（cap=6）
│  len = 3        │      └─┬─┘
│  cap = 6        │      len=3 可访问范围
└─────────────────┘
```

**array vs slice（必考对比）：**

| 对比项 | 数组 array | 切片 slice |
|--------|-----------|-----------|
| 长度 | 编译期固定，是类型的一部分（`[3]int` 与 `[4]int` 是不同类型） | 动态可变 |
| 传参 | 整体拷贝（大数组传参开销大） | 拷贝 24 字节头部，共享底层数组 |
| 比较 | 可 ==（元素可比较时） | 不可 ==（只能与 nil 比） |
| 使用 | 极少直接用 | 日常主力 |

### 1.2 扩容机制（必考）

**Go 1.18+ 扩容规则：**

```text
旧容量 < 256   → 2 倍扩容
旧容量 >= 256  → 渐进式约 1.25 倍：newcap += (newcap + 3*256) / 4
                （从 2 倍平滑过渡到接近 1.25 倍）
最后按内存 size class 对齐修正（roundupsize，实际扩容值略大于计算值）
```

> **为什么 Go 1.18 改了规则？** 旧版固定"小于 1024 翻倍，大于 1024 按 1.25 倍"，在 1024 附近扩容倍数突变（1023→2046，1024→1280），大切片内存浪费。新版平滑过渡。

**扩容的行为本质：** 分配新底层数组 → 拷贝旧数据 → slice 头指向新数组。**旧数组若无其他 slice 引用则被 GC**。

### 1.3 共享底层数组的经典坑（必考）

```go
// 坑1：截断后 append 污染原切片
a := []int{1, 2, 3, 4}
b := a[:2]              // b 与 a 共享底层数组，b.cap = 4
b = append(b, 99)       // cap 够（4>=3）不扩容 → a 变成 [1 2 99 4]！

// 坑2：函数内 append 有时不影响外部
func append(s []int, v int) {
    s = append(s, v)    // 若扩容 → 新数组，外部无感知；不扩容 → 改了共享数组但外部 len 不变也看不到
}

// 坑3：大切片截断造成内存泄漏
var big []byte = load1GB()
small := big[:100]      // 1GB 底层数组因 small 引用无法 GC！
// 正确做法：拷贝出来
small := append([]byte(nil), big[:100]...)   // 或 Go 1.20+ slices.Clone(big[:100])

// 坑4：range 遍历是值拷贝
for i, v := range users {
    v.Age = 10          // 改的是副本，原切片不变
    users[i].Age = 10   // ✅ 用索引改
}
```

**避坑口诀：** 截断后若还要 append，用 **full slice expression**：`b := a[:2:2]`（第三个参数限死 cap=2），强制 append 必扩容，隔离共享。

### 1.4 预分配优化（生产规范）

```go
// ❌ 已知数量却不预分配：多次扩容拷贝 + 旧数组 GC 压力
s := []int{}
for i := 0; i < 10000; i++ { s = append(s, i) }

// ✅ 预分配 cap
s := make([]int, 0, 10000)
```

---

## 二、string 字符串

### 2.1 底层结构

```go
type stringStruct struct {
    str unsafe.Pointer  // 指向只读字节数组
    len int
}
```

**关键特性：**
- string **不可变**：底层字节数组只读，修改必须创建新 string。
- string 是 **UTF-8 字节序列**（不是字符数组）：`len("中文")` = 6（字节数），不是 2。

### 2.2 string / []byte / []rune 转换（必考）

| 转换 | 说明 |
|------|------|
| `[]byte(s)` | 按 UTF-8 字节切分，拷贝新数组 |
| `[]rune(s)` | 按 Unicode 码点切分，`len([]rune("中文"))` = 2 |
| `string(bytes)` | 拷贝生成新字符串 |

```go
s := "hello中文"
len(s)              // 11（5 + 3 + 3 字节）
len([]rune(s))      // 7（字符数）
for i, r := range s { }  // range 按 rune 遍历，i 是字节索引（跳跃：0..5..8）
```

**性能优化（黑魔法，了解）：** Go 1.20+ 可用 `unsafe.String(&b[0], len(b))` / `unsafe.StringData(s)` 做零拷贝转换，需保证 []byte 生命周期长且不再修改。普通业务代码别用。

### 2.3 字符串拼接性能对比（高频）

| 方式 | 性能 | 适用 |
|------|------|------|
| `+` 少量拼接 | 好（编译器优化连续 + 为单次分配） | < 5 个，非循环 |
| `strings.Builder` | 最好（内部 []byte 累积，Grow 预分配） | **循环拼接首选** |
| `bytes.Buffer` | 好（可读写） | 需要复用/读写 |
| `fmt.Sprintf` | 差（反射 + interface 装箱） | 复杂格式，非热路径 |

```go
var sb strings.Builder
sb.Grow(1024)              // 预分配，避免多次扩容
for _, s := range parts {
    sb.WriteString(s)
}
result := sb.String()
```

---

## 三、Map 哈希表（必考，源码级，Go 面试第一高频）

### 3.1 底层结构：hmap + bmap

```go
type hmap struct {
    count     int    // 元素个数
    B         uint8  // 桶数量的对数：桶数 = 2^B
    buckets   unsafe.Pointer // 桶数组指针
    oldbuckets unsafe.Pointer // 扩容时的旧桶（渐进式迁移用）
    nevacuate uintptr        // 迁移进度计数器
    extra     *mapextra      // 溢出桶信息
}

// bmap（桶）：编译期生成，逻辑结构如下
type bmap struct {
    tophash  [8]uint8  // 每个 key 哈希值的高 8 位，用于快速过滤
    // 随后紧跟 8 个 key、8 个 value（key 和 value 分开排列，防内存对齐浪费）
    // 最后可能链接 overflow bucket（溢出桶）
}
```

```text
hmap
 ├─ buckets ──→ [桶0][桶1][桶2]...[桶 2^B-1]
 │                │
 │                ▼
 │            ┌─────────────────────────┐
 │            │ tophash[8]：h1 h2 ... h8 │  ← 先用高8位快速比对
 │            │ keys[8]：  k1 k2 ... k8  │
 │            │ values[8]：v1 v2 ... v8  │
 │            │ overflow ──→ [溢出桶]      │  ← 桶满后链式挂载
 │            └─────────────────────────┘
 └─ oldbuckets（扩容期间指向旧桶数组）
```

**定位一个 key 的过程：**

```text
1. hash(key) 得到 64 位哈希值
2. 低 B 位 → 定位桶编号（buckets[hash & (2^B - 1)]）
3. 高 8 位 → 与桶内 tophash 数组逐一比对（快速过滤，不等则跳过）
4. tophash 相等 → 比较完整 key（防哈希冲突误判）
5. 命中返回 value；桶内未命中 → 沿 overflow 溢出桶继续找
```

**为什么 tophash 存高 8 位？** 一字节快速比对，绝大部分不匹配的 key 在这一步就被过滤掉，避免完整 key 比较的开销（key 可能是长字符串/大结构体）。

**为什么 key 和 value 分开存储（k,k,k…v,v,v）而不是 kv 交替？** 避免内存对齐填充浪费。例如 `map[int64]int8`：kv 交替每个 kv 对要填充 7 字节；分开存则 keys 区连续 8×8 字节，values 区连续 8×1 字节，几乎无浪费。

### 3.2 扩容机制：渐进式 rehash（必考）

**触发条件（两个）：**

| 条件 | 说明 | 扩容方式 |
|------|------|----------|
| **负载因子 > 6.5** | count / 2^B > 6.5，桶太满，冲突严重 | **翻倍扩容**（B+1，桶数 ×2） |
| **溢出桶过多** | 溢出桶数 ≥ 常规桶数（大量删除后又插入导致的松散分布） | **等量扩容**（B 不变，重新排列整理，收紧数据） |

**为什么是渐进式？** 一次性 rehash 在大 map（百万级 key）上会造成长时间停顿。Go 把迁移摊到每次读写：

```text
1. 扩容开始：分配 2 倍新桶，buckets 指向新桶，oldbuckets 指向旧桶
2. 每次对 map 增删改查时，顺带迁移当前访问的旧桶 + 顺次迁移一个桶（growWork/evacuate，每次最多 2 个）
3. 迁移完成的旧桶标记 tophash 为 evacuatedXxx
4. 全部迁移完，oldbuckets 置 nil，旧内存 GC
```

**扩容期间的读写规则：**
- **查询**：先查旧桶（未迁移的桶数据还在旧桶），旧桶已迁移标记则查新桶。
- **写入**：先确保对应旧桶已迁移（不在旧桶上写新数据）。

**扩容期间性能注意：** map 处于扩容中（growing）时性能略降；频繁增删的大 map 可能触发等量扩容，属正常整理。

### 3.3 关键特性（必考）

| 特性 | 说明 | 原因 |
|------|------|------|
| **遍历顺序随机** | 每次 for range 顺序不同 | runtime 故意加随机种子（起点随机），防止程序依赖遍历顺序 |
| **非并发安全** | 并发写 panic（`fatal error: concurrent map writes`，**不可 recover**） | hmap.flags 的 writing 标志位检测，fail-fast 设计 |
| key 必须可比较 | slice/map/func 不能做 key | 需要 == 判等定位 |
| value 不可寻址 | `&m["k"]` 编译错误 | 扩容会搬迁 value，地址会变 |
| 删除不缩容 | delete 只清标记位，桶不收缩（等下一轮等量扩容） | 避免抖动 |
| 预分配 | `make(map[K]V, hint)` 指定容量减少扩容 | 生产规范 |

**并发写 panic 为什么不可 recover？** `concurrent map writes` 是 runtime 抛的 fatal error（不是普通 panic），直接 crash 进程。设计意图：宁可快速失败暴露 bug，也不让数据撕裂继续错下去。

### 3.4 并发场景的三种方案（必考）

| 方案 | 原理 | 适用 |
|------|------|------|
| **RWMutex + map** | 读锁/写锁保护 | 通用，写操作也不少时首选 |
| **sync.Map** | read（无锁）+ dirty（加锁）双 map | **读多写少** 或 键集合不相交 |
| **分片锁（sharded map）** | 按 key hash 分成 N 个桶，每桶独立 RWMutex+map | 高并发读写都多（如本地缓存，concurrent-map 库） |

### 3.5 sync.Map 原理（高频追问）

```text
┌──────────────────────────────────────────────────────┐
│  read（atomic.Value，无锁读）                          │
│   └─ readOnly{ m: map[any]*entry, amended: bool }    │
│        ▲ misses ≥ len(dirty) 时 dirty 整体晋升为新 read│
│        │（O(1) 指针交换，旧 read 连空壳一起丢弃）        │
│  dirty（加锁 map，新增 key 写这里）                     │
│   └─ map[any]*entry（重建时拷存活 entry 指针）          │
│  misses：读未命中计数                                   │
└──────────────────────────────────────────────────────┘
```

**核心认知：两个 map 容器，一份数据。** read 和 dirty 是两个独立的 map（key 集合可以不同），但 value 都是 `*entry` 指针，且**相同 key 指向同一个 entry 对象**——值只有一份，改值就是 CAS 改那一个 entry，天然两边可见，不存在双副本漂移。

- **读**：先无锁读 read；命中直接返回；未命中看 `amended`（true 表示 dirty 里有 read 没有的 key）→ 加锁查 dirty 兜底，misses++。
- **写**：key 已在 read（未删除）→ **无锁 CAS** 改共享 entry；新 key → 加锁写 dirty 并把 amended 置 true；dirty 为 nil 时先从 read 拷贝存活 entry **指针**（非深拷贝）重建。
- **misses ≥ len(dirty)** → dirty 整体晋升为新 read，dirty 置 nil。

**写 dirty 时 read 不变，会不会不一致？（高频追问）**

- **值层面永远一致**：两个 map 共享同一批 entry，更新是 CAS 改同一个对象。
- **key 集合允许短暂不一致**，由 amended 兜底查询 + misses 晋升收敛：

| key 类型 | read | dirty | 说明 |
|---------|------|-------|------|
| 老 key，存活 | ✓ | ✓ | 指向同一个 entry |
| 新增 key | ✗ | ✓ | 读靠 amended 兜底查 dirty |
| 已删除 key | ✓（空壳） | ✗ | 惰性删除：先置 nil，重建时标 expunged 不拷入 dirty |

> 精确表述：**dirty 是实时全量；read 是"带空壳的旧快照"**——缺新增 key，但可能挂着已删除 key 的 nil/expunged 条目。

- **生命周期（一个 map ↔ 两个 map 交替）**：稳定期 dirty=nil，只有 read 在工作 → 写新 key 时 dirty 从 read 复制（拷指针）→ misses 攒够后 dirty 晋升替换 read → 回到单 map 状态。

**惰性删除与 expunged 复活：** 删除只把 entry 置 nil/expunged，条目暂挂在 read 里；若该 key 被重新写入，可 unexpunge 复活原 entry 重新加回 dirty，**连 entry 对象都不用新建**——key 集合固定场景下省内存省分配。

**适用判断：**
- ✅ 读远多于写、不同 goroutine 操作**不相交的键集合**。
- ❌ 写多（dirty 反复重建/晋升是 O(n) 拷贝）或需要遍历（Range 要拷贝 read）、需要 len 精确值（sync.Map 没有 Len 方法！）→ 退化为普通 map+RWMutex 更好。

---

## 四、Channel 作为数据结构（底层结构速览，并发语义详见并发篇）

```go
type hchan struct {
    qcount   uint           // 队列中元素数
    dataqsiz uint           // 环形队列容量
    buf      unsafe.Pointer // 环形缓冲区
    sendx    uint           // 发送索引
    recvx    uint           // 接收索引
    recvq    waitq          // 阻塞接收者队列（sudog 链表）
    sendq    waitq          // 阻塞发送者队列
    lock     mutex          // 互斥锁（channel 所有操作先抢锁）
}
```

**与并发相关的核心语义速查：**

| 操作 | nil channel | 无缓冲 | 有缓冲（未满） | 已关闭 |
|------|------------|--------|---------------|--------|
| 发送 | 永久阻塞 | 无接收者则阻塞 | 立即成功 | **panic** |
| 接收 | 永久阻塞 | 无发送者则阻塞 | 有值取走，无值阻塞 | 返回零值 + false |
| close | panic | 唤醒全部阻塞者 | 唤醒全部阻塞者 | panic（重复关闭） |

---

## 五、Go vs Java 集合对比（加分项）

| 维度 | Go | Java |
|------|-----|------|
| 动态数组 | slice（共享底层数组是坑） | ArrayList（扩容 1.5 倍，拷贝隔离） |
| 扩容倍数 | <256 翻倍，≥256 约 1.25 倍渐进 | 1.5 倍 |
| 哈希表 | map（桶 8 槽位 + 溢出桶，渐进式扩容） | HashMap（数组+链表+红黑树，2 倍一次性扩容） |
| 负载因子 | 6.5 | 0.75 |
| 并发容器 | sync.Map（双 map）、分片锁、channel | ConcurrentHashMap（CAS+synchronized 锁桶头） |
| 有序 Map | 无内置（第三方 ordered-map） | LinkedHashMap / TreeMap |
| Set | 无内置（map[K]struct{} 模拟） | HashSet/TreeSet |
| 线程安全迭代 | range map 无 fail-fast，但并发写直接 fatal | fail-fast（CME）/ 弱一致（CHM） |

**面试话术：** Go 的集合哲学是"少而精"——slice/map/channel 内置于语言，配合 for range 统一遍历；Java 是"全家桶"——JCF 框架层级完整但笨重。Go 用 `map[string]struct{}` 模拟 Set（struct{} 零字节不占地），用 slice+sort 或第三方库替代 TreeMap 的有序需求。

---

## 六、高频面试题速记

**Q1：slice 的底层结构和扩容机制？**
> slice 是 {ptr, len, cap} 三元组。append 超容量时扩容：Go 1.18+ 小于 256 翻倍，大于等于 256 约 1.25 倍渐进，最后按 size class 对齐。扩容会分配新数组拷贝数据，旧数组无引用则被 GC。

**Q2：map 的底层实现？**
> hmap（元信息）+ bmap（桶）。每个桶存 8 个 key/value + tophash[8]（哈希高 8 位快速过滤），桶满挂溢出桶。定位：哈希低 B 位定桶、高 8 位比对 tophash、再比较完整 key。key/value 分开存储避免对齐浪费。

**Q3：map 如何扩容？为什么渐进式？**
> 触发：负载因子 > 6.5（翻倍扩容）或溢出桶过多（等量扩容整理）。一次性 rehash 大 map 会长时间停顿，所以渐进式：每次读写顺带迁移 1~2 个旧桶，扩容期间查询先查旧桶再查新桶。

**Q4：map 并发读写会怎样？怎么解决？**
> 并发写触发 `fatal error: concurrent map writes`，不可 recover，进程直接崩溃（fail-fast 设计）。方案：RWMutex+map（通用）、sync.Map（读多写少/键不相交）、分片锁（高并发读写）。

**Q5：sync.Map 什么场景用？原理？**
> read（atomic 无锁快照）+ dirty（加锁）两个 map，相同 key 共享同一个 *entry——**两个容器、一份数据**。读命中 read 完全无锁；未命中看 amended 兜底查 dirty，misses 达阈值 dirty 晋升为新 read。写已有 key 无锁 CAS 共享 entry，新 key 加锁写 dirty。一致性 = entry 指针共享（值）+ amended 兜底（key 集合）；删除是惰性的（nil→expunged 空壳，重建 dirty 时不拷入）。适合读多写少或键集合不相交；写多、要遍历、要 Len 的场景不如 RWMutex+map。

**Q6：为什么 map 遍历顺序是随机的？**
> runtime 故意在 range 起点注入随机性，防止开发者依赖遍历顺序（哈希表本就无序，Go 团队用强制随机避免"偶然有序"的隐式依赖 bug）。

**Q7：slice 作为函数参数，函数内 append 会影响外部吗？**
> 不触发扩容：修改的是共享底层数组，但外部 len 不变所以"看不见"新增元素（已有元素被改可见）；触发扩容：新数组，完全不影响外部。要可靠修改外部，返回新 slice 或传 *[]T。

**Q8：string 和 []byte 互转有什么开销？len("中文") 是多少？**
> 互转都有拷贝（string 不可变保证）。len("中文")=6（UTF-8 每汉字 3 字节）；字符数用 len([]rune("中文"))=2 或 utf8.RuneCountInString。

**Q9：如何删除 map 中所有元素？**
> Go 1.21+ `clear(m)`；旧版重新 make 一个新 map（原 map 靠 GC 回收）。遍历时 delete 是安全的（Go 允许 range 中删除当前 key）。

**Q10：大 map 内存不释放怎么办？**
> delete 不缩容，元素删光桶结构还在。方案：定期重新 make 替换（热替换）、用分片 map 控制单片大小、或换 offheap/本地缓存库（bigcache、freecache，绕开 GC 扫描）。
