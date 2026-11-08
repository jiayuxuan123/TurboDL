# TurboDL 0.5.0.1 — 原生快速路径与稳定性修复

## 新增

- 新增可选原生快速路径 `turbodl native <url>`：Zig 数据面直接完成简单 HTTP Range 下载（网络、按偏移写盘、重试、续传侧车、最终 SHA-256），不启动 JVM。
- 新增 Zig 启动器 `turbodl`：原生 `--version` 即时返回；普通命令继续交接给原有 Kotlin/JVM 引擎。
- DoH 响应在 DNS 解析前限制为 64 KiB，避免异常端点造成无界内存读取。
- JS 定时器能力测试改用线程安全日志容器，消除测试期 `ConcurrentModificationException` 抖动；生产行为未变。

## A/B 验证

同一夹具、同一本地 HTTP 服务、交错运行，每轮独立校验输出 SHA-256：

| 夹具 | 原生 p50 | JVM p50 | 提升 |
|---|---:|---:|---:|
| 32 MiB | 577.8 ms | 1412.0 ms | 2.44x |
| 256 MiB | 2534.7 ms | 4158.4 ms | 1.64x |
| 2 GiB | 27250.8 ms | 34080.7 ms | 1.25x |

原生 C SHA-256 校验器也做了 A/B，但同一 256 MiB 文件上约 1088 ms，慢于 JVM SHA-NI 的约 750 ms，因此**不进入引擎、不随本版发布**。

## 平台

- Windows x86_64：构建、运行、A/B 通过。
- Windows arm64、Linux x86_64/arm64（glibc/musl）、macOS x86_64/arm64/universal2：已交叉编译并检查产物；目标平台运行仍标注为未验证。
- Android：继续使用 Kotlin/ART 主引擎；原生校验候选因 A/B 变慢被淘汰，不强行引入更慢的原生实现。

## 验证

- 全量 7 模块：105 suites / 570 tests / 0 failures / 0 errors / 0 skipped。
- Windows 自包含目录冒烟：`turbodl --version`、`turbodl native`、`turbodl`（JVM）均退出码 0，SHA-256 与夹具一致。

## 兼容性

- 默认引擎、公开 Kotlin API、Maven 坐标、插件 ABI、CLI 原有参数均保持不变。
- 原生快速路径是可选子命令；复杂功能（批量、镜像、代理、HLS、插件）继续使用 JVM 引擎。
