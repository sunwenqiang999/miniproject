# Go 语言基础面试知识点

## 阅读建议

Go 面试知识点已按复习模块拆分为五类文档：

| 文档 | 对应 Java 模块 | 内容重点 |
|------|---------------|----------|
| [`go语言基础面试知识点.md`](go语言基础面试知识点.md) | java语言基础 | 类型系统、值语义、defer、error 体系、interface、反射、泛型、nil 陷阱 |
| [`go集合数据结构面试知识点.md`](go集合数据结构面试知识点.md) | java集合 | slice、string、map 源码级原理、sync.Map、扩容机制 |
| [`go并发编程面试知识点.md`](go并发编程面试知识点.md) | juc并发编程 | GMP 调度、channel、sync 全家桶、context、并发模式 |
| [`go运行时与GC面试知识点.md`](go运行时与GC面试知识点.md) | jvm | 内存分配、逃逸分析、三色标记 GC、写屏障、pprof 调优 |
| [`go后端工程实战面试知识点.md`](go后端工程实战面试知识点.md) | — | 网络编程、Gin、存储中间件、微服务、工程化、性能优化 |

---

## 一、Go 设计哲学与语言对比

### 1.1 Go 的核心特点

**面试回答模板：**

> Go 是 Google 2009 年发布的编译型静态语言，设计目标是在**开发效率**（接近 Python）与**运行效率**（接近 C++）之间取得平衡。核心卖点：编译快、单二进制部署无依赖、原生 CSP 并发（goroutine + channel）、低延迟 GC（STW 亚毫秒）、语法极简（25 个关键字）、接口隐式实现、组合优于继承。

| 特点 | 说明 |
|------|------|
| 编译部署 | 编译速度快，静态链接单二进制，无运行时依赖（对比 Java 需 JVM） |
| 并发模型 | goroutine + channel，CSP 哲学："不要通过共享内存来通信，要通过通信来共享内存" |
| GC | 并发三色标记，STW 亚毫秒级 |
| 语法 | 25 个关键字，强制 gofmt，无继承、无异常、无方法重载 |
| 面向对象 | 无 class，struct + 方法集，接口隐式实现（duck typing），组合（嵌入）替代继承 |
| 跨平台 | `GOOS=linux GOARCH=amd64 go build` 一键交叉编译 |

### 1.2 Go vs Java vs C++（高频对比题）

| 对比项 | Go | Java | C++ |
|--------|-----|------|-----|
| 编译产物 | 机器码单二进制 | 字节码（需 JVM） | 机器码 |
| 启动速度 | 毫秒级 | 秒级（JVM 预热） | 毫秒级 |
| 内存占用 | 小 | 大（JVM 堆+元空间） | 小 |
| 并发 | goroutine（轻量，2KB 栈） | Thread（1:1 内核线程） | std::thread |
| GC | 并发三色标记 | 多种收集器（G1/ZGC） | 无 GC（手动/智能指针） |
| 异常 | error 显式返回 + panic | 异常体系 try-catch | 异常 + 错误码 |
| 泛型 | Go 1.18+（类型参数） | 类型擦除泛型 | 模板（编译期展开） |
| 继承 | 无（组合/嵌入） | 单继承 + 接口 | 多继承 |
| 适用场景 | 云原生、微服务、中间件、CLI | 大型企业应用、安卓 | 系统软件、游戏、高性能计算 |

**Go 不适合的场景：**
- 对实时性要求极高的系统（GC 停顿不可完全消除）。
- 重度数值计算、科学计算（生态不如 Python/C++）。
- 极致性能无 GC 场景（数据库内核、操作系统、高频交易核心路径）。

---

## 二、值语义与类型系统

### 2.1 Go 只有值传递（高频）

Go 中**所有传参都是值拷贝**，但部分类型内部包含指针，表现出"引用语义"。

| 类型 | 底层结构 | 拷贝行为 |
|------|----------|----------|
| 基本类型（int、bool）、struct、数组 | 纯值 | 完全拷贝，互不影响 |
| slice | 结构体{ptr, len, cap} | 拷贝头部，**共享底层数组** |
| map | 指向 hmap 的指针 | 共享底层哈希表 |
| channel | 指向 hchan 的指针 | 共享底层队列 |
| interface | (type, data) 二元组 | 拷贝二元组，data 可能是指针 |
| func | 指向闭包的指针 | 共享闭包环境 |
| string | 结构体{ptr, len} | 拷贝头部，共享只读字节数组 |

```go
func modifySlice(s []int) {
    s[0] = 100        // 影响外部：共享底层数组
    s = append(s, 1)  // 若触发扩容则不再共享，不影响外部
}
```

> **面试高频**：函数内对 slice 重新 append 后外部为何有时变有时不变？答案：append 是否触发扩容导致底层数组重新分配。未扩容 → 共享数组，外部可见；扩容 → 新数组，外部不可见。

### 2.2 new 和 make 的区别（必考）

| 对比项 | new | make |
|--------|-----|------|
| 作用 | 分配零值内存，返回**指针** *T | 初始化并返回类型**本身**（非指针） |
| 适用类型 | 任意类型 | **仅** slice、map、channel |
| 是否可用 | 返回值类型直接用 | slice 需指定 len/cap、map/channel 分配了内部数据结构后才可用 |

```go
p := new([]int)      // p 是 *[]int，*p 是 nil slice
s := make([]int, 0, 10)  // s 是 []int，已初始化可直接 append
m := make(map[string]int) // 等价 &map[...]{} 的初始化版本
```

### 2.3 nil 的坑（必考汇总）

| 类型 | nil 行为 |
|------|----------|
| nil slice | 可 append、可 range（len=0），等价空切片（打印是 []） |
| nil map | **可读**（返回零值），**写会 panic**；必须先 make |
| nil channel | 读写**永久阻塞**（可用于 select 中动态禁用分支） |
| nil 指针 | 调用值方法 panic；调用指针接收者方法且方法内不解引用则正常 |
| nil interface | 只有 (type, data) 都为 nil 才 == nil |

```go
var m map[string]int
fmt.Println(m["a"]) // 0，读 OK
m["a"] = 1          // panic: assignment to entry in nil map
```

### 2.4 类型系统细节

- **类型推断**：`a := 1` 推断为 int；字面量是无类型常量（untyped constant），根据上下文推导。
- **类型定义 vs 类型别名**：
  - `type MyInt int`：全新类型，与 int 不可隐式转换，可定义方法。
  - `type MyInt = int`：别名，完全相同。
- **不可比较类型**：slice、map、func 不能 `==` 比较，也不能做 map key；struct 若全部字段可比较则 struct 可比较。

---

## 三、defer 机制（必考）

### 3.1 执行规则（三条铁律）

1. **LIFO**：多个 defer 后进先出（栈）。
2. **参数即刻求值**：`defer f(x)` 中 x 在**声明时**求值；闭包捕获的变量在**执行时**取值。
3. **在 return 赋值之后、函数真正返回之前执行**：可修改**具名返回值**。

```go
func f() (result int) {
    defer func() { result += 10 }()
    return 5   // 实际拆解：result=5 → defer 执行 result=15 → 返回 15
}

func g() int {
    result := 5
    defer func() { result += 10 }()
    return result   // 返回 5：非具名返回值，defer 改的是局部变量副本
}

// 参数即刻求值 vs 闭包延迟取值
func h() {
    i := 0
    defer fmt.Println(i)        // 打印 0（声明时求值）
    defer func() { fmt.Println(i) }()  // 打印 1（执行时捕获）
    i++
}
```

### 3.2 defer 性能演进

| 版本 | 实现 | 性能 |
|------|------|------|
| Go 1.13 前 | 堆上分配 defer 链表 | 开销大（~1μs） |
| Go 1.13 | 开放编码 defer（open-coded）：编译期直接展开到函数尾部 | 提升 30% |
| Go 1.14 | 开放编码覆盖大部分场景 | 接近普通函数调用，**放心用** |

**注意：** 循环内 defer 仍会在**函数返回时**统一执行（不是每轮循环结束执行），批量处理文件/锁时要包一层函数或使用后立即释放，否则会积累大量未释放资源。

### 3.3 defer vs Java finally（对比亮点）

| 对比项 | Go defer | Java finally |
|--------|----------|--------------|
| 作用范围 | 函数级（函数返回前执行） | 代码块级（try 块结束后） |
| 资源释放 | 惯用 `f, _ := os.Open(); defer f.Close()` | try-with-resources 自动关闭 |
| 修改返回值 | 可修改具名返回值 | finally 中 return 会覆盖（禁止） |
| 异常恢复 | defer + recover 捕获 panic | catch 捕获异常 |

### 3.4 常见用途

资源释放（关文件、放锁、关连接）、panic 恢复（recover）、耗时统计、日志埋点。

---

## 四、错误处理体系（Go 特色，必考）

### 4.1 error 接口

```go
type error interface {
    Error() string
}
```

Go 没有异常，错误是**显式返回值**（通常是最后一个返回值），强迫调用方处理。

### 4.2 Go 1.13 错误包装与判定（必考）

```go
// 1. %w 包装：保留错误链
if err != nil {
    return fmt.Errorf("query user %d: %w", uid, err)
}

// 2. errors.Is：判定错误链中是否包含某错误（可穿透包装层）
if errors.Is(err, sql.ErrNoRows) { return nil, nil }

// 3. errors.As：提取错误链中特定类型的错误
var pe *fs.PathError
if errors.As(err, &pe) { fmt.Println(pe.Path) }

// 4. 哨兵错误（包级导出，供调用方 Is 判定）
var ErrNotFound = errors.New("resource not found")
```

### 4.3 panic 与 recover

| 对比项 | panic | error |
|--------|-------|-------|
| 定位 | **不可恢复**的程序错误（数组越界、nil 解引用、启动失败） | **可预期**的业务错误 |
| 处理 | defer + recover 捕获 | 显式 if err != nil |
| 使用频率 | 极少（库代码几乎不用） | 日常 |

**recover 的正确姿势（必须在 defer 中直接调用）：**

```go
func safe() (err error) {
    defer func() {
        if r := recover(); r != nil {
            err = fmt.Errorf("panic recovered: %v", r)
        }
    }()
    panic("boom")
}
```

**注意：** recover 只能捕获**同一 goroutine** 的 panic，无法捕获子 goroutine 的 panic（子 goroutine panic 会 crash 整个进程）。**HTTP 服务必须有 recover 中间件兜底**，防止单个请求 panic 打挂进程。

### 4.4 错误处理生产规范

1. 错误只处理一次（handle 或 return，不要既打日志又往上抛——日志重复）。
2. 错误信息带上下文（%w 包装参数、操作），不带堆栈用 `pkg/errors` 或 Go 1.20 `errors.Join`。
3. 哨兵错误用 errors.New 包级导出；需要携带信息的错误定义自定义类型实现 Error()。
4. **panic 不跨 API 边界**（库对外只返回 error）。

---

## 五、interface 与面向对象

### 5.1 接口底层结构（源码级，必考）

```go
// 带方法的接口（iface）
type iface struct {
    tab  *itab          // 类型信息 + 方法表（itab 缓存接口与实现类型的匹配结果）
    data unsafe.Pointer // 指向实际数据
}

// 空接口（eface）：interface{}
type eface struct {
    _type *_type
    data  unsafe.Pointer
}
```

**接口判 nil 的经典坑（必考）：**

```go
type MyError struct{ msg string }
func (e *MyError) Error() string { return e.msg }

func do() error {
    var e *MyError = nil
    return e              // 返回的接口 != nil！
}
err := do()
fmt.Println(err == nil)   // false！type=*MyError（非nil），data=nil
// 正确做法：返回前显式判空 if e == nil { return nil }
```

> 接口 = (type, data) 二元组，**只有两者都为 nil 接口才是 nil**。一个类型为 `*MyStruct` 值为 nil 的接口不等于 nil。

### 5.2 类型断言与 type switch

```go
v, ok := i.(string)     // ok 模式：失败不 panic，ok=false
v := i.(string)         // 非 ok 模式：失败 panic

switch v := i.(type) {  // type switch 遍历多种类型
case string: ...
case int: ...
case nil: ...
}
```

### 5.3 接口设计哲学（与 Java 对比）

| 对比项 | Go interface | Java interface |
|--------|-------------|----------------|
| 实现方式 | **隐式**（duck typing：方法签名匹配即实现） | 显式 `implements` |
| 定义位置 | 推荐**使用方定义**（需要哪些方法定义哪些） | 通常实现方定义 |
| 大小 | 小接口为美（io.Reader 只有 1 个方法） | 接口可较臃肿 |
| nil 语义 | (type,data) 二元组判 nil | 引用直接判 null |
| 默认方法 | 不支持 | Java 8 default |

**Go 接口的最佳实践：**
- 接口**越小越好**（1~3 个方法），标准库典范：`io.Reader`、`io.Writer`、`fmt.Stringer`。
- **面向接口编程**：函数参数用接口，返回值用具体类型（"Accept interfaces, return structs"）。
- 隐式实现的检查技巧：`var _ io.Reader = (*MyReader)(nil)`（编译期断言）。

### 5.4 组合（嵌入）替代继承

```go
type Animal struct{ Name string }
func (a *Animal) Eat() { fmt.Println("eating") }

type Dog struct {
    Animal        // 匿名嵌入：Dog 自动获得 Animal 的字段和方法
    Breed string
}
d := Dog{}
d.Eat()  // 方法提升（promoted method），类似"继承"
```

- 嵌入是**组合**不是继承：没有多态（Dog 不是 Animal 的子类型），方法提升只是语法糖。
- 嵌入接口可实现**装饰器模式**（struct 嵌入接口，只重写关心的方法）。
- 字段/方法冲突时：浅层优先，同层歧义编译报错。

---

## 六、反射（reflect）

### 6.1 反射三大法则

1. **从接口值可以反射出 reflect.Value 和 reflect.Type**：`reflect.ValueOf(i)`、`reflect.TypeOf(i)`。
2. **从 reflect.Value 可以回到接口值**：`v.Interface()`。
3. **要修改反射值，必须传入指针且 v.CanSet()**：`reflect.ValueOf(&x).Elem().SetInt(7)`。

### 6.2 要点与性能

- TypeOf 返回**动态类型**（接口的静态类型信息丢失）。
- 反射性能差：绕过编译期类型检查 + 大量内存分配（interface 装箱逃逸），**热路径慎用**。
- 典型应用：ORM（GORM）、JSON 序列化（encoding/json）、依赖注入（wire/fx）、参数校验器（validator）。
- struct tag 是反射的核心数据源：`` `json:"name" gorm:"column:user_name"` ``。

---

## 七、泛型（Go 1.18+）

### 7.1 基本语法

```go
func Max[T constraints.Ordered](a, b T) T {
    if a > b { return a }
    return b
}

type Stack[T any] struct{ items []T }
func (s *Stack[T]) Push(v T) { s.items = append(s.items, v) }
```

- 类型参数用 `[T constraint]` 声明；`any` 是 `interface{}` 的别名。
- 约束：`constraints.Ordered`、`~int`（近似元素：底层类型匹配）、`|` 联合、`comparable`。

### 7.2 与 Java 泛型对比（面试亮点）

| 对比项 | Go 泛型 | Java 泛型 |
|--------|---------|-----------|
| 实现 | 编译期**单态化**（gc shape stenciling，按需实例化） | **类型擦除** |
| 基本类型 | 直接用（无需装箱） | 必须包装类（Integer） |
| 运行时类型信息 | 保留（dict 传类型元数据） | 擦除，运行时不可知 |
| 通配符 | 无（无 extends/super） | 有（PECS） |
| 协变/逆变 | 不支持 | 通过通配符支持 |

**现状：** 标准库泛型化有限（slices、maps、cmp 包已泛型化）；`sync.Map`、`atomic.Value` 等仍靠 interface{}；谨慎使用，简单场景不必强上泛型。

---

## 八、版本演进重要变化（面试常考）

| 版本 | 关键变化 |
|------|----------|
| Go 1.13 | 错误包装（%w、errors.Is/As）、开放编码 defer |
| Go 1.14 | goroutine **异步抢占**（SIGURG 信号，解决死循环饿死调度） |
| Go 1.18 | **泛型**、模糊测试（Fuzzing）、工作区模式（workspace） |
| Go 1.19 | **GOMEMLIMIT** 软内存上限 |
| Go 1.20 | errors.Join（多错误合并）、arena（实验性手动内存） |
| Go 1.21 | **slog 结构化日志**、slices/maps/cmp 泛型包、min/max 内置 |
| Go 1.22 | **循环变量每轮迭代新建**（修复闭包捕获坑）、增强路由 `GET /users/{id}`、`for range int` |
| Go 1.23 | range-over-func 迭代器（iter 包）、unique 包（字符串驻留） |
| Go 1.25 | Green Tea GC（实验性，span 扫描优化，CPU 降 10%~40%） |

**Go 1.22 循环变量修复（高频）：**

```go
for _, v := range list {
    go func() { fmt.Println(v) }()
}
// Go 1.22 前：全部打印最后一个值（v 是同一个变量被闭包共享）
// Go 1.22+：每轮迭代 v 都是新变量，行为符合直觉
```

---

## 九、高频面试题速记

**Q1：Go 和 Java 的最大区别是什么？**
> Go 编译成机器码单二进制部署、启动快内存占用低、goroutine 轻量并发（用户态调度）；Java 依赖 JVM、生态庞大、线程 1:1 内核映射。错误处理上 Go 显式 error 返回 vs Java 异常体系。

**Q2：Go 是值传递还是引用传递？**
> Go 只有值传递。slice/map/channel/func/interface 的"引用效果"是因为其头部结构体内含指针，拷贝头部后共享底层数据。

**Q3：defer 的执行顺序和注意点？**
> LIFO 栈序执行；参数声明时即刻求值，闭包捕获执行时取值；在 return 赋值后真正返回前执行，可修改具名返回值。循环内 defer 不会每轮执行，会累积到函数返回——批量资源操作要包函数。

**Q4：error 和 panic 怎么选？**
> 可预期的业务错误用 error 显式返回（调用方处理）；不可恢复的程序错误用 panic（数组越界、启动失败）。panic 不跨 API 边界，HTTP 层用 recover 中间件兜底，recover 只在 defer 中直接调用有效，且无法捕获其他 goroutine 的 panic。

**Q5：接口判 nil 为什么有坑？**
> 接口是 (type, data) 二元组，只有两者都为 nil 接口才 == nil。`*MyStruct` 类型的 nil 值赋给接口后 type 非 nil，接口 != nil。返回 error 时若具体指针为 nil 要显式返回 nil。

**Q6：Go 为什么不需要继承？**
> 继承带来强耦合（父类改动影响子类）、菱形继承问题。Go 用组合（struct 匿名嵌入获得方法提升）+ 接口（隐式实现）达到同样的代码复用与抽象，耦合更低、更灵活。"组合优于继承"是 Go 的核心设计哲学。

**Q7：new 和 make 区别？**
> new 分配零值内存返回指针，适用所有类型；make 只用于 slice/map/channel，返回初始化后的值本身（分配了底层数据结构），直接可用。

**Q8：Go 泛型与 Java 泛型区别？**
> Go 泛型是编译期实例化（单态化），基本类型无需装箱，运行时保留类型信息；Java 泛型是类型擦除，运行时不保留，基本类型必须装箱。Go 泛型无通配符/型变。
