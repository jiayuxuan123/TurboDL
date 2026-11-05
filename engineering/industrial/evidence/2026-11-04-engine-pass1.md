# TurboDL 引擎第一阶段实测记录

日期：2026-11-04（Asia/Shanghai）。这是阶段性证据，不是“全面工业级完成”声明。

## 基线与环境

- TurboDL 基线提交：`3368ec4`，工作树为执行中的未提交改动。
- JDK：21；Windows；CLI fat JAR SHA-256：`DF33ECD43F8CECCB50AF0EBF47A405B28EABF61925CCC31202A6060FC617A1B6`。
- 公网源：Electron `v45.2.2` 的 Chromedriver ZIP，32,241,380 字节；
  SHA-256 `eaa589420f83de98efcb8845badb6e439a7539d3f368edf875ca9ea0674661be`。
- 代理：`http://127.0.0.1:7897`。不同 phase 跨越数分钟，CDN/代理波动明显，
  因此单次结果不能外推；失败样本保留，不参与速度赢家。

## 回归

- `turbodl-core`：75 个测试套件、258 项测试、0 失败、0 跳过（全量 `--rerun-tasks`）。
- `turbodl-cli`：1 个测试套件、13 项测试、0 失败；覆盖 54 个解析标记、NDJSON schema、ASCII 安全转义和 batch 单 client。
- `turbo-plugin-hls`：5 个测试套件、47 项测试、0 失败；新增单 `EXT-X-MAP`/fMP4 init map 下载、缓存和字节顺序回归。
- `traffic_budget` + `run_engine_compare` + `restart_resume`：21 项当前 runner/budget Python 单测通过（本轮已把 prewarm 改为逐引擎创建独立 lease）。
- `git diff --check`（CRLF 配置下）通过；未提交。

## 已落地的行为变化

1. `DOWNWARD` 自适应在无显式 `slowStartInitial` 时从用户连接上限起跑。
2. `DOWNWARD` 首次下探至少需要 64 MiB 有效传输和 2 秒稳态观察；短文件不再中途降档。
3. 单连接任务只生成一个 Range 分片，避免 `segmentsPerConnection` 造成无收益的串行请求。
4. `tailAssistMinBytes` 默认从 2 MiB 调整为 256 KiB；真实验证最后 0.5–1 MiB
   不再长期由单连接拖尾。
5. 跳过探测且发生重定向时，首个 source 请求单飞学习终址；32 连接重定向端点从
   24 次访问降到 1 次，后续分片直接打最终 URL。
6. `Metadata` 可返回真实 resolved URL 和实际 Range 支持，不再由总大小伪推断；
   新增 `reportResolvedMetadata`，同时保留旧六参数 `reportMetadata` 并委托新接口，
   不破坏既有 backend 的二进制兼容。
7. `dynamicSegmentation=false` 现在确实关闭 tail assist；`maxStallRecoveries` 明确标为弃用兼容字段。
8. `maxConnectionsPerHost` 在多镜像路径按来源独立限制，并通过原子领取避免并发超发。
9. 终态任务历史只保留最近 256 条，避免长跑 SDK 的进度/请求/完成 Map 无界增长。
10. CLI JSON start/progress/completed/failed 带 taskId；`--timing` 用 UNDISPATCHED 订阅，
   关闭启动事件丢失窗口。
11. resume 异常先发 `StateChanged(FAILED)` 再发 `Failed`；迟到 backend progress 不能把终态改回下载中。
12. 强/弱 ETag、`W/` ETag、缺失校验器和备份侧车统一走多区间内容指纹；首个前缀相同不能再覆盖后续已确认的 MISMATCH。
13. 指纹覆盖已完成网格（大于 8 片时均匀抽样，最多 24 个 64 KiB 窗口）；任一计划样本失败时只能得到 PARTIAL_UNVERIFIABLE，不能升级为 MATCH。
14. 目标数据先 `force(false)`，之后才持久化侧车；`.validator` 使用临时文件、fsync 和备份替换。
15. 侧车恢复同时识别主文件和 `.bak`；丢弃失效进度时用 `reset()` 同删主/备/临时文件和目标，且先确认目标可删除。
16. `loadOrCreate()` 后发生截断会被 `ensureLengthChecked()` 重新识别；目标比新总长更长时显式截断旧尾部。
17. 同长度替换目标通过文件身份（POSIX fileKey；Windows NTFS ADS）识别；完成后清理身份 ADS。
18. 不安全 `stableKey` 追加 SHA-256 摘要避免目录碰撞；同 key 由进程内注册表和 OS 文件锁独占，冲突明确返回 `STABLE_KEY_BUSY`。
19. `shutdown()` 有界等待活动 Job 完成 finally（含 stableKey 租约释放），新 client 可立即复用同一断点目录。
20. CLI NDJSON 将所有非 ASCII 字符输出为 `\uXXXX`，不再依赖 Windows 控制台 GBK/UTF-8 编码。
21. CLI batch 改为整批共用一个 `TurboClient`，新增 `--jobs` 控制任务并发；任务级 `workDir` 保持输出旁的断点目录，同时让 `maxTotalConnections` 和全局限速覆盖整批任务。
22. 镜像大小不一致现在作为结构性来源失败永久隔离，不再在普通退避后重新抢占；同时事件 staging queue 改为有界，慢消费者下优先丢弃进度/元数据但保留终态。
23. 多连接任务的探测改用分片客户端；成功探测不再取消 Call，并消费 `bytes=0-0` 的极小响应体，使首个分片可以复用探测连接。新增 `ProbeConnectionReuseTest` 验证本地源端口复用。
24. 单连接新任务在探测后改用普通整文件 GET；有旧分片时仍走 Range 续传。新增 `SingleConnectionWholeGetTest` 验证只有探测请求使用 Range。

## C02 真实进程恢复

- 本地独立 JVM 强杀：16 MiB，8 连接；第一次响应 6,356,993 字节、耐久侧车
  3,350,528 字节，强杀后第二次响应 13,885,441 字节，最终 SHA-256 通过。
- 本地双 JVM stableKey 争抢：第二个进程 exit=1，错误含
  `STABLE_KEY_BUSY_CROSS_PROCESS`；首个进程强杀后第三个进程 exit=0、复用侧车并通过 SHA-256。
- 公网 GitHub Electron Chromedriver ZIP：32,241,380 字节；独立 JVM 在耐久
  7,128,903 字节后强杀，第二进程 exit=0，最终 SHA-256
  `eaa589420f83de98efcb8845badb6e439a7539d3f368edf875ca9ea0674661be` 通过，
  metadata 明确 `parts=true discard=false`。
- 公网测试原始结果：`_bench/results/remote-restart-resume/remote-restart-20261104T100353770171Z/result.json`（忽略提交）。

## 真实网络结果

以下数字单位均为 MiB/s，均为 `run_engine_compare.py` 按 repeat/connection 分块交错执行；
完整 raw/summary 在 `_bench/results`（未提交）：

| 场景 | TurboDL | aria2 | curl | 说明 |
| --- | ---: | ---: | ---: | --- |
| c=16 production，对比 run 04 | 2.66 中位 | 1.48 中位 | 1.91 单成功样本 | Turbo/aria2 ≈ 1.80x |
| c=8 production，对比 run 04 | 2.07 中位 | 2.01 中位 | 2.44 单成功样本 | 持平，未见高并发税 |
| c=1 单独复测 | 1.56 | — | 1.58 | 新单分片路径与 curl 基本持平 |

修复后的 32,241,380 字节公网样本（代理，5 次重复）仍显示：

| 场景 | TurboDL | curl |
| --- | ---: | ---: |
| c=1 | 5.31 MiB/s | 9.16 MiB/s |

该差距包含 JVM 启动、探测和 CLI 进程开销，不能直接等同于 Android 内嵌 SDK
的数据阶段吞吐；后续必须把启动/探测/传输/收尾拆开记录。修复前的 c=8/c=16
矩阵中 TurboDL 中位数为 4.44/3.88 MiB/s；修复后尚未重新跑完整 c=8/c=16 对照。

尾端 A/B（同一优化 JAR，2 次）：

| 连接 | 开启 tail assist | 关闭 tail assist |
| ---: | ---: | ---: |
| 8 | 3.13 / 3.16 | 3.44 / 3.15 |
| 16 | 3.61 / 3.00 | 3.59 / 2.90 |

该 A/B 说明 256 KiB 尾端拆分没有引入可见吞吐退化；但网络波动大于差异，
不能把单个格子的高低当作严格加速证明。

失败记录：

- 对比 run 04 的 curl c16 r0 和 c8 r1 出现 `curl: (35) schannel ... TLS handshake`，
  这两个样本保持失败，没有删除或改写成成功。
- 旧对比 run 03 因用户中止留下部分样本，预算账本已按实际响应体 reconcile，未把半截样本当最终结论。

## 预算

- 唯一默认账本 `_bench/results/traffic-budget/` 剩余约 4.82 GiB，已对账约 3.18 GiB；
  默认路径已收敛，避免新建空账本绕过 8 GiB 硬门禁。
- 公网实验未使用未登记来源或秘密 URL；详细结果文件在忽略提交目录。

## 仍未完成

- 多 client / 插件后端的进程级资源预算；BT/ed2k 服务运行时；HLS/DASH 完整产物。
- CLI 的真实 Ctrl+C 信号、真实 socket 峰值和协议插件路由。
- 修复后 c=8/c=16 的完整公网复测与启动/探测/传输分段归因。
- 多来源同大小异内容、真实整机断电、24h soak 和手机真机证据。
- 1.8x 不是所有场景的硬承诺；目前只在有证据的 c16/该公网模型上接近 1.80x，
  其他场景必须按各自门槛继续测试。

## 2026-11-05 补充：HLS 安全、known-size 与尾段回归

### 新增行为

1. HLS playlist 增加 SHA-256 身份侧车；身份变化或旧版本无身份时清空旧分片缓存。
2. HLS 媒体分片文件名包含 `index|URI|Range|key URI|IV` 语义摘要，不再只凭序号复用。
3. AES-128 分片先解密到临时文件，完整后再发布 canonical part；崩溃不会留下“非空但未解完”的成品。
4. playlist/key 响应改用已有 `HlsIo.readBounded()`，8 MiB 上限真正生效。
5. HLS 分片下载从“每个 playlist 条目一个协程”改为固定 worker + 有界 Channel，避免超大 VOD 创建海量 Job。
6. CLI 新增 `--known-size`，直接映射到 `DownloadRequest.knownSize`。无旧侧车时跳过独立 probe RTT，首个数据请求仍学习重定向并校验实际长度。
7. `tailAssistMinBytes` 默认先由 256 KiB 降到 64 KiB；随后真实 32 MiB 下载中仍观察到最后 57 KiB 无法拆分、16 路塌到 1 路并卡约 12 秒，因此继续降到 8 KiB，单分片最大拆分数提升到 3。
8. `--timing` 增加 `cli_shutdown` 总耗时。新的真实日志可把 JVM/事件启动、probe、传输、canonical 完成与关闭分开。
9. `BackendRegistry` 隔离插件的 `supports()` 异常：记录 owner/operation 后继续后续插件或内置 HTTP fallback；`VirtualMachineError`/`ThreadDeath` 仍上抛。
10. 进度侧车格式升级为 v2，文件身份内嵌进同一原子快照；受支持的 v1 旧快照仍通过独立 identity 文件校验，但 identity 缺失/不可读时判为 `UNKNOWN` 并安全重置。这样消除了“进度已发布、identity 未发布”崩溃窗口导致的同长度混写。
11. 调度器新增统一 `failTask()`：429/503 预算耗尽、授权失败耗尽、镜像全失效和普通重试耗尽时，在记录终态原因后立即取消所有在飞 Call，避免另一条慢滴连接让任务永久停在 `DOWNLOADING`。
12. `cancelCalls()` 现在同时标记任务已终止；尚未进入 HTTP Call、仍在等待 redirect-learner gate 的 worker 会在 gate 返回后再次检查并直接退出。整文件回退会清除该标记，避免 Range fallback 被误伤。
13. 未提供 `stableKey` 时，分片目录从共享的 `task_<id>` 改为每个 `TurboClient` 独立的 `client_<uuid>/task_<id>`。内部 id 都从 1 开始，两个 client 共用默认 workDir 时不再竞争同一侧车；跨 client/进程续传仍明确要求 `stableKey`。

### 定向回归

- `turbo-plugin-hls`：5 个套件、50 项测试、0 失败、0 跳过（全 HLS 套件；含主 playlist 失败后切换镜像）。
- `turbodl-cli`：1 个套件、14 项测试、0 失败、0 跳过；55 个解析标记。
- `turbodl-core`：尾段、慢启动初始策略、全局连接预算 3 个套件、9 项测试、0 失败。
- `turbodl-core/SegmentStoreTest`：1 个套件、16 项测试、0 失败；包含旧版 v1 缺 identity + 同长度替换的安全重置，以及 v1 有效 identity 兼容回归。
- `turbodl-core/ThrottleTerminalCancellationTest`：1 项限流预算耗尽 + 慢滴连接回归，5 秒内进入 FAILED。
- `turbodl-core/ClientWorkDirIsolationTest`：两个无 stableKey client 的 task id 都为 1 时，计算目录必须不同。
- `turbo-plugin-runtime`：5 个套件、64 项测试、0 失败；含故障插件后仍路由到后续后端、故障插件不阻断内置 HTTP fallback，以及一参数 Java 构造器兼容。
- `turbo-plugin-bootstrap`：1 个套件、2 项测试、0 失败。
- Python runner/budget：21 项测试通过。

### 真实网络 A/B（known-size）

对象仍为 Electron Chromedriver ZIP，32,241,380 字节；同一代理；逐引擎 prewarm；
c8/c16 各 2 次，所有样本 SHA-256 正确：

| 引擎 | 连接 | 成功/总数 | 中位吞吐 MiB/s | 中位 wall time |
| --- | ---: | ---: | ---: | ---: |
| TurboDL | 8 | 2/2 | 6.05 | 5.09 s |
| TurboDL | 16 | 2/2 | 5.41 | 5.73 s |
| aria2 | 8 | 2/2 | 5.44 | 6.94 s |
| aria2 | 16 | 2/2 | 5.63 | 5.53 s |
| curl 单流 | 8 标签 | 2/2 | 9.73 | 3.27 s |
| curl 单流 | 16 标签 | 2/2 | 10.11 | 3.06 s |

TurboDL c8 相对 aria2 c8 为 1.11x，c16 为 0.96x；本轮不能据此宣称
“所有连接档全面领先 aria2”。curl 的标签连接数不控制实际流数，仍是单流，
但它在这条 GitHub/代理/CDN 路径上明显更快。

TurboDL 内部 timing（c16）：

- known-size metadata：70 ms，`probe_ms=5–9`；
- 32 MiB 数据阶段：约 4.0–5.0 s；
- `cli_shutdown`：从 completed 到进程可退出约 0.8–1.2 s；
- 最终文件 SHA-256 全部通过。

原始报告：

- `_bench/results/compare-production-20261105/compare-20261105T094924344002Z-47ea42ea/summary.json`
- `_bench/results/compare-known-size-20261105/compare-20261105T095956863508Z-90f35740/summary.json`

流量账本：累计已消费 `7,641,207,060` 字节，剩余 `948,727,532` 字节。手工
`--timing` 复测的 10 次成功对象传输已作为 `manual-timing-20261105` 补记。

## 2026-11-05 发布候选全量门禁

命令：

```powershell
.\gradlew.bat :turbodl-core:test :turbodl-cli:test :turbo-plugin-hls:test `
  :turbo-plugin-js:test :turbo-plugin-parser:test :turbo-plugin-bootstrap:test `
  :turbo-plugin-runtime:test :turbodl-cli:fatJar --rerun-tasks --no-daemon
```

实际 XML 汇总：

| 模块 | suites | tests | failed | skipped |
| --- | ---: | ---: | ---: | ---: |
| turbodl-core | 79 | 265 | 0 | 0 |
| turbodl-cli | 1 | 14 | 0 | 0 |
| turbo-plugin-hls | 5 | 50 | 0 | 0 |
| turbo-plugin-js | 11 | 120 | 0 | 0 |
| turbo-plugin-parser | 3 | 42 | 0 | 0 |
| turbo-plugin-bootstrap | 1 | 2 | 0 | 0 |
| turbo-plugin-runtime | 5 | 64 | 0 | 0 |
| **合计** | **105** | **557** | **0** | **0** |

- fat JAR SHA-256：`1A1B92CDB66C86C5E3B451C9F02235B6928AB645119EB6D329045ECA7FD268AD`。
- Python runner / traffic ledger / restart-resume：28 项通过。
- 官网：42 页构建成功；2223 条内部链接 0 错误；43 页审计 0 问题。
- `git diff --check`：通过。
- 诊断探活现在有 3s connect、5s read、8s 总调用上限，不可达测试不再挂数分钟。
- 8 KiB 尾段阈值真实复测：32,241,380 字节、c=16、known-size，传输在 5.56s 完成；
  99% 时仍有 2 路活动连接，没有再次出现 64 KiB 阈值下最后 57 KiB 卡约 12 秒。

本次全量门禁是代码候选验证，不等于已授权提交、推送或创建 Release。

### 未解决

- 该公网路径上单流 curl 仍显著领先；probe 已不是 known-size 模式主因，
  当前主因是 Range 分片/多连接启动和传输阶段本身。
- HLS 同一 URI 的 playlist 内容不变、但服务端原地替换分片字节时，仍缺分片
  ETag/Range 哈希证明。
- HLS/插件仍不共享 `ConnectionBudget`，插件资源网关尚未闭合。
- 发布前尚未跑最终全量；本记录明确不是 release gate PASS。

### 续传验证工具修复（2026-11-05）

- 强制中断续传工具此前只支持 v1 断点快照，已补上 v2 身份字段解析及对应测试。
- 本地 16 MiB、4 连接测试中，强制结束第一次下载后记录 2,236,416 字节已持久化；
  重启复用断点，最终 SHA-256 校验通过。首次 8 MiB 试验下载过快，未触发中断，
  不能计作续传成功。
- 结果文件：`_bench/results/restart-resume/restart-resume-20261105T133901194279Z/result.json`。
  此次只证明本地进程强制终止后的一个场景，不代表公网、断电或 Android 真机已验证。

### 公网进程中断续传（2026-11-05）

- GitHub Electron Chromedriver，32,241,380 字节，同一代理及已知 SHA-256：4 连接
  和 16 连接两次各自强制终止进程后重启，均报告复用断点且最终 SHA-256 正确。
  对应任务分别为 `remote-restart-20261105T134255109097Z` 和
  `remote-restart-20261105T134508195819Z`，中断时持久进度分别为 9,079,896 和
  5,108,293 字节；账本每次按两个完整文件保守计费，不代表实测流量。
- 断点快照损坏/备份、目标替换或截断及强弱校验器的定向测试共 28 项通过。
- 本地模拟链接失效 403、分片断连及续传完整性的另外 11 项定向测试通过。
- 弱校验器场景中，抽样无法取得且没有内容变化的其他证据时，目前默认保留旧进度；
  服务端若原地替换同大小内容，无法据此证明旧分片正确。预期 SHA-256 可在下载完成时
  检出混合内容，但没有预期摘要时这一风险仍存在。
- 进程强制终止和模拟侧车损坏不能证明真实突然断电时磁盘缓存的持久性。

### 无法验证续传时的宿主策略（2026-11-05）

- 新增 `submit(request, UnverifiableResumePolicy.FAIL)`：内置 HTTP 后端无法验证旧
  分片时失败并保留进度；旧 `submit(request)` 保持现有行为。不支持的后端明确报错。
- 续传策略、旧请求构造兼容和插件边界定向测试：18 项，0 失败。抽样匹配仍不等于
  完整校验，已知摘要时使用 `expectedSha256`。
- 新构建 fat JAR SHA-256：`41D1029F3574B9EBEFF40ED2883BFE3C6B397C20B160ACA23D71428F91205978`。
  用该 JAR 本地强杀并恢复 16 MiB 文件，通过最终 SHA-256；结果为
  `_bench/results/restart-resume/restart-resume-20261105T135647919873Z/result.json`。
- 之前的公网测试和 557 项全量测试对应旧 JAR，不是此更改后的最终发布门禁。
