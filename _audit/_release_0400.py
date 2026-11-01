import os, sys, subprocess
from pathlib import Path

VERSION = "0.4.0.0"
M2 = Path.home() / ".m2" / "repository" / "dev" / "turbodl"
MODULES = ["turbodl-core", "turbo-plugin-runtime", "turbo-plugin-js",
           "turbo-plugin-hls", "turbo-plugin-bootstrap", "turbo-plugin-parser"]

files = []
for m in MODULES:
    d = M2 / m / VERSION
    for ext in ("jar", "module", "pom"):
        f = d / f"{m}-{VERSION}.{ext}"
        if not f.exists():
            print("Missing:", f); sys.exit(1)
        files.append(str(f))

cli = Path("turbodl-cli/build/libs/turbodl-cli-all.jar").resolve()
zipf = Path(f"build/TurboDL-v{VERSION}-mavenLocal.zip").resolve()
for f in (cli, zipf):
    if not f.exists():
        print("Missing:", f); sys.exit(1)
files += [str(cli), str(zipf)]
print("assets:", len(files))

notes = """TurboDL 0.4.0.0 —— 正确性大修 + 多镜像来源

这一版以修 bug 为主：内部审计翻出 13 个真实缺陷，其中 3 个会让输出文件静默损坏。

【P0：会造成静默数据损坏】
1. 续传取证：强校验器已变、但内容指纹取不到时，不再复用旧分片（否则会拼出新旧混合、长度校验却通过的文件）。
2. 整文件回退：写入前强制截断目标文件（旧文件尾部不再被保留；窗口下 delete() 失败也不影响）。
3. 进度侧车：persist 串行化 + 完整网格校验 + reset 同时清理 .tmp/.bak（并发写不再产生混合快照）。

【P1：真实会踩到的坑】
4. 「全局限速」现在是全进程一个令牌桶（之前是每任务一个，3 任务 = 3 倍）。
5. resume() 暂停时完成 Deferred、失败时落 FAILED（之前 await() 会永久挂起）。
6. 背压可以降到 1 路（服务器只允许 1–3 条连接时不再必然失败）。
7. 自适应降档不再永久压低天花板（网络变好后能重新上探 16/32）。
8. DNS 解析加硬超时（connectTimeout / readTimeout 都不覆盖 DNS，之前可能无限挂起）。
9. 206 缺 Content-Range 直接拒绝（之前会按请求偏移写入可能来自别处的字节）。
10. 预热改到拿到全局连接预算之后（不再绕过 maxTotalConnections）。
11. 限速器每 400ms 重读配置（运行中改限速立即生效）。
12. 只有 429/503 算限流；其它 5xx 走普通失败（不再误降并发、不再报「持续被限流」）。

【新能力：多镜像来源】
- `DownloadRequest.mirrors`：同一文件的其它 URL。
- 引擎先给每个来源各跑一段实测速度，之后按 EWMA 速度加权派发分片；快的多派、慢的少派。
- 某个来源连续失败进入指数退避（0.5s → 30s 封顶），分片自动切到其它来源。
- 硬校验：镜像返回的 Content-Range 总长与任务总长不一致时，该来源直接判失败并被拉黑——不同长度的镜像绝不会写进目标文件。
- CLI：`--mirror <URL>`（可重复）。

【测试】
- 核心 213 项测试全绿。
- 新增 8 项回归测试（RegressionFixesTest）+ 2 项端到端镜像测试（MirrorPoolTest）。

【兼容性】
- 公开 API 只新增了 `DownloadRequest.mirrors`（有默认值，旧代码无需改动）。
- `maxConnectionsPerHost`、插件、CLI 参数语义均未破坏。
"""

cmd = ["gh", "release", "create", f"v{VERSION}", *files,
       "--title", f"TurboDL {VERSION}：13 项正确性修复 + 多镜像来源",
       "--notes", notes, "--repo", "jiayuxuan123/TurboDL"]
ret = subprocess.run(cmd, env=os.environ.copy())
sys.exit(ret.returncode)
