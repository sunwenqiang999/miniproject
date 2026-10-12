# Docker 面试知识点总结

## 阅读建议

Docker 面试知识点已按复习层次拆分为三类文档：

| 文档 | 适合阶段 | 内容重点 |
|------|----------|----------|
| [`docker面试知识点.md`](docker面试知识点.md) | 总览复习 | Docker 基础、镜像/容器/仓库、常用命令、Dockerfile、整体知识框架 |
| [`docker原理机制面试知识点.md`](docker原理机制面试知识点.md) | 原理深入 | Namespace、Cgroups、UnionFS、镜像分层、存储驱动、网络模式、容器运行时 |
| [`docker生产实战面试知识点.md`](docker生产实战面试知识点.md) | 进阶实战 | 镜像瘦身、多阶段构建、Compose、日志监控、安全加固、性能调优、与 K8s 的关系 |

建议先掌握第一篇 Docker 基础和命令，再深入学习原理机制，最后背诵生产实战的回答模板。

---

## 一、Docker 基础概念

### 1.1 什么是 Docker？

Docker 是一个开源的**应用容器引擎**，基于 Go 语言实现，利用 Linux 内核的 **Namespace**、**Cgroups**、**联合文件系统（UnionFS）** 等技术，为应用提供进程级别的隔离环境。

**核心特点：**

- 轻量级：容器共享宿主机内核，启动秒级、开销远小于虚拟机
- 一致性：镜像打包了应用及其所有依赖，"一次构建，到处运行"
- 隔离性：每个容器有独立的进程、网络、文件系统视图
- 可移植性：镜像遵循 OCI 标准，可在任意支持 Docker 的环境运行
- 版本化：镜像分层管理，支持增量构建和分发
- 生态完善：Docker Hub 镜像仓库、Compose 编排、Swarm 集群等

### 1.2 Docker 解决了什么问题？

Docker 主要解决了传统软件交付中的四个痛点：

| 痛点 | 传统方式 | Docker 方式 |
|------|----------|-------------|
| 环境不一致 | 开发能跑、测试不能跑 | 镜像打包一切依赖，环境一致 |
| 部署繁琐 | 手动装 JDK、装依赖、改配置 | `docker run` 一条命令启动 |
| 资源浪费 | 每台机器只跑一个应用 | 一台机器可跑几十个容器 |
| 扩缩容慢 | 虚拟机启动分钟级 | 容器启动秒级 |

### 1.3 Docker 和虚拟机的区别？

这是 Docker 面试**必考题**，一定要能画出对比图。

```text
虚拟机架构                       Docker 容器架构
+----------------+              +----------------+
|   App A | App B|              | App A | App B  |
+----------------+              +----------------+
| Guest OS| Guest|              |  Bins/Libs     |
|         |  OS  |              +----------------+
+----------------+              |  Docker Engine |
|   Hypervisor   |              +----------------+
+----------------+              |   Host OS      |
|   Host OS      |              +----------------+
+----------------+              |   Hardware     |
|   Hardware     |              +----------------+
+----------------+
```

**对比：**

| 对比项 | 虚拟机 | Docker 容器 |
|--------|--------|-------------|
| 隔离级别 | 硬件级（Hypervisor） | 进程级（Namespace/Cgroups） |
| 启动速度 | 分钟级 | 秒级 |
| 资源占用 | GB 级（包含完整 Guest OS） | MB 级（共享宿主机内核） |
| 性能损耗 | 有明显损耗 | 接近原生 |
| 系统支持 | 可运行任意 OS | 只能运行与宿主机内核兼容的 OS |
| 单机密度 | 几个到几十个 | 几十到上百个 |
| 隔离强度 | 强 | 相对弱（共享内核） |

**面试回答：**

> Docker 容器和虚拟机的核心区别在于隔离层次。虚拟机通过 Hypervisor 虚拟出完整硬件，每个虚拟机跑一套 Guest OS，隔离性强但资源开销大、启动慢。Docker 容器则是操作系统层的虚拟化，通过 Linux 的 Namespace 隔离进程视图、Cgroups 限制资源，所有容器共享宿主机内核。因此 Docker 更轻量、启动更快、单机密度更高，但隔离强度不如虚拟机，且只能运行与宿主机内核兼容的系统。

### 1.4 Docker 的三大核心概念

Docker 的三个核心对象是理解一切的基础：

| 概念 | 类比 | 说明 |
|------|------|------|
| Image（镜像） | 类（Class） | 只读的模板，包含应用和依赖，可派生出多个容器 |
| Container（容器） | 对象（Object） | 镜像的运行实例，有独立的读写层和运行时状态 |
| Repository（仓库） | Maven 中央仓库 | 集中存放镜像的地方，如 Docker Hub、Harbor |

三者关系：

```text
Repository（仓库）
    │
    ├── Image A:v1  ──┐
    │                 ├──> docker run ──> Container 1
    ├── Image A:v2  ──┤                ──> Container 2
    │                 └──> docker run ──> Container 3
    └── Image B:v1
```

### 1.5 Docker 的整体架构

Docker 采用 **C/S 架构**：

```text
+-----------------------------------------------------+
|                   Docker Client                     |
|   docker build / docker run / docker pull ...       |
+-----------------------------------------------------+
                        │  REST API / Unix Socket
                        ▼
+-----------------------------------------------------+
|                   Docker Daemon (dockerd)           |
|                                                     |
|  +---------+   +----------+   +------------------+  |
|  | Images  |   | Containers|  | Networks/Volumes |  |
|  +---------+   +----------+   +------------------+  |
|                        │                            |
|                        ▼                            |
|                   containerd                        |
|                        │                            |
|                        ▼                            |
|                    runc / OCI                       |
+-----------------------------------------------------+
                        │
                        ▼
                    Linux Kernel
             (Namespace / Cgroups / UnionFS)
                        │
                        ▼
+-----------------------------------------------------+
|                Docker Registry                      |
|             （Docker Hub / Harbor）                 |
+-----------------------------------------------------+
```

**核心组件：**

- **Docker Client**：命令行工具 `docker`，通过 REST API 与 Daemon 通信
- **Docker Daemon（dockerd）**：后台守护进程，管理镜像、容器、网络、卷
- **containerd**：容器运行时管理组件，负责容器生命周期
- **runc**：OCI 标准的最底层运行时，真正调用内核 API 创建容器
- **Registry**：镜像仓库，用于存储和分发镜像

---

## 二、Docker 镜像（Image）

### 2.1 什么是 Docker 镜像？

Docker 镜像是一个**只读的文件系统模板**，包含运行应用所需的一切：代码、运行时、库、环境变量、配置文件。

镜像的最重要特性是**分层（Layer）**：

```text
+-----------------------------+  <- 应用层：COPY app.jar
+-----------------------------+  <- 依赖层：RUN apt-get install
+-----------------------------+  <- JDK 层：FROM openjdk:17
+-----------------------------+  <- 基础镜像层：debian:bullseye
```

每一层都是只读的，多个镜像可以**共享底层**，节省磁盘和网络传输。

### 2.2 镜像常用命令

```bash
# 从仓库拉取镜像
docker pull nginx:1.25

# 查看本地镜像
docker images
docker image ls

# 查看镜像详情（含分层）
docker inspect nginx:1.25
docker history nginx:1.25

# 给镜像打新标签
docker tag nginx:1.25 myregistry.com/nginx:v1

# 推送镜像到远程仓库
docker push myregistry.com/nginx:v1

# 删除本地镜像
docker rmi nginx:1.25

# 清理无用镜像（悬空镜像）
docker image prune
docker image prune -a  # 清理所有未被容器使用的镜像

# 从容器创建镜像
docker commit <container_id> myimage:v1

# 保存/加载镜像（离线传输）
docker save -o nginx.tar nginx:1.25
docker load -i nginx.tar
```

### 2.3 `docker history` 有什么用？

`docker history` 可以查看镜像每一层的构建历史，是排查镜像体积问题的利器。

```bash
docker history nginx:1.25
```

**返回示例：**

```text
IMAGE          CREATED        CREATED BY                       SIZE
605c77e624dd   3 days ago     /bin/sh -c #(nop) CMD ["nginx"]  0B
<missing>      3 days ago     /bin/sh -c #(nop) EXPOSE 80      0B
<missing>      3 days ago     /bin/sh -c apt-get update && ... 54.3MB
<missing>      3 days ago     /bin/sh -c #(nop) ADD file:...   80.4MB
```

从上到下依次是最新层到最底层。可以看到每一步 Dockerfile 指令产生了多大的层。

### 2.4 `docker save/load` 和 `docker export/import` 的区别？

这是一道经典的**易混淆题**。

| 命令 | 操作对象 | 是否保留分层 | 是否保留元数据 |
|------|----------|--------------|----------------|
| `docker save` | 镜像 | 保留 | 保留（历史、层信息） |
| `docker load` | 镜像 | 保留 | 保留 |
| `docker export` | 容器 | **不保留**（合并为一层） | 不保留（无 CMD/ENV 等） |
| `docker import` | 容器 tar | 不保留 | 需要重新指定 CMD |

**面试回答：**

> `save`/`load` 是针对镜像的完整备份，会保留镜像所有分层和构建历史；`export`/`import` 是针对容器文件系统的快照，会把容器所有层合并成一层，且丢失原镜像的元数据。生产中做镜像离线传输应该用 `save`/`load`。

### 2.5 镜像标签 latest 有什么坑？

`latest` 只是一个**默认标签名**，并不代表"最新版本"。它只是当你 `docker pull nginx` 不指定 tag 时的默认值。

**生产陷阱：**

- 不同时间 pull 到的 `latest` 可能是不同版本 —— 不可复现
- 镜像维护者忘记更新 `latest`，可能拿到旧版本
- K8s 中默认 `imagePullPolicy: Always` + `latest` 会每次都拉最新，可能导致同一 Deployment 的多个 Pod 版本不一致

**最佳实践：** 生产环境**永远不要用 `latest`**，一定要指定明确的版本号，如 `nginx:1.25.3`，甚至加上摘要 `nginx@sha256:xxx`。

---

## 三、Docker 容器（Container）

### 3.1 什么是容器？

容器是镜像的**运行实例**。可以简单理解为：

```text
容器 = 镜像的只读层 + 一个可写层 + 运行时进程 + 独立命名空间
```

同一个镜像可以启动**多个容器**，每个容器有独立的可写层和进程。

### 3.2 容器生命周期

```text
    +--------+
    | Created|  <- docker create
    +--------+
        │ docker start
        ▼
    +--------+     docker pause    +---------+
    | Running|-------------------->|  Paused |
    +--------+<--------------------+---------+
        │  docker stop / kill      docker unpause
        ▼
    +--------+
    | Stopped|
    +--------+
        │ docker rm
        ▼
    +--------+
    | Deleted|
    +--------+
```

对应命令：

```bash
docker create nginx        # 创建但不启动
docker start <id>          # 启动已创建的容器
docker run nginx           # create + start 的组合
docker stop <id>           # 优雅停止（先发 SIGTERM，10s 后发 SIGKILL）
docker kill <id>           # 立即强杀（SIGKILL）
docker restart <id>        # 重启
docker pause <id>          # 暂停（进程冻结）
docker unpause <id>        # 恢复
docker rm <id>             # 删除已停止的容器
docker rm -f <id>          # 强制删除运行中的容器
```

### 3.3 `docker run` 高频参数

```bash
docker run \
  -d \                              # 后台运行（detach）
  --name mynginx \                  # 指定容器名
  -p 8080:80 \                      # 端口映射 宿主机:容器
  -v /data/nginx:/usr/share/nginx/html \  # 挂载卷
  -e ENV=prod \                     # 环境变量
  --restart=always \                # 重启策略
  --network=mynet \                 # 指定网络
  --cpus=2 \                        # CPU 限制
  -m 512m \                         # 内存限制
  --rm \                            # 容器退出后自动删除
  nginx:1.25
```

**重启策略 `--restart`：**

| 策略 | 说明 |
|------|------|
| `no` | 默认，不重启 |
| `on-failure[:max]` | 非 0 退出时重启，可指定最大次数 |
| `always` | 无论如何都重启（含 Docker 重启） |
| `unless-stopped` | 除非手动 stop，否则重启 |

### 3.4 容器常用命令

```bash
# 查看容器
docker ps               # 运行中的容器
docker ps -a            # 全部容器（含已停止）
docker ps -q            # 只显示容器 ID

# 进入容器
docker exec -it <id> bash        # 推荐，会启动新进程
docker attach <id>               # 附加到主进程（Ctrl+C 会杀死容器！）

# 查看日志
docker logs <id>                 # 查看全部日志
docker logs -f <id>              # 实时跟踪（follow）
docker logs --tail 100 <id>      # 最后 100 行
docker logs --since 10m <id>     # 最近 10 分钟

# 查看容器详情
docker inspect <id>              # 完整 JSON 详情
docker top <id>                  # 查看容器内进程
docker stats                     # 实时资源占用

# 文件拷贝
docker cp <id>:/etc/nginx.conf ./  # 从容器拷贝到宿主机
docker cp ./config <id>:/etc/       # 从宿主机拷贝到容器

# 查看容器变更
docker diff <id>                 # 查看容器相对于镜像的文件变化
```

### 3.5 `docker exec` 和 `docker attach` 的区别？

这是一道经典**易混淆题**。

| 对比项 | docker exec | docker attach |
|--------|-------------|----------------|
| 本质 | 在容器内启动**新进程** | 附加到容器的**主进程**（PID 1） |
| 退出影响 | 退出后容器继续运行 | Ctrl+C 会**杀死容器主进程**，导致容器退出 |
| 使用场景 | 进容器排查问题 | 查看主进程输出（一般不推荐） |
| 典型用法 | `docker exec -it <id> bash` | 极少使用 |

**面试回答：**

> `docker exec` 是在运行中的容器里**启动一个新进程**，退出这个新进程不会影响容器；`docker attach` 是把当前终端**附加到容器的主进程**（PID 1）的标准输入输出，用户 Ctrl+C 会直接把信号发给主进程，可能导致容器退出。所以进容器排查问题应该用 `docker exec -it <id> bash`。

### 3.6 容器为什么退出了？如何排查？

容器退出的**根本原因是主进程（PID 1）退出了**。Docker 容器不像虚拟机，它只跟随 PID 1 生死。

**排查步骤：**

```bash
# 1. 查看容器退出码
docker ps -a
# STATUS: Exited (137) 5 minutes ago

# 2. 查看容器日志
docker logs <id>

# 3. 查看容器详细信息
docker inspect <id> | grep -A 10 "State"
```

**常见退出码：**

| 退出码 | 含义 | 常见原因 |
|--------|------|----------|
| 0 | 正常退出 | 进程主动结束 |
| 1 | 应用错误 | 代码抛异常、启动失败 |
| 125 | Docker 本身错误 | docker 命令参数错误 |
| 126 | 命令不可执行 | 权限问题、格式问题 |
| 127 | 命令未找到 | ENTRYPOINT 命令不存在 |
| 137 | 被 SIGKILL 杀死 | OOM Killer / `docker kill` |
| 139 | 段错误 SIGSEGV | 程序 bug |
| 143 | 被 SIGTERM 杀死 | `docker stop` 优雅退出 |

**面试常问：容器启动后立刻退出是什么原因？**

答：Docker 容器的生命周期跟随主进程（PID 1）。如果 CMD/ENTRYPOINT 是一个前台不阻塞的进程，或者是一个执行完就退出的脚本，容器就会立即退出。常见坑是执行 `nginx` 时没有加 `-g "daemon off;"`，nginx 会以 daemon 模式后台运行，导致前台进程退出，容器随之退出。解决方案是让主进程在前台运行。

---

## 四、Docker 仓库（Registry）

### 4.1 什么是 Docker Registry？

Docker Registry 是**集中存储和分发镜像的服务**。

常见的 Registry：

| Registry | 说明 |
|----------|------|
| Docker Hub | 官方公共仓库，默认拉取源 |
| Harbor | VMware 开源的企业级私有仓库，最流行 |
| GitHub Container Registry | GitHub 提供的镜像仓库 |
| 阿里云/腾讯云 ACR | 各大云厂商的镜像服务 |
| Nexus / JFrog | 通用制品仓库，支持 Docker 镜像 |

### 4.2 镜像名格式

一个完整的镜像名由四部分组成：

```text
[registry-host]:[port]/[namespace]/[repository]:[tag]
     │              │        │          │
     └── 仓库地址   └── 端口 └── 命名空间 └── 镜像名 └── 版本标签
```

例如：

```text
harbor.company.com:5000/backend/user-service:v1.2.3
```

**默认值：**

- 不指定 registry：从 `docker.io`（Docker Hub）拉取
- 不指定 namespace：默认 `library`（Docker Hub 官方镜像）
- 不指定 tag：默认 `latest`

例如 `docker pull nginx` 实际等价于 `docker pull docker.io/library/nginx:latest`。

### 4.3 私有仓库操作

```bash
# 登录私有仓库
docker login harbor.company.com

# 打标签（必须打 registry 前缀）
docker tag myapp:v1 harbor.company.com/backend/myapp:v1

# 推送
docker push harbor.company.com/backend/myapp:v1

# 拉取
docker pull harbor.company.com/backend/myapp:v1

# 登出
docker logout harbor.company.com
```

**注意：** 使用非 HTTPS 的私有仓库需要在 `/etc/docker/daemon.json` 配置：

```json
{
  "insecure-registries": ["harbor.company.com:5000"]
}
```

修改后需要重启 Docker：`systemctl restart docker`。

---

## 五、Dockerfile 详解

### 5.1 什么是 Dockerfile？

Dockerfile 是**构建镜像的脚本文件**，包含一系列指令，Docker 会按顺序执行这些指令来生成镜像。

**核心思想：** 每一条指令生成一个**镜像层**（Layer），层之间只保存差异。

### 5.2 Dockerfile 常用指令

| 指令 | 作用 | 示例 |
|------|------|------|
| `FROM` | 指定基础镜像 | `FROM openjdk:17-slim` |
| `MAINTAINER` | 维护者信息（已废弃，用 LABEL） | `MAINTAINER xxx` |
| `LABEL` | 镜像元数据 | `LABEL version="1.0"` |
| `RUN` | 构建时执行命令，生成新层 | `RUN apt-get install -y curl` |
| `COPY` | 复制本地文件到镜像 | `COPY app.jar /app/` |
| `ADD` | 复制文件（支持 URL 和自动解压 tar） | `ADD https://xx.tar.gz /` |
| `WORKDIR` | 设置工作目录 | `WORKDIR /app` |
| `ENV` | 设置环境变量 | `ENV JAVA_OPTS="-Xmx512m"` |
| `ARG` | 构建时变量（不进入镜像） | `ARG VERSION=1.0` |
| `EXPOSE` | 声明容器暴露的端口（仅文档作用） | `EXPOSE 8080` |
| `VOLUME` | 声明匿名卷挂载点 | `VOLUME /data` |
| `USER` | 指定运行用户 | `USER nobody` |
| `CMD` | 容器默认执行命令（可被覆盖） | `CMD ["java", "-jar", "app.jar"]` |
| `ENTRYPOINT` | 容器入口命令（不易被覆盖） | `ENTRYPOINT ["java", "-jar"]` |
| `HEALTHCHECK` | 健康检查 | `HEALTHCHECK CMD curl localhost` |

### 5.3 一个完整的 Dockerfile 示例

以 Spring Boot 应用为例：

```dockerfile
# 基础镜像
FROM openjdk:17-slim

# 元数据
LABEL maintainer="dev@company.com"
LABEL version="1.0"

# 环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV TZ="Asia/Shanghai"

# 工作目录
WORKDIR /app

# 复制 jar 包
COPY target/user-service-*.jar app.jar

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 使用非 root 用户
USER 1000

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

构建镜像：

```bash
docker build -t user-service:v1 .
docker build -f Dockerfile.prod -t user-service:v1 .
docker build --no-cache -t user-service:v1 .    # 不使用缓存
docker build --build-arg VERSION=1.2 -t user-service:v1 .  # 传递构建参数
```

### 5.4 `CMD` 和 `ENTRYPOINT` 的区别？

这是 Dockerfile 中**最高频**的面试题。

| 对比项 | CMD | ENTRYPOINT |
|--------|-----|------------|
| 作用 | 容器启动时的**默认命令** | 容器启动时的**固定入口** |
| 是否可覆盖 | `docker run image cmd` 会**完全覆盖** | 不会被覆盖，`docker run` 后的参数会作为**参数**传递 |
| 是否可组合 | 可作为 ENTRYPOINT 的默认参数 | 可与 CMD 组合使用 |

**四种组合方式：**

```dockerfile
# 方式 1：只用 CMD
CMD ["nginx", "-g", "daemon off;"]
# docker run image → 执行 nginx -g daemon off;
# docker run image echo hi → 执行 echo hi（CMD 被覆盖）

# 方式 2：只用 ENTRYPOINT
ENTRYPOINT ["nginx", "-g", "daemon off;"]
# docker run image → 执行 nginx -g daemon off;
# docker run image -v → 执行 nginx -g daemon off; -v（-v 追加）

# 方式 3：ENTRYPOINT + CMD（最佳实践）
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--spring.profiles.active=prod"]
# docker run image → java -jar app.jar --spring.profiles.active=prod
# docker run image --spring.profiles.active=dev → 覆盖为 dev

# 方式 4：shell 形式（不推荐）
CMD nginx -g "daemon off;"
# 会被 shell 包装：/bin/sh -c "nginx -g \"daemon off;\""
# 主进程变成 sh，收不到 SIGTERM 信号！
```

**面试回答：**

> `CMD` 提供容器启动时的默认命令，可以被 `docker run` 覆盖；`ENTRYPOINT` 定义容器的固定入口，`docker run` 后的参数会作为参数追加。生产中最佳实践是用 `ENTRYPOINT` 定义命令本身，用 `CMD` 定义默认参数，这样既有默认行为，又能灵活覆盖参数。另外一定要用**数组形式（exec form）**，避免 shell 形式导致主进程变成 `sh`、无法接收 SIGTERM 信号的问题。

### 5.5 `COPY` 和 `ADD` 的区别？

| 对比项 | COPY | ADD |
|--------|------|-----|
| 复制本地文件 | 支持 | 支持 |
| 从 URL 下载 | 不支持 | 支持 |
| 自动解压 tar | 不支持 | 支持（本地 tar 会自动解压） |
| 推荐场景 | 一般场景，官方推荐 | 需要下载或解压时 |

**最佳实践：** 优先用 `COPY`，语义更清晰。只有在需要自动解压 tar 时才用 `ADD`。下载文件推荐用 `RUN curl` 或 `wget` 更明确。

### 5.6 `ENV` 和 `ARG` 的区别？

| 对比项 | ENV | ARG |
|--------|-----|-----|
| 作用域 | 构建时 + 运行时 | 仅构建时 |
| 是否进入最终镜像 | 是 | 否 |
| 是否可通过 `--build-arg` 传值 | 否 | 是 |
| 是否可被容器 env 覆盖 | 是 | N/A |

**示例：**

```dockerfile
ARG VERSION=1.0                  # 构建时变量
ENV APP_VERSION=${VERSION}       # 转换为环境变量，运行时可见

FROM openjdk:17
COPY app-${VERSION}.jar app.jar
```

```bash
docker build --build-arg VERSION=1.2 -t myapp:1.2 .
```

### 5.7 Dockerfile 分层机制与缓存

Dockerfile 的**每一条指令**都会产生一个新层，Docker 构建时会**从上到下**依次执行，并且会缓存每一层的结果。

**缓存命中规则：**

- 从第一条指令开始，如果内容与上次构建**完全相同**，则命中缓存，直接复用
- 一旦某一层缓存失效，**后续所有层都会重新构建**

**指令顺序对缓存至关重要：**

```dockerfile
# 反例：应用代码变化会导致依赖重新安装
FROM node:18
COPY . /app
WORKDIR /app
RUN npm install
CMD ["node", "server.js"]

# 正例：先复制依赖清单，再安装依赖，最后复制代码
FROM node:18
WORKDIR /app
COPY package*.json ./       # 依赖清单不变时，下面这层命中缓存
RUN npm install             # 不需要重新安装依赖
COPY . .                    # 代码变化只影响这一层
CMD ["node", "server.js"]
```

**核心原则：** **变化频率低的指令放前面，变化频率高的指令放后面**。

### 5.8 减少镜像层数：合并 RUN 指令

每一条 `RUN` 都会产生一层，多条 `RUN` 会导致镜像层数过多，且中间产物（如 apt 缓存）也会被永久保留。

```dockerfile
# 反例：3 层，且 apt cache 会永久留在镜像里
RUN apt-get update
RUN apt-get install -y curl
RUN rm -rf /var/lib/apt/lists/*

# 正例：1 层，apt cache 不会进入最终镜像
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*
```

### 5.9 `.dockerignore` 文件

类似 `.gitignore`，用于排除构建上下文中不需要传给 Docker Daemon 的文件，可以显著提升构建速度并减小镜像。

```gitignore
# .dockerignore
.git
node_modules
*.log
target/
Dockerfile
.env
README.md
```

**面试要点：** 构建时 Docker 会把整个上下文（当前目录）打包发送给 Daemon，即使你 Dockerfile 中没用到，也会被传输。因此**大项目一定要配置 .dockerignore**。

---

## 六、Docker 数据管理：Volume 与 Bind Mount

### 6.1 为什么需要数据管理？

容器的写操作发生在**可写层**中，容器删除后**可写层也会被删除**。对于需要持久化的数据（数据库、日志、上传文件），必须存到容器外部。

### 6.2 三种数据挂载方式

| 类型 | 说明 | 使用场景 |
|------|------|----------|
| **Volume（数据卷）** | Docker 管理的挂载，存在宿主机 `/var/lib/docker/volumes/` 下 | 数据库数据、需要备份的应用数据 |
| **Bind Mount（绑定挂载）** | 直接把宿主机路径挂载到容器 | 开发时热更新、宿主机日志目录 |
| **tmpfs Mount** | 内存中的挂载，容器停止后消失 | 敏感临时数据（如密钥） |

### 6.3 常用命令

```bash
# 创建卷
docker volume create mydata

# 查看卷
docker volume ls
docker volume inspect mydata

# 挂载卷到容器
docker run -d --name mysql \
  -v mydata:/var/lib/mysql \
  mysql:8.0

# Bind Mount
docker run -d --name nginx \
  -v /host/data:/usr/share/nginx/html \
  nginx

# 只读挂载
docker run -v /host/config:/etc/config:ro nginx

# tmpfs 挂载
docker run --tmpfs /tmp:size=100m nginx

# 删除未使用的卷
docker volume prune
```

### 6.4 Volume 和 Bind Mount 的区别？

| 对比项 | Volume | Bind Mount |
|--------|--------|-------------|
| 管理方 | Docker 管理 | 用户管理 |
| 位置 | `/var/lib/docker/volumes/` | 用户指定的任意路径 |
| 可移植性 | 好（跨主机） | 差（依赖宿主机路径） |
| 备份 | 容易（`docker volume` 命令） | 需要手动 |
| 性能 | 与 Bind Mount 相当 | 与 Volume 相当 |
| 推荐场景 | 生产、持久化数据 | 开发调试、宿主机配置文件 |

**面试回答：**

> Volume 是 Docker 官方推荐的数据管理方式，由 Docker 统一管理，位置固定、易于备份、可跨主机迁移；Bind Mount 是直接把宿主机的某个路径挂载到容器，灵活但依赖宿主机目录结构，可移植性差。生产环境优先用 Volume 存储数据库等持久化数据，Bind Mount 更适合开发时挂载源码或宿主机配置文件。

---

## 七、Docker 网络基础

### 7.1 Docker 四种网络模式

Docker 默认提供了四种网络模式（详细原理见 [`docker原理机制面试知识点.md`](docker原理机制面试知识点.md)）：

| 模式 | 说明 | 使用场景 |
|------|------|----------|
| `bridge`（默认） | Docker 创建虚拟网桥 docker0，容器通过 veth pair 接入 | 单机默认，容器有独立 IP |
| `host` | 容器与宿主机共享网络栈，没有网络隔离 | 追求极致网络性能 |
| `none` | 容器只有 lo 回环，没有外部网络 | 完全隔离，特殊安全场景 |
| `container` | 与另一个容器共享网络命名空间 | Sidecar 模式，如 K8s Pod |

### 7.2 常用网络命令

```bash
# 查看所有网络
docker network ls

# 创建自定义网络（推荐）
docker network create mynet
docker network create --driver bridge --subnet 172.20.0.0/16 mynet

# 容器加入指定网络
docker run --network=mynet --name web nginx

# 容器加入或退出网络
docker network connect mynet mycontainer
docker network disconnect mynet mycontainer

# 查看网络详情（含所有连接的容器）
docker network inspect mynet

# 删除网络
docker network rm mynet
docker network prune
```

### 7.3 为什么推荐用自定义 bridge 网络？

默认的 `bridge` 网络有几个缺陷：

| 特性 | 默认 bridge | 自定义 bridge |
|------|-------------|----------------|
| 容器间通过名字访问 | **不支持**，只能通过 IP | **支持**，自动 DNS 解析 |
| 隔离性 | 所有容器混在一起 | 每个自定义网络相互隔离 |
| 动态添加/移除容器 | 需要重启容器 | 可动态 `connect`/`disconnect` |

**最佳实践：** 相关联的一组容器（如 web + db）应该放到同一个自定义网络中，通过服务名直接通信：

```bash
docker network create myapp
docker run -d --name db --network myapp mysql:8.0
docker run -d --name web --network myapp -e DB_HOST=db myweb:v1
# web 容器内可以直接通过 hostname "db" 访问 MySQL
```

---

## 八、Docker 高频命令速查表

### 8.1 镜像相关

```bash
docker pull <image>                    # 拉取镜像
docker push <image>                    # 推送镜像
docker images                          # 查看本地镜像
docker rmi <image>                     # 删除镜像
docker tag <src> <dst>                 # 打标签
docker build -t <name>:<tag> .         # 构建镜像
docker history <image>                 # 查看镜像分层
docker inspect <image>                 # 查看镜像详情
docker save -o file.tar <image>        # 导出镜像
docker load -i file.tar                # 导入镜像
docker image prune -a                  # 清理无用镜像
```

### 8.2 容器相关

```bash
docker run [OPTIONS] <image>           # 运行容器
docker ps [-a]                         # 查看容器
docker start/stop/restart <id>         # 生命周期操作
docker rm [-f] <id>                    # 删除容器
docker exec -it <id> bash              # 进入容器
docker logs [-f] [--tail N] <id>       # 查看日志
docker stats                           # 实时资源使用
docker inspect <id>                    # 容器详情
docker cp <src> <id>:<dst>             # 文件拷贝
docker top <id>                        # 容器内进程
docker container prune                 # 清理已停止容器
```

### 8.3 网络与卷

```bash
docker network ls / create / rm        # 网络管理
docker volume ls / create / rm         # 卷管理
docker system df                       # 磁盘占用统计
docker system prune -a --volumes       # 全面清理（慎用）
```

---

## 九、高频面试题速答

### 9.1 Docker 是什么？和虚拟机有什么区别？

见 [`1.3 Docker 和虚拟机的区别`](#13-docker-和虚拟机的区别)。

### 9.2 Docker 的核心组件有哪些？

答：Docker 是 C/S 架构，包含 Docker Client（命令行）、Docker Daemon（后台服务）、containerd（容器运行时管理）、runc（OCI 标准运行时）、Registry（镜像仓库）。底层依赖 Linux 内核的 Namespace、Cgroups、UnionFS 三大技术。

### 9.3 镜像和容器的关系？

答：镜像是只读的模板，容器是镜像的运行实例。可以类比为面向对象中的类和对象：一个镜像可以启动多个容器，每个容器有独立的读写层和运行时状态。容器 = 镜像的只读层 + 一个可写层 + 独立命名空间 + 运行进程。

### 9.4 CMD 和 ENTRYPOINT 的区别？

见 [`5.4 CMD 和 ENTRYPOINT 的区别`](#54-cmd-和-entrypoint-的区别)。

### 9.5 Dockerfile 构建镜像的最佳实践？

答：核心思路是**缩减层数、优化缓存、减小体积、增强安全**：

1. 变化频率低的指令放前面，充分利用缓存
2. 合并相关的 RUN 指令，减少层数
3. 用 `.dockerignore` 排除无关文件
4. 使用小的基础镜像，如 `alpine`、`distroless`、`slim`
5. 采用多阶段构建，只保留运行时需要的文件
6. 用非 root 用户运行进程
7. 用 `COPY` 而非 `ADD`
8. 用数组形式的 `ENTRYPOINT`/`CMD`（exec form）
9. 明确指定基础镜像版本，避免用 `latest`

### 9.6 容器启动后立即退出，怎么排查？

答：容器生命周期跟随 PID 1 主进程。排查步骤：

1. `docker ps -a` 查看退出码（如 137=OOM/SIGKILL、143=SIGTERM、0=正常退出）
2. `docker logs <id>` 查看日志
3. `docker inspect <id>` 查看详细状态、OOMKilled 标志
4. 检查 Dockerfile 的 CMD 是否是前台阻塞进程（比如 `nginx` 要加 `-g "daemon off;"`）
5. 用 `docker run -it <image> sh` 手动进入排查

### 9.7 如何让容器内的数据持久化？

答：使用 Volume 或 Bind Mount 挂载。生产推荐 Volume（`docker volume create` 后 `-v vol:/path` 挂载），因为它由 Docker 统一管理、便于备份、可跨主机迁移；开发调试可以用 Bind Mount（`-v /host:/container`）直接挂载宿主机路径。

### 9.8 Docker 容器之间怎么通信？

答：

1. **同一个网络内**：可通过容器名直接访问（推荐用自定义 bridge 网络）
2. **不同网络之间**：需要通过端口映射或 `docker network connect` 加入同一网络
3. **访问宿主机**：用特殊 DNS `host.docker.internal`（Mac/Win）或宿主机 IP（Linux）
4. **同一 Pod 内（K8s 场景）**：`container` 网络模式，共享网络命名空间，通过 localhost 通信
