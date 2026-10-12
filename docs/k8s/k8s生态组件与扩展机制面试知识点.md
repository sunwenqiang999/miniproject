# Kubernetes 生态组件与扩展机制面试知识点

## 1. Sidecar 模式

### 1.1 什么是 Sidecar 模式

Sidecar 模式是一种常见的容器设计模式。它把与主业务容器紧密相关但相对独立的功能，拆分成一个独立的 Sidecar 容器，和主容器运行在同一个 Pod 中。

Sidecar 容器与主容器共享：

- 网络命名空间；
- 存储卷；
- 生命周期；
- 调度位置。

### 1.2 为什么使用 Sidecar

优点：

- 职责分离：主容器只关心业务逻辑，Sidecar 负责通用能力；
- 无需修改业务代码即可获得新能力；
- 可以独立升级和复用；
- 语言无关：主容器用 Java，Sidecar 用 Go、Rust 都可以；
- 便于统一管理和标准化。

### 1.3 常见 Sidecar 场景

| 场景 | Sidecar 作用 |
|---|---|
| 日志采集 | 采集主容器日志并发送到日志系统 |
| 监控指标 | 暴露 Prometheus metrics 或采集指标 |
| 配置热更新 | 监听配置中心变化，更新本地配置文件 |
| 服务代理 | 代理进出流量，实现服务发现、熔断、限流 |
| 认证鉴权 | 统一处理 TLS、mTLS、Token 校验 |
| 数据同步 | 同步数据文件、缓存预热 |

### 1.4 Sidecar 和主容器的协作方式

常见协作方式：

- 共享 emptyDir：主容器写日志，Sidecar 读日志；
- 共享 Unix Socket：主容器通过本地 socket 与 Sidecar 通信；
- 共享网络：Sidecar 监听 localhost 端口，代理主容器流量；
- Init Container 预热：启动前完成配置、权限、数据准备。

### 1.5 Sidecar 模式的缺点

- 增加 Pod 内资源消耗；
- 容器生命周期耦合：Sidecar 异常可能导致 Pod 状态受影响；
- 调试复杂度增加；
- 需要合理设计共享卷和通信方式；
- 过多 Sidecar 会增加启动时间。

### 1.6 Kubernetes 对 Sidecar 的支持演进

传统上，Sidecar 和普通容器没有本质区别，Job 场景下可能出现 Sidecar 提前结束导致主容器无法正常运行的问题。

Kubernetes 1.29 引入了 Sidecar Container 的初步支持：可以在 `initContainers` 中设置 `restartPolicy: Always`，让 Sidecar 容器在 Init 阶段启动并持续运行到 Pod 结束。

示例：

```yaml
initContainers:
  - name: sidecar-proxy
    image: envoy:latest
    restartPolicy: Always
```

这样 Sidecar 会在普通容器之前启动，并在整个 Pod 生命周期中持续运行。

---

## 2. 服务网格 Service Mesh

### 2.1 什么是 Service Mesh

Service Mesh，即服务网格，是一种用于处理服务间通信的基础设施层。

它的核心思想是：把服务发现、负载均衡、流量管理、安全通信、可观测性等通用能力，从业务代码中剥离出来，下沉到一个独立的代理层。

### 2.2 Service Mesh 解决的问题

- 服务间调用复杂，手工维护困难；
- 不同语言、框架的服务难以统一治理能力；
- 缺乏细粒度流量控制；
- 服务间安全通信成本高；
- 调用链路、指标、日志难以统一观测；
- 熔断、限流、重试需要在各服务中重复实现。

### 2.3 Service Mesh 典型架构

```text
+---------------------------------------------------+
|                    Application                    |
|  +----------------+        +----------------+     |
|  |  Service A     |        |  Service B     |     |
|  |  +----------+  |        |  +----------+  |     |
|  |  | App Code |  |        |  | App Code |  |     |
|  |  +----------+  |        |  +----------+  |     |
|  |  | Proxy    |  |<------>|  | Proxy    |  |     |
|  |  +----------+  |  mTLS  |  +----------+  |     |
|  +----------------+        +----------------+     |
|                                                   |
|  Control Plane: 管理代理配置、证书、策略、指标     |
+---------------------------------------------------+
```

每个服务实例旁边都有一个代理，所有进出流量都经过代理。控制平面负责统一配置这些代理。

这里的“服务实例”在 Kubernetes 中通常就是一个 Pod。Service Mesh 常见实现是 sidecar 模式，即每个业务 Pod 旁边注入一个代理容器，例如 Envoy。

需要注意：sidecar 是 Pod 维度部署的，但治理规则通常是 Service 维度配置的，最终流量会落到具体 Pod 实例上。

可以这样理解：

```text
Service 维度：定义访问哪个逻辑服务，例如 user-service
Endpoint / Pod 维度：记录这个服务后面有哪些真实实例
Sidecar 维度：每个 Pod 里的代理负责执行本 Pod 的进出流量治理
```

例如：

```text
order-service
  ├── order-pod-1: order app + envoy sidecar
  └── order-pod-2: order app + envoy sidecar

user-service
  ├── user-pod-1: user app + envoy sidecar
  ├── user-pod-2: user app + envoy sidecar
  └── user-pod-3: user app + envoy sidecar
```

order-service 调用 user-service 时，流量大致是：

```text
order app
   |
   v
order-pod-1 里的 sidecar
   |
   | 根据控制平面下发的服务发现、路由和负载均衡规则
   v
选择某个 user-service 后端 Pod
   |
   v
user-pod-2 里的 sidecar
   |
   v
user app
```

所以 Service Mesh 不是每个 Service 一个代理，而是每个 Pod 一个代理；所有代理共同组成一张服务通信网格。控制平面统一给这些代理下发服务发现、路由、证书、熔断、重试和观测配置。

---

### 2.4 为什么 Pod 维度 sidecar 能做 Service 维度治理

原因是：每个 Pod 的 sidecar 都掌管这个 Pod 的进出流量。

```text
Service 维度的规则
    下发到
每个相关 Pod 的 sidecar
    然后作用在
这个 Pod 发出或接收的每一条请求上
```

比如配置：

```text
user-service:
  90% 流量到 v1
  10% 流量到 v2
```

控制平面会把这条规则下发给所有可能调用 user-service 的 sidecar。每个调用方 sidecar 在发送请求时执行这个规则：

```text
order-pod-1 sidecar:
  90% -> user-service v1 pod
  10% -> user-service v2 pod

order-pod-2 sidecar:
  90% -> user-service v1 pod
  10% -> user-service v2 pod

payment-pod-1 sidecar:
  90% -> user-service v1 pod
  10% -> user-service v2 pod
```

因此：

```text
规则是 Service 维度的
执行点是 Pod 里的 sidecar
最终目标是某个具体 Pod 实例
```

Kubernetes 原生 Service 更偏四层转发，通常由 kube-proxy、IPVS 或 eBPF 根据 ClusterIP 和 EndpointSlice 转发到后端 Pod；Service Mesh 的 sidecar 更偏客户端侧和七层流量治理，可以按 HTTP Header、路径、权重、版本、用户特征等做更细粒度控制。

### 2.5 Service Mesh 核心能力

- **服务发现**：自动发现服务后端；
- **负载均衡**：支持多种算法；
- **流量路由**：按版本、Header、权重分配流量；
- **灰度发布**：金丝雀、蓝绿、A/B 测试；
- **熔断降级**：失败率超过阈值时自动熔断；
- **超时重试**：统一配置超时和重试策略；
- **安全通信**：mTLS 双向认证；
- **可观测性**：Metrics、Tracing、Logging；
- **访问控制**：基于身份和策略的访问控制。

---

## 3. Istio

### 3.1 什么是 Istio

Istio 是目前最流行的 Service Mesh 实现之一，由 Google、IBM、Lyft 等公司发起。

Istio 主要由两部分组成：

- **数据平面**：Envoy 代理，负责实际流量转发和治理；
- **控制平面**：istiod，负责配置管理、证书管理、服务发现等。

### 3.2 Istio 架构

```text
+-------------------+
|     istiod        |
|  - Pilot          |
|  - Citadel        |
|  - Galley         |
+-------------------+
         |
         | xDS API
         v
+-------------------+       +-------------------+
|   Envoy Sidecar   |<----->|   Envoy Sidecar   |
|   in Service A    |  mTLS |   in Service B    |
+-------------------+       +-------------------+
```

istiod 通过 xDS API 向 Envoy 下发路由、监听、集群、证书等配置。

### 3.3 Istio 核心资源

Istio 通过 CRD 扩展 Kubernetes API，常见资源包括：

#### VirtualService

定义流量路由规则。

示例：按权重做金丝雀发布。

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
spec:
  hosts:
    - user-service
  http:
    - route:
        - destination:
            host: user-service
            subset: v1
          weight: 90
        - destination:
            host: user-service
            subset: v2
          weight: 10
```

#### DestinationRule

定义目标服务的策略，如负载均衡、连接池、子集、TLS 模式。

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: user-service
spec:
  host: user-service
  trafficPolicy:
    loadBalancer:
      simple: ROUND_ROBIN
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
```

#### Gateway

定义入口网关，暴露集群内服务到外部。

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: user-gateway
spec:
  selector:
    istio: ingressgateway
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "*.example.com"
```

#### ServiceEntry

将外部服务注册到 Istio 服务网格中，使其可以像内部服务一样被治理。

#### PeerAuthentication / RequestAuthentication / AuthorizationPolicy

用于配置 mTLS、JWT 认证和授权策略。

### 3.4 Istio 如何和 Kubernetes 交互

Istio 的控制平面核心组件是 istiod。istiod 会连接 Kubernetes API Server，通过 List-Watch 机制监听 Kubernetes 原生资源和 Istio 自定义资源。

交互关系可以概括为：

```text
Kubernetes API Server
  ├── Service
  ├── Pod
  ├── EndpointSlice
  ├── Namespace
  ├── Secret
  └── Istio CRD: VirtualService / DestinationRule / Gateway 等
          |
          | List-Watch
          v
istiod 控制平面
          |
          | xDS
          v
每个 Pod 里的 Envoy sidecar
```

istiod 监听的 Kubernetes 原生资源包括：

- Service：有哪些逻辑服务；
- Pod：有哪些工作负载实例，以及它们的 label、IP、namespace；
- EndpointSlice：每个 Service 后面有哪些 Ready Pod IP 和端口；
- Namespace：命名空间信息和注入策略；
- Secret：证书、密钥等安全数据；
- ServiceAccount：工作负载身份。

istiod 监听的 Istio CRD 包括：

- VirtualService：流量路由规则；
- DestinationRule：目标服务策略、subset、负载均衡、熔断、TLS；
- Gateway：入口网关配置；
- ServiceEntry：把外部服务纳入网格治理；
- PeerAuthentication：服务间 mTLS 策略；
- RequestAuthentication：JWT 等请求认证规则；
- AuthorizationPolicy：访问授权策略；
- EnvoyFilter：对 Envoy 配置做高级扩展。

这些资源仍然是 Kubernetes API 对象，会提交给 API Server 并存入 etcd。istiod 通过 watch 感知变化，再重新计算需要下发给 sidecar 的代理配置。

---

### 3.5 Istio 控制平面有哪些数据

istiod 的数据主要来自 Kubernetes 原生资源、Istio CRD、Secret/证书以及 sidecar 连接信息。

#### 服务发现数据

来自 Service、Pod、EndpointSlice。

例如：

```text
Service:
  user-service.default.svc.cluster.local

Endpoints:
  10.244.1.11:8080
  10.244.2.22:8080
  10.244.3.33:8080
```

这些数据告诉 istiod：集群里有哪些服务，每个服务后面有哪些真实 Pod 实例。

#### 流量路由数据

来自 VirtualService。

例如：

```text
访问 user-service 的流量：
  90% -> v1
  10% -> v2
```

#### 目标服务策略数据

来自 DestinationRule。

它可以定义：

- subset，例如 v1、v2；
- 负载均衡算法；
- 连接池；
- 熔断；
- TLS 模式。

#### 安全和证书数据

来自 PeerAuthentication、RequestAuthentication、AuthorizationPolicy 和 Secret。

这些数据决定：

- 是否启用 mTLS；
- 谁可以访问谁；
- 是否校验 JWT；
- 使用什么证书；
- 工作负载身份是什么。

Istio 常用 SPIFFE 风格身份，例如：

```text
spiffe://cluster.local/ns/default/sa/user-service
```

表示 default 命名空间下使用 user-service 这个 ServiceAccount 的工作负载身份。

#### sidecar 连接信息

每个 Envoy sidecar 启动后会连接 istiod，并携带自身信息，例如：

```text
我是谁
我在哪个 namespace
我属于哪个 workload
我有哪些 labels
我使用哪个 ServiceAccount
```

istiod 会根据这些信息下发适合该 sidecar 的配置。并不是所有 sidecar 都一定拿到完全相同的配置。

---

### 3.6 CRD、Envoy 配置和 xDS 分别是什么

#### CRD

CRD，全称 CustomResourceDefinition，是 Kubernetes 的自定义资源定义。

Istio 通过 CRD 扩展 Kubernetes API，使 Kubernetes 能认识 Istio 自己定义的资源类型，例如：

```text
VirtualService
DestinationRule
Gateway
ServiceEntry
PeerAuthentication
AuthorizationPolicy
RequestAuthentication
EnvoyFilter
Sidecar
```

用户写的 VirtualService、DestinationRule 等都是 Istio CRD 对象。它们会被 API Server 保存到 etcd，并被 istiod 监听。

一句话理解：

```text
CRD = 用户声明的 Istio 治理规则
```

#### Envoy 配置

Envoy 是运行在 sidecar 中的代理，它真正执行流量治理。Envoy 需要知道：

- 监听哪些端口；
- 请求如何匹配路由；
- 上游服务有哪些；
- 每个服务后面有哪些 Pod IP；
- 是否做 90/10 灰度；
- 超时时间是多少；
- 是否重试；
- 是否启用 mTLS；
- 使用什么证书；
- 哪些请求允许访问。

这些就是 Envoy 配置。

常见配置类型：

| 配置类型 | 作用 |
|---|---|
| Listener | Envoy 监听哪些端口、拦截哪些流量 |
| Route | HTTP 请求怎么路由 |
| Cluster | 上游服务抽象，例如 user-service |
| Endpoint | 某个服务后面具体有哪些 Pod IP |
| Secret | mTLS 证书和密钥 |
| Filter | HTTP/TCP 过滤器，例如认证、限流、日志、指标 |

一句话理解：

```text
Envoy 配置 = istiod 根据 K8s 资源和 Istio CRD 生成的、Envoy 能直接执行的代理配置
```

#### xDS

xDS 是 Envoy 的动态配置协议族。istiod 通过 xDS 把配置下发给 Envoy sidecar。

常见 xDS 类型：

| xDS | 全称 | 作用 |
|---|---|---|
| LDS | Listener Discovery Service | 下发监听器配置 |
| RDS | Route Discovery Service | 下发路由规则 |
| CDS | Cluster Discovery Service | 下发上游服务配置 |
| EDS | Endpoint Discovery Service | 下发后端实例地址 |
| SDS | Secret Discovery Service | 下发证书和密钥 |

简单记忆：

```text
LDS：监听什么端口
RDS：请求怎么路由
CDS：有哪些上游服务
EDS：服务后面有哪些 Pod IP
SDS：证书密钥是什么
```

三者关系：

```text
用户写 Istio CRD
        |
        v
API Server 保存 CRD 对象
        |
        v
istiod 监听 K8s 资源和 Istio CRD
        |
        v
istiod 生成 Envoy 配置
        |
        v
通过 xDS 下发给 Envoy sidecar
        |
        v
sidecar 执行流量治理
```

---

### 3.7 sidecar 什么时候创建，是先有配置还是先有 sidecar

Istio sidecar 通常是在业务 Pod 创建时由注入 Webhook 自动加入的。

完整过程：

```text
1. 安装 Istio
   -> 创建 istiod、CRD、sidecar injector webhook

2. 给 Namespace 或 Pod 开关注入
   -> namespace 标记 istio-injection=enabled
   -> 或 Pod 标记 sidecar.istio.io/inject=true

3. 创建业务 Deployment / Pod
   -> 原始 PodSpec 里通常只有业务容器

4. API Server 准入控制阶段调用 Istio sidecar injector
   -> 修改 PodSpec
   -> 注入 istio-proxy 容器、initContainer 或 CNI 重定向配置、证书 volume、Envoy bootstrap 配置

5. kubelet 创建 Pod
   -> 启动业务容器和 istio-proxy 容器

6. Envoy sidecar 读取 bootstrap 配置
   -> 知道 istiod 地址和自身身份

7. Envoy sidecar 连接 istiod
   -> 通过 xDS 获取完整动态配置
```

所以，顺序不是简单的“先有完整配置”或“先有 sidecar”。更准确是：

```text
Istio CRD 可以先存在
Pod 创建时先注入 sidecar 和 bootstrap 配置
sidecar 启动后通过 xDS 从 istiod 获取动态配置
```

如果先创建 VirtualService / DestinationRule，Pod 后创建，那么 sidecar 启动后会拿到当前最新规则。

如果 Pod 和 sidecar 已经在运行，后来再创建或修改 VirtualService / DestinationRule，istiod 会监听到变化，并通过 xDS 增量推送给相关 sidecar，通常不需要重启业务 Pod。

---

### 3.8 Istio 流量管理具体实现流程

以 order-service 调用 user-service 为例，user-service 有两个版本：

```text
v1: 3 个 Pod
v2: 1 个 Pod
```

目标：

```text
90% 流量到 v1
10% 流量到 v2
```

#### 第一步：Kubernetes 中有基础服务数据

Service：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: user-service
spec:
  selector:
    app: user-service
  ports:
    - port: 80
      targetPort: 8080
```

后端 Pod：

```text
user-v1-pod-1: app=user-service, version=v1, 10.244.1.11
user-v1-pod-2: app=user-service, version=v1, 10.244.1.12
user-v1-pod-3: app=user-service, version=v1, 10.244.1.13
user-v2-pod-1: app=user-service, version=v2, 10.244.2.21
```

EndpointSlice 会记录这些后端地址。

#### 第二步：用户配置 Istio 流量规则

DestinationRule 定义版本分组：

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: user-service
spec:
  host: user-service
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
```

VirtualService 定义流量比例：

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
spec:
  hosts:
    - user-service
  http:
    - route:
        - destination:
            host: user-service
            subset: v1
          weight: 90
        - destination:
            host: user-service
            subset: v2
          weight: 10
```

#### 第三步：istiod 监听并计算配置

istiod 监听到：

```text
Service: user-service
EndpointSlice: user-service 后端 Pod IP
DestinationRule: v1 / v2 subset 定义
VirtualService: 90 / 10 流量比例
```

然后生成 Envoy 能理解的配置：

```text
Route:
  host=user-service
  90% -> cluster user-service|v1
  10% -> cluster user-service|v2

Cluster:
  user-service|v1
    endpoints:
      10.244.1.11:8080
      10.244.1.12:8080
      10.244.1.13:8080

  user-service|v2
    endpoints:
      10.244.2.21:8080
```

#### 第四步：istiod 通过 xDS 下发给 sidecar

每个 order-service 的 Envoy sidecar 都会和 istiod 建立连接。

istiod 通过 xDS 下发：

```text
RDS：访问 user-service 时怎么路由
CDS：有哪些 upstream cluster
EDS：每个 cluster 后面有哪些 Pod IP
SDS：mTLS 证书
```

#### 第五步：业务请求被 sidecar 拦截

order-service 业务代码发请求：

```text
GET http://user-service/api/users/1
```

请求会被本 Pod 里的 sidecar 拦截。通常通过 iptables 规则或 CNI 把业务容器的出站流量重定向到本地 Envoy。

```text
order app
   |
   | 访问 user-service
   v
本地 iptables / CNI 重定向
   |
   v
order pod 里的 Envoy sidecar
```

#### 第六步：Envoy 执行路由和负载均衡

Envoy 根据 istiod 下发的规则执行：

```text
1. 识别目标 host 是 user-service
2. 匹配 VirtualService 路由规则
3. 按 90/10 权重选择 v1 或 v2
4. 在对应 subset 的 endpoints 中选择一个 Pod
5. 应用超时、重试、熔断、连接池等策略
6. 如果启用 mTLS，和目标 sidecar 建立加密连接
7. 转发请求到目标 Pod
```

如果本次请求命中 v2：

```text
order-pod-1 Envoy
        |
        | mTLS
        v
user-v2-pod-1 Envoy
        |
        v
user-service app
```

#### 第七步：目标 Pod sidecar 接收流量

目标 Pod 里的 sidecar 可以做：

- mTLS 解密；
- 身份校验；
- AuthorizationPolicy 检查；
- 指标采集；
- 日志记录；
- Trace 传播。

然后再把请求交给业务容器。

---

### 3.9 Istio 流量管理能力分别怎么实现

| 能力 | 主要配置 | 执行点 |
|---|---|---|
| 灰度 / 金丝雀 | VirtualService 权重 + DestinationRule subset | 调用方 sidecar |
| 按 Header 路由 | VirtualService match headers | 调用方 sidecar |
| 超时 | VirtualService timeout | 调用方 sidecar |
| 重试 | VirtualService retries | 调用方 sidecar |
| 熔断 | DestinationRule connectionPool / outlierDetection | 调用方 sidecar |
| mTLS | PeerAuthentication / DestinationRule TLS / 证书 | 双方 sidecar |
| 访问控制 | AuthorizationPolicy | 目标方 sidecar |
| 可观测性 | Envoy access log / metrics / tracing | 双方 sidecar |

一句话总结：Istio 的规则通常是 Service 维度配置的，istiod 把 Kubernetes 服务发现数据和 Istio CRD 转换成 Envoy 配置，通过 xDS 下发给每个 Pod 里的 sidecar；sidecar 在请求路径上执行路由、负载均衡、灰度、熔断、重试、mTLS 和可观测性。

### 3.10 Istio 安全能力

- **mTLS**：服务间自动开启双向 TLS；
- **身份认证**：基于 SPIFFE 身份；
- **JWT 认证**：验证请求 Token；
- **授权策略**：基于 namespace、service、method、path 等做访问控制；
- **证书自动轮换**：Citadel 自动签发和轮换证书。

### 3.11 Istio 可观测性

Istio 内置或集成：

- **Metrics**：Prometheus + Grafana；
- **Tracing**：Jaeger、Zipkin、SkyWalking；
- **Logging**：Envoy Access Log；
- **Kiali**：可视化服务拓扑和流量。

### 3.12 Istio 的 Sidecar 注入

Istio 通过 Webhook 自动向 Pod 注入 Envoy Sidecar。

注入方式：

- 命名空间级别：`istio-injection=enabled` Label；
- Pod 级别：`sidecar.istio.io/inject: "true"` Annotation；
- 手动注入：`istioctl kube-inject`。

### 3.13 Istio 优缺点

优点：

- 功能全面；
- 社区活跃；
- 与 Kubernetes 深度集成；
- 可视化能力强；
- 安全能力成熟。

缺点：

- 引入额外资源开销；
- 学习曲线陡峭；
- 配置复杂；
- 排障链路变长；
- 对延迟有一定影响；
- 运维成本高。

### 3.14 Istio 和 Ingress 的关系

- Ingress 是 Kubernetes 原生的七层入口方案；
- Istio Gateway 是 Istio 提供的更强大的入口方案；
- Istio Gateway 可以做更细粒度的流量治理，如按 Header 路由、故障注入、mTLS；
- 生产中可以只用 Ingress，也可以 Ingress + Istio Gateway 结合使用。

---

## 4. 其他 Service Mesh 方案

### 4.1 Linkerd

Linkerd 是另一个流行的 Service Mesh，由 Buoyant 公司维护。

特点：

- 设计更轻量；
- 强调简单和性能；
- 数据平面使用 Rust 编写的 linkerd2-proxy；
- 适合不想引入过重基础设施的团队。

### 4.2 Consul Connect

HashiCorp Consul 的服务网格能力。

特点：

- 与 Consul 服务发现深度集成；
- 支持 VM 和 Kubernetes 混合部署；
- 适合已有 Consul 基础设施的团队。

### 4.3 AWS App Mesh、Alibaba Cloud Service Mesh

云厂商托管的服务网格方案，降低自建和运维成本。

---

## 5. Helm

### 5.1 什么是 Helm

Helm 是 Kubernetes 的包管理工具，被称为 "Kubernetes 的 apt/yum"。

它可以把一组相关的 Kubernetes 资源打包成一个 Chart，便于安装、升级、回滚和共享。

### 5.2 Helm 核心概念

| 概念 | 说明 |
|---|---|
| Chart | Helm 包，包含一组 Kubernetes 模板和资源定义 |
| Release | Chart 的一次安装实例 |
| Values | 用户自定义配置值 |
| Repository | Chart 仓库，用于存储和分发 Chart |
| Template | 使用 Go template 语法定义 Kubernetes 资源 |

### 5.3 Helm Chart 目录结构

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
└── README.md
```

### 5.4 Helm 常用命令

```bash
# 添加仓库
helm repo add stable https://charts.helm.sh/stable

# 搜索 Chart
helm search repo nginx

# 安装 Chart
helm install my-nginx stable/nginx

# 查看 Release
helm list

# 升级 Release
helm upgrade my-nginx stable/nginx

# 回滚 Release
helm rollback my-nginx 1

# 卸载 Release
helm uninstall my-nginx

# 渲染模板但不安装
helm template my-chart

# 查看 Release 历史
helm history my-nginx
```

### 5.5 Helm Values 和模板

`values.yaml` 示例：

```yaml
replicaCount: 3
image:
  repository: nginx
  tag: "1.25"
service:
  type: ClusterIP
  port: 80
```

模板中引用：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-nginx
spec:
  replicas: {{ .Values.replicaCount }}
  template:
    spec:
      containers:
        - name: nginx
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
```

### 5.6 Helm 升级和回滚

Helm 升级时会：

1. 渲染新模板；
2. 对比当前 Release 和新版本差异；
3. 向 Kubernetes 应用变更；
4. 记录新版本到 Release History。

回滚时 Helm 会恢复到指定版本的 Kubernetes 资源状态。

### 5.7 Helm 3 和 Helm 2 的区别

| 特性 | Helm 2 | Helm 3 |
|---|---|---|
| Tiller | 有 | 无 |
| 安全性 | Tiller 权限较大，容易成为攻击点 | 直接使用 kubeconfig 权限 |
| 架构 | 客户端 + 服务端 | 仅客户端 |
| 发布信息存储 | ConfigMap 或 Secret | Secret |
| 库 Chart | 支持 | 支持 |

面试重点：Helm 3 移除了 Tiller，直接使用用户 kubeconfig 权限操作 Kubernetes，安全性更好。

### 5.8 Helm 使用场景

- 复杂应用一键部署；
- 多环境配置管理；
- 应用版本升级和回滚；
- Chart 复用和共享；
- CI/CD 集成；
- 多组件应用统一管理。

---

## 6. Operator

### 6.1 什么是 Operator

Operator 是一种利用 Kubernetes 控制器模式自动化运维复杂应用的方法。

它通常包括：

- 自定义资源类型 CRD；
- 一个控制器，监听 CRD 变化并执行运维操作。

### 6.2 Operator 常见能力

- 自动化部署；
- 配置管理；
- 备份恢复；
- 滚动升级；
- 故障自动切换；
- 扩缩容；
- 证书轮换。

### 6.3 常见 Operator

- Prometheus Operator；
- etcd Operator；
- MySQL Operator；
- Redis Operator；
- Kafka Operator；
- Cert-manager。

### 6.4 Operator 框架

- Operator SDK；
- Kubebuilder；
- KUDO；
- Metacontroller。

---

## 7. Ingress Controller 进阶

### 7.1 Ingress Controller 是什么

Ingress 资源只是规则定义，真正处理流量的是 Ingress Controller。

常见 Ingress Controller：

- Nginx Ingress Controller；
- Traefik；
- HAProxy Ingress；
- Kong Ingress；
- Istio Ingress Gateway；
- 云厂商 ALB/CLB Ingress Controller。

### 7.2 Ingress Class

Kubernetes 1.18+ 引入 IngressClass，用于区分不同 Ingress Controller。

```yaml
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  name: nginx
spec:
  controller: k8s.io/ingress-nginx
```

Ingress 资源通过 `ingressClassName` 指定使用哪个 Controller。

### 7.3 Ingress 高级能力

不同 Ingress Controller 支持不同高级能力：

- 基于 Header/Host/Path 路由；
- TLS 终止；
- 重写；
- 重定向；
- 限流；
- 灰度发布；
- Basic Auth/OAuth；
- 自定义错误页。

---

## 8. GitOps

### 8.1 什么是 GitOps

GitOps 是一种以 Git 仓库为唯一事实来源的持续交付方式。

核心思想：

- 应用的期望状态保存在 Git 中；
- 自动化工具监听 Git 变化；
- 自动将变更同步到 Kubernetes；
- 回滚也通过 Git 历史完成。

### 8.2 常见 GitOps 工具

- ArgoCD；
- Flux；
- Jenkins X；
- Rancher Fleet。

### 8.3 GitOps 优势

- 版本控制；
- 审计可追溯；
- 便于回滚；
- 多环境一致性；
- 自动化程度高。

---

## 9. 生态组件对比

| 组件 | 解决的问题 | 典型场景 |
|---|---|---|
| Sidecar | 扩展 Pod 能力 | 日志采集、服务代理、配置热更新 |
| Istio | 服务间流量治理 | 金丝雀、mTLS、可观测性 |
| Helm | 应用包管理 | 复杂应用部署、升级、回滚 |
| Operator | 自动化运维复杂应用 | 数据库、中间件全生命周期管理 |
| Ingress Controller | 七层入口流量 | 对外暴露 HTTP/HTTPS 服务 |
| GitOps | 持续交付 | 以 Git 为中心的发布流程 |

---

## 10. 高频面试题

### 10.1 Sidecar 模式有什么优缺点

优点：

- 职责分离；
- 语言无关；
- 无需修改业务代码；
- 可独立升级。

缺点：

- 增加资源开销；
- 增加 Pod 启动时间；
- 调试复杂；
- 生命周期耦合。

### 10.2 Service Mesh 解决了什么问题

答：Service Mesh 把服务间通信相关能力下沉到基础设施层，解决服务发现、负载均衡、流量管理、安全通信、可观测性、熔断限流等问题，让业务代码无需关心这些通用能力。

### 10.3 Istio 的数据平面和控制平面分别是什么

答：

- 数据平面：Envoy Sidecar，负责拦截和治理服务间流量；
- 控制平面：istiod，负责配置下发、证书管理、服务发现。

### 10.4 Istio 和 Nginx Ingress 有什么区别

答：

- Nginx Ingress 主要负责七层入口流量；
- Istio 是完整的服务网格，管理服务间流量；
- Istio Gateway 也可以作为入口；
- Istio 提供更细粒度的流量治理和安全能力，如 mTLS、金丝雀、故障注入。

### 10.5 Helm 3 相比 Helm 2 有什么改进

答：

- 移除了 Tiller；
- 直接使用 kubeconfig 权限；
- 安全性更高；
- 架构更简单；
- Release 信息存储在 Secret 中。

### 10.6 Operator 和 Deployment 有什么区别

答：

- Deployment 适合管理无状态应用；
- Operator 适合管理复杂有状态系统；
- Operator 可以自定义 CRD 和控制器，实现自动化部署、备份、升级、故障恢复等高级运维能力。

### 10.7 什么情况下应该引入 Service Mesh

答：

- 微服务数量多，服务间调用复杂；
- 需要统一的流量治理能力；
- 需要服务间安全通信；
- 需要统一的可观测性；
- 团队有能力承担额外运维成本。

但小规模、简单架构下引入 Service Mesh 可能得不偿失。

### 10.8 Sidecar 注入对 Pod 有什么影响

答：

- 增加一个容器，占用额外 CPU 和内存；
- 增加 Pod 启动时间；
- 所有进出流量被 Sidecar 拦截，可能增加延迟；
- 排障时需要同时关注业务容器和 Sidecar 日志；
- 安全策略和版本升级可以通过 Sidecar 统一管理。

### 10.9 GitOps 的核心思想是什么

答：

GitOps 把 Git 仓库作为唯一事实来源，应用的期望状态、环境配置都保存在 Git 中。自动化工具持续对比 Git 状态和集群实际状态，自动同步差异，实现版本控制、审计、回滚和自动化交付。
