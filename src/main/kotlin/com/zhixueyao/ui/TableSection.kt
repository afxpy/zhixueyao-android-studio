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

        header?.let {
            it.alignmentX = Component.LEFT_ALIGNMENT
            box.add(it)
            box.add(Box.createVerticalStrut(6))
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
            box.add(Box.createVerticalStrut(8))
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
