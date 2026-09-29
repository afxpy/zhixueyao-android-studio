package com.zhixueyao.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zhixueyao.mcp.McpServerConfig
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * MCP 服务器配置对话框。
 *
 * 提供两种模式的表单切换：stdio（本地命令）与 http（远程地址）。
 */
class McpServerDialog(private val existing: McpServerConfig?) : DialogWrapper(true) {

    private val nameField = JBTextField()
    private val typeBox = JComboBox(arrayOf("本地命令 (stdio)", "远程地址 (http)"))
    private val idField = JBTextField()

    private val commandField = JBTextField()
    private val envArea = JBTextArea().apply {
        rows = 3
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, UIUtil.getLabelFont().size - 1)
    }

    private val urlField = JBTextField()
    private val headersArea = JBTextArea().apply {
        rows = 3
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, UIUtil.getLabelFont().size - 1)
    }

    private val enabledCheck = JCheckBox("启用", true)

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    init {
        title = if (existing == null) "添加 MCP 服务器" else "编辑 MCP 服务器"
        init()
        loadExisting()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(620, 380)
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }
        var row = 0

        fun addRow(label: String, comp: JComponent, hint: String? = null) {
            gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0.0; gbc.weighty = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(comp, gbc)
            if (hint != null) {
                gbc.gridy = ++row; gbc.gridx = 1
                panel.add(
                    JBLabel(hint).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        font = font.deriveFont(font.size - 1f)
                    },
                    gbc
                )
            }
            row++
        }

        addRow("显示名称", nameField)
        addRow("标识符", idField, "用于工具名前缀，仅字母数字与下划线，留空则自动生成")
        addRow("连接方式", typeBox)
        addRow("", enabledCheck)

        // stdio 表单
        val stdioPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        run {
            val g = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            }
            g.gridy = 0; g.gridx = 0; g.weightx = 0.0
            stdioPanel.add(JBLabel("启动命令"), g)
            g.gridy = 1; g.gridx = 0; g.weightx = 1.0
            stdioPanel.add(commandField, g)
            g.gridy = 2; g.gridx = 0
            stdioPanel.add(
                JBLabel("例：npx -y @modelcontextprotocol/server-filesystem D:/project").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = font.deriveFont(font.size - 1f)
                },
                g
            )
            g.gridy = 3; g.gridx = 0
            stdioPanel.add(JBLabel("环境变量（每行 KEY=VALUE，常用于传密钥）"), g)
            g.gridy = 4; g.gridx = 0; g.weighty = 1.0; g.fill = GridBagConstraints.BOTH
            stdioPanel.add(javax.swing.JScrollPane(envArea), g)
        }

        // http 表单
        val httpPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        run {
            val g = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            }
            g.gridy = 0; g.gridx = 0; g.weightx = 0.0
            httpPanel.add(JBLabel("服务地址"), g)
            g.gridy = 1; g.gridx = 0; g.weightx = 1.0
            httpPanel.add(urlField, g)
            g.gridy = 2; g.gridx = 0
            httpPanel.add(
                JBLabel("例：http://127.0.0.1:13337/mcp").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = font.deriveFont(font.size - 1f)
                },
                g
            )
            g.gridy = 3; g.gridx = 0
            httpPanel.add(JBLabel("请求头（每行 KEY=VALUE）"), g)
            g.gridy = 4; g.gridx = 0; g.weighty = 1.0; g.fill = GridBagConstraints.BOTH
            httpPanel.add(javax.swing.JScrollPane(headersArea), g)
        }

        cards.add(stdioPanel, "stdio")
        cards.add(httpPanel, "http")

        gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(cards, gbc)

        typeBox.addActionListener {
            cardLayout.show(cards, if (typeBox.selectedIndex == 1) "http" else "stdio")
            panel.revalidate()
            panel.repaint()
        }

        return panel
    }

    private fun loadExisting() {
        val cfg = existing
        if (cfg == null) {
            typeBox.selectedIndex = 0
            cardLayout.show(cards, "stdio")
            return
        }
        nameField.text = cfg.name
        idField.text = cfg.id
        typeBox.selectedIndex = if (cfg.type == "http") 1 else 0
        cardLayout.show(cards, if (cfg.type == "http") "http" else "stdio")
        commandField.text = cfg.command
        urlField.text = cfg.url
        envArea.text = cfg.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
        headersArea.text = cfg.headers.entries.joinToString("\n") { "${it.key}=${it.value}" }
        enabledCheck.isSelected = cfg.enabled
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        if (nameField.text.isBlank()) {
            return com.intellij.openapi.ui.ValidationInfo("请填写显示名称", nameField)
        }
        return if (typeBox.selectedIndex == 1) {
            if (urlField.text.isBlank()) {
                com.intellij.openapi.ui.ValidationInfo("请填写服务地址", urlField)
            } else null
        } else {
            if (commandField.text.isBlank()) {
                com.intellij.openapi.ui.ValidationInfo("请填写启动命令", commandField)
            } else null
        }
    }

    /** 对话框确认后的结果。 */
    var result: McpServerConfig? = null
        private set

    override fun doOKAction() {
        val name = nameField.text.trim()
        val id = idField.text.trim().ifBlank {
            name.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        }
        val isHttp = typeBox.selectedIndex == 1

        result = McpServerConfig(
            id = id,
            name = name,
            type = if (isHttp) "http" else "stdio",
            command = if (isHttp) "" else commandField.text.trim(),
            url = if (isHttp) urlField.text.trim() else "",
            env = parseKeyValues(envArea.text).toMutableMap(),
            headers = parseKeyValues(headersArea.text).toMutableMap(),
            enabled = enabledCheck.isSelected
        )
        super.doOKAction()
    }

    private fun parseKeyValues(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx <= 0) continue
            out[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
        return out
    }

    companion object {
        /** 弹出对话框，返回用户填写的配置（取消则返回 null）。 */
        fun show(existing: McpServerConfig?): McpServerConfig? {
            val dialog = McpServerDialog(existing)
            return if (dialog.showAndGet()) dialog.result else null
        }
    }
}
