# TurboDL 公开契约逐项落实路线图

> 范围：只基于 `TurboConfig.kt`、`Models.kt`、`TurboClient.kt` 的公开声明，`DownloadBackend.kt`、`TurboBackends.kt`，以及 CLI `Main.kt` 的参数解析和帮助文本定向清点。本文是后续执行清单，不代表行为测试已通过；未由这些声明直接确定的运行时细节标为“待验证”。不包含全仓审计。
>
> 约束：路线图工作不得覆盖已发布的 0.4.1 包；任何契约变更先讨论版本策略，发布产物另行决策。DOWNWARD、UPWARD、自适应并发与 slowStart 均为宿主通用策略，不可写成某类宿主专属逻辑。

## 1. 清点分母与追踪规则

首次清点基线（以本次源码为准）：`TurboConfig` 主构造字段 **46** 项；`DownloadRequest` 字段 **9** 项；CLI 可解析的选项标记 **55** 个（含短选项、别名及兼容标记，不含位置 URL）。55 是标记数，不是 55 种独立功能；帮助覆盖率在 P02 中逐项复核。字段及选项逐项见下表。

清点分母只增不减：每次执行前先从公开声明重新枚举，对照本基线并记录差异。新增字段、事件、状态或 CLI 标记必须新增追踪行；删除/弃用项仍留在台账并写明版本与兼容策略。不得通过合并字段、删行、改口径或只统计“已完成项”降低分母。若公开 API 有意删除，须保留历史行并记录删除版本和迁移证据。

每一行后续须填写可追踪链：**契约项 → 消费代码（文件/符号/行）→ 行为测试（测试名/用例）→ 证据（命令、结果、版本/提交）**。代码消费只能证明接线，不能代替行为测试；源码注释不能代替实测证据。未完成时明确写 `待实现/待验证`，不能宣称通过。

建议执行者复制以下列为工作表，保持一项一行：`ID | 公开字段/标记 | 语义与交互 | 消费代码定位 | 行为测试 | 证据 | 状态 | 首次引入/兼容备注`。基线 ID 为 `CFG-01..46`、`REQ-01..09`、`CLI-01..55`，另为事件、状态、客户端方法、派生配置和后端 SPI 单独编号。

P02a 必须生成机器可读映射，并把每项归入且只归入以下一类：

- `ACTIVE`：有真实读取/传递点，且有行为测试和运行证据。
- `DEPRECATED_NOOP`：为兼容保留但不再生效；必须有白名单条目、引入/弃用版本、原因、迁移说明和
  “两种取值行为一致”的回归。
- `MISSING`：可解析/可声明但找不到生产消费点；保持失败状态，不能进入发布 PASS。

白名单为空时，任何只赋值不读取的公开项都会使 P02a 失败。当前重点待判定项是
`dynamicSegmentation`、`overlapMerge` 及各隐藏 CLI 别名；结论必须由实际消费链
和运行证据决定，不能沿用注释。

## 2. TurboConfig 字段清单（46）

“验收建议”是未来应补的行为验证，不表示现有覆盖情况。范围/默认值按声明与构造器校验记录；未明示限制的字段不要自行推定合法范围。

### 2.1 并发、调度与分片

| ID | 字段（默认） | 语义 / 互斥或交互 | 行为验收建议 |
|---|---|---|---|
| CFG-01 | `maxConnectionsPerTask=16` | 单任务连接上限；1..256。与任务级覆盖、全局预算、host 上限共同限流。 | 验证 1、256 边界；并发实际值不越配置及其他上限。 |
| CFG-02 | `maxConcurrentTasks=3` | 同时活动任务槽；1..64。与全局连接预算、IO 缓冲预算分母交互。 | 提交超额任务，核对排队、FIFO/公平语义和调大后唤醒；顺序语义待验证。 |
| CFG-03 | `maxTotalConnections=0` | 跨任务活动连接上限；0 表示不限，非零 1..8192。 | 多任务同时下载，验证总连接不超限、暂停/失败后预算归还。 |
| CFG-04 | `globalSpeedLimitBytesPerSec=0` | 进程级总限速；0 不限，不能为负。 | 多任务共享限速；核对更新配置后即时效果及精度，具体窗口待验证。 |
| CFG-05 | `maxRetries=5` | 分片失败最大重试次数；0..50。 | 断网/恢复及永久错误分别验证重试次数、任务终态与分片保留。 |
| CFG-06 | `dynamicSegmentation=true` | 声明称历史名称；注释指向 `tailAssist`，称本字段为历史名称。 | 查消费代码确认是否仍有读取；若不消费，保留兼容并验证开关不改变行为。 |
| CFG-07 | `tailAssist=true` | 收尾阶段空闲连接接管在飞分片；与最小剩余量、最大拆分数、分片直写交互。 | 用长尾连接构造收尾，验证只在队列无新块时接管且覆盖无缺口/重叠。 |
| CFG-08 | `tailAssistMinBytes=8 KiB` | 小于阈值不拆；负值构造失败；与块大小、剩余区间和 `tailAssist` 交互。真实回归曾出现最后 57 KiB 在 64 KiB 阈值下无法拆分、单连接卡约 12 秒；8 KiB 允许继续拆分该尾段。 | 覆盖阈值前、等于阈值、超过阈值；真实网络尾速和请求成本需继续回归。 |
| CFG-09 | `maxSplitsPerSegment=2` | 单分片最多拆分次数；与收尾托管交互。 | 反复制造空闲 worker，证明拆分次数有上限且不丢区间；合法范围待验证。 |
| CFG-10 | `overlapMerge=false` | `@Deprecated`；声明称被按偏移直写取代且引擎不再读取。**兼容保留，不应作为新功能承诺。** | 旧源码/JVM 构造调用仍可链接；两种取值行为相同的证据应覆盖消费路径。 |
| CFG-11 | `bufferedSegmentWrite=false` | 分片缓冲写开关；不宣称性能收益。 | 两种取值均核对字节正确、续传边界与资源释放；性能结论需单独基准。 |
| CFG-12 | `minSegmentSize=64 KiB` | 分片尺寸下限，构造校验至少 4096；与 `blockSize` 上限交互，允许上限小于下限。 | 覆盖最小值、`blockSize < minSegmentSize`、文件小于阈值及切块覆盖。 |
| CFG-13 | `blockSize=16 MiB` | 固定块尺寸上限；与连接数、每连接块数、最小尺寸及任务总块数交互。 | 验证块大小不越有效上限，边界组合仍完整覆盖；有效值算法须从消费代码确定。 |
| CFG-14 | `segmentsPerConnection=4` | 每连接目标分块数；1..64。与分片上限、任务连接数共同决定任务切块。 | 对不同连接数验证块数目标、工作窃取与请求量；精确公式待验证。 |
| CFG-15 | `maxSegmentsPerTask=0` | 单任务切块数上限；0 不限，不能为负。 | 设小上限并验证调度不超限、尾部仍可完成；与最小分片冲突的裁决待验证。 |
| CFG-16 | `backpressureConsecutiveFailures=4` | 连续连接失败阈值；0 表示关闭该保护。与自适应及 429/503 背压策略交互。 | 逐一验证阈值前后、设 0、HTTP 429/503；下降幅度和恢复行为待验证。 |

### 2.2 网络、协议与连接池

| ID | 字段（默认） | 语义 / 互斥或交互 | 行为验收建议 |
|---|---|---|---|
| CFG-17 | `proxy=Direct` | 代理模式；与 DNS、重定向和探测请求路径交互。 | 直连、HTTP、SOCKS、PAC 分别验证解析/请求行为；PAC 支持细节待验证。 |
| CFG-18 | `dns=System` | DNS 模式；与代理解析、DoH 端点及重定向交互。 | System、StaticHosts、DoH、Auto 分别验证地址解析、失败回退和缓存范围。 |
| CFG-19 | `trustAllCerts=false` | 是否忽略 TLS 证书校验。 | 自签证书分别验证拒绝/接受；探测、镜像、重定向是否一致待验证。 |
| CFG-20 | `connectTimeoutMs=15000` | 建连超时（毫秒）。与预热/探测超时可能叠加。 | 慢握手边界验证错误类型和总等待时间；超时组合上界待验证。 |
| CFG-21 | `readTimeoutMs=30000` | 单次读取等待上限；注释与 stall 检测区分。 | 无字节、间歇字节、低速连续字节分别验证 read/stall 判定。 |
| CFG-22 | `userAgent=Chrome 125 UA` | 默认 User-Agent；请求头可覆盖的优先级需以消费代码确认。 | 空值、自定义头、镜像请求分别验证实际发送值和大小写处理。 |
| CFG-23 | `maxIdleConnections=256` | 连接池最大空闲连接数；与并发上限及连接复用交互。 | 多任务结束后观察复用/淘汰；池作用域与生效时机待验证。 |
| CFG-24 | `forceHttp1=true` | 旧式协议开关；当 `httpVersionPolicy=UNSET` 时作为兼容回退输入。 | 检验 `UNSET` 下 true/false 映射及显式 policy 优先级；不可与下一项含糊。 |
| CFG-25 | `httpVersionPolicy=UNSET` | AUTO / FORCE_HTTP1 / FORCE_HTTP2 / UNSET；UNSET 为兼容语义入口。AUTO 区分分片与单流。 | 矩阵验证 AUTO、强制 H1/H2、UNSET×`forceHttp1`，并记录实际协商协议。 |
| CFG-26 | `maxConnectionsPerHost=0` | 每 host 分片并发上限；0 表示使用任务连接上限。与重定向 host 改变交互。 | 多 host、同 host、重定向及 200 忽略 Range 情况下验证限额口径。 |
| CFG-27 | `keepAliveSeconds=300` | 连接保活秒数。 | 验证连接复用闲置期前后行为；零值/负值策略及底层映射待验证。 |

### 2.3 工作目录、启动策略与自适应

| ID | 字段（默认） | 语义 / 互斥或交互 | 行为验收建议 |
|---|---|---|---|
| CFG-28 | `workDir=null` | 分片临时目录根；null 使用系统临时目录。与稳定任务键、并行任务隔离交互。 | 指定目录跨 client/进程恢复；同键冲突、清理及权限失败行为待验证。 |
| CFG-29 | `warmUpConnections=true` | 是否进行连接预热/DNS 预解析；与计数、并行度、超时交互。 | 开关对正确性无影响；验证预热失败不阻断正式下载。 |
| CFG-30 | `warmUpConnectionCount=0` | <=0 自动取 min(任务连接上限, 8)；与预热开关和并行上限交互。 | 0、显式值、超过任务上限分别验证实际预热数量。 |
| CFG-31 | `slowStart=true` | 宿主通用慢启动策略；负责健康时升并发，与背压的遇错下调职责正交。 | 分别开关、短/长任务及自适应开关组合；起步阶梯与上限待验证。 |
| CFG-32 | `slowStartInitial=0` | 注释称 0 从上限起跑，但 `SegmentScheduler.slowStartInit` 在 slowStart=true 且无显式初值时，UPWARD 取较低起点，其余分支为 max(4,workers/4)。声明与消费点不一致，列入 C05 首批复现。 | 0、1、超过上限、开关组合和续传逐项记录实际起点；先确定宿主契约再修实现/说明，不能照抄注释。CLI 目前裁剪到 >=1。 |
| CFG-33 | `adaptiveConcurrency=true` | 宿主通用吞吐自适应策略；与模式、全局限速、slowStart、背压交互。 | 有/无聚合限速、稳定/波动链路分别验证吞吐与并发变化。 |
| CFG-34 | `adaptiveConcurrencyMode=DOWNWARD` | 宿主通用搜索方向：DOWNWARD 从设定上限起跑向下收敛；UPWARD 从较低档向上探测。 | 两种方向用同一可控吞吐曲线验证起点、扩/缩档和停止条件。不得绑定宿主类型。 |
| CFG-35 | `warmUpMaxParallel=4` | 预热并行度上限，至少 1；与总连接预算交互待验证。 | 阻塞握手服务器记录最大并行预热数及取消/超时释放。 |
| CFG-36 | `skipProbeWhenSizeKnown=true` | 已知大小时允许跳过探测；可能失去最终重定向 URL；与强制探测条件交互。 | knownSize 正/零/负、Range 支持与否、重定向及探测事件分别验证。 |
| CFG-37 | `trustWeakValidator=false` | 弱身份续传的信任开关；还与内容指纹及其可取得性共同作用，不能简单声称 false 必然删除断点。 | ETag/Last-Modified 缺失、样本取得/不可得、同长异内容分别验证复用/保留/丢弃决定与宿主风险提示。 |

### 2.4 缓冲、进度、探测与恢复

| ID | 字段（默认） | 语义 / 互斥或交互 | 行为验收建议 |
|---|---|---|---|
| CFG-38 | `ioBufferSize=1 MiB` | 单连接缓冲上限；至少 8 KiB。与总预算及并发分母共同决定有效大小。 | 覆盖最小值、低并发/高并发及直接缓冲开关，测得实际分配上限。 |
| CFG-39 | `ioBufferTotalBudgetBytes=32 MiB` | 缓冲总预算；构造时不得小于单连接上限。 | 多任务×高连接数下验证总量、保底行为和动态更新语义。 |
| CFG-40 | `useDirectIoBuffers=平台默认` | 是否用直接内存读缓冲；平台默认函数确定默认值。 | JVM/Android 平台分别检查默认、堆/直接分配和释放；平台默认待证据。 |
| CFG-41 | `progressIntervalMs=200` | 进度上报最小间隔；0 不节流，关键状态事件声明不受节流。 | 高吞吐验证进度节流、终态不丢、0 行为；并发任务是否共享节流待验证。 |
| CFG-42 | `probeTimeoutMs=6000` | 单次探测超时；至少 1000ms，与探测重试、建连超时交互。 | 慢服务器验证单次上限与总时长；超时异常/回退状态待验证。 |
| CFG-43 | `probeRetries=1` | 探测失败/超时重试次数；不能为负。 | 分别验证重试次数、退避和探测失败后整文件下载回退。 |
| CFG-44 | `stallTimeoutMs=45000` | 单连接收到数据后无新字节的停滞阈值；0 关闭。 | 验证零进度、涓流、正常慢速及重试计数；与 readTimeout 谁先触发。 |
| CFG-45 | `maxStallRecoveries=3` | 连续卡死重置上限；不能为负。 | 阈值前恢复成功、达到上限失败，核对断点保留与错误原因。 |
| CFG-46 | `warmUpTimeoutMs=8000` | 单次预热请求超时；至少 500ms。 | 预热服务不响应时确认正式下载继续，核对总等待时间上限。 |

派生配置也必须验收：`effectiveIoBufferSize(connections)`、`effectiveHttpVersionPolicy`、
`MIN_IO_BUFFER_BYTES` 和 `budgetedIoBufferSize(totalBudgetBytes, perConnectionCapBytes, connections, concurrentTasks)`。
特别检查 64 任务 × 256 连接时 8 KiB 保底是否突破 32 MiB 总预算；不能拿 app 的较小任务上限证明 SDK 全配置安全。

模式类型逐项登记：`ProxyMode.Direct/System/Manual(type,host,port,username,password)/Pac(pacUrl)`、
`ProxyType.HTTP/SOCKS`、`DnsMode.System/StaticHosts(hosts)/DoH(dohUrl)/Auto(endpoints)`、
`DnsMode.DEFAULT_DOH_ENDPOINTS`、`HttpVersionPolicy.UNSET/AUTO/FORCE_HTTP1/FORCE_HTTP2`。
每个字段/枚举值覆盖非法输入、认证、取消、热更新和失败回退，默认端点不能被当成永久可达保证。

## 3. DownloadRequest 字段清单（10）

| ID | 字段（默认） | 语义 / 互斥或交互 | 行为验收建议 |
|---|---|---|---|
| REQ-01 | `url`（必填） | 主下载 URL，声明限定 HTTP/HTTPS；与重定向、镜像和请求头交互。 | 非法 scheme、重定向及最终 URL 记录；错误分类待验证。 |
| REQ-02 | `destination`（必填） | 目标文件；注释称绝对路径。与父目录创建、覆盖策略交互。 | 绝对/相对路径、已存在目标、无权限和父目录缺失逐项验证覆盖策略。 |
| REQ-03 | `headers=emptyMap()` | 附加请求头，可承载 Cookie/Referer/UA；与默认 UA 和跨源镜像敏感头过滤交互。 | 主源与跨 scheme/host/port 镜像验证头继承、覆盖及凭据隔离。 |
| REQ-04 | `knownSize=-1` | <=0 表示未知；正值作为已知大小。与跳过探测、Range 回退交互。 | 正值准确/错误、0、负值均验证总量及下载完整性。 |
| REQ-05 | `expectedSha256=null` | 可选 SHA-256；构造器校验 64 位十六进制；声明称完成前校验。 | 大小写、正确/错误摘要验证终态、目标文件和续传侧车处理。 |
| REQ-06 | `connectionsOverride=null` | 本任务覆盖全局连接数；范围仍受 1..256 限制。与全局预算、host 限额交互。 | null、1、256、越界值和多任务总预算验证；实际校验时机待验证。 |
| REQ-07 | `stableKey=null` | 稳定分片目录键；为空退回内部任务 ID。须避免不同业务任务碰撞。 | 跨 submit/进程恢复，同键并发冲突、空字符串、路径安全及隔离待验证。 |
| REQ-08 | `tags=emptyMap()` | 业务元数据透传，不应影响引擎行为；声明由事件返回。 | 创建/进度/完成/失败事件中透传可见性；各事件是否都携带请求待验证。 |
| REQ-09 | `mirrors=emptyList()` | 同文件替代源；按大小剔除不一致来源，按速度调度并故障切换；跨源不自动带敏感请求头。 | 同大小异内容、大小不符、源失败/恢复、响应头不一致与敏感头隔离验证；一致性判据需细化。 |
| REQ-10 | `workDir=null` | 单任务断点/临时目录根；null 使用 `TurboConfig.workDir`。引擎仍追加 stableKey 子目录。 | 自定义目录、相对/绝对路径、同 client 多任务、路径冲突和旧 JVM 构造器兼容。 |

`mirrors` 位于构造器第 9 位、`workDir` 位于第 10 位，末尾默认参数由 `@JvmOverloads` 保留旧 JVM 构造器。必须单独做字节码兼容验收，不能仅靠 Kotlin 源码重新编译成功推断旧二进制可运行。

## 4. 客户端命令、事件与状态

### 4.1 TurboClient 公开入口

| ID | API | 契约要点 | 验收建议 |
|---|---|---|---|
| API-01 | `TurboClient(config=TurboConfig())` | SDK 门面；线程/协程安全为声明。 | 多线程并发提交/控制及关闭竞态验证。 |
| API-02 | `config`（只读属性） | 读取当前配置；由 `updateConfig` 改写。 | 更新前后读取一致性及可见性验证。 |
| API-03 | `updateConfig(newConfig)` | 运行时热更新；注释称限速/并发即时生效、运行中连接数不回缩。 | 每类可热更新字段逐项验证；哪些字段只对新任务生效待追消费点。 |
| API-04 | `backendResolver` | 可选 resolver；空时内置后端；resolver 先于 built-in fallback。 | null、返回后端、返回 null、抛错时路由/失败语义待验证。 |
| API-05 | `events: SharedFlow<TurboEvent>` | 观测事件流；不能据此假定可重放。内部 staging queue 有界，慢消费者会优先丢弃进度/元数据，状态和终态尽量保留。 | 订阅时机、慢消费者、缓冲满、顺序和关闭行为验证。 |
| API-06 | `progress: StateFlow<Map<Long,TaskProgress>>` | 当前进度快照。 | 新订阅者初值、任务清理、终态保留时间验证。 |
| API-07 | `submit(request): Long` | 入队并异步开始，返回任务 ID；关闭后调用会失败。 | ID 唯一性、事件顺序、提交/关闭竞态验证。 |
| API-08 | `await(id): Result<File>` | 挂起等待；未知 ID 返回失败结果。 | 成功、失败、暂停/取消和未知 ID 分别验证完成语义。 |
| API-09 | `pause(id)` | 暂停并保留断点；无返回值。 | 排队/探测/下载/终态各状态暂停及竞态验证。 |
| API-10 | `resume(id): Boolean` | 恢复任务；返回布尔含义需对照实现明确。 | 逐种状态验证返回值、重复调用及配置变化。 |
| API-10b | `resumeAsync(id): Boolean` | Android/UI 协程使用的非阻塞恢复入口；旧 `resume` 保留兼容。 | 调用线程不阻塞、暂停态恢复、失败态和关闭态返回值、与 legacy resume 的语义等价。 |
| API-11 | `cancel(id, deleteOutput=false)` | 取消，可选择删除输出。 | false/true 对目标文件、侧车、await 和事件影响逐项验证。 |
| API-12 | `shutdown()` | 关闭客户端。 | 有活动任务、排队任务、重复关闭及随后 await 的行为待验证。 |

### 4.2 事件与进度契约

| ID | 类型/字段 | 兼容与验收 |
|---|---|---|
| EVT-01 | `Created(taskId, request)` | 公开事件；字段新增须保持旧消费者可编译/运行。验证提交时点、request/tags 原样性。 |
| EVT-02 | `StateChanged(taskId, state)` | 状态转换通知。验证转换序列、重复状态及事件相对终态事件顺序。 |
| EVT-03 | `Progress(taskId, progress)` | 快照通知；受 `progressIntervalMs` 影响的细节待验证。验证字节单调、节流和终态。 |
| EVT-04 | `Completed(taskId, file, totalBytes)` | 成功事件。验证与 `await`、进度 `COMPLETED` 和文件可读之间的顺序。 |
| EVT-05 | `Failed(taskId, reason)` | 失败事件。验证 reason 稳定性、异常/错误状态对应和是否可能重复。 |
| EVT-06 | `Metadata(...)` | 尽力而为的静默元数据事件；不少字段可空，`probeMs` 旧值 -1、跳过探测时声明为 0；`resumeNote` 有格式说明。验证缺字段、跳探测、重定向、续传诊断。 |
| PRG-01 | `TaskProgress` 8 个构造字段 + 派生 percent | taskId/state/downloaded/total/speed/connections/eta/error；未知 total/eta 为 -1，percent 无总量为 -1，否则 0..100。逐字段验证单位、未知值和边界。 |
| STATE-01 | `QUEUED` | 等待任务槽。 |
| STATE-02 | `PROBING` | 探测阶段。 |
| STATE-03 | `DOWNLOADING` | 下载阶段。 |
| STATE-04 | `MERGING` | 合并阶段；按偏移直写后的可达性需验证。 |
| STATE-05 | `COMPLETED` | 成功终态。 |
| STATE-06 | `PAUSED` | 暂停并保留断点。 |
| STATE-07 | `FAILED` | 失败终态。 |
| STATE-08 | `CANCELED` | 取消终态。 |

`TurboEvent.Metadata` 也是 `TurboEvent` 子类型，故事件子类总数 6。状态值是公开枚举；新增状态对 Kotlin `when` 穷举调用方属于源码兼容变化，须纳入版本策略和迁移说明。

## 5. 后端公开契约

| ID | 声明 | 逐项落实要求 |
|---|---|---|
| SPI-01 | `DownloadBackend.name` | 名称稳定性、诊断用途待验证。 |
| SPI-02 | `supports(request)` | 选择语义与异常策略待验证；测试匹配/不匹配/异常。 |
| SPI-03 | `suspend DownloadBackend.download(context): BackendResult` | 协作取消、异常失败、结果文件所有权及返回后不得继续写；合并和校验由 core 管理。 |
| SPI-04 | `BackendResult.orderedParts` | 文件排序与结果完整性待验证。 |
| SPI-05 | `BackendResult.totalBytes` | 字节数语义、未知值约定待验证。 |
| SPI-06 | `BackendResult.partOffsets=null` | 可选偏移；省略时的解释待验证。 |
| SPI-07 | `BackendContext.taskId/request/workDir/config` | 后端可见任务上下文；生命周期与可变配置快照语义待验证。 |
| SPI-08 | `BackendContext.isActive()` | 取消/暂停时状态含义待验证。 |
| SPI-09 | `reportTotalSize(total)` | 对 progress/events 的影响待验证。 |
| SPI-10 | `reportMetadata(...)` / `reportResolvedMetadata(...)` | 旧六参数接口保持二进制兼容并委托新接口；新接口显式携带真实 `resolvedUrl` 与 `supportsRange`，且允许多次上报，最后一次字段影响后续 Metadata 事件。 |
| SPI-11 | `BackendResolver.resolve(request): DownloadBackend?` | null 回退语义有声明；多个 resolver 或线程安全未纳入本次范围。 |
| SPI-12 | `TurboBackends.builtinHttp(clientConfig)` | built-in 后端工厂及配置传播待验证。 |
| SPI-13 | `suspend BackendContext.throttle(bytes)` | 插件每次写入的全局预算扣除、等待中取消、动态限速和非法字节数验收。 |
| SPI-14 | `TurboHttpClients.create(config)` | 返回客户端协议/配置映射待验证。 |
| SPI-15 | `probeDohLatency(...)` | 参数、单位、失败表示及网络副作用待验证。 |
| SPI-16 | `suspend BackendContext.reportProgress(absoluteBytes,activeConnections)` | 按累计字节解释；未知总长合法；跨任务隔离；终态后的迟到回调已忽略；累计/增量误用仍无显式防护。 |

`TransportClientHolder.client/onConfigSeen` 属于 internal 类，不是 `TurboBackends` 公开入口；
其传输热更新行为归 C07 验收，不能误列为对外承诺。SPI-07 的 taskId/request/workDir/config
在正式台账各自拆行；`reportMetadata` 六个参数逐个追踪，新增的 `reportResolvedMetadata`
另按 `resolvedUrl`、`supportsRange` 和回退语义验收。

## 6. CLI 选项清单（解析器 55 标记）

表中“帮助”指当前 `printHelp` 是否列出；“保留”表示现有脚本兼容期间不得悄然移除。帮助未列出但解析器接受的标记，先按兼容接口处理，是否长期公开需单独决策。CLI 与 SDK 默认值不同处以 CLI 现状为准，不互相推导。

| ID | 参数标记 | 语义/验收 | 帮助 / 兼容 |
|---|---|---|---|
| CLI-01 | `-h` | 立即显示帮助并返回；验证与其他参数组合时解析顺序。 | 有帮助；保留 |
| CLI-02 | `--help` | 同上。 | 有帮助；保留 |
| CLI-03 | `-V` | 打印版本并返回。 | 有帮助；保留 |
| CLI-04 | `--version` | 同上。 | 有帮助；保留 |
| CLI-05 | `-o` | 输出文件；单任务优先级与 batch 路径交互待验证。 | 有帮助；保留 |
| CLI-06 | `--output` | `-o` 长别名。 | 有帮助；保留 |
| CLI-07 | `-d` | 输出目录。 | 有帮助；保留 |
| CLI-08 | `--dir` | `-d` 长别名。 | 有帮助；保留 |
| CLI-09 | `-c` | 连接数，CLI 将输入限制到 1..256。 | 有帮助；保留 |
| CLI-10 | `--connections` | 同上。 | 有帮助；保留 |
| CLI-11 | `--threads` | 连接数旧别名；并发语义应与 `--connections` 一致。 | 未列帮助；兼容保留，评估弃用前须查使用情况 |
| CLI-12 | `--max-total-connections` | 全局连接预算 0..8192。 | 有帮助；保留 |
| CLI-13 | `--sha256` | 64 位十六进制并转小写；成功后 JSON 还输出摘要。 | 有帮助；保留 |
| CLI-14 | `-l` | 大小解析的限速别名。 | 有帮助；保留 |
| CLI-15 | `--limit` | 同上。 | 有帮助；保留 |
| CLI-16 | `--speed-limit` | 同上；CLI 解析器可接受但帮助未列。 | 未列帮助；兼容保留 |
| CLI-17 | `-r` | 重试数 0..50。 | 有帮助；保留 |
| CLI-18 | `--retry` | 同上。 | 有帮助；保留 |
| CLI-19 | `-b` | 批量任务文件。 | 有帮助；保留 |
| CLI-20 | `--batch` | 同上；每行 URL 或 URL+输出路径，# 注释。 | 有帮助；保留 |
| CLI-54 | `--jobs` | 批量同时运行的任务数 1..64；整批共用 client、连接预算和限速。 | 有帮助；保留 |
| CLI-55 | `--known-size` | 已知精确文件大小时透传到 `DownloadRequest.knownSize`，跳过独立 probe RTT；首个数据请求仍学习重定向并校验实际长度。 | 有帮助；保留 |
| CLI-21 | `-H` | 增加请求头，可重复；同名键覆盖策略待验证。 | 有帮助；保留 |
| CLI-22 | `--header` | 同上。 | 有帮助；保留 |
| CLI-23 | `-A` | 自定义 User-Agent。 | 有帮助；保留 |
| CLI-24 | `--user-agent` | 同上。 | 有帮助；保留 |
| CLI-25 | `-M` | 增加镜像 URL，可重复。 | 有帮助；保留 |
| CLI-26 | `--mirror` | 同上。 | 有帮助；保留 |
| CLI-27 | `-p` | 设置代理；验证 HTTP/SOCKS 与非法格式。 | 有帮助；保留 |
| CLI-28 | `--proxy` | 同上。 | 有帮助；保留 |
| CLI-29 | `--doh` | 设置 DoH URL；空值变 null。 | 有帮助；保留 |
| CLI-30 | `--http-policy` | auto/http1/http2；解析别名 h1/h2/force-http1/force-http2。 | 有帮助；保留 |
| CLI-31 | `--force-http1` | 强制 H1 的快捷标记；帮助未列。 | 未列帮助；兼容保留 |
| CLI-32 | `--no-warmup` | 关闭预热。 | 有帮助；保留 |
| CLI-33 | `--slow-start` | 显式打开 slowStart；默认已开。 | 未列帮助；兼容保留 |
| CLI-34 | `--no-slow-start` | 关闭 slowStart。 | 有帮助；保留 |
| CLI-35 | `--adaptive` | 显式打开自适应；默认已开。 | 未列帮助；兼容保留 |
| CLI-36 | `--no-adaptive` | 关闭自适应。 | 有帮助；保留 |
| CLI-37 | `--adaptive-mode` | DOWNWARD/UPWARD 及 down/up 别名；宿主通用策略。 | 有帮助；保留 |
| CLI-38 | `--spc` | 每连接分块数 1..64。 | 有帮助；保留 |
| CLI-39 | `--block-size` | 按 parseSize 解析块上限。 | 有帮助；保留 |
| CLI-40 | `--min-segment` | 按 parseSize 解析分片下限。 | 有帮助；保留 |
| CLI-41 | `--slow-start-initial` | CLI 要求 >=1；与 SDK 0=自动有差异。 | 有帮助；保留；差异需注明 |
| CLI-42 | `--no-tail-assist` | 关闭收尾托管。 | 有帮助；保留 |
| CLI-43 | `--no-overlap` | 关闭 overlapMerge；对应配置已弃用且声明称不再读取。 | 有帮助；兼容保留；可评估隐藏/弃用但不可误称有效旋钮 |
| CLI-44 | `--stall-timeout` | 毫秒，>=0；0 关闭。 | 有帮助；保留 |
| CLI-45 | `--insecure` | 忽略证书校验。 | 有帮助；保留 |
| CLI-46 | `--connect-timeout` | 毫秒，CLI 最低 1000。 | 有帮助；保留 |
| CLI-47 | `--read-timeout` | 毫秒，CLI 最低 1000；CLI 默认 60000。 | 未单独列帮助；兼容保留 |
| CLI-48 | `--timeout` | `--read-timeout` 旧别名。 | 有帮助；兼容保留 |
| CLI-49 | `--json` | 输出 NDJSON，事件类型见下节。 | 有帮助；保留 |
| CLI-50 | `--timing` | 同时打开 JSON 与阶段诊断事件。 | 未列帮助；兼容保留 |
| CLI-51 | `-q` | 静默显示。 | 有帮助；保留 |
| CLI-52 | `--quiet` | 同上。 | 有帮助；保留 |
| CLI-53 | `--progress-interval` | 毫秒，解析下限 100；CLI 默认 400。 | 有帮助；保留 |

解析器标记冻结为 55；帮助覆盖数须按实际文本核对，不能把上表“有帮助”列当作已完成自动校验。

### 6.1 位置参数、内联值、JSON 与退出码

- 支持 `--flag=value` 形式；需为每个取值参数验证空值、缺值和包含等号/空格的输入。
- 首个非选项参数可作为 URL；额外位置参数报用法错误。`--batch` 与位置 URL 混用的具体报错路径待验证。
- `--batch` 文件忽略空行及 `#` 开头行；其余按空白拆成 URL 与可选输出路径。路径含空格的格式限制须在文档说明并验收。
- 输出路径优先级：行内路径、单任务 `--output`、`--dir`/当前目录加 URL 文件名。覆盖既有文件策略待验证。
- `--json` 的 NDJSON 类型：`start`、`progress`、`completed`、`failed`、失败汇总 `summary`、中断 `interrupted`；`--timing` 另输出 `timing` 的 metadata/state 阶段。核对每行都是合法 JSON、字段稳定、stdout 不混入人类进度。
- 退出码：0 成功、1 下载失败、2 用法错误；逐路径验证。Ctrl+C/部分批量失败的最终码与输出组合待验证。
- 帮助声称 `--timeout` 默认 60000ms，而 SDK `readTimeoutMs` 默认 30000ms；CLI 当前 Options 默认 60000ms。保留该差异并建立显式回归，不要擅自统一。
- 解析器存在 `--force-http1`、`--slow-start`、`--adaptive`、`--speed-limit`、`--threads`、`--timing` 等帮助未列参数。逐项决定“公开兼容保留 / 标注弃用 / 不再文档化”，不得未经版本评估删除。

## 7. 必要交互矩阵

矩阵中的每个格子至少有一个正向与一个边界/冲突用例；组合爆炸时可用 pairwise 生成，但标注未覆盖的高风险三方组合。

| 维度 A | 维度 B | 必须回答的问题 |
|---|---|---|
| `httpVersionPolicy` | `forceHttp1` | UNSET 时旧开关映射；显式 AUTO/H1/H2 是否覆盖旧字段；分片与单流实际协议。 |
| `adaptiveConcurrency` | `adaptiveConcurrencyMode` | 关闭时模式是否无效；DOWNWARD/UPWARD 起步、扩缩与停止规则。 |
| 自适应 | `slowStart` / `slowStartInitial` | 两套通用策略谁决定初始并发；显式初值是否只在 slowStart 开启时有效。 |
| 自适应 | 全局限速 / 背压阈值 | 限速是否禁用吞吐判断；429/503 与连续失败如何下调及恢复。 |
| 单任务连接 | 全局连接预算 | 多任务同时申请、部分预算分配、暂停/取消归还及公平性。 |
| 单任务连接 | host 上限 / 连接覆盖 | 生效上限是否为三者最小值；跨 host 重定向后重新计数。 |
| `tailAssist` | 最小尾量 / 最大拆分 / 动态分段 | 关闭时其他参数是否无效；任务尾部完整性和区间竞争。 |
| `overlapMerge` | `tailAssist` / 直写 | 已弃用开关是否完全不影响行为；不得把旧重叠写风险误作当前契约。 |
| 已知大小 | 跳过探测 / Range 支持 | 不探测时元数据与最终 URL；服务器忽略 Range 的回退正确性。 |
| 弱校验器信任 | stableKey / 续传 | 同长度换版、不同任务同键、跨进程续传与数据安全。 |
| 镜像 | 请求头 / 重定向 / 大小 | 凭据隔离、镜像筛除、来源切换和整文件一致性。 |
| 缓冲预算 | 任务并发 / 连接并发 / direct I/O | 总内存上限、最小保底及 Android/JVM 行为。 |
| 预热开关 | count / maxParallel / timeout | 数量裁剪、并发峰值、失败不阻塞和时间上界。 |
| read timeout | stall timeout / 最大恢复次数 | 零字节、涓流、正常慢链路的触发顺序及终态。 |
| `progressIntervalMs` | 状态事件 / SharedFlow 缓冲 | 节流只影响普通进度，关键事件顺序、慢订阅者行为。 |
| CLI `--json` | `--quiet` / `--timing` / batch | stdout 纯 NDJSON、错误流、事件交错和任务标识。 |
| CLI 输出路径 | batch 行路径 / `--output` / `--dir` | 优先级、相对路径基准及覆盖行为。 |

## 8. Java/Kotlin 旧字节码兼容验收

在每次涉及公开模型/构造器/API 的版本变更中，保存旧版调用方产物，并对新版库做二进制链接测试；仅重新编译测试源码不够。

1. 保留 0.3.1.0、0.4.0.0、0.4.1.0 三个历史消费者夹具；按各版本真实存在的类型生成 Java 与 Kotlin 样本，覆盖默认参数、位置参数、命名参数、copy/componentN、getter 和 Java 构造器重载。
2. 新库替换旧库运行已编译样本，检查 `NoSuchMethodError`、`NoSuchFieldError`、`AbstractMethodError`、`IncompatibleClassChangeError` 和 Kotlin `NoSuchMethodError`。
3. `DownloadRequest` 的 `@JvmOverloads` 与末尾新增 `mirrors` 必须验证旧构造器 descriptor 仍存在；后续字段新增须逐项检查重载集合与歧义。
4. `TurboConfig` 新增构造字段可能改变 Kotlin data class 的 JVM 构造签名；制定显式兼容策略（兼容工厂/重载/版本化 API），不要假定默认值自动保住 Java 二进制兼容。
5. 验证枚举新增值对 Java `switch` 与 Kotlin 穷举 `when` 的影响；事件 sealed 子类新增也需记录源码兼容风险。
6. 对公开方法签名、可见性、默认参数生成的 `$default`、`copy`、组件函数及 nullability 注解做 API/ABI 快照比较；将快照和旧调用方运行结果作为证据归档。
7. **0.4.1 已发布，不覆盖、不替换、不重打包旧包。**修复或契约澄清应进入后续版本候选；发布、覆盖分发文件及提交均不属于本路线图授权。

## 9. 执行顺序与完成定义

1. 冻结基线：复核字段/选项分母，生成追踪表；确认公开 API 与兼容保留项清单。
2. 逐项定位消费代码：每个字段至少链接到真实读点；找不到读点则标“未消费/待确认”，不可按注释假定已生效。
3. 编写行为验收：边界、错误、取消/恢复、多任务竞态及兼容用例按上表落地；测试名称能回链到行 ID。
4. 采集证据：记录运行环境、命令、实际结果、目标版本和产物摘要。失败也保留，不覆盖历史证据。
5. 评审契约变更及迁移说明；任何删改先更新分母台账和兼容决策，再实现。旧包 0.4.1 保持原样。

清点交付允许存在明确的“待验证”行；这些行保持 VERIFYING/BLOCKED，不能算能力完成。
能力验收要求每个强制行有消费点、运行证据和 PASS 状态，全部交互矩阵有记录；
旧字节码可运行或有明确破坏性版本决策；新增 API 已加行。本文不构成行为已通过的证明。

## 10. 核心清单之外的公开能力：P00 还须冻结的分母

前面的 46/9/55 只是本轮定向清点的数量，不能当作整个 SDK/插件生态的全部公开符号数。

## 9. 私有断点侧车兼容

- `progress.tdlp` 是私有格式，不属于公开 ABI；MAGIC 仍为 `TDLP`。
- v2 在同一快照中内嵌目标文件身份，避免“进度已发布、独立 identity 未发布”的崩溃窗口。
- 读取端继续支持 v1：v1 必须有可读的 `progress.tdlp.identity` 且匹配当前目标；缺失或不可读时判为 `UNKNOWN`，丢弃旧断点。
- 不支持的文件系统若无法取得稳定文件身份，会安全退回“不可续传”而不是默认放行。
P00 在以下拥有模块做一次公开声明/导出扫描，建独立分类台账；E01/O01/E02 等按其逐项实现。

| 契约组 | 拥有源码 | 必须补齐的追踪项 |
| --- | --- | --- |
| API 版本与诊断 | core `ApiVersion.kt`、`TurboDiagnostics.kt` | 解析/比较/版本要求、诊断结果字段、各公开探针、取消/超时/网络字节副作用 |
| 生命周期与服务 | runtime `Plugin.kt`、`PluginHost.kt`、`PluginContext.kt`、`ServiceRegistry.kt`、`Disposer.kt`、`EventBus.kt` | 每个方法、状态、服务键、优先级、并发/卸载/异常隔离 |
| 扩展点与注册 | runtime `ExtensionPoint.kt`、`ExtensionRegistry.kt`、`ext/ExtensionPoints.kt`、`ext/ProtocolRegistry.kt`、`ext/BackendRegistry.kt` | 每个扩展类型、注册/释放/冲突裁决、resolver 路由及 fallback |
| JS ABI/能力 | JS `resources/dev/turbodl/plugin/js/abi.js`、`JsAbi.kt`、`JsHostApi.kt`、`JsValueCodec.kt`、`JsIo.kt` | 每个导出函数/参数/返回类型、异常与值转换、权限/秘密/IO/超时限制 |
| 插件元数据/装载 | `JsPluginConfig.kt`、`JsPluginManager.kt`、bootstrap `TurboBootstrap.kt`、各模块实际 manifest | 每个实际声明的字段、必填/默认/未知字段、版本与能力要求、签名/来源信任、禁用恢复 |
| 媒体/解析器 | HLS `HlsManifest.kt`、`HlsPlugin.kt`；parser `PlatformParser.kt`、`PlatformParserPlugin.kt` | 播放列表语法/标签支持表，解析 URL 类型与不支持项；测试入口不能冒充生产 API |
| 发布入口 | `npm/cli/package.json` 及其实际 bin 入口、demo、README 公共示例 | 安装/缓存下载/JAR 版本/错误码/离线升级，示例真实编译运行 |

每种格式的未知语法要明确拒绝或记录受支持的兼容规则，不无声吞掉关键字段。
参数“可以解析”但未传到实现，或插件只注册却不能真正下载，都不得计为实现完成。

## 11. 插件后端资源契约（C06b/E01 的前置设计）

当前 `DownloadBackend` 只统一了字节节流，没有统一的连接、缓冲或后端生命周期资源租约。
在 C06b/E01 完成前，不得声称 `maxTotalConnections`、IO 总预算或停止清理覆盖“所有后端”。
后续设计卡至少确定：

- 连接租约的申请、排队、取消、超时、释放和全局公平；探测、预热、重试也算租约。
- IO 缓冲租约与任务/连接/插件三层的上限，禁止插件用私有 Semaphore 绕过。
- HLS/JS/进程型后端的生命周期，进程/文件/端口/Socket 的关闭承诺和孤儿检测。
- API 版本握手、旧后端兼容适配、缺能力时的能力探测和明确错误。
- 内置 HTTP、HLS、Kotlin 测试后端、JS 测试后端混跑的预算回归。

PAC、DoH A/AAAA、系统代理和 H1/H2 的能力声明同样必须与真实实现一致；未支持组合明确拒绝，
不能偷偷 DIRECT 回退。具体验收归 C07a 和 [EXECUTION_BOARD.md](EXECUTION_BOARD.md)。

## 12. 无法验证时的续传策略

`TurboClient.submit(request)` 保持既有兼容策略。宿主可以对单个任务选择：

```kotlin
val id = client.submit(request, UnverifiableResumePolicy.FAIL)
```

内置 HTTP 后端在已有断点无法核验时以 `ResumeVerificationException` 失败，并保留已下载文件和断点；
稍后可在链接或网络恢复后重试。该策略覆盖该任务的后续 `resume` 调用；插件后端未实现此能力时明确报错。
抽样匹配不能证明完整文件一致；如已知文件摘要，应另外设置 `expectedSha256`。
该选择不能替代完整文件摘要，也不改变旧接口的默认续传行为。
