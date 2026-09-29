package com.zhixueyao.agent

import com.zhixueyao.llm.ChatMessage

/**
 * 上下文压缩。
 *
 * ## 为什么必须有
 *
 * 模型一次能看的内容有硬上限（上下文窗口）。[AgentRunner] 此前把**全部历史**一条不落
 * 地发给模型，于是读几个大文件、跑几轮工具之后就会出两种事：
 *  - **超出窗口** → 服务端直接 400，整轮对话失败，且失败得毫无征兆；
 *  - **即使没超**，每次请求都把几万 token 的旧内容重发一遍，又慢又贵。
 *
 * 开源 agent（Claude Code / Cline / Aider）的共同做法是：**估算占用 → 接近上限时压缩**。
 * 压缩的层次也大同小异，本实现按「代价从低到高」分三级：
 *
 *  1. **工具结果瘦身**（不需要模型参与）：把旧工具结果的长正文换成一行占位说明。
 *     工具结果通常是上下文里最占地方的部分（一次 read_file 就是几千 token），
 *     而它只在「刚拿到」的那一刻最有用，几轮之后就没必要再逐字带着。
 *  2. **旧轮次摘要**：把最近 K 轮之外的历史交给模型压成一份摘要，整段替换。
 *     信息损失最小，代价是要多花一次模型调用。
 *  3. **硬裁剪**：从最旧的一轮开始整轮丢弃，保证一定落到窗口以内。
 *
 * ## 不变式（改这个文件前务必先读）
 *
 * 压缩**绝不能**拆散这两组配对，否则请求会被服务端直接拒绝：
 *  - assistant 的 `toolCalls` ↔ 紧随其后的 tool 结果。
 *    OpenAI 靠 `tool_call_id` 配对，缺一条就报「tool 消息没有对应的调用」；
 *    Anthropic 更严，会校验 `tool_use` 与 `tool_result` 数量是否一致。
 *  - 因此本文件里**所有切割都发生在「用户消息」这个边界上**：
 *    一个「轮次」（[Turn]）就是从一条用户消息起、到下一条用户消息之前的全部消息。
 *    整轮取舍，配对自然不会断。
 *
 * 除结构完整性外还有一条原则：**最近的内容一律不动**。最新一轮用户诉求和最近几条
 * 工具结果是模型眼下最需要的东西，压缩它们等于把当前任务搞砸 —— 省下的 token
 * 远不值得。
 */
object ContextCompactor {

    /** 每条消息的固定开销：JSON 里的 role / id / 结构括号等 */
    private const val PER_MESSAGE_OVERHEAD = 4

    /**
     * 单条工具结果超过这个字符数才值得瘦身。
     *
     * 低于此值的短结果（「文件已写入」这类）留着更好 —— 替换成占位说明反而
     * 可能更长，白折腾。
     */
    private const val SLIM_THRESHOLD = 600

    /**
     * 尾部保留多少条工具结果原文不瘦身。
     *
     * 这些是模型「刚刚做完的事」，下一步推理几乎一定会引用到，
     * 换成占位说明会直接导致它重复调用同一个工具。
     */
    const val KEEP_RECENT_TOOL_RESULTS = 4

    /**
     * 默认保留多少张「已经看过的」旧图（见 [slimOldImages]）。
     * 2 张足够覆盖「刚发的图要对照着看」这类需求，再多就是纯烧 token。
     */
    const val DEFAULT_MAX_RECENT_IMAGES = 2

    /** 图片被省略后留在正文里的占位说明 */
    private const val IMAGE_ELIDED_NOTE = "（此消息附带的图片已省略，需要再看请重新贴一次）"

    // ---------------- token 估算 ----------------

    /**
     * 估算一段文本的 token 数。
     *
     * 为什么是启发式而不是精确分词：精确分词要带一个几 MB 的词表，而本插件
     * 硬约束是**零第三方依赖**（插件类加载器与 IDE 共享，塞不进大依赖）。
     * 各家分词器也不一样，同一个词表对不同服务商同样不准。
     *
     * 启发式的取值按「宁可高估」来定：
     *  - 中日韩字符按 **1 字 ≈ 1 token** 计（主流分词器多在 0.6~1.2 之间）；
     *  - 其余字符按 **4 字符 ≈ 1 token** 计（代码与英文的经验值）。
     *
     * 高估的后果只是压缩得略早一点，低估的后果是请求被服务端打回 —— 两者不对称，
     * 所以往安全的一侧偏。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0L
        var other = 0L
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjk(cp)) cjk++ else other++
            i += Character.charCount(cp)
        }
        val total = cjk + (other + 3) / 4
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x2E80..0x2EFF,      // 部首扩展
        in 0x3000..0x303F,      // 中文标点
        in 0x3040..0x30FF,      // 日文假名
        in 0x3400..0x4DBF,      // 汉字扩展 A
        in 0x4E00..0x9FFF,      // 汉字基本区
        in 0xAC00..0xD7AF,      // 谚文
        in 0xF900..0xFAFF,      // 兼容汉字
        in 0xFE30..0xFE4F,      // 兼容标点
        in 0xFF00..0xFFEF,      // 全角字符
        in 0x20000..0x2FA1F     // 汉字扩展 B 及以上
        -> true
        else -> false
    }

    /**
     * 单条消息的 token 估算。
     *
     * 刻意**不计** [ChatMessage.reasoning]：思维链只用于界面展示，不会回传给模型
     * （见 [com.zhixueyao.llm.LlmProvider] 的消息转换），计进来会虚高。
     */
    fun messageTokens(m: ChatMessage): Int {
        var t = PER_MESSAGE_OVERHEAD
        t += estimateTokens(m.content)
        t += estimateTokens(m.toolName)
        t += estimateTokens(m.toolCallId)
        for (c in m.toolCalls) {
            t += estimateTokens(c.name) + estimateTokens(c.arguments) + 8
        }
        for (img in m.images) t += imageTokens(img.base64)
        return t
    }

    /**
     * 图片的 token 估算。
     *
     * 图片按**分辨率**计价而不是按字节数，但我们拿不到原始尺寸（只有 base64）。
     * 所以用一个偏保守的固定下限 + 随体积缓增的估算：一张常规截图在任何家
     * 大约都在 1000~1600 token 这个量级。
     */
    private fun imageTokens(base64: String): Int =
        if (base64.isEmpty()) 0 else maxOf(1100, base64.length / 800)

    fun estimateTokens(messages: List<ChatMessage>): Int {
        var sum = 0L
        for (m in messages) sum += messageTokens(m)
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ---------------- 占用测量 ----------------

    /**
     * 一次实测锚点。
     *
     * 服务商在响应里回报的 `prompt_tokens` 是**精确值**，比启发式估算可信得多。
     * 但它描述的是「上一次请求发出去的那批消息」，之后新追加的消息它并不知道，
     * 所以还要记下当时的消息条数，用「精确基线 + 新增部分的估算」算出当前占用。
     */
    data class Baseline(val tokens: Int, val messageCount: Int)

    /**
     * 计算当前历史的实际占用。
     *
     * 有实测锚点时用「锚点 + 增量估算」，否则整体估算。
     */
    fun sizeOf(messages: List<ChatMessage>, baseline: Baseline? = null): Int {
        if (baseline == null || baseline.messageCount > messages.size) {
            return estimateTokens(messages)
        }
        val tail = messages.subList(baseline.messageCount, messages.size)
        val sum = baseline.tokens.toLong() + estimateTokens(tail)
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ---------------- 轮次切分 ----------------

    /** 一个轮次：从一条用户消息开始，到下一条用户消息之前的全部消息 */
    data class Turn(val messages: List<ChatMessage>)

    /**
     * 按「用户消息」为界切分轮次。系统提示不参与（由调用方单独保管）。
     *
     * 开头若出现不属于任何用户轮次的消息（理论上不该有，但历史可能被手工改坏），
     * 它们会自成一个首轮 —— 这样后续整轮丢弃时同样能保证配对完整。
     */
    fun splitTurns(messages: List<ChatMessage>): List<Turn> {
        val out = mutableListOf<Turn>()
        var cur = mutableListOf<ChatMessage>()
        for (m in messages) {
            if (m.role == ChatMessage.Role.SYSTEM) continue
            if (m.role == ChatMessage.Role.USER && cur.isNotEmpty()) {
                out.add(Turn(cur.toList()))
                cur = mutableListOf()
            }
            cur.add(m)
        }
        if (cur.isNotEmpty()) out.add(Turn(cur.toList()))
        return out
    }

    /**
     * 每一轮起始消息在 [messages] 里的下标（系统消息占的位置会让下标跳号）。
     *
     * ## 摘要消息**不是一轮**，必须排除
     *
     * 这里踩过一个很隐蔽的坑：摘要本身是一条 `role = USER` 的消息
     * （见 [summaryMessage]），所以按「USER 就是一轮的开始」去算，
     * **摘要会被当成第 1 轮**。
     *
     * 后果在 `compact` 的第 3 级（整轮丢弃）里爆发 —— 那一级是「从最旧开始丢」，
     * 于是**第一个被丢掉的就是摘要本身**。
     *
     * 而摘要恰恰是**最不该丢的那一条**：
     *
     * - 它是整段历史的**唯一**记录（原文已经被它替换掉了）
     * - 体积最小（几百 token）、信息密度最高
     *
     * 丢掉它 = 一句话把之前所有对话清空，却只省下几百 token；
     * 而它为了省这点预算，还会把后面几轮也一起搭进去。
     * **方向完全反了。**
     *
     * 排除掉之后，`starts[0]` 指向第一轮**真实对话**，第 3 级丢的就是它该丢的东西。
     */
    private fun turnStartIndices(messages: List<ChatMessage>): List<Int> {
        val out = mutableListOf<Int>()
        var seenContent = false
        for (i in messages.indices) {
            val m = messages[i]
            if (m.role == ChatMessage.Role.SYSTEM) continue
            // 摘要不是对话轮次，是**背景元数据** —— 不参与「丢弃」的候选
            if (isSummaryMessage(m)) continue
            if (m.role == ChatMessage.Role.USER || !seenContent) out.add(i)
            seenContent = true
        }
        return out
    }

    // ---------------- 增量摘要 ----------------

    /**
     * 增量摘要要送哪一段。
     *
     * 已摘要过的部分**不再重发原文**（只把旧摘要带上当上下文），只发新增的那一段 ——
     * 这是「增量式摘要」的核心，也是最容易算错的地方（要靠对象身份把
     * 「压缩器给我的旧消息」映射回「完整历史里的下标」），所以单独抽成纯函数，
     * 便于离线验证。
     *
     * @param fullHistory 完整历史（只会追加，不会被改写）
     * @param oldMessages 本次要压缩的那批旧消息（[compact] 回调里传进来的）
     * @param covered 上次摘要已经覆盖到 [fullHistory] 的前多少条
     * @return 要送的区间 `[first, last]`；返回 null 表示算不出来，调用方应退回整段重摘
     */
    fun incrementalRange(
        fullHistory: List<ChatMessage>,
        oldMessages: List<ChatMessage>,
        covered: Int
    ): IntRange? {
        if (oldMessages.isEmpty()) return null
        // 按**对象身份**找位置：它们本来就是同一批对象，比按内容比对可靠
        // （同样的内容可能出现多次，按内容找会指错地方）。
        val last = oldMessages.last()
        val end = fullHistory.indexOfLast { it === last }
        if (end < 0) return null
        // covered 落在 (0, end) 之间才算「有增量可摘」；否则（没摘过 / 越界）整段重摘
        val from = if (covered in 1..end) covered else 0
        return from..end
    }

    // ---------------- 摘要 ----------------

    /** 摘要器。由调用方注入真实的模型调用；失败返回 null 即降级到别的压缩级别。 */
    fun interface Summarizer {
        fun summarize(messages: List<ChatMessage>): String?
    }

    /**
     * 构造摘要请求。
     *
     * 送给摘要模型的不是原始 JSON 而是「角色标记 + 正文」的可读文本 —— 同样内容
     * 少掉一大半结构开销，摘要质量也更稳。
     */
    fun buildSummaryRequest(history: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> =
        listOf(
            ChatMessage.system(SUMMARY_INSTRUCTION),
            ChatMessage.user(transcriptForSummary(history, budgetTokens))
        )

    /**
     * 把历史渲染成摘要用的文本。
     *
     * 超预算时**首尾都留**：开头是用户最初的目标与约束（丢了摘要就没了魂），
     * 结尾是最近发生的事（与后续对话衔接最紧）。中间省略并注明。
     * 只留结尾会丢掉原始诉求，只留开头则摘不出当前进度，两头都要。
     */
    private fun transcriptForSummary(messages: List<ChatMessage>, budgetTokens: Int): String {
        val rendered = messages.map { renderForSummary(it) }
        val total = rendered.sumOf { estimateTokens(it) }
        if (total <= budgetTokens) return rendered.joinToString("\n\n")

        val headBudget = (budgetTokens * 3) / 10
        val tailBudget = budgetTokens - headBudget

        val head = StringBuilder()
        var hi = 0
        var used = 0
        while (hi < rendered.size && used + estimateTokens(rendered[hi]) <= headBudget) {
            used += estimateTokens(rendered[hi])
            head.append(rendered[hi]).append("\n\n")
            hi++
        }

        val tail = StringBuilder()
        var ti = rendered.size - 1
        var usedTail = 0
        while (ti > hi && usedTail + estimateTokens(rendered[ti]) <= tailBudget) {
            usedTail += estimateTokens(rendered[ti])
            tail.insert(0, rendered[ti] + "\n\n")
            ti--
        }

        val omitted = ti - hi + 1
        return head.toString() +
            "……（此处省略 $omitted 条中间消息，内容过长）……\n\n" +
            tail.toString()
    }

    private fun renderForSummary(m: ChatMessage): String = when (m.role) {
        ChatMessage.Role.USER -> "【用户】${trim(m.content)}"
        ChatMessage.Role.ASSISTANT -> buildString {
            if (m.content.isNotBlank()) append("【助手】${trim(m.content)}")
            if (m.toolCalls.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("【助手调用工具】")
                append(m.toolCalls.joinToString("、") { "${it.name}(${trim(it.arguments, 200)})" })
            }
        }
        ChatMessage.Role.TOOL -> "【工具 ${m.toolName} 的结果】${trim(m.content, 400)}"
        ChatMessage.Role.SYSTEM -> ""
    }

    private fun trim(text: String, limit: Int = 1200): String =
        if (text.length <= limit) text else text.take(limit) + "…"

    // ---------------- 摘要消息 ----------------

    /** 摘要消息的正文前缀。刻意显眼，避免模型把它当成用户的新指令。 */
    private const val SUMMARY_HEADER = "【历史对话摘要】"

    fun isSummaryMessage(m: ChatMessage): Boolean =
        m.role == ChatMessage.Role.USER && m.content.startsWith(SUMMARY_HEADER)

    private fun summaryMessage(text: String) = ChatMessage.user(
        buildString {
            append(SUMMARY_HEADER)
            append("以下内容因上下文长度限制，由早前的对话压缩而成。")
            append("它记录的是**已经发生过的**事情，不是新的要求，请把它当作背景，")
            append("在用户接下来的请求上继续工作。\n\n")
            append(text.trim())
        }
    )

    // ---------------- 压缩结果 ----------------

    data class Report(
        val beforeTokens: Int,
        val afterTokens: Int,
        /** 被摘要替换掉的轮次数 */
        val summarizedTurns: Int = 0,
        /** 被整轮丢弃的轮次数 */
        val droppedTurns: Int = 0,
        /** 被瘦身的工具结果条数 */
        val slimmedToolResults: Int = 0,
        /** 被移除的图片张数 */
        val droppedImages: Int = 0,
        val usedSummary: Boolean = false
    ) {
        val savedTokens: Int get() = (beforeTokens - afterTokens).coerceAtLeast(0)
        /** 什么都没做（本来就没超预算） */
        val isNoop: Boolean
            get() = summarizedTurns == 0 && droppedTurns == 0 &&
                slimmedToolResults == 0 && droppedImages == 0
    }

    data class Result(val messages: List<ChatMessage>, val report: Report)

    // ---------------- 每轮都做的低成本瘦身 ----------------

    /**
     * **每轮请求前**都做的一遍工具结果瘦身（不管有没有超预算）。
     *
     * 为什么不能只在「超预算」时才做：工具结果**会一直挂在历史里、每轮重发** ——
     * 后台跑十几个工具的会话，光是这些结果就能到几万 token，
     * 用户看到「问个简单问题怎么有 44k 输入」就是这个（实测过）。
     * 等超预算再压已经晚了：那之前每一轮都在白花这份钱。
     *
     * 只动**旧的**大型工具结果（保留最近 [KEEP_RECENT_TOOL_RESULTS] 条 + 最近一轮），
     * 换成一行「已省略，原长 N 字符」——模型想重新看可以再调一次工具，
     * 而绝大多数情况下它早就不需要那份原文了。
     *
     * @return 瘦身后的列表；没动过就返回原列表（调用方用 `!==` 判断）
     */
    fun slimOldToolResults(
        messages: List<ChatMessage>,
        keepRecentTurns: Int = 1
    ): List<ChatMessage> {
        val indices = messages.indices.filter { messages[it].role == ChatMessage.Role.TOOL }
        if (indices.isEmpty()) return messages

        // 保护：最近几条工具结果 + 最近 keepRecentTurns 轮里的全部消息
        val protectedTools = indices.takeLast(KEEP_RECENT_TOOL_RESULTS).toSet()
        val starts = turnStartIndices(messages)
        val recentFrom = if (starts.size > keepRecentTurns) starts[starts.size - keepRecentTurns] else 0

        val candidates = indices
            .filter { it !in protectedTools && it < recentFrom }
            .filter { estimateTokens(messages[it].content) > SLIM_THRESHOLD }
            .sortedByDescending { estimateTokens(messages[it].content) }
        if (candidates.isEmpty()) return messages

        val out = messages.toMutableList()
        var changed = false
        for (i in candidates) {
            val old = out[i]
            val tokens = estimateTokens(old.content)
            out[i] = ChatMessage(
                role = old.role,
                content = "[工具 ${old.toolName} 的结果已省略（约 $tokens tokens）。" +
                    "需要再看就重新调用一次该工具。]",
                toolCallId = old.toolCallId,
                toolName = old.toolName
            )
            changed = true
        }
        return if (changed) out else messages
    }

    /**
     * **每轮请求前**对历史里的图片做的保留策略（纯函数，不改原始历史）。
     *
     * 借鉴自参考项目 astravia 的 image budget，规则一句话：
     * **「看过的」旧图只留最新 [maxRecentImages] 张，「没看过的」无条件保留。**
     *
     * 为什么要这条：
     *  - 图片 base64 **每轮都会重发**，一张截图轻松上万 token。贴过几张图之后，
     *    哪怕后面聊的全是文字，那份图也一直在烧钱（用户会看到「简单问题怎么 4 万输入」）。
     *  - 但**不能一刀切删**：模型还没「看过」的图一个都不能少，
     *    否则它会对本该现在看的图收到占位符 —— 那就是「读了等于没读」的幻读。
     *
     * 「看过没看过」有个**确定性、无状态**的判定：
     * 一张图若位于**最后一条 assistant 消息之后**，说明这次调用是模型第一次看它 → 未看过。
     * 一旦其后出现了 assistant 消息，模型已经处理过这一批 → 转成「看过」，才进入预算候选。
     *
     * @return 处理后的列表；没动过就返回原列表（调用方用 `!==` 判断）
     */
    fun slimOldImages(
        messages: List<ChatMessage>,
        maxRecentImages: Int = DEFAULT_MAX_RECENT_IMAGES
    ): List<ChatMessage> {
        if (messages.none { it.images.isNotEmpty() }) return messages
        if (maxRecentImages <= 0) return messages   // 配置成 0 = 全部保留（用户自己选）

        // 最后一条 assistant 之后的就是「还没看过」的
        val lastAssistant = messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        val seenUpTo = if (lastAssistant < 0) -1 else lastAssistant

        // 列出「看过的」每一张图（消息下标, 图下标），按时间从早到晚。
        // 必须精确到「张」而不是「消息」——一条消息可以带多张图，
        // 按消息粒度去留会连带多留几张（第一版就是这么写错的）。
        val seen = mutableListOf<Pair<Int, Int>>()
        for (i in 0..seenUpTo) {
            repeat(messages[i].images.size) { k -> seen.add(i to k) }
        }
        if (seen.size <= maxRecentImages) return messages
        // 只留最新 N 张
        val keep = seen.takeLast(maxRecentImages).toSet()

        val out = messages.toMutableList()
        var dropped = 0
        for (i in 0..seenUpTo) {
            val m = out[i]
            if (m.images.isEmpty()) continue
            val kept = m.images.filterIndexed { k, _ -> (i to k) in keep }
            if (kept.size == m.images.size) continue
            dropped += m.images.size - kept.size
            out[i] = m.copy(
                content = if (m.content.isBlank()) IMAGE_ELIDED_NOTE
                else m.content + "\n" + IMAGE_ELIDED_NOTE,
                images = kept
            )
        }
        if (dropped == 0) return messages
        AgentLog.record(
            kind = AgentLog.Kind.COMPACT,
            title = "省略 $dropped 张旧图片",
            detail = "只保留最近 $maxRecentImages 张已看过的图；未看过的图一律保留"
        )
        return out
    }

    // ---------------- 压缩主流程 ----------------

    /**
     * 压缩历史，使其落进 [budgetTokens] 以内。
     *
     * @param messages 完整历史（含系统提示）
     * @param budgetTokens 输入部分可用预算
     * @param keepRecentTurns 最近多少轮**完全不动**（不摘要、不瘦身）
     * @param summarizer 摘要器；传 null 表示跳过第 2 级（例如用户关掉了自动摘要）
     */
    fun compact(
        messages: List<ChatMessage>,
        budgetTokens: Int,
        keepRecentTurns: Int,
        summarizer: Summarizer?
    ): Result {
        val before = sizeOf(messages)
        if (before <= budgetTokens) {
            return Result(messages, Report(before, before))
        }

        val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }
        val turns = splitTurns(messages)
        val keep = keepRecentTurns.coerceAtLeast(1)

        var summarizedTurns = 0
        var usedSummary = false

        // ---- 第 2 级：旧轮次摘要 ----
        // 放在最前面做，因为它的信息保真度最高：整段历史压成要点，
        // 比逐条删工具结果更能留住「当初为什么这么改」。
        var working: MutableList<ChatMessage> =
            (system + turns.flatMap { it.messages }).toMutableList()

        if (summarizer != null && turns.size > keep) {
            val old = turns.subList(0, turns.size - keep)
            val recent = turns.subList(turns.size - keep, turns.size)
            val oldFlat = old.flatMap { it.messages }
            val summary = runCatching { summarizer.summarize(oldFlat) }.getOrNull()
            if (!summary.isNullOrBlank()) {
                working = (system + summaryMessage(summary) +
                    recent.flatMap { it.messages }).toMutableList()
                summarizedTurns = old.size
                usedSummary = true
            }
        }

        // ---- 第 1 级：工具结果瘦身 ----
        var slimmed = 0
        var droppedImages = 0
        var current = sizeOf(working)
        if (current > budgetTokens) {
            val pass = slimPass(working, budgetTokens, current, keep)
            working = pass.messages.toMutableList()
            slimmed = pass.slimmed
            droppedImages = pass.droppedImages
            current = pass.resultingTokens
        }

        // ---- 第 3 级：整轮丢弃 ----
        var dropped = 0
        while (current > budgetTokens) {
            val starts = turnStartIndices(working)
            // 至少保留一轮：把最后一轮丢掉等于这次对话没法继续了
            if (starts.size <= 1) break
            val from = starts[0]
            val to = starts[1]
            for (i in from until to) current -= messageTokens(working[i])
            working.subList(from, to).clear()
            dropped++
        }

        // 收尾：确保开头是系统提示 + 一条用户消息（Anthropic 对交替结构敏感）
        working = sanitizeLeading(working).toMutableList()

        val after = sizeOf(working)
        return Result(
            messages = working,
            report = Report(
                beforeTokens = before,
                afterTokens = after,
                summarizedTurns = summarizedTurns,
                droppedTurns = dropped,
                slimmedToolResults = slimmed,
                droppedImages = droppedImages,
                usedSummary = usedSummary
            )
        )
    }

    /**
     * 工具结果瘦身 + 旧图移除。
     *
     * 两个取舍：
     *  - 按**体积从大到小**处理，而不是从旧到新。省同样的 token，动大块头能少动
     *    好几条消息，留下的信息更完整。
     *  - 最近 [KEEP_RECENT_TOOL_RESULTS] 条工具结果与最近 [keepTurns] 轮内的图片
     *    一概不动 —— 那是模型正在用的东西。
     */
    private fun slimPass(
        messages: List<ChatMessage>,
        budget: Int,
        startTokens: Int,
        keepTurns: Int
    ): SlimPass {
        val out = messages.toMutableList()
        var current = startTokens

        // 受保护的工具结果：全列表最后 N 条
        val toolIdx = out.indices.filter { out[it].role == ChatMessage.Role.TOOL }
        val protectedTools = toolIdx.takeLast(KEEP_RECENT_TOOL_RESULTS).toSet()

        // 受保护的图片：最近 keepTurns 轮里的
        val starts = turnStartIndices(out)
        val protectedFrom = if (starts.size > keepTurns) starts[starts.size - keepTurns] else 0

        // 候选：旧工具结果
        val candidates = toolIdx
            .filter { it !in protectedTools }
            .filter { out[it].content.length > SLIM_THRESHOLD }
            .sortedByDescending { estimateTokens(out[it].content) }

        var slimmed = 0
        var droppedImages = 0

        for (i in candidates) {
            if (current <= budget) break
            val old = out[i]
            val replacement = ChatMessage(
                role = old.role,
                content = "[工具 ${old.toolName} 的结果已在上下文压缩中省略，原长 ${old.content.length} 字符]",
                toolCallId = old.toolCallId,
                toolName = old.toolName
            )
            current -= messageTokens(old) - messageTokens(replacement)
            out[i] = replacement
            slimmed++
        }

        // 还超的话，再移除旧消息里的图片（base64 是体积大户）
        if (current > budget) {
            for (i in out.indices) {
                if (current <= budget) break
                if (i >= protectedFrom) continue
                val m = out[i]
                if (m.role != ChatMessage.Role.USER || m.images.isEmpty()) continue
                val removed = messageTokens(m)
                val replacement = m.copy(
                    images = emptyList(),
                    content = m.content + "\n\n（此处原有的 ${m.images.size} 张图片已在上下文压缩中移除）"
                )
                current -= removed - messageTokens(replacement)
                out[i] = replacement
                droppedImages += m.images.size
            }
        }

        return SlimPass(out, slimmed, droppedImages, current)
    }

    private data class SlimPass(
        val messages: List<ChatMessage>,
        val slimmed: Int,
        val droppedImages: Int,
        val resultingTokens: Int
    )

    /**
     * 去掉「第一条用户消息之前」的孤立消息。
     *
     * 正常历史不会出现这种结构，但整轮丢弃之后可能出现（例如原本开头就是
     * 一段没有用户提问的助手消息）。Anthropic 对消息交替结构敏感，
     * 留着头部的孤立助手 / 工具消息有被拒的风险，直接摘掉最稳。
     */
    private fun sanitizeLeading(messages: List<ChatMessage>): List<ChatMessage> {
        var firstUser = -1
        for (i in messages.indices) {
            if (messages[i].role == ChatMessage.Role.USER) {
                firstUser = i
                break
            }
        }
        if (firstUser < 0) return messages
        val head = messages.subList(0, firstUser)
        // 头部本来就只剩系统提示的话，不动
        if (head.all { it.role == ChatMessage.Role.SYSTEM }) return messages
        return messages.filterIndexed { i, m ->
            i >= firstUser || m.role == ChatMessage.Role.SYSTEM
        }
    }

    // ---------------- 摘要提示词 ----------------

    private val SUMMARY_INSTRUCTION = """
        你是一个对话压缩器。请把下面这段「编程助手与用户」的对话历史压缩成一份摘要，
        供后续对话继续使用 —— 读过这份摘要后，助手应当能像没丢过上下文一样接着干活。

        必须保留：
        1. 用户的目标，以及所有明确提出的要求、约束、偏好
        2. 已经完成的工作：改了哪些文件、做了什么改动、结果如何
        3. 关键技术决策和当时的理由
        4. 遇到过的报错，以及是否已解决
        5. 当前进度，以及下一步待办
        6. 尚未解决、悬而未决的问题

        要求：
        - 用中文写，技术名词保留英文原文（类名、方法名、路径、命令）
        - 条目式罗列，不要客套话、不要复述原文
        - 文件路径、类名、方法名**一个都不要省**，那是最容易被用到的信息
        - 已经失效的中间过程可以合并，不要逐条流水账
        - 全文控制在 800 字以内
    """.trimIndent()
}

/**
 * 上下文窗口推断。
 *
 * 模型一次能吃多少，各家差别极大（32K 到 1M），而我们必须在发请求**之前**知道这个数
 * 才能决定压不压缩。来源有三个，优先级从高到低：
 *
 *  1. 用户在设置里手填的数值（最准，因为只有他自己知道接的是哪个模型）
 *  2. 按模型名推断（见 [infer]）
 *  3. 兜底默认值
 *
 * 取值刻意**偏保守**：真窗口 128K 的模型这里可能按 100K 算。原因是两种错法的代价
 * 不对称 —— 估大了会直接撞服务端的 400 报错、整轮对话作废；估小了只是压缩得早一点，
 * 对话照常进行。宁可多压一次，不可失败一次。
 */
object ModelWindows {

    /** 无法识别模型时的兜底窗口 */
    const val FALLBACK = 64_000

    /** 设置里填 0 表示「自动判断」 */
    const val AUTO = 0

    /**
     * 按模型名推断上下文窗口。
     *
     * 用 `contains` 而不是精确匹配：同一个模型在不同服务商那里的写法五花八门
     * （`deepseek-chat` / `deepseek-ai/DeepSeek-V3` / `deepseek_v3`），
     * 关键词匹配才能都罩住。
     */
    fun infer(model: String): Int {
        val m = model.lowercase()
        if (m.isBlank()) return FALLBACK

        // 1M 级别的
        if ("gemini" in m) return 500_000

        // 200K 级别的
        if ("claude" in m) return 150_000

        // 128K 级别的
        if (m.startsWith("gpt") || m.startsWith("o1") || m.startsWith("o3") ||
            m.startsWith("o4") || "chatgpt" in m
        ) return 100_000
        if ("qwen" in m || "通义" in m || "tongyi" in m) return 100_000
        if ("glm" in m || "chatglm" in m || "智谱" in m) return 100_000
        if ("kimi" in m || "moonshot" in m) return 100_000
        if ("grok" in m) return 100_000
        if ("minimax" in m) return 100_000
        if ("ernie" in m || "文心" in m) return 100_000

        // 64K 级别的
        if ("deepseek" in m) return 48_000

        // 32K 级别的
        if ("llama" in m || "mistral" in m || "mixtral" in m) return 32_000

        return FALLBACK
    }

    /** 综合设置与模型名得出实际生效的窗口大小 */
    fun resolve(configured: Int, model: String): Int =
        if (configured > 0) configured else infer(model)
}
