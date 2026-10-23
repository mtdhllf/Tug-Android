package com.github.mtdhllf.tug.core.engine.http.okhttp

import com.github.mtdhllf.tug.core.engine.http.HttpRequest
import java.io.ByteArrayOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getWithRange_returnsBodyAndHeaders() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/100")
                .setBody("test"),
        )
        val transport = OkHttpTransport()
        try {
            val call = transport.newCall(
                HttpRequest(
                    url = server.url("/file").toString(),
                    headers = mapOf("Range" to "bytes=0-3"),
                ),
            )
            call.execute().use { response ->
                assertEquals(206, response.code)
                assertEquals("bytes 0-3/100", response.header("Content-Range"))
                val body = ByteArrayOutputStream().also { out ->
                    response.body().use { it.copyTo(out) }
                }.toByteArray()
                assertEquals("test", String(body))
                assertTrue(response.finalUrl.contains("/file"))
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun redirect_followsToFinalUrl() {
        val target = server.url("/final")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val transport = OkHttpTransport()
        try {
            val call = transport.newCall(HttpRequest(server.url("/start").toString()))
            call.execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.finalUrl.endsWith("/final"))
            }
        } finally {
            transport.close()
        }
    }
}
