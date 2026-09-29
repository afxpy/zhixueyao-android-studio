package com.zhixueyao.util

import com.intellij.openapi.project.Project
import com.zhixueyao.llm.ChatMessage
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon

/**
 * 上传文件的处理。
 *
 * 分两类：
 *  - 图片 → 转 base64 作为多模态输入（需压缩，否则大图会撑爆请求体）
 *  - 文本/代码 → 直接读取内容拼进提示词，并标注文件名便于模型引用
 *  - 其他二进制 → 只告知文件名与大小，让模型知道用户提到了它
 */
object AttachmentSupport {

    const val MAX_TEXT_CHARS = 60_000
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val JPEG_QUALITY = 0.85f

    /** 单个附件的处理结果。 */
    data class Processed(
        val fileName: String,
        /** 文本形式的内容描述，用于拼进用户消息 */
        val textBlock: String,
        /** 图片数据，非图片时为 null */
        val image: ChatMessage.ImagePart? = null,
        val isImage: Boolean = false,
        /**
         * 已缩放的预览图，只用于界面展示，不参与请求。
         *
         * 为什么要留一份：剪贴板来的图片没有文件路径，
         * 而缩略图原来只能从路径读 —— 于是粘贴的截图在附件条里
         * 只能显示一个通用图标，用户看不到自己贴的是哪张。
         */
        val preview: BufferedImage? = null,
        /** 原始文本内容（未拼成 textBlock 的形式），用于折叠预览 */
        val rawText: String? = null
    ) {
        /** 字符数，用于判断是否需要折叠 */
        val charCount: Int get() = rawText?.length ?: textBlock.length
    }

    /**
     * 按「图片」处理的扩展名。
     *
     * **svg 必须在这里**：它是矢量图，模型也认它（可以当图片发）。
     * 之前漏了 —— 于是粘贴/拖拽一个 .svg 会掉进「按文本读」的分支，
     * 几千行 `<path d="...">` 原样塞进上下文（又贵又没用）。
     * 现在走图片路径：栅格化后作为视觉输入发出去。
     */
    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")
    private val textExts = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt", "toml",
        "yml", "yaml", "pro", "c", "cpp", "h", "hpp", "js", "ts", "jsx", "tsx",
        "html", "css", "scss", "sql", "sh", "bat", "py", "log", "smali", "csv", "ini", "cfg"
    )

    fun isImageFile(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase() in imageExts

    fun isTextFile(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase() in textExts

    /** 处理一个附件文件。 */
    fun process(path: Path): Processed {
        val name = path.fileName.toString()
        val ext = name.substringAfterLast('.', "").lowercase()

        if (ext in imageExts) {
            val part = runCatching { encodeImage(path) }.getOrNull()
            return if (part != null) {
                Processed(
                    name,
                    "【用户上传了图片：$name】",
                    part,
                    isImage = true,
                    preview = runCatching { readPreview(path) }.getOrNull()
                )
            } else {
                Processed(name, "【用户上传了图片 $name，但读取失败】")
            }
        }

        if (ext in textExts) {
            val content = runCatching { Files.readString(path) }.getOrNull()
            return if (content != null) {
                val truncated = content.length > MAX_TEXT_CHARS
                val body = if (truncated) content.take(MAX_TEXT_CHARS) else content
                val note = if (truncated) "\n...(文件过长已截断，共 ${content.length} 字符)" else ""
                Processed(
                    name,
                    "【用户上传的文件：$name】\n```$ext\n$body$note\n```",
                    rawText = body
                )
            } else {
                Processed(name, "【用户上传了文件 $name，但无法按文本读取】")
            }
        }

        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        return Processed(name, "【用户上传了二进制文件：$name（${formatSize(size)}），内容无法直接解析】")
    }

    /** 把多个附件拼成一段文本块。 */
    fun buildAttachmentBlock(items: List<Processed>): String =
        items.filter { !it.isImage }
            .joinToString("\n\n") { it.textBlock }

    fun collectImages(items: List<Processed>): List<ChatMessage.ImagePart> =
        items.mapNotNull { it.image }

    /**
     * 图片编码。
     *
     * 先按最长边缩放到 1568px 以内（主流多模态模型的有效分辨率上限），
     * 再转 JPEG 以控制体积 —— 直接传原图 base64 很容易超过请求体限制。
     */
    private fun encodeImage(path: Path): ChatMessage.ImagePart {
        val original = readAsImage(path)

        val scaled = scaleDown(original, MAX_IMAGE_DIMENSION)

        // 带透明通道的图转 JPEG 需要先铺白底，否则透明区域会变黑
        val rgb = BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, scaled.width, scaled.height)
        g.drawImage(scaled, 0, 0, null)
        g.dispose()

        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val ios = ImageIO.createImageOutputStream(out)
        writer.output = ios
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        writer.write(null, javax.imageio.IIOImage(rgb, null, null), params)
        ios.close()
        writer.dispose()

        val base64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return ChatMessage.ImagePart("image/jpeg", base64)
    }

    /**
     * 读成 BufferedImage。
     *
     * svg 要走平台渲染器（`ImageIO` 读不了矢量图，只会返回 null）；
     * 而且**按 2 倍固有尺寸栅格化**：矢量图的固有尺寸常常只有 24×24，
     * 按 1:1 渲染再放大就是马赛克 —— 那对「让模型看清图标」毫无帮助。
     */
    private fun readAsImage(path: Path): BufferedImage {
        if (path.fileName.toString().substringAfterLast('.', "").lowercase() == "svg") {
            val probe = path.toFile().inputStream().use {
                com.intellij.util.SVGLoader.load(it, 1f)
            } ?: throw IllegalArgumentException("SVG 渲染失败：$path")
            val intrinsic = maxOf(probe.getWidth(null), probe.getHeight(null), 1)
            // 目标：让最长边接近 MAX_IMAGE_DIMENSION，这样下面 scaleDown 基本不会再缩
            val scale = (MAX_IMAGE_DIMENSION.toDouble() / intrinsic).coerceIn(1.0, 64.0).toFloat()
            val rendered = path.toFile().inputStream().use {
                com.intellij.util.SVGLoader.load(it, scale)
            } ?: throw IllegalArgumentException("SVG 渲染失败：$path")
            return toBuffered(rendered)
        }
        return ImageIO.read(path.toFile())
            ?: throw IllegalArgumentException("无法解析图片：$path")
    }

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

    private fun scaleDown(src: BufferedImage, maxDim: Int): BufferedImage {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = minOf(maxDim.toDouble() / w, maxDim.toDouble() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        val scaled = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH)
        val result = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        g.drawImage(scaled, 0, 0, null)
        g.dispose()
        return result
    }

    /**
     * 读取图片文件的预览图（不参与请求，只用于界面）。
     *
     * 尺寸压到 96px 以内 —— 附件条上的缩略图只有 24px，
     * 留 4 倍余量供高 DPI 屏使用，同时避免把几十 MB 的原图留在内存里。
     */
    private fun readPreview(path: Path): BufferedImage? {
        val img = ImageIO.read(path.toFile()) ?: return null
        val max = 96
        if (img.width <= max && img.height <= max) return img
        val ratio = minOf(max.toDouble() / img.width, max.toDouble() / img.height)
        val w = (img.width * ratio).toInt().coerceAtLeast(1)
        val h = (img.height * ratio).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return out
    }

    /** 从剪贴板图片生成附件。 */
    fun fromClipboardImage(image: java.awt.Image): Processed? {
        return runCatching {
            val buffered = if (image is BufferedImage) image else {
                val bi = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB)
                val g = bi.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
                bi
            }
            val scaled = scaleDown(buffered, MAX_IMAGE_DIMENSION)
            val rgb = BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB)
            val g = rgb.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, scaled.width, scaled.height)
            g.drawImage(scaled, 0, 0, null)
            g.dispose()

            val out = ByteArrayOutputStream()
            ImageIO.write(rgb, "jpeg", out)
            val base64 = Base64.getEncoder().encodeToString(out.toByteArray())
            Processed(
                "剪贴板图片.png",
                "【用户粘贴了剪贴板中的截图】",
                ChatMessage.ImagePart("image/jpeg", base64),
                isImage = true,
                // 预览用原始尺寸的那张，不重复缩放
                preview = runCatching { scaledPreview(buffered) }.getOrNull()
            )
        }.getOrNull()
    }

    /** 把任意图缩到预览尺寸，供剪贴板图片用。 */
    private fun scaledPreview(src: BufferedImage): BufferedImage {
        val max = 96
        if (src.width <= max && src.height <= max) return src
        val ratio = minOf(max.toDouble() / src.width, max.toDouble() / src.height)
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(src.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return out
    }

    /** 生成附件缩略图，用于在输入区展示。 */
    fun thumbnail(path: Path, size: Int = 48): ImageIcon? = runCatching {
        val img = ImageIO.read(path.toFile()) ?: return null
        ImageIcon(scaledTo(img, size))
    }.getOrNull()

    /** 从已有的预览图生成缩略图 —— 剪贴板图片没有路径，走这个入口。 */
    fun thumbnailOf(image: BufferedImage, size: Int = 48): ImageIcon = ImageIcon(scaledTo(image, size))

    /** 等比缩放到边长 [size] 以内。 */
    private fun scaledTo(img: BufferedImage, size: Int): BufferedImage {
        val ratio = minOf(size.toDouble() / img.width, size.toDouble() / img.height)
        val w = (img.width * ratio).toInt().coerceAtLeast(1)
        val h = (img.height * ratio).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return out
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
