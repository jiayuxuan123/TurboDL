package dev.turbodl.plugin.native

import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource

/**
 * L2（原生）插件加载器的契约。
 *
 * ## 为什么引擎只定契约、不实现加载
 *
 * 加载原生插件需要 **Android 的 dex 机制**（`DexClassLoader`）、可选的 `System.load`
 * 装原生库、以及对"这份 jar 从哪来、能不能信"的判断。这些全是平台能力，
 * 引擎（纯 JVM、要跑在桌面与服务端）没有也不该有。
 *
 * 而且这里有一条**不能含糊**的边界：引擎侧若提供一个"尽力加载"的默认实现，
 * 它在 JVM 上会走 `URLClassLoader`，在 Android 上走 dex —— 两条路径的失败模式
 * 与安全前提都不同。**宁可让宿主显式提供，也不提供一个看起来能用但行为不一致的默认。**
 *
 * ## 与 JS loader 的关系
 *
 * 两者都实现 [PluginLoaderProvider]、都产出 [Plugin]，内核**一视同仁**：
 * 同一个注册表、同一套生命周期、同一批扩展点。内核不认识 JS，也不该认识 L2。
 *
 * ## 关键约束：L2 不能热加载
 *
 * 已加载的类**无法被替换**（类加载器不卸载类）；插件还可能持有解码器、文件句柄、
 * 原生库等不可逆资源。所以 L2 的安装/更新/卸载/启停一律**重启才生效** ——
 * 宿主必须照实提示，不能做成"看起来立即生效"。理由与完整讨论见
 * `_audit/插件分级补充.md`。
 */
interface NativePluginLoaderProvider {

    /**
     * 把一个来源变成插件实例。
     *
     * @param source [KIND] 种类的 [PluginSource]
     * @return 成功时的插件列表（正常是一个入口类 → 一个插件）
     * @throws NativePluginLoadException 失败时抛，并带上**可展示给用户**的原因
     */
    fun load(source: PluginSource): List<Plugin>
}

/**
 * 加载失败。带一个**能直接显示**的原因。
 *
 * 分成几个具体类型而不是一个大而全的异常，是为了宿主能给出对应的处置建议：
 * 插件没装好要"重新安装"，版本不匹配要"更新应用"，ABI 不匹配要"换设备"。
 */
class NativePluginLoadException(
    message: String,
    val kind: Kind = Kind.UNKNOWN,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** jar 不存在 / 不可读。 */
        JAR_UNREADABLE,

        /** jar 里没有承诺的入口类。 */
        ENTRY_CLASS_MISSING,

        /** 入口类不是 [Plugin]，或没有无参构造。 */
        ENTRY_CLASS_INVALID,

        /** 插件要求更高的宿主 API 版本。 */
        API_VERSION_TOO_LOW,

        /** 依赖的其它插件没装。 */
        DEPENDENCY_MISSING,

        /** 类加载 / 实例化 / onLoad 抛出。 */
        LOAD_FAILED,

        /** 其它。 */
        UNKNOWN,
    }
}

/**
 * L2 插件的风险面（宿主用它如实告知用户，**不做任何"沙箱"暗示**）。
 *
 * 设计文档第 8.4 章明确要求"不能伪装成完全沙箱"。这个类的存在就是为了让那句
 * 提示有**具体内容**，而不是一句谁都可以写的免责声明。
 */
enum class NativePluginRisk {
    /**
     * 代码运行在宿主进程内，可调用宿主能调用的一切系统 API。
     *
     * 这是事实，不是推测：类加载器不做任何访问限制，插件里的代码与宿主代码
     * 享有同样的权限。
     */
    SAME_PRIVILEGE_AS_HOST,

    /**
     * 可以注册扩展点（下载后端、解析器、钩子），即**能改变应用的行为**。
     *
     * 与"读文件"的差别在于：注册后端意味着用户的下载流量会经过这段代码。
     */
    CAN_REGISTER_EXTENSIONS,

    /**
     * 可能持有不可逆资源（原生库句柄、解码器、文件描述符），因此不能热卸载。
     */
    HOLDS_IRREVERSIBLE_RESOURCES,

    /** 无法隔离崩溃：一个插件的未捕获异常会带崩整个应用进程。 */
    CRASH_NOT_ISOLATED,
}

/** 给安装确认界面用的风险清单（宿主直接渲染成一段话）。 */
fun NativePluginRisk.describe(): String = when (this) {
    NativePluginRisk.SAME_PRIVILEGE_AS_HOST ->
        "它的代码运行在本应用的进程内，可以调用本应用能调用的系统接口"
    NativePluginRisk.CAN_REGISTER_EXTENSIONS ->
        "它可以注册扩展点（如下载后端、链接解析器），也就是说下载行为会经过它的代码"
    NativePluginRisk.HOLDS_IRREVERSIBLE_RESOURCES ->
        "它可能持有原生库、文件句柄等无法自动释放的资源"
    NativePluginRisk.CRASH_NOT_ISOLATED ->
        "它出错时无法被隔离，整个应用可能一起退出"
}
