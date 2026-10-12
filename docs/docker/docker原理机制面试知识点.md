# Docker 原理机制面试知识点

> 本文是 Docker 面试的**原理深入篇**。总览与命令请看 [`docker面试知识点.md`](docker面试知识点.md)；生产实战请看 [`docker生产实战面试知识点.md`](docker生产实战面试知识点.md)。

## 一、Docker 的三大底层技术

Docker 之所以能实现"轻量级虚拟化"，本质上是**站在 Linux 内核的肩膀上**，核心依赖三大技术：

| 技术 | 解决的问题 | 类比 |
|------|-----------|------|
| **Namespace** | 资源**视图**隔离（进程、网络、文件系统等） | "看到的世界不同" |
| **Cgroups** | 资源**用量**限制（CPU、内存、IO） | "能吃多少饭有配额" |
| **UnionFS** | 镜像**分层**与容器读写层 | "透明叠加多层文件系统" |

一句话概括：**Docker = Namespace（隔离视图）+ Cgroups（限制资源）+ UnionFS（镜像分层）+ 容器运行时（管理生命周期）**。

---

## 二、Namespace：资源视图隔离

### 2.1 什么是 Namespace？

Linux Namespace 是 Linux 内核提供的一种资源**隔离机制**，可以让不同的进程看到不同的"系统视图"。同一个宿主机上的不同 Namespace 中的进程，感受到的进程列表、网络设备、挂载点、主机名等都是独立的。

### 2.2 Linux 支持的 6 类 Namespace（Docker 使用）

| Namespace | 缩写 | 隔离内容 | 常见问题 |
|-----------|------|----------|----------|
| **PID** | pid | 进程 ID | 容器内 PID 1 就是主进程 |
| **Network** | net | 网络设备、IP、端口、路由 | 容器有独立的网卡和 IP |
| **Mount** | mnt | 挂载点、文件系统 | 容器有独立的文件系统视图 |
| **UTS** | uts | Hostname、域名 | 容器可以有独立的 hostname |
| **IPC** | ipc | 信号量、消息队列、共享内存 | 进程间通信隔离 |
| **User** | user | 用户和用户组 UID/GID | 容器内 root ≠ 宿主机 root |

后来 Linux 又新增了 `Cgroup Namespace` 和 `Time Namespace`。

### 2.3 Namespace 的三个核心系统调用

Linux 内核提供了三个系统调用来操作 Namespace：

| 系统调用 | 作用 |
|----------|------|
| `clone()` | 创建新进程时，指定要新建的 Namespace |
| `unshare()` | 让当前进程脱离原 Namespace，进入新 Namespace |
| `setns()` | 让当前进程加入已存在的 Namespace |

例如 `docker exec -it <id> bash` 底层就是通过 `setns()` 让 bash 进程加入到目标容器的所有 Namespace 中。

### 2.4 用命令验证 Namespace 隔离

```bash
# 启动一个容器
docker run -d --name test nginx

# 查看容器主进程 PID（在宿主机视角）
docker inspect -f '{{.State.Pid}}' test
# 输出：12345

# 查看该进程的 Namespace
ls -l /proc/12345/ns/
# 输出示例：
# lrwxrwxrwx 1 root root 0 ipc -> ipc:[4026532198]
# lrwxrwxrwx 1 root root 0 mnt -> mnt:[4026532196]
# lrwxrwxrwx 1 root root 0 net -> net:[4026532201]
# lrwxrwxrwx 1 root root 0 pid -> pid:[4026532199]
# lrwxrwxrwx 1 root root 0 uts -> uts:[4026532197]

# 对比宿主机自身进程的 Namespace
ls -l /proc/1/ns/
# 会看到完全不同的 ID
```

**面试要点：**

> `/proc/<pid>/ns/` 下的每个 Namespace 都用一个 inode 编号唯一标识。两个进程如果对应 Namespace 的 inode 相同，就说明它们共享该 Namespace。这也是 K8s Pod 中多个容器"共享网络"的实现原理——它们的 net namespace inode 相同。

### 2.5 PID Namespace 详解

PID Namespace 让容器内看到的**进程 PID 从 1 开始重新编号**。

```text
宿主机视角                      容器内视角
PID 1: /sbin/init
PID 2: kthreadd
...
PID 12345: java -jar app.jar   <-->  PID 1: java -jar app.jar
PID 12346: worker-thread       <-->  PID 2: worker-thread
```

**关键结论：**

- 容器内 PID 1 就是**你在 Dockerfile 中的 ENTRYPOINT/CMD**
- PID 1 是特殊进程：如果它退出，整个容器就会退出
- PID 1 需要正确处理 SIGTERM 信号（否则 `docker stop` 会等 10s 后 SIGKILL）
- PID 1 需要负责回收僵尸子进程（zombie process）

**面试常问：为什么容器需要 tini 或 dumb-init 这类 init 系统？**

答：因为 PID 1 有特殊责任——回收孤儿僵尸进程、正确转发信号。普通应用（如 Java、Python）作为 PID 1 时，可能不处理这些，导致：

1. 僵尸进程堆积
2. `docker stop` 时收不到 SIGTERM，最终被 SIGKILL 强杀
3. 子进程孤儿后无法回收

解决方案是用一个轻量 init 系统作为 PID 1，如 `tini`、`dumb-init`，它们会正确处理信号和僵尸进程。Docker 也提供了 `--init` 参数：

```bash
docker run --init myapp
```

---

## 三、Cgroups：资源用量限制

### 3.1 什么是 Cgroups？

Cgroups（Control Groups）是 Linux 内核提供的**资源限制、审计、优先级控制**机制。它可以限制、记录、隔离进程组的物理资源使用（CPU、内存、磁盘 IO 等）。

**核心能力：**

| 能力 | 说明 |
|------|------|
| Resource Limitation | 限制资源使用上限（CPU 时间、内存大小） |
| Prioritization | 分配优先级（CPU 权重、IO 权重） |
| Accounting | 统计资源使用量 |
| Control | 冻结、恢复进程组 |

### 3.2 Cgroups 常见子系统

| 子系统 | 作用 |
|--------|------|
| `cpu` | 限制 CPU 使用率 |
| `cpuset` | 绑定进程到指定 CPU 核 |
| `memory` | 限制内存使用，超出触发 OOM |
| `blkio` | 限制块设备 IO |
| `net_cls` | 网络包分类和限速 |
| `pids` | 限制进程数量 |
| `devices` | 控制设备访问权限 |
| `freezer` | 冻结/恢复进程 |

### 3.3 Cgroups 在 Docker 中的应用

```bash
# 限制 CPU：最多使用 2 个 CPU 核
docker run --cpus=2 nginx

# 限制 CPU 权重（相对权重，默认 1024）
docker run --cpu-shares=512 nginx

# 绑定到指定 CPU 核
docker run --cpuset-cpus="0,1" nginx

# 限制内存：最多 512MB
docker run -m 512m nginx

# 内存 + 交换分区总量
docker run -m 512m --memory-swap=1g nginx

# 限制 PID 数量：防止 fork 炸弹
docker run --pids-limit=100 nginx

# 限制块设备 IOPS
docker run --device-read-iops /dev/sda:1000 nginx
```

### 3.4 查看 Cgroups 配置

```bash
# 找到容器 ID
docker ps

# Cgroups v1（旧版）
cat /sys/fs/cgroup/memory/docker/<container_id>/memory.limit_in_bytes
cat /sys/fs/cgroup/cpu/docker/<container_id>/cpu.cfs_quota_us

# Cgroups v2（新版，统一层次）
cat /sys/fs/cgroup/system.slice/docker-<container_id>.scope/memory.max
```

### 3.5 内存限制的 OOM Killer

当容器内存超过 `-m` 限制时，Linux 内核的 **OOM Killer** 会介入，杀死容器内进程。

```bash
# 查看容器是否被 OOM 杀死
docker inspect <id> | grep -i oom
# "OOMKilled": true

# 退出码 137 = 128 + 9 (SIGKILL)
```

**生产陷阱：Java 应用在容器中的内存问题**

Java 8u131 之前的 JVM **不识别 Cgroups 内存限制**，会读取宿主机内存计算堆大小，导致 Java 进程实际使用的内存远超容器限制，触发 OOMKilled。

**解决方案：**

- 升级到 Java 8u191+ / 10+，JVM 自动识别 Cgroups
- 或显式指定：`-Xmx=<内存限制的 70%>`
- 老版本用：`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`

### 3.6 面试题：Docker 是怎么限制 CPU 的？

答：Docker 通过 Cgroups 的 cpu 子系统限制 CPU，主要有三种方式：

1. **`--cpus=2`**：设置 CPU 使用上限（绝对值），等价于设置 `cpu.cfs_quota_us` 和 `cpu.cfs_period_us`，即每 100ms 周期内最多使用 200ms CPU 时间
2. **`--cpu-shares=512`**：设置**相对权重**（默认 1024），仅在 CPU 争抢时生效，空闲时不限制
3. **`--cpuset-cpus="0,1"`**：**绑定**到指定 CPU 核（cpuset 子系统）

生产中通常混用 `--cpus`（防止个别容器吃满）和 `--cpu-shares`（保证核心业务优先）。

---

## 四、UnionFS：镜像分层的秘密

### 4.1 什么是联合文件系统（UnionFS）？

UnionFS 是一种**分层的、轻量级的、高性能的文件系统**，它可以把多个文件系统**透明地叠加**在一起，形成一个统一的文件系统视图。

**核心特性：**

- 分层：多层文件系统上下叠加
- 只读 + 可读写：底层只读，顶层可读写
- Copy-on-Write（写时复制）：修改文件时才复制到读写层

### 4.2 Docker 中的分层示意

```text
容器视角看到的文件系统：
+----------------------------------+
|  Container Layer (可读写)         |  <- 容器运行时的修改都在这里
+----------------------------------+
|  Image Layer 3: COPY app.jar     |  <- 只读
+----------------------------------+
|  Image Layer 2: RUN apt install  |  <- 只读
+----------------------------------+
|  Image Layer 1: FROM openjdk:17  |  <- 只读
+----------------------------------+
             ↓
       UnionFS 合并
             ↓
       容器看到的完整文件系统
```

**核心原理：**

- 镜像的所有层都是**只读**的
- 启动容器时，Docker 在最上层加一个**可读写层**
- 容器内所有修改都发生在这个可读写层
- 容器删除时，可读写层也会被删除，但镜像层保留

### 4.3 Copy-on-Write（COW）机制

当容器内**修改**一个来自镜像层的文件时：

1. 从只读层**复制**文件到可读写层
2. 在可读写层修改
3. 上层遮盖下层，读取时看到修改后的版本

```text
修改前：
+-----------------+
| Writable Layer  |  (空)
+-----------------+
| Image Layer     |  /etc/nginx/nginx.conf (原始)
+-----------------+

容器修改 /etc/nginx/nginx.conf：
+-----------------+
| Writable Layer  |  /etc/nginx/nginx.conf (修改后)  ← 遮盖下层
+-----------------+
| Image Layer     |  /etc/nginx/nginx.conf (原始)   ← 不变
+-----------------+
```

**优点：**

- **共享**：多个容器可以共享同一个镜像层，节省磁盘
- **快速启动**：不需要复制整个文件系统
- **增量分发**：镜像 pull 时只需下载不存在的层

**缺点：**

- 修改大文件时会触发整个文件复制，性能损耗大
- 频繁写数据的场景（如数据库）应该用 Volume，而不是写在容器读写层

### 4.4 Docker 常见的存储驱动

Docker 支持多种 UnionFS 实现，通过**存储驱动（Storage Driver）**对接：

| 驱动 | 说明 | 现状 |
|------|------|------|
| **overlay2** | 基于 OverlayFS，性能好，稳定 | **当前默认，官方推荐** |
| aufs | 早期默认，Ubuntu 支持好 | 已过时，被 overlay2 取代 |
| devicemapper | 基于块设备的快照 | CentOS 早期使用，已不推荐 |
| btrfs | Btrfs 文件系统的原生快照 | 特殊场景 |
| zfs | ZFS 文件系统的原生快照 | 特殊场景 |
| vfs | 无 CoW 的原始实现 | 仅测试用 |

查看当前驱动：

```bash
docker info | grep "Storage Driver"
# Storage Driver: overlay2
```

### 4.5 overlay2 详解

overlay2 是当前最常用的存储驱动，它有 **4 个核心目录**：

| 目录 | 说明 |
|------|------|
| **lowerdir** | 只读层（镜像层） |
| **upperdir** | 可读写层（容器层） |
| **merged** | 合并后的视图（容器看到的） |
| **workdir** | overlay 内部工作目录 |

```text
lowerdir（多层只读镜像）
   ↓
   +
   ↓
upperdir（可写容器层）
   ↓
merged（容器看到的完整文件系统）
```

查看容器的 overlay2 目录：

```bash
docker inspect <id> | grep -A 10 GraphDriver
# 输出：
# "GraphDriver": {
#   "Data": {
#     "LowerDir": "/var/lib/docker/overlay2/xxx/diff:...",
#     "UpperDir": "/var/lib/docker/overlay2/yyy/diff",
#     "WorkDir":  "/var/lib/docker/overlay2/yyy/work",
#     "MergedDir": "/var/lib/docker/overlay2/yyy/merged"
#   }
# }
```

### 4.6 面试题：镜像分层是怎么共享的？

答：Docker 镜像通过内容寻址（Content-Addressable）实现分层共享。每一层用其内容的 SHA256 哈希作为唯一标识，只要两个镜像的某一层内容完全相同，就共享同一份物理数据。

举例：假设有两个镜像都基于 `openjdk:17`，那么：

- `openjdk:17` 这一层只在磁盘上存一份
- 拉取新镜像时，Docker 会检查每一层的哈希，已存在的层直接跳过
- 存储上共享，运行时通过 UnionFS 各自看到独立的文件系统

这就是为什么第一次拉镜像慢，之后拉相似镜像很快的原因。

### 4.7 面试题：容器可写层的数据会持久化吗？

答：**不会**。容器可写层（upperdir）的生命周期跟随容器，容器删除后可写层也一起删除。如果需要持久化数据，必须使用 Volume 或 Bind Mount 挂载到容器外部。

同时，写在可写层的数据由于 COW 机制，性能不如原生文件系统，且不能在多个容器间共享。因此：

- 数据库数据、上传文件、日志 → 用 Volume
- 只需要读的配置文件 → 打进镜像
- 临时数据 → 用 tmpfs 挂载

---

## 五、Docker 网络原理

### 5.1 四种网络模式深入解析

#### 5.1.1 bridge 模式（默认）

Docker 启动时会创建一个**虚拟网桥 `docker0`**，容器通过 **veth pair**（一对虚拟网卡）接入网桥。

```text
                    宿主机
+---------------------------------------+
|                                       |
|  eth0 (宿主机物理网卡)                 |
|    │                                  |
|    │  iptables NAT                    |
|    │                                  |
|  docker0 (虚拟网桥) 172.17.0.1        |
|    ├── veth1 ──┐                      |
|    └── veth2 ──┼──┐                   |
|                │  │                   |
|            +---+--+--+                |
|            | ns1  ns2 |               |
|            |  容器1  容器2            |
|            |  eth0    eth0            |
|            | 172.17.  172.17.         |
|            |  0.2      0.3            |
|            +---------+                |
+---------------------------------------+
```

**关键点：**

- `docker0` 是一个虚拟网桥（Linux Bridge），作用类似交换机
- 每个容器有一对 veth：容器内看到 eth0，宿主机看到 vethXXX
- 容器 IP 是 `docker0` 子网内分配的（默认 `172.17.0.0/16`）
- 容器访问外网通过 iptables NAT 做源地址转换（SNAT）
- 外部访问容器通过 `-p` 端口映射（iptables DNAT）

**查看：**

```bash
# 查看网桥
brctl show
# bridge name   bridge id       STP   interfaces
# docker0       8000.024...     no    vethXXX vethYYY

# 查看 iptables NAT 规则
iptables -t nat -L -n

# 容器内查看网络
docker exec <id> ip addr
```

#### 5.1.2 host 模式

容器**共享宿主机的网络命名空间**，没有独立 IP，直接使用宿主机的网卡和端口。

```bash
docker run --network=host nginx
# 容器内的 nginx 直接监听宿主机的 80 端口
# 不需要 -p 端口映射
```

**优缺点：**

| 优点 | 缺点 |
|------|------|
| 性能好（没有 NAT 开销） | 端口冲突（容器和宿主机共享端口空间） |
| 不需要端口映射 | 隔离性差 |

**适用场景：** 网络密集型应用（如高性能网关）、需要监听宿主机所有网卡时。

#### 5.1.3 none 模式

容器**只有一个 lo 回环接口**，没有其他网络。

```bash
docker run --network=none nginx
# 容器内只有 127.0.0.1，无法通信
```

**适用场景：** 完全隔离的计算任务、需要用户自己配置网络的特殊场景。

#### 5.1.4 container 模式

新容器**共享**已存在容器的 network namespace，两个容器共用同一个 IP 和端口空间。

```bash
docker run -d --name web nginx
docker run --network=container:web busybox
# 第二个容器与 web 共享网络，可以通过 localhost 访问 nginx
```

**这就是 Kubernetes Pod 的实现原理**——一个 Pod 内有一个 `pause` 容器持有 network namespace，其他业务容器都以 `container` 模式加入，共享 IP。

### 5.2 容器网络的关键技术：veth pair、Bridge、iptables

Docker 单机网络的核心是**三件套**：

| 技术 | 作用 |
|------|------|
| **veth pair** | 一对虚拟网卡，两端连通，一端在容器内、一端在宿主机 |
| **Linux Bridge** | 虚拟网桥（docker0），负责容器之间的二层转发 |
| **iptables** | NAT 转换、端口映射、防火墙规则 |

**容器访问外网的完整链路：**

```text
容器内进程 → 容器 eth0 → veth pair → 宿主机 vethXXX 
        → docker0 网桥 → iptables SNAT（源 IP 改为宿主机 IP） 
        → 宿主机 eth0 → 外网
```

**外部访问容器（-p 8080:80）：**

```text
外部请求宿主机 8080 端口 → iptables DNAT（目的地址改为容器 IP:80）
        → docker0 → veth pair → 容器 eth0 → 容器内 nginx
```

### 5.3 面试题：Docker 是怎么做端口映射的？

答：`docker run -p 8080:80` 底层是通过 **iptables DNAT** 实现的。Docker 会在 iptables 的 `nat` 表中插入 DNAT 规则：

```bash
iptables -t nat -A DOCKER -p tcp --dport 8080 -j DNAT --to-destination 172.17.0.2:80
```

外部请求到达宿主机 8080 端口后，iptables 会把目的地址改写为容器 IP:80，然后转发进 docker0 网桥，最后送到容器内。

同时会有一条 SNAT 规则处理返回流量。

### 5.4 面试题：跨主机容器怎么通信？

答：单机 bridge 网络无法跨主机。跨主机方案：

| 方案 | 原理 |
|------|------|
| **overlay 网络**（Docker Swarm/K8s） | VXLAN 封包，通过 UDP 隧道跨主机 |
| **macvlan** | 给容器分配宿主机同网段真实 MAC |
| **CNI 插件**（K8s 常用） | Flannel、Calico、Cilium 等 |
| **host 网络 + 端口映射** | 简单粗暴，但容易端口冲突 |

生产 K8s 环境几乎都是通过 CNI 插件解决：

- **Flannel**：VXLAN 封包，简单
- **Calico**：BGP 路由，性能好，支持 NetworkPolicy
- **Cilium**：基于 eBPF，性能极致

---

## 六、Docker 容器运行时：containerd 和 runc

### 6.1 容器运行时的分层架构

新版 Docker 采用**分层的容器运行时架构**，符合 OCI（Open Container Initiative）标准：

```text
+--------------------------------------+
|         docker CLI / API             |
+--------------------------------------+
                 │
                 ▼
+--------------------------------------+
|         dockerd (Docker Daemon)      |  高层：镜像管理、网络、卷
+--------------------------------------+
                 │
                 ▼
+--------------------------------------+
|         containerd                   |  中层：容器生命周期管理
+--------------------------------------+
                 │
                 ▼
+--------------------------------------+
|         containerd-shim              |  为每个容器创建的 shim
+--------------------------------------+
                 │
                 ▼
+--------------------------------------+
|         runc (OCI Runtime)           |  底层：真正调用内核创建容器
+--------------------------------------+
                 │
                 ▼
      Linux Kernel（Namespace/Cgroups）
```

### 6.2 各组件职责

| 组件 | 职责 |
|------|------|
| **dockerd** | Docker 守护进程，处理 API、构建镜像、管理网络和卷 |
| **containerd** | 容器生命周期管理（拉镜像、启停、监控）、支持插件 |
| **containerd-shim** | 为每个容器创建一个 shim 进程，隔离容器与 containerd/dockerd 生命周期 |
| **runc** | OCI 标准实现，调用 Linux 内核 API 创建容器 |

### 6.3 为什么要有 containerd-shim？

**核心作用：解耦容器和 dockerd/containerd 的生命周期。**

如果没有 shim，dockerd 崩溃时会连带杀死所有容器。有了 shim 后：

- 每个容器由自己的 shim 进程守护
- containerd 或 dockerd 重启时，容器**继续运行**
- shim 负责收集容器退出状态，转交给 containerd

### 6.4 Kubernetes 为什么要移除 dockershim？

在旧版 K8s 中，kubelet 通过 **dockershim** 适配层与 Docker 通信：

```text
kubelet → dockershim → dockerd → containerd → runc
```

链路太长，性能损耗大。K8s 1.24 起移除 dockershim，直接对接 containerd：

```text
kubelet → CRI → containerd → runc
```

**注意：** 这不影响 Docker 构建的镜像在 K8s 运行，因为镜像格式是 OCI 标准，与运行时无关。

### 6.5 面试题：runc 是怎么创建容器的？

答：runc 是 OCI 标准的参考实现，根据 OCI Runtime Spec 创建容器。核心步骤：

1. 读取 config.json（容器配置）
2. 调用 `clone()` 系统调用，创建新进程并指定新的 Namespace（PID、Net、Mount、UTS、IPC 等）
3. 通过 Cgroups 设置资源限制
4. 挂载 rootfs（联合文件系统）
5. 执行 pivot_root 切换根文件系统
6. 应用 Capabilities、SELinux/AppArmor 等安全策略
7. 通过 `execve()` 执行用户指定的 ENTRYPOINT/CMD

整个过程调用 Linux 内核已有能力，runc 本身很轻量，只有几 MB。

---

## 七、Docker 镜像原理深入

### 7.1 镜像的本质：一堆分层的 tar 包 + 元数据

Docker 镜像不是一个单一的文件，而是**一系列层 + JSON 元数据的集合**。

镜像格式（OCI Image Spec）核心组成：

| 组成 | 说明 |
|------|------|
| **Manifest** | 镜像清单，列出所有层的哈希 |
| **Config** | 镜像配置：环境变量、CMD、ENTRYPOINT、构建历史等 |
| **Layers** | 每一层是一个 tar 压缩包（gzip） |

### 7.2 镜像的存储结构

```text
/var/lib/docker/
├── image/
│   └── overlay2/
│       ├── imagedb/          # 镜像元数据（config、manifest）
│       │   └── content/sha256/xxx
│       └── layerdb/          # 层元数据
│           └── sha256/xxx
└── overlay2/                 # 实际的镜像层内容
    ├── layer-hash-1/diff/    # 层的文件系统内容
    ├── layer-hash-2/diff/
    └── ...
```

### 7.3 镜像 ID vs 层 ID

新手容易混淆：

| 概念 | 说明 |
|------|------|
| **Image ID** | 镜像 config 文件的 SHA256 |
| **Layer ID** | 每一层文件系统内容的 SHA256 |
| **Diff ID** | 未压缩层内容的哈希 |
| **Chain ID** | 层链的累积哈希（用于快速比较） |

### 7.4 面试题：`docker pull nginx` 内部发生了什么？

答：完整流程：

1. **解析镜像名**：`nginx` → `docker.io/library/nginx:latest`
2. **认证**：向 Registry 请求 token（如果需要）
3. **获取 Manifest**：请求 `GET /v2/library/nginx/manifests/latest`，返回镜像清单
4. **对比本地层**：查看本地已有哪些层（通过层的 SHA256）
5. **并行下载缺失层**：下载 `.tar.gz` 格式的层
6. **解压层**：解压到 `/var/lib/docker/overlay2/<layer_id>/diff/`
7. **写入元数据**：更新 imagedb 和 layerdb
8. **组装镜像**：把 Config 和层关联，生成完整镜像

已存在的层直接跳过下载，这就是为什么拉相似镜像很快。

### 7.5 镜像分层的最大数量限制

- overlay2 存储驱动**最多支持 128 层**
- 超过后会报错：`max depth exceeded`
- 实际生产建议控制在 25 层以内，过多层数会影响性能

---

## 八、Docker 常见原理性面试题

### 8.1 Docker 容器的本质是什么？

答：Docker 容器**本质上就是一个特殊的进程**，通过 Linux 内核的 Namespace 实现资源视图隔离，通过 Cgroups 实现资源用量限制，通过 UnionFS 实现镜像分层文件系统。它没有虚拟机那样的 Guest OS，直接运行在宿主机内核上，只是通过这些内核机制"看起来"像是运行在独立环境中。

一句话总结：**容器 = 视图隔离的进程 + 资源受限的进程 + 独立文件系统的进程**。

### 8.2 Docker 为什么比虚拟机启动快？

答：三个原因：

1. **无需启动 Guest OS**：容器直接使用宿主机内核，虚拟机需要完整启动一个操作系统（几十秒到几分钟）
2. **无硬件模拟**：容器不需要 Hypervisor 模拟硬件，直接调用内核 API
3. **共享内核缓存**：容器共享宿主机的文件系统缓存、内核缓存

容器启动本质就是"启动一个进程并给它套上 Namespace 和 Cgroups"，通常几百毫秒到几秒。

### 8.3 Docker 网络的核心原理是什么？

答：Docker 单机网络的核心是三件套：

- **veth pair**：一对虚拟网卡，两端连通，一端在容器 Network Namespace 内（容器的 eth0），一端在宿主机 root Namespace（vethXXX）
- **docker0 网桥**：Linux Bridge，充当虚拟交换机，宿主机端的所有 vethXXX 都接入 docker0，让容器之间可以互通
- **iptables**：NAT 表实现端口映射（DNAT）和外网访问（SNAT）

跨主机网络需要 overlay（VXLAN 封包）或路由方案（如 Calico BGP）。

### 8.4 Docker 是如何实现文件系统隔离的？

答：通过 **Mount Namespace + pivot_root + UnionFS** 三步：

1. **Mount Namespace**：容器有独立的挂载点视图，看不到宿主机的挂载
2. **pivot_root**：把容器的 rootfs（联合文件系统合并后的目录）切换为容器的根文件系统 `/`
3. **UnionFS**：多层镜像层 + 一层可写层叠加，容器看到统一视图，修改只写在可写层（COW）

这样容器看到的 `/` 完全是镜像里的内容，与宿主机文件系统完全隔离。

### 8.5 一个容器可以有多个进程吗？

答：**技术上可以，但不推荐**。

- 容器内 PID 1 是 ENTRYPOINT/CMD 指定的主进程
- 主进程可以 fork 出多个子进程（如 nginx 的 master + worker）
- 但**一个容器只做一件事**是 Docker 的核心哲学

如果一个容器跑多个不相关的进程（如 nginx + mysql），会带来问题：

1. 日志混在一起，难以采集
2. 单点故障放大：一个进程挂了不会重启容器
3. 无法独立扩缩容
4. 违反微服务和 K8s 的设计理念

正确做法是**一个容器一个主进程**，多进程用 sidecar 模式（同 Pod 多容器）。

### 8.6 容器内的 root 用户和宿主机 root 是同一个吗？

答：**默认是同一个 UID**（都是 0），这是**严重的安全风险**。虽然 PID Namespace 和 Mount Namespace 隔离了视图，但 UID 是相同的，容器内 root 如果通过内核漏洞逃逸，就是宿主机 root。

**解决方案：**

1. **不用 root 运行**：Dockerfile 中 `USER 1000`
2. **User Namespace 映射**：启用 userns-remap，把容器内 UID 0 映射到宿主机的非特权 UID
3. **rootless Docker**：整个 dockerd 都以非 root 运行
4. **减少 Capabilities**：`--cap-drop=ALL --cap-add=NET_BIND_SERVICE`
5. **只读根文件系统**：`--read-only`

生产中至少要做到：非 root 用户运行 + 最小 Capabilities + 只读文件系统。

### 8.7 容器停止后数据会丢吗？

答：分情况：

- **容器停止但没删除（`docker stop`）**：数据保留在可写层，`docker start` 后依然存在
- **容器删除（`docker rm`）**：可写层被删除，**数据永久丢失**
- **挂载了 Volume 的目录**：数据在 Volume 中保留，与容器生命周期无关
- **挂载了 Bind Mount 的目录**：数据在宿主机上，与容器无关

因此**任何需要持久化的数据都必须挂载到 Volume 或 Bind Mount**，不能依赖容器可写层。

### 8.8 Docker 支持哪些隔离机制？隔离性够强吗？

答：Docker 支持的隔离：

| 层面 | 机制 |
|------|------|
| 进程视图 | PID Namespace |
| 网络 | Network Namespace |
| 文件系统 | Mount Namespace + UnionFS |
| 主机名 | UTS Namespace |
| IPC | IPC Namespace |
| 用户 | User Namespace |
| 资源用量 | Cgroups |
| 权限 | Capabilities、SELinux、AppArmor、seccomp |

**但隔离性比虚拟机弱**：所有容器共享宿主机内核，如果内核有漏洞，可能被容器逃逸攻击。因此：

- 强隔离场景（多租户、不信任代码）应使用 gVisor、Kata Containers 等安全容器
- 或者直接用虚拟机
