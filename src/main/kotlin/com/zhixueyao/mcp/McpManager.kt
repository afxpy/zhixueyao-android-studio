package com.zhixueyao.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArrOf
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 服务器配置项。
 * 兼容 Claude Desktop / Cursor 的 mcp.json 结构，便于直接导入现有配置。
 */
data class McpServerConfig(
    /** 唯一标识，用于工具名前缀，避免不同服务器的同名工具冲突 */
    var id: String = "",
    var name: String = "",
    /** stdio | http */
    var type: String = "stdio",
    /** stdio 方式：可执行命令（第一个元素是程序，其余是参数） */
    var command: String = "",
    /** 附加环境变量，常用于传递 API Key */
    var env: MutableMap<String, String> = mutableMapOf(),
    /** http 方式：服务地址 */
    var url: String = "",
    /** http 方式的额外请求头 */
    var headers: MutableMap<String, String> = mutableMapOf(),
    var enabled: Boolean = true,
    /** 上次连接失败的原因，供界面展示 */
    var lastError: String = ""
)

@State(name = "ZhixueyaoMcpServers", storages = [Storage("zhixueyao-mcp.xml")])
class McpServerRegistry : PersistentStateComponent<McpServerRegistry> {

    var servers: MutableList<McpServerConfig> = mutableListOf()

    override fun getState(): McpServerRegistry = this

    override fun loadState(state: McpServerRegistry) {
        servers = state.servers
    }

    companion object {
        fun getInstance(): McpServerRegistry =
            ApplicationManager.getApplication().getService(McpServerRegistry::class.java)
    }
}

/** 一个已建立连接的服务器及其工具清单。 */
class LiveServer(
    val config: McpServerConfig,
    val connection: McpConnection,
    val tools: List<McpTool>
) : AutoCloseable {
    override fun close() = connection.close()
}

/**
 * MCP 服务器连接池。
 *
 * 工具名统一加 `mcp__{serverId}__{toolName}` 前缀，
 * 既避免跨服务器重名，也让模型能直观看出工具来源。
 */
class McpManager {

    private val live = ConcurrentHashMap<String, LiveServer>()
    private val errors = ConcurrentHashMap<String, String>()

    /**
     * 建立（或重建）指定服务器的连接。
     *
     * `@Synchronized` 是必需的：这个方法开头会 `disconnect(id)` 再建新连接，
     * 两次并发调用同一个 id 的话 —— 两边都会先断开、再各建一个传输，
     * 而 `live[id]` 只留得下最后一个。**第一个连接就成了没人认得、也没人会关的泄漏**
     * （进程 + 线程 + socket 全留着）。
     *
     * 加锁的代价可以忽略：连接本身是秒级的慢操作，而且不常发生。
     */
    @Synchronized
    fun connect(config: McpServerConfig): LiveServer {
        disconnect(config.id)

        val transport = try {
            buildTransport(config)
        } catch (e: Exception) {
            errors[config.id] = e.message ?: "构建传输失败"
            throw e
        }

        val connection = McpConnection(config.name.ifBlank { config.id }, transport)
        try {
            (transport as? StdioTransport)?.start()
            connection.initialize("zhixueyao", "0.2.0")
            val tools = connection.listTools()
            val server = LiveServer(config, connection, tools)
            live[config.id] = server
            errors.remove(config.id)
            return server
        } catch (e: Exception) {
            val detail = buildString {
                append(e.message ?: e.javaClass.simpleName)
                if (transport is StdioTransport) {
                    val err = transport.recentStderr()
                    if (err != "无错误输出") append("\n服务器输出：").append(err)
                }
            }
            errors[config.id] = detail
            runCatching { connection.close() }
            throw McpException(detail)
        }
    }

    /** 连接所有已启用的服务器，单个失败不影响其余。 */
    fun connectAll(configs: List<McpServerConfig>): Map<String, String> {
        val failures = mutableMapOf<String, String>()
        for (cfg in configs) {
            if (!cfg.enabled) continue
            try {
                connect(cfg)
            } catch (e: Exception) {
                failures[cfg.id] = e.message ?: "连接失败"
            }
        }
        return failures
    }

    @Synchronized
    fun disconnect(id: String) {
        live.remove(id)?.let { runCatching { it.close() } }
    }

    /**
     * 把已经死掉的连接从 [live] 里摘掉，并记下原因。
     *
     * 在**所有对外查询之前**调用。不这么做的话：
     * 服务器进程崩了 → `live` 里还留着它 → `isConnected` 说 true、
     * `stats()` 报它的工具数、`allTools()` 把它的工具发给模型 ——
     * **界面在说谎，模型会反复去调一个已经不存在的工具。**
     *
     * 这里选择「摘掉 + 记原因」而不是「自动重连」：自动重连会掩盖问题
     * （用户永远不会知道服务器崩过），而且重连本身也可能反复失败。
     * 摘掉之后 `errorOf(id)` 能给出原因，用户在设置页看得见。
     */
    @Synchronized
    private fun pruneDead() {
        val dead = live.entries.filter { (_, server) ->
            runCatching { !server.connection.isAlive() }.getOrDefault(false)
        }
        for ((id, server) in dead) {
            live.remove(id)
            if (errors[id] == null) {
                errors[id] = "服务器进程已退出（连接已失效）。可在设置页重新连接。"
            }
            runCatching { server.close() }
        }
    }

    fun disconnectAll() {
        live.keys.toList().forEach { disconnect(it) }
    }

    fun isConnected(id: String): Boolean {
        pruneDead()
        return live.containsKey(id)
    }

    fun errorOf(id: String): String? = errors[id]

    fun toolCount(id: String): Int = live[id]?.tools?.size ?: 0

    /** 汇总所有已连接服务器的工具，名称已加前缀。 */
    fun allTools(): List<PrefixedTool> {
        pruneDead()
        return live.values.flatMap { server ->
            server.tools.map { PrefixedTool(server.config.id, server.config.name, it) }
        }
    }

    /**
     * 汇总各服务器在 initialize 时下发的使用说明。
     *
     * 协议专门设计这个字段，就是为了让模型知道「这家服务器的工具该怎么配合使用」
     * ——例如 everything 服务器会用上百字说明演示顺序、资源引用方式。
     * 此前我们解析了却丢掉，等于白拿了一份现成的上下文。
     *
     * @return 每项为「服务器名 → 说明」，已过滤空值
     */
    fun allInstructions(): List<Pair<String, String>> {
        pruneDead()
        return live.values.mapNotNull { server ->
            val text = server.connection.instructions.trim()
            if (text.isEmpty()) null else server.config.name to text
        }
    }

    /** 已连接服务器数量与工具总数，供状态展示。 */
    fun stats(): Pair<Int, Int> {
        pruneDead()
        return live.size to live.values.sumOf { it.tools.size }
    }

    /** 调用工具。传入的是加前缀后的名称。 */
    fun callTool(
        prefixedName: String,
        arguments: Json,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    ): McpToolResult {
        pruneDead()
        val parsed = parsePrefixed(prefixedName)
            ?: throw McpException("无法识别的工具名称：$prefixedName")
        val server = live[parsed.first]
            ?: throw McpException("MCP 服务器 ${parsed.first} 未连接")
        val real = server.tools.firstOrNull { it.name == parsed.second }
            ?: throw McpException("服务器 ${parsed.first} 上不存在工具 ${parsed.second}")
        return server.connection.callTool(real.name, arguments, cancelFlag)
    }

    private fun buildTransport(config: McpServerConfig): McpTransport = when (config.type) {
        "http" -> {
            if (config.url.isBlank()) throw McpException("HTTP 类型服务器缺少地址")
            StreamableHttpTransport(config.url, config.headers)
        }
        else -> {
            val parts = splitCommand(config.command)
            if (parts.isEmpty()) throw McpException("stdio 类型服务器缺少启动命令")
            StdioTransport(parts, config.env)
        }
    }

    companion object {
        const val PREFIX = "mcp__"
        private const val SEP = "__"

        /**
         * 全局共享的连接池。
         *
         * 之前 `McpManager()` 被 new 了三处（ChatPanel 一个、设置页测试连接一个、
         * 工具一个），**谁都不知道别人连了什么**。于是「装个 MCP」的工具在自己那份
         * 实例上折腾，agent 读的却是 ChatPanel 那份，新工具永远不会出现 ——
         * 用户反馈的「不知道有没有装好就回答了」根子在这里。
         *
         * 设置页的「测试连接」仍然用临时实例：它只要探活，不需要保持连接。
         */
        private val shared: McpManager by lazy { McpManager() }

        fun getInstance(): McpManager = shared

        fun prefixed(serverId: String, toolName: String): String =
            "$PREFIX${sanitize(serverId)}$SEP$toolName"

        fun parsePrefixed(name: String): Pair<String, String>? {
            if (!name.startsWith(PREFIX)) return null
            val rest = name.removePrefix(PREFIX)
            val idx = rest.indexOf(SEP)
            if (idx <= 0) return null
            return rest.substring(0, idx) to rest.substring(idx + SEP.length)
        }

        /** 工具名只允许字母数字下划线连字符，其余替换掉 */
        private fun sanitize(s: String): String =
            s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

        /**
         * 按 shell 规则切分命令，支持引号包裹带空格的路径。
         * 例：`npx -y "@scope/pkg" --dir "C:/My Dir"` 会被正确切分。
         */
        fun splitCommand(command: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < command.length) {
                val c = command[i]
                when {
                    quote != null -> {
                        if (c == quote) quote = null else sb.append(c)
                    }
                    c == '"' || c == '\'' -> quote = c
                    c == ' ' || c == '\t' -> {
                        if (sb.isNotEmpty()) {
                            out.add(sb.toString()); sb.setLength(0)
                        }
                    }
                    else -> sb.append(c)
                }
                i++
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }

        /**
         * 解析标准 mcp.json 内容，兼容 Claude Desktop / Cursor 两种写法。
         */
        fun parseConfigJson(text: String): List<McpServerConfig> {
            val root = Json.parse(text).asObjOrNull ?: throw McpException("配置文件格式不正确")
            val serversNode = root.obj("mcpServers")
                ?: root.obj("servers")
                ?: throw McpException("未找到 mcpServers 字段")
            val result = mutableListOf<McpServerConfig>()
            for ((key, value) in serversNode.fields) {
                val node = value.asObjOrNull ?: continue
                val url = node.str("url") ?: node.str("serverUrl")
                val isHttp = url != null && node.str("command") == null

                val cfg = McpServerConfig(
                    id = key,
                    name = key,
                    type = if (isHttp) "http" else "stdio",
                    command = node.str("command")?.let { cmd ->
                        val args = node.arr("args")?.items?.mapNotNull { it.asStringOrNull } ?: emptyList()
                        (listOf(cmd) + args).joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
                    } ?: "",
                    url = url ?: "",
                    enabled = node.bool("enabled") ?: node.bool("disabled")?.not() ?: true
                )
                node.obj("env")?.fields?.forEach { (k, v) ->
                    v.asStringOrNull?.let { cfg.env[k] = it }
                }
                node.obj("headers")?.fields?.forEach { (k, v) ->
                    v.asStringOrNull?.let { cfg.headers[k] = it }
                }
                result.add(cfg)
            }
            return result
        }

        /** 导出为 mcp.json 格式，便于与其他工具互通。 */
        fun exportConfigJson(configs: List<McpServerConfig>): String {
            val servers = Json.Obj()
            for (c in configs) {
                if (c.type == "http") {
                    val o = jsonObj("url" to c.url.toJson())
                    if (c.headers.isNotEmpty()) {
                        val headers = Json.Obj()
                        c.headers.forEach { (k, v) -> headers[k] = v.toJson() }
                        o["headers"] = headers
                    }
                    servers[c.id] = o
                } else {
                    val parts = splitCommand(c.command)
                    val o = jsonObj(
                        "command" to (parts.firstOrNull() ?: "").toJson(),
                        // 注意：这里必须展开为一个个独立字符串，
                        // 否则会写成 ["npx", ["-y", "xxx"]] 这种嵌套结构，外部工具无法识别
                        "args" to jsonArrOf(parts.drop(1))
                    )
                    if (c.env.isNotEmpty()) {
                        val env = Json.Obj()
                        c.env.forEach { (k, v) -> env[k] = v.toJson() }
                        o["env"] = env
                    }
                    servers[c.id] = o
                }
            }
            return jsonObj("mcpServers" to servers).stringifyPretty()
        }
    }
}

data class PrefixedTool(
    val serverId: String,
    val serverName: String,
    val tool: McpTool
)
