# TurboDL 插件生態開發協定

**狀態：** 官方 · **版本：** 1.0 · **適用 TurboDL API：** `1.x`

這是 TurboDL 專案與插件作者之間的官方契約。它的存在是為了讓生態能夠成長，而不會因為一次破壞性的 core 更新而靜默地弄壞每一個插件。它由 TurboDL 專案本身維護（不委派給第三方），以確保相容性規則在整個生態中保持一致。

如果你只是想*編寫*一個插件，請從 [docs/plugins/README.md](README_zh-TW.md) 開始；本文件是那份指南所依賴的*規則手冊*。

---

## 1. 範圍與術語

- **Core** —— `turbodl-core` 模組：獨立的下載引擎以及插件被允許使用的公開資料模型/契約。
- **執行時期核心（Runtime kernel）** —— `turbo-plugin-runtime` 模組：生命週期、disposer、服務註冊表、事件匯流排、擴充點註冊表、版本握手。僅機制，無業務邏輯。
- **插件（Plugin）** —— 任何實作 `dev.turbodl.plugin.runtime.Plugin` 的東西。載入器、後端、解析器、鉤子與轉接器全都是插件。只有核心不是插件。
- **穩定 API** —— §3 所列的符號。其餘一切皆屬內部，可能無預警變更。
- **MUST / SHOULD / MAY** 遵循 RFC 2119。

---

## 2. 版本與相容性策略

TurboDL 以語意化版本對其**公開的、面向插件的 API** 進行版本化，透過 `dev.turbodl.core.ApiVersion.CURRENT` 暴露。

- **MAJOR（主版本）** —— 對任一穩定 API 符號（§3）的破壞性變更時遞增。跨 MAJOR 一律視為不相容。
- **MINOR（次版本）** —— 向後相容的新增時遞增（帶預設值的新方法、新擴充點、新的可選配置）。
- **PATCH（修訂號）** —— 向後相容的修復時遞增。

由核心強制執行的握手規則：

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- 每個插件宣告 `Plugin.requiredApiVersion`（預設 `1.0.0`）。
- 只有當 `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)` 時，宿主才會載入該插件。
- 不符時插件被標記為 `PluginState.INCOMPATIBLE`，`onLoad` **絕不**被呼叫，並記錄一條診斷。這是刻意設計：破壞性發布會在載入期大聲失敗，而非損壞行為。

插件作者：
- MUST 將 `requiredApiVersion` 設為其實際使用之 API 的最低版本。
- SHOULD 為其支援的每個 core MAJOR 發布一個新的插件版本。
- MUST NOT 依賴內部（非 §3）類別以繞過握手。

### TurboDL 對插件作者的承諾

在單一 MAJOR 線內，本專案：
- MUST NOT 移除或變更任何穩定 API 符號的簽章。
- MUST NOT 以破壞現有實作的方式變更擴充點的既定語意。
- MAY 新增穩定 API（MINOR）—— 新增必須源碼相容且二進位相容（介面新增須附帶預設實作）。
- MUST 在 `CHANGELOG` 中記錄每一項變更，並在 MAJOR 遞增時提供遷移說明。

---

## 3. 穩定 API 面（`1.x`）

只有這些符號受相容性策略涵蓋。套件前綴：`dev.turbodl.core.*`（core）與 `dev.turbodl.plugin.runtime.*`（核心）。

**Core 契約**
- `ApiVersion`（+ `CURRENT`、`satisfies`、`parse`）
- `DownloadRequest`、`TaskState`、`TaskProgress`、`TurboEvent`（sealed 階層）
- `DownloadBackend`、`BackendContext`、`BackendResult`、`BackendResolver`
- `TurboConfig`、`ProxyMode`、`ProxyType`、`DnsMode`
- `TurboClient` 公開方法：`submit`、`await`、`pause`、`resume`、`cancel`、`updateConfig`、`shutdown`、`events`、`progress`、`backendResolver`
- `TurboBackends.builtinHttp`、`TurboHttpClients.create`

**執行時期核心契約**
- `Plugin`、`PluginContext`（+ `service` reified 輔助方法）
- `PluginHost` 公開方法：`install`、`installAll`、`uninstall`、`shutdown`、`publishEvent`、`applyRequestInterceptors`、`diagnostics`，以及 `services`/`extensions`/`eventBus` 存取器
- `Disposer`、`PluginState`、`PluginInfo`、`DiagnosticsSnapshot`
- `ExtensionPointKey`、`ExtensionRegistration`、`ExtensionRegistry`、`ServiceRegistry`、`EventBus`
- `PluginLoaderProvider`（+ `KEY`）、`PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`：`ExtensionPoints`、`LinkParser`、`TaskPreHook`、`TaskPostHook`、`BackendRegistry`

**明確不穩定**（內部；請勿依賴）：`SegmentDownloader`、`SegmentScheduler`、`BuiltinHttpBackend`、`HttpClientFactory`、`PartMerger`、`SpeedLimiter`，以及任何未列於上方者。

---

## 4. 插件身分與命名

- `Plugin.id` MUST 全域唯一、跨版本穩定、小寫、以點分隔：`<category>.<name>`，例如 `backend.http`、`backend.hls`、`loader.kotlin`、`adapter.cordis`。
- 官方專案擁有的保留分類前綴：`backend.`、`loader.`、`core.`。第三方插件 SHOULD 使用帶廠商限定的名稱，例如 `adapter.acme-cloud`、`backend.acme-ftp`（新的協定後端允許使用 `backend.`，但 SHOULD 帶廠商限定）。
- 透過 `registerService` 註冊的服務 id 遵循相同規則，且對插件的主要服務 SHOULD 與插件 id 相符。
- 變更一個已發布的 `Plugin.id` 對任何依賴它的人都是破壞性變更；請將其視為你插件的 MAJOR 事件。

---

## 5. 生命週期契約

- `onLoad` 恰好執行一次，且僅在（a）版本握手通過且（b）所有已宣告的 `dependencies`（服務 id）都存在之後。
- 插件 MUST 為每個副作用註冊對應的清理。實務上，優先使用 `PluginContext` 的方法 —— 服務/事件/擴充註冊會自動接入 disposer —— 並對其他任何東西（執行緒、通訊端、暫存檔、外部 SDK 控制代碼）使用 `context.disposer.register { ... }`。
- 如果 `onLoad` 拋出例外，宿主會回滾該插件的 disposer 鏈並將其標記為 `FAILED`。部分副作用 MUST 可安全回滾。
- `onUnload` MAY 做額外工作，但 MUST NOT 假設 disposer 已執行（它在之後才執行）。
- 插件 MUST 隨時可安全 `uninstall`：卸載後，插件的任何服務、監聽器或擴充實作都不得仍可被觸及。
- `onLoad`/`onUnload` MUST 迅速返回。長時或阻塞的工作應放在插件自己的協程/執行緒上，並透過 disposer 拆除。

---

## 6. 擴充點

- 透過 `PluginContext.registerExtension(key, impl, priority)` 註冊實作。
- 在消費者挑選「那個」實作之處（例如後端路由），較高的 `priority` 勝出。官方基礎插件使用優先級 `0`；意在覆蓋基礎能力的插件使用較高值（HLS 使用 `100`；轉接器通常 `200`）。選擇能達成你意圖的最低優先級。
- `DownloadBackend.supports` MUST 廉價、無副作用且保守 —— 只對你確實能處理的請求回傳 `true`，讓路由保持可預測，不匹配的請求落回內建的 HTTP 後端。
- `LinkParser.parse` MUST 對它不處理的輸入回傳 `null`（而非拋出例外），以便路由器可以嘗試下一個解析器。
- 鉤子/解析器/後端實作 MUST 能容忍被並行呼叫。

---

## 7. 後端編寫規則

`DownloadBackend` 只擁有協定層；引擎擁有狀態、事件、合併與完整性。一個後端：

- MUST 尊重協作式取消：檢查 `BackendContext.isActive()` 並尊重協程取消；在暫停/取消時迅速停止。
- MUST 在得知後透過 `reportTotalSize` 回報大小（未知/串流時使用 `-1`），並透過 `reportProgress` 回報進度。
- SHOULD 透過 `BackendContext.throttle(bytes)` 對位元組寫入進行限速，使全域速度限制在各任務間得到遵守。
- MUST 將輸出寫入 `BackendContext.workDir` 並以有序的 `BackendResult.orderedParts` 回傳；引擎會嚴格按該順序串接它們。
- MUST 在不可恢復的錯誤時以例外失敗，而非產出被截斷或損壞的輸出。當某格式超出範圍時，顯式失敗（參見 HLS 後端如何拒絕 live/DRM/fMP4，而非輸出一個損壞的檔案）。
- MUST NOT 深入 core 內部；只使用 §3 符號。如果你需要 core 的 HTTP 傳輸策略（代理/DNS/TLS），透過 `TurboHttpClients.create(config)` 取得客戶端。

---

## 8. 事件與服務

- 事件監聽器與請求攔截器 MUST NOT 拋出例外；匯流排會隔離並記錄失敗，但一個行為良好的插件會處理自己的錯誤。
- 攔截器 MUST 大致純淨且快速；回傳未變更的輸入即為 no-op。它們執行在提交路徑上。
- 服務是一個輕量級的 id→實例註冊表，而非 IoC 容器。透過在 `dependencies` 中列出其 id 來依賴某服務；用 `context.service<T>(id)` 查找它。
- 不要阻塞事件匯流排；把繁重工作卸載出去。

---

## 9. 安全

- 將所有播放清單/清單/重新導向/連結內容視為不可信輸入。驗證協定；拒絕非 `http(s)` 的 URI，除非你的協定明確另有要求（HLS 後端拒絕 `file://` 以防止 SSRF/本地檔案讀取）。
- 不要外洩使用者資料或憑證。插件 MUST NOT 將請求 URL、標頭、cookie 或已下載內容傳輸至第三方端點，除非那是插件明確且有記載的用途。
- 釘選依賴版本；避免把龐大或未經審查的傳遞依賴拉進執行時期。
- 插件 MUST NOT 削弱 TLS（`trustAllCerts`），除非使用者透過配置明確選擇加入；絕不硬編碼開啟它。
- 以引用方式處理機密（金鑰、權杖）；絕不記錄其值。

---

## 10. 打包與分發

- 一個插件儲存庫 SHOULD 只提供一項主要能力。提供一份 `turbodl-plugin.json` 清單（見插件市集文件），並為儲存庫打上適當的 GitHub 主題標籤。
- 在清單與發布說明中都宣告該版本面向的 TurboDL MAJOR 線。
- 提供一個可執行的範例或測試，證明插件能載入並執行其能力。
- 你可以自由選擇插件的授權方式。在 TurboDL 的補充條款下，透過公開 API/擴充點互動並不會使你的插件成為 TurboDL 的衍生作品。

---

## 11. 破壞性變更紀律（給插件作者）

對你自己的插件套用 TurboDL 對 core 所套用的相同紀律：
- 當你變更其 `Plugin.id`、移除它發布的服務，或變更某擴充的可觀察行為時，遞增你插件的 MAJOR。
- 保持 `requiredApiVersion` 準確。
- 在你插件的變更日誌中記錄遷移步驟。

---

## 12. 變更本協定

本文件已版本化。向後相容的釐清會遞增其 MINOR；使先前合規插件失效的變更會遞增其 MAJOR，並且 MUST 隨對應的 core MAJOR 與一份遷移說明一起發布。提案透過 TurboDL 儲存庫進行。
