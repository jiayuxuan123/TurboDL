# TurboDL 插件（繁體中文）

> 繁體中文翻譯 · 原文：[English](../../../README.md) · [中文總覽](../README_zh-TW.md)

TurboDL 既是一個獨立的下載引擎，**也是**一個可選的插件平台。本目錄是插件作者所需一切資料的所在地：

- **[插件接入教學](#編寫一個插件)** —— 即本文件：概念 + 一步步的操作指南。
- **[開發協定](CONVENTION_zh-TW.md)** —— 官方的相容性規則手冊（穩定 API、版本、命名、安全）。發布前請先閱讀。
- **[插件市集](MARKET_zh-TW.md)** —— 如何透過 GitHub 主題標籤 + `turbodl-plugin.json` 清單（[schema](../../plugins/turbodl-plugin.schema.json)）發布/發現插件。

可執行範例位於 [`demo`](../../../demo) 模組：`./gradlew :demo:run --args="1"`（Kotlin 插件）、`"2"`（bootstrap 引導）、`"3"`（shim 轉接器）。

---

## 設計理念

三條思想驅動著設計：

1. **核心獨立可用。** `turbodl-core` 是一個完整、獨立的多執行緒下載引擎，零插件依賴。插件嚴格是增量：如果你從不載入任何插件，一切都不會改變。

2. **一切皆插件，核心僅機制。** 執行時期核心（`turbo-plugin-runtime`）對 HTTP、HLS 或任何協定一無所知。它只提供機制：生命週期、清理（disposer）、帶依賴解析的服務註冊表、型別安全事件匯流排、擴充點註冊表、版本握手與診斷。Kotlin 載入器、HTTP 後端、HLS 後端——它們全都是普通插件。即便是未來的 JS 執行時期，也只是一個實作了 `PluginLoaderProvider` 的插件。

3. **混合 A+B。**（A）core 內建一個 HTTP 後端，因此開箱即可用。（B）插件後端可以覆蓋內建後端，或透過擴充註冊表新增協定——而 core 從不依賴執行時期。依賴方向嚴格：`runtime → core`，絕不反向。

結果：一個可供第三方在其上建置的小而穩定的契約面，配合一份相容性策略（[開發協定](CONVENTION_zh-TW.md)），使得未來破壞性的 core 發布會顯式失敗，而非靜默損壞插件。

---

## 建置模組

| 概念 | 型別 | 用途 |
|---|---|---|
| 插件 | `Plugin` | 你實作的單元。包含 `id`、`requiredApiVersion`、可選的 `dependencies`。 |
| 上下文 | `PluginContext` | 傳給 `onLoad`；用於註冊服務/事件/擴充。所有註冊在卸載時自動清理。 |
| 清理 | `Disposer` | LIFO 清理鏈；卸載時逐回呼隔離地排空。 |
| 服務 | `ServiceRegistry` | 輕量級 id→實例註冊表 + 依賴閘控。 |
| 事件 | `EventBus` | 觀察 `TurboEvent`，並在提交時攔截 `DownloadRequest`。 |
| 擴充點 | `ExtensionPointKey<T>` | 插件實作的型別化契約；消費者按 key/優先級查詢。 |
| 版本 | `ApiVersion` | 版本握手：宿主必須滿足插件的 `requiredApiVersion`。 |

**內建擴充點**（`dev.turbodl.plugin.runtime.ext.ExtensionPoints`）：
- `DOWNLOAD_BACKEND` —— 新增/覆蓋一個協定（`DownloadBackend`）
- `LINK_PARSER` —— 將原始連結轉換為 `DownloadRequest`（`LinkParser`）
- `TASK_PRE_HOOK` —— 提交前重寫請求（`TaskPreHook`）
- `TASK_POST_HOOK` —— 任務結束後進行回應（`TaskPostHook`）

**核心**按名字認識的唯一擴充點是 `PluginLoaderProvider`（載入器）。

---

## 編寫一個插件

### 1. 依賴契約

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. 實作 `Plugin`

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // 唯一、點分隔、帶廠商限定
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // 你實際用到的最低 API

    override fun onLoad(context: PluginContext) {
        // 在這裡註冊服務 / 事件 / 擴充 —— 卸載時全部自動清理
    }
}
```

### 3. 在 `onLoad` 中註冊能力

```kotlin
// 其他插件可能依賴的服務：
context.registerService(id, myService)

// 觀察引擎事件（卸載時自動取消訂閱）：
context.onEvent { event -> /* ... */ }

// 在執行前重寫請求：
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// 提供擴充點實作（優先級高者勝出，成為「那個」實作）：
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// 任何其他需要收尾清理的東西：
context.disposer.register { myThreadPool.shutdown() }
```

透過 `context` 註冊的一切都會在插件被卸載時自動拆除。如果 `onLoad` 拋出例外，部分註冊會被替你回滾。

### 4. 編寫 `DownloadBackend`

後端擁有協定；引擎擁有狀態、事件、合併與完整性。規則（完整清單見[開發協定 §7](CONVENTION_zh-TW.md)）：

- `supports(request)` —— 廉價、無副作用、保守。
- 尊重 `context.isActive()` 與協程取消。
- 一旦獲知總大小就呼叫 `context.reportTotalSize(total)`（未知則傳 `-1`），過程中呼叫 `context.reportProgress(...)`，用 `context.throttle(bytes)` 遵守全域速度限制。
- 把分片寫入 `context.workDir`，**按序**以 `BackendResult.orderedParts` 回傳它們。
- 對於不可恢復/超出範圍（out-of-scope）的輸入，**拋出例外**——絕不產出損壞的檔案。
- 需要 core 的 HTTP 傳輸策略（代理/DNS/TLS）？用 `TurboHttpClients.create(config)`；絕不觸碰 core 內部。

參見 `turbo-plugin-hls`，了解一個完整且非平凡的後端（播放清單解析、AES-128、位元組區間、顯式拒絕不支援的構造）。

### 5. 安裝它

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// 讓下載路由經過插件後端（無匹配時回退到內建 HTTP）：
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

或者使用 bootstrap 便捷裝配：

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. 用診斷驗證

```kotlin
println(host.diagnostics().render())
// 列出插件及其狀態（LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED）、
// 擴充點、服務、監聽器數量。
```

如果你的插件顯示為 `INCOMPATIBLE`，說明當前執行的 TurboDL API 不滿足你的 `requiredApiVersion`——檢查版本握手（[開發協定 §2](CONVENTION_zh-TW.md)）。

### 7. 發布

遵循[插件市集](MARKET_zh-TW.md)的步驟：加入 `turbodl-plugin.json`，為儲存庫打上 `turbodl-plugin` + 一個分類 + 能力標籤，發布構件，打一個 release。

---

## Shim 轉接器

「shim」包裝一個外部下載器/SDK，並透過 `LinkParser` + `DownloadBackend` 將其暴露給 TurboDL，而 TurboDL 對該系統一無所知。從 `demo/.../Example3ShimAdapter.kt` 中的範本開始——用真實 SDK 替換佔位符 `ExternalDownloader`。

---

## 關於 JS 的說明

JavaScript 執行時期是一項**預留的、未來的**能力。它會作為一個實作了 `PluginLoaderProvider` 的獨立插件發布；core 與 Kotlin 載入器始終保持對 JS 無感知，且核心不會引入任何 JS 引擎。清單為那一天預留了 `entry.language: "js"`。
