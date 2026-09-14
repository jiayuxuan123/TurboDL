plugins {
    kotlin("jvm")
    application
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    implementation(project(":turbodl-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
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

application {
    mainClass.set("dev.turbodl.cli.MainKt")
}

/**
 * 【根治版本号漂移】把根 `build.gradle.kts` 的 `version` 生成为 Kotlin 常量。
 *
 * 此前 `Main.kt` 里硬编码 `private const val VERSION = "0.2.0-rc11"`，
 * 而 Gradle 的 `version` 一直在往前跑（rc12/rc13/rc14）——
 * 结果 `turbodl-cli --version` **从 rc11 起就一直在撒谎**，且没人发现。
 *
 * 这类"同一个事实写两遍"的字段必然漂移。现在版本只有一个来源（Gradle 的 `version`），
 * 编译期注入，改不动也不会忘。
 */
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/turbodl")
    val v = project.version.toString()
    outputs.dir(outDir)
    inputs.property("version", v)
    doLast {
        val f = outDir.get().file("BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("package dev.turbodl.cli")
                appendLine()
                appendLine("/**")
                appendLine(" * 构建自动生成，**请勿手改**。")
                appendLine(" *")
                appendLine(" * 版本唯一来源：根 `build.gradle.kts` 的 `version`。")
                appendLine(" * 改版本只需改那一处，这里会自动跟随（见 turbodl-cli/build.gradle.kts 的 generateBuildInfo）。")
                appendLine(" */")
                appendLine("internal const val BUILD_VERSION: String = \"$v\"")
                appendLine()
            }
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/turbodl"))
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

/** 打包全部运行时依赖的单文件可执行 JAR（含 Main-Class），便于 Agent/脚本直接 java -jar 调用。 */
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("turbodl-cli")
    archiveClassifier.set("all")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.turbodl.cli.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}
