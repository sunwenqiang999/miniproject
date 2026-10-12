# 阿里云 DCDN（全站加速）— 域名监控调研

## 一、背景

公司 DCDN（全站加速）域名分布在多家 CDN 厂商，本文档梳理**阿里云 DCDN（全站加速）**侧提供的监控指标和对应 API 接口，为后续搭建统一监控看板提供依据。

- **CDN 域名管理平台**: https://multicdn.devops.xiaohongshu.com/cdn/domain
- **阿里云 DCDN 控制台**: https://dcdn.console.aliyun.com/
- **DCDN API 文档**: https://help.aliyun.com/zh/dcdn/developer-reference/
- **OpenAPI 在线调试**: https://api.aliyun.com/product/dcdn

---

## 二、需要监控的 DCDN 域名（阿里云）

以下域名来源于 https://multicdn.devops.xiaohongshu.com/cdn/domain ，筛选条件：**资源类型=DCDN，供应商=阿里云**。

| ID | 域名 | 状态 |
|----|------|------|
| 137779 | live-room-d2-test.xhscdn.com | online |
| 137782 | www.hsxsquare.com | online |
| 137842 | live-room.xiaohongshu.com | online |
| 137814 | www.xiaohong-muse.com | online |
| 137826 | www.xhs-glam.com | online |
| 137838 | upload-ae-ali.rnote.com | online |
| 137787 | api.doro.app | online |
| 137823 | www.redgian.com | online |
| 137791 | upload.doro.app | online |
| 137758 | www.xhs-boutique.com | online |

> **注意**: 以上域名从截图中提取，完整列表请在 CDN 域名管理平台中按 **供应商=阿里云 + 资源类型=DCDN** 筛选确认。

---

## 三、DCDN 可采集的监控指标

### 3.1 访问数据指标（核心）

阿里云 DCDN 的监控指标按**独立 API** 提供（不同于腾讯云 EdgeOne 的统一 API + MetricNames 模式）。

| 指标名称 | 对应 API | 返回字段 | 单位 | 采集优先级 |
|---------|---------|---------|------|----------|
| 带宽 | `DescribeDcdnDomainBpsData` | `Bps` | bit/s | **高** |
| 动态HTTPS带宽 | 同上 | `DynamicHttpsBps` | bit/s | 中 |
| 动态HTTP带宽 | 同上 | `DynamicHttpBps` | bit/s | 中 |
| 静态HTTPS带宽 | 同上 | `StaticHttpsBps` | bit/s | 中 |
| 静态HTTP带宽 | 同上 | `StaticHttpBps` | bit/s | 中 |
| 流量 | `DescribeDcdnDomainTrafficData` | `Traffic` | Byte | **高** |
| 动态HTTPS流量 | 同上 | `DynamicHttpsTraffic` | Byte | 中 |
| 动态HTTP流量 | 同上 | `DynamicHttpTraffic` | Byte | 中 |
| 静态HTTPS流量 | 同上 | `StaticHttpsTraffic` | Byte | 中 |
| 静态HTTP流量 | 同上 | `StaticHttpTraffic` | Byte | 中 |
| QPS | `DescribeDcdnDomainQpsData` | `Qps` | 次/秒 | **高** |
| 总访问次数 | 同上 | `Acc` | 次 | **高** |
| 动态HTTPS QPS | 同上 | `DynamicHttpsQps` | 次/秒 | 中 |
| 动态HTTP QPS | 同上 | `DynamicHttpQps` | 次/秒 | 中 |
| 字节命中率 | `DescribeDcdnDomainHitRateData` | `ByteHitRate` | % | **高** |
| 请求命中率 | 同上 | `ReqHitRate` | % | **高** |

### 3.2 回源数据指标

| 指标名称 | 对应 API | 返回字段 | 单位 | 采集优先级 |
|---------|---------|---------|------|----------|
| 回源带宽 | `DescribeDcdnDomainOriginBpsData` | `OriginBps` | bit/s | **高** |
| 动态HTTPS回源带宽 | 同上 | `DynamicHttpsOriginBps` | bit/s | 中 |
| 动态HTTP回源带宽 | 同上 | `DynamicHttpOriginBps` | bit/s | 中 |
| 回源流量 | `DescribeDcdnDomainOriginTrafficData` | `OriginTraffic` | Byte | **高** |
| 动态HTTPS回源流量 | 同上 | `DynamicHttpsOriginTraffic` | Byte | 中 |
| 动态HTTP回源流量 | 同上 | `DynamicHttpOriginTraffic` | Byte | 中 |

### 3.3 维度分析指标

| 指标名称 | 对应 API | 说明 | 数据粒度 |
|---------|---------|------|---------|
| 区域分布 | `DescribeDcdnDomainRegionData` | 各地区 QPS/带宽/流量/响应时间 | 天 |
| 运营商分布 | `DescribeDcdnDomainIspData` | 各运营商 QPS/带宽/流量/响应时间 | 天 |
| HTTP状态码 | `DescribeDcdnDomainHttpCodeData` | 各状态码分布 | 5min/1h/1d |
| HTTP状态码(按协议) | `DescribeDcdnDomainHttpCodeDataByLayer` | 按协议层分 | 5min/1h/1d |

### 3.4 实时监控指标

| 指标名称 | 对应 API | 说明 | 数据粒度 |
|---------|---------|------|---------|
| 实时带宽 | `DescribeDcdnDomainRealTimeBpsData` | 实时带宽（1min粒度） | 1 分钟 |
| 实时QPS | `DescribeDcdnDomainRealTimeQpsData` | 实时QPS | 1 分钟 |
| 实时HTTP状态码 | `DescribeDcdnDomainRealTimeHttpCodeData` | 实时状态码分布 | 1 分钟 |
| 实时回源带宽 | `DescribeDcdnDomainRealTimeSrcBpsData` | 实时回源带宽 | 1 分钟 |
| 实时回源流量 | `DescribeDcdnDomainRealTimeSrcTrafficData` | 实时回源流量 | 1 分钟 |

---

## 四、API 接口详情

### 4.1 DescribeDcdnDomainBpsData（带宽监控数据）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainbpsdata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainBpsData
- **请求域名**: `dcdn.aliyuncs.com`
- **频率限制**: 100次/秒
- **数据延迟**: 约 15 分钟（5分钟粒度）

#### 输入参数

| 参数 | 必选 | 类型 | 说明 |
|------|-----|------|------|
| DomainName | 否 | String | 加速域名，多个用逗号分隔，不传查所有 |
| StartTime | 否 | ISO8601 UTC | 开始时间，格式 `yyyy-MM-ddTHH:mm:ssZ` |
| EndTime | 否 | ISO8601 UTC | 结束时间 |
| Interval | 否 | String | 时间粒度（秒）：`300` / `3600` / `86400` |
| IspNameEn | 否 | String | 运营商英文名（如 `unicom`） |
| LocationNameEn | 否 | String | 地域英文名（如 `beijing`） |

#### 请求示例

```json
{
    "DomainName": "login2.xiaohongshu.com",
    "StartTime": "2026-05-26T00:00:00Z",
    "EndTime": "2026-05-26T12:00:00Z",
    "Interval": "300"
}
```

#### 返回结构

```json
{
    "EndTime": "2026-05-26T12:00:00Z",
    "StartTime": "2026-05-26T00:00:00Z",
    "RequestId": "99AD5CF2-1098-5793-8E26-687AD5C9008E",
    "DomainName": "login2.xiaohongshu.com",
    "DataInterval": "300",
    "BpsDataPerInterval": {
        "DataModule": [
            {
                "DynamicHttpsBps": 5382.608,
                "Bps": 5382.608,
                "DynamicHttpBps": 0,
                "StaticHttpBps": 0,
                "TimeStamp": "2026-05-26T00:00:00Z",
                "StaticHttpsBps": 0
            }
        ]
    }
}
```

### 4.2 DescribeDcdnDomainTrafficData（流量监控数据）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomaintrafficdata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainTrafficData

#### 输入参数

同 4.1（DomainName / StartTime / EndTime / Interval / IspNameEn / LocationNameEn）

#### 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| Traffic | number | 总流量（Byte） |
| DynamicHttpsTraffic | number | 动态 HTTPS 流量 |
| DynamicHttpTraffic | number | 动态 HTTP 流量 |
| StaticHttpsTraffic | number | 静态 HTTPS 流量 |
| StaticHttpTraffic | number | 静态 HTTP 流量 |
| TimeStamp | string | 时间片起始时刻 |

### 4.3 DescribeDcdnDomainQpsData（QPS/请求数）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainqpsdata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainQpsData

#### 输入参数

同 4.1

#### 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| Qps | number | 总 QPS |
| Acc | number | 总访问次数 |
| DynamicHttpsQps | number | 动态 HTTPS QPS |
| DynamicHttpsAcc | number | 动态 HTTPS 访问次数 |
| DynamicHttpQps | number | 动态 HTTP QPS |
| DynamicHttpAcc | number | 动态 HTTP 访问次数 |
| StaticHttpsQps | number | 静态 HTTPS QPS |
| StaticHttpsAcc | number | 静态 HTTPS 访问次数 |
| StaticHttpQps | number | 静态 HTTP QPS |
| StaticHttpAcc | number | 静态 HTTP 访问次数 |
| TimeStamp | string | 时间片起始时刻 |

### 4.4 DescribeDcdnDomainHitRateData（缓存命中率）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainhitratedata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainHitRateData

#### 输入参数

| 参数 | 必选 | 类型 | 说明 |
|------|-----|------|------|
| DomainName | 否 | String | 仅支持单个域名 |
| StartTime | 否 | ISO8601 UTC | 开始时间 |
| EndTime | 否 | ISO8601 UTC | 结束时间 |
| Interval | 否 | String | 时间粒度：`300` / `3600` / `86400` |

#### 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| ByteHitRate | number | 字节命中百分比 |
| ReqHitRate | number | 请求命中百分比 |
| TimeStamp | string | 时间片起始时刻 |

### 4.5 DescribeDcdnDomainOriginBpsData（回源带宽）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainoriginbpsdata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainOriginBpsData

#### 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| OriginBps | number | 回源带宽（bit/s） |
| DynamicHttpOriginBps | number | 动态 HTTP 回源带宽 |
| DynamicHttpsOriginBps | number | 动态 HTTPS 回源带宽 |
| StaticHttpOriginBps | number | 静态 HTTP 回源带宽 |
| StaticHttpsOriginBps | number | 静态 HTTPS 回源带宽 |
| TimeStamp | string | 时间片起始时刻 |

### 4.6 DescribeDcdnDomainOriginTrafficData（回源流量）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainorigintrafficdata
- **在线调试**: https://api.aliyun.com/api/dcdn/2018-01-15/DescribeDcdnDomainOriginTrafficData

#### 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| OriginTraffic | number | 回源流量（Byte） |
| DynamicHttpOriginTraffic | number | 动态 HTTP 回源流量 |
| DynamicHttpsOriginTraffic | number | 动态 HTTPS 回源流量 |
| StaticHttpOriginTraffic | number | 静态 HTTP 回源流量 |
| StaticHttpsOriginTraffic | number | 静态 HTTPS 回源流量 |
| TimeStamp | string | 时间片起始时刻 |

### 4.7 DescribeDcdnDomainRegionData（区域分布）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainregiondata
- **数据粒度**: 天级（仅支持天粒度）
- **最多可查**: 90 天

#### 返回字段

| 字段 | 说明 |
|------|------|
| Region | 地域名称（如 "重庆市"） |
| RegionEname | 地域英文名 |
| Proportion | 访问占比（%） |
| Qps | 每秒查询率 |
| Bps | 带宽 |
| TotalBytes | 总流量 |
| TotalQuery | 总请求次数 |
| AvgResponseTime | 平均响应时间（ms） |
| AvgResponseRate | 平均响应速度（byte/s） |

### 4.8 DescribeDcdnDomainIspData（运营商分布）

- **文档**: https://help.aliyun.com/zh/dcdn/developer-reference/api-dcdn-2018-01-15-describedcdndomainispdata
- **数据粒度**: 天级
- **最多可查**: 90 天

#### 返回字段

| 字段 | 说明 |
|------|------|
| Isp | 运营商名称（如 "联通"） |
| IspEname | 运营商英文名（如 `unicom`） |
| Proportion | 访问占比（%） |
| Qps | 每秒查询率 |
| Bps | 带宽 |
| TotalBytes | 总流量 |
| TotalQuery | 总请求次数 |
| AvgResponseTime | 平均响应时间（ms） |
| AvgResponseRate | 平均响应速度（byte/ms） |

---

## 五、支持查询的时间粒度

所有访问数据 / 回源数据 API 通用：

| 时间粒度 | Interval | 单次查询最大时间跨度 | 可查历史数据范围 | 数据延迟 |
|---------|----------|-----------------|-------------|---------|
| 5 分钟 | `300` | 3 天 | 93 天 | 15 分钟 |
| 1 小时 | `3600` | 31 天 | 186 天 | 3~4 小时 |
| 1 天 | `86400` | 366 天 | 366 天 | 4 小时~24 小时 |

> **注意**: 阿里云的时间格式使用 **UTC**（以 `Z` 结尾），如 `2026-05-26T00:00:00Z`，不同于腾讯云使用 `+08:00` 时区偏移。

---

## 六、腾讯云 EdgeOne vs 阿里云 DCDN 对比

| 维度 | 腾讯云 EdgeOne | 阿里云 DCDN |
|------|-------------|-----------|
| API 风格 | 统一 API + MetricNames 切换指标 | 每个指标独立 API |
| 域名过滤 | `Filters: [{Key:"domain", Value:["xxx"]}]` | `DomainName: "xxx"` |
| 时间格式 | ISO8601 + 时区 (`+08:00`) | ISO8601 UTC (`Z` 结尾) |
| 时间粒度 | `min`/`5min`/`hour`/`day` | `300`/`3600`/`86400`（秒） |
| 请求域名 | `teo.tencentcloudapi.com` | `dcdn.aliyuncs.com` |
| 频率限制 | 30 次/秒 | 100 次/秒 |
| 数据延迟（5min粒度）| 约 10 分钟 | 约 15 分钟 |
| 缓存命中率 | 通过 Filters.cacheType=hit/miss 计算 | 直接由 `HitRateData` API 返回 |
| 响应耗时 | `l7Flow_avgResponseTime`（API直接返回） | `RegionData` / `IspData` 中的 `AvgResponseTime`（仅天粒度） |
| 运营商/地域过滤 | Filters 中 `isp`/`province` | 请求参数 `IspNameEn`/`LocationNameEn` |
| 站点概念 | 有 Zone（站点），查询需 ZoneIds | 无站点概念，直接按域名查询 |

### 指标对照

| 监控项 | 腾讯云 EdgeOne API | 阿里云 DCDN API |
|-------|------------------|---------------|
| 带宽 | `DescribeTimingL7AnalysisData` + `l7Flow_outBandwidth` | `DescribeDcdnDomainBpsData` |
| 流量 | `DescribeTimingL7AnalysisData` + `l7Flow_outFlux` | `DescribeDcdnDomainTrafficData` |
| 请求数/QPS | `DescribeTimingL7AnalysisData` + `l7Flow_request` | `DescribeDcdnDomainQpsData` |
| 缓存命中率 | Filters.cacheType 计算 | `DescribeDcdnDomainHitRateData` |
| 响应耗时 | `DescribeTimingL7AnalysisData` + `l7Flow_avgResponseTime` | `DescribeDcdnDomainRegionData` (天粒度) |
| HTTP状态码 | `DescribeTopL7AnalysisData` + `l7Flow_request_statusCode` | `DescribeDcdnDomainHttpCodeData` |
| 回源带宽 | `DescribeOriginData` | `DescribeDcdnDomainOriginBpsData` |
| 回源流量 | `DescribeOriginData` | `DescribeDcdnDomainOriginTrafficData` |
| 域名排行 | `DescribeTopL7AnalysisData` + `l7Flow_request_domain` | 无直接 API，需逐域名查询后排序 |
| 地区分布 | `DescribeTopL7AnalysisData` + `l7Flow_request_country` | `DescribeDcdnDomainRegionData` |
| 运营商分布 | Filters.isp | `DescribeDcdnDomainIspData` |

---

## 七、实测数据基线（2026-05-26）

以 `login2.xiaohongshu.com` 为例（来源：DCDN 控制台资源监控页面）：

| 指标 | 值 |
|------|------|
| 带宽峰值 | 104.21 Kbps |
| 总流量 | 110.72 MB |
| 回源流量 | 103.11 MB |
| 总请求数 | 5.87 万次 |
| 流量类型 | 100% 动态 HTTPS |

> 数据来源：阿里云 DCDN 控制台 → 业务监控 → 资源监控，域名 `login2.xiaohongshu.com`，时间范围 2026-05-26 全天。

---

## 八、建议采集方案

### 采集策略

```
定时任务（每 5 分钟）
│
├── [核心] 访问数据
│   ├── DescribeDcdnDomainBpsData — 带宽
│   ├── DescribeDcdnDomainTrafficData — 流量
│   ├── DescribeDcdnDomainQpsData — QPS / 请求数
│   ├── DescribeDcdnDomainHitRateData — 缓存命中率
│   └── DescribeDcdnDomainHttpCodeData — HTTP 状态码分布
│
├── [核心] 回源数据
│   ├── DescribeDcdnDomainOriginBpsData — 回源带宽
│   └── DescribeDcdnDomainOriginTrafficData — 回源流量
│
├── [辅助] 维度分析（每天）
│   ├── DescribeDcdnDomainRegionData — 区域分布
│   └── DescribeDcdnDomainIspData — 运营商分布
│
└── [辅助] 实时监控（每 1 分钟，按需开启）
    ├── DescribeDcdnDomainRealTimeBpsData — 实时带宽
    ├── DescribeDcdnDomainRealTimeQpsData — 实时 QPS
    └── DescribeDcdnDomainRealTimeHttpCodeData — 实时状态码
```

### 采集注意事项

1. **时间格式**：阿里云使用 UTC 时间（`Z` 结尾），北京时间需 **-8小时** 转换
2. **5分钟粒度限制**：单次查询最大跨度 3 天，超过 3 天需改用 `3600`（1小时）粒度
3. **域名传参**：多域名用逗号分隔（如 `a.com,b.com`），不传查所有域名
4. **频率控制**：100次/秒，比腾讯云（30次/秒）宽松，但逐域名查询仍需注意

### 存储与展示

| 组件 | 用途 |
|------|------|
| **Prometheus** | 存储时序指标数据 |
| **ClickHouse** | 存储明细/维度分析数据 |
| **Grafana** | 监控看板可视化 |

### 关键告警项

| 告警项 | 条件 | 级别 |
|-------|------|------|
| 带宽突增 | 带宽 > 基线 × 2 | P1 |
| 5xx 错误率 | 5xx / 总请求 > 1% | P0 |
| 缓存命中率下降 | ByteHitRate < 80% | P2 |
| 回源带宽突增 | 回源带宽 > 基线 × 3 | P1 |
| 请求量骤降 | 请求量 < 基线 × 0.3 | P1 |

---

## 九、控制台操作路径

| 操作 | 路径 |
|------|------|
| 查看域名列表 | DCDN 控制台 → 域名管理 |
| 查看监控图表 | DCDN 控制台 → 业务监控 → 资源监控 |
| 查看实时数据 | DCDN 控制台 → 业务监控 → 实时监控 |
| 查看运营报表 | DCDN 控制台 → 数据中心 → 运营报表 |
| 配置告警 | DCDN 控制台 → 右上角 → 监控告警设置 |
| API 在线调试 | https://api.aliyun.com/product/dcdn |
