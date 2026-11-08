# Platform Matrix

| 平台/架构 | 启动器 | 原生数据面 | Kotlin/JVM 引擎 | 状态 |
|---|---|---|---|---|
| Windows x86_64 | COMPILED + TESTED | COMPILED + TESTED | TESTED | 本机验证 |
| Windows arm64 | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| Linux x86_64 glibc | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| Linux x86_64 musl | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| Linux arm64 glibc | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| Linux arm64 musl | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| macOS x86_64 | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| macOS arm64 | COMPILED | COMPILED | JVM 适配 | 运行 BLOCKED |
| macOS universal2 | COMPILED | COMPILED | JVM 适配 | 签名/公证 BLOCKED |
| Android arm64-v8a | N/A | N/A（sidecar 模型不适用） | Kotlin/ART | 原生校验已 A/B 淘汰 |
| Android armeabi-v7a | N/A | N/A | Kotlin/ART | 原生校验已 A/B 淘汰 |
| Android x86_64 | N/A | N/A | Kotlin/ART | 原生校验已 A/B 淘汰 |

“COMPILED” 不等于运行通过；Linux/macOS/Android 真机运行仍需后续实测。
