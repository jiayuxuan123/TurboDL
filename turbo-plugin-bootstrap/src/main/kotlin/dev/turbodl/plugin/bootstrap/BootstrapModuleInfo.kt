package dev.turbodl.plugin.bootstrap

/**
 * turbo-plugin-bootstrap —— 可选引导便利模块（阶段 0 骨架）。
 *
 * 定位：封装「一键加载基础必备插件（Kotlin 插件加载器、HTTP 下载后端）」，供普通用户开箱即用。
 * 非强制依赖——高级用户可完全不使用本模块，手动编排全部插件加载顺序。
 *
 * 阶段 0 仅建立模块骨架；一键装载逻辑将在阶段 3 实现。
 */
internal object BootstrapModuleInfo {
    const val NAME = "turbo-plugin-bootstrap"
    const val STAGE = "scaffold-0"
}
