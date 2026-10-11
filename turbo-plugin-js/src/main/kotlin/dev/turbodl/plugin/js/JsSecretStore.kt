package dev.turbodl.plugin.js

/**
 * 插件凭据的加密存取接口 —— 由宿主实现，插件通过 `host.secrets.*` 使用。
 *
 * ## 为什么引擎只定接口、不实现
 *
 * 加密的**密钥**必须存在宿主平台的安全设施里（Android 是 Keystore，桌面是系统钥匙串或
 * 由用户提供的口令）。引擎是纯 JVM 库、要在任何环境里跑，它**没有一个可信的地方放密钥** ——
 * 如果在这里实现一个"自己生成密钥存在文件里"的版本，那是假加密：拿到文件就等于拿到密钥。
 *
 * 所以职责这么分：
 *
 *  - **引擎**：定义接口与调用协议（本文件），把 `host.secrets.*` 接到实现上；
 *  - **宿主**：提供真正的加密后端（Android Keystore / 桌面钥匙串），
 *    以及"哪个插件能访问哪些键"的隔离。
 *
 * 宿主不注入实现时，`host.secrets.*` 一律返回「不可用」，而不是退化成明文存储 ——
 * 详见 [Unavailable]。
 *
 * ## 与 `host.storage` 的区别（这不是重复功能）
 *
 * | | `host.storage` | `host.secrets` |
 * |---|---|---|
 * | 用途 | 插件的普通状态（配置、缓存、书签） | **凭据**（cookie、token、账号口令） |
 * | 存储形态 | 明文文件 | 由宿主的加密设施保护 |
 * | 谁能读 | 插件自己 | 插件自己，且键名带插件前缀隔离 |
 * | 卸载时 | 随沙箱删除 | 同样删除（见 [SecretStore.purgeOwner]） |
 * | 大小 | 有配额（默认 1MB 级） | 单值有上限，但不做总量配额 |
 *
 * 分成两套而不是"给 storage 加个加密开关"：两者的**威胁模型与生命周期不同**。
 * 普通状态泄露的危害是隐私，凭据泄露的危害是账号被接管；混在一起会让
 * "这个插件的某些键是加密的、某些不是"变成一件要靠记的事情。
 *
 * ## 隔离
 *
 * 每个键在落入实现之前都会被加上**插件 id 前缀**（由 [SecretScope] 负责）。
 * 插件无法通过构造特殊键名去读别的插件的数据 —— 这是"凭据只能看自己的"那条验收标准的落点。
 */
interface JsSecretStore {

    /**
     * 读一个键。不存在返回 null。
     *
     * 实现**不应**通过抛异常表达"没这个键"——那是正常情况，不是错误。
     */
    fun get(scope: JsSecretScope, key: String): String?

    /** 写一个键。已存在则覆盖。 */
    fun put(scope: JsSecretScope, key: String, value: String)

    /** 删一个键；返回是否确实删掉了。 */
    fun remove(scope: JsSecretScope, key: String): Boolean

    /** 该命名空间下的全部键名（**不带前缀**，返回插件视角的原始键名）。 */
    fun keys(scope: JsSecretScope): List<String>

    /**
     * 删除某个插件的全部凭据。
     *
     * 由宿主在**卸载插件**时调用。必须实现：留着一份再也访问不到的加密凭据，
     * 既占空间又是个隐患（用户以为卸载就清干净了）。
     */
    fun purgeOwner(scope: JsSecretScope)

    /**
     * 后端是否真的可用（宿主装了加密设施且能工作）。
     *
     * 用来区分两种情况：**"没有这个键"**（返回 null，正常）与
     * **"这台设备上凭据功能根本没用"**（应当报错，让插件知道要降级）。
     * 混为一谈会让插件把"设备不支持"当成"用户还没登录"，表现为反复提示登录。
     */
    fun isAvailable(): Boolean

    companion object {
        /**
         * 宿主未注入实现时的占位。
         *
         * 所有写操作**抛异常**而不是静默丢弃：静默丢弃会让插件以为凭据存下来了，
         * 下次读却读不到，表现为"登录状态随机丢失"——那种问题极难排查。
         * 读操作返回 null（等同于"没有这个键"），因为读失败没有别的可做。
         */
        val Unavailable: JsSecretStore = object : JsSecretStore {
            override fun get(scope: JsSecretScope, key: String): String? = null

            override fun put(scope: JsSecretScope, key: String, value: String): Nothing =
                throw IllegalStateException(
                    "宿主没有提供凭据存储后端（host.secrets 不可用）；" +
                        "插件应改用 host.storage 存非敏感数据，或提示用户当前环境不支持保存登录态"
                )

            override fun remove(scope: JsSecretScope, key: String): Boolean = false

            override fun keys(scope: JsSecretScope): List<String> = emptyList()

            override fun purgeOwner(scope: JsSecretScope) = Unit

            override fun isAvailable(): Boolean = false
        }
    }
}

/**
 * 键的命名空间逻辑：把"插件 id"与"插件自己的键名"合成一个实际存储键，
 * 并在读取时把前缀剥掉。
 *
 * ## 为什么要单独一个类
 *
 * 隔离的正确性全在这里，而它容易被"看起来对"地写错：
 *
 *  - 前缀分隔符不能是插件 id 里可能出现的字符（否则 `a.b` 与 `a` 加键 `b.c` 会撞车）；
 *  - 剥前缀时不能只做 `removePrefix`，因为插件的键名本身可能以别的插件 id 开头。
 *
 * 这两点都由测试钉住（`JsSecretScopeTest`）。放在这里而不是散在调用点，
 * 是为了让"隔离规则"只有一处可改。
 */
class JsSecretScope private constructor(private val ownerId: String) {

    /**
     * 实际存储键 = `ownerId` + [SEP] + 插件键名。
     *
     * `SEP` 用一个**不可能出现在合法插件 id 里**的字符（NUL 的可见替代：`\u0000` 不行，
     * 存储层可能不接受；用 `\u001f` 单元分隔符）。插件 id 的合法字符集是
     * `[a-z0-9.-]`，所以任何控制字符都不会出现在 id 里 —— 这就排除了
     * "插件 A 的 id 恰好是插件 B 的 id 加分隔符再加点什么"这类撞车。
     */
    fun storageKeyFor(key: String): String = ownerId + SEP + key

    /**
     * 从存储键剥回插件键名；不属于本命名空间返回 null。
     *
     * 先精确匹配 `ownerId + SEP` 再截断 —— 不能只判断 `startsWith(ownerId)`，
     * 因为插件 `a` 与插件 `ab` 互为前缀，只用 startsWith 会让 `ab` 的数据被 `a` 读到。
     */
    fun keyOf(storageKey: String): String? {
        val prefix = ownerId + SEP
        return if (storageKey.startsWith(prefix)) storageKey.substring(prefix.length) else null
    }

    /**
     * 本命名空间的归属者 id（构造时传入的那个）。
     *
     * 给实现用来做**按插件分区**（宿主侧按它建子目录、卸载时整目录删）。
     */
    fun ownerId(): String = ownerId

    /** 校验一个插件键名是否可接受。 */
    fun validateKey(key: String): String? = when {
        key.isEmpty() -> "键名不能为空"
        key.length > MAX_KEY_CHARS -> "键名过长（上限 $MAX_KEY_CHARS 字符）"
        key.contains(SEP) -> "键名不能包含保留字符"
        else -> null
    }

    companion object {
        /** 命名空间分隔符。见 [storageKeyFor] 关于选它的理由。 */
        const val SEP: Char = '\u001f'

        const val MAX_KEY_CHARS = 128

        /** 单个凭据值的字符数上限（凭据是短串；几 KB 已经远超合理范围）。 */
        const val MAX_VALUE_CHARS = 16 * 1024

        fun of(ownerId: String): JsSecretScope {
            require(ownerId.isNotBlank()) { "ownerId 不能为空" }
            require(!ownerId.contains(SEP)) { "ownerId 不能包含保留字符" }
            return JsSecretScope(ownerId)
        }
    }
}
