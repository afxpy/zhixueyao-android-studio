package com.zhixueyao.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * 图片预览窗口：缩放 + 上一张 / 下一张。
 *
 * ## 为什么要有它
 *
 * 以前点产物卡片是「在编辑器中打开」——对 `.svg` 来说那就是**打开 XML 文本**，
 * 用户看到一堆 `<path d="M12 2..."/>`，等于没有预览（用户反馈：
 * 「这个怎么在编辑器里面打开这个 svg，按理来说不应该出现一个窗口来预览放大缩小之类的效果吗？
 * 还有上一张图片和下一张图片的按钮」）。
 *
 * 顺带纠正一个**错误的旧结论**：`MessageBubble.addAttachment` 的老注释写着
 * 「JDK 里没有 SVG 渲染器，硬要做预览只能写个只支持子集的渲染器，比不给更误导」。
 * 那是**按 JDK 说话，忘了平台**——IntelliJ 平台自带 SVG 渲染能力
 * （`com.intellij.ui.svg.JSvgDocument`，底下是 jsvg），渲染质量足够看。
 * 所以现在 SVG 也能真预览，而且**和 IDE 图标渲染是同一条路径**。
 *
 * ## 上一张 / 下一张的范围
 *
 * 取**同目录下的所有图片**（png/jpg/gif/bmp/webp/svg），按文件名排序，
 * 和 IDE 自带 Images 编辑器的语义一致：翻的是「这个文件夹里的图」，
 * 而不是「这次会话产出的图」——后者在对照着看素材时才更常需要跨批次翻。
 */
class ImagePreviewDialog(
    private val project: Project?,
    /** 可翻页的全部图片（按文件名排好序） */
    private val files: List<File>,
    startIndex: Int
) : DialogWrapper(project, false) {

    private var index = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))

    /** 当前已解码的图（原始像素，不缩放）；null = 还没读完或读失败 */
    private var image: BufferedImage? = null
    private var loadToken = 0
    private var failed: String? = null

    /** 缩放倍率。0 是个特殊值 = 「适应窗口」，见 [fitScale] */
    private var zoom = 0.0

    private val canvas = Canvas()
    private val scroll = JScrollPane(canvas)
    private val infoLabel = JLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.faint
    }
    private val titleLabel = JLabel("").apply { font = font.deriveFont(font.size + 0.5f) }

    init {
        title = "图片预览"
        isModal = false                 // 非模态：可以一边看图一边改代码
        setResizable(true)
        setSize(900, 680)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UiKit.input
        add(buildTopBar(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        scroll.border = UiKit.cardBorder()
        canvas.onZoomByWheel = { steps ->
            // Ctrl + 滚轮缩放；普通滚轮留给滚动（大图要能上下看）
            setZoom(baseScale() * Math.pow(1.15, steps.toDouble()))
        }
        load(index)
    }

    private fun buildTopBar(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        isOpaque = false
        border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0)

        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(infoLabel, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(UiKit.iconButton(PreviewIcons.prev(), "上一张（←）") { step(-1) })
            add(UiKit.iconButton(PreviewIcons.next(), "下一张（→）") { step(1) })
            add(Box.createHorizontalStrut(10))
            add(UiKit.iconButton(PreviewIcons.zoomOut(), "缩小（Ctrl+-）") { setZoom(baseScale() / 1.25) })
            add(UiKit.iconButton(PreviewIcons.zoomIn(), "放大（Ctrl++）") { setZoom(baseScale() * 1.25) })
            add(UiKit.iconButton(PreviewIcons.fit(), "适应窗口（空格 / Ctrl+0）") { fitToWindow() })
            add(UiKit.iconButton(PreviewIcons.actual(), "原始大小（Ctrl+1）") { setZoom(1.0) })
            add(Box.createHorizontalStrut(10))
            add(UiKit.iconButton(PreviewIcons.external(), "用系统默认程序打开") { openExternally() })
        }, BorderLayout.CENTER)
    }

    // ---------------- 载入 ----------------

    /** 解码是 CPU 活（SVG 还要栅格化，可能上百 ms），一律丢后台 */
    private fun load(at: Int) {
        val file = files.getOrNull(at) ?: return
        val token = ++loadToken
        failed = null
        image = null
        canvas.revalidate()
        canvas.repaint()
        updateChrome()

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { decode(file) }
            com.zhixueyao.ui.UiKit.ui {
                if (token != loadToken) return@ui   // 用户已经翻到别的图了
                result.onSuccess {
                    image = it
                    fitToWindowInternal()   // 换图先适应窗口，不然上一张的倍率套到这张会很怪
                }.onFailure {
                    failed = it.message ?: it.javaClass.simpleName
                }
                canvas.revalidate()
                canvas.repaint()
                updateChrome()
            }
        }
    }

    /**
     * 把一个文件读成 BufferedImage。
     *
     * SVG 走平台渲染器：先按当前需要的尺寸栅格化（**不按原始尺寸栅格化**，
     * 否则一张 2000px 的图会白占十几 MB；矢量图的意义就是按需渲染）。
     */
    private fun decode(file: File): BufferedImage {
        if (!file.exists()) throw IllegalStateException("文件不存在：${file.name}")
        val ext = file.extension.lowercase()
        if (ext == "svg") return decodeSvg(file)
        return ImageIO.read(file) ?: throw IllegalStateException("这个格式读不出来（${ext}）")
    }

    /**
     * SVG 渲染。
     *
     * 用的是平台**稳定**的那条入口 `com.intellij.util.SVGLoader`（Java 可见签名，
     * 不受 Kotlin 内部类/名字改写影响）；底层同样是 jsvg，和 IDE 画图标是同一条路径，
     * 所以渲染结果和用户在别处看到的一致。
     *
     * **两遍渲染**：先按 1:1 探一下矢量图的固有尺寸，再按「目标约 1200px」算倍率重渲。
     * 必须这么做 —— 图标类 SVG 固有尺寸常常只有 24×24，
     * 直接 1:1 渲染出来放大到窗口里就是一团马赛克，而那正是「预览」最该避免的。
     */
    private fun decodeSvg(file: File): BufferedImage {
        // 先落成 BufferedImage 再取尺寸 —— SVGLoader 返回的是 java.awt.Image，
        // 它的尺寸要 `getWidth(observer)` 才拿得到，Kotlin 里没有 `.width` 可用
        val probe = toBuffered(
            file.inputStream().use { com.intellij.util.SVGLoader.load(it, 1f) }
                ?: throw IllegalStateException("SVG 渲染失败")
        )
        val targetMax = maxOf(viewportSize().width, 640)
        val scale = (targetMax.toDouble() / maxOf(probe.width, probe.height, 1))
            .coerceIn(1.0, 40.0).toFloat()
        val img = file.inputStream().use { com.intellij.util.SVGLoader.load(it, scale) }
            ?: throw IllegalStateException("SVG 渲染失败")
        return toBuffered(img)
    }

    /** 把任意 Image 落成 BufferedImage（后面要按像素尺寸算缩放，需要确定尺寸） */
    private fun toBuffered(img: java.awt.Image): BufferedImage {
        if (img is BufferedImage) return img
        val w = img.getWidth(null).coerceAtLeast(1)
        val h = img.getHeight(null).coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    private fun viewportSize(): Dimension =
        scroll.viewport?.extentSize?.takeIf { it.width > 10 } ?: Dimension(800, 600)

    // ---------------- 缩放与翻页 ----------------

    /** 适应窗口的倍率 */
    private fun fitScale(): Double {
        val img = image ?: return 1.0
        val vp = viewportSize()
        val s = minOf(
            (vp.width - 24).toDouble() / img.width,
            (vp.height - 24).toDouble() / img.height
        )
        return s.coerceIn(0.02, 8.0)
    }

    private fun baseScale(): Double = if (zoom <= 0.0) fitScale() else zoom

    private fun fitToWindowInternal() {
        zoom = 0.0
        updateChrome()
    }

    private fun fitToWindow() {
        fitToWindowInternal()
        canvas.revalidate()
        canvas.repaint()
    }

    private fun setZoom(value: Double) {
        zoom = value.coerceIn(0.05, 8.0)
        updateChrome()
        canvas.revalidate()
        canvas.repaint()
    }

    private fun step(delta: Int) {
        if (files.isEmpty()) return
        index = (index + delta).mod(files.size)   // 循环翻页，到头再按就绕回去
        load(index)
    }

    private fun updateChrome() {
        val file = files.getOrNull(index) ?: return
        titleLabel.text = file.name
        val img = image
        val box = if (img != null) "${img.width} × ${img.height}" else "—"
        val pct = if (img != null) "${Math.round(baseScale() * 100)}%" else "—"
        val fitNote = if (zoom <= 0.0) "（适应窗口）" else ""
        infoLabel.text = buildString {
            append(box).append("　").append(pct).append(fitNote)
            append("　").append(index + 1).append("/").append(files.size)
            failed?.let { append("　读取失败：").append(it) }
        }
        title = "图片预览 · ${file.name}"
    }

    private fun openExternally() {
        val file = files.getOrNull(index) ?: return
        runCatching {
            java.awt.Desktop.getDesktop().open(file)
        }.onFailure {
            javax.swing.JOptionPane.showMessageDialog(
                canvas, "打不开：${it.message}\n\n可以试试左边的「用系统默认程序打开」",
                "图片预览", javax.swing.JOptionPane.WARNING_MESSAGE
            )
        }
    }

    // ---------------- 快捷键 ----------------

    override fun createActions(): Array<Action> {
        // 只要一个「关闭」——其余操作都在工具栏上，键盘也够用
        val close = object : AbstractAction("关闭") {
            override fun actionPerformed(e: ActionEvent) = doCancelAction()
        }
        close.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
        return arrayOf(close)
    }

    /** canvas 上的按键绑定：左右翻页、加减缩放 */
    private inner class Canvas : JComponent() {
        var onZoomByWheel: ((Int) -> Unit)? = null

        init {
            isOpaque = true
            background = UiKit.input
            border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)

            // ← → 翻页
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)) { step(-1) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)) { step(1) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0)) { step(-1) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0)) { step(1) }
            // 缩放
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK)) {
                setZoom(baseScale() * 1.25)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, java.awt.event.InputEvent.CTRL_DOWN_MASK)) {
                setZoom(baseScale() * 1.25)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK)) {
                setZoom(baseScale() / 1.25)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, java.awt.event.InputEvent.CTRL_DOWN_MASK)) {
                setZoom(baseScale() / 1.25)
            }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_0, java.awt.event.InputEvent.CTRL_DOWN_MASK)) { fitToWindow() }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_1, java.awt.event.InputEvent.CTRL_DOWN_MASK)) { setZoom(1.0) }
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)) { fitToWindow() }

            addMouseWheelListener(object : MouseAdapter() {
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (e.isControlDown) {
                        onZoomByWheel?.invoke(-e.wheelRotation)
                    } else {
                        // 非 Ctrl：交给滚动容器，别自己吃掉（否则大图没法滚）
                        parent?.parent?.dispatchEvent(e)
                    }
                }
            })
        }

        private fun bind(stroke: KeyStroke, action: () -> Unit) {
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(stroke, stroke.toString())
            actionMap.put(stroke.toString(), object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }

        /** 画布尺寸跟着缩放走，这样放进 JScrollPane 就能正常滚动 / 平移 */
        override fun getPreferredSize(): Dimension {
            val img = image ?: return Dimension(480, 320)
            val s = baseScale()
            val w = (img.width * s).toInt().coerceAtLeast(1) + 24
            val h = (img.height * s).toInt().coerceAtLeast(1) + 24
            val vp = viewportSize()
            return Dimension(maxOf(w, vp.width - 4), maxOf(h, vp.height - 4))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val img = image
            if (img == null) {
                g2.color = UiKit.faint
                val msg = failed?.let { "读取失败：$it" } ?: "加载中…"
                val fm = g2.fontMetrics
                g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, height / 2)
                return
            }

            val s = baseScale()
            val w = (img.width * s).toInt().coerceAtLeast(1)
            val h = (img.height * s).toInt().coerceAtLeast(1)
            val x = (width - w) / 2
            val y = (height - h) / 2

            // 棋盘底：透明 PNG 的透明区域要能看出来（不然和白底混在一起，像没画东西）
            drawChecker(g2, x, y, w, h)
            g2.drawImage(img, x, y, w, h, null)
        }

        private fun drawChecker(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
            val cell = 8
            val a = UiKit.input
            val b = UiKit.card
            g2.clipRect(x, y, w, h)
            var yy = y
            var row = 0
            while (yy < y + h) {
                var xx = x
                var col = 0
                while (xx < x + w) {
                    g2.color = if ((row + col) % 2 == 0) a else b
                    g2.fillRect(xx, yy, cell, cell)
                    xx += cell; col++
                }
                yy += cell; row++
            }
            g2.clip = null
        }
    }

    companion object {
        /** 预览窗口认识的图片扩展名（**含 svg** —— 平台自带渲染器，能真预览） */
        val PREVIEW_EXTS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")

        /**
         * 视频扩展名。
         *
         * **刻意不做内嵌播放**：Swing 里放视频要拖进整套解码器（ffmpeg/JCodec），
         * 为了「看一眼生成的视频」装一个几十 MB 的依赖不划算，而且拖进插件包
         * 还会拖慢 IDE 启动。这类文件直接交给**系统默认播放器**——
         * 用户本来也就是想看一眼效果。
         */
        val VIDEO_EXTS = setOf("mp4", "webm", "mov", "mkv", "avi")

        fun canPreview(file: File): Boolean = file.extension.lowercase() in PREVIEW_EXTS

        fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO_EXTS

        /** 图片或视频 —— 决定「点一下该有反应」的范围 */
        fun isMedia(file: File): Boolean = canPreview(file) || isVideo(file)

        /**
         * 用系统默认程序打开（视频、或者预览失败时的兜底）。
         *
         * @return 出错说明；null = 正常
         */
        fun openExternally(file: File): String? = runCatching {
            java.awt.Desktop.getDesktop().open(file)
            null
        }.getOrElse { "打不开 ${file.name}：${it.message}" }

        /**
         * 打开预览。
         *
         * @param gallery 左右翻页的范围。**不给就用同目录的图片**（和 IDE 自带
         *        Images 编辑器的语义一致：翻的是「这个文件夹里的图」）。
         *        会话里生成了多张图时，调用方会传**整个会话的图**进来 ——
         *        用户要的是「点开一张能左右翻着看这次生成的所有图」，
         *        而它们不一定在同一个目录（临时产物目录按会话分，但子目录可能不同）。
         * @return 打不开时给调用方一句可展示的说明（null = 正常打开了）
         */
        fun show(project: Project?, file: File, gallery: List<File> = emptyList()): String? {
            if (!file.exists()) return "文件不在了：${file.name}"
            if (!canPreview(file)) return "这个格式不支持预览：${file.extension}"
            val list = when {
                gallery.isNotEmpty() -> (gallery.filter { it.exists() && canPreview(it) } + file)
                    .distinctBy { it.absolutePath }
                    .sortedBy { it.absolutePath.lowercase() }
                else -> (file.parentFile?.listFiles() ?: emptyArray())
                    .filter { it.isFile && canPreview(it) }
                    .sortedBy { it.name.lowercase() }
            }.ifEmpty { listOf(file) }
            val at = list.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
            ImagePreviewDialog(project, list, at).show()
            return null
        }
    }
}

/**
 * 预览窗口用的图标 —— 直接画，不用图标资源文件。
 *
 * 理由：这几个形状（+ / − / 方框箭头 / 左右箭头）画出来只有十来行，
 * 而引入图标资源要额外加 SVG 文件、还要处理明暗主题两套。
 * 用 [UiKit.text] 当颜色，天然跟随主题。
 */
internal object PreviewIcons {
    private const val S = 16

    private fun icon(draw: (Graphics2D, Int) -> Unit) = object : javax.swing.Icon {
        override fun getIconWidth() = S
        override fun getIconHeight() = S
        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create(x, y, S, S) as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = if (c?.isEnabled == false) UiKit.faint else UiKit.text
            g2.stroke = java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            draw(g2, S)
            g2.dispose()
        }
    }

    /** 放大镜 + 加号 */
    fun zoomIn() = icon { g, s ->
        g.drawOval(2, 2, s - 7, s - 7)
        g.drawLine(s - 5, s - 5, s - 2, s - 2)
        g.drawLine(s / 2 - 2, (s - 3) / 2 + 1, s / 2 + 2, (s - 3) / 2 + 1)
        g.drawLine(s / 2, (s - 3) / 2 - 1, s / 2, (s - 3) / 2 + 3)
    }

    /** 放大镜 + 减号 */
    fun zoomOut() = icon { g, s ->
        g.drawOval(2, 2, s - 7, s - 7)
        g.drawLine(s - 5, s - 5, s - 2, s - 2)
        g.drawLine(s / 2 - 2, (s - 3) / 2 + 1, s / 2 + 2, (s - 3) / 2 + 1)
    }

    /**
     * 适应窗口：**四角向外撑开**的箭头。
     *
     * 原来画的是「方框 + 四角各两段短刻度」。问题是**那些刻度太小了** ——
     * 图标只有十几像素，两段 3px 的短线在高分屏上看不出来，
     * 于是整个图标看起来就是一个**空方框**，和旁边的「原始大小」几乎一样。
     *
     * 用户的原话：「适应窗口那个按钮有问题」—— 他说不出哪里有问题，
     * 因为**它看起来什么都没表达**。
     *
     * 现在改成四角朝外的斜箭头，和「原始大小」的 1:1 方框有明确区别。
     */
    fun fit() = icon { g, s ->
        val m = 3                       // 边距
        val a = 4                       // 箭头长度
        // 中间的小方框（比外框小一圈，暗示「被放进窗口里」）
        g.drawRect(s / 2 - 2, s / 2 - 2, 4, 4)
        // 四个角朝外
        g.drawLine(m, m, m + a, m); g.drawLine(m, m, m, m + a)
        g.drawLine(s - m, m, s - m - a, m); g.drawLine(s - m, m, s - m, m + a)
        g.drawLine(m, s - m, m + a, s - m); g.drawLine(m, s - m, m, s - m - a)
        g.drawLine(s - m, s - m, s - m - a, s - m); g.drawLine(s - m, s - m, s - m, s - m - a)
    }

    /**
     * 原始大小：1:1 的方框。
     *
     * 保持原样 —— 用户反馈里这是**没问题的**那一个（`1:1` 字样够明确）。
     * 记在这里是为了说明：改图标时不要顺手把好的也改了。
     */
    fun actual() = icon { g, s ->
        g.drawRect(3, 3, s - 7, s - 7)
        g.font = g.font.deriveFont(7f)
        g.drawString("1:1", 3, s - 4)
    }

    /**
     * 上一张：**单个**左尖角。
     *
     * 原来画的是「竖线 + 三角」= `|◁`，那个符号的含义是**「跳到第一张」**
     * （大多数播放器/看图工具都是这个约定）。于是按钮做的事（上一张）
     * 和它看起来要做的事（跳到开头）对不上 —— 用户根本不敢点。
     *
     * 去掉竖线就对了：单尖角 = 上一张，这是跨平台的通用画法。
     */
    fun prev() = icon { g, s ->
        g.drawLine(s - 4, 3, 4, s / 2)
        g.drawLine(4, s / 2, s - 4, s - 3)
    }

    /** 下一张：单个右尖角（理由同上，去掉原来那根竖线） */
    fun next() = icon { g, s ->
        g.drawLine(4, 3, s - 4, s / 2)
        g.drawLine(s - 4, s / 2, 4, s - 3)
    }

    /** 用系统程序打开：方框 + 出框箭头 */
    fun external() = icon { g, s ->
        g.drawRect(2, 5, s - 8, s - 7)
        g.drawLine(s - 7, 3, s - 2, 3)
        g.drawLine(s - 2, 3, s - 2, 8)
        g.drawLine(s - 7, 8, s - 2, 3)
    }
}