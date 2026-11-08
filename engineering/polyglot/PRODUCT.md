# TurboDL 0.5.0.1 多语言本地成品

## 定位

Kotlin/JVM 仍是主引擎与兼容边界；原生代码只放在 A/B 证明更快的子系统，
其余一律保留 Kotlin。被 A/B 否决的原生实现不进入成品。

## 多语言分工

| 子系统 | 语言 | 说明 |
|---|---|---|
| API / 插件 / 任务状态机 / HLS / 全功能引擎 | Kotlin/JVM | 默认主引擎，570 项测试基线 |
| 启动器与即时版本反馈 | Zig | 原生秒起，`--version` 不启动 JVM |
| 简单 HTTP Range 数据面（网络+磁盘+续传+SHA） | Zig | `turbodl native ...`，不启动 JVM |
| 最终 SHA-256 校验 | JVM（保留） | 原生 C 版本 A/B 慢约 45%，已淘汰，不发布 |

## 使用

```text
turbodl <url> [options]        # 全功能 Kotlin/JVM 引擎（默认）
turbodl native <url> [options] # 原生快速数据面（简单 HTTP 下载）
turbodl --version              # 原生即时返回
```

原生快速路径支持：位置 URL、`-o/--output`、`-c/--connections`、
`-r/--retry`、`--sha256`、`--known-size`、`--json`、`--quiet`、`--workdir`。
其余参数（batch、mirror、proxy、HLS、插件等）请使用默认 JVM 引擎。

## 平台适配

| 平台 | 启动器 | 原生数据面 | Kotlin 引擎 |
|---|---|---|---|
| Windows x86_64 | 已构建并运行 | 已构建并 A/B 运行 | 已运行 |
| Windows arm64 | 已交叉编译 | 已交叉编译 | JVM（产物适配） |
| Linux x86_64 / arm64 | 已交叉编译（glibc/musl） | 已交叉编译（glibc/musl） | JVM（产物适配） |
| macOS x86_64 / arm64 / universal2 | 已交叉编译 | 已交叉编译 | JVM（产物适配） |
| Android arm64-v8a / armeabi-v7a / x86_64 | 不适用（应用入口） | 不适用（裸 sidecar 不适合 Android 进程模型） | Kotlin/ART 主引擎 |

说明：Android 继续使用 Kotlin/ART 主引擎；原生校验候选因 A/B 变慢被淘汰，
不为了“多语言”而强塞更慢的原生代码。

## A/B 结论

原生数据面在 32 MiB / 256 MiB / 2 GiB 上均比 JVM 路径更快：

- 32 MiB：577.8 ms vs 1412.0 ms，2.44x
- 256 MiB：2534.7 ms vs 4158.4 ms，1.64x
- 2 GiB：27250.8 ms vs 34080.7 ms，1.25x

均逐轮独立校验 SHA-256。原生 SHA 校验器 A/B 更慢，已淘汰。

## 边界

- 原生数据面只覆盖简单 HTTP Range；不含代理、镜像、HLS、插件、批量。
- 公网、TLS、Linux/macOS 真实运行、Android 真机仍需后续实测。
- 本成品为本地构建，不推送、不发布。
