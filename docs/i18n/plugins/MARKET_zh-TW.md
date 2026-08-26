# TurboDL 插件市集（基於 GitHub 主題標籤）

TurboDL 不執行任何中心化的套件伺服器。插件「市集」僅僅是 **GitHub 主題標籤加上一份機器可讀的清單**。任何人都可以透過推送一個儲存庫、加上正確的主題標籤，並在儲存庫根目錄放一份 `turbodl-plugin.json` 來發布插件。任何人都可以用一次 GitHub 主題搜尋來發現插件。這讓生態保持開放、去中心化、零基礎設施。

本文件定義了標籤、清單以及發布/發現流程。它與[插件生態開發協定](CONVENTION_zh-TW.md)（相容性規則手冊）和[插件接入教學](README_zh-TW.md)配套。

---

## 1. 發現插件

所有 TurboDL 插件都帶有根主題 **`turbodl-plugin`**。在此瀏覽它們：

```
https://github.com/topics/turbodl-plugin
```

在 GitHub 搜尋中組合主題以按能力收窄：

```
topic:turbodl-plugin topic:turbodl-backend      # 協定後端
topic:turbodl-plugin topic:turbodl-adapter       # shim/服務轉接器
topic:turbodl-plugin topic:turbodl-hls           # HLS 相關
```

GitHub API 同樣可用：

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. 主題標籤（「貨架」）

每個插件儲存庫 MUST 帶有 `turbodl-plugin`，加上恰好一個**分類**主題，再加上任意數量的**能力**主題。

**根（必需）**
- `turbodl-plugin`

**分類（擇一，必需）**
- `turbodl-backend` —— 新增/覆蓋一個下載協定（`DownloadBackend`）
- `turbodl-adapter` —— 橋接一個外部系統/服務（shim；通常是 `LinkParser` + 後端）
- `turbodl-parser` —— 僅連結/清單解析器（`LinkParser`）
- `turbodl-hook` —— 任務前/後處理（`TaskPreHook` / `TaskPostHook`）
- `turbodl-loader` —— 一個插件載入器（`PluginLoaderProvider`，例如 JS provider）

**能力（可選，任意數量）**
- 協定/格式：`turbodl-hls`、`turbodl-dash`、`turbodl-ftp`、`turbodl-magnet`、`turbodl-m3u8`
- 行為：`turbodl-remux`、`turbodl-checksum`、`turbodl-notify`、`turbodl-unpack`
- 整合：`turbodl-cloud`、`turbodl-drm-free`

分類與能力標籤正是讓市集插件易於建置與尋找的關鍵：你挑選你的貨架，使用者篩選到它。

---

## 3. `turbodl-plugin.json` 清單

把這個檔案放在儲存庫根目錄。它是一個工具（或未來的官方索引器）用來理解你插件的唯一機器可讀描述符。

```json
{
  "manifestVersion": "1.0",
  "id": "backend.hls",
  "name": "HLS VOD Backend",
  "description": "Downloads HLS VOD (.m3u8) streams: variant selection, AES-128, byte-range.",
  "version": "1.0.0",
  "author": "your-name-or-org",
  "homepage": "https://github.com/you/turbodl-plugin-hls",
  "license": "MIT",

  "category": "turbodl-backend",
  "capabilities": ["turbodl-hls", "turbodl-m3u8"],

  "turbodl": {
    "apiMajor": 1,
    "requiredApiVersion": "1.0.0"
  },

  "entry": {
    "language": "kotlin",
    "pluginClass": "dev.turbodl.plugin.hls.HlsPlugin"
  },

  "artifact": {
    "type": "maven",
    "coordinates": "dev.turbodl:turbo-plugin-hls:1.0.0"
  },

  "extensionPoints": ["turbo.downloadBackend"],
  "services": ["backend.hls"]
}
```

欄位說明：
- `id` MUST 等於插件的 `Plugin.id`，並遵循協定（§4）中的命名規則。
- `turbodl.apiMajor` 與 `requiredApiVersion` MUST 與插件在程式碼中宣告的（`Plugin.requiredApiVersion`）一致。這正是市集/工具在**下載之前**篩掉無法在給定 TurboDL 版本上執行之插件的方式。
- `category` MUST 是分類主題之一；`capabilities` SHOULD 對映儲存庫的能力主題。
- `entry.language` 目前是 `kotlin`。`js` 為未來的 JS provider **預留**；core 與 Kotlin 載入器對 JS 保持無感知。
- `artifact.type` 是 `maven`（已發布的 JAR）或 `jar`（`artifact.url` 中的直接 release 資產 URL）。選擇你的分發所使用的方式。

一份用於驗證的 JSON Schema 位於 [`turbodl-plugin.schema.json`](../../plugins/turbodl-plugin.schema.json)。

---

## 4. 建議的儲存庫佈局

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # manifest (root)
├─ README.md                    # what it does, install snippet, supported TurboDL MAJOR
├─ LICENSE
├─ src/main/kotlin/...          # the Plugin implementation
└─ src/test/kotlin/...          # a test proving it loads + performs its capability
```

儲存庫描述與 README SHOULD 明確聲明所支援的 TurboDL MAJOR 線（例如「TurboDL 1.x」）。

---

## 5. 發布檢查清單

1. 依照[接入教學](README_zh-TW.md)與[協定](CONVENTION_zh-TW.md)實作一個 `Plugin`。
2. 將 `Plugin.requiredApiVersion` 設為你實際使用之 API 的最低版本。
3. 在儲存庫根目錄加入 `turbodl-plugin.json`；對照 schema 驗證它。
4. 加入 GitHub 主題：`turbodl-plugin` + 一個分類 + 能力。
5. 在 README 中填入安裝片段與所支援的 TurboDL MAJOR。
6. 發布一個與 `artifact` 相符的構件（Maven 座標或一個 release JAR）。
7. 打一個版本等於清單 `version` 的 release。

這就是整個「市集」：推送、打標籤、完成。無守門人，無伺服器。

---

## 6. 安裝插件（消費者側）

1. 把插件構件加入你的建置（來自清單的 Maven 座標），與 `turbodl-core` 和 `turbo-plugin-runtime` 並列。
2. 把它安裝進你的宿主：

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // or the plugin's documented entry class
// If you use the bootstrap convenience:
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. 版本握手會自動執行。如果插件需要比你的 TurboDL 更新的 API，它會被標記為 `INCOMPATIBLE` 且絕不載入 —— 檢查 `host.diagnostics().render()`。

---

## 7. 信任與安全

沒有中心化審查，因此對待第三方插件應如對待任何依賴：
- 閱讀原始碼；優先選擇帶有測試與明確授權的插件。
- 安裝前檢查清單 `apiMajor` 與你的 TurboDL 相符。
- [協定 §9](CONVENTION_zh-TW.md) 列出了插件應遵循的安全規則（不可信輸入驗證、無資料外洩、無靜默削弱 TLS）。違反這些的插件應在其儲存庫上被回報，並且 MAY 從任何官方索引中被除名。

---

## 8. 未來：可選的官方索引

基於主題的市集無需伺服器。如果需求增長，本專案 MAY 發布一個靜態、自動生成的索引，定期爬取 `topic:turbodl-plugin`、驗證每份 `turbodl-plugin.json`，並呈現一個可按分類、能力與所支援 API MAJOR 篩選的可搜尋清單。這將始終是 GitHub 主題之上的一個便利層，絕非守門人。
