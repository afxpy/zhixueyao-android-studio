package com.zhixueyao.agent

/**
 * 思考标记切分器。
 *
 * 有些模型（以及部分中转站的实现）不把思考放在独立的 `reasoning_content` 字段里，
 * 而是**内联在正文流**里：` thinking先看目录结构……用户要的是……`。
 * 这种流必须自己切，否则标记和思考内容会原样漏进回答。
 *
 * 两个必须处理的细节：
 *
 *  1. **标记会被分片切开**：网络分片是按字节来的，`<th` 和 `ink>` 完全可能落在两个
 *     不同的 SSE 帧里。所以末尾要缓冲「可能是标记前缀」的那几个字符，等下一片到了再判。
 *  2. **只有一个 `<` 也不能直接发**：万一它就是标记的开头，发出去就再也收不回来了。
 *
 * 用法：每个回合 new 一个，`feed()` 喂分片，流结束时 `finish()` 把缓冲吐干净。
 */
class ThinkMarkerSplitter(
    private val onThink: (String) -> Unit,
    private val onText: (String) -> Unit
) {

    private val buffer = StringBuilder()
    private var inThink = false

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        buffer.append(chunk)
        drain(flush = false)
    }

    /** 流结束：把缓冲里剩下的内容按当前状态发出去 */
    fun finish() {
        drain(flush = true)
    }

    private fun drain(flush: Boolean) {
        while (true) {
            val marker = if (inThink) CLOSE else OPEN
            val idx = buffer.indexOf(marker)
            if (idx >= 0) {
                emit(buffer.substring(0, idx))
                buffer.delete(0, idx + marker.length)
                inThink = !inThink
                continue
            }
            if (flush) {
                emit(buffer.toString())
                buffer.setLength(0)
                return
            }
            // 没找到标记：只把「可能还是标记前缀」的尾巴留在缓冲里，其余立刻发出去
            val keep = longestSuffixThatIsPrefixOf(marker, buffer)
            val emitLen = buffer.length - keep
            if (emitLen > 0) {
                emit(buffer.substring(0, emitLen))
                buffer.delete(0, emitLen)
            }
            return
        }
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        if (inThink) onThink(text) else onText(text)
    }

    /** 末尾有多长一段可能是 marker 的前缀（用于跨分片判断） */
    private fun longestSuffixThatIsPrefixOf(marker: String, sb: StringBuilder): Int {
        val max = minOf(marker.length - 1, sb.length)
        for (len in max downTo 1) {
            if (marker.startsWith(sb.substring(sb.length - len))) return len
        }
        return 0
    }

    companion object {
        // 标记用**字符码**拼接：源码里出现实 "<" 字面量会被写入链路当成 HTML 标签吃掉，
        // 连字符串的收尾引号一起吞（实测踩过，常量会变成半截且编译不过）
        private val LT = 60.toChar()
        private val GT = 62.toChar()
        val OPEN = "" + LT + "think" + GT
        val CLOSE = "" + LT + "/think" + GT

        /** 一次性剥离（用于最终消息：思考段整体丢掉，只留正文） */
        fun stripAll(text: String): String {
            if (!text.contains(OPEN) && !text.contains(CLOSE)) return text
            val out = StringBuilder()
            var inThink = false
            var i = 0
            while (i < text.length) {
                when {
                    !inThink && text.startsWith(OPEN, i) -> {
                        inThink = true
                        i += OPEN.length
                    }

                    inThink && text.startsWith(CLOSE, i) -> {
                        inThink = false
                        i += CLOSE.length
                    }

                    else -> {
                        if (!inThink) out.append(text[i])
                        i++
                    }
                }
            }
            return out.toString().trim()
        }

        /** 文本里是否含思考标记（决定要不要走切分器） */
        fun hasMarkers(text: String): Boolean = text.contains(OPEN) || text.contains(CLOSE)
    }
}
