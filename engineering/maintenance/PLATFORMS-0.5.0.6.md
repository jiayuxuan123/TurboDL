# TurboDL 0.5.0.6 平台边界

| 平台 | 构建 | 验证边界 |
|---|---|---|
| Windows x86_64 | 原生启动器与数据面 | 本机编译、版本检查 |
| Windows ARM64 | 原生启动器与数据面 | 交叉编译和 PE 架构检查 |
| Linux x86_64 glibc/musl | 原生启动器与数据面 | 交叉编译和 ELF 架构检查 |
| Linux ARM64 glibc/musl | 原生启动器与数据面 | 交叉编译和 ELF 架构检查 |
| macOS x86_64/ARM64 | 原生启动器与数据面 | 交叉编译、Mach-O 检查和 universal2 组合 |
| Android | Kotlin/ART | 由云取消费 Maven 模块，不打包裸原生侧车 |

本版修改位于 Kotlin/JVM 核心。原生数据面未改变，只同步启动器版本并重新交叉编译。macOS 未签名、未公证；Windows 未做商业代码签名。
