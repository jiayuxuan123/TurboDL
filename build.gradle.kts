plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "dev.turbodl"
    version = "0.2.0-rc6"
}

// 为可作为 SDK 发布的库模块统一启用 maven-publish（发布到 mavenLocal 供 YunGet 等下游按坐标依赖）。
// CLI / demo 是应用示例，不发布。
subprojects {
    val publishable = setOf(
        "turbodl-core",
        "turbo-plugin-runtime",
        "turbo-plugin-bootstrap",
        "turbo-plugin-hls",
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
