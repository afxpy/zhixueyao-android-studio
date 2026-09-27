package com.zhixueyao.ui

/**
 * 两段文本的差异摘要。
 *
 * 为什么需要它：差异对话框一打开，用户看到的是「左边整个文件、右边一大段」，
 * **改动在哪一行得自己翻**（用户反馈「改动都不知道跳到指定位置，还要自己滑」）。
 * 这里算出「第几行开始改、一共动了多少行」，写到对话框顶部 ——
 * 用户一眼就知道该看哪儿。
 *
 * 做法是「掐头去尾」：去掉相同的开头和结尾，中间就是改动块。
 * 比 LCS 简单得多，但对付「模型重写了一个文件」这种场景足够 ——
 * 那类改动本来就集中在连续的一段。
 */
object DiffSummary {

    data class Result(
        /** 第一处不同在第几行（1 开始；完全相同返回 0） */
        val firstChangedLine: Int,
        /** 改动块涉及多少行（旧文本侧） */
        val removedLines: Int,
        /** 改动块涉及多少行（新文本侧） */
        val addedLines: Int,
        /** 是否完全相同 */
        val identical: Boolean
    ) {
        /** 一句话摘要，直接显示在对话框顶部 */
        fun render(): String = if (identical) {
            "内容没有变化"
        } else {
            "从第 $firstChangedLine 行开始有改动：旧 $removedLines 行 → 新 $addedLines 行"
        }
    }

    fun of(oldText: String, newText: String): Result {
        if (oldText == newText) return Result(0, 0, 0, true)
        val a = oldText.split('\n')
        val b = newText.split('\n')

        // 相同的前缀
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++

        // 相同的后缀（不能越过前缀，否则整段都是「改动」）
        var tail = 0
        while (
            tail < a.size - head && tail < b.size - head &&
            a[a.size - 1 - tail] == b[b.size - 1 - tail]
        ) {
            tail++
        }

        val removed = a.size - head - tail
        val added = b.size - head - tail
        return Result(
            firstChangedLine = head + 1,
            removedLines = removed,
            addedLines = added,
            identical = false
        )
    }

    /**
     * 这段代码看起来是不是「**整个文件**」？
     *
     * 判断这个是为了拦住一种会毁文件的误操作：模型给的代码块常常只是**片段**，
     * 而「应用改动」是整文件替换（`doc.setText(code)`）——
     * 拿一行片段去替换，整个文件就只剩那一行了。
     *
     * 判据刻意保守（宁可提示得多，不可放过）：
     *  - 片段明显短于原文件（行数不到一半）→ 不是整文件
     *  - 原文件有 package/import 这类文件级声明，而片段没有 → 不是整文件
     */
    fun looksLikeWholeFile(code: String, oldText: String): Boolean {
        val codeLines = code.count { it == '\n' } + 1
        val oldLines = oldText.count { it == '\n' } + 1
        if (oldLines <= 3) return true                 // 原文件本来就短，谈不上片段
        if (codeLines >= oldLines) return true          // 不比原来短，当它是整文件
        if (codeLines * 2 < oldLines) return false      // 不到一半 → 片段

        // 行数接近时，再看文件级声明：原文件有、片段没有 → 片段
        val fileLevel = Regex("""^\s*(package|import)\s""", RegexOption.MULTILINE)
        return !(fileLevel.containsMatchIn(oldText) && !fileLevel.containsMatchIn(code))
    }
}
