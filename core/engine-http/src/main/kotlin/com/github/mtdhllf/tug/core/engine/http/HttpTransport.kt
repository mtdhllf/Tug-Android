package com.github.mtdhllf.tug.core.engine.http

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 可插拔 HTTP 传输层。
 *
 * 核心默认提供 [UrlConnectionTransport]（纯 JDK，无第三方依赖）；
 * 宿主项目可以注入自己的实现（例如 OkHttp 适配器），避免与项目已有版本冲突。
 */
public interface HttpTransport {
    public fun newCall(request: HttpRequest): HttpCall

    public fun close()
}

/** 一次 HTTP 请求（GET；重定向由传输层处理）。 */
public data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
)

/** 可取消的请求句柄。 */
public interface HttpCall {
    /** 同步执行请求；调用方负责关闭 [HttpResponse]。 */
    public fun execute(): HttpResponse

    /** 取消在途请求（可从其他线程调用）。 */
    public fun cancel()

    public fun close()
}

/** HTTP 响应。 */
public interface HttpResponse : Closeable {
    public val code: Int

    /** 跟随重定向后的最终 URL。 */
    public val finalUrl: String

    /** Content-Length；未知返回 null。 */
    public val contentLength: Long?

    public fun header(name: String): String?

    public fun body(): InputStream
}

/**
 * 基于 [HttpURLConnection] 的默认传输实现。
 *
 * 不依赖 OkHttp，支持 HTTP/HTTPS、Range、If-Range、重定向与取消。
 */
public class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 10,
    private val userAgent: String = "Tug-Android/1.1",
) : HttpTransport {

    override fun newCall(request: HttpRequest): HttpCall =
        UrlConnectionCall(request, connectTimeoutMs, readTimeoutMs, maxRedirects, userAgent)

    override fun close() = Unit
}

private class UrlConnectionCall(
    private val request: HttpRequest,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val maxRedirects: Int,
    private val userAgent: String,
) : HttpCall {

    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var stream: InputStream? = null

    override fun execute(): HttpResponse {
        var current = URL(request.url)
        var redirects = 0
        while (true) {
            if (cancelled) throw IOException("请求已取消")
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = request.method
                setRequestProperty("User-Agent", userAgent)
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection = conn
            conn.connect()
            val code = conn.responseCode
            if (code in REDIRECT_CODES && redirects < maxRedirects) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val next = URL(current, location)
                    conn.disconnect()
                    connection = null
                    current = next
                    redirects++
                    continue
                }
            }
            val input = runCatching {
                if (code >= 400) conn.errorStream else conn.inputStream
            }.getOrNull() ?: ByteArrayInputStream(ByteArray(0))
            stream = input
            return UrlConnectionResponse(code, current.toString(), conn, input)
        }
    }

    override fun cancel() {
        cancelled = true
        runCatching { stream?.close() }
        runCatching { connection?.disconnect() }
    }

    override fun close() = cancel()

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private class UrlConnectionResponse(
    override val code: Int,
    override val finalUrl: String,
    private val connection: HttpURLConnection,
    private val input: InputStream,
) : HttpResponse {

    override val contentLength: Long?
        get() = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

    override fun header(name: String): String? = connection.getHeaderField(name)

    override fun body(): InputStream = input

    override fun close() {
        runCatching { input.close() }
        runCatching { connection.disconnect() }
    }
}
