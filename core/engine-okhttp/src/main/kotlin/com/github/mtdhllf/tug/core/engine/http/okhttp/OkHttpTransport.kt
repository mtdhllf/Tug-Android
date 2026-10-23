package com.github.mtdhllf.tug.core.engine.http.okhttp

import com.github.mtdhllf.tug.core.engine.http.HttpCall
import com.github.mtdhllf.tug.core.engine.http.HttpRequest
import com.github.mtdhllf.tug.core.engine.http.HttpResponse
import com.github.mtdhllf.tug.core.engine.http.HttpTransport
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * OkHttp 传输实现，供宿主项目直接使用或在已有 OkHttp 版本冲突时参考适配。
 *
 * 与 `tug-http` 的 JDK 实现二选一：
 * - 不想引入 OkHttp → 用 `tug-http` 的 `UrlConnectionTransport`；
 * - 已有 OkHttp / 需要其连接池与 HTTP/2 → 用本模块。
 */
public class OkHttpTransport(
    client: OkHttpClient? = null,
    private val ownsClient: Boolean = client == null,
) : HttpTransport {

    private val client: OkHttpClient = client ?: defaultClient()

    override fun newCall(request: HttpRequest): HttpCall {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        return OkHttpCall(this.client.newCall(builder.build()))
    }

    override fun close() {
        if (!ownsClient) return
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    public companion object {
        public fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

private class OkHttpCall(private val call: Call) : HttpCall {

    override fun execute(): HttpResponse = OkHttpResponse(call.execute())

    override fun cancel() = runCatching { call.cancel() }.let { }

    override fun close() = cancel()
}

private class OkHttpResponse(private val response: Response) : HttpResponse {

    override val code: Int
        get() = response.code

    override val finalUrl: String
        get() = response.request.url.toString()

    override val contentLength: Long?
        get() = runCatching { response.body.contentLength() }
            .getOrNull()
            ?.takeIf { it >= 0 }

    override fun header(name: String): String? = response.header(name)

    override fun body(): InputStream = response.body.byteStream()

    override fun close() {
        runCatching { response.close() }
    }
}
