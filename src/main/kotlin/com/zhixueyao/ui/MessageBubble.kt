package com.zhixueyao.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zhixueyao.llm.ChatMessage
import com.zhixueyao.settings.ZhixueyaoSettings
import com.zhixueyao.tools.ToolResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * 单条消息气泡。
 *
 * 包含正文区、思维链折叠区、工具调用卡片区。
 * 正文中的代码块会被单独渲染为带「应用」按钮的区块。
 */
class MessageBubble(
    /** 消息角色，界面与外部（重新生成时定位上一条用户消息）都要用 */
    val messageKind: Kind,
    /** 用于代码块写入；为空时「应用」按钮会被禁用 */
    private val project: Project? = null
) : JBPanel<MessageBubble>(BorderLayout()) {

    enum class Kind { USER, ASSISTANT, SYSTEM }

    private companion object {
        /** 标记头部已挂过消息级操作，避免 finalize 多次调用时堆按钮 */
        const val ACTIONS_ADDED = "zhixueyao.actionsAdded"

        /** 标记折叠监听已挂过，同上 */
        const val COLLAPSE_READY = "zhixueyao.collapseReady"
    }

    /** 兼容旧调用：内部一律用 [messageKind] */
    private val kind: Kind get() = messageKind

    /**
     * 与这条气泡对应的对话消息（同一个实例，来自 ChatPanel 的历史）。
     *
     * 「编辑」要按它定位历史位置 —— 按文本匹配不可靠（同一句话可能发两次），
     * 按序号匹配也不可靠（重新生成会截断历史、序号会变）。持有实例引用最稳。
     */
    var linkedMessage: ChatMessage? = null

    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()

    /** 思考过程的展示方式，创建气泡时读一次（见 ZhixueyaoSettings.reasoningView） */
    private val reasoningMode = ZhixueyaoSettings.getInstance().reasoningView

    private val headerLabel = JBLabel()
    private val bodyArea = createReadOnlyArea()

    /**
     * 富文本正文：Markdown 渲染后的 HTML 走这里（纯文本仍走 [bodyArea]）。
     *
     * 为什么用两个控件而不是一个：流式过程中每个增量都重新解析整篇 Markdown 太贵，
     * 所以**流式时显示纯文本**，等这一轮结束再换成渲染好的富文本 ——
     * 用户看到的最终结果是有格式的，过程也不卡。
     */
    private val richArea = javax.swing.JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        foreground = UiKit.text
        alignmentX = LEFT_ALIGNMENT
        // 同上：流式中每次换 HTML 都不能把外层的滚动位置拽走
        disableCaretScroll(this)
        // 正文里的链接**要能点**。
        //
        // 模型经常把产物写成 markdown 链接（「[下载 abstract_4k_landscape.svg](...)」），
        // 不处理的话点了毫无反应 —— 用户以为坏了。这里分流：
        // 指向本地图片/视频的 → 开预览窗口（用户要的是「点一下就看见图」，
        // 而不是被甩到 IDE 编辑器里看 XML 原文）；其余 → 交给 IDE 打开。
        addHyperlinkListener { e ->
            if (e.eventType != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            resolveLocalFile(e)?.let { openPreview(it, it.absolutePath) }
        }
    }

    /**
     * 把正文里的链接解析成本地文件；不是本地文件（http 等）就返回 null。
     *
     * markdown 链接可能写成 `file:///C:/x.svg`、`C:/x.svg`、
     * 也可能写成工程相对路径 —— 三种都要认。
     */
    private fun resolveLocalFile(e: javax.swing.event.HyperlinkEvent): java.io.File? {
        val raw = e.url?.toString()?.trim().orEmpty().ifBlank { e.description.orEmpty().trim() }
        if (raw.isBlank()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return null
        val cleaned = raw.removePrefix("file:///").removePrefix("file://").removePrefix("file:")
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
        // 绝对路径直接用；相对路径按工程根解析
        val direct = java.io.File(cleaned)
        val file = if (direct.isAbsolute) direct
        else java.io.File(project?.basePath ?: return null, cleaned)
        return if (file.isFile) file else null
    }

    /**
     * 富文本的滚动容器。
     *
     * **必须能整体隐藏**：`JScrollPane` 的 preferredSize 至少是滚动条那一圈的尺寸，
     * 就算里面的视图不可见、内容为空，它依然会占掉 28 像素左右
     * （探针实测：隐藏内部 JEditorPane 没用，外层滚动容器照样贡献 28px）。
     * 而这 28px 空白正是「正文被挤到气泡底部、没跟头部对齐」的直接原因。
     */
    private val richScroll = object : JBScrollPane(richArea) {
        init {
            setBorder(JBUI.Borders.empty())
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBar.unitIncrement = 16
            isOpaque = false
            viewport.isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }

        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, richHeight())

        /** 同样限高：不限的话它会把气泡撑高、把正文推下去 */
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, richHeight())
    }
    /**
     * 思考容器（ThinkStreamViewer）。
     *
     * 三条硬规则，都是流式渲染的实际情况逼出来的：
     *  1. **只追加，不重建**：每个增量只 append 文本，绝不重新构造整块（重建会闪，
     *     也会打断用户正在看的滚动位置）；
     *  2. **用完即销毁**：正文一开始就把整块移除，不保留、不折叠 —— 思考是过程，不是内容；
     *  3. **内部滚动**：最高 160px，长思考不会把气泡撑爆。
     */
    private val thinkArea = createReadOnlyArea().apply {
        // 最淡的一档灰：「虚」靠的是和正文的对比度差，不是斜体或小字号
        foreground = UiKit.faint
    }

    private val thinkScroll = JBScrollPane(thinkArea).apply {
        setBorder(JBUI.Borders.empty())
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        isOpaque = false
        viewport.isOpaque = false
        preferredSize = Dimension(0, 0)
    }

    /** 思考块外观：左侧一条竖条 + 缩进，不填底色（气泡本身已是卡片，再填就成「框里套框」） */
    private val thinkPanel = object : JBPanel<JBPanel<*>>(BorderLayout()) {
        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 9, 4, 0)
            isVisible = false
            add(thinkScroll, BorderLayout.CENTER)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )
                g2.color = UiKit.faint
                g2.fillRoundRect(1, 2, 2, (height - 4).coerceAtLeast(2), 2, 2)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * 头部状态：「已完成 · 12 秒」这类过程信息。
     *
     * 对齐主流 agent（Cline 的 loading → success 指示、WorkBuddy 的「已完成 2m58s」）：
     * 用户最想知道的两件事是「它还在跑吗」和「跑了多久」。
     */
    private val headerStatus = JBLabel().apply {
        font = font.deriveFont(font.size - 1.5f)
        foreground = UiKit.faint
        isVisible = false
    }

    /** 本轮开始时间（用于「已完成 · 用时」） */
    private var turnStartedAt = 0L

    /**
     * 气泡圆角。
     *
     * 这个值不能小：圆角 8px 放在几百像素宽的块上肉眼几乎看不出圆角，看起来仍是「矩形」。
     */
    private val corner = 18

    /** 这条消息的时间（脚注右侧显示） */
    private val createdAt: String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    /** 操作行左侧：无边框小图标（复制 / 重新生成 / 编辑） */
    private val actionsLeft = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }

    /** 当前显示的版本序号（从 0 开始）与总版本数 */
    private var variantIndex = 0
    private var variantCount = 0
    private var onVariantSwitch: ((Int) -> Unit)? = null

    /**
     * 多版本回答的切换控件（‹ 2/3 ›）。
     *
     * 只在重新生成过（版本数 > 1）时出现。以前重新生成把上一版**直接丢掉** ——
     * 想「还是上一版好」的时候没得回退，只能再赌一次。
     */
    private val variantLabel = JBLabel().apply {
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.subtle
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        isVisible = false
        border = JBUI.Borders.emptyRight(4)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (variantCount <= 1) return
                // 左三分之一 = 上一版，右三分之一 = 下一版，中间不动（点歪了不该乱跳）
                val next = when {
                    e.x < width / 3 -> variantIndex - 1
                    e.x > width * 2 / 3 -> variantIndex + 1
                    else -> return
                }
                if (next in 0 until variantCount) onVariantSwitch?.invoke(next)
            }
        })
    }

    /** 脚注右侧元信息：模型名 · 时间 */
    private val actionsMeta = JBLabel().apply {
        font = font.deriveFont(font.size - 1.5f)
        foreground = UiKit.faint
    }

    /** 操作行：左图标 + 右元信息，挂在气泡**外面**的下沿 */
    private val actionsRow = object : JBPanel<JBPanel<*>>(BorderLayout(6, 0)) {
        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(4)
            add(actionsLeft, BorderLayout.WEST)
            // **默认隐藏**：一轮没跑完（或异常中断）时不会走到 attachMessageActions，
            // 而 paintComponent 是按「操作行可见就扣掉它的高度」算卡片底边的 ——
            // 留着一个空的操作行，卡片底边会凭空少掉一条，正文末尾就露在卡片外面。
            isVisible = false
        }

        /**
         * 必须限高。
         *
         * JPanel 默认 maximumSize 是 2147483647，纵向 BoxLayout 会把气泡里
         * 多出来的高度**全堆给最后这个能长大的子组件**（探针实测：580 高的容器里
         * 它独吞了 524）。后果就是正文被顶偏、下方一片空白 —— 也就是
         * 「内容没跟头部对齐、掉到下面」的另一种形态。
         */
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * 正文与操作行之间的留白。
     *
     * 必须有这么一条：圆角卡只画到操作行上方，而正文的下沿恰好等于操作行顶部 ——
     * 不留白的话正文最后一行就紧贴卡片边缘（用户反馈的「文字被挤压」）。
     * 操作行隐藏时它也得跟着隐藏，否则气泡底部白多一块。
     */
    private val actionsGap: java.awt.Component = Box.createVerticalStrut(JBUI.scale(8))

    /** 代码块容器 */
    private val codeBlocksPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
    }

    /** 工具卡片容器（默认收起，见 [toolsSummary]） */
    private val toolCardsPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
    }

    /**
     * 本轮产出的多媒体附件（图片缩略图 / 文件卡片），横向排列。
     *
     * 只在有附件时出现；附件**只带路径**，二进制内容不进对话上下文。
     */
    private val attachmentsPanel = JBPanel<JBPanel<*>>().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(6)
        isVisible = false
    }

    /** 已挂过的附件路径，避免同一张卡挂两遍（工具结果可能重复上报） */
    private val attachedPaths = mutableSetOf<String>()

    /**
     * 「还没收到第一个字」时的占位。
     *
     * 为什么需要：气泡是**按需创建**的（有内容才建），而开了思考档位之后首字可能要几十秒。
     * 这段时间界面上什么都没有，用户只能盯着状态栏那句「正在思考…」——
     * 看起来就像卡死了（用户反馈「AI 框都不实时显示」）。
     *
     * 它和「一进来就放个空白气泡」不是一回事：这里有明确文字 + **走秒的计时**，
     * 用户能确认它真的在动。
     */
    private val pendingLabel = JBLabel().apply {
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.faint
        border = JBUI.Borders.emptyBottom(3)
    }

    private var pendingTimer: javax.swing.Timer? = null
    private var pendingStartedAt = 0L

    /**
     * 头部的**实时阶段**（「· 思考中 12 秒」）。
     *
     * 为什么必须有：气泡本身在流式过程中和「已经答完」长得一模一样 ——
     * 别人扫一眼会以为做完了（用户原话：「他这个发出来的别人还以为做完了呢」）。
     * 结束时会由 [markTurnFinished] 换成「已完成 N 秒」，两者颜色也不同。
     */
    private var liveTimer: javax.swing.Timer? = null
    private var liveStartedAt = 0L

    /** 切到某个进行中的阶段（由 ChatPanel 按状态机调） */
    fun setLiveStatus(label: String) {
        if (kind != Kind.ASSISTANT) return
        if (liveStartedAt == 0L) liveStartedAt = System.currentTimeMillis()
        liveLabel = label
        tickLive()
        if (liveTimer == null) {
            liveTimer = javax.swing.Timer(1000) { tickLive() }.apply { start() }
        }
        headerStatus.foreground = UiKit.brand
        headerStatus.isVisible = true
        headerStatus.revalidate()
    }

    private var liveLabel = ""

    private fun tickLive() {
        val sec = ((System.currentTimeMillis() - liveStartedAt) / 1000).coerceAtLeast(0)
        headerStatus.text = "· " + liveLabel + " " + sec + " 秒"
    }

    private fun stopLiveStatus() {
        liveTimer?.stop()
        liveTimer = null
    }

    /**
     * 「这些文件在项目里没找到」的提示行。
     *
     * 用词刻意是「没找到」而不是「编造」—— 模型可能就是在说一个**待创建**的文件，
     * 那种情况它没做错。这里只是把「值得你自己确认一下」的路径挑出来。
     */
    private val refHint = JBLabel().apply {
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.warn
        border = JBUI.Borders.emptyTop(4)
    }

    /**
     * 工具卡片的**限高**滚动容器（展开时才显示）。
     *
     * 为什么必须限高：一次回答动辄十几次工具调用，全铺开会把正文顶到屏幕外，
     * 而且展开本身会触发一次很大的重排 —— 卡片越多越卡
     * （用户反馈「点击正在执行的 mcp 这个文字就会卡界面」）。
     * 限高之后展开的代价是常数级，列表内部自己滚。
     */
    private val toolCardsScroll = object : JBScrollPane(toolCardsPanel) {
        init {
            setBorder(JBUI.Borders.empty())
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            isOpaque = false
            viewport.isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }

        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width, minOf(d.height, JBUI.scale(TOOL_CARDS_MAX_HEIGHT)))
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** 已发生的工具调用次数（汇总行文案） */
    private var toolCallCount = 0

    /** 正在执行的工具名（为空表示当前没有在跑的工具） */
    private var runningTool: String? = null

    /** 最近一张工具卡（用于同类合并） */
    private var lastToolCard: ToolCard? = null

    /** 工具卡片最多保留多少张（Swing 无虚拟列表，用上限兜住组件数） */
    private val MAX_TOOL_CARDS = 40

    /**
     * 展开工具卡片时的最大高度（逻辑像素，会再乘缩放）。
     *
     * 限高是为了两件事：**别把正文顶出屏幕**，以及**别让展开变成一次大重排**
     * （卡片越多越卡，用户反馈「点击就会卡界面」）。超出部分在容器内部滚动。
     */
    private val TOOL_CARDS_MAX_HEIGHT = 320

    /** 附件缩略图的尺寸 */
    private val THUMB_W = 132
    private val THUMB_H = 92

    /** 能直接出缩略图的扩展名（JDK 自带 ImageIO 支持的那些） */
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    /** 工具卡片按**调用 id** 索引 —— 按工具名索引会在同名连续调用时互相覆盖 */
    private val activeToolCards = mutableMapOf<String, ToolCard>()

    /** 「▸ 工具调用 N 次」汇总行：点一下展开/收起逐条卡片 */
    private val toolsSummary = UiKit.linkLabel("") { _ ->
        toolCardsScroll.isVisible = !toolCardsScroll.isVisible
        updateToolsSummary()
        revalidate()
        repaint()
    }.apply {
        isVisible = false
        // 「虚字」：工具调用的状态用最淡的一档，和正文的实字拉开对比
        foreground = UiKit.faint
    }

    /** 流式期间是否已经切到富文本渲染 */
    private var streamRich = false

    /** 上次流式富文本重排的时间戳（节流用） */
    private var lastRichRenderAt = 0L

    /** 流式富文本的最短触发长度：太短就渲染，纯属浪费 */
    private val STREAM_RICH_MIN_CHARS = 160

    /** 流式富文本的重排间隔 */
    private val STREAM_RICH_INTERVAL_MS = 320L

    /** 本条消息里已经上过高亮的代码块数量（真编辑器较重，需要限量） */
    private var highlightedBlocks = 0

    /** 单条消息最多给几个代码块上语法高亮 */
    private val MAX_HIGHLIGHTED_BLOCKS = 4

    /** 长回答折叠 */
    private var collapsed = false
    private val collapseToggle = UiKit.linkLabel("") { }.apply { isVisible = false }

    /**
     * 自绘圆角实底。
     *
     * 卡片只画到操作行**上方**（操作行在气泡外面、贴着下沿，对齐 WorkBuddy）。
     *
     * 必须分两种情况：以前是无条件 `height - insets.bottom - footerHeight - 2`，
     * 有两个毛病：
     *  1. 那 `- 2` 会把正文底边切到卡片外 —— 正文的下沿恰好等于操作行顶部，
     *     再减 2 就等于「正文最后一行贴着卡片边缘」，也就是用户反馈的「文字被挤压」；
     *  2. 没有操作行时（流式中、出错没走完 finalize）footerHeight 仍是 0 却也减了
     *     insets.bottom，卡片凭空少掉一条，正文照样贴边。
     * 现在：有操作行就画到操作行顶部（正文与它之间靠 [actionsGap] 留白），
     * 没有操作行就画满整块，底部内边距由 content 自己的 insets 提供。
     */
    override fun paintComponent(g: Graphics) {
        val footer = if (actionsRow.isVisible) actionsRow.preferredSize.height + insets.bottom else 0
        val cardBottom = height - footer
        if (cardBottom > 4) {
            UiKit.paintRoundedCard(g, background, corner, width, cardBottom)
        }
        super.paintComponent(g)
    }

    /** 内容多高就多高，别被外层拉伸（拉高后会由 BoxLayout 内部再分配，导致正文错位） */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    /** 思考开始时间 / 定格后的耗时 */
    private var reasoningStartedAt = 0L
    private var reasoningSeconds = 0L

    /** 思考结束就把耗时定格：不然每次重画都重算，秒数会越点越大 */
    private fun freezeReasoningTimer() {
        if (reasoningSeconds == 0L && reasoningStartedAt > 0) {
            reasoningSeconds = ((System.currentTimeMillis() - reasoningStartedAt) / 1000).coerceAtLeast(1)
        }
    }

    /**
     * 销毁思考容器（正文开始时调用）。
     *
     * 直接 remove() 会让气泡高度瞬间塌陷、视觉上「跳一下」，所以先用 ~120ms
     * 把高度收到 0 再移除 —— 平滑过渡，也不阻塞流式渲染。
     */
    private fun destroyThinking() {
        if (!thinkPanel.isVisible) return
        val startHeight = thinkScroll.preferredSize.height.coerceAtLeast(thinkArea.preferredSize.height)

        // 高度下限：Swing 没有 CSS transition，只能靠「锁住最小高度 + 分步收缩」逼近。
        // 不锁的话，思考块消失与正文首帧之间会有一瞬间的塌陷 —— 看起来就是「跳一下」。
        val floor = preferredSize.height
        minimumSize = Dimension(0, floor)

        val steps = 8
        var step = 0
        javax.swing.Timer(15) { e ->
            step++
            val h = (startHeight * (steps - step) / steps).coerceAtLeast(0)
            thinkScroll.preferredSize = Dimension(0, h)
            thinkPanel.revalidate()
            if (step >= steps) {
                (e.source as javax.swing.Timer).stop()
                thinkPanel.isVisible = false
                thinkArea.text = ""
                thinkScroll.preferredSize = Dimension(0, 0)
                // **从容器里摘掉**，不只是隐藏：BoxLayout 仍会为不可见子组件保留位置，
                // 留着它就等于气泡里白占一块（实测探针确认过）
                (thinkPanel.parent as? java.awt.Container)?.remove(thinkPanel)
                // 高度下限一律放开 —— 之前只在「正文已到」时放开，
                // 整段回答一次到齐的情况下就永远放不开了，气泡会一直偏高一截
                minimumSize = Dimension(0, 0)
                revalidate()
                repaint()
            }
        }.start()
    }

    init {
        // 不透明背景会把圆角「填成矩形」：Swing 的 background 填充永远是矩形，
        // 只有 RoundedLineBorder 画的那一圈描边是圆的。所以改成自己画圆角实底。
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        // 一律不描边：一圈线会让气泡看起来像「框」
        border = JBUI.Borders.compound(JBUI.Borders.empty(), JBUI.Borders.empty(10, 14))
        background = when (kind) {
            Kind.USER -> UiKit.userBubble
            Kind.ASSISTANT -> UiKit.assistantBubble
            Kind.SYSTEM -> UiKit.systemBubble
        }

        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, headerLabel.font.size - 0.5f)
        headerLabel.text = when (kind) {
            Kind.USER -> "你"
            Kind.ASSISTANT -> "止血药"
            Kind.SYSTEM -> "提示"
        }
        headerLabel.foreground = when (kind) {
            Kind.USER -> UiKit.link
            Kind.ASSISTANT -> UiKit.brand
            Kind.SYSTEM -> UiKit.subtle
        }

        // 内容列。用自写的 StackLayout 而不是 BoxLayout(Y_AXIS) ——
        // 后者的横向位置是按各子组件的 alignmentX 算的（JComponent 默认 0.5 居中），
        // 混进一个没设过 alignmentX 的组件就会把其余兄弟整体推右、并压窄到约 2/3。
        // 见 StackLayout 的注释（含离线真值表实测数据）。
        val content = object : JBPanel<JBPanel<*>>() {
            init {
                layout = StackLayout()
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

            override fun addImpl(comp: java.awt.Component, constraints: Any?, index: Int) {
                super.addImpl(comp, constraints, index)
                if (comp is JComponent) comp.alignmentX = LEFT_ALIGNMENT
            }
        }

        // 角色头：图标 + 名字（主流 agent 每条回复上方都有）。高度必须覆写方法动态求值，
        // 在 init 里读 preferredSize 时子组件还没 add，拿到的是空高度。
        val headerRow = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val toRight = kind == Kind.USER
        val headerSide = JBPanel<JBPanel<*>>(
            FlowLayout(if (toRight) FlowLayout.RIGHT else FlowLayout.LEFT, 5, 0)
        ).apply { isOpaque = false }
        val headerIcon = JLabel(
            when (kind) {
                Kind.USER -> Avatars.currentUserIcon()
                Kind.ASSISTANT -> JLabel(UiKit.brandIcon).icon
                Kind.SYSTEM -> AllIcons.General.Information
            }
        )
        if (toRight) {
            // 右侧顺序反过来：名字在前、图标贴边，读作「你 ●」
            headerSide.add(headerLabel)
            headerSide.add(headerIcon)
        } else {
            headerSide.add(headerIcon)
            headerSide.add(headerLabel)
            // 助手侧再挂一个过程状态（「已完成 · 12 秒」），用户侧不需要
            headerSide.add(headerStatus)
        }
        headerRow.add(headerSide, if (toRight) BorderLayout.EAST else BorderLayout.WEST)
        content.add(headerRow)
        content.add(Box.createVerticalStrut(4))

        // 脚注右侧只放「模型 · 时间」：头像在头部，不重复（一条消息两个同款头像很怪）
        val footerRight = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(actionsMeta)
        }
        actionsRow.add(footerRight, BorderLayout.EAST)

        content.add(thinkPanel)
        content.add(bodyArea)
        // 富文本放进横向可滚的容器：Markdown 里的宽表格 / 超长无空格串
        // 在 JEditorPane 里会直接溢出被裁，包一层滚动才拿得到
        content.add(richScroll)

        content.add(toolsSummary)
        content.add(toolCardsScroll)
        content.add(codeBlocksPanel)
        // 附件预览：正文之后、引用校验之前
        content.add(attachmentsPanel)
        // 引用校验提示：夹在正文和操作行之间，平时不可见
        content.add(refHint)
        // 操作行放最后 = 气泡左下角；前面垫一条留白，正文才不会贴着卡片边缘
        content.add(actionsGap)
        content.add(actionsRow)

        add(content, BorderLayout.CENTER)
    }

    /**
     * 布局必须**一路下探**到内容列。
     *
     * BorderLayout 只会排直接子级 —— 它给 `content` 设好新宽度就结束了，
     * `content` 内部的 BoxLayout 不会自动跟着跑。而 BoxLayout 的横向位置是
     * 按「当时的容器宽度」算出来的：气泡变窄后如果只重排了外层，内容列就会
     * 停在上一次（更宽时）算出的坐标上 —— 卡片是按**当前**宽高画的，
     * 于是看起来就是「圆角框尺寸正常，里面的内容却整体挤在右半边」。
     * 实测复现：气泡从 1000 压到 852，只重排外层时 content 仍停留在 972 宽。
     */
    override fun doLayout() {
        super.doLayout()
        (getComponent(0) as? JPanel)?.doLayout()
    }

    /** 富文本区该占多高：跟随内容高度（可见时），不可见时为 0 */
    private fun richHeight(): Int =
        if (richArea.isVisible) richArea.preferredSize.height + JBUI.scale(16) else 0

    /**
     * 只读文本区：可选中复制、自动折行、跟随主题。
     *
     * **必须限制最大高度**：JTextArea 默认 maximumSize 是 32767，纵向 BoxLayout
     * 会把容器里多出来的高度全分给「能长大的」组件 —— 于是气泡被撑高，
     * 文字那一块被顶到最下面（实测踩过：单行消息的气泡高达一百多像素，文字掉到底部）。
     */
    /**
     * 让 caret **不随文档变化而移动**。
     *
     * 机制：`JTextComponent.setText(...)` 会重置 caret，caret 变化会触发
     * `scrollRectToVisible(caret)` —— 而它会**向上找可滚动的祖先**，
     * 在聊天界面里就找到了外层消息列表（`messagesScroll`）。
     * 也就是说：每来一片流式内容，都可能把用户正在看的滚动位置拽走一次。
     *
     * 这是 Java 里展示型文本控件的标准做法，代价为零、收益明确，所以直接设上。
     * **但要说清楚**：离线探针**没能验证**这条链 ——
     * `scrollRectToVisible` 只在组件 `isShowing`（真的显示在屏幕上）时才生效，
     * 而本机无图形环境（headless），`isShowing` 永远是 false。
     * 所以这是一个「消除已知隐患」的修复，不是「已复现问题的根治」——
     * 滚动跟随那边的事件驱动改造仍然要保留（见 ChatPanel.autoScroll）。
     */
    private fun disableCaretScroll(area: javax.swing.text.JTextComponent) {
        (area.caret as? javax.swing.text.DefaultCaret)?.updatePolicy =
            javax.swing.text.DefaultCaret.NEVER_UPDATE
    }

    private fun createReadOnlyArea(): JTextArea = object : JTextArea() {
        init {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            font = JBUI.Fonts.label()
            foreground = UiKit.text
            isFocusable = true
            caretColor = UiKit.text
            disableCaretScroll(this)
            // **必须显式设**：JTextArea 继承自 JComponent，alignmentX 默认是 0.5（居中）。
            // 纵向 BoxLayout 里只要有一个子组件是 0.5，其他 0.0 的兄弟就会被整体推到右边，
            // 而且宽度被压到约 2/3（离线真值表实测：容器宽 800 时
            // [x=266 w=534 a=0.0] [x=0 w=800 a=0.5]）。
            // 用户看到的「气泡内容整体右移、左边空掉一大片、右侧还被裁」就是这个。
            alignmentX = LEFT_ALIGNMENT
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * 把内容列里所有子组件的 alignmentX 统一刷成 LEFT_ALIGNMENT。
     *
     * 正常路径不需要它 —— [MessageBubble] 的内容列在 `addImpl` 里就自动刷了。
     * 留着是给「组件已经挂上去之后，对齐又被外部改回默认值」这种兜底场景。
     */
    private fun normalizeContentAlignment() {
        val content = getComponent(0) as? JPanel ?: return
        for (c in content.components) {
            if (c is JComponent) c.alignmentX = LEFT_ALIGNMENT
        }
    }

    /**
     * 追加正文（流式）。
     *
     * 只 append 到正文控件，不重建任何东西 —— 重建会闪、也会打断用户正在看的滚动位置。
     * 正文第一次到来时，把思考容器**立刻销毁**（不保留、不折叠）：思考是过程，不是内容。
     */
    /**
     * 追加正文分片。
     *
     * 不能直接往正文里塞：模型可能把思考**内联**在正文流里（` thinking…<｜end▁of▁thinking｜>`），
     * 而 SSE 分片会把标记切开（`<th` + `ink>`）—— 直接匹配必然漏掉，标记会原样显示出来。
     * 所以先入缓冲，再按标记切分；末尾疑似标记前缀的部分留着等下一片。
     */
    fun appendText(text: String) {
        clearPending()
        if (turnStartedAt == 0L) turnStartedAt = System.currentTimeMillis()
        streamBuffer.append(text)
        drainStreamBuffer()
    }

    private val streamBuffer = StringBuilder()

    // 标记用字符码拼，源码里不出现实 '<' 字面量 ——
    // 写入链路会把 "<xxx" 当成 HTML 标签吃掉，会连字符串的收尾引号一起吞，直接编译不过
    private val LT = 60.toChar()
    private val GT = 62.toChar()
    /** 思考容器最高多少像素，超出在容器内部滚动（长思考不撑爆气泡） */
    private val THINK_MAX_HEIGHT = 180

    private val thinkStart = "" + LT + "think" + GT
    private val thinkEnd = "" + LT + "/think" + GT

    /** 当前是否在思考段内（由流里的标记切换） */
    private var inThink = false

    /** 把缓冲里的内容按标记切分成「思考」与「正文」两段 */
    private fun drainStreamBuffer() {
        while (true) {
            val buf = streamBuffer.toString()
            if (inThink) {
                val end = buf.indexOf(thinkEnd)
                if (end < 0) {
                    val keep = markerPrefixKeep(buf, thinkEnd)
                    if (buf.length > keep) appendThinkText(buf.substring(0, buf.length - keep))
                    streamBuffer.setLength(0)
                    streamBuffer.append(buf.takeLast(keep))
                    return
                }
                appendThinkText(buf.substring(0, end))
                streamBuffer.delete(0, end + thinkEnd.length)
                exitThink()
            } else {
                val start = buf.indexOf(thinkStart)
                if (start < 0) {
                    val keep = markerPrefixKeep(buf, thinkStart)
                    if (buf.length > keep) appendBodyText(buf.substring(0, buf.length - keep))
                    streamBuffer.setLength(0)
                    streamBuffer.append(buf.takeLast(keep))
                    return
                }
                appendBodyText(buf.substring(0, start))
                streamBuffer.delete(0, start + thinkStart.length)
                enterThink()
            }
        }
    }

    /** buf 末尾有多少字符可能是 marker 的前缀（要留着等下一片） */
    private fun markerPrefixKeep(buf: String, marker: String): Int {
        for (k in minOf(buf.length, marker.length - 1) downTo 1) {
            if (buf.endsWith(marker.substring(0, k))) return k
        }
        return 0
    }

    private fun enterThink() {
        inThink = true
        if (reasoningMode == "hidden") return
        thinkPanel.isVisible = true
        revalidate()
    }

    /** 思考段结束：**立即销毁**思考容器（不保留、不折叠），正文接管同一个气泡 */
    private fun exitThink() {
        inThink = false
        freezeReasoningTimer()
        // 规格：思考阶段结束就**销毁**容器 —— 不保留、不折叠、不留入口
        destroyThinking()
    }

    /** 思考文字：浅灰「虚字」+ 容器内滚动 */
    private fun appendThinkText(chunk: String) {
        if (chunk.isEmpty()) return
        if (reasoningMode == "hidden") return
        clearPending()
        if (reasoningStartedAt == 0L) reasoningStartedAt = System.currentTimeMillis()
        reasoningBuffer.append(chunk)
        // 只追加、不整段重设：setText 会重置滚动位置，长思考会一直「跳回顶部」
        thinkArea.append(chunk)
        // 把**内层**滚动条拉到底，而不是用 `thinkArea.caretPosition = ...`。
        //
        // setCaretPosition 会走 scrollRectToVisible 那条链，理论上可能一路冒泡到外层
        // messagesScroll 把用户的位置拽走。（离线探针**没能复现**这条传播 ——
        // 组件不 isShowing 时根本不触发。但直接设滚动条值是等价且更可控的做法，
        // 不依赖任何隐含行为，所以就这么写。）
        val thinkBar = thinkScroll.verticalScrollBar
        thinkBar.value = thinkBar.maximum
        if (!thinkPanel.isVisible) thinkPanel.isVisible = true
        // 高度必须**每次追加后重算**：只算一次的话，容器会停在「首次那一行」的高度，
        // 后面增长的思考文字既看不见也滚不动（表现就是「思考过程没了」）。
        // 上限 THINK_MAX_HEIGHT，超出在容器内部滚动。
        thinkScroll.preferredSize = Dimension(
            0,
            thinkArea.preferredSize.height.coerceIn(1, THINK_MAX_HEIGHT)
        )
        revalidate()
        repaint()
    }

    /** 正文：正常颜色，结束后走 Markdown 渲染 */
    /**
     * 正文增量。
     *
     * **纯文本更新要节流**：原来是每来一片就 `bodyArea.text = 全文` ——
     * 这是 O(n) 的整文档替换，一次回答上千片就是 O(n²) 的拷贝，
     * 而且每次都会重新折行计算高度。实测长回答时能明显看到打字卡顿。
     * 现在最多每 [PLAIN_INTERVAL_MS] 刷一次，中间的内容只进 buffer。
     */
    private fun appendBodyText(chunk: String) {
        if (chunk.isEmpty()) return
        textBuffer.append(chunk)
        // 正文已经有内容了，放开思考销毁时锁的高度下限（幂等）
        if (minimumSize.height > 0) minimumSize = Dimension(0, 0)
        if (maybeRenderStreamingRich()) return
        // 已经切到富文本 → 正文由 richArea 负责，不必再刷纯文本
        if (streamRich) return
        schedulePlainFlush()
    }

    /** 节流刷新纯文本正文（见 [appendBodyText]） */
    private fun schedulePlainFlush() {
        val now = System.currentTimeMillis()
        if (now - lastPlainAt >= PLAIN_INTERVAL_MS) {
            flushPlainNow()
            return
        }
        // 距上次太近 → 排一次延后刷新。**这必须有**：
        // 只按时间戳丢帧的话，最后一片可能永远不落地，正文停在半句话上
        if (plainTimer == null) {
            plainTimer = javax.swing.Timer(PLAIN_INTERVAL_MS.toInt()) {
                plainTimer?.stop()
                plainTimer = null
                flushPlainNow()
            }.apply { isRepeats = false; start() }
        }
    }

    private fun flushPlainNow() {
        lastPlainAt = System.currentTimeMillis()
        bodyArea.text = textBuffer.toString()
        revalidate()
        repaint()
    }

    /** 纯文本刷新间隔（见 [schedulePlainFlush]） */
    private val PLAIN_INTERVAL_MS = 80L
    private var lastPlainAt = 0L
    private var plainTimer: javax.swing.Timer? = null

    /**
     * 流式过程中的 Markdown 节流渲染。
     *
     * 不这么做的话，整段生成过程都是**原始 Markdown 记号**（`##`、`**`、`| 表格 |`
     * 一行行裸着显示），观感很糙；每 320ms 重排一次的开销可以接受，
     * 而且因为是整块换 HTML（不是逐 token 改文档），不会有打字机式的抖动。
     * 真正逐 token 的开销在回合结束的 finalize 里还会再渲染一次（保证终态完整）。
     */
    /** @return true = 这次已经渲染成富文本（调用方不必再刷纯文本） */
    private fun maybeRenderStreamingRich(): Boolean {
        val text = textBuffer.toString()
        if (text.length < STREAM_RICH_MIN_CHARS) return false
        val now = System.currentTimeMillis()
        if (now - lastRichRenderAt < STREAM_RICH_INTERVAL_MS) return false
        if (!streamRich && !MarkdownRenderer.looksLikeMarkdown(text)) return false
        val html = MarkdownRenderer.toHtml(text) ?: return false
        lastRichRenderAt = now
        richArea.text = html
        streamRich = true
        richScroll.isVisible = true
        richArea.isVisible = true
        bodyArea.isVisible = false
        return true
    }

    /**
     * 独立 reasoning 字段（DeepSeek-R1 / QwQ 这类）走这里。
     *
     * 与流内标记（appendText）是两条来源，但**共用同一个思考容器** ——
     * 界面上只有一种「思考」的样子，不区分它从哪来。
     */
    fun appendReasoning(text: String) {
        if (reasoningMode == "hidden") return
        appendThinkText(text)
    }

    fun appendAttachmentNote(names: List<String>) {
        val label = JBLabel("附件：" + names.joinToString("、")).apply {
            foreground = UiKit.subtle
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.emptyBottom(3)
            alignmentX = LEFT_ALIGNMENT
        }
        val content = getComponent(0) as JPanel
        content.add(label, 2)
        normalizeContentAlignment()
    }

    /**
     * 收尾：把正文里识别出的代码块渲染成独立区块（各带「应用」按钮），
     * 并在气泡**左下角**挂上消息级操作。
     *
     * @param onRegenerate 点「重新生成」时回调（助手气泡传，用户气泡传 null）
     * @param onEdit 点「编辑」时回调（用户气泡传，助手气泡传 null）
     *
     * **调用时一律用命名参数**，不要写 `finalize(text) { regenerate() }` ——
     * 尾随 lambda 会绑定到**最后一个参数**（也就是 onEdit），于是「重新生成」被当成
     * 「编辑」传进来：助手气泡长出一个点了没反应的编辑按钮，而重新生成图标消失。
     * 这个坑真踩过（用户反馈「为什么只有复制这一条」+「点编辑并没有撤回」）。
     */
    /**
     * @param stopped 是被用户**中途停止**的（不是正常答完）。
     *        以前这两种在界面上完全一样，都写「已完成」—— 停止过的那条
     *        看起来像答完了，用户会以为 AI 就答了这么点。
     * @param replay 这是**从历史重建**的气泡（重载会话、编辑重发）。
     *        重建时没有真实的起始时间，[markTurnFinished] 会算出个假的「1 秒」——
     *        所以重建的气泡**不显示耗时**，宁可少一条信息，也不要一个错数字。
     */
    fun finalize(
        finalText: String,
        onRegenerate: (() -> Unit)? = null,
        onEdit: (() -> Unit)? = null,
        stopped: Boolean = false,
        replay: Boolean = false
    ) {
        val source = if (finalText.isNotBlank()) finalText else textBuffer.toString()

        // 收尾：思考容器还挂着就销毁（正文还没开始的极端情况，例如纯工具调用轮）
        if (thinkPanel.isVisible) {
            freezeReasoningTimer()
            destroyThinking()
        }

        clearPending()
        stopLiveStatus()
        // 停掉正文的节流定时器并同步一次：否则定时器可能稍后 fire，
        // 在已经渲染好富文本之后再覆盖一次（白做，且可能闪一下）
        plainTimer?.stop()
        plainTimer = null
        flushPlainNow()
        freezeReasoningTimer()
        if (!replay) markTurnFinished(stopped)
        renderRichText(source)
        attachMessageActions(source, onRegenerate, onEdit)
        applyCollapseIfNeeded(source)

        val blocks = CodeBlockSupport.extractBlocks(source)
        if (blocks.isEmpty()) {
            revalidate()
            repaint()
            return
        }

        codeBlocksPanel.removeAll()
        for (block in blocks) {
            codeBlocksPanel.add(buildCodeBlockCard(block))
            codeBlocksPanel.add(javax.swing.Box.createVerticalStrut(4))
        }
        codeBlocksPanel.isVisible = true
        revalidate()
        repaint()
    }

    /**
     * 标记「已发出、还没收到任何内容」。
     *
     * 由 ChatPanel 在**发起请求时**调用（不是收到第一个 token 时）——
     * 目的就是让用户马上看到反馈。
     */
    fun markPending() {
        if (kind != Kind.ASSISTANT) return
        if (pendingStartedAt > 0L) return
        pendingStartedAt = System.currentTimeMillis()
        val content = getComponent(0) as? JPanel ?: return
        if (pendingLabel.parent == null) content.add(pendingLabel, 2)
        pendingLabel.isVisible = true
        tickPending()
        pendingTimer = javax.swing.Timer(1000) { tickPending() }.apply { start() }
        content.revalidate()
        content.repaint()
    }

    private fun tickPending() {
        val sec = ((System.currentTimeMillis() - pendingStartedAt) / 1000).coerceAtLeast(0)
        pendingLabel.text = "正在思考…（已 " + sec + " 秒）"
    }

    /** 收到任何真实内容就撤掉占位 */
    private fun clearPending() {
        if (pendingStartedAt == 0L) return
        pendingTimer?.stop()
        pendingTimer = null
        pendingLabel.isVisible = false
        val content = getComponent(0) as? JPanel
        content?.revalidate()
        content?.repaint()
    }

    /**
     * 停掉这个气泡里所有还在跑的定时器。
     *
     * **移除气泡前必须调**：`javax.swing.Timer` 不随组件一起消失，
     * 气泡被 remove 之后它们照样按秒触发（占位计时、阶段计时、工具卡旋转动效），
     * 一个长会话反复清空/切会话就会攒下一堆空转的定时器。
     */
    fun disposeTimers() {
        pendingTimer?.stop()
        pendingTimer = null
        plainTimer?.stop()
        plainTimer = null
        liveTimer?.stop()
        liveTimer = null
        // 工具卡的旋转动效（取消时卡片可能还停在「运行中」）
        for (c in toolCardsPanel.components) {
            (c as? ToolCard)?.stopAnimation()
        }
    }

    /** 切回 EDT。MessageBubble 是独立组件，拿不到 ChatPanel 的 edt 助手 */
    private fun onEdt(action: () -> Unit) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action()
        else com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(action)
    }

    /**
     * 挂一个附件卡片。
     *
     * 位图给真缩略图（`ImageIO` 直接能读 PNG/JPG/GIF）；**SVG 走 [imageCard] 的
     * 预览窗口路径**（以前这里写着「JDK 里没有 SVG 渲染器，做预览比不给更误导」——
     * 那是**按 JDK 说话，忘了平台**：IntelliJ 平台自带 SVG 渲染器
     * `com.intellij.ui.svg.JSvgDocument`，渲染质量和 IDE 图标同源，能真预览）。
     * XML/视频仍是文件卡片（VectorDrawable 的 XML 需要 Android 语义，静态渲染会失真）。
     *
     * @param relPath 项目内的相对路径
     */
    fun addAttachment(relPath: String) {
        if (relPath.isBlank() || !attachedPaths.add(relPath)) return
        // **绝对路径要直接用**，不能拼到工程根下面。
        // 临时产物现在落在 `~/.zhixueyao/Conversation/Product/...`（不在工程里），
        // 工具回报的就是绝对路径 —— 拼成 `工程根\C://Users//...` 自然找不到，
        // 于是卡片显示「读不出来」、连预览入口都没有（用户反馈的正是这个）。
        val file = java.io.File(relPath).let { f ->
            if (f.isAbsolute) f else java.io.File(project?.basePath ?: return, relPath)
        }
        val ext = file.extension.lowercase()
        // svg 也和位图一样给预览卡片（缩略图由平台的 SVG 渲染器出）
        val card = if (ext in IMAGE_EXTS || ext == "svg") imageCard(file, relPath) else fileCard(file, relPath)
        onEdt {
            if (attachmentsPanel.componentCount > 0) {
                attachmentsPanel.add(javax.swing.Box.createHorizontalStrut(6))
            }
            attachmentsPanel.add(card)
            attachmentsPanel.isVisible = true
            attachmentsPanel.revalidate()
            attachmentsPanel.repaint()
            revalidate()
            repaint()
        }
    }

    /** 图片：后台读、缩放到缩略图大小，读完回 EDT 贴上去 */
    private fun imageCard(file: java.io.File, relPath: String): JComponent {
        val holder = javax.swing.JLabel().apply {
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            verticalAlignment = javax.swing.SwingConstants.CENTER
            preferredSize = Dimension(THUMB_W, THUMB_H)
            text = "加载中…"
            font = font.deriveFont(font.size - 2f)
            foreground = UiKit.faint
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val icon = runCatching {
                // svg 要单独走平台渲染器 —— ImageIO 读不了它，会得到 null
                val img = if (file.extension.lowercase() == "svg") {
                    file.inputStream().use { com.intellij.util.SVGLoader.load(it, 2f) }?.let { raw ->
                        // SVGLoader 给的是 java.awt.Image，尺寸要用 getWidth(observer) 取，
                        // 而下面要算缩放比例 → 先落成 BufferedImage
                        val w = raw.getWidth(null).coerceAtLeast(1)
                        val h = raw.getHeight(null).coerceAtLeast(1)
                        java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
                            val g = it.createGraphics(); g.drawImage(raw, 0, 0, null); g.dispose()
                        }
                    }
                } else {
                    javax.imageio.ImageIO.read(file)
                }
                if (img == null) null else {
                    val scale = minOf(THUMB_W.toDouble() / img.width, THUMB_H.toDouble() / img.height, 1.0)
                    val w = (img.width * scale).toInt().coerceAtLeast(1)
                    val h = (img.height * scale).toInt().coerceAtLeast(1)
                    val scaled = img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)
                    javax.swing.ImageIcon(scaled)
                }
            }.getOrNull()
            onEdt {
                if (icon != null) {
                    holder.icon = icon
                    holder.text = null
                } else {
                    holder.text = "读不出来"
                }
                holder.revalidate()
                holder.repaint()
            }
        }
        return cardShell(holder, relPath, file, clickable = true)
    }

    /** 非图片：文件名 + 大小 + 类型标记 */
    private fun fileCard(file: java.io.File, relPath: String): JComponent {
        val ext = file.extension.lowercase()
        val kind = when (ext) {
            "xml" -> "VectorDrawable"
            "svg" -> "SVG"
            "mp4", "webm" -> "视频"
            "gif" -> "动图"
            else -> ext.uppercase()
        }
        val body = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(THUMB_W, THUMB_H)
            add(
                JBLabel(kind).apply {
                    alignmentX = LEFT_ALIGNMENT
                    font = font.deriveFont(font.size + 1f)
                    horizontalAlignment = javax.swing.SwingConstants.CENTER
                    foreground = UiKit.subtle
                },
                java.awt.BorderLayout.CENTER
            )
        }
        return cardShell(body, relPath, file, clickable = true)
    }

    /** 卡片外壳：内容 + 文件名 + 大小，整卡可点（在 IDE 里打开） */
    private fun cardShell(
        body: JComponent,
        relPath: String,
        file: java.io.File,
        clickable: Boolean
    ): JComponent {
        val sizeText = runCatching {
            val kb = file.length() / 1024.0
            if (kb < 1) "${file.length()} B" else String.format(java.util.Locale.US, "%.0f KB", kb)
        }.getOrDefault("")
        val card = object : JPanel(java.awt.BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(6)
                if (clickable) cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                add(body, java.awt.BorderLayout.CENTER)
                add(
                    JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
                        isOpaque = false
                        add(
                            JBLabel(file.name).apply {
                                font = font.deriveFont(font.size - 1f)
                                foreground = UiKit.subtle
                                toolTipText = relPath
                            },
                            java.awt.BorderLayout.NORTH
                        )
                        add(
                            JBLabel(sizeText).apply {
                                font = font.deriveFont(font.size - 2f)
                                foreground = UiKit.faint
                            },
                            java.awt.BorderLayout.SOUTH
                        )
                    },
                    java.awt.BorderLayout.SOUTH
                )
                // 能预览的格式（含 svg）点开是**预览窗口**（缩放 + 上下张）；
                // 其余（XML/视频等）仍然直接在编辑器里打开。
                // 以前一律「在编辑器中打开」—— 对 svg 就是打开一堆 XML 原文，
                // 等于没有预览（用户反馈原话：「按理来说不应该出现一个窗口来预览放大缩小之类的效果吗？」）
                // 图片 → 预览窗口（可缩放、左右翻页）；视频 → 系统默认播放器；
                // 其余（XML/源码等）才在编辑器里打开。
                val previewable = com.zhixueyao.ui.ImagePreviewDialog.isMedia(file)
                toolTipText = when {
                    com.zhixueyao.ui.ImagePreviewDialog.isVideo(file) ->
                        "$relPath\n单击用系统默认播放器打开"
                    previewable ->
                        "$relPath\n单击预览（可缩放、左右翻页）　双击在编辑器中打开"
                    else -> "$relPath（点击在编辑器中打开）"
                }
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (previewable && e.clickCount == 1) {
                            openPreview(file, relPath)
                        } else {
                            openInIde(relPath)
                        }
                    }
                })
            }

            override fun paintComponent(g: java.awt.Graphics) {
                // 自绘圆角底：isOpaque=false + 自绘（border 只画描边，撑不出圆角实底）
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    g2.color = com.intellij.util.ui.UIUtil.getPanelBackground()
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                    g2.color = UiKit.border
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
        card.maximumSize = Dimension(THUMB_W + 14, Int.MAX_VALUE)
        return card
    }

    /** 在 IDE 里打开这个文件（比内置查看器省事，而且看到的**就是真实渲染**） */
    /**
     * 「请求打开预览」的出口。
     *
     * 由外层（ChatPanel）挂上 —— 因为**左右翻页的范围是「这个会话产出的全部图」**，
     * 只有外层知道那份清单（气泡自己只看得到自己附件里那几张）。
     * 没挂（组件被单独使用时）就退回「同目录翻页」。
     */
    var onPreviewRequest: ((java.io.File) -> Unit)? = null

    /**
     * 打开预览窗口。
     *
     * 打不开时**把原因说出来** —— 静默失败的话用户只会反复点，
     * 不知道该换格式还是文件真没了。
     */
    private fun openPreview(file: java.io.File, relPath: String) {
        val handler = onPreviewRequest
        if (handler != null) {
            handler(file)
            return
        }
        val err = com.zhixueyao.ui.ImagePreviewDialog.show(project, file)
        if (err != null) {
            com.intellij.openapi.ui.Messages.showWarningDialog(project, "$err\n\n路径：$relPath", "预览")
        }
    }

    private fun openInIde(relPath: String) {
        val p = project ?: return
        runCatching {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath("${p.basePath}/$relPath") ?: return
            com.intellij.openapi.fileEditor.OpenFileDescriptor(p, vf, 0).navigate(true)
        }
    }

    /**
     * 显示引用校验结果。**只在真的找不到时才显示** ——
     * 一个每次回答都出现的提示等于没有提示。
     */
    fun showReferenceHint(missing: List<String>) {
        if (missing.isEmpty()) return
        val shown = missing.take(4).joinToString("、")
        val tail = if (missing.size > 4) " 等 ${missing.size} 个" else ""
        refHint.text = "⚠ 这些文件在项目里没找到：$shown$tail"
        // 完整清单放 tooltip：状态栏一行放不下，气泡里也不该铺开一大片
        refHint.toolTipText = missing.joinToString("\n")
        refHint.isVisible = true
        refHint.revalidate()
        repaint()
    }

    /**
     * 挂上版本切换控件。
     *
     * @param variants 全部版本（含当前这版）
     * @param active 当前显示的是第几版
     */
    fun setVariants(variants: List<String>, active: Int, onSwitch: (Int) -> Unit) {
        if (variants.size <= 1) return
        variantCount = variants.size
        variantIndex = active.coerceIn(0, variants.size - 1)
        onVariantSwitch = onSwitch
        variantLabel.text = "‹ ${variantIndex + 1}/$variantCount ›"
        variantLabel.toolTipText = "这条回答有 $variantCount 个版本，点两侧切换"
        variantLabel.isVisible = true
        if (variantLabel.parent == null) {
            // 放在最左边：它是「这条回答的状态」，比复制/重新生成更该先被看到
            actionsLeft.add(variantLabel, 0)
        }
        actionsLeft.revalidate()
        actionsLeft.repaint()
    }

    /**
     * 这一轮的 token 用量（页脚显示）。
     *
     * 为什么放在**气泡页脚**而不是只放状态栏：状态栏那行是全局的、只显示一会儿，
     * 下一个动作（切换模型、排队发送…）就把它覆盖了，翻历史时更是看不到。
     * 用户反馈「为什么我看不到使用了多少 token」——
     * 用量属于**这条消息**，就该跟着消息走。
     */
    private var usageText: String = ""
    private var usageTooltip: String = ""

    /**
     * 写入用量。**必须在 finalize 之后调**（finalize 里会重建页脚）。
     *
     * @param prompt 输入 token；0 = 服务商没回报
     * @param completion 输出 token
     * @param estimated 服务商没回报时用估算值兜底（会标注「估算」）
     */
    fun setUsage(prompt: Int, completion: Int, estimated: Int = 0, steps: Int = 0) {
        if (prompt <= 0 && completion <= 0 && estimated <= 0) return
        if (prompt > 0 || completion > 0) {
            val total = prompt + completion
            usageText = formatTokens(total) + " tokens"
            usageTooltip = "输入 " + withSeparators(prompt) + " · 输出 " + withSeparators(completion) +
                if (steps > 0) " · 共 " + steps + " 步" else ""
        } else {
            usageText = "≈" + formatTokens(estimated) + " tokens（估算）"
            usageTooltip = "服务商没有回报用量，这是按字符估算的"
        }
        refreshMeta()
    }

    /** 重新拼页脚：模型 · 时间 · 用量 */
    private fun refreshMeta() {
        actionsMeta.text = if (kind == Kind.ASSISTANT) {
            val model = ZhixueyaoSettings.getInstance().model
            val parts = mutableListOf<String>()
            if (model.isNotBlank()) parts.add(model)
            parts.add(createdAt)
            if (usageText.isNotBlank()) parts.add(usageText)
            parts.joinToString(" · ")
        } else {
            createdAt
        }
        actionsMeta.toolTipText = usageTooltip.ifBlank { null }
    }

    private fun formatTokens(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 10_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
        else -> String.format(java.util.Locale.US, "%.0fk", n / 1000.0)
    }

    private fun withSeparators(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

    /** 收尾时把「已完成 / 已停止 · 用时」写进头部状态 */
    private fun markTurnFinished(stopped: Boolean = false) {
        if (kind != Kind.ASSISTANT || turnStartedAt == 0L) return
        val seconds = ((System.currentTimeMillis() - turnStartedAt) / 1000).coerceAtLeast(1)
        headerStatus.text = if (stopped) "· 已停止 " + seconds + " 秒" else "· 已完成 " + seconds + " 秒"
        // 停止过的用提示色，扫一眼就能和正常回答区分开
        headerStatus.foreground = if (stopped) UiKit.warn else UiKit.faint
        headerStatus.isVisible = true
        headerStatus.revalidate()
    }

    /**
     * 把 Markdown 渲染成富文本（失败就保持纯文本）。
     *
     * 只在**这一轮结束时**做一次：流式过程中每个增量都解析整篇 Markdown 太贵。
     */
    private fun renderRichText(source: String) {
        // 代码块由下方卡片单独展示，正文里摘掉 —— 否则同一段代码显示两遍
        val forHtml = CodeBlockSupport.stripBlocks(source)
        if (!MarkdownRenderer.looksLikeMarkdown(forHtml)) {
            showPlainBody()
            return
        }
        val html = MarkdownRenderer.toHtml(forHtml) ?: run {
            showPlainBody()
            return
        }
        richArea.text = html
        richScroll.isVisible = true
        richArea.isVisible = !collapsed
        bodyArea.isVisible = collapsed
        revalidate()
        repaint()
    }

    /** 显示纯文本正文（流式中、折叠预览、渲染失败时） */
    private fun showPlainBody() {
        // 连外层滚动容器一起隐藏 —— 只藏里面那个编辑器没用，它照样占高度
        richScroll.isVisible = false
        richArea.isVisible = false
        bodyArea.isVisible = true
    }

    /**
     * 长回答折叠。
     *
     * 规则本身在 [CollapsePolicy]，这里只负责接线（改文本、换按钮文案、重布局）。
     * 把判定抽出去是为了能离线实测边界值 —— 见 `CollapsePolicyTest`。
     *
     * 折叠作用于**显示层**：头部的「复制」按钮始终复制完整原文
     * （[attachMessageActions] 拿到的是未折叠的 source），
     * 所以折叠不会让人拿不到内容。只是拖选正文时选到的是可见部分。
     */
    private fun applyCollapseIfNeeded(source: String) {
        if (!CollapsePolicy.shouldCollapse(kind == Kind.ASSISTANT, source)) return
        // finalize 可能被多次调用（出错后重试），只挂一次监听
        if (collapseToggle.getClientProperty(COLLAPSE_READY) == true) return
        collapseToggle.putClientProperty(COLLAPSE_READY, true)

        collapsed = true
        bodyArea.text = CollapsePolicy.preview(source)
        collapseToggle.isVisible = true
        collapseToggle.relabel(CollapsePolicy.expandLabel(source))
        collapseToggle.toolTipText = "这段回答有 ${source.length} 字符，点击展开全文"

        collapseToggle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                collapsed = !collapsed
                bodyArea.text = if (collapsed) MarkdownRenderer.stripSyntax(CollapsePolicy.preview(source)) else source
                // 展开时把富文本（Markdown 渲染结果）换回来，折叠时用纯文本预览
                richArea.isVisible = !collapsed && richArea.text.isNotBlank()
                richScroll.isVisible = richArea.isVisible
                bodyArea.isVisible = !richArea.isVisible
                collapseToggle.relabel(
                    if (collapsed) CollapsePolicy.expandLabel(source) else CollapsePolicy.COLLAPSE_LABEL
                )
                revalidate()
                repaint()
            }
        })

        // 插在正文正上方。用 bodyArea 的实际索引而不是数出来的常数 ——
        // 前面还有角色头、间距、思维链开关与思维链区，数量会随状态增减
        val content = getComponent(0) as? JPanel ?: return
        val idx = content.getComponentZOrder(bodyArea)
        if (idx >= 0) content.add(collapseToggle, idx)
        normalizeContentAlignment()
    }

    /** 当前正文（供「修改回答」对话框取初值） */
    fun currentText(): String = textBuffer.toString().ifBlank { bodyArea.text }

    /**
     * 气泡「自然宽度」：内容真正需要多宽。由 [com.zhixueyao.ui.ChatPanel] 拿来给气泡定宽。
     *
     * 为什么要这个：气泡以前**一律拉满 82% 宽**，于是两个字的「你好」也是一整条蓝色，
     * 文字孤零零贴在左边、名字孤零零贴在右边，中间一大片空 —— 又空又难看。
     * 短消息就该是个小气泡。
     *
     * **不能读 `bodyArea.preferredSize.width`**：JTextArea 开了 lineWrap 之后，
     * 首选宽度是「按当前宽度」算出来的（自己依赖自己），拿到的是上一次布局的残留值，
     * 没有意义。所以这里用字体度量现算。
     *
     * 返回 **0 表示「算不准，按上限撑满」**：富文本、代码块、工具卡片、折叠态都属于这种
     * （宽度由 HTML / 等宽文本 / 内部布局决定，量出来不可靠，宁可不贴合）。
     */
    fun naturalWidth(): Int {
        if (richScroll.isVisible || richArea.isVisible) return 0
        if (codeBlocksPanel.isVisible || toolCardsScroll.isVisible) return 0
        if (collapsed) return 0
        val text = textBuffer.toString().ifBlank { bodyArea.text }
        if (text.isBlank()) return 0
        val fm = bodyArea.getFontMetrics(bodyArea.font)
        // 按**逻辑行**量：软换行由宽度决定，参与进来就循环了
        val textWidth = text.lines().maxOfOrNull { fm.stringWidth(it) } ?: 0
        // 操作行虽然画在气泡外面，但它的宽度决定了气泡至少得多宽，
        // 否则「复制/编辑」会和「模型 · 时间」挤在一起
        val floorWidth = actionsRow.preferredSize.width
        return maxOf(textWidth, floorWidth) + insets.left + insets.right
    }

    /**
     * 用新文本替换正文（「修改回答」用）。
     *
     * 必须**重新提取代码块** —— 否则用户把代码改了，下面的代码卡片和「应用」按钮
     * 还停在旧内容上，看着像没改成功。
     */
    fun replaceContent(text: String) {
        textBuffer.clear()
        textBuffer.append(text)
        bodyArea.text = text

        // **必须重渲染富文本那一层**。
        //
        // finalize 之后气泡显示的是 `richArea`（HTML），`bodyArea`（纯文本）是隐藏的 ——
        // 只改 bodyArea 的话，切换版本时**用户看到的文字一个字都不会变**
        // （用户反馈：「为什么第 1 版和第 2 版是同一个回答，文字什么的都没有改变」）。
        renderRichText(text)

        val blocks = CodeBlockSupport.extractBlocks(text)
        codeBlocksPanel.removeAll()
        for (block in blocks) {
            codeBlocksPanel.add(buildCodeBlockCard(block))
            codeBlocksPanel.add(Box.createVerticalStrut(4))
        }
        codeBlocksPanel.isVisible = blocks.isNotEmpty()

        // 折叠是按内容长度判定的：换了一版之后长度可能差很多，
        // 折叠条要重新算（否则会出现「新版很短却还挂着展开按钮」）
        refreshCollapseFor(text)

        revalidate()
        repaint()
    }

    /**
     * 内容变了之后重算折叠状态。
     *
     * 和 [applyCollapseIfNeeded] 的区别：那个只在**首次**判定时挂监听（幂等），
     * 这里要的是「内容换了之后，折叠条的长度描述和显隐重新算一遍」。
     */
    private fun refreshCollapseFor(source: String) {
        if (!CollapsePolicy.shouldCollapse(kind == Kind.ASSISTANT, source)) {
            // 这一版短到不需要折叠：收起折叠条，正文按正常方式显示
            //（有富文本显示富文本，否则显示纯文本）
            collapseToggle.isVisible = false
            collapsed = false
            val hasRich = richArea.text.isNotBlank()
            richArea.isVisible = hasRich
            richScroll.isVisible = hasRich
            bodyArea.isVisible = !hasRich
            return
        }
        collapseToggle.isVisible = true
        collapseToggle.relabel(
            if (collapsed) CollapsePolicy.expandLabel(source) else CollapsePolicy.COLLAPSE_LABEL
        )
        collapseToggle.toolTipText = "这段回答有 ${source.length} 字符，点击展开全文"
        if (collapsed) {
            // 折叠状态下显示的是纯文本预览 —— 换版本后这段预览也要跟着换
            bodyArea.text = MarkdownRenderer.stripSyntax(CollapsePolicy.preview(source))
            richArea.isVisible = false
            richScroll.isVisible = false
            bodyArea.isVisible = true
        }
    }

    /**
     * 在气泡**左下角**挂消息级操作。
     *
     *  - 助手：复制 / 重新生成
     *  - 用户：复制 / 编辑（编辑 = 把内容放回输入框，并丢弃这条之后的内容）
     *
     * 只挂一次 —— finalize 可能被调用多次（比如出错后重试），重复添加会堆一串按钮。
     * 用 client property 而不是 `isVisible` 判断：可见性是结果，不是「挂没挂过」的状态。
     */
    private fun attachMessageActions(
        source: String,
        onRegenerate: (() -> Unit)?,
        onEdit: (() -> Unit)?
    ) {
        if (actionsRow.getClientProperty(ACTIONS_ADDED) == true) return
        actionsRow.putClientProperty(ACTIONS_ADDED, true)

        // 按钮由**气泡类型**决定，而不是「调用方传了哪个回调就显示哪个」。
        //
        // 之前按回调显示，结果助手气泡也长出了「编辑」；而助手消息在历史里没有可回退的
        // 位置，点下去只会静默什么都不发生（用户反馈「点了编辑并没有撤回」）。
        // 按 kind 决定之后，这类错配从根上不可能出现。
        when (kind) {
            Kind.ASSISTANT -> {
                actionsLeft.add(copyAction(source, "复制这条回答"))
                onRegenerate?.let { cb -> actionsLeft.add(iconAction(UiKit.refresh, "重新生成这条回答", cb)) }
                // 助手**不给编辑**：回答是模型产出，要改就重新生成（用户明确要求去掉）
            }

            Kind.USER -> {
                actionsLeft.add(copyAction(source, "复制这条消息"))
                onEdit?.let { cb -> actionsLeft.add(iconAction(UiKit.edit, "把这条消息放回输入框，并撤回它之后的回复", cb)) }
            }

            Kind.SYSTEM -> actionsLeft.add(copyAction(source, "复制"))
        }

        // 右侧脚注：助手消息带模型名 + token 用量，用户消息只有时间
        refreshMeta()

        actionsRow.isVisible = actionsLeft.componentCount > 0
        // 留白跟着操作行一起显隐：否则没操作行的气泡底部会白多一条
        actionsGap.isVisible = actionsRow.isVisible
        revalidate()
        repaint()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    /**
     * 消息操作按钮：无边框小图标 + tooltip（WorkBuddy 那种样式），悬停浮出淡底。
     *
     * 用图标而不是文字：一排两个带框的文字按钮看着很笨重；图标按钮是聊天界面的通行做法。
     */
    private fun iconAction(icon: Icon, tooltip: String, onClick: () -> Unit): JComponent =
        UiKit.iconButton(icon, tooltip, 1, onClick)

    /**
     * 复制按钮：点一下把图标换成对勾，1.2 秒后复原。
     *
     * 图标按钮没有文字可改，所以反馈走图标 —— 但**必须有反馈**：
     * 静默成功等于失败（用户不知道到底复制上没有）。
     */
    private fun copyAction(text: String, tooltip: String): JButton {
        val button = UiKit.iconButton(UiKit.copy, tooltip, 1) { }
        button.addActionListener {
            copyToClipboard(text)
            button.icon = UiKit.check
            javax.swing.Timer(1200) {
                button.icon = UiKit.copy
                button.revalidate()
            }.apply { isRepeats = false }.start()
        }
        return button
    }

    /**
     * 单个操作项：小号灰字，悬停变链接色，点击后短暂反馈。
     *
     * 「复制」这类操作必须有可见反馈 —— 静默成功等于失败（用户不知道到底复制上没有）。
     * 这里用「已复制 → 1.2 秒后复原」的轻量反馈，不弹框、不打断。
     */
    private fun actionItem(
        label: String,
        tooltip: String,
        feedback: String? = null,
        onClick: () -> Unit
    ): JComponent {
        val item = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JBLabel(label).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UiKit.subtle
            }, BorderLayout.CENTER)
            toolTipText = tooltip
        }
        val labelOf = { item.getComponent(0) as? JBLabel }

        item.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
                if (feedback == null) return
                val l = labelOf() ?: return
                l.text = feedback
                l.foreground = UiKit.ok
                javax.swing.Timer(1200) {
                    l.text = label
                    l.foreground = UiKit.subtle
                    l.revalidate()
                }.apply { isRepeats = false }.start()
            }

            override fun mouseEntered(e: MouseEvent) {
                labelOf()?.foreground = UiKit.link
            }

            override fun mouseExited(e: MouseEvent) {
                val l = labelOf()
                if (l?.text == label) l.foreground = UiKit.subtle
            }
        })
        return item
    }

    /**
     * 用平台编辑器渲染代码块（带语法高亮）。
     *
     * 为什么用它而不是自绘：IDE 的编辑器内核自带高亮、缩进与配色方案，
     * 效果和用户在主编辑器里看到的**完全一致** —— 自己写高亮器不可能对齐主题，
     * 而且每换一次主题就得跟着改。
     *
     * 代价与兜底（三条都是必须的）：
     *  - 每个块都是一个真编辑器，比较重 → 单条消息只给前 [MAX_HIGHLIGHTED_BLOCKS] 个块上高亮；
     *  - 需要 project，没有就返回 null，调用方回落纯文本；
     *  - 高度按行数算并封顶，超出在卡片内部滚动（不封顶的话长代码会把气泡撑爆）。
     */
    private fun createHighlightedCode(block: CodeBlockSupport.Block): JComponent? {
        val p = project ?: return null
        if (highlightedBlocks >= MAX_HIGHLIGHTED_BLOCKS) return null
        return runCatching {
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByExtension(extensionOf(block.language))
            val lines = block.code.lines().size.coerceAtLeast(1)
            val field = com.intellij.ui.EditorTextField(block.code, p, fileType).apply {
                isViewer = true
                setOneLineMode(false)
                border = JBUI.Borders.empty(6, 8)
                preferredSize = Dimension(
                    0,
                    (lines * JBUI.scale(18) + JBUI.scale(18)).coerceAtMost(JBUI.scale(340))
                )
            }
            highlightedBlocks++
            JBScrollPane(field).apply {
                // 滚动条按需出现即可，**不加边框** —— 再画一层就是「框里套框」
                setBorder(JBUI.Borders.empty())
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                isOpaque = false
                viewport.isOpaque = false
            }
        }.getOrNull()
    }

    /**
     * 代码围栏的语言名 → 文件扩展名。
     *
     * 用扩展名去问 FileTypeManager 最稳：不直接引用任何语言插件类
     * （引了就得在 plugin.xml 里声明依赖，用户没装那个插件时会加载失败）。
     */
    private fun extensionOf(language: String): String = when (language.trim().lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "gradle", "groovy" -> "gradle"
        "yaml", "yml" -> "yml"
        "js", "javascript" -> "js"
        "ts", "typescript" -> "ts"
        "python", "py" -> "py"
        "html" -> "html"
        "css" -> "css"
        "sh", "bash", "shell" -> "sh"
        "sql" -> "sql"
        "md", "markdown" -> "md"
        "properties" -> "properties"
        else -> "txt"
    }

    private fun buildCodeBlockCard(block: CodeBlockSupport.Block): JComponent {
        // 代码块卡片：**只铺一层底色，不画描边** ——
        // 原来这里是「圆角描边 + 内部深色代码区」，两层框线叠在一起就是
        // 「框里套框」（用户反馈）。现在改成：整块是一层圆角底色的板，代码区是
        // 板上一块更深色的区域，靠**颜色差**区分，而不是靠两条边框线。
        val card = object : JBPanel<JBPanel<*>>(BorderLayout(0, 4)) {
            init {
                isOpaque = false
                background = UiKit.code
                border = JBUI.Borders.empty(6, 8)
                alignmentX = LEFT_ALIGNMENT
            }

            override fun paintComponent(g: Graphics) {
                UiKit.paintRoundedCard(g, background, UiKit.radiusSmall, width, height)
                super.paintComponent(g)
            }
        }

        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val title = JBLabel(
            buildString {
                append(block.language.ifBlank { "代码" })
                block.suggestedPath?.let { append("  ·  ").append(it) }
            }
        ).apply {
            foreground = UiKit.subtle
            font = font.deriveFont(Font.BOLD, font.size - 1f)
        }
        header.add(title, BorderLayout.WEST)

        val actions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        actions.add(UiKit.textButton("复制", "复制到剪贴板") {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(block.code), null)
        })
        // 先建按钮再挂监听：回调里要改按钮自身状态，
        // 不能在初始化表达式里引用自己（Kotlin 会报未解析）
        val applyBtn: JButton = UiKit.textButton(
            label = "应用",
            tooltip = if (block.suggestedPath != null) {
                "写入 ${block.suggestedPath}（可 Ctrl+Z 撤销）"
            } else {
                "选择目标文件后写入"
            }
        ).apply {
            // 设置里「代码块显示应用按钮」以前也是摆设：关掉它就该真的不显示
            isEnabled = project != null
            isVisible = com.zhixueyao.settings.ZhixueyaoSettings.getInstance().showApplyButton
        }

        applyBtn.addActionListener {
            val p = project ?: return@addActionListener
            val msg = CodeBlockSupport.confirmAndApply(p, block, this)
            if (msg != null) {
                applyBtn.text = "已应用"
                applyBtn.isEnabled = false
                title.text = title.text + "  ✓"
            }
        }
        actions.add(applyBtn)
        header.add(actions, BorderLayout.EAST)
        card.add(header, BorderLayout.NORTH)

        // 代码区：优先用**平台自带的编辑器内核**渲染（语法高亮、配色与主编辑器一致），
        // 没有 project / 超过上限 / 创建失败时回落到纯文本 —— 保证一定能看到内容。
        val codeView: JComponent = createHighlightedCode(block) ?: createReadOnlyArea().apply {
            text = block.code
            font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)
            // 比卡片底色再深一档，靠颜色差形成层次（不用再画一圈边框）
            background = UiKit.assistantBubble
            foreground = UiKit.text
            border = JBUI.Borders.empty(6, 8)
            isOpaque = true
            rows = minOf(block.code.lines().size, 24)
        }
        card.add(codeView, BorderLayout.CENTER)

        return card
    }

    // ---------------- 工具调用卡片 ----------------

    /**
     * 工具调用卡片。
     *
     * 几条刻意的设计（都是被「刷屏 / 卡顿 / 简陋」反馈逼出来的）：
     *  - **同类合并**：连续调同一个工具（read_file 连着来五次）合成一张卡，标题显示 `×5`，
     *    而不是铺五张卡；
     *  - **长日志内部滚动**：详情区放在滚动容器里、最高 150px，长输出不会把卡片撑到整屏；
     *  - **执行中有动效**：`|/─\` 旋转而不是干巴巴一个「…」；
     *  - **等宽字体**：工具名是代码类文本，用等宽字体和中文状态区分开；
     *  - **圆角 + 间距 + 淡阴影**：卡片之间有 6px 间距，底沿一条极淡的阴影线把它从背景里托起来。
     */
    private class ToolCard(private val toolName: String) : JBPanel<ToolCard>(BorderLayout()) {
        private val titleLabel = JBLabel()
        private val detailArea = createDetailArea()
        private val detailScroll = JBScrollPane(detailArea).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            isOpaque = false
            viewport.isOpaque = false
            isVisible = false
        }
        private val toggle = JBLabel("详情").apply {
            foreground = UiKit.link
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(font.size - 1f)
        }
        private var running = true
        private var count = 1
        private var frame = 0

        /** 执行中的旋转动效：只在 running 时跑，结束就停（别让计时器空转） */
        private val spin = javax.swing.Timer(110) {
            frame++
            updateTitle()
        }

        init {
            // 圆角底色必须自绘，否则 background 会填成矩形
            isOpaque = false
            background = UiKit.card
            border = UiKit.cardBorder(UiKit.border, CORNER)
            alignmentX = LEFT_ALIGNMENT

            val top = JBPanel<ToolCard>(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 9, 6, 9)
            }
            top.add(titleLabel, BorderLayout.CENTER)

            val east = JBPanel<ToolCard>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
            east.add(toggle)
            top.add(east, BorderLayout.EAST)

            add(top, BorderLayout.NORTH)
            add(detailScroll, BorderLayout.CENTER)

            toggle.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val show = !detailScroll.isVisible
                    detailScroll.isVisible = show
                    if (show) {
                        // 展开时给详情区一个高度上限，长日志在卡片内部滚动
                        val pref = detailArea.preferredSize
                        detailScroll.preferredSize = Dimension(0, pref.height.coerceAtMost(150))
                    }
                    toggle.text = if (show) "收起" else "详情"
                    revalidate()
                    repaint()
                }
            })

            updateTitle()
            spin.start()
        }

        private fun createDetailArea(): JTextArea = JTextArea().apply {
            isEditable = false
            // 这里也在气泡里，文档变化同样不能拽外层滚动位置
            (caret as? javax.swing.text.DefaultCaret)?.updatePolicy =
                javax.swing.text.DefaultCaret.NEVER_UPDATE
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size - 1)
            border = JBUI.Borders.empty(0, 9, 6, 9)
            foreground = UiKit.subtle
        }

        /** 连续调用同一个工具时合并到本卡（返回 false 表示类型不同，调用方另建一张） */
        fun mergeSameTool(name: String): Boolean {
            if (name != toolName) return false
            count++
            running = true
            if (!spin.isRunning) spin.start()
            updateTitle()
            return true
        }

        private fun updateTitle() {
            val mark = if (running) SPINNER[frame % SPINNER.size] else if (failed) "✕" else "✓"
            val suffix = if (running) "执行中" else if (failed) "失败" else "完成"
            val times = if (count > 1) "  ×" + count else ""
            // 工具名用等宽：它是代码类文本，和后面的中文状态在视觉上分开
            titleLabel.text = "<html><span style='font-family:monospaced'>" +
                mark + " " + escape(toolName) + times + "</span>  ·  " + suffix + "</html>"
            titleLabel.foreground = when {
                failed -> UiKit.danger
                running -> UiKit.subtle
                else -> UiKit.text
            }
        }

        private fun escape(t: String): String =
            t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private var failed = false

        /** 停掉旋转动效。移除气泡前调，别让它一直空转 */
        fun stopAnimation() = spin.stop()

        fun setFinished(result: ToolResult) {
            running = false
            spin.stop()
            failed = !result.ok
            updateTitle()
            detailArea.text = result.text
            // 同理：用 viewport 直接定位，不用 caretPosition（会冒泡到外层把用户拽走）
            detailScroll.verticalScrollBar.value = 0
        }

        /** 圆角实底 + 底沿一条淡阴影，把卡片从背景里托起来 */
        override fun paintComponent(g: Graphics) {
            UiKit.paintRoundedCard(g, background, CORNER, width, height)
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.KEY_ANTIALIASING
                )
                g2.color = java.awt.Color(0, 0, 0, 28)
                g2.fillRoundRect(2, height - 3, (width - 4).coerceAtLeast(1), 3, CORNER, CORNER)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        private companion object {
            /** 卡片圆角（比气泡小一档，小卡片用大圆角会显得「肿」） */
            const val CORNER = 8

            /** 旋转动画的帧 —— 用 ASCII 的四帧，任何字体都能渲染 */
            val SPINNER = arrayOf("|", "/", "─", "\\")
        }
    }

    /**
     * 记一次工具调用。
     *
     * 用**调用 id** 而不是工具名做键：同名工具连续调用（或一轮里并行调用）时，
     * 按名字索引会互相覆盖 —— 前一条永远停在「执行中」，后一条直接「完成」，
     * 界面上看起来就是「同一个工具出现了两次」（用户截图里的重复卡片）。
     */
    fun addToolCard(callId: String, toolName: String, args: String) {
        clearPending()
        toolCallCount++
        runningTool = toolName

        // 同类合并：连续调同一个工具（read_file 连着五次）合成一张卡
        val last = lastToolCard
        if (last != null && last.mergeSameTool(toolName)) {
            activeToolCards[callId] = last
            updateToolsSummary()
            revalidate()
            repaint()
            return
        }

        val card = ToolCard(toolName)
        activeToolCards[callId] = card
        lastToolCard = card
        toolCardsPanel.add(card)
        toolCardsPanel.add(javax.swing.Box.createVerticalStrut(6))

        // 条目上限：Swing 没有虚拟列表，靠「只保留最近 N 张」把组件数钉死。
        // 一次长任务动辄上百次调用，全留着会把布局和绘制拖垮。
        while (toolCardsPanel.componentCount > MAX_TOOL_CARDS * 2) {
            toolCardsPanel.remove(0)
            toolCardsPanel.remove(0)
        }
        updateToolsSummary()
        revalidate()
        repaint()
    }

    fun finishToolCard(callId: String, result: ToolResult) {
        activeToolCards.remove(callId)?.setFinished(result)
        if (activeToolCards.isEmpty()) runningTool = null
        updateToolsSummary()
        revalidate()
        repaint()
    }

    /**
     * 刷新「工具调用」汇总行。
     *
     * 默认**收起**：一次回答动辄十几次工具调用，全部铺开会把正文挤到屏幕外
     * （用户截图：一整屏都是工具卡片）。汇总成一行，想看细节再点开。
     */
    private fun updateToolsSummary() {
        if (toolCallCount == 0) {
            toolsSummary.isVisible = false
            return
        }
        val expanded = toolCardsScroll.isVisible
        toolsSummary.isVisible = true
        // 进行中就说「正在做什么」，做完只留一行淡色汇总 ——
        // 对齐 WorkBuddy：操作过程是**虚字**，正常回答才是实字
        val running = runningTool
        toolsSummary.relabel(
            when {
                running != null -> "⋯ 正在执行 " + running + "…"
                expanded -> "▾ 已完成 " + toolCallCount + " 次工具调用"
                else -> "✓ 已完成 " + toolCallCount + " 次工具调用"
            }
        )
        toolsSummary.toolTipText = if (expanded) "点击收起" else "点击查看每一步调用了什么"
        toolsSummary.revalidate()
    }

    fun showError(message: String) {
        showDiagnostic(message)
    }

    /**
     * 展示一次失败的诊断信息。
     *
     * 这类文案由 [com.zhixueyao.llm.LlmErrorAdvice] 生成，固定是「结论 / 建议 / 原始信息」三段，
     * 所以不能按普通错误那样简单染红 —— 一股脑红色会让人以为插件崩了，而多数情况
     * （余额、限流、上下文过长）其实是「你调一下就能继续」。
     *
     * 因此按可恢复程度分色：可自行处理的用琥珀色，其余用错误色；标题行加粗放大，
     * 建议与原文用小一号的灰字，让用户一眼先看到「出了什么事」，再看「该怎么办」。
     */
    fun showDiagnostic(message: String) {
        val lines = message.trim().lines()
        val title = lines.firstOrNull().orEmpty()
        val body = lines.drop(1).joinToString("\n").trim()

        // 可自行恢复的错误：这类不需要用户怀疑插件本身
        val recoverable = listOf("余额", "额度", "限流", "频繁", "截断", "拦截", "超时")
            .any { title.contains(it) }
        val accent = if (recoverable) UiKit.warn else UiKit.danger

        fun html(text: String) = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        // 卡片高度动态求值：maximumSize 若在 init 里写死会锁住空 preferredSize（约 8px），
        // 内容会被压成一条缝；而高度不限又会在纵向 BoxLayout 里吸走多余空间、把卡片拉长
        val card = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.compound(
                    javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, accent),
                    JBUI.Borders.empty(6, 8)
                )
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        // 标题与正文都用「会按容器宽度折行」的组件，不用 HTML 标签：
        // HTML 的 width 是写死的像素值，窗口拉宽时它不会跟着变
        // （用户反馈「拉伸时后面的说明不跟着动」就是这类写死宽度的文本）。
        card.add(
            JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, font.size + 0.5f)
                foreground = accent
                alignmentX = LEFT_ALIGNMENT
            },
            BorderLayout.NORTH
        )
        if (body.isNotEmpty()) {
            card.add(
                createReadOnlyArea().apply {
                    text = body
                    foreground = UiKit.subtle
                    border = JBUI.Borders.emptyTop(4)
                    isFocusable = false
                },
                BorderLayout.CENTER
            )
        }
        // **必须插在操作行之前**。
        //
        // 气泡的圆角卡只画到「操作行上方」（见 paintComponent：要从底部扣掉操作行高度），
        // 也就是约定「操作行永远是内容列的最后一个子组件」。以前这里直接 add 到末尾，
        // 诊断卡就落到了操作行下面 —— 那块区域正好被 paintComponent 当成卡片外，
        // 于是卡片只画到一半，文案的下半截悬在卡片外面（还顺带让操作行夹在了中间）。
        val content = getComponent(0) as? JPanel ?: return
        val actionIdx = content.getComponentZOrder(actionsRow)
        if (actionIdx >= 0) content.add(card, actionIdx) else content.add(card)
        normalizeContentAlignment()
        revalidate()
        repaint()
    }
}
