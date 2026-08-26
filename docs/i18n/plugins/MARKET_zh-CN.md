# TurboDL 插件市场（基于 GitHub 主题标签）（简体中文）

> 简体中文翻译 · 原文：[English](../../../README.md) · [中文总览](../README_zh-CN.md)

TurboDL 不运行集中式包服务器。插件的“市场”其实就是 **GitHub 主题标签（topic tags）加上机器可读的清单**。任何人发布插件只需：推送一个仓库、添加正确的主题标签、并在仓库根放置 `turbodl-plugin.json`。任何人都能通过 GitHub 主题搜索发现插件。这让生态保持开放、去中心化、零基础设施。

本文档定义这些标签、清单以及发布/发现流程。它与[插件生态开发协定](CONVENTION_zh-CN.md)（兼容性规则手册）和[插件接入教程](README_zh-CN.md)配合使用。

---

## 1. 发现插件

所有 TurboDL 插件都携带根主题标签 **`turbodl-plugin`**。在此浏览：

```
https://github.com/topics/turbodl-plugin
```

通过在 GitHub 搜索中组合主题标签来按能力收窄范围：

```
topic:turbodl-plugin topic:turbodl-backend      # 协议后端
topic:turbodl-plugin topic:turbodl-adapter       # shim/服务适配器
topic:turbodl-plugin topic:turbodl-hls           # 与 HLS 相关
```

GitHub API 同样可用：

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. 主题标签（“货架”）

每个插件仓库 MUST 拥有 `turbodl-plugin`，外加**恰好一个**分类主题标签，以及任意数量的能力主题标签。

**根标签（必需）**
- `turbodl-plugin`

**分类（任选其一，必需）**
- `turbodl-backend` —— 新增/覆盖一个下载协议（`DownloadBackend`）
- `turbodl-adapter` —— 桥接外部系统/服务（shim；通常是 `LinkParser` + 下载后端）
- `turbodl-parser` —— 仅链接/清单解析器（`LinkParser`）
- `turbodl-hook` —— 任务前/后处理（`TaskPreHook` / `TaskPostHook`）
- `turbodl-loader` —— 插件加载器（`PluginLoaderProvider`，例如某个 JS 提供方）

**能力（可选，任意数量）**
- 协议/格式：`turbodl-hls`、`turbodl-dash`、`turbodl-ftp`、`turbodl-magnet`、`turbodl-m3u8`
- 行为：`turbodl-remux`、`turbodl-checksum`、`turbodl-notify`、`turbodl-unpack`
- 集成：`turbodl-cloud`、`turbodl-drm-free`

分类与能力标签正是让市场插件易于构建和查找的东西：你挑选自己的货架，用户按它筛选。

---

## 3. `turbodl-plugin.json` 清单

将此文件放在仓库根。它是工具（或未来的官方索引器）读取以理解你插件的唯一机器可读描述符。

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

字段说明：
- `id` MUST 等于插件的 `Plugin.id`，并遵循开发协定（§4）中的命名规则。
- `turbodl.apiMajor` 与 `requiredApiVersion` MUST 与插件在代码中声明的 `Plugin.requiredApiVersion` 一致。市场/工具正是据此在**下载前**过滤掉无法在给定 TurboDL 版本上运行的插件。
- `category` MUST 是分类主题标签之一；`capabilities` SHOULD 镜像仓库的能力主题标签。
- `entry.language` 目前是 `kotlin`。`js` **预留**给未来的 JS 提供方；core 与 Kotlin 加载器保持对 JS 无感知。
- `artifact.type` 是 `maven`（已发布的 JAR）或 `jar`（`artifact.url` 中的直接发行版资源 URL）。按你的分发方式选择。

用于校验的 JSON Schema 位于 [`turbodl-plugin.schema.json`](../../plugins/turbodl-plugin.schema.json)。

---

## 4. 推荐的仓库布局

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # 清单（仓库根）
├─ README.md                    # 用途、安装片段、支持的 TurboDL MAJOR
├─ LICENSE
├─ src/main/kotlin/...          # Plugin 实现
└─ src/test/kotlin/...          # 证明它能加载并履行能力的测试
```

仓库描述与 README SHOULD 显式声明支持的 TurboDL 主版本线（例如“TurboDL 1.x”）。

---

## 5. 发布清单

1. 依据[接入教程](README_zh-CN.md)和[开发协定](CONVENTION_zh-CN.md)实现一个 `Plugin`。
2. 将 `Plugin.requiredApiVersion` 设为你实际使用到的最低 API。
3. 在仓库根添加 `turbodl-plugin.json`；对照 schema 校验它。
4. 添加 GitHub 主题标签：`turbodl-plugin` + 一个分类 + 能力。
5. 用安装片段和所支持的 TurboDL MAJOR 填写 README。
6. 发布与 `artifact` 匹配的构件（Maven 坐标或发行版 JAR）。
7. 打一个版本号等于清单 `version` 的 release。

这就是“市场”的全部：推送、打标签、完成。没有守门人，没有服务器。

---

## 6. 安装插件（使用者侧）

1. 将插件构件加入你的构建（清单中的 Maven 坐标），与 `turbodl-core` 和 `turbo-plugin-runtime` 一同引入。
2. 安装到你的宿主：

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // 或该插件文档记载的入口类
// 若使用 bootstrap 便捷装配：
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. 版本握手自动运行。如果插件需要的 API 比你的 TurboDL 更新，它会被标记为 `INCOMPATIBLE` 且永不加载——检查 `host.diagnostics().render()`。

---

## 7. 信任与安全

这里没有中心审查，所以请像对待任何依赖一样对待第三方插件：
- 阅读源码；优先选择带测试与清晰许可证的插件。
- 安装前检查清单中的 `apiMajor` 是否与你的 TurboDL 匹配。
- [开发协定 §9](CONVENTION_zh-CN.md) 列出了插件应当遵循的安全规则（不可信输入校验、无数据外泄、无静默削弱 TLS）。违反这些规则的插件应在其仓库上举报，并 MAY 从任何官方索引中除名。

---

## 8. 未来：可选的官方索引

基于主题标签的市场不需要服务器。如果需求增长，项目 MAY 发布一个静态、生成的索引：定期爬取 `topic:turbodl-plugin`、校验每个 `turbodl-plugin.json`，并渲染一个可按分类、能力及所支持的 API 主版本过滤的可搜索列表。这仍将是 GitHub 主题标签之上的一层便利设施，永远不会变成守门人。
