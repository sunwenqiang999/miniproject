# Linux 硬件问题排查面试知识点

## 阅读建议

Linux 面试知识点已按复习层次拆分为四类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`linux面试知识点.md`](linux面试知识点.md) | 总览复习 | Linux 基础、目录结构、常用命令、权限管理、进程管理、Shell 基础、整体知识框架 |
| [`linux原理机制面试知识点.md`](linux原理机制面试知识点.md) | 原理深入 | 用户态内核态、VFS、inode、硬链接软链接、进程调度、虚拟内存、Page Cache、Swap、OOM |
| [`linux生产实战面试知识点.md`](linux生产实战面试知识点.md) | 进阶实战 | 性能分析方法论、CPU/内存/磁盘/网络排查命令、日志分析三剑客、线上问题排查模板 |
| [`linux硬件问题排查面试知识点.md`](linux硬件问题排查面试知识点.md) | 硬件专题 | MCE/EDAC/RAS 硬件错误检测机制、CPU/内存/磁盘/网卡硬件故障排查、IPMI 带外管理、宕机根因分析 |

**本文档与生产实战文档的分工：** 生产实战文档解决"**性能问题**"（CPU 高、内存高、磁盘慢），本文档解决"**硬件故障**"（机器宕机、自动重启、内核报硬件错误、硬件导致的数据损坏）。面试中"机器莫名其妙重启了怎么排查"这类问题，答案就在本文档。

---

## 一、硬件问题排查总览

### 1.1 硬件故障 vs 软件故障（高频）

面试时首先要会区分两类问题，它们的排查路径完全不同：

| 对比项 | 硬件故障 | 软件/性能故障 |
|--------|----------|---------------|
| 典型症状 | 宕机、自动重启、内核 Panic、MCE 日志、I/O error、数据损坏 | CPU 高、内存泄漏、响应慢、磁盘打满 |
| 发生规律 | 往往**无规律、随机出现**，与负载不一定相关 | 通常与流量、负载、变更相关，可复现 |
| 日志位置 | dmesg、`/var/log/mcelog`、IPMI SEL、`/var/log/messages` | 应用日志、perf、监控指标 |
| 处理手段 | 定位坏件 → 报修换件（内存条、磁盘、电源） | 优化代码、扩容、限流 |
| 排查工具 | rasdaemon、smartctl、ipmitool、memtester | top、iostat、jstack、arthas |

**判断口诀：** 先查内核日志有没有硬件错误关键字（`mce`、`Hardware Error`、`ECC`、`I/O error`），有 → 走硬件排查流程；没有 → 走性能排查流程（见生产实战文档）。

### 1.2 硬件问题的典型症状（必考）

遇到以下现象，要立刻怀疑硬件：

| 症状 | 最可疑的硬件 |
|------|--------------|
| 机器**无规律重启**，无 OOM 无 Panic 日志 | 电源、主板、CPU 过热保护 |
| 内核日志出现 `mce: [Hardware Error]` | CPU、内存、主板 |
| 应用随机的**数据错乱、段错误（SIGSEGV）**，换台机器就好 | 内存条（位翻转） |
| `I/O error, dev sda`、`Buffer I/O error on dev` | 磁盘、RAID 卡、硬盘背板 |
| 文件系统突然变**只读**（read-only file system） | 内核检测到磁盘错误后的保护动作 |
| 网卡频繁 down/up（link flap）、大量 CRC 错误 | 网卡、光模块、网线 |
| 同一时间段批量机器告警 | 机房供电、交换机、温度（**先怀疑共性环境因素**） |
| 性能逐渐劣化、同配置机器中某台特别慢 | CPU 过热降频、内存降频、磁盘坏道增多 |

### 1.3 硬件排查总流程（面试必背模板）

```text
第 1 步：看内核日志，找硬件错误证据
    dmesg -T | grep -iE "error|fail|mce|ecc|edac"
    │
第 2 步：分类定位到具体硬件
    ├── mce/Hardware Error → CPU / 内存
    ├── EDAC / ECC         → 内存
    ├── I/O error / ata    → 磁盘
    ├── NIC/link           → 网卡
    └── 日志里什么都没有   → 查 IPMI SEL（带外日志）
    │
第 3 步：用专用工具确诊坏件
    ├── 内存：rasdaemon / mcelog / memtester
    ├── 磁盘：smartctl -a /dev/sda
    ├── 温度：sensors / ipmitool sensor
    └── 整机：ipmitool sel list（BMC 记录的错误事件）
    │
第 4 步：交叉验证 + 处理
    ├── 单机单件报错 → 报修换件（换内存条/磁盘/电源）
    ├── 同批次机器集体报错 → 怀疑批次质量或机房环境
    └── 替换后回归观察，错误消失 → 闭环
```

**面试回答：**

> 排查硬件问题我的思路是"先取证、再分类、后确诊"。第一步看 dmesg 和 messages 里有没有 mce、Hardware Error、ECC、I/O error 这些关键字，Linux 内核的 MCE 和 EDAC 机制会主动上报硬件错误。第二步按错误类型分类：MCE 指向 CPU 和内存，ECC 指向内存，I/O error 指向磁盘，link flap 指向网卡。第三步用专用工具确诊，比如内存用 rasdaemon 看错误计数、磁盘用 smartctl 看 SMART 健康状态、整机用 ipmitool sel list 查 BMC 的带外日志——这一步很关键，因为有些致命错误机器直接宕机，内核日志来不及落盘，但 BMC 是独立供电的，日志还在。最后确诊坏件后走报修换件，换完回归观察确认闭环。

---

## 二、硬件错误检测机制（原理层，必考）

### 2.1 MCE（Machine Check Exception，机器检查异常）

**MCE 是 CPU 硬件层面的错误检测机制**，Intel/AMD CPU 内部集成了一组 **MCA（Machine Check Architecture）寄存器**，持续监测 CPU 核心、缓存、内存控制器、总线的错误。

```text
硬件错误发生
    │
CPU MCA 寄存器记录错误（错误码、地址、发生时间）
    │
触发 #MC 异常（18 号异常，不可屏蔽）────────► NMI 级别的最高优先级
    │
内核 mce 驱动接管：
    ├── 可纠正错误（CE）：记录日志，静默修复，机器继续跑
    │       dmesg: mce: [Hardware Error]: Machine check events logged
    │
    └── 不可纠正错误（UE）：
            ├── 错误在内核态/无法隔离 → 内核 Panic，机器宕机
            └── 错误在用户态且可隔离 → 杀掉受影响进程（SIGBUS），机器保住
```

**MCE 日志解读（面试高频）：**

```bash
# 查看 MCE 相关日志
dmesg -T | grep -i mce
grep -i "hardware error" /var/log/messages

# 典型输出
# mce: [Hardware Error]: CPU 6: Machine Check Exception: 5 Bank 4: ba00000000800400
# mce: [Hardware Error]: TSC 5b0d2a ADDR 3f8a2c000 MISC 1
# mce: [Hardware Error]: PROCESSOR 0:50654 TIME 1726848000 SOCKET 1 APIC 21
```

| 字段 | 含义 |
|------|------|
| CPU 6 | 出错的逻辑 CPU 核号 |
| Bank 4 | MCA 寄存器组编号，不同 Bank 对应不同部件（缓存/内存控制器/总线） |
| ADDR | 出错的物理内存地址（内存类错误时有效） |
| SOCKET 1 | 出错 CPU 的物理插槽号，**换件时按这个定位** |
| STATUS 值 | 错误类型编码，可用 `mcelog --ascii` 解码 |

**工具链：**

```bash
# mcelog：传统工具，实时解码 MCE 事件
mcelog --client          # 前台监听模式
cat /var/log/mcelog      # 历史日志

# rasdaemon：新一代 RAS 事件统一收集工具（推荐）
rasdaemon -f -r          # 前台运行并记录到 sqlite 数据库
ras-mc-ctl --summary     # 汇总各类错误计数
ras-mc-ctl --errors      # 查看错误明细
```

### 2.2 EDAC 与 ECC 内存纠错（必考）

**ECC 内存（Error Correcting Code）**：服务器内存自带校验位，每 64 位数据配 8 位校验码，可以**自动纠正 1 位错误、检测 2 位错误**。

**EDAC（Error Detection and Correction）**：Linux 内核中专门收集 ECC 内存错误的子系统，驱动位于 `drivers/edac/`。

```text
内存位翻转发生（宇宙射线、硬件老化、电压不稳）
    │
内存控制器 ECC 引擎检测到错误
    │
    ├── 单比特错误（CE，Correctable Error）
    │       硬件自动纠正，EDAC 记录计数 +1
    │       /sys/devices/system/edac/mc/mc0/ce_count
    │       → 机器正常，但要关注计数增长速度
    │
    └── 多比特错误（UE，Uncorrectable Error）
            无法纠正，触发 MCE
            → 杀进程或内核 Panic
```

**CE 和 UE 的区别（面试必背）：**

| 对比项 | CE 可纠正错误 | UE 不可纠正错误 |
|--------|---------------|-----------------|
| 错误位数 | 单比特 | 多比特 |
| 影响 | 硬件自动修复，业务无感 | 数据已损坏，杀进程或宕机 |
| 处理策略 | 计数监控，增长快则换内存条 | 立即换内存条 |
| 危险信号 | **CE 持续增长是 UE 的前兆**，内存条正在坏 | 已经坏了 |

**查看 ECC 错误：**

```bash
# 方式 1：EDAC 的 sysfs 接口
cat /sys/devices/system/edac/mc/mc*/ce_count    # 可纠正错误计数
cat /sys/devices/system/edac/mc/mc*/ue_count    # 不可纠正错误计数
ls /sys/devices/system/edac/mc/mc0/             # 查看出错内存的 DIMM 槽位信息

# 方式 2：rasdaemon（推荐，能定位到具体 DIMM 槽位）
ras-mc-ctl --error-counts
ras-mc-ctl --errors

# 方式 3：日志
dmesg -T | grep -iE "edac|ecc"
# EDAC MC0: 1 CE error on CPU_SrcID#0_MC#0_Chan#1_DIMM#0
#                          ↑ 这个字段直接告诉你是哪根内存条
```

**内存硬件故障排查流程：**

```text
dmesg 发现 EDAC/ECC 报错
    │
ras-mc-ctl --errors 定位 DIMM 槽位（如 DIMM_A2）
    │
看错误类型：
    ├── 偶发 CE（几个月 1 次）→ 记录观察，可暂不处理
    ├── CE 持续增长（每天多次）→ 内存条正在劣化，计划性更换
    └── 出现 UE → 立刻更换，随时可能宕机
    │
想进一步验证？→ 离线跑 memtester / memtest86+
    │
更换内存条（按 dmidecode 或 IPMI 提供的槽位信息）→ 回归观察
```

```bash
# memtester：用户态内存压力测试工具（在线可做，但不覆盖被占用内存）
yum install -y memtester
memtester 8G 1                # 测试 8G 内存，跑 1 轮

# memtest86+：开机启动项，独占测试全部内存（需要停机窗口，最彻底）

# dmidecode 查看内存插槽物理位置（换件时要用）
dmidecode -t memory | grep -A 10 "Memory Device"
```

### 2.3 RAS 体系与 NMI 看门狗

**RAS（Reliability / Availability / Serviceability）** 是服务器硬件可靠性的统称，前面讲的 MCE、EDAC、PCIe AER 都属于 RAS 机制。内核侧由 rasdaemon 统一收集。

**NMI Watchdog（不可屏蔽中断看门狗）**：检测 CPU 硬锁死的机制。

```text
内核定期向各 CPU 发送 NMI
    │
某 CPU 长时间不响应（中断被关死、自旋锁死循环、硬件故障）
    │
触发 hard lockup 检测，内核打印：
    "NMI watchdog: Watchdog detected hard LOCKUP on cpu 3"
    │
根据配置：打印堆栈继续跑 或 直接 Panic 重启（kernel.panic_on_warn）
```

**面试要点：** 看到 `hard LOCKUP` 日志，说明某颗 CPU 卡死了，原因可能是内核 bug（驱动自旋锁）也可能是硬件问题（CPU 过热、电压不稳）。需要结合当时的温度记录（`ipmitool sel list`）和其他 MCE 日志综合判断。

---

## 三、CPU 硬件问题排查

### 3.1 CPU 硬件故障的典型表现

| 表现 | 判断依据 |
|------|----------|
| MCE 报 CPU 缓存/核心错误 | dmesg 中 Bank 0/1/2（对应缓存层次）反复报错，且固定在某 SOCKET |
| 机器自动重启，无任何内核日志 | 过热保护、供电问题，查 IPMI SEL |
| 同配置机器中某台**性能明显差** | 过热降频（thermal throttling）或电源管理异常 |

### 3.2 CPU 过热降频排查（高频）

```bash
# 第 1 步：看 CPU 温度
sensors                              # lm_sensors 工具，看各核心温度
ipmitool sensor | grep -i temp       # 通过 BMC 看温度（更全，含进风口温度）

# 第 2 步：看当前频率 vs 标称频率，判断是否降频
lscpu | grep MHz                     # 当前频率
cat /proc/cpuinfo | grep MHz
cpupower frequency-info              # 频率调节器详情

# 第 3 步：看内核有没有降频记录
dmesg -T | grep -i -E "thermal|throttl"
# 典型输出：CPU0: Core temperature above threshold, cpu clock throttled
```

**降频的常见原因与处理：**

| 原因 | 处理 |
|------|------|
| 风扇故障/转速不足 | `ipmitool sensor \| grep -i fan` 查风扇转速，报修换风扇 |
| 散热硅脂老化、散热器接触不良 | 重新涂抹硅脂、紧固散热器 |
| 机房温度高/空调故障 | 看同机柜其他机器是否也高温，找 IDC |
| 灰尘堵塞风道 | 清灰 |

### 3.3 CPU 硬件排查流程

```text
怀疑 CPU 硬件问题
    │
dmesg 查 MCE：固定 SOCKET 反复报错 → 确诊，报修换 CPU/主板
    │
无 MCE 但机器重启：
    ├── ipmitool sel list → 有温度/电压告警 → 散热/电源问题
    └── SEL 也干净 → 查机房供电、PDU
    │
无 MCE 但性能差：
    ├── 查温度和频率 → 过热降频 → 处理散热
    └── 温度正常 → 查 microcode 版本、节能模式（C-State/P-State）
```

---

## 四、磁盘硬件问题排查

### 4.1 磁盘故障的典型日志（必考）

```bash
dmesg -T | grep -iE "error|fail|ata|scsi|nvme"
```

| 日志特征 | 含义 |
|----------|------|
| `Buffer I/O error on dev sda1` | 读盘失败，坏道或磁盘 dying |
| `ata1.00: failed command: READ FPDMA QUEUED` | SATA 链路层错误，磁盘/线缆/背板 |
| `sd 0:0:0:0: [sda] Sense Key : Medium Error` | 磁盘介质错误（坏块） |
| `blk_update_request: critical medium error` | 同上，块设备层报的介质错误 |
| `nvme nvme0: I/O 123 QID 4 timeout` | NVMe 盘 IO 超时，盘或固件问题 |
| `XFS (sda1): metadata I/O error` + 文件系统变只读 | 内核自我保护，停止写入防止数据进一步损坏 |

### 4.2 SMART 磁盘健康检查（必考）

磁盘固件内置的 **S.M.A.R.T.**（Self-Monitoring, Analysis and Reporting Technology）持续记录磁盘健康指标，用 `smartctl` 读取：

```bash
# 整体健康结论（PASSED / FAILED）
smartctl -H /dev/sda

# 完整信息（重点看下半部分的属性表）
smartctl -a /dev/sda

# 触发磁盘自检（短测几分钟，长测几十分钟）
smartctl -t short /dev/sda
smartctl -l selftest /dev/sda        # 查看自检结果
```

**重点关注的 SMART 属性（面试高频）：**

| 属性 | 含义 | 危险信号 |
|------|------|----------|
| `Reallocated_Sector_Ct` | 已重映射坏扇区数 | **RAW 值 > 0 且持续增长**，磁盘在坏 |
| `Current_Pending_Sector` | 待映射的疑似坏扇区 | > 0 就要警惕 |
| `Offline_Uncorrectable` | 无法修复的扇区数 | > 0 尽快换盘 |
| `Temperature_Celsius` | 磁盘温度 | > 60°C 影响寿命 |
| `Power_On_Hours` | 累计通电时间 | 评估磁盘年龄 |
| `Media_Wearout_Indicator`（SSD） | SSD 寿命磨损指标 | 接近 0 说明寿命将尽 |

**面试回答：**

> 排查磁盘问题，第一步看 dmesg 里有没有 `Buffer I/O error`、`Medium Error` 这类关键字，这基本可以确认是磁盘硬件问题而不是文件系统逻辑错误。第二步用 `smartctl -H` 看整体健康结论，用 `smartctl -a` 看详细属性，重点是 `Reallocated_Sector_Ct` 重映射扇区数，如果这个值不为零还在涨，说明磁盘正在产生坏道，应该尽快备份数据换盘。如果是 RAID 环境，还要用 `megacli` 或 `storcli` 看 RAID 卡视角的物理盘状态。线上发现磁盘报介质错误，标准动作是：先把该盘上的服务流量切走，备份关键数据，然后走换盘流程，换完重建 RAID 或重新同步副本。

### 4.3 磁盘故障排查流程

```text
dmesg 出现 I/O error
    │
smartctl -H /dev/sda 看健康状态
    │
    ├── FAILED → 直接确诊，切流量、备份、换盘
    │
    └── PASSED（SMART 可能滞后）→ 继续深挖：
            ├── smartctl -a 看重映射扇区计数
            ├── badblocks /dev/sda（离线坏道扫描，谨慎使用）
            ├── 检查 RAID 卡日志：storcli /c0 show events
            └── 换槽位/换线缆交叉验证 → 定位是盘还是背板/线缆
```

---

## 五、网卡与 PCIe 硬件问题排查

### 5.1 网卡硬件故障表现

| 症状 | 排查命令 |
|------|----------|
| 链路频繁 down/up（link flap） | `dmesg -T \| grep -i "link"` |
| 丢包、CRC 错误计数增长 | `ethtool -S eth0 \| grep -iE "err|crc|drop"` |
| 速率协商异常（千兆变成 100M） | `ethtool eth0` 看 Speed/Duplex |
| 网卡识别不到/掉卡 | `lspci \| grep -i eth` 看设备还在不在 |

```bash
# 查看网卡详细错误计数（重点）
ethtool -S eth0 | grep -iE "error|crc|drop|miss"
# 关键计数：
#   rx_crc_errors      CRC 校验错误 → 网线/光模块/对端端口问题
#   rx_missed_errors   网卡收包丢包 → 网卡 ring buffer 不足或硬件问题
#   tx_errors          发包错误

# 查看网卡硬件信息
ethtool -i eth0              # 驱动、固件版本
lspci -vv -s 02:00.0        # PCIe 视角的网卡详情

# 光模块信息（排查光衰）
ethtool -m eth0             # 看收发光功率，光衰过大 → 换光模块/光纤
```

### 5.2 PCIe AER 错误

**AER（Advanced Error Reporting）** 是 PCIe 设备的硬件错误上报机制，类似内存的 ECC：

```bash
dmesg -T | grep -i aer
# pcieport 0000:00:1c.0: AER: Corrected error received: 0000:03:00.0
# 03:00.0: PCIe Bus Error: severity=Corrected, type=Physical Layer
```

| 级别 | 含义 | 处理 |
|------|------|------|
| Corrected | PCIe 链路层自动重传修复 | 关注计数，持续增长要换件 |
| Uncorrected (Non-Fatal) | 单次传输失败，设备还能用 | 检查设备 |
| Uncorrected (Fatal) | 设备掉线 | 换设备（网卡/NVMe 盘/RAID 卡） |

### 5.3 网卡排查流程

```text
网络异常（丢包/断连/慢）
    │
ethtool -S 看错误计数
    │
    ├── rx_crc_errors 增长 → 物理层问题：
    │       换网线 → 换光模块 → 换交换机端口（逐段排除）
    │
    ├── link flap → 查对端交换机日志 + 光模块功率（ethtool -m）
    │
    ├── dmesg 有 AER 错误指向该网卡 → 网卡或 PCIe 插槽硬件问题
    │
    └── 错误计数干净 → 大概率不是硬件，走软件网络排查（见生产实战文档）
```

---

## 六、电源、温度与 IPMI 带外管理（必考）

### 6.1 IPMI/BMC 是硬件排查的最后底牌

**BMC（Baseboard Management Controller）** 是主板上独立的管理芯片，**有自己的 CPU、内存、网口、独立供电**，即使操作系统宕机、断电重启，BMC 依然工作并记录硬件事件。

**SEL（System Event Log）** 是 BMC 记录的硬件事件日志，是排查"机器为什么突然重启/宕机"的关键证据。

```bash
# 查看 BMC 记录的硬件事件（排查莫名重启的第一手资料）
ipmitool sel list
ipmitool sel elist                    # 带传感器读数的详细版
ipmitool sel list | grep -iE "crit|assert"

# 典型事件：
# 1 | 09/21/2026 | 10:15:22 | Temperature CPU1_Temp | Upper Critical going high | Asserted
# 2 | 09/21/2026 | 10:15:30 | Power Unit PS_Redundancy | Fully Redundant → Degraded
# 3 | 09/21/2026 | 10:15:35 | Watchdog2 | Hard reset | Asserted

# 查看所有传感器实时状态（温度/电压/风扇/电源）
ipmitool sensor
ipmitool sdr type Temperature
ipmitool sdr type Fan

# 查看电源状态
ipmitool power status
ipmitool chassis status               # 机箱状态（含上次重启原因）
```

**`ipmitool chassis status` 的 `Last Power Event` 字段（高频）：**

| 字段值 | 含义 |
|--------|------|
| `ac-failed` | 市电断过电 |
| `power-fault` | 电源故障 |
| `thermal` | 过热保护关机 |
| `watchdog` | 看门狗触发的重启 |

### 6.2 "机器莫名重启"排查流程（面试必背场景题）

```text
第 1 步：确认重启时间点
    last reboot / last -x shutdown reboot
    uptime 对比监控，确定精确时间
    │
第 2 步：查内核日志（时间点之前最后的内容）
    journalctl -k -b -1 --no-pager | tail -100    # 上一次启动的内核日志
    /var/log/messages 里找重启时间点之前有没有 Panic、MCE、OOM
    │
    ├── 有 Panic 堆栈 → 按堆栈分析（驱动 bug / 硬件 MCE）
    ├── 有 MCE Hardware Error → CPU/内存硬件故障
    ├── 有 OOM → 内存耗尽被杀（这是软件问题）
    └── 日志戛然而止，什么都没留下 → 硬件级断电/复位，去查 BMC
    │
第 3 步：查 IPMI SEL（内核日志查不到时的救命稻草）
    ipmitool sel elist | less
    找重启时间点附近的：
        ├── Temperature 告警 → 过热保护
        ├── Power 相关事件 → 电源/市电问题
        ├── Watchdog → 系统 hang 死被看门狗重启
        └── 完全无记录 → 机房 PDU 断电/人为操作
    │
第 4 步：横向对比
    同机柜/同 PDU 其他机器是否同时重启 → 供电/环境问题
    只有这一台 → 单机硬件问题
    │
第 5 步：取证留存
    抓 kdump vmcore（如果配置了）用 crash 工具分析
    确认硬件坏件 → 报修换件 → 观察闭环
```

**面试回答：**

> 排查机器莫名重启，我的流程是"先内核、后 BMC、再横向"。第一步用 `last reboot` 确认重启时间点，然后用 `journalctl -k -b -1` 翻上一次启动的内核日志最后部分——如果有 Panic 堆栈就分析堆栈，有 MCE 就是 CPU 内存硬件问题，有 OOM 就是内存耗尽。如果内核日志戛然而止什么都没留下，说明是硬件级断电复位，操作系统根本来不及写日志，这时第二步查 BMC 的带外日志 `ipmitool sel elist`，BMC 是独立供电的，机器挂了它还在记录，能看到过热保护、电源故障、看门狗复位这些事件。第三步横向对比同机柜机器是否同时重启，区分单机故障和机房供电环境问题。最后如果配置了 kdump，用 crash 工具分析 vmcore 拿到宕机时刻的完整现场，确诊坏件后报修换件并回归观察。

---

## 七、硬件导致的内核 Panic 与宕机分析

### 7.1 kdump 内核转储机制

kdump 利用 **kexec** 机制：预留一块内存常驻一个"捕获内核"，主内核 Panic 时直接跳转启动捕获内核，把宕机现场（vmcore）完整转储到磁盘。

```bash
# 检查 kdump 状态
systemctl status kdump
cat /proc/cmdline | grep crashkernel        # 需预留 crashkernel 内存

# 配置文件
cat /etc/kdump.conf                          # 配置 vmcore 存放路径

# vmcore 默认位置
ls /var/crash/127.0.0.1-*/
```

### 7.2 crash 工具分析 vmcore

```bash
# 安装 crash 和内核 debuginfo
crash /usr/lib/debug/lib/modules/$(uname -r)/vmlinux /var/crash/*/vmcore

# crash 交互环境常用命令
crash> bt              # 宕机时当前任务的调用栈（最重要）
crash> log             # 内核 ring buffer 最后的日志
crash> ps              # 宕机时的进程列表
crash> vm              # 内存状态
crash> dev -i          # 中断状态
crash> irq -a          # 中断统计
```

**硬件相关 Panic 的特征：** 堆栈中有 `mce`、`machine_check`、`#MC`，或 `log` 输出里有 `Hardware Error`、CPU 上下文错误码（RIP、CR2 指向异常地址），基本可以定性为硬件问题而非内核软件 bug。

### 7.3 系统 hang 死（无响应但未宕机）排查

| hang 类型 | 特征 | 排查 |
|-----------|------|------|
| 软 hang（soft lockup） | 某进程占住 CPU 不让出调度 | dmesg 找 `soft lockup - CPU#2 stuck for 22s`，分析对应进程 |
| 硬 hang（hard lockup） | CPU 关中断卡死，NMI 看门狗报 `hard LOCKUP` | 驱动自旋锁 bug 或 CPU 硬件问题 |
| 整机 hang 死，ping 不通 | 无任何日志 | 触发 SysRq（`echo c > /proc/sysrq-trigger` 强制 panic 落 kdump），或从 BMC 侧发 NMI 诊断 |

```bash
# SysRq 魔术键：系统 hang 死时的救命工具
echo 1 > /proc/sys/kernel/sysrq
echo t > /proc/sysrq-trigger     # 打印所有任务堆栈到 dmesg
echo w > /proc/sysrq-trigger     # 打印 D 状态（不可中断）任务
echo m > /proc/sysrq-trigger     # 打印内存信息
echo c > /proc/sysrq-trigger     # 强制 Panic，配合 kdump 抓现场
```

---

## 八、硬件信息收集工具箱（速查表）

| 工具 | 用途 | 常用命令 |
|------|------|----------|
| `dmesg -T` | 内核日志（硬件错误第一现场） | `dmesg -T \| grep -iE "error\|mce\|ecc\|fail"` |
| `journalctl -k` | 系统日志，可查历史启动 | `journalctl -k -b -1`（上一次启动） |
| `lscpu` | CPU 拓扑信息 | `lscpu -e`（按核列出） |
| `lspci` | PCIe 设备列表 | `lspci -vvv -s <slot>`（详情含 AER 能力） |
| `lsblk` | 块设备拓扑 | `lsblk -o NAME,SIZE,MODEL,SERIAL` |
| `dmidecode` | 读取 BIOS/主板 DMI 信息 | `dmidecode -t memory`（内存槽位）、`-t baseboard` |
| `smartctl` | 磁盘 SMART 健康 | `smartctl -a /dev/sda` |
| `sensors` | 温度/电压/风扇 | `sensors` |
| `ipmitool` | BMC 带外管理 | `ipmitool sel list`、`ipmitool sensor` |
| `rasdaemon` | RAS 错误统一收集 | `ras-mc-ctl --summary` |
| `mcelog` | MCE 解码 | `mcelog --client` |
| `memtester` | 内存压测 | `memtester 8G 1` |
| `ethtool` | 网卡信息与统计 | `ethtool -S eth0`、`ethtool -m eth0`（光模块） |
| `storcli`/`megacli` | RAID 卡管理 | `storcli /c0 show all` |
| `numactl -H` | NUMA 拓扑 | 排查跨节点内存访问问题 |
| `decode-dimms` | 内存条 SPD 信息 | 内存型号、厂商、序列号 |

---

## 九、面试高频问题速答

**Q1：怎么判断一个问题是硬件问题还是软件问题？**

> 看三点：一看日志，dmesg/messages 里有 mce、Hardware Error、ECC、I/O error 关键字的基本是硬件问题；二看规律，硬件故障通常无规律、与负载无关、随机出现，软件问题往往可复现、与流量/变更相关；三看替换验证，把应用迁移到同配置的其他机器，问题消失的大概率是原机器硬件问题，问题跟过来的是软件问题。

**Q2：MCE 是什么？看到 `Hardware Error` 日志怎么处理？**

> MCE 是 CPU 的机器检查异常机制，CPU 内部的 MCA 寄存器检测到缓存、内存控制器、总线错误时触发 18 号异常，内核 mce 驱动接管处理。日志里的关键字段是 Bank（出错部件）、ADDR（出错物理地址）、SOCKET（CPU 插槽）。处理上先区分可纠正还是不可纠正：可纠正的关注增长趋势，不可纠正的或同一 SOCKET 反复报错的，用 rasdaemon 定位具体部件后报修换件。

**Q3：ECC 内存的 CE 和 UE 有什么区别？线上 CE 持续增长怎么办？**

> CE 是可纠正的单比特错误，硬件自动修复，业务无感；UE 是多比特错误，无法修复，会导致杀进程甚至内核 Panic。CE 持续增长是内存条劣化的明确前兆，标准动作是：用 `ras-mc-ctl --errors` 定位出错的 DIMM 槽位，评估增长速度，安排在维护窗口更换内存条，换完回归观察 ce_count 是否归零。

**Q4：SMART 里哪些指标说明磁盘要坏了？**

> 重点看四个：`Reallocated_Sector_Ct` 重映射扇区数不为零且增长，说明坏道在扩大；`Current_Pending_Sector` 待映射扇区大于零要警惕；`Offline_Uncorrectable` 无法修复扇区大于零要尽快换盘；SSD 还要看 `Media_Wearout_Indicator` 磨损指标评估寿命。另外 `smartctl -H` 直接给出 PASSED/FAILED 结论，但要注意 SMART 有滞后性，报 FAILED 时盘往往已经很危险了，日常应该监控重映射扇区计数做提前预警。

**Q5：机器突然重启，内核日志什么都没留下，怎么排查？**

> 内核日志来不及写说明是硬件级断电复位，这时要靠 BMC 的带外日志：`ipmitool sel elist` 查系统事件日志，BMC 独立供电独立运行，机器挂了它还在记录，能看到过热保护、电源故障、看门狗复位等事件；`ipmitool chassis status` 的 Last Power Event 字段直接给出上次掉电/重启原因。同时横向对比同机柜同 PDU 的其他机器是否同时重启，区分单机故障和机房供电问题。如果配置了 kdump 且是 Panic 类重启，还可以用 crash 工具分析 vmcore。

**Q6：同一批机器里有一台性能明显差，怀疑硬件，怎么查？**

> 先查 CPU 是否过热降频：`sensors` 看温度、`cpupower frequency-info` 看实际频率是否低于标称、dmesg 搜 `throttled`。再查内存：`ras-mc-ctl --error-counts` 看有没有 ECC 错误导致内存降频或通道降级。再查磁盘：`iostat -x 1` 对比 await 是否异常高、`smartctl -a` 看坏道。网卡侧 `ethtool` 看协商速率是否从万兆掉到千兆。这套查下来，CPU 降频、内存降级、磁盘坏道、网卡降速这几个硬件性能杀手就都能覆盖到。

**Q7：文件系统突然变只读了，什么原因？**

> 大概率是磁盘硬件出问题了。内核检测到块设备报介质错误（Medium Error）后，为了防止错误数据继续写入造成更大范围的损坏，文件系统（ext4/xfs）默认会把挂载点切换为只读进行自我保护。排查动作：dmesg 搜 `I/O error` 确认是哪块盘，`smartctl` 检查磁盘健康，确认硬件问题后走备份换盘流程。应急恢复可以先 umount 后 fsck，但根因不解决还会再犯。

---

## 十、总结：硬件排查三板斧

```text
┌──────────────────────────────────────────────────────┐
│  一板斧：内核日志取证                                   │
│    dmesg + journalctl → 找 mce/ecc/io error 关键字    │
├──────────────────────────────────────────────────────┤
│  二板斧：专用工具确诊                                   │
│    内存 → rasdaemon / EDAC / memtester               │
│    磁盘 → smartctl / storcli                         │
│    网卡 → ethtool -S / AER                           │
│    整机 → ipmitool sel list（BMC 带外日志，宕机后的底牌）│
├──────────────────────────────────────────────────────┤
│  三板斧：交叉验证换件闭环                               │
│    横向对比同批次机器 → 定位坏件 → 报修 → 回归观察      │
└──────────────────────────────────────────────────────┘
```

**核心原则：** 硬件排查和性能排查最大的不同是——性能问题靠"分析"找瓶颈，硬件问题靠"证据"找坏件。dmesg、SMART、IPMI SEL 这三个日志源是硬件排查的铁三角，任何硬件故障都会在其中至少一处留下痕迹。
