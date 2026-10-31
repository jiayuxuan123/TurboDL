plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "dev.turbodl"
    // 0.2.1.0：P12/P14/P13 三件引擎侧评估与收敛。
    //   P12：分片写盘是否加缓冲 —— 实测无收益（差异 0.3%，噪声内），保持直写；
    //        新增 bufferedSegmentWrite 开关（默认 false）供更快链路复测。
    //   P14：HTTP 版本策略验证 —— 实测**订正了旧注释的错误结论**：
    //        h2 确实塌缩连接（32→2 条），但并不更慢（6.79 vs 2.84 MB/s），
    //        真变量是该 CDN 惩罚高并发（单连接基线 6.16 MB/s）。默认值仍保持 h1，
    //        但理由改为"结论不能外推"，而非"h2 更慢"。协议决策固化为断言。
    //   P13：预算摊薄算法收敛为唯一实现（budgetedIoBufferSize），
    //        App 侧兜底引擎删掉副本改为调用，避免两份实现漂移（OOM 是本项目踩过的坑）。
    //   次要版本号递增（0.2.0.9 → 0.2.1.0）：对外新增了公开 API 与配置字段。
    //   0.2.3.0：网络健壮性一批（UA 协商、失败收敛、错误归因）；
    //        详见 _audit/网络缺陷_UA拦截与故障注入.md。
    //   0.2.3.1：修两个"开关组合起来才发作"的缺陷：
    //        overlapMerge + tailAssist 同时开启会写坏文件（长度对、SHA-256 不符）；
    //        httpVersionPolicy=AUTO 被 forceHttp1 反向覆盖，导致"改成 AUTO"实际
    //        变成了 FORCE_HTTP2。另补 ConfigConflictTest 钉住这些组合。
    //   0.2.6.0：在 0.2.5.0 的直写基础上继续收口（与 aria2 1.37 实测对比驱动）：
    //        · 每写入线程独立 FileChannel —— 共用通道会让 16 条分片的写入**串行排队**
    //          （实测写入耗时是读取的 20~30 倍，聚合吞吐被锁死在单通道速度）；
    //        · DNS 带 TTL 的缓存（含负缓存）—— 移动网络一次解析 50~200ms，几十次连接就是几秒；
    //        · 卡死守护改为**只掐不动的那一条连接**，不再 cancel 整个任务的全部请求；
    //        · 爬升间隔 250ms → 100ms，慢启动到顶时间从 500ms 降到 200ms（保留逐档保护形态）。
    //        实测：回环 256MB/16 连接 141→165~200 MB/s（aria2 1.37 为 188）；
    //        真实网络 33MB：aria2 46.2s vs 本引擎 28.5s（1.62 倍）。
    //   0.2.5.0：**吞吐结构性重做** —— 分片不再各写一个临时文件再合并，改为按偏移直写目标文件。
    //        实测（16MB、同一块盘、去掉 fsync）：512 片时旧结构"创建 512 个文件"要 627ms，
    //        直写只要 11ms（57 倍）；回环 128 连接吞吐 20 MB/s → 88 MB/s（4.4 倍），
    //        且不再随连接数上升而下降。真实网络（33MB、GitHub CDN）：45.5s → 25s。
    //        同时修掉三处会让并发永久卡死的交互（背压 → 天花板 → 自适应恢复互相挡路），
    //        并支持服务器 Retry-After、复用读缓冲、删除已失效的 OverlapMerger。
    //   0.2.4.0：新增可选模块 turbo-plugin-parser —— 视频平台链接解析（微博/小红书/Pixiv）
    //        做成**引擎级原生插件**（与 turbo-plugin-hls 同类，不是宿主内置）。
    //        它只往 LINK_PARSER 注册一个解析器、往 PROTOCOL_HANDLER 每个平台声明一条，
    //        core 与 runtime 一行未改。
    //   0.2.7.0：
    //        修复：某条连接耗尽重试后，被顺带中断的其他连接不再计入自己的重试次数；
    //              失败提示改为指向真正出错那条连接的原因。
    //        优化：连接失效判定 60 秒 → 30 秒，且只对"已收到数据后停止推进"的连接生效；
    //              继续下载直接按设定连接数开始；域名解析结果短时缓存；
    //              按服务器给出的重试等待时间重试。
    //        移除：预先合并、分片写缓冲两个开关（对应机制已不存在）。
    //        实测：本机 32MB、32/128 连接各连跑 6 次，173~180 / 160~172 MB/s；
    //              真实网络 6MB 经代理 3.98 秒。
    //   0.2.8.0：
    //        并发：连接数改为“吞吐上限”而不是开跑即打满；从 8 路开始，只有实测
    //              吞吐再提升 30% 才翻倍。服务端聚合封顶时自动收敛，每连接限速时
    //              继续按需扩到 64/128/256 路。
    //        调度：worker 与分片网格都按实际并发懒创建，请求 256 路但适配在 8 路时
    //              不再创建 256 个空转协程或上千个无效小分片。
    //        写盘：并行下载前一次预留逻辑文件长度，避免 128/256 条连接反复争抢
    //              NTFS 扩容元数据锁。
    //        实测：6.4MB 回环 229ms → 154~170ms（约 1.35 倍）；本地无限速下
    //              8~256 路不再随连接数上升而退化；每连接 100KB/s 时限速源
    //              64 路约 3.05MB/s，16 路约 1.38MB/s（2.2 倍）。
    //   0.3.0.0：
    //        正确性：修复目标文件被删/强校验器下的空洞续传、收尾托管边界写入、
    //              预热误读整文件、暂停后 await 旧结果、任务槽超发、完成 Job 泄漏；
    //              侧车原子替换并保留 .bak 恢复点。
    //        资源：新增 maxTotalConnections 全局连接预算、公平 FIFO 任务槽、
    //              实际并发懒启动、按任务归还连接。
    //        吞吐：按 ETA 选择收尾慢分片；重试加入 ±25% jitter；直接 ByteBuffer
    //              写入桌面 JVM，Android/Dalvik 自动回落堆缓冲。
    //        实测：100KB/s 每连接源，8→16→32 路为 0.80→1.44→2.66MB/s；
    //              64/128/256 路在聚合天花板处稳定约 3.0MB/s，不继续堆连接。
    version = "0.3.0.0"
}

// 为可作为 SDK 发布的库模块统一启用 maven-publish。
//
// ## 为什么有两个坐标（这是发 JitPack 时踩到的实际问题）
//
// `dev.turbodl:*` 是**内部坐标**：YunGet 自己与本地开发都靠它，坐标里带版本号后缀，
// 变更可控。JitPack 却**只认 `com.github.<owner>:<repo>:<module>`**
// —— 它按仓库坐标校验产物。原先只有 `dev.turbodl`，于是 JitPack 构建出来的包
// 下游根本 `implementation` 不到（找不到对应坐标）。
//
// 所以这里同时发布两套：内部继续用 `dev.turbodl`（不变，避免影响已有构建），
// 另加一套 JitPack 坐标供外部开发者使用。
//
// CLI / demo 是应用示例，不发布。
subprojects {
    val publishable = setOf(
        "turbodl-core",
        "turbo-plugin-runtime",
        "turbo-plugin-bootstrap",
        "turbo-plugin-hls",
        "turbo-plugin-js",
        "turbo-plugin-parser",
    )
    if (name in publishable) {
        apply(plugin = "maven-publish")
        // java-library 已提供 `java` 组件；等其配置完成后再挂载发布组件。
        afterEvaluate {
            extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
                publications {
                    // 内部坐标：dev.turbodl:<module>:<version>
                    create<org.gradle.api.publish.maven.MavenPublication>("maven") {
                        from(components["java"])
                        artifactId = this@subprojects.name
                    }
                }
            }
        }

    }
}
