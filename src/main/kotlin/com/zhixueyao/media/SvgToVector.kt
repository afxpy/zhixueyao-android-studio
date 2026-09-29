package com.zhixueyao.media

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SVG → Android VectorDrawable。
 *
 * 为什么值得单独做：模型生成图标时天然会写 SVG（文本、可流式、它熟），
 * 但 Android 用的是 `<vector>` 那套 XML。中间这一步手工做很烦（颜色映射、
 * viewBox 换算、基本图形转 path），做一次之后「生成图标」才是真的可用。
 *
 * 支持范围（覆盖模型实际会写的绝大部分）：
 *  - 元素：`path` `rect` `circle` `ellipse` `line` `polyline` `polygon` `g`
 *  - 属性：fill / stroke / stroke-width / linecap / linejoin / fill-rule / opacity 系列
 *  - `viewBox` → viewportWidth/Height，`width/height` → dp
 *  - `<g transform="translate/scale/rotate">` → `<group>`
 *
 * **不支持**的会明确写进 [Result.warnings]（渐变、滤镜、文本、图片、mask、clipPath）——
 * 静默丢弃是最糟的：用户会以为转换成功了，结果图少了一半。
 */
object SvgToVector {

    data class Result(
        val xml: String?,
        val warnings: List<String>,
        val widthDp: Int = 0,
        val heightDp: Int = 0
    ) {
        val ok: Boolean get() = xml != null
    }

    /** SVG 命名颜色。只收常用的；收不全的会退化成黑色并给出提示 */
    private val NAMED_COLORS = mapOf(
        "black" to "#FF000000", "white" to "#FFFFFFFF", "red" to "#FFFF0000",
        "green" to "#FF008000", "blue" to "#FF0000FF", "yellow" to "#FFFFFF00",
        "cyan" to "#FF00FFFF", "aqua" to "#FF00FFFF", "magenta" to "#FFFF00FF",
        "fuchsia" to "#FFFF00FF", "gray" to "#FF808080", "grey" to "#FF808080",
        "silver" to "#FFC0C0C0", "maroon" to "#FF800000", "olive" to "#FF808000",
        "lime" to "#FF00FF00", "teal" to "#FF008080", "navy" to "#FF000080",
        "purple" to "#FF800080", "orange" to "#FFFFA500", "pink" to "#FFFFC0CB",
        "brown" to "#FFA52A2A", "gold" to "#FFFFD700", "indigo" to "#FF4B0082",
        "violet" to "#FFEE82EE", "darkgray" to "#FFA9A9A9", "darkgrey" to "#FFA9A9A9",
        "lightgray" to "#FFD3D3D3", "lightgrey" to "#FFD3D3D3"
    )

    fun convert(svg: String, fallbackSizeDp: Int = 24): Result {
        val warnings = mutableListOf<String>()
        val doc = runCatching { parse(svg) }.getOrNull()
            ?: return Result(null, listOf("SVG 解析失败：不是合法的 XML"), 0, 0)
        val root = doc.documentElement
            ?: return Result(null, listOf("SVG 里没有根元素"), 0, 0)
        if (root.tagName.lowercase().substringAfter(':') != "svg") {
            return Result(null, listOf("根元素是 <${root.tagName}>，不是 <svg>"), 0, 0)
        }

        // ---- 尺寸与视口 ----
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        val vbW = viewBox.getOrNull(2)?.toFloatOrNull()
        val vbH = viewBox.getOrNull(3)?.toFloatOrNull()
        val wAttr = lengthValue(root.getAttribute("width"))
        val hAttr = lengthValue(root.getAttribute("height"))

        val width = vbW ?: wAttr ?: fallbackSizeDp.toFloat()
        val height = vbH ?: hAttr ?: fallbackSizeDp.toFloat()
        val outW = (wAttr ?: width).let { if (it <= 0f) fallbackSizeDp.toFloat() else it }
        val outH = (hAttr ?: height).let { if (it <= 0f) fallbackSizeDp.toFloat() else it }

        // ---- 遍历 ----
        val body = StringBuilder()
        val inherited = Style(
            fill = root.getAttribute("fill").ifBlank { "black" },
            stroke = root.getAttribute("stroke"),
            strokeWidth = root.getAttribute("stroke-width").toFloatOrNull(),
            fillRule = root.getAttribute("fill-rule"),
            lineCap = root.getAttribute("stroke-linecap").ifBlank { null },
            lineJoin = root.getAttribute("stroke-linejoin").ifBlank { null },
            fillAlpha = alphaOf(root, "fill-opacity", "opacity"),
            strokeAlpha = alphaOf(root, "stroke-opacity", "opacity")
        )
        for (child in root.childNodes.asList()) {
            if (child !is Element) continue
            convertNode(child, inherited, body, warnings, 0)
        }

        if (body.isBlank()) {
            return Result(null, warnings + "SVG 里没有可转换的图形（可能只用了渐变/文字/图片）", 0, 0)
        }

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!-- 由止血药从 SVG 转换生成 -->\n")
            append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("    android:width=\"${n(outW)}dp\"\n")
            append("    android:height=\"${n(outH)}dp\"\n")
            append("    android:viewportWidth=\"${n(width)}\"\n")
            append("    android:viewportHeight=\"${n(height)}\">\n")
            append(body)
            append("</vector>\n")
        }
        return Result(xml, warnings, outW.toInt(), outH.toInt())
    }

    // ---------------- 解析 ----------------

    private fun parse(svg: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // 模型给的 SVG 不可信：禁 DTD、禁外部实体（XXE）。
        // 这些 feature 在个别实现上会抛异常，所以逐个 runCatching —— 关不上也别整个崩掉
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isExpandEntityReferences = false }
    }.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))

    // ---------------- 样式 ----------------

    private data class Style(
        val fill: String?,
        val stroke: String?,
        val strokeWidth: Float?,
        val fillRule: String?,
        val lineCap: String?,
        val lineJoin: String?,
        val fillAlpha: Float?,
        val strokeAlpha: Float?
    )

    private fun alphaOf(e: Element, vararg names: String): Float? {
        for (n in names) {
            val v = e.getAttribute(n).trim()
            if (v.isNotEmpty()) v.toFloatOrNull()?.let { return it.coerceIn(0f, 1f) }
        }
        return null
    }

    /** 子元素继承父元素的样式（SVG 里 fill/stroke 是可继承属性） */
    private fun styleOf(e: Element, parent: Style): Style = Style(
        fill = e.getAttribute("fill").ifBlank { parent.fill },
        stroke = e.getAttribute("stroke").ifBlank { parent.stroke },
        strokeWidth = e.getAttribute("stroke-width").toFloatOrNull() ?: parent.strokeWidth,
        fillRule = e.getAttribute("fill-rule").ifBlank { parent.fillRule },
        lineCap = e.getAttribute("stroke-linecap").ifBlank { parent.lineCap },
        lineJoin = e.getAttribute("stroke-linejoin").ifBlank { parent.lineJoin },
        fillAlpha = alphaOf(e, "fill-opacity", "opacity") ?: parent.fillAlpha,
        strokeAlpha = alphaOf(e, "stroke-opacity", "opacity") ?: parent.strokeAlpha
    )

    // ---------------- 元素 ----------------

    private fun convertNode(
        e: Element,
        parent: Style,
        out: StringBuilder,
        warnings: MutableList<String>,
        depth: Int
    ) {
        if (depth > 12) return
        val tag = e.tagName.lowercase().substringAfter(':')
        when (tag) {
            "g" -> {
                val style = styleOf(e, parent)
                val transform = e.getAttribute("transform").trim()
                val inner = StringBuilder()
                for (c in e.childNodes.asList()) {
                    if (c is Element) convertNode(c, style, inner, warnings, depth + 1)
                }
                if (inner.isBlank()) return
                if (transform.isBlank()) {
                    out.append(inner)
                } else {
                    // 常见变换映射到 <group>；复杂矩阵直接给出警告而不是悄悄画错
                    val attrs = groupAttrs(transform, warnings)
                    if (attrs == null) {
                        warnings += "忽略了不支持的 transform：$transform"
                        out.append(inner)
                    } else {
                        out.append("    <group $attrs>\n")
                        out.append(inner.lineSequence().joinToString("\n") { "    $it" })
                        out.append("\n    </group>\n")
                    }
                }
            }

            "path" -> {
                val d = e.getAttribute("d").trim()
                if (d.isEmpty()) return
                emit(out, normalizePath(d), styleOf(e, parent), warnings)
            }

            "rect" -> {
                val x = e.num("x"); val y = e.num("y")
                val w = e.num("width"); val h = e.num("height")
                if (w <= 0f || h <= 0f) return
                val rx = e.num("rx").let { if (it > 0f) it else e.num("ry") }
                val d = if (rx > 0f) {
                    // 圆角矩形：用两段圆弧，和 Android 自己生成的一致
                    val r = rx.coerceAtMost(minOf(w, h) / 2f)
                    "M${n(x + r)},${n(y)}H${n(x + w - r)}A$r,$r 0 0 1 ${n(x + w)},${n(y + r)}" +
                        "V${n(y + h - r)}A$r,$r 0 0 1 ${n(x + w - r)},${n(y + h)}" +
                        "H${n(x + r)}A$r,$r 0 0 1 ${n(x)},${n(y + h - r)}" +
                        "V${n(y + r)}A$r,$r 0 0 1 ${n(x + r)},${n(y)}Z"
                } else {
                    "M${n(x)},${n(y)}h${n(w)}v${n(h)}h${n(-w)}Z"
                }
                emit(out, d, styleOf(e, parent), warnings)
            }

            "circle" -> {
                val cx = e.num("cx"); val cy = e.num("cy"); val r = e.num("r")
                if (r <= 0f) return
                emit(out, ellipsePath(cx, cy, r, r), styleOf(e, parent), warnings)
            }

            "ellipse" -> {
                val cx = e.num("cx"); val cy = e.num("cy")
                val rx = e.num("rx"); val ry = e.num("ry")
                if (rx <= 0f || ry <= 0f) return
                emit(out, ellipsePath(cx, cy, rx, ry), styleOf(e, parent), warnings)
            }

            "line" -> {
                emit(
                    out,
                    "M${n(e.num("x1"))},${n(e.num("y1"))}L${n(e.num("x2"))},${n(e.num("y2"))}",
                    styleOf(e, parent),
                    warnings
                )
            }

            "polygon", "polyline" -> {
                val pts = e.getAttribute("points").trim()
                    .split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                    .mapNotNull { it.toFloatOrNull() }
                if (pts.size < 4) return
                val d = StringBuilder()
                var i = 0
                while (i + 1 < pts.size) {
                    d.append(if (i == 0) "M" else "L").append(n(pts[i])).append(",").append(n(pts[i + 1]))
                    i += 2
                }
                if (tag == "polygon") d.append("Z")
                emit(out, d.toString(), styleOf(e, parent), warnings)
            }

            "defs", "title", "desc", "metadata", "style" -> Unit   // 无害，直接忽略

            "text" -> warnings += "忽略了文字元素 <text>（VectorDrawable 不支持文字）"
            "image" -> warnings += "忽略了 <image>（VectorDrawable 不支持位图）"
            // 注意：tag 上面已经 lowercase 过了，这里必须用小写比较 ——
            // 写成 linearGradient 会永远匹配不上，渐变会被归到「不支持的元素」，
            // 用户看到的提示就是错的（说「不支持」而没说是渐变）
            "lineargradient", "radialgradient" ->
                warnings += "忽略了渐变（VectorDrawable 的渐变需要另写 aapt 属性）"
            "filter", "mask", "clippath", "pattern" -> warnings += "忽略了 <$tag>（VectorDrawable 不支持）"
            else -> warnings += "忽略了不支持的 <$tag>"
        }
    }

    private fun Element.num(attr: String): Float =
        getAttribute(attr).let { lengthValue(it) } ?: 0f

    private fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float): String =
        "M${n(cx - rx)},${n(cy)}a$rx,$ry 0 1 0 ${n(rx * 2)},0a$rx,$ry 0 1 0 ${n(-rx * 2)},0Z"

    private fun emit(out: StringBuilder, pathData: String, s: Style, warnings: MutableList<String>) {
        val fill = s.fill?.trim()
        val stroke = s.stroke?.trim()
        val noFill = fill.isNullOrBlank() || fill.equals("none", true)
        val noStroke = stroke.isNullOrBlank() || stroke.equals("none", true)
        if (noFill && noStroke) return   // 什么都不画

        val attrs = StringBuilder()
        attrs.append("android:pathData=\"${escape(pathData)}\"")
        if (!noFill) attrs.append("\n        android:fillColor=\"${color(fill!!, warnings)}\"")
        if (!noStroke) {
            attrs.append("\n        android:strokeColor=\"${color(stroke!!, warnings)}\"")
            attrs.append("\n        android:strokeWidth=\"${n(s.strokeWidth ?: 1f)}\"")
            s.lineCap?.let { attrs.append("\n        android:strokeLineCap=\"${capOf(it)}\"") }
            s.lineJoin?.let { attrs.append("\n        android:strokeLineJoin=\"${joinOf(it)}\"") }
        }
        s.fillAlpha?.let { attrs.append("\n        android:fillAlpha=\"${n(it)}\"") }
        s.strokeAlpha?.let { attrs.append("\n        android:strokeAlpha=\"${n(it)}\"") }
        // evenodd → evenOdd；VectorDrawable 默认就是 nonZero，不用写
        if (s.fillRule.equals("evenodd", true)) attrs.append("\n        android:fillType=\"evenOdd\"")

        out.append("    <path\n        ").append(attrs).append(" />\n")
    }

    private fun capOf(v: String) = when (v.lowercase()) {
        "round" -> "round"; "square" -> "square"; else -> "butt"
    }

    private fun joinOf(v: String) = when (v.lowercase()) {
        "round" -> "round"; "bevel" -> "bevel"; else -> "miter"
    }

    // ---------------- 颜色 ----------------

    private fun color(raw: String, warnings: MutableList<String>): String {
        val v = raw.trim().lowercase()
        if (v == "currentcolor") {
            warnings += "currentColor 无法映射，已按黑色处理（请改用具体颜色）"
            return "#FF000000"
        }
        NAMED_COLORS[v]?.let { return it }
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> "#FF" + hex.map { "$it$it" }.joinToString("").uppercase()
                4 -> {  // #RGBA
                    val a = hex[3].toString().repeat(2)
                    "#" + a + hex.take(3).map { "$it$it" }.joinToString("")
                }
                6 -> "#FF" + hex.uppercase()
                8 -> "#" + hex.uppercase()
                else -> {
                    warnings += "颜色格式不认识：$raw（已按黑色处理）"
                    "#FF000000"
                }
            }
        }
        val rgb = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(v)
        if (rgb != null) {
            val (r, g, b) = rgb.destructured
            return "#FF%02X%02X%02X".format(r.toIntOrNull() ?: 0, g.toIntOrNull() ?: 0, b.toIntOrNull() ?: 0)
        }
        warnings += "颜色格式不认识：$raw（已按黑色处理）"
        return "#FF000000"
    }

    // ---------------- transform ----------------

    /** 把简单的 transform 映射到 `<group>` 属性；含矩阵的一律返回 null（交给调用方警告） */
    private fun groupAttrs(transform: String, warnings: MutableList<String>): String? {
        val attrs = StringBuilder()
        val re = Regex("""(translate|scale|rotate)\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
        var matched = false
        var consumed = 0
        for (m in re.findAll(transform)) {
            matched = true
            consumed += m.value.length
            val args = m.groupValues[2].split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                .mapNotNull { it.toFloatOrNull() }
            when (m.groupValues[1].lowercase()) {
                "translate" -> {
                    attrs.append("\n        android:translateX=\"${n(args.getOrElse(0) { 0f })}\"")
                    if (args.size > 1) attrs.append("\n        android:translateY=\"${n(args[1])}\"")
                }
                "scale" -> {
                    attrs.append("\n        android:scaleX=\"${n(args.getOrElse(0) { 1f })}\"")
                    attrs.append("\n        android:scaleY=\"${n(args.getOrElse(1) { args.getOrElse(0) { 1f } })}\"")
                }
                "rotate" -> {
                    val deg = args.getOrElse(0) { 0f }
                    attrs.append("\n        android:rotation=\"${n(deg)}\"")
                    // 绕指定点旋转：<group> 的 rotation 是绕 (pivotX, pivotY)
                    if (args.size >= 3) {
                        attrs.append("\n        android:pivotX=\"${n(args[1])}\"")
                        attrs.append("\n        android:pivotY=\"${n(args[2])}\"")
                    }
                }
            }
        }
        // 除空白外还有没被识别的部分（比如 matrix(...)）→ 不支持
        if (!matched) return null
        val leftover = transform.replace(re, "").replace(Regex("[\\s,]+"), "")
        if (leftover.isNotEmpty()) {
            warnings += "transform 里含不支持的部分：$leftover"
        }
        return attrs.toString().trim().removePrefix("\n").let { "android:name=\"g\"" + if (it.isBlank()) "" else "\n        $it" }
    }

    // ---------------- 小工具 ----------------

    /** 取长度数值：去掉 px/dp/pt 等单位；百分比返回 null（算不了） */
    private fun lengthValue(raw: String): Float? {
        val v = raw.trim()
        if (v.isEmpty() || v.endsWith("%")) return null
        val num = v.removeSuffix("px").removeSuffix("dp").removeSuffix("pt").removeSuffix("em").trim()
        return num.toFloatOrNull()
    }

    /** 紧凑输出数值：24.0 → 24，0.5 → 0.5 */
    private fun n(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v)

    /** pathData 里只保留合法字符，避免把属性注进 XML */
    private fun normalizePath(d: String): String =
        d.replace(Regex("[^MmZzLlHhVvCcSsQqTtAa0-9eE+\\-.,\\s]"), "").replace(Regex("\\s+"), " ").trim()

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun org.w3c.dom.NodeList.asList(): List<Node> = (0 until length).map { item(it) }
}
