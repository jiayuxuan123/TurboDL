# TurboDL 0.5.0.2 平台验证

| 平台 | 架构 | 原生构建 | 实际运行 | 签名与公证 |
| --- | --- | --- | --- | --- |
| Windows | x86_64 | 已编译；PE 架构检查 | 本机 CLI 与 SDK 验证 | 未进行发行签名 |
| Windows | arm64 | 已交叉编译；PE 架构检查 | BLOCKED：无运行设备 | 未进行发行签名 |
| Linux glibc 目标 | x86_64 / arm64 | 已交叉编译；ELF 架构检查 | BLOCKED：无本轮原生运行环境 | 不适用 |
| Linux musl 目标 | x86_64 / arm64 | 已交叉编译；ELF 架构检查 | BLOCKED：无本轮原生运行环境 | 不适用 |
| macOS | x86_64 / arm64 | 已交叉编译；Mach-O 架构检查 | BLOCKED：无 Mac | 未核验签名、未公证 |
| macOS Universal 2 | x86_64 + arm64 | 由已检查的两个薄构件组合，校验架构与原始字节 | BLOCKED：无 Mac | 未核验签名、未公证 |
| Android | arm64-v8a / armeabi-v7a / x86_64 | Kotlin/ART；由云取应用包验证 | BLOCKED：本轮 ADB 无已授权设备 | 应用 APK 单独核对签名 |

原生工具链为 Zig 0.14.1，下载包使用官方 SHA-256 校验。JVM 字节码目标保持 17，本机验证使用 JDK 21.0.12。目标标签、编译成功、构件检查和实际运行是不同结论；未运行的平台不标为运行通过。

Android 没有新增裸原生侧车进程，继续使用现有 SDK 与插件路径。Windows 原生 CLI 不代表恢复云取 Windows 客户端开发。
