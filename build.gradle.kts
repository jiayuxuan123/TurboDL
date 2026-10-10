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
    version = "0.2.1.0"
}

// 为可作为 SDK 发布的库模块统一启用 maven-publish（发布到 mavenLocal 供 YunGet 等下游按坐标依赖）。
// CLI / demo 是应用示例，不发布。
subprojects {
    val publishable = setOf(
        "turbodl-core",
        "turbo-plugin-runtime",
        "turbo-plugin-bootstrap",
        "turbo-plugin-hls",
        "turbo-plugin-js",
    )
    if (name in publishable) {
        apply(plugin = "maven-publish")
        // java-library 已提供 `java` 组件；等其配置完成后再挂载发布组件。
        afterEvaluate {
            extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
                publications {
                    create<org.gradle.api.publish.maven.MavenPublication>("maven") {
                        from(components["java"])
                        // 坐标：dev.turbodl:<module>:0.1.0
                        artifactId = this@subprojects.name
                    }
                }
            }
        }
    }
}
