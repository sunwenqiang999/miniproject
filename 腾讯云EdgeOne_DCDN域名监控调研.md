# 腾讯云 EdgeOne — DCDN 域名监控调研

## 一、背景

公司 DCDN（全站加速）域名分布在多家 CDN 厂商，本文档梳理**腾讯云 EdgeOne（EO）**侧提供的监控指标和对应 API 接口，为后续搭建统一监控看板提供依据。

- **CDN 域名管理平台**: https://multicdn.devops.xiaohongshu.com/cdn/domain
- **腾讯云 EdgeOne 控制台**: https://console.cloud.tencent.com/edgeone/zones
- **EdgeOne API 文档**: https://cloud.tencent.com/document/api/1552
- **筛选条件说明**: https://cloud.tencent.com/document/product/1552/98219

---

## 二、需要监控的 DCDN 域名（腾讯云EO）

以下域名来源于 https://multicdn.devops.xiaohongshu.com/cdn/domain ，筛选条件：**资源类型=DCDN，供应商=腾讯云EO**。

| ID | 域名 | 所属父域名 |
|----|------|----------|
| 138504 | tst-apple.rednote.com | rednote.com |
| 137632 | ark.rnote.com | rnote.com |
| 127385 | castle.rnote.com | rnote.com |
| 137590 | login2.xiaohongshu.com | xiaohongshu.com |
| 137583 | t3.xyrednote.com | xyrednote.com |
| 137660 | spider-tracker.rnote.com | rnote.com |
| 137624 | justitia.xiaohongshu.com | xiaohongshu.com |
| 137629 | agree.rnote.com | rnote.com |
| 137664 | city.xiaohongshu.com | xiaohongshu.com |
| 137592 | as.rednote.com | rednote.com |
| 137620 | live-go.rnote.com | rnote.com |
| 137646 | live-room.rnote.com | rnote.com |
| 137582 | t2.xyrednote.com | xyrednote.com |
| 137639 | live-room.xyrednote.com | xyrednote.com |
| 126426 | fe-resource-app.rnote.com | rnote.com |
| 137602 | redimage-hk-ak-accelerate.xiaohongshu.com | xiaohongshu.com |
| 137604 | live-mall.xyrednote.com | xyrednote.com |
| 137606 | redgarden.xiaohongshu.com | xiaohongshu.com |
| 137581 | rec.xiaohongshu.com | xiaohongshu.com |
| 141289 | www.noshlife.com | noshlife.com |

---

## 三、EdgeOne 可采集的监控指标

### 3.1 七层时序指标（核心）

通过 `DescribeTimingL7AnalysisData` 接口获取。

| 指标名称 | MetricName | 单位 | 说明 | 采集优先级 |
|---------|-----------|------|------|----------|
| EdgeOne 响应流量 | `l7Flow_outFlux` | Byte | 边缘节点响应给客户端的流量 | 高 |
| 客户端请求流量 | `l7Flow_inFlux` | Byte | 客户端发送到边缘节点的流量 | 高 |
| 访问总流量 | `l7Flow_flux` | Byte | 响应流量 + 请求流量 | 中 |
| EdgeOne 响应带宽 | `l7Flow_outBandwidth` | bps | 边缘节点响应带宽 | **高** |
| 客户端请求带宽 | `l7Flow_inBandwidth` | bps | 客户端请求带宽 | 中 |
| 访问总带宽 | `l7Flow_bandwidth` | bps | 响应带宽 + 请求带宽 | 中 |
| 访问请求数 | `l7Flow_request` | 次 | L7 请求总数 | **高** |
| 平均响应耗时 | `l7Flow_avgResponseTime` | ms | 所有请求的平均响应时间 | **高** |
| 平均首字节耗时 | `l7Flow_avgFirstByteResponseTime` | ms | 所有请求的平均 TTFB | **高** |

> **注意**: API 返回的响应耗时指标（`l7Flow_responseTime` / `l7Flow_firstByteResponseTime`）为该时间段内**所有请求耗时的总和**，需除以请求数才是每请求平均耗时。

### 3.2 七层 Top 排行指标

通过 `DescribeTopL7AnalysisData` 接口获取。MetricName 格式为 `{指标}_{维度}`。

**按请求数统计**：

| MetricName | 维度 |
|-----------|------|
| `l7Flow_request_domain` | 域名 |
| `l7Flow_request_statusCode` | 状态码 |
| `l7Flow_request_country` | 国家/地区 |
| `l7Flow_request_province` | 省份 |
| `l7Flow_request_url` | URL Path |
| `l7Flow_request_resourceType` | 资源类型 |
| `l7Flow_request_sip` | 客户端 IP |
| `l7Flow_request_referers` | Referer |
| `l7Flow_request_ua_device` | 设备类型 |
| `l7Flow_request_ua_browser` | 浏览器类型 |
| `l7Flow_request_ua_os` | 操作系统 |

**按流量统计**：

| MetricName | 维度 |
|-----------|------|
| `l7Flow_outFlux_domain` | 域名 |
| `l7Flow_outFlux_statusCode` | 状态码 |
| `l7Flow_outFlux_country` | 国家/地区 |
| `l7Flow_outFlux_province` | 省份 |
| `l7Flow_outFlux_url` | URL Path |
| `l7Flow_outFlux_resourceType` | 资源类型 |
| `l7Flow_outFlux_sip` | 客户端 IP |

### 3.3 其他可用接口

| 接口 | API 名称 | 说明 | 状态 |
|-----|---------|------|------|
| 查询回源时序数据 | `DescribeOriginData` | 回源带宽、回源请求数、回源失败率 | 可用 |
| 查询四层流量时序数据 | `DescribeTimingL4Data` | L4 连接数、流量 | 可用 |
| 查询DDoS攻击时序数据 | `DescribeDDoSAttackData` | DDoS 攻击流量 | 可用 |
| 查询DDoS攻击Top数据 | `DescribeDDoSAttackTopData` | 攻击来源 Top | 可用 |
| ~~查询监控流量时序数据~~ | — | — | 待废弃 |
| ~~查询缓存分析时序数据~~ | — | — | 待废弃 |
| ~~查询缓存分析Top数据~~ | — | — | 待废弃 |

---

## 四、API 接口详情

### 4.1 DescribeTimingL7AnalysisData（七层时序数据）

- **文档**: https://cloud.tencent.com/document/api/1552/80648
- **请求域名**: `teo.tencentcloudapi.com`
- **频率限制**: 30次/秒
- **数据延迟**: 约 10 分钟

#### 输入参数

| 参数 | 必选 | 类型 | 说明 |
|------|-----|------|------|
| StartTime | 是 | ISO8601 | 开始时间 |
| EndTime | 是 | ISO8601 | 结束时间（与 StartTime 间距 ≤ 31 天） |
| MetricNames.N | 是 | Array of String | 指标列表（见 3.1） |
| ZoneIds.N | 是 | Array of String | 站点 ID 集合（`*` 表示所有站点） |
| Interval | 否 | String | 时间粒度：`min` / `5min` / `hour` / `day` |
| Filters.N | 否 | Array of QueryCondition | 筛选条件（见 4.3） |

#### 请求示例

```json
{
    "StartTime": "2026-05-26T17:00:00+08:00",
    "EndTime": "2026-05-26T18:00:00+08:00",
    "MetricNames": [
        "l7Flow_outBandwidth",
        "l7Flow_request",
        "l7Flow_avgResponseTime",
        "l7Flow_avgFirstByteResponseTime",
        "l7Flow_outFlux"
    ],
    "ZoneIds": ["*"],
    "Interval": "5min",
    "Filters": [
        {
            "Key": "domain",
            "Operator": "equals",
            "Value": ["login2.xiaohongshu.com"]
        }
    ]
}
```

#### 返回结构

```json
{
    "Response": {
        "TotalCount": 1,
        "Data": [
            {
                "TypeKey": "账号AppId",
                "TypeValue": [
                    {
                        "MetricName": "l7Flow_outBandwidth",
                        "Avg": 16024,
                        "Max": 19998,
                        "Sum": 192291,
                        "Detail": [
                            { "Timestamp": 1779782400, "Value": 14946 },
                            { "Timestamp": 1779782700, "Value": 13998 }
                        ]
                    }
                ]
            }
        ]
    }
}
```

### 4.2 DescribeTopL7AnalysisData（七层 Top 数据）

- **文档**: https://cloud.tencent.com/document/api/1552/80646

#### 输入参数

| 参数 | 必选 | 类型 | 说明 |
|------|-----|------|------|
| StartTime | 是 | ISO8601 | 开始时间 |
| EndTime | 是 | ISO8601 | 结束时间 |
| MetricName | 是 | String | 查询指标（格式: `{指标}_{维度}`，见 3.2） |
| ZoneIds.N | 是 | Array of String | 站点 ID 集合 |
| Limit | 否 | Integer | Top N，最大 1000，默认 10 |
| Filters.N | 否 | Array of QueryCondition | 筛选条件 |

#### 请求示例

```json
{
    "StartTime": "2026-05-26T12:00:00+08:00",
    "EndTime": "2026-05-26T18:00:00+08:00",
    "MetricName": "l7Flow_request_domain",
    "ZoneIds": ["*"],
    "Limit": 50
}
```

### 4.3 筛选条件（Filters）

Filters 中的每个条件结构为 `QueryCondition`:

```json
{
    "Key": "筛选维度",
    "Operator": "运算符",
    "Value": ["值1", "值2"]
}
```

#### 支持的 Key

| Key | 筛选维度 | 示例值 | 适用指标 |
|-----|---------|-------|---------|
| `domain` | 域名（Host） | `www.example.com` | 所有 L7 指标 |
| `country` | 国家/地区 | `CN` | 流量/带宽/请求数/耗时 |
| `province` | 省份（仅国内） | `22` | 流量/带宽/请求数/耗时 |
| `isp` | 运营商（仅国内） | `2`(电信) `26`(联通) `1046`(移动) | 流量/带宽/请求数/耗时 |
| `statusCode` | 状态码 | `2XX` `3XX` `4XX` `5XX` `404` | 流量/带宽/请求数 |
| `protocol` | HTTP 协议版本 | `HTTP/1.1` `HTTP/2.0` `HTTP/3` | 流量/带宽/请求数 |
| `socket` | HTTP/HTTPS | `HTTP` `HTTPS` | 流量/带宽/请求数/耗时 |
| `cacheType` | 缓存状态 | `hit` `miss` `dynamic` | 流量/带宽/请求数 |
| `url` | URL Path | `/content/test.jpg` | 流量/带宽/请求数 |
| `ipVersion` | IP 版本 | `4` `6` | 流量/带宽/请求数/耗时 |
| `tlsVersion` | TLS 版本 | `TLS1.2` `TLS1.3` | 流量/带宽/请求数 |
| `deviceType` | 设备类型 | `Mobile` `Desktop` `Tablet` | 流量/带宽/请求数 |
| `browserType` | 浏览器类型 | `Chrome` `Safari` `Firefox` | 流量/带宽/请求数 |
| `operatingSystemType` | 操作系统 | `Android` `IOS` `Windows` | 流量/带宽/请求数 |
| `resourceType` | 资源类型 | `.jpg` `.html` `.js` | 流量/带宽/请求数 |
| `referer` | Referer | `http://www.example.com/` | 流量/带宽/请求数 |
| `clientIp` | 客户端 IP | `1.1.1.1` | 流量/带宽/请求数 |
| `mitigatedByWebSecurity` | 是否被安全防护缓释 | `yes` `no` | 流量/带宽/请求数 |

#### 支持的运算符（Operator）

| 运算符 | 说明 |
|-------|------|
| `equals` | 等于（多个值为或关系） |
| `notEquals` | 不等于 |
| `include` | 包含指定字符串 |
| `notInclude` | 不包含 |
| `startWith` | 以...开始 |
| `notStartWith` | 不以...开始 |
| `endWith` | 以...结尾 |
| `notEndWith` | 不以...结尾 |

> **多个 Filter 之间为"且"关系**，同一个 Filter 内多个 Value 之间为"或"关系。

---

## 五、EdgeOne 站点与域名映射

EdgeOne 中，**站点（Zone）是顶级管理单元**，域名挂在站点下面。查询数据时 `ZoneIds` 为必填参数（可用 `*` 查所有站点）。

### 已确认的站点

以 `xiaohongshu.com` 为例：

| 子站点名称 | Zone ID | 服务区域 | 类型 |
|-----------|---------|---------|------|
| ecdn-3df1549d-00... | zone-37zigexkghx8 | 中国大陆 | DCDN |
| ecdn-33d61ea7-00... | zone-37ziclalvhdo | 全球 | DCDN |
| fetest | zone-3ndl75djwb0c | 中国大陆 | 测试 |
| default-alias | zone-2uzbsuuaw8u4 | 全球（不含中国大陆） | 默认 |

> **其他父域名**（rnote.com、rednote.com、xyrednote.com、noshlife.com）的 Zone ID 需要在 EdgeOne 控制台的「服务总览」中逐个查找确认。

---

## 六、全站数据基线（实测，2026-05-26）

通过 `ZoneIds: ["*"]` 不加域名过滤查询的全站汇总数据：

| 指标 | 平均值 | 峰值 |
|------|-------|------|
| EdgeOne 响应带宽 | 33.6 Mbps | 36.1 Mbps |
| 客户端请求带宽 | 98.7 Mbps | 104.6 Mbps |
| 请求数（每30分钟） | 529 万次 | 561 万次 |
| QPS | ~2,939 | ~3,116 |
| 平均每请求响应耗时 | ~180 ms | — |
| 平均每请求 TTFB | ~178 ms | — |

---

## 七、建议采集方案

### 采集策略

```
定时任务（每 5 分钟）
│
├── [核心] DescribeTimingL7AnalysisData
│   ├── 指标: outBandwidth, request, avgResponseTime, avgFirstByteResponseTime, outFlux
│   ├── 按域名: Filters.domain = 各 DCDN 域名
│   ├── 按状态码: Filters.statusCode = 5XX（用于错误率告警）
│   └── 按缓存: Filters.cacheType = hit/miss（用于缓存命中率）
│
├── [辅助] DescribeTopL7AnalysisData（每小时）
│   ├── l7Flow_request_domain — 域名请求量排行
│   ├── l7Flow_request_statusCode — 状态码分布
│   └── l7Flow_outFlux_domain — 域名流量排行
│
└── [辅助] 回源数据（每 5 分钟）
    └── 回源带宽 + 回源请求数
```

### 存储与展示

| 组件 | 用途 |
|------|------|
| **Prometheus** | 存储时序指标数据 |
| **ClickHouse** | 存储明细/Top 数据 |
| **Grafana** | 监控看板可视化 |

### 关键告警项

| 告警项 | 条件 | 级别 |
|-------|------|------|
| 带宽突增 | 带宽 > 基线 × 2 | P1 |
| 5xx 错误率 | 5xx / 总请求 > 1% | P0 |
| 响应耗时 | 平均耗时 > 500ms | P1 |
| 缓存命中率下降 | hit率 < 80% | P2 |
| 请求量骤降 | 请求量 < 基线 × 0.3 | P1 |
