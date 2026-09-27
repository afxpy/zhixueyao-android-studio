package com.zhixueyao.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zhixueyao.llm.ChatMessage
import com.zhixueyao.llm.LlmConfig
import com.zhixueyao.llm.LlmEvent
import com.zhixueyao.llm.Usage
import com.zhixueyao.llm.LlmProvider
import com.zhixueyao.llm.ToolCall
import com.zhixueyao.llm.ToolSpec
import com.zhixueyao.mcp.McpManager
import com.zhixueyao.settings.ZhixueyaoSettings
import com.zhixueyao.tools.AgentTool
import com.zhixueyao.tools.ToolResult
import com.zhixueyao.util.Json
import com.zhixueyao.util.toJsonValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 执行引擎。
 *
 * 采用 ReAct 式循环：模型思考 → 请求工具 → 执行工具 → 结果回灌 → 模型再思考，
 * 直到模型不再请求工具或达到轮数上限。
 *
 * 全部执行在后台线程；界面回调由调用方负责切回 EDT。
 */
class AgentRunner(
    private val project: Project,
    private val mcpManager: McpManager
) {

    private val log = Logger.getInstance(AgentRunner::class.java)

    /** 界面回调。所有方法都可能从后台线程触发。 */
    interface Listener {
        /** 收到文本增量，用于流式显示 */
        fun onTextDelta(text: String)

        /** 收到思维链增量 */
        fun onReasoningDelta(text: String) = Unit

        /** 模型请求调用某个工具 */
        fun onToolStart(call: ToolCall) = Unit

        /** 工具执行完成 */
        fun onToolFinish(call: ToolCall, result: ToolResult) = Unit

        /**
         * 整轮结束（可能含多步工具调用）。
         *
         * [usage] 是服务商回报的**真实** token 用量（多步累加）；
         * 服务商不回报时为 null，调用方可以退回估算。
         */
        fun onComplete(finalText: String, steps: Int, usage: Usage? = null) = Unit

        /** 上下文被压缩了（长对话模式）。界面可以据此提示用户 */
        fun onCompact(report: ContextCompactor.Report) = Unit

        /** 出错 */
        fun onError(message: String) = Unit
    }

    /**
     * 摘要的**跨轮状态**。
     *
     * 为什么需要它：摘要是一次真实模型调用，很贵。已摘要过的部分不能每轮重摘一遍 ——
     * 这里记住「上次摘要的结果」和「它覆盖到历史的第几条」，下次只把**新增的那一段**
     * 连同旧摘要一起送过去，让模型合并。这就是规格里的「增量式摘要」。
     */
    class SummaryState {
        var summary: String? = null

        /** 摘要已覆盖到 [MutableList] 的前多少条 */
        var covered: Int = 0

        fun reset() {
            summary = null
            covered = 0
        }
    }

    private companion object {
        /**
         * 单条工具结果进上下文的上限（估算 token）。
         *
         * 6,000 是权衡后的值：足够放下「带结构的一个类」「一段构建报错」
         * 这类真正需要模型看全的内容，又不会让单条结果就吃掉上下文的一大块。
         * 需要更多细节时，模型可以自己用 offset/limit 再读。
         */
        const val MAX_TOOL_RESULT_TOKENS = 6_000

        /** 输入预算占模型窗口的比例：留 30% 给工具返回与生成内容 */
        const val CONTEXT_BUDGET_PERCENT = 70

        /** 预算下限，防止窗口推断得太小时每轮都在压缩 */
        const val MIN_CONTEXT_BUDGET = 4_000

        /** 送给摘要模型的原文上限（超出会被首尾截断） */
        const val SUMMARY_INPUT_BUDGET = 12_000

        /** 摘要本身的最大输出 */
        const val SUMMARY_MAX_TOKENS = 1_500
    }

    /**
     * 执行一轮对话。
     *
     * @param history 完整对话历史（调用方持有，本方法会追加新消息）
     * @param cancelFlag 置位后尽快中断
     * @return 更新后的对话历史
     */
    fun run(
        history: MutableList<ChatMessage>,
        tools: List<AgentTool>,
        listener: Listener,
        cancelFlag: AtomicBoolean,
        onHistoryChanged: (List<ChatMessage>) -> Unit = {},
        summaryState: SummaryState = SummaryState(),
        /**
         * 「边跑边追加」的消息来源（steer）。
         *
         * 用户看到 Agent 跑偏时，不该只能「停止 → 等这一轮收尾 → 重新说一遍」——
         * 那样中间白做的工作全废，而且 MCP 调用最长要等 2 分钟才停得下来。
         * 这里每个 **turn 边界**（工具执行完、下次请求前）取一次，
         * 有就作为用户消息插进历史，让模型带着新信息重新规划，
         * **已经做完的工作全部保留**。
         *
         * 借鉴自参考项目 astravia 的 steer 机制。
         */
        steering: () -> List<String> = { emptyList() }
    ) {
        val settings = ZhixueyaoSettings.getInstance()
        val config = LlmConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            // 思考档位与方言一起带上：光有档位没有方言的话，
            // 参数会按 OpenAI 标准发出去，对着智谱/通义就会 400
            thinking = com.zhixueyao.llm.ThinkingLevel.byId(settings.thinkingLevel),
            thinkingStyle = com.zhixueyao.llm.Providers.thinkingStyleOf(settings.providerId)
        )

        if (config.baseUrl.isBlank()) {
            listener.onError("尚未配置接口地址。请到 设置 → 工具 → 止血药 中填写。")
            return
        }
        if (config.model.isBlank()) {
            listener.onError("尚未配置模型名称。请到 设置 → 工具 → 止血药 中填写。")
            return
        }

        val provider = com.zhixueyao.llm.Providers.createProvider(settings.apiFormat)

        // 组装工具清单：内置工具（经预设过滤） + 已连接的 MCP 工具。
        // 真正的 specs 在循环里每轮重算（MCP 工具会中途增减），这里只准备过滤好的内置工具。
        val preset = AgentPresets.byId(settings.agentPreset)
        val allowedTools = tools.filter { preset.allowTool(it.name) }

        var steps = 0
        val maxSteps = settings.maxToolRounds.coerceIn(1, 100)
        val finalText = StringBuilder()

        // 真实 token 用量：多步之间累加。服务商不回报（部分中转站不返回 usage）时保持 null，
        // 由界面退回估算 —— 以前是把这里解析出来的 usage **直接丢掉**，
        // 状态栏只能用「字符数 × 0.7」猜。
        var promptTokens = 0
        var completionTokens = 0
        var sawUsage = false
        fun usageOrNull(): Usage? =
            if (sawUsage) Usage(promptTokens, completionTokens) else null

        // 上下文占用的**精确基线**：上一次请求服务商数出来的 prompt_tokens，
        // 以及当时发出去的消息条数。之后新追加的消息用估算补上，
        // 「精确 + 增量」比整体估算准得多（见 ContextCompactor.Baseline 的注释）。
        var lastPromptTokens = 0
        var lastPromptMessageCount = 0

        while (steps < maxSteps) {
            if (cancelFlag.get()) {
                listener.onComplete(ThinkMarkerSplitter.stripAll(finalText.toString()), steps, usageOrNull())
                return
            }

            // 工具清单**每轮重算**，不放在循环外。
            //
            // 因为「装 MCP」这类工具会改变可用工具集合：放循环外的话，模型本轮装上服务器、
            // 本轮却看不到它的工具，只能等用户再发一条消息 —— 用户看到的就是
            // 「说装好了，但根本没生效」。每轮重算是廉价的（纯内存拼装）。
            // ---- 注入「边跑边追加」的消息（steer）----
            //
            // 放在这里（每次请求前）而不是只在开头：用户在 Agent 跑的过程中说的话，
            // 应该**下一个 turn 就被看到**，而不是等整轮结束。
            // 多个 turn 都可能各追加一条，所以每轮都取。
            val steered = runCatching { steering() }.getOrDefault(emptyList())
                .filter { it.isNotBlank() }
            if (steered.isNotEmpty()) {
                for (text in steered) {
                    history.add(ChatMessage.user(text))
                    AgentLog.record(
                        AgentLog.Kind.SESSION,
                        "收到运行中的追加输入",
                        text.take(200)
                    )
                }
                onHistoryChanged(history)
                // 追加输入改变了上下文构成，旧的精确基线失效
                lastPromptTokens = 0
                lastPromptMessageCount = 0
            }

            val specs = buildToolSpecs(allowedTools, settings.enableMcp, preset.readOnlyOnly)

            // ---- 上下文压缩 ----
            //
            // 以前 ContextCompactor 整块没被调用（约 600 行死代码），
            // 「长会话无感知 token 限制」是个空承诺 —— 聊到一定长度，
            // 请求会被服务端直接打回。这里在**每轮请求前**测一次占用，超预算就压。
            //
            // 压缩的是**发出去的副本**，不动调用方的 history：
            // 界面和会话存档仍保留完整对话，规格里「可查看被压缩的原始内容」才成立。
            val baseline =
                if (lastPromptTokens > 0) ContextCompactor.Baseline(lastPromptTokens, lastPromptMessageCount)
                else null
            val outgoing = compactIfNeeded(
                history, baseline, summaryState, provider, config, cancelFlag, listener
            )
            if (outgoing !== history) {
                // 压缩改变了消息构成，旧基线（按条数对齐）立刻失效
                lastPromptTokens = 0
                lastPromptMessageCount = 0
            }

            val turnText = StringBuilder()
            var completed: ChatMessage? = null
            var failure: String? = null

            provider.streamChat(
                config = config,
                messages = outgoing,
                tools = specs,
                cancelFlag = cancelFlag,
                onEvent = { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            turnText.append(event.text)
                            listener.onTextDelta(event.text)
                        }
                        is LlmEvent.ReasoningDelta -> listener.onReasoningDelta(event.text)
                        is LlmEvent.Completed -> {
                            completed = event.message
                            event.usage?.let {
                                sawUsage = true
                                promptTokens += it.promptTokens
                                completionTokens += it.completionTokens
                                if (it.promptTokens > 0) {
                                    lastPromptTokens = it.promptTokens
                                    lastPromptMessageCount = history.size
                                }
                            }
                        }
                        is LlmEvent.Failure -> failure = event.error
                    }
                }
            )

            if (cancelFlag.get()) {
                listener.onComplete(ThinkMarkerSplitter.stripAll(finalText.toString()), steps, usageOrNull())
                return
            }

            failure?.let {
                AgentLog.record(AgentLog.Kind.ERROR, "模型返回错误", it.take(600))
                listener.onError(it)
                return
            }

            val assistant = completed
            if (assistant == null) {
                listener.onError("模型未返回有效响应")
                return
            }

            history.add(assistant)
            onHistoryChanged(history)
            finalText.append(turnText)

            // 没有工具调用 → 对话结束
            if (assistant.toolCalls.isEmpty()) {
                listener.onComplete(ThinkMarkerSplitter.stripAll(finalText.toString()), steps, usageOrNull())
                return
            }

            steps++
            listener.onToolStart(assistant.toolCalls.first())

            // 执行本轮所有工具调用。串行执行以保证文件改动的顺序性，
            // 并发执行会导致「先写后读」类操作产生竞态。
            for (call in assistant.toolCalls) {
                if (cancelFlag.get()) {
                    history.add(
                        ChatMessage.tool(call.id, call.name, "用户已取消操作")
                    )
                    // **必须通知界面收尾**：不通知的话工具卡会永远停在「运行中」，
                    // 里面的旋转动效还会 110ms 一次地空转下去
                    // （和「任务清单不收摊」是同一类问题：外部不更新了，界面得自己收尾）
                    listener.onToolFinish(call, ToolResult("已取消", ok = false))
                    continue
                }

                listener.onToolStart(call)
                val startedAt = System.currentTimeMillis()
                val result = executeToolSafely(call, allowedTools, cancelFlag)
                val elapsed = System.currentTimeMillis() - startedAt
                listener.onToolFinish(call, result)
                // 操作留痕：无确认模式下必须能回看「当时做了什么」
                AgentLog.record(
                    kind = if (result.ok) AgentLog.Kind.TOOL_OK else AgentLog.Kind.TOOL_FAIL,
                    title = "${call.name}  ${elapsed}ms",
                    detail = call.arguments.ifBlank { "（无参数）" }.take(600) +
                        "\n→ " + result.text.take(600)
                )

                history.add(
                    ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = clipResult(result.text),
                        toolCallId = call.id,
                        toolName = call.name
                    )
                )
            }
            onHistoryChanged(history)
        }

        // 达到轮数上限
        listener.onComplete(ThinkMarkerSplitter.stripAll(finalText.toString()), steps, usageOrNull())
        log.info("达到工具调用轮数上限 $maxSteps，对话结束")
    }

    /** 组装给模型的工具描述。
     *
     * @param readOnlyOnly 只读模式下为 true，此时 MCP 工具只放行声明了
     *        `readOnlyHint` 的那些。
     */
    // ---------------- 上下文压缩 ----------------

    /**
     * 上下文超预算就压缩，返回**本次请求要发出去的消息**。
     *
     * 没超预算时**原样返回传入的 list**（调用方用 `!==` 判断有没有压过），
     * 避免每轮都白白复制一份。
     */
    private fun compactIfNeeded(
        history: List<ChatMessage>,
        baseline: ContextCompactor.Baseline?,
        summaryState: SummaryState,
        provider: LlmProvider,
        config: LlmConfig,
        cancelFlag: AtomicBoolean,
        listener: Listener
    ): List<ChatMessage> {
        val settings = ZhixueyaoSettings.getInstance()
        // 先做一遍**低成本**的旧工具结果瘦身（不管超没超预算）——
        // 工具结果会一直挂在历史里每轮重发，等超预算才压已经白花了很多轮。
        // 实测：后台跑十几个工具的会话，光旧工具结果就能占几万 token，
        // 用户看到「问个简单问题怎么有 44k 输入」就是这个。
        val slimmedTools = ContextCompactor.slimOldToolResults(history, keepRecentTurns = 1)
        // 图片也要**每轮**压：base64 每轮重发，一张截图轻松上万 token。
        // 规则是「看过的旧图只留最新 N 张、**没看过的无条件保留**」——
        // 后者是关键，删了会造成「读了等于没读」的幻读（借鉴 astravia 的 image budget）。
        val slimmed = ContextCompactor.slimOldImages(slimmedTools, com.zhixueyao.settings
            .ZhixueyaoSettings.getInstance().maxRecentImages)
        if (!settings.autoCompact) return slimmed

        val window = ModelWindows.resolve(settings.contextWindow, settings.model)
        // 输入预算 = 窗口的 70% 再扣掉输出预留
        val budget = (window * CONTEXT_BUDGET_PERCENT / 100 - settings.maxTokens)
            .coerceAtLeast(MIN_CONTEXT_BUDGET)
        val used = ContextCompactor.sizeOf(history, baseline)
        if (used <= budget) return history

        val result = ContextCompactor.compact(
            messages = history,
            budgetTokens = budget,
            keepRecentTurns = settings.compactKeepTurns.coerceAtLeast(1)
        ) { oldMessages ->
            // 取消时不要再发起摘要请求（那也是一次网络调用）
            if (cancelFlag.get()) null
            else summarizeIncrementally(oldMessages, history, summaryState, provider, config, cancelFlag)
        }

        if (result.report.isNoop) return history
        AgentLog.record(
            kind = AgentLog.Kind.COMPACT,
            title = "${result.report.beforeTokens} → ${result.report.afterTokens} tokens",
            detail = "摘要 ${result.report.summarizedTurns} 轮 · 瘦身工具结果 ${result.report.slimmedToolResults} 条 · " +
                "丢弃 ${result.report.droppedTurns} 轮 · 用摘要=${result.report.usedSummary}"
        )
        listener.onCompact(result.report)
        return result.messages
    }

    /**
     * 增量摘要：只把**新增的那段**连同旧摘要一起送过去，让模型合并。
     *
     * 为什么不能整体重摘：摘要是一次真实模型调用。历史越长，重摘越慢越贵；
     * 而旧摘要本身已经是对早期内容的浓缩，没必要再读一遍原文。
     *
     * @return 合并后的摘要；失败返回 null（[ContextCompactor.compact] 会降级到别的压缩级别）
     */
    private fun summarizeIncrementally(
        oldMessages: List<ChatMessage>,
        fullHistory: List<ChatMessage>,
        state: SummaryState,
        provider: LlmProvider,
        config: LlmConfig,
        cancelFlag: AtomicBoolean
    ): String? {
        if (oldMessages.isEmpty()) return null

        // 区间计算抽在 ContextCompactor 里（纯函数，可离线验证）
        val range = ContextCompactor.incrementalRange(fullHistory, oldMessages, state.covered)
            ?: return null
        val end = range.last + 1

        val prev = state.summary
        val delta: List<ChatMessage> = fullHistory.subList(range.first, end)
        // 已经摘过的部分不再发原文，只把旧摘要带上当上下文，让模型合并
        val incremental = prev != null && range.first > 0
        val payload: List<ChatMessage> =
            if (incremental) listOf(ChatMessage.system("【此前已生成的历史摘要】\n$prev")) + delta
            else delta
        if (payload.isEmpty()) return null

        val request = ContextCompactor.buildSummaryRequest(payload, SUMMARY_INPUT_BUDGET)
        // 摘要用「跟随模型默认」的思考档位：这是纯归纳任务，不需要深度推理，
        // 开着思考只会让它又慢又贵
        val summaryConfig = config.copy(
            maxTokens = SUMMARY_MAX_TOKENS,
            thinking = com.zhixueyao.llm.ThinkingLevel.DEFAULT
        )

        val out = StringBuilder()
        var failure: String? = null
        provider.streamChat(
            config = summaryConfig,
            messages = request,
            tools = emptyList(),
            cancelFlag = cancelFlag,
            onEvent = { event ->
                when (event) {
                    is LlmEvent.TextDelta -> out.append(event.text)
                    is LlmEvent.Failure -> failure = event.error
                    else -> Unit
                }
            }
        )
        if (failure != null || cancelFlag.get()) return null

        val text = out.toString().trim()
        if (text.isBlank()) return null
        state.summary = text
        state.covered = end
        return text
    }

    private fun buildToolSpecs(
        tools: List<AgentTool>,
        includeMcp: Boolean,
        readOnlyOnly: Boolean = false
    ): List<ToolSpec> {
        val specs = tools.map {
            ToolSpec(
                name = it.name,
                description = it.description,
                parameters = it.parameters
            )
        }.toMutableList()
        var skipped = 0

        if (includeMcp) {
            runCatching {
                for (pt in mcpManager.allTools()) {
                    // 只读模式下只放行服务器**明确声明**为只读的工具。
                    //
                    // 为什么必须这么做：内置工具走 preset.allowTool() 白名单，
                    // 但 MCP 工具此前是整包放行的 —— 也就是说切到「只读」模式后，
                    // 一个 MCP 服务器提供的 delete_all 之类工具依然能被调用。
                    // 规范里 readOnlyHint 只是「提示」，但它是客户端唯一能拿到的依据；
                    // 未声明的按有害处理（宁可少给工具，不可越权）。
                    if (readOnlyOnly && !pt.tool.readOnlyHint) {
                        skipped++
                        continue
                    }
                    // 需要任务增强的工具当前无法同步调用，不暴露给模型，
                    // 否则模型会调用并收到一个它无法理解的错误
                    if (!pt.tool.callable) {
                        skipped++
                        continue
                    }

                    // MCP 工具的 inputSchema 直接透传，它本身已是 JSON Schema
                    val schema = if (pt.tool.inputSchema.fields.isEmpty()) {
                        Json.Obj().apply { this["type"] = "object".toJsonValue() }
                            .apply { this["properties"] = Json.Obj() }
                    } else {
                        pt.tool.inputSchema
                    }
                    val label = pt.tool.title.ifBlank { pt.tool.name }
                    val safe = if (pt.tool.readOnlyHint) "（只读）" else ""
                    specs.add(
                        ToolSpec(
                            name = McpManager.prefixed(pt.serverId, pt.tool.name),
                            description = "[MCP:${pt.serverName}] $label$safe ${pt.tool.description}",
                            parameters = schema,
                            source = pt.serverId
                        )
                    )
                }
                if (skipped > 0) {
                    log.info("MCP 工具过滤：跳过 $skipped 个（只读限制或需任务增强）")
                }
            }.onFailure {
                log.warn("读取 MCP 工具清单失败", it)
            }
        }
        return specs
    }

    /** 执行工具，捕获所有异常转为可读结果 —— 绝不让工具异常中断整轮对话。 */
    private fun executeToolSafely(
        call: ToolCall,
        tools: List<AgentTool>,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean
    ): ToolResult {
        return try {
            val mcpName = McpManager.parsePrefixed(call.name)
            if (mcpName != null) {
                // 把取消标志传下去：MCP 调用最长要等 2 分钟，
                // 不传的话用户点「停止」得干等它超时（用户反馈过）
                val result = mcpManager.callTool(call.name, call.argsAsJson(), cancelFlag)
                ToolResult(
                    if (result.isError) "工具执行出错：${result.text}" else result.text,
                    ok = !result.isError
                )
            } else {
                val tool = tools.firstOrNull { it.name == call.name }
                    ?: return ToolResult.error(
                        "不存在名为 ${call.name} 的工具。可用工具：" +
                            tools.joinToString(", ") { it.name }
                    )
                val args = call.argsAsJson()
                if (args !is Json.Obj) {
                    return ToolResult.error("工具参数必须是 JSON 对象")
                }
                tool.execute(project, args)
            }
        } catch (e: Exception) {
            log.warn("工具 ${call.name} 执行异常", e)
            ToolResult.error("${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
        }
    }

    /** 工具结果可能非常长（如搜索命中几百行），截断以免撑爆上下文。 */
    /**
     * 工具结果进上下文前裁剪。
     *
     * **上限按 token 估，不按字符数** —— 这两者对中文差了 3 倍：
     * 原来写的是 30,000 字符，中文内容就是 3 万 token；
     * 一条 read_file 读个带注释的文件就能到 2 万 token，而且这条结果
     * **会一直留在历史里、每轮重发**（用户反馈「问个简单问题怎么有 44k 输入」）。
     *
     * 现在按估算 token 限制在 [MAX_TOOL_RESULT_TOKENS]。超了**留头留尾**：
     * 开头通常是结构/声明（有用），结尾通常是模型最关心的收尾部分；
     * 中间省略并写明「可用 read_file 分段读」。只留开头会丢掉结尾的关键上下文。
     */
    private fun clipResult(text: String, maxTokens: Int = MAX_TOOL_RESULT_TOKENS): String {
        val tokens = ContextCompactor.estimateTokens(text)
        if (tokens <= maxTokens) return text
        // 按字符数比例折算要留多少（估算函数本来就是按字符类型算的，方向一致）
        val keepChars = (text.length.toLong() * maxTokens / tokens).toInt().coerceAtLeast(200)
        val head = (keepChars * 6) / 10
        val tail = keepChars - head
        return text.take(head) +
            "\n\n...(中间省略约 ${tokens - maxTokens} tokens，可用 read_file 的 offset/limit 分段读)\n\n" +
            text.takeLast(tail)
    }

}

/** 内置工具注册表。 */
object ToolRegistry {

    val fileTools: List<AgentTool> = listOf(
        com.zhixueyao.tools.ReadFileTool(),
        com.zhixueyao.tools.EditFileTool(),
        com.zhixueyao.tools.WriteFileTool(),
        // 软删除 + 从备份还原：AI 能删能改，但永远有退路
        com.zhixueyao.tools.DeleteFileTool(),
        com.zhixueyao.tools.RestoreFileTool(),
        // 多媒体：SVG 直接转成 VectorDrawable 落 res/drawable，图/视频按 Android 规范归类
        com.zhixueyao.tools.GenerateSvgTool(),
        com.zhixueyao.tools.ExportDrawableTool(),
        com.zhixueyao.tools.SaveAssetTool(),
        com.zhixueyao.tools.ListDirectoryTool(),
        com.zhixueyao.tools.GlobFilesTool()
    )

    val searchTools: List<AgentTool> = listOf(
        com.zhixueyao.tools.SearchCodeTool(),
        com.zhixueyao.tools.FindSymbolTool(),
        com.zhixueyao.tools.GetEditorContextTool()
    )

    val buildTools: List<AgentTool> = listOf(
        com.zhixueyao.tools.GetDiagnosticsTool(),
        com.zhixueyao.tools.RunBuildTool(),
        com.zhixueyao.tools.ProjectStructureTool()
    )

    /**
     * 交互类工具：任务清单 + 让用户拍板。
     *
     * 与其它工具的区别：它们不碰代码，产出的是**给用户看的界面**。
     * 放进工具清单是因为模型只能通过工具调用来表达「我要列个清单」「我想让你选一下」。
     */
    val interactionTools: List<AgentTool> = listOf(
        com.zhixueyao.tools.TodoWriteTool(),
        com.zhixueyao.tools.AskUserTool(),
        // 技能加载：只读 SKILL.md，不改工程 —— 只读预设里同样需要
        com.zhixueyao.tools.SkillTool(),
        // 自管理：装技能 / 配 MCP。写的是**插件自己的配置目录**，不碰工程文件，
        // 所以只读预设里也放行 —— 「帮我装个技能」不该因为当前是研究模式就做不了
        com.zhixueyao.tools.InstallSkillTool(),
        com.zhixueyao.tools.ManageMcpTool()
    )

    val all: List<AgentTool> = fileTools + searchTools + buildTools + interactionTools

    fun byName(name: String): AgentTool? = all.firstOrNull { it.name == name }

    /**
     * 系统提示词：定义助手的行为准则与工具使用规范。
     *
     * @param mcpInstructions 已连接 MCP 服务器下发的使用说明（服务器名 → 说明）。
     *        这些是服务器作者写给模型的用法指引，直接拼进提示词比自己猜怎么用可靠。
     */
    /**
     * 技能目录（渐进式加载）。
     *
     * 只放「名字 + 一句话说明」，正文等模型用 skill 工具来取 ——
     * 把所有技能正文都塞进提示词既费 token 又互相干扰。
     */
    private fun skillCatalog(): String =
        com.zhixueyao.agent.Skills.catalogForPrompt(
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        )

    /**
     * 当前沙盒档位对应的行为说明。
     *
     * 必须动态生成：提示词里写死「只能访问当前项目」的话，用户切成「全盘沙盒」后
     * 模型仍然会回答「我看不到你的桌面」—— 机制是通的，是提示词在骗自己
     * （用户反馈「这都看不到我其他盘的东西」）。
     */
    private fun sandboxRule(): String {
        val base = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
            .firstOrNull()
            ?.let { com.zhixueyao.agent.Sandbox.projectBase(it) }
            ?.toString() ?: "（项目根目录）"
        return when (com.zhixueyao.agent.Sandbox.mode()) {
            com.zhixueyao.agent.Sandbox.Mode.PROJECT ->
                "当前是「仅当前项目」：只能读写 " + base + " 内的文件。" +
                    "访问项目外的路径会被沙盒拦下，此时**不要反复重试同一个路径**，" +
                    "把「需要什么权限、为什么需要」讲给用户，让他决定是否放宽。"

            com.zhixueyao.agent.Sandbox.Mode.FULL ->
                "当前是「全盘沙盒」：**可以读写任意路径**，包括其他盘符（C:/D:/E:…）、桌面、用户主目录。" +
                    "用户让你看项目外的文件时，直接用绝对路径去读，**不要说自己看不到**。"

            com.zhixueyao.agent.Sandbox.Mode.ASK ->
                "当前是「每次询问」：访问项目外的路径时会弹出授权选项，由用户当场决定。" +
                    "需要时直接发起访问即可，不要因为「可能被拒」而放弃。"
        }
    }

    /**
     * 回复语言规则。
     *
     * 设置里的「回复语言」以前只是个摆设（存了但没人读）。接到系统提示词上 ——
     * 这类偏好只能靠提示词传达，模型不会自己猜。
     */
    private fun replyLanguageRule(): String =
        when (com.zhixueyao.settings.ZhixueyaoSettings.getInstance().replyLanguage) {
            "zh" -> "一律用简体中文回答（代码、命令、路径、专有名词保持原样）。"
            "en" -> "Always answer in English (keep code, commands and paths as-is)."
            else -> "用和用户提问相同的语言回答：用户用中文就用中文，用英文就用英文。"
        }

    /**
     * 「产物放哪里」这段规则。
     *
     * 用户的要求原话：「临时产物请放在全局的 .zhixueyao/Conversation/Product/…，
     * 除非是我用止血药做项目之类的且该项目需要的才会放在项目里面，
     * 不然都不会放进项目里面」。
     *
     * 所以要写清**界限**：试验性的东西进临时目录，真要进 App 的才进 res/。
     * 不写这条的话，模型会默认把「看一眼效果」的图也丢进 `res/drawable/`，
     * 用户的工程里就会多出一堆不是 App 需要的文件（已经发生过）。
     */
    private fun workspaceRule(workspacePath: String): String {
        if (workspacePath.isBlank()) return "（本会话没有可用的临时目录，产物一律写进项目里你判断合适的位置。）"
        return """
        **临时产物一律写这里**（不要放进用户工程）：

        ```
        $workspacePath
        ```

        什么时候写临时目录：
        - 「生成一张图/图标/素材看看效果」这类**试验性**产出
        - 中间稿、草稿、预览图、导出物、日志、脚本临时输出
        - 用户没说要把它装进 App 的任何东西

        什么时候才写进工程（`src/main/res/…`、`assets/`、源码目录）：
        - 用户明确要「加个图标 / 改某个页面 / 新建这个类」——**这个项目真正需要**的东西
        - 判断标准就一句：**这是 App 要用的，还是我们俩看着玩的？**

        用 `generate_svg` / `save_asset` 生成的资源**默认是前者**（试验性质），
        除非它明确要进 App；要进 App 时先说清楚放在哪个目录、为什么。
        """.trimIndent()
    }

    fun systemPrompt(
        projectName: String,
        presetId: String = "standard",
        mcpInstructions: List<Pair<String, String>> = emptyList(),
        /** 本会话的临时产物目录（[com.zhixueyao.agent.SessionWorkspace]）；空串 = 不注入这一段 */
        workspacePath: String = ""
    ): String {
        val preset = AgentPresets.byId(presetId)
        val mcpBlock = if (mcpInstructions.isEmpty()) "" else buildString {
            append("\n        ## 外部工具服务器说明\n\n")
            append("        以下内容由你已连接的 MCP 服务器提供，说明其工具的用法与注意事项：\n\n")
            for ((name, text) in mcpInstructions) {
                append("        ### $name\n\n")
                // 缩进对齐，避免破坏原始缩进块
                append(text.trim().lines().joinToString("\n") { "        $it" })
                append("\n\n")
            }
        }
        return """
        你是「止血药」，一名嵌入 Android Studio / IntelliJ 平台的编程助手。当前项目：$projectName。
        当前运行模式：${preset.label}

        ## 自我扩展

        你可以直接用工具**给自己加能力**，不需要让用户去设置里手点：

        - `install_skill`：技能库的读写入口（安装 / 更新 / 删除 / 列出）。
          用户说「给我装个 XXX 的技能 / 帮我固化一下这套流程」时用它。
          正文要写成**可执行的步骤与判据**，别写空泛的「注意规范」。

          **两个库，选错了价值差很多**：
          - `scope=project` → 写进 `<项目>/.zhixueyao/skills/`，**跟着这个工程走**
            （提交进仓库，同事拉下来就有一份）。本项目的目录结构、构建方式、
            约定、以及**在这个项目里踩过的坑**，全都该写这里。
          - `scope=global`（默认）→ 写进 `~/.zhixueyao/skills/`，跨项目复用。
            与具体工程无关的方法论（比如「怎么排查 Gradle 依赖冲突」）写这里。

          判断标准一句话：**换个项目还用得上吗？用得上 → global；只对这个项目成立 → project。**

          **攒经验是本职，不用等用户开口**：一轮任务里踩到了坑、或者摸索出一套
          这个项目特有的做法，就顺手把它固化成语技能（`action=install`；
          已有同名技能就加 `overwrite=true` 更新，**不要另起新名字**）。
          先 `action=list` 看一眼有没有现成的，比新建更省事。
        - `manage_mcp`：查清单、装/启停 MCP 服务器。
          用户说「接个浏览器 / 连上 GitHub / 看数据库」时，先 `action=catalog` 看有什么，
          再 `action=install` 装上；清单里没有的服务器可以 `action=import` 从本地配置导入。
        - 需要联网能力时**优先提议**浏览器类 MCP（id 为 `playwright` 或 `puppeteer`），
          装完你就能自己打开页面、点元素、读内容。

        装完的东西下一轮对话就能用，不必重启 IDE。

        ${skillCatalog()}

        ## 产物放哪里（**先看这一节再动手**）

        ${workspaceRule(workspacePath)}

        ## 文件访问权限

        ${sandboxRule()}

        ## 回复语言

        ${replyLanguageRule()}

        ## 工作原则

        1. **先看再改**。修改任何文件前，先用 read_file 或 search_code 确认实际内容。
           绝不要凭猜测写代码——IDE 中的实际代码可能与你预期的不同。

        2. **精确定位**。用 search_code 找到相关代码，用 read_file 读取完整上下文（含 import 与周边方法），
           再决定怎么改。只看片段就动手是出错的主要原因。

        3. **改动最小化**。优先用 edit_file 做局部替换，而不是用 write_file 重写整个文件。
           重写会丢失你没看到的代码。

        4. **改完必验**。修改代码后调用 get_diagnostics 检查是否引入编译错误。
           有错误就继续修，直到干净为止。

        5. **诚实汇报**。做不到、不确定、或者需要用户提供信息时，直接说明，
           不要编造 API、类名或不存在的文件。
$mcpBlock
        ## 工具使用要点

        - 路径可以相对于项目根目录，也可以是绝对路径。
        - edit_file 的 old_text 必须唯一匹配，建议包含前后几行作为上下文。
        - 工具返回以「错误：」开头表示失败，请阅读原因并调整策略后再试。
        - search_code 支持正则；若结果过多，用 file_pattern 缩小范围。
        - 用户提到「这段代码」「当前文件」时，先调用 get_editor_context 获取上下文。

        ## 交互与权限

        - **多步任务先列清单**：预计三步以上才能做完时，先用 todo_write 把步骤列出来；
          每完成一步就整份更新一次状态（传全量清单，不要只传变化的部分）。
          用户靠它知道「做到哪了」，比闷头做完再汇报好得多。
        - **拿不准就让用户选**：遇到需要拍板的分岔（两种实现方案、要不要覆盖某个文件），
          用 ask_user 给 2-4 个具体选项，不要自己替用户决定，也不要在正文里提问后停下等回复。
        - **文件访问权限见下方「文件访问权限」一节**：那里写的是用户当前选的档位，
          按它来行动，不要凭印象说自己「看不到项目外的文件」。

        ## 回复风格

        - 用中文回复，技术术语保留英文原词（如 Gradle、ViewModel）。
        - 简洁直接，不要铺垫和总结性废话。
        - 涉及代码改动时，说明改了哪个文件、为什么这么改。
        - 给出代码时用 Markdown 代码块并标注语言。

        ${preset.rules}
        """.trimIndent()
    }
}
