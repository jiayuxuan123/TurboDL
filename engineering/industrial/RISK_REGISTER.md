# TurboDL 工业级风险登记

日期：2026-11-04。来源：本轮对公开契约、CLI/API、配置消费链、竞品源码和现有测试的定向静态审计。
这些条目是**待复现风险**，不是已经通过运行验证的缺陷结论。每条在修复时必须补最小复现、
实际测试、原始证据和版本，不得只改注释或放宽断言。

状态：TODO / VERIFYING / FIXING / PASS / BLOCKED。当前全部为 TODO 或 VERIFYING。

| ID | 优先级 | 风险 | 当前静态证据 | 目标任务 | 必须的完成证据 |
| --- | --- | --- | --- | --- | --- |
| RISK-01 | S1 | “全局”连接/缓冲预算只在单个 `TurboClient` 内成立 | `TurboClient` 构造并持有 `ConnectionBudget`；多 client、HLS 和其他后端各有自己的限制 | C06/C06b | 2 个 client + HLS + 测试插件混跑，实际 socket/lease 总量不超配置；停止后全部归还 |
| RISK-02 | S1 | `stableKey` 清洗碰撞和同 key 双写 | 不同原始 key 可能映射到同一 sanitized 目录，当前没有跨任务写锁 | C02 | 碰撞 key 映射可区分或明确复用一个受锁 key；同一 key 并发任务只允许一个 owner，输出摘要正确 |
| RISK-03 | S1 | 公开参数存在静默 no-op | 配置矩阵未找到 `dynamicSegmentation`、`maxStallRecoveries` 生产读点；`overlapMerge` 仍由 CLI 赋值但弃用 | P02a/Q00/C05 | 每项归为 ACTIVE 或 DEPRECATED_NOOP；no-op 有白名单/迁移/差分测试，ACTIVE 有真实行为测试 |
| RISK-04 | S1 | `maxConnectionsPerHost` 不是按 host 分桶 | 调度器当前把任务 worker 上限压到该值，镜像多 host 时语义失真 | C06/C07 | 多 host 镜像并发日志证明每 host 独立计数；重定向换 host 时额度重新分配且总量不超 |
| RISK-05 | S1 | 公共 `Metadata.resolvedUrl` 可能固定为原始 URL | 配置/API 审计显示内部已学习最终 URL，但公开 metadata 仍填请求原值 | C01 | 302 主源、镜像和跳探测场景的 Completed/Metadata 事件显示真实终址或明确契约改名 |
| RISK-06 | S1 | 插件后端可绕过连接/缓冲预算 | `BackendContext` 有 throttle，但无统一连接/缓冲 lease；HLS 已改为固定 worker + 有界队列，且 playlist/key 读取受 8 MiB 上限，但仍不共享全局连接预算 | C06b/E01 | 插件资源网关设计 + 内置 HTTP/HLS/JS 混跑预算断言 + 崩溃/取消归还 |
| RISK-07 | S1 | 插件 `supports()` 异常可能阻断内置 fallback | 已改为逐插件隔离：`supports()` 异常记录 owner/operation 并跳过，后续插件和内置 HTTP 继续；VM fatal 仍上抛。故障插件 + 正常插件 + 内置 fallback 回归通过。 | E01 | 继续保持插件卸载/并发注册/异常日志脱敏的矩阵测试 |
| RISK-08 | S1 | HLS 续传可能复用旧 playlist 分片 | 已加入 playlist 身份侧车和 `uri/range/key/iv` 语义哈希文件名；身份变化会清空缓存，改播回归通过。同 URL 同 playlist 下分片内容被原地替换仍无法仅靠清单识别。 | E02-0/E02-HLS | 继续增加分片 validator/摘要；验证同 URL 原地换内容、旧版本缓存迁移和可播放输出 |
| RISK-09 | S1 | 同大小异内容镜像可能进入同一文件 | 大小不一致已永久隔离；同大小异内容仍缺全文件或块哈希证明 | C03/E00b | 同大小异内容镜像被块哈希/预期摘要拒绝；错误区间可重下；没有哈希能力时明确降级/拒绝 |
| RISK-10 | P1 | 多 client/多后端下 IO 缓冲总预算可突破 | 单 client 按 `maxConcurrentTasks × connections` 摊薄；极端 64×256 有 8 KiB 保底 | C06 | 进程级或明确作用域的 lease 方案；极端合法配置不超名义预算，或构造时明确拒绝 |
| RISK-11 | P1 | `warmUpConnectionCount<=0` 与注释不一致 | 实际预热受当前 connections、`warmUpMaxParallel` 和预算共同裁剪，不等于注释的 min(任务上限,8) | C05/C06 | 0/负数/显式值/预算不足矩阵，记录实际握手峰值并修正文档或实现 |
| RISK-12 | P1 | `slowStartInitial=0` 在多分支有不同起点 | 续传、UPWARD、DOWNWARD、普通慢启动的 `slowStartInit` 分支不同 | C05 | 每个方向/续传/短任务的事件记录实际初始并发，决定并固定契约 |
| RISK-13 | P1 | `maxStallRecoveries` 无效且测试假覆盖 | 生产主源码未找到读点；现有测试仍传该字段 | P02a/Q00/C05 | 要么真正接入并覆盖 0/1/3 边界，要么明确弃用；测试不能靠邻近机制假通过 |
| RISK-14 | P1 | CLI batch 没有真正的跨任务预算 | 已改为整批共用一个 client，`--jobs` 控制任务并发；仍需真实 socket/取消证据 | O01b/C06 | 一个 client 批量下载，实际总量/限速/取消和断点共享；并发任务数可配置 |
| RISK-15 | P1 | `--timing` 启动事件存在丢失竞态 | 事件 SharedFlow replay=0，收集线程没有 ready 握手 | C04/O01b | 多次启动事件计数和顺序稳定；订阅就绪后才 submit，终端事件不丢 |
| RISK-16 | P1 | `resume()` 失败事件与状态顺序不一致 | 失败事件可能先于 FAILED 状态，且缺 StateChanged(FAILED) | C04 | 所有 resume 失败分支的顺序、终态、await 和晚订阅行为一致 |
| RISK-17 | P1 | CLI NDJSON/退出码缺少自动化验收 | 已有 14 项 CLI 契约测试；progress 带 taskId，部分输入静默夹取，Ctrl+C 仍缺真实信号覆盖 | O01b/Q00 | 55 标记、输入边界、退出码、JSON schema、batch、Ctrl+C 全有 CLI 级测试 |
| RISK-18 | P1 | PAC/DoH 能力声明超过实际实现 | PAC 只取第一个字面量代理；DoH 只解析 A 记录，未证明 AAAA/完整 PAC | C07a | PAC 子集或多代理执行、DIRECT、无匹配、DoH A/AAAA、IPv6 回退均有真实用例 |
| RISK-19 | P1 | 事件队列和任务表可能长期增长 | staging queue 已改为有界并优先丢弃进度/元数据；终态历史限制为最近 256 条；HLS 不再为每个分片创建 Job，改为固定 worker + 有界队列 | C04/Q01 | 24h/大量短任务后队列、Map、协程、句柄回到稳态；慢消费者策略有界 |
| RISK-20 | P1 | `resume()` 同步等待旧 job，`shutdown()` 清理承诺不足 | `resumeAsync` 已提供；探测/分片连接复用已补齐；shutdown 未证明等待所有 activity/写盘 | C04/A01 | 主线程不阻塞；shutdown 有界等待或明确错误；停止后无写入孤儿 |
| RISK-21 | P1 | HLS/JS 路由不消费 `mirrors` | HLS playlist 已按主 URL→mirrors 顺序回退，成功后相对分片 URI 以实际清单为基准；主清单 404 + 镜像成功的回归通过。JS bridge 仍未映射 mirrors。 | E01/E02-0 | 继续验证镜像来源鉴权隔离、分片层镜像语义和 JS bridge 映射 |
| RISK-22 | P2 | `tags` 只在 Created 请求可见 | 其他事件没有 request/tags；宿主若按所有事件取业务元数据会缺失 | C04/P02a | 明确每个事件携带契约；需要 tags 的事件补齐或文档改成只保证 Created |
| RISK-23 | P2 | `maxIdleConnections` 被静默抬高 | 空闲池上限取 `maxOf`，低于任务连接数时配置失去上限意义 | C06/C07 | 低值、热更新、多任务池复用/淘汰行为与错误/告警契约明确 |
| RISK-24 | P2 | 测试名/断言或测试 helper 造成假覆盖 | `ConfigConflictTest` 本地复刻公式，取消测试名称与断言不一致 | Q00 | 测试直接调用生产 API；断言与名称一致；Gradle XML 方法数/失败数可审计 |
| RISK-25 | P2 | HLS/DASH/协议“支持”可能只停留在注册/解析 | 当前 HLS 拒绝 fMP4/live；DASH/BT/ed2k/Metalink 尚无完整后端；CLI 不加载插件 | E02/E03/E04/E05/O01b | 每协议 L0-L5 台账、CLI/SDK 路由、真实服务、断点、取消、预算和输出校验 |
| RISK-26 | P1 | 弱校验器内容指纹仍是抽样，不是全文件证明 | 最多 24 个 64 KiB 窗口；变化若落在所有样本之外且服务器不提供强 ETag/摘要，无法证明一致 | C02-e/Q00/E00b | 可选的已完成区间分块摘要、`expectedSha256` 完整校验路径，或明确要求严格模式重下 |
| RISK-27 | P2 | 目标文件身份检测依赖平台能力 | POSIX 用 fileKey；Windows 用 NTFS ADS；Android 用设备号和 inode，仍需验证目标文件系统和 inode 复用风险 | C06-d/A01 | Android 真机/外置存储、FAT、符号链接和权限矩阵；不支持时给出可观测诊断 |
| RISK-28 | S0 | 旧 v1 进度侧车缺少可验证的目标文件身份 | v2 快照内嵌身份。旧 v1 无身份时，兼容模式采样匹配后才接纳；不可验证时保留数据并失败。严格模式保留并失败。采样不能证明所有字节一致。 | C02-b | 旧版真实断点、校验失败的数据保留、完整摘要或分块摘要；参见 RISK-26。 |
| RISK-29 | S1 | 429/503 限流预算耗尽后不取消其他在飞请求 | 一个分片达到限流上限只写 `failReason`；另一个持续涓流的分片不会触发 read/stall timeout，`await()` 可永久不返回。 | C05/C08 | 终态统一 `failTask()`：compare-and-set 成功后立即取消所有在飞 Call；限流耗尽 + 慢滴集成测试 5 秒内 FAILED。 |
| RISK-30 | S1 | 两个无 stableKey 的 `TurboClient` 默认共用 `task_1` 分片目录 | 每个 client 的内部任务 id 都从 1 开始；默认 workDir 相同且无 stableKey 时会竞争同一侧车/身份/目标文件。 | C02/C06 | 每个 client 获得独立未键控命名空间；稳定续传继续要求 stableKey；双 client 路径隔离测试通过。 |

## 2026-11-04 第一轮状态快照

- `PASS`：RISK-05（Metadata 真实终址/Range）、RISK-07（`supports()` 故障隔离与 fallback）、RISK-13（卡死字段明确弃用）、RISK-16（resume 失败顺序）、RISK-29（限流终态取消在飞涓流）、RISK-30（无 stableKey 的 client 目录隔离）。
- `FIXING`：RISK-02（摘要后缀与跨进程租约已落地；符号链接/长路径/权限未测）、RISK-03、RISK-04、RISK-08（playlist 身份和语义分片名已落地；同 URL 原地换内容未解）、RISK-09（大小不一致已隔离；同大小/块哈希未做）、RISK-14（单 client/`--jobs` 已落地；真实 socket/Ctrl+C 未全）、RISK-15、RISK-17（14 项 CLI 契约测试、55 标记；Ctrl+C 未全）、RISK-19（有界事件队列、终态窗口、HLS 有界 worker 已落地；长跑未验证）、RISK-20（`resumeAsync` 已提供；内部 legacy join 仍未替换）、RISK-21（HLS playlist 镜像已落地；JS/分片层未闭合）、RISK-24、RISK-26、RISK-27、RISK-28（旧进度采样迁移不等于全量验证）。
- `TODO`：RISK-01、06、10-12、18、21-23、25。静态描述保留，修复前必须先补最小复现，不能仅凭本条快照关闭。

## 处理顺序

1. 先做 C02/C03/C04/C06 中的 S1 数据与预算风险，补最小复现。
2. 再用 Q00 清理假覆盖，避免“测试通过”掩盖 no-op。
3. C07/C08 修正传输和错误状态机，确保公网实验失败可归因。
4. E01-E05 只在资源网关、哈希和媒体/服务运行时契约冻结后扩展。
5. O01b/A01 接入真实消费者，最后用 Q02 公网与 Q01 soak 关闭风险。

关闭风险时必须保留：引入版本、根因、修复提交、测试命令、原始证据、是否影响旧 JVM/Android 消费者。
证据不足的项只能改状态为 VERIFYING/BLOCKED，不能改为 PASS。
