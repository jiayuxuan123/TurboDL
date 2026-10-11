package dev.turbodl.plugin.native

import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.Plugin
import java.io.File

/**
 * 原生（L2）插件的**发现契约**。
 *
 * ## 这里为什么只定"发现"，不定"加载"
 *
 * 真正的加载（类加载器、实例化、生命周期）由 [NativePluginLoader] 负责。
 * 把两者分开是因为二者的失败模式完全不同：发现失败 = "目录里没有这个插件"，
 * 加载失败 = "类不在 / ABI 不匹配 / onLoad 抛异常" —— 用户要看到的提示不一样。
 *
 * ## 分发形态：为什么是 JAR 而不是独立 APK 或 dex
 *
 * 总纲把这个列为必须先定的三个决策之一。选 **JAR（宿主可读目录里的 .jar，含 so）**：
 *
 * | 形态 | 为什么排除 |
 * |---|---|
 * | 独立 APK | 要装第二个应用，用户看到的是"另一个 App"而不是"云取的插件"；卸载、权限、版本协调全都变成跨应用问题。而我们并不需要独立 APK 带来的任何东西（组件复用、Intent 通信） |
 * | 裸 dex | dex 只能放**类**，放不下 `JNI` 的 .so（那要放在 `lib/` 或资产里）。而原生插件的价值恰恰在于能调原生库 —— Adapter 要挂进下载引擎，很可能带 .so。dex 表达不了这个组合 |
 * | **JAR** | 能同时装类与 .so；能复用宿主已有的 Java 类路径（不用重复打包依赖）；Android 上 `DexClassLoader` 与 `System.load` 都能直接从它取。**并且它就是 AAR 里的东西** —— 用户若已有 AAR（像 Gopeed 内核那样），解包即得 |
 *
 * 代价要说清：JAR 里的代码**没有**沙箱，运行在宿主进程内、能调用宿主能调用的一切。
 * 这不是缺陷被掩盖，而是如实告知（见 [NativePluginRisk]），并在安装确认界面写明。
 */
data class NativePluginSource(
    /** 插件 id（用于诊断与依赖解析）。 */
    val pluginId: String,
    /** 插件 jar 的绝对路径。 */
    val jarFile: File,
    /**
     * jar 里**入口类**的全限定名。
     *
     * 为什么不用"扫 jar 找实现了 [Plugin] 的类"：扫描意味着要在运行时对每个类
     * 调 `Class.forName`，那会触发链接（可能加载 native 库、执行静态初始化），
     * 而 jar 是**用户给的不可信输入**。写成明确的一个类名，跳转链只有一跳。
     */
    val entryClass: String,
    /** 人类可读名（诊断用）。 */
    val displayName: String = pluginId,
    /** 最低宿主 API 版本；不满足时 [NativePluginLoaderProvider] 拒绝加载。 */
    val requiredApiVersion: dev.turbodl.core.ApiVersion = dev.turbodl.core.ApiVersion(1, 0, 0),
) {
    init {
        require(pluginId.isNotBlank()) { "pluginId 不能为空" }
        require(entryClass.isNotBlank()) { "entryClass 不能为空（不为空才能保证类查找只有一跳）" }
    }
}

/**
 * 把 [NativePluginSource] 变成宿主能装载的 [PluginSource]。
 *
 * 存在的意义是**收敛一种 kind**：内核只认 `PluginSource`（kind + uri + attributes），
 * 而原生插件的元数据有六项，逐字段塞进 attributes 会让内核开始理解 L2 的细节 ——
 * 那正是内核不该知道的（它连 JS 都不认识）。
 */
fun NativePluginSource.toPluginSource(): PluginSource = PluginSource(
    kind = KIND,
    uri = jarFile.absolutePath,
    attributes = mapOf(
        ATTR_PLUGIN_ID to pluginId,
        ATTR_ENTRY_CLASS to entryClass,
        ATTR_DISPLAY_NAME to displayName,
        ATTR_API_VERSION to "${requiredApiVersion.major}.${requiredApiVersion.minor}.${requiredApiVersion.patch}",
    ),
)

/** L2 的 kind。与 `"js"` 并列，内核不区分二者，只交给各自的 loader。 */
const val KIND: String = "native"

/**
 * [PluginSource.attributes] 的键。这些是**跨模块的公开契约**：
 * 宿主要读它们来构造 / 校验 L2 插件，所以不能是 internal ——
 * internal 会让 App 侧连读都读不到，L2 在真机上根本装不上。
 */
const val ATTR_PLUGIN_ID = "pluginId"
const val ATTR_ENTRY_CLASS = "entryClass"
const val ATTR_DISPLAY_NAME = "displayName"
const val ATTR_API_VERSION = "requiredApiVersion"
