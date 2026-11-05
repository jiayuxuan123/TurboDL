# TurboDL 竞品能力与架构差距矩阵

日期：2026-11-04。范围：只依据工作区已有材料，不重新下载仓库，不运行 benchmark，不读取任何用户账号、Cookie 或私有请求头。本文是能力与架构对标，不是速度结论，也不是“竞品已通过”的验收记录。

## 1. 取证口径

本矩阵把“看到源码实现路径”和“实际运行通过”严格分开：

- `[S]` **源码已确认**：本地源码中存在明确类、函数、字段或协议解析路径；只证明实现代码存在，不代表所有边界、平台或真实网络行为已经运行验证。
- `[D]` **文档声明但未运行验证**：只在 README、manual、help、配置说明中看到能力声明，未用本地编译产物或真实下载做验收。
- `[U]` **本地未知**：本地材料没有足够入口、只看到注释，或本轮没有做运行验证；不把“未找到”写成“必然不支持”。
- `[N/A*]` **本冻结样本未见可比入口**：只对本次本地样本成立，不代表该项目全部历史版本或未来版本永远没有该能力。

本轮的“源码已确认”不等于 TurboDL 可以对标通过；正式结论仍应按 `NETWORK_ACCEPTANCE.md` 的同一来源、同一时刻、同一完整哈希和原始结果留存执行。

## 2. 冻结样本

| 项目 | 本地样本 | 版本/提交 | 许可证 | 备注 |
|---|---|---|---|---|
| aria2 | `_research/turbodl-upstreams/aria2/aria2-1.37.0-win-64bit-build1/aria2c.exe`；`_research/turbodl-upstreams/aria2-src/` | Windows 二进制 `1.37.0`；源码 HEAD `9e72735`，2026-06-25 | `GPL-2.0-or-later`（`COPYING`） | 源码与二进制不是同一个时间点，协议能力按源码与本地手册交叉确认 |
| Gopeed | `_research/turbodl-upstreams/gopeed-src/`；`gopeed-web/gopeed-web-v1.9.3-windows-amd64/gopeed.exe` | 源码 HEAD `8719166`，2026-10-30；另有本地 `1.9.3` web 二进制 | `GPL-3.0`（`LICENSE`） | 本轮以源码 HEAD 为主；未运行 GUI/服务 |
| Axel | `_research/turbodl-upstreams/axel-src/` | 源码 HEAD `dc10ba0`，2026-09-29 | `GPL-2.0-or-later`，带 OpenSSL 例外（`COPYING`、`src/axel.h`） | 只读源码和本地文档 |
| XDM | `_research/turbodl-upstreams/xdm-src/` | 源码 HEAD `e5a9ecd`，2026-10-27 | 顶层 `GPL-3.0`；`xdm-core/LICENSE` 为 Apache-2.0 | `xdm-core` 可单独复用，但 app、浏览器扩展仍是 GPL-3.0；复用时必须保留 `xdm-core/NOTICE` |
| TurboDL | `TurboDL/` | 规划基线文档写 `3368ec4`、`0.4.1.0` | `MIT` | 本文件只写规划入口，不重新验证全部行为 |

## 3. 总览矩阵

| 能力 | aria2 | Gopeed | Axel | XDM | TurboDL 当前规划任务 |
|---|---|---|---|---|---|
| HTTP Range/分块 | `[S]` HTTP Range、分段、`Content-Range` 解析 | `[S]` 分段 Range、强校验器、分块校验 | `[S]` `Range: bytes=`、固定分片 | `[S]` 分块 Range、动态拆分 | `C01`（旧 `TD-01`）、`C02`（旧 `TD-02/03`） |
| 连接调度 | `[S]` `split`/每服务器上限、分片管理 | `[S]` slow start、work stealing | `[S]` 固定连接数、分片再分配 | `[S]` 固定上限内动态拆分/接管 | `C05`（旧 `TD-08/09`）、`C06`（旧 `TD-09/10/16`） |
| 自适应/限速 | `[S/D]` URI 选择器和并发下载优化；全局/单任务限速 | `[S]` 自适应连接；`[U]` HTTP 用户级限速入口 | `[S]` 固定 max-speed，非自适应 | `[S]` 速度限制、动态分块；无独立吞吐自适应控制器 | `C05`、`C06`（旧 `TD-08/09/10`） |
| 断点/会话文件 | `[S/D]` `.aria2` 控制文件、session、continue | `[S]` `gopeed.db` bbolt、任务恢复 | `[S]` `.st` 状态文件 | `[S]` GUI/引擎 state；headless 运行态临时清理 | `C02`（旧 `TD-02/03`）、`C04`（旧 `TD-05/16`） |
| 镜像/多来源 | `[S]` 多 URI、反馈/自适应选择器、Metalink 资源 | `[S]` 仅见 GitHub mirror provider；通用单任务多镜像 `[U/N/A*]` | `[S/D]` 多 URL 和 filesearching 搜索；明确不校验文件相同 | `[U/N/A*]` 本地源码未见通用 mirror pool | `C03`（旧 `TD-04/11`） |
| 校验 | `[S]` 整文件 checksum、Metalink 分块 checksum | `[S]` MD5/SHA-1/SHA-256 | `[N/A*]` 本地源码/帮助未见文件校验 | `[S]` MD5/SHA-1/SHA-256/SHA-512、侧车文件 | `C01/C03`（旧 `TD-01/04/11`），另见 `CFG-05` |
| 重试/退避 | `[S/D]` `max-tries`、`retry-wait`、重试命令 | `[S]` 递增等待到 5 秒封顶，部分错误持续重试 | `[S/U]` 重连/重启路径；正式退避策略不明 | `[S]` Retry-After、最大重试、可取消等待 | `C05`（旧 `TD-08/09`）、`C03`、`P02` |
| 代理/DNS/TLS/H1/H2 | `[S]` 代理、异步 DNS、TLS、HTTP/1.1；HTTP/2 `[N/A*]` | `[S]` 代理/TLS；Go transport 可协商 H2；DoH `[U]` | `[S]` 环境代理、SSL/TLS、IPv4/6；HTTP/2 `[N/A*]` | `[S]` 代理/TLS、HTTP/1.1；HTTP/2 `[N/A*]` | `C07`（旧 `TD-11`） |
| FTP/SFTP | `[S]` FTP 和 SFTP 均有下载命令 | `[S]` FTP/FTPS/FTPES；SFTP `[U/N/A*]` | `[S]` FTP/FTPS；SFTP `[N/A*]` | `[N/A*]` xdm-core 无 FTP 下载器，headless 明确拒绝 `ftp://` | `E03`（旧 `TD-14`） |
| BT/Magnet | `[S/D]` BT、Magnet、DHT、tracker 等完整路径 | `[S/D]` BT、Magnet、DHT、tracker、选文件/做种 | `[N/A*]` | `[N/A*]` | `E04`（旧 `TD-14`） |
| Metalink | `[S]` Metalink v3/v4、分块校验、资源优先级 | `[N/A*]` 本地源码未见 Metalink | `[N/A*]` | `[N/A*]` | `E05`（旧 `TD-14`） |
| HLS/DASH | `[N/A*]` 本地源码/手册未见 HLS/DASH | `[S]` HLS VOD；DASH 未见 | `[N/A*]` | `[S]` HLS/DASH 解析和下载；DASH dynamic 明确拒绝 | `E02`（旧 `TD-12`） |
| 插件/扩展 | `[N/A*]` 无通用协议插件；有 RPC/外部控制 | `[S]` JS extension manifest、host、REST 安装 | `[N/A*]` | `[S]` 浏览器扩展；无通用引擎插件 API | `E01`（旧 `TD-13`）、`REL-02` |
| CLI/API | `[S]` CLI、JSON-RPC/XML-RPC | `[S]` 命令行下载、REST/headless web API | `[S]` CLI；内部 C API 不是稳定适配接口 | `[S]` headless CLI；浏览器集成端口非通用公开 API | `O01/P02`（旧 `TD-15`/`TD-06`） |
| 资源/长跑 | `[D/S]` daemon、disk cache、session；长跑未测 | `[S]` 连接缓冲、任务上限；长跑未测 | `[S]` 轻量结构；长跑未测 | `[S]` 分块缓冲、同步窗口；长跑未测 | `C06`（旧 `TD-09/10/16`）、`Q01` |
| 许可证 | GPL-2.0-or-later | GPL-3.0 | GPL-2.0-or-later（OpenSSL 例外） | core Apache-2.0，app/扩展 GPL-3.0 | GPL 源码不能直接复制进 MIT core；Apache core 也需 NOTICE/归因 |

## 4. 逐项证据与对标动作

### 4.1 HTTP Range、分块与响应校验

**aria2（`[S]`）**

- `src/HttpRequest.cc` 的 `HttpRequest::getRange`、`HttpRequest::isRangeSatisfied` 和构造 `Range:` 请求头的路径，确认 Range 不是只由 CLI 文案声明。
- `src/HttpHeader.cc` 的 `HttpHeader::getRange` 解析 `Content-Range`，`src/HttpResponse.cc` 在响应范围不满足时进入失败/重试路径；`src/SegmentMan.cc` 管理分片与 `GrowSegment/PiecedSegment`。
- `src/usage_text.h` 和 `doc/manual-src/en/aria2c.rst` 提供 `--split`、`--min-split-size`、`--max-connection-per-server` 的公开入口。

**Gopeed（`[S]`）**

- `internal/protocol/http/fetcher.go` 的 `chunk{Begin,End,Downloaded}`、`downloadChunkOnce`、`parseRangeResponse`、`validateRangeResponse` 和 `fallbackToSequentialDownload` 覆盖 206 校验、Range 被忽略回到 200、短读/长度不一致等路径。
- `internal/protocol/http/helper.go` 的 `extractIfRangeValidator` 只接受强 ETag，或在 `Date` 与 `Last-Modified` 关系足够强时使用后者；`fetcher_manager.go` 持久化 `IfRange`、`RangeReprobeEligible`、`RangeValidatorPinned`。
- 这是本轮最值得直接对标“Range 正确性”的实现路径，但它仍是 `[S]`，不是已运行通过的证据。

**Axel（`[S]`）**

- `src/http.c` 在请求中追加 `Range: bytes=...`；`src/axel.c` 的 `axel_divide` 把文件划分给固定连接，`src/conn.c` 的 `conn_setup`/`conn_exec` 处理 HTTP 和 FTP 的偏移请求。
- 结构是固定分片加连接重启/再分配，不是 Gopeed 那种基于吞吐估计的 slow start 或 work stealing。

**XDM（`[S]`）**

- `xdm-core/src/main/java/xdm/core/downloaders/web/http/HttpDownloader.kt` 有 `splitChuck`、`takeOverChunk`、`piecesMakeUpFile`；`HttpChunkRetriever.kt` 发送 Range，并在响应 `200`/长度不符时拒绝把整文件写入分片偏移。
- `HttpChunkRetriever.kt` 还使用 `If-Range`，但当前源码采用 `Last-Modified` 作为下载器校验器；注释明确说明不拿 ETag 作为跨镜像恢复依据。

**TurboDL 当前任务与必须测的数据**

- 入口：`C01`（旧 `TD-01`）负责 200/206/416、短读、超长、总长变化、编码、重定向和探测；`C02` 负责分片侧车和恢复边界。
- 必测：正常分段；忽略 Range 的 200；错误/缺失 `Content-Range`；短读；`416` 后恢复/持续 416；Range 期间文件变化；重定向后 Range；gzip identity；零字节/未知大小/大文件；用非周期字节和独立完整哈希验证，不用文件长度或重复填充替代。

### 4.2 连接调度

**aria2（`[S]`）**

- `SegmentMan::checkoutSegment/getSegment` 负责分片分配；`prefs.cc` 和 `usage_text.h` 明确 `--split`、`--max-connection-per-server`、`--min-split-size`；本地手册还列出 `--stream-piece-selector`。
- aria2 的核心优势是多个连接和多个 URI 的统一分片管理，不能只测“连接数是否变大”。

**Gopeed（`[S]`）**

- `internal/protocol/http/fetcher.go` 的 `slowStartController` 按批次扩大连接，初始批次为 1；`helpOtherConnection`、`stealThresholdSeconds = 3`、`stealMinChunkSize = 512 KiB` 构成连接间工作窃取。
- 这意味着 Gopeed 的调度不是固定把文件除以连接数；对标时要同时记录实际 socket/H2 stream、慢连接分布、尾部完成时间和无效连接数。

**Axel（`[S]`）**

- `axel.c` 的 `axel_new` 按 `num_connections` 建立连接数组，`axel_divide` 负责固定切分，`reactivate_connection`/`restart_connections` 处理连接失效后的任务转移。
- 没有看到 Gopeed 式基于实时吞吐的控制循环；它的可比较点是低资源固定分片、重连和再分配。

**XDM（`[S]`）**

- `HttpDownloader.kt` 的 `maxChunk` 限制任务分片数，`splitChuck` 在有空闲槽时拆分未完成的最大分片，`takeOverChunk` 接管连续分片，最小拆分阈值约为 256 KiB。
- 这是“固定上限内的动态分块/接管”，不是自适应下载器；对标时要确认它不是通过超发连接伪装提速。

**TurboDL 当前任务与必须测的数据**

- 入口：`C05`（旧 `TD-08/09`）统一 slow start、目标连接、工作窃取、尾段托管；`C06`（旧 `TD-09/10/16`）统一任务槽、连接预算、host 上限和全局限速。
- 必测：1/4/8/16/32/64/128/256 连接；单连接限速；全局限速；慢 10 倍连接；快速/慢任务混排；暂停/取消；实际 socket 与 H2 stream 必须分别记录；尾部 ETA 不能只看进度条。

### 4.3 自适应与限速

**aria2（`[S/D]`）**

- `src/AdaptiveURISelector.cc` 的 `selectOne`、`getBestMirror`、`getMaxDownloadSpeed` 使用服务器性能档案挑选镜像。
- `--optimize-concurrent-downloads` 在本地手册/`usage_text.h` 声明按历史速度对并发下载数做对数公式调整；`--max-overall-download-limit`、`--max-download-limit` 提供全局限速和单任务限速。
- 需要把“URI 选择自适应”和“连接数自适应”分开，不能合成一个“aria2 自适应”结论。

**Gopeed（`[S/U]`）**

- slow start、连接扩展和工作窃取是 `[S]`；`internal/protocol/http/config.go` 只有 `UserAgent`、`Connections`、`UseServerCtime` 等字段。
- 本轮在 Go 源码中没有找到 HTTP 任务级/全局用户下载限速的明确配置入口，因此标 `[U]`，不把 BT 侧的 rate limiter 或 UI 文案当作 HTTP 限速已经成立。

**Axel（`[S]`）**

- `src/axel.c` 的 `enforce_throttling` 使用 `max_speed` 调整等待；`src/conf.c` 从配置/环境初始化 `max_speed`，固定 `num_connections`。
- 可比较的是固定速度目标误差、短任务突发和 TCP 慢启动，而不是“自适应收敛”。

**XDM（`[S/U]`）**

- `xdm-core/.../web/SpeedLimiter.kt` 按累计字节和经过时间睡眠，CLI 有 `--speed-limit <KB/s>`；`HttpDownloader.kt` 有固定 `maxChunk` 和动态拆分。
- 本轮未见类似 `AdaptiveConcurrencyController` 的吞吐反馈控制；标 `[U]`，不做“支持自适应”的肯定结论。

**TurboDL 当前任务与必须测的数据**

- 入口：`C05` 的自适应/slow start 交互，`C06` 的 `globalSpeedLimitBytesPerSec`、`SpeedLimiter` 和连接预算。
- 必测：稳态 30 秒限速误差、短文件突发、动态修改限速、每连接限速与总限速、429/503 降档、并发任务公平性；将连接上限、起始连接、实际连接、已接受档位分开记录。

### 4.4 断点、侧车与会话文件

**aria2（`[S/D]`）**

- `src/SessionSerializer.cc`、`src/SaveSessionCommand.cc` 和 `src/AutoSaveCommand.cc` 构成 session 保存/恢复路径；本地手册列出 `--save-session`、`--save-session-interval`、`--continue`、`--auto-save-interval`。
- `.aria2` 控制文件是协议相关控制状态，不是“把下载进度写进日志”；对标应检查控制文件、目标文件和真实数据三者的一致性。

**Gopeed（`[S]`）**

- `pkg/download/storage.go` 使用 bbolt，本地文件名 `gopeed.db`；`pkg/download/downloader.go` 定义 `task`、`save`、`task_stats`、`protocol_state` 等 bucket，并在 `Setup` 中恢复任务。
- `internal/protocol/http/fetcher_manager.go` 将 `IfRange`、Range 模式、连接状态等快照存回存储层。

**Axel（`[S]`）**

- `src/stfile.c` 的 `stfile_load`、`stfile_save`、`stfile_unlink`，以及 `axel.c` 定时调用 `stfile_save`，证明 `.st` 状态文件是真实实现。
- 状态记录的是各连接当前字节位置和连接数，不是完整 HTTP 校验器；对标时不能要求 Axel 提供 Gopeed 式 `If-Range` 语义。

**XDM（`[S]`，但区分入口）**

- `xdm-core/.../web/StateSaver.kt` 保存 `Chunk.synced`、下载范围、校验器和 `noRangeSupport`；`TaskInfoDB.kt` 保存任务参数；`HttpDownloader.kt` 只有在 `syncAll()` 后才发布完成，源码注释明确“记录已知在磁盘上的数据”。
- 但 `xdm-app/.../headless/HeadlessCli.kt` 明确使用临时目录并在结束后删除，任务被中断时下次从头开始；GUI/引擎恢复和 headless CLI 不能混成一个能力。

**TurboDL 当前任务与必须测的数据**

- 入口：`C02`（旧 `TD-02/03`）要求 `.tmp/.bak`、动态网格、一致快照、strong/weak/unknown 三种身份结果，以及同一 `stableKey` 的跨进程锁。
- 必测：暂停/继续、精确 PID kill、整机重启/断电设备、侧车主文件截断、目标文件删改、磁盘满/权限丢失、签名过期、同大小换内容；记录“已证明耐久区间”和允许重下窗口。

### 4.5 镜像与多来源

**aria2（`[S]`）**

- `src/AdaptiveURISelector.cc`、`src/FeedbackURISelector.cc`、`src/InorderURISelector.cc` 以及 `usage_text.h` 中的 `--uri-selector`、`--server-stat-*`，确认可按顺序、历史速度或自适应方式选择 URI。
- `src/Metalink2RequestGroup.cc` 将同一条目的 HTTP/FTP/BT 资源建立为请求组；Metalink 还能提供分块 checksum。
- 这说明 aria2 的多来源和内容身份/分块校验是配套能力，不能只抄“多个 URL 轮询”。

**Gopeed（`[S]`，仅限 GitHub mirror；通用多镜像 `[U/N/A*]`）**

- `internal/githubmirror/config.go`、`execute.go`，以及 `pkg/download/github_mirror.go`、`pkg/download/mirror.go` 是 GitHub raw/repository 的候选和 race/fallback 路径；`pkg/api/service.go` 还有 `POST /api/v1/mirrors/{provider}/candidates`。
- 本轮没有找到像 aria2 那样把多个任意同内容 URL 放进同一 HTTP 任务、按内容身份路由的通用 mirror pool；因此不能把 GitHub mirror 当成通用多来源能力。

**Axel（`[S/D]`）**

- `doc/axel.txt` 明确支持多个镜像 URL，`src/search.c` 的 `search_makelist`/`search_getspeeds` 和 `src/axel.c` 的 `axel_new(conf, count, res)` 将多个 URL 分配给连接。
- 文档同时明确“程序不检查文件是否相等”；这是与 TurboDL `C03` 的关键安全差距，不能照抄其多源策略而省略内容证明。

**XDM（`[U/N/A*]`）**

- `xdm-core` 的 HTTP 下载器围绕单任务 URL、Range、分片状态展开；本地源码只在注释中提到“mirror 的 ETag 可能不同”，没有看到通用 `MirrorPool` 或多 URL 任务模型。
- 因此本轮不能给 XDM 记“通用多来源”；其 checkum 侧车探测也不是多源下载。

**TurboDL 当前任务与必须测的数据**

- 入口：`C03`（旧 `TD-04/11`）的 `MirrorPool`、`MirrorHeaderPolicy`、`RequestOrigin`、`HttpClientFactory`。
- 必测：主源/镜像同大小异内容；缺总长；强/弱校验器；跨源重定向；敏感请求头隔离；镜像过期刷新；来源失败退避；坏源隔离；有预期哈希时必须整文件校验，不能仅比较长度或局部样本。

### 4.6 校验

**aria2（`[S]`）**

- `src/Checksum.cc`、`src/download_helper.cc`、`src/ChecksumCheckIntegrityEntry.cc` 是整文件校验路径；`src/Metalink2RequestGroup.cc` 在 Metalink 中接收 chunk checksum，并对可用情况设置分片哈希。
- 本地手册提供 `--checksum` 和 `--hash-check-only` 等入口；具体算法/构建依赖仍需按实际二进制再测。

**Gopeed（`[S]`）**

- `pkg/protocol/http/model.go` 的 `ChecksumOption` 规范化支持 `md5`、`sha1`/`sha-1`、`sha256`/`sha-256`；`internal/protocol/http/fetcher.go` 的 `verifyChecksum` 在完成后校验，失败会强制下一次完整重下。
- `cmd/gopeed/flags.go` 还有 `-checksum` 和 `-checksum-algo`。

**Axel（`[N/A*]`）**

- 本地 `src/` 和 `doc/axel.txt` 未找到文件摘要/哈希校验入口；TLS 校验只能证明链路，不等于内容校验。

**XDM（`[S]`）**

- `xdm-core/.../checksum/Checksums.kt` 支持 MD5、SHA-1、SHA-256、SHA-512；`ChecksumVerifier.kt` 支持粘贴哈希、常规侧车 URL 探测和流式校验。
- 校验侧车请求只对同 host 发送原请求头/Cookie，且丢弃 Range/If-Range 等传输头；这个范围边界值得对标。

**TurboDL 当前任务与必须测的数据**

- 入口：现有 `expectedSha256` 在 `C01/C02` 链路消费；`C03` 的 `ChecksumTest` 负责镜像/来源与摘要关系。建议在任务卡中明确“校验失败后删除/保留断点”的策略。
- 必测：期望哈希正确/错误；未知哈希；MD5/SHA-1/SHA-256/SHA-512 如果列为支持范围；哈希格式错误；校验期间取消；校验失败后是否有资格复用临时文件；镜像侧车与主文件是否同 host。

### 4.7 重试与退避

**aria2（`[S/D]`）**

- `src/HttpSkipResponseCommand.cc` 和 `src/DlRetryEx.*` 有重试/退避入口；本地手册列出 `--max-tries`（默认 5，0 不限）和 `--retry-wait`。
- `src/RequestGroup.cc` 还维护 `fileNotFoundCount`，并按 `--max-file-not-found` 终止持续不存在；本轮没有对所有错误码的退避公式做运行验证。

**Gopeed（`[S]`）**

- `internal/protocol/http/fetcher.go` 维护每连接 `retryTimes`；计数错误达到阈值后失败，重试等待按 `(retries+1)` 秒递增并封顶 5 秒；源码还明确存在对非计数错误持续重试的分支。
- `slowStartController` 有 `paused` 字段用于在 429 等情况下暂停扩容；对标时要记录 429、403 和普通断连是否走同一路径。

**Axel（`[S/U]`）**

- `axel.c` 的 `restart_connections`、`reactivate_connection` 和配置项 `reconnect_delay` 提供重连/重排路径；本地源码和帮助没有看到 aria2 式的 `max-tries`/正式指数退避参数。
- 只能确认“存在重连实现”；不同错误下的实际重试次数、退避和取消行为需要运行测试，标 `[U]`。

**XDM（`[S]`）**

- `HttpChunkRetriever.kt` 解析 `Retry-After`、在无数据时累计 `retryCount`，并切换为分块可取消的 sleep；`CoreConfig.maxRetries` 控制重试上限。
- HTTP/2 不参与这里的结论；当前 `HttpClientImpl` 明确把协议设为 `HTTP_1_1`。

**TurboDL 当前任务与必须测的数据**

- 入口：`CFG-05 maxRetries`；`C05`（429/503/背压与恢复）；`C03`（镜像来源独立退避）；`P02`（参数是否真正消费）。
- 必测：429 + `Retry-After` 的秒数和日期形式；503；非法/超大 Retry-After；同一连接成功传输后失败是否消耗重试；暂停/取消是否能打断等待；坏源退避不能阻塞其他镜像；不能无限重试刷成功率。

### 4.8 代理、DNS、TLS、HTTP/1.1 与 HTTP/2

**aria2（`[S]`）**

- `src/AsyncNameResolver.cc`、`src/AsyncNameResolverMan.cc`、`src/DNSCache.cc` 证明异步 DNS/缓存路径；`--async-dns`、`--async-dns-server` 见 `prefs.cc`、`usage_text.h`。
- `src/TLSContext.h`、`src/LibsslTLSContext.cc`、`src/LibgnutlsTLSContext.cc`、`src/WinTLSContext.cc` 是 TLS 抽象；`--all-proxy`、`--http-proxy`、`--https-proxy`、`--ftp-proxy` 和认证选项在本地手册/`usage_text.h` 中可见。
- `README.rst` 明确写 HTTP/1.1；本地源码未找到 HTTP/2/DoH 的对应入口。因此 H1 为 `[S]`，H2/DoH 为 `[N/A*]`/`[U]`，不能把异步 DNS 当成 DoH。

**Gopeed（`[S]`）**

- `internal/httpclient/client.go` 设置 `ForceAttemptHTTP2: true`，并支持 `Proxy`、`DialContext`、`TLSClientConfig`；`internal/protocol/http/helper.go` 的客户端构造使用代理并设置 `InsecureSkipVerify` 配置。
- `pkg/base/model.go` 有系统/自定义/跟随请求代理模式；BT 另有 DNS cache，`internal/webview/proxy` 保留 SOCKS 远端 DNS 语义。HTTP 的 DoH 入口在本轮源码中未见，标 `[U]`，不能由 Go 默认解析器推定为 DoH。

**Axel（`[S]`）**

- `src/conf.c` 读取 `http_proxy`/`HTTP_PROXY`、`no_proxy`；`src/conn.c`/`src/http.c` 建立代理请求；`src/ssl.c`、`src/ssl_verify.c` 处理 TLS；`src/tcp.c`/`--ipv4`/`--ipv6` 对应地址族。
- 代码以 HTTP/1.1 请求文本为核心，未发现 HTTP/2；标 H1 `[S]`、H2 `[N/A*]`。

**XDM（`[S]`）**

- `xdm-core/.../network/http/impl/HttpClientImpl.kt` 使用 OkHttp，明确 `.protocols(listOf(Protocol.HTTP_1_1))`；`CoreConfig.kt` 提供 HTTP/SOCKS 代理字段和 `readTimeoutSeconds`；`HttpAuth.kt`/`Ntlm.kt` 有代理和服务器 Basic/NTLM。
- TLS 默认使用平台校验，只有显式 `ignoreCertErrors` 才关闭证书/主机名校验；HTTP/2 在本冻结 core 中不支持，DoH 未见入口，标 `[U/N/A*]`。

**TurboDL 当前任务与必须测的数据**

- 入口：`C07`（旧 `TD-11`）必须逐项证明系统 DNS/DoH/自动候选、IPv4/IPv6、PAC/HTTP/SOCKS/系统代理、TLS 证书/主机名、H1/H2 策略、连接池热更新和重定向上限。
- 必测：实际协商协议；强制 H1/H2；UNSET × `forceHttp1`；代理断开时不绕过宿主策略直连；DoH 故障退出有界；跨源重定向不泄露 Cookie/Authorization/`Proxy-Authorization`；DNS 慢于读取超时时的取消。

### 4.9 FTP、FTPS 与 SFTP

**aria2（`[S]`）**

- `src/FtpDownloadCommand.cc`、`src/FtpNegotiationCommand.cc`、`src/FtpConnection.cc` 确认 FTP；`src/SftpDownloadCommand.cc`、`src/SftpNegotiationCommand.cc` 确认 SFTP。
- `src/SftpNegotiationCommand.cc` 的 `sshHandshake`、`sshSFTPOpen`、`sshSFTPStat`、`sshSFTPSeek` 证明偏移恢复路径；`--ssh-host-key-md` 见本地手册，说明 host key 校验是可配置项而非默认完成。

**Gopeed（`[S]`；SFTP `[U/N/A*]`）**

- `internal/protocol/ftp/fetcher.go` 明确支持 FTP、FTPS（implicit TLS）、FTPES（AUTH TLS）；`pkg/protocol/ftp/model.go` 定义 `TLSNone/TLSExplicit/TLSImplicit`，分片使用 REST/RETR，连接上限会因服务器拒绝登录而降档。
- 本地源码只看到 `pkg/protocol/ftp`，未找到 SFTP 协议目录或 SSH 依赖入口；SFTP 标 `[U/N/A*]`，不能由 FTP/FTPS 推定。

**Axel（`[S]`；SFTP `[N/A*]`）**

- `src/conn.h` 定义 `PROTO_FTP`、`PROTO_FTPS`、`PROTO_HTTP`、`PROTO_HTTPS`；`src/ftp.c` 实现 `USER/PASS/TYPE I/PASV/SIZE/REST/RETR` 等；`doc/axel.txt` 明确 HTTP/HTTPS/FTP/FTPS。
- 没有 SFTP 源文件或帮助入口；标 `[N/A*]`，不使用 aria2 的 SFTP 作为 Axel 的弱项扣分。

**XDM（`[N/A*]`/`[U]`）**

- 本轮 `xdm-core` 只有 HTTP、HLS/DASH 下载器；`xdm-app/src/test/java/xdm/HeadlessCliTest.kt` 明确把 `ftp://` 作为不支持的 URL 拒绝。`xdm-app/.../UrlHelper.kt` 对 FTP 字符串的识别不足以证明有真实 FTP 下载实现。
- 因此在本冻结样本中不把 XDM 当作 FTP/SFTP 竞品；如果后续更换版本，需重新取证。

**TurboDL 当前任务与必须测的数据**

- 入口：`E03`（旧 `TD-14`）要求 FTP REST/主动被动/FTPS 证书、SFTP host key/认证/偏移恢复分别实现，不能自动接受未知 host key。
- 必测：FTP 主动/被动和 REST 不支持回退；FTPS 证书错误；SFTP host key 不匹配；认证失败；偏移续传；连接被服务器限流；Android 可分发方案和依赖许可证。

### 4.10 BitTorrent 与 Magnet

**aria2（`[S/D]`）**

- `src/bittorrent_helper.cc` 的 `parseMagnet`、`loadMagnet`、`torrent2Magnet` 是 Magnet 入口；`src/DefaultBtInteractive.cc`、`src/DefaultBtAnnounce.cc`、`src/DHT*`、`src/UDPTrackerClient.cc` 等覆盖 peer、tracker、DHT 和 piece。
- `README.rst` 和本地手册声明 Fast extension、DHT、PEX、MSE/PSE、WebSeeding、选择性下载等；这些仍需实际固定 torrent/swarm 验收，不能只凭类名。

**Gopeed（`[S/D]`）**

- `internal/protocol/bt/fetcher.go` 的 `TorrentSpecFromMagnetUri`、tracker/DHT 配置、`PieceStateRuns`、选文件、`doUpload`、`SeedRatio/SeedTime` 是源码入口；`pkg/base/piece_map.go` 是已验证 piece bitset 模型。
- `README.md` 声明 uTP、DHT、Web Seeds、tracker management、peer/piece stats 和 ratio/time seeding；本轮未构建 swarm。

**Axel（`[N/A*]`）**

- 本地 `src/`、`doc/axel.txt` 和 `README.md` 只覆盖 HTTP/HTTPS/FTP/FTPS，没有 BT/Magnet 入口。

**XDM（`[N/A*]`）**

- 本轮 xdm-core 没有 BT/Magnet 协议目录；浏览器扩展和视频抓取不能算 BT/Magnet。

**TurboDL 当前任务与必须测的数据**

- 入口：`E04`（旧 `TD-14`）要求元数据获取、tracker/DHT、piece hash、选文件、优先级、续传、做种/上传预算、取消和版本握手。
- 必测：获授权私有 swarm；无 peer/元数据超时；坏 piece；断点重启；选择性文件；做种比例/时间；Android 生命周期；不能把“启动了 Gopeed/aria2 进程”算成 TurboDL 自有协议完成。

### 4.11 Metalink（及 ed2k 边界）

**aria2（`[S]`）**

- `src/Metalink2RequestGroup.cc`、`src/MetalinkParserController.cc`、`src/MetalinkParserStateV3Impl.cc`、`src/MetalinkParserStateV4Impl.cc`、`src/MetalinkEntry.cc` 是 Metalink v3/v4 入口；`src/Metalink2RequestGroup.cc` 处理资源优先级、整文件 checksum 和 chunk checksum。
- `src/RpcMethodImpl.cc` 有 `aria2.addMetalink`；本地手册也列出 Metalink 版本与资源选择参数。

**Gopeed（`[N/A*]`）**

- 本轮 `pkg/`、`internal/protocol/` 未见 Metalink 解析或请求组入口；不能把 GitHub mirror 候选当成 Metalink。

**Axel（`[N/A*]`）**

- 本地源码/README 未见 Metalink。

**XDM（`[N/A*]`）**

- 本地源码/README 未见 Metalink；HLS/DASH manifest 不是 Metalink。

**TurboDL 当前任务与必须测的数据**

- 入口：`E05`（旧 `TD-14`）把 Metalink 与 ed2k 分开；Metalink 需要文件/镜像/块哈希/优先级/坏源隔离，ed2k 需要文件身份/分块校验/来源发现/恢复。
- 必测：同大小异内容；缺块哈希；坏镜像；优先级冲突；块哈希失败重下；ed2k 链接身份与分块边界；不能把 Metalink 静默转换成普通单 URL 下载而丢失原始约束。

### 4.12 HLS 与 DASH

**aria2（`[N/A*]`）**

- 本地 aria2 源码/手册未找到 HLS m3u8 或 DASH MPD 解析/下载入口；其 Metalink 支持不能被误写成 HLS/DASH。

**Gopeed（`[S]`，HLS VOD；DASH `[N/A*]`）**

- `internal/protocol/hls/doc.go` 明确是原生 HLS：支持 VOD media playlist、TS、单一常量 `EXT-X-MAP` 的 fMP4、AES-128 identity、显式/序列派生 IV、`EXT-X-BYTERANGE`。
- 同一 doc 明确拒绝 live、SAMPLE-AES/DRM、`EXT-X-GAP`、`EXT-X-DISCONTINUITY`、变化 init section、独立音轨；`playlist.go` 的解析和 `fetcher.go` 的 segment 下载/合并路径与文档一致。
- 本地协议目录中没有 DASH；不要因为 `README` 说“媒体”就推定为 DASH。

**Axel（`[N/A*]`）**

- 本地源码是普通 HTTP/HTTPS/FTP/FTPS 文件下载，没有媒体 manifest 或分片序列解析。

**XDM（`[S]`，HLS/DASH VOD；live `[U]`）**

- `xdm-core/.../manifest/hls/HlsParser.kt`、`downloader/hls/HlsDownloaderTask.kt`、`HlsKeyStore.kt` 解析 HLS 分段/ByteRange/AES-128。
- `manifest/dash/MpdParser.kt`、`downloader/dash/DashDownloaderTask.kt`、`TemplateParser.kt` 解析 DASH；`MpdParser.kt` 对 `type="dynamic"` 明确抛错，`SAMPLE-AES` 类加密也明确拒绝。
- HLS live 刷新/滚动窗口在本轮源码中没有确定的完整路径，标 `[U]`；不能把 VOD 下载成功外推为 live。

**TurboDL 当前任务与必须测的数据**

- 入口：`E02`（旧 `TD-12`）已规划 HLS/DASH；建议把它拆成 HLS 和 DASH 两张验收卡，且把 VOD 与 live 分开。
- 必测：HLS 主/媒体列表选择、TS、fMP4/init、`EXT-X-MAP`、ByteRange、AES-128/密钥轮换、断点、音轨、live 滑窗；DASH dynamic/Period/BaseURL/模板/ByteRange/ContentProtection；输出必须做独立容器/时长/轨道/可播放性验证，不能只拼字节。

### 4.13 插件与扩展

**aria2（`[N/A*]`）**

- 本地源码没有通用协议/后端插件加载器；JSON-RPC/XML-RPC 和外部完成脚本属于控制/自动化接口，不是 Gopeed 式 JS 扩展 ABI。

**Gopeed（`[S]`）**

- `pkg/download/extension.go`、`extension_engine.go`、`extension_engine_session.go` 及 `internal/webview/extension-api.d.ts` 是 JS extension、manifest、设置、事件和宿主 API 路径；本地 UI 目录也有 extension store。
- `pkg/download/extension_git_transport.go` 和 `internal/githubmirror` 负责扩展仓库/GitHub mirror 的获取；这同时说明扩展安装和下载器镜像是两个不同层面。

**Axel（`[N/A*]`）**

- 没有插件宿主或稳定对外扩展 ABI；`doc/API` 描述的是把 Axel 代码编进其他程序的内部 C API，不能误当成可分发插件标准。

**XDM（`[S]` 浏览器扩展；通用引擎插件 `[N/A*]`）**

- `browser-extension/chrome-extension/manifest.json`、`browser-extension/firefox-extension/manifest.json` 和 `xdm-app/.../integration/HttpServer.kt` 把浏览器事件送到本机 XDM，包含 loopback 限制、body 上限和超时。
- 但本冻结 `xdm-core` 没有类似 Gopeed 的第三方协议插件加载契约；浏览器扩展是“宿主接入”，不是协议后端扩展。

**TurboDL 当前任务与必须测的数据**

- 入口：`E01`（旧 `TD-13`）负责插件注册/优先级/冲突/卸载/异常/能力授权/秘密作用域，`REL-02` 负责版本和产物。
- 必测：加载/禁用/卸载/升级；版本不匹配；插件异常/超时/CPU 死循环/大输入；网络/存储/连接预算越权；秘密隔离；插件失败不影响其他任务；不能只验证插件“注册成功”。

### 4.14 CLI 与 API

**aria2（`[S]`）**

- `aria2c` CLI 入口和本地手册；`src/RpcMethodImpl.h` 明确 `aria2.addUri`、`aria2.addTorrent`、`aria2.addMetalink`、`aria2.getGlobalStat`、`aria2.saveSession`，`doc/manual-src/en/aria2c.rst` 有 JSON-RPC/XML-RPC 端点。
- 这是完整“任务控制面 + 下载引擎”分离，可作为 API 设计参考，但不能把 RPC 成功等同于下载正确性。

**Gopeed（`[S]`）**

- `cmd/gopeed/flags.go` 有独立命令行下载入口；`cmd/web/flags.go`、`pkg/rest/server.go`、`pkg/api/service.go` 组成 headless/REST API、任务筛选、REST route 和 MCP 入口。
- REST 路由包含 `resolve`、`tasks`、`pause/continue/delete`、`stats`、`config`、`extensions`、`mirrors/{provider}/candidates`；适合对标机器可读控制面。

**Axel（`[S]`）**

- `doc/axel.txt` 是稳定 CLI 入口；`doc/API` 的 C API 要求把源文件编入宿主，且文档明确“尚未转成正式库”，不适合作为长期公开 SDK 契约。

**XDM（`[S]`）**

- `xdm-app/.../headless/HeadlessCli.kt` 和 `CliOptions.kt` 提供 `--headless`/`--no-gui`、HTTP/HLS/DASH、格式选择、分段数、速度限制、代理、cookie/header、退出码和 stdout 路径输出；`HeadlessCliTest.kt` 有解析和格式选择测试。
- `xdm-app/.../integration/HttpServer.kt` 是浏览器扩展专用集成端口，不能自动当成通用外部下载 API；其 headless 临时状态也不提供跨进程续传。

**TurboDL 当前任务与必须测的数据**

- 入口：`O01`（旧 `TD-15`）和 `P02`（旧 `TD-06/15`）要求 CLI 参数、退出码、NDJSON、事件、诊断和 SDK 状态一致；`API_CONTRACTS.md` 要求不把“可解析”当“已消费”。
- 必测：成功/404/429/超时/校验失败/取消退出码；stdout 纯 NDJSON；字段稳定；暂停/恢复/删除的幂等；批处理路径；代理/DNS/TLS 状态诊断；脱敏；如后续增加 REST，需定义任务 ID、状态迁移和版本握手。

### 4.15 资源与长跑

**aria2（`[S/D]`）**

- `src/WrDiskCache.cc`、`src/DefaultDiskWriter.cc` 等提供磁盘缓存/写盘实现；本地手册有 daemon、磁盘缓存、session 和连接/速度参数。
- 本轮没有运行 30 分钟、2 小时或 24 小时 soak；具体 RSS、句柄、线程和文件描述符稳态为 `[U]`。

**Gopeed（`[S]`）**

- `internal/protocol/http/fetcher.go` 的 `connReadBufferSize` 注释直接量化 64 KiB × 256 连接 = 16 MiB/任务；`Downloader` 有 `maxRunning`、任务槽和持久化。
- 这些是资源上界的设计证据，不是本机实测值；长跑 `[U]`。

**Axel（`[S]`）**

- `axel.c`、`conn.h`、`abuf.c` 显示轻量连接/缓冲结构，适合作为小资源基线；没有本地长跑数据，`[U]`。

**XDM（`[S]`）**

- `HttpChunkRetriever.kt` 每分片 256 KiB 缓冲，`CoreConfig.MAX_SEGMENTS = 256`，`HttpDownloader.kt` 有周期 sync 和状态写入；`TaskInfoDB`/`StateSaver` 有文件持久化。
- 没有运行资源测试；“峰值 RSS/句柄/线程是否回落”标 `[U]`。

**TurboDL 当前任务与必须测的数据**

- 入口：`C06`（旧 `TD-09/10/16`）和 `Q01`；`NETWORK_ACCEPTANCE.md` 已规定 30 分钟冒烟、2 小时故障混跑、24 小时 soak。
- 必测：空闲基线、活动增量、峰值、完成后回落；任务槽/连接槽/IO 线程/文件句柄/直接内存；暂停/取消/配置热更新；磁盘慢、磁盘满、只读目录；不能只报一次吞吐。

### 4.16 许可证与复用边界

| 项目 | 本地证据 | 对 TurboDL（MIT）的含义 |
|---|---|---|
| aria2 | `_research/turbodl-upstreams/aria2-src/COPYING` | `GPL-2.0-or-later`；不能把实现源码复制进 MIT core 后仅改类名 |
| Gopeed | `_research/turbodl-upstreams/gopeed-src/LICENSE` | `GPL-3.0`；可以研究协议/架构思路，不能直接拷贝 Go 实现 |
| Axel | `_research/turbodl-upstreams/axel-src/COPYING`、`src/axel.h` | `GPL-2.0-or-later` 带 OpenSSL 例外；例外只针对特定链接条件，不等于任意源码都可搬进 MIT |
| XDM | 顶层 `LICENSE`、`xdm-core/LICENSE`、`xdm-core/NOTICE`、`README.md` | `xdm-core` 为 Apache-2.0，可在保留 LICENSE/NOTICE、标明修改并做归属的前提下参考/复用；`xdm-app` 和浏览器扩展仍为 GPL-3.0 |
| TurboDL | `TurboDL/LICENSE` | MIT；引入 GPL 代码、静态链接 GPL 库或把 GPL 插件当 core 的一部分，都需要单独法律/发布决策 |

许可证不是“代码是否开源”的同义词。对 TurboDL 最安全的三层策略是：

1. 只借鉴行为、状态机、测试模型和接口设计，独立实现。
2. 若确需复用 XDM core，先固定具体文件、NOTICE、修改记录和依赖清单；不要顺手复制 app/扩展。
3. 若需要 aria2/Gopeed/Axel 的 GPL 代码，先把它作为独立进程/独立模块做边界评估，不能直接并入 MIT core。

## 5. 真正值得借鉴的架构点

### 5.1 先用“强校验器 + Range 响应校验”再谈续传

Gopeed 的 `If-Range` 强 ETag/Last-Modified 选择、`Content-Range` 校验，XDM 的 `piecesMakeUpFile` 与“200 响应的整文件拒绝写入偏移”，都说明续传安全不能依赖同一文件长度或局部样本。

适用边界：TurboDL 可以借鉴这个判定顺序，但必须保留“已证明相同 / 已证明不同 / 无法判断”三种结果；弱校验器、短期 CDN 节点差异和无校验器镜像不能被包装成强证明。

许可证：Gopeed 代码 `GPL-3.0`，只借鉴判定顺序；XDM core 可参考其 Apache-2.0 实现，但要保留 NOTICE 和变更说明。

### 5.2 slow start、工作窃取和尾部 ETA 必须共享一个控制权

Gopeed 的 `slowStartController` 与 work stealing 说明：连接数扩容、慢连接重分片和尾部收尾不能由三个线程各自决定。TurboDL `C05` 已提出统一策略所有权，这是正确方向。

适用边界：只在有足够剩余字节、每连接确实限速、服务端允许 Range 时追求扩容；小文件、全局限速、429/503、已知磁盘瓶颈时应停止无收益扩容。

许可证：不能复制 Gopeed 的 Go/Flutter 实现；只使用状态机与验收指标。

### 5.3 “耐久数据先于进度承诺”应成为侧车协议的不变量

XDM 的 `syncAll()` 后才发布完成、只记录 `Chunk.synced`，以及 Gopeed 把任务状态和保存数据分桶持久化，都是可借鉴的耐久性边界。TurboDL 侧车应继续坚持“数据落盘在前、进度承诺在后”。

适用边界：不同平台的 fsync 语义、Android 私有目录、SAF/MediaStore 和整机断电不能混为一谈；`C02` 的 R01-R10 必须分开报告。

许可证：这是通用工程原则，不需要复制任何源码。

### 5.4 重试必须按错误类别和取消语义建模

aria2 的 `max-tries`/`retry-wait`，Gopeed 的递增等待/封顶和 429 暂停扩容，XDM 的 Retry-After/可取消 sleep，都提供了分层思路。值得借鉴的是“错误类别 → 是否重试 → 等待来源 → 是否消耗该分片预算 → 是否暂停扩容”的表，而不是一个全局 `retry` 数值。

适用边界：429/503 必须尊重 Retry-After；401/403/签名过期应先走来源刷新；DNS/TLS/磁盘/校验失败不能一律用重试解决；取消必须能打断等待。

许可证：可独立实现；不要直接复制 Gopeed GPL 代码。

### 5.5 镜像池应把内容身份和凭据作用域绑定到来源

aria2 的 URI 选择器与 Metalink checksum，Gopeed 的 GitHub mirror provider，Axel 文档中“多 URL 但不检查文件相等”，三者共同提醒：多来源不是一个简单数组。

适用边界：TurboDL 的 `MirrorPool` 应把原 URL、解析终址、来源身份、范围校验、强校验器和允许携带的请求头绑定；没有可信内容证明时降低并发或显式报错，不能静默混写。

许可证：aria2/Axel/Gopeed 的实现不可直接搬；可借鉴 API 和数据模型后独立实现。

### 5.6 CLI/API 与协议后端分层，但不把“可扩展”当“已有协议”

aria2 的 RPC、Gopeed 的 REST/headless、XDM 的 headless CLI 证明下载引擎应有稳定的任务控制面和机器可读状态。与此同时，Gopeed 的 JS extension、XDM 的浏览器扩展和 TurboDL 的插件运行时不是同一层能力。

适用边界：TurboDL `O01/P02` 应先把 submit/pause/resume/cancel/shutdown、任务 ID、事件顺序、错误分类和脱敏固定；协议后端再由 `E01-E05` 逐一补齐。不要用“插件可扩展”替代 FTP/BT/Metalink 的可运行后端。

许可证：接口和状态模型可独立设计；第三方代码按各自许可证处理。

## 6. TurboDL 必须新增或调整的任务卡

下列“建议新增”ID 尚未在现有 `ROADMAP.md` 中核准，不能当作既有任务完成；标为“调整”的卡片仍沿用现有 ID。

| 类型/ID | 任务卡 | 调整原因与验收重点 |
|---|---|---|
| 调整 `C01`（旧 `TD-01`） | Range 响应与元数据统一入口 | 增加 `If-Range` 强/弱校验器决策、`Content-Range` 起点/终点/总长、200/206/416、短读/超长、Range 被忽略后的安全单流回退；用非周期文件和独立完整哈希验收 |
| 调整 `C02`（旧 `TD-02/03`） | 续传身份、侧车与耐久边界 | 明确 strong/weak/unknown 三态、数据落盘先于进度承诺、同一 `stableKey` 跨进程锁、`.tmp/.bak` 恢复、断电与强杀分开测；不要把“长度相同”当内容相同 |
| 调整 `C03`（旧 `TD-04/11`） | 镜像池、来源刷新与凭据隔离 | 把“同文件镜像”与 GitHub mirror 候选区分；每源独立退避/终址/身份；敏感头只在允许来源和重定向边界内传播；有预期哈希时必须整文件校验 |
| 新增 `C08（已纳入路线图）` | 重试、Retry-After 与 429/503 状态机 | 建立单卡定义错误分类、最大重试、等待来源、取消、镜像隔离和“不得重试风暴”的可执行测试 |
| 调整 `C05`（旧 `TD-08/09`） | slow start、工作窃取、尾部调度 | 统一“配置上限 / 起始目标 / 实际连接 / 已接受档位”；明确 429/503 暂停扩容、慢连接接管、尾部 ETA 和日志字段；不能用 UI 目标数代替实际 socket/H2 stream |
| 调整 `C06`（旧 `TD-09/10/16`） | 全局预算、限速、公平性与长跑 | 将 `globalSpeedLimitBytesPerSec`、每连接/host/任务上限、暂停/取消归还、IO buffer、文件句柄和 30min/2h/24h soak 放进同一验收卡；限速稳态和短任务突发分开记 |
| 调整 `C07`（旧 `TD-11`） | DNS、TLS、代理和 HTTP 协商 | 明确 HTTP/1.1 与 HTTP/2 各自策略、DoH 不是系统 DNS 的同义词、代理失败不能静默直连；记录实际协商协议、TLS 错误、重定向和连接池热更新 |
| 调整 `E02`（旧 `TD-12`） | HLS 与 DASH 分开验收 | Gopeed 只确认 HLS VOD，XDM 有 HLS/DASH VOD，二者都不能证明 live；采用 `E02-HLS`、`E02-DASH`，VOD/live/fMP4/DRM 分类报告 |
| 调整 `E03`（旧 `TD-14`） | FTP/FTPS/SFTP 独立后端 | `TD-14` 不能作为一个总完成项；分别定义 FTP REST、FTPS 证书、SFTP host key、认证/偏移恢复、Android 分发和许可证 |
| 调整 `E04`（旧 `TD-14`） | BT/Magnet 独立后端 | 单独验收元数据、tracker/DHT、piece hash、选文件、做种/上传预算、取消和进程边界；不与 FTP/Metalink 共用一个完成状态 |
| 调整 `E05`（旧 `TD-14`） | Metalink/ed2k 独立后端 | Metalink 必须有镜像/块哈希/优先级/坏源隔离；ed2k 必须有链接身份、分块校验和来源发现；缺一不能共用 TD-14 完成状态 |
| 调整 `O01/P02`（旧 `TD-15`/`TD-06`） | CLI、API/控制面与公开契约 | 增加机器可读任务 ID、状态迁移、退出码、NDJSON、批处理、诊断脱敏和版本握手；如果新增 REST/RPC，不能仅靠参数解析成功证明可用 |
| 调整 `P00` | 证据台账和许可证快照 | 每个竞品记录二进制/源码版本、commit、构建选项、许可证和 `[S]/[D]/[U]` 等级；竞品升级或本地样本变化时，旧结论必须重新标定 |
| 调整 `R01` | 发布时的第三方归因 | 若参考或复用 XDM core，保留 Apache LICENSE/NOTICE 和修改记录；不把 GPL app/extension 或 GPL 竞品源码复制进 MIT core |

## 7. 不具可比性的能力

- **aria2 的 BT/Magnet/Metalink/SFTP 原生协议**：它们是完整原生协议栈，不应与 TurboDL 尚未实现的插件后端按同一条能力行打分；只有 TurboDL 完成 `E03/E04/E05` 后才做同协议、同授权来源的验收。
- **Gopeed 的 GitHub mirror candidate**：这不是通用的任意同内容多 URL MirrorPool，也没有通用镜像池；不能用它给 Gopeed 记“多来源已覆盖”，也不能拿它替代 aria2 Metalink 作为 TurboDL C03 的强基线。
- **Axel 的 filesearching.com 镜像搜索**：它依赖外部搜索服务，且本地文档明确不校验文件相同；只能作为“多 URL 轮询/固定分片”的行为参考，不能作为安全多来源的对照。
- **XDM 的浏览器扩展、视频抓取和媒体封装**：浏览器扩展是宿主接入和站点事件采集，不是 xdm-core 的协议插件；HLS/DASH mux/媒体容器测试也不能与 HTTP Range 吞吐混在一个指标里。
- **XDM headless 与 GUI 续传**：headless 明确使用临时目录并在结束后删除，中断后设计为下次从头开始；GUI/核心的 `.state` 恢复不能自动记到 headless CLI 上。
- **Gopeed HLS 与 XDM HLS/DASH**：Gopeed 当前源码文档明确拒绝 HLS live，XDM DASH 明确拒绝 dynamic；把 VOD 结果外推为 live 或把 HLS 结果外推为 DASH 都不可比。
- **BT 的 DHT/tracker/seed 生态与 HTTP 并发**：两者资源模型、合法性和时间尺度不同；BT 需要私有授权 swarm、peer 数量和做种策略，不能用普通 HTTP 的“更多连接更快”指标评价。
- **未运行的长跑与资源数字**：本轮没有竞品 benchmark 或 soak 结果；所有 RSS、句柄、线程、socket、CPU 和 24h 结论保留为 `[U]`，不得用代码注释代替实测。
- **GPL/Apache 之外的界面和生态**：aria2/Axel/Gopeed 的 GPL 源码、XDM app/浏览器扩展的 GPL 代码不可直接搬进 MIT core；能力和接口可独立实现，许可证风险不能由“只是参考”自动消除。

## 8. 结论摘要

1. **最成熟的参考是协议边界，而不是复制实现。** aria2 提供最广的 HTTP/FTP/SFTP/BT/Metalink/校验/会话组合，Gopeed 提供现代化的 HTTP slow start/工作窃取和 JS/REST 控制面，XDM core 提供 Apache-2.0 范围内可研究的 Range/状态/HLS/DASH 代码，Axel 适合作为固定分片、FTP/FTPS 和低资源基线。
2. **TurboDL 的真正风险集中在一致性，而不是“有没有更多连接”。** 强校验器/Range 校验、侧车耐久顺序、镜像内容身份、Retry-After、代理/重定向凭据边界和全局资源预算都必须先成为可执行契约。
3. **当前 `TD-14` 不应被当作一个可完成的布尔项。** FTP/FTPS/SFTP、BT/Magnet、Metalink/ed2k 的证据和许可证边界完全不同，应按 `E03/E04/E05` 分开验收。
4. **必须补一个显式的重试/退避状态卡。** 现有计划和源码都有零散重试路径，但缺少统一的错误分类、Retry-After、取消、镜像隔离和重试预算契约；已纳入 `C08`。
5. **本轮没有运行任何 benchmark。** 所有性能、长跑、跨 CDN 和真实 BT/HLS/DASH 结论都不是已验证结果；正式对比仍按 `NETWORK_ACCEPTANCE.md` 的同条件方法和完整证据执行。

