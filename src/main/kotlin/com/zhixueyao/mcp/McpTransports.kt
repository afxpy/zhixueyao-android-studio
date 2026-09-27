package com.zhixueyao.mcp

import com.zhixueyao.util.HttpSupport
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * stdio 传输：把 MCP 服务器作为子进程启动，通过标准输入输出交换 JSON-RPC 消息。
 *
 * 分帧规则（MCP 规范）：一行一条完整的 JSON 消息，以换行符分隔。
 * 注意服务器的 stderr 是日志输出，必须单独抽干，否则管道写满会导致进程阻塞。
 */
class StdioTransport(
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: String? = null
) : McpTransport {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null
    private var stderrThread: Thread? = null

    private val pending = ConcurrentHashMap<Long, PendingCall>()
    private val stderrLog = LinkedBlockingQueue<String>()
    private val alive = AtomicBoolean(false)

    override val description: String get() = "stdio: ${command.joinToString(" ")}"

    private class PendingCall(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var response: Json.Obj? = null
    }

    @Synchronized
    fun start() {
        if (alive.get()) return

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(false)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        if (workingDir != null) pb.directory(java.io.File(workingDir))

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw McpException(
                "无法启动 MCP 服务器进程：${command.firstOrNull() ?: "?"}\n" +
                    "原因：${e.message}\n" +
                    "提示：请确认该命令已安装并在系统 PATH 中，或改用绝对路径。"
            )
        }
        process = proc
        alive.set(true)

        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))

        // 读 stdout：逐行解析 JSON-RPC 响应
        readerThread = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { r ->
                    while (alive.get()) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        dispatch(line)
                    }
                }
            } catch (_: Exception) {
                // 进程退出导致流关闭属正常情况
            } finally {
                failAllPending("MCP 服务器已断开连接")
            }
        }, "zhixueyao-mcp-stdout").apply {
            isDaemon = true
            start()
        }

        // 读 stderr：仅用于故障诊断，必须持续消费
        stderrThread = Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { r ->
                    while (alive.get()) {
                        val line = r.readLine() ?: break
                        if (line.isNotBlank()) {
                            stderrLog.offer(line)
                            while (stderrLog.size > 200) stderrLog.poll()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "zhixueyao-mcp-stderr").apply {
            isDaemon = true
            start()
        }
    }

    private fun dispatch(line: String) {
        val json = runCatching { Json.parse(line) }.getOrNull() as? Json.Obj ?: return
        val id = json["id"]?.asLongOrNull ?: return
        val call = pending.remove(id) ?: return
        call.response = json
        call.latch.countDown()
    }

    private fun failAllPending(reason: String) {
        alive.set(false)
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            it.remove()
            e.value.response = jsonObj("error" to jsonObj("code" to (-32000).toJson(), "message" to reason.toJson()))
            e.value.latch.countDown()
        }
    }

    override fun exchange(
        message: Json.Obj,
        timeoutMillis: Long,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean?
    ): Json.Obj {
        if (!alive.get()) throw McpException("MCP 服务器未运行（${recentStderr()}）")
        val id = message["id"]?.asLongOrNull ?: throw McpException("请求缺少 id")
        val call = PendingCall()
        pending[id] = call
        write(message)

        // **分片等待**：一次等满超时的话，用户点了「停止」要等它超时才生效
        // （最多 2 分钟毫无反应）。每 100ms 醒一次看看取消标志。
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            if (cancelFlag?.get() == true) {
                pending.remove(id)
                throw McpException("已取消")
            }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                pending.remove(id)
                throw McpException("等待 MCP 服务器响应超时（${timeoutMillis / 1000} 秒）")
            }
            if (call.latch.await(minOf(100L, remain), TimeUnit.MILLISECONDS)) break
        }
        return call.response ?: throw McpException("MCP 服务器返回空响应")
    }

    override fun notify(message: Json.Obj) {
        write(message)
    }

    @Synchronized
    private fun write(message: Json.Obj) {
        val w = writer ?: throw McpException("MCP 服务器输入流不可用")
        try {
            w.write(message.stringify())
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            throw McpException("向 MCP 服务器写入失败：${e.message}")
        }
    }

    /** 取最近几行 stderr，用于把服务端报错反馈给用户 */
    fun recentStderr(lines: Int = 5): String {
        if (stderrLog.isEmpty()) return "无错误输出"
        return stderrLog.toList().takeLast(lines).joinToString(" | ")
    }

    override fun close() {
        alive.set(false)
        failAllPending("连接已关闭")
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        // 给进程一点优雅退出的时间，超时则强杀，避免残留进程
        runCatching {
            if (process?.isAlive == true && !process!!.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process!!.destroyForcibly()
            }
        }
    }
}

/**
 * Streamable HTTP 传输。
 *
 * 服务端可能在 initialize 响应头里下发 Mcp-Session-Id，后续请求必须带上；
 * 响应体可能是单个 JSON，也可能是 SSE 流（需要从中提取 result 事件）。
 */
class StreamableHttpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : McpTransport {

    private var sessionId: String? = null

    override val description: String get() = "http: $url"

    override fun exchange(
        message: Json.Obj,
        timeoutMillis: Long,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean?
    ): Json.Obj {
        val h = LinkedHashMap(headers)
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        sessionId?.let { h["Mcp-Session-Id"] = it }

        val body = message.stringify()
        val resp = sendRaw(url, body, h, timeoutMillis, cancelFlag)

        resp.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }

        val text = resp.body().orEmpty()
        val parsed = extractJson(text)
            ?: throw McpException("MCP 服务端返回无法解析的内容：${text.take(300)}")

        return parsed.asObjOrNull ?: Json.Obj()
    }

    override fun notify(message: Json.Obj) {
        val h = LinkedHashMap(headers)
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        sessionId?.let { h["Mcp-Session-Id"] = it }
        runCatching { sendRaw(url, message.stringify(), h, 30_000) }
    }

    private fun sendRaw(
        target: String,
        body: String,
        h: Map<String, String>,
        timeoutMillis: Long,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    ): java.net.http.HttpResponse<String> {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
            .timeout(java.time.Duration.ofMillis(timeoutMillis))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        h.forEach { (k, v) -> builder.header(k, v) }

        // 用异步发送 + **分片轮询**，这样取消能立刻生效。
        // 同步 send() 一等等满超时，用户点停止要干等（最长 2 分钟）。
        // 注意：取消只是「不再等」，请求可能已经发到服务端了 —— 这是没法撤回的，
        // 但至少界面不会卡着不动。
        val future = HttpSupport.client.sendAsync(
            builder.build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        val deadline = System.currentTimeMillis() + timeoutMillis
        // Kotlin 没有「break 带值」这种写法（那是 Java 的），所以用变量接
        var got: java.net.http.HttpResponse<String>? = null
        while (got == null) {
            if (cancelFlag?.get() == true) {
                future.cancel(true)
                throw McpException("已取消")
            }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                future.cancel(true)
                throw McpException("等待 MCP 服务器响应超时（${timeoutMillis / 1000} 秒）")
            }
            try {
                got = future.get(minOf(100L, remain), TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                continue
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause
                // HttpClient 自己的请求超时也会从这里抛出来（JDK 的英文 "request timed out"）。
                // 它和我们的 deadline 谁先到是**不确定**的，所以两边都统一成同一句中文提示 ——
                // 否则同一个问题，用户有时看到中文、有时看到英文原文。
                if (cause is java.net.http.HttpTimeoutException ||
                    cause?.message?.contains("timed out", ignoreCase = true) == true
                ) {
                    throw McpException("等待 MCP 服务器响应超时（${timeoutMillis / 1000} 秒）")
                }
                throw (cause as? Exception) ?: e
            }
        }
        val resp = got
        if (resp.statusCode() !in 200..299) {
            // 404/405 常见于服务端不支持通知类消息，交由调用方决定是否忽略
            throw HttpSupport.HttpError(resp.statusCode(), resp.body() ?: "")
        }
        return resp
    }

    /**
     * 响应可能是裸 JSON，也可能是 SSE 帧。
     * SSE 情况下取最后一个含 result/error 的 data 载荷。
     */
    private fun extractJson(text: String): Json? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return runCatching { Json.parse(trimmed) }.getOrNull()
        }
        var last: Json? = null
        for (line in trimmed.lines()) {
            val l = line.trim()
            if (!l.startsWith("data:")) continue
            val payload = l.substring(5).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val j = runCatching { Json.parse(payload) }.getOrNull() ?: continue
            val o = j.asObjOrNull ?: continue
            if (o.has("result") || o.has("error")) last = o
        }
        return last
    }

    override fun close() {
        // 按规范发送会话终止请求，失败也无所谓
        val sid = sessionId ?: return
        runCatching {
            val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Mcp-Session-Id", sid)
                .DELETE()
            headers.forEach { (k, v) -> builder.header(k, v) }
            HttpSupport.client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
        }
    }
}
