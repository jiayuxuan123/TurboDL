# 第三方组件与致谢

TurboDL 自己的代码是 MIT 协议（见 [LICENSE](../../../LICENSE)）。
这份文件把**别人的东西**列清楚：用到了哪些第三方库、它们的许可、以及哪些是想法的来源。

有这份清单的理由很实际：你要把 TurboDL 集成进自己的产品时，需要知道会连带引入什么。

## 运行时依赖

这些会随你的应用一起发布，它们的许可都是宽松的（Apache-2.0 / MIT），
与闭源商业集成不冲突。

| 组件 | 版本 | 许可 | 用在哪 |
|---|---|---|---|
| [Kotlin 标准库](https://github.com/JetBrains/kotlin) | 2.0.21 工具链 | Apache-2.0 | 全项目语言与标准库 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.9.0 | Apache-2.0 | 并发调度、事件流 |
| [OkHttp](https://github.com/square/okhttp) | 4.12.0 | Apache-2.0 | HTTP 传输、连接复用 |
| [Okio](https://github.com/square/okio) | 随 OkHttp | Apache-2.0 | OkHttp 的 I/O 基础（间接依赖） |
| [quickjs-kt](https://github.com/dokar3/quickjs-kt) | 1.0.15 | Apache-2.0 | JS 引擎的 Kotlin/JVM 绑定 |
| [QuickJS](https://bellard.org/quickjs/) | 随 quickjs-kt 打包 | MIT | JS 引擎本体（Fabrice Bellard） |

只有 `turbodl-core` 是必需的。`turbo-plugin-*` 与 `turbodl-cli` 按需引入：

- `turbo-plugin-js` 才引入 quickjs-kt 与 QuickJS 的原生库。**不用 JS 插件就不会带上它们。**
- `turbo-plugin-hls`、`turbo-plugin-bootstrap` 只依赖 core 与 runtime，不引入额外第三方库。

## 构建期工具

只在开发与 CI 里出现，不进你的产物：Gradle、Kotlin 编译器、
`kotlin-test` 与 `kotlinx-coroutines-test`（测试用，Apache-2.0）。

## 思路来源（无代码引用）

TurboDL 的引擎设计参考了几个成熟开源下载器的做法 —— 分片调度、连接复用、
限速与重试这些问题的解法在业界已经很成熟，值得学习。这些项目是：

[aria2](https://github.com/aria2/aria2)、
[Xtreme Download Manager](https://github.com/subhra74/xdm)、
[axel](https://github.com/axel-download-accelerator/axel)、
[Persepolis](https://github.com/persepolisdm/persepolis)、
[Motrix](https://github.com/agalwood/Motrix)、
[ab-download-manager](https://github.com/amir1376/ab-download-manager)。

**没有复制它们的任何源代码**，因此也不承担其许可的传染性义务；
本清单是致谢，不是许可声明。也不暗示这些项目对 TurboDL 有任何背书。

这段说明同时写在 `LICENSE` 的附加条款第 3 条里，两处一致。

## 如果你要再分发

- TurboDL 自身的代码：保留 `LICENSE` 里的版权与许可声明即可（MIT）。
- 上面的运行时依赖：保留各自的许可与版权声明。用 Gradle 的原生依赖管理不会漏掉这一步；
  如果你把依赖 shade 成一个 fat JAR，请把它们的许可文件一并带上。
- 插件：本仓库的插件随本仓库一起授权；仓库之外的插件由各自作者决定，与本项目无关。
