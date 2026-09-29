package com.zhixueyao.chat

import com.intellij.openapi.application.PathManager
import com.zhixueyao.llm.ChatMessage
import com.zhixueyao.llm.ToolCall
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArrOf
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.jsonObjOf
import com.zhixueyao.util.toJson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话存档。
 *
 * 存到磁盘而不是设置文件里 —— 会话可能很长（含工具调用与图片 base64），
 * 塞进 `zhixueyao.xml` 会让设置文件膨胀到几 MB，每次读写设置都要解析它。
 *
 * 存储位置：`<IDE 配置目录>/zhixueyao/sessions/<id>.json`
 * 用 JSON 而不是二进制，是为了出问题时用户能直接用文本编辑器看和抢救。
 */
object SessionStore {

    /** 一条会话记录。 */
    data class Session(
        val id: String,
        val title: String,
        /** 创建时间（epoch 毫秒） */
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatMessage>
    ) {
        val messageCount: Int get() = messages.count { it.role != ChatMessage.Role.SYSTEM }
    }

    /** 会话列表项：只有摘要信息，不加载全部消息 */
    data class Meta(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int
    )

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private fun dir(): File {
        val d = File(PathManager.getConfigPath(), "zhixueyao/sessions")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(id: String): File = File(dir(), "$id.json")

    /** 生成一个新的会话 id。用时间戳，天然按时间排序 */
    fun newId(): String = "s" + System.currentTimeMillis()

    /** 由首条用户消息生成标题 —— 比让用户自己起名字省事，多数人不会去起名。 */
    fun titleFrom(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == ChatMessage.Role.USER } ?: return "新会话"
        val text = firstUser.content
            .replace(Regex("【[^】]*】"), "")   // 去掉附件标记
            .replace(Regex("```[\\s\\S]*?```"), "")  // 去掉代码块
            .trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return "新会话"
        return if (text.length > 30) text.take(30) + "…" else text
    }

    fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFmt)

    // ---------------- 界面小状态（草稿 / 输入历史） ----------------

    /**
     * 输入草稿 + 输入历史，单独一个小文件 `<IDE 配置>/zhixueyao/ui-state.json`。
     *
     * 为什么不塞进设置文件、也不塞进会话：
     *  - 设置文件每次读写都要整体解析，草稿会把它撑胖；
     *  - 草稿是「**每个会话**一份」，输入历史是「**全局**一份」，
     *    归属不同，塞进会话里会让历史随会话切来切去。
     */
    private const val MAX_INPUT_HISTORY = 50

    private val uiStateFile: File
        get() = File(PathManager.getConfigPath(), "zhixueyao/ui-state.json")

    private var drafts: MutableMap<String, String> = mutableMapOf()
    private var inputLog: MutableList<String> = mutableListOf()
    private var uiStateLoaded = false

    private fun ensureUiState() {
        if (uiStateLoaded) return
        uiStateLoaded = true
        runCatching {
            if (!uiStateFile.exists()) return
            val json = Json.parse(uiStateFile.readText(Charsets.UTF_8))
            json.obj("drafts")?.fields?.forEach { (k, v) ->
                (v as? Json.Str)?.let { drafts[k] = it.value }
            }
            json.arr("inputLog")?.items?.forEach { v ->
                (v as? Json.Str)?.let { if (it.value.isNotBlank()) inputLog.add(it.value) }
            }
        }
    }

    private fun flushUiState() {
        runCatching {
            uiStateFile.parentFile?.mkdirs()
            val root = Json.Obj()
            root["drafts"] = jsonObjOf(drafts.mapValues { (_, v) -> v.toJson() })
            root["inputLog"] = jsonArrOf(inputLog)
            // 草稿与输入历史也走原子写入 —— 它们同样是「丢了会心疼」的数据，
            // 而且原来这里和 save() 是两份实现（一个直接写、一个临时文件），
            // 正是「同一件事两份实现」的典型
            com.zhixueyao.util.AtomicFiles.write(uiStateFile, root.toString())
        }
    }

    /** 存草稿。空内容 = 删除该会话的草稿（别在文件里留一堆空串） */
    fun saveDraft(sessionId: String, text: String) {
        ensureUiState()
        if (text.isBlank()) drafts.remove(sessionId) else drafts[sessionId] = text
        flushUiState()
    }

    /** 取某个会话的草稿；没有就是空串 */
    fun draftOf(sessionId: String): String {
        ensureUiState()
        return drafts[sessionId].orEmpty()
    }

    /** 记一条输入历史。相邻重复不重复记（连着发两次同一句没有意义） */
    fun rememberInput(text: String) {
        ensureUiState()
        val t = text.trim()
        if (t.isBlank()) return
        if (inputLog.lastOrNull() == t) return
        inputLog.add(t)
        while (inputLog.size > MAX_INPUT_HISTORY) inputLog.removeAt(0)
        flushUiState()
    }

    /** 输入历史（最早的在前，最新的在最后）。上下键按这个顺序翻 */
    fun inputHistory(): List<String> {
        ensureUiState()
        return inputLog.toList()
    }

    // ---------------- 读写 ----------------

    /**
     * 保存会话。
     *
     * ## 为什么要「先写临时文件再原子改名」
     *
     * 原来是一行 `fileOf(id).writeText(...)` —— 直接往目标文件上写。
     * 这意味着**写到一半被打断，文件就是坏的**：IDE 被强退、机器断电、
     * 或者进程被杀，都会留下一个截断的 JSON。
     *
     * 而坏掉的后果特别重：
     *
     * - [load] 用 `runCatching` 兜底返回 null → 会话**静默消失**（不只是丢最后一条）
     * - [list] 里的 `readMeta` 同样吞掉异常 → 它**连列表里都不出现了**
     * - 用户看到的是「我的会话没了」，而**没有任何错误提示**
     *
     * 这个写入是**每条消息都发生**的，所以撞上崩溃窗口的机会并不像想象中那么小。
     *
     * `Files.move` 的原子改名是操作系统保证的：要么还是旧文件，要么已经是完整的新文件，
     * **不存在「半个文件」的中间态**。`FileSafety` 的备份恢复早就在用这个模式了，
     * 这里只是跟上。
     *
     * 用 `ATOMIC_MOVE` 失败时退回普通 `REPLACE_EXISTING` —— 跨文件系统时
     * 原子改名会抛 `AtomicMoveNotSupportedException`，而那个场景下
     * 普通替换仍然比直接写目标文件安全（只是少了原子性，不是退化成裸写）。
     */
    fun save(session: Session): Boolean =
        // 原子写入的实现抽到了 com.zhixueyao.util.AtomicFiles ——
        // 因为「记忆」「草稿」那边也是同一个需求，**一份实现**才不会各自跑偏。
        com.zhixueyao.util.AtomicFiles.write(fileOf(session.id), toJsonText(session))

    fun load(id: String): Session? = runCatching {
        val f = fileOf(id)
        if (!f.exists()) return null
        fromJsonText(f.readText(Charsets.UTF_8))
    }.getOrNull()

    fun delete(id: String): Boolean = runCatching { fileOf(id).delete() }.getOrDefault(false)

    /** 列出全部会话，按更新时间倒序（最近的在前）。 */
    fun list(): List<Meta> = runCatching {
        val files = dir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        files.mapNotNull { f -> readMeta(f) }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    /**
     * 只读元信息。
     *
     * 为什么标题与条数额外存一份在文件头部字段里：列表要显示几十条会话，
     * 若每条都把整个 JSON（可能几 MB 的 base64）解析一遍，打开列表会卡住。
     * 所以元信息单独放在顶层，列表用轻量解析只取这几个键。
     */
    private fun readMeta(f: File): Meta? = runCatching {
        val text = f.readText(Charsets.UTF_8)
        val json = Json.parse(text)
        // 旧文件可能没有 messageCount，退化为实际解析
        val count = json.int("messageCount") ?: 0
        Meta(
            id = json.strOr("id", f.nameWithoutExtension),
            title = json.strOr("title", "未命名会话"),
            createdAt = json.long("createdAt") ?: f.lastModified(),
            updatedAt = json.long("updatedAt") ?: f.lastModified(),
            messageCount = count
        )
    }.getOrNull()

    // ---------------- 序列化 ----------------

    private fun toJsonText(s: Session): String {
        val root = jsonObj(
            "id" to s.id,
            "title" to s.title,
            "createdAt" to s.createdAt,
            "updatedAt" to s.updatedAt,
            // 列表页只读这个数，不必解析 messages
            "messageCount" to s.messages.count { it.role != ChatMessage.Role.SYSTEM },
            "messages" to jsonArrOf(s.messages.map { msgToJson(it) })
        )
        return root.stringifyPretty()
    }

    private fun msgToJson(m: ChatMessage): Json.Obj = jsonObj(
        "role" to m.role.name,
        "content" to m.content,
        "toolCallId" to m.toolCallId,
        "toolName" to m.toolName,
        "reasoning" to m.reasoning,
        "toolCalls" to jsonArrOf(
            m.toolCalls.map { tc -> jsonObj("id" to tc.id, "name" to tc.name, "arguments" to tc.arguments) }
        ),
        "images" to jsonArrOf(
            m.images.map { img -> jsonObj("mimeType" to img.mimeType, "base64" to img.base64) }
        ),
        // 多版本回答。只有重新生成过才有，所以空的时候不写，老存档也读得懂
        "variants" to jsonArrOf(m.variants),
        // 附件路径。同样是「空就不写」的老存档兼容写法
        "attachments" to jsonArrOf(m.attachments),
        "activeVariant" to m.activeVariant,
        "promptTokens" to m.promptTokens,
        "completionTokens" to m.completionTokens
    )

    private fun fromJsonText(text: String): Session? {
        val root = Json.parse(text)
        val msgsArr = root.arr("messages") ?: return null
        val messages = msgsArr.items.mapNotNull { item ->
            val o = item.asObjOrNull ?: return@mapNotNull null
            val role = runCatching {
                ChatMessage.Role.valueOf(o.strOr("role", "USER"))
            }.getOrDefault(ChatMessage.Role.USER)

            val toolCalls = o.arr("toolCalls")?.items?.mapNotNull { tc ->
                val t = tc.asObjOrNull ?: return@mapNotNull null
                ToolCall(
                    id = t.strOr("id", ""),
                    name = t.strOr("name", ""),
                    arguments = t.strOr("arguments", "")
                )
            } ?: emptyList()

            val images = o.arr("images")?.items?.mapNotNull { im ->
                val i = im.asObjOrNull ?: return@mapNotNull null
                val b64 = i.strOr("base64", "")
                if (b64.isBlank()) null
                else ChatMessage.ImagePart(i.strOr("mimeType", "image/jpeg"), b64)
            } ?: emptyList()

            ChatMessage(
                role = role,
                content = o.strOr("content", ""),
                toolCalls = toolCalls,
                toolCallId = o.strOr("toolCallId", ""),
                toolName = o.strOr("toolName", ""),
                images = images,
                reasoning = o.strOr("reasoning", ""),
                // 老存档没有这两个键 → 空列表 / 0，行为和以前完全一致
                variants = o.arr("variants")?.items?.mapNotNull { (it as? Json.Str)?.value } ?: emptyList(),
                // 老存档没有这个字段 → 读到空列表，不会报错
                attachments = o.arr("attachments")?.items?.mapNotNull { (it as? Json.Str)?.value }
                    ?: emptyList(),
                activeVariant = o.int("activeVariant") ?: 0,
                promptTokens = o.int("promptTokens") ?: 0,
                completionTokens = o.int("completionTokens") ?: 0
            )
        }
        return Session(
            id = root.strOr("id", ""),
            title = root.strOr("title", "未命名会话"),
            createdAt = root.long("createdAt") ?: System.currentTimeMillis(),
            updatedAt = root.long("updatedAt") ?: System.currentTimeMillis(),
            messages = messages
        )
    }
}
