# TurboDL 发布候选全量门禁

日期：2026-11-05。本文件只定义 **release candidate** 门禁。日常开发仍只跑受影响的
定向测试；有人准备提交、打 tag、建 Release 或上传附件时，必须一次跑完本节全部项目。

## 1. 进入条件

- `git status --short -uall` 已逐项审阅，未误带敏感文件、运行锁、下载内容或临时截图。
- 版本号已提升；不得覆盖已发布版本。
- 工作树、发布附件和文档指向同一个 commit/binary。
- 公网账本仍有足够余额；没有新建空账本绕过 8 GiB 总量。

## 2. 构建与静态检查

工作区 `C:\Users\38476\Desktop\yunluo\TurboDL`，PowerShell：

```powershell
git -c core.whitespace=cr-at-eol diff --check
.\gradlew.bat :turbodl-cli:fatJar --rerun-tasks --no-daemon
python website\build.py
python website\check_links.py
python website\audit_pages.py
```

## 3. 全量自动化回归

必须重新生成所有测试结果，防止把旧 XML 当成当前通过。可使用 `--rerun-tasks`；也可先执行本节所有模块的 `cleanTest` 再执行全量 `test`，保留增量编译以减少无关重建。最终仍须逐项核对 XML 与构件哈希：

```powershell
.\gradlew.bat `
  :turbodl-core:test `
  :turbodl-cli:test `
  :turbo-plugin-hls:test `
  :turbo-plugin-js:test `
  :turbo-plugin-parser:test `
  :turbo-plugin-bootstrap:test `
  :turbo-plugin-runtime:test `
  --rerun-tasks --no-daemon
```

然后从 `build/test-results/test/TEST-*.xml` 汇总实际 suites/tests/failures/skipped；
不能只引用控制台最后一行。

Python 实验与恢复工具：

```powershell
Set-Location _bench\industrial
python -m unittest -v test_run_engine_compare.py test_traffic_budget.py test_restart_resume.py
```

## 4. 产物与兼容性

- fat JAR 可执行；`--help`、`--version`、`--json`、`--timing`、`--known-size` 正常。
- 全部 Maven 模块完成 `publishToMavenLocal`。
- 旧 8 参数 `DownloadRequest` JVM 构造器仍存在；核心二进制兼容测试通过。
- CLI 55 个解析标记全部识别，缺值/非法值/退出码符合合同。
- 发布附件数量与清单一致；每个 JAR/module/pom/ZIP 都有 SHA-256。

## 5. 真实网络与断点

至少保留以下同条件证据：

- 单流、c8、c16 以及最高支持档的 TurboDL/aria2/curl 对照，失败样本不删除。
- 同一对象、同代理、逐引擎 prewarm；记录 CDN 缓存状态和实际响应字节。
- 独立 JVM 强杀后重启，弱/强校验器、侧车覆盖、最终 SHA-256 全部正确。
- 断网/403/签名过期只保留有效断点，不静默假成功。
- 真实网络矩阵前后都通过 `traffic_budget.py` 对账，累计不超过 8 GiB。

## 6. YunGet 消费者门禁

在 `C:\Users\38476\Desktop\yunluo\YunGet`：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:assembleRelease :app:verifyApkVersion --no-daemon
```

- app/host 的 TurboDL 依赖版本一致。
- universal 桥接包优先，四个 ABI APK 的 `versionCode/versionName` 正确。
- 上传 updater 选择逻辑、旧版桥接、SHA-256 和安装路径已在测试覆盖。
- 真机至少验证后台下载、通知、暂停/继续、进程被杀、存储不足和升级。

## 7. 发布结论

只有同时满足以下条件才能写 `RELEASE READY`：

1. 第 2–6 节全部 PASS；失败项必须修复，不能用“旧版本也这样”解释。
2. 全量测试 XML 数字与证据文件一致。
3. fat JAR 和 APK 的 SHA-256 与待上传附件一致。
4. 未解决风险只允许具名 `BLOCKED`，且不影响已声明支持范围。
5. 用户明确授权提交、推送和创建 Release；未授权时止于本地 release candidate。

发布记录格式：

```text
Status: RELEASE CANDIDATE / RELEASE READY / BLOCKED
Base: commit + version
Automated: suites/tests/failures/skipped per module
Network: run IDs + traffic remaining
Breakpoint: kill/restart evidence
Consumers: YunGet app/host versions + APK hashes
Artifacts: count + SHA-256
Unresolved: risk IDs with exact scope
Authorization: pending / granted
```
