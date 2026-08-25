rootProject.name = "TurboDL"

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

include(":turbodl-core")
include(":turbodl-cli")

// 插件框架（可选，阶段 0 骨架）：core 不依赖以下任何模块。
include(":turbo-plugin-runtime")
include(":turbo-plugin-bootstrap")
include(":demo")
