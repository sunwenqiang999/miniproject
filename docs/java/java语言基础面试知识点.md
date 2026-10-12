# Java 语言基础面试知识点

## 阅读建议

Java 后端面试八股已按模块拆分为四类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`java语言基础面试知识点.md`](java语言基础面试知识点.md) | 基础复习 | 数据类型、String、面向对象、异常、泛型、反射、Java 8 特性 |
| [`java集合面试知识点.md`](java集合面试知识点.md) | 源码深入 | ArrayList、HashMap、ConcurrentHashMap 源码级原理 |
| [`juc并发编程面试知识点.md`](juc并发编程面试知识点.md) | 并发核心 | synchronized、volatile、JMM、AQS、线程池、ThreadLocal |
| [`jvm面试知识点.md`](jvm面试知识点.md) | 进阶调优 | 内存区域、类加载、GC 算法、垃圾收集器、线上问题排查 |

建议先掌握语言基础，再深入集合源码，然后攻克并发编程，最后学习 JVM 原理与调优。面试官通常按此顺序由浅入深提问。

---

## 一、Java 概述与平台机制

### 1.1 Java 有哪些特点？

**面试回答：**

> Java 的核心特点是：跨平台（一次编写到处运行）、面向对象、自动内存管理（GC）、强类型静态语言、丰富的生态（Spring、各种中间件客户端）、多线程支持（JUC）、高性能（JIT 即时编译优化）。

| 特点 | 说明 |
|------|------|
| 跨平台 | 编译成字节码，由 JVM 解释/编译执行，与操作系统解耦 |
| 面向对象 | 封装、继承、多态，一切皆对象（基本类型除外） |
| 自动内存管理 | 堆内存由 GC 自动回收，减少内存泄漏风险（不是杜绝） |
| 安全性 | 字节码校验、沙箱机制、无指针直接操作内存 |
| 多线程 | 语言级支持线程（Thread、synchronized），JUC 提供丰富并发工具 |
| 高性能 | JIT 热点代码编译为机器码，性能接近 C++ |

### 1.2 JDK、JRE、JVM 的区别？

```text
┌─────────────────────────────────────┐
│  JDK（Java Development Kit）         │
│  ┌─────────────────────────────┐    │
│  │  JRE（Java Runtime Env）     │    │
│  │  ┌─────────────────────┐   │    │
│  │  │  JVM（Java虚拟机）   │   │    │
│  │  └─────────────────────┘   │    │
│  │  + 核心类库（rt.jar 等）     │    │
│  └─────────────────────────────┘    │
│  + 开发工具（javac、jmap、jstack）     │
└─────────────────────────────────────┘
```

- **JVM**：Java 虚拟机，负责加载字节码并执行，是跨平台的核心。
- **JRE**：Java 运行时环境 = JVM + 核心类库，只能运行不能开发。
- **JDK**：Java 开发工具包 = JRE + 编译器（javac）+ 诊断工具（jmap、jstack、jps 等）。

### 1.3 什么是字节码？为什么说 Java 语言"编译与解释并存"？

- **字节码**：`.java` 源码经 `javac` 编译生成 `.class` 文件，里面是 JVM 能理解的中间指令，不针对特定机器。
- **编译与解释并存**：
  - 启动时：JVM **解释执行**字节码（逐条翻译），启动快但慢。
  - 运行时：JIT（Just-In-Time）编译器把**热点代码**（频繁执行的方法/循环）编译成**本地机器码**缓存，后续直接执行机器码，速度快。
- HotSpot 虚拟机内置 C1（客户端编译器，编译快优化少）和 C2（服务端编译器，编译慢优化激进），配合**分层编译**策略。

### 1.4 Java 和 C++ 的区别（高频）

| 对比项 | Java | C++ |
|--------|------|-----|
| 内存管理 | GC 自动回收 | 手动 new/delete |
| 指针 | 无指针（只有引用） | 有指针，可直接操作内存 |
| 多继承 | 类单继承，接口多实现 | 类支持多继承 |
| 跨平台 | 字节码跨平台 | 需针对平台编译 |
| 性能 | JIT 后接近 C++ | 通常更高（无虚拟机层） |
| 运算符重载 | 不支持 | 支持 |

---

## 二、基本数据类型与包装类

### 2.1 Java 的八种基本数据类型

| 类型 | 字节数 | 位数 | 默认值 | 取值范围 | 包装类 |
|------|--------|------|--------|----------|--------|
| byte | 1 | 8 | 0 | -128 ~ 127 | Byte |
| short | 2 | 16 | 0 | -2^15 ~ 2^15-1 | Short |
| int | 4 | 32 | 0 | -2^31 ~ 2^31-1 | Integer |
| long | 8 | 64 | 0L | -2^63 ~ 2^63-1 | Long |
| float | 4 | 32 | 0.0f | IEEE 754 单精度 | Float |
| double | 8 | 64 | 0.0d | IEEE 754 双精度 | Double |
| char | 2 | 16 | '\u0000' | 0 ~ 65535 | Character |
| boolean | 视 JVM 实现 | - | false | true/false | Boolean |

**易错点：**
- `long` 赋值超过 int 范围要加 `L`：`long a = 3000000000L;`
- `float` 字面量必须加 `f`：`float f = 1.5f;`（1.5 默认是 double）
- `char` 用单引号，`String` 用双引号。
- boolean 在 JVM 规范中没有明确规定占用大小（HotSpot 中单个 boolean 通常按 int 处理占 4 字节，boolean 数组中每个元素占 1 字节）。

### 2.2 为什么需要包装类？自动装箱拆箱是什么？

**为什么需要包装类：**
1. 泛型不支持基本类型：`List<int>` 编译错误，必须 `List<Integer>`。
2. 基本类型不是对象，无法调用方法、不能为 null（如数据库字段可能为 NULL）。
3. 提供工具方法：`Integer.parseInt()`、`Integer.compare()` 等。

**自动装箱拆箱（JDK 1.5+）：**

```java
Integer a = 100;      // 自动装箱：编译为 Integer a = Integer.valueOf(100);
int b = a;            // 自动拆箱：编译为 int b = a.intValue();
```

**装箱陷阱（高频面试题）：**

```java
Integer a = 127, b = 127;
Integer c = 128, d = 128;
System.out.println(a == b);  // true  —— 命中缓存，同一对象
System.out.println(c == d);  // false —— 超出缓存范围，new 了两个对象
```

**原理：** `Integer.valueOf()` 内部有 **IntegerCache**，默认缓存 **-128 ~ 127**（可通过 `-XX:AutoBoxCacheMax` 调大上限）。缓存范围内直接返回缓存对象，范围外 `new Integer()`。

**各包装类缓存范围：**

| 包装类 | 缓存范围 |
|--------|----------|
| Byte | 全部（-128 ~ 127） |
| Short | -128 ~ 127 |
| Integer | -128 ~ 127（上限可调） |
| Long | -128 ~ 127 |
| Character | 0 ~ 127 |
| Boolean | true、false |

> **生产建议：** 包装类比较一律用 `equals()` 或 `Objects.equals()`，不要用 `==`；循环中避免大量装箱拆箱（如 `Integer sum` 累加），有性能损耗和 NPE 风险。

### 2.3 int 和 Integer 使用场景？数据库字段为 NULL 的坑

- 数据库字段**可能为 NULL** → 实体类必须用 `Integer`，用 `int` 接收 NULL 会拆箱 NPE。
- RPC 传输的 DTO 建议用包装类，可以表达"未传值"（null）与"传了 0"的区别。
- 高频计算场景（如循环计数器）用 `int`，避免装箱开销。

### 2.4 为什么浮点数不能直接比较大小？金额用什么类型？

```java
System.out.println(0.1 + 0.2 == 0.3);  // false
System.out.println(0.1 + 0.2);          // 0.30000000000000004
```

**原因：** float/double 采用 IEEE 754 标准，二进制无法精确表示 0.1 等十进制小数，存在精度误差。

**解决方案：**
- 比较：`|a - b| < 1e-6` 判断"约等于"。
- **金额计算必须用 `BigDecimal`**，且构造时**用 String 参数**：`new BigDecimal("0.1")`，不要用 `new BigDecimal(0.1)`（double 参数本身就不精确）。
- 更极致的做法：用 `long` 存"分"为单位，彻底避免小数。

---

## 三、String 字符串（高频中的高频）

### 3.1 String、StringBuilder、StringBuffer 的区别？

| 对比项 | String | StringBuilder | StringBuffer |
|--------|--------|---------------|--------------|
| 可变性 | 不可变 | 可变 | 可变 |
| 线程安全 | 天然安全（不可变） | 不安全 | 安全（方法加 synchronized） |
| 性能 | 拼接产生新对象，慢 | 最快 | 较快（有锁开销） |
| 适用场景 | 少量操作、常量 | 单线程大量拼接 | 多线程共享拼接（实际很少用） |

**面试回答模板：**

> String 是不可变的，底层 char 数组被 final 修饰，每次修改都会创建新对象；StringBuilder 和 StringBuffer 是可变的，基于可扩容的 char 数组，append 直接在原数组上操作。StringBuffer 的方法加了 synchronized 所以线程安全但略慢，StringBuilder 没有同步所以更快，单线程场景优先用 StringBuilder。

### 3.2 String 为什么设计成不可变的？

1. **字符串常量池需要**：只有不可变，常量池的共享才有意义（两个引用指向同一字面量对象）。
2. **线程安全**：不可变对象天然线程安全，无需同步。
3. **缓存 hashCode**：String 的 hashCode 会缓存，适合做 HashMap 的 key（不可变保证 hash 不变）。
4. **安全性**：网络连接、文件路径、类加载等大量使用 String，若可变，校验后被篡改会导致安全问题。

### 3.3 字符串常量池与 intern()

```java
String s1 = "abc";                  // 常量池
String s2 = "abc";                  // 复用常量池
String s3 = new String("abc");      // 堆中新建对象
System.out.println(s1 == s2);       // true
System.out.println(s1 == s3);       // false
System.out.println(s1 == s3.intern()); // true，intern 返回常量池引用
```

**JDK 7 的变化：** 字符串常量池从**方法区（永久代）移到了堆中**。原因：永久代默认空间小且难回收，大量字符串容易导致 OOM；移到堆中可以被 GC 正常回收。

**经典面试题：**

```java
String s = new String("abc");
// 创建了几个对象？
// 答：1 个或 2 个。
// 如果常量池已有 "abc"：只在堆中创建 1 个；
// 如果常量池没有：先在常量池创建 1 个，再在堆中创建 1 个，共 2 个。
```

**拼接的坑：**

```java
String a = "hello" + "world";   // 编译期常量折叠，直接是 "helloworld"，进常量池
String b = "hello";
String c = b + "world";         // 有变量参与，运行期通过 StringBuilder 拼接，在堆中
String d = "helloworld";
System.out.println(a == d);     // true
System.out.println(c == d);     // false
```

### 3.4 JDK 9 的 Compact String 优化

JDK 8 及之前：String 底层是 `char[]`（UTF-16，每字符 2 字节）。
JDK 9+：改为 `byte[]` + **coder 标志位**（0=Latin-1 单字节，1=UTF-16）。纯 Latin-1 字符串（如英文）内存占用减半。

---

## 四、面向对象

### 4.1 面向对象三大特性

| 特性 | 含义 | 举例 |
|------|------|------|
| 封装 | 隐藏内部实现，暴露必要接口 | private 字段 + getter/setter |
| 继承 | 子类复用并扩展父类 | `class Dog extends Animal` |
| 多态 | 同一行为不同实现，父类引用指向子类对象 | `Animal a = new Dog(); a.eat();` |

**多态的两个前提：**
1. 继承/实现关系 + 方法重写。
2. 父类引用指向子类对象（向上转型）。

**多态的体现（高频细节）：**
- **方法**：运行时绑定（动态分派），执行子类重写的方法。
- **成员变量**：编译期绑定，看声明类型！`Animal a = new Dog(); a.name` 取的是 Animal 的 name。

### 4.2 重载（Overload）与重写（Override）的区别？

| 对比项 | 重载 Overload | 重写 Override |
|--------|--------------|---------------|
| 发生位置 | 同一个类中 | 父子类之间 |
| 方法名 | 相同 | 相同 |
| 参数列表 | **必须不同**（类型/个数/顺序） | **必须相同** |
| 返回值 | 无要求 | 相同或是其子类（协变返回） |
| 访问修饰符 | 无要求 | 不能比父类更严格 |
| 异常 | 无要求 | 不能抛出比父类更宽泛的检查异常 |
| 绑定时机 | **编译期**（静态分派） | **运行期**（动态分派） |

**陷阱：** 仅返回值不同不能构成重载，编译报错。

### 4.3 抽象类 vs 接口（高频）

| 对比项 | 抽象类 abstract class | 接口 interface |
|--------|----------------------|----------------|
| 构造方法 | 有（子类实例化时调用） | 没有 |
| 成员变量 | 任意 | 默认 `public static final` |
| 方法 | 可有抽象方法和普通方法 | JDK8 前全是抽象方法；JDK8 支持 default/static；JDK9 支持 private |
| 继承/实现 | 单继承 | 多实现；接口可多继承接口 |
| 设计理念 | "is-a"，模板复用（模板方法模式） | "has-a / can-do"，定义能力契约 |

**使用场景：**
- 需要复用代码、维护公共状态 → 抽象类（如 `AbstractList`、`HttpServlet`）。
- 定义行为规范、需要多实现 → 接口（如 `Comparable`、`List`）。
- JDK8 default 方法的作用：**接口演进**，给接口加新方法时不用改所有实现类（典型：`Collection.stream()`）。

### 4.4 final、finally、finalize 的区别？

| 关键字 | 作用 |
|--------|------|
| final | 修饰类：不可继承（如 String）；修饰方法：不可重写；修饰变量：常量（基本类型值不变，引用类型引用不变但对象内容可变） |
| finally | try-catch 后的代码块，**通常**必执行（除非 JVM 退出 `System.exit()` 或线程被杀），用于释放资源 |
| finalize | Object 的方法，GC 回收对象前调用，**已废弃（JDK9 deprecated）**，不可靠且影响性能，应用 try-with-resources 或 Cleaner 替代 |

**final 与并发：** final 字段在构造完成后对其它线程保证可见（JMM 的 final 语义），这是"安全发布"的手段之一。

**经典问题：finally 中有 return 会怎样？**

```java
public int test() {
    try {
        return 1;
    } finally {
        return 2;   // 返回 2！finally 的 return 会覆盖 try 的 return
    }
}
// 生产代码严禁在 finally 中 return，会吞掉异常
```

### 4.5 static 关键字

- **静态变量**：属于类，所有实例共享一份，类加载时初始化。
- **静态方法**：属于类，不能用 this/super，不能直接访问非静态成员（无实例上下文）。
- **静态代码块**：类加载时执行一次，常用于初始化静态资源。
- **静态内部类**：不持有外部类引用（普通内部类持有外部类 this$0，易造成内存泄漏）。

**执行顺序（高频）：**

```text
父类静态变量/静态块 → 子类静态变量/静态块 → 父类实例变量/实例块 → 父类构造器 → 子类实例变量/实例块 → 子类构造器
```

记忆口诀：**先静态后非静态，先父后子，静态只执行一次。**

### 4.6 this 和 super

- `this`：当前对象引用；`this()` 调用本类其他构造器（必须在第一行）。
- `super`：父类对象引用；`super()` 调用父类构造器（必须在第一行）。
- `this()` 和 `super()` 不能同时出现（都要求在第一行）。

---

## 五、==、equals 与 hashCode

### 5.1 == 和 equals 的区别？

- `==`：基本类型比较**值**；引用类型比较**内存地址**（是否同一对象）。
- `equals`：Object 默认实现就是 `==`；String、Integer 等重写了 equals 比较内容。

### 5.2 为什么重写 equals 必须重写 hashCode？（高频）

**约定（Java 规范）：**
1. `a.equals(b)` 为 true → `a.hashCode() == b.hashCode()` 必须为 true。
2. hashCode 相同 → equals 不一定 true（哈希冲突）。
3. equals 为 false → hashCode 最好不同（减少冲突，非强制）。

**原因：** HashMap/HashSet 依赖 hashCode 定位桶，再依赖 equals 判等。如果只重写 equals 不重写 hashCode，两个"相等"的对象 hashCode 不同，会被放进不同的桶，导致：
- `map.put(key, v)` 后 `map.get(等值key)` 拿不到值。
- `set` 中出现重复元素。

### 5.3 HashMap 中 key 的要求（延伸）

- key 必须正确实现 equals 和 hashCode。
- key 最好是**不可变对象**（String、Integer）：若 key 放入 map 后其参与 hashCode 计算的字段被修改，会导致找不到这个 key（对象还在 map 里，但哈希值对不上了），造成内存泄漏。

---

## 六、异常体系

### 6.1 Java 异常体系结构

```text
                    Throwable
                   /         \
              Error           Exception
             (不可处理)        /          \
                    RuntimeException    检查异常(Checked)
                    (非检查异常)         IOException、SQLException
                    /    |     \
             NPE  数组越界  类型转换  非法参数...
```

| 分类 | 说明 | 例子 | 处理方式 |
|------|------|------|----------|
| Error | JVM 层面严重错误，程序无法恢复 | OutOfMemoryError、StackOverflowError、NoClassDefFoundError | 一般不捕获，排查代码/配置 |
| 检查异常（Checked） | 编译期强制处理 | IOException、SQLException、ClassNotFoundException | 必须 try-catch 或 throws |
| 非检查异常（Unchecked/Runtime） | 运行期才可能发生，多为代码 bug | NullPointerException、IndexOutOfBoundsException、ClassCastException、IllegalArgumentException | 不强制处理，应靠编码避免 |

**面试高频：** `NoClassDefFoundError` vs `ClassNotFoundException`：
- `ClassNotFoundException`：**检查异常**，运行时通过 `Class.forName()` 显式加载类找不到。
- `NoClassDefFoundError`：**Error**，编译时类存在，运行时（new、静态调用）找不到（如 jar 冲突、类初始化失败）。

### 6.2 try-with-resources（JDK 7）

```java
try (FileInputStream fis = new FileInputStream("a.txt")) {
    // 使用 fis
} // 自动调用 fis.close()，无需 finally
```

要求资源实现 `AutoCloseable` 接口。多个资源用 `;` 分隔，**逆序关闭**（后声明的先关闭）。

### 6.3 异常处理的生产实践

1. 不要吞异常（`catch` 后什么都不做），至少打日志。
2. 不要 catch Throwable/Exception 一把梭，精确捕获。
3. 异常信息要带上下文（参数、traceId），方便排查。
4. 不要用异常控制流程（性能差：创建异常要填充堆栈轨迹）。
5. 自定义业务异常继承 RuntimeException，配合全局异常处理器（@ControllerAdvice）。

---

## 七、泛型

### 7.1 泛型的作用

1. **编译期类型检查**：`List<String>` 不能放 Integer，错误提前暴露。
2. **消除强制类型转换**：取出来直接用。
3. **代码复用**：一套逻辑适配多种类型（如 `Comparable<T>`）。

### 7.2 类型擦除（Type Erasure）（高频）

**什么是类型擦除：** 泛型只存在于**编译期**，编译后字节码中泛型信息被擦除，替换为限定类型（无限定则为 Object）。

```java
List<String> list1 = new ArrayList<>();
List<Integer> list2 = new ArrayList<>();
System.out.println(list1.getClass() == list2.getClass());  // true，运行时都是 ArrayList
```

**类型擦除带来的限制：**
- 不能 `new T()`、不能 `new T[]`、不能 `instanceof List<String>`。
- 不能用基本类型做泛型参数（`List<int>` ❌）。
- 静态方法/变量不能使用类的泛型参数（类加载时还没有泛型实参）。
- 重载冲突：`void f(List<String>)` 和 `void f(List<Integer>)` 擦除后签名相同，编译报错。

**桥接方法（Bridge Method）：** 子类实现泛型接口/继承泛型类并重写方法时，编译器自动生成桥接方法保持多态。

### 7.3 通配符：? extends 与 ? super（PECS 原则）

| 通配符 | 含义 | 能做什么 |
|--------|------|----------|
| `? extends T`（上界） | T 或 T 的子类 | 只能**读**（取出来是 T），不能 add（null 除外） |
| `? super T`（下界） | T 或 T 的父类 | 能**写**（add T 及其子类），读出来只能当 Object |

**PECS 原则：Producer Extends, Consumer Super**
- 集合是**生产者**（只往外拿数据）→ 用 `? extends T`。
- 集合是**消费者**（只往里放数据）→ 用 `? super T`。

经典例子：`Collections.copy(List<? super T> dest, List<? extends T> src)` —— src 生产用 extends，dest 消费用 super。

### 7.4 如何绕过类型擦除获取泛型真实类型？

1. **Class 传递**：`public <T> T parse(String json, Class<T> clazz)`。
2. **TypeReference（匿名子类）**：`new TypeReference<List<User>>() {}`，利用父类泛型在字节码中会保留到 `Signature` 属性（`getGenericSuperclass()`），Fastjson/Jackson 常用此机制。

---

## 八、反射

### 8.1 什么是反射？

在**运行期**动态获取类的信息（方法、字段、构造器）并操作对象的能力。核心是 `java.lang.Class` 和 `java.lang.reflect` 包。

**获取 Class 的三种方式：**

```java
Class<?> c1 = Class.forName("com.example.User");  // 会触发类初始化
Class<?> c2 = User.class;                          // 类字面量
Class<?> c3 = user.getClass();                     // 实例方法
```

### 8.2 反射的典型应用

- Spring：IOC 容器创建 Bean、AOP 动态代理。
- 序列化框架：Jackson/Fastjson 读写私有字段。
- ORM：MyBatis 结果集映射。
- 注解处理器、单元测试框架（JUnit）。

### 8.3 反射的性能问题与优化

反射比直接调用慢（原因：绕过编译期优化、需做访问检查、无法内联），优化手段：
1. **缓存** Method/Field 对象，不要每次 `getMethod()`。
2. `setAccessible(true)` 关闭访问检查（JDK9+ 模块化下对 JDK 内部类受限）。
3. 高性能场景用 **MethodHandle（JDK7+ invokedynamic）** 或字节码生成（CGLIB、ByteBuddy、Javassist）。

---

## 九、其他高频基础题

### 9.1 Java 是值传递还是引用传递？（高频）

**结论：Java 只有值传递。** 只不过对于引用类型，传递的是**引用的副本（地址值的拷贝）**。

```java
public void change(User user) {
    user.setName("李四");   // 有效：通过引用副本修改了堆中同一个对象
    user = new User();      // 无效：只是让副本指向新对象，原引用不受影响
}
```

- 修改对象的属性 → 方法外可见。
- 让参数重新 new 一个对象 → 方法外无感知。

### 9.2 深拷贝与浅拷贝

| 类型 | 含义 | 实现方式 |
|------|------|----------|
| 浅拷贝 | 只拷贝对象本身，内部引用字段仍指向同一对象 | 默认 clone()、BeanUtils.copyProperties |
| 深拷贝 | 连同内部引用的对象一起递归拷贝 | 序列化/反序列化（JSON、Java 原生）、手动逐层拷贝、Kryo |

### 9.3 序列化与 serialVersionUID

- 实现 `Serializable` 接口才能被 Java 原生序列化；`transient` 修饰的字段不参与序列化。
- **serialVersionUID**：版本号，序列化和反序列化双方 UID 一致才能反序列化成功。不显式声明则 JVM 根据类结构自动生成，类结构一变（加字段）UID 就变，导致反序列化失败。**生产必须显式声明 `private static final long serialVersionUID = 1L;`**。
- 静态变量不属于对象，不参与序列化。

### 9.4 BIO、NIO、AIO 的区别？

| 模型 | 特点 | 适用 |
|------|------|------|
| BIO（阻塞 IO） | 一个连接一个线程，读写阻塞 | 连接少、JDK1.4 前 |
| NIO（非阻塞 IO） | Channel + Buffer + Selector（IO 多路复用），一个线程管理多个连接 | 高并发、Netty 基础 |
| AIO（异步 IO） | 基于回调/Proactor，操作系统完成 IO 后通知 | Linux 支持不完善，Netty 弃用，实际用得少 |

**NIO 三大组件：**
- **Buffer**：数据容器（读写切换 flip()）。
- **Channel**：双向通道，替代单向 Stream。
- **Selector**：单线程监听多个 Channel 事件（基于 epoll）。

### 9.5 Java 8 核心新特性

| 特性 | 说明 | 示例 |
|------|------|------|
| Lambda 表达式 | 匿名函数，简化函数式接口 | `(a, b) -> a + b` |
| 函数式接口 | 只有一个抽象方法的接口 | `@FunctionalInterface`，Runnable、Comparator |
| 方法引用 | Lambda 的简化写法 | `System.out::println`、`String::compareTo` |
| Stream API | 声明式集合处理 | `list.stream().filter().map().collect()` |
| Optional | 显式处理 null | `Optional.ofNullable(x).orElse(default)` |
| 接口默认/静态方法 | 接口可带实现 | `default int size() { return 0; }` |
| 新日期时间 API | 线程安全（替代 SimpleDateFormat） | LocalDateTime、DateTimeFormatter |
| CompletableFuture | 异步编排 | `supplyAsync().thenApply().thenAccept()` |
| 重复注解 | 同一注解多次使用 | @Repeatable |

**Stream 高频问题：**
- **中间操作**（filter/map/sorted，惰性，返回 Stream）vs **终止操作**（collect/forEach/count，触发执行）。
- `parallelStream()` 底层用公共 ForkJoinPool，**生产慎用**：公共池被占满会拖垮所有 parallel 任务；IO 密集任务不适合。
- `collect(Collectors.groupingBy(...))`、`partitioningBy` 是高频考点。

**SimpleDateFormat 为什么线程不安全？** 内部 Calendar 是可变成员变量，多线程共享同一实例并发 parse/format 会互相污染。解决：ThreadLocal 封装、每次 new、或换用线程安全的 `DateTimeFormatter`（不可变）。

### 9.6 SPI 机制

**Java SPI（Service Provider Interface）：** 面向接口编程 + `META-INF/services/` 配置文件，由 `ServiceLoader` 在运行期加载接口的实现类。典型应用：JDBC Driver、Dubbo 扩展点（Dubbo 有自己的增强 SPI）、SLF4J 绑定实现。

**缺陷：** 一次性加载所有实现、无 IOC/AOP、无优先级。Dubbo SPI 解决：按需加载、支持 AOP/自动注入、Adaptive 自适应。
