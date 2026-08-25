# TurboDL

> 高性能多线程下载引擎 SDK —— 纯 Kotlin/JVM，无 Android 依赖，可被任意 JVM 应用（含 Android）直接集成。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

TurboDL 是一个从零编写的多线程下载内核。它**只参考**了业界成熟下载器（aria2、IDM/XDM、axel、Persepolis、Motrix、ab-download-manager）的架构与算法**思想**，未复制任何一方的源码，因此以宽松的 **Apache-2.0** 许可发布，可自由用于开源或商业项目。

## 特性

- **多线程分段下载**：HTTP Range 分片并发，连接复用（HTTP/2 多路复用 + keep-alive）。
- **动态分段**：细粒度预分块 + 工作窃取，慢连接不拖累整体，消除"最后一块单线程收尾"的长尾（IDM/XDM 思想）。
- **顺序分片优先**：靠前的分片优先下载，便于边下边预览/播放（axel/Persepolis 思想）。
- **健壮的回退与重试**：
  - 服务器不支持 / 忽略 Range → 自动回退整文件单流下载；
  - 个别分片坏块 / 超时 → **仅重试该分片**，不作废整个任务；
  - 校验实际返回字节是否匹配请求的 Range 区间（防服务器篡改 Range 返回整文件）。
- **克制的自适应**：**只有**在收到 429/503 或连续失败达到阈值时才乘性下调并发；普通网速波动**绝不**主动减少线程（不照搬 AIMD 抖动判断）。
- **断点续传**：分片持久化，暂停/恢复从磁盘真实进度继续。
- **字节级完整性校验**：合并后校验总大小，杜绝损坏文件。

## 9 项引擎能力

| 能力 | 配置项 |
|---|---|
| 全局速度限制 | `globalSpeedLimitBytesPerSec`（令牌桶，0=不限） |
| 线程数（最高 256） | `maxConnectionsPerTask`（1..256） |
| 并发任务数 | `maxConcurrentTasks`（1..64） |
| 最大下载重试次数 | `maxRetries`（0..50） |
| 动态分段 | `dynamicSegmentation`（true/false） |
| 手动/自动代理 | `proxy = Direct / System / Manual(HTTP,SOCKS,鉴权) / Pac(url)` |
| DNS 配置 | `dns = System / StaticHosts / DoH(url)` |
| 忽略 SSL | `trustAllCerts` |
| 主题 | 由上层 App 负责（SDK 不涉及 UI 渲染） |

## 快速开始

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // 不限速
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// 提交任务
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// 观测进度事件
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("完成: ${event.file}")
            is TurboEvent.Failed -> println("失败: ${event.reason}")
            else -> {}
        }
    }
}

// 挂起直到完成
val result = client.await(id)   // Result<File>

// 控制
client.pause(id)
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

## 命令行

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [输出文件] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## 构建

```
./gradlew build        # 编译 + 运行单元测试
```

单元测试用内嵌 HTTP(Range) 服务器覆盖：多线程字节级正确性、动态分段、Range 不支持回退、Range 被篡改回退、瞬时 503 仅重试分片、全局限速。

## 模块

- `turbodl-core`：下载引擎 SDK（对外发布的库）。
- `turbodl-cli`：命令行示例，演示 SDK 用法。

## 设计说明与致谢

TurboDL 的设计吸收了以下开源项目的思想（仅思想，**未复制源码**），在此致谢：
[aria2](https://github.com/aria2/aria2)、[Xtreme Download Manager](https://github.com/subhra74/xdm)、[axel](https://github.com/axel-download-accelerator/axel)、[Persepolis](https://github.com/persepolisdm/persepolis)、[Motrix](https://github.com/agalwood/Motrix)、[ab-download-manager](https://github.com/amir1376/ab-download-manager)。

> 插件 / 扩展框架为后续规划，本仓库当前版本不包含插件系统。

## 许可

[Apache License 2.0](LICENSE)。
