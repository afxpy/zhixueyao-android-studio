package com.zhixueyao.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zhixueyao.llm.CustomProvider
import com.zhixueyao.llm.ThinkingStyle
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * 自建服务商（中转站 / 自建网关）的编辑对话框。
 *
 * 为什么需要它：中转站可能有好几家 —— 不同的地址、密钥、模型、计费，
 * 必须能各自独立并存，并且在下拉里一眼看出用的是哪家。
 * 所以这里至少要收「名称」这个字段，否则多个中转站在列表里长得一模一样。
 *
 * 字段取「够用就好」的原则，只收四项：
 *  - 名称：唯一必须用户自己想的字段，用于下拉里区分
 *  - 接口地址：OpenAI 兼容端点
 *  - 协议格式：决定请求体形状，选错会 400
 *  - 思考方言：决定思考参数用哪种字段名，选错同样会 400
 *
 * 密钥与模型名刻意**不放这里** —— 它们在设置页主界面按服务商分别记忆，
 * 放两份会产生「以哪个为准」的歧义。
 */
class CustomProviderDialog(
    private val titleText: String,
    private val initial: CustomProvider
) : DialogWrapper(true) {

    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val formatBox = JComboBox(arrayOf("OpenAI 兼容协议", "Anthropic 原生协议"))
    private val styleBox = JComboBox(
        ThinkingStyle.entries.map { it.label }.toTypedArray()
    )
    private val styleHint = JBLabel()
    private val urlHint = JBLabel()

    /** 对话框确认后的结果；取消时为 null */
    var result: CustomProvider? = null
        private set

    init {
        title = titleText
        urlField.text = initial.baseUrl
        nameField.text = initial.name
        formatBox.selectedIndex = if (initial.apiFormat == "anthropic") 1 else 0
        styleBox.selectedIndex = ThinkingStyle.entries
            .indexOfFirst { it.name == initial.thinkingStyle }
            .coerceAtLeast(0)

        init()
        refreshHints()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(560, 220)
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }
        var row = 0

        fun addRow(label: String, comp: JComponent, hint: JBLabel? = null) {
            gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0.0; gbc.weighty = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(comp, gbc)
            if (hint != null) {
                gbc.gridy = ++row; gbc.gridx = 1
                panel.add(hint, gbc)
            }
            row++
        }

        nameField.emptyText.text = "例如：公司中转、某宝额度、自建网关"
        addRow("服务商名称", nameField)

        urlField.emptyText.text = "https://api.example.com/v1"
        addRow("接口地址", urlField, urlHint)

        addRow("协议格式", formatBox)
        addRow("思考方言", styleBox, styleHint)

        for (h in listOf(urlHint, styleHint)) {
            h.foreground = UIUtil.getContextHelpForeground()
            h.font = h.font.deriveFont(h.font.size - 1f)
        }

        // 方言说明跟着选择变 —— 选错方言的后果是请求被拒，
        // 而报错信息通常很含糊，所以这里必须把「会发生什么」写清楚
        styleBox.addActionListener { refreshHints() }

        return panel
    }

    private fun refreshHints() {
        val style = ThinkingStyle.entries.getOrElse(styleBox.selectedIndex) { ThinkingStyle.NONE }
        styleHint.text = "· ${style.hint}"
        urlHint.text = if (formatBox.selectedIndex == 1) {
            "Anthropic 原生端点通常以 /v1 结尾，不需要写 /messages"
        } else {
            "OpenAI 兼容端点通常以 /v1 结尾，不需要写 /chat/completions"
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo("请填写服务商名称", nameField)
        // 重名会让下拉里出现两个一样的条目，用户分不清哪家是哪家。
        // 编辑时排除自己，否则不改名直接确定会被自己的旧名字挡住
        val s = ZhixueyaoSettings.getInstance()
        if (s.customProviders.any { it.id != initial.id && it.name.equals(name, ignoreCase = true) }) {
            return ValidationInfo("已有同名服务商，请换一个名称", nameField)
        }

        val url = urlField.text.trim()
        if (url.isEmpty()) return ValidationInfo("请填写接口地址", urlField)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ValidationInfo("接口地址需以 http:// 或 https:// 开头", urlField)
        }
        return null
    }

    override fun doOKAction() {
        result = initial.copy(
            name = nameField.text.trim(),
            baseUrl = urlField.text.trim().trimEnd('/'),
            apiFormat = if (formatBox.selectedIndex == 1) "anthropic" else "openai",
            thinkingStyle = ThinkingStyle.entries
                .getOrElse(styleBox.selectedIndex) { ThinkingStyle.NONE }.name
        )
        super.doOKAction()
    }

    companion object {
        /**
         * 弹出对话框，返回填好的服务商；取消返回 null。
         *
         * [title] 由调用方给（新增 / 编辑），比让对话框自己猜准。
         * [parent] 用来定位对话框 —— 不给的话它会出现在屏幕正中，
         * 而设置面板在 IDE 里，两者分开看着像卡住了。
         */
        fun show(
            parent: java.awt.Component?,
            title: String,
            initial: CustomProvider
        ): CustomProvider? {
            val dialog = CustomProviderDialog(title, initial)
            if (!dialog.showAndGet()) return null
            return dialog.result
        }
    }
}
