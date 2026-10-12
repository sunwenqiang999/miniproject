# Linux 面试知识点总结

## 阅读建议

Linux 面试知识点已按复习层次拆分为四类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`linux面试知识点.md`](linux面试知识点.md) | 总览复习 | Linux 基础、目录结构、常用命令、权限管理、进程管理、Shell 基础、整体知识框架 |
| [`linux原理机制面试知识点.md`](linux原理机制面试知识点.md) | 原理深入 | 用户态内核态、VFS、inode、硬链接软链接、进程调度、虚拟内存、Page Cache、Swap、OOM |
| [`linux生产实战面试知识点.md`](linux生产实战面试知识点.md) | 进阶实战 | 性能分析方法论、CPU/内存/磁盘/网络排查命令、日志分析三剑客、线上问题排查模板 |
| [`linux硬件问题排查面试知识点.md`](linux硬件问题排查面试知识点.md) | 硬件专题 | MCE/EDAC/RAS 硬件错误检测机制、CPU/内存/磁盘/网卡硬件故障排查、IPMI 带外管理、宕机根因分析 |

建议先掌握本文档的常用命令和权限模型，再学习原理机制，最后背诵生产实战的排查模板。线上排查类场景可结合 [`../场景题/CPU飙高场景题排查思路.md`](../场景题/CPU飙高场景题排查思路.md) 和 [`../场景题/内存飙升场景题排查思路.md`](../场景题/内存飙升场景题排查思路.md) 一起复习；原理部分可结合 [`../os/操作系统面试知识点.md`](../os/操作系统面试知识点.md) 对照学习。

---

## 一、Linux 基础概念

### 1.1 什么是 Linux？

Linux 是一套开源的类 Unix 操作系统**内核**，由 Linus Torvalds 于 1991 年发布。日常所说的"Linux 系统"通常是 **Linux 内核 + GNU 工具链 + 各类软件**组成的发行版。

**核心特点：**

- 开源免费：遵循 GPL 协议，源码开放，可自由修改
- 多用户多任务：支持多个用户同时登录，同时运行多个进程
- 一切皆文件：硬件设备、管道、Socket 都抽象为文件，统一用文件接口操作
- 稳定性强：服务器可数年不重启，是互联网服务器的绝对主力
- 安全性高：完善的权限模型、SELinux、社区漏洞修复快
- 丰富的命令行工具：配合管道可组合出强大的文本处理能力

**常见发行版：**

| 系列 | 代表 | 包管理 | 典型场景 |
|------|------|--------|----------|
| RedHat 系 | CentOS、RHEL、Rocky Linux | yum / dnf（rpm 包） | 企业生产环境 |
| Debian 系 | Debian、Ubuntu | apt（deb 包） | 开发机、云服务器 |
| 其他 | Alpine、Arch、Gentoo | apk / pacman | 容器镜像（Alpine 极小） |

**面试回答：**

> Linux 是一个开源的类 Unix 操作系统内核，配合 GNU 工具组成完整操作系统。它的设计哲学是"一切皆文件"，进程、设备、管道、Socket 统一抽象为文件。Linux 凭借开源、稳定、安全、性能好的特点，成为服务器领域的主流操作系统，我们生产环境的应用、Docker、K8s 全部运行在 Linux 之上。

### 1.2 Linux 内核与发行版的关系

- **内核（Kernel）**：操作系统核心，负责进程调度、内存管理、文件系统、设备驱动、网络协议栈，由 kernel.org 统一维护版本号（如 5.10、6.1）。
- **发行版（Distribution）**：厂商在内核之上打包安装器、包管理器、桌面环境、常用软件形成的完整系统，如 CentOS 7 默认内核 3.10，CentOS Stream 9 默认 5.14。

```bash
# 查看内核版本
uname -r
# 查看发行版信息
cat /etc/os-release
cat /etc/redhat-release    # RedHat 系
```

### 1.3 Linux 目录结构（FHS 标准）

Linux 采用单根目录树结构，所有目录都从 `/` 开始，不像 Windows 有 C 盘 D 盘。

```text
/
├── bin      -> usr/bin     常用命令（ls、cp、ps），所有用户可用
├── sbin     -> usr/sbin    系统管理命令（iptables、fdisk），root 使用
├── boot                    内核与启动引导文件
├── dev                     设备文件（/dev/sda 磁盘、/dev/null、/dev/zero）
├── etc                     配置文件（/etc/passwd、/etc/hosts、/etc/sysctl.conf）
├── home                    普通用户家目录
├── root                    root 用户家目录
├── proc                    虚拟文件系统，内核与进程运行时信息（/proc/cpuinfo、/proc/[pid]）
├── sys                     虚拟文件系统，内核对象与硬件信息
├── tmp                     临时文件，重启可能清空
├── usr                     用户程序与数据（Unix System Resources）
│   ├── bin、sbin           应用程序命令
│   ├── local               本地编译安装的软件（推荐安装位置）
│   └── lib                 共享库
├── var                     经常变化的数据
│   ├── log                 日志（/var/log/messages、/var/log/nginx/）
│   └── spool               队列数据（邮件、cron）
├── run                     运行时数据（PID 文件）
└── mnt / media             临时挂载点 / 可移动介质挂载点
```

**高频面试点：**

| 目录 | 面试会问什么 |
|------|--------------|
| `/proc` | 它是虚拟文件系统不占磁盘；`/proc/[pid]/fd` 查进程打开的文件；`/proc/cpuinfo`、`/proc/meminfo` 是 top/free 的数据来源 |
| `/dev/null` | 黑洞设备，写入的数据被丢弃，常用于 `command > /dev/null 2>&1` 屏蔽输出 |
| `/dev/zero` | 读取永远返回 0，配合 dd 造大文件 |
| `/var/log` | 日志在哪看：`messages`（系统日志）、`secure`（登录审计）、`dmesg`（内核日志） |
| `/tmp` 与 `/var/tmp` | 前者重启清空（tmpfs），后者持久 |

**一句话总结：**

> Linux 目录结构遵循 FHS 标准，重点记住：`/etc` 放配置、`/var/log` 放日志、`/proc` 是内核暴露的运行时信息虚拟文件系统、`/dev` 是设备文件、`/usr/local` 是手动装软件的位置。

### 1.4 一切皆文件

Linux 把进程、磁盘、终端、管道、Socket 全部抽象成文件，用统一的 `open/read/write/close` 接口操作。

**文件类型（`ls -l` 第一个字符）：**

| 字符 | 类型 | 说明 |
|------|------|------|
| `-` | 普通文件 | 文本、二进制、压缩包 |
| `d` | 目录 | 目录本质也是文件，内容是文件名到 inode 的映射 |
| `l` | 符号链接 | 软链接（快捷方式） |
| `b` | 块设备 | 磁盘 `/dev/sda`，按块读写 |
| `c` | 字符设备 | 键盘、终端、串口，按字节流读写 |
| `p` | 管道（FIFO） | `mkfifo` 创建的命名管道 |
| `s` | Socket | 网络套接字文件（如 MySQL 的 `/var/lib/mysql/mysql.sock`） |

```bash
ls -l /dev/sda        # brw-rw----  b 开头，块设备
ls -l /dev/null       # crw-rw-rw-  c 开头，字符设备
ls -l /var/lib/mysql/mysql.sock  # srwxrwxrwx  s 开头，Socket
```

---

## 二、Linux 常用命令（面试高频）

> 面试重点：命令是 Linux 面试的硬通货，考察方式通常是"给你一个场景说用什么命令"。比如"查找 3 天前的日志并删除"、"统计日志中出现次数最多的 IP"。本节按场景组织，每条命令都要能手写。

### 2.1 文件与目录操作

#### 2.1.1 ls / cd / pwd / mkdir / rm / cp / mv / ln

```bash
ls -lhtr                 # l 详细信息 h 人类可读 t 按时间排序 r 倒序（最新在最后）
ls -a                    # 显示隐藏文件（. 开头）
cd -                     # 回到上一次所在目录
mkdir -p /a/b/c          # 递归创建多级目录
rm -rf dir               # 递归强制删除（生产慎用！）
cp -r src dst            # 递归复制目录
mv old new               # 移动或重命名
ln -s /data/app/logs logs  # 创建软链接
```

**rm 安全建议（生产实战）：**

- `rm -rf` 前先 `ls` 确认路径，警惕变量为空：`rm -rf /${dir}` 当 `dir` 为空时等价于 `rm -rf /`。
- 可用 `mv` 到回收目录代替 `rm`，或用 `find ... -delete` 限定范围。

#### 2.1.2 查看文件内容

```bash
cat file                 # 全量输出，适合小文件
tac file                 # 倒序输出
head -n 100 file         # 前 100 行
tail -n 100 file         # 后 100 行
tail -f app.log          # 实时跟踪文件末尾（看日志神器）
tail -F app.log          # 类似 -f，但文件被切割（logrotate）后自动重新打开
less file                # 分页查看，/ 搜索，G 到末尾，gg 到开头
wc -l file               # 统计行数
```

**tail -f 和 tail -F 的区别（高频）：**

> `-f` 按文件描述符跟踪，如果日志被 logrotate 切割（原文件被 mv 改名），`-f` 会跟丢；`-F` 会按文件名重新打开新文件，生产环境看日志应该用 `tail -F`。

#### 2.1.3 文件查找 find（必考）

```bash
# 按名称查找
find /data -name "*.log"
find /data -iname "*.LOG"            # 忽略大小写

# 按时间查找（重点）
find /data/logs -name "*.log" -mtime +3        # 3 天前修改的文件
find /data/logs -name "*.log" -mtime -1        # 1 天内修改的文件
find /data -mmin -30                            # 30 分钟内修改的文件

# 按大小查找
find / -size +100M                              # 大于 100M 的文件（磁盘排查常用）

# 按类型与权限
find /data -type f -name "*.tmp"                # 只找普通文件
find /data -type d -name cache                  # 只找目录

# 查找后执行动作（两种等价写法）
find /data/logs -name "*.log" -mtime +3 -exec rm -f {} \;
find /data/logs -name "*.log" -mtime +3 -delete
find /data/logs -name "*.log" -mtime +3 | xargs rm -f
```

**面试回答：**

> 查找并删除 3 天前的日志：`find /data/logs -name "*.log" -mtime +3 -delete`。`-mtime +3` 表示修改时间距今超过 3 天，`{}` 是 find 找到的文件占位符，`\;` 表示命令结束。生产上建议先不加 `-delete` 跑一遍确认文件列表，再加删除动作，避免误删。

**find vs locate：**

| 对比项 | find | locate |
|--------|------|--------|
| 原理 | 实时遍历目录 | 查预建数据库（updatedb） |
| 速度 | 慢 | 极快 |
| 实时性 | 实时 | 依赖数据库更新频率，可能找不到新建文件 |

### 2.2 文本处理三剑客：grep / sed / awk（必考）

> 三剑客是 Linux 面试的重中之重，常与日志分析结合考察。这里掌握基础高频用法，复杂的日志分析实战见 [`linux生产实战面试知识点.md`](linux生产实战面试知识点.md)。

#### 2.2.1 grep：按模式过滤行

```bash
grep "ERROR" app.log                    # 查找包含 ERROR 的行
grep -v "DEBUG" app.log                 # 反向匹配：排除 DEBUG
grep -i "error" app.log                 # 忽略大小写
grep -c "ERROR" app.log                 # 统计匹配行数
grep -n "ERROR" app.log                 # 显示行号
grep -A 5 "Exception" app.log           # 匹配行及其后 5 行（After，看堆栈神器）
grep -B 5 "Exception" app.log           # 匹配行及其前 5 行（Before）
grep -C 5 "Exception" app.log           # 匹配行前后各 5 行（Context）
grep -E "ERROR|WARN" app.log            # 扩展正则，匹配多个模式
grep -r "keyword" /data/                # 递归搜索目录
grep -w "user" app.log                  # 整词匹配，不会匹配 username
tail -f app.log | grep --line-buffered "ERROR"   # 实时过滤日志
```

#### 2.2.2 sed：流编辑器（替换、删除行）

```bash
sed -n '10,20p' file                    # 打印第 10~20 行（-n 关闭默认输出，配合 p）
sed -n '/2024-01-01 10:00/,/2024-01-01 11:00/p' app.log   # 打印时间段内的日志
sed 's/foo/bar/g' file                  # 全文把 foo 替换为 bar（只输出到屏幕，不改文件）
sed -i 's/foo/bar/g' file               # -i 直接修改文件（生产先备份：-i.bak）
sed '/^$/d' file                        # 删除空行
sed '/^#/d' file                        # 删除注释行
sed '2d' file                           # 删除第 2 行
```

#### 2.2.3 awk：按列处理文本

awk 默认以**空白字符**切分列，`$1` 表示第一列，`$0` 表示整行，`NF` 列数，`NR` 行号。

```bash
awk '{print $1}' access.log                       # 打印第一列（如 IP）
awk -F':' '{print $1}' /etc/passwd                # -F 指定分隔符为冒号
awk '$9 == 404 {print $7}' access.log             # 打印状态码为 404 的 URL
awk '$3 > 1000 {print $0}' data.txt               # 第三列大于 1000 的行
awk '{sum+=$1} END {print sum}' data.txt          # 累加第一列求和
awk 'NR>=10 && NR<=20' file                       # 打印第 10~20 行
```

**经典面试题：统计日志中出现次数最多的 10 个 IP**

```bash
awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10
```

**管道执行流程：**

```text
awk '{print $1}'   取出第一列（IP）
        │
        ▼
sort               排序，让相同 IP 相邻（uniq 只对相邻行去重）
        │
        ▼
uniq -c            统计每个 IP 出现次数
        │
        ▼
sort -rn           按次数数值倒序
        │
        ▼
head -10           取前 10
```

### 2.3 系统信息查看

```bash
uname -a                 # 内核、主机名、架构
hostname                 # 主机名
uptime                   # 开机时长 + 平均负载（1/5/15 分钟）
date                     # 系统时间
cal                      # 日历
who / w                  # 当前登录用户
last                     # 最近登录记录
history                  # 历史命令（!100 执行第 100 条）
df -h                    # 磁盘分区使用情况
du -sh /data/*           # 统计目录下各项大小
free -h                  # 内存使用情况
top / htop               # 实时进程与资源占用
lscpu                    # CPU 信息
cat /proc/cpuinfo        # CPU 详细信息
cat /proc/meminfo        # 内存详细信息
```

### 2.4 压缩与解压

```bash
# tar（最常用）
tar -zcvf app.tar.gz app/          # 压缩目录（z gzip，c 创建，v 显示过程，f 指定文件名）
tar -zxvf app.tar.gz               # 解压（x 解压）
tar -zxvf app.tar.gz -C /data/     # 解压到指定目录
tar -ztvf app.tar.gz               # 不解压查看包内文件列表

# zip / unzip
zip -r app.zip app/
unzip app.zip -d /data/

# 单独 gzip（只压单文件，不打包）
gzip file        # 生成 file.gz 并删除原文件
gzip -d file.gz  # 解压
```

**记忆口诀：** 压缩用 `zcvf`（"create"），解压用 `zxvf`（"extract"）。

### 2.5 网络相关命令

```bash
# 查看端口占用（高频）
netstat -tunlp | grep 8080         # t tcp u udp n 数字端口 l 监听中 p 显示进程
ss -tunlp | grep 8080              # netstat 的现代替代品，更快

# 测试连通性
ping -c 4 www.baidu.com            # 发 4 个 ICMP 包
telnet 10.0.0.1 8080               # 测试 TCP 端口是否通
nc -zv 10.0.0.1 8080               # nc 测试端口，更轻量

# DNS 解析
nslookup www.baidu.com
dig www.baidu.com

# 抓包与下载
tcpdump -i eth0 port 8080 -nn      # 抓 8080 端口的包
curl -v http://api.example.com     # 发请求看详细过程
curl -o file.zip http://xx/file.zip  # 下载文件
wget http://xx/file.zip            # 下载

# 防火墙
iptables -L -n                     # 查看规则
firewall-cmd --list-all            # CentOS7+ firewalld
```

**netstat 常用组合参数：**

| 参数 | 含义 |
|------|------|
| `-t` | TCP |
| `-u` | UDP |
| `-n` | 数字形式显示 IP/端口（不做 DNS 反解，更快） |
| `-l` | 只看 LISTEN 状态 |
| `-p` | 显示进程名和 PID |
| `-a` | 所有连接 |

---

## 三、文件权限与用户管理（必考）

### 3.1 权限模型 rwx

`ls -l` 输出的第一列共 10 个字符：

```text
 -  rwx  r-x  ---
 │   │    │    └── 其他用户（others）：无权限
 │   │    └─────── 所属组（group）：读 + 执行
 │   └──────────── 属主（owner/user）：读 + 写 + 执行
 └──────────────── 文件类型（- 普通文件）
```

| 权限 | 字符 | 数字 | 对文件 | 对目录 |
|------|------|------|--------|--------|
| 读 | r | 4 | 可 cat 看内容 | 可 ls 列出内容 |
| 写 | w | 2 | 可修改内容 | 可在目录内创建/删除文件 |
| 执行 | x | 1 | 可作为程序运行 | 可 cd 进入 |

> 注意：目录的 `x` 是"进入权限"，只有 `r` 没有 `x` 时 ls 能看到文件名但无法访问文件本身。

### 3.2 chmod / chown / chgrp

```bash
# 数字法（推荐，面试直接说数字）
chmod 755 start.sh        # rwxr-xr-x，脚本的典型权限
chmod 644 application.yml # rw-r--r--，配置文件的典型权限
chmod 600 id_rsa          # rw-------，私钥必须 600，否则 SSH 拒绝使用
chmod -R 755 /data/app/   # -R 递归修改

# 符号法
chmod u+x start.sh        # 给属主加执行权限
chmod g-w file            # 去掉所属组写权限
chmod o=r file            # 其他用户只读

# 修改属主和属组
chown appuser:appgroup app.jar      # 同时改属主和属组
chown -R appuser /data/app/         # 递归改属主
chgrp appgroup file                 # 只改属组
```

**常见权限数字速记：**

| 数字 | 权限 | 典型用途 |
|------|------|----------|
| 777 | rwxrwxrwx | 全部放开，生产禁用 |
| 755 | rwxr-xr-x | 可执行脚本、目录 |
| 700 | rwx------ | 私有目录 |
| 644 | rw-r--r-- | 普通文件、配置文件 |
| 600 | rw------- | 私钥、敏感配置 |
| 400 | r-------- | 只读私钥 |

**面试回答：**

> Linux 文件权限分属主、属组、其他人三类，每类有读 r=4、写 w=2、执行 x=1 三种权限，组合成三位八进制数。755 表示属主可读写执行、属组和其他人只读加执行，常用于脚本和目录；644 常用于配置文件；600 用于私钥等敏感文件。目录的执行权限代表能否 cd 进入，这是很多人容易忽略的点。

### 3.3 特殊权限（SUID / SGID / Sticky Bit）

| 特殊权限 | 数字 | 作用 | 典型例子 |
|----------|------|------|----------|
| SUID | 4xxx | 执行时以文件**属主**身份运行 | `/usr/bin/passwd`（普通用户改自己密码需写 /etc/shadow） |
| SGID | 2xxx | 执行时以文件**属组**身份运行；目录下新建文件继承属组 | 团队协作共享目录 |
| Sticky | 1xxx | 目录内文件只有属主能删 | `/tmp`（drwxrwxrwt） |

```bash
chmod 4755 file          # 设置 SUID，执行位显示为 s：-rwsr-xr-x
chmod 1777 /shared       # 设置 Sticky Bit，其他人执行位显示为 t
ls -ld /tmp              # drwxrwxrwt，t 就是 Sticky Bit
ls -l /usr/bin/passwd    # -rwsr-xr-x，s 就是 SUID
```

### 3.4 用户与用户组管理

```bash
useradd -m zhangsan          # 创建用户并建家目录
useradd -r -s /sbin/nologin nginx   # 创建系统用户（不可登录，用于运行服务）
passwd zhangsan              # 设置密码
usermod -aG docker zhangsan  # 追加到 docker 组（-a 必须加，否则覆盖原组）
userdel -r zhangsan          # 删除用户及家目录
groupadd devops              # 创建组
id zhangsan                  # 查看用户的 uid、gid、所属组
su - zhangsan                # 切换用户（- 表示加载目标用户环境变量）
sudo command                 # 以 root 权限执行单条命令
```

**关键配置文件：**

| 文件 | 内容 |
|------|------|
| `/etc/passwd` | 用户信息（用户名:x:UID:GID:描述:家目录:Shell） |
| `/etc/shadow` | 加密后的密码，仅 root 可读 |
| `/etc/group` | 用户组信息 |
| `/etc/sudoers` | sudo 权限配置（必须用 `visudo` 编辑，有语法检查） |

### 3.5 sudo 原理与配置

sudo 允许普通用户以 root（或其他用户）身份执行命令，执行记录会记入日志，比直接使用 root 更安全、可审计。

```bash
# /etc/sudoers 中的典型配置（用 visudo 编辑）
zhangsan ALL=(ALL) ALL                        # zhangsan 可用 sudo 执行所有命令（需输密码）
%devops  ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart nginx   # devops 组免密重启 nginx
```

---

## 四、进程管理

### 4.1 查看进程 ps / top

```bash
# ps 两种风格（都常用）
ps -ef | grep java          # 标准风格：UID PID PPID ... CMD
ps aux | grep java          # BSD 风格：多了 %CPU %MEM RSS 等

# top 交互快捷键（面试常问）
top
  P      # 按 CPU 排序
  M      # 按内存排序
  1      # 展开每个 CPU 核的使用率
  k      # kill 进程
  c      # 显示完整命令行
  q      # 退出
```

**ps aux 输出列含义：**

| 列 | 含义 |
|----|------|
| USER | 进程属主 |
| PID | 进程 ID |
| %CPU | CPU 使用率 |
| %MEM | 内存使用率 |
| VSZ | 虚拟内存大小（KB），包含未实际使用的地址空间 |
| RSS | 常驻内存（KB），**实际占用**的物理内存，排查内存看这个 |
| TTY | 终端 |
| STAT | 进程状态（R 运行、S 睡眠、D 不可中断、Z 僵尸、T 停止） |
| START | 启动时间 |
| TIME | 累计占用 CPU 时间 |
| COMMAND | 命令 |

### 4.2 kill 与信号

```bash
kill -l                    # 查看所有信号
kill PID                   # 默认发 SIGTERM(15)，请求进程优雅退出
kill -9 PID                # 发 SIGKILL(9)，强制杀死，进程无法捕获和忽略
kill -HUP PID              # 发 SIGHUP(1)，常用于让进程重载配置（如 nginx -s reload 底层）
killall java               # 按进程名杀死所有匹配进程
pkill -f "app.jar"         # 按命令行模糊匹配杀死
```

**常见信号：**

| 信号 | 编号 | 默认动作 | 说明 |
|------|------|----------|------|
| SIGTERM | 15 | 终止 | 礼貌请求退出，进程可捕获做清理（推荐） |
| SIGKILL | 9 | 终止 | 内核强制杀死，不可捕获、不可忽略，清理动作不会执行 |
| SIGINT | 2 | 终止 | Ctrl+C 发出 |
| SIGHUP | 1 | 终止 | 终端挂断，常被 daemon 用来触发重载配置 |
| SIGSTOP | 19 | 暂停 | 暂停进程（Ctrl+Z 是 SIGTSTP） |
| SIGCHLD | 17 | 忽略 | 子进程退出时通知父进程 |

**面试回答：**

> kill 默认发送 SIGTERM(15)，进程收到后可以捕获并做资源清理、关闭连接等优雅退出动作；kill -9 发送 SIGKILL，由内核直接终止进程，进程没有任何处理机会，可能导致数据没落盘、文件句柄残留。所以生产上应该先 kill，等待几秒，确认进程不退再用 kill -9。

### 4.3 前台 / 后台 / 守护

```bash
command &                  # 后台运行，但终端关闭进程会收到 SIGHUP 而退出
jobs                       # 查看当前终端的后台任务
fg %1                      # 把 1 号任务调回前台
bg %1                      # 让 1 号任务在后台继续运行
Ctrl+Z                     # 把前台任务暂停并放入后台

nohup command > app.log 2>&1 &   # 忽略 SIGHUP，终端关闭也不退出，输出重定向到文件
```

**面试高频：`command &` 和 `nohup command &` 的区别？**

> 两者都放到后台，但 `&` 启动的进程仍是当前终端会话的 job，终端退出时内核会发 SIGHUP 导致进程退出；`nohup` 让进程忽略 SIGHUP 信号，终端退出后进程继续运行。生产上部署 Java 服务常用 `nohup java -jar app.jar > app.log 2>&1 &`，更规范的做法是用 systemd 托管。

### 4.4 定时任务 crontab

```bash
crontab -e                 # 编辑当前用户的定时任务
crontab -l                 # 查看任务列表
```

**时间格式（五段）：**

```text
┌───────────── 分钟 (0-59)
│ ┌───────────── 小时 (0-23)
│ │ ┌───────────── 日 (1-31)
│ │ │ ┌───────────── 月 (1-12)
│ │ │ │ ┌───────────── 星期 (0-7，0 和 7 都是周日)
│ │ │ │ │
* * * * *  要执行的命令
```

**常见写法：**

```bash
*/5 * * * *        # 每 5 分钟
0 2 * * *          # 每天凌晨 2 点
0 3 * * 0          # 每周日凌晨 3 点
30 4 1 * *         # 每月 1 号凌晨 4:30
*/10 9-18 * * 1-5  # 工作日 9~18 点每 10 分钟
0 0 1,15 * *       # 每月 1 号和 15 号 0 点
```

**生产注意：**

- crontab 环境变量极简（PATH 只有 `/usr/bin:/bin`），脚本里**必须写绝对路径**或在脚本开头 export PATH。
- 输出建议重定向到日志文件：`*/5 * * * * /data/job.sh >> /data/logs/job.log 2>&1`。
- 排查 cron 没执行：看 `/var/log/cron`（CentOS）或 `/var/log/syslog`（Ubuntu）。

---

## 五、重定向、管道与环境变量

### 5.1 重定向（必考）

Linux 每个进程默认打开三个文件描述符：

| 文件描述符 | 名称 | 说明 |
|------------|------|------|
| 0 | stdin | 标准输入 |
| 1 | stdout | 标准输出 |
| 2 | stderr | 标准错误 |

```bash
command > out.log          # 标准输出覆盖写入（等价于 1>）
command >> out.log         # 标准输出追加写入
command 2> err.log         # 标准错误覆盖写入
command > all.log 2>&1     # 标准输出和标准错误都写入同一文件（高频）
command &> all.log         # 同上，bash 简写
command < input.txt        # 从文件读取标准输入
command > /dev/null 2>&1   # 丢弃所有输出
command << EOF             # Here Document，读取直到 EOF 的内容
...
EOF
```

**高频考点：`2>&1` 为什么要写在 `>` 后面？**

> `command > out.log 2>&1` 的顺序含义是：先把 stdout 重定向到 out.log，再把 stderr 重定向到"当前 stdout 指向的地方"（即 out.log）。如果写成 `command 2>&1 > out.log`，stderr 会先指向当时的 stdout（终端），之后 stdout 改向文件，但 stderr 仍留在终端，错误信息就不会进文件。顺序很重要。

### 5.2 管道 |

管道把前一个命令的 stdout 作为后一个命令的 stdin，是 Linux 组合小工具完成复杂任务的核心机制。

```bash
ps aux | grep java | grep -v grep | awk '{print $2}' | xargs kill -9
```

**xargs 的作用：** 把管道传来的标准输入转成命令行参数。没有 xargs，`kill -9` 拿不到 PID 列表。

### 5.3 环境变量

```bash
echo $PATH                 # 查看 PATH
export JAVA_HOME=/usr/local/jdk        # 设置变量（仅当前 Shell 会话有效）
env                        # 查看所有环境变量
```

**让变量永久生效的配置文件及加载顺序：**

| 文件 | 作用范围 | 加载时机 |
|------|----------|----------|
| `/etc/profile` | 全局 | 登录 Shell 时加载 |
| `/etc/bashrc` | 全局 | 打开 bash 时加载 |
| `~/.bash_profile` | 当前用户 | 登录时加载（内部通常再调 `~/.bashrc`） |
| `~/.bashrc` | 当前用户 | 每次打开 bash 时加载 |

修改后用 `source ~/.bashrc` 或 `. ~/.bashrc` 立即生效，不用重新登录。

---

## 六、软件包管理

### 6.1 yum（CentOS/RHEL）与 apt（Ubuntu/Debian）

| 操作 | yum（CentOS） | apt（Ubuntu） |
|------|---------------|---------------|
| 安装 | `yum install -y nginx` | `apt install -y nginx` |
| 卸载 | `yum remove nginx` | `apt remove nginx` |
| 更新索引 | `yum makecache` | `apt update` |
| 搜索 | `yum search nginx` | `apt search nginx` |
| 查看信息 | `yum info nginx` | `apt show nginx` |
| 查文件属于哪个包 | `yum provides */ifconfig` | `apt-file search ifconfig` |
| 列出包安装的文件 | `rpm -ql nginx` | `dpkg -L nginx` |

### 6.2 rpm / dpkg 底层命令

```bash
rpm -ivh pkg.rpm       # 安装本地 rpm 包（i 安装 v 详细 h 进度条）
rpm -qa | grep nginx   # 查询已安装的包
rpm -ql nginx          # 列出包安装的所有文件
rpm -e nginx           # 卸载
```

> yum/apt 是在 rpm/dpkg 之上解决**依赖关系**的高级工具，面试问区别就答：rpm 只处理单个包，依赖需手动解决；yum 自动从仓库解析并安装依赖。

---

## 七、服务管理 systemd

CentOS 7+ / Ubuntu 16+ 使用 systemd 管理服务，取代旧的 `service` / `chkconfig`。

```bash
systemctl start nginx          # 启动
systemctl stop nginx           # 停止
systemctl restart nginx        # 重启
systemctl reload nginx         # 热加载配置（不断连接）
systemctl status nginx         # 查看状态
systemctl enable nginx         # 开机自启
systemctl disable nginx        # 取消自启
systemctl is-enabled nginx     # 查看是否自启
systemctl daemon-reload        # 修改 unit 文件后重新加载
journalctl -u nginx            # 查看服务日志
journalctl -u nginx -f         # 实时跟踪
journalctl --since "10 min ago" -u nginx   # 最近 10 分钟
```

**自定义服务的 unit 文件示例 `/etc/systemd/system/myapp.service`：**

```ini
[Unit]
Description=My Java App
After=network.target

[Service]
User=appuser
ExecStart=/usr/local/jdk/bin/java -jar /data/app/app.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**要点：** `Restart=on-failure` 进程挂了自动拉起，比 nohup 可靠得多；改完 unit 文件必须 `systemctl daemon-reload`。

---

## 八、SSH 与远程操作

```bash
ssh user@10.0.0.1                    # 登录
ssh -p 2222 user@10.0.0.1            # 指定端口
ssh-keygen -t rsa -b 4096            # 生成密钥对（~/.ssh/id_rsa 私钥、id_rsa.pub 公钥）
ssh-copy-id user@10.0.0.1            # 把公钥推送到目标机实现免密登录
scp local.txt user@10.0.0.1:/data/   # 本地传远程
scp user@10.0.0.1:/data/app.log .    # 远程拉本地
scp -r dir user@10.0.0.1:/data/      # 传目录
rsync -avz /data/ user@10.0.0.1:/backup/  # 增量同步（只传差异，比 scp 适合备份）
```

**SSH 免密登录原理：**

> 本地 `ssh-keygen` 生成密钥对，把公钥追加到目标机的 `~/.ssh/authorized_keys`。登录时目标机用公钥加密一段随机串发给客户端，客户端用私钥解密回传，验证通过即登录。私钥必须 600 权限，否则 SSH 出于安全拒绝使用。

**rsync vs scp：**

| 对比项 | scp | rsync |
|--------|-----|-------|
| 传输方式 | 全量复制 | 增量（校验差异只传变化部分） |
| 速度（已有部分文件） | 慢 | 快 |
| 适用 | 首次传输小文件 | 大目录同步、定期备份 |

---

## 九、其他高频小命令

```bash
which java               # 查命令在 PATH 中的位置
whereis java             # 查命令、源码、man 位置
type cd                  # 查命令是内建还是外部
alias ll='ls -l'         # 设置别名
ln -s /data/app/logs logs  # 软链接
md5sum file              # 计算 MD5（校验文件完整性）
diff file1 file2         # 对比文件差异
sort file | uniq         # 去重（sort 使重复行相邻）
split -l 100000 big.log  # 按行切分大文件
shuf -n 10 file          # 随机抽 10 行
tr 'a-z' 'A-Z' < file    # 字符替换（小写转大写）
cut -d':' -f1 /etc/passwd  # 按分隔符取列（类似 awk -F）
chattr +i file           # 加不可变属性，root 也删不掉（防误删/安全加固）
lsattr file              # 查看特殊属性
watch -n 1 'ss -s'       # 每 1 秒重复执行命令并全屏显示
```

---

## 十、本章面试速记卡

1. **一切皆文件**：进程、设备、管道、Socket 统一抽象为文件，文件类型 7 种（`-d l b c p s`）。
2. **tail -f vs -F**：`-F` 能感知日志切割重新打开，生产看日志用 `-F`。
3. **find 删 3 天前日志**：`find /data/logs -name "*.log" -mtime +3 -delete`。
4. **统计 Top10 IP**：`awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10`。
5. **权限数字**：r=4 w=2 x=1；脚本 755、配置 644、私钥 600；目录 x 权限表示可进入。
6. **kill 与 kill -9**：先 TERM 优雅退出，不行再 KILL 强杀。
7. **nohup + &**：忽略 SIGHUP 且后台运行，终端退出进程不死。
8. **crontab 五段**：分时日月周；环境变量少，脚本用绝对路径。
9. **重定向顺序**：`> all.log 2>&1`，`2>&1` 必须在 stdout 重定向之后。
10. **systemd**：`systemctl enable --now nginx` 启动并开机自启；改 unit 文件后 `daemon-reload`。
