package com.zhixueyao.ui

import com.intellij.openapi.diagnostic.Logger
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * 把模型回答里的 Markdown 渲染成 HTML。
 *
 * 为什么要做：模型回答默认是 Markdown（标题、加粗、表格、列表、行内代码），
 * 直接塞进纯文本控件就是 `## 项目概况`、`**万象**`、`| 项 | 内容 |` 全裸着显示 ——
 * 用户反馈「看上去很简陋很不美观」。
 *
 * 解析器用平台自带的 `intellij.libraries.markdown`（`org.intellij.markdown`）：
 * 不引第三方依赖，也不需要用户装 markdown 插件。
 *
 * 渲染是**锦上添花**：任何异常都返回 null，调用方回落纯文本 ——
 * 绝不能因为渲染失败把回答本身弄丢。
 */
object MarkdownRenderer {

    private val log = Logger.getInstance(MarkdownRenderer::class.java)

    /** 粗判：含 Markdown 特征才走渲染，纯文本保持原样（省一次解析） */
    fun looksLikeMarkdown(text: String): Boolean = text.length >= 8 && MARKER.containsMatchIn(text)

    private val MARKER = Regex(
        "(?m)^(#{1,6}\\s|\\s*[-*+]\\s|\\s*\\d+\\.\\s|>\\s|\\|.*\\|)|\\*\\*|`{1,3}"
    )

    /**
     * 去掉 Markdown 记号，留干净的文字 —— 给「折叠预览」这类纯文本控件用。
     *
     * 预览是纯文本，直接把原文塞进去就会看到 `## 标题`、`**加粗**`、`| 表格 |`，
     * 用户反馈「折叠的文字有问题」多半就是这个观感。
     */
    fun stripSyntax(text: String): String = text
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        .replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), "")
        .replace(Regex("(?m)^\\s*[-|: ]{4,}\\s*$"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("(?m)^>\\s*"), "")

    fun toHtml(markdown: String): String? = runCatching {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val body = HtmlGenerator(markdown, tree, flavour).generateHtml()
        wrap(body)
    }.onFailure { log.warn("Markdown 渲染失败，回落纯文本", it) }.getOrNull()

    /**
     * 注入主题色。
     *
     * 必须做：Swing 的 HTMLEditorKit 默认按「深字浅底」渲染，
     * 暗色主题下会变成黑字黑底 —— 看起来像回答丢了。
     */
    private fun wrap(body: String): String = buildString {
        append("<html><head><style>")
        append("body { color: ").append(hex(UiKit.text)).append("; font-family: sans-serif; font-size: 13px; margin: 0; }")
        append("h1, h2, h3, h4 { color: ").append(hex(UiKit.text)).append("; margin: 12px 0 6px 0; }")
        append("h1 { font-size: 18px; } h2 { font-size: 16px; } h3 { font-size: 14px; } h4 { font-size: 13px; }")
        append("p { margin: 6px 0; }")
        append("code { color: ").append(hex(UiKit.link)).append("; background: ").append(hex(UiKit.card)).append("; }")
        // 长代码不能溢出被裁：
        //  - pre/code 用 pre-wrap 自动折行（聊天里折行比横向拖动更好读）
        //  - word-break 处理没有空格的超长标识符/URL
        append("pre { background: ").append(hex(UiKit.card)).append("; padding: 6px; ")
            .append("white-space: pre-wrap; word-break: break-word; }")
        append("code { white-space: pre-wrap; word-break: break-word; }")
        append("a { color: ").append(hex(UiKit.link)).append("; }")
        // 表格宽度让浏览器自己算，超宽时由外层滚动容器接管（不用写死宽度）
        append("table { border-collapse: collapse; margin: 6px 0; }")
        append("th, td { border: 1px solid ").append(hex(UiKit.border)).append("; padding: 3px 7px; }")
        append("th { background: ").append(hex(UiKit.card)).append("; }")
        append("blockquote { color: ").append(hex(UiKit.subtle)).append("; margin: 6px 0 6px 8px; }")
        append("ul, ol { margin: 6px 0 6px 20px; }")
        append("hr { color: ").append(hex(UiKit.border)).append("; }")
        append("</style></head><body>").append(body).append("</body></html>")
    }

    private fun hex(c: java.awt.Color): String = String.format("#%06X", c.rgb and 0xFFFFFF)
}
