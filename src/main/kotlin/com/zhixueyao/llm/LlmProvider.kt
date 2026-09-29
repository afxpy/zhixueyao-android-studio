package com.zhixueyao.llm

import com.intellij.openapi.diagnostic.Logger
import com.zhixueyao.util.HttpSupport
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型服务提供方抽象。
 *
 * 关键设计：不同厂商的请求格式、工具调用协议、流式事件结构都不同，
 * 但对上层只暴露「输入消息 + 工具清单 → 输出流式事件」这一件事。
 */
interface LlmProvider {

    val displayName: String

    /** 以流式方式发起一次对话，事件通过 [onEvent] 回调。 */
    fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onEvent: (LlmEvent) -> Unit,
        cancelFlag: AtomicBoolean
    )
}

/** 运行时配置快照，避免流式过程中用户改设置导致状态撕裂。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    /** 思考强度，默认不干预。具体发什么参数由各 Provider 按 [thinkingStyle] 决定 */
    val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
    /** 思考参数用哪种方言，决定发 `reasoning_effort` 还是 `enable_thinking` 还是 `thinking.type` */
    val thinkingStyle: ThinkingStyle = ThinkingStyle.NONE
) {
    /** 去掉尾部斜杠，便于拼接端点 */
    val normalizedBase: String get() = baseUrl.trimEnd('/')
}

// ============================================================
// OpenAI 兼容协议
// 覆盖：OpenAI / DeepSeek / 通义千问 / Kimi / 智谱 / 硅基流动 / Ollama / 各类中转
// ============================================================

class OpenAiCompatProvider : LlmProvider {

    private val log = Logger.getInstance(OpenAiCompatProvider::class.java)

    override val displayName: String = "OpenAI 兼容接口"

    override fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onEvent: (LlmEvent) -> Unit,
        cancelFlag: AtomicBoolean
    ) {
        try {
            val body = buildRequestBody(config, messages, tools)
            val headers = buildMap {
                if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
            }

            // 流式累积状态
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            // 工具调用按 index 聚合，因为流式下参数是分片到达的
            val toolAcc = LinkedHashMap<Int, ToolAccumulator>()
            var usage: Usage? = null
            var finishReason: String? = null

            HttpSupport.postSse(
                url = "${config.normalizedBase}/chat/completions",
                body = body,
                headers = headers,
                cancelFlag = cancelFlag
            ) { payload ->
                if (payload == "[DONE]") {
                    return@postSse false
                }
                val root = runCatching { Json.parse(payload) }.getOrNull() ?: return@postSse true

                // 流内错误事件：有些服务商 HTTP 状态是 200，错误却藏在 SSE 里，
                // 所以这里也要走一遍错误翻译，否则用户只会看到一句英文原文
                root.obj("error")?.let { err ->
                    val raw = err.str("message") ?: root.str("message") ?: "服务端返回未知错误"
                    onEvent(LlmEvent.Failure(LlmErrorAdvice.explain(raw, config.model).render()))
                    return@postSse false
                }

                // 部分兼容实现会在流中插入 usage 统计
                root.obj("usage")?.let { u ->
                    usage = Usage(
                        promptTokens = u.int("prompt_tokens") ?: 0,
                        completionTokens = u.int("completion_tokens") ?: 0
                    )
                }

                // 某些中转站在限流、余额不足或异步错误时会返回空 choices。
                // 直接 get(0) 会把服务端异常伪装成 Index 0 out of bounds，
                // 用户只能看到 Kotlin 堆栈，完全不知道该检查什么。
                val choices = root.arr("choices")
                val choice = choices?.items?.firstOrNull()?.asObjOrNull
                if (choice == null) {
                    root.str("finish_reason")?.let { finishReason = it }
                    val hint = root.obj("error")?.str("message")
                        ?: root.str("message")
                        ?: root.str("detail")
                    if (!hint.isNullOrBlank()) {
                        onEvent(LlmEvent.Failure(LlmErrorAdvice.explain(hint, config.model).render()))
                        return@postSse false
                    }
                    // 没有 choices 也没有 error 时，可能只是 usage-only SSE 帧。
                    return@postSse true
                }
                choice.str("finish_reason")?.let { finishReason = it }
                val delta = choice.obj("delta") ?: return@postSse true

                delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                    textBuf.append(it)
                    onEvent(LlmEvent.TextDelta(it))
                }

                // 推理模型（DeepSeek-R1 / QwQ 等）的思维链字段
                (delta.str("reasoning_content") ?: delta.str("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                    reasoningBuf.append(it)
                    onEvent(LlmEvent.ReasoningDelta(it))
                }

                delta.arr("tool_calls")?.items?.forEach { item ->
                    val tc = item.asObjOrNull ?: return@forEach
                    val idx = tc.int("index") ?: 0
                    val acc = toolAcc.getOrPut(idx) { ToolAccumulator() }
                    tc.str("id")?.let { acc.id = it }
                    tc.obj("function")?.let { fn ->
                        fn.str("name")?.takeIf { it.isNotEmpty() }?.let { acc.name = it }
                        fn.str("arguments")?.let { acc.args.append(it) }
                    }
                }
                true
            }

            val calls = toolAcc.toSortedMap().values
                .filter { it.name.isNotBlank() }
                .map { ToolCall(it.id.ifBlank { "call_${it.name}_${System.nanoTime()}" }, it.name, it.args.toString()) }

            val message = ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = textBuf.toString(),
                toolCalls = calls,
                reasoning = reasoningBuf.toString()
            )

            // 一个字都没拿到、也没有工具调用 —— 空回复对用户毫无价值，
            // 直接当成失败并给出排查顺序，否则界面上只剩一个空气泡，用户不知道发生了什么
            if (message.content.isBlank() && calls.isEmpty()) {
                onEvent(LlmEvent.Failure(LlmErrorAdvice.renderEmptyStream(config.model, config.baseUrl, finishReason)))
                return
            }
            onEvent(LlmEvent.Completed(message, usage))
        } catch (e: HttpSupport.HttpError) {
            onEvent(LlmEvent.Failure(describeHttpError(e, config.model), e))
        } catch (e: Exception) {
            // 全量记日志是硬要求：这里以前只把 message 拼进界面文案，
            // 一旦遇到代码缺陷类异常（越界、NPE…），用户看到的是
            // 「Index 0 out of bounds for length 0」这种 JVM 内部消息 ——
            // 既不知道该改什么，idea.log 里也没有堆栈，只能靠猜。
            log.error("流式请求失败", e)
            if (cancelFlag.get()) {
                onEvent(LlmEvent.Failure("已取消"))
            } else {
                onEvent(LlmEvent.Failure(LlmErrorAdvice.renderNetworkError(e, config.baseUrl), e))
            }
        }
    }

    private class ToolAccumulator {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private fun buildRequestBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>
    ): String {
        val msgs = Json.Arr()
        for (m in messages) {
            msgs.add(convertMessage(m))
        }

        val root = jsonObj(
            "model" to config.model.toJson(),
            "messages" to msgs,
            "stream" to true.toJson(),
            "temperature" to config.temperature.toJson(),
            "max_tokens" to config.maxTokens.toJson(),
            "stream_options" to jsonObj("include_usage" to true.toJson())
        )

        // 思考参数按服务商方言注入（OpenAI / 通义 / 开关式三种形态）
        ThinkingParams.apply(root, config)

        if (tools.isNotEmpty()) {
            val arr = Json.Arr()
            for (t in tools) {
                arr.add(
                    jsonObj(
                        "type" to "function".toJson(),
                        "function" to jsonObj(
                            "name" to t.name.toJson(),
                            "description" to t.description.toJson(),
                            "parameters" to t.parameters
                        )
                    )
                )
            }
            root["tools"] = arr
            root["tool_choice"] = "auto".toJson()
        }
        return root.stringify()
    }

    private fun convertMessage(m: ChatMessage): Json.Obj = when (m.role) {
        ChatMessage.Role.SYSTEM -> jsonObj(
            "role" to "system".toJson(),
            "content" to m.content.toJson()
        )

        ChatMessage.Role.USER -> jsonObj(
            "role" to "user".toJson(),
            "content" to buildUserContent(m)
        )

        ChatMessage.Role.ASSISTANT -> {
            val o = jsonObj("role" to "assistant".toJson())
            // 有工具调用时，content 允许为 null
            o["content"] = if (m.content.isEmpty() && m.toolCalls.isNotEmpty()) Json.Null else m.content.toJson()
            if (m.toolCalls.isNotEmpty()) {
                val arr = Json.Arr()
                for (c in m.toolCalls) {
                    arr.add(
                        jsonObj(
                            "id" to c.id.toJson(),
                            "type" to "function".toJson(),
                            "function" to jsonObj(
                                "name" to c.name.toJson(),
                                "arguments" to (c.arguments.ifBlank { "{}" }).toJson()
                            )
                        )
                    )
                }
                o["tool_calls"] = arr
            }
            o
        }

        ChatMessage.Role.TOOL -> jsonObj(
            "role" to "tool".toJson(),
            "tool_call_id" to m.toolCallId.toJson(),
            "content" to m.content.toJson()
        )
    }

    /** 纯文本走字符串，带图时走多模态数组 */
    private fun buildUserContent(m: ChatMessage): Json {
        if (m.images.isEmpty()) return m.content.toJson()
        val arr = Json.Arr()
        if (m.content.isNotBlank()) {
            arr.add(jsonObj("type" to "text".toJson(), "text" to m.content.toJson()))
        }
        for (img in m.images) {
            arr.add(
                jsonObj(
                    "type" to "image_url".toJson(),
                    "image_url" to jsonObj(
                        "url" to "data:${img.mimeType};base64,${img.base64}".toJson()
                    )
                )
            )
        }
        return arr
    }

    companion object {
        /**
         * HTTP 层错误 → 界面可读文案。
         *
         * 职责只有「取原文 + 交给 [LlmErrorAdvice] 翻译」，不再自己拼中文结论 ——
         * 原先在这里写死的几句话既笼统又和流内错误的口径不一致（同一个 401，
         * 走到 HTTP 分支和走到 SSE 分支看到的提示完全不同）。
         */
        fun describeHttpError(e: HttpSupport.HttpError, model: String = ""): String {
            val raw = LlmErrorAdvice.extractServerMessage(e.body)
            return LlmErrorAdvice.explain(raw, model, e.status).render()
        }
    }
}

// ============================================================
// Anthropic 原生协议
// ============================================================

class AnthropicProvider : LlmProvider {

    private val log = Logger.getInstance(AnthropicProvider::class.java)

    override val displayName: String = "Anthropic Claude"

    override fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onEvent: (LlmEvent) -> Unit,
        cancelFlag: AtomicBoolean
    ) {
        try {
            val body = buildRequestBody(config, messages, tools)
            val headers = mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )

            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            // Anthropic 的工具调用是「content_block_start 带 id/name → input_json_delta 累积」两段式
            val blocks = LinkedHashMap<Int, BlockAccumulator>()
            var usage: Usage? = null
            var stopReason: String? = null

            HttpSupport.postSse(
                url = "${config.normalizedBase}/messages",
                body = body,
                headers = headers,
                cancelFlag = cancelFlag
            ) { payload ->
                val root = runCatching { Json.parse(payload) }.getOrNull() ?: return@postSse true
                when (val type = root.str("type")) {
                    "error" -> {
                        val msg = root.obj("error")?.strOr("message", "未知错误") ?: "未知错误"
                        onEvent(LlmEvent.Failure(LlmErrorAdvice.explain(msg, config.model).render()))
                        return@postSse false
                    }

                    "message_start" -> {
                        root.obj("message")?.obj("usage")?.let { u ->
                            usage = Usage(promptTokens = u.int("input_tokens") ?: 0)
                        }
                    }

                    "content_block_start" -> {
                        val idx = root.int("index") ?: 0
                        val block = root.obj("content_block") ?: return@postSse true
                        when (block.str("type")) {
                            "tool_use" -> {
                                val acc = BlockAccumulator(kind = BlockKind.TOOL)
                                acc.id = block.str("id") ?: ""
                                acc.name = block.str("name") ?: ""
                                // 部分实现会在 start 事件里直接给完整 input
                                block.obj("input")?.let { if (it.fields.isNotEmpty()) acc.args.append(it.stringify()) }
                                blocks[idx] = acc
                            }
                            "thinking" -> blocks[idx] = BlockAccumulator(kind = BlockKind.THINKING)
                            else -> blocks[idx] = BlockAccumulator(kind = BlockKind.TEXT)
                        }
                    }

                    "content_block_delta" -> {
                        val idx = root.int("index") ?: 0
                        val delta = root.obj("delta") ?: return@postSse true
                        when (delta.str("type")) {
                            "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }?.let {
                                textBuf.append(it)
                                onEvent(LlmEvent.TextDelta(it))
                            }
                            "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }?.let {
                                reasoningBuf.append(it)
                                onEvent(LlmEvent.ReasoningDelta(it))
                            }
                            "input_json_delta" -> {
                                delta.str("partial_json")?.let {
                                    blocks.getOrPut(idx) { BlockAccumulator(kind = BlockKind.TOOL) }.args.append(it)
                                }
                            }
                        }
                    }

                    "message_delta" -> {
                        root.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                        root.obj("usage")?.let { u ->
                            usage = Usage(
                                promptTokens = usage?.promptTokens ?: 0,
                                completionTokens = u.int("output_tokens") ?: 0
                            )
                        }
                    }

                    "message_stop" -> return@postSse false
                    else -> Unit
                }
                true
            }

            val calls = blocks.values
                .filter { it.kind == BlockKind.TOOL && it.name.isNotBlank() }
                .map { ToolCall(it.id.ifBlank { "toolu_${System.nanoTime()}" }, it.name, it.args.toString()) }

            val message = ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = textBuf.toString(),
                toolCalls = calls,
                reasoning = reasoningBuf.toString()
            )

            if (message.content.isBlank() && calls.isEmpty()) {
                onEvent(LlmEvent.Failure(LlmErrorAdvice.renderEmptyStream(config.model, config.baseUrl, stopReason)))
                return
            }
            onEvent(LlmEvent.Completed(message, usage))
        } catch (e: HttpSupport.HttpError) {
            onEvent(LlmEvent.Failure(OpenAiCompatProvider.describeHttpError(e, config.model), e))
        } catch (e: Exception) {
            // 与 OpenAI 分支同理：异常原文必须落进 idea.log，界面只给人话
            log.error("流式请求失败", e)
            if (cancelFlag.get()) onEvent(LlmEvent.Failure("已取消"))
            else onEvent(LlmEvent.Failure(LlmErrorAdvice.renderNetworkError(e, config.baseUrl), e))
        }
    }

    private enum class BlockKind { TEXT, TOOL, THINKING }

    private class BlockAccumulator(val kind: BlockKind) {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private fun buildRequestBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>
    ): String {
        // Anthropic 把 system 单独拎出来，不放在 messages 里
        val systemText = messages.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.content }

        val msgs = Json.Arr()
        val conversation = messages.filter { it.role != ChatMessage.Role.SYSTEM }
        var i = 0
        while (i < conversation.size) {
            val m = conversation[i]
            when (m.role) {
                ChatMessage.Role.USER -> {
                    msgs.add(convertUserMessage(m))
                    i++
                }
                ChatMessage.Role.ASSISTANT -> {
                    msgs.add(convertAssistantMessage(m))
                    i++
                }
                ChatMessage.Role.TOOL -> {
                    // 连续的多个工具结果必须合并进同一条 user 消息，
                    // 否则 Anthropic 会报 tool_result 与 tool_use 数量不匹配
                    val results = Json.Arr()
                    while (i < conversation.size && conversation[i].role == ChatMessage.Role.TOOL) {
                        val t = conversation[i]
                        results.add(
                            jsonObj(
                                "type" to "tool_result".toJson(),
                                "tool_use_id" to t.toolCallId.toJson(),
                                "content" to t.content.toJson()
                            )
                        )
                        i++
                    }
                    msgs.add(jsonObj("role" to "user".toJson(), "content" to results))
                }
                else -> i++
            }
        }

        val root = jsonObj(
            "model" to config.model.toJson(),
            "messages" to msgs,
            "max_tokens" to config.maxTokens.toJson(),
            "temperature" to config.temperature.toJson(),
            "stream" to true.toJson()
        )
        if (systemText.isNotBlank()) root["system"] = systemText.toJson()

        // Anthropic 的思考参数结构独立（thinking.budget_tokens），且受 max_tokens 约束
        ThinkingParams.applyAnthropic(root, config)

        if (tools.isNotEmpty()) {
            val arr = Json.Arr()
            for (t in tools) {
                arr.add(
                    jsonObj(
                        "name" to t.name.toJson(),
                        "description" to t.description.toJson(),
                        "input_schema" to t.parameters
                    )
                )
            }
            root["tools"] = arr
        }
        return root.stringify()
    }

    private fun convertUserMessage(m: ChatMessage): Json.Obj {
        if (m.images.isEmpty()) {
            return jsonObj("role" to "user".toJson(), "content" to m.content.toJson())
        }
        val arr = Json.Arr()
        for (img in m.images) {
            arr.add(
                jsonObj(
                    "type" to "image".toJson(),
                    "source" to jsonObj(
                        "type" to "base64".toJson(),
                        "media_type" to img.mimeType.toJson(),
                        "data" to img.base64.toJson()
                    )
                )
            )
        }
        if (m.content.isNotBlank()) {
            arr.add(jsonObj("type" to "text".toJson(), "text" to m.content.toJson()))
        }
        return jsonObj("role" to "user".toJson(), "content" to arr)
    }

    private fun convertAssistantMessage(m: ChatMessage): Json.Obj {
        val blocks = Json.Arr()
        if (m.content.isNotBlank()) {
            blocks.add(jsonObj("type" to "text".toJson(), "text" to m.content.toJson()))
        }
        for (c in m.toolCalls) {
            blocks.add(
                jsonObj(
                    "type" to "tool_use".toJson(),
                    "id" to c.id.toJson(),
                    "name" to c.name.toJson(),
                    "input" to runCatching {
                        if (c.arguments.isBlank()) Json.EMPTY_OBJ else Json.parse(c.arguments)
                    }.getOrElse { Json.EMPTY_OBJ }
                )
            )
        }
        if (blocks.size == 0) blocks.add(jsonObj("type" to "text".toJson(), "text" to "".toJson()))
        return jsonObj("role" to "assistant".toJson(), "content" to blocks)
    }
}

// ============================================================
// 提供方注册表
// ============================================================

/** 预置服务商。baseUrl 已按各家文档填好，用户只需填密钥。 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val format: String,
    val keyHint: String,
    val note: String = "",
    /**
     * 该家的思考参数方言。
     *
     * 为什么要在预设里写死而不是自动判断：`reasoning_effort` 并非所有家都认，
     * 往智谱发这个字段会 400。而聚合站后面的模型五花八门，猜错就是请求失败，
     * 所以按官方文档显式声明更稳。
     */
    val thinkingStyle: ThinkingStyle = ThinkingStyle.NONE
)

object Providers {

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            "deepseek", "DeepSeek 深度求索",
            "https://api.deepseek.com/v1", "deepseek-chat", "openai",
            "sk-...", "国内直连，性价比高；推理模型填 deepseek-reasoner",
            // DeepSeek V4 用 thinking.type 开关，启用时另发 reasoning_effort
            ThinkingStyle.TOGGLE
        ),
        ProviderPreset(
            "dashscope", "通义千问 (阿里云)",
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "openai",
            "sk-...", "兼容模式，支持 qwen-max / qwen3 系列",
            ThinkingStyle.QWEN
        ),
        ProviderPreset(
            "moonshot", "Kimi (月之暗面)",
            "https://api.moonshot.cn/v1", "kimi-k2-0905-preview", "openai",
            "sk-...", "长上下文见长",
            ThinkingStyle.TOGGLE
        ),
        ProviderPreset(
            "zhipu", "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", "openai",
            "....", "支持 GLM 系列",
            ThinkingStyle.TOGGLE
        ),
        ProviderPreset(
            "siliconflow", "硅基流动",
            "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "openai",
            "sk-...", "聚合多种开源模型",
            ThinkingStyle.TOGGLE
        ),
        ProviderPreset(
            "openai", "OpenAI 官方",
            "https://api.openai.com/v1", "gpt-5", "openai",
            "sk-...", "国内需自备网络环境",
            ThinkingStyle.EFFORT
        ),
        ProviderPreset(
            "anthropic", "Anthropic Claude",
            "https://api.anthropic.com/v1", "claude-sonnet-4-5", "anthropic",
            "sk-ant-...", "原生协议，工具调用最稳",
            // Anthropic 走独立的 thinking.budget_tokens，由 applyAnthropic 处理
            ThinkingStyle.EFFORT
        ),
        ProviderPreset(
            "ollama", "Ollama 本地模型",
            "http://127.0.0.1:11434/v1", "qwen3:8b", "openai",
            "无需填写", "完全本地运行，密钥留空即可",
            ThinkingStyle.QWEN
        )
    )

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    fun createProvider(format: String): LlmProvider =
        if (format == "anthropic") AnthropicProvider() else OpenAiCompatProvider()

    /**
     * 取某个服务商的显示名。
     *
     * 统一入口 —— 预设和自定义服务商混在一起，界面上到处都要显示「当前是哪家」，
     * 与其在每处都判断一次，不如集中在这里。
     */
    fun displayNameOf(id: String): String {
        if (id.startsWith("custom:")) {
            val c = com.zhixueyao.settings.ZhixueyaoSettings.getInstance().customById(id)
            return c?.name?.takeIf { it.isNotBlank() } ?: "自定义服务商"
        }
        return byId(id)?.label ?: "自定义"
    }

    /** 取某个服务商的思考方言 */
    fun thinkingStyleOf(id: String): ThinkingStyle {
        if (id.startsWith("custom:")) {
            val c = com.zhixueyao.settings.ZhixueyaoSettings.getInstance().customById(id)
            return ThinkingStyle.byId(c?.thinkingStyle ?: "NONE")
        }
        return byId(id)?.thinkingStyle ?: ThinkingStyle.NONE
    }
}
