package com.zhixueyao.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.ui.components.JBScrollPane
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 界面视觉规范。
 *
 * 集中管理配色与常用组件，避免每个面板各写一套导致风格不一致。
 * 所有颜色都成对给出「浅色主题 / 深色主题」两个值，由 [JBColor] 按当前主题自动选择。
 *
 * 注意：本文件内的属性名（如 [border]、[text]）会与 Swing 组件的同名成员冲突，
 * 因此内部引用一律写全限定名 `UiKit.xxx`，工厂函数的参数也避免叫 text/border。
 */
object UiKit {

    // ---------------- 配色 ----------------

    /** 品牌色（止血药的红）。浅色主题下压深一点保证对比度。 */
    val brand: Color = JBColor(Color(0xC0392B), Color(0xFF6B5B))

    /** 用户消息气泡底色 */
    val userBubble: Color = JBColor(Color(0xE8F0FE), Color(0x2B3A55))

    /** AI 消息气泡底色 */
    val assistantBubble: Color = JBColor(Color(0xFAFAFA), Color(0x2B2D30))

    /** 系统/提示消息底色 */
    val systemBubble: Color = JBColor(Color(0xF4F4F4), Color(0x26282B))

    /** 卡片（工具调用、代码块）底色 */
    val card: Color = JBColor(Color(0xF7F8F9), Color(0x2E3135))

    /** 代码区底色 */
    val code: Color = JBColor(Color(0xF2F3F5), Color(0x1E1F22))

    /** 输入框底色 */
    val input: Color = JBColor(Color(0xFFFFFF), Color(0x1E1F22))

    /** 主文字 */
    val text: Color = JBColor(Color(0x1F2328), Color(0xDFE1E5))

    /** 次要文字 */
    val subtle: Color = JBColor(Color(0x6E7781), Color(0x9DA3AB))

    /** 更弱的提示文字 */
    val faint: Color = JBColor(Color(0x8C959F), Color(0x7E848C))

    /** 边框 */
    val border: Color = JBColor(Color(0xD8DCE1), Color(0x3C3F44))

    /** 成功 / 失败 */
    val ok: Color = JBColor(Color(0x1A7F37), Color(0x5FBF7F))
    val danger: Color = JBColor(Color(0xC0392B), Color(0xFF8A80))

    /** 警告色（未配置等状态） */
    val warn: Color = JBColor(Color(0x9A6700), Color(0xD4A72C))

    /** 链接色 */
    val link: Color = JBColor(Color(0x0969DA), Color(0x6CB6FF))

    /** 顶部栏底色，比面板背景略深一档，形成层次 */
    val header: Color = JBColor(Color(0xF2F3F5), Color(0x2B2D30))

    /** 悬停高亮底色 */
    val hover: Color = JBColor(Color(0xE4E8EC), Color(0x3A3D42))

    /** 品牌淡底，用于欢迎卡等需要品牌氛围的区块 */
    val brandSoft: Color = JBColor(Color(0xFDF2F0), Color(0x3A2A28))

    /** 分区标题文字色（比正文弱、比提示强） */
    val sectionLabel: Color = JBColor(Color(0x57606A), Color(0x9DA3AB))

    /**
     * 品牌色的半透明变体 —— 「默认安静、悬停显形」的关键。
     *
     * 参照 Pylon 的 `--accent-soft: color-mix(accent 14%, transparent)`：
     * 图标按钮平时完全透明，只有鼠标进入才浮出这一层淡品牌底。
     * 这一招让一排按钮在任何主题下都不显得「灰扑扑一堆方块」。
     *
     * 注意：这些函数在**绘制时**才求值，此时 [brand] 已由 JBColor 解析成
     * 当前主题的实际颜色，所以深浅主题都能拿到正确的色相。
     * 不能用 JBColor 包装带 alpha 的颜色 —— JBColor 内部走 ColorUIResource，
     * alpha 会被丢掉。
     */
    fun brandFade(alpha: Int = 26): Color = Color(brand.red, brand.green, brand.blue, alpha)

    /** 品牌色投影（Pylon 的 send 按钮用 `0 5px 14px accent/34%`） */
    fun brandShadow(alpha: Int = 110): Color = Color(brand.red, brand.green, brand.blue, alpha)

    /** 危险色半透明底（停止按钮的悬停态） */
    fun dangerFade(alpha: Int = 32): Color = Color(danger.red, danger.green, danger.blue, alpha)

    // ---------------- 尺寸 ----------------

    const val radius: Int = 8
    const val radiusSmall: Int = 10

    /** 输入区方形按钮的尺寸（Pylon: 36×36，圆角 10） */
    const val btnSize: Int = 34
    const val btnCorner: Int = 12

    // ---------------- 边框 ----------------

    /** 圆角卡片边框。 */
    @JvmStatic
    fun cardBorder(lineColor: Color = border, corner: Int = radius): RoundedLineBorder =
        RoundedLineBorder(lineColor, 1, corner)

    // ---------------- 容器 ----------------

    /** 透明面板（继承父级背景）。 */
    fun transparent(layout: LayoutManager): JBPanel<JBPanel<*>> =
        JBPanel<JBPanel<*>>(layout).apply { isOpaque = false }

    /** 水平排列的透明行容器。 */
    fun row(gap: Int = 4, padTop: Int = 0, padBottom: Int = 0): JBPanel<JBPanel<*>> =
        JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(padTop, 0, padBottom, 0)
        }

    /** 实底圆角面板，用于顶栏、卡片等需要独立底色的区块。 */
    fun solid(bg: Color, pad: Insets = JBUI.insets(0)): JBPanel<JBPanel<*>> =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = bg
            border = JBUI.Borders.empty(pad.top, pad.left, pad.bottom, pad.right)
        }

    /** 圆角卡片容器：带底色、圆角描边、内边距。 */
    fun roundedCard(
        bg: Color = card,
        pad: Insets = JBUI.insets(8, 10),
        corner: Int = radius,
        lineColor: Color = border
    ): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = true
        background = bg
        border = JBUI.Borders.compound(
            RoundedLineBorder(lineColor, 1, corner),
            JBUI.Borders.empty(pad.top, pad.left, pad.bottom, pad.right)
        )
    }

    // ---------------- 文本 ----------------

    /**
     * 以下所有文本组件都显式声明 `alignmentX = LEFT_ALIGNMENT`。
     *
     * 这不是可有可无的修饰：Swing 的 `JComponent` 默认 `alignmentX` 是 **0.5（居中）**，
     * 而 `BoxLayout(Y_AXIS)` 在横向是按各子组件的 alignmentX 计算摆放位置的。
     * 一旦同一个纵向容器里混有 0.5 和 0.0 两种子组件，**所有子组件的位置都会被算偏**
     * （实测：一个居中对齐的气泡能把整页左对齐的内容推右几十像素，并且宽度被压窄）。
     * 表现就是「内容莫名其妙跑到右边 / 标题跑到中间」。
     *
     * 所以规则是：**凡是要放进纵向 BoxLayout 的组件，一律显式左对齐**。
     * 宁可多写一行，也不要依赖默认值 —— 默认值是居中，不是左对齐。
     */
    private fun <T : JComponent> T.leftAligned(): T = apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }

    /** 标题文字。 */
    fun title(label: String): JBLabel = JBLabel(label).leftAligned().apply {
        font = font.deriveFont(Font.BOLD, font.size + 0.5f)
        foreground = UiKit.text
    }

    /** 提示文字标签。 */
    fun hint(label: String): JBLabel = JBLabel(label).leftAligned().apply {
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.faint
    }

    /** 分组标题。 */
    fun sectionTitle(label: String): JBLabel = JBLabel(label).leftAligned().apply {
        font = font.deriveFont(Font.BOLD, font.size.toFloat())
        foreground = UiKit.text
        border = JBUI.Borders.emptyBottom(4)
    }

    /**
     * 分区小标题 —— 欢迎页那种「Quick Actions」式的分组标签。
     * 比正文弱、比提示强的灰阶，靠字重和灰度区分层级，不靠字号。
     */
    fun groupLabel(label: String): JBLabel = JBLabel(label).leftAligned().apply {
        font = font.deriveFont(Font.BOLD, font.size - 1f)
        foreground = UiKit.sectionLabel
        border = JBUI.Borders.empty(10, 0, 3, 0)
    }

    /**
     * 带图标的动作行 —— 欢迎页里「点一下就触发某个功能」的项。
     *
     * 做成整行可点（不是只有文字可点），悬停整行高亮，
     * 这是 IDE 工具窗口里的通行交互，比小链接好点得多。
     */
    /**
     * 行太窄时把右侧说明藏起来，避免和左侧标题重叠。
     *
     * 为什么需要：BorderLayout 在宽度不够时，会按各自的首选宽度摆放 WEST 与 EAST，
     * 两者在中间**重叠**（用户截图：窗口拉窄后「解释选中的代码」和「读编辑器选区」
     * 叠在一起，糊成一团）。所以布局前先算一次 —— 放不下就隐藏右侧说明，
     * 把整行让给标题；窗口拉回来它会自动重新出现。
     */
    private fun hideDetailIfTight(row: JPanel, gap: Int = 8) {
        if (row.componentCount < 2) return
        val left = row.getComponent(0)
        val right = row.getComponent(1)
        val available = row.width - row.insets.left - row.insets.right
        val fits = left.preferredSize.width + right.preferredSize.width + gap <= available
        // 只在结论变化时才写可见性：setVisible 会触发 revalidate → 再进 doLayout，
        // 每次都写等于每次都在请求重排，极端情况下会把 EDT 拖住（表现为界面卡死）
        if (right.isVisible != fits) right.isVisible = fits
    }

    /**
     * 弹出一个**可滚动**的菜单。
     *
     * 为什么需要：`JPopupMenu` 自己不会滚动 —— 项数一多（中转站动辄几十个模型）
     * 高度就超出屏幕，底部的项被裁掉且**点不到**（用户反馈「模型清单不能下滑」）。
     * 平台通行做法：把菜单塞进 JScrollPane，再放进一个占满的 JMenuItem。
     */
    fun showScrollableMenu(menu: JPopupMenu, invoker: JComponent, maxHeight: Int = 420) {
        val natural = menu.preferredSize
        if (natural.height <= maxHeight) {
            menu.show(invoker, 0, invoker.height + 2)
            return
        }
        val scroll = JBScrollPane(menu).apply {
            // 必须用 setBorder：在 UiKit 作用域里直接写 `border = ...` 会被解析成
            // UiKit.border（那个 Color），不是组件的边框
            setBorder(JBUI.Borders.empty())
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 18
            isOpaque = false
            viewport.isOpaque = false
        }
        val holder = JMenuItem().apply {
            layout = BorderLayout()
            isOpaque = false
            setBorder(JBUI.Borders.empty())
            preferredSize = Dimension(natural.width + 26, minOf(natural.height, maxHeight))
            add(scroll, BorderLayout.CENTER)
        }
        JPopupMenu().apply { add(holder) }.show(invoker, 0, invoker.height + 2)
    }

    /**
     * 给一棵子树挂「悬停 + 点击」。
     *
     * 为什么不能只挂在行容器上：**AWT 的鼠标事件不冒泡** —— 事件派发给最深的那个组件。
     * 行里放了文字标签之后，点在文字上事件就停在标签那儿，行容器的监听收不到，
     * 表现就是「点了没反应」（会话列表、选项行都踩过这个）。
     *
     * `AbstractButton` 整棵跳过：行内的按钮（删除、次级操作）有自己的动作，
     * 不能被当成「点了整行」。
     */
    fun attachRowInteractions(
        root: JComponent,
        onHover: (Boolean) -> Unit,
        onClick: () -> Unit
    ) {
        fun walk(c: java.awt.Component) {
            if (c !is javax.swing.AbstractButton) {
                c.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) = onHover(true)

                    override fun mouseExited(e: MouseEvent) {
                        // 移进子组件也会触发父组件的 exited；用「鼠标是否还在整行内」兜住，
                        // 否则整行底色会一闪一闪
                        val p = SwingUtilities.convertPoint(e.component, e.point, root)
                        if (!root.contains(p)) onHover(false)
                    }

                    override fun mouseClicked(e: MouseEvent) = onClick()
                })
            }
            if (c is java.awt.Container) for (k in c.components) walk(k)
        }
        walk(root)
    }

    /**
     * 选择题里的一个选项：整行可点，**文案自动折行**。
     *
     * 为什么不用按钮：选项文案是模型生成的，可能很长
     * （「审计万象项目源码，扫 Android 常见安全问题（组件暴露/硬编码密钥/WebView 等）」这种）。
     * 按钮不能折行，长文案只会把按钮撑成一整条大色块，又大又重
     * —— 用户反复反馈「按钮怎么这么大」。
     * 整行 + 折行就永远不会有这个问题，观感也轻得多。
     *
     * @param primary 推荐项：加一层淡品牌底 + 加粗品牌色文字
     */
    fun optionRow(label: String, primary: Boolean, onClick: () -> Unit): JComponent {
        var hovered = false
        val row = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(5, 10, 5, 8)
                toolTipText = label
            }

            override fun paintComponent(g: Graphics) {
                val fill = when {
                    hovered -> brandFade(if (primary) 44 else 30)
                    primary -> brandFade(18)
                    else -> null
                }
                paintRoundedFill(g, fill, radiusSmall, width, height)
                super.paintComponent(g)
            }

            // 高度由内部折行文本决定，别被外层拉伸（走覆写方法，不在 init 里赋值）
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val text = object : javax.swing.JTextArea(label) {
            init {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty()
                font = JBUI.Fonts.label().deriveFont(
                    if (primary) Font.BOLD else Font.PLAIN,
                    JBUI.Fonts.label().size.toFloat()
                )
                foreground = if (primary) brand else UiKit.text
                isFocusable = false
            }

            /** 跟随折行后的真实高度 */
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        row.add(text, BorderLayout.CENTER)

        // 文字是子组件，事件不会冒泡上来 —— 整棵子树都要挂
        attachRowInteractions(
            root = row,
            onHover = { h -> hovered = h; row.repaint() },
            onClick = onClick
        )
        return row
    }

    fun actionRow(
        icon: Icon?,
        label: String,
        detail: String? = null,
        onClick: () -> Unit
    ): JComponent = object : JPanel(BorderLayout(8, 0)) {
        private var hovered = false

        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            // 高度靠下方覆写的 getMaximumSize() 动态求值，不在这里设属性
            toolTipText = if (detail != null) "$label — $detail" else label

            val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            if (icon != null) left.add(JLabel(icon))
            left.add(JBLabel(label).apply {
                font = font.deriveFont(font.size.toFloat())
                foreground = UiKit.text
            })
            add(left, BorderLayout.WEST)

            if (detail != null) {
                add(JBLabel(detail).apply {
                    font = font.deriveFont(font.size - 1f)
                    foreground = UiKit.faint
                    horizontalAlignment = SwingConstants.RIGHT
                }, BorderLayout.EAST)
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

        override fun paintComponent(g: Graphics) {
            // 常态**完全透明**（就是一行文字），只在悬停时浮出一层淡底。
            //
            // 这里刻意不加常驻的底色与描边：给每一行都套个框会变成「一排方块」，
            // 用户明确要求「这里的按钮不要那个框框」。可点性由左侧图标 + 悬停反馈承担。
            paintRoundedFill(g, if (hovered) UiKit.hover else null, radiusSmall, width, height)
            super.paintComponent(g)
        }

        override fun doLayout() {
            hideDetailIfTight(this)
            super.doLayout()
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * 品牌标识块 —— 图标 + 产品名 + 一句话定位。
     * 欢迎页顶部用它建立「这是谁」的第一印象。
     */
    /**
     * 品牌标识块 —— 图标 + 产品名。
     * 欢迎页顶部用它建立「这是谁」的第一印象。
     */
    fun brandHeader(icon: Icon, name: String, tagline: String): JComponent {
        // 高度动态求值（覆写方法）而不是在 init 里写死 26 ——
        // 写死会在子组件加进来之前就把空的 preferredSize 锁成上限，
        // 大字号 / 高 DPI 下图标行会被裁掉。详见 [infoRow] 的注释。
        val row = object : JBPanel<JBPanel<*>>(BorderLayout(8, 0)) {
            init {
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        row.add(JLabel(icon), BorderLayout.WEST)
        row.add(JBLabel(name).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1.5f)
            foreground = UiKit.brand
        }, BorderLayout.CENTER)
        row.toolTipText = tagline
        return row
    }

    /**
     * 静态说明行 —— 排版与 [actionRow] 一致，但**不可点、无悬停反馈**。
     *
     * 这一点是踩过坑的：欢迎页原来把「我能做的事」也做成了 [actionRow]，
     * 用户看到悬停高亮就以为是按钮，点下去却只是把说明文字填进输入框，
     * 反馈为零，于是反馈成「很多按钮都没用」。只读的说明就该长得像说明。
     *
     * 注意 `getMaximumSize()` 必须**动态求值**（覆写方法）而**不是**在 init 里
     * 设一次 `maximumSize` 属性 —— 后者会在子组件加进来之前就把空的
     * preferredSize（约 8px）锁成最大高度，整行被压成一条缝、内容看不见。
     */
    fun infoRow(icon: Icon?, label: String, detail: String? = null): JComponent =
        object : JPanel(BorderLayout(8, 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 6)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT

                val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                if (icon != null) left.add(JLabel(icon))
                left.add(JBLabel(label).apply {
                    font = font.deriveFont(font.size.toFloat())
                    foreground = UiKit.subtle
                })
                add(left, BorderLayout.WEST)

                if (detail != null) {
                    add(JBLabel(detail).apply {
                        font = font.deriveFont(font.size - 1f)
                        foreground = UiKit.faint
                        horizontalAlignment = SwingConstants.RIGHT
                    }, BorderLayout.EAST)
                }
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

            override fun doLayout() {
                hideDetailIfTight(this)
                super.doLayout()
            }
        }

    /**
     * 可点击的链接文字。悬停时加下划线。
     *
     * 做成具名类而非匿名对象：匿名对象内部无法自引用（Kotlin 不支持 `this@object`），
     * 而回调需要把标签自身传出去，供调用方在点击后改文案。
     */
    class LinkLabel(label: String, private val onClick: (JBLabel) -> Unit) : JBLabel(label) {
        private var plain = label

        /** 置为 true 后文案固定，悬停不再改写 —— 用于「已复制」这类一次性反馈 */
        private var sticky = false

        init {
            foreground = UiKit.link
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(font.size - 1f)
            // 链接会被放进纵向 BoxLayout（如「显示思考过程」），必须左对齐
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!sticky) text = "<html><u>$plain</u></html>"
                }

                override fun mouseExited(e: MouseEvent) {
                    if (!sticky) text = plain
                }

                override fun mouseClicked(e: MouseEvent) {
                    onClick(this@LinkLabel)
                }
            })
        }

        /**
         * 固定显示新文案。
         *
         * 没有这个方法的话，点完「复制」写上去的「已复制」会被随后的
         * mouseExited 立刻改回原文案，用户根本看不到反馈。
         */
        fun setStickyText(newText: String) {
            plain = newText
            sticky = true
            text = newText
        }

        /**
         * 换一个可悬停的文案（区别于 [setStickyText] 的「一次性反馈」）。
         *
         * 必须走这个方法而不是直接赋值 `text` —— 直接赋值只改了显示，
         * 内部的 [plain] 还是旧值，鼠标一移开就会被还原成旧文案。
         * 「展开 / 收起」这种需要来回切换的链接正是这个坑。
         */
        fun relabel(newText: String) {
            plain = newText
            sticky = false
            text = newText
        }
    }

    fun linkLabel(label: String, onClick: (JBLabel) -> Unit): LinkLabel =
        LinkLabel(label, onClick)

    // ---------------- 按钮 ----------------

    /**
     * 悬停状态跟踪的公共基类。
     *
     * 抽出来是因为下面几个按钮都要「记住鼠标是否在上面」并触发重绘，
     * 之前每处各写一份 `MouseAdapter`，改起来容易漏。
     */
    private abstract class HoverButton(text: String) : JButton(text) {
        @Volatile
        protected var hovered = false

        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // 关于 border 与内边距 —— 这里有三个连环坑，实测出来的：
            //
            // 1) `isBorderPainted = false` 只是**不画**边框，`getInsets()` 依然
            //    返回边框的内边距。JButton 的 UI 算文本可用宽度用的是
            //    `width - insets`，于是那圈看不见的边距白吃宽度，文字被
            //    Swing 自己截断成「选择服...」。
            //
            // 2) `margin` 不能拿来兜底。它只有在 border 里含
            //    `BasicBorders.MarginBorder` 时才生效（那时它会叠加进 insets）。
            //    一旦把 border 换成 empty()，MarginBorder 没了，margin 立刻
            //    彻底失效 —— 设成 Insets(40,90,40,90) 尺寸也纹丝不动。
            //
            // 3) 所以内边距只能由 border 的 insets 提供，而它同时会被算进
            //    preferredSize，两边天然对齐。需要内边距的按钮（见
            //    [textButton]/[primaryButton]/[pillButton]）必须自己给一个
            //    带 insets 的空 border，不能设 margin。
            //
            // 基类默认零内边距：图标按钮尺寸写死、图标本就该铺满，多一圈反而偏。
            border = JBUI.Borders.empty()
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }
    }

    /**
     * 画一层圆角底色。[fill] 为 null 表示不画。
     *
     * 尺寸必须由调用方传入 —— 本函数是 `object` 的成员，
     * 作用域里没有 `width`/`height`（那是按钮实例的成员）。
     */
    /** 自绘圆角实底。公开是因为弹出菜单里的自绘行（会话列表）也要用同一套观感 */
    fun paintRoundedFill(g: Graphics, fill: Color?, corner: Int, w: Int, h: Int) {
        if (fill == null) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, w, h, corner, corner)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 画圆角实底 —— 所有「卡片式」容器（气泡、工具卡片、代码块）都该用它。
     *
     * 为什么必须有这个函数：`isOpaque = true` 时 Swing 用 `background` 填的是**矩形**，
     * 而 `RoundedLineBorder` 只负责描边。两者叠加的结果是「圆角描边 + 方形底色」，
     * 看起来仍然是个方框 —— 用户反馈「对话框都是矩形的」正是这个原因。
     *
     * 用法：组件设 `isOpaque = false`，在 `paintComponent` 里先调本方法再 `super`。
     */
    fun paintRoundedCard(g: Graphics, fill: Color, corner: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            // 减 1 像素：描边压在边界上，填满会把边线盖掉一半
            g2.fillRoundRect(0, 0, (w - 1).coerceAtLeast(1), (h - 1).coerceAtLeast(1), corner * 2, corner * 2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 圆角描边（只画线，不填充）—— ghost 按钮的骨架。
     *
     * 为什么需要它：按钮**必须一眼看出是按钮**。之前 [textButton] 只在悬停时才浮出底色，
     * 结果是「平时像一行纯文字、鼠标一移上去突然冒出个方块」，用户反馈「像贴上去的、
     * 悬停很反常」。常态给一圈淡描边，悬停只是加深，才符合直觉。
     */
    fun paintRoundedOutline(g: Graphics, color: Color, corner: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(1f)
            g2.drawRoundRect(0, 0, (w - 1).coerceAtLeast(1), (h - 1).coerceAtLeast(1), corner * 2, corner * 2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 图标按钮 —— 工具栏里的小按钮统一走这里。
     *
     * 参照 Pylon 的 `.input-btn.attach`：**默认完全透明**，
     * 鼠标进入才浮出一层品牌色淡底，图标同时由灰转亮。
     * 「安静 → 显形」的对比比一直挂着灰底干净得多。
     */
    fun iconButton(icon: Icon, tooltip: String, pad: Int = 1, onClick: () -> Unit): JButton =
        object : HoverButton("") {
            init {
                this.icon = icon
                border = JBUI.Borders.empty(pad)
                toolTipText = tooltip
                addActionListener { onClick() }
            }

            override fun paintComponent(g: Graphics) {
                // 悬停底色比卡片圆角更小、更淡：图标按钮的块一旦和卡片一样圆、
                // 颜色又重，就会比图标本身大一圈，显得笨重（用户反馈「有点大了」）
                // 悬停块就按图标大小画：框比图标大一圈会显得像「格子填充」
                paintRoundedFill(
                    g,
                    if (hovered && isEnabled) brandFade(26) else null,
                    8,
                    width,
                    height
                )
                super.paintComponent(g)
            }
        }

    /**
     * 输入区方形图标按钮 —— 上传 / 停止 / 发送共用这一种外形。
     *
     * 参照 Pylon 的 composer 按钮：36×36、圆角 10、图标居中。
     * 三种语义靠 [variant] 区分外观：
     *
     *  - [Variant.QUIET]  上传：透明 → 悬停品牌淡底
     *  - [Variant.DANGER] 停止：透明 + 危险色图标 → 悬停危险淡底
     *  - [Variant.BRAND]  发送：品牌实底 + 彩色投影 → 悬停提亮并上浮
     *
     * 发送按钮的「品牌实底 + 同色投影 + 悬停上浮 1px」直接来自 Pylon：
     * 那一圈同色投影是让实心按钮显得「有光」而不是「贴了块色块」的关键。
     */
    enum class Variant { QUIET, DANGER, BRAND }

    fun squareButton(
        icon: Icon,
        tooltip: String,
        variant: Variant = Variant.QUIET,
        onClick: () -> Unit
    ): JButton = object : HoverButton("") {
        init {
            this.icon = icon
            preferredSize = Dimension(btnSize, btnSize)
            minimumSize = Dimension(btnSize, btnSize)
            maximumSize = Dimension(btnSize, btnSize)
            toolTipText = tooltip
            if (variant == Variant.DANGER) foreground = danger
            addActionListener { onClick() }
        }

        override fun paintComponent(g: Graphics) {
            when (variant) {
                Variant.BRAND -> paintBrandSend(g)
                Variant.DANGER ->
                    paintRoundedFill(g, if (hovered && isEnabled) dangerFade(34) else null, btnCorner, width, height)
                Variant.QUIET ->
                    paintRoundedFill(g, if (hovered && isEnabled) brandFade(30) else null, btnCorner, width, height)
            }
            super.paintComponent(g)
        }

        /** 品牌实底 + 同色投影 + 悬停上浮。 */
        private fun paintBrandSend(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                // 悬停时整体上移 1px，做出「浮起来」的手感
                val lift = if (hovered && isEnabled) 1 else 0
                val x = 2
                val y = 2 - lift
                val w = width - 4
                val h = height - 4

                if (!isEnabled) {
                    // 禁用态：去掉投影，底色压成中性灰，箭头也随之变淡。
                    //
                    // 原来的禁用色偏深（#C8CDD2），实底仍像「一个能按的按钮」，
                    // 用户反馈「输入框空的时候发送按钮居然是亮的」正在于此 ——
                    // 问题不在逻辑而在观感：它看起来还是可点的。
                    g2.color = JBColor(Color(0xE3E6E9), Color(0x3C3F44))
                    g2.fillRoundRect(x, y, w, h, btnCorner, btnCorner)
                    // 箭头改画灰色 —— 默认图标是白的，压在浅灰底上几乎看不见
                    g2.color = JBColor(Color(0xA0A6AD), Color(0x7E848C))
                    g2.stroke = java.awt.BasicStroke(
                        1.9f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND
                    )
                    val cx = width / 2f
                    val cy = height / 2f
                    g2.drawLine((cx).toInt(), (cy - 6).toInt(), (cx).toInt(), (cy + 6).toInt())
                    g2.drawLine((cx - 5).toInt(), (cy - 1).toInt(), (cx).toInt(), (cy - 6).toInt())
                    g2.drawLine((cx).toInt(), (cy - 6).toInt(), (cx + 5).toInt(), (cy - 1).toInt())
                    return
                }

                // 同色投影：Pylon 的 box-shadow 0 5px 14px accent/34%
                // 用两层递减 alpha 模拟柔化，避免真做高斯模糊（Swing 里太贵）
                val shadow = brandShadow(if (hovered) 88 else 62)
                g2.color = shadow
                g2.fillRoundRect(x, y + 3, w, h, btnCorner, btnCorner)
                g2.color = Color(brand.red, brand.green, brand.blue, 34)
                g2.fillRoundRect(x - 1, y + 5, w + 2, h, btnCorner + 2, btnCorner + 2)

                // 主面：悬停提亮 12%
                g2.color = if (hovered) {
                    Color(
                        (brand.red + (255 - brand.red) * 12 / 100),
                        (brand.green + (255 - brand.green) * 12 / 100),
                        (brand.blue + (255 - brand.blue) * 12 / 100)
                    )
                } else {
                    brand
                }
                g2.fillRoundRect(x, y, w, h, btnCorner, btnCorner)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * 带文字的轻量按钮，悬停时淡底高亮。
     *
     * [onClick] 可以省略 —— 调用方有时需要在建好按钮之后再挂监听
     * （比如回调里要改按钮自身状态，就不能在初始化表达式里引用自己）。
     */
    fun textButton(
        label: String,
        tooltip: String? = null,
        onClick: (() -> Unit)? = null
    ): JButton =
        object : HoverButton(label) {
            init {
                font = font.deriveFont(font.size - 1f)
                // 内边距走 border 而不是 margin —— margin 依赖 border 里的
                // MarginBorder，会被 HoverButton 清 border 的动作一并干掉。
                // 详见 HoverButton 里的注释。
                border = JBUI.Borders.empty(4, 12)
                if (tooltip != null) toolTipText = tooltip
                if (onClick != null) addActionListener { onClick() }
            }

            override fun paintComponent(g: Graphics) {
                // 常态就有淡底 + 圆角描边：文字按钮也得看得出是按钮。
                // 之前只在悬停时浮出底色，用户的原话是「像是贴在那里的、悬停很反常」。
                paintRoundedFill(
                    g,
                    if (hovered && isEnabled) hover else card,
                    radiusSmall,
                    width,
                    height
                )
                paintRoundedOutline(g, UiKit.border, radiusSmall, width, height)
                super.paintComponent(g)
            }
        }

    /**
     * 主按钮 —— 文字形态的品牌实心按钮（「选择服务商」这类引导操作）。
     *
     * 与 [squareButton] 的 BRAND 变体保持同一套视觉语言：
     * 品牌实底、同色投影、悬停提亮。区别只在这里是文字而非图标。
     */
    fun primaryButton(label: String, onClick: () -> Unit): JButton =
        object : HoverButton(label) {
            init {
                font = font.deriveFont(Font.BOLD, font.size.toFloat())
                // 同 textButton：内边距必须由 border 承载，margin 会被清 border 搞失效
                border = JBUI.Borders.empty(6, 18)
                foreground = Color.WHITE
                addActionListener { onClick() }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val lift = if (hovered && isEnabled) 1 else 0
                    val y = -lift

                    if (isEnabled) {
                        g2.color = brandShadow(if (hovered) 128 else 96)
                        g2.fillRoundRect(0, y + 2, width, height, radiusSmall, radiusSmall)
                    }

                    g2.color = when {
                        !isEnabled -> JBColor(Color(0xC8CDD2), Color(0x4A4D52))
                        hovered -> Color(
                            (brand.red + (255 - brand.red) * 12 / 100),
                            (brand.green + (255 - brand.green) * 12 / 100),
                            (brand.blue + (255 - brand.blue) * 12 / 100)
                        )
                        else -> brand
                    }
                    g2.fillRoundRect(0, y, width, height, radiusSmall, radiusSmall)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }

    /**
     * 胶囊按钮 —— 顶栏的模型切换器用它。
     * 外形是圆角描边 + 左侧文字 + 右侧小箭头。
     */
    fun pillButton(
        label: String,
        icon: Icon? = arrowDown,
        tooltip: String? = null,
        onClick: () -> Unit
    ): JButton = object : JButton(label, icon) {
        private var hovered = false

        init {
            isFocusable = false
            font = font.deriveFont(font.size - 1f)
            foreground = UiKit.text
            horizontalTextPosition = SwingConstants.LEFT
            iconTextGap = 6
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            // 同一个坑：border 既要不画图、又必须提供 insets 当内边距。
            // 用 empty(3,10) 而不是 margin —— margin 在 border 被替换后就失效了。
            border = JBUI.Borders.empty(3, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (tooltip != null) toolTipText = tooltip
            addActionListener { onClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create()
            try {
                val h = height
                g2.color = if (hovered && isEnabled) UiKit.hover else Color(0, 0, 0, 0)
                g2.fillRoundRect(0, 0, width, h, h, h)
                g2.color = UiKit.border
                g2.drawRoundRect(0, 0, width - 1, h - 1, h, h)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    // ---------------- 徽标与指示 ----------------

    /** 徽标（小标签），用于显示 MCP 工具数等状态信息。 */
    fun badge(label: String, fg: Color = subtle, bg: Color? = null, onClick: (() -> Unit)? = null): JBLabel =
        object : JBLabel(label) {
            private var hovered = false

            init {
                font = font.deriveFont(font.size - 1f)
                foreground = fg
                border = JBUI.Borders.empty(2, 8)
                isOpaque = false
                if (onClick != null) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            hovered = true
                            repaint()
                        }

                        override fun mouseExited(e: MouseEvent) {
                            hovered = false
                            repaint()
                        }

                        override fun mouseClicked(e: MouseEvent) = onClick()
                    })
                }
            }

            override fun paintComponent(g: Graphics) {
                val fill = when {
                    hovered -> UiKit.hover
                    bg != null -> bg
                    else -> null
                }
                if (fill != null) {
                    val g2 = g.create()
                    try {
                        g2.color = fill
                        g2.fillRoundRect(0, 0, width, height, radiusSmall, radiusSmall)
                    } finally {
                        g2.dispose()
                    }
                }
                super.paintComponent(g)
            }
        }

    /**
     * 状态圆点，用于表示连接状态（已配置 / 未配置 / 运行中）。
     * 颜色可随时通过 [StatusDot.color] 修改并自动重绘。
     */
    class StatusDot(private var dotColor: Color, private val dotSize: Int = 8) : JComponent() {

        var color: Color
            get() = dotColor
            set(value) {
                dotColor = value
                repaint()
            }

        init {
            preferredSize = Dimension(dotSize, dotSize)
            minimumSize = Dimension(dotSize, dotSize)
            maximumSize = Dimension(dotSize, dotSize)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = dotColor
                g2.fillOval(0, (height - dotSize) / 2, dotSize, dotSize)
            } finally {
                g2.dispose()
            }
        }
    }

    fun statusDot(color: Color, size: Int = 8): StatusDot = StatusDot(color, size)

    // ---------------- 分隔线 ----------------

    /** 水平分隔线。 */
    fun separator(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        preferredSize = Dimension(1, 1)
        border = JBUI.Borders.customLineTop(UiKit.border)
    }

    /** 竖直分隔线，用于顶栏。 */
    fun vSeparator(height: Int = 16): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        preferredSize = Dimension(1, height)
        minimumSize = Dimension(1, height)
        maximumSize = Dimension(1, height)
        border = JBUI.Borders.customLineRight(UiKit.border)
    }

    // ---------------- 图标快捷方式 ----------------

    val gear: Icon get() = AllIcons.General.GearPlain
    val refresh: Icon get() = AllIcons.Actions.Refresh
    val stop: Icon get() = AllIcons.Actions.Suspend
    val close: Icon get() = AllIcons.General.InlineClose
    val add: Icon get() = AllIcons.General.Add
    val remove: Icon get() = AllIcons.General.Delete
    val upload: Icon get() = AllIcons.Actions.Upload
    val copy: Icon get() = AllIcons.General.Copy
    val edit: Icon get() = AllIcons.General.Inline_edit
    val check: Icon get() = AllIcons.General.InspectionsOK
    val arrowDown: Icon get() = AllIcons.General.ArrowDown
    val arrowDownSmall: Icon get() = AllIcons.General.ArrowDownSmall

    /** 回形针 —— 输入区「上传附件」用，语义比上传箭头更贴切 */
    val attach: Icon get() = AllIcons.Actions.Attach

    /** 向上箭头 —— 发送按钮（Pylon 同款语义：把内容推上去） */
    val sendUp: Icon get() = AllIcons.General.ArrowUp

    // 欢迎页动作行与工具栏用到的图标
    val search: Icon get() = AllIcons.Actions.Find
    val compile: Icon get() = AllIcons.Actions.Compile
    val run: Icon get() = AllIcons.Actions.Execute
    val file: Icon get() = AllIcons.Actions.ListFiles
    val folder: Icon get() = AllIcons.Actions.Show
    val help: Icon get() = AllIcons.Actions.Help
    val warning: Icon get() = AllIcons.General.Warning
    val error: Icon get() = AllIcons.General.Error
    val send: Icon get() = AllIcons.Actions.Execute
    val clearAll: Icon get() = AllIcons.Actions.GC

    /** 历史会话入口（顶栏「会话」按钮）。注意 History 在 Vcs / General 下，Actions 下没有 */
    val history: Icon get() = AllIcons.Vcs.History

    /** 品牌图标（工具窗口图标，盾牌+十字） */
    val brandIcon: Icon by lazy {
        com.intellij.openapi.util.IconLoader.getIcon("/icons/toolwindow.svg", UiKit::class.java)
    }
}

/**
 * 纵向堆叠布局：横向**一律**「x = 容器内左边、宽 = 容器内宽」。
 *
 * 为什么不直接用 `BoxLayout(Y_AXIS)`：
 * 它的横向位置是按每个子组件的 `alignmentX` 算的，而 `JComponent` 的默认值是
 * **0.5（居中）**。只要同一个容器里混进一个没显式设过 alignmentX 的组件，
 * 其余组件就会被整体推右、并且被压窄到约 2/3。离线真值表实测（容器宽 800、
 * 三个子组件、只有中间一个是 0.5）：
 * ```
 * [x=266 w=534 a=0.0] [x=0 w=800 a=0.5] [x=266 w=534 a=0.0]
 * ```
 * 偏移量 ≈ 容器宽 / 3。而气泡**按当前宽高画圆角卡**，子组件却停在被推右的位置 ——
 * 看起来就是「框是正常的，里面内容整体挤在右半边」。
 *
 * 自己写规则就没有这种隐式约定：横向位置永远是确定的 0，跟任何组件的
 * alignmentX / minimumSize 取值都无关。
 */
class StackLayout : java.awt.LayoutManager {

    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) = Unit

    override fun removeLayoutComponent(comp: java.awt.Component?) = Unit

    override fun preferredLayoutSize(parent: java.awt.Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: java.awt.Container): Dimension = measure(parent)

    /**
     * 量尺寸：**按当前宽度实算，但把结果缓存起来**。
     *
     * 这里踩过两个坑，两个都要避开：
     *
     * 1. **每次调用都实排一遍** → `getPreferredSize()` 会被 Swing 反复调用，
     *    每次排一遍全部子组件就是 O(调用次数 × 子组件数)，工具卡片一多就卡。
     * 2. **改成累加「上一次布局的高度」** → 高度会**滞后一拍**：内容变了但还没重排时，
     *    首选高度是旧的 → 滚动条最大值忽大忽小 → 用户往上翻的滚动位置被拉回去
     *    （用户反馈「AI 回复中上下滑动会拉回到之前的位置」）。
     *
     * 正解是「实算 + 缓存」：同一个宽度、且布局没被作废时直接复用；
     * 宽度变了或 `revalidate()` 之后必须重算 —— 折行高度本来就依赖宽度。
     */
    private fun measure(parent: java.awt.Container): Dimension {
        val ins = parent.insets
        val width = (parent.width - ins.left - ins.right).coerceAtLeast(0)

        // 换了个容器（万一有人把同一个 layout 实例挂到两个面板上）→ 缓存作废
        if (cachedOwner !== parent) {
            cachedOwner = parent
            cachedSize = null
        }
        // 布局被作废（内容变了）→ 缓存不可信，必须重算
        if (!parent.isValid) cachedSize = null
        cachedSize?.let { if (cachedWidth == width) return it }

        val fresh = if (width > 0) {
            // 排一层，拿到子组件在当前宽度下的真实高度
            layoutContainer(parent)
            var h = 0
            for (c in parent.components) if (c.isVisible) h += c.height
            Dimension(width + ins.left + ins.right, h + ins.top + ins.bottom)
        } else {
            // 宽度还没定（首次）：退回累加首选高度，下一轮就准了
            var w = 0
            var h = 0
            for (c in parent.components) {
                if (!c.isVisible) continue
                val d = c.preferredSize
                if (d.width > w) w = d.width
                h += d.height
            }
            Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom)
        }
        cachedSize = fresh
        cachedWidth = width
        return fresh
    }

    /** 上次量出来的首选尺寸 + 对应的宽度（见 [measure]） */
    private var cachedSize: Dimension? = null
    private var cachedWidth = -1
    private var cachedOwner: java.awt.Container? = null

    override fun layoutContainer(parent: java.awt.Container) {
        val ins = parent.insets
        val width = (parent.width - ins.left - ins.right).coerceAtLeast(0)
        var y = ins.top
        for (c in parent.components) {
            if (!c.isVisible) continue
            // ① 先定宽，并给一个**够大的临时高度**。
            //
            //    高度不能只给 1：行容器一扣内边距就成了负数，里面的折行文本拿不到有效尺寸，
            //    `BasicTextUI.getPreferredSize` 里「宽高都有效才按新宽度重排」那个分支
            //    进不去，量出来的还是旧值（实测 47 字的选项算出 778px 高）。
            //    给足高度之后它才会按新宽度重排，高度也就对了。
            val tempH = maxOf(c.height, c.preferredSize.height, JBUI.scale(24))
            c.setBounds(ins.left, y, width, tempH)
            // ② 子容器先按新宽度排一遍内部：只给宽度还不够，行里的折行文本要等
            //    它自己的布局跑完才知道多高。
            if (c is java.awt.Container) c.doLayout()
            // ③ 再问高
            val min = c.minimumSize.height
            val max = c.maximumSize.height.coerceAtLeast(min)
            val h = c.preferredSize.height.coerceIn(min, max)
            c.setBounds(ins.left, y, width, h)
            y += h
        }
    }
}

/*
 * 用在：MessageBubble 的内容列（气泡内部）、ChatPanel 的选项列表。
 * 凡是「纵向堆叠 + 每行撑满宽度」的地方都该用它，别用 BoxLayout(Y_AXIS)。
 */

