package com.zhixueyao.ui

import com.zhixueyao.llm.ChatMessage

/**
 * 把「发给模型的历史」整理成「界面上该显示的一轮对话」。
 *
 * ## 为什么需要它
 *
 * 这两件事的粒度**天生不一样**：
 *
 * - **历史**是按 API 协议组织的：模型每「说一次话」就有一条 assistant 记录。
 *   一次带工具调用的回答因此是：
 *   `USER → ASSISTANT(先说了段计划, toolCalls=[...]) → TOOL(结果) → ASSISTANT(最终结果)`
 *   —— **两条 assistant** 是协议要求的，不能合并。
 * - **界面**上这**是一轮**对话：用户问了一句，AI 答了一段（中间调了工具）。
 *
 * 直接按 assistant 条数渲染，就会出现「我只发了一句话，却出来好几个回答框」
 * （用户反馈）—— 而且这几个框的内容还是**半截的**（第一个只有计划、第二个只有结论）。
 *
 * 更糟的是：**流式过程中界面是对的**（气泡只有一个，文本累积），
 * 只有重载会话 / 编辑重发之后才变成多个 —— 因为那两条路径会按历史重建界面。
 * 也就是说同一轮对话，「刚才看到的」和「重新打开看到的」长得不一样。
 *
 * 所以这里把历史**按「一轮对话」重新分组**，让重建出来的界面和流式时一致。
 *
 * ## 分组规则
 *
 * 以 USER 消息为界：**一条 USER 到（不含）下一条 USER 之间的所有内容属于同一轮**。
 * 该轮内多条 assistant 的正文按顺序拼接（与流式时的 `finalText.append(...)` 同口径），
 * TOOL 与 SYSTEM 不显示。
 *
 * 抽成纯函数是为了能离线把边界情况跑一遍（见 `HistoryGroupingProbe`）。
 */
object HistoryGrouping {

    /**
     * 界面上要显示的一轮。
     *
     * @param userMessage 该轮的用户消息；null = 开场（还没有用户说话，例如欢迎语后的开场白）
     * @param assistantText 该轮累积的助手正文（多条 assistant 直接拼接）
     * @param lastAssistant 该轮**最后一条** assistant 消息 ——
     *        界面的 `linkedMessage` 用它（[ChatPanel.switchVariant]、重新生成都按它定位）
     */
    data class Turn(
        val userMessage: ChatMessage?,
        val assistantText: String,
        val lastAssistant: ChatMessage?
    ) {
        /** 这一轮有没有助手内容可显示（只有工具调用、没出文字的轮次不建气泡） */
        val hasAssistantText: Boolean get() = assistantText.isNotBlank()

        /** 该轮助手内容的用量（取自最后一条 assistant，那里才是完整的一轮） */
        val promptTokens: Int get() = lastAssistant?.promptTokens ?: 0
        val completionTokens: Int get() = lastAssistant?.completionTokens ?: 0

        /** 该轮助手的思维链（各条拼起来，界面上可折叠展示） */
        val reasoning: String
            get() = lastAssistant?.reasoning.orEmpty()
    }

    /**
     * 分组。
     *
     * 注意**不会丢掉任何一轮**：即使某轮的用户消息后面还没有助手回答
     * （比如刚发出去就被中断），也会产出一个 `assistantText` 为空的 Turn ——
     * 调用方据此只渲染用户气泡，不会让用户的问题凭空消失。
     */
    fun group(history: List<ChatMessage>): List<Turn> {
        val turns = mutableListOf<Turn>()
        var currentUser: ChatMessage? = null
        val text = StringBuilder()
        var lastAssistant: ChatMessage? = null
        var started = false

        fun flush() {
            if (!started) return
            turns.add(Turn(currentUser, text.toString(), lastAssistant))
            currentUser = null
            text.setLength(0)
            lastAssistant = null
            started = false
        }

        for (msg in history) {
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> Unit   // 系统提示不显示

                ChatMessage.Role.USER -> {
                    flush()                      // 上一条用户消息之后的算作上一轮
                    currentUser = msg
                    started = true
                }

                ChatMessage.Role.ASSISTANT -> {
                    started = true                 // 开场白（还没有用户消息）也要能显示
                    // **直接拼接，不加分隔符** —— 与 AgentRunner 里
                    // `finalText.append(turnText)` 完全同口径，
                    // 这样重建出来的文本和流式时看到的一模一样。
                    text.append(msg.content)
                    lastAssistant = msg
                }

                ChatMessage.Role.TOOL -> Unit     // 工具结果不进界面
            }
        }
        flush()
        return turns
    }
}