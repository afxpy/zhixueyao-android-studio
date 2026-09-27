package com.zhixueyao.ui

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.zhixueyao.settings.ZhixueyaoSettings
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * 头像。
 *
 * 两条设计取舍：
 *
 *  1. **一律圆形**。方形头像挨着圆角气泡会显得「块状」，圆形和气泡是同一套视觉语言；
 *     而且圆形头像天然对称，左右分侧（助手左 / 用户右）时两边看起来是平衡的。
 *  2. **内置头像不引入图片资源**，用「圆形底 + 中心单字」现画。比塞一堆 PNG 更轻，
 *     自动适配深浅主题，也自动跟随高 DPI（按逻辑像素画，由 JBR 的缩放接管）。
 *     只有用户自定义头像才读外部图片。
 *
 * 缓存：同一 (预设/文件, 尺寸) 只画一次。设置里换头像时调 [invalidate] 清掉。
 */
object Avatars {

    private val log = Logger.getInstance(Avatars::class.java)

    /** 内置头像。[glyph] 是画在圆心里的字，一个字符最稳（两个字符会挤） */
    data class Preset(val id: String, val label: String, val glyph: String, val color: Color)

    /**
     * AI 头像候选。
     *
     * 第一项是**默认**：直接用插件自己的图标（`toolwindow.svg`），
     * 和工具窗口标题栏、欢迎页上的品牌标识是同一个图形 —— 用户一眼就认得出「这是止血药」。
     * 其余是圆形字标，供不喜欢默认图标的人换。
     */
    val aiPresets: List<Preset> = listOf(
        Preset("brand-icon", "止血药图标", "", UiKit.brand),
        Preset("brand", "品牌红", "药", UiKit.brand),
        Preset("blue", "科技蓝", "AI", UiKit.link),
        Preset("green", "青竹绿", "AI", UiKit.ok),
        Preset("violet", "沉静紫", "AI", Color(0x8B5CF6)),
        Preset("amber", "暖橙", "AI", Color(0xD97706)),
        Preset("slate", "石墨灰", "AI", Color(0x64748B))
    )

    /** 用户头像候选（也可以选一张本地图片，存成 `file:路径`） */
    val userPresets: List<Preset> = listOf(
        Preset("u-blue", "蓝", "我", UiKit.link),
        Preset("u-green", "绿", "我", UiKit.ok),
        Preset("u-violet", "紫", "我", Color(0x8B5CF6)),
        Preset("u-amber", "橙", "我", Color(0xD97706)),
        Preset("u-slate", "灰", "我", Color(0x64748B))
    )

    /** 自定义头像的存储前缀：`file:<绝对路径>` */
    const val FILE_PREFIX = "file:"

    const val DEFAULT_AI = "brand-icon"
    const val DEFAULT_USER = "u-blue"

    /** 头像渲染尺寸（逻辑像素） */
    const val SIZE = 22

    /** 设置页里的大号预览尺寸 */
    const val PREVIEW_SIZE = 26

    private val cache = HashMap<String, Icon>()

    fun aiPreset(id: String): Preset = aiPresets.firstOrNull { it.id == id } ?: aiPresets.first()

    fun userPreset(id: String): Preset = userPresets.firstOrNull { it.id == id } ?: userPresets.first()

    /** 预设对应的图标：glyph 为空的预设表示「用插件自己的图标」，其余画圆形字标 */
    fun presetIcon(preset: Preset, size: Int): Icon =
        if (preset.glyph.isEmpty()) UiKit.brandIcon else circleIcon(preset, size)

    /**
     * 当前生效的 AI 头像。
     *
     * 三种取值：自定义图片（`file:`）→ 读图；插件图标预设 → 用 SVG；
     * 其余预设 → 圆形字标。图片读不到就回落到默认图标，绝不出现空白框。
     */
    fun currentAiIcon(): Icon {
        val raw = ZhixueyaoSettings.getInstance().aiAvatar.ifBlank { DEFAULT_AI }
        if (raw.startsWith(FILE_PREFIX)) {
            imageIcon(raw.removePrefix(FILE_PREFIX), SIZE)?.let { return it }
        }
        return presetIcon(aiPreset(raw), SIZE)
    }

    /**
     * 当前生效的用户头像：自定义图片优先，图片读不到就回落到预设。
     *
     * 「读不到就回落」是刻意的：用户把图片删了、U 盘拔了、路径失效，
     * 界面不能出现一个空白框 —— 静默回落比报错体面得多。
     */
    fun currentUserIcon(): Icon {
        val raw = ZhixueyaoSettings.getInstance().userAvatar
        if (raw.startsWith(FILE_PREFIX)) {
            imageIcon(raw.removePrefix(FILE_PREFIX), SIZE)?.let { return it }
        }
        return circleIcon(userPreset(raw), SIZE)
    }

    /** 圆形底色 + 中心单字 */
    fun circleIcon(preset: Preset, size: Int): Icon = cache.getOrPut("p:${preset.id}:$size") {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = preset.color
            g.fill(Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat()))

            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.5f).toInt().coerceAtLeast(8))
            val fm = g.fontMetrics
            val text = preset.glyph
            // 居中：水平按实际字宽回正，垂直用 ascent 而不是 height（否则中文会偏低）
            g.drawString(text, (size - fm.stringWidth(text)) / 2f, (size + fm.ascent - fm.descent) / 2f)
        } finally {
            g.dispose()
        }
        ImageIcon(img)
    }

    /** 读本地图片并裁成圆形；失败返回 null（调用方回落预设） */
    fun imageIcon(path: String, size: Int): Icon? {
        val file = File(path)
        if (!file.isFile) return null
        val key = "f:$path:$size:${file.lastModified()}"
        cache[key]?.let { return it }
        return runCatching {
            val src = ImageIO.read(file) ?: return null
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.clip = Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
                // 居中裁成正方形再缩放，避免非等比图片被拉变形
                val side = minOf(src.width, src.height)
                val sx = (src.width - side) / 2
                val sy = (src.height - side) / 2
                g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null)
            } finally {
                g.dispose()
            }
            ImageIcon(img).also { cache[key] = it }
        }.onFailure { log.warn("读取自定义头像失败：$path", it) }.getOrNull()
    }

    /**
     * 把用户选的图片**复制**到 IDE 配置目录再用。
     *
     * 为什么不直接存原路径：用户挪走或删掉那张图，头像就变成空框。
     * 设置项要自洽 —— 复制一份进来，之后和原文件就没关系了。
     * 统一转成 PNG，避免原格式（GIF 索引色、CMYK JPEG 等）后续读取出问题。
     */
    fun importUserAvatar(source: File): String? {
        val img = ImageIO.read(source) ?: return null
        val dir = java.nio.file.Paths.get(PathManager.getConfigPath(), "zhixueyao-avatars")
        java.nio.file.Files.createDirectories(dir)
        val target = dir.resolve("user-avatar.png")
        ImageIO.write(img, "png", target.toFile())
        invalidate()
        return FILE_PREFIX + target.toString()
    }

    /** 设置变更后清缓存 —— 换了头像必须立刻看到新图，不能吃旧缓存 */
    fun invalidate() = cache.clear()
}
