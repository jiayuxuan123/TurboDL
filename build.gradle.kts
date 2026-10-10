plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "dev.turbodl"
    // 0.2.0.9：新增基于任务总吞吐的并发收敛（TurboConfig.adaptiveConcurrency，默认开）。
    //   在慢启动爬到设定值之后，用「同档稳定窗口的总吞吐」判断多开的连接是否真有收益：
    //   没有收益就回到更低档（下限 4，连接数同时是应对链路不均的余量），
    //   并在稳定一段时间后重新测量，避免锁死在某一档。429/503 背压仍优先。
    //   固定并发诊断台（ConnectionSweepTest）显式关闭它，以保留"分离服务端限速模型"的能力。
    version = "0.2.0.9"
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
