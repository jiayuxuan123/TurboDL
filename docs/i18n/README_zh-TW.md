# TurboDL

> 高效能多執行緒下載引擎 SDK —— 純 Kotlin/JVM，無 Android 依賴，可被任意 JVM 應用程式（含 Android）直接整合。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../../LICENSE)

**語言：** [English](../../README.md) · [简体中文](README_zh-CN.md) · 繁體中文 · [日本語](README_ja.md) · [한국어](README_ko.md) · [Deutsch](README_de.md)

TurboDL 是一個從零開始撰寫的多執行緒下載核心。它**僅參考**了業界成熟下載器（aria2、IDM/XDM、axel、Persepolis、Motrix、ab-download-manager）的架構與演算法**思想**，未複製任何一方的原始碼，因此以寬鬆的 **MIT（附插件生態補充條款）** 授權釋出，可自由用於開源或商業專案。

## 特性

- **多執行緒分段下載**：HTTP Range 分片並行，連線複用（HTTP/2 多工 + keep-alive）。
- **動態分段**：細粒度預分塊 + 工作竊取，慢連線不拖累整體，消除「最後一塊由單一執行緒收尾」的長尾（IDM/XDM 思想）。
- **順序分片優先**：靠前的分片優先下載，便於邊下邊預覽／播放（axel/Persepolis 思想）。
- **穩健的回退與重試**：
  - 伺服器不支援／忽略 Range → 自動回退為整檔單流下載；
  - 個別分片壞塊／逾時 → **僅重試該分片**，不作廢整個任務；
  - 校驗實際回傳的位元組是否符合請求的 Range 區間（防止伺服器竄改 Range 而回傳整檔）。
- **克制的自適應**：**只有**在收到 429/503 或連續失敗達到閾值時才乘性下調並行度；一般網速抖動**絕不**主動減少執行緒數（不套用 AIMD 抖動判斷）。
- **中斷續傳**：分片持久化，暫停／恢復從磁碟真實進度繼續。
- **位元組級完整性校驗**：合併後校驗總大小，杜絕損壞檔案。

## 9 項引擎能力

| 能力 | 設定項 |
|---|---|
| 全域速度限制 | `globalSpeedLimitBytesPerSec`（權杖桶，0＝不限） |
| 執行緒數（最高 256） | `maxConnectionsPerTask`（1..256） |
| 並行任務數 | `maxConcurrentTasks`（1..64） |
| 最大下載重試次數 | `maxRetries`（0..50） |
| 動態分段 | `dynamicSegmentation`（true/false） |
| 手動／自動代理 | `proxy = Direct / System / Manual(HTTP,SOCKS,鑑權) / Pac(url)` |
| DNS 設定 | `dns = System / StaticHosts / DoH(url)` |
| 忽略 SSL | `trustAllCerts` |
| 主題 | 由上層 App 負責（SDK 不涉及 UI 繪製） |

## 快速開始

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // unlimited
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// 提交任務
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// 觀測進度事件
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("完成: ${event.file}")
            is TurboEvent.Failed -> println("失敗: ${event.reason}")
            else -> {}
        }
    }
}

// 掛起直到完成
val result = client.await(id)   // Result<File>

// 控制
client.pause(id)
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

## 命令列

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [輸出檔案] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## 建置

```
./gradlew build        # 編譯 + 執行單元測試
```

單元測試以內嵌 HTTP(Range) 伺服器覆蓋：多執行緒位元組級正確性、動態分段、Range 不支援回退、Range 被竄改回退、瞬時 503 僅重試分片、全域限速。

## 模組

- `turbodl-core`：下載引擎 SDK（對外釋出的函式庫，獨立可用，不依賴插件框架）。
- `turbodl-cli`：命令列範例，示範 SDK 用法。
- `turbo-plugin-runtime`：**可選**插件執行時期核心（生命週期／disposer／事件匯流排／服務註冊／擴充點／版本握手／診斷）。core 不依賴它；不引入時 core 照常運作。
- `turbo-plugin-bootstrap`：**可選**引導模組，一鍵裝配基礎插件（Kotlin 載入器 + HTTP 後端）；非強制依賴。
- `turbo-plugin-hls`：**可選** HLS VOD 協定轉接插件 —— 解析 master/media M3U8 播放清單、並行下載分段（分段級重試）、AES-128 解密、支援 EXT-X-BYTERANGE，並按序回傳分片交由引擎合併。以路由式 `DownloadBackend` 自我註冊；不支援的構造（直播串流、DRM/SAMPLE-AES、fMP4 EXT-X-MAP、discontinuity）會顯式失敗，而非產出損壞檔案。
- `demo`：三個可執行範例 —— Kotlin 原生插件、bootstrap 使用、Shim 轉接器範本。執行：`./gradlew :demo:run --args="1"`（或 `2`、`3`、`all`）。

## 插件框架（可選）

TurboDL 既是獨立引擎，**也是**可選的插件平台。三條設計理念：

1. **核心獨立可用。** `turbodl-core` 是完整的多執行緒引擎，零插件依賴。插件嚴格為增量。
2. **一切皆插件，核心僅機制。** 執行時期核心不認識任何協定 —— 只提供生命週期、清理（disposer）、服務註冊表、型別安全事件匯流排、擴充點註冊表、版本握手與診斷。Kotlin 載入器、HTTP 後端、HLS 後端都是普通插件。
3. **混合 A+B。** core 內建 HTTP 後端（A）；插件後端可覆蓋它或經註冊表新增協定（B）—— 而 core 從不依賴執行時期。依賴方向嚴格：`runtime → core`，絕不反向。

版本化的公開 API（`ApiVersion`，目前 `1.0.0`）加上載入期握手，意味著未來破壞性的 core 釋出會**顯式失敗**（插件被標記為 `INCOMPATIBLE` 且絕不載入），而非靜默損壞行為。

插件文件：
- [插件接入教學](plugins/README_zh-TW.md) —— 如何建置並接入插件。
- [開發協定](plugins/CONVENTION_zh-TW.md) —— 官方相容性規則手冊（穩定 API、版本、命名、安全）。
- [插件市集](plugins/MARKET_zh-TW.md) —— 透過 GitHub 主題標籤 + `turbodl-plugin.json` 清單發布／發現插件。

## 設計說明與致謝

TurboDL 的設計吸收了以下開源專案的思想（僅思想，**未複製原始碼**），在此致謝：
[aria2](https://github.com/aria2/aria2)、[Xtreme Download Manager](https://github.com/subhra74/xdm)、[axel](https://github.com/axel-download-accelerator/axel)、[Persepolis](https://github.com/persepolisdm/persepolis)、[Motrix](https://github.com/agalwood/Motrix)、[ab-download-manager](https://github.com/amir1376/ab-download-manager)。

## 授權

[MIT License](../../LICENSE)，附「插件生態補充條款」（釐清插件為獨立作品、可自選授權，不因透過公開 API／擴充點互動而被視為衍生作品）。
