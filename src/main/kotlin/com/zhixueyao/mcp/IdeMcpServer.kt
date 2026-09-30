package com.zhixueyao.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zhixueyao.tools.AgentTool
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 把插件内置的 IDE 工具通过 MCP 协议对外暴露。
 *
 * 这样 Claude Code、Cursor 等外部 AI 客户端就能调用本 IDE 的代码搜索、
 * 诊断、构建能力 —— 相当于让外部 AI 拥有了 IDE 的索引视野。
 *
 * 安全约束：
 *  - 仅绑定 127.0.0.1，不对外网开放
 *  - 校验 Host 头，防御 DNS Rebinding（规范要求的防护措施）
 *  - 工具执行仍受 PathGuard 限制，无法访问系统目录
 */
class IdeMcpServer(
    private val projectProvider: () -> Project?,
    private val toolsProvider: () -> List<AgentTool>
) {

    private val log = Logger.getInstance(IdeMcpServer::class.java)
    private var server: HttpServer? = null

    /**
     * 线程池**必须存成字段**。
     *
     * 原来只写了 `srv.executor = Executors.newFixedThreadPool(4){...}`，
     * 引用没有留下来 —— 于是 `stop()` 里没有它可关（想关也关不了）。
     *
     * 这是 `HttpServer` 一个很容易踩的地方：**`stop()` 不会关掉用户传进去的 executor**
     * （JDK 文档写得很明确，但 API 名字太像会让人以为它会一起关）。
     * 结果就是每启停一次泄漏最多 4 个线程 —— 而固定线程池的核心线程**永不超时**，
     * 所以它们会一直挂着，直到整个 JVM 退出。
     */
    private var executor: java.util.concurrent.ExecutorService? = null

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(port: Int): Boolean {
        if (server != null) return true
        return try {
            val srv = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
            val pool = Executors.newFixedThreadPool(4) { r ->
                Thread(r, "zhixueyao-mcp-server").apply { isDaemon = true }
            }
            srv.executor = pool
            srv.createContext("/") { exchange -> handle(exchange) }
            srv.start()
            server = srv
            executor = pool
            log.info("止血药 MCP 服务已启动：http://127.0.0.1:$port/")
            true
        } catch (e: Exception) {
            log.warn("启动 MCP 服务失败", e)
            // 启动失败也要把刚建的池收掉 —— 否则「端口被占用」这种常见错误
            // 每试一次就漏一个池（用户可能会反复点重试）
            runCatching { executor?.shutdownNow() }
            server = null
            executor = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(0) }
        // 两件事都要做：停服务**和**收线程池。
        // 用 shutdownNow 而不是 shutdown：此刻还在处理中的请求已经随着 server 停下而失去意义，
        // 让它们尽快中断比等它们跑完好（而且不中断的话，长请求会拖住池不释放）。
        runCatching { executor?.shutdownNow() }
        server = null
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            // DNS Rebinding 防护：只接受本机来源的 Host
            val host = exchange.requestHeaders.getFirst("Host") ?: ""
            val hostName = host.substringBefore(':').lowercase()
            if (hostName !in setOf("127.0.0.1", "localhost", "[::1]", "::1")) {
                respond(exchange, 403, """{"error":"仅允许本机访问"}""")
                return
            }

            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                "GET" -> respond(exchange, 405, """{"error":"请使用 POST 发送 JSON-RPC 请求"}""")
                "DELETE" -> {
                    // 规范定义的会话终止请求，本实现无状态，直接接受
                    respond(exchange, 200, "")
                }
                else -> respond(exchange, 405, """{"error":"不支持的方法"}""")
            }
        } catch (e: Exception) {
            log.warn("处理 MCP 请求异常", e)
            runCatching { respond(exchange, 500, """{"error":"${escape(e.message ?: "内部错误")}"}""") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val bodyText = exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        val request = runCatching { Json.parse(bodyText).asObjOrNull }.getOrNull()
        if (request == null) {
            respond(exchange, 400, """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"请求体不是合法 JSON"}}""")
            return
        }

        val id = request["id"]
        val method = request.str("method") ?: ""
        val params = request.obj("params") ?: Json.Obj()

        // 通知类消息没有 id，按规范不返回响应体
        if (id == null || id is Json.Null) {
            respond(exchange, 202, "")
            return
        }

        val result: Json = when (method) {
            "initialize" -> handleInitialize()
            // 新协议（2026-07-28 起）用 server/discover 取代 initialize 握手。
            // 支持它，Claude Code 等已升级的客户端才能连上本服务。
            "server/discover" -> handleDiscover()
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(params)
            "ping" -> Json.Obj()
            "resources/list" -> jsonObj("resources" to jsonArr())
            "resources/templates/list" -> jsonObj("resourceTemplates" to jsonArr())
            "prompts/list" -> jsonObj("prompts" to jsonArr())
            else -> {
                respondJson(
                    exchange,
                    jsonObj(
                        "jsonrpc" to "2.0",
                        "id" to id,
                        "error" to jsonObj(
                            "code" to (-32601),
                            "message" to "不支持的方法：$method"
                        )
                    )
                )
                return
            }
        }

        respondJson(
            exchange,
            jsonObj("jsonrpc" to "2.0", "id" to id, "result" to result)
        )
    }

    private fun handleInitialize(): Json.Obj = jsonObj(
        "protocolVersion" to McpConnection.PROTOCOL_VERSION,
        "capabilities" to jsonObj(
            "tools" to jsonObj("listChanged" to false)
        ),
        "serverInfo" to jsonObj(
            "name" to "zhixueyao-ide",
            "version" to "0.2.0"
        ),
        "instructions" to "止血药 IDE 服务：提供当前 IDE 项目中的代码搜索、文件读取、" +
            "编译诊断与构建能力。路径可相对于项目根目录。"
    )

    /**
     * 新协议的发现接口（2026-07-28 起）。
     *
     * 该版本取消了 initialize 握手，客户端先调 server/discover 了解服务端
     * 支持哪些版本与能力。我们同时支持新旧两代，所以这里把两个版本都列出来。
     */
    private fun handleDiscover(): Json.Obj = jsonObj(
        "protocolVersions" to jsonArr(
            McpConnection.PROTOCOL_VERSION.toJson(),
            "2026-07-28".toJson(),
            McpConnection.LEGACY_PROTOCOL_VERSION.toJson()
        ),
        "capabilities" to jsonObj(
            "tools" to jsonObj("listChanged" to false)
        ),
        "serverInfo" to jsonObj(
            "name" to "zhixueyao-ide",
            "version" to "0.2.0"
        ),
        "instructions" to "止血药 IDE 服务：提供当前 IDE 项目中的代码搜索、文件读取、" +
            "编译诊断与构建能力。路径可相对于项目根目录。"
    )

    private fun handleToolsList(): Json.Obj {
        val tools = Json.Arr()
        for (t in toolsProvider()) {
            tools.add(
                jsonObj(
                    "name" to t.name,
                    "description" to t.description,
                    "inputSchema" to t.parameters
                )
            )
        }
        return jsonObj("tools" to tools)
    }

    private fun handleToolsCall(params: Json.Obj): Json {
        val name = params.str("name") ?: return errorResult("缺少 name 参数")
        val args = params.obj("arguments") ?: Json.Obj()

        val project = projectProvider()
            ?: return errorResult("当前没有打开的项目，请在 IDE 中打开项目后重试")

        val tool = toolsProvider().firstOrNull { it.name == name }
            ?: return errorResult("不存在名为 $name 的工具")

        return try {
            // 工具内部会自行做线程切换；这里在 HTTP 工作线程上直接执行
            val result = tool.execute(project, args)
            jsonObj(
                "content" to jsonArr(
                    jsonObj(
                        "type" to "text",
                        "text" to result.text
                    )
                ),
                "isError" to !result.ok
            )
        } catch (e: Exception) {
            log.warn("MCP 工具 $name 执行失败", e)
            errorResult("${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
        }
    }

    private fun errorResult(message: String): Json = jsonObj(
        "content" to jsonArr(jsonObj("type" to "text", "text" to message)),
        "isError" to true
    )

    private fun respondJson(exchange: HttpExchange, body: Json) {
        respond(exchange, 200, body.stringify(), "application/json")
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8"
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (body.isNotEmpty()) {
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.responseHeaders.add("Mcp-Protocol-Version", McpConnection.PROTOCOL_VERSION)
        }
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        /** 供 IDE 外部客户端使用的配置片段。 */
        fun clientConfigJson(port: Int): String = jsonObj(
            "mcpServers" to jsonObj(
                "zhixueyao-ide" to jsonObj(
                    "type" to "streamable-http",
                    "url" to "http://127.0.0.1:$port/"
                )
            )
        ).stringifyPretty()
    }
}
