# TurboDL CLI / API 消费矩阵

> 核对日期：2026-11-04  
> 核对范围：`turbodl-cli`、`turbodl-core`、`turbo-plugin-runtime`、`turbo-plugin-bootstrap`、`turbo-plugin-hls` 的定向源码检索与测试声明核对。  
> 验证边界：后续已补 `turbodl-cli:test`，当前 14 个 CLI 契约测试通过，覆盖 55 个解析标记、基础退出码、批量单 client、knownSize 透传和 NDJSON schema；
> 仍不覆盖 Ctrl+C 信号、真实网络终态和所有参数的行为级端到端。
> 状态口径：`PASS`=声明到消费路径清楚，且核心行为有测试或入口实现本身可直接证明；`VERIFYING`=路径存在，但 CLI 级行为、竞态或组合语义未充分验证；`TODO`=发现契约缺口或无可靠消费保证；`DEPRECATED`=保留但已无实际效果。

## 结论摘要

- 解析器实际有 **55 个选项标记**；`--jobs` 和 `--known-size` 已进入帮助及真实消费路径。帮助仍未列出兼容旋钮：`--threads`、`--speed-limit`、`--force-http1`、`--slow-start`、`--adaptive`、`--spc`、`--block-size`、`--min-segment`、`--slow-start-initial`、`--no-tail-assist`、`--no-overlap`、`--stall-timeout`、`--read-timeout`、`--timing`。
- `--no-overlap` 写入的 `TurboConfig.overlapMerge` 已标注 `@Deprecated`，注释明确写明“引擎不再读取它”；除声明和测试里的本地辅助函数外，未找到运行时代码消费点，因此该 CLI 标记实际为 no-op。
- `--json` 不使用 `TurboEvent` 生成主输出，而是 CLI 自己打印 `start/progress/completed/failed`；`--timing` 通过 `CoroutineStart.UNDISPATCHED` 在 submit 前建立订阅，启动事件丢失窗口已收窄；真实信号中断仍待补测。
- `TurboClient.resume()` 的异常分支已先落 `FAILED` 状态并发 `StateChanged(FAILED)`，再由 `Failed` 收尾；`EventOrderingTest` 已覆盖该顺序。同步 join 造成的主线程阻塞仍需异步命令契约。

## 1. CLI 解析器 55 个选项标记

解析入口均为 `turbodl-cli/src/main/kotlin/dev/turbodl/cli/Main.kt:main`；`Options` 声明见同文件 `Options`。除特别注明外，“现有测试”均指核心模块测试，不是 CLI 级测试。

| ID | 入口 | 解析/声明位置 | 真正消费位置（文件 + 符号） | 实际行为 | 现有测试 | 缺口 | 建议状态 |
|---|---|---|---|---|---|---|---|
| CLI-01 | `-h` | `Main.kt:112` | `Main.kt:printHelp` | 立即向 stdout 打印帮助并返回；后续参数不再解析；退出码 0。 | 未找到 CLI 测试。 | 未验证与非法参数组合时的先后顺序。 | PASS |
| CLI-02 | `--help` | `Main.kt:112` | `Main.kt:printHelp` | 与 `-h` 同分支，语义等价。 | 未找到 CLI 测试。 | 同上。 | PASS |
| CLI-03 | `-V` | `Main.kt:113` | `Main.kt:main`、构建注入的 `BUILD_VERSION` | 打印 `turbodl <version>` 并返回；退出码 0。 | 未找到 CLI 测试；版本单一来源见 `turbodl-cli/build.gradle.kts:generateBuildInfo`。 | 未验证生成版本与实际产物一致。 | PASS |
| CLI-04 | `--version` | `Main.kt:113` | 同 `-V` | 与 `-V` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-05 | `-o` | `Main.kt:114`；`Options.output` | `Main.kt:main` 输出路径解析；`TurboClient.submit → DownloadRequest.destination → BuiltinHttpBackend.download` | 仅单任务时作为最终输出；批量或行内路径优先。未找到覆盖既有文件前的拒绝逻辑。 | 未找到 CLI 输出路径测试。 | 多任务、重复路径、既有文件覆盖未验收。 | VERIFYING |
| CLI-06 | `--output` | `Main.kt:114` | 同 `-o` | 与 `-o` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-07 | `-d` | `Main.kt:115`；`Options.dir` | `Main.kt:main` 默认文件名与目录拼接；`DownloadRequest.destination` | 未给行内路径且非单任务 `-o` 时，用目录加 URL 文件名；目录缺失由下载入口递归创建。 | 未找到 CLI 路径测试。 | URL 片段、空文件名、目录冲突未验收。 | VERIFYING |
| CLI-08 | `--dir` | `Main.kt:115` | 同 `-d` | 与 `-d` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-09 | `-c` | `Main.kt:116-118`；`Options.connections` | `Main.kt:downloadOne → TurboConfig.maxConnectionsPerTask → BuiltinHttpBackend.downloadWithStore → SegmentScheduler.run` | 非整数报用法错误；数字被 `coerceIn(1,256)`，例如 0 静默变 1、999 静默变 256。 | `TurboClientTest.multiThreadRangeDownloadIsByteExact`、`ConcurrencySaturationTest`。 | CLI 越界值不是报错而是夹取；无 CLI 测试。 | VERIFYING |
| CLI-10 | `--connections` | `Main.kt:116` | 同 `-c` | 与 `-c` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-11 | `--threads` | `Main.kt:116` | 同 `-c` | 旧别名，和 `-c/--connections` 完全同分支；帮助未列。 | 同上。 | 兼容别名是否长期保留未决策；无 CLI 测试。 | VERIFYING |
| CLI-12 | `--max-total-connections` | `Main.kt:119-121`；`Options.maxTotalConnections` | `TurboConfig.maxTotalConnections → TurboClient.connectionBudget → ConnectionBudget.acquire/release → BuiltinHttpBackend.downloadWithStore` | 解析后夹到 0..8192；0 为不限；CLI batch 共用一个 client，预算覆盖同一批任务。 | `ConnectionBudgetTest`、`GlobalConnectionBudgetIntegrationTest`、`batch uses one client and shares the jobs budget`。 | HLS/插件后端共享预算和真实 socket 峰值仍待验证。 | VERIFYING |
| CLI-13 | `--sha256` | `Main.kt:122-127`；`Options.expectedSha256` | `DownloadRequest.expectedSha256 → TurboClient.finish → sha256Of` | 严格校验 64 位十六进制并转小写；完成后、`COMPLETED` 前校验；失败删续传侧车并置失败。 | `ChecksumTest`。 | CLI 合法/非法输入和退出码无直接测试；JSON 完成行另算一次摘要。 | PASS |
| CLI-14 | `-l` | `Main.kt:129-130`；`Options.speedLimit` | `TurboConfig.globalSpeedLimitBytesPerSec → TurboClient.speedLimiter → BackendContext.throttle → SpeedLimiter.awaitAllow` | `parseSize` 支持 B/KB/MB/GB/K/M/G 和纯字节；无单位十进制按字节；内置后端和 HLS 均需主动调用 `throttle`。 | `TurboClientTest.globalSpeedLimitIsRoughlyRespected`。 | `parseSize` 的 CLI 输入边界无测试；超小限速等待成本未做 CLI 验收。 | PASS |
| CLI-15 | `--limit` | `Main.kt:129` | 同 `-l` | 与 `-l` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-16 | `--speed-limit` | `Main.kt:129` | 同 `-l` | 与 `-l` 同分支，语义等价；帮助未列。 | 同上。 | 隐藏别名兼容策略未定。 | VERIFYING |
| CLI-17 | `-r` | `Main.kt:131-133`；`Options.retry` | `TurboConfig.maxRetries → SegmentScheduler.run`；HLS 还用于 `downloadSegmentWithRetry` | 非整数报错；数字被夹到 0..50；主要控制分片重试，429/503 使用独立更宽预算。 | `TurboClientTest.retriesTransient503WithoutFailingWholeTask`、`FailureReportingTest`。 | 越界静默夹取；CLI 级重试次数无测试。 | VERIFYING |
| CLI-18 | `--retry` | `Main.kt:131` | 同 `-r` | 与 `-r` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-54 | `--jobs` | `Main.kt:Options.jobs`；`buildConfig(maxConcurrentTasks)` | `TurboClient` 任务槽、批处理 `async/awaitAll` | 1..64；一批任务共用一个 client，任务槽由 `maxConcurrentTasks` 控制。 | `batch uses one client and shares the jobs budget`。 | Ctrl+C 和真实网络批处理仍待验证。 | VERIFYING |
| CLI-55 | `--known-size` | `Main.kt:Options.knownSize`；`DownloadRequest.knownSize` | `BuiltinHttpBackend.canSkipProbe` | 非负精确字节数；无旧侧车时跳过独立探测 RTT，首个数据请求仍学习重定向终址并校验实际长度。 | `known size reaches the SDK request and skips the extra probe`。 | 错误 knownSize、Range ignored 与 CLI 真实网络矩阵仍需验证。 | VERIFYING |
| CLI-19 | `-b` | `Main.kt:134`；`Options.batchFile` | `Main.kt:main` 批量文件读取 → `Task` 列表 → `resolved` → `downloadOne` | 空行和以 `#` 开头行忽略；其余以首个空白拆成 URL 和剩余输出路径；不解析引号。批量文件不存在退出 2。 | `batch uses one client and shares the jobs budget`。 | `URL --batch file` 会混下载；`--batch file URL` 却报错，行为依赖参数顺序。 | VERIFYING |
| CLI-20 | `--batch` | `Main.kt:134` | 同 `-b` | 与 `-b` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-21 | `-H` | `Main.kt:135-140`；`Options.headers` | `Main.kt:downloadOne → DownloadRequest.headers → BuiltinHttpBackend.probeWithRetry/downloadWithStore` | 可重复；`linkedMap` 同名键后值覆盖前值；格式要求首个冒号前的键非空。 | `RedirectHeaderIsolationTest`、`RedirectReuseTest`。 | 空值、大小写同名、跨镜像脱敏只部分覆盖；无 CLI 测试。 | PASS |
| CLI-22 | `--header` | `Main.kt:135` | 同 `-H` | 与 `-H` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-23 | `-A` | `Main.kt:141`；`Options.userAgent` | `Main.kt:downloadOne` 写入 `headers.putIfAbsent("User-Agent") → DownloadRequest.headers → HttpClientFactory` 默认 UA 拦截器 | 不会写入 `TurboConfig.userAgent`；显式 `-H User-Agent` 因已存在而压过 `-A`。显式 UA 还禁用 UA 协商。 | `UaNegotiationTest`、`TransportConfigHotSwapTest`。 | `-A` 与 `-H UA` 的优先级无 CLI 测试。 | PASS |
| CLI-24 | `--user-agent` | `Main.kt:141` | 同 `-A` | 与 `-A` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-25 | `-M` | `Main.kt:142`；`Options.mirrors` | `DownloadRequest.mirrors → BuiltinHttpBackend.downloadWithStore → MirrorPool` | 可重复追加；空字符串在核心中被忽略；仅内置 HTTP 后端消费；主 URL 与镜像总大小不一致时镜像失败/剔除。 | `MirrorPoolTest`、`MirrorSourceIsolationTest`。 | CLI 参数到镜像池的端到端无测试；插件后端是否遵守镜像契约未统一。 | PASS |
| CLI-26 | `--mirror` | `Main.kt:142` | 同 `-M` | 与 `-M` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-27 | `-p` | `Main.kt:143`；`Main.kt:parseProxy` | `TurboConfig.proxy → HttpClientFactory.applyProxy` | 支持 `http://host:port`、`socks5://host:port`、`socks://host:port`、裸 `host:port`（按 HTTP）；只解析主机端口，不解析 URL 中的用户名/密码。 | 未找到 CLI 代理解析测试；`RedirectPathTest`/`RealNetworkABTest` 使用 `ProxyMode.System`。 | HTTP/SOCKS、非法格式、认证代理均无端到端测试。 | VERIFYING |
| CLI-28 | `--proxy` | `Main.kt:143` | 同 `-p` | 与 `-p` 同分支，语义等价。 | 同上。 | 同上。 | VERIFYING |
| CLI-29 | `--doh` | `Main.kt:144-145` | `TurboConfig.dns=DnsMode.DoH → HttpClientFactory.applyDns` | 仅非空值写入；空白串回到系统 DNS；URL 合法性未在 CLI 预检。 | `DohParsingTest`；真实网络测试默认跳过。 | 非法 URL 的失败时机和退出码未验证。 | PASS |
| CLI-30 | `--http-policy` | `Main.kt:145-150` | `TurboConfig.httpVersionPolicy → effectiveHttpVersionPolicy → HttpClientFactory.protocols` | 接受 `auto/http1/h1/force-http1/http2/h2/force-http2`；帮助只列 `auto/http1/http2`。 | `HttpVersionPolicyTest`、`ConfigConflictTest`。 | 隐藏值别名未在帮助中说明。 | PASS |
| CLI-31 | `--force-http1` | `Main.kt:151` | 同 `httpVersionPolicy=FORCE_HTTP1` | 与 `--http-policy http1` 的实际枚举写入等价；帮助未列。 | `HttpVersionPolicyTest`。 | 隐藏快捷标记的兼容策略未定。 | VERIFYING |
| CLI-32 | `--no-warmup` | `Main.kt:152` | `TurboConfig.warmUpConnections=false → BuiltinHttpBackend.downloadWithStore → SegmentDownloader.warmUp` | 关闭连接预热；预热默认开启且异步进行，不阻塞分片开始。 | `WarmUpSafetyTest`、`SlowStartWarmUpTest`。 | CLI 开关无直接测试。 | PASS |
| CLI-33 | `--slow-start` | `Main.kt:153` | `TurboConfig.slowStart=true → SegmentScheduler.run` | 默认已是 true；单独使用是 no-op，只有放在 `--no-slow-start` 之后才能重新打开；帮助未列。 | `SlowStartWarmUpTest`、`ConfigConflictTest`。 | 参数顺序语义未文档化。 | VERIFYING |
| CLI-34 | `--no-slow-start` | `Main.kt:154` | `TurboConfig.slowStart=false → SegmentScheduler.run` | 关闭慢启动，初始并发直接取计算后的 workers。 | `SlowStartWarmUpTest`。 | CLI 开关无直接测试。 | PASS |
| CLI-35 | `--adaptive` | `Main.kt:155` | `TurboConfig.adaptiveConcurrency=true → SegmentScheduler.run` | 默认已是 true；单独使用是 no-op，只有放在 `--no-adaptive` 之后才能重新打开；帮助未列。 | `AdaptiveConcurrencyTest`、`AdaptiveConcurrencyControllerTest`。 | 参数顺序语义未文档化。 | VERIFYING |
| CLI-36 | `--no-adaptive` | `Main.kt:156` | `TurboConfig.adaptiveConcurrency=false → SegmentScheduler.run` | 关闭吞吐自适应；全局限速开启时自适应本来也会停用。 | `AdaptiveConcurrencyTest`。 | CLI 开关无直接测试。 | PASS |
| CLI-37 | `--adaptive-mode` | `Main.kt:157-161` | `TurboConfig.adaptiveConcurrencyMode → SegmentScheduler.run` | 接受 `downward/down/upward/up`；帮助只列 `downward/upward`。 | `ConcurrencySaturationTest`、`AdaptiveConcurrencyControllerTest`。 | `down/up` 未写入帮助。 | PASS |
| CLI-38 | `--spc` | `Main.kt:162-163`；`Options.segmentsPerConnection` | `TurboConfig.segmentsPerConnection → SegmentScheduler` 分片网格 | 解析后夹到 1..64；帮助未列。 | `SegmentPlanTest.segmentsPerConnection scales block count`。 | CLI 层未测试；调优旋钮为何公开未说明。 | PASS |
| CLI-39 | `--block-size` | `Main.kt:164`；`Options.blockSize` | `TurboConfig.blockSize → SegmentScheduler` 分片硬上限；`SegmentStore.buildGrid` | 使用 `parseSize`；未显式给值时复制 `TurboConfig()` 默认值；帮助未列。 | `SegmentPlanTest.large file caps block size at blockSize`。 | 非法/极小值的 CLI 退出码未验证。 | PASS |
| CLI-40 | `--min-segment` | `Main.kt:165`；`Options.minSegmentSize` | `TurboConfig.minSegmentSize → SegmentScheduler` 软下限 | 使用 `parseSize`；小于 4KB 会被 `TurboConfig.init` 拒绝，但异常发生在下载函数内，最终按下载失败退出 1，而非用法错误 2；帮助未列。 | `SegmentPlanTest`、`FanoutCostTest`。 | 输入校验时机错误；CLI 级未测试。 | TODO |
| CLI-41 | `--slow-start-initial` | `Main.kt:166-167`；`Options.slowStartInitial` | `TurboConfig.slowStartInitial → SegmentScheduler.run` | 非整数报错；数字夹到至少 1，因此 CLI 无法表达 SDK 的 0=自动；最终仍取 `min(值, workers)`；帮助未列。 | `SlowStartWarmUpTest`、`ConfigConflictTest`。 | CLI 与 SDK 的 0 语义有意不同但未在帮助说明。 | PASS |
| CLI-42 | `--no-tail-assist` | `Main.kt:168` | `TurboConfig.tailAssist=false → SegmentScheduler` 收尾托管 | 关闭收尾分片拆让；帮助未列。 | `TailAssistTest`。 | CLI 开关无直接测试。 | PASS |
| CLI-43 | `--no-overlap` | `Main.kt:169` | `Options.noOverlap → TurboConfig.overlapMerge=false`；`BuiltinHttpBackend` 未找到读取 `overlapMerge` 的路径 | `TurboConfig.overlapMerge` 已 `@Deprecated`，注释明确“引擎不再读取”；因此本标记实际 no-op。帮助已不展示，但解析器仍兼容旧脚本。 | CLI 契约测试记录配置赋值；没有行为差分，因为字段明确弃用。 | 后续大版本可移除或打印弃用告警。 | DEPRECATED |
| CLI-44 | `--stall-timeout` | `Main.kt:170-171` | `TurboConfig.stallTimeoutMs → SegmentScheduler` watchdog；`BuiltinHttpBackend.downloadWhole` | 毫秒；负数夹到 0，0 关闭；帮助未列。 | `StallRecoveryTest`、`DownloadStabilityTest`。 | CLI 开关无直接测试。 | PASS |
| CLI-45 | `--insecure` | `Main.kt:172` | `TurboConfig.trustAllCerts=true → HttpClientFactory.applyTrustAll` | 关闭 TLS 证书校验；只在传输层 client 构建时生效。 | 未找到直接校验 trust-all 是否放行的测试。 | 安全副作用大，缺独立测试和显式告警。 | VERIFYING |
| CLI-46 | `--connect-timeout` | `Main.kt:173-174` | `TurboConfig.connectTimeoutMs → HttpClientFactory` | 毫秒；低于 1000 夹到 1000；无上限。 | `TransportConfigHotSwapTest` 验证传输配置变化会重建 client。 | CLI 输入和极小/极大值无测试。 | PASS |
| CLI-47 | `--read-timeout` | `Main.kt:175-176` | `TurboConfig.readTimeoutMs → HttpClientFactory` | 毫秒；低于 1000 夹到 1000；CLI 默认 60000，SDK 默认 30000；帮助未单独列。 | `FailureReportingTest`、`DownloadStabilityTest` 使用短读超时。 | 与 SDK 默认值差异只写在源码注释，帮助未说明。 | PASS |
| CLI-48 | `--timeout` | `Main.kt:175` | 同 `--read-timeout` | 旧别名，与实际读超时同分支；不是总下载超时。 | 同上。 | 名称容易被误认为总超时。 | PASS |
| CLI-49 | `--json` | `Main.kt:177`；`Options.json` | `Main.kt:printProgress`、`downloadOne` 成功/失败输出、`main` 失败汇总 | 输出 NDJSON：`start`、轮询得到的 `progress`、`completed`、`failed`；仅在存在失败时追加 `summary`；中断钩子输出前带换行。进度行没有 `taskId`/URL。 | 未找到 CLI NDJSON 测试。 | 无 schema 测试；批量归因、空白行、部分失败组合未验收。 | VERIFYING |
| CLI-50 | `--timing` | `Main.kt:178` | `Main.kt:downloadOne` 以 `CoroutineStart.UNDISPATCHED` 在 submit 前订阅 `client.events` | 自动把 `json=true`；输出带 taskId 的 metadata/state 行，并在关闭后输出 `cli_shutdown` 总耗时。真实网络已用于定位 probe/传输/退出开销。 | CLI 契约测试与真实 32 MiB 网络日志。 | 并发 stdout 的严格行序仍无独立锁。 | VERIFYING |
| CLI-51 | `-q` | `Main.kt:179` | `Main.kt` 人类输出分支 | 抑制启动横幅、进度、完成提示和错误提示；不抑制 `--json`，因为 JSON 分支先判断。 | 未找到 CLI 测试。 | `--json --quiet` 组合未文档化。 | PASS |
| CLI-52 | `--quiet` | `Main.kt:179` | 同 `-q` | 与 `-q` 同分支，语义等价。 | 同上。 | 同上。 | PASS |
| CLI-53 | `--progress-interval` | `Main.kt:180-181`；`Options.progressIntervalMs` | `Main.kt:downloadOne` 轮询节流；`TurboClient.progress` 快照读取 | 毫秒；低于 100 夹到 100；只控制 CLI 打印轮询，不写入 `TurboConfig.progressIntervalMs`，核心仍按 SDK 默认 200ms 更新。 | 核心测试只验证 `TurboConfig.progressIntervalMs`，未覆盖 CLI 轮询。 | 100ms 设置不能获得 100ms 核心更新；CLI 与 SDK 参数同名但作用层不同。 | VERIFYING |

### 1.1 别名与等价性结论

- 真正同分支、语义等价：`-h/--help`、`-V/--version`、`-o/--output`、`-d/--dir`、`-c/--connections/--threads`、`-l/--limit/--speed-limit`、`-r/--retry`、`-b/--batch`、`-H/--header`、`-A/--user-agent`、`-M/--mirror`、`-p/--proxy`、`--read-timeout/--timeout`、`-q/--quiet`。
- `--force-http1` 与 `--http-policy http1` 写入同一枚举 `FORCE_HTTP1`，实际等价，但不是同一解析分支。
- `--slow-start`、`--adaptive` 默认已为 true，单独使用无效果；仅可用于覆盖之前出现的对应 `--no-*`。
- `--timing` 不是 `--json` 的别名；它额外设置 `timing=true` 并启动事件收集线程。
- `--no-overlap` 不是等价别名，而是已失效的兼容标记。

### 1.2 帮助覆盖与隐藏选项

`printHelp` 位于 `Main.kt:412-456`。实际未列出的 14 个标记是：

```text
--threads --speed-limit --force-http1 --slow-start --adaptive --spc
--block-size --min-segment --slow-start-initial --no-tail-assist
--no-overlap --stall-timeout --read-timeout --timing
```

此外，`--http-policy` 的帮助未列出 `h1`、`force-http1`、`h2`、`force-http2` 值别名；`--adaptive-mode` 的帮助未列出 `down`、`up`。

### 1.3 `--json`、`--timing` 与退出码

- `--json` 主输出不消费 `TurboEvent`。`start` 在 `submit` 返回后打印；`progress` 来自 `client.progress.value[id]` 轮询；`completed` 由 `client.await` 成功后打印，并额外计算整个输出文件的 SHA-256；`failed` 由 `await` 失败打印；失败汇总只在 `failed > 0` 时打印。
- `progress` 行不含 `taskId`、URL 或稳定键。批量模式依靠顺序和前后 `start`/`completed` 行关联，机器消费者无法仅凭进度行判断归属。
- Ctrl+C 的钩子会打印 `interrupted` JSON，但文本是 `println("\n{...}")`，严格 NDJSON 消费者会读到空行；代码没有显式 `exitProcess(130)`，最终退出码交给 JVM/信号处理，未验证。
- 退出码明确路径：帮助、版本、无任务时输出帮助均为 0；参数格式错误、额外位置参数、批量文件不存在为 2；任一任务失败为 1；解析成功但核心构造异常（如 `--min-segment` 小于 4KB）会进入下载失败路径，退出 1 而不是 2。

### 1.4 batch 与输出路径

- 输出优先级：batch 行内路径 > 单任务 `--output` > `--dir`/当前目录加 URL 文件名。
- `--output` 只在最终任务数为 1 时生效；batch 中若有多条任务，全局 `--output` 被忽略。
- `URL --batch file` 会把位置 URL 与 batch 任务混在一起；`--batch file URL` 则因 `batchFile` 已设置而把 URL 判为无法识别参数并退出 2。该差异只由参数顺序决定。
- batch 行按 `\s+` 限制拆成两段：第一段是 URL，剩余全部作为输出路径，因此路径本身可含空格，但不支持引号语法。
- 未找到重复输出路径、目录与文件冲突、覆盖既有文件前的预检逻辑。

## 2. TurboClient 公开入口

| ID | API | 声明位置 | 真正消费位置（文件 + 符号） | 实际行为 | 现有测试 | 缺口 | 建议状态 |
|---|---|---|---|---|---|---|---|
| API-01 | `TurboClient(config=TurboConfig())` | `TurboClient.kt:47` | `segmentClient`、`downloader`、`speedLimiter`、`connectionBudget`、`builtinBackend` 初始化 | 创建核心下载器、两个客户端策略、事件队列和协程作用域；不自动安装插件 resolver。 | `TurboClientTest`、`BuiltinBackendE2ETest`。 | 多线程构造/关闭压力未单列。 | PASS |
| API-02 | `config` | `TurboClient.kt:49-51` | `runTask`、`BackendContext.config`、`SpeedLimiter`、`ConnectionBudget` 的 provider | 公开只读当前配置；`BackendContext.config` 是活 getter，任务执行中可读到后续 `updateConfig`。 | `TransportConfigHotSwapTest`、`ShutdownDrainContractTest`。 | 配置快照与热更新可见性没有完整逐字段测试。 | PASS |
| API-03 | `updateConfig(newConfig)` | `TurboClient.kt:53-72` | 重建 `segmentClient`；若 `streamClientRef` 已创建则重建；`grantSlotsLocked`、`connectionBudget.limitChanged` | 关闭后直接返回；更新限速/并发/槽位；运行中连接不回缩；不负责更新第三方插件自持客户端，内置后端通过 `onConfigSeen` 自行重建传输 client。 | `ShutdownDrainContractTest.updateConfig retires old segment client`、`TransportConfigHotSwapTest`。 | 插件后端只有遵守 `BackendContext.config` 时才能热更新；无统一强制。 | VERIFYING |
| API-04 | `backendResolver` | `TurboClient.kt:127-132` | `TurboClient.backendFor` → `BackendResolver.resolve` → 失败时 `builtinBackend` | 每个任务执行时解析一次；resolver 返回 null 才走内置后端；resolver 抛异常会让任务失败，而不是回退。 | `BackendOverrideTest`、`BuiltinBackendE2ETest`。 | resolver 抛错、运行时替换 resolver、并发替换未测试。 | PASS |
| API-05 | `events` | `TurboClient.kt:137-164`、`619-623` | `eventQueue` 单消费者转发到 `MutableSharedFlow(replay=0, buffer=256)` | 引擎内事件按入队顺序串行转发；慢订阅者不阻塞任务；订阅前事件丢失；关闭时先关队列并取消 scope。 | `EventOrderingTest`、`ProbeMetadataTest`。 | 无 replay、无丢失提示；`--timing` 正是受影响消费者。 | VERIFYING |
| API-06 | `progress` | `TurboClient.kt` 的 `_progress`、`updateProgress`、`rememberTerminal` | `MutableStateFlow<Map<Long, TaskProgress>>`；CLI 轮询、宿主 UI、测试读取 | 当前任务的最后快照；`updateProgress` 同时触发 `TurboEvent.Progress`；终态后保留可查询窗口，但任务表按终态队列限制为最近 256 条。 | 大量测试读取 `client.progress.value[id]`；`TurboClientTest.terminal task history is bounded`。 | 256 条窗口的淘汰策略和慢消费者内存仍需长跑验证。 | VERIFYING |
| API-07 | `submit(request): Long` | `TurboClient.kt` 的 `submit`、`runTask`、`rememberTerminal` | 分配 ID；写 `requests/completions`；发 `Created`；置 `QUEUED`；启动 job | 立即返回 ID；任务异步排队；关闭后同步抛 `IllegalStateException`；与 shutdown 有二次检查；终态历史有界。 | `TurboClientTest`、`TaskSlotFairnessTest`、`ShutdownDrainContractTest`、`terminal task history is bounded`。 | 窗口淘汰后查询旧任务明确返回 unknown，需要消费者文档化。 | VERIFYING |
| API-08 | `await(id): Result<File>` | `TurboClient.kt:248-250` | `completions[id]` | 等待当前 Deferred；未知 ID 返回失败；暂停会先以 `CancellationException` 完成旧 Deferred，`resume` 后替换为新 Deferred。 | `BuiltinBackendE2ETest.resume 后 await 返回续传任务的结果`。 | 未知 ID、shutdown 中等待、并发 await 未单列。 | PASS |
| API-09 | `pause(id)` | `TurboClient.kt:252-271` | `pauseRequested`、`downloader.cancelCalls`、`jobs.remove/cancelAndJoin`、`setNonTerminalState(PAUSED)` | 异步暂停；保留断点；排队/探测/下载都可能被取消；完成后一定完成当前 Deferred。 | `BuiltinBackendE2ETest`、`DownloadStabilityTest`、`ResumeAfterPauseTest`（后者用重新 submit 模拟宿主继续）。 | job 取消 catch 与 pause cleanup 都可能写 PAUSED，存在重复 `StateChanged(PAUSED)` 风险，未测试。 | VERIFYING |
| API-10 | `resume(id): Boolean` | `TurboClient.kt` 的 `resume`、`runTask` | 同步 join 旧 job；替换 Deferred；重新 `runTask`；`putIfAbsent` 防重复 job | 未知 ID/关闭返回 false；正常可继续；失败分支先 `StateChanged(FAILED)` 再 `Failed`，与普通失败路径一致。 | `BuiltinBackendE2ETest`、`EventOrderingTest` 的 resume 失败顺序。 | 同步 join 对主线程的阻塞仍需异步契约评估。 | VERIFYING |
| API-11 | `cancel(id, deleteOutput=false)` | `TurboClient.kt:349-372` | `cancelRequested`、`downloader.cancelCalls`、删除 `chunkDir`、可选删除 destination、`setState(CANCELED)` | 异步取消；删除临时分片；完成当前 Deferred 为取消失败；保留进度表中的 CANCELED 快照。 | `BuiltinBackendE2ETest`、`ShutdownDrainContractTest.pause racing with cancel...`。 | 排队态/完成态/未知 ID/重复取消语义未系统覆盖。 | PASS |
| API-12 | `shutdown()` | `TurboClient.kt:374-404` | `closed` CAS、关闭 `eventQueue`、取消 scope、关闭两个 OkHttp dispatcher/pool、完成所有 Deferred | 幂等、不抛错；活动任务被取消；待 await 立即失败；事件队列可能随 scope 取消而丢尾部事件。 | `ShutdownDrainContractTest`。 | 关闭期间事件交付与批量任务资源释放未验证。 | VERIFYING |

## 3. TurboEvent 与 TaskState

| ID | 类型/状态 | 声明与发射位置 | 真正消费路径 | 实际行为/顺序 | 现有测试 | 缺口 | 建议状态 |
|---|---|---|---|---|---|---|---|
| EVT-01 | `TurboEvent.Created` | `Models.kt:128`；`TurboClient.submit` 发射 | CLI `--timing` 不处理；插件 `EventBus`、JS `eventToMap`、demo 消费 | 在 ID 分配后、`QUEUED` 前发射；入队顺序为 `Created → Progress(QUEUED) → StateChanged(QUEUED)`。 | 未找到专门断言 `Created` 先于 QUEUED 的测试。 | 无 replay 时会丢；顺序没有直接测试。 | VERIFYING |
| EVT-02 | `TurboEvent.StateChanged` | `Models.kt`；`setState`、`setNonTerminalState` | CLI `--timing`、插件 EventBus、JS bridge、测试 | 正常路径为 QUEUED→PROBING→DOWNLOADING→MERGING→COMPLETED；普通失败和 resume 失败均先落 FAILED 状态再发 Failed。 | `EventOrderingTest` 覆盖普通成功、普通失败和 resume 失败。 | 未覆盖 pause 重复状态、cancel/shutdown 顺序。 | VERIFYING |
| EVT-03 | `TurboEvent.Progress` | `Models.kt:130`；`updateProgress` 发射 | 插件 EventBus、JS bridge、测试；CLI 主 JSON 不消费事件，只轮询 `client.progress` | 每次状态更新和节流后的 `reportProgress` 都发；因此状态改变前会先有一个 `Progress` 事件；0 间隔时全量发。 | `ProbeMetadataTest`、`RampUpReportingTest`、多个进度测试。 | 状态事件与进度事件的精确交错没有完整断言。 | PASS |
| EVT-04 | `TurboEvent.Completed` | `Models.kt:131`；`TurboClient.finish` 发射 | 插件 EventBus、JS bridge、demo、测试终态等待 | 顺序为 `StateChanged(COMPLETED) → Progress(COMPLETED, 最终字节) → Completed → Deferred.success`。 | `EventOrderingTest` 验证 COMPLETED 状态先于 Completed。 | 未断言最终 Progress 在 Completed 前。 | PASS |
| EVT-05 | `TurboEvent.Failed` | `Models.kt`；`runTask` 异常分支 | 插件 EventBus、JS bridge、测试终态等待 | submit/resume 异常均先 `StateChanged(FAILED)`，再含 error 的 Progress，再 Failed。 | `EventOrderingTest` 覆盖普通成功、普通失败和 resume 失败。 | 取消、关闭时的失败事件语义仍需补。 | PASS |
| EVT-06 | `TurboEvent.Metadata` | `Models.kt`；`BackendContext.reportMetadata/reportResolvedMetadata` 实现在 `TurboClient.runTask` | CLI `--timing` 打印 metadata 行；插件 EventBus、JS bridge；`ProbeMetadataTest` | 每次 backend 调用都会发；字段可空；内置 HTTP 传入真实重定向终址和实际 Range 能力；旧六参数接口委托新接口并保持兼容。 | `ProbeMetadataTest` 覆盖 Content-Disposition/ETag/Content-Type、重定向终址和 Range 能力。 | 第三方 backend 未适配新接口时仍只有 fallback 元数据。 | PASS |
| ST-01 | `QUEUED` | `Models.kt:79`；`submit` 的 `setState` | 并发槽、CLI 活动状态判断 | 提交后立即写入；任务可能尚未进入 worker。 | `EventOrderingTest`。 | 排队取消/暂停未系统覆盖。 | PASS |
| ST-02 | `PROBING` | `Models.kt:80`；`runTask` 开头 | 状态与事件；插件 backend 也先经过此状态 | 对所有 backend 都先写 PROBING，即使 HLS/自定义后端并未做 HTTP Range 探测。 | `EventOrderingTest`。 | 状态名会被误解为“后端正在探测”。 | PASS |
| ST-03 | `DOWNLOADING` | `Models.kt`；`runTask`；`reportProgress` | 状态与事件 | 在 backend 真正产出首字节前就可能出现；终态（COMPLETED/FAILED/CANCELED/PAUSED）后的迟到 progress 现在直接忽略，不会把状态改回 DOWNLOADING。 | `EventOrderingTest`、多个进度测试。 | MERGING 期间的迟到 progress 语义仍可细分。 | PASS |
| ST-04 | `MERGING` | `Models.kt:82`；`TurboClient.finish` 无条件设置 | 状态与事件；CLI 人类进度打印“合并中” | 即使 `BackendResult.orderedParts` 已直接指向目标文件、实际无需合并，也会短暂进入 MERGING。 | `EventOrderingTest`。 | 该状态不再等同于“真的在合并”。 | VERIFYING |
| ST-05 | `COMPLETED` | `Models.kt:83`；`finish` 在校验完成后设置 | 状态、Completed 事件、await 成功 | 大小校验和可选 SHA-256 通过后设置；随后发 Completed 并完成 Deferred。 | `EventOrderingTest`、`ChecksumTest`、`BuiltinBackendE2ETest`。 | 无。 | PASS |
| ST-06 | `PAUSED` | `Models.kt:84`；job 取消 catch 与 pause cleanup | 状态、宿主 UI、resume 前置条件 | 异步写入；暂停会先完成当前 Deferred 为取消失败；cleanup 可能重复写 PAUSED。 | `BuiltinBackendE2ETest`、`DownloadStabilityTest`。 | 重复 `StateChanged(PAUSED)` 风险；无精确一次语义。 | VERIFYING |
| ST-07 | `FAILED` | `Models.kt`；`runTask` catch | 状态、Failed 事件、await 失败 | 普通 submit 和 resume 失败都通过 `setState(FAILED)` 写状态并发 StateChanged，然后更新错误进度和 Failed 事件。 | `EventOrderingTest` 普通失败和 resume 失败；`ChecksumTest` 断言最终状态。 | pause/cancel/shutdown 与失败的竞态仍需补。 | PASS |
| ST-08 | `CANCELED` | `Models.kt:86`；cancel cleanup | 状态、宿主 UI、await 失败 | 异步删分片、可选删输出、置 CANCELED，再完成 Deferred；任务条目继续保留。 | `BuiltinBackendE2ETest`、`ShutdownDrainContractTest`。 | 排队态取消、删除失败、终态清理未验证。 | PASS |

## 4. DownloadBackend SPI

| ID | SPI 项 | 声明位置 | 真正消费位置（文件 + 符号） | 实际行为/语义 | 现有测试 | 缺口 | 建议状态 |
|---|---|---|---|---|---|---|---|
| SPI-01 | `DownloadBackend.name` | `DownloadBackend.kt:20-22` | 未找到 `backend.name` 的运行时读取点 | 当前只是实现者声明；`BuiltinHttpBackend="builtin-http"`、`HlsBackend="hls-vod"`，但引擎诊断/日志未读取。 | 测试实现均填写该值。 | 声明称诊断用途，实际未消费。 | TODO |
| SPI-02 | `supports(request)` | `DownloadBackend.kt:24-25` | `BackendRegistry.resolve` 遍历带 owner 的注册项 | 仅在插件 registry 路由时调用；单个插件 `supports()` 抛错会被隔离、记录 owner/operation 并跳过，后续插件与内置 HTTP fallback 继续；VM fatal 仍上抛。 | `PluginFailureBehaviourTest` 的故障插件 + 后续插件 + 内置 fallback 回归。 | 并发卸载和异常日志脱敏仍未专测。 | PASS |
| SPI-03 | `suspend download(context)` | `DownloadBackend.kt:27-37` | `TurboClient.runTask → backend.download(context)` | 后端负责协议层并写入 `workDir`；抛异常由 TurboClient 捕获为任务失败；返回后引擎合并、大小校验、SHA 校验。协作取消依赖后端检查 `isActive()` 或协程取消。 | `BackendOverrideTest`、`EventOrderingTest`、内置/HLS 后端测试。 | 第三方后端“返回后不得继续写”没有强制或测试。 | PASS |
| SPI-04 | `BackendResult.orderedParts` | `DownloadBackend.kt:53-57` | `TurboClient.finish → PartMerger.merge`；单文件目标命中 `alreadyMerged` | 多文件按列表顺序拼接；若唯一 part 的 canonical path 等于 destination，则不做二次合并。 | 内置直写后端和多段 HLS 测试；未找到自定义多 part 顺序测试。 | 第三方多 part 顺序/空列表/不存在文件覆盖不足。 | VERIFYING |
| SPI-05 | `BackendResult.totalBytes` | `DownloadBackend.kt:54` | `TurboClient.finish` 大小校验 | `>0` 时要求最终目标长度完全相等；`-1` 或 `0` 不执行大小相等校验。 | 内置后端与 HLS 测试。 | 第三方后端返回 `-1/0/错误正数` 的契约不明确。 | VERIFYING |
| SPI-06 | `BackendResult.partOffsets` | `DownloadBackend.kt:55-56` | `TurboClient.finish → PartMerger.merge(offsets=...)` | 非 null 时按显式偏移写入，允许重叠幂等；null 时退化为顺序拼接。 | 未找到直接构造非 null `partOffsets` 的测试。 | 偏移长度不匹配、乱序、重叠冲突无测试。 | VERIFYING |
| SPI-07 | `BackendContext.taskId/request/workDir/config` | `DownloadBackend.kt:63-71`；`TurboClient.runTask` 匿名 context | 内置 HTTP 后端、HLS 后端、第三方 backend | `workDir` 已创建；`request` 是提交对象；`config` 是活 getter，不是固定快照；`taskId` 是引擎 ID。 | `PluginFailureBehaviourTest`、`HlsBackendTest`。 | 文档称“快照”但实现为实时配置，语义不一致。 | VERIFYING |
| SPI-08 | `BackendContext.isActive()` | `DownloadBackend.kt:73-74` | `BuiltinHttpBackend` 的循环/调度器回调；`HlsBackend.ensureBackendActive/execute` | 返回捕获的任务 Job 是否 active；pause/cancel 取消 Job 后为 false；但无法单独区分“暂停”和“取消”。 | HLS 有调用点；未找到直接伪造 pause/cancel 后断言第三方 backend 收到 false 的测试。 | 取消与暂停的 SPI 可见语义未测试。 | VERIFYING |
| SPI-09 | `reportTotalSize(total)` | `DownloadBackend.kt:79-80` | `TurboClient.runTask` context 写 `totalRef`；后续 Progress/Metadata/ETA/大小校验使用 | 只更新内部总大小引用，不立即发事件；下一次 Progress 或 Metadata 才体现。 | `ProbeMetadataTest`、内核大小校验测试。 | 多次上报、-1 回退、终态后上报未定义。 | PASS |
| SPI-10 | `reportMetadata(...)` / `reportResolvedMetadata(...)` | `DownloadBackend.kt`；`TurboClient.runTask` | `TurboClient.runTask` 构造 `TurboEvent.Metadata` | 新接口显式传 `resolvedUrl` 和 `supportsRange`；旧六参数接口委托新接口，`totalBytes=totalRef`。 | `ProbeMetadataTest`。 | 插件/第三方 backend 尚未迁移到新接口，需在 ABI 指南中说明。 | PASS |
| SPI-11 | `throttle(bytes)` | `DownloadBackend.kt:76-77` | `TurboClient.runTask → SpeedLimiter.awaitAllow` | 只有后端主动调用才扣全局令牌桶；动态限速在等待循环内重读；非法/非正字节直接返回。 | `TurboClientTest.globalSpeedLimitIsRoughlyRespected`。 | 第三方后端不调用 throttle 时不会限速；取消中的等待只有协程取消能打断。 | PASS |
| SPI-12 | `reportProgress(absoluteBytes, activeConnections)` | `DownloadBackend.kt` | `TurboClient.runTask` 更新 `_progress`，计算速度/ETA，发射 Progress | 参数按累计字节解释；受 `TurboConfig.progressIntervalMs` 节流；终态后的迟到上报被忽略。 | `BaselineMetricsTest`、`RampUpReportingTest`、`ProbeMetadataTest`、`EventOrderingTest`。 | 仍无累计/增量误用的显式防护。 | VERIFYING |
| SPI-13 | `BackendResolver.resolve(request)` | `DownloadBackend.kt:118-120` | `TurboClient.backendFor`；插件由 `BackendRegistry.resolve` 实现 | 每任务调用一次；返回 null 回退内置；自定义 resolver 自身抛错仍会让任务失败并保留错误；`BackendRegistry` 明确按扩展优先级取首个 supports=true。 | `BackendOverrideTest`、`BuiltinBackendE2ETest`、`PluginFailureBehaviourTest`。 | 自定义 resolver 的失败诊断格式未单独断言。 | PASS |
| SPI-14 | `BackendRegistry` 路由 | `BackendRegistry.kt` | `TurboBootstrap.create` 安装到 `client.backendResolver` | 只用 `supports` 选择，不比较 `name`；故障 provider 被跳过并上报 owner；没有内置注册项，未命中返回 null 触发 core fallback。 | `PluginFailureBehaviourTest`、`HlsRoutingTest`。 | 同一 owner 多 provider 的错误聚合尚未专测。 | PASS |

## 5. 高风险阻塞清单

1. **`--no-overlap` 是兼容 no-op。** `TurboConfig.overlapMerge` 已弃用且无运行时读取点；帮助已隐藏，解析器仍接受旧脚本参数。
2. **`--timing` 的订阅窗口已收窄。** 使用 `UNDISPATCHED` 在 submit 前订阅，但 timing 行与普通 NDJSON 仍可能并发写 stdout。
3. **`resume()` 的主线程阻塞。** 当前为同步 join 旧 job；失败事件顺序已经修复，但 Android 主线程仍需使用异步命令或专用调度器。
4. **`pause()` 存在重复 `PAUSED` 状态事件风险。** job 取消 catch 和 pause cleanup 都可能写 `PAUSED`；没有精确一次或去重保证测试。
5. **TurboClient 终态历史只保留最近 256 条。** 长生命周期宿主不会无界增长，但窗口外旧任务不可再查询。
6. **CLI batch 与位置 URL 混用依赖参数顺序。** `URL --batch file` 混下，`--batch file URL` 退出 2；全局 `--output` 在多任务中被忽略，且没有重复/覆盖路径预检。
7. **CLI 参数校验与退出码分层不干净。** 多个范围参数静默夹取；`--min-segment` 小于 4KB 在 parse 后由 `TurboConfig` 抛异常，被记为下载失败退出 1，而不是用法错误 2。
8. **`Metadata` 的真实终址/Range 已落地。** `reportResolvedMetadata` 公开实际重定向终址和实际 Range 能力，旧六参数接口保持兼容。
9. **SPI 终态防护已落地。** `reportProgress()` 忽略终态后的迟到回调；第三方 backend 的后台写线程隔离仍属于插件生命周期任务。
10. **CLI 契约测试覆盖 55 个标记。** Ctrl+C 真实信号、真实网络批处理和插件后端路由仍未覆盖。
