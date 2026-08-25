plugins {
    kotlin("jvm")
    application
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    // demo 演示如何使用框架，不侵入任何核心源码。
    implementation(project(":turbodl-core"))
    implementation(project(":turbo-plugin-runtime"))
    implementation(project(":turbo-plugin-bootstrap"))
    implementation(project(":turbo-plugin-hls"))
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
    // Entry point dispatches to one of the three examples by name.
    mainClass.set("dev.turbodl.demo.DemoAppKt")
}
