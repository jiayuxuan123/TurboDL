# TurboDL 全范围工业级建设路线图

制定日期：2026-11-04。状态：规划已冻结并进入执行；第一轮证据见
[evidence/2026-11-04-engine-pass1.md](evidence/2026-11-04-engine-pass1.md)。
本文仍是最终验收标准，不代表全部能力已经完成。

## 1. 目标、事实和入口

目标是让 TurboDL 的功能完整性、数据正确性、断点恢复、实际网络表现、资源效率、
SDK 契约及交付质量逐项达到成熟开源下载引擎的相应水平，并在适合并发的链路争取领先。
不以一个吞吐数字、测试总数、已有类名或发布标题替代整体验收。

路线图先冻结任务、消费点、依赖和验收方法，后续实现必须按卡片领取并留下证据；
已完成的第一轮只关闭了下文列出的有限条目，不得据此宣称全范围工业级。
主仓库为 TurboDL；YunGet 是通用 SDK 的消费者，负责网盘业务、Android 生命周期和 UI。

阅读顺序：

1. 本文：范围、依赖、任务卡、阶段门禁。
2. [API_CONTRACTS.md](API_CONTRACTS.md)：公开参数、命令、状态与交互清单。
3. [NETWORK_ACCEPTANCE.md](NETWORK_ACCEPTANCE.md)：本地/公网/竞品实验协议和判分方式。
4. [COMPETITOR_GAP_MATRIX.md](COMPETITOR_GAP_MATRIX.md)：冻结竞品的源码/能力差距和许可证边界。
5. [EXECUTION_BOARD.md](EXECUTION_BOARD.md)：批次、并行边界、8 GiB 公网预算和子卡拆分。
6. [RISK_REGISTER.md](RISK_REGISTER.md)：静态审计发现的待复现数据、资源和兼容风险。
7. [HANDOFF.md](HANDOFF.md)：给较弱模型的执行提示、最小阅读量、交接与结果模板。

静态起点：TurboDL `3368ec4`，版本 `0.4.1.0`；YunGet `0d4d3e4`，版本 `2.7.23`。
执行者先确认当前 HEAD、工作树和依赖，发生漂移时只补相关差异，不重做整仓分析。
旧规划位于 `../YunGet/README.md` 的 TD-01 至 TD-16、YG-01 至 YG-08、REL-01/02。
其中 2026-11-03 的“观察”是修复前快照，不能照抄为当前缺陷。本计划细化其后续执行与验收。

已有依据：0.4.1.0 包含 416、侧车、跨源头、事件顺序、暂停竞态等修复及针对性测试；
第一轮当前源码已重新运行 core 239 项、CLI 11 项，均有 0 失败；YunGet app 历史记录
为 282 项通过。公网第一轮有同一对象下的 curl/aria2/TurboDL 样本，但来源和时序波动明显，
只能证明该轮次和该模型，不能推出全部公网场景通过。
旧实验只给对应提交、来源、参数和时间窗作证，不能推出全部公网场景通过。
官网 Cloudflare 反代 APK 的速度/哈希验证属于分发链路，不是 TurboDL 优于竞品的证据。

## 2. 不变的边界

- core 保持纯 Kotlin/JVM；协议能力通过通用接口或独立后端提供，不识别 YunGet 或具体网盘。
- 宿主下发起点、上限、重试、退避和来源刷新策略；自适应保留，慢启动与其协作。
- “线程数”必须区分配置上限、调度目标、在途请求、真实 socket 和 H2 stream。
- 数据完整性优先；未经验证的区间不能标完成，探测临时失败不能等价于删除断点。
- 已发布的 0.4.1.0/2.7.23 保留；实现变更进入后续新版本，不覆盖已发布附件冒充旧包。
- Windows 客户端继续暂停；以后共享代码方案另行设计，不复活 Flutter 独立桌面端。
- 本机 `hub/`、`accounts/` 不读、不打包、不导出；测试凭据用虚构数据或获授权临时输入。
- 只操作确认的测试 PID；临时服务用 8791–8850 的空闲端口，不使用退役的 8787/8788。

## 3. 何时才能说“达到目标”

建立 `ledger/<task-id>.csv` 分片台账（任务 P00/P00c 产物，只读聚合为汇总），每行至少包含：能力 ID、公开入口、拥有模块、
现状依据、目标行为、对标版本、场景 ID、实现状态、证据状态、阻塞、提交、报告路径。
状态只允许 TODO / IMPLEMENTING / VERIFYING / PASS / FAIL / BLOCKED。
“已实现但未测”放 VERIFYING；跳过和环境不足放 BLOCKED，不能算 PASS。
实现完成率和验收完成率分别报告；冻结清单后新增能力必须补行，不能删除失败行提高覆盖率。

| 维度 | 最终门槛 |
| --- | --- |
| 字节正确性 | 所有必测故障场景输出全文件摘要正确，错误输出零次冒充成功 |
| 断点恢复 | SDK 重建、进程重启、源身份变化、侧车故障均有证据；可续传时不无故从零开始 |
| 公开契约 | 每个参数/命令/事件/扩展点有消费点、行为证据、兼容性结论；弃用项有明确约定 |
| 性能 | 对标共同能力按 NETWORK_ACCEPTANCE 逐行比较；显著回退不得被平均值掩盖 |
| 资源 | 多任务预算兑现；停止后资源回落；长跑没有持续性增长与队列饥饿 |
| 协议/媒体 | 全部规划行逐项交付；不支持项明确拒绝；协议缺口未完不能称全面对标完成 |
| 集成 | Android 真机完成后台、重启、安装升级、保存失败和 UI 状态一致性验收 |
| 发布 | 真正构建产物、公开版本、源码、依赖及哈希一致，可安装、可消费、可回退 |

性能目标不是“所有场景一律快 1.3 倍”：每连接限速且带宽有余量的预登记场景争取 >=1.3x；
聚合限速时目标是不浪费连接且不损失有效吞吐；不具备提速条件仍须证明正确性和资源水平。
不支持的新协议也留在清单里，不能用“插件可扩展”代替实际可用的后端。

## 4. 路线与并行边界

```text
P00 基线/清单 -> P01 可比较实验台 -> P02 公开契约/兼容
                        |
                        +-> C01 HTTP -> C02 断点 -> C03 来源/凭据
                                                  |
                        C04 生命周期 -> C05 调度/策略 -> C06 资源 -> C07 传输 -> C08 错误/重试
                                                  |
                E01 插件 -> E02 HLS/DASH -> E03 FTP/SFTP
                         -> E04 BT/Magnet -> E05 Metalink/ed2k
                                                  |
                          O01 CLI/诊断 -> A01 Android -> A02 UI
                                                  |
                          Q01 长跑/故障 -> Q02 公网复测 -> R01 交付
```

箭头表示主要门禁，不代表可跳过旁支。P01 完成初版后，公网测量伴随每个阶段持续进行；
不能最后才发现真实 CDN、手机或代理不可用。数据/鉴权缺陷先关闭再扩展性能实验。
顺序编号不是日历承诺，每批只拿一张任务卡或两个互不相交的小任务。

允许并行：文档与实现消费点清点、独立协议插件、独立 CLI 适配和 UI 盘点。
禁止同时改 `TurboConfig.kt`、`Models.kt`、`TurboClient.kt`、`SegmentScheduler.kt`、
`SegmentStore.kt` 的同一契约；这些改动先串行评审合入，再让下游跟进。

## 5. 第一阶段：能可信地测，能逐项追踪

下列路径相对 TurboDL。`core/` 简写代表
`turbodl-core/src/main/kotlin/dev/turbodl/core/`；测试简写代表同模块 `src/test/kotlin/dev/turbodl/core/`。
测试文件名写的是可复用入口，不代表已有测试已覆盖任务的全部验收。
插件生产前缀分别为 `turbo-plugin-runtime/src/main/kotlin/dev/turbodl/plugin/`（含 runtime、native）、
`turbo-plugin-hls/src/main/kotlin/dev/turbodl/plugin/hls/`、
`turbo-plugin-js/src/main/kotlin/dev/turbodl/plugin/js/`、
`turbo-plugin-parser/src/main/kotlin/dev/turbodl/plugin/parser/`、
`turbo-plugin-bootstrap/src/main/kotlin/dev/turbodl/plugin/bootstrap/`。

### P00 固定起点和完整清单（旧 TD-06/07、REL）

- 依赖：无。入口：本目录四份文件、Gradle 版本、各模块 manifest、现有测试结果与 `_bench`。
- 工作：冻结两项目 HEAD、干净/脏状态、JDK/OS/设备、真实依赖 JAR 哈希；记录竞品二进制版本、
  源码 commit、构建选项、许可证。用 API_CONTRACTS 建清单，用全部任务卡建台账。
- 产物：`baseline.json`、`ledger/<task-id>.csv`、`owners.json`、`leases/<task-id>.json`、
  `competitors.json`，位于忽略的实验目录，
  将脱敏摘要/表头模板存本目录；不把本机时间戳目录或私有请求头提交。
- 完成：没有“最新版本”这种浮动基线；每条历史结论能关联原始数据，否则标历史待复现。

### P01 比较台与非周期测试语料（旧 TD-07）

- 依赖：P00。入口：`_bench/run_concurrency_matrix.py`、`bench_server.py`、`bench_server_node.js`、
  `RealNetworkABTest.kt`、`RealLinkSweepTest.kt`、`DiagValidityTest.kt`。
- 硬门禁：必须先建立并启用 [EXECUTION_BOARD.md](EXECUTION_BOARD.md) 第 3 节的持久流量账本；
  所有公网 run（包括竞品、重试、断点和失败重跑）都计入本轮 8 GiB 上限。
- 工作：按 NETWORK_ACCEPTANCE 补 curl/aria2/Gopeed 适配器；引擎、进程启动、完整校验时间分开采集。
  现脚本固定档会同时关闭 slowStart/adaptive/tailAssist，而 adaptive 档关闭 slowStart；
  两档不能直接归因于某一个开关，必须增加单变量对照与宿主生产配置。
- 工作：生成带种子、按块偏移变化的非周期文件及独立摘要，排除周期填充掩盖错位写入；
  让故障服务器记录实际请求、区间、socket、发送字节和注入事件。
- 完成：一次命令生成原始 JSONL、摘要和校验结果；非零退出/摘要不符/被跳过不会混进速度赢家。

### P02 公开接口逐项兑现与二进制兼容（旧 TD-06/15）

- 依赖：P00；以 API_CONTRACTS 为清单。入口：`TurboConfig.kt`、`Models.kt`、`TurboClient.kt`、
  `DownloadBackend.kt`、`TurboBackends.kt`、CLI `Main.kt`、`ApiVersion.kt`。
- 工作：逐项追踪字段的消费点、有效范围、运行中生效时点、后端适用性和错误返回；
  有参数没有行为必须实现或显式弃用并迁移，不能静默忽略。兼容保留的 no-op 列白名单说明。
- 复用：`DownloadRequestCompatibilityTest`、`ApiVersionTest`、`ConfigConflictTest`、
  `TransportConfigHotSwapTest`、`BackendOverrideTest`。
- 完成：Java/Kotlin 消费者旧 JAR 编译后换新依赖真实运行，包含默认参数桥、copy/componentN；
  反射数构造器不能替代旧字节码执行。文档样例和每个 CLI 选项有实际消费/运行证据。

## 6. 第二阶段：正确性、断点与网络基础

### C01 HTTP 范围响应与元数据（旧 TD-01）

- 依赖：P01/P02。入口：`SegmentDownloader.kt`、`BuiltinHttpBackend.kt`、`HttpClientFactory.kt`。
- 工作：统一两条写入路径的 200/206/416、短读、超长、总长变化、编码、重定向与探测判定；
  检查整数溢出、空文件、未知大小、HEAD 不可靠、中文 Content-Disposition、URL 文件名优先级。
- 复用：`RangeNotSatisfiableTest`、`AuditFixesRegressionTest`、`GzipIdentityTest`、
  `ProbeMetadataTest`、`ProbeFastFailTest`、`SegmentShrinkSafetyTest`。
- 完成：响应异常不得错位写入/假成功；Range 失效的单流回退有明确安全的截断与断点策略。

### C02 断点身份、持久化与崩溃恢复（旧 TD-02/03）

- 依赖：C01。入口：`BuiltinHttpBackend.prepareResume`、`SegmentStore.kt`、`SegmentScheduler.persistProgress`。
- 工作：定义相同/不同/无法判断三态；强弱 ETag、Last-Modified、指纹、预期摘要与镜像证明分级。
  局部指纹一致不等于全文件一致；身份不明时保留数据并给宿主风险选择，禁止静默拼新旧内容。
- 工作：说明每个写盘承诺的耐久级别；校验 `.tmp/.bak`、动态网格、一致快照、目标删改、
  大小预留、同一 stableKey 双写锁、跨进程 SDK 重建、磁盘满/只读/侧车损坏。
- 复用：`ResumeIntegrityTest`、`ResumeProbeTest`、`ResumeStableKeyTest`、`SegmentStoreTest`、
  `DynamicSegmentSidecarConsistencyTest`、`TailAssistTest`、`ProbeFailureKeepsPartsTest`。
- 完成：NETWORK_ACCEPTANCE 的 R01–R10 全部产证；进程 kill 和真实断电分别报告，不能混称。

### C03 多来源与授权刷新（旧 TD-04/11）

- 依赖：C01/C02。入口：`MirrorPool.kt`、`MirrorHeaderPolicy.kt`、`RequestOrigin.kt`、`HttpClientFactory.kt`。
- 工作：每个来源独立终址/身份/退避；同长度异内容不能仅凭大小判相同；定义可信摘要/块哈希策略。
  通用来源刷新回调要有重入、取消、超时、去重和旧请求隔离约定，由宿主刷新网盘签名链接。
- 工作：证明 301/302/303/307/308、跨端口、HTTPS 降级和镜像切换时的敏感头边界；
  `Proxy-Authorization` 只能到实际代理，不能因为保留而泄露到源站。
- 复用：`MirrorSourceIsolationTest`、`MirrorHeaderPolicyTest`、`RedirectHeaderIsolationTest`、
  `IfRangeGuardTest`、`RedirectReuseTest`、`ChecksumTest`。
- 完成：镜像毒化、凭据泄露和刷新风暴有回归；401/403 时不无意义地靠降线程解决。

### C04 生命周期、异步命令与有序状态（旧 TD-05/16）

- 依赖：P02/C02。入口：`TurboClient.kt`、`Models.kt`、`DownloadBackend.kt`。
- 工作：给 submit/await/pause/resume/cancel/shutdown/updateConfig 写状态迁移表，含排队、
  暂停准备、校验、失败可恢复、清理阶段及命令幂等；终态可查询，晚订阅不丢状态。
  异步命令避免阻塞 Android 主线程；老接口保留清晰兼容路径；取消贯穿 DNS/HTTP/限速/插件。
- 复用：`EventOrderingTest`、`ShutdownDrainContractTest`、`TurboClientTest`、`TaskSlotFairnessTest`。
- 完成：1000 次提交/暂停/继续/取消交错、同键并发和关闭竞争，没有永久 await、双执行、终态倒退。

## 7. 第三阶段：真正的吞吐与资源提升

### C05 自适应、慢启动、调度和收尾（旧 TD-08/09）

- 依赖：P01、C01–C04。入口：`AdaptiveConcurrencyController.kt`、`SegmentScheduler.kt`。
- 工作：按 API_CONTRACTS 做 DOWNWARD/UPWARD × slowStart × 续传 × 背压 × 全局限速交互；
  降档等在途请求排空，采样拒绝混合窗口和收尾窗口，恢复不永久锁档、不周期性无收益探测。
  统一时钟/策略状态所有权，避免多个 worker 与控制循环并发修改决策状态。
- 首批定向复现：配置注释声称 slowStartInitial=0 从上限开始，但当前 `slowStartInit` 的
  slowStart=true 默认分支仍取 max(4,workers/4)。这是已见的声明/消费点不一致，实际影响须以测试确认。
- 工作：拆分粒度、工作窃取、尾速 ETA 与请求成本建模；32 MiB 文件能看到高并发发挥作用；
  不为了跑满连接把小文件切成海量无效请求，保留宿主不同风险策略。
- 复用：`AdaptiveConcurrencyTest`、`RampUpReportingTest`、`SlowStartWarmUpTest`、
  `ConcurrencySaturationTest`、`FanoutCostTest`、`SkewSweepTest`、`TailAssistTest`。
- 完成：本地五模型与公网多个来源都达预登记门槛；汇报实际 socket/stream，不能用 UI 目标数证明并发。

### C06 全局预算、公平性、写盘与资源成本（旧 TD-09/10/16）

- 依赖：C04/C05。入口：`ConnectionBudget.kt`、`SpeedLimiter.kt`、`SegmentStore.kt`、`TurboClient.kt`。
- 工作：任务级/host级/全局连接与缓冲预算覆盖所有内置和插件后端、预热、校验和恢复；
  异构任务有可解释公平策略和防饥饿；限速运行中生效且突发预算有界。
- 工作：Windows/Linux/Android 文件写入路径、稀疏/预留空间、文件句柄、落盘频率和取消清理分开测；
  内存峰值按并行任务总数计算，不靠全局无限 IO 线程提高表面速度。
- 专项核对 `TurboConfig.budgetedIoBufferSize` 的 8 KiB 保底：64×256 的 SDK 合法并发组合
  可能突破默认 32 MiB 预算；不能以 YunGet 限制任务数为理由豁免通用 SDK 的预算契约。
- 复用：`GlobalConnectionBudgetIntegrationTest`、`IoBufferBudgetTest`、`TaskSlotFairnessTest`、
  `SegmentWritePathTest`、`TransportConfigHotSwapTest`、`BackpressureRecoveryDeadlockTest`。
- 完成：预算不超配；1/8/32 任务混合无饥饿；限速 30s 窗口误差 <=5%（边界突发另记）；
  内存/CPU/句柄按 NETWORK_ACCEPTANCE 非劣门槛与已发布版本及竞品比较。

### C07 DNS、TLS、代理和 HTTP 协商（旧 TD-11）

- 依赖：C01/C03。入口：`HttpClientFactory.kt`、`TurboConfig.kt`、`TurboDiagnostics.kt`。
- 工作：系统 DNS/指定 DoH/自动候选、IPv4/IPv6、PAC/HTTP/SOCKS/系统代理、连接池热更新、
  H1/H2 模式逐项证明；取消、DNS 硬超时、证书/主机名校验、重定向上限都要覆盖。
  不支持的组合明确拒绝；不能擅自 trustAll 或回退直连绕过宿主代理策略。
- 复用：`HttpVersionPolicyTest`、`DohParsingTest`、`RealDohProbeTest`、`RedirectPathTest`、
  `StartupLatencyTest`、`TransportConfigHotSwapTest`。
- 完成：公网直连/代理分组、移动网络切换、DNS 故障和 TLS 错误结果可归因，退出有界。

### C08 错误分类、重试、Retry-After 与取消状态机

- 依赖：C01/C03/C05/C07。入口：`SegmentScheduler.kt`、`BuiltinHttpBackend.kt`、
  `HttpClientFactory.kt`、`MirrorPool.kt`、错误与事件模型。
- 工作：统一错误分类（DNS/TLS/连接/读超时/429/503/401/403/410/416/坏 Range/磁盘/校验/取消），
  每类定义是否重试、消耗哪个预算、等待来源、是否暂停扩容、是否切换镜像、是否通知宿主刷新。
  尊重 `Retry-After`，使用有界指数退避和 jitter；取消必须打断等待；镜像失败隔离，禁止重试风暴。
- 完成：可控服务器逐错误注入和公网 403/429/503 样本均能预测终态、尝试次数、等待和断点；
  不把不可重试错误隐藏在最大连接数/最大重试数里，不把取消误记成源失败。

## 8. 第四阶段：插件、媒体和协议差距逐项补齐

### E01 插件运行时和脚本契约（旧 TD-13）

- 依赖：P02/C04/C06。入口：runtime 的 `PluginHost`、`ExtensionRegistry`、`Disposer`、
  `ProtocolRegistry`、`BackendRegistry`；JS 的 `JsHostApi`、`JsRuntime`、`JsIo`、`abi.js`。
- 工作：注册/优先级/冲突/卸载/异常传播/能力授权/超时取消/秘密作用域一致；
  JS 的 CPU 死循环和大内存输入必须说明进程内隔离边界，必要时选可中止运行时或进程边界。
  不把包版本握手等价于可信插件，不让插件绕过网络/存储/连接预算。
- 复用：`PluginHostTest`、`PluginFailureBehaviourTest`、`JsLifecycleTest`、`JsSandboxPolicyTest`、
  `JsIsolationTest`、`JsHostCapabilityTest`、`JsSecretScopeTest`、`EvaluateTypeContractTest`。
- 完成：每个扩展点和 JS ABI 有可运行示例及无权限/失效/取消验收；加载禁用不会影响其他任务。

### E02-HLS / E02-DASH 媒体后端（旧 TD-12）

- 依赖：E01/C02/C06。入口：HLS 生产前缀下的 `HlsManifest.kt`、`HlsBackend.kt`、`HlsIo.kt`、`HlsRequestPolicy.kt`。
- 工作：`E02-HLS` 先收紧现有 VOD/TS/AES-128/ByteRange 的重试、密钥/IV、分片身份、落盘恢复与资源预算；
  再分卡交付 fMP4/init map、主播放列表选择、多音轨、live 滑窗。`E02-DASH` 独立模块，
  先 VOD 静态 MPD，再 dynamic/live；HLS 与 DASH 的成功不能互相外推。
  每种新增格式先确定产物容器和依赖许可证，不能简单拼字节冒充可播放媒体。
- 复用：`HlsManifestParserTest`、`HlsBackendTest`、`HlsRequestPolicyTest`、`HlsRoutingTest`。
- 完成：源片摘要、输出时长/轨道/可播放性独立验证；直播重连不重片/丢片；
  DRM 不绕过，遇到不支持的加密返回明确错误，不输出假成功文件。

### E03 FTP / FTPS / SFTP 后端（旧 TD-14）

- 依赖：E01/C02/C06。入口：`DownloadBackend.kt`、runtime `ext/ProtocolRegistry.kt`、`BackendRegistry.kt`。
- 工作：先 ADR 比较可维护库和许可证，再建独立插件；FTP REST/主动被动/FTPS 证书、
  SFTP host key/认证/偏移恢复分别实施；不得自动接受未知 host key。
- 完成：自有测试服务的连接失败、凭据、偏移续传、重启、损坏检测、预算和取消有证据；
  各协议与 aria2 的实际启用构建比较，不能拿没有 SFTP 支持的竞品二进制作弱基准。

### E04 BT / Magnet（旧 TD-14）

- 依赖：E01/C04/C06。入口：同 E03；外部内核可先借鉴 YunGet Gopeed 接入，核心不依赖 Android。
- 工作：明确元数据获取、tracker/DHT、piece hash、选择文件、优先级、续传状态、做种和上传预算；
  选稳定后端并封装进通用 SDK 命令/状态/取消/版本握手，不把“启动了进程”当完成。
- 完成：获授权固定种子/私有测试 swarm 中，冷启动/无 peer/元数据超时/坏 piece/断点复启均验收；
  无公网 peer 的失败标环境阻塞，不以单个公开 torrent 的波动评价引擎。

### E05 Metalink 与 ed2k（旧 TD-14）

- 依赖：C03/E01/E04。入口：新独立解析/协议插件，注册到现有协议扩展点。
- 工作：Metalink 文件/镜像/块哈希/优先级/坏源隔离，ed2k 文件身份/分块校验/来源发现/恢复独立分卡；
  做同源大小/摘要一致性和协议预算，不允许默默转换为别的协议却丢失原始约束。
- 完成：与冻结版本竞品的受控来源比较；所有功能从 SDK 和 CLI 都可调用，未完成保留缺口。

### O01 CLI、错误与可观测性（旧 TD-15）

- 依赖：P02/C04/C07/E01。入口：CLI `Main.kt`、`Models.kt`、`TurboDiagnostics.kt`、插件 Diagnostics。
- 工作：每个选项合法/非法/缺参/组合都可复现；支持批量任务、退出码、NDJSON 版本与错误结构；
  进度真实，摘要字段绝不放续传键。输出保留任务/尝试/来源关联与阶段时间，默认脱敏。
- 完成：诊断不会无界下载或改掉宿主设置；stdout 机器流不混调试日志；SIGINT/取消后进程退出，断点可用。

## 9. 第五阶段：YunGet 消费者验收与 UI 收尾

本节 `appcode/` 表示 YunGet 的 `app/src/main/kotlin/com/yunget/app/`。

### A01 Android 接入与后台/存储（旧 YG-01 至 YG-06、YG-08）

- 依赖：C02/C04/C06/C07/P02。入口：`appcode/data/download/TurboDownloadManager.kt`、
  `appcode/data/download/EngineManagerFactory.kt`、`appcode/data/prefs/SettingsRepository.kt`、
  `appcode/data/update/UpdateChecker.kt`、`appcode/data/download/DownloadService.kt`、
  `appcode/data/db/AppDatabase.kt`、`DownloadTaskDao.kt`、`DownloadTaskEntity.kt`。
- 工作：旧/新真实依赖解析；stableKey、事件映射、签名刷新、设置生效范围、前台通知/Doze/失网恢复、
  用户停止与系统回收区别；SAF/MediaStore、重名、空间不足、缓存上限与可续传数据保护。
  私密请求头/链接的日志和存储迁移有明确兼容策略。ABI、universal 桥和新版本升级链按设备验证。
- 完成：ARM64 真机至少一台并覆盖最低支持环境或明确 BLOCKED；屏灭/重启/换网/覆盖升级任务一致。
  SDK 只接受通用命令；网盘账号与特殊站点策略留在应用，不为 app 修改引擎通用默认值来作弊。

### A02 现有 Material 3 UI 适度升级（旧 YG-03/07）

- 依赖：A01；此前只做 UI 问题清单。入口：`DownloadScreen.kt`、`ResolveScreen.kt`、
  `SettingsScreen.kt`、`DownloadEngineScreen.kt`、`EngineAdvancedScreen.kt`、`ui/components`、`ui/theme`。
- 工作：保留现有视觉基础，修短任务闪烁、窄屏/长列表/长文件名、大字体、暗色对比、可取消解析、
  按钮即时反馈、错误恢复与缓存占用说明；统一 Material 3 状态和动效、48dp 触控、TalkBack/减少动画。
- 完成：改前后同设备截图和交互/帧时间对照，无功能丢失；显示的实际连接数和设定上限语义准确。

## 10. 最终门禁与持续回归

### Q01 长跑、故障和兼容平台

- 依赖：C/E/O 已集成，A01 对 Android 参加。按 NETWORK_ACCEPTANCE 执行 30min 冒烟、2h 压测、
  24h soak：短/长任务混排、周期取消、代理重建、插件卸载、磁盘水位和网络切换。
- 完成：零损坏/假成功/永久等待/凭据泄漏；收尾任务、句柄、socket、线程和缓冲回落；
  每个异常有原始证据与回归，不把“第二遍没出现”当修复。

### Q02 公网全矩阵与对标复测

- 依赖：P01 后逐步累积，Q01 之后对最终同一候选全量确认。严格执行 NETWORK_ACCEPTANCE，
  包含 1/4/8/16/32/64/128/256、单流普通下载、各竞品支持的共同档位及各自产线模式。
- 完成：性能、断点、错误恢复、资源、协议各有报告；所有强制行 PASS 或显式列入未达成，
  不能只发最快截图。仅两次/一次的历史结果不满足最终统计要求。

### R01 完整交付和公开结论（旧 REL-01/02）

- 依赖：各必测任务验收。干净环境构建所有模块和真实消费者；生成源码提交、版本、依赖/许可证、
  `.jar/.pom/.module`、CLI、Maven 离线包、各 ABI APK 的完整清单和哈希。
- 对照源代码实际发布模块算附件数；不能机械凑历史 20 个。应用更新保留 universal 旧版桥，
  实际检查 API 附件排序/选择及签名升级，修改更新器不会追溯改变已安装的旧版。
- 完成：迁移、回退、支持矩阵、已知限制与性能报告同步；没有全项证据不能用“全面工业级”标题。
  推送、Release、线上覆盖留给对应明确授权轮次；本规划本身不触发发布。

## 11. 下一次执行直接从哪里开始

先做 P00，然后 P01 的最小版本（非周期语料 + 单流/当前版/aria2 三方 + 一条公网来源），
同时完成 P02 的字段清单冻结。P01 的流量账本和来源登记必须先于任何公网下载；本轮累计上限
为 8 GiB，分配、预留和回填规则见 [EXECUTION_BOARD.md](EXECUTION_BOARD.md) 第 3 节。
优先用现有回归核对 C01/C02 已发布修复，接着做真实 kill/restart 断点。
第一份交付应是可重跑的基线、参数台账和首组公网断点证据，不是重新大改并发算法。
后续模型每次只按 HANDOFF 领取一张卡；遇到门禁失败回到拥有模块修复，不跳到另一项掩盖问题。

## 12. 规划审计后追加的硬任务卡

以下卡片由 2026-11-04 的源码审计补入，作为原有伞形任务的必经子卡。没有完成这些子卡时，
对应父任务不得标 PASS。

### P00c 所有权、台账分片与任务租约

- 依赖：P00。入口：`HANDOFF.md`、`EXECUTION_BOARD.md`、实验目录。
- 工作：把总台账拆成 `ledger/<task-id>.csv`，使用 `owners.json` 和 `leases/<task-id>.json`
  记录 owner、branch/worktree、允许写路径、开始 HEAD、锁定热点文件和证据目录；
  由一个聚合器只读合并为汇总。公开项增加或删除仍只改总 schema，分片不得各自改 schema。
- 完成：两个模拟执行者同时领取任务时，第二个在冲突路径上被拒绝；汇总可重现每个 PASS 的来源；
  失败/被取消任务不留下永久租约。

### P01b 8 GiB 流量门禁（TRAFFIC-GATE）

- 依赖：P00c。入口：`_bench/`、`NETWORK_ACCEPTANCE.md` 第 2/5/9 节、实验账本。
- 工作：runner 开跑前按对象大小 × 引擎 × 档位 × 重复数 × 重试余量预留字节；使用原子锁
  防止并行 runner 超卖；运行后按响应体/服务器/proxy 记录回填。字段至少包括
  `budget_bytes`、`wire_bytes`、`budget_remaining`、`budget_stop_reason`。
- 完成：超过 8 GiB 的合成计划被拒绝；超预留 20% 的 cell 自动中止；失败重试也记账；
  无流量证据的 run 不能生成 PASS/性能赢家。

### P02a 契约消费地图与 no-op 白名单

- 依赖：P02。入口：`CONFIG_CONSUMPTION_MATRIX.md`、`CLI_API_CONSUMPTION_MATRIX.md`、源码。
- 工作：机器可读生成“声明 -> 解析/传参 -> 生产消费 -> 行为测试 -> 证据”；每项只能处于
  `ACTIVE`、`DEPRECATED_NOOP`、`MISSING`。no-op 白名单必须有保留原因、版本和迁移计划；
  未分类项使检查失败。重点判定 `dynamicSegmentation`、`overlapMerge`、隐藏 CLI 别名。
- 完成：新增/删除公开字段或 CLI 标记时 CI 能发现漏项；白名单之外没有只赋值不读取的参数；
  每个 ACTIVE 项都有真实行为用例，而不是仅构造对象。

### Q00 测试真实性与假覆盖清理

- 依赖：P00。入口：所有测试模块、Gradle XML、CLI 新测试目录。
- 工作：核对测试是否真的执行断言、是否复刻生产公式而没调用生产实现、是否把配置传给一个已无消费点的
  字段、测试名与断言是否一致。至少清理 `maxStallRecoveries` 的假覆盖信号、
  `ConfigConflictTest` 的本地 helper、取消测试名/断言不一致、CLI 零测试目录。
- 完成：每个行为证据能定位到生产读点和真实断言；测试数不能由空套件/被跳过套件灌水；
  Gradle XML、退出码和测试方法计数三者一致；删除或重写误导性测试并保留变更记录。

### C06b 插件资源网关

- 依赖：C06/E01 的接口评审。入口：`DownloadBackend.kt`、`TurboClient.kt`、
  `ConnectionBudget.kt`、HLS 和插件后端。
- 工作：为内置与插件后端提供统一的连接租约、IO 缓冲租约、限速和归还接口；覆盖预热、
  重试、探测、插件卸载和取消。先做兼容式新增和旧后端适配，不能让插件自行创建不受控连接。
- 完成：内置 HTTP、HLS、测试 Kotlin/JS 后端混跑时，`maxTotalConnections` 与
  `ioBufferTotalBudgetBytes` 仍成立；插件崩溃或取消后租约全部归还。

### C07a 传输一致性

- 依赖：C07 路由主链。入口：`HttpClientFactory.kt`、PAC/DoH/IPv6 测试。
- 工作：明确 PAC 只支持可验证的字面量/`FindProxyForURL` 子集并拒绝其他脚本，或做出执行器 ADR；
  DoH 覆盖 A/AAAA；系统代理、HTTP/SOCKS 鉴权、DIRECT、IPv4/IPv6 回退和请求头泄漏分别验收。
- 完成：能力表与真实实现一致，未支持的 PAC/IPv6 组合明确报错，不静默直连或错走代理。

### E00b 通用哈希与分块身份契约

- 依赖：C03/E01。入口：`Models.kt`、后端 SPI、续传侧车。
- 工作：在 SHA-256 整文件摘要之外定义 block/piece 哈希、算法标识、区间证明和未知哈希三态；
  为 ed2k、BT piece、Metalink 块哈希及镜像一致性提供统一结果，不把算法硬编码到某个宿主。
- 完成：坏块能被定位并重取；哈希算法/分块大小变化使旧侧车失效；镜像不能凭长度冒充同内容。

### E02-0 媒体打包契约

- 依赖：E02 现有 VOD 基线。入口：HLS manifest/backend、新 DASH 模块设计、ffmpeg/ffprobe。
- 工作：先冻结输出容器、fMP4 init/segment、主播放列表、多音轨/字幕、live 滑窗和直播结束语义；
  记录复用器/依赖许可证、ffprobe 验证命令和不支持 DRM 的错误。没有包装出可播放产物的实现不算后端。
- 完成：每种新增媒体格式都有源片/输出轨道/时长/可播放性独立验证；live 重连不重片/丢片。

### E04b 协议服务运行时（BT/Magnet/ed2k 前置）

- 依赖：E01/C06b。入口：新 protocol service 设计、进程/端口/IPC/NAT 配置。
- 工作：定义长生命周期服务、子进程沙箱、IPC、监听端口/UDP、NAT/端口映射、上传预算、
  崩溃恢复和关闭；单次 `DownloadBackend.download()` 不能假装覆盖服务型协议。
- 完成：服务启动、取消、重启、端口占用、崩溃和孤儿进程都有受控测试；默认不暴露不受控公网端口。

### O01b CLI 运行时与自动化

- 依赖：C04/C06b/E01。入口：CLI `Main.kt`、`build.gradle.kts`、新 `turbodl-cli:test`。
- 工作：接入 bootstrap/插件目录/classpath；batch 使用一个 client 和全局预算，提供 `--jobs`；
  NDJSON 强制 `taskId/url`，修启动事件竞态、resume 失败顺序、中断空行和退出码；帮助覆盖并逐项验收
  55 个标记。隐藏别名明确保留/弃用，`--no-overlap` 不能继续表现为有效旋钮。
- 完成：CLI 无网络单测和本地 E2E 覆盖全部解析路径/退出码/NDJSON；批量并发/限速/取消/断点可从
  同一进程完成，插件后端可被实际路由。

### A00 设备与 UI 证据基线

- 依赖：P00。入口：Android 构建、ADB、截图/TalkBack/Doze/升级脚本。
- 工作：冻结最低 API/ABI、至少一台 ARM64 真机、ADB 命令、安装/覆盖升级/换网/屏灭/重启、
  截图基线和无障碍检查；UI 变更附改前改后同设备证据。
- 完成：A01/A02 的“真机 PASS”可复现，不能用构建成功或模拟器截图替代真机；设备缺失写 BLOCKED。

### 审计发现的既有隐性缺陷归属

以下不是新功能，必须进入对应卡的最小复现和回归：

- C04：事件无限容量队列、进度/request 表无统一淘汰、`resume()` 调用线程 `runBlocking`、
  `shutdown()` 未等待活动任务写盘清理、`resume()` 失败的事件顺序。
- C02：stableKey 清洗可能碰撞且缺少同键跨任务写锁；HLS 侧车需单独归入 E02-0。
- C06/C07：`maxConnectionsPerHost` 按整个任务 worker 数而非实际来源 host 计数的语义需证明/修正；
  `maxTotalConnections`/缓冲预算当前按单个 `TurboClient`，需明确并实现 SDK 承诺的作用域；
  `warmUpConnectionCount<=0` 与注释不一致；`maxStallRecoveries` 当前无生产消费点。
- E01：插件 `supports()` 抛错可能阻断内置 fallback，必须有隔离测试。
- E01/E02：HLS/JS 路径对 `DownloadRequest.mirrors` 的消费需补齐或明确拒绝；HLS 续传目前需验证
  playlist 身份/分片哈希，换源不得复用旧分片。
- C07：PAC 字面量解析和 DoH 仅 A 记录与能力声明不一致，归入 C07a。
- C01：`Metadata.resolvedUrl` 固定为原始 URL，重定向后的实际终址需修正或改掉错误文档。
- P02a/Q00：`dynamicSegmentation`、`maxStallRecoveries`、`overlapMerge` 的 no-op/测试假覆盖要先处理；
  `tags` 只在 Created request 中可见，不能宣称所有事件自动携带。
- O01：batch 每任务新建 client、全局预算不共享、progress NDJSON 无 taskId、`--timing` 启动竞态，
  全部归入 O01b。
