package com.zhixueyao.ui

/**
 * 「流式期间要不要自动跟随到底部」的判定。
 *
 * 这段逻辑被坑过三次，每次都是**想用数值比较猜用户意图**：
 *
 * 1. 只按「距底部 48px 以内就跟」：滚轮一格大约 30~50px，用户往上滚一格
 *    **仍在阈值内**，下一个增量又把他拽回底部 —— 表现就是「上下滑动卡住、滑不动」。
 * 2. 改成「记住上次放的位置，比它高就停」：内容增长时 `maximum` 变大而 `value` 不变，
 *    会被误判成「用户往上滚」→ **跟随会自己断掉**（越聊越不跟了）。
 * 3. 结论：`value` / `maximum` 的变化**区分不了**「用户滚了」和「内容长了」。
 *
 * 所以现在**只信真实事件**：滚轮、拖动滚动条这类操作才算「用户滚了」
 * （见 [onUserScroll]），其余情况一律保持跟随。
 * 数值比较只用来判断「他是不是已经滚回底部了」——那是它擅长的事。
 */
object ScrollFollow {

    /**
     * @param follow 是否跟随到底部
     * @param userScrolled 用户是否主动滚动过（还没滚回底部）
     */
    data class State(
        val follow: Boolean = true,
        val userScrolled: Boolean = false
    )

    data class Decision(
        val state: State,
        /** 要滚到的位置；null = 这次不滚 */
        val scrollTo: Int?
    )

    /** 判定「已经回到最底部」的容差 */
    const val BOTTOM_TOLERANCE = 8

    /** 用户主动滚了一下（滚轮 / 拖滚动条 / 键盘）。由界面在真实事件里调用 */
    fun onUserScroll(state: State): State = state.copy(userScrolled = true)

    fun decide(state: State, value: Int, bottom: Int, visible: Int): Decision {
        var follow = state.follow
        var userScrolled = state.userScrolled

        if (userScrolled) {
            // 他动过滚动条：滚回底部就恢复跟随，否则一直尊重他的位置
            if (isAtBottom(value, bottom, visible)) {
                follow = true
                userScrolled = false
            } else {
                follow = false
            }
        }
        return Decision(
            state = State(follow = follow, userScrolled = userScrolled),
            scrollTo = if (follow) bottom else null
        )
    }

    /** 注意 Swing 语义：value 的可滚范围是 0..(maximum - visibleAmount) */
    fun isAtBottom(value: Int, bottom: Int, visible: Int): Boolean =
        value >= bottom - visible - BOTTOM_TOLERANCE
}
