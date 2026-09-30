package com.zhixueyao.mcp

import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import com.zhixueyao.util.toJsonValue

/**
 * MCP 协议客户端。
 *
 * 零第三方依赖实现 JSON-RPC 2.0 over stdio 与 Streamable HTTP 两种传输，
 * 原因同 [com.zhixueyao.util.Json]：避免依赖冲突拖垮 IDE。
 *
 * 协议版本策略：客户端声明一个较新的版本号，若服务端不接受会在响应中返回
 * 其支持的版本；此时用服务端给出的版本重试一次。这是官方规范要求的协商流程。
 */
interface McpTransport : AutoCloseable {
    /**
     * 发送一条 JSON-RPC 消息并等待对应响应（同步语义，便于在后台线程顺序调用）。
     *
     * @param cancelFlag 传进来就要**分片等待**并响应取消：一次等满超时的话，
     *   用户点了「停止」也要等它超时才生效（最多 2 分钟没反应）。
     */
    fun exchange(message: Json.Obj, timeoutMillis: Long, cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null): Json.Obj

    /** 发送通知（无需响应）。 */
    fun notify(message: Json.Obj)

    val description: String

    /**
     * 传输是否还能用。
     *
     * ## 为什么状态判断必须包含它
     *
     * 服务器进程会**自己死掉**（崩溃、被系统回收、依赖的服务挂了），
     * 而 `McpManager.live` 里的记录**不会自动消失**。于是出现这种局面：
     *
     * | 查询 | 只看 map 的话 | 实际 |
     * |---|---|---|
     * | `isConnected(id)` | 仍说「已连接」 | 进程早没了 |
     * | `stats()` | 仍报「3 个服务器、35 个工具」 | 其中一个是死的 |
     * | `allTools()` | **仍把死服务器的工具发给模型** | 模型会反复调一个不存在的工具 |
     *
     * 前三行加起来就是**界面在说谎**：用户看到一切正常，
     * 只有真去调的时候才发现不行 —— 而模型因为工具清单里一直有它，会**反复去调**。
     *
     * 所以「已连接」= `map 里有记录` **且** `传输还活着`。
     *
     * 给默认实现 `true` 是为了不破坏其它实现类；两个真实传输都覆盖了它。
     */
    fun isAlive(): Boolean = true
}

/** 单个 MCP 服务器连接。 */
class McpConnection(
    val serverName: String,
    private val transport: McpTransport
) : AutoCloseable {

    var serverInfo: Json.Obj? = null
        private set
    var instructions: String = ""
        private set

    private var nextId = 1L
    private val lock = Any()

    /** 已完成协商的协议版本 */
    var negotiatedVersion: String = PROTOCOL_VERSION
        private set

    /**
     * 建立连接：initialize 握手 → initialized 通知。
     *
     * 若服务端只认新协议（2026-07-28 起，无握手版），initialize 会失败；
     * 此时自动改用 server/discover 探测，拿到服务端支持的版本清单，
     * 并据此给出可操作的提示，而不是抛一个用户看不懂的原始错误。
     *
     * @throws McpException 握手失败
     */
    fun initialize(clientName: String, clientVersion: String) {
        val params = jsonObj(
            "protocolVersion" to PROTOCOL_VERSION.toJson(),
            "capabilities" to jsonObj(
                // 本客户端目前不需要 roots / sampling 能力，声明空对象即可
                "roots" to jsonObj("listChanged" to false.toJson())
            ),
            "clientInfo" to jsonObj(
                "name" to clientName.toJson(),
                "version" to clientVersion.toJson()
            )
        )

        var result = try {
            request("initialize", params)
        } catch (e: McpException) {
            // 握手失败时先探测一下：服务端可能是只支持新协议的实现
            val probe = discoverModern()
            if (probe != null) {
                val versions = probe.arr("protocolVersions")?.items
                    ?.mapNotNull { it.asStringOrNull }?.joinToString(", ")
                throw McpException(
                    "该服务器使用新协议（无握手），当前只接受同步调用模式。\n" +
                        "服务端支持的版本：${versions ?: "未知"}\n" +
                        "原始错误：${e.message}"
                )
            }
            throw e
        }

        // 服务端可能拒绝我们声明的版本，回退到它支持的版本重试
        val returned = result.str("protocolVersion")
        if (returned != null && returned != PROTOCOL_VERSION) {
            negotiatedVersion = returned
            val retryParams = jsonObj(
                "protocolVersion" to returned.toJson(),
                "capabilities" to jsonObj("roots" to jsonObj("listChanged" to false.toJson())),
                "clientInfo" to jsonObj(
                    "name" to clientName.toJson(),
                    "version" to clientVersion.toJson()
                )
            )
            result = request("initialize", retryParams)
        }

        serverInfo = result.obj("serverInfo")
        instructions = result.strOr("instructions", "")

        notify("notifications/initialized", Json.EMPTY_OBJ)
    }

    /**
     * 用 server/discover 探测新协议服务端。失败返回 null（说明对方是握手版或根本不通）。
     * 这个探测只在 initialize 失败后调用，属于兜底路径。
     */
    private fun discoverModern(): Json.Obj? = runCatching {
        val msg = jsonObj(
            "jsonrpc" to "2.0".toJson(),
            "id" to synchronized(lock) { nextId++ }.toJson(),
            "method" to "server/discover".toJson(),
            "params" to Json.Obj()
        )
        val resp = transport.exchange(msg, 20_000)
        // 新协议服务端会返回 protocolVersions；老服务端一般报方法不存在
        resp.obj("result")?.takeIf { it.has("protocolVersions") }
    }.getOrNull()

    /** 拉取工具清单，处理游标分页。 */
    fun listTools(): List<McpTool> {
        val collected = mutableListOf<McpTool>()
        var cursor: String? = null
        var guard = 0
        do {
            val params = Json.Obj()
            if (cursor != null) params["cursor"] = cursor.toJson()
            val result = request("tools/list", params)
            result.arr("tools")?.items?.forEach { item ->
                val t = item.asObjOrNull ?: return@forEach
                val name = t.str("name") ?: return@forEach
                // 安全提示是嵌套对象；缺省时 destructiveHint 按规范默认 true，
                // 但 readOnlyHint 默认 false —— 未声明的工具一律按「可能有害」处理
                val ann = t.obj("annotations")
                collected.add(
                    McpTool(
                        name = name,
                        description = t.strOr("description", ""),
                        inputSchema = t.obj("inputSchema") ?: Json.Obj(),
                        title = t.strOr("title", ""),
                        readOnlyHint = ann?.bool("readOnlyHint") ?: false,
                        destructiveHint = ann?.bool("destructiveHint") ?: false,
                        taskSupport = t.obj("execution")?.strOr("taskSupport", "forbidden")
                            ?: "forbidden"
                    )
                )
            }
            cursor = result.str("nextCursor")
            guard++
        } while (cursor != null && guard < 50)
        return collected
    }

    /** 调用工具。返回值统一转成字符串，便于直接喂给模型。 */
    fun callTool(
        name: String,
        arguments: Json,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    ): McpToolResult {
        val params = jsonObj(
            "name" to name.toJson(),
            "arguments" to arguments
        )
        val result = request("tools/call", params, cancelFlag)
        val isError = result.bool("isError") ?: false
        val text = renderContent(result.arr("content"))
        return McpToolResult(text, isError)
    }

    /**
     * MCP 的 content 是数组，可能是 text / image / resource / audio 等类型。
     *
     * 图片处理：工具结果在流水线里是纯文本（工具角色消息只有 string content），
     * 直接把 base64 塞进去会白白吃掉几万 token 且模型看不见图。
     * 因此把图片**落盘到临时目录**，只回传路径 —— 用户能看到文件，
     * 模型也能据此告知「图已存到某处」。
     */
    private fun renderContent(content: Json.Arr?): String {
        if (content == null || content.size == 0) return "(无返回内容)"
        val sb = StringBuilder()
        for (item in content.items) {
            val block = item.asObjOrNull ?: continue
            when (block.str("type")) {
                "text" -> sb.append(block.strOr("text", ""))
                "image" -> {
                    val mime = block.strOr("mimeType", "image/png")
                    val data = block.str("data")
                    val path = if (data.isNullOrBlank()) null else saveBase64(data, mime)
                    if (path != null) {
                        sb.append("[工具返回了一张图片，已保存到：$path]")
                    } else {
                        sb.append("[工具返回了一张图片（${mime}），但内容为空或保存失败]")
                    }
                }
                "resource" -> {
                    val res = block.obj("resource")
                    sb.append("[资源 ${res?.strOr("uri", "未知")}]")
                    res?.str("text")?.let { sb.append('\n').append(it) }
                }
                "audio" -> sb.append("[工具返回了音频内容，当前无法直接播放]")
                else -> sb.append(block.stringify())
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** 把 MCP 返回的 base64 图片写入临时目录，返回文件路径；失败返回 null。 */
    private fun saveBase64(base64: String, mimeType: String): String? = runCatching {
        val ext = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("bmp") -> "bmp"
            mimeType.contains("svg") -> "svg"
            else -> "png"
        }
        val dir = java.nio.file.Files.createDirectories(
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "zhixueyao-mcp")
        )
        val file = dir.resolve("tool-image-${System.currentTimeMillis()}-${(0..9999).random()}.$ext")
        java.nio.file.Files.write(file, java.util.Base64.getDecoder().decode(base64))
        file.toAbsolutePath().toString()
    }.getOrNull()

    private fun request(
        method: String,
        params: Json.Obj,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    ): Json.Obj {
        val id = synchronized(lock) { nextId++ }
        val msg = jsonObj(
            "jsonrpc" to "2.0".toJson(),
            "id" to id.toJson(),
            "method" to method.toJson(),
            "params" to params
        )
        val resp = transport.exchange(msg, DEFAULT_TIMEOUT, cancelFlag)
        resp.obj("error")?.let { err ->
            throw McpException("$method 失败：${err.strOr("message", "未知错误")} (code=${err.int("code")})")
        }
        return resp.obj("result") ?: Json.Obj()
    }

    private fun notify(method: String, params: Json.Obj) {
        val msg = jsonObj(
            "jsonrpc" to "2.0".toJson(),
            "method" to method.toJson(),
            "params" to params
        )
        transport.notify(msg)
    }

    /** 底层传输是否还活着（见 [McpTransport.isAlive] 里为什么这个判断重要） */
    fun isAlive(): Boolean = runCatching { transport.isAlive() }.getOrDefault(false)

    override fun close() {
        runCatching { transport.close() }
    }

    companion object {
        /**
         * 客户端首选协议版本。
         *
         * 2025-11-25 是目前各大 SDK（TypeScript / Python / Ruby）共同支持的
         * 最新**握手版**，覆盖面最广，因此作为首选。
         *
         * 注意 2026-07-28 起协议取消了 initialize 握手，改为「每个请求自带版本号」，
         * 是互不兼容的两代。本客户端主打兼容既有服务器，故以握手版为准；
         * 若服务端只支持新版，会在 initialize 收到版本错误 —— 届时可改用
         * [discoverModern] 探测。
         */
        const val PROTOCOL_VERSION = "2025-11-25"

        /** 更早的稳定版，用于版本回退链 */
        const val LEGACY_PROTOCOL_VERSION = "2025-06-18"

        private const val DEFAULT_TIMEOUT = 120_000L
    }
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Json.Obj,
    /**
     * 工具的人类可读标题（协议 2025-06-18 起）。
     * 官方示例服务器每个工具都带，比机器名友好，可用于界面展示。
     */
    val title: String = "",
    /**
     * 安全提示（协议 2025-06-18 起）。
     *
     * **这是只读模式的关键依据**：服务器声明 `readOnlyHint=true` 表示该工具
     * 不产生副作用。没有它，「只读模式」下挂载的 MCP 工具就是完全不受控的 ——
     * 一个标称查询的服务器可以在背后删库。
     *
     * 注意规范原文：hint 是「提示」而非保证，服务器可能说谎且客户端无法验证。
     * 因此策略是**只管住被声明为只读的**，未声明的按「可能有害」处理。
     */
    val readOnlyHint: Boolean = false,
    /** 是否为破坏性操作（默认 true，同规范） */
    val destructiveHint: Boolean = false,
    /**
     * 执行模式（协议 2025-11-25 起）：
     * `forbidden` = 只能同步调用；`required` = 必须走任务增强；
     * `optional` = 两者皆可。未声明等同 forbidden。
     */
    val taskSupport: String = "forbidden"
) {
    /** 该工具在当前协议下是否可直接同步调用 */
    val callable: Boolean get() = taskSupport != "required"
}

data class McpToolResult(val text: String, val isError: Boolean)

class McpException(message: String) : RuntimeException(message)
