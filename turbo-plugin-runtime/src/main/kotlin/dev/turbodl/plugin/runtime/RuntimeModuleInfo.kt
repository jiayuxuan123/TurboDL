package dev.turbodl.plugin.runtime

/**
 * turbo-plugin-runtime —— 可选插件运行时内核（阶段 0 骨架）。
 *
 * 硬性约束：
 *  - 本模块为「可选」模块，turbodl-core 不得依赖本模块；
 *  - core 仅定义 DownloadBackend 等扩展点接口，只有引入本模块后才做插件注册与优先级路由；
 *  - runtime 内核只包含：插件生命周期调度、disposer 清理、扩展点注册表、类型安全事件总线、
 *    轻量服务注册与依赖解析、PluginLoaderProvider 扩展点；不内置任何业务实现。
 *
 * 阶段 0 仅建立模块骨架；生命周期/事件总线/服务注册/扩展点注册表将在阶段 1-2 实现。
 */
internal object RuntimeModuleInfo {
    const val NAME = "turbo-plugin-runtime"
    const val STAGE = "scaffold-0"
}
