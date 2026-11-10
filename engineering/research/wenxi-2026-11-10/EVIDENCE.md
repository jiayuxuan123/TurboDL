# 原始证据和重现命令

## 固定来源

- 仓库：`https://github.com/z7786/wenxi`
- 提交：`28e7b4576f5c3a600a44240967c03e21ef579a1f`。
- 官方分支核对：`GET https://api.github.com/repos/z7786/wenxi/branches/main`，返回同一提交；未重新下载完整源码。
- 原始源码只读副本：`C:\Users\38476\Desktop\yunluo\_research\wenxi-reference\28e7b457\source`。
- 源码归档及原始 helper：`D:\CodexResearch\wenxi-28e7b457-20261110`。
- 深度研究原始日志：该目录下的 `review`。
- 下载 A/B 原始数据与冻结 JAR：`D:\CodexResearch\turbodl-maintenance-20261110`。
- 原始 helper SHA-256：`00d47313c2d9042b2a09921e0c6dc7436c610f71676409096bd6ea41137fe9f3`。

源码矩阵共1026个所选源码/测试/文档文件、323528行；应用 Dart 为379文件。索引不包含整份第三方 vendor，也不把结构索引标成完整实现审查。

## 原始内核构建

以下命令在文析只读源码的 `native/gopeed` 中执行，退出码0；输出留在D盘。Go没有改写其源码。

```powershell
$env:GOTOOLCHAIN='local'
$env:CGO_ENABLED='0'
$env:GOMAXPROCS='4'
$env:GOTMPDIR='D:\CodexResearch\wenxi-28e7b457-20261110\go-tmp'
& 'D:\env\go\bin\go.exe' build -p 4 -mod=vendor -trimpath `
  -o 'D:\CodexResearch\wenxi-28e7b457-20261110\wenxi-helper.exe' ./cmd/asterlink
```

工具链实际为 Go1.25.7；上游固定工具链声明为1.24.7，本轮不冒充严格按该旧版本重建的官方发行包。

## 所选原项目回归

在同一 `native/gopeed` 目录，保持上述环境：

```powershell
go test -p 4 -mod=vendor -json -count=1 -timeout=90s `
  -run '^Test(SlowDownloadTailKeepsIdleWorkers|SlowTailPauseAndRestore|FastDownloadTailDoesNotAddRequests|QuarkConnectionBudget|SmallFileConnectionBudget|RetryAfterAndCancellableCooldown|ConnectionBudgetCancellationReleasesWaiter|XunleiRangesStartBeforeFirstResponseHeaders|XunleiOverloadCountsExistingRequestsAndCancelsWaiters)$' `
  ./internal/protocol/http

go test -p 4 -mod=vendor -json -count=1 -timeout=90s `
  -run '^Test(MissingAndTruncatedCacheRestartFromZero|ImmediatePauseAndReplacementCannotReceiveOldEvents|UcCookieResumeAfterRestartUsesFreshHeadersAndSavedRanges|UnknownLengthAndEmptyFiles)$' `
  ./bind/asterlink
```

实际退出码均0：

| 组 | 顶层测试 | 其中额外子用例 | 失败事件 | 跳过事件 | Go记录的测试执行时间 |
| --- | ---: | ---: | ---: | ---: | ---: |
| HTTP | 9 | 4 | 0 | 0 | 6.633秒 |
| 恢复/绑定 | 4 | 4 | 0 | 0 | 5.726秒 |

顶层和子用例分开记录，不相加伪称独立套件数；编译时间不算在表内。未执行公开网络源测试、BT/ED2K负载测试或整个项目全量。

## 专项实验

两个脚本均独立编写，只使用自己的回环测试服务和测试字节。可重现副本随报告保存：

```powershell
python engineering/research/wenxi-2026-11-10/probes/check_queue.py `
  --evidence-root 'D:\CodexResearch\wenxi-28e7b457-20261110\review'
python engineering/research/wenxi-2026-11-10/probes/check_header_forwarding.py `
  --evidence-root 'D:\CodexResearch\wenxi-28e7b457-20261110\review'
```

- 队列：4任务提交、3任务有活动连接，释放响应体后4任务均完成，独立SHA-256正确。`queue-result.json` 保存当时快照。
- 头隔离：两个不同主机名的本地服务；只记录虚构头是否出现，不记录任何真实凭据。前两次302后Authorization/Cookie缺失，复用缓存终址后重新出现；每次的Range和布尔观察保存在 `header-result.json`。
- 两项退出码0，测试进程及服务已退出，载荷目录已清理。

## A/B 和竞品对照

在 TurboDL 中使用独立fixture，客户端和服务端均为本机：

```powershell
node _bench/maintenance/download_profiles.mjs 8845
python _bench/maintenance/compare_ranges.py --mode ab `
  --output 'D:\CodexResearch\turbodl-maintenance-20261110\range-ab-NEW'
python _bench/maintenance/compare_ranges.py --mode wenxi `
  --output 'D:\CodexResearch\turbodl-maintenance-20261110\wenxi-compare-NEW'
```

历史原始目录是 `range-ab-final` 和 `wenxi-compare-final`，运行均退出0。每次重现应使用新输出目录，不能覆盖旧证据。脚本默认的CLI/核心路径应与对应manifest中的SHA一致；冻结CLI另存 `baseline/turbodl-cli-base-all.jar`。

- AB：4场景×9组×2侧=72个测量，另8个预热。
- 竞品：2场景×9组×2引擎=36个测量，另4个预热。
- 120次最终传输全部独立SHA-256匹配；载荷逐次删除。
- 每轮原始记录同时保留 `engine_ms`、`wall_ms`、CPU、峰值RSS、线程、服务端请求数/活动处理器峰值和阶段事件。
- `wall_ms`包括进程初始化/退出；CPU/RSS是整个子进程采样，不能当稳定态传输成本。服务端handler数也不等于精确socket数。
- 最终竞品比较在17:09:47结束；上游HTTP回归17:14:01才开始，两者未并行争抢CPU。
- 超过CV门限的场景已标记 `INCONCLUSIVE`，即使中位数更快也不包装成稳定倍数。

本轮无公网载荷下载。现有8GiB账本保留，核对时余额为674711456字节、无未结算预留；GitHub元数据查询仅用于确认版本。没有新建账本。

## 本地 TurboDL / 云取验证

新的两个缺陷先由真实HTTP路径的回归触发失败：首响应门未及时释放、小尾段未实际接管。修复后相关HTTP、重定向隔离、镜像、实际并发及尾段/恢复7套件共20项通过；另有空闲连接策略2项通过。日志和XML保留在本批 `_bench/results/maintenance-20261110/test-evidence`。

- HLS：完整插件6套件57项通过，0失败/错误/跳过。
- 云取本轮aria2参数/readout：2套件8项通过，0失败/错误/跳过；此前同批更广的23项结果见应用记录，重复执行不累加。
- 本轮没有把这些定向结果当成发布全量门禁，也没有据此改版本号或覆盖稳定Release。

本地CLI候选已重新构建并验证ZIP完整性、34个调度/下载器class与当前core一致，`--version`可执行。文件：`D:\CodexResearch\turbodl-maintenance-20261110\TurboDL-maintenance-candidate.jar`，4929774字节，SHA-256为 `90669d08caa5c36fa657315d6ce475f9247446998feaf9597e7e4b342d6b6cb5`。该候选尚未升正式版本，不替换线上0.5.0.1。

## Flutter与其他平台未验证项

复制Dart、测试、资源和插件到D盘隔离副本后执行：

```powershell
& 'D:\env\flutter\bin\flutter.bat' pub get --offline
```

退出69，缺少缓存中的 `pinyin`，未解析出完整锁定依赖，因此没有执行Flutter测试。没有删依赖绕过，也没有为本轮研究下载新框架。原始错误日志 `flutter-offline-resolve.log` 的摘要保存在 `EVIDENCE.json`。

Android AAR/APK、真机后台、macOS/iOS签名、BT/ED2K公网负载、第三方取链服务均没有在本轮运行。平台矩阵只据实际入口和工作流区分源码存在与运行成功。

## 重建矩阵

```powershell
python engineering/research/wenxi-2026-11-10/collect_evidence.py `
  --source 'C:\Users\38476\Desktop\yunluo\_research\wenxi-reference\28e7b457\source' `
  --evidence 'D:\CodexResearch\wenxi-28e7b457-20261110\review' `
  --bench 'D:\CodexResearch\turbodl-maintenance-20261110'
```

该脚本核对所有矩阵引用的文件，生成源码哈希/来源链接，收集原始测试和基准统计；不读账号数据，不修改参考项目或生产API。
