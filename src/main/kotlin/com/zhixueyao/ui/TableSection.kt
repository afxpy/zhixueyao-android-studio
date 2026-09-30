package com.zhixueyao.ui

import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable

/**
 * 设置页里「说明 + 表格（限高可滚） + 工具栏」的固定布局。
 *
 * ## 为什么需要它（用户反馈过两次）
 *
 * 原话：「怎么没看到删除按钮，可能是你的列表框不是一个可滑动的框框，所以导致按钮都在很下面，
 * 这非常不方便」；「插件这里也是列表框太长了导致的，要我一直滑很久下去才可以看到按钮，
 * 但是这非常不方便，万一我选错了但是没注意怎么办？」
 *
 * 根因是**表格被放在 `BorderLayout.CENTER`** —— 那个位置会把组件拉满剩余空间，
 * `preferredSize` / `maximumSize` 全都不起作用。
 * （所以之前那段「用匿名类覆写 `getPreferredSize` 限高」的写法是无效的：
 * 表格照样撑满，把下面的按钮顶到屏幕外。）
 *
 * 正确做法：把表格放进**固定高度**的滚动容器，整块放在 `NORTH`
 * （按 preferredSize 排布，不会被拉伸），剩下的空间用 glue 吃掉。
 * 这样无论表里 1 行还是 200 行，工具栏**永远就在表格正下方**。
 *
 * 顺带一个好处：表格自己滚（而不是整页滚），按钮位置就不会随数据量漂移 ——
 * 这正是用户担心的「万一我选错了但是没注意」。
 */
object TableSection {

    /** 表格滚动区默认高度：约放得下 8 行，再长就表格内部滚 */
    const val DEFAULT_HEIGHT = 220

    /**
     * 构造整块。
     *
     * 返回的组件**必须放进 `BorderLayout.NORTH`**（或 BoxLayout 里按 preferredSize 排布），
     * 放 CENTER 就又被拉满了 —— 那正是要修的毛病。
     */
    fun panel(
        table: JTable,
        toolbar: JComponent? = null,
        header: JComponent? = null,
        height: Int = DEFAULT_HEIGHT
    ): JComponent {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.isOpaque = false
        box.alignmentX = Component.LEFT_ALIGNMENT

        /**
         * 加一个间距，**并且把它也左对齐**。
         *
         * ## 这个 helper 是用户报的布局 bug 的修复
         *
         * `Box.createVerticalStrut()` 返回的是 `Filler`，**它的 `alignmentX` 默认是 0.5（居中）**。
         * 而纵向 `BoxLayout` 的对齐规则是：**混用不同的 alignmentX 时，
         * 整块内容会被按最大/中间那个基准推偏** ——
         * 于是「左对齐的 header + 左对齐的表格」被一个居中的 6px 间距**整体推到了右边**。
         *
         * 症状就是用户截图里的：**表格只占右半边，左边空一大片**，
         * 而同页面里没经过 BoxLayout 的按钮行却正常贴在左边。
         *
         * ## 为什么值得单独写个函数
         *
         * 这个坑**只要有人加一行 `Box.createVerticalStrut(...)` 就会重现** ——
         * 而它看起来完全无害（谁会怀疑一个间距？）。
         * 包成函数之后，「加间距」这件事**只有一个正确写法**，
         * 下一个加间距的人不会有机会踩到它。
         */
        fun gap(h: Int) {
            // 直接用共用实现 —— 这正是不该在这里手写第二份的理由：
            // 我第一版在这里手写的 apply 就写错了（Component 上没有 alignmentX），
            // 而 UiKit.strut 里那行是能编译的。
            box.add(UiKit.strut(h))
        }

        header?.let {
            it.alignmentX = Component.LEFT_ALIGNMENT
            box.add(it)
            gap(6)
        }

        box.add(
            JBScrollPane(table).apply {
                border = UiKit.cardBorder()
                // **固定高度** —— 整件事的关键。不固定就又变成「无限长列表」了。
                preferredSize = Dimension(0, height)
                minimumSize = Dimension(0, height)
                maximumSize = Dimension(Int.MAX_VALUE, height)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )

        toolbar?.let {
            gap(8)
            // toolbar 原来也没设 —— 同一个错，只是它在两张截图里恰好看不出来
            it.alignmentX = Component.LEFT_ALIGNMENT
            box.add(it)
        }
        return box
    }

    /**
     * 把底部内容（按钮行、说明文字等）包成「靠上排 + 剩余空间吃掉」的一块，
     * 配合 `BorderLayout` 的 CENTER 使用。
     */
    fun bottom(content: JComponent, gap: Int = 10): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = javax.swing.BorderFactory.createEmptyBorder(gap, 0, 0, 0)
        add(content, BorderLayout.NORTH)
    }
}
