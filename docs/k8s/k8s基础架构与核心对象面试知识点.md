-=# Kubernetes 基础架构与核心对象面试知识点

## 1. Kubernetes 是什么

Kubernetes，简称 K8s，是一个用于自动化部署、扩缩容、服务发现、负载均衡、滚动发布、自愈和管理容器化应用的平台。

它解决的核心问题是：

- 如何把大量容器调度到多台机器上运行；
- 如何在容器异常、节点异常时自动恢复；
- 如何让服务之间稳定通信；
- 如何声明式地管理应用状态；
- 如何进行弹性伸缩、滚动升级和回滚；
- 如何屏蔽底层机器差异，统一管理计算、网络和存储资源。

一句话总结：Kubernetes 是容器编排系统，它关注的是“如何把容器应用稳定、可靠、可扩展地运行在集群中”。

---

## 2. Kubernetes 的核心设计理念

### 2.1 声明式 API

Kubernetes 通常不要求用户一步步告诉系统“怎么做”，而是告诉系统“我期望最终是什么状态”。

例如用户声明：

```yaml
replicas: 3
```

表示期望某个应用始终有 3 个副本运行。至于当前只有 2 个副本时如何补齐，某个 Pod 挂了以后如何重建，都由 Kubernetes 控制器自动完成。

面试重点：

- 声明式 API 描述目标状态；
- 控制器不断对比当前状态和期望状态；
- 如果二者不一致，就执行调谐逻辑，使当前状态逐渐逼近期望状态。

这就是 Kubernetes 中常说的 Reconcile 调谐机制。

---

### 2.2 控制循环

Kubernetes 内部大量组件都遵循控制循环：

```text
观察当前状态 -> 比较期望状态 -> 执行动作 -> 再次观察
```

典型例子：Deployment 控制器。

如果期望副本数是 5，当前只有 3 个 Pod，那么控制器会创建新的 ReplicaSet 或 Pod，使副本数逐渐达到 5。

---

### 2.3 一切皆资源

Kubernetes 中的 Pod、Deployment、Service、ConfigMap、Secret、Namespace、Node、Ingress、PersistentVolume 等都是资源对象。

每个资源对象通常包括：

- `apiVersion`：资源 API 版本；
- `kind`：资源类型；
- `metadata`：元信息，如名称、标签、命名空间；
- `spec`：用户期望的状态；
- `status`：系统观察到的实际状态。

示例：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
        - name: nginx
          image: nginx:1.25
status:
  availableReplicas: 3
```

面试常问：`spec` 和 `status` 有什么区别？

答：

- `spec` 是用户定义的期望状态；
- `status` 是 Kubernetes 系统维护的实际状态；
- 控制器负责让 `status` 尽量接近 `spec`。

---

## 3. Kubernetes 整体架构

Kubernetes 集群由控制平面和工作节点组成。

```text
+---------------------------------------------------+
|                 Control Plane                     |
|                                                   |
|  kube-apiserver  kube-scheduler  controller-manager|
|        |                |              |           |
|        +----------------+--------------+           |
|                         |                          |
|                        etcd                        |
+---------------------------------------------------+
                         |
                         |
+---------------------------------------------------+
|                    Worker Node                    |
|                                                   |
|  kubelet   kube-proxy   container runtime          |
|     |          |              |                    |
|    Pods      Service       containerd / CRI-O      |
+---------------------------------------------------+
```

---

## 4. 控制平面组件

### 4.1 kube-apiserver

`kube-apiserver` 是 Kubernetes 的统一入口，所有资源操作都要经过它。

职责：

- 对外提供 REST API；
- 处理认证、鉴权、准入控制；
- 校验请求合法性；
- 读写 etcd；
- 作为其他组件通信的中枢。

所有组件通常不直接访问 etcd，而是通过 API Server 访问集群状态。

面试重点：

- API Server 是 Kubernetes 控制面的流量入口；
- kubectl、scheduler、controller-manager、kubelet 都与 API Server 通信；
- API Server 本身一般是无状态组件，可以水平扩展；
- API Server 挂了会影响新操作，但已运行 Pod 通常不会立刻停止。

---

### 4.2 etcd

`etcd` 是 Kubernetes 的分布式强一致性 KV 存储，用来保存集群所有核心数据。

存储内容包括：

- Pod 信息；
- Deployment 信息；
- Service 信息；
- ConfigMap、Secret；
- Node 状态；
- 集群元数据。

特点：

- 基于 Raft 协议；
- 强一致性；
- 高可用部署通常为奇数个节点，例如 3、5、7；
- 对磁盘 IO 和网络延迟敏感。

面试常问：为什么 etcd 集群一般部署奇数个节点？

答：Raft 协议需要超过半数节点达成一致。3 个节点可容忍 1 个故障，5 个节点可容忍 2 个故障。偶数节点不会提升容错能力，反而增加成本和通信复杂度。例如 4 个节点仍然只能容忍 1 个节点故障，因为需要 3 个节点多数派。

---

### 4.3 kube-scheduler

`kube-scheduler` 负责为未绑定节点的 Pod 选择合适的 Node。

调度过程通常分为两个阶段：

1. 过滤阶段：筛掉不满足条件的节点；
2. 打分阶段：给剩余节点打分，选择分数最高的节点。

过滤条件示例：

- Node 是否资源足够；
- Node 是否满足 `nodeSelector`；
- Node 是否满足亲和性、反亲和性；
- Node 是否存在不可容忍的污点；
- Pod 是否使用了特定端口且端口冲突；
- 节点是否处于不可调度状态。

打分因素示例：

- 资源利用率是否均衡；
- 镜像是否已存在于节点；
- Pod 亲和性偏好；
- 拓扑分布约束；
- 节点负载情况。

面试重点：scheduler 只负责“选择节点”，真正拉镜像、创建容器的是目标节点上的 kubelet。

---

### 4.4 kube-controller-manager

`kube-controller-manager` 运行多个控制器，每个控制器负责一种资源的调谐。

常见控制器：

- Deployment Controller；
- ReplicaSet Controller；
- StatefulSet Controller；
- DaemonSet Controller；
- Job Controller；
- CronJob Controller；
- Node Controller；
- EndpointSlice Controller；
- ServiceAccount Controller。

控制器核心逻辑：

```text
watch 资源变化 -> 对比 spec 和 status -> 修正实际状态
```

例子：

- Deployment 控制器维护 ReplicaSet；
- ReplicaSet 控制器维护 Pod 副本数；
- Node 控制器监控节点状态；
- Job 控制器确保任务成功完成指定次数。

---

### 4.5 cloud-controller-manager

`cloud-controller-manager` 用于将 Kubernetes 与云厂商能力解耦。

常见能力：

- 云主机节点管理；
- 云负载均衡器；
- 云盘挂载；
- 路由配置。

在云上部署 Kubernetes 时，Service 类型为 `LoadBalancer` 往往会通过它调用云厂商接口创建负载均衡实例。

---

## 5. 工作节点组件

### 5.1 kubelet

`kubelet` 运行在每个 Node 上，是节点代理。

职责：

- 向 API Server 注册节点；
- 监听分配到本节点的 Pod；
- 调用容器运行时创建、启动、停止容器；
- 执行健康检查；
- 上报 Pod 和 Node 状态；
- 挂载 Volume；
- 管理 Pod 生命周期。

面试重点：

- kubelet 不负责调度；
- kubelet 根据 API Server 中绑定到本节点的 PodSpec 来创建容器；
- kubelet 通过 CRI 与容器运行时交互。

---

### 5.2 kube-proxy

`kube-proxy` 负责实现 Service 的网络转发。

它会监听 Service 和 EndpointSlice 的变化，并在节点上维护转发规则。

常见模式：

- userspace：早期模式，性能较差；
- iptables：常见模式，通过 iptables 规则转发；
- IPVS：适合大规模 Service，性能和调度算法更强；
- eBPF：部分 CNI 或发行版使用 eBPF 替代 kube-proxy。

面试重点：

- Service 本身是一个抽象概念；
- Service 的 ClusterIP 通常不是一个真实网卡 IP；
- kube-proxy 通过内核转发规则把访问 Service 的流量转发到后端 Pod。

---

### 5.3 Container Runtime

容器运行时负责真正创建和管理容器。

常见运行时：

- containerd；
- CRI-O；
- Docker Engine，较新版本 Kubernetes 不再直接内置 dockershim。

Kubernetes 通过 CRI，即 Container Runtime Interface，与运行时交互。

面试常问：为什么 Kubernetes 后来移除了 dockershim？

答：Kubernetes 标准化了 CRI 接口，而 Docker Engine 并不直接实现 CRI，需要中间适配层 dockershim。移除 dockershim 是为了减少维护成本，让 Kubernetes 直接对接符合 CRI 标准的运行时，如 containerd 和 CRI-O。注意：这并不意味着 Docker 构建的镜像不能在 Kubernetes 中运行，因为镜像格式仍然兼容 OCI 标准。

---

## 6. Pod

### 6.1 Pod 是什么

Pod 是 Kubernetes 中最小的可调度和部署单元。

一个 Pod 可以包含一个或多个容器，这些容器共享：

- 网络命名空间；
- Pod IP；
- 端口空间；
- 存储卷；
- 生命周期管理。

通常一个 Pod 中只放一个主业务容器。如果多个容器需要强绑定、共享网络和存储，并且生命周期一致，可以放在同一个 Pod 中。

---

### 6.2 为什么 Kubernetes 不直接调度容器，而是调度 Pod

原因：

1. Pod 可以封装一组紧密协作的容器；
2. Pod 内容器共享网络和存储，便于 sidecar 模式；
3. Pod 是更适合编排的抽象，可以统一管理生命周期；
4. 容器本身更偏运行时概念，Pod 更偏调度和应用部署概念。

典型 sidecar 场景：

- 日志采集容器；
- 服务网格代理，如 Envoy；
- 配置热更新容器；
- 数据同步容器。

---

### 6.3 Pod 生命周期

常见阶段：

- `Pending`：Pod 已创建，但还没有全部容器运行起来，可能正在调度或拉镜像；
- `Running`：Pod 已绑定节点，至少一个容器正在运行；
- `Succeeded`：所有容器正常退出，且不会重启；
- `Failed`：所有容器退出，至少一个容器失败退出；
- `Unknown`：无法获取 Pod 状态，通常是节点通信异常。

容器状态：

- `Waiting`；
- `Running`；
- `Terminated`。

常见等待原因：

- `ImagePullBackOff`：镜像拉取失败并进入退避重试；
- `ErrImagePull`：镜像拉取错误；
- `CrashLoopBackOff`：容器反复崩溃重启；
- `CreateContainerConfigError`：容器配置错误；
- `ContainerCreating`：容器创建中。

---

### 6.4 Pod 重启策略

`restartPolicy` 支持：

- `Always`：总是重启，Deployment 默认使用；
- `OnFailure`：失败时重启，Job 常用；
- `Never`：不重启。

注意：Deployment 管理的 Pod 通常要求 `restartPolicy: Always`。

---

### 6.5 Init Container

Init Container 是在业务容器启动前运行的初始化容器。

特点：

- 按顺序执行；
- 前一个成功后才会执行下一个；
- 全部成功后才启动主容器；
- 适合做初始化检查和准备工作。

常见用途：

- 等待依赖服务可用；
- 初始化配置文件；
- 执行数据库迁移；
- 拉取启动前所需数据。

---

### 6.6 健康检查

Kubernetes 支持三种 Probe：

#### livenessProbe

用于判断容器是否还活着。如果失败，kubelet 会重启容器。

适合处理：

- 应用死锁；
- 进程还在但无法响应；
- 内部状态不可恢复。

#### readinessProbe

用于判断容器是否可以接收流量。如果失败，Pod 会从 Service 后端摘除，但容器不会被重启。

更准确地说，readinessProbe 判断的是：

```text
这个 Pod 当前是否已经准备好接收新流量
```

容器处于 Running 状态，不代表应用已经可以正常处理业务请求。比如 Java 服务启动时可能还在加载配置、连接数据库、初始化缓存、预热线程池，这时进程已经启动，但业务接口还不能稳定响应。

所以新 Pod 启动时，通常流程是：

```text
Pod 启动
  -> 容器 Running
  -> readinessProbe 还没通过
  -> Pod Ready=False
  -> 不加入 Service Endpoint
  -> readinessProbe 通过
  -> Pod Ready=True
  -> 加入 Service Endpoint
  -> 开始接流量
```

因此，对于还在启动中的 Pod，readinessProbe 失败并不是异常，而是表示：这个 Pod 暂时还没资格接收流量。它本来就不会被加入 Service 后端。

已经正常接流量的 Pod，如果后续 readinessProbe 失败，才更像是“从 Service 后端摘除”：

```text
Pod Ready=True
  -> Service Endpoint 中有这个 Pod
  -> readinessProbe 失败
  -> Pod Ready=False
  -> EndpointSlice 标记该 Pod 不可用
  -> Service 不再转发新流量给它
```

但容器不会被重启，因为 readinessProbe 不负责判断“容器是否需要重启”，这是 livenessProbe 的职责。

适合处理：

- 应用启动慢；
- 依赖服务未准备好；
- 临时过载，不希望继续接流量；
- 应用暂时不可对外服务，但仍有机会自行恢复。

慢启动应用可以设置合理的 `initialDelaySeconds`、`periodSeconds`、`failureThreshold`，或者配合 startupProbe 使用，避免应用还在启动阶段时被错误地重启。

#### startupProbe

用于判断应用是否启动完成。启动探针成功前，liveness 和 readiness 可以被延后，避免慢启动应用被误杀。

面试常问：livenessProbe 和 readinessProbe 的区别？

答：

- livenessProbe 失败会导致容器重启；
- readinessProbe 失败只会让 Pod 暂时不接收 Service 流量；
- liveness 关注“是否需要重启”，readiness 关注“是否可以接流量”。

---

### 6.7 Pod 优雅终止

Pod 删除时并不是立刻杀死容器，而是：

1. Pod 进入 Terminating；
2. 从 Service Endpoint 中摘除；
3. 执行 `preStop` Hook；
4. 向容器主进程发送 SIGTERM；
5. 等待 `terminationGracePeriodSeconds`；
6. 超时后发送 SIGKILL 强制终止。

优化建议：

- 应用正确处理 SIGTERM；
- readinessProbe 失败后停止接新流量；
- 设置合理的优雅终止时间；
- preStop 中可以 sleep 几秒等待流量摘除传播。

---

## 7. Label、Selector、Annotation

### 7.1 Label

Label 是资源对象上的键值对，用于组织和选择资源。

示例：

```yaml
metadata:
  labels:
    app: user-service
    env: prod
```

常见用途：

- Service 选择后端 Pod；
- Deployment 管理 Pod；
- 按环境、版本、业务线筛选资源；
- 调度亲和性匹配。

---

### 7.2 Selector

Selector 用于根据 Label 选择资源。

常见类型：

- 等值选择：`app=user-service`；
- 集合选择：`env in (prod, staging)`。

Service 示例：

```yaml
selector:
  app: user-service
```

表示该 Service 会把流量转发到带有 `app=user-service` 标签的 Pod。

---

### 7.3 Annotation

Annotation 也是键值对，但通常用于存放非选择性的附加信息。

常见用途：

- Ingress 控制器配置；
- 监控采集配置；
- 发布工具元数据；
- 说明、备注；
- 外部系统集成信息。

Label 和 Annotation 区别：

- Label 用于选择和分组；
- Annotation 用于存储附加元数据；
- Label 应简洁稳定，Annotation 可以更复杂。

---

### 7.4 综合示例

下面是一个 Deployment YAML，其中同时用到了 Label、Selector 和 Annotation：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  labels:                 # Label：给 Deployment 自身打标签
    app: user-service
    env: prod
spec:                     # 第 1 层 spec：Deployment 的期望状态
  replicas: 3
  selector:
    matchLabels:          # Selector：选择哪些 Pod 归这个 Deployment 管
      app: user-service
  template:
    metadata:
      labels:             # Label：给 Pod 打标签
        app: user-service
      annotations:        # Annotation：附加信息，例如 Prometheus 采集配置
        prometheus.io/scrape: "true"
    spec:                 # 第 2 层 spec：Pod 内部容器的具体配置
      containers:
        - name: user-service
          image: user-service:v1
```

注意：

- `metadata.labels` 是给 Deployment 自身打的标签；
- `spec.selector.matchLabels` 必须和 `template.metadata.labels` 匹配，否则 Deployment 找不到自己创建的 Pod；
- `template.metadata.annotations` 是给 Pod 添加的附加信息，不用于选择；
- 这里有两个 `spec`，外层 `spec` 描述 Deployment 如何管理应用，内层 `template.spec` 描述每个 Pod 内部如何运行容器。

---

## 8. Namespace

Namespace 用于在同一集群中进行逻辑隔离。

常见用途：

- 按环境隔离：dev、test、prod；
- 按团队隔离；
- 按业务线隔离；
- 配合 RBAC 控制权限；
- 配合 ResourceQuota 限制资源。

注意：Namespace 不是强安全隔离，不等同于虚拟集群。网络层是否隔离取决于 NetworkPolicy 和 CNI 插件支持。

常见命名空间：

- `default`：默认命名空间；
- `kube-system`：系统组件；
- `kube-public`：公开资源；
- `kube-node-lease`：节点心跳租约。

---

## 9. Deployment

### 9.1 Deployment 是什么

Deployment 用于管理无状态应用，支持：

- 副本数维护；
- 滚动更新；
- 回滚；
- 扩缩容；
- 自愈。

Deployment 并不直接管理 Pod，而是通过 ReplicaSet 间接管理 Pod。

层级关系：

```text
Deployment -> ReplicaSet -> Pod
```

---

### 9.2 Deployment 滚动更新

Deployment 更新镜像或模板后，会创建新的 ReplicaSet，同时逐步缩容旧 ReplicaSet，扩容新 ReplicaSet。

关键参数：

- `maxSurge`：滚动更新过程中最多可以额外创建多少 Pod；
- `maxUnavailable`：滚动更新过程中最多允许多少 Pod 不可用。

例如：

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

表示更新过程中最多额外多 1 个 Pod，并且不允许可用副本减少。

---

### 9.3 Deployment 回滚

Deployment 会保留历史 ReplicaSet，可以回滚到旧版本。

常用命令：

```bash
kubectl rollout history deployment nginx
kubectl rollout undo deployment nginx
kubectl rollout status deployment nginx
```

面试重点：回滚本质上是把 PodTemplate 回退到之前版本，由 Deployment 控制器重新调谐 ReplicaSet。

---

## 10. ReplicaSet

ReplicaSet 负责保证指定数量的 Pod 副本运行。

功能：

- 根据 selector 选择 Pod；
- 副本不足时创建 Pod；
- 副本过多时删除 Pod。

实际使用中很少直接创建 ReplicaSet，通常通过 Deployment 间接使用。

---

## 11. StatefulSet

### 11.1 StatefulSet 是什么

StatefulSet 用于管理有状态应用。

特点：

- Pod 名称稳定；
- 网络标识稳定；
- 存储稳定；
- 有序部署；
- 有序扩缩容；
- 有序滚动更新。

Pod 名称示例：

```text
mysql-0
mysql-1
mysql-2
```

适用场景：

- MySQL；
- Redis Cluster；
- ZooKeeper；
- Kafka；
- Elasticsearch。

---

### 11.2 StatefulSet 和 Deployment 的区别

| 对比项 | Deployment | StatefulSet |
|---|---|---|
| 应用类型 | 无状态应用 | 有状态应用 |
| Pod 名称 | 随机 | 稳定、有序 |
| 网络标识 | 不稳定 | 稳定 |
| 存储 | 通常共享或临时 | 每个 Pod 独立稳定存储 |
| 扩缩容顺序 | 无严格顺序 | 有序 |
| 典型场景 | Web 服务、API 服务 | 数据库、中间件集群 |

---

## 12. DaemonSet

DaemonSet 用于确保每个或部分 Node 上运行一个 Pod。

典型场景：

- 日志采集 Agent，如 Fluent Bit；
- 监控 Agent，如 Node Exporter；
- 网络插件，如 CNI Agent；
- 存储插件；
- 安全 Agent。

特点：

- 新节点加入时自动创建 Pod；
- 节点删除时对应 Pod 被清理；
- 可通过 nodeSelector、亲和性、污点容忍限制运行范围。

---

## 13. Job 和 CronJob

### 13.1 Job

Job 用于运行一次性任务，确保任务成功完成指定次数。

适用场景：

- 数据迁移；
- 批处理任务；
- 离线计算；
- 一次性脚本。

关键字段：

- `completions`：需要成功完成的次数；
- `parallelism`：并发执行数量；
- `backoffLimit`：失败重试次数。

---

### 13.2 CronJob

CronJob 用于定时创建 Job。

适用场景：

- 定时备份；
- 定时清理；
- 定时报表；
- 定时同步数据。

关键字段：

- `schedule`：Cron 表达式；
- `concurrencyPolicy`：并发策略；
- `successfulJobsHistoryLimit`：成功任务保留数量；
- `failedJobsHistoryLimit`：失败任务保留数量。

并发策略：

- `Allow`：允许并发；
- `Forbid`：禁止并发；
- `Replace`：新任务替换旧任务。

---

## 14. ConfigMap 和 Secret

### 14.1 ConfigMap

ConfigMap 用于保存非敏感配置。

使用方式：

- 环境变量；
- 命令行参数；
- 挂载为配置文件；
- 应用启动时读取。

适合保存：

- 配置项；
- 配置文件；
- URL；
- 开关参数。

---

### 14.2 Secret

Secret 用于保存敏感信息。

适合保存：

- 密码；
- Token；
- 证书；
- 私钥；
- 镜像仓库凭证。

注意：Kubernetes 默认 Secret 只是 base64 编码，不等于加密。生产环境应启用 etcd 加密、严格 RBAC、最小权限访问。

---

### 14.3 ConfigMap 和 Secret 的区别

| 对比项 | ConfigMap | Secret |
|---|---|---|
| 用途 | 非敏感配置 | 敏感配置 |
| 编码 | 明文 | base64 编码 |
| 安全性 | 普通资源 | 可配合加密和权限控制 |
| 使用方式 | 环境变量、文件挂载 | 环境变量、文件挂载 |

---

## 15. ServiceAccount

ServiceAccount 是 Pod 内访问 Kubernetes API 的身份。

每个 Pod 默认会绑定一个 ServiceAccount。如果 Pod 内程序需要访问 API Server，应为其配置合适的 ServiceAccount，并通过 RBAC 授权。

面试重点：

- UserAccount 面向人；
- ServiceAccount 面向运行在集群内的程序；
- ServiceAccount 需要配合 Role、ClusterRole、RoleBinding、ClusterRoleBinding 使用。

---

## 16. 常见基础面试题

### 16.1 Kubernetes 创建 Pod 的完整流程是什么

答：

1. 用户通过 kubectl 或客户端向 API Server 提交资源；
2. API Server 进行认证、鉴权、准入控制和校验；
3. API Server 将 Pod 数据写入 etcd；
4. scheduler 监听到未绑定 Node 的 Pod；
5. scheduler 经过过滤和打分选择合适 Node；
6. scheduler 将调度结果写回 API Server；
7. 目标 Node 上的 kubelet 监听到该 Pod 被分配给自己；
8. kubelet 调用容器运行时拉取镜像并创建容器；
9. CNI 插件为 Pod 配置网络；
10. kubelet 挂载 Volume；
11. kubelet 启动容器并执行健康检查；
12. kubelet 持续上报 Pod 状态。

---

### 16.2 Pod 和容器有什么区别

答：Pod 是 Kubernetes 的最小调度单元，容器是实际运行进程的隔离环境。一个 Pod 可以包含一个或多个容器，同一个 Pod 内的容器共享网络、存储和生命周期。Kubernetes 调度的是 Pod，不是单个容器。

---

### 16.3 Deployment、ReplicaSet、Pod 的关系是什么

答：Deployment 管理 ReplicaSet，ReplicaSet 管理 Pod。Deployment 负责声明应用版本、滚动更新、回滚等高级能力；ReplicaSet 负责维持 Pod 副本数；Pod 是实际运行容器的实例。

---

### 16.4 Kubernetes 如何实现自愈

答：Kubernetes 通过控制器和 kubelet 实现自愈：

- 容器异常退出时，kubelet 根据 restartPolicy 重启容器；
- Pod 被删除或节点异常时，ReplicaSet/Deployment 控制器会创建新 Pod；
- readinessProbe 失败时，Pod 会从 Service 后端摘除；
- livenessProbe 失败时，kubelet 会重启容器；
- Node 异常时，Node Controller 会标记节点不可用，并在合适条件下驱逐 Pod。

---

### 16.5 Kubernetes 中 etcd 损坏会有什么影响

答：etcd 保存集群状态，如果 etcd 不可用，API Server 无法正常读写集群资源，新的创建、更新、删除操作会失败，控制器也无法正常调谐。但已运行在节点上的 Pod 通常不会立即停止。生产环境必须定期备份 etcd，并保证 etcd 高可用部署。

---

## 17. Deployment 部署流程与控制器监听机制

### 17.1 在 Kubernetes 中部署一个 Deployment 的完整流程是什么

答：部署 Deployment 的本质是：用户向 Kubernetes 提交应用的期望状态，由 Kubernetes 控制器、调度器和节点组件协作，把这个期望状态变成真实运行的 Pod，并持续保证实际状态接近期望状态。

完整流程如下：

```text
kubectl apply -f deployment.yaml
        |
        v
API Server 接收请求
        |
        v
认证 / 鉴权 / 准入控制 / 参数校验
        |
        v
写入 etcd，保存 Deployment 期望状态
        |
        v
Deployment Controller 监听到 Deployment 变化
        |
        v
创建或更新 ReplicaSet
        |
        v
ReplicaSet Controller 创建 Pod
        |
        v
Scheduler 为 Pending Pod 选择合适 Node
        |
        v
Kubelet 在目标 Node 上拉取镜像并启动容器
        |
        v
CNI 为 Pod 配置网络
        |
        v
Pod Running
        |
        v
Service / Ingress 提供访问入口
```

关键步骤说明：

1. 用户先将应用打包成容器镜像，并推送到镜像仓库。
2. 编写 Deployment YAML，声明副本数、镜像、端口、标签、更新策略等信息。
3. 通过 `kubectl apply` 把 YAML 提交给 API Server。
4. API Server 完成认证、鉴权、准入控制和字段校验后，将 Deployment 对象写入 etcd。
5. Deployment Controller 监听到新的 Deployment 对象后，创建对应的 ReplicaSet。
6. ReplicaSet Controller 根据期望副本数创建 Pod。
7. Scheduler 监听到未绑定 Node 的 Pod 后，为其选择合适节点。
8. 目标 Node 上的 kubelet 监听到该 Pod 被分配给自己后，调用容器运行时拉取镜像、创建并启动容器。
9. CNI 插件为 Pod 配置网络，使 Pod 拥有独立 IP。
10. 如果需要稳定访问入口，通常再创建 Service；如果需要集群外访问，通常再创建 Ingress。

Deployment、ReplicaSet、Pod 的层级关系是：

```text
Deployment
  └── ReplicaSet
        ├── Pod
        ├── Pod
        └── Pod
```

其中：

- Deployment 负责声明式部署、滚动更新、回滚和版本管理；
- ReplicaSet 负责保证指定数量的 Pod 副本存在；
- Pod 是真正承载业务容器的运行实例。

---

### 17.2 Deployment Controller 怎么监听到有新的 Deployment

答：Deployment Controller 不是直接扫描 etcd，也不是定时全量轮询所有 Deployment，而是通过 Kubernetes 的 List-Watch 机制监听 API Server 中 Deployment 资源的变化。

核心链路如下：

```text
用户创建 Deployment
        |
        v
API Server 写入 etcd
        |
        v
Deployment Controller 通过 watch 监听 API Server
        |
        v
收到 ADDED / MODIFIED / DELETED 事件
        |
        v
事件进入 WorkQueue
        |
        v
Controller Worker 执行 reconcile
```

需要注意：

- Deployment Controller 运行在 kube-controller-manager 中；
- 它监听的是 API Server，不是直接监听 etcd；
- API Server 负责屏蔽 etcd 细节，并统一处理认证、鉴权、版本转换和资源校验；
- 控制器监听到事件后，不是简单地按事件做一次操作，而是执行 reconcile 调谐逻辑。

List-Watch 通常分为两步：

1. `List`：控制器启动时先全量获取当前已有的 Deployment，并记录当前的 `resourceVersion`；
2. `Watch`：从该 `resourceVersion` 开始持续监听后续新增、修改、删除事件。

`resourceVersion` 可以理解为 Kubernetes 资源变化的版本号。Watch 连接中断后，控制器可以基于上次看到的 `resourceVersion` 继续监听，尽量避免事件丢失。

---

### 17.3 Informer 是控制器里的内存缓存吗

答：Informer 可以理解为控制器中的“资源监听机制 + 本地缓存 + 事件分发器”，它不只是一个普通内存缓存。

Informer 的核心职责包括：

- 通过 List-Watch 从 API Server 同步资源对象；
- 将资源对象保存到本地缓存，也就是 Store / Indexer；
- 在对象新增、修改、删除时触发事件回调；
- 支持控制器通过本地缓存读取资源，减少对 API Server 的直接访问；
- 支持多个控制器共享缓存，即 SharedInformer。

更完整的内部流程可以表示为：

```text
API Server
   |
   | List / Watch
   v
Reflector
   |
   v
DeltaFIFO
   |
   v
Informer
   |
   | 更新本地缓存 Store / Indexer
   | 触发 AddFunc / UpdateFunc / DeleteFunc
   v
WorkQueue
   |
   v
Controller Worker
   |
   v
Reconcile
```

所以，“先监听，然后存到 Informer，然后再到队列执行”这个理解大方向是对的，但更准确的说法是：

1. 控制器通过 Informer 的 List-Watch 机制监听 API Server；
2. Informer 将监听到的资源对象同步到本地缓存；
3. Informer 触发 AddFunc、UpdateFunc、DeleteFunc 等事件回调；
4. 事件回调通常不会直接处理业务逻辑，而是把对象的 key 放入 WorkQueue；
5. Controller Worker 从 WorkQueue 中取出 key；
6. Worker 再通过 Lister 从 Informer 本地缓存读取对象的最新状态；
7. 最后执行 reconcile，对比期望状态和实际状态，并修正差异。

队列里通常放的不是完整对象，而是对象 key，例如：

```text
namespace/name
```

例如：

```text
default/my-app
```

这样做的原因是：事件发生时的对象可能已经不是最新版本了。Worker 真正处理时，会根据 key 再去 Informer 缓存中读取最新对象，避免使用过期数据。

Informer 缓存和 WorkQueue 的区别如下：

| 组件 | 作用 |
|---|---|
| Informer / Store / Indexer | 保存资源对象的最新状态 |
| EventHandler | 处理资源新增、更新、删除事件的回调 |
| WorkQueue | 保存待处理对象的 key，并支持削峰、重试、去重 |
| Worker | 从队列取出 key，执行具体处理逻辑 |
| Reconcile | 对比期望状态和实际状态，修正差异 |

一句话总结：Deployment Controller 通过 Informer 监听 API Server 中 Deployment 的变化，Informer 更新本地缓存并触发事件回调，事件回调将对象 key 放入 WorkQueue，最后由 Controller Worker 取出 key 执行 reconcile，从而创建或更新 ReplicaSet。

---

## 18. Service、Ingress 与底层转发机制

### 18.1 Service 是怎么工作的

答：Service 用来解决集群内部如何稳定访问一组 Pod 的问题。

Deployment 创建出来的 Pod IP 是不稳定的。Pod 可能因为重建、扩缩容、节点迁移而发生变化。如果其他服务直接访问 Pod IP，就会遇到地址变化、后端实例增减、负载均衡等问题。

Service 会通过 selector 选择一组 Pod，并提供一个稳定的虚拟访问入口。

示例：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: user-service-svc
spec:
  type: ClusterIP
  selector:
    app: user-service
  ports:
    - port: 80
      targetPort: 8080
```

这个 Service 表示：

```text
访问 user-service-svc:80
会被转发到 app=user-service 的 Pod 的 8080 端口
```

后端关系可以理解为：

```text
Service: user-service-svc
        |
        | selector: app=user-service
        v
Pod 1: app=user-service, 10.244.1.11:8080
Pod 2: app=user-service, 10.244.2.23:8080
Pod 3: app=user-service, 10.244.3.17:8080
```

访问链路：

```text
其他 Pod
  |
  | http://user-service-svc
  v
Service ClusterIP
  |
  v
kube-proxy / iptables / IPVS / eBPF 转发
  |
  v
某一个后端 Pod
```

Service 主要解决三个问题：

1. Pod IP 不稳定：调用方不需要感知后端 Pod 的创建、销毁和 IP 变化；
2. 多副本负载均衡：一个 Service 后面可以挂多个 Pod；
3. 服务发现：Kubernetes 会为 Service 生成 DNS 名称，例如 `user-service-svc.default.svc.cluster.local`。

常见 Service 类型：

| 类型 | 作用 |
|---|---|
| ClusterIP | 默认类型，只能在集群内部访问 |
| NodePort | 在每个节点上暴露一个端口，可以从集群外访问 |
| LoadBalancer | 结合云厂商负载均衡器暴露服务 |
| ExternalName | 将 Service 映射到外部 DNS 名称 |

---

### 18.2 Ingress 是怎么工作的

答：Ingress 用来解决集群外部 HTTP/HTTPS 流量如何进入集群的问题。

Service 主要面向集群内部访问。如果用户在浏览器访问公网域名，例如：

```text
https://api.example.com/users
```

这个请求来自集群外部，需要有一个统一入口把 HTTP/HTTPS 流量转发到集群内部的 Service，这就是 Ingress 的作用。

Ingress 示例：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: user-service-ingress
spec:
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /users
            pathType: Prefix
            backend:
              service:
                name: user-service-svc
                port:
                  number: 80
```

这个配置表示：

```text
访问 https://api.example.com/users
转发到 user-service-svc:80
再由 Service 转发到后端 Pod:8080
```

完整链路：

```text
浏览器 / 客户端
   |
   | https://api.example.com/users
   v
Ingress Controller
   |
   | 根据 host + path 匹配 Ingress 规则
   v
Service: user-service-svc:80
   |
   | Service 负载均衡
   v
Pod: user-service-xxx:8080
```

注意：Ingress 本身只是一份路由规则，真正处理流量的是 Ingress Controller，例如 Nginx Ingress Controller、Traefik、HAProxy Ingress 或云厂商提供的 Ingress Controller。

Ingress 主要解决：

- HTTP/HTTPS 外部访问入口；
- 基于域名转发；
- 基于路径转发；
- TLS 证书配置；
- 多个服务共用一个公网入口。

---

### 18.3 Service 和 Ingress 发挥作用的例子

假设有一个电商系统，包含三个服务：

```text
user-service       用户服务
order-service      订单服务
product-service    商品服务
```

每个服务都通过 Deployment 部署多个 Pod：

```text
user-service:    3 个 Pod
order-service:   3 个 Pod
product-service: 3 个 Pod
```

Service 解决内部服务调用问题。

例如 order-service 创建订单时需要调用 user-service 查询用户信息。如果没有 Service，order-service 可能要直接记录 user-service 后面多个 Pod 的 IP，这些 IP 会随着 Pod 重建而变化，非常不稳定。

有了 Service 后，order-service 只需要访问：

```text
http://user-service-svc
```

内部调用链路：

```text
order-service Pod
   |
   | http://user-service-svc
   v
user-service Service
   |
   v
某个 user-service Pod
```

Ingress 解决外部用户访问问题。

用户在浏览器访问：

```text
https://mall.example.com/users/profile
https://mall.example.com/orders/123
https://mall.example.com/products/10086
```

可以通过一个 Ingress 做路径转发：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: mall-ingress
spec:
  rules:
    - host: mall.example.com
      http:
        paths:
          - path: /users
            pathType: Prefix
            backend:
              service:
                name: user-service-svc
                port:
                  number: 80
          - path: /orders
            pathType: Prefix
            backend:
              service:
                name: order-service-svc
                port:
                  number: 80
          - path: /products
            pathType: Prefix
            backend:
              service:
                name: product-service-svc
                port:
                  number: 80
```

访问效果：

```text
https://mall.example.com/users    -> user-service-svc    -> user-service Pod
https://mall.example.com/orders   -> order-service-svc   -> order-service Pod
https://mall.example.com/products -> product-service-svc -> product-service Pod
```

所以，在这个例子中：

- Service 解决服务之间通过稳定名称互相调用的问题；
- Ingress 解决外部用户通过统一域名访问多个服务的问题。

---

### 18.4 iptables、IPVS、eBPF 分别是什么

Service 本身只是 Kubernetes 中的抽象对象，真正的数据转发需要底层网络规则或数据面能力完成。常见实现包括 iptables、IPVS 和 eBPF。

三者从抽象上看都可以理解为维护一张映射关系：

```text
ServiceIP:Port -> PodIP:TargetPort
```

例如：

```text
10.96.0.100:80
  -> 10.244.1.10:8080
  -> 10.244.2.20:8080
  -> 10.244.3.30:8080
```

但它们真正的区别在于：

- 这张映射关系存在哪里；
- 请求如何查找这张映射关系；
- 匹配和更新成本是多少；
- 能否支持更复杂的网络能力。

---

#### 18.4.1 iptables 模式

iptables 是 Linux 内核中基于 netfilter 的包过滤和 NAT 机制。

在 Kubernetes 中，iptables 模式的核心是：kube-proxy 把 Service 和 Endpoint 转换成 iptables NAT 规则，通过 DNAT 将访问 ClusterIP 的流量转发到后端 Pod。

可以类比为一长串 if/else 规则：

```text
if 目标是 10.96.0.100:80:
    按概率转发到 Pod A
else if 目标是 10.96.0.101:80:
    按概率转发到 Pod B
else if 目标是 10.96.0.102:80:
    ...
```

请求链路：

```text
Client Pod
   |
   | 访问 10.96.0.100:80
   v
iptables 规则匹配 Service IP
   |
   | DNAT
   v
10.244.2.20:8080
```

特点：

- 成熟稳定；
- 实现简单；
- 小规模集群下够用；
- 大规模 Service 和 Endpoint 场景下规则数量多，匹配和更新成本较高；
- 负载均衡能力相对简单，通常是基于概率的随机分发。

一句话总结：iptables 模式是用防火墙 NAT 规则模拟 Service 转发，简单稳定，但大规模下规则会膨胀。

---

#### 18.4.2 IPVS 模式

IPVS 是 Linux 内核中的四层负载均衡模块，也是 LVS 的核心能力。

在 Kubernetes 中，IPVS 模式的核心是：kube-proxy 使用内核 ip_vs 模块维护虚拟服务和真实后端。

它更像一张专门的负载均衡表：

```text
Virtual Server:
  10.96.0.100:80

Real Servers:
  10.244.1.10:8080
  10.244.2.20:8080
  10.244.3.30:8080
```

请求链路：

```text
Client Pod
   |
   | 访问 10.96.0.100:80
   v
IPVS 虚拟服务
   |
   | 根据负载均衡算法选择后端
   v
某一个 Pod
```

IPVS 支持多种负载均衡算法：

| 算法 | 含义 |
|---|---|
| rr | Round Robin，轮询 |
| wrr | Weighted Round Robin，加权轮询 |
| lc | Least Connection，最少连接 |
| wlc | Weighted Least Connection，加权最少连接 |
| sh | Source Hash，源地址哈希 |
| dh | Destination Hash，目标地址哈希 |

特点：

- 专门为四层负载均衡设计；
- 大规模 Service 下性能通常优于 iptables；
- 支持更丰富的负载均衡算法；
- 依赖 Linux 内核 ip_vs 模块；
- 实际使用中仍可能配合少量 iptables 规则处理流量拦截、NodePort、SNAT 等场景。

一句话总结：IPVS 模式是用 Linux 内核原生四层负载均衡能力实现 Service 转发，比 iptables 更适合大规模集群。

---

#### 18.4.3 eBPF 模式

eBPF 是 Linux 内核中的可编程技术，允许在内核特定挂载点运行安全的程序，用来处理网络包、系统调用、追踪和安全策略等。

在 Kubernetes 网络中，eBPF 可以用于：

- Service 负载均衡；
- 网络策略；
- 可观测性；
- 安全控制；
- 跨节点转发；
- 替代部分或全部 kube-proxy 能力。

典型代表是 Cilium。

eBPF 通常会通过 BPF Map 保存映射关系：

```text
Service Map:
  10.96.0.100:80 -> service_id=1001

Backend Map:
  service_id=1001 ->
    10.244.1.10:8080
    10.244.2.20:8080
    10.244.3.30:8080
```

但 eBPF 的重点不只是有一张表，而是“表 + 内核可编程逻辑”。

示意逻辑：

```text
if packet.dst == ServiceIP:
    backend = choose_backend(service_map)
    if policy_allows(packet, backend):
        record_metrics(packet)
        forward(packet, backend)
    else:
        drop(packet)
```

请求链路：

```text
请求到达节点
   |
   v
eBPF 程序在内核网络路径上拦截
   |
   v
查询 BPF Map
   |
   v
选择后端 Pod
   |
   v
直接转发到目标 Pod
```

特点：

- 可以在更靠近内核网络路径的位置处理包；
- 性能和扩展能力强；
- 可以同时承载转发、网络策略、可观测性、安全等能力；
- 部分方案可以替代 kube-proxy；
- 依赖较新的 Linux 内核能力，学习和排障门槛更高。

一句话总结：eBPF 模式是在内核中运行可编程网络逻辑，通过 BPF Map 做 Service 转发，同时还能扩展网络策略、可观测性和安全能力。

---

### 18.5 iptables、IPVS、eBPF 的核心区别

虽然三者看起来都是维护 Service 到后端 Pod 的映射关系，但区别在底层实现方式。

```text
iptables：把映射关系展开成一串规则，让数据包逐条匹配
IPVS：把映射关系放进内核负载均衡表，按虚拟服务查表
 eBPF：把映射关系放进 BPF Map，由内核中的自定义程序直接处理
```

对比表：

| 对比项 | iptables | IPVS | eBPF |
|---|---|---|---|
| 实现基础 | netfilter 规则 | Linux 内核 ip_vs 模块 | Linux 内核可编程能力 |
| 转发方式 | 规则链匹配 + DNAT | 虚拟服务表 + 真实后端 | BPF 程序 + BPF Map |
| 类比 | 一长串 if/else | 专用负载均衡哈希表 | 内核里运行的可编程转发程序 |
| 性能 | 小规模够用，大规模较弱 | 大规模更好 | 通常更强 |
| 负载均衡算法 | 相对简单，随机概率 | 丰富，如 rr、lc、sh | 取决于 CNI 实现 |
| 规则更新成本 | 较高 | 较低 | 较低 |
| 可观测性 | 一般 | 一般 | 很强 |
| 排障难度 | 中等 | 中等偏高 | 较高 |
| 典型组件 | kube-proxy iptables 模式 | kube-proxy IPVS 模式 | Cilium 等 CNI |
| 是否可替代 kube-proxy | 否 | 否 | 可以，取决于实现 |

面试回答可以这样组织：

> 从抽象上看，iptables、IPVS、eBPF 都是在维护 Service 到后端 Pod 的映射关系，但区别在于底层实现方式。iptables 是把映射关系展开成一系列 NAT 规则，通过规则链匹配做 DNAT；IPVS 是使用 Linux 内核 ip_vs 模块，把 Service 抽象成虚拟服务，把 Pod 抽象成真实服务器，用专门的四层负载均衡表和算法转发；eBPF 则是在内核网络路径上运行可编程逻辑，通过 BPF Map 做查表，同时还能实现网络策略、可观测性和更灵活的数据面能力。所以它们看起来都是表映射，但性能、扩展性和能力边界不一样。
