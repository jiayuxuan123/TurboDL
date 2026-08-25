plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    // 引导模块：便利封装，依赖 runtime 与 core。绝非强制依赖——用户可完全不引入本模块，
    // 手动编排全部插件加载顺序。
    api(project(":turbodl-core"))
    api(project(":turbo-plugin-runtime"))

    testImplementation(kotlin("test"))
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
}
