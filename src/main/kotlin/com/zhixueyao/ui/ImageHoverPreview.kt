package com.zhixueyao.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Point
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.Popup
import javax.swing.PopupFactory
import javax.swing.Timer

/**
 * 鼠标悬停在缩略图上时，浮出一张大图。
 *
 * ## 为什么不用 tooltip
 *
 * Swing 的 tooltip 是 `JLabel` + HTML，图片只能走 `<img src="...">`：
 *  - `data:` URI 它不认
 *  - 剪贴板粘进来的图**根本没有文件**，没有 URL 可指
 *
 * 所以自己开一个轻量浮层（`PopupFactory`），直接画 `Image` —— 内存里的图也能显示。
 *
 * ## 交互上的两个细节
 *
 * 1. **延迟 260ms 再弹**。鼠标划过一排芯片时不该一路闪大图；
 *    停一下才说明「我想看这张」。
 * 2. **移开立刻收**。用户要的就是「移除则不显示」——
 *    浮层如果会自己赖着不走，反而挡视线。
 */
object ImageHoverPreview {

    /** 弹出前的停留时间：短了会闪，长了显得迟钝 */
    private const val DELAY_MS = 260

    /** 浮层最大尺寸（超过就等比缩小） */
    private const val MAX_W = 340
    private const val MAX_H = 260

    private var popup: Popup? = null
    private var timer: Timer? = null

    /** 当前正在显示的那张图，用来判断「还是同一张吗」 */
    private var shownFor: Any? = null

    /**
     * 安排一次预览。鼠标进入缩略图时调。
     *
     * @param key 身份标记（一般传附件对象）。同一张图重复调不会重弹，
     *            鼠标在芯片内部移动也不会闪。
     */
    fun schedule(owner: Component, image: Image, key: Any) {
        if (shownFor === key && popup != null) return   // 已经在显示这一张了
        cancelTimer()
        // 立刻收起上一张：鼠标已经移到别的芯片上了，旧图不该还在
        hide()

        val p = owner.locationOnScreen ?: return
        val size = owner.size
        timer = Timer(DELAY_MS) {
            show(owner, image, key, Point(p.x + size.width / 2, p.y))
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** 鼠标离开时调 */
    fun hide() {
        cancelTimer()
        popup?.hide()
        popup = null
        shownFor = null
    }

    private fun cancelTimer() {
        timer?.stop()
        timer = null
    }

    private fun show(owner: Component, image: Image, key: Any, anchor: Point) {
        val scaled = scaleToFit(image) ?: return
        val label = object : JLabel(javax.swing.ImageIcon(scaled)) {
            override fun paintComponent(g: Graphics) {
                // 自绘圆角底 + 细描边：浮层没有窗口装饰，靠这一圈把图和背景分开
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = UiKit.card
                g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                g2.color = UiKit.border
                g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                g2.dispose()
                super.paintComponent(g)
            }

            override fun getPreferredSize(): Dimension =
                Dimension(scaled.getWidth(null) + 10, scaled.getHeight(null) + 10)
        }
        label.isOpaque = false
        label.border = JBUI.Borders.empty(5)

        // 定位：芯片上方居中；顶上放不下就挪到下方
        val screen = owner.graphicsConfiguration?.bounds
        val w = label.preferredSize.width
        val h = label.preferredSize.height
        var x = anchor.x - w / 2
        var y = anchor.y - h - 6
        if (y < 0) y = anchor.y + owner.size.height + 6
        if (screen != null) {
            x = x.coerceIn(screen.x + 4, (screen.x + screen.width - w - 4).coerceAtLeast(screen.x))
            y = y.coerceIn(screen.y + 4, (screen.y + screen.height - h - 4).coerceAtLeast(screen.y))
        }

        popup = runCatching {
            PopupFactory.getSharedInstance().getPopup(owner, label, x, y)
        }.getOrNull()
        popup?.show()
        shownFor = key
    }

    /** 等比缩到上限之内；已经够小就原样返回（不放大，放大只会更糊） */
    private fun scaleToFit(image: Image): BufferedImage? {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val scale = minOf(MAX_W.toDouble() / w, MAX_H.toDouble() / h, 1.0)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, tw, th, null)
        g.dispose()
        return out
    }

    /** 面板销毁时收干净，免得浮层留在屏幕上 */
    fun dispose() = hide()
}
