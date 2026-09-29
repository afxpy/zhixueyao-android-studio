package com.zhixueyao.llm

import com.zhixueyao.util.Json

/**
 * 对话消息。刻意做成与具体厂商无关的中立结构，
 * 由各 [LlmProvider] 实现负责翻译成自家的请求格式。
 */
data class ChatMessage(
    val role: Role,
    val content: String = "",
    /** 模型发起的工具调用（仅 assistant 角色使用） */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 工具执行结果（仅 tool 角色使用） */
    val toolCallId: String = "",
    /** 工具名称。部分厂商（如 Anthropic）在回灌工具结果时强制要求携带 */
    val toolName: String = "",
    /** 附带的图片，base64 编码（不含 data: 前缀） */
    val images: List<ImagePart> = emptyList(),
    /** 推理模型的思维链内容，仅用于界面展示，不回传 */
    val reasoning: String = "",
    /**
     * 同一条助手消息的**多个回答版本**（「重新生成」时追加，而不是把旧的丢掉）。
     *
     * 为空 = 只有 [content] 这一版（老存档、以及从没重新生成过的消息都是这样）。
     * 非空时 [content] 恒等于 `variants[activeVariant]` —— content 始终是**回传给模型的那一份**，
     * 版本列表只服务于界面切换。
     */
    val variants: List<String> = emptyList(),
    /** 当前显示的是第几版（[variants] 的下标） */
    val activeVariant: Int = 0,
    /**
     * 这一轮服务商回报的 token 用量（输入 / 输出）。
     *
     * 存进消息是为了**翻历史时也看得到**：状态栏那行只显示一会儿，
     * 下一个动作就被覆盖了（用户反馈「为什么我看不到使用了多少 token」）。
     * 0 = 服务商没回报（界面退回估算，不写进这里）。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,

    /**
     * 这一轮产出的**附件路径**（图片/文件），只给界面用，不回传。
     *
     * ## 为什么存在消息上
     *
     * 原来附件只活在界面上：`addAttachment` 把预览卡加进气泡，
     * 而**会话存档里一个字段都没有**。后果是**重开会话，图片全不见了** ——
     * 只剩正文里那句「已生成一张图片」（那是 Markdown 文本，存下来了）。
     *
     * 用户的原话：「怎么图片不见了？」
     *
     * 挂在消息上而不是会话上，是因为**附件属于某一轮回答**；
     * 和 [variants] 一样属于「只给界面看」的字段，所以放在这里是一致的。
     *
     * 存的是**路径不是内容**：图片 base64 进存档会让会话文件涨到几 MB，
     * 而且临时产物被清理之后，路径失效也比存一份过期副本更好判断。
     *
     * ## 为什么必须加在**最后**
     *
     * 这里踩了个坑，代价是 4 个探针一起红。
     *
     * 一开始按语义把它插在 `reasoning` 后面（「都是只给界面看的」），
     * 结果 `VariantProbe` / `UsageDisplayProbe` / `TokenSlimProbe` /
     * `HistoryGroupingProbe` 全挂了 —— 它们用**位置参数**构造 `ChatMessage`，
     * 新字段插在中间会让后面所有参数**整体错位**。
     *
     * 而错位**编译期不一定报错**：`List<String>` 和 `int` 混着传时，
     * 类型碰巧对得上编译器就沉默，运行也不崩，只是**字段值全是错的** ——
     * 是探针靠断言值把它抓出来的。
     *
     * 加在末尾则完全安全（原有位置参数一个个对应原位，新字段拿默认值）。
     * **data class 加字段，一律加在最后。**
     */
    val attachments: List<String> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    data class ImagePart(val mimeType: String, val base64: String)

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)

        fun user(text: String, images: List<ImagePart> = emptyList()) =
            ChatMessage(Role.USER, text, images = images)

        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.ASSISTANT, text, toolCalls = toolCalls)

        fun tool(callId: String, name: String, result: String) =
            ChatMessage(Role.TOOL, result, toolCallId = callId, toolName = name)
    }
}

/** 一次工具调用请求 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串形式的参数，流式输出需要增量拼接 */
    val arguments: String = ""
) {
    fun argsAsJson(): Json = runCatching {
        if (arguments.isBlank()) Json.EMPTY_OBJ else Json.parse(arguments)
    }.getOrElse { Json.EMPTY_OBJ }
}

/** 暴露给模型的工具描述 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 形式的参数定义 */
    val parameters: Json.Obj,
    /** 来源标记：内置工具为 null，MCP 工具带服务器标识 */
    val source: String? = null
)

/** 流式事件 */
sealed class LlmEvent {
    /** 文本增量 */
    data class TextDelta(val text: String) : LlmEvent()

    /** 思维链增量 */
    data class ReasoningDelta(val text: String) : LlmEvent()

    /** 本轮结束，带上完整的助手消息 */
    data class Completed(val message: ChatMessage, val usage: Usage? = null) : LlmEvent()

    data class Failure(val error: String, val cause: Throwable? = null) : LlmEvent()
}

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val total: Int get() = promptTokens + completionTokens
}
