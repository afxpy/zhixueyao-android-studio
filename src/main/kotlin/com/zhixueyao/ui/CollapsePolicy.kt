package com.zhixueyao.ui

/**
 * 长文本折叠的判定规则。
 *
 * 独立成对象而不是散在界面代码里，有两个理由：
 *
 *  1. **可测**。这段逻辑决定「用户会不会被一屏几千字冲走」，
 *     但它不依赖任何 Swing 组件 —— 放在 [MessageBubble] 里就只能靠肉眼看，
 *     抽出来才能对着边界值实测（见 `CollapsePolicyTest` 的用例：
 *     恰好等于阈值不折、有代码块不折、用户消息永不折）。
 *  2. **有一个地方能看全规则**。折叠条件有三条（角色、长度、有无代码块），
 *     之前散在方法里，改一条容易漏看另一条。
 */
object CollapsePolicy {

    /**
     * 触发折叠的字符数。
     *
     * 1200 约等于两屏正文 —— 短于它折叠反而多一次点击，长于它不折就会刷屏。
     */
    const val THRESHOLD: Int = 1200

    /** 折叠时保留的摘要长度。500 字符通常能覆盖开头那句结论 */
    const val PREVIEW: Int = 500

    /**
     * 是否启用折叠。
     *
     * 做成开关而不是写死：折叠会遮住内容，属于「有人喜欢有人烦」的功能 ——
     * 对新用户是净收益（不被长回答冲走上下文），对老用户可能是干扰。
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * 这条文本要不要折。
     *
     * 三条规则，缺一不可：
     *  - 只折助手回答（用户自己写的、系统提示不折）
     *  - 超过阈值才折
     *  - **含代码块就不折** —— 代码要看得见，且用户多半是来复制代码的
     *
     * @param isAssistant 是否助手消息
     * @param text 消息正文
     */
    fun shouldCollapse(isAssistant: Boolean, text: String): Boolean =
        enabled && isAssistant && text.length > THRESHOLD && !text.contains("```")

    /** 折叠后显示的摘要文本。 */
    fun preview(text: String): String =
        text.take(PREVIEW) + "\n\n……（已折叠，共 ${text.length} 字符）"

    /** 折叠状态下开关按钮的文案。 */
    fun expandLabel(text: String): String = "▸ 展开全文（${text.length} 字符）"

    /** 展开状态下开关按钮的文案。 */
    const val COLLAPSE_LABEL: String = "▾ 收起全文"

    /**
     * 内容预览弹窗里的行数上限（「上传内容超长要折叠」的另一处落点）。
     *
     * 比消息区的字符阈值小得多，原因不同：这里是「确认要发出去的内容」的场景，
     * 用户多半只想扫一眼开头，不需要几千行全展开占满屏幕。
     */
    const val PREVIEW_DIALOG_LINE_LIMIT: Int = 40
}
