# Docker 生产实战面试知识点

> 本文是 Docker 面试的**进阶实战篇**。基础与命令请看 [`docker面试知识点.md`](docker面试知识点.md)；底层原理请看 [`docker原理机制面试知识点.md`](docker原理机制面试知识点.md)。

## 一、镜像优化：减小体积

### 1.1 为什么要优化镜像体积？

生产中一个 Java 应用镜像动辄 800MB+，一个 Node 应用 500MB+ 是很常见的。镜像大有四大问题：

| 问题 | 影响 |
|------|------|
| **传输慢** | 从 Registry 拉取耗时长，影响部署速度 |
| **存储成本** | Registry、节点磁盘占用大 |
| **启动慢** | 节点首次拉镜像慢，扩容响应差 |
| **安全面广** | 包含更多不必要的软件包，攻击面大 |

**目标：** 一个典型 Java 服务的镜像应控制在 **200MB 以内**，Go 应用甚至可以做到 **10MB 以内**。

### 1.2 选择更小的基础镜像

基础镜像大小对比：

| 镜像 | 大小 | 说明 |
|------|------|------|
| `ubuntu:22.04` | ~77MB | 通用 Linux |
| `debian:bullseye` | ~124MB | 更完整 |
| `debian:bullseye-slim` | ~74MB | 精简版 |
| `alpine:3.18` | ~7MB | 极致精简（musl libc） |
| `distroless/java17` | ~180MB | Google 无 shell 极致安全 |
| `scratch` | 0MB | 空镜像，用于静态编译（Go） |

**Java 镜像对比：**

| 镜像 | 大小 |
|------|------|
| `openjdk:17` | ~470MB |
| `openjdk:17-slim` | ~230MB |
| `eclipse-temurin:17-jre-alpine` | ~150MB |
| `gcr.io/distroless/java17` | ~180MB |

**选择建议：**

- 追求小体积：Alpine
- 追求安全：Distroless（无 shell、无包管理器）
- 需要调试：Slim 系列
- 静态编译（Go/Rust）：Scratch

### 1.3 Alpine 的坑

Alpine 用 **musl libc**（不是 glibc），会带来兼容性问题：

- 某些用 glibc 编译的 native 库不能运行
- Java 应用启动时可能报 `libc.so.6 not found`
- DNS 解析行为不一致

**解决：**

- Java 应用推荐 `eclipse-temurin:17-jre-alpine`（已适配 musl）
- 或者用 `eclipse-temurin:17-jre-jammy`（基于 Ubuntu）

### 1.4 多阶段构建（Multi-stage Build）

这是**最重要的镜像优化技术**。核心思想：**构建阶段用完整环境，运行阶段只保留产物**。

#### 1.4.1 Go 应用示例

```dockerfile
# ===== 构建阶段 =====
FROM golang:1.21 AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# 静态编译，不依赖 glibc
RUN CGO_ENABLED=0 GOOS=linux go build -o app main.go

# ===== 运行阶段 =====
FROM scratch
COPY --from=builder /src/app /app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
EXPOSE 8080
ENTRYPOINT ["/app"]
```

**效果对比：**

- 单阶段：`golang:1.21` + 代码 + 依赖 ≈ **900MB**
- 多阶段：`scratch` + 静态二进制 ≈ **15MB**

#### 1.4.2 Java 应用示例

```dockerfile
# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# 先拉依赖，利用缓存
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

# ===== 运行阶段 =====
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
USER 1000
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**效果对比：**

- 单阶段（含 Maven）：**1.2GB+**
- 多阶段（jre-alpine）：**~200MB**

#### 1.4.3 Node.js 应用示例

```dockerfile
# ===== 依赖阶段 =====
FROM node:18-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production

# ===== 构建阶段 =====
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# ===== 运行阶段 =====
FROM node:18-alpine
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY package*.json ./
USER node
EXPOSE 3000
CMD ["node", "dist/server.js"]
```

### 1.5 其他优化技巧

#### 1.5.1 合并 RUN 指令

```dockerfile
# 反例：3 层，中间产物永久保留
RUN apt-get update
RUN apt-get install -y curl vim
RUN apt-get clean

# 正例：1 层，中间产物清理干净
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl vim && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
```

#### 1.5.2 用 `.dockerignore`

```gitignore
.git
.github
node_modules
*.log
target/
build/
dist/
.env*
README.md
docs/
tests/
```

#### 1.5.3 优化层顺序（利用缓存）

```dockerfile
# 反例：任何代码改动都会重新装依赖
COPY . /app
WORKDIR /app
RUN npm install

# 正例：依赖变化才重装
COPY package*.json /app/
WORKDIR /app
RUN npm install
COPY . /app
```

#### 1.5.4 用 BuildKit 加速构建

```bash
# 启用 BuildKit（Docker 20.10+ 默认启用）
DOCKER_BUILDKIT=1 docker build -t myapp .

# 或全局启用
export DOCKER_BUILDKIT=1
```

BuildKit 的优势：

- 并行构建多阶段
- 更好的缓存管理
- 支持 secrets 挂载
- 更快的层传输

**挂载 cache（推荐）：**

```dockerfile
# syntax=docker/dockerfile:1
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
# 挂载 npm 缓存，多次构建复用
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
CMD ["node", "server.js"]
```

### 1.6 镜像瘦身工具

- **`docker history <image>`**：查看每层大小，定位问题
- **`dive`**：交互式分析镜像层，找出冗余文件（`brew install dive`）
- **`docker-slim`**：自动瘦身镜像，可减小 30 倍

---

## 二、Docker Compose：单机多容器编排

### 2.1 什么是 Docker Compose？

Docker Compose 是 Docker 官方提供的**多容器编排工具**，通过一个 `docker-compose.yml` 定义整套服务，一条命令启动所有容器。

**典型场景：** 本地开发环境（后端 + MySQL + Redis + RabbitMQ）、简单单机部署。

### 2.2 docker-compose.yml 示例

```yaml
version: '3.8'

services:
  # 后端应用
  backend:
    build:
      context: .
      dockerfile: Dockerfile
    image: myapp:v1
    container_name: backend
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DB_HOST=mysql
      - REDIS_HOST=redis
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_started
    networks:
      - myapp-net
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  # MySQL
  mysql:
    image: mysql:8.0
    container_name: mysql
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root123
      - MYSQL_DATABASE=myapp
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    networks:
      - myapp-net
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis
  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - myapp-net
    restart: unless-stopped

networks:
  myapp-net:
    driver: bridge

volumes:
  mysql-data:
  redis-data:
```

### 2.3 常用命令

```bash
# 启动所有服务（后台）
docker compose up -d

# 停止并删除所有容器
docker compose down

# 停止但保留容器
docker compose stop

# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f backend

# 重启某个服务
docker compose restart backend

# 只重建某个服务（重新构建镜像）
docker compose up -d --build backend

# 扩容
docker compose up -d --scale backend=3

# 执行命令
docker compose exec backend bash

# 完全清理（含 Volume）
docker compose down -v
```

### 2.4 Compose 高频面试点

**Q：Compose 中的服务之间怎么通信？**

答：Compose 会自动创建一个默认网络，所有服务加入其中。服务之间可以**直接通过服务名互相访问**。例如上面 backend 中 `DB_HOST=mysql`，Docker 内部 DNS 会把 `mysql` 解析到 MySQL 容器的 IP。

**Q：depends_on 能保证被依赖服务真的可用吗？**

答：**不能**。默认的 `depends_on` 只保证**启动顺序**，不保证被依赖服务已经**就绪**。比如 MySQL 容器启动了不代表 MySQL 端口能连、能查询。

解决方案：

1. 用 `depends_on.condition: service_healthy` + `healthcheck`（推荐）
2. 应用侧实现重试逻辑
3. 用 `wait-for-it.sh` 之类的脚本

**Q：Compose 和 Kubernetes 什么关系？**

答：Compose 是**单机**多容器编排；K8s 是**集群**容器编排。Compose 适合本地开发和小型部署，K8s 适合生产集群。有个工具叫 `kompose` 可以把 `docker-compose.yml` 转成 K8s YAML。

---

## 三、Docker 日志与监控

### 3.1 Docker 日志机制

**核心原则：容器应用应该把日志输出到 stdout/stderr，而不是写文件。**

Docker 会捕获 stdout/stderr，通过**日志驱动（logging driver）**处理。

### 3.2 常见日志驱动

| 驱动 | 说明 |
|------|------|
| `json-file`（默认） | 写到 `/var/lib/docker/containers/<id>/<id>-json.log` |
| `local` | 更高效的本地文件格式 |
| `journald` | 写到 systemd journal |
| `syslog` | 转发到 syslog |
| `fluentd` | 转发到 Fluentd |
| `awslogs` | 转发到 AWS CloudWatch |
| `gelf` | 转发到 Graylog |
| `none` | 不记录日志 |

### 3.3 生产陷阱：日志爆盘

默认 `json-file` 驱动**没有大小限制**，容器日志无限增长会撑爆磁盘。

**解决：配置日志轮转**

`/etc/docker/daemon.json`：

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "3",
    "compress": "true"
  }
}
```

重启 Docker：`systemctl restart docker`。

**或在单个容器指定：**

```bash
docker run --log-driver=json-file \
  --log-opt max-size=100m \
  --log-opt max-file=3 \
  nginx
```

### 3.4 生产日志方案

单机 Compose 可以用 `docker logs`；生产集群需要**集中日志方案**：

| 方案 | 说明 |
|------|------|
| ELK（Elasticsearch + Logstash + Kibana） | 经典方案，功能强 |
| EFK（Elasticsearch + Fluentd + Kibana） | Fluentd 更轻量 |
| Loki + Grafana | 云原生，成本低 |
| Filebeat + ES | 采集端更轻量 |
| SLS/CLS（云厂商日志服务） | 免运维 |

采集方式：

- **Sidecar 模式**：每个 Pod 内跑一个日志采集容器
- **Node 级采集**：DaemonSet 部署 Fluentd/Filebeat，采集节点上所有容器日志
- **应用直写**：应用直接把日志发到日志服务（不推荐，耦合太重）

### 3.5 Docker 监控

**核心指标：**

- CPU 使用率、限流次数（cpu.stat 中的 throttled）
- 内存使用、OOM 次数
- 网络流量、丢包
- 磁盘 IO

**监控方案：**

| 方案 | 说明 |
|------|------|
| `docker stats` | 命令行实时查看，简单 |
| cAdvisor | Google 出品，容器级指标采集器 |
| Prometheus + cAdvisor + Grafana | 云原生标配 |
| Datadog、New Relic | 商业方案 |

---

## 四、Docker 安全加固

### 4.1 生产 Docker 安全的核心原则

**7 大最佳实践：**

1. 不用 root 用户运行容器
2. 使用最小基础镜像
3. 只读根文件系统
4. 最小 Capabilities
5. 启用 seccomp/AppArmor/SELinux
6. 定期扫描镜像漏洞
7. 严格控制镜像来源

### 4.2 非 root 用户运行

**Dockerfile 中：**

```dockerfile
FROM alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --chown=app:app app.jar /app/
```

**运行时：**

```bash
docker run -u 1000:1000 myapp
```

**面试问：为什么容器不能用 root？**

答：容器内的 root 和宿主机 root **UID 是同一个（都是 0）**。虽然 Namespace 隔离了视图，但内核共享，一旦发生容器逃逸（如通过内核漏洞），攻击者就直接拿到宿主机 root 权限，风险巨大。

### 4.3 只读根文件系统

```bash
docker run --read-only \
  --tmpfs /tmp \
  --tmpfs /run \
  nginx
```

好处：即使被攻破，也无法修改文件系统、无法写入恶意脚本。

### 4.4 最小 Capabilities

Linux Capabilities 把 root 的权限拆分成 30+ 个能力。默认容器有一堆能力，实际大部分用不到。

```bash
# 移除所有 Capability，只保留网络绑定的必需能力
docker run --cap-drop=ALL --cap-add=NET_BIND_SERVICE nginx
```

### 4.5 禁用特权模式

**永远不要在生产用 `--privileged`！**

```bash
# 危险！等于给容器 root 全部权限
docker run --privileged nginx
```

`--privileged` 会：

- 关闭 Capabilities 限制
- 允许访问所有设备
- 允许挂载文件系统
- 基本等于宿主机 root

只有极少数场景（如 Docker in Docker）需要，且要有 rigorous 的隔离。

### 4.6 镜像漏洞扫描

**开源工具：**

- **Trivy**：Aqua 出品，扫描镜像漏洞和配置错误
- **Grype**：Anchore 出品
- **Clair**：CoreOS 出品

```bash
# 用 Trivy 扫描
trivy image nginx:latest

# 只显示高危漏洞
trivy image --severity HIGH,CRITICAL nginx:latest
```

**Harbor 集成 Trivy**：企业级 Registry 通常集成漏洞扫描，push 时自动扫描，禁止有高危漏洞的镜像上线。

### 4.7 生产安全 checklist

```dockerfile
FROM eclipse-temurin:17-jre-alpine
# 1. 最小基础镜像
# 2. 明确版本号

RUN addgroup -S app && adduser -S app -G app
USER app
# 3. 非 root 用户

WORKDIR /app
COPY --chown=app:app app.jar .

# 4. 健康检查
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -qO- http://localhost:8080/health || exit 1

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

运行时：

```bash
docker run -d \
  --name myapp \
  --read-only --tmpfs /tmp \
  --cap-drop=ALL --cap-add=NET_BIND_SERVICE \
  --security-opt=no-new-privileges \
  -m 512m --cpus=1 \
  --pids-limit=100 \
  --restart=on-failure:3 \
  myapp:v1
```

---

## 五、Docker 性能调优

### 5.1 资源限制

**核心原则：始终为容器配置资源限制，避免"贪吃"影响其他服务。**

```bash
docker run \
  --cpus=2 \                    # CPU 上限
  -m 512m \                     # 内存上限
  --memory-swap=512m \          # 内存 + swap 上限（禁用 swap）
  --oom-kill-disable=false \    # 允许 OOM 杀
  --pids-limit=200 \            # 进程数上限
  myapp
```

### 5.2 JVM 在容器中的坑

**Java 应用在容器中的三大坑：**

1. **JVM 不识别容器内存限制**（Java < 8u191）
   - 老 JVM 读宿主机内存，导致堆过大触发 OOMKilled
   - 修复：升级 JDK，或显式设置 `-Xmx`

2. **JVM 看不到 CPU 限制**
   - 影响 GC 线程数、`Runtime.availableProcessors()`
   - 修复：Java 10+ 支持 `-XX:+UseContainerSupport`（默认开启）

3. **DNS 解析行为差异**
   - Alpine + musl libc 可能导致 DNS 问题
   - 修复：用 glibc 基础镜像或调整 JVM DNS 设置

**推荐 JVM 参数（容器化）：**

```bash
JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp"
```

### 5.3 存储驱动性能

- **overlay2** 是当前性能最好的存储驱动，默认使用
- 高 IO 应用（如数据库）**不要写在容器读写层**，一定要挂 Volume
- 数据库直接用 Bind Mount 到 SSD 效果更好

### 5.4 网络性能

- **网络密集型应用可用 `--network=host`**：省去 NAT 转换开销
- **K8s 中启用 IPVS 模式**：大规模 Service 时性能远好于 iptables
- **CNI 插件选型**：Cilium（eBPF）> Calico（BGP）> Flannel（VXLAN）

### 5.5 磁盘性能优化

```bash
# 查看 Docker 磁盘占用
docker system df

# 定期清理
docker system prune -a --volumes  # 清理所有未使用资源（慎用）
docker container prune             # 清理已停止容器
docker image prune -a              # 清理无用镜像
docker volume prune                # 清理无用卷
docker builder prune               # 清理构建缓存
```

---

## 六、Docker 与 Kubernetes 的关系

### 6.1 Docker 和 K8s 是什么关系？

| 层次 | Docker | Kubernetes |
|------|--------|------------|
| 定位 | 容器**运行时** + 镜像格式 | 容器**编排**平台 |
| 单位 | 容器 | Pod（一个或多个容器） |
| 单机/集群 | 单机为主 | 集群 |
| 编排能力 | 有限（Compose、Swarm） | 强大（Deployment、StatefulSet、Service 等） |

一句话：**Docker 负责跑单个容器，K8s 负责跑一堆容器**。

### 6.2 K8s 移除 dockershim 后 Docker 还有用吗？

**有用！但角色变了。**

- **构建镜像**：Docker 仍然是最流行的镜像构建工具
- **本地开发**：`docker run`、`docker compose` 仍是本地开发首选
- **CI/CD**：Jenkins、GitLab CI 中用 Docker 构建镜像

**变化的是：**

- K8s 集群运行时不再用 `dockerd`，改用 `containerd` 或 `CRI-O`
- Docker 构建的镜像仍然兼容（都遵循 OCI 标准）

### 6.3 Docker 镜像和 OCI 镜像

**OCI（Open Container Initiative）**是容器业界标准组织，定义了：

- **OCI Image Spec**：镜像格式标准
- **OCI Runtime Spec**：运行时标准
- **OCI Distribution Spec**：分发标准

Docker 镜像格式已经**完全兼容 OCI**，所以：

- Docker 构建的镜像可以在 containerd、CRI-O、Podman 中运行
- 反之也成立

### 6.4 从 Docker 到 K8s 的概念对照

| Docker | Kubernetes |
|--------|------------|
| Container | Container（在 Pod 内） |
| Docker Compose Service | Deployment/StatefulSet |
| Docker Compose `depends_on` | Init Container、Readiness Probe |
| Docker network | Service + CNI 网络 |
| Docker volume | PersistentVolume + PVC |
| Docker `-e ENV=xx` | ConfigMap / Secret |
| Docker Registry | 同（Harbor/DockerHub） |
| `docker run` | `kubectl run` / Deployment YAML |
| `docker logs` | `kubectl logs` |
| `docker exec` | `kubectl exec` |
| `docker stats` | `kubectl top` |

---

## 七、生产常见问题与排查

### 7.1 容器一直重启：CrashLoopBackOff

**排查步骤：**

```bash
# 1. 查看容器状态和退出码
docker ps -a
docker inspect <id> | grep -A 10 State

# 2. 查看日志
docker logs --tail 200 <id>

# 3. 检查是否 OOM
docker inspect <id> | grep -i oom

# 4. 手动进入调试
docker run -it --entrypoint=sh <image>
```

**常见原因：**

- 配置错误：数据库连不上、配置文件缺失
- OOM：内存不足
- 依赖服务未就绪：数据库还没启动就 connect
- ENTRYPOINT 命令不存在或无执行权限

### 7.2 磁盘被 Docker 撑满

**排查：**

```bash
docker system df -v
```

**常见元凶：**

1. **日志无限增长**：配置日志轮转（见 3.3）
2. **已停止容器堆积**：`docker container prune`
3. **无用镜像**：`docker image prune -a`
4. **孤儿 Volume**：`docker volume prune`
5. **构建缓存**：`docker builder prune`

### 7.3 镜像拉取慢

**解决方案：**

1. **配置国内镜像加速**：`/etc/docker/daemon.json`

    ```json
    {
      "registry-mirrors": [
        "https://docker.mirrors.ustc.edu.cn",
        "https://hub-mirror.c.163.com"
      ]
    }
    ```

2. **使用私有镜像仓库**：把公共镜像同步到内网 Harbor

3. **预热镜像**：CI 提前把镜像分发到各节点

### 7.4 容器内网络不通

**排查步骤：**

```bash
# 1. 容器内 ping 网关（docker0）
docker exec <id> ping 172.17.0.1

# 2. 容器内查网络配置
docker exec <id> ip addr
docker exec <id> ip route

# 3. 检查 iptables
iptables -t nat -L

# 4. 检查 net.ipv4.ip_forward
sysctl net.ipv4.ip_forward
# 必须为 1
```

**常见原因：**

- 宿主机没开启 IP 转发：`sysctl -w net.ipv4.ip_forward=1`
- 防火墙拦截：检查 iptables / firewalld
- DNS 问题：容器内 `/etc/resolv.conf`

### 7.5 容器时区不对

**问题：** 容器内时间是 UTC，与宿主机差 8 小时。

**解决：**

```dockerfile
# 方法 1：Dockerfile 中
ENV TZ=Asia/Shanghai
RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 方法 2：运行时挂载宿主机时区
docker run -v /etc/localtime:/etc/localtime:ro nginx
```

### 7.6 `docker stop` 卡 10 秒才结束

**原因：** 容器主进程没有处理 SIGTERM 信号，Docker 等 10 秒（默认 grace period）后发 SIGKILL 强杀。

**解决：**

1. 应用侧正确处理 SIGTERM，做优雅关闭
2. 用数组形式的 CMD/ENTRYPOINT（exec form），避免被 sh 包装
3. 用 `tini`/`dumb-init` 作为 PID 1
4. 或加 `docker run --init`

### 7.7 Java 应用 OOMKilled

**排查：**

```bash
docker inspect <id> | grep -i oom
# "OOMKilled": true

dmesg | grep -i "killed process"
```

**原因分析：**

- JVM 堆 + Metaspace + 直接内存 + 线程栈 > 容器内存限制
- 老 JVM 不识别 Cgroups，堆分配过大

**解决：**

- 用 Java 11+，启用 `-XX:+UseContainerSupport`
- 设置合理的 `-Xmx`（容器内存的 70-75%）
- 监控 JVM 直接内存（Netty、NIO 大量使用）

---

## 八、Docker 生产实战面试题

### 8.1 你们生产用 Docker 的镜像有多大？怎么优化的？

答：我们的 Java 微服务镜像**从初期的 800MB 优化到 200MB 以内**，主要做了：

1. **多阶段构建**：构建阶段用 `maven`，运行阶段用 `eclipse-temurin:17-jre-alpine`
2. **精简基础镜像**：从 `openjdk:17` 换到 `-jre-alpine`
3. **合并 RUN 指令 + 清理缓存**：`apt clean && rm -rf /var/lib/apt/lists/*`
4. **`.dockerignore`**：排除 target/、tests/、docs/ 等
5. **分层优化**：先 copy pom.xml 装依赖，再 copy 源码，充分利用缓存
6. **使用 BuildKit + cache 挂载**：加速构建

Go 服务用 `scratch` 基础镜像 + 静态编译，可以做到 10MB 以内。

### 8.2 生产环境 Docker 的资源限制怎么设置？

答：**必须**为每个容器配置资源限制：

- **CPU**：`--cpus=<核数>`，一般按服务的历史 CPU 使用峰值 × 1.5 设置
- **内存**：`-m <大小>`，Java 服务要留 20-30% 给堆外内存和 Metaspace
- **PID**：`--pids-limit=200`，防止 fork 炸弹
- **禁用 swap**：`--memory-swap` 等于 `-m`，避免用 swap 导致抖动

Java 应用还要配合：`-XX:MaxRAMPercentage=75.0`。

### 8.3 你们的日志方案是什么？

答：我们用 **EFK（Elasticsearch + Fluent Bit + Kibana）** 方案：

1. **应用侧**：所有日志打到 stdout/stderr，用 JSON 格式（包含 traceId、level、msg）
2. **采集侧**：K8s 上用 DaemonSet 部署 Fluent Bit，采集节点上所有容器日志（`/var/log/containers/`）
3. **传输**：Fluent Bit → Kafka（削峰）→ Logstash → ES
4. **查询**：Kibana 查询 + Grafana 看板
5. **日志轮转**：`json-file` 驱动配置 `max-size=100m`，`max-file=3`
6. **降级**：日志量太大时按级别过滤，只采集 WARN/ERROR

### 8.4 Docker 部署时怎么保证平滑上线？

答：单机 Docker 部署可以用**蓝绿部署**或**滚动更新**：

**蓝绿部署（Compose）：**

```bash
# 新版本先启动为 backend-new
docker compose up -d backend-new

# 健康检查通过后
# 修改 nginx 上游或 SLB，把流量切到新版本

# 旧版本下线
docker compose stop backend-old
```

**滚动更新（Swarm/K8s）：**

- K8s Deployment 默认滚动更新，可以配置 `maxSurge=1`、`maxUnavailable=0`
- 结合 readinessProbe 确保新 Pod 就绪后才接流量
- 应用侧处理 SIGTERM，优雅关闭

### 8.5 如果发现某个容器 CPU 飙高，怎么排查？

答：分五步：

1. **确认是哪个容器**：`docker stats` 找到高 CPU 容器
2. **进入容器**：`docker exec -it <id> bash`
3. **找高 CPU 线程**：`top -H -p 1`，记住 TID
4. **导出线程栈**：Java 服务 `jstack 1 > stack.txt`
5. **分析栈**：把 TID 转 16 进制，在 stack.txt 中定位是哪个线程、执行什么代码

**常见原因：** 死循环、GC 频繁、锁竞争、批处理逻辑。

### 8.6 容器内 OOMKilled 但 JVM 没报 OutOfMemoryError，为什么？

答：因为 **JVM 报 OOM 是堆内存溢出**（`-Xmx` 满了），而 **Linux OOMKiller 触发是整个容器进程的 RSS 超过 Cgroups 限制**。

一个 Java 进程的内存 = 堆（Heap） + Metaspace + 直接内存（Direct Memory） + JIT 缓存 + 线程栈 × 线程数 + native 内存等。

即使堆没满，堆外内存（如 Netty 用 DirectByteBuffer、大量线程）可能撑爆容器限制，然后被 Linux 内核 OOMKiller 杀掉。此时 JVM 是被 SIGKILL 强杀的，来不及输出任何日志。

**排查方向：**

- 用 NMT（Native Memory Tracking）：`-XX:NativeMemoryTracking=summary`
- 监控 `RSS - Heap` 的差值
- 关注直接内存、Metaspace、线程数

### 8.7 生产 Docker 网络方案怎么选？

答：分场景：

- **单机 Compose**：默认 bridge 网络够用，注意用自定义 bridge 支持 DNS
- **性能极致**：`--network=host`，省 NAT 开销
- **多机 Swarm**：overlay 网络
- **K8s 集群**：CNI 插件
  - 中小规模：Flannel（简单，VXLAN）
  - 大规模：Calico（BGP 路由，支持 NetworkPolicy）
  - 追求极致性能：Cilium（eBPF）

### 8.8 你们怎么保证镜像的安全？

答：**四层防护**：

1. **构建时**：
   - 用官方或可信基础镜像
   - Dockerfile 里 USER 非 root
   - 最小化依赖，减少攻击面
2. **入库时**：
   - Harbor 集成 Trivy 自动扫描
   - 高危漏洞禁止 push
   - 镜像签名（Cosign）
3. **拉取时**：
   - 只从内网 Harbor 拉取
   - `imagePullPolicy: IfNotPresent`
4. **运行时**：
   - `--read-only`、`--cap-drop=ALL`
   - `--security-opt=no-new-privileges`
   - Pod Security Standards（K8s）
   - 定期用 Falco 监控容器行为异常
