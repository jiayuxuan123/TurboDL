plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    // 系统插件（loader 类）：与 turbo-plugin-hls 同一层级，只依赖 core/runtime 的公开契约。
    api(project(":turbodl-core"))
    api(project(":turbo-plugin-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // QuickJS 引擎绑定（JNI，自带 linux/mac/windows 原生库）。
    // 必须是 implementation：JS 引擎是本模块的实现细节，绝不泄漏到消费者的编译期 API。
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.15")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // quickjs-kt 1.0.15 由 Kotlin 2.4.x 编译（metadata 2.4.0），本仓库工具链是 2.0.21。
        // 已实测：跳过元数据版本检查后编译与运行行为均正确（evaluate / 绑定 / 超时 / 内存上限
        // 全部按预期工作）。用模块级标志换取「不动全仓库工具链」——升级到 2.4.x 会强制下游
        // （YunGet，Kotlin 2.1.0）一起升级，代价远大于本标志的收益。
        freeCompilerArgs.add("-Xskip-metadata-version-check")
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
