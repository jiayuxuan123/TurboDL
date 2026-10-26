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

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 平台解析器共用的 HTTP 取数层。
 *
 * ## 为什么每个平台不自己 new 一个 OkHttpClient
 *
 * OkHttpClient 的价值几乎全在**连接池与线程池**上：每个实例都有自己的一套。
 * 三个平台各建一个，就是三套空闲连接三套线程 —— 移动端上这是纯粹的内存浪费，
 * 而同域请求（同一平台的页面 + 接口）本来就能复用连接。
 * 这里用进程内唯一一个懒加载实例，所有解析器共用。
 *
 * ## 为什么不带 `Cookie` / `Authorization`
 *
 * 这三个平台（微博/小红书/Pixiv）在本插件里**只做匿名解析** —— 需要登录的内容
 * 交由宿主自己的账号体系处理，插件不持有任何凭证。所以这里不注入任何认证头，
 * 也就不存在"凭证跟着 302 坐到别的域"这类问题（见 HlsRequestPolicy 的同类考量）。
 *
 * ## 判据：`ok` 看状态码，不看异常
 *
 * 非 2xx **不是异常** —— 它是平台在正常回答"你没登录/内容没了/被风控了"。
 * 把它当异常抛出去会让上层无法区分"网络断了"与"这条内容看不见"，
 * 而这两者对用户是完全不同的两句话。
 */
internal object ParserHttp {

    /** 所有平台共用的客户端（懒加载：只有真的解析过链接才会建连接池）。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 解析是"用户点了按钮在等"的同步路径，超时要比下载链路短得多：
            // 10s 还没拿到一个几 KB 的 JSON，基本可以判定这个平台在拒绝我们。
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            // 重定向由我们自己逐跳判断（见 getText 的 followRedirects 参数），
            // 交给 OkHttp 跟随会让我们看不到中间的 Location。
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 一次请求的结果。非 2xx 也是成功返回（[ok] = false），只有传输失败才是异常。 */
    data class TextResult(
        val ok: Boolean,
        val status: Int,
        val body: String,
        val finalUrl: String,
        val location: String?,
        val headers: Map<String, String>,
    )

    /**
     * GET 一段文本。
     *
     * @param asciiOnly 为 true 时把响应当 ISO-8859-1 读（用于**原样保留**响应字节的 base64 场景）；
     *   默认按 UTF-8 解。平台的 JSON 与 HTML 都是 UTF-8。
     * @return 传输成功即返回（哪怕状态码是 404）；传输失败（DNS/连接/超时）返回 null。
     */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): TextResult? {
        val request = try {
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            // 非法 URL（含空格、非 http(s) scheme）—— 不是网络错误，是"这不是我们能取的地址"
            return null
        }

        return try {
            client.newCall(request).execute().use { response -> readText(response, maxBytes) }
        } catch (e: IOException) {
            null
        }
    }

    private fun readText(response: Response, maxBytes: Long): TextResult {
        // 有界读取：远端可以声明一个巨大的 Content-Length，或干脆持续吐字节。
        // 不设上限就是给一个"平台被篡改后把 App 的内存吃光"的入口。
        val bytes = response.body?.byteStream()?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: ByteArray(0)

        return TextResult(
            ok = response.isSuccessful,
            status = response.code,
            body = String(bytes, Charsets.UTF_8),
            finalUrl = response.request.url.toString(),
            location = response.header("Location"),
            headers = buildMap {
                for (name in response.headers.names()) {
                    response.header(name)?.let { put(name.lowercase(), it) }
                }
            },
        )
    }

    /** 默认读取上限：平台页面普遍 200~800 KB，留到 8 MB 只防失控，不拦正常页面。 */
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024

    /** 桌面 Chrome UA —— 小红书 / Pixiv 的接口按这个 UA 才返回完整数据。 */
    const val UA_CHROME: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 移动 Safari UA —— 微博的 m.weibo.cn 接口认移动端。 */
    const val UA_MOBILE_SAFARI: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}
