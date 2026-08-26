# TurboDL 插件生态开发协定

> 简体中文翻译 · 原文：[English](../../../README.md) · [中文总览](../README_zh-CN.md)

**状态：** 正式 · **版本：** 1.0 · **适用于 TurboDL API：** `1.x`

这是 TurboDL 项目与插件作者之间的正式契约。它存在的意义在于：即使 core 出现破坏性更新，也不会静默弄坏每个插件，生态仍能成长。本协定由 TurboDL 项目自身维护（不委托给第三方），以确保兼容性规则在整个生态中保持一致。

如果你只是想*写*一个插件，请先阅读[插件接入教程](README_zh-CN.md)；本文档是该教程所依赖的*规则手册*。

---

## 1. 范围与术语

- **Core（内核库）** —— `turbodl-core` 模块：独立的下载引擎，以及插件允许使用的公开数据模型/契约。
- **运行时内核** —— `turbo-plugin-runtime` 模块：生命周期、disposer、服务注册表、事件总线、扩展点注册表、版本握手。仅机制，无业务逻辑。
- **插件** —— 一切实现了 `dev.turbodl.plugin.runtime.Plugin` 的东西。加载器、后端、解析器、钩子与适配器都是插件。只有内核不是插件。
- **稳定 API** —— §3 中列出的符号。其余一切均为内部实现，可能随时变更而不另行通知。
- **MUST / SHOULD / MAY** 遵循 RFC 2119。

---

## 2. 版本与兼容性策略

TurboDL 采用语义化版本对其**面向插件的公开 API** 进行版本管理，以 `dev.turbodl.core.ApiVersion.CURRENT` 暴露。

- **MAJOR（主版本）** —— 对 §3 中任一稳定 API 符号造成破坏性变更时递增。跨主版本（Cross-MAJOR）始终视为不兼容。
- **MINOR（次版本）** —— 向后兼容的新增（带默认值的新方法、新扩展点、新的可选配置）时递增。
- **PATCH（补丁版本）** —— 向后兼容的修复时递增。

由内核强制执行的握手规则：

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- 每个插件声明 `Plugin.requiredApiVersion`（默认 `1.0.0`）。
- 宿主仅当 `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)` 成立时才加载插件。
- 不匹配时，插件被标记为 `PluginState.INCOMPATIBLE`，`onLoad` **绝不被调用**，并记录一条诊断信息。这是有意为之：破坏性发布会在加载期显式失败，而不是损坏行为。

对插件作者的要求：
- MUST 将 `requiredApiVersion` 设为你实际使用到的 API 的最低版本。
- SHOULD 为你支持的每个 core 主版本发布一个新的插件版本。
- MUST NOT 依赖内部（§3 之外的）类来绕过握手。

### TurboDL 对插件作者的承诺

在同一个主版本线（MAJOR line）内，项目：
- MUST NOT 移除或更改任何稳定 API 符号的签名。
- MUST NOT 以破坏既有实现的方式更改扩展点的文档化语义。
- MAY 新增稳定 API（MINOR）——新增必须源码级与二进制级兼容（接口新增需随附默认实现）。
- MUST 在 `CHANGELOG` 中记录每次变更，并且对于主版本递增，提供迁移说明（migration note）。

---

## 3. 稳定 API 面（`1.x`）

只有以下符号受兼容性策略覆盖。包前缀：`dev.turbodl.core.*`（core）与 `dev.turbodl.plugin.runtime.*`（内核）。

**core 契约**
- `ApiVersion`（+ `CURRENT`、`satisfies`、`parse`）
- `DownloadRequest`、`TaskState`、`TaskProgress`、`TurboEvent`（sealed 层级结构）
- `DownloadBackend`、`BackendContext`、`BackendResult`、`BackendResolver`
- `TurboConfig`、`ProxyMode`、`ProxyType`、`DnsMode`
- `TurboClient` 公开方法：`submit`、`await`、`pause`、`resume`、`cancel`、`updateConfig`、`shutdown`、`events`、`progress`、`backendResolver`
- `TurboBackends.builtinHttp`、`TurboHttpClients.create`

**运行时内核契约**
- `Plugin`、`PluginContext`（+ 具体化的 `service` 辅助函数）
- `PluginHost` 公开方法：`install`、`installAll`、`uninstall`、`shutdown`、`publishEvent`、`applyRequestInterceptors`、`diagnostics`，以及 `services`/`extensions`/`eventBus` 访问器
- `Disposer`、`PluginState`、`PluginInfo`、`DiagnosticsSnapshot`
- `ExtensionPointKey`、`ExtensionRegistration`、`ExtensionRegistry`、`ServiceRegistry`、`EventBus`
- `PluginLoaderProvider`（+ `KEY`）、`PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`：`ExtensionPoints`、`LinkParser`、`TaskPreHook`、`TaskPostHook`、`BackendRegistry`

**明确不稳定的**（内部实现；请勿依赖）：`SegmentDownloader`、`SegmentScheduler`、`BuiltinHttpBackend`、`HttpClientFactory`、`PartMerger`、`SpeedLimiter`，以及上述未列出的任何内容。

---

## 4. 插件身份与命名

- `Plugin.id` MUST 全局唯一、跨版本稳定、小写、点分隔：`<category>.<name>`，例如 `backend.http`、`backend.hls`、`loader.kotlin`、`adapter.cordis`。
- 由官方项目拥有的保留分类前缀：`backend.`、`loader.`、`core.`。第三方插件 SHOULD 使用厂商限定的名称，例如 `adapter.acme-cloud`、`backend.acme-ftp`（新协议后端允许使用 `backend.`，但 SHOULD 加厂商限定）。
- 通过 `registerService` 注册的服务 id 遵循相同规则；对于插件的主服务，SHOULD 与插件 id 保持一致。
- 更改已发布的 `Plugin.id` 对任何依赖它的方面都是一种破坏性变更；请将其视为你插件的 MAJOR 事件。

---

## 5. 生命周期契约

- `onLoad` 恰好运行一次，且仅在（a）版本握手通过且（b）所有声明的 `dependencies`（服务 id）都已就位之后。
- 插件 MUST 为每个副作用注册匹配的清理。实践中，优先使用 `PluginContext` 的方法——服务/事件/扩展注册会自动接入 disposer——其他一切（线程、套接字、临时文件、外部 SDK 句柄）用 `context.disposer.register { ... }`。
- 若 `onLoad` 抛出异常，宿主会回滚插件的 disposer 链并将其标记为 `FAILED`。部分副作用 MUST 可安全回滚。
- `onUnload` MAY 做额外的工作，但 MUST NOT 假设 disposer 已运行（它在其之后运行）。
- 插件 MUST 随时可安全 `uninstall`：卸载之后，插件的任何服务、监听器或扩展实现都不得再可达。
- `onLoad`/`onUnload` MUST 快速返回。长时间/阻塞性的工作应放在插件自己的协程/线程上，通过 disposer 拆除。

---

## 6. 扩展点

- 通过 `PluginContext.registerExtension(key, impl, priority)` 注册实现。
- 在消费者选择“那个”实现（例如后端路由）时，较高的 `priority` 胜出。官方基础插件使用优先级 `0`；打算覆盖基础能力的插件使用更高值（HLS 用 `100`；适配器常用 `200`）。选择能达成你意图的最低优先级。
- `DownloadBackend.supports` MUST 廉价、无副作用且保守——只为你确实能处理的请求返回 `true`，这样路由保持可预测，不匹配的请求会落到内置 HTTP 后端。
- `LinkParser.parse` MUST 对不处理的输入返回 `null`（而非抛出异常），以便路由器尝试下一个解析器。
- 钩子/解析器/后端的实现 MUST 容忍被并发调用。

---

## 7. 后端编写规则

`DownloadBackend` 只拥有协议层；引擎拥有状态、事件、合并与完整性。一个后端：
- MUST 尊重协作式取消：检查 `BackendContext.isActive()` 与协程取消；被暂停/取消时立即停止。
- MUST 一旦获知大小就通过 `reportTotalSize` 报告（未知/流式时用 `-1`），并通过 `reportProgress` 报告进度。
- SHOULD 通过 `BackendContext.throttle(bytes)` 对字节写入限速，以便跨任务遵守全局速度限制。
- MUST 将输出写入 `BackendContext.workDir`，并以有序的 `BackendResult.orderedParts` 返回；引擎严格按该顺序拼接。
- MUST 在不可恢复的错误上抛出异常，而非产出截断或损坏的输出。当一种格式超出范围时，显式失败（参见 HLS 后端如何拒绝 live/DRM/fMP4，而不是产出坏文件）。
- MUST NOT 触及 core 内部；只使用 §3 中的符号。若需要 core 的 HTTP 传输策略（代理/DNS/TLS），通过 `TurboHttpClients.create(config)` 获取客户端。

---

## 8. 事件与服务

- 事件监听器与请求拦截器 MUST NOT 抛出异常；总线会隔离并记录失败，但行为良好的插件会自己处理错误。
- 拦截器 MUST 尽量纯净且快速；要 no-op 就直接原样返回输入。它们运行在提交路径（submit path）上。
- 服务是轻量级 id→实例注册表，不是 IoC 容器。通过在 `dependencies` 中列出其 id 来依赖某服务；用 `context.service<T>(id)` 查找。
- 不要阻塞事件总线；把繁重的工作卸载到别处。

---

## 9. 安全与防护

- 将一切 playlist/manifest/redirect/link 内容视为不可信输入。校验 scheme（协议）；除非你的协议明确需要，否则拒绝非 `http(s)` URI（HLS 后端拒绝 `file://` 以防 SSRF/本地文件读取）。
- 不得外泄用户数据或凭据。除非这恰恰是插件明确且有文档记载的目的，否则插件 MUST NOT 将请求 URL、头、cookie 或下载内容发送给第三方端点。
- 固定依赖版本；避免把庞大或未经验证的传递依赖拉进运行时。
- 插件 MUST NOT 削弱 TLS（`trustAllCerts`），除非用户通过配置显式选择；绝不硬编码开启。
- 以引用方式处理机密（密钥、令牌）；绝不记录其值。

---

## 10. 打包与分发

- 一个插件仓库 SHOULD 交付一个主要能力。提供 `turbodl-plugin.json` 清单（参见插件市场文档），并用恰当的 GitHub 主题标签标记仓库。
- 在清单与发布说明中都声明该发布面向哪条 TurboDL MAJOR 线。
- 提供一个可运行的示例或测试，证明插件能被加载并履行其能力。
- 你可以按自己的意愿为插件授权。根据 TurboDL 的补充条款，通过公开 API/扩展点交互不会使你的插件成为 TurboDL 的衍生作品。

---

## 11. 破坏性变更纪律（针对插件作者）

把 TurboDL 应用于 core 的同一套纪律也应用到自己的插件上：
- 当你变更插件的 `Plugin.id`、移除其发布的某个服务，或改变某个扩展的可观察行为时，提升插件的 MAJOR。
- 保持 `requiredApiVersion` 准确。
- 在插件的 changelog 中记录迁移步骤。

---

## 12. 修订本协定

本文档是有版本的。向后兼容的澄清递增其 MINOR；会使此前合规的插件失效的变更递增其 MAJOR，并 MUST 随相应的 core MAJOR 一并发布、附上迁移说明。提案经由 TurboDL 仓库进行。
