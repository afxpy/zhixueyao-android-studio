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
    val completionTokens: Int = 0
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
