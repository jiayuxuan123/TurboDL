/*
 * TurboDL — 多线程下载引擎
 *
 * Copyright (C) 2026 TurboDL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.turbodl.plugin.parser

/**
 * 极小的严格 JSON 读取器。
 *
 * ## 为什么手写而不是加依赖
 *
 * 这个模块要能被宿主单独发布成一个 SDK 插件，加 `kotlinx-serialization` 或 Gson
 * 会把一整套运行时塞进每个用它的 App —— 而我们只用得到"把一段响应解成 Map/List"。
 * `turbo-plugin-js` 里已经有一份同风格的实现，但它是 `internal`（属于 JS 桥的私有面），
 * 跨模块拿不到，所以这里独立一份。
 *
 * ## 严格性为什么重要
 *
 * 输入是**远端可控**的响应体：一个被篡改的平台响应可以塞一个 200 MB 的 JSON、
 * 或者嵌套几万层把解析线程的栈打穿。所以这里有三个硬上限，任何超限都**显式失败**
 * （抛 [IllegalArgumentException]）而不是悄悄截断 —— 截断会产出一个"看起来解析成功"
 * 的半成品，比直接失败难查得多。
 *
 * 值的域与 `turbo-plugin-js` 保持一致：null / Boolean / Long / Double / String /
 * `List<Any?>` / `Map<String, Any?>`。整数解成 Long，带小数点或指数的解成 Double。
 */
internal object MiniJson {

    /** 文本上限：平台响应普遍 < 1 MB，4 MB 只防失控。 */
    private const val MAX_CHARS = 4 * 1024 * 1024

    /** 嵌套深度上限：正常响应远低于此，异常嵌套是栈溢出攻击。 */
    private const val MAX_DEPTH = 64

    /** 解码入口。失败抛 [IllegalArgumentException]（调用方普遍用 runCatching 兜）。 */
    fun decode(text: String): Any? {
        require(text.length <= MAX_CHARS) { "JSON 超过 $MAX_CHARS 字符" }
        return Reader(text).run {
            val value = readValue(0)
            skipWs()
            require(pos >= text.length) { "JSON 尾部有多余内容（位置 $pos）" }
            value
        }
    }

    /** 便捷入口：顶层必须是对象，否则返回 null。 */
    fun parseObject(text: String): Map<String, Any?>? =
        runCatching { decode(text) as? Map<String, Any?> }.getOrNull()

    private class Reader(private val text: String) {
        var pos = 0

        fun skipWs() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON 嵌套超过 $MAX_DEPTH 层" }
            skipWs()
            require(pos < text.length) { "JSON 意外结束" }
            return when (val ch = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(ch == '-' || ch.isDigit()) { "JSON 非法字符 '$ch'（位置 $pos）" }
                    readNumber()
                }
            }
        }

        fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                require(pos < text.length && text[pos] == '"') { "对象键必须是字符串（位置 $pos）" }
                val key = readString()
                skipWs()
                require(pos < text.length && text[pos] == ':') { "对象键后缺少 ':'（位置 $pos）" }
                pos++
                map[key] = readValue(depth + 1)
                skipWs()
                require(pos < text.length) { "对象未闭合" }
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("对象里出现意外字符 '${text[pos]}'（位置 $pos）")
                }
            }
        }

        fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                list += readValue(depth + 1)
                skipWs()
                require(pos < text.length) { "数组未闭合" }
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("数组里出现意外字符 '${text[pos]}'（位置 $pos）")
                }
            }
        }

        fun readString(): String {
            pos++ // 开头的引号
            val sb = StringBuilder()
            while (true) {
                require(pos < text.length) { "字符串未闭合" }
                when (val ch = text[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        require(pos < text.length) { "转义序列未完成" }
                        when (val esc = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 < text.length) { "\\u 转义不完整" }
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("\\u 后面不是十六进制：$hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("未知转义 '\\$esc'")
                        }
                        pos++
                    }
                    else -> { sb.append(ch); pos++ }
                }
            }
        }

        fun readNumber(): Any {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            require(raw.isNotEmpty() && raw != "-") { "非法数字 '$raw'" }
            return if (isDouble) {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字 '$raw'")
            } else {
                raw.toLongOrNull() ?: raw.toDoubleOrNull()
                    ?: throw IllegalArgumentException("非法数字 '$raw'")
            }
        }

        fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, pos)) { "期望 '$literal'（位置 $pos）" }
            pos += literal.length
            return value
        }
    }
}

// ---------------------------------------------------------------- 取值辅助
//
// 远端 JSON 的字段类型是不可信的：期望对象的地方可能是字符串，期望字符串的地方可能是数字。
// 下面这些扩展把"类型不符"统一成 null，让解析器用 `?:` 表达"这个字段没有"，
// 而不是到处写 `as? Map<*, *>`。

/** 取字符串；非字符串返回 null。 */
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

/** 取对象；非对象返回 null。 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

/** 取数组；非数组返回 null。 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key] as? List<Any?>

/** 取整数；接受 Long、Int 与整值 Double（JSON 数字经常是这样）。 */
internal fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    else -> null
}

/** 取布尔；只有真正的 Boolean 才算（`"true"` 字符串不算，避免猜）。 */
internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
