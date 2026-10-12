# Kubernetes 进阶原理与高频面试题

## 1. Kubernetes 架构原理总览

Kubernetes 的核心不是简单地“启动容器”，而是围绕声明式 API、控制器模式和资源状态调谐构建出来的一套分布式编排系统。

核心链路可以概括为：

```text
用户声明期望状态 -> API Server 持久化到 etcd -> 控制器和调度器监听变化 -> 节点 kubelet 执行 -> 持续上报状态 -> 控制器持续调谐
```

理解 Kubernetes 进阶原理，重点要抓住以下几个关键词：

- 声明式 API；
- 资源对象；
- Watch 机制；
- 控制循环；
- 调谐 Reconcile；
- 插件化扩展；
- 最终一致性。

---

## 2. API Server 请求处理流程

一次 Kubernetes API 请求通常经过以下阶段：

```text
HTTP 请求
  -> 认证 Authentication
  -> 鉴权 Authorization
  -> 准入控制 Admission
  -> API 对象校验与默认值填充
  -> 写入 etcd
  -> 返回响应
```

### 2.1 认证

认证解决“你是谁”的问题。

常见方式：

- 客户端证书；
- Bearer Token；
- ServiceAccount Token；
- OIDC；
- Webhook Token Authentication。

### 2.2 鉴权

鉴权解决“你能不能做这个操作”的问题。

常见鉴权模式：

- RBAC；
- ABAC；
- Node Authorization；
- Webhook Authorization。

生产环境最常用的是 RBAC。

### 2.3 准入控制

准入控制在对象持久化前执行，可以对请求进行校验、修改或拒绝。

分为：

- Mutating Admission：修改对象，例如注入 Sidecar、默认字段；
- Validating Admission：校验对象，例如禁止特权容器、限制镜像仓库。

常见准入控制器：

- NamespaceLifecycle；
- LimitRanger；
- ResourceQuota；
- ServiceAccount；
- MutatingAdmissionWebhook；
- ValidatingAdmissionWebhook。

面试常问：准入控制和 RBAC 有什么区别？

答：RBAC 主要判断用户是否有权限执行某类资源操作，例如能否创建 Pod。准入控制是在权限通过后，对具体对象内容进行校验或修改，例如禁止创建 privileged Pod、自动注入 sidecar、强制设置资源限制。

---

## 3. Watch 机制与 Informer

Kubernetes 组件通常不会频繁轮询 API Server，而是通过 List + Watch 机制监听资源变化。

基本过程：

1. 先 List 获取全量资源；
2. 再 Watch 后续增量变化；
3. 把对象缓存到本地；
4. 事件进入工作队列；
5. 控制器从队列中取出对象执行 Reconcile。

Informer 的作用：

- 降低 API Server 压力；
- 提供本地缓存；
- 监听资源变化；
- 触发事件回调；
- 支撑控制器编写。

面试重点：控制器不是一直直接查 API Server，而是依赖 Informer 缓存和事件机制提高性能。

---

## 4. Reconcile 调谐机制

控制器的核心是 Reconcile。

伪代码：

```text
while true:
  obj = queue.pop()
  desired = obj.spec
  actual = get_actual_state()
  if actual != desired:
      do_something_to_make_actual_close_to_desired()
  update_status()
```

特点：

- 不要求一次操作完全成功；
- 会不断重试；
- 追求最终一致性；
- 操作需要尽量幂等；
- 适合分布式环境中的异常恢复。

例子：Deployment 期望 3 个副本，当前只有 2 个，控制器会创建新的 Pod；如果创建失败，后续还会继续重试。

---

## 5. 调度器深度解析

### 5.1 调度器职责

调度器只负责给未调度 Pod 选择一个合适的 Node，并把绑定结果写回 API Server。

它不负责：

- 拉取镜像；
- 创建容器；
- 启动业务进程；
- 配置 Pod 网络；
- 挂载数据卷。

这些由目标节点上的 kubelet 和相关插件完成。

---

### 5.2 调度流程

调度过程：

```text
监听未绑定 Pod -> 过滤节点 -> 节点评分 -> 选择最佳节点 -> Assume -> Bind -> kubelet 执行
```

过滤阶段会排除不满足条件的节点，例如：

- 资源不足；
- 端口冲突；
- 节点不可调度；
- 不满足 nodeSelector；
- 不满足 NodeAffinity；
- 存在不可容忍的 Taint；
- Volume 不能挂载到该节点。

评分阶段会对候选节点打分，例如：

- 节点资源利用率是否均衡；
- Pod 是否更适合与某些 Pod 靠近；
- Pod 是否应该分散到不同拓扑域；
- 节点是否已有镜像；
- 节点负载情况。

---

### 5.3 为什么 Pod 一直 Pending

Pod 一直处于 `Pending`，通常说明 Pod 对象已经被 API Server 接收并写入 etcd，但是调度器还没有成功为它选择合适的 Node，或者虽然已经调度，但 kubelet 还没有完成前置准备。

最常见的排查入口是：

```bash
kubectl describe pod <pod-name>
```

重点看输出里的 `Events`，里面通常会出现类似 `FailedScheduling`、`pod has unbound immediate PersistentVolumeClaims`、`Insufficient cpu`、`node(s) had untolerated taint` 等提示。

下面是几个常见原因。

#### 5.3.1 CPU 或内存 requests 太大，没有节点满足

`requests` 表示 Pod 启动时向 Kubernetes 申请的最低资源保障。

例如：

```yaml
resources:
  requests:
    cpu: "8"
    memory: "16Gi"
```

这表示该容器至少需要 8 核 CPU 和 16Gi 内存。调度器会根据 Node 的可分配资源判断能不能放下这个 Pod。

如果集群中每个节点剩余资源都不够，例如每个节点最多只剩 4 核 CPU 或 8Gi 内存，那么调度器就不会把这个 Pod 调度到任何节点上，Pod 会一直 Pending。

常见事件类似：

```text
0/3 nodes are available: 3 Insufficient cpu.
0/3 nodes are available: 3 Insufficient memory.
```

解决思路：

- 降低 Pod 的 CPU / 内存 requests；
- 清理或缩容其他占用资源的 Pod；
- 增加节点；
- 启用或修复集群自动扩容。

---

#### 5.3.2 PVC 未绑定

PVC，全称 PersistentVolumeClaim，是 Pod 对存储资源的申请。

如果 Pod 声明要挂载一个 PVC：

```yaml
volumes:
  - name: data
    persistentVolumeClaim:
      claimName: data-pvc
```

但是这个 PVC 没有成功绑定到 PV，或者动态存储卷创建失败，那么 Pod 可能无法调度或无法启动。

常见事件类似：

```text
pod has unbound immediate PersistentVolumeClaims
```

这表示 Pod 依赖的存储还没准备好。

常见原因：

- PVC 找不到匹配的 PV；
- StorageClass 写错或不存在；
- 云盘、NAS、分布式存储创建失败；
- PVC 申请的容量过大；
- 访问模式不匹配，例如 PVC 要求 ReadWriteMany，但存储只支持 ReadWriteOnce。

解决思路：

```bash
kubectl get pvc
kubectl describe pvc <pvc-name>
```

重点看 PVC 是否处于 `Bound` 状态。

---

#### 5.3.3 节点有 Taint，Pod 没有 Toleration

Taint 是节点上的“污点”，表示这个节点不希望普通 Pod 随便调度上来。

例如某个节点打了污点：

```bash
kubectl taint nodes node-1 dedicated=gpu:NoSchedule
```

含义是：

```text
node-1 是专用节点，普通 Pod 不允许调度过来。
```

如果 Pod 没有对应的 toleration，就无法调度到这个节点。

Pod 需要这样声明自己可以容忍这个污点：

```yaml
tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "gpu"
    effect: "NoSchedule"
```

常见事件类似：

```text
node(s) had untolerated taint {dedicated: gpu}
```

可以这样理解：

```text
Taint：节点说“别来”
Toleration：Pod 说“我能接受”
```

只有 Pod 能容忍节点上的 Taint，才有机会被调度到该节点。

---

#### 5.3.4 nodeSelector 写错

`nodeSelector` 用来要求 Pod 只能调度到带有指定 Label 的节点上。

例如：

```yaml
nodeSelector:
  disk: ssd
```

表示这个 Pod 只能调度到带有下面标签的 Node 上：

```text
disk=ssd
```

如果集群里没有任何节点有这个 Label，或者 Label 名称、值写错了，调度器就找不到可用节点。

常见事件类似：

```text
node(s) didn't match Pod's node affinity/selector
```

排查命令：

```bash
kubectl get nodes --show-labels
```

解决思路：

- 修正 Pod 的 nodeSelector；
- 给目标节点补充正确 Label；
- 不需要强制调度到特定节点时，删除 nodeSelector。

---

#### 5.3.5 亲和性或反亲和性规则过严

亲和性和反亲和性比 nodeSelector 更灵活，但也更容易把调度条件写得过严。

常见有三类：

- `nodeAffinity`：要求 Pod 调度到满足条件的节点；
- `podAffinity`：希望或要求 Pod 和某些 Pod 调度到一起；
- `podAntiAffinity`：希望或要求 Pod 不要和某些 Pod 调度到一起。

例如强制要求 Pod 必须调度到某个可用区：

```yaml
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms:
        - matchExpressions:
            - key: topology.kubernetes.io/zone
              operator: In
              values:
                - zone-a
```

如果 `zone-a` 没有可用节点，Pod 就无法调度。

再比如强制反亲和：

```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchLabels:
            app: user-service
        topologyKey: kubernetes.io/hostname
```

这表示同一个节点上不能有相同 `app=user-service` 的 Pod。如果节点数量不足，而副本数又很多，就可能调度不出来。

解决思路：

- 把硬约束 `requiredDuringSchedulingIgnoredDuringExecution` 改成软约束 `preferredDuringSchedulingIgnoredDuringExecution`；
- 放宽匹配条件；
- 增加满足条件的节点；
- 检查 topologyKey 是否正确。

---

#### 5.3.6 所有节点被 cordon

`cordon` 表示把节点标记为不可调度。

执行：

```bash
kubectl cordon node-1
```

这个节点上已经运行的 Pod 通常不会被主动驱逐，但新的 Pod 不会再调度到这个节点。

如果所有节点都被 cordon，调度器就没有任何节点可以选择，Pod 会一直 Pending。

常见事件类似：

```text
node(s) were unschedulable
```

排查命令：

```bash
kubectl get nodes
```

如果节点状态里带有 `SchedulingDisabled`，说明节点被 cordon 了。

恢复方式：

```bash
kubectl uncordon <node-name>
```

---

#### 5.3.7 指定 hostPort 冲突

`hostPort` 表示把容器端口直接绑定到宿主机 Node 的某个端口。

例如：

```yaml
ports:
  - containerPort: 8080
    hostPort: 8080
```

这表示 Pod 启动后会占用宿主机的 8080 端口。

如果某个节点上已经有其他 Pod 或进程占用了这个 `hostPort`，新的 Pod 就不能再调度到该节点，因为同一台机器上的同一个端口不能被重复占用。

常见事件类似：

```text
node(s) didn't have free ports for the requested pod ports
```

解决思路：

- 尽量避免使用 hostPort；
- 使用 Service 暴露服务；
- 如果必须使用 hostPort，需要保证不同 Pod 调度到不同节点；
- 检查是否有端口冲突。

---

#### 5.3.8 集群自动扩容失败

在云上 Kubernetes 集群中，通常会启用 Cluster Autoscaler。当 Pod 因为资源不足无法调度时，自动扩容组件会尝试增加新节点。

但如果自动扩容失败，Pod 仍然会 Pending。

常见原因包括：

- 云厂商资源配额不足；
- 节点池达到最大节点数；
- 没有符合 Pod 要求的节点池；
- Pod 的 nodeSelector、nodeAffinity、Taint/Toleration 限制导致新增节点也不满足；
- 云厂商 API 调用失败；
- 自动扩容组件本身异常。

常见事件可能类似：

```text
pod didn't trigger scale-up
max node group size reached
```

解决思路：

- 查看 Cluster Autoscaler 日志；
- 检查节点池最大容量；
- 检查云厂商资源配额；
- 检查 Pod 调度约束是否过严；
- 手动扩容节点验证是否能恢复。

---

#### 5.3.9 快速记忆方式

Pod Pending 可以按照下面顺序排查：

```text
先看资源够不够
再看存储绑没绑
再看节点让不让调度
再看 Pod 有没有写死调度条件
再看端口有没有冲突
最后看自动扩容有没有失败
```

对应到具体原因：

| 原因 | 可以理解为 |
|---|---|
| CPU / 内存 requests 太大 | Pod 要的资源太多，没有机器放得下 |
| PVC 未绑定 | Pod 要用的磁盘还没准备好 |
| Taint / Toleration 不匹配 | 节点拒绝普通 Pod，Pod 没有通行证 |
| nodeSelector 写错 | Pod 指定了不存在的节点标签 |
| 亲和性 / 反亲和性过严 | Pod 对部署位置要求太苛刻 |
| 所有节点被 cordon | 所有节点都被标记为不接收新 Pod |
| hostPort 冲突 | Pod 要占用的宿主机端口已被占用 |
| 自动扩容失败 | 本该加机器，但加不出来或加了也不满足条件 |

---

## 6. kubelet 工作原理

kubelet 是每个 Node 上最核心的组件。

核心职责：

1. 注册节点；
2. 监听分配给本节点的 Pod；
3. 调用容器运行时创建容器；
4. 调用 CNI 配置网络；
5. 挂载 Volume；
6. 执行 Probe；
7. 上报 Pod 和 Node 状态；
8. 管理容器重启和 Pod 生命周期。

kubelet 与容器运行时通过 CRI 通信，与网络插件通过 CNI 协作，与存储插件通过 CSI 协作。

### 6.1 kubelet 上报 Pod 和 Node 状态的时间

kubelet 上报 Pod 和 Node 状态不是一个单一固定时间点，而是“状态变化触发 + 周期同步兜底”结合。

#### Node 状态上报

kubelet 会定期向 API Server 上报本节点状态。现代 Kubernetes 中，节点心跳主要通过 Node Lease 实现。

常见默认值可以这样理解：

| 参数 | 作用 | 常见默认值 |
|---|---|---|
| nodeStatusUpdateFrequency | kubelet 更新节点状态或心跳相关频率 | 约 10 秒 |
| nodeStatusReportFrequency | Node 状态无变化时完整上报频率 | 约 5 分钟 |
| nodeLeaseDurationSeconds | Node Lease 租约有效时间 | 约 40 秒 |
| syncFrequency | kubelet 主循环同步周期 | 约 1 分钟 |

Node Lease 可以理解为轻量心跳：

```text
kubelet 每隔约 10 秒更新一次 Lease：
我这个 Node 还活着。
```

相比频繁更新完整 Node 对象，更新 Lease 更轻量，可以降低 API Server 压力。

如果节点状态发生变化，例如：

- Ready 状态变化；
- DiskPressure；
- MemoryPressure；
- PIDPressure；
- NetworkUnavailable；
- 节点资源或条件变化；

kubelet 会尽快把新的 Node 状态更新到 API Server。

如果 Node 状态没有变化，kubelet 通常不需要频繁完整更新 Node 对象，而是主要通过 Lease 维持心跳，完整 Node 状态可以低频上报。

#### Pod 状态上报

Pod 状态也不是严格每隔固定时间上报一次，而是：

```text
Pod 状态变化时尽快上报
Pod 状态没变化时周期同步兜底
```

常见会触发 Pod 状态更新的场景：

- Pod 被调度到当前节点；
- 镜像拉取中或拉取失败；
- 容器创建中；
- 容器启动成功；
- 容器退出或重启；
- livenessProbe 失败；
- readinessProbe 状态变化；
- startupProbe 状态变化；
- Pod 从 Pending 变成 Running；
- Pod 从 Running 变成 Failed 或 Succeeded。

例如容器启动成功后，kubelet 会把 Pod 状态从：

```text
Pending -> Running
```

如果 readinessProbe 通过，会把 Pod 的 Ready 状态从：

```text
Ready: False -> Ready: True
```

随后 EndpointSlice 控制器会把这个 Ready Pod 加入 Service 后端，流量才会进入这个 Pod。

需要注意：`syncFrequency` 是 kubelet 主循环同步周期，不等于所有 Pod 状态严格上报周期。实际实现中还会有缓存、批量、去重和失败重试。

#### Node 多久会被判断 NotReady

如果 kubelet 长时间没有更新 Node Lease 或 NodeStatus，Node Controller 会认为节点异常。

大致链路：

```text
kubelet 定期更新 Node Lease / NodeStatus
        |
        v
Node Controller 监控心跳
        |
        v
超过一定时间没有收到心跳
        |
        v
把 Node 标记为 NotReady / Unknown
```

常见是几十秒级别，例如 `node-monitor-grace-period` 通常在 40 秒或 50 秒左右，不同 Kubernetes 版本和集群配置可能略有差异。

一句话总结：kubelet 对 Node 的心跳通常通过 Node Lease 约 10 秒一次更新，完整 Node 状态无变化时低频上报；Pod 状态则是状态变化时尽快上报，并有周期同步兜底。

---

## 7. 容器运行时与 CRI

CRI 是 Kubernetes 定义的容器运行时接口。

常见运行时：

- containerd；
- CRI-O；
- Mirantis Container Runtime；
- Docker Engine + cri-dockerd。

CRI 主要包含两类能力：

- RuntimeService：创建、启动、停止容器和 PodSandbox；
- ImageService：拉取、删除、查询镜像。

PodSandbox 可以理解为 Pod 的基础运行环境，通常先创建 pause 容器，再创建业务容器加入同一个网络命名空间。

---

## 8. Pause 容器

每个 Pod 通常都有一个 Pause 容器，也叫 infra container。

作用：

- 持有 Pod 的网络命名空间；
- 作为 Pod 内容器共享网络的基础；
- 让业务容器可以重启而不丢失 Pod 网络命名空间；
- 回收僵尸进程。

为什么需要 Pause 容器？

如果直接用业务容器创建网络命名空间，当业务容器重启时网络命名空间可能消失。Pause 容器生命周期与 Pod 绑定，能稳定持有共享网络环境。

---

## 9. kube-proxy 原理

kube-proxy 负责把 Service 抽象落地为节点上的转发规则。

### 9.1 iptables 模式

特点：

- 基于 iptables 规则；
- 随机选择后端 Pod；
- 规则多时管理和匹配成本较高；
- 成熟稳定。

### 9.2 IPVS 模式

特点：

- 基于 Linux IPVS；
- 适合大规模 Service；
- 支持更多负载均衡算法；
- 性能通常优于大量 iptables 规则场景。

常见算法：

- rr：轮询；
- lc：最少连接；
- dh：目标地址哈希；
- sh：源地址哈希；
- sed：最短期望延迟。

### 9.3 eBPF 替代方案

一些网络方案如 Cilium 使用 eBPF 实现 Service 转发、网络策略和可观测性，减少 iptables 复杂度，提高性能和可扩展性。

---

## 10. Endpoint 和 EndpointSlice

Endpoint 记录 Service 后端 Pod IP 和端口。

EndpointSlice 是 Endpoint 的增强版本，适合大规模集群。

EndpointSlice 优点：

- 避免单个 Endpoint 对象过大；
- 降低 API Server 和网络组件更新压力；
- 支持更多拓扑信息；
- 更适合大规模服务发现。

面试常问：Service 找不到后端怎么办？

答：首先检查 Service selector 是否匹配 Pod label，然后检查 EndpointSlice 是否生成。如果 EndpointSlice 为空，说明 Service 没有找到 Ready 的后端 Pod，可能是 label 不匹配、Pod 未 Ready、端口配置错误等。

### 10.1 有了 EndpointSlice，为什么还需要 iptables / IPVS / eBPF 负载均衡

EndpointSlice 和 IPVS 解决的是两个不同层面的问题：

```text
EndpointSlice：告诉你 Service 后面有哪些 Pod
IPVS：真正决定一次请求转发到哪个 Pod
```

也就是说，EndpointSlice 是“后端列表”，IPVS 是“转发执行器 / 负载均衡器”。

例如：

```text
Service: user-service
ClusterIP: 10.96.0.100:80

后端 Pod:
  10.244.1.11:8080
  10.244.2.22:8080
  10.244.3.33:8080
```

EndpointSlice 里记录的是：

```text
user-service 后端：
  - 10.244.1.11:8080
  - 10.244.2.22:8080
  - 10.244.3.33:8080
```

但 EndpointSlice 只是 Kubernetes API 里的资源对象，本质上是一个后端地址清单，它不会自己处理网络包，也不会自己做转发。

真正的请求转发需要底层数据面来完成，例如 iptables、IPVS 或 eBPF。

kube-proxy 在 IPVS 模式下会监听 Service 和 EndpointSlice 变化，然后生成节点上的 IPVS 虚拟服务和真实后端：

```text
Service
   |
   v
EndpointSlice
   |
   v
kube-proxy 监听变化
   |
   v
生成 IPVS 虚拟服务和真实后端
   |
   v
Linux 内核 IPVS 执行转发
```

IPVS 中类似维护：

```text
Virtual Server:
  10.96.0.100:80

Real Servers:
  10.244.1.11:8080
  10.244.2.22:8080
  10.244.3.33:8080
```

当请求访问：

```text
10.96.0.100:80
```

IPVS 才会真正选择一个后端并转发：

```text
10.96.0.100:80 -> 10.244.2.22:8080
```

二者分工：

| 组件 | 解决的问题 |
|---|---|
| Service | 提供稳定访问入口 |
| EndpointSlice | 记录 Service 后面有哪些 Ready Pod |
| kube-proxy | 监听 Service / EndpointSlice，生成节点转发规则 |
| IPVS | 在 Linux 内核中真正执行四层负载均衡 |
| Pod | 最终处理请求 |

完整链路：

```text
业务 Pod
  |
  | http://user-service
  v
CoreDNS 解析到 ClusterIP
  |
  v
访问 Service ClusterIP
  |
  v
IPVS 根据 EndpointSlice 生成的后端表选择 Pod
  |
  v
目标 Pod
```

一句话总结：EndpointSlice 只是记录 Service 后面有哪些可用 Pod，不负责真正转发流量；IPVS 根据这些后端信息在 Linux 内核里建立负载均衡规则，真正负责把访问 Service ClusterIP 的请求转发到某一个后端 Pod。

---

## 11. DNS 解析原理

Kubernetes 默认通过 CoreDNS 提供服务发现。DNS 在集群中的核心作用是：把服务名解析成可访问地址，让应用不用记 Pod IP 或 Service IP，而是通过稳定的名字访问服务。

### 11.1 DNS 在 Service 中的作用

Service 解决的是一组 Pod 的稳定访问入口问题，而 DNS 解决的是“如何通过服务名找到这个入口”的问题。

例如有两个服务：

```text
order-service   订单服务
user-service    用户服务
```

order-service 需要调用 user-service。如果没有 DNS，可能需要写死 Service 的 ClusterIP：

```text
http://10.96.12.34:80
```

但这样有几个问题：

- IP 不好记；
- 不同环境 IP 可能不同；
- Service 删除重建后 ClusterIP 可能变化；
- 应用代码不应该依赖具体 IP；
- 配置可读性差。

有了 Kubernetes DNS 后，order-service 可以直接访问：

```text
http://user-service
```

或者使用完整域名：

```text
http://user-service.default.svc.cluster.local
```

Service 的完整 DNS 名称格式是：

```text
<ServiceName>.<Namespace>.svc.<ClusterDomain>
```

例如：

```text
user-service.default.svc.cluster.local
```

各部分含义：

| 部分 | 含义 |
|---|---|
| user-service | Service 名称 |
| default | Namespace |
| svc | 表示这是 Service |
| cluster.local | 集群默认域名后缀 |

同一个 Namespace 内通常可以直接使用短名称：

```text
http://user-service
```

跨 Namespace 访问时，至少要带上 Namespace：

```text
http://user-service.default
```

或者写完整域名：

```text
http://user-service.default.svc.cluster.local
```

### 11.2 普通 ClusterIP Service 的 DNS 解析

普通 Service 有 ClusterIP。

例如：

```text
Service: user-service
ClusterIP: 10.96.12.34
```

DNS 查询：

```text
user-service.default.svc.cluster.local
```

返回的是 Service 的 ClusterIP：

```text
10.96.12.34
```

完整访问链路：

```text
order-service Pod
   |
   | DNS 查询 user-service
   v
CoreDNS 返回 10.96.12.34
   |
   | 请求 10.96.12.34:80
   v
Service 转发规则
   |
   | kube-proxy / IPVS / eBPF
   v
某个 user-service Pod:8080
```

注意：DNS 只负责把 Service 名称解析成 Service IP，不负责把请求负载均衡到具体 Pod。真正转发到哪个 Pod，是 Service 底层转发机制负责的。

### 11.3 Headless Service 的 DNS 解析

Headless Service 是没有 ClusterIP 的 Service：

```yaml
spec:
  clusterIP: None
```

这时 DNS 不返回 Service 虚拟 IP，而是直接返回后端 Pod IP 列表。

例如查询：

```text
user-service.default.svc.cluster.local
```

可能返回：

```text
10.244.1.11
10.244.2.23
10.244.3.17
```

访问链路变成：

```text
应用访问 user-service
        |
        v
DNS 返回多个 Pod IP
        |
        v
客户端自己选择其中一个 Pod 访问
```

Headless Service 常用于：

- StatefulSet；
- 数据库集群；
- ZooKeeper；
- Kafka；
- Elasticsearch；
- 需要直接感知每个实例地址的场景。

### 11.4 DNS、Service、EndpointSlice 的关系

Service 通过 selector 匹配 Pod label，EndpointSlice 记录真正可用的后端 Pod IP 和端口。

大致关系：

```text
Service selector
   |
   v
匹配 Pod label
   |
   v
EndpointSlice 记录后端 Pod IP 和端口
   |
   v
kube-proxy / IPVS / eBPF 根据 EndpointSlice 更新转发规则
```

普通 Service 的访问路径：

```text
DNS -> ClusterIP -> kube-proxy / IPVS / eBPF -> Pod
```

Headless Service 的访问路径：

```text
DNS -> Pod IP 列表 -> 客户端直接访问 Pod
```

### 11.5 DNS 解决的问题总结

| 问题 | DNS 如何解决 |
|---|---|
| 不想记 Service IP | 通过 Service 名称访问 |
| 不同环境 IP 不同 | 应用只依赖服务名 |
| Service 重建 IP 可能变化 | DNS 重新解析即可 |
| 跨 Namespace 访问 | 使用 `service.namespace` |
| 有状态服务需要发现具体实例 | Headless Service 返回 Pod IP |
| 微服务之间调用 | 用稳定服务名做服务发现 |

面试回答：Kubernetes 中 DNS 主要由 CoreDNS 提供，用于集群内部服务发现。创建 Service 后，DNS 会为它生成形如 `service.namespace.svc.cluster.local` 的域名。普通 ClusterIP Service 的 DNS 解析结果是 Service 的 ClusterIP，后续流量再由 kube-proxy、IPVS 或 eBPF 转发到后端 Pod。如果是 Headless Service，DNS 不返回 ClusterIP，而是直接返回后端 Pod IP 列表，常用于 StatefulSet 或需要直接发现实例的有状态服务。

---

## 12. StatefulSet 深度理解

StatefulSet 的核心价值是为有状态应用提供稳定身份。

稳定性包括：

- 稳定 Pod 名称；
- 稳定 DNS 名称；
- 稳定 PVC；
- 有序启动；
- 有序终止；
- 有序滚动更新。

Headless Service 配合 StatefulSet 可以为每个 Pod 提供稳定域名：

```text
pod-name.service-name.namespace.svc.cluster.local
```

例如：

```text
mysql-0.mysql.default.svc.cluster.local
```

这对数据库、消息队列、分布式协调服务非常重要，因为它们通常需要稳定成员身份。

---

## 13. 高可用设计

### 13.1 控制面高可用

控制面高可用通常包括：

- 多个 API Server；
- 多个 controller-manager 实例，使用 leader election；
- 多个 scheduler 实例，使用 leader election；
- 多节点 etcd；
- API Server 前置负载均衡。

controller-manager 和 scheduler 可以多副本运行，但同一时刻通常只有 leader 真正工作，避免重复调谐。

### 13.2 etcd 高可用

etcd 建议奇数节点部署。

容错能力：

| etcd 节点数 | 可容忍故障数 | 多数派 |
|---|---:|---:|
| 1 | 0 | 1 |
| 3 | 1 | 2 |
| 5 | 2 | 3 |
| 7 | 3 | 4 |

etcd 对磁盘延迟和网络延迟敏感，生产环境应使用 SSD、低延迟网络，并定期备份。

---

## 14. PodDisruptionBudget

PDB 用于限制自愿中断时可同时不可用的 Pod 数量。

自愿中断包括：

- 节点维护；
- drain 节点；
- 集群缩容；
- 人工驱逐。

示例：

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: user-service-pdb
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: user-service
```

表示驱逐过程中至少保留 2 个可用副本。

注意：PDB 不防止非自愿中断，例如节点突然宕机。

---

## 15. 优雅发布和优雅下线

生产中零停机发布通常需要：

1. 多副本部署；
2. readinessProbe 准确反映是否可接流量；
3. livenessProbe 不要过于激进；
4. preStop Hook 给应用留出处理时间；
5. 应用监听 SIGTERM 并停止接新请求；
6. terminationGracePeriodSeconds 足够长；
7. maxUnavailable 控制不可用副本数；
8. 数据库变更兼容新旧版本。

Pod 删除时流量摘除不是瞬时的，EndpointSlice、kube-proxy、Ingress Controller 都需要传播时间，所以应用最好在收到 SIGTERM 后先停止接新请求，再等待已有请求完成。

---

## 16. Operator 和 CRD

### 16.1 CRD

CRD，全称 CustomResourceDefinition，用于扩展 Kubernetes API，让用户定义自己的资源类型。

例如定义：

- RedisCluster；
- KafkaTopic；
- MySQLBackup；
- Certificate；
- Application。

### 16.2 Operator

Operator 是一种基于 Kubernetes 控制器模式的自动化运维程序。

它通常会：

1. 定义 CRD；
2. 监听自定义资源；
3. 根据资源 spec 执行创建、扩容、备份、恢复、升级等操作；
4. 更新资源 status。

面试回答：Operator 本质上是把运维经验编码成控制器，通过声明式 API 管理复杂有状态系统。

---

## 17. Helm

Helm 是 Kubernetes 的包管理工具。

核心概念：

- Chart：应用包模板；
- Values：用户配置值；
- Release：一次安装实例；
- Repository：Chart 仓库。

Helm 适合管理复杂应用的多资源部署，例如一个应用同时包含 Deployment、Service、Ingress、ConfigMap、Secret、HPA 等。

### 17.1 Helm Chart 目录存在哪里

Helm Chart 本质上是一组文件目录，通常存放在应用代码仓库或专门的部署仓库中。

常见位置有几种：

```text
应用代码仓库内：
my-app/
├── src/
├── Dockerfile
└── charts/
    └── my-chart/
```

或者：

```text
应用代码仓库内：
my-app/
├── src/
├── Dockerfile
└── deploy/
    └── helm/
        └── my-chart/
```

也可以放在单独的 GitOps / 运维部署仓库中：

```text
deployments/
├── user-service/
│   └── my-chart/
├── order-service/
│   └── my-chart/
└── product-service/
    └── my-chart/
```

如果 Chart 需要被多个项目复用，还可以打包后发布到 Helm Repository 或 OCI Registry 中。

例如：

```bash
helm package my-chart
helm push my-chart-0.1.0.tgz oci://registry.example.com/helm-charts
```

所以，`my-chart/` 不是 Kubernetes 集群里天然存在的目录，而是开发者或运维在 Git 仓库、本地目录、CI/CD 工作目录、Helm 仓库中维护的一套模板文件。真正安装到集群时，Helm 会把模板渲染成普通 Kubernetes YAML，然后提交给 API Server。

---

### 17.2 Helm Chart 目录结构

一个典型 Chart 目录如下：

```text
my-chart/
├── Chart.yaml          # Chart 元信息
├── values.yaml         # 默认配置值
├── charts/             # 依赖的 Chart
├── templates/          # K8s 资源模板
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   └── _helpers.tpl
└── README.md           # 使用说明，可选
```

各文件作用：

| 文件或目录 | 作用 |
|---|---|
| Chart.yaml | 描述 Chart 名称、版本、应用版本、依赖等元信息 |
| values.yaml | 保存默认配置值，例如镜像、端口、副本数、资源限制 |
| charts/ | 存放当前 Chart 依赖的其他 Chart |
| templates/ | 存放 Kubernetes 资源模板 |
| deployment.yaml | Deployment 模板 |
| service.yaml | Service 模板 |
| ingress.yaml | Ingress 模板 |
| _helpers.tpl | 定义可复用模板函数，例如名称拼接、标签生成 |
| README.md | Chart 使用说明，可选 |

示例 `values.yaml`：

```yaml
replicaCount: 3

image:
  repository: registry.example.com/user-service
  tag: v1

service:
  type: ClusterIP
  port: 80
  targetPort: 8080

ingress:
  enabled: true
  host: user.example.com
```

示例 `templates/deployment.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
    spec:
      containers:
        - name: {{ .Release.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: {{ .Values.service.targetPort }}
```

Helm 安装时：

```bash
helm install user-service ./my-chart
```

Helm 会把模板和 values 渲染成最终 YAML，再提交给 Kubernetes。

---

### 17.3 没有 Helm 的时候是什么样的

没有 Helm 时，通常需要直接维护多份 Kubernetes YAML。

例如部署一个 user-service，可能要有这些文件：

```text
k8s/
├── deployment.yaml
├── service.yaml
├── ingress.yaml
├── configmap.yaml
└── hpa.yaml
```

如果有 dev、test、prod 三套环境，可能变成：

```text
k8s/
├── dev/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── test/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
└── prod/
    ├── deployment.yaml
    ├── service.yaml
    └── ingress.yaml
```

它们大部分内容可能都一样，只是镜像版本、副本数、域名、资源限制不同。

例如 dev 环境：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: user-service
          image: registry.example.com/user-service:dev
```

prod 环境：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
spec:
  replicas: 5
  template:
    spec:
      containers:
        - name: user-service
          image: registry.example.com/user-service:v1.0.0
```

没有 Helm 的问题是：

- 多环境 YAML 重复；
- 修改一个公共字段要改很多文件；
- 容易出现 dev、test、prod 配置不一致；
- 多资源对象安装、升级、回滚缺少统一版本管理；
- 依赖关系和参数化能力弱。

使用 Helm 后，可以只维护一套模板，通过不同 values 文件区分环境：

```text
my-chart/
├── Chart.yaml
├── values.yaml
├── values-dev.yaml
├── values-prod.yaml
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    └── ingress.yaml
```

部署 dev：

```bash
helm upgrade --install user-service-dev ./my-chart -f values-dev.yaml
```

部署 prod：

```bash
helm upgrade --install user-service-prod ./my-chart -f values-prod.yaml
```

也就是说：

```text
没有 Helm：维护多套重复 YAML
有 Helm：维护一套模板 + 多套 values
```

---

### 17.4 用一个例子理解 Helm 解决的问题

假设一个服务需要部署 Deployment、Service 和 Ingress。

没有 Helm 时，你需要维护三份固定 YAML：

```text
deployment.yaml
service.yaml
ingress.yaml
```

如果要部署到 dev 和 prod 两个环境，你可能需要复制两份：

```text
k8s/dev/deployment.yaml
k8s/dev/service.yaml
k8s/dev/ingress.yaml

k8s/prod/deployment.yaml
k8s/prod/service.yaml
k8s/prod/ingress.yaml
```

其中差异可能只是：

| 配置项 | dev | prod |
|---|---|---|
| replicas | 1 | 5 |
| image.tag | dev | v1.0.0 |
| ingress.host | user-dev.example.com | user.example.com |
| resources | 小 | 大 |

使用 Helm 后，可以把差异提取到 values 文件中。

`values-dev.yaml`：

```yaml
replicaCount: 1
image:
  tag: dev
ingress:
  host: user-dev.example.com
```

`values-prod.yaml`：

```yaml
replicaCount: 5
image:
  tag: v1.0.0
ingress:
  host: user.example.com
```

模板只保留一份：

```yaml
spec:
  replicas: {{ .Values.replicaCount }}
```

最终效果：

```text
同一套模板 + 不同 values
        |
        v
渲染出不同环境的 Kubernetes YAML
        |
        v
提交给 API Server
```

Helm 解决的核心问题不是替代 Kubernetes，而是让 Kubernetes YAML 模板化、参数化、版本化，并提供统一安装、升级、回滚能力。

---

### 17.5 部署配置放在单独运维仓库时怎么使用

如果 k8s YAML 或 Helm Chart 存放在单独的运维仓库 / 部署仓库中，服务部署时通常不是应用代码仓库直接依赖本地目录，而是通过 CI/CD 或 GitOps 工具把应用镜像和部署配置串起来。

可以理解为：

```text
应用代码仓库：负责构建镜像
部署配置仓库：负责描述怎么部署这个镜像
CI/CD 或 GitOps：负责把二者串起来
```

假设服务叫 `user-service`。

应用代码仓库通常类似：

```text
user-service/
├── src/
├── Dockerfile
├── pom.xml
└── README.md
```

它主要负责：

- 业务代码开发；
- 构建应用；
- 构建容器镜像；
- 推送镜像到镜像仓库。

例如最终产物是：

```text
registry.example.com/user-service:v1.0.0
```

部署仓库通常类似：

```text
deployments/
├── user-service/
│   ├── helm/
│   │   └── user-service/
│   │       ├── Chart.yaml
│   │       ├── values.yaml
│   │       ├── values-dev.yaml
│   │       ├── values-prod.yaml
│   │       └── templates/
│   │           ├── deployment.yaml
│   │           ├── service.yaml
│   │           └── ingress.yaml
│   └── k8s/
│       ├── dev/
│       └── prod/
├── order-service/
└── product-service/
```

它主要负责：

- Deployment、Service、Ingress 等资源配置；
- 多环境差异配置；
- 副本数；
- 资源 requests / limits；
- 域名；
- 探针；
- HPA；
- ConfigMap / Secret 引用。

#### 方式一：CI/CD 拉取应用仓库和部署仓库后部署

常见流程：

```text
开发提交 user-service 代码
        |
        v
CI/CD 构建镜像 registry.example.com/user-service:v1.0.0
        |
        v
CI/CD 拉取 deployments 运维仓库
        |
        v
传入或修改镜像 tag
        |
        v
执行 helm upgrade 或 kubectl apply
        |
        v
部署到 Kubernetes
```

示例命令：

```bash
# 1. 拉取应用代码仓库
git clone git@example.com:app/user-service.git

# 2. 构建并推送镜像
docker build -t registry.example.com/user-service:v1.0.0 .
docker push registry.example.com/user-service:v1.0.0

# 3. 拉取部署仓库
git clone git@example.com:ops/deployments.git

# 4. 使用部署仓库里的 Helm Chart 部署
helm upgrade --install user-service \
  ./deployments/user-service/helm/user-service \
  -f ./deployments/user-service/helm/user-service/values-prod.yaml \
  --set image.tag=v1.0.0
```

这里的关键是：Chart 不在应用仓库也没关系，CI/CD 会拉取部署仓库，然后使用部署仓库里的 Chart 或 YAML 执行部署。

#### 方式二：CI/CD 只更新部署仓库，由 GitOps 自动同步

GitOps 场景下，常见工具有 Argo CD、Flux。

流程是：

```text
应用代码仓库构建镜像
        |
        v
生成镜像 tag
        |
        v
更新部署仓库里的 values-prod.yaml 或 k8s YAML
        |
        v
提交部署仓库
        |
        v
Argo CD / Flux 监听部署仓库变化
        |
        v
自动同步到 Kubernetes 集群
```

例如部署仓库的 `values-prod.yaml`：

```yaml
image:
  repository: registry.example.com/user-service
  tag: v1.0.0
```

发布新版本时，流水线把 tag 改成：

```yaml
image:
  repository: registry.example.com/user-service
  tag: v1.0.1
```

然后提交部署仓库：

```bash
git add deployments/user-service/helm/user-service/values-prod.yaml
git commit -m "deploy user-service v1.0.1 to prod"
git push
```

Argo CD 或 Flux 发现 Git 仓库变化后，会自动执行类似 `helm template`、`kustomize build`、`kubectl apply` 的动作，把新版本同步到集群。

这种模式下：

```text
CI/CD 负责构建镜像并修改 Git
GitOps 工具负责把 Git 中的期望状态同步到集群
```

#### 方式三：Chart 发布到 Helm 仓库或 OCI Registry

如果 Chart 需要复用，也可以打包发布到 Helm 仓库或 OCI Registry。

```bash
helm package ./charts/user-service
```

生成：

```text
user-service-0.1.0.tgz
```

然后发布到 Helm Repository 或 OCI Registry。

安装时可以直接引用远端 Chart：

```bash
helm repo add company https://helm.example.com/charts
helm repo update

helm upgrade --install user-service company/user-service \
  -f values-prod.yaml \
  --set image.repository=registry.example.com/user-service \
  --set image.tag=v1.0.0
```

或者 OCI 方式：

```bash
helm upgrade --install user-service \
  oci://registry.example.com/helm-charts/user-service \
  -f values-prod.yaml \
  --set image.tag=v1.0.0
```

#### 如果不用 Helm，只用普通 YAML

部署仓库也可以只保存普通 Kubernetes YAML：

```text
deployments/
└── user-service/
    └── k8s/
        └── prod/
            ├── deployment.yaml
            ├── service.yaml
            └── ingress.yaml
```

CI/CD 可以这样部署：

```bash
git clone git@example.com:ops/deployments.git
kubectl apply -f deployments/user-service/k8s/prod/
```

如果镜像 tag 要动态更新，可以：

```bash
kubectl apply -f deployments/user-service/k8s/prod/

kubectl set image deployment/user-service \
  user-service=registry.example.com/user-service:v1.0.1
```

也可以使用 Kustomize 管理多环境：

```text
deployments/user-service/
├── base/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── kustomization.yaml
└── overlays/
    ├── dev/
    │   └── kustomization.yaml
    └── prod/
        └── kustomization.yaml
```

部署 prod：

```bash
kubectl apply -k deployments/user-service/overlays/prod
```

#### 应用仓库和部署仓库如何关联

二者主要通过镜像地址和镜像 tag 关联。

应用仓库产物：

```text
registry.example.com/user-service:v1.0.0
```

部署仓库引用：

```yaml
image:
  repository: registry.example.com/user-service
  tag: v1.0.0
```

或者普通 YAML 中写：

```yaml
image: registry.example.com/user-service:v1.0.0
```

所以两个仓库之间不是靠目录直接依赖，而是靠：

```text
镜像仓库地址 + 镜像 tag
```

串起来。

一句话总结：如果 Helm Chart 或 k8s YAML 放在单独运维仓库，部署时通常由 CI/CD 拉取部署仓库后执行 `helm upgrade` / `kubectl apply`，或者由 GitOps 工具监听部署仓库变化自动同步；应用仓库和部署仓库之间主要通过镜像地址和 tag 关联。

---

## 18. 监控与可观测性

Kubernetes 可观测性通常包括：

- Metrics：指标；
- Logs：日志；
- Traces：链路追踪；
- Events：事件。

常见组件：

- Prometheus：指标采集；
- Grafana：指标展示；
- Alertmanager：告警；
- Loki / Elasticsearch：日志存储；
- Fluent Bit / Filebeat：日志采集；
- Jaeger / Tempo：链路追踪；
- kube-state-metrics：导出 Kubernetes 资源状态；
- metrics-server：提供 HPA 所需基础资源指标。

常见监控指标：

- Node CPU、内存、磁盘、网络；
- Pod CPU、内存、重启次数；
- API Server 延迟和错误率；
- etcd 延迟、leader 变化、磁盘 fsync；
- scheduler 调度延迟；
- controller-manager 队列长度；
- kubelet 心跳和运行时错误；
- Deployment 可用副本数；
- HPA 扩缩容状态。

---

## 19. 日志采集方案

常见日志采集模式：

### 19.1 Node Agent 模式

每个节点部署一个 DaemonSet 采集节点上所有容器日志。

优点：资源开销低、统一管理。

常见工具：Fluent Bit、Filebeat、Vector。

### 19.2 Sidecar 模式

每个 Pod 内部署一个日志采集 Sidecar。

优点：灵活，可针对应用定制。

缺点：资源开销大，维护复杂。

### 19.3 应用直写模式

应用直接把日志发送到日志系统。

优点：链路短。

缺点：侵入业务代码，稳定性和背压处理复杂。

生产中最常见的是 Node Agent 模式。

---

## 20. 高频面试题汇总

### 20.1 Kubernetes 创建一个 Deployment 后发生了什么

答：

1. kubectl 将请求发送给 API Server；
2. API Server 完成认证、鉴权、准入控制；
3. Deployment 对象写入 etcd；
4. Deployment Controller 监听到新对象，创建 ReplicaSet；
5. ReplicaSet Controller 创建 Pod；
6. scheduler 监听到未调度 Pod，为其选择 Node；
7. kubelet 监听到分配到本节点的 Pod；
8. kubelet 调用容器运行时拉镜像、创建容器；
9. CNI 配置 Pod 网络；
10. kubelet 挂载 Volume、执行 Probe；
11. Pod Ready 后加入 Service Endpoint；
12. kube-proxy 或 eBPF 更新转发规则，流量可进入 Pod。

---

### 20.2 Deployment 滚动更新失败如何排查

答：

排查方向：

1. 新 Pod 是否 Pending，若是看调度事件；
2. 是否 ImagePullBackOff，检查镜像和凭证；
3. 是否 CrashLoopBackOff，查看应用日志；
4. readinessProbe 是否失败；
5. maxUnavailable、maxSurge 配置是否导致更新卡住；
6. PDB 是否限制驱逐；
7. 新旧版本是否存在配置或数据库兼容问题。

常用命令：

```bash
kubectl rollout status deployment <name>
kubectl describe deployment <name>
kubectl describe pod <pod-name>
kubectl logs <pod-name>
```

---

### 20.3 Kubernetes 如何保证高可用

答：

从多个层面保证：

- 控制面：多个 API Server、多副本 scheduler/controller-manager、etcd 高可用；
- 应用层：Deployment 多副本、反亲和性分散、PDB 限制中断；
- 网络层：Service 负载均衡、Ingress Controller 多副本；
- 存储层：高可用存储后端、定期备份；
- 运维层：监控告警、自动扩缩容、滚动发布和快速回滚。

---

### 20.4 Pod 被 OOMKilled 怎么排查

答：

1. 查看 Pod 状态和退出原因；
2. 查看容器内存 limit 是否过小；
3. 查看应用是否内存泄漏；
4. 查看是否存在突发流量或大对象处理；
5. 检查 JVM、Go runtime 等运行时内存配置；
6. 根据监控调整 requests 和 limits；
7. 必要时做水平扩容或优化代码。

---

### 20.5 为什么不建议滥用 latest 镜像标签

答：

`latest` 不可追溯、不可复现，可能导致不同节点拉到不同镜像版本，也不利于回滚和审计。生产环境建议使用明确版本号或镜像 digest。

---

### 20.6 Headless Service 有什么用

答：

Headless Service 不分配 ClusterIP，DNS 直接返回后端 Pod IP。它常用于 StatefulSet，为每个 Pod 提供稳定 DNS 记录，便于有状态服务之间发现固定成员。

---

### 20.7 为什么 readinessProbe 很重要

答：

readinessProbe 决定 Pod 是否加入 Service 后端。如果没有 readinessProbe，Pod 可能刚启动但应用还没准备好就接收流量，导致请求失败。发布、扩容、重启时都依赖 readinessProbe 保证流量只进入可用实例。

---

### 20.8 如何排查 DNS 解析失败

答：

排查方向：

1. CoreDNS Pod 是否正常；
2. kube-dns Service 是否存在；
3. Pod 的 `/etc/resolv.conf` 是否正确；
4. NetworkPolicy 是否拦截 DNS 请求；
5. 节点网络或 CNI 是否异常；
6. CoreDNS 日志是否有错误；
7. Service 名称和 Namespace 是否写对。

---

### 20.9 如何控制 Pod 分散到不同节点

答：

可以使用：

- Pod Anti-Affinity；
- topologySpreadConstraints；
- Deployment 多副本；
- 节点 Label；
- 合理资源 requests；
- 避免所有副本因约束过严无法调度。

---

### 20.10 Kubernetes 和 Docker 的关系是什么

答：

Docker 是容器构建和运行相关工具，Kubernetes 是容器编排平台。Kubernetes 不依赖 Docker Engine 本身，它通过 CRI 对接 containerd、CRI-O 等运行时。但 Docker 构建的镜像通常符合 OCI 标准，仍然可以被 Kubernetes 使用。

---

## 21. 面试回答技巧

回答 Kubernetes 问题时，可以采用以下结构：

1. 先给定义：这个组件是什么；
2. 再说作用：解决什么问题；
3. 再讲流程：请求或状态如何流转；
4. 再补充生产实践：如何配置、如何排查、有什么坑；
5. 最后给对比：和相近概念有什么区别。

例如回答 Service：

- 定义：Service 是一组 Pod 的稳定访问入口；
- 作用：屏蔽 Pod IP 变化，提供服务发现和负载均衡；
- 流程：selector 选中 Pod，EndpointSlice 记录后端，kube-proxy/eBPF 转发；
- 实践：检查 selector、targetPort、readiness、EndpointSlice；
- 对比：Ingress 负责七层入口，Service 更偏四层和服务发现。
