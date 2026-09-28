package com.zhixueyao.llm

import java.util.concurrent.atomic.AtomicBoolean

/**
 * **脚本化的假模型**，用来离线驱动真实的 Agent 循环。
 *
 * ## 为什么要有它
 *
 * 借鉴自参考项目 insight-agents 的 `evals/fault_inject/mock_llm.py`
 * （它叫 `ScriptedChatModel`）。那个项目的注释里有一句很实在的话：
 * 「使演练可重复、零 token 成本、CI 可跑」。
 *
 * 我们这个工程的 20 多个探针**全是单元级** —— 测函数、测布局、测解析。
 * 但历史上最贵的 bug 一条都不在那儿：
 *
 * | 出过的事故 | 在哪一层 |
 * |---|---|
 * | steer 的追加输入没被模型看到 | Agent 循环 |
 * | 点「停止」要等 2 分钟 | Agent 循环（MCP 分片等待） |
 * | 工具结果撑爆上下文 | Agent 循环（压缩时机） |
 * | 工具报错后整轮崩掉 | Agent 循环（错误恢复） |
 * | 一句话渲染成好几个气泡 | 历史 → 界面（轮次分组） |
 *
 * 这些**只能靠「给定输入，跑一遍循环，看它调了什么、产出什么」**来验。
 * 有了这个假模型，就能用真实循环做行为断言，而且不烧一分钱、不连一次网。
 *
 * ## 脚本长什么样
 *
 * 每一项是模型「一次回答」：
 * ```
 * ScriptedProvider(
 *     turn.text("我先看看这个文件。"),
 *     turn.tool("read_file", """{"path":"a.kt"}"""),
 *     turn.text("看完了，结论是……"),
 * )
 * ```
 * 循环每请求一次就消费一项；脚本用完后的行为由 [whenExhausted] 决定。
 *
 * ## 三个刻意的设计
 *
 * 1. **`error()` 会真的抛异常**（不是返回 Failure 事件）——
 *    因为要验的是「网络断了/服务挂了，循环能不能扛住」，
 *    而真实世界的那类故障正是**抛异常**，不是优雅地回一个错误码。
 * 2. **`cancelFlag` 会被尊重**：脚本里可以插 `delayMs`，
 *    等待期间检查取消标志 —— 这样才测得出「取消能不能立刻生效」。
 * 3. **`whenExhausted = FAIL` 默认**：脚本用完还继续要，
 *    说明循环多跑了一轮（比如 maxSteps 没生效）——
 *    **这本身就是个 bug，要让它响亮地失败**，而不是悄悄返回空文本。
 */
class ScriptedProvider(
    private val turns: List<Turn>,
    /** 脚本用完后的行为 */
    private val whenExhausted: Exhausted = Exhausted.FAIL
) : LlmProvider {

    override val displayName: String get() = "ScriptedProvider（离线回归用）"

    /** 已经消费了几项 —— 探针靠它断言「循环请求了几次」 */
    @Volatile
    var consumed: Int = 0
        private set

    /** 每次请求时看到的**消息条数**，用来观察历史有没有被压缩 */
    val historySizes = mutableListOf<Int>()

    /** 每次请求时看到的工具清单名字，用来观察工具过滤 */
    val toolNamesSeen = mutableListOf<List<String>>()

    /** 每次请求时看到的**最后一条消息内容**（用于观察 steer 有没有注入进来） */
    val lastMessageSeen = mutableListOf<String>()

    private val lock = Any()

    override fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onEvent: (LlmEvent) -> Unit,
        cancelFlag: AtomicBoolean
    ) {
        synchronized(lock) {
            historySizes.add(messages.size)
            toolNamesSeen.add(tools.map { it.name })
            lastMessageSeen.add(messages.lastOrNull()?.content.orEmpty())
        }

        val turn = synchronized(lock) {
            val t = turns.getOrNull(consumed)
            consumed++
            t
        }

        if (turn == null) {
            when (whenExhausted) {
                // 默认：脚本用完还要请求 → 循环多跑了一轮，这是 bug，要响亮地失败
                Exhausted.FAIL -> throw IllegalStateException(
                    "脚本已用完（共 ${turns.size} 项），但循环还在请求第 ${consumed} 次 —— " +
                        "通常意味着 maxSteps / 终止条件没生效"
                )
                Exhausted.STOP -> {
                    onEvent(LlmEvent.Completed(ChatMessage.assistant("（脚本结束）", emptyList())))
                    return
                }
            }
        }

        // 模拟「流式逐片到达」，同时给取消留出可观察的窗口
        if (turn.delayMs > 0) {
            val step = 20L
            var waited = 0L
            while (waited < turn.delayMs) {
                if (cancelFlag.get()) {
                    // 真实的 provider 在取消时就是「停止回调」——
                    // 不发 Completed，让上层按「没有结果」处理
                    return
                }
                Thread.sleep(step)
                waited += step
            }
        }
        if (cancelFlag.get()) return

        if (turn.error != null) throw turn.error

        turn.text?.let { text ->
            // 分片发出，贴近真实的流式节奏（也顺带验了增量拼接）
            text.chunked(12).forEach { onEvent(LlmEvent.TextDelta(it)) }
        }
        turn.reasoning?.let { onEvent(LlmEvent.ReasoningDelta(it)) }

        onEvent(
            LlmEvent.Completed(
                message = ChatMessage.assistant(
                    text = turn.text.orEmpty(),
                    toolCalls = turn.toolCalls
                ),
                usage = turn.usage
            )
        )
    }

    /** 脚本用完之后怎么办 */
    enum class Exhausted {
        /** 抛异常（默认）—— 脚本用完还被请求，说明循环多跑了，那是 bug */
        FAIL,

        /** 当作正常结束，返回一段占位文本 */
        STOP
    }

    /** 模型的一次「回答」 */
    class Turn private constructor(
        val text: String?,
        val reasoning: String?,
        val toolCalls: List<ToolCall>,
        val error: Throwable?,
        val delayMs: Long,
        val usage: Usage?
    ) {
        companion object {
            // ---- Java 友好的入口 ----
            //
            // Kotlin 的 vararg + 默认参数在 Java 里调不动（要么变数组、要么缺参数），
            // 而离线探针是 Java 写的。这里给一组**不带默认参数、不带变参**的简单签名，
            // 让探针能一行写完一个 turn。Kotlin 侧继续用下面带默认参数的版本。
            @JvmStatic
            fun textOf(text: String): Turn = text(text)

            @JvmStatic
            fun reasoningOf(text: String, reasoning: String): Turn = text(text, reasoning)

            /** 一次调用一个工具 */
            @JvmStatic
            fun toolOf(name: String, args: String): Turn =
                tool(kotlin.Pair(name, args))

            /** 一次调用两个工具（验并发/顺序时用） */
            @JvmStatic
            fun twoToolsOf(n1: String, a1: String, n2: String, a2: String): Turn =
                tool(kotlin.Pair(n1, a1), kotlin.Pair(n2, a2))

            /** 抛异常（默认是 IOException，模拟断网） */
            @JvmStatic
            fun errorOf(message: String): Turn = error(message)

            /** 拖一段时间再回答 */
            @JvmStatic
            fun slowOf(delayMs: Long, text: String): Turn = slow(delayMs, text)

            /** 纯文本回答 */
            fun text(
                text: String,
                reasoning: String? = null,
                usage: Usage? = Usage(promptTokens = 100, completionTokens = 20)
            ) = Turn(text, reasoning, emptyList(), null, 0, usage)

            /** 带工具调用的回答（可以一次调多个） */
            fun tool(
                vararg calls: Pair<String, String>,
                text: String? = null,
                usage: Usage? = Usage(promptTokens = 100, completionTokens = 20)
            ) = Turn(
                text = text,
                reasoning = null,
                toolCalls = calls.mapIndexed { i, (name, args) ->
                    ToolCall(id = "call_$i", name = name, arguments = args)
                },
                error = null,
                delayMs = 0,
                usage = usage
            )

            /** 抛异常 —— 模拟断网 / 服务不可达 */
            fun error(e: Throwable) = Turn(null, null, emptyList(), e, 0, null)

            /** 抛一个普通异常（省得每次 new） */
            fun error(message: String = "模拟：连接被重置"): Turn =
                error(java.io.IOException(message))

            /** 拖一段时间再回答，用来测「取消能不能立刻生效」 */
            fun slow(delayMs: Long, text: String = "慢回答") =
                Turn(text, null, emptyList(), null, delayMs, Usage(100, 20))
        }
    }
}
