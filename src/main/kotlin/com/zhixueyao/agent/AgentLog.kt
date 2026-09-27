package com.zhixueyao.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作留痕（内存环形缓冲）。
 *
 * 为什么要有：插件是「无确认、直接改」的模式 —— 写文件、删文件、跑构建全都静默执行。
 * 出了事（改坏了、删错了、上下文被压了）必须能回看「当时到底做了什么、按什么顺序」。
 *
 * 刻意**只放内存**、只留最近 [MAX_ENTRIES] 条：
 *  - 落盘会让「无确认」变成「到处留文件」，也牵扯隐私；
 *  - 调试信息过夜就没价值，重启清空反而干净。
 *
 * 需要长期日志的场景走 IDE 自己的日志（Help → Show Log），这里是给用户看的「操作台账」。
 */
object AgentLog {

    private const val MAX_ENTRIES = 300

    enum class Kind(val label: String) {
        TOOL_OK("工具"),
        TOOL_FAIL("工具失败"),
        COMPACT("上下文压缩"),
        ERROR("错误"),
        SESSION("会话")
    }

    data class Entry(
        val at: Long,
        val kind: Kind,
        val title: String,
        val detail: String
    )

    private val entries = ArrayDeque<Entry>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 记录一条。线程安全：工具在后台线程跑，界面在读 */
    @Synchronized
    fun record(kind: Kind, title: String, detail: String = "") {
        entries.addLast(Entry(System.currentTimeMillis(), kind, title, detail))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    /** 渲染成可复制的纯文本 */
    fun asText(): String = snapshot().joinToString("\n") { e ->
        val head = "${stamp.format(Date(e.at))}  [${e.kind.label}]  ${e.title}"
        if (e.detail.isBlank()) head else "$head\n    ${e.detail.replace("\n", "\n    ")}"
    }
}
