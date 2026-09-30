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
    /**
     * MCP 管理器。
     *
     * **可空**：没有 MCP 的运行器是一个有意义的状态 ——
     * 离线回归（`ScriptedProvider` + 真实循环）不需要任何外部服务，
     * 而 `McpManager` 是 final 类、既不能继承也不能代理，硬造一个假的做不到。
     * 传 null 时：工具清单里不含 MCP 工具，调用 MCP 工具会明确报错（而不是静默失败）。
     */
    private val mcpManager: McpManager? = null
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

        /**
         * 子代理的兜底寿命：超过就自动停。
         *
         * 10 分钟。取这个数的依据：一次只读调研（读十几个文件）正常在 1-3 分钟，
         * 给三倍余量。它挡的是**没人认领的子代理**（模型 spawn 完没 wait 就回复用户了），
         * 不是「跑得比较慢的子代理」—— 所以宁可宽一点，别把正常任务砍了。
         */
        const val SUBAGENT_MAX_LIFETIME_MS = 10 * 60 * 1000L
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
        steering: () -> List<String> = { emptyList() },
        /**
         * 替换掉默认的模型接入（**只给离线回归用**）。
         *
         * 为什么需要这个口子：Agent 循环里最容易出错的东西 —— 工具编排、
         * 工具报错后能不能继续、取消能不能立刻生效、steer 有没有在 turn 边界注入、
         * 压缩有没有触发 —— 全都**不在单元函数里**，而在这一圈循环里。
         * 只测单元函数的话，这些地方永远测不到。
         *
         * 传了它就能用「脚本化的假模型」驱动**真实的循环**（见 `ScriptedProvider`）：
         * 零 token、无网络、可进回归。
         *
         * 默认 null = 行为与之前**逐字节一致**（参考项目 mavis 的 "Off by default" 原则：
         * 可选能力不配置时不得改变既有行为）。
         */
        providerOverride: com.zhixueyao.llm.LlmProvider? = null,
        /**
         * 嵌套层级。0 = 用户直接发起的那一轮，1 = 子代理。
         *
         * 用它把子代理工具**只加在最外层**：第 1 层不再加 `spawn_agent`，
         * 于是「子代理再派子代理」在构造上就走不通。
         *
         * 为什么不用计数器来限深度：计数器在异常路径上容易漏减，
         * 漏一次就会留下一条能无限派发的路径。**「工具箱里根本没有那个工具」
         * 是没有这个问题的不变量。**
         *
         * （第二道保险：[com.zhixueyao.tools.SubagentRegistry.SUBAGENT_ALLOWED]
         * 白名单里也没有 spawn_agent，所以就算有人忘了传 depth 也派不出去。）
         */
        depth: Int = 0
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

        val provider = providerOverride
            ?: com.zhixueyao.llm.Providers.createProvider(settings.apiFormat)

        // 组装工具清单：内置工具（经预设过滤） + 已连接的 MCP 工具。
        // 真正的 specs 在循环里每轮重算（MCP 工具会中途增减），这里只准备过滤好的内置工具。
        val preset = AgentPresets.byId(settings.agentPreset)
        val allowedTools = buildList {
            addAll(tools.filter { preset.allowTool(it.name) })

            // 子代理工具**在这一层才加**，不进 ToolRegistry 的静态清单。
            //
            // 原因是它们要 `config` / `cancelFlag` / `providerOverride` ——
            // 这些只有 run() 内部才有。放进静态清单的话，工具类就得自己去取，
            // 又变成「读全局状态」，也就没法离线验证了
            // （ToolRegistry 里那几处踩过这个坑，见其注释）。
            //
            // 放行条件：
            //   depth == 0         —— 子代理不能再派子代理
            //   预设能读文件        —— 「委派」本质是「去读书」，读都不许就别谈了
            //   子代理有工具可用    —— 裁剪后为空时加了也是白加
            if (depth == 0 && settings.enableSubagent && preset.allowTool("read_file")) {
                val scoped = com.zhixueyao.tools.SubagentRegistry.scopeTools(tools)
                if (scoped.isNotEmpty()) {
                    add(
                        com.zhixueyao.tools.SpawnAgentTool(tools) { job, subTools ->
                            launchSubagent(job, subTools, config, preset, cancelFlag)
                        }
                    )
                    add(com.zhixueyao.tools.ListAgentsTool())
                    add(com.zhixueyao.tools.WaitAgentTool())
                    add(com.zhixueyao.tools.InterruptAgentTool())
                }
            }
        }

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

            // **provider 抛出的异常必须有兜底**。
            //
            // 现有两个 provider 内部都自己 runCatching、把错转成 LlmEvent.Failure，
            // 所以这条路径平时不走 —— 但那是**它们的实现细节，不是接口契约**。
            // 一旦某个 provider（新加的厂商、第三方实现、将来重构）漏了一处，
            // 异常就会直接冒到界面，表现为「点了发送没反应」或 IDE 报错弹窗
            // （故障注入探针 AgentLoopProbe 场景③ 就是这么把它们逼出来的）。
            //
            // 这里兜住并转成 onError，与 provider 自己报错走同一条出口。
            val threw = runCatching {
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
            }.exceptionOrNull()

            if (threw != null) {
                // 取消导致的异常不算错误（用户自己按的停止）
                if (!cancelFlag.get()) {
                    AgentLog.record(AgentLog.Kind.ERROR, "模型接入抛出异常", "${threw.message}")
                    listener.onError(
                        "模型接入出错：${threw.message ?: threw.javaClass.simpleName}" +
                            "\n\n可以检查：接口地址与密钥是否正确、网络是否可用、模型名是否存在。"
                    )
                }
                return
            }

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
    /**
     * 把一个子代理放到后台线程上跑。
     *
     * ## 为什么要新起线程而不是同步跑
     *
     * 同步版本写起来短得多，但 `interrupt_agent` 会变成**死代码**：
     * 父代理正在 `wait` 里被卡住，根本没机会调它。异步还带来一个实打实的收益 ——
     * 「分别看看这三块代码」可以**并行**读，串行的话三个文件组要排队。
     *
     * ## 为什么它有自己的系统提示词，而不是复用父代理那份
     *
     * 父代理那份里写着「怎么跟用户说话」「技能库目录」「当前项目沙盒规则」
     * 等等一大堆**对一个只读侦察兵毫无用处**的东西。子代理要的是相反的指导：
     * 少说废话、把结论压到最短、别贴大段代码 ——
     * **它吐出来的每个字都要占父代理的上下文**，这正是委派要省的东西。
     *
     * 另外它**看不到父代理对话**（这正是省 token 的来源），所以任务描述里
     * 必须自带全部背景；提示词里要明确说这一条，否则子代理会以为
     * 「那个文件」是有所指的。
     */
    private fun launchSubagent(
        job: com.zhixueyao.tools.SubagentRegistry.Job,
        subTools: List<AgentTool>,
        config: LlmConfig,
        preset: AgentPresets.Preset,
        parentCancel: java.util.concurrent.atomic.AtomicBoolean
    ) {
        val thread = Thread({
            val collected = StringBuilder()
            val subHistory = mutableListOf(
                ChatMessage.system(subagentSystemPrompt()),
                ChatMessage.user(job.task)
            )

            // 子代理的取消标志 = 它自己的 cancel ∪ 父代理的 cancel。
            // 「用户点了停止」时子代理必须跟着停 —— 否则父代理都退出了，
            // 后台还有几个线程在打接口，用户看到的是「明明停了还在烧钱」。
            val listener = object : Listener {
                /**
                 * 每个事件都过一遍的两道闸。
                 *
                 * 放在事件回调里而不是单起一个看门狗线程：**零成本**（本就要处理事件），
                 * 而看门狗要为一个可能只跑两秒的子代理睡十分钟。
                 */
                private fun checkParent() {
                    // 闸一：用户点了停止 —— 子代理必须跟着停。
                    // 否则父代理都退出了，后台还有几个线程在打接口，
                    // 用户看到的是「明明停了还在烧钱」。
                    if (parentCancel.get()) job.cancel.set(true)

                    // 闸二：兜底寿命。
                    // 为什么需要它：模型可能 spawn 完就不 wait 了（自己忘了、或者直接给用户
                    // 回复了）。那个子代理就成了**没人认领的后台任务** ——
                    // 结果永远取不回来，token 一直烧。
                    // 不选「父代理一结束就全杀」是因为「这轮派、下轮取」是合理用法；
                    // 也不选看门狗线程（见上）。
                    if (job.elapsedMs() > SUBAGENT_MAX_LIFETIME_MS) {
                        job.cancel.set(true)
                        job.activity = "超过 ${SUBAGENT_MAX_LIFETIME_MS / 60_000} 分钟，自动停止"
                    }
                }

                override fun onTextDelta(text: String) {
                    checkParent()
                    collected.append(text)
                }

                override fun onToolStart(call: com.zhixueyao.llm.ToolCall) {
                    checkParent()
                    // 这一步就是「它现在在干什么」，list_agents 直接展示给模型和用户
                    job.activity = describeCall(call)
                }

                override fun onToolFinish(call: com.zhixueyao.llm.ToolCall, result: ToolResult) {
                    checkParent()
                }

                override fun onComplete(finalText: String, steps: Int, usage: com.zhixueyao.llm.Usage?) {
                    // onComplete 的 finalText 是权威版本（已剥掉思维标记），
                    // 流式攒的那份可能有重复，所以以它为准
                    job.result = finalText.ifBlank { collected.toString() }
                    job.state = "done"
                    job.activity = "已结束（$steps 步）"
                }

                override fun onError(message: String) {
                    job.result = message
                    job.state = "failed"
                    job.activity = "出错"
                }
            }

            try {
                // 子代理用**独立实例**，不共用父代理那个 ——
                // run() 里有一堆局部状态（steps、token 累加、压缩基线），
                // 复用实例会让两边的计数互相污染
                AgentRunner(project, mcpManager).run(
                    history = subHistory,
                    tools = subTools,
                    listener = listener,
                    cancelFlag = job.cancel,
                    // 不传 steering：那是「用户中途插话」的通道，子代理不该有
                    // 不传 summaryState：独立上下文不需要压缩（步数上限就卡住了）
                    depth = 1
                )
                if (job.state == "running") {
                    // run() 正常返回但 onComplete 没被调用 —— 只可能是被取消
                    job.state = if (job.cancel.get()) "cancelled" else "done"
                    if (job.result == null) job.result = collected.toString()
                }
            } catch (t: Throwable) {
                // 子代理里**任何**异常都不能把父代理拖垮，也不能变成静默失败：
                // 记进 job.result，父代理 wait 时会如实看到
                job.state = "failed"
                job.result = "子代理异常退出：${t.message ?: t.javaClass.simpleName}"
                job.activity = "异常退出"
                log.warn("子代理 ${job.id} 异常", t)
            }
        }, "zhixueyao-subagent-${job.id}")

        // 守护线程：IDE 退出时不会因为还有子代理在跑而挂住
        thread.isDaemon = true
        thread.start()
        log.info("派出子代理 ${job.id}：${job.task.take(80)}（工具 ${subTools.size} 个）")
    }

    /** 把一次工具调用说成一句人话，用于 list_agents 的「正在：」 */
    private fun describeCall(call: com.zhixueyao.llm.ToolCall): String {
        val arg = runCatching {
            val o = Json.parse(call.arguments)
            o.str("path") ?: o.str("pattern") ?: o.str("query") ?: o.str("name") ?: ""
        }.getOrDefault("")
        return call.name + (if (arg.isBlank()) "" else "（${arg.take(60)}）")
    }

    /**
     * 子代理的系统提示词。
     *
     * 刻意写得**很短**，而且和父代理那份完全分开。三条要求都是有原因的：
     *
     * 1. **只读** —— 工具箱已经限制了，但提示词里也要说。否则子代理会反复尝试
     *    调 `edit_file`，每次都被「没有这个工具」打回，白烧几轮。
     * 2. **结论要短** —— 它吐的每个字都进父代理上下文。约束输出长度是这套机制
     *    **省 token 的最后一环**：读了十万 token，最后回两千才划算；
     *    回五万的话等于白委派。
     * 3. **看不到父对话** —— 这一条不说清，子代理会写出「那个文件里……」这种
     *    对父代理毫无信息量的话。
     */
    private fun subagentSystemPrompt(): String = """
        你是一个**只读侦察子代理**，被主代理派来做一次调研。完成任务后把结论交回去。

        ## 你要遵守的

        1. **只能读、搜、看**。你没有任何写文件的工具，也不要试图改任何东西。
           你的价值在于把散在多个文件里的信息汇总成一段结论。

        2. **你看不到主代理和用户的对话**。任务描述里写了什么，你就只有什么。
           如果任务描述里提到的东西你不知道在哪，就去搜 —— 不要猜，也不要反问
           （你问不到任何人，只能自己找）。

        3. **结论要短，这是硬要求**。你读的内容不会传给主代理，只有你写下的字会。
           所以：
           - 直接给结论和依据（`文件路径:行号` 这种定位要留）
           - **不要贴大段代码**，最多贴关键的一两行
           - 不要复述你搜过哪些关键词、读过哪些文件（除非那次搜索是结论的一部分）
           - 目标长度：**能说清就行，通常十几行以内**

        ## 你要做的

        用 read_file / search_code / find_symbol / glob_files / list_directory
        把任务查清楚，然后输出一段结构化结论。如果任务里问了多个问题，
        就分点回答，每点给依据。

        如果任务本身无法完成（比如要找的东西根本不存在），**直接说「没找到」并说明
        你查了哪些方向** —— 这比编一个看似合理的答案有用得多。
    """.trimIndent()

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

        val mcp = mcpManager
        if (includeMcp && mcp != null) {
            runCatching {
                for (pt in mcp.allTools()) {
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
                val m = mcpManager ?: return ToolResult(
                    "这个会话没有连接 MCP，无法调用 ${call.name}。" +
                        "请到 设置 → 插件 里配置并启用对应的服务器。",
                    ok = false
                )
                // 把取消标志传下去：MCP 调用最长要等 2 分钟，
                // 不传的话用户点「停止」得干等它超时（用户反馈过）
                val result = m.callTool(call.name, call.argsAsJson(), cancelFlag)
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
        // 跑测试。和 run_build 是两件事：**编译通过只说明类型对，不说明行为对** ——
        // 「改完有没有把别的功能改坏」只有测试能回答
        com.zhixueyao.tools.RunTestsTool(),
        com.zhixueyao.tools.ProjectStructureTool()
    )

    /**
     * 工程与外部能力。
     *
     * 这一组是**后来补上的最大一块空白**：在这之前插件只能「读代码、改代码、编译」，
     * 不能看历史、不能上网、不能算。而参考项目里这些都是基础配置 ——
     * agents-universe 有 `git_repo` / `web_fetch` / `code_executor`，
     * insight-agents 有「沙箱定量计算」，astravia 有内置终端。
     *
     * 补上之后，很多原来只能靠模型记忆和心算的问题，变成了**有真实依据**的：
     * 「这段代码为什么变成这样」→ git log/blame；
     * 「这个 API 怎么用」→ web_fetch 查官方文档；
     * 「占比是多少」→ run_script 真跑一遍。
     */
    val devTools: List<AgentTool> = listOf(
        com.zhixueyao.tools.GitTool(),
        com.zhixueyao.tools.RunScriptTool(),
        com.zhixueyao.tools.WebFetchTool(),
        // 后台任务三件套。**它们只在「最外层」加** ——
        // 子代理是只读侦察兵，不该起常驻进程（那会在父代理结束后继续占着端口）。
        // 与子代理工具同样的理由：需要 cancelFlag 之外的生命周期管理，
        // 放进静态清单会让工具类去读全局状态。
        com.zhixueyao.tools.RunBackgroundTool(),
        com.zhixueyao.tools.TaskOutputTool(),
        com.zhixueyao.tools.TaskStopTool(),
        // 取时间。**不加它的话模型不知道今天几号** —— `git log` 里看到 2026-09-25
        // 分不清是三天前还是三个月前，「最近一周」也只能猜。
        // 提示词里也注入了日期作基线，两者分工见 CurrentTimeTool 的注释。
        com.zhixueyao.tools.CurrentTimeTool()
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
        com.zhixueyao.tools.ManageMcpTool(),
        // 记忆同理：写的是自己的配置目录，不碰工程。而且**只读研究最需要记住结论** ——
        // 「读了半天得出的判断」下次不该重读一遍
        com.zhixueyao.tools.MemoryTool(),
        // 知识库同理：读写的是 ~/.zhixueyao/kb/，不碰工程文件。
        // **研究模式恰恰最需要它** —— 「读了很多资料」正是该沉淀成知识页的时刻。
        com.zhixueyao.tools.KbWriteTool(),
        com.zhixueyao.tools.KbSearchTool(),
        com.zhixueyao.tools.KbTagsTool()
    )

    val all: List<AgentTool> =
        fileTools + searchTools + buildTools + devTools + interactionTools

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
    /**
     * 技能目录 + 写作速查。
     *
     * @param project 用来找「项目技能库」；null 时只列全局的。
     *
     * **以前这里自己去 `ProjectManager.getInstance().openProjects.firstOrNull()` 拿项目** ——
     * 那是全局状态，导致这个函数在离线探针里直接 NPE（MockApplication 没有 ProjectManager），
     * 也就没法验证「提示词里到底带了什么」。改成参数传入后，
     * 纯函数部分可以随便测（SkillFieldsProbe 场景④ 就是在测它）。
     */
    private fun skillCatalog(project: com.intellij.openapi.project.Project?): String =
        com.zhixueyao.agent.Skills.catalogForPrompt(project) +
            // 技能写作速查。
            //
            // 为什么放在**系统提示词**而不是工具描述里：技能库是「越用越好」的东西，
            // 而写得好不好直接决定它下次会不会被想起来。工具描述只在模型真去调
            // install_skill 时才有语境，提示词则是每轮都在 —— 攒经验这个动作
            // 本来就不该只在「用户让我装技能」时才发生。
            //
            // 只留最关键的 4 条，约 150 token。抄自参考项目 agents-universe 的
            // skill-authoring-guide（它那份很全，但全量塞进来不值这个 token）。
            """

            ## 怎么写一个好技能（攒经验时对照）

            1. **正文写可执行的步骤与判据**，不写「注意规范」这种空话。
               好例子：「改 ui/ 前先对照清单：圆角必须自绘、BoxLayout 要设 alignmentX」。
            2. **`triggers` 必须填**（2-5 条，逗号分隔，**别写单字**）。
               写「用户可能的不同说法」，不是把技能名换个说法。
               不填的话它只能靠模型自己想起来 —— 那等于没写。
            3. **`scope=project` 还是 `global`**：换个项目还用得上吗？
               用得上 → global；只对这个工程成立（目录结构、构建方式、踩过的坑）→ project。
            4. **有缺口就标出来**：正文里写
               `<!-- gaps: ["真机交互还没验证"] -->`，
               下次更新时就知道该补什么，而不是重写一遍。
            """.trimIndent()

    /**
     * 当前沙盒档位对应的行为说明。
     *
     * 必须动态生成：提示词里写死「只能访问当前项目」的话，用户切成「全盘沙盒」后
     * 模型仍然会回答「我看不到你的桌面」—— 机制是通的，是提示词在骗自己
     * （用户反馈「这都看不到我其他盘的东西」）。
     */
    /**
     * 沙盒规则那一段。
     *
     * 与 [skillCatalog] 同一处改动：**项目由参数传进来，不自己去查全局状态**。
     * 原因也一样 —— 读全局状态让这个函数在离线探针里直接 NPE
     * （`ProjectManager.getInstance()` 在 MockApplication 下是 null），
     * 于是「提示词到底写了什么」就永远验不了。
     */
    private fun sandboxRule(project: com.intellij.openapi.project.Project?): String {
        val base = project
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

    /**
     * 「今天是几号」那一行。
     *
     * ## 为什么必须写进提示词
     *
     * 加它之前，**没有任何地方告诉模型今天是什么日子** —— 后果都很具体：
     * `git log` 里看到 `2026-09-25` 分不清是三天前还是三个月前；
     * 「最近一周改了哪些文件」只能挑个数字去猜；用户说「下周之前搞定」算不出是哪天。
     * 这类错误不报错、不崩，只是**答得不对**。
     *
     * ## 时间在这里取，还是从外面传？
     *
     * 这里直接取当前时间 —— 和 [com.zhixueyao.tools.CurrentTimeTool] 相反。
     * 理由：**它没有可断言的输出**（每次调用都不同），
     * 所以不存在「探针要验具体内容」的需求；而调用方（ChatPanel）每轮重建提示词，
     * 天然就是新鲜的。给这个函数加一个 `now` 参数只会让所有调用点都要传时间，白增负担。
     *
     * 星期也要带上：判断「周末」「下周三」这类说法时，模型自己从日期推星期几
     * 是高频出错点（闰年、跨月尤其容易错），直接给了就不用推。
     */
    private fun todayLine(): String {
        val now = java.time.ZonedDateTime.now()
        val w = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(now.dayOfWeek.value - 1) { "?" }
        return now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            .toString() + "（星期$w）"
    }

    fun systemPrompt(
        projectName: String,
        presetId: String = "standard",
        /**
         * 当前项目。
         *
         * 用来找「项目技能库」；`null` 时只列全局技能。
         * **不要在这里再退回全局查找** —— 调用方本来就有 project，
         * 自己去找全局状态只会让这个函数没法离线验证。
         */
        project: com.intellij.openapi.project.Project? = null,
        mcpInstructions: List<Pair<String, String>> = emptyList(),
        /** 本会话的临时产物目录（[com.zhixueyao.agent.SessionWorkspace]）；空串 = 不注入这一段 */
        workspacePath: String = "",
        /**
         * 本条消息命中的技能提示（[Skills.renderTriggerHint]）；空串 = 没命中，不占 token。
         *
         * 放在**系统提示词的靠后位置**：它和「这一轮具体要做什么」有关，
         * 而系统提示词整体是每轮重发的固定开销 —— 靠后也照样会被看到，
         * 但不会被误当成长期约定。
         */
        triggerHint: String = ""
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

        今天的日期：${todayLine()}

        **这条日期是本轮请求开始时取的**（提示词每一轮都会重发）。所以它足够新，
        但如果你正在做一件跑很久的事、或者需要精确到分钟，用 current_time 再确认一次。
        判断「几天前」时以它为基准 —— 不要凭训练数据里的时间感猜。

        ## 自我扩展

        你可以直接用工具**给自己加能力**，不需要让用户去设置里手点：

        - `git`：看历史与改动（status/diff/log/show/blame）与提交。
          **改代码前先看一眼这段代码为什么长这样**，比读十遍当前代码有用。
          不提供 push / reset --hard / clean —— 那类操作让用户自己决定。
        - `web_fetch`：抓网页。**查 API 用法、报错含义、版本变化时必须用它** ——
          库的 API 是最容易过时的东西，凭记忆答错一个参数名会让用户白改半天。
          只抓公网，不访问本机与内网。
        - `git`：查历史与改动、也能提交。**push / pull 需要用户先在设置里开「Git 助手」**
          —— 没开时调它们会被挡回来并说明去哪儿开。开了之后插件会按用户配的代理与账号去连，
          **不会改动他的 git 配置**（参数是临时传的）。
          推送失败时先看报错里的 `over proxy` 字样：那是配置里一个过期的代理地址，
          不是网络不通 —— 让用户去「设置 → Git 助手」点一次「测试连接」就有结论了。
        - `run_script`：跑几行 Python / Node 做定量计算。
          **凡是涉及数数、占比、解析、试正则的，都用它真跑一遍**，不要心算 ——
          它的价值正是「结果是真的，不是估的」。
        - `spawn_agent`：**派一个只读子代理**去独立地读、搜、看，然后只把结论带回来。
          它读到的内容**不进我们的对话**，所以这是省上下文的主要手段。

          什么时候用：**「要读很多东西，但结论只有几句话」**的时候。
          典型场景：「找出这个功能的完整调用链」「把所有 ViewModel 扫一遍看谁没做空值处理」
          「这个报错是从哪儿抛出来的」。这种事自己读要烧掉几万 token，
          而其中九成内容在结论里用不到 —— 派出去，只收结论。

          什么时候**不要**用：只有一个文件要看（直接 read_file 更快）、
          需要改东西（子代理是只读的）、或者你已经在读文件了（别为了用而用）。

          三个要点：
          - 任务描述要**自带全部背景** —— 它是独立上下文，看不到我们在聊什么
          - **必须 wait_agent 取结论**，不要凭印象猜它看到了什么
          - 互不依赖的任务可以**一次派几个并行跑**（最多 3 个）
        - `memory`：跨会话记住事实。听到「以后都这样」「记住」就记下来，
          否则下次开新会话用户又要重说一遍。
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
        - `run_tests`：跑测试。**`run_build` 通过只说明类型对，不说明行为对** ——
          「这次改动有没有把别的功能改坏」只有它能回答。
          改完涉及逻辑的代码就该跑一次；纯改文案、注释、格式不用。
        - `run_background` / `task_output` / `task_stop`：起**不会自己结束**的命令
          （开发服务器、日志跟踪、几分钟的完整构建）。判据很简单：
          **「这条命令会自己跑完吗？」会 → 用同步的；不会 → 用后台的。**
          起了之后用 `task_output` 看进度，`task_stop` 收尾 —— 常驻服务用完要停掉。
        - `kb_search` / `kb_write`：跨项目共用的知识库。**它和 `search_code` 的分工是核心**：

          | 你的处境 | 用哪个 |
          |---|---|
          | 记得确切的字符串（函数名、报错原文） | `search_code` |
          | **记得有这回事、但想不起原话** | **`kb_search`** |
          | 要沉淀一条以后还用得上的结论 | `kb_write` |

          第二种是它唯一存在的理由 —— `search_code` 要求你给出确切字符串，
          而「上次记过依赖冲突怎么处理来着」这种问题你根本不知道该搜什么词。
          `kb_search` 返回**按相关度排序的小节**，相关度低时会明确标注
          —— 看到那个标注就别硬当结论用。`kb_tags` 可以看里面都有什么。

        - `manage_mcp`：查清单、装/启停 MCP 服务器。
          用户说「接个浏览器 / 连上 GitHub / 看数据库」时，先 `action=catalog` 看有什么，
          再 `action=install` 装上；清单里没有的服务器可以 `action=import` 从本地配置导入。
        - 需要联网能力时**优先提议**浏览器类 MCP（id 为 `playwright` 或 `puppeteer`），
          装完你就能自己打开页面、点元素、读内容。

        装完的东西下一轮对话就能用，不必重启 IDE。

        ## 找东西的时候用哪个

        这四个都能「找东西」，但用途不重叠，选错了会白跑一趟：

        | 你要找的是 | 用 |
        |---|---|
        | 某段**代码内容**（我知道大概写了什么） | `search_code`（支持正则与 `file_pattern`） |
        | 某个**符号定义**（类/函数名，想跳到定义） | `find_symbol` |
        | **文件名**（我知道文件叫什么） | `find_files` |
        | **用户当前在看什么**（没说是哪个文件时） | `get_editor_context` |

        用户说「帮我看看这个」「这里有问题」而没指明文件时 —— **先用 `get_editor_context`**，
        别上来就猜文件路径。它给的是用户光标所在的位置，那通常就是他们指的「这个」。

        ${skillCatalog(project)}

        ${if (triggerHint.isBlank()) "" else triggerHint}

        ${com.zhixueyao.agent.MemoryStore.renderForPrompt(project)}

        ## 产物放哪里（**先看这一节再动手**）

        ${workspaceRule(workspacePath)}

        ## 文件访问权限

        ${sandboxRule(project)}

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
