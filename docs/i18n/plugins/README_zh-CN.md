# TurboDL 插件

> 简体中文翻译 · 原文：[English](../../../README.md) · [中文总览](../README_zh-CN.md)

TurboDL 既是一个独立的下载引擎，**也是**一个可选的插件平台。本目录是插件作者所需一切资料的所在地：

- **[插件接入教程](#编写一个插件)** —— 即本文件：概念 + 一步步的操作指南。
- **[开发协定](CONVENTION_zh-CN.md)** —— 官方的兼容性规则手册（稳定 API、版本、命名、安全）。发布前请先阅读。
- **[插件市场](MARKET_zh-CN.md)** —— 如何通过 GitHub 主题标签 + `turbodl-plugin.json` 清单（[schema](../../plugins/turbodl-plugin.schema.json)）发布/发现插件。

可运行示例位于 [`demo`](../../../demo) 模块：`./gradlew :demo:run --args="1"`（Kotlin 插件）、`"2"`（bootstrap 引导）、`"3"`（shim 适配器）。

---

## 设计理念

三条思想驱动着设计：

1. **内核独立可用。** `turbodl-core` 是一个完整、独立的多线程下载引擎，零插件依赖。插件严格是增量：如果你从不加载任何插件，一切都不会改变。

2. **一切皆插件，内核仅机制。** 运行时内核（`turbo-plugin-runtime`）对 HTTP、HLS 或任何协议一无所知。它只提供机制：生命周期、清理（disposer）、带依赖解析的服务注册表、类型安全事件总线、扩展点注册表、版本握手与诊断。Kotlin 加载器、HTTP 后端、HLS 后端——它们全都是普通插件。即便是未来的 JS 运行时，也只是一个实现了 `PluginLoaderProvider` 的插件。

3. **混合 A+B。** （A）core 内置一个 HTTP 后端，因此开箱即可用。（B）插件后端可以覆盖内置后端，或通过扩展注册表新增协议——而 core 从不依赖运行时。依赖方向严格：`runtime → core`，绝不反向。

结果：一个可供第三方在其上构建的小而稳定的契约面，配合一份兼容性策略（[开发协定](CONVENTION_zh-CN.md)），使得未来破坏性的 core 发布会显式失败，而非静默损坏插件。

---

## 构建模块

| 概念 | 类型 | 用途 |
|---|---|---|
| 插件 | `Plugin` | 你实现的单元。包含 `id`、`requiredApiVersion`、可选的 `dependencies`。 |
| 上下文 | `PluginContext` | 传给 `onLoad`；用于注册服务/事件/扩展。所有注册在卸载时自动清理。 |
| 清理 | `Disposer` | LIFO 清理链；卸载时逐回调隔离地排空。 |
| 服务 | `ServiceRegistry` | 轻量级 id→实例注册表 + 依赖门控。 |
| 事件 | `EventBus` | 观察 `TurboEvent`，并在提交时拦截 `DownloadRequest`。 |
| 扩展点 | `ExtensionPointKey<T>` | 插件实现的类型化契约；消费者按 key/优先级查询。 |
| 版本 | `ApiVersion` | 版本握手：宿主必须满足插件的 `requiredApiVersion`。 |

**内置扩展点**（`dev.turbodl.plugin.runtime.ext.ExtensionPoints`）：
- `DOWNLOAD_BACKEND` —— 新增/覆盖一个协议（`DownloadBackend`）
- `LINK_PARSER` —— 将原始链接转换为 `DownloadRequest`（`LinkParser`）
- `TASK_PRE_HOOK` —— 提交前重写请求（`TaskPreHook`）
- `TASK_POST_HOOK` —— 任务结束后进行响应（`TaskPostHook`）

**内核**按名字认识的唯一扩展点是 `PluginLoaderProvider`（加载器）。

---

## 编写一个插件

### 1. 依赖契约

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. 实现 `Plugin`

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // 唯一、点分隔、带厂商限定
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // 你实际用到的最低 API

    override fun onLoad(context: PluginContext) {
        // 在这里注册服务 / 事件 / 扩展 —— 卸载时全部自动清理
    }
}
```

### 3. 在 `onLoad` 中注册能力

```kotlin
// 其他插件可能依赖的服务：
context.registerService(id, myService)

// 观察引擎事件（卸载时自动取消订阅）：
context.onEvent { event -> /* ... */ }

// 在执行前重写请求：
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// 提供扩展点实现（优先级高者胜出，成为“那个”实现）：
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// 任何其他需要收尾清理的东西：
context.disposer.register { myThreadPool.shutdown() }
```

通过 `context` 注册的一切都会在插件被卸载时自动拆除。如果 `onLoad` 抛出异常，部分注册会被替你回滚。

### 4. 编写 `DownloadBackend`

后端拥有协议；引擎拥有状态、事件、合并与完整性。规则（完整列表见[开发协定 §7](CONVENTION_zh-CN.md)）：

- `supports(request)` —— 廉价、无副作用、保守。
- 尊重 `context.isActive()` 与协程取消。
- 一旦获知总大小就调用 `context.reportTotalSize(total)`（未知则传 `-1`），过程中调用 `context.reportProgress(...)`，用 `context.throttle(bytes)` 遵守全局速度限制。
- 把分片写入 `context.workDir`，**按序**以 `BackendResult.orderedParts` 返回它们。
- 对于不可恢复/超出范围（out-of-scope）的输入，**抛出异常**——绝不产出损坏的文件。
- 需要 core 的 HTTP 传输策略（代理/DNS/TLS）？用 `TurboHttpClients.create(config)`；绝不触碰 core 内部。

参见 `turbo-plugin-hls`，了解一个完整且非平凡的后端（播放列表解析、AES-128、字节区间、显式拒绝不支持的构造）。

### 5. 安装它

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// 让下载路由经过插件后端（无匹配时回退到内置 HTTP）：
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

或者使用 bootstrap 便捷装配：

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. 用诊断验证

```kotlin
println(host.diagnostics().render())
// 列出插件及其状态（LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED）、
// 扩展点、服务、监听器数量。
```

如果你的插件显示为 `INCOMPATIBLE`，说明当前运行的 TurboDL API 不满足你的 `requiredApiVersion`——检查版本握手（[开发协定 §2](CONVENTION_zh-CN.md)）。

### 7. 发布

遵循[插件市场](MARKET_zh-CN.md)的步骤：添加 `turbodl-plugin.json`，为仓库打上 `turbodl-plugin` + 一个分类 + 能力标签，发布构件，打一个 release。

---

## Shim 适配器

“shim”包装一个外部下载器/SDK，并通过 `LinkParser` + `DownloadBackend` 将其暴露给 TurboDL，而 TurboDL 对该系统一无所知。从 `demo/.../Example3ShimAdapter.kt` 中的模板开始——用真实 SDK 替换占位符 `ExternalDownloader`。

---

## 关于 JS 的说明

JavaScript 运行时是一项**预留的、未来的**能力。它会作为一个实现了 `PluginLoaderProvider` 的独立插件发布；core 与 Kotlin 加载器始终保持对 JS 无感知，且内核不会引入任何 JS 引擎。清单为那一天预留了 `entry.language: "js"`。
