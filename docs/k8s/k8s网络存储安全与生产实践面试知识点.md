# Kubernetes 网络、存储、安全与生产实践面试知识点

## 1. Kubernetes 网络模型

Kubernetes 对集群网络有几个基本要求：

1. 每个 Pod 都有独立 IP；
2. Pod 与 Pod 之间可以直接通信，不需要 NAT；
3. Node 与 Pod 之间可以直接通信；
4. Pod 内看到的 IP 与其他组件看到的 IP 一致。

这套模型的目标是让 Kubernetes 中的网络更接近传统虚拟机网络：每个 Pod 都像一台独立主机，有自己的 IP 地址。

---

## 2. CNI

CNI，全称 Container Network Interface，是容器网络接口规范。Kubernetes 通过 CNI 插件为 Pod 配置网络。

常见 CNI 插件：

- Flannel：简单易用，常用于入门和中小规模集群；
- Calico：支持 BGP、IPIP、VXLAN、NetworkPolicy，生产使用广泛；
- Cilium：基于 eBPF，性能强，支持可观测性和高级网络策略；
- Weave Net：早期常见方案；
- Canal：Flannel + Calico 的组合方案。

Pod 创建时，kubelet 会调用容器运行时，容器运行时再调用 CNI 插件完成网络配置，包括：

- 创建网络命名空间；
- 创建 veth pair；
- 分配 Pod IP；
- 配置路由；
- 配置网络策略。

面试常问：Kubernetes 为什么需要 CNI？

答：Kubernetes 本身不直接实现底层网络，而是定义网络模型和插件接口。不同环境可以通过不同 CNI 插件实现 Pod IP 分配、跨节点通信和网络策略，从而保持 Kubernetes 核心简单、可扩展。

---

## 3. Service

### 3.1 Service 是什么

Pod 是易变的：会重建、漂移、扩缩容，Pod IP 也会变化。Service 为一组 Pod 提供稳定访问入口。

Service 通过 Label Selector 选择后端 Pod，并提供稳定的虚拟 IP 或访问方式。

常见作用：

- 服务发现；
- 负载均衡；
- 屏蔽 Pod IP 变化；
- 提供稳定访问入口。

---

### 3.2 Service 类型

#### ClusterIP

默认类型，只能在集群内部访问。

适合：

- 微服务之间调用；
- 内部数据库、中间件访问；
- 内部 API 服务。

#### NodePort

在每个 Node 上暴露一个端口，外部可以通过 `NodeIP:NodePort` 访问服务。

特点：

- 简单直接；
- 端口范围默认通常是 30000-32767；
- 不适合大规模生产直接暴露大量服务。

#### LoadBalancer

通常用于云环境，Kubernetes 会向云厂商申请外部负载均衡器。

特点：

- 适合生产对外暴露服务；
- 依赖云厂商或 MetalLB 等实现；
- 成本相对更高。

#### ExternalName

将 Service 映射到外部 DNS 名称，不创建 ClusterIP。

适合：

- 集群内服务访问外部服务；
- 迁移过程中做域名别名；
- 屏蔽外部服务真实地址。

---

### 3.3 Service 如何转发流量

Service 依赖 kube-proxy 或等价组件维护转发规则。

大致流程：

1. 用户访问 Service 的 ClusterIP；
2. 节点上的 iptables、IPVS 或 eBPF 规则拦截流量；
3. 按负载均衡策略选择一个后端 Pod；
4. 将流量转发到目标 Pod IP。

Service 后端 Pod 列表来自 EndpointSlice。EndpointSlice 会记录 Service 对应的一组 Pod IP 和端口。

---

## 4. Ingress

### 4.1 Ingress 是什么

Ingress 用于管理 HTTP/HTTPS 七层入口流量。

它可以根据域名和路径把请求转发到不同 Service。

示例能力：

- 域名路由；
- 路径路由；
- TLS 证书终止；
- HTTP 重定向；
- 限流；
- 灰度发布；
- Header 改写。

注意：Ingress 资源本身只是规则声明，真正处理流量的是 Ingress Controller。

常见 Ingress Controller：

- Nginx Ingress Controller；
- Traefik；
- HAProxy Ingress；
- Kong Ingress；
- 云厂商 ALB Ingress Controller。

---

### 4.2 Ingress 和 Service 的区别

| 对比项 | Service | Ingress |
|---|---|---|
| 网络层级 | 四层为主 | 七层 HTTP/HTTPS |
| 作用 | 给 Pod 提供稳定访问入口 | 管理外部 HTTP 入口规则 |
| 路由能力 | 主要按 Service 转发 | 可按域名、路径转发 |
| 是否需要控制器 | kube-proxy 或等价实现 | 必须有 Ingress Controller |

---

## 5. DNS 与服务发现

Kubernetes 通常使用 CoreDNS 提供集群 DNS 服务。

Service DNS 格式：

```text
service-name.namespace.svc.cluster.local
```

例如：

```text
user-service.prod.svc.cluster.local
```

同一命名空间内可以直接使用：

```text
user-service
```

不同命名空间建议使用：

```text
user-service.prod
```

面试常问：Kubernetes 服务发现有哪些方式？

答：主要有两种：

1. 环境变量：Pod 创建时注入已有 Service 信息，但不适合动态发现；
2. DNS：通过 CoreDNS 解析 Service 名称，是更常用、更推荐的方式。

---

## 6. NetworkPolicy

NetworkPolicy 用于定义 Pod 之间或 Pod 与外部之间的网络访问控制。

它类似 Kubernetes 网络层的防火墙规则。

可以控制：

- 入站流量 Ingress；
- 出站流量 Egress；
- 哪些 Pod 可以访问；
- 哪些 Namespace 可以访问；
- 哪些 IPBlock 可以访问；
- 哪些端口可以访问。

注意：NetworkPolicy 需要 CNI 插件支持。不是所有 CNI 都完整支持。

常见面试点：默认情况下 Pod 间是否隔离？

答：默认情况下，如果没有 NetworkPolicy，Pod 之间通常是全互通的。只有当某个 Pod 被 NetworkPolicy 选中后，规则才会开始限制它的流量。

---

## 7. Kubernetes 存储模型

容器本身是临时的，Pod 删除后容器文件系统中的数据也会丢失。Kubernetes 通过 Volume 体系管理存储。

常见 Volume 类型：

- emptyDir；
- hostPath；
- configMap；
- secret；
- persistentVolumeClaim；
- projected；
- nfs；
- csi。

---

## 8. emptyDir 和 hostPath

### 8.1 emptyDir

emptyDir 是随 Pod 生命周期存在的临时目录。

特点：

- Pod 创建时创建；
- Pod 删除时数据删除；
- 同一 Pod 内多个容器可共享；
- 适合临时缓存、中间文件、Sidecar 共享数据。

### 8.2 hostPath

hostPath 将宿主机目录挂载到 Pod 中。

风险：

- 强依赖节点本地路径；
- Pod 调度到其他节点可能数据不存在；
- 可能带来安全风险；
- 容易破坏节点文件系统。

生产环境应谨慎使用 hostPath。

---

## 9. PV、PVC、StorageClass

### 9.1 PV

PersistentVolume 是集群级别的持久化存储资源，由管理员或动态供应器创建。

### 9.2 PVC

PersistentVolumeClaim 是用户对存储资源的申请。

用户不直接关心底层存储实现，只声明需要多大容量、什么访问模式、什么存储类型。

### 9.3 StorageClass

StorageClass 定义动态创建 PV 的模板。

包含：

- provisioner：存储供应器；
- parameters：供应参数；
- reclaimPolicy：回收策略；
- volumeBindingMode：绑定模式。

关系：

```text
Pod -> PVC -> PV -> 具体存储
           ↑
      StorageClass 动态创建 PV
```

---

## 10. PV 访问模式和回收策略

### 10.1 访问模式

常见访问模式：

- ReadWriteOnce，RWO：可被单个节点读写挂载；
- ReadOnlyMany，ROX：可被多个节点只读挂载；
- ReadWriteMany，RWX：可被多个节点读写挂载；
- ReadWriteOncePod，RWOP：只允许单个 Pod 读写挂载。

### 10.2 回收策略

- Retain：PVC 删除后 PV 和数据保留，需要人工处理；
- Delete：PVC 删除后 PV 和后端存储一起删除；
- Recycle：旧策略，基本废弃。

生产中有状态数据通常要谨慎选择 Delete，避免误删数据。

---

## 11. CSI

CSI，全称 Container Storage Interface，是容器存储接口标准。

Kubernetes 通过 CSI 插件对接不同存储系统，例如：

- 云盘；
- Ceph；
- NFS；
- Longhorn；
- Portworx；
- 本地盘；
- 对象存储网关。

CSI 的价值：

- Kubernetes 不需要内置各种存储驱动；
- 存储厂商可以独立开发插件；
- 存储能力可以独立升级；
- 支持快照、扩容、克隆等高级能力。

---

## 12. 资源请求与限制

Kubernetes 使用 requests 和 limits 管理资源。

### 12.1 requests

requests 表示容器需要的最低资源，主要用于调度。

scheduler 会根据 requests 判断节点资源是否足够。

### 12.2 limits

limits 表示容器最多可以使用的资源。

- CPU 超过 limit 通常会被限流；
- 内存超过 limit 可能触发 OOMKilled。

示例：

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "1"
    memory: "1Gi"
```

面试重点：

- CPU 是可压缩资源，超限通常 throttling；
- 内存是不可压缩资源，超限可能 OOM；
- requests 影响调度，limits 影响运行时限制。

---

## 13. QoS 等级

Kubernetes 根据 requests 和 limits 给 Pod 划分 QoS 等级。

### 13.1 Guaranteed

所有容器都设置 CPU 和内存 requests、limits，且 requests 等于 limits。

优先级最高，最不容易被驱逐。

### 13.2 Burstable

至少有一个容器设置了 requests 或 limits，但不满足 Guaranteed 条件。

### 13.3 BestEffort

没有设置任何 CPU 和内存 requests、limits。

资源紧张时最容易被驱逐。

面试常问：节点资源紧张时 Pod 驱逐顺序？

答：通常 BestEffort 最先被驱逐，其次 Burstable，最后 Guaranteed。同时还会考虑 Pod 实际资源使用量是否超过 requests、Pod 优先级等因素。

---

## 14. HPA、VPA、Cluster Autoscaler

### 14.1 HPA

Horizontal Pod Autoscaler，水平 Pod 自动扩缩容。

根据指标调整 Pod 副本数。

常见指标：

- CPU 使用率；
- 内存使用率；
- 自定义业务指标，如 QPS、队列长度；
- 外部指标。

### 14.2 VPA

Vertical Pod Autoscaler，垂直 Pod 自动扩缩容。

它调整 Pod 的 requests 和 limits。部分场景下需要重建 Pod 才能生效。

### 14.3 Cluster Autoscaler

集群节点自动扩缩容。

当 Pod 因资源不足无法调度时，Cluster Autoscaler 可以增加节点；当节点长期空闲时，可以缩容节点。

三者区别：

| 组件 | 调整对象 | 目标 |
|---|---|---|
| HPA | Pod 副本数 | 水平扩容应用实例 |
| VPA | Pod 资源规格 | 调整单个实例资源 |
| Cluster Autoscaler | Node 数量 | 调整集群容量 |

---

## 15. 调度机制

### 15.1 nodeSelector

最简单的节点选择方式，根据节点 Label 调度。

### 15.2 Node Affinity

节点亲和性，比 nodeSelector 表达能力更强。

支持：

- requiredDuringSchedulingIgnoredDuringExecution：硬约束；
- preferredDuringSchedulingIgnoredDuringExecution：软偏好。

### 15.3 Pod Affinity / Anti-Affinity

Pod 亲和性和反亲和性用于控制 Pod 之间的分布关系。

典型场景：

- 让应用和缓存尽量部署在同一区域；
- 让同一应用多个副本尽量分散到不同节点；
- 避免主备副本部署在同一故障域。

### 15.4 Taint 和 Toleration

Taint 是节点上的污点，表示节点排斥某些 Pod。

Toleration 是 Pod 对污点的容忍。

常见效果：

- NoSchedule：不调度新 Pod；
- PreferNoSchedule：尽量不调度；
- NoExecute：不容忍的已有 Pod 也会被驱逐。

面试常问：Taint/Toleration 和 Node Affinity 有什么区别？

答：Affinity 是 Pod 主动选择节点；Taint 是 Node 主动排斥 Pod。二者经常配合使用，例如专用节点用 Taint 防止普通 Pod 调度，再让特定 Pod 通过 Toleration 容忍并通过 Affinity 选择这些节点。

---

## 16. 安全机制

### 16.1 认证、鉴权、准入控制

Kubernetes API 请求通常经过：

```text
认证 Authentication -> 鉴权 Authorization -> 准入控制 Admission Control -> 持久化 etcd
```

认证：确认“你是谁”。

鉴权：确认“你能做什么”。

准入控制：在对象持久化前进行校验、修改或拒绝。

---

### 16.2 RBAC

RBAC，基于角色的访问控制。

核心资源：

- Role：命名空间级权限；
- ClusterRole：集群级权限；
- RoleBinding：把 Role 或 ClusterRole 绑定给用户、组或 ServiceAccount；
- ClusterRoleBinding：集群级绑定。

最佳实践：

- 最小权限原则；
- 不随意授予 cluster-admin；
- 为不同应用创建独立 ServiceAccount；
- 定期审计权限。

---

### 16.3 Secret 安全

Secret 默认是 base64 编码，不是加密。

生产建议：

- 启用 etcd 静态加密；
- 限制 Secret 的 RBAC 访问；
- 避免把 Secret 打入镜像；
- 避免在日志中输出 Secret；
- 使用外部密钥系统，如 Vault、云 KMS；
- 定期轮换密钥。

---

### 16.4 Pod 安全

常见配置：

- 禁止特权容器；
- 禁止使用 hostNetwork、hostPID、hostIPC；
- 使用只读根文件系统；
- 不以 root 用户运行；
- 设置 allowPrivilegeEscalation: false；
- 使用 seccomp、AppArmor、SELinux；
- 限制 Linux capabilities。

---

## 17. 发布策略

### 17.1 滚动发布

逐步替换旧版本 Pod，是 Deployment 默认策略。

优点：简单、资源开销较小。

缺点：新旧版本会同时存在，需要兼容数据库和接口变更。

### 17.2 蓝绿发布

同时部署蓝、绿两套环境，通过流量切换完成发布。

优点：回滚快。

缺点：资源成本高。

### 17.3 金丝雀发布

先让少量流量进入新版本，观察无问题后逐步扩大。

优点：风险低。

缺点：需要更复杂的流量治理能力。

---

## 18. 常见故障排查

### 18.1 Pod Pending

可能原因：

- 节点资源不足；
- PVC 未绑定；
- nodeSelector 或亲和性不满足；
- 污点无法容忍；
- 镜像拉取 Secret 配置问题；
- 调度器异常。

排查命令：

```bash
kubectl describe pod <pod-name>
kubectl get events --sort-by=.metadata.creationTimestamp
```

### 18.2 ImagePullBackOff

可能原因：

- 镜像名称错误；
- tag 不存在；
- 私有仓库凭证错误；
- 节点无法访问镜像仓库；
- 镜像仓库限流或异常。

### 18.3 CrashLoopBackOff

可能原因：

- 应用启动报错；
- 配置错误；
- 依赖不可用；
- livenessProbe 配置过于激进；
- 内存不足被 OOM；
- 启动命令错误。

排查思路：

```bash
kubectl logs <pod-name>
kubectl logs <pod-name> --previous
kubectl describe pod <pod-name>
```

### 18.4 Service 无法访问

排查顺序：

1. Service selector 是否匹配 Pod label；
2. EndpointSlice 是否有后端地址；
3. Pod readiness 是否通过；
4. targetPort 是否正确；
5. 容器是否真正监听端口；
6. kube-proxy 或 CNI 是否异常；
7. NetworkPolicy 是否拦截。

### 18.5 Ingress 无法访问

排查顺序：

1. DNS 是否解析到正确入口；
2. Ingress Controller 是否运行；
3. IngressClass 是否匹配；
4. Ingress 规则是否正确；
5. 后端 Service 是否正常；
6. TLS 证书是否有效；
7. Controller 日志是否报错。

---

## 19. 生产实践建议

1. 所有业务容器设置 requests 和 limits；
2. 配置 readinessProbe 和 livenessProbe；
3. 对慢启动应用配置 startupProbe；
4. 使用 Deployment 滚动发布，并设置合理 maxSurge、maxUnavailable；
5. 多副本应用配合 PodDisruptionBudget；
6. 使用反亲和性或拓扑分布约束分散副本；
7. Secret 启用加密和最小权限；
8. 核心组件和 etcd 做高可用；
9. 定期备份 etcd；
10. 使用监控和日志系统观测集群状态；
11. 谨慎使用 hostPath 和 privileged；
12. 给命名空间设置 ResourceQuota 和 LimitRange；
13. 使用 NetworkPolicy 做必要隔离；
14. 镜像使用不可变 tag 或 digest；
15. 发布前确认数据库变更兼容新旧版本。

---

## 20. 高频面试题

### 20.1 Service 的 ClusterIP 是真实 IP 吗

答：通常不是绑定在某块真实网卡上的 IP，而是一个虚拟 IP。访问 ClusterIP 的流量会被 kube-proxy 维护的 iptables、IPVS 或 eBPF 规则转发到后端 Pod。

### 20.2 readinessProbe 失败后会发生什么

答：Pod 不会被重启，但会从 Service 后端摘除，不再接收流量。探针恢复成功后，Pod 会重新加入 Service 后端。

### 20.3 livenessProbe 配置不合理有什么风险

答：如果 livenessProbe 过于激进，应用启动慢或短暂抖动时会被频繁重启，导致 CrashLoopBackOff，反而降低稳定性。慢启动应用应配置 startupProbe。

### 20.4 requests 和 limits 如何影响调度和运行

答：requests 影响调度，scheduler 根据 requests 判断节点是否有足够资源。limits 影响运行时资源上限，CPU 超限通常被限流，内存超限可能 OOMKilled。

### 20.5 如何做到 Kubernetes 零停机发布

答：需要多方面配合：

- Deployment 使用滚动更新；
- 设置 `maxUnavailable: 0` 或合理不可用比例；
- 配置 readinessProbe；
- 应用支持优雅关闭；
- 删除 Pod 前停止接收新流量；
- 数据库和接口变更保持向前向后兼容；
- 多副本部署并配合 PDB；
- 监控发布过程并支持快速回滚。
