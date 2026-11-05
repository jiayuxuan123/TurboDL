# TurboDL 工业级执行板

日期：2026-11-04。本文负责**排期、并行边界、预算和交接方式**，不重新定义产品范围。

- 范围和最终门槛以 [ROADMAP.md](ROADMAP.md) 为准。
- 公开参数、CLI、事件和 SPI 以 [API_CONTRACTS.md](API_CONTRACTS.md) 为准。
- 公网、竞品、故障和资源验收以 [NETWORK_ACCEPTANCE.md](NETWORK_ACCEPTANCE.md) 为准。
- 弱模型的最小阅读量、提示模板和交付格式以 [HANDOFF.md](HANDOFF.md) 为准。
- `CONFIG_CONSUMPTION_MATRIX.md`、`CLI_API_CONSUMPTION_MATRIX.md` 和
  `COMPETITOR_GAP_MATRIX.md` 是执行中生成的清单，必须与源码同步更新。

任何模型不得用“类已存在”“参数可解析”“编译通过”“一个本地 happy path 通过”证明任务完成。

## 1. 执行规则

1. 一张卡只交付一个主要行为、一组相邻文件、一组可复现证据。超过 5 个生产文件或
   同时跨越两个高风险契约时，先拆卡。
2. 同一工作树只允许一个写入者。并行子智能体必须使用独立 worktree 或完全不相交的
   路径集合；不能靠“应该不会同时改同一文件”冒险。
3. 下列热点一次只能有一个 owner：`TurboConfig.kt`、`Models.kt`、`TurboClient.kt`、
   `DownloadBackend.kt`、`SegmentScheduler.kt`、`SegmentStore.kt`、
   `BuiltinHttpBackend.kt`、`HttpClientFactory.kt`、CLI `Main.kt`。
4. 公开字段/选项必须有：源码消费点、成功路径、失败或非法路径、兼容结论、测试证据。
   找不到消费点就是 `TODO`，不能保留成静默 no-op 后宣称完成。
5. 每个协议后端必须完成同一套生命周期：成功、重试、取消、断点、连接/速度预算、
   错误归因、SDK、CLI、资源释放。只注册 handler 或只解析元数据不算后端。
6. 数据损坏、凭据越界、永久 await、静默假成功优先于性能和功能扩展。
7. 失败样本不能从汇总中删除。无法判断时状态是 `VERIFYING`；环境不足是 `BLOCKED`。
8. 已发布的 `0.4.1.0` 和 YunGet `2.7.23` 不覆盖。新能力进入下一版本。
9. 公网实验必须走第 3 节账本；没有账本、余额不足或来源未登记时禁止开跑。
10. 只按核对后的 PID 停止服务，不按进程名通配符清理。

## 2. 批次与门禁

| 批次 | 任务 | 进入条件 | 必须交付 | 退出条件 |
| --- | --- | --- | --- | --- |
| B0 | P00 + P02 清点 | 当前 HEAD/工作树已核对 | 基线、竞品版本、能力台账、消费矩阵 | 所有公开项有稳定 ID；旧证据与当前证据分开 |
| B1 | P01/P01b + P02/P02a + Q00 | B0 | 非周期语料、统一 runner、流量账本、消费地图、测试真实性审计 | 同一命令可跑 curl/旧版/候选/aria2；禁止假覆盖和无流量 PASS |
| B2 | C01 -> C02 | B1 | HTTP 边界、身份/侧车、独立进程 kill/restart、公网断点 | 必测故障零假成功；可证明已耐久区间不无故重下 |
| B3 | C03 -> C04 | B2 | 镜像身份、跨源头隔离、刷新回调、状态/命令竞态 | 安全边界和交错压力通过；await 不悬挂 |
| B4 | C05 -> C06/C06b -> C07/C07a -> C08 | B3 | 并发策略、预算/公平/写盘、传输、统一错误/重试状态机 | 本地模型和公网归因通过；资源不超预算；重试可预测 |
| B5 | E01 | B4 | 插件生命周期、能力、取消、JS ABI、资源边界 | 插件失败不污染其他任务；无权限调用有拒绝证据 |
| B6 | E00b -> E02-0 -> E02-HLS/E02-DASH/E03/E04b/E04/E05 | E01；协议可并行但写集隔离 | HLS/DASH、FTP/SFTP、BT/Magnet、Metalink/ed2k | 每项按能力级别 L0-L5 单独验收，不以占位代替 |
| B7 | O01 + A01 | B3-B6 的公开能力稳定 | CLI/诊断、Android 后台/存储/升级链 | 同默认值在 SDK/CLI/app 真实消费；真机或明确 BLOCKED |
| B8 | A02 | A01 | 现有 UI 的 Material 3 小步升级、截图/交互/无障碍 | 功能不回退，动效不阻塞，前后截图可比较 |
| B9 | Q01 -> Q02 -> R01 | B2-B8 必测项完成 | 30m/2h/24h soak、最终公网、完整交付 | 所有强制项 PASS 或有具名 BLOCKED，发布另行授权 |

B0/B1 之后，公网探针应随每个批次持续运行，不能把真实网络问题拖到最后。
B4 内部的 C05/C06 会在调度/资源契约上相交，应串行；C07 可在 C05 契约冻结后并行。

## 3. 8 GiB 公网实验硬预算

本轮公网实验的累计上限是 **8 GiB = 8192 MiB**。预算按所有引擎、重试、镜像切换、
断点恢复和竞品对照的全部响应体累计，不是只计算最终文件大小。

建议硬分配：

| 用途 | 上限 |
| --- | ---: |
| P00/P01 连通性、来源确认、runner 自测 | 384 MiB |
| C01-C04/E/O 功能性公网与恢复 | 1024 MiB |
| 候选引擎性能定位与迭代 | 1536 MiB |
| aria2/Gopeed/XDM/axel 共同档位 | 1024 MiB |
| 断点/故障/多来源恢复矩阵 | 1024 MiB |
| 最终同候选全矩阵与复测 | 2048 MiB |
| 未预见来源波动、失败重跑、竞品补充 | 1152 MiB |
| **合计** | **8192 MiB** |

执行器必须在忽略提交的实验目录建立持久账本。台账按任务分片为
`ledger/<task-id>.csv`，由只读聚合器生成汇总；并发执行者不能同时写一个总 CSV。
每个分片和 `leases/<task-id>.json` 至少包含：

```text
run_id, reserved_bytes, actual_response_bytes, engine, source_id, case_id,
started_at, finished_at, attempts, retry_bytes, cache_state, status, evidence_path,
budget_bytes, budget_remaining, budget_stop_reason, owner, branch, start_head
```

流程：

1. 开跑前登记来源、对象大小、引擎数、重复数、代理路径和预计响应字节。
2. 用原子锁或单写者占用预算；`reserved_bytes` 未释放前，其他 runner 不得超卖余额。
3. 预计后余额小于零时直接拒绝；不允许“跑了再看”。
4. 单次实际字节超过预留 20% 时立即停止该 cell，保留现场并重新估算。
5. 运行后把实际值回填；异常退出也要根据服务器/proxy 日志补账，不能漏记。
6. CDN 缓存 HIT/MISS 分开记录，但预算按客户端实际收到的响应体保守计数。
7. 竞品和候选使用同一来源、同一代理、同一对象；顺序交错，禁止只让 TurboDL 享受热路径。
8. 没有可核对的流量数据时，该公网 cell 标 `BLOCKED` 或只做不计分的连通性检查。
9. 每个任务租约记录 owner、worktree、允许写路径、开始 HEAD 和锁定的热点文件；
   第二执行者在冲突路径上必须被拒绝，任务结束或取消时释放租约。

预算不足以完成所有理想 cell 时，优先保证：正确性、断点恢复、同条件竞品、最终候选。
删除的只是“统计重复”，不是失败样本；台账中保留未执行的预算原因。

## 4. 能力成熟度

所有任务和能力行使用统一等级。最终发布不能只看 L2。

| 等级 | 含义 |
| --- | --- |
| L0 | 只有声明、注释或设计 |
| L1 | 参数/协议被解析并接线，真实语义未验证 |
| L2 | 受控成功路径可用，输出摘要正确 |
| L3 | 非法输入、失败、取消、超时、重试和资源回收已验证 |
| L4 | 独立进程/重建/SDK 和 CLI 真实消费；符合预算与状态契约 |
| L5 | 可重复公网/竞品同条件非劣，目标场景达到预登记收益 |

发布门禁：

- HTTP、断点、生命周期、资源、CLI、Android 主链至少 L4。
- 已声明可用的每种协议后端至少 L4；暂不支持的格式必须明确拒绝并保留 L0-L2 缺口。
- 性能结论至少 L5 才有资格写“领先”；L4 只能写“可用且非劣待确认”。
- 安全或数据正确性任一 FAIL，整体不得进入 R01。

## 5. 弱模型可直接领取的子卡

以下拆分保持原任务 ID，不新增第二套编号。每张子卡应独立提交证据，再由主模型合并。

### C02 断点

| 子卡 | 单一行为 | 主要写集 |
| --- | --- | --- |
| C02-a | 相同/不同/未知三态与强/弱 validator 决策表 | `BuiltinHttpBackend.kt`、`Models.kt`、对应测试 |
| C02-b | 侧车原子写、备份、截断和完整覆盖校验 | `SegmentStore.kt`、`SegmentStoreTest.kt` |
| C02-c | 独立进程强杀/重启/同 stableKey 恢复 | 新测试 + `_bench` 重启 runner |
| C02-d | 目标删/截断/改写/重名与权限/磁盘失败 | `SegmentStore.kt`、错误映射测试 |
| C02-e | 弱 ETag、同长异内容与局部指纹边界 | `BuiltinHttpBackend.kt`、续传测试 |
| C02-f | 公网签名过期/403/断网保留断点 | 测试 runner + 报告，不改公共默认值 |

C02-b/C02-d 不能并行写 `SegmentStore.kt`；C02-a/C02-e 不能并行写
`BuiltinHttpBackend.kt`。用 worktree 隔离后按依赖顺序合并。

### C05 调度

| 子卡 | 单一行为 | 主要写集 |
| --- | --- | --- |
| C05-a | DOWNWARD/UPWARD 真实起点矩阵 | `AdaptiveConcurrencyController.kt`、`SegmentScheduler.kt` |
| C05-b | slowStart/slowStartInitial/续传/短任务交互 | `SegmentScheduler.kt`、配置测试 |
| C05-c | 采样干净窗口、收尾窗口排除、恢复不锁档 | `AdaptiveConcurrencyController.kt` |
| C05-d | 32 MiB 高并发供应、尾段 ETA、请求成本 | `SegmentScheduler.kt`、吞吐回归 |
| C05-e | 256 路暂停/取消/背压/多任务交错压力 | 压力测试，先不改生产算法 |

### C06 资源

| 子卡 | 单一行为 | 主要写集 |
| --- | --- | --- |
| C06-a | 64×256 合法配置下 IO 缓冲总预算 | `TurboConfig.kt`、`IoBufferBudgetTest.kt` |
| C06-b | 全局连接预算覆盖预热/重试/插件/恢复 | `ConnectionBudget.kt`、E2E 测试 |
| C06-c | 全局限速热更新、多任务共享和突发上界 | `SpeedLimiter.kt`、限速测试 |
| C06-d | Windows/Linux/Android 写盘、稀疏与失败语义 | `SegmentStore.kt`、平台测试 |
| C06-e | 任务公平、防饥饿、停止后资源回落 | `TurboClient.kt`、长跑 runner |

### C07 传输

拆成 DNS、TLS、HTTP proxy、SOCKS/PAC、H1/H2 协商、取消/超时六张子卡。
共享 `HttpClientFactory.kt` 时严格串行，每张卡只保留一个行为变更。

### E02 媒体

按现有 HLS VOD -> fMP4/init map -> master/多轨 -> live -> DASH 顺序拆卡。
每张卡先证明输入格式和输出可播放性，再优化并发。DRM/SAMPLE-AES 只允许明确拒绝。

### E03/E04/E05 协议

每张卡都按“解析/身份 -> 单会话下载 -> 偏移恢复 -> 取消/超时 -> 预算 -> CLI/SDK ->
真实服务 -> 竞品”的 L0-L5 顺序推进。BT/Magnet 至少拆出 metadata、tracker/DHT、
piece 校验、文件选择、断点复启、上传预算六张卡。

### A01/A02 Android

A01 先修 SDK 消费和服务/数据库/存储，再碰 UI。A02 每张卡只改一个现有页面或组件族，
必须附改前改后截图、长列表/大字体/暗色/TalkBack 中至少两个相关证据，不允许顺手重设计。

## 6. 单卡交接格式

主模型把下面内容发给执行模型，并把路径写成真实路径，不使用“相关文件”这种模糊词：

```text
Task: 【任务/子卡 ID】
Goal: 【一个可观察行为】
Base: TurboDL HEAD=【sha】；YunGet HEAD=【sha】
Read only: 【2-5 个入口文件】；【最多 2 个相关测试】
Write allowlist: 【明确文件集合】
Do not touch: 【热点文件/其他 owner】
Behavior to prove: 【触发条件 -> 当前 -> 目标】
Acceptance: 【回归测试、完整摘要、资源/网络证据、对应 NETWORK_ACCEPTANCE ID】
Network budget: 【无 / 预留字节和 ledger run_id】
Compatibility: 【旧 JVM 字节码/公共默认值/发布版本约束】
Deliver: changed files, root cause, tests/raw evidence, unverified items, next task
```

执行模型若发现目标需要修改公开契约或跨越 write allowlist，立即停止并返回一个最小设计差异，
不得自行扩展成全模块重写。

## 7. 隐性缺陷流程

新缺陷分配 `BUG-<区域>-<序号>`，记录最小复现、影响版本、触发条件、数据/安全影响、
所属任务 ID。分类为：

- `S0`：静默数据损坏、凭据泄漏、不可逆误删。
- `S1`：假成功、永久卡死、断点失效、资源无界。
- `S2`：可恢复错误归因错误、吞吐严重退化、兼容破坏。
- `S3`：可观测性、小体感或文档偏差。

S0/S1 暂停依赖它的性能与协议扩展，先修复并补回归。S2/S3 进入台账，不打断当前卡，
除非本卡证据依赖它。不能把“复现一次未再出现”当修复。

## 8. 每轮结束必须更新

1. 对应任务卡的状态和证据路径。
2. 能力台账的成熟度、阻塞和版本。
3. 实际命令、退出码、测试数、摘要和 run_id。
4. 受影响的消费者与旧字节码/发布兼容结论。
5. 未验证项和下一张可领取卡。

只有当 ROADMAP 第 3 节的所有维度都有 L4/L5 证据，且失败行被显式处理时，
项目才可以从“建设中”改为“工业级完成”。
