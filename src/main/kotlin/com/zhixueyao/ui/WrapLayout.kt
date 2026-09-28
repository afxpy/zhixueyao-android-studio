package com.zhixueyao.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * 会换行的流式布局，而且**高度算得对**。
 *
 * ## 为什么要自己写一个
 *
 * 用户报的现象：「上传多张图片或者文件会被挤压成第一次上传的图片或者文件，
 * 其他上传的看不到」。
 *
 * 根因不在上传，在**布局**：标准 `FlowLayout` 计算首选高度时是按
 * **自己的当前宽度**折行的（`target.getWidth()`），而布局过程中这个宽度
 * 往往还是 0 或上一轮的旧值 —— 于是它按「全部排成一行」算出一个单行高度。
 *
 * 外面套的又是 `BoxLayout`（附件条上面还堆着产物面板、任务清单），
 * `BoxLayout` 会严格按子组件的 `maximumSize.height` 分配高度，
 * 而那个值正是 `preferredSize.height`（单行）——
 * **换行到第二排的芯片就被裁在可视区之外了**。
 *
 * ## 关键差别
 *
 * 这个实现**优先用父容器的宽度**来折行：
 * 父容器的宽度在布局早期就是已知的，不存在「等自己先被布局」的循环依赖。
 * 拿不到父容器时才退回自己的宽度，最后才用一个保守的兜底值 ——
 * 宁可多折几行（看起来松一点），也不要少算一行（**内容直接消失**）。
 */
class WrapLayout(
    private val hgap: Int = 6,
    private val vgap: Int = 4
) : FlowLayout(FlowLayout.LEFT, hgap, vgap) {

    /** 父容器也拿不到宽度时的兜底：取小一点，保证会折行而不是溢出 */
    private val fallbackWidth = 360

    override fun preferredLayoutSize(target: Container): Dimension = computeSize(target)

    override fun minimumLayoutSize(target: Container): Dimension = computeSize(target)

    /**
     * 按「可用宽度」逐行摆放并累计高度。
     *
     * 注意**跳过不可见组件**：附件条里被移除的芯片还在容器里（Swing 的 remove 是异步的），
     * 把它们算进去会让高度虚高。
     */
    private fun computeSize(target: Container): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val width = availableWidth(target)
            val usable = (width - insets.left - insets.right - hgap).coerceAtLeast(1)

            var rows = 1
            var rowWidth = 0
            var rowHeight = 0
            var totalHeight = 0
            var widestRow = 0

            for (c in target.components) {
                if (!c.isVisible) continue
                val d = c.preferredSize
                if (rowWidth > 0 && rowWidth + hgap + d.width > usable) {
                    // 这一行放不下了：结算，另起一行
                    totalHeight += rowHeight + vgap
                    widestRow = maxOf(widestRow, rowWidth)
                    rows++
                    rowWidth = d.width
                    rowHeight = d.height
                } else {
                    if (rowWidth > 0) rowWidth += hgap
                    rowWidth += d.width
                    rowHeight = maxOf(rowHeight, d.height)
                }
            }
            totalHeight += rowHeight
            widestRow = maxOf(widestRow, rowWidth)

            return Dimension(
                widestRow + insets.left + insets.right + hgap,
                totalHeight + insets.top + insets.bottom + vgap * (rows - 1).coerceAtLeast(0) + vgap * 2
            )
        }
    }

    /**
     * 可用宽度：父容器优先。
     *
     * 顺序不能反 —— 自己的宽度在首次布局时是 0，正是这次踩的坑。
     */
    private fun availableWidth(target: Container): Int {
        target.parent?.width?.takeIf { it > 0 }?.let { return it }
        target.width.takeIf { it > 0 }?.let { return it }
        return fallbackWidth
    }

    /** 当前排了几行（探针用，也方便别处判断要不要折叠） */
    fun rowsFor(target: Container): Int {
        val insets = target.insets
        val usable = (availableWidth(target) - insets.left - insets.right - hgap).coerceAtLeast(1)
        var rows = 1
        var rowWidth = 0
        for (c in target.components) {
            if (!c.isVisible) continue
            val w = c.preferredSize.width
            if (rowWidth > 0 && rowWidth + hgap + w > usable) {
                rows++
                rowWidth = w
            } else {
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += w
            }
        }
        return rows
    }

    /** 供外部（探针 / 调试）读一行最多能放多宽 */
    fun usableWidthFor(target: Container): Int =
        (availableWidth(target) - target.insets.left - target.insets.right - hgap).coerceAtLeast(1)
}

/** 小工具：给容器换成 [WrapLayout] 并保持左对齐 */
fun Container.useWrapLayout(hgap: Int = 6, vgap: Int = 4): WrapLayout {
    val l = WrapLayout(hgap, vgap)
    layout = l
    return l
}
