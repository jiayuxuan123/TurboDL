plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    // 与 hls 插件同样的定位：只依赖 core 的公共契约（含 okhttp），不碰宿主。
    api(project(":turbodl-core"))
    api(project(":turbo-plugin-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 端到端注册测试要装进真的 TurboBootstrap，并确认与 HLS 插件共存
    testImplementation(project(":turbo-plugin-bootstrap"))
    testImplementation(project(":turbo-plugin-hls"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
