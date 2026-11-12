# TurboDL 0.5.0.4 平台边界

## 构建与运行

| 平台 | 构建 | 验证边界 |
|---|---|---|
| Windows x86_64 | 原生启动器与数据面 | 本机编译；启动器和 CLI 版本检查 |
| Windows ARM64 | 原生启动器与数据面 | 交叉编译和 PE 架构检查，未在本机运行 |
| Linux x86_64 glibc/musl | 原生启动器与数据面 | 交叉编译和 ELF 架构检查，未在本机运行 |
| Linux ARM64 glibc/musl | 原生启动器与数据面 | 交叉编译和 ELF 架构检查，未在原生设备运行 |
| macOS x86_64/ARM64 | 原生启动器与数据面 | 交叉编译和 Mach-O 架构检查；组合 universal2 |
| Android | Kotlin/ART | 不打包裸原生侧车，继续由云取 Android 应用消费 Maven 模块 |

## 签名与公证

- macOS 产物为未签名研究/可选原生构建，不声明公证或 Gatekeeper 通过。
- Windows 产物未做商业代码签名。
- Android 正式 APK 的签名在云取发布门禁中单独验证。

## 兼容性

- JVM 目标保持 Java 17 字节码。
- Maven 模块、CLI 参数、任务策略、插件 ABI 和断点格式保持兼容。
- 原生路径仍只用于支持的普通 HTTP 下载；代理、HLS、插件和复杂鉴权继续走 Kotlin/JVM 路径。
