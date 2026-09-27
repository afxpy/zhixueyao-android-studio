package com.zhixueyao.llm

/**
 * 把「content 里夹着 ` thinking…<｜end▁of▁thinking｜>`」的流切成思考 / 正文两路。
 *
 * 为什么必须带缓冲：SSE 分片是按字节切的，标记极可能被切开 ——
 * `"<th"` + `"ink>"`、`"</thi"` + `"nk>"` 都是常态。逐片做 `contains("<think")`
 * 必然漏判，于是思考内容被当成正文渲染出来（这是流式解析最经典的 bug）。
 *
 * 做法：每次只吐出「确定不是标记前缀」的部分，把可能是前缀的尾巴留在缓冲里等下一片。
 * 流结束时调 [flush] 把尾巴吐出来。
 *
 * 用法：
 * ```
 * val splitter = ThinkTagSplitter()
 * for (chunk in stream) splitter.feed(chunk).forEach { part -> ... }
 * splitter.flush().forEach { part -> ... }
 * ```
 */
class ThinkTagSplitter {

    /** 切出来的片段：[think] 为 true 表示属于思考内容 */
    data class Part(val text: String, val think: Boolean)

    private val buffer = StringBuilder()
    private var inThink = false

    companion object {
        // 标记一律用拼接构造，不写字面量 ——
        // 某些写入链路会把 "<think>" 当成 HTML 标签吃掉，常量会变成半截
        // （实测踩过：OPEN 只剩 " thinking"，于是标准标记根本识别不了）。
        private val LT = 60.toChar()
        private val GT = 62.toChar()
        private val OPEN_TAGS = listOf("" + LT + "think" + GT, "" + LT + "thinking" + GT)
        private val CLOSE_TAGS = listOf(
            "" + LT + "/think" + GT,
            "" + LT + "/thinking" + GT,
            "" + LT + "｜end▁of▁thinking｜" + GT
        )

        /** 最长的标记长度，决定要留多少尾巴 */
        private val MAX_TAG = (OPEN_TAGS + CLOSE_TAGS).maxOf { it.length }
    }

    fun feed(chunk: String): List<Part> {
        buffer.append(chunk)
        val out = mutableListOf<Part>()
        while (true) {
            val text = buffer.toString()
            val tags = if (inThink) CLOSE_TAGS else OPEN_TAGS
            val hit = tags.mapNotNull { tag ->
                val idx = text.indexOf(tag, ignoreCase = true)
                if (idx >= 0) idx to tag else null
            }.minByOrNull { it.first }

            if (hit == null) {
                // 没有完整标记：把「可能是标记前缀」的尾巴留住，其余照常吐出
                val keep = possiblePrefixLength(text, tags)
                val emit = text.substring(0, text.length - keep)
                if (emit.isNotEmpty()) out.add(Part(emit, inThink))
                buffer.clear()
                buffer.append(text.substring(text.length - keep))
                return out
            }

            val (idx, tag) = hit
            if (idx > 0) out.add(Part(text.substring(0, idx), inThink))
            buffer.clear()
            buffer.append(text.substring(idx + tag.length))
            inThink = !inThink
        }
    }

    /** 流结束：把缓冲里剩下的都吐出来（截断的标记按普通文本处理） */
    fun flush(): List<Part> {
        if (buffer.isEmpty()) return emptyList()
        val part = Part(buffer.toString(), inThink)
        buffer.clear()
        return listOf(part)
    }

    /** 结尾有多少字符可能是某个标记的前缀（不区分大小写） */
    private fun possiblePrefixLength(text: String, tags: List<String>): Int {
        val lower = text.lowercase()
        var best = 0
        for (tag in tags) {
            val max = minOf(tag.length - 1, text.length)
            for (len in max downTo 1) {
                if (lower.endsWith(tag.substring(0, len))) {
                    if (len > best) best = len
                    break
                }
            }
        }
        // 兜底：末尾若是一个孤立的 '<'，也可能是标记开头
        if (best == 0 && lower.endsWith("<")) best = 1
        return minOf(best, MAX_TAG - 1)
    }
}
