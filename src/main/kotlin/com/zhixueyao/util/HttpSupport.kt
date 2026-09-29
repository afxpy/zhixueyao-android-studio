package com.zhixueyao.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 JDK 内置 HttpClient 的轻量封装。不引入 OkHttp，避免依赖冲突。
 */
object HttpSupport {

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    class HttpError(val status: Int, val body: String) :
        RuntimeException("HTTP $status: ${body.take(600)}")

    /** 发送 JSON 请求并读取完整响应体。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 300
    ): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() !in 200..299) {
            throw HttpError(resp.statusCode(), resp.body() ?: "")
        }
        return resp.body() ?: ""
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 60
    ): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() !in 200..299) {
            throw HttpError(resp.statusCode(), resp.body() ?: "")
        }
        return resp.body() ?: ""
    }

    /**
     * 发送请求并以 SSE 方式逐行消费响应。
     *
     * 设计要点：不使用 BodyHandlers.ofLines()，因为它需要请求完全结束才交付首行，
     * 流式体验会丧失。这里直接消费 InputStream，边到边推。
     *
     * @param onData 每收到一个 `data:` 载荷回调一次；返回 false 表示请求方希望中断
     * @return 是否被主动中断
     */
    fun postSse(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        cancelFlag: AtomicBoolean? = null,
        onData: (String) -> Boolean
    ): Boolean {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) {
            val errBody = resp.body().use { String(it.readBytes(), StandardCharsets.UTF_8) }
            throw HttpError(resp.statusCode(), errBody)
        }

        var cancelled = false
        resp.body().use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                var line: String?
                while (true) {
                    if (cancelFlag?.get() == true) {
                        cancelled = true
                        break
                    }
                    line = reader.readLine() ?: break
                    when {
                        // 空行 = 一个 SSE 事件结束
                        line.isEmpty() -> {
                            if (dataBuffer.isNotEmpty()) {
                                val payload = dataBuffer.toString()
                                dataBuffer.setLength(0)
                                if (!onData(payload)) {
                                    cancelled = true
                                    return@use
                                }
                            }
                        }
                        line.startsWith("data:") -> {
                            val chunk = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(chunk)
                        }
                        // event: / id: / retry: 等字段本实现不需要，忽略
                        else -> Unit
                    }
                }
                // 处理末尾未以空行收尾的事件
                if (dataBuffer.isNotEmpty()) {
                    onData(dataBuffer.toString())
                }
            }
        }
        return cancelled
    }

    /** 带重试的 POST，用于瞬时网络抖动。 */
    fun postJsonWithRetry(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        attempts: Int = 3,
        timeoutSeconds: Long = 300
    ): String {
        var last: Exception? = null
        for (i in 1..attempts) {
            try {
                return postJson(url, body, headers, timeoutSeconds)
            } catch (e: HttpError) {
                // 4xx 是请求本身的问题，重试无意义
                if (e.status in 400..499) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
            if (i < attempts) {
                runCatching { Thread.sleep(600L * i) }
            }
        }
        throw last ?: RuntimeException("请求失败")
    }
}
