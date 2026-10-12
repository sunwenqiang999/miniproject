# Linux 生产实战面试知识点

## 阅读建议

Linux 面试知识点已按复习层次拆分为四类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`linux面试知识点.md`](linux面试知识点.md) | 总览复习 | Linux 基础、目录结构、常用命令、权限管理、进程管理、Shell 基础、整体知识框架 |
| [`linux原理机制面试知识点.md`](linux原理机制面试知识点.md) | 原理深入 | 用户态内核态、VFS、inode、硬链接软链接、进程调度、虚拟内存、Page Cache、Swap、OOM |
| [`linux生产实战面试知识点.md`](linux生产实战面试知识点.md) | 进阶实战 | 性能分析方法论、CPU/内存/磁盘/网络排查命令、日志分析三剑客、线上问题排查模板 |
| [`linux硬件问题排查面试知识点.md`](linux硬件问题排查面试知识点.md) | 硬件专题 | MCE/EDAC/RAS 硬件错误检测机制、CPU/内存/磁盘/网卡硬件故障排查、IPMI 带外管理、宕机根因分析 |

本文档是面试中最能体现实战能力的部分，建议理解原理后背诵第七节的排查模板。CPU/内存两大场景可结合 [`../场景题/CPU飙高场景题排查思路.md`](../场景题/CPU飙高场景题排查思路.md) 与 [`../场景题/内存飙升场景题排查思路.md`](../场景题/内存飙升场景题排查思路.md) 一起复习。

---

## 一、性能分析方法论

### 1.1 USE 方法（Utilization / Saturation / Errors）

Netflix 性能工程师 Brendan Gregg 提出的 USE 方法：对每一类资源检查三个指标。

| 指标 | 含义 | CPU 示例 | 内存示例 | 磁盘示例 | 网卡示例 |
|------|------|----------|----------|----------|----------|
| 使用率 Utilization | 资源忙于服务的时间占比 | %us+%sy | used/total | %util | 带宽占比 |
| 饱和度 Saturation | 排队等待的程度 | load / 核数 | swap 换入换出 | avgqu-sz | 网卡队列丢包 |
| 错误 Errors | 错误事件计数 | - | OOM、maj_flt | IO error | 丢包、重传 |

**排查顺序口诀：** 先整体（top/uptime）→ 再分类（CPU/内存/磁盘/网络）→ 后定位（进程 → 线程 → 代码）。

### 1.2 Load Average 平均负载（必考）

`uptime` / `top` 第一行的 `load average: 2.14, 1.85, 1.60` 分别表示 1、5、15 分钟的平均负载。

**负载的准确定义：** 单位时间内处于 **R（运行/等待 CPU）+ D（不可中断睡眠，通常在等磁盘 IO）** 状态的进程平均数。

**判断标准（以 CPU 核数为基准）：**

| load / 核数 | 含义 |
|-------------|------|
| < 0.7 | 健康 |
| 0.7 ~ 1.0 | 较忙，需关注 |
| > 1.0 | 过载，有进程在排队 |
| > 核数 × 3 | 严重过载 |

**面试高频追问：Load 高一定是 CPU 高吗？**

> 不一定。Load 统计的是 R + D 状态进程数。两种情况 Load 高但 CPU 不高：
> 1. **大量进程 D 状态等磁盘 IO**（磁盘慢、IO 打满），CPU 空闲但负载飙升。
> 2. **进程数远多于核数**，大量进程在 R 状态排队。
>
> 排查时先看 CPU 使用率（us/sy）高不高：CPU 高说明是计算密集；CPU 低但 wa 高说明是 IO 问题；用 `ps aux | awk '$8 ~ /^D/'` 找 D 状态进程。

```bash
nproc                       # 查看核数
uptime                      # 看负载
cat /proc/loadavg           # 负载详情：2.14 1.85 1.60 3/520 12345
#                            1分  5分 15分 运行中/总进程数 最大PID
```

### 1.3 top 输出全解析（必考）

```text
top - 10:30:00 up 100 days,  3:15,  2 users,  load average: 2.14, 1.85, 1.60
Tasks: 200 total,   2 running, 197 sleeping,   1 stopped,   0 zombie
%Cpu(s): 25.0 us,  5.0 sy,  0.0 ni, 68.0 id,  1.5 wa,  0.0 hi,  0.5 si,  0.0 st
KiB Mem : 16384000 total,  1024000 free,  8192000 used,  7168000 buff/cache
KiB Swap:  4096000 total,  4096000 free,        0 used.  6500000 avail Mem
```

**CPU 行每个字段的含义与异常指向：**

| 字段 | 含义 | 高了说明什么 |
|------|------|--------------|
| us | 用户态 CPU | 应用计算密集（业务代码、GC） |
| sy | 内核态 CPU | 系统调用频繁、上下文切换多、IO 密集 |
| ni | nice 调整过优先级的用户进程 | 少见 |
| id | 空闲 | 越低成本越低 |
| wa | 等待 IO | **磁盘/存储瓶颈**，配合 iostat 确认 |
| hi | 硬中断 | 硬件中断频繁 |
| si | 软中断 | 网络收发包繁忙、定时器多 |
| st | 被虚拟机偷走的时间 | 云主机超售、宿主机繁忙，找云厂商 |

**内存行关键点：**

- `buff/cache` 是 Page Cache，**可回收**，不是真正被占用。
- **`avail Mem` 才是真实可用内存**，不要看 `free` 列误判内存不足。

---

## 二、CPU 排查

### 2.1 CPU 飙高排查四板斧（面试必背）

**场景：** 监控告警某台机器 CPU 使用率 95%+。

```text
第 1 板斧：top → 按 P 排序，找到吃 CPU 的进程 PID（如 12345）
     │
第 2 板斧：top -H -p 12345 → 找到吃 CPU 的线程 TID（如 12360）
     │
第 3 板斧：printf "%x\n" 12360 → 得到 16 进制线程号（如 0x2e98）
     │
第 4 板斧：jstack 12345 | grep -A 30 0x2e98 → 定位到具体 Java 代码行
```

**完整命令：**

```bash
top                                        # 1. 找进程
top -H -p 12345                            # 2. 找线程（Java 进程每个线程对应一个 LWP）
printf "%x\n" 12360                        # 3. 转 16 进制（jstack 里的 nid 是 16 进制）
jstack 12345 > /tmp/stack.txt              # 4. 导出线程快照再分析，避免线上多次打快照
grep -A 30 "nid=0x2e98" /tmp/stack.txt     # 5. 定位堆栈
```

**常见根因：**

| 堆栈特征 | 根因 |
|----------|------|
| 业务方法自旋/正则/序列化 | 代码死循环、正则灾难回溯、大对象 JSON 化 |
| GC 线程（GC Thread#n）占满 | Full GC 频繁，内存不足或内存泄漏 |
| C2 CompilerThread | JIT 编译热点方法，一般短暂 |
| 大量线程 BLOCKED 在某锁 | 锁竞争激烈，synchronized 热点 |

**面试回答：**

> CPU 飙高排查四步：先用 top 按 CPU 排序找到目标进程；再用 `top -H -p PID` 找到具体线程；然后 `printf %x` 把线程号转 16 进制，因为 jstack 里的 nid 是 16 进制；最后 jstack 抓线程快照，grep 这个 nid 定位到具体代码。如果是 GC 线程吃 CPU，说明在频繁 Full GC，转向内存排查；如果是业务线程，重点看有没有死循环、正则回溯、大对象序列化。线上打 jstack 建议重定向到文件分析，避免反复打快照加重负担。

### 2.2 其他 CPU 分析工具

```bash
mpstat -P ALL 1              # 看每个核的使用率（单核打满常见于单线程热点、中断绑核不均）
pidstat -u 1                 # 按进程看 CPU
pidstat -w 1                 # 看上下文切换：cswch 自愿 / nvcswch 非自愿
vmstat 1                     # 看 cs（切换次数）、r（运行队列长度）
perf top                     # 实时查看热点函数（内核态+用户态）
perf record -g -p 12345      # 采样进程调用栈
perf report                  # 分析采样结果（火焰图原料）
```

**vmstat 输出关键列：**

```text
procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----
 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st
 4  0      0  500000 200000 3000000  0    0    10    20 2000 5000 30  5 65  0  0
```

- `r`：运行队列长度，**持续大于核数说明 CPU 饱和**。
- `b`：D 状态进程数，持续大于 0 说明 IO 有瓶颈。
- `si/so`：swap 换入换出，持续非 0 说明内存不足。
- `cs`：上下文切换，几十万级说明线程过多或锁竞争。

---

## 三、内存排查

### 3.1 free 输出解析（必考）

```bash
free -h
#               total   used    free    shared  buff/cache  available
# Mem:           16G    8.0G    1.0G    200M      7.0G        7.5G
# Swap:         4.0G      0B    4.0G
```

| 列 | 含义 |
|----|------|
| used | 应用真正使用的内存 |
| free | 完全未使用的内存（这个低**不代表**内存不足） |
| buff/cache | Page Cache + slab，应用需要时可回收 |
| **available** | **估算的可用内存（free + 可回收的 cache），看这个判断内存够不够** |

### 3.2 内存排查命令体系

```bash
# 整体
free -h                          # 总览（看 available）
vmstat 1                         # si/so 看 swap 活动
sar -r 1                         # 内存使用率历史（sysstat）

# 进程级
ps aux --sort=-rss | head -20    # 按物理内存排序找大户
smem -k                          # 更准确的 PSS 统计（共享内存按比例分摊）
cat /proc/<pid>/status | grep VmRSS   # 单进程实际物理内存
pmap -x <pid>                    # 进程内存映射详情（堆、栈、mmap 各占多少）

# 内核级
slabtop                          # slab 缓存（dentry/inode 缓存膨胀可查这个）
cat /proc/meminfo                # 全部内存指标

# OOM 相关
dmesg -T | grep -i "killed process"      # OOM Killer 记录
cat /proc/<pid>/oom_score                # 进程 OOM 得分
```

### 3.3 内存泄漏 vs 内存膨胀（高频）

| 现象 | 可能原因 | 验证 |
|------|----------|------|
| Java 进程 RSS 缓慢增长不回落 | 堆内泄漏（集合类只进不出）、堆外泄漏（Netty DirectBuffer、线程栈过多）、元数据区泄漏（动态生成类） | jmap -histo 看对象分布；NMT（-XX:NativeMemoryTracking）看堆外 |
| cache 持续增长 | 文件读写多导致 Page Cache 增长，**正常现象**，需要时会回收 | available 仍然充足就无需处理 |
| slab 中 dentry 巨大 | 大量文件被打开/查找过，目录项缓存堆积 | slabtop 确认，可 `echo 2 > /proc/sys/vm/drop_caches` 释放 |
| 进程 RSS 不减 | glibc 的 malloc 回收滞后（内存碎片化） | 可设 MALLOC_ARENA_MAX，或 Java 用 -XX:+UseG1GC 归还可回收堆 |

**drop_caches 手动清缓存（应急用，生产谨慎）：**

```bash
sync                                # 先把脏页刷盘，防止丢数据
echo 1 > /proc/sys/vm/drop_caches   # 清 Page Cache
echo 2 > /proc/sys/vm/drop_caches   # 清 dentry/inode 缓存
echo 3 > /proc/sys/vm/drop_caches   # 两者都清
```

> 注意：清缓存后磁盘读会穿透到磁盘造成短暂 IO 上升，仅限应急，不要写进定时任务常态化执行。

### 3.4 Java 进程被 OOM Killer 杀掉的特征（必考）

**现象：** 服务进程消失，没有 JVM 错误日志、没有 heap dump，supervisor/systemd 显示进程已退出。

**判断：**

```bash
dmesg -T | grep -i "killed process"
# Out of memory: Killed process 12345 (java) total-vm:xxx anon-rss:xxx ...
```

**区别：**

| 死法 | 日志位置 | 原因 |
|------|----------|------|
| JVM 堆 OOM（OutOfMemoryError） | 应用日志 / hs_err_pid 文件 | 堆不够或堆内泄漏 |
| 被 OOM Killer 杀 | dmesg / /var/log/messages | **整机**内存不足，JVM 只是得分最高的倒霉蛋 |

---

## 四、磁盘排查

### 4.1 磁盘打满排查（必考）

**标准流程：**

```bash
# 第 1 步：确认哪个分区满
df -h

# 第 2 步：定位大目录（--max-depth 逐层下钻）
du -sh /* 2>/dev/null | sort -rh | head -10
du -h --max-depth=1 /var | sort -rh | head -10
du -h --max-depth=1 /data | sort -rh | head -10

# 第 3 步：找出大文件
find /data -type f -size +500M -exec ls -lh {} \;

# 第 4 步：df 显示满但 du 找不到大文件？查被占用已删除文件
lsof | grep deleted
```

**常见罪魁祸首：** 应用日志没配切割、core dump 文件、Docker 容器日志（/var/lib/docker/containers/*-json.log）、MySQL binlog、临时文件堆积。

**清理注意：**

- 正在被写入的日志**不要直接 rm**，空间不会释放。先 `> app.log` 清空内容释放块，再配置 logrotate。
- Docker 日志配置 `log-opt max-size=100m max-file=3` 限制滚动。

### 4.2 df 与 du 显示不一致 / inode 耗尽

| 现象 | 原因 | 解决 |
|------|------|------|
| df 满、du 统计远小于 df | 已删除文件被进程持有 | `lsof \| grep deleted`，重启进程 |
| df 显示 Use% 100% 但还有几十 G | 文件系统保留块（默认 5% 给 root） | `tune2fs -m 1 /dev/sda1` 调小保留比例 |
| 有空间但创建文件报 No space left | **inode 耗尽**（小文件太多） | `df -i` 确认，清理海量小文件（如 session 文件、邮件队列） |

### 4.3 磁盘 IO 高排查

```bash
# 第 1 步：确认哪块盘忙
iostat -x 1
# 重点列：
#   %util      磁盘繁忙程度（接近 100% 说明饱和）
#   await      每次 IO 平均等待时间（ms，SSD 应 < 2ms，高了说明慢）
#   r_await / w_await   读写分别的延迟
#   avgrq-sz   平均 IO 大小（判断随机 IO 还是顺序 IO）

# 第 2 步：找谁在读写
iotop -oP                    # 只显示正在做 IO 的进程
pidstat -d 1                 # 按进程看磁盘读写

# 第 3 步：Java 应用写日志导致的，检查是否同步刷盘配置过猛
```

**iostat 关键列解读：**

| 列 | 健康参考 |
|----|----------|
| %util < 60% | 正常 |
| %util ≈ 100% 且 await 高 | 磁盘已成瓶颈 |
| await 低但 %util 高 | IO 量大但磁盘扛得住（如顺序写），不算异常 |

---

## 五、网络排查

### 5.1 连接状态统计（必考）

```bash
ss -s                                # Socket 汇总
ss -ant | awk '{print $1}' | sort | uniq -c | sort -rn   # 各状态连接数统计
netstat -nat | awk '/^tcp/ {++S[$NF]} END {for(a in S) print a, S[a]}'
```

**TCP 状态含义与异常指向：**

| 状态 | 含义 | 大量存在说明 |
|------|------|--------------|
| ESTABLISHED | 正常连接 | 并发高，正常 |
| TIME_WAIT | **主动关闭方**等待 2MSL | 短连接过多，应用主动关连接频繁；可调 tcp_tw_reuse 或改长连接/连接池 |
| CLOSE_WAIT | **被动关闭方**等待应用 close | **代码 bug：对方关了连接但自己没 close()，连接泄漏** |
| SYN_SENT | 发起连接未收到 ACK | 目标不可达或被防火墙丢包 |
| SYN_RECV | 收到 SYN 未完成握手 | 可能的 SYN 洪水攻击或 backlog 溢出 |

**面试回答：**

> TIME_WAIT 和 CLOSE_WAIT 是两类高频问题。TIME_WAIT 是主动关闭方等 2MSL 的状态，大量出现说明短连接太频繁，方案是改 HTTP 长连接、用连接池，或调整内核参数 tcp_tw_reuse 允许复用。CLOSE_WAIT 大量出现基本是对端关闭了连接但本端应用忘了 close，属于连接泄漏的代码 bug，要检查 IO 框架的连接管理，比如 HTTP client 没有释放 response。

### 5.2 端口不通排查链路（必背模板）

```text
服务在 A 机器，客户端连不上 A:8080，分层排查：

1. 服务进程是否在？
   ps -ef | grep java

2. 端口是否在监听？监听在什么地址？
   ss -tunlp | grep 8080
   → 监听 127.0.0.1:8080 只能本机访问！要监听 0.0.0.0

3. 本机防火墙是否放行？
   iptables -L -n | grep 8080
   firewall-cmd --list-all
   → 云主机还要查安全组规则！

4. 网络是否可达？
   从客户端：ping A_ip           （ICMP 通不代表 TCP 通）
             telnet A_ip 8080     或  nc -zv A_ip 8080

5. 端口是否在 backlog 排队？
   ss -lnt | grep 8080    （Send-Q 列持续满说明 accept 不及时）

6. 抓包确认包到底到哪层了：
   tcpdump -i any port 8080 -nn
```

### 5.3 tcpdump 抓包实战

```bash
# 基础抓包
tcpdump -i eth0 port 8080 -nn               # -nn 不解析主机名和端口名，快
tcpdump -i any host 10.0.0.2 -nn            # 抓与特定 IP 的通信
tcpdump -i eth0 tcp port 8080 and host 10.0.0.2 -nn

# 保存到文件用 Wireshark 分析
tcpdump -i eth0 port 8080 -nn -w /tmp/8080.pcap -c 10000   # 抓 1 万个包后停止

# 看 TCP 标志位（排查握手/挥手问题）
tcpdump -i eth0 'tcp[tcpflags] & tcp-syn != 0' -nn   # 只看 SYN 包
tcpdump -i eth0 'tcp[tcpflags] & tcp-rst != 0' -nn   # 只看 RST（连接被重置）

# 抓 HTTP 请求内容（调试用，明文才有效）
tcpdump -i eth0 port 80 -nn -A | grep -E "GET|POST"
```

### 5.4 网络吞吐与延迟

```bash
sar -n DEV 1                 # 每秒网卡收发流量（rxkB/s 接近带宽上限说明打满）
iftop -i eth0                # 实时按连接显示流量，找流量大户
ethtool eth0 | grep Speed    # 网卡速率（Speed: 10000Mb/s）
ping -c 10 目标IP             # 看延迟和丢包
mtr 目标IP                    # ping + traceroute 结合，定位链路哪一跳丢包
ss -i | grep -A1 目标IP       # 看 TCP 连接的 rtt、重传率
```

---

## 六、日志分析实战（三剑客组合）

> 面试常现场出题："给你一份 Nginx access.log，统计 XXX"。access.log 典型格式：
> `10.0.0.1 - - [17/Sep/2026:10:00:01 +0800] "GET /api/user HTTP/1.1" 200 1234 0.052 "https://ref.com" "Mozilla/5.0"`
> 对应列：$1=IP $4=时间 $7=URL $9=状态码 $10=字节数 $11=耗时（若配置）

### 6.1 高频统计场景

```bash
# 1. 访问量 Top10 的 IP
awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10

# 2. 状态码分布统计
awk '{print $9}' access.log | sort | uniq -c | sort -rn

# 3. 访问量 Top10 的 URL
awk '{print $7}' access.log | sort | uniq -c | sort -rn | head -10

# 4. 5xx 错误的 URL 及次数
awk '$9 ~ /^5/ {print $7}' access.log | sort | uniq -c | sort -rn

# 5. 平均响应时间
awk '{sum+=$11; count++} END {print sum/count}' access.log

# 6. 响应时间 Top10 的慢请求
awk '{print $11, $7}' access.log | sort -rn | head -10

# 7. 每秒请求数 QPS 峰值
awk -F'[' '{print $2}' access.log | awk -F: '{print $2":"$3":"$4}' | sort | uniq -c | sort -rn | head

# 8. 统计今天 ERROR 日志数
grep "ERROR" app.log | grep "$(date +%Y-%m-%d)" | wc -l
```

### 6.2 时间窗口与上下文提取

```bash
# 提取 10:00 到 11:00 之间的日志（排查故障时间段）
sed -n '/17\/Sep\/2026:10:00:00/,/17\/Sep\/2026:11:00:00/p' access.log

# 提取某次异常前后 50 行（还原现场）
grep -n "NullPointerException" app.log | head -1     # 先拿行号 12345
sed -n '12295,12395p' app.log                        # 再取前后 50 行

# 或者直接看异常堆栈（异常行 + 后面 20 行）
grep -A 20 "NullPointerException" app.log

# 实时跟踪且只看 ERROR，带行缓冲防止无输出
tail -f app.log | grep --line-buffered "ERROR"
```

### 6.3 综合面试题：找出最耗时的接口并统计

**题目：** 找出访问次数超过 100 且平均耗时最高的接口。

```bash
awk '{count[$7]++; sum[$7]+=$11} END {for (url in count) if (count[url] > 100) printf "%s 次数:%d 平均耗时:%.3f\n", url, count[url], sum[url]/count[url]}' access.log | sort -k5 -rn | head
```

**思路：** awk 用两个关联数组分别累加次数和耗时，END 块遍历计算平均值并过滤，管道 sort 排序。

---

## 七、线上问题排查模板（背诵版）

### 7.1 模板一：CPU 飙高

```text
1. top 按 P 排序 → 确认是哪个进程（是不是我们的 Java 服务）
2. top -H -p PID → 找到高 CPU 线程 TID
3. printf "%x\n" TID → 转 16 进制
4. jstack PID > stack.txt；grep -A 30 "nid=0xXXXX" stack.txt → 定位代码
5. 分支判断：
   - 业务线程死循环/正则/序列化 → 改代码或先扩容止血
   - GC 线程高 → 转内存排查（jstat -gcutil 看 Full GC 频率）
   - 多个线程 BLOCKED 同一把锁 → 锁优化
6. 止血：重启/下线问题实例，保留现场（jstack、jmap dump）供复盘
```

### 7.2 模板二：内存飙升 / OOM

```text
1. free -h → 看 available 是否紧张，区分"真不够"还是"cache 多"
2. ps aux --sort=-rss | head → 找内存大户进程
3. 若是 Java 进程：
   - jstat -gcutil PID 1000 → 看 GC 频率和老年代占用
   - jmap -histo:live PID | head -30 → 看大对象
   - jmap -dump:live,format=b,file=heap.bin PID → 堆 dump 用 MAT 分析
4. 若 RSS 远大于堆设置 → 堆外内存：Netty DirectBuffer、线程数过多、mmap
5. 若进程突然消失 → dmesg -T | grep "killed process" 查 OOM Killer
6. 止血：重启前先 dump 保留现场；短期扩容，长期修复泄漏
```

### 7.3 模板三：磁盘打满

```text
1. df -h → 定位满的分区
2. df -i → 排除 inode 耗尽（小文件过多）
3. du -h --max-depth=1 /分区 | sort -rh | head → 逐层下钻定位大目录
4. find /目录 -type f -size +500M → 定位大文件（日志、dump、binlog）
5. du 找不到但 df 满 → lsof | grep deleted → 被进程持有的已删除文件
6. 清理：
   - 在写的日志先 > file 清空，不要直接 rm
   - 配置 logrotate / Docker log-opt 防止复发
```

### 7.4 模板四：Load 高但 CPU 不高

```text
1. uptime → 确认 load 与核数比值
2. top 看 wa（IO 等待）高不高
3. wa 高 → iostat -x 1 确认磁盘 %util 和 await → iotop -oP 找 IO 大户
4. ps aux | awk '$8 ~ /^D/' → 找 D 状态进程（不可中断，多在等 IO）
5. r 队列长（vmstat 的 r 列）→ 进程数超核数，调度饱和
```

### 7.5 模板五：服务端口不通

```text
1. ps -ef | grep 服务名 → 进程活着吗
2. ss -tunlp | grep 端口 → 在监听吗？监听 0.0.0.0 还是 127.0.0.1？
3. 本机 curl 127.0.0.1:端口 → 服务本身正常吗
4. iptables -L -n / firewall-cmd --list-all → 防火墙
5. 云主机 → 检查安全组（最容易漏！）
6. tcpdump -i any port 端口 -nn → 客户端的包到没到本机
   - 没到 → 网络链路/安全组问题
   - 到了没回 → 应用或 backlog 满（ss -lnt 看 Send-Q）
```

### 7.6 模板六：接口突然变慢（全链路）

```text
1. 看监控确认范围：单机慢还是全集群慢（单机 → 机器问题；集群 → 依赖/流量问题）
2. top → CPU 高转 CPU 排查模板；wa 高转 IO 排查
3. free -h + vmstat 1 → swap 活动（si/so 非 0 会导致毛刺）
4. Java：jstat -gcutil → Full GC 频繁会导致 STW 停顿
5. ss -ant 看连接状态 → CLOSE_WAIT 堆积说明下游连接泄漏
6. 依赖排查：下游服务、DB 慢查询、Redis 大 key
7. sar -n DEV 1 → 网卡流量是否打满
8. 拿慢请求 trace/日志，定位耗时在哪个环节
```

---

## 八、安全与加固（简要）

```bash
# 登录审计
last                         # 登录历史
lastb                        # 失败登录尝试（爆破检测）
cat /var/log/secure | grep "Failed password"   # SSH 爆破记录

# 入侵排查应急
ps aux --sort=-%cpu | head   # 找异常进程（挖矿常伪装成 kworker）
crontab -l                   # 查恶意定时任务
cat /etc/rc.local            # 查开机自启
ls -lt /etc/cron.d/          # 查被篡改的 cron
ss -tunlp | grep -v "127.0.0.1"  # 查异常监听端口（后门）
find / -perm -4000 -type f   # 查 SUID 文件（提权风险）
```

**基本加固清单：**

- SSH 禁密码登录改密钥 + 禁 root 直登（`PermitRootLogin no`）
- 防火墙默认拒绝，按需放行
- 定期 `yum update` 打安全补丁
- 重要命令 history 审计、操作走堡垒机

---

## 九、本章面试速记卡

1. **USE 方法**：每类资源看使用率、饱和度、错误三指标。
2. **Load 定义**：R + D 状态进程平均数；Load 高 CPU 不高 → 查 D 状态和 IO。
3. **top 字段**：wa 高=IO 瓶颈，si 高=网络软中断忙，st 高=云主机被超售。
4. **CPU 四板斧**：top → top -H → printf %x → jstack grep nid。
5. **free 看 available**，不看 free；buff/cache 可回收。
6. **Java 进程无故消失**：先 dmesg 查 OOM Killer，别只翻应用日志。
7. **磁盘满**：df 定位分区 → du 下钻 → lsof|grep deleted 兜底；inode 耗尽用 df -i。
8. **TIME_WAIT 多**=短连接太多（改长连接）；**CLOSE_WAIT 多**=代码忘 close（连接泄漏）。
9. **端口不通链路**：进程 → 监听地址 → iptables → 云安全组 → tcpdump 确认包到没到。
10. **日志统计模板**：`awk '{print $列}' log | sort | uniq -c | sort -rn | head`。
11. **清 drop_caches 前先 sync**，且只能应急不能常态化。
12. **删日志别直接 rm 在写文件**：先 `> file` 清空，再配 logrotate。
