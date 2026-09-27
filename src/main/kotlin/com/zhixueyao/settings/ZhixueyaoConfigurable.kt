package com.zhixueyao.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.zhixueyao.agent.AgentPresets
import com.zhixueyao.agent.Sandbox
import com.zhixueyao.llm.Providers
import com.zhixueyao.llm.ThinkingLevel
import com.zhixueyao.mcp.McpManager
import com.zhixueyao.mcp.McpServerConfig
import com.zhixueyao.mcp.McpServerRegistry
import com.zhixueyao.ui.Avatars
import com.zhixueyao.ui.UiKit
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/**
 * 设置窗口的目标高度上限。
 *
 * 只影响「窗口打开时多大」：内容比它高时右侧内容区自己滚动。
 * 不设这个上限的话，窗口会按内容全展开的高度（模型页过千像素）撑满屏幕。
 */
private const val SETTINGS_WINDOW_HEIGHT = 620

/**
 * 设置面板 —— 左侧分类导航 + 右侧内容区。
 *
 * 四个分类（对齐 dsh web 的设置信息架构）：
 *  - 通用：回复语言、界面选项
 *  - 模型：服务商预设、接口地址、密钥、模型名、生成参数
 *  - 插件：外部 MCP 服务器管理 + 本插件对外提供 MCP 服务
 *  - Agent 预设：运行模式与工具权限
 *
 * 多服务商记忆：切换服务商时，先把当前填写的内容存回该服务商名下，
 * 再载入目标服务商记住的那份，避免来回切换时反复重填密钥。
 *
 * ## 为什么必须实现 [Configurable.NoScroll]
 *
 * 平台在把 configurable 塞进设置窗口时，会做一件事（`ConfigurableCardPanel`）：
 *
 * ```
 * if (configurable !is Configurable.NoScroll) {
 *     return ScrollPaneFactory.createScrollPane(component, true)   // ← 外面套一层滚动容器
 * }
 * return component                                                  // ← 原样使用
 * ```
 *
 * 也就是说**默认**情况下，整个组件（含本页自己的顶栏与左侧导航）会被塞进平台的
 * 滚动容器里。用户一滚，导航和标题就跟着内容一起滚出视口 —— 表现就是
 * 「左侧边栏会跟着下滑」。光在内部把导航放在滚动区之外是治不了的，
 * 因为外面还套着一层更大的滚动区。
 *
 * 正确做法是声明「本页自己管滚动」（[NoScroll]），平台便不再包那层容器，
 * 于是页面高度恒等于视口高度：顶栏与导航固定，只有右侧内容区滚动。
 * 这是平台提供的官方开关，不是绕过框架的取巧写法。
 */
class ZhixueyaoConfigurable : Configurable, Configurable.NoScroll {

    private var rootPanel: JPanel? = null
    private val cardLayout = CardLayout()
    private var contentPanel: JPanel? = null

    private val navPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private var selectedSection = 0

    /** 构造时指定要选中的分类，createComponent 时生效 */
    private var initialSection = 0

    /** 当前输入框里的内容属于哪个服务商，用于切换时归位 */
    private var fieldOwnerId: String = ""

    // ---------------- 通用页 ----------------
    private val presetBox = JComboBox(AgentPresets.all.map { it.label }.toTypedArray())
    private val presetSummary = JBLabel()
    private val presetTools = JBLabel()
    private val languageBox = JComboBox(arrayOf("跟随系统", "简体中文", "English"))
    private val confirmSendCheck = JCheckBox("发送前确认（内容较长时先让我看一眼）")
    private val showApplyCheck = JCheckBox("代码块显示「应用」按钮（一键写入文件，可 Ctrl+Z 撤销）")
    private val showTokenCheck = JCheckBox("状态栏显示本轮 token 用量（服务商回报的真实值，拿不到时估算）")
    private val autoCompactCheck =
        JCheckBox("长对话模式：上下文接近模型上限时自动压缩（关掉 = 高精度，信息不丢，但聊长了会被服务端打回）")
    private val contextWindowSpinner = JSpinner(SpinnerNumberModel(0, 0, 2_000_000, 1_000))
    private val keepTurnsSpinner = JSpinner(SpinnerNumberModel(4, 1, 50, 1))
    private val recentImagesSpinner = JSpinner(SpinnerNumberModel(2, 0, 50, 1))

    // ---------------- 头像与思考展示（通用页） ----------------

    /**
     * 头像选择的草稿值。
     *
     * 设置页的规矩是「界面改动先攒着，apply() 时才写回设置」，所以头像不能直接读写
     * [ZhixueyaoSettings] —— 否则点「取消」也生效了。
     */
    private var draftUserAvatar: String = Avatars.DEFAULT_USER
    private var draftAiAvatar: String = Avatars.DEFAULT_AI

    private val aiAvatarRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        .apply { isOpaque = false }
    private val userAvatarRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        .apply { isOpaque = false }

    /** 技能库状态文字（技能数量） */
    private val skillsInfoLabel = JBLabel()

    /** 技能目录路径（全局 / 项目 / 外部，各一行） */
    private val skillsPathLabel = JBLabel()

    /** 技能清单（逐条列出：名称 + 来源 + 说明） */
    private val skillsListBody = JBPanel<JBPanel<*>>()

    /** 思考过程展示方式：默认收起 / 实时展开 / 不显示 */
    private val reasoningViewBox = JComboBox(arrayOf("默认收起", "实时展开", "不显示"))

    /**
     * 思考强度勾选 —— 决定聊天窗口「思考模式」下拉里显示哪些档位。
     *
     * 与「当前强度」的区别：这里是**可选清单**，那里是**当前生效值**。
     * 档位一共 7 个，全塞进聊天窗口的下拉会让人每次都要在一堆选项里找，
     * 所以由用户在设置里挑出常用的几档。
     */
    private val thinkingChecks: Map<String, JCheckBox> =
        ThinkingLevel.entries.associate { level -> level.name to JCheckBox(level.label) }

    // ---------------- AI 助手行为（通用页） ----------------

    /** 沙盒权限：仅当前项目 / 全盘沙盒 / 每次询问 */
    private val sandboxModeBox = JComboBox(
        arrayOf(Sandbox.Mode.PROJECT.label, Sandbox.Mode.FULL.label, Sandbox.Mode.ASK.label)
    )
    private val showTaskListCheck = JCheckBox("让 AI 展示任务清单（多步任务时列出步骤并逐项打勾）")
    private val allowAskUserCheck = JCheckBox("允许 AI 用选项询问我（遇到岔路时给几个按钮让我点）")

    // ---------------- 模型页 ----------------
    private val providerBox = com.intellij.openapi.ui.ComboBox(Providers.presets.map { it.label }.toTypedArray())
    private val baseUrlField = JBTextField()
    private val apiKeyField = JPasswordField()
    private val modelField = JBTextField()
    private val formatBox = JComboBox(arrayOf("OpenAI 兼容协议", "Anthropic 原生协议"))
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.2, 0.0, 2.0, 0.1))
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(8192, 256, 200_000, 256))
    private val maxRoundsSpinner = JSpinner(SpinnerNumberModel(25, 1, 100, 1))
    private val enableMcpCheck = JCheckBox("启用 MCP 工具（把外部 MCP 服务器的能力交给 AI）")
    private val presetNote = JBLabel()
    private val testResultLabel = JBLabel()
    private val providerStatusLabel = JBLabel()

    /**
     * 当前服务商已添加的模型清单。
     *
     * 用一个横向的标签流展示 —— 每个模型是一个可点的小标签（点击即切换成当前模型），
     * 比一个下拉框更直观：一眼看清「这家一共有哪些模型」，
     * 而模型数量通常只有几个到十几个，不需要滚动列表那种重控件。
     */
    private val modelChipsPanel = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4))
    private val modelListHint = JBLabel()

    /** 思考强度。档位是中立概念，具体发什么参数由「参数方言」决定 */
    private val thinkingLevelBox = com.intellij.openapi.ui.ComboBox(
        com.zhixueyao.llm.ThinkingLevel.entries.map { it.label }.toTypedArray()
    )
    private val thinkingHintLabel = JBLabel()
    private val thinkingStyleBox = com.intellij.openapi.ui.ComboBox(
        com.zhixueyao.llm.ThinkingStyle.entries.map { it.label }.toTypedArray()
    )

    // ---------------- MCP 服务 ----------------
    private val mcpTableModel = McpTableModel()
    /** 临时会话管理页（全局临时产物目录的查看 / 清理入口） */
    private val tempSessionsPanel = TempSessionsPanel()
    private val mcpTable = JBTable(mcpTableModel)
    private val mcpServerEnabledCheck = JCheckBox("把本 IDE 作为 MCP 服务器对外提供（供 Claude Code / Cursor 等调用）")
    private val mcpPortSpinner = JSpinner(SpinnerNumberModel(29170, 1024, 65535, 1))
    private val mcpStatusLabel = JBLabel()
    private val configSnippetArea = JBTextArea()

    /** 让调用方指定打开时落在哪个分类上。需在 [createComponent] 之前调用。 */
    fun selectSection(id: String) {
        val normalized = SECTION_ALIASES[id] ?: id
        val idx = SECTIONS.indexOfFirst { it.first == normalized }
        if (idx >= 0) {
            initialSection = idx
            select(idx)
        }
    }

    /** 兼容旧调用：直达插件（MCP）页 */
    fun selectMcpTab() = selectSection("plugins")

    // ---------------- Configurable 接口 ----------------

    override fun getDisplayName(): String = "止血药"

    override fun createComponent(): JComponent {
        if (rootPanel == null) {
            rootPanel = buildRoot()
            reset()
        }
        return rootPanel!!
    }

    override fun isModified(): Boolean {
        val s = ZhixueyaoSettings.getInstance()
        return s.baseUrl != baseUrlField.text.trim() ||
            s.apiKey != String(apiKeyField.password) ||
            s.model != modelField.text.trim() ||
            s.temperature != (temperatureSpinner.value as Number).toDouble() ||
            s.maxTokens != (maxTokensSpinner.value as Number).toInt() ||
            s.maxToolRounds != (maxRoundsSpinner.value as Number).toInt() ||
            s.enableMcp != enableMcpCheck.isSelected ||
            s.mcpServerEnabled != mcpServerEnabledCheck.isSelected ||
            s.mcpServerPort != (mcpPortSpinner.value as Number).toInt() ||
            s.apiFormat != currentFormat() ||
            s.providerId != currentProviderId() ||
            s.thinkingLevel != currentThinkingLevelName() ||
            s.agentPreset != currentPresetId() ||
            s.replyLanguage != currentLanguage() ||
            s.userAvatar != draftUserAvatar ||
            s.aiAvatar != draftAiAvatar ||
            s.reasoningView != currentReasoningView() ||
            s.thinkingLevels.toSet() != currentThinkingLevels().toSet() ||
            s.sandboxMode != currentSandboxMode().id ||
            s.showTaskList != showTaskListCheck.isSelected ||
            s.allowAskUser != allowAskUserCheck.isSelected ||
            s.confirmBeforeSend != confirmSendCheck.isSelected ||
            s.showApplyButton != showApplyCheck.isSelected ||
            s.showTokenEstimate != showTokenCheck.isSelected ||
            s.autoCompact != autoCompactCheck.isSelected ||
            s.contextWindow != (contextWindowSpinner.value as Int) ||
            s.compactKeepTurns != (keepTurnsSpinner.value as Int) ||
            s.maxRecentImages != (recentImagesSpinner.value as Int) ||
            McpServerRegistry.getInstance().servers != mcpTableModel.snapshot()
    }

    override fun apply() {
        val s = ZhixueyaoSettings.getInstance()

        // 头像与思考展示（草稿值写回）
        s.userAvatar = draftUserAvatar
        s.aiAvatar = draftAiAvatar
        s.reasoningView = currentReasoningView()
        s.thinkingLevels = currentThinkingLevels().toMutableList()
        // 当前强度若已不在勾选清单里，回落到「默认」—— 否则聊天窗口的下拉会显示一个
        // 列表里根本没有的当前值，看起来像 bug（实际也确实不一致）
        if (s.thinkingLevel != ThinkingLevel.DEFAULT.name && !s.thinkingLevels.contains(s.thinkingLevel)) {
            s.thinkingLevel = ThinkingLevel.DEFAULT.name
        }
        // 头像可能换了，清掉渲染缓存，否则聊天里还是旧头像
        Avatars.invalidate()

        // AI 助手行为
        s.sandboxMode = currentSandboxMode().id
        s.showTaskList = showTaskListCheck.isSelected
        s.allowAskUser = allowAskUserCheck.isSelected

        // 模型
        s.providerId = currentProviderId()
        s.baseUrl = baseUrlField.text.trim()
        s.apiKey = String(apiKeyField.password)
        s.model = modelField.text.trim()
        s.apiFormat = currentFormat()
        s.thinkingLevel = currentThinkingLevelName()
        s.rememberKey(s.providerId, s.apiKey)
        s.rememberModel(s.providerId, s.model)
        s.rememberBaseUrl(s.providerId, s.baseUrl)

        // 自定义服务商：把界面上改动的地址/模型/方言写回它自己的定义里，
        // 否则用户改名后重开设置会发现地址又变回去了
        val customId = currentProviderId()
        val custom = s.customById(customId)
        if (custom != null) {
            s.upsertCustom(
                custom.copy(
                    baseUrl = s.baseUrl,
                    model = s.model,
                    apiFormat = s.apiFormat,
                    thinkingStyle = currentThinkingStyleName()
                )
            )
        }

        s.temperature = (temperatureSpinner.value as Number).toDouble()
        s.maxTokens = (maxTokensSpinner.value as Number).toInt()
        s.maxToolRounds = (maxRoundsSpinner.value as Number).toInt()
        s.enableMcp = enableMcpCheck.isSelected

        // 通用
        s.agentPreset = currentPresetId()
        s.replyLanguage = currentLanguage()
        s.confirmBeforeSend = confirmSendCheck.isSelected
        s.showApplyButton = showApplyCheck.isSelected
        s.showTokenEstimate = showTokenCheck.isSelected
        s.autoCompact = autoCompactCheck.isSelected
        s.contextWindow = contextWindowSpinner.value as Int
        s.compactKeepTurns = keepTurnsSpinner.value as Int
        s.maxRecentImages = recentImagesSpinner.value as Int

        // MCP 服务
        val wasServerEnabled = s.mcpServerEnabled
        s.mcpServerEnabled = mcpServerEnabledCheck.isSelected
        s.mcpServerPort = (mcpPortSpinner.value as Number).toInt()

        val registry = McpServerRegistry.getInstance()
        val newServers = mcpTableModel.snapshot()
        if (registry.servers != newServers) {
            registry.servers = newServers.toMutableList()
            registry.loadState(registry)
        }

        applyMcpServerState(wasServerEnabled)
        refreshProviderStatus()
    }

    private fun applyMcpServerState(wasEnabled: Boolean) {
        val s = ZhixueyaoSettings.getInstance()
        val service = McpServerController.getInstance()

        if (s.mcpServerEnabled) {
            val ok = service.start(s.mcpServerPort)
            if (!ok) {
                mcpStatusLabel.text = "启动失败，端口 ${s.mcpServerPort} 可能已被占用"
                mcpStatusLabel.foreground = UiKit.danger
            } else {
                mcpStatusLabel.text = "运行中：http://127.0.0.1:${s.mcpServerPort}/"
                mcpStatusLabel.foreground = UiKit.ok
            }
        } else if (wasEnabled) {
            service.stop()
            mcpStatusLabel.text = "已停止"
            mcpStatusLabel.foreground = UiKit.faint
        }
        configSnippetArea.text = McpController.clientConfigJson(s.mcpServerPort)
    }

    override fun reset() {
        val s = ZhixueyaoSettings.getInstance()

        // 通用
        val presetIdx = AgentPresets.all.indexOfFirst { it.id == s.agentPreset }
        presetBox.selectedIndex = if (presetIdx >= 0) presetIdx else 0
        updatePresetSummary()
        languageBox.selectedIndex = when (s.replyLanguage) {
            "zh" -> 1
            "en" -> 2
            else -> 0
        }
        confirmSendCheck.isSelected = s.confirmBeforeSend
        showApplyCheck.isSelected = s.showApplyButton
        showTokenCheck.isSelected = s.showTokenEstimate
        autoCompactCheck.isSelected = s.autoCompact
        contextWindowSpinner.value = s.contextWindow
        keepTurnsSpinner.value = s.compactKeepTurns
        recentImagesSpinner.value = s.maxRecentImages

        // 头像与思考展示
        draftUserAvatar = s.userAvatar.ifBlank { Avatars.DEFAULT_USER }
        draftAiAvatar = s.aiAvatar.ifBlank { Avatars.DEFAULT_AI }
        rebuildAvatarRows()
        reasoningViewBox.selectedIndex = when (s.reasoningView) {
            "live" -> 1
            "hidden" -> 2
            else -> 0
        }
        val checkedLevels = s.thinkingLevels.toSet()
        thinkingChecks.forEach { (name, box) -> box.isSelected = checkedLevels.contains(name) }

        // 技能库
        refreshSkillsInfo()

        // AI 助手行为
        sandboxModeBox.selectedIndex = Sandbox.Mode.entries.indexOf(Sandbox.Mode.byId(s.sandboxMode))
        showTaskListCheck.isSelected = s.showTaskList
        allowAskUserCheck.isSelected = s.allowAskUser

        // 模型
        // 先重建下拉项（含用户自建的服务商），再按已保存的 providerId 定位
        rebuildProviderEntries()
        val pIdx = providerEntries.indexOfFirst { it.id == s.providerId }
        providerBox.selectedIndex = if (pIdx >= 0) pIdx else 0
        baseUrlField.text = s.baseUrl
        apiKeyField.text = s.apiKey
        modelField.text = s.model
        formatBox.selectedIndex = if (s.apiFormat == "anthropic") 1 else 0
        temperatureSpinner.value = s.temperature
        maxTokensSpinner.value = s.maxTokens
        maxRoundsSpinner.value = s.maxToolRounds
        enableMcpCheck.isSelected = s.enableMcp

        val levelIdx = com.zhixueyao.llm.ThinkingLevel.entries.indexOfFirst { it.name == s.thinkingLevel }
        thinkingLevelBox.selectedIndex = if (levelIdx >= 0) levelIdx else 0

        fieldOwnerId = s.providerId
        updatePresetNote()
        refreshProviderStatus()
        refreshThinkingHint()
        refreshModelList()

        // MCP
        mcpTableModel.setData(McpServerRegistry.getInstance().servers)
        mcpServerEnabledCheck.isSelected = s.mcpServerEnabled
        mcpPortSpinner.value = s.mcpServerPort

        val service = McpServerController.getInstance()
        mcpStatusLabel.text = if (service.isRunning) {
            "运行中：http://127.0.0.1:${s.mcpServerPort}/"
        } else {
            "未启用"
        }
        mcpStatusLabel.foreground = if (service.isRunning) UiKit.ok else UiKit.faint
        configSnippetArea.text = McpController.clientConfigJson(s.mcpServerPort)
        testResultLabel.text = ""
    }

    override fun disposeUIResources() {
        rootPanel = null
        contentPanel = null
    }

    // ---------------- 界面构建：外壳 ----------------

    private fun buildRoot(): JPanel {
        // 高度封顶。不封顶会怎样：窗口尺寸按「内容全展开」的高度去要，
        // 而四个设置页里模型页很长（服务商 + 生成参数 + 模型清单，轻松过千像素），
        // 于是设置窗一打开就是满屏高 —— 用户反馈「设置窗口太高了」。
        // 封顶后窗口是正常尺寸，超出部分由右侧内容区自己滚动
        // （平台默认套的那层滚动容器已被 NoScroll 去掉，见类注释）。
        val root = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun getPreferredSize(): Dimension {
                val natural = super.getPreferredSize()
                return Dimension(natural.width, minOf(natural.height, SETTINGS_WINDOW_HEIGHT))
            }
        }
        root.add(buildTopBar(), BorderLayout.NORTH)
        root.add(buildBody(), BorderLayout.CENTER)
        return root
    }

    /** 顶栏：标题 + 右侧「打开配置文件」。 */
    private fun buildTopBar(): JComponent {
        val bar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UiKit.header
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(UiKit.border),
                JBUI.Borders.empty(8, 14)
            )
        }
        bar.add(UiKit.title("止血药设置"), BorderLayout.WEST)

        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        right.add(UiKit.textButton("打开配置文件", "查看插件持久化配置的实际内容") { openConfigFile() })
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    /** 主体：左侧导航 + 右侧内容。 */
    private fun buildBody(): JComponent {
        val body = JBPanel<JBPanel<*>>(BorderLayout())

        val navWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UiKit.header
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineRight(UiKit.border),
                JBUI.Borders.empty(10, 8)
            )
            preferredSize = Dimension(148, 0)
            minimumSize = Dimension(148, 0)
        }
        navWrapper.add(navPanel, BorderLayout.NORTH)

        // 右侧是唯一滚动区域。导航栏放在滚动区域外，始终固定在左侧，
        // 不会因为模型页或 MCP 页内容很长而跟着向上滚出视口。
        val cards = JBPanel<JBPanel<*>>(cardLayout).apply { isOpaque = false }
        cards.add(buildGeneralPage(), "general")
        cards.add(buildModelPage(), "model")
        cards.add(buildMcpPage(), "plugins")
        cards.add(buildPresetsPage(), "presets")
        // 临时会话管理：全局临时产物的「看得见 + 能删」入口。
        // 那些目录不在任何工程里，不给入口就等于永远不会被清理。
        cards.add(tempSessionsPanel.build(), "temp")
        contentPanel = cards
        val contentScroll = JBScrollPane(cards).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            isOpaque = false
            viewport.isOpaque = false
        }

        SECTIONS.forEachIndexed { index, section ->
            navPanel.add(buildNavButton(index, section.second, section.third))
            navPanel.add(Box.createVerticalStrut(2))
        }

        body.add(navWrapper, BorderLayout.WEST)
        body.add(contentScroll, BorderLayout.CENTER)

        select(initialSection)
        return body
    }

    private fun buildNavButton(index: Int, label: String, icon: javax.swing.Icon): JComponent {
        val button = object : JPanel(BorderLayout()) {
            private var hovered = false

            init {
                isOpaque = true
                background = UiKit.header
                border = JBUI.Borders.empty(7, 10)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                // 显式左对齐：导航栏是纵向 BoxLayout，混用默认的居中(0.5)会让
                // 高亮块位置随兄弟项变化而偏移（详见 UiKit 里 alignmentX 的说明）
                alignmentX = java.awt.Component.LEFT_ALIGNMENT

                add(
                    JLabel(label).apply {
                        this.icon = icon
                        iconTextGap = 8
                        font = font.deriveFont(font.size.toFloat() + 0.5f)
                    },
                    BorderLayout.WEST
                )

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) = select(index)
                })
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val active = selectedSection == index
                if (active || hovered) {
                    val g2 = g.create()
                    try {
                        val base = UiKit.hover
                        g2.color = if (active) {
                            base
                        } else {
                            Color(base.red, base.green, base.blue, 100)
                        }
                        g2.fillRoundRect(0, 0, width, height, UiKit.radiusSmall, UiKit.radiusSmall)
                    } finally {
                        g2.dispose()
                    }
                }
                super.paintComponent(g)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        return button
    }

    private fun select(index: Int) {
        if (index !in SECTIONS.indices) return
        selectedSection = index
        contentPanel?.let { cardLayout.show(it, SECTIONS[index].first) }
        navPanel.components.filterIsInstance<JPanel>().forEachIndexed { i, panel ->
            val label = panel.components.filterIsInstance<JLabel>().firstOrNull() ?: return@forEachIndexed
            label.foreground = if (i == index) UiKit.text else UiKit.subtle
            panel.repaint()
        }
    }

    // ---------------- 通用页 ----------------

    private fun buildGeneralPage(): JComponent {
        val form = newForm()
        val panel = form.panel

        form.section("回复")
        form.row("回复语言", languageBox, "模型默认用中文回答；自动即交给模型自己判断")

        form.section("界面")
        form.wideRow(showTokenCheck)
        form.wideRow(showApplyCheck)
        form.wideRow(confirmSendCheck)

        // ---- 头像 ----
        // 头像影响的是「谁在说话」的第一眼辨识度，所以放在界面分区里而不是藏到关于页
        form.section("头像")
        form.row("AI 头像", aiAvatarRow, "换一个头像，聊天里 AI 的气泡头就跟着变")
        form.row("我的头像", userAvatarRow, "可选内置头像，或选一张本地图片（会自动裁成圆形）")

        // ---- 思考过程 ----
        form.section("思考过程")
        form.row(
            "展示方式",
            reasoningViewBox,
            "「实时展开」边生成边显示思维链，正文出现后自动收起；「不显示」连入口都不给"
        )
        form.hintRow(
            UiKit.hint("思考强度档位在「模型」页勾选 —— 只有勾选过的档位才会出现在聊天窗口的「思考模式」下拉里。")
        )

        // ---- 技能库 ----
        // 这一块要「看得见」：用户问「我有哪些 skills」时，设置页里应当能直接看全，
        // 而不是只有一句数量统计。
        form.section("技能库")
        form.wideRow(skillsInfoLabel)
        form.wideRow(skillsPathLabel)

        skillsListBody.layout = BoxLayout(skillsListBody, BoxLayout.Y_AXIS)
        skillsListBody.isOpaque = false
        val skillsListCard = UiKit.roundedCard(pad = JBUI.insets(8, 10, 8, 10))
        skillsListCard.add(
            object : JBScrollPane(skillsListBody) {
                init {
                    setBorder(JBUI.Borders.empty())
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                    isOpaque = false
                    viewport.isOpaque = false
                }

                // 限高：技能可能有几十个，不限的话设置页会被撑得很长
                override fun getPreferredSize(): Dimension =
                    Dimension(super.getPreferredSize().width, 210)

                override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 210)
            },
            BorderLayout.CENTER
        )
        form.wideRow(skillsListCard)

        val skillButtons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        skillButtons.add(UiKit.textButton("打开项目技能目录", "随工程走：提交进仓库，同事拉下来就有一份") {
            openSkillsDir(projectFirst = true)
        })
        skillButtons.add(UiKit.textButton("打开全局技能目录", "跨项目复用，换个工程也还在") {
            openSkillsDir(projectFirst = false)
        })
        skillButtons.add(UiKit.textButton("重新扫描", "重新读取技能目录") { refreshSkillsInfo() })
        form.wideRow(skillButtons)
        form.wideRow(
            UiKit.hint(
                "一个技能 = 一个目录 + 目录里的 SKILL.md（frontmatter 写 name 与 description，正文写步骤）。" +
                    "AI 只会先看到「名字 + 说明」，判断用得上才用 skill 工具取回正文 —— 所以技能再多也不占提示词。"
            )
        )

        // ---- AI 助手行为 ----
        form.section("AI 助手行为")
        form.row(
            "沙盒权限",
            sandboxModeBox,
            "「仅当前项目」= AI 只能读写当前项目目录内的文件，越界会被拦下并说明原因"
        )
        form.wideRow(showTaskListCheck)
        form.wideRow(allowAskUserCheck)

        // ---- 长会话 ----
        form.section("长会话")
        form.wideRow(autoCompactCheck)
        form.row(
            "上下文窗口",
            contextWindowSpinner,
            "单位 token。填 0 = 按模型名自动推断（如 claude→200k、gpt-4o→128k）；填错只会影响压缩时机，不影响回答"
        )
        form.row(
            "保留最近轮数",
            keepTurnsSpinner,
            "压缩时最近这么多轮**完全不改**，保证「刚才在说什么」不会丢"
        )
        form.row(
            "保留旧图片张数",
            recentImagesSpinner,
            "只约束**已经看过**的旧图（图片每轮都会重发，一张截图上万 token）。" +
                "填 0 = 不限制。模型还没看过的图不受这个限制"
        )

        form.section("关于")
        form.row("版本", JBLabel("0.2.0"))
        form.row("平台", JBLabel("Android Studio / IntelliJ 261+"))
        form.row("内置工具", JBLabel("${com.zhixueyao.agent.ToolRegistry.all.size} 个"))
        form.row("选中代码提问", JBLabel("Ctrl+Alt+Z"))
        form.wideRow(UiKit.hint("所有配置都存在本机 IDE 配置目录，不会上传到任何服务器。"))
        form.wideRow(UiKit.hint("API 密钥仅保存在 zhixueyao.xml 中，随 IDE 配置一同管理。"))

        form.finish()
        return panel
    }

    // ---------------- 头像选择器 ----------------

    /** 重建两个头像行（选中项变化后要重画选中圆环） */
    private fun rebuildAvatarRows() {
        // AI 头像同样支持自定义图片：和用户头像走同一套选择器
        fillAvatarRow(aiAvatarRow, Avatars.aiPresets, { draftAiAvatar }, { draftAiAvatar = it }, withFile = true)
        fillAvatarRow(userAvatarRow, Avatars.userPresets, { draftUserAvatar }, { draftUserAvatar = it }, withFile = true)
    }

    private fun fillAvatarRow(
        row: JPanel,
        presets: List<Avatars.Preset>,
        current: () -> String,
        onPick: (String) -> Unit,
        withFile: Boolean
    ) {
        row.removeAll()
        for (preset in presets) {
            row.add(
                avatarChoice(
                    Avatars.presetIcon(preset, Avatars.PREVIEW_SIZE),
                    preset.label,
                    current() == preset.id
                ) {
                    onPick(preset.id)
                    rebuildAvatarRows()
                }
            )
        }

        if (withFile) {
            val value = current()
            // 自定义图片生效时，把裁剪后的实际头像也显示出来 —— 否则用户看不到自己选了什么
            if (value.startsWith(Avatars.FILE_PREFIX)) {
                Avatars.imageIcon(value.removePrefix(Avatars.FILE_PREFIX), Avatars.PREVIEW_SIZE)?.let { icon ->
                    row.add(avatarChoice(icon, "当前使用的自定义图片", true) { })
                }
            }
            row.add(
                UiKit.textButton("选择图片…", "选一张本地图片作为头像（会复制进 IDE 配置目录并裁成圆形）") {
                    pickAvatarFile()
                }
            )
            if (value.startsWith(Avatars.FILE_PREFIX)) {
                row.add(UiKit.textButton("改用内置头像", "回到内置的圆形头像") {
                    onPick(Avatars.DEFAULT_USER)
                    rebuildAvatarRows()
                })
            }
        }

        row.revalidate()
        row.repaint()
    }

    /**
     * 单个头像选项：圆形图标 + 选中时的品牌色圆环。
     *
     * 选中态画的是**圆环**而不是方框 —— 头像本身是圆的，方形描边会破坏这个视觉语言
     * （和「气泡圆角必须自绘」是同一个道理）。
     */
    private fun avatarChoice(
        icon: javax.swing.Icon,
        tooltip: String,
        selected: Boolean,
        onClick: () -> Unit
    ): JComponent = object : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tooltip
            add(JLabel(icon), BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

        override fun paintComponent(g: java.awt.Graphics) {
            if (selected) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    g2.color = UiKit.brandFade(90)
                    g2.fillOval(0, 0, width, height)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    /**
     * 选一张本地图片当头像。
     *
     * 会把图片**复制**进 IDE 配置目录（见 Avatars.importUserAvatar）再引用 ——
     * 直接存原路径的话，用户挪走或删掉那张图，头像就变成空框。
     */
    private fun pickAvatarFile() {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "选择头像图片"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "图片 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"
            )
        }
        if (chooser.showOpenDialog(rootPanel) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        val saved = runCatching { Avatars.importUserAvatar(file) }.getOrNull()
        if (saved == null) {
            Messages.showErrorDialog(
                rootPanel,
                "这张图片读不了，换一张试试（支持 PNG / JPG / GIF / BMP）。",
                "止血药"
            )
            return
        }
        draftUserAvatar = saved
        rebuildAvatarRows()
    }

    /** 勾选中的思考档位（按枚举顺序，便于比对与稳定输出） */
    private fun currentThinkingLevels(): List<String> =
        ThinkingLevel.entries.filter { thinkingChecks[it.name]?.isSelected == true }.map { it.name }

    /**
     * 刷新技能库展示：数量 + 各处目录 + 逐条清单。
     *
     * 查找顺序：**项目优先，其次全局**（同名时项目里的覆盖全局的），
     * 另外会把机器上其他 agent 的技能库（~/.agents/skills 等）也扫出来供参考。
     */
    private fun refreshSkillsInfo() {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        val list = com.zhixueyao.agent.Skills.all(project)

        skillsInfoLabel.text = if (list.isEmpty()) {
            "还没有技能 —— 点「打开技能目录」把技能放进去"
        } else {
            "已发现 " + list.size + " 个技能（项目优先，其次全局）"
        }
        skillsInfoLabel.foreground = if (list.isEmpty()) UiKit.warn else UiKit.text

        // 路径行：把两个可写目录都写出来，用户才知道该往哪放
        val dirs = buildList {
            com.zhixueyao.agent.Skills.projectDir(project)?.let {
                add("项目：" + it.path + if (it.isDirectory) "" else "（未创建）")
            }
            add("全局：" + com.zhixueyao.agent.Skills.globalDir().path)
            com.zhixueyao.agent.Skills.externalDirs().forEach { add("外部（只读）：" + it.path) }
        }
        skillsPathLabel.text = "<html><body style='width:420px'>" + dirs.joinToString("<br>") + "</body></html>"
        skillsPathLabel.foreground = UiKit.subtle
        skillsPathLabel.font = skillsPathLabel.font.deriveFont(skillsPathLabel.font.size - 1f)

        // 逐条清单
        skillsListBody.removeAll()
        if (list.isEmpty()) {
            skillsListBody.add(
                JBLabel("（这里会列出已发现的技能）").apply {
                    foreground = UiKit.faint
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                }
            )
        }
        for (skill in list) {
            val row = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply {
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0)
                maximumSize = Dimension(Int.MAX_VALUE, 52)
            }
            val name = JBLabel(skill.name).apply {
                font = font.deriveFont(Font.BOLD, font.size.toFloat())
                foreground = UiKit.text
            }
            val head = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(name)
                add(
                    JBLabel(skill.source).apply {
                        font = font.deriveFont(font.size - 2f)
                        foreground = if (skill.source == "外部") UiKit.warn else UiKit.faint
                    }
                )
            }
            row.add(head, BorderLayout.NORTH)
            row.add(
                JBLabel("<html><body style='width:400px'>" + skill.description.replace("<", "&lt;") + "</body></html>").apply {
                    font = font.deriveFont(font.size - 1f)
                    foreground = UiKit.subtle
                },
                BorderLayout.CENTER
            )
            // 点名称直接打开技能所在目录，省得自己去翻
            name.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            name.toolTipText = skill.dir.path + "\n单击在文件管理器中打开"
            name.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    runCatching { java.awt.Desktop.getDesktop().open(skill.dir) }
                }
            })

            // 删除：**外部目录不给删**（那是别的工具在管的，比如 ~/.workbuddy/skills），
            // 只对本插件自己的两个库开放。删的是磁盘目录，所以要二次确认。
            val deletable = skill.source != "外部"
            val del = UiKit.iconButton(
                AllIcons.Actions.GC,
                if (deletable) "删除这个技能（连同它的附属文件）"
                else "外部技能目录只读，不能在这里删"
            ) {
                if (!deletable) {
                    Messages.showInfoMessage(
                        rootPanel,
                        "「${skill.name}」来自外部技能目录：\n${skill.dir.parentFile?.path}\n\n" +
                            "那里由别的工具管理，本插件只读不改。要删请自己去那个目录处理。",
                        "止血药 · 技能库"
                    )
                    return@iconButton
                }
                // rootPanel 是可空的（面板还没建好时）——确认框要一个非空父组件，
                // 这种情况下静默返回更安全（宁可没删成，也不要删错）
                val parent: java.awt.Component = rootPanel ?: return@iconButton
                val answer = Messages.showYesNoDialog(
                    parent,
                    "将删除技能「${skill.name}」（${skill.source}技能库）：\n${skill.dir.path}\n\n" +
                        "它会连同目录里的附属文件一起删掉，确定吗？",
                    "删除技能",
                    Messages.getWarningIcon()
                )
                if (answer != Messages.YES) return@iconButton
                runCatching { skill.dir.walkBottomUp().forEach { it.delete() } }
                com.zhixueyao.agent.Skills.invalidateCache()
                refreshSkillsInfo()
            }.apply {
                isEnabled = true
                if (!deletable) foreground = UiKit.faint
            }
            val tail = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(del)
            }
            row.add(tail, BorderLayout.EAST)
            skillsListBody.add(row)
        }

        skillsInfoLabel.revalidate()
        skillsPathLabel.revalidate()
        skillsListBody.revalidate()
        skillsListBody.repaint()
    }

    /**
     * 打开技能目录（不存在就建出来，省得用户自己找路径）。
     *
     * @param projectFirst true = 项目技能库（`<工程>/.zhixueyao/skills`），
     *        false = 全局技能库（`~/.zhixueyao/skills`）。
     *        两个库的用途不一样，所以给两个按钮 —— 只给一个的话，
     *        用户想往全局放东西会被带到项目目录里，反之亦然。
     */
    private fun openSkillsDir(projectFirst: Boolean) {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        val dirs = com.zhixueyao.agent.Skills.ensureDirs(project)
        val target = (if (projectFirst) dirs.firstOrNull() else dirs.lastOrNull()) ?: return
        runCatching {
            java.awt.Desktop.getDesktop().open(target)
        }.onFailure {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                rootPanel,
                "技能目录：" + target.path,
                "止血药 · 技能目录"
            )
        }
        refreshSkillsInfo()
    }

    /** 当前选中的沙盒档位 */
    private fun currentSandboxMode(): Sandbox.Mode =
        Sandbox.Mode.entries.getOrElse(sandboxModeBox.selectedIndex) { Sandbox.Mode.PROJECT }

    // ---------------- Agent 预设页 ----------------

    private fun buildPresetsPage(): JComponent {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(14, 18)
        }

        val form = newFormFor(panel)
        form.section("运行模式")
        presetBox.addActionListener { updatePresetSummary() }
        form.row("选择预设", presetBox)
        form.hintRow(presetSummary)
        form.hintRow(presetTools)

        form.section("预设说明")
        form.wideRow(UiKit.hint("预设决定 AI 能用哪些工具 —— 这是能力层面的限制，不只是提示词叮嘱。"))
        form.wideRow(UiKit.hint("例如「只读研究」模式下，写文件与执行构建的工具根本不会暴露给模型。"))

        form.finish()

        // 预设一览卡片
        val listCard = UiKit.roundedCard(pad = JBUI.insets(12, 14, 12, 14))
        listCard.add(UiKit.sectionTitle("全部预设"), BorderLayout.NORTH)
        val listBody = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        for (p in AgentPresets.all) {
            val rowPanel = JBPanel<JBPanel<*>>(BorderLayout(10, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 0)
                maximumSize = Dimension(Int.MAX_VALUE, 52)
            }
            val name = JBLabel(p.label).apply { foreground = UiKit.text }
            rowPanel.add(name, BorderLayout.WEST)
            val desc = JBLabel("<html><div width='420'>${p.summary}</div></html>").apply {
                foreground = UiKit.subtle
                font = font.deriveFont(font.size - 1f)
            }
            rowPanel.add(desc, BorderLayout.CENTER)
            listBody.add(rowPanel)
        }
        listCard.add(listBody, BorderLayout.CENTER)

        val wrapper = JBPanel<JBPanel<*>>(BorderLayout(0, 14)).apply { isOpaque = false }
        wrapper.add(panel, BorderLayout.NORTH)
        wrapper.add(listCard, BorderLayout.CENTER)

        return wrapper
    }

    private fun updatePresetSummary() {
        val p = AgentPresets.byId(currentPresetId())
        presetSummary.text = "· ${p.summary}"
        presetSummary.foreground = UiKit.faint
        presetSummary.font = presetSummary.font.deriveFont(presetSummary.font.size - 1f)
        presetTools.text = "· ${AgentPresets.toolSummary(p.id)}"
        presetTools.foreground = UiKit.subtle
        presetTools.font = presetTools.font.deriveFont(presetTools.font.size - 1f)
    }

    // ---------------- 模型页 ----------------

    /**
     * 服务商下拉里的一项。
     *
     * 预设与自定义混在同一个下拉里，靠 [preset] 是否为空区分 ——
     * 对用户来说它们都是「一家服务商」，没必要分两个下拉去选。
     */
    private data class ProviderEntry(
        val id: String,
        val label: String,
        val preset: com.zhixueyao.llm.ProviderPreset?,
        /** 哨兵项：不是真实服务商，选中它＝触发「新增」 */
        val isAddNew: Boolean = false
    )

    /** 与 [providerBox] 的索引一一对应的条目表 */
    private var providerEntries: List<ProviderEntry> = emptyList()

    /** 重建下拉项：预设在前，用户自建的服务商排在其后，末尾挂「新增」入口 */
    private fun rebuildProviderEntries() {
        val s = ZhixueyaoSettings.getInstance()
        val keepId = currentProviderIdIfAny()
        val entries = mutableListOf<ProviderEntry>()
        for (p in Providers.presets) {
            entries.add(ProviderEntry(p.id, p.label, p))
        }
        for (c in s.customProviders) {
            val name = c.name.ifBlank { "未命名服务商" }
            entries.add(ProviderEntry(c.id, "★ $name", null))
        }
        // 「新增」直接做成下拉里的一项。
        // 原先只在右侧放了一个「新增」小按钮，结果没人找得到 —— 用户打开下拉
        // 只看到一串预设，会以为压根不支持自建服务商。放在列表末尾最符合直觉：
        // 想加一家，就在「选哪家」的地方加。
        entries.add(ProviderEntry(ADD_NEW_SENTINEL, ADD_NEW_LABEL, null, isAddNew = true))
        providerEntries = entries

        // 更新下拉内容。用 removeAllItems + addItem 会触发两次事件，
        // 所以要临时摘掉监听，避免在重建过程中触发切换逻辑
        val listeners = providerBox.actionListeners
        listeners.forEach { providerBox.removeActionListener(it) }
        providerBox.removeAllItems()
        for (e in entries) providerBox.addItem(e.label)
        val idx = entries.indexOfFirst { it.id == keepId }
        // 定位到「新增」上说明 keepId 是哨兵 —— 不该停在那里，回落到第一家
        providerBox.selectedIndex = if (idx >= 0 && !entries[idx].isAddNew) idx else 0
        listeners.forEach { providerBox.addActionListener(it) }
    }

    private fun currentProviderIdIfAny(): String =
        providerEntries.getOrNull(providerBox.selectedIndex)?.id
            // 哨兵项不是服务商，别让它冒充成 providerId
            ?.takeIf { it != ADD_NEW_SENTINEL }
            ?: ""

    private fun currentEntry(): ProviderEntry? =
        providerEntries.getOrNull(providerBox.selectedIndex)

    private fun buildModelPage(): JComponent {
        val form = newForm()
        val panel = form.panel

        form.section("服务商")
        providerBox.addActionListener {
            val entry = currentEntry() ?: return@addActionListener
            // 选中「新增」＝要建一家新的，弹完对话框把选中项拨回真实服务商
            if (entry.isAddNew) {
                addCustomProvider()
                return@addActionListener
            }
            onProviderSwitched(entry.id)
        }

        // 让哨兵项在下拉里显成品牌色，一眼区分「这是一条操作」而不是「一家服务商」
        providerBox.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus
                )
                if (index >= 0 && providerEntries.getOrNull(index)?.isAddNew == true) {
                    c.foreground = if (isSelected) c.foreground else UiKit.brand
                }
                return c
            }
        }

        // 服务商行：下拉 + 自定义服务商的管理按钮
        val providerRow = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply { isOpaque = false }
        providerRow.add(providerBox, BorderLayout.CENTER)
        val providerBtns = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        providerBtns.add(UiKit.textButton("编辑", "修改当前自建服务商的名称、地址等") { editCustomProvider() })
        providerBtns.add(UiKit.textButton("删除", "删除当前自建服务商") { removeCustomProvider() })
        providerRow.add(providerBtns, BorderLayout.EAST)
        form.row("选择服务商", providerRow)

        form.hintRow(providerStatusLabel)
        form.hintRow(presetNote)

        form.row("接口地址", baseUrlField, "OpenAI 兼容端点通常以 /v1 结尾")
        form.row("API 密钥", apiKeyField, "只保存在本机 IDE 配置里，不会外传；各服务商分别记忆")

        // 模型名 + 拉取按钮同一行：不必先去服务商官网翻文档再回来手打模型名
        val modelRow = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply { isOpaque = false }
        modelRow.add(modelField, BorderLayout.CENTER)
        modelRow.add(UiKit.textButton("拉取模型", "从服务商接口读取可用模型，点选即填入") { pickModel() }, BorderLayout.EAST)
        form.row("模型名称", modelRow, "点「拉取模型」从服务商读取列表，也可以直接手输")
        form.row("协议格式", formatBox, "不确定时保持「OpenAI 兼容协议」")

        form.section("思考能力")
        form.row("思考强度", thinkingLevelBox, "")
        form.hintRow(thinkingHintLabel)
        // 可选清单：勾选哪些档位，聊天窗口的「思考模式」下拉里就出现哪些
        val thinkingChecksPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            for (level in ThinkingLevel.entries) {
                thinkingChecks[level.name]?.let { box ->
                    box.toolTipText = level.description
                    add(box)
                }
            }
        }
        form.row("快捷档位", thinkingChecksPanel, "勾选后才会出现在聊天窗口的「思考模式」下拉里；不勾选则只有「默认」")
        form.row("参数方言", thinkingStyleBox, "决定用哪种参数名发思考配置；发错字段服务商会拒绝请求")

        form.section("生成参数")
        val tempPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        tempPanel.add(temperatureSpinner)
        temperatureSpinner.preferredSize = Dimension(90, temperatureSpinner.preferredSize.height)
        form.row("随机性", tempPanel, "0 最稳定，1 以上更有创造性")
        form.row("单次最大输出", maxTokensSpinner, "单位 token，太大可能触发服务端上限")
        form.row("工具调用上限", maxRoundsSpinner, "单轮对话中最多执行多少步工具，防止死循环")

        // ---- 模型清单 ----
        // 一个服务商可以有好几个模型，这里管理「以后能从菜单里直接切」的那批。
        // 放在生成参数之后，因为它是「配置完接口和参数之后要做的事」。
        form.section("模型清单")

        modelChipsPanel.isOpaque = false
        val chipsScroll = JBScrollPane(modelChipsPanel).apply {
            border = UiKit.cardBorder(UiKit.border, UiKit.radiusSmall)
            preferredSize = Dimension(0, 74)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            isOpaque = false
            viewport.isOpaque = false
        }
        // JBScrollPane 的视口默认不跟随宽度换行，要让 FlowLayout 真的「流」起来，
        // 必须把面板的宽度绑到视口上，否则它会撑成一行超宽然后出现横向滚动条
        modelChipsPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                modelChipsPanel.preferredSize =
                    Dimension(modelChipsPanel.parent?.width ?: 0, modelChipsPanel.preferredSize.height)
            }
        })
        form.wideRow(chipsScroll)

        val modelBtnRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        modelBtnRow.add(UiKit.textButton("拉取并添加…", "从服务商读取可用模型，可多选后批量加入") { pickModel() })
        modelBtnRow.add(UiKit.textButton("手动添加…", "直接输入模型名加入清单") { addModelManually() })
        modelBtnRow.add(UiKit.textButton("清空", "移除当前服务商的全部模型") { clearModelList() })
        form.wideRow(modelBtnRow)
        form.hintRow(modelListHint)

        form.section("能力")
        form.wideRow(enableMcpCheck)

        form.wideRow(UiKit.textButton("测试连接") { testConnection() })
        form.hintRow(testResultLabel)

        form.finish()
        return panel
    }

    // ---------------- 模型清单管理 ----------------

    /**
     * 重建模型清单的标签区。
     *
     * 每个模型一个标签：点一下 = 设为当前模型，右侧的 × = 从清单移除。
     * 当前正在用的那个用品牌色描边标出来。
     */
    private fun refreshModelList() {
        val s = ZhixueyaoSettings.getInstance()
        val pid = currentProviderId()
        val models = s.modelListOf(pid)
        val current = modelField.text.trim()

        modelChipsPanel.removeAll()
        if (models.isEmpty()) {
            modelChipsPanel.add(JBLabel("还没有添加模型 —— 点下面的「拉取并添加」或「手动添加」").apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UiKit.faint
            })
        } else {
            for (m in models) {
                // 两个 lambda 都要显式传参 —— 尾随 lambda 语法只匹配最后一个参数，
                // 写 `f(a) { } { }` 会把两个块都算给最后一个，第一个就缺了
                modelChipsPanel.add(
                    modelChip(
                        name = m,
                        active = m == current,
                        onPick = {
                            modelField.text = m
                            s.rememberModel(pid, m)
                            refreshModelList()
                            testResultLabel.text = "已切换到：$m"
                            testResultLabel.foreground = UiKit.ok
                        },
                        onRemove = {
                            s.removeModel(pid, m)
                            // 移除的正好是当前正在用的，就把模型框同步成回落后剩下的那个
                            if (m == modelField.text.trim()) modelField.text = s.modelOf(pid)
                            refreshModelList()
                        }
                    )
                )
            }
        }
        modelListHint.text = if (models.isEmpty()) {
            "菜单里会按服务商展开这些模型，选一次即可切换"
        } else {
            "共 ${models.size} 个模型；点击标签即切换到该模型，点 × 移除"
        }
        modelChipsPanel.revalidate()
        modelChipsPanel.repaint()
    }

    /** 一个模型标签。带悬停高亮与移除按钮 */
    private fun modelChip(
        name: String,
        active: Boolean,
        onPick: () -> Unit,
        onRemove: () -> Unit
    ): JComponent = object : JBPanel<JBPanel<*>>(BorderLayout(4, 0)) {
        private var hovered = false

        init {
            isOpaque = false
            border = javax.swing.BorderFactory.createCompoundBorder(
                UiKit.cardBorder(if (active) UiKit.brand else UiKit.border, UiKit.radiusSmall),
                JBUI.Borders.empty(3, 8)
            )
            val label = JBLabel(name).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = if (active) UiKit.brand else UiKit.text
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = if (active) "正在使用" else "点击切换到这个模型"
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = onPick()
                })
            }
            add(label, BorderLayout.CENTER)

            val close = UiKit.linkLabel("×") { onRemove() }.apply {
                toolTipText = "从清单移除"
                font = font.deriveFont(Font.BOLD, font.size + 1f)
            }
            add(close, BorderLayout.EAST)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: java.awt.Graphics) {
            if (hovered) {
                val g2 = g.create()
                try {
                    g2.color = UiKit.hover
                    g2.fillRoundRect(0, 0, width, height, UiKit.radiusSmall, UiKit.radiusSmall)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    /** 手动输入模型名加入清单 —— 拉不到列表（内网 / 未实现 /models）时仍能用 */
    private fun addModelManually() {
        val s = ZhixueyaoSettings.getInstance()
        val pid = currentProviderId()
        if (pid.isBlank()) {
            testResultLabel.text = "请先选择服务商"
            testResultLabel.foreground = UiKit.danger
            return
        }
        val input = Messages.showInputDialog(
            rootPanel ?: return,
            "输入模型名，多个可用逗号或换行分隔：",
            "添加模型",
            null
        )?.trim().orEmpty()
        if (input.isEmpty()) return

        val names = input.split(',', '\n', '，')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val n = s.addModels(pid, names)
        refreshModelList()
        testResultLabel.text = if (n > 0) "已加入 $n 个模型" else "这些模型都已在列表里"
        testResultLabel.foreground = if (n > 0) UiKit.ok else UiKit.faint
    }

    private fun clearModelList() {
        val s = ZhixueyaoSettings.getInstance()
        val pid = currentProviderId()
        if (pid.isBlank()) return
        val count = s.modelListOf(pid).size
        if (count == 0) {
            testResultLabel.text = "清单本来就是空的"
            testResultLabel.foreground = UiKit.faint
            return
        }
        val ok = Messages.showYesNoDialog(
            rootPanel ?: return,
            "确定清空当前服务商的 $count 个模型吗？\n\n只影响这份清单，不会删除服务商本身。",
            "清空模型清单",
            null
        ) == Messages.YES
        if (!ok) return
        s.clearModels(pid)
        refreshModelList()
        testResultLabel.text = "已清空模型清单"
        testResultLabel.foreground = UiKit.faint
    }

    // ---------------- 自定义服务商管理 ----------------

    private fun addCustomProvider() {
        val s = ZhixueyaoSettings.getInstance()
        val id = s.nextCustomId()
        val created = CustomProviderDialog.show(
            parent = rootPanel,
            title = "新增服务商",
            initial = com.zhixueyao.llm.CustomProvider(
                id = id,
                name = "我的服务商 ${s.customProviders.size + 1}",
                baseUrl = "",
                apiFormat = "openai",
                thinkingStyle = "NONE"
            )
        ) ?: run {
            // 用户取消了 —— 下拉此刻多半还停在「新增」那条上，
            // 不拨回去就会显示成一个不存在的服务商，看着像创建成功了
            restoreSelection()
            return
        }

        s.upsertCustom(created)
        rebuildProviderEntries()
        providerBox.selectedIndex = providerEntries.indexOfFirst { it.id == created.id }
        onProviderSwitched(created.id)
        updateStatusNote("已添加服务商「${created.name}」，请填写接口地址与密钥")
    }

    /**
     * 把下拉选中项拨回某个真实服务商。
     *
     * 用在「选中新增 → 又取消」之后：哨兵项不是服务商，停在它上面会让
     * 下方所有输入框都处于一个没有归属的状态。
     */
    private fun restoreSelection() {
        val s = ZhixueyaoSettings.getInstance()
        val idx = providerEntries.indexOfFirst { it.id == s.providerId }
        providerBox.selectedIndex = if (idx >= 0) idx else 0
        onProviderSwitched(currentProviderId())
    }

    private fun editCustomProvider() {
        val entry = currentEntry() ?: return
        val s = ZhixueyaoSettings.getInstance()
        val existing = s.customById(entry.id)
        if (existing == null) {
            updateStatusNote("只有自建服务商可以编辑名称；预设服务商请直接用上方的地址与密钥")
            return
        }

        // 先把输入框里可能改过的内容归档，再弹对话框 —— 否则用户改了地址
        // 又点「编辑」，会发现刚才的修改没了
        s.rememberKey(existing.id, String(apiKeyField.password))
        s.rememberModel(existing.id, modelField.text.trim())
        s.rememberBaseUrl(existing.id, baseUrlField.text.trim())

        val edited = CustomProviderDialog.show(
            parent = rootPanel,
            title = "编辑服务商",
            initial = existing.copy(
                baseUrl = baseUrlField.text.trim(),
                model = modelField.text.trim()
            )
        ) ?: return

        s.upsertCustom(edited)
        rebuildProviderEntries()
        providerBox.selectedIndex = providerEntries.indexOfFirst { it.id == edited.id }
        onProviderSwitched(edited.id)
        updateStatusNote("已保存「${edited.name}」")
    }

    private fun removeCustomProvider() {
        val entry = currentEntry() ?: return
        val s = ZhixueyaoSettings.getInstance()
        if (s.customById(entry.id) == null) {
            updateStatusNote("预设服务商不能删除")
            return
        }
        val confirmed = Messages.showDialog(
            rootPanel ?: return,
            "确定删除服务商「${entry.label.removePrefix("★ ")}」吗？\n\n它名下的密钥、模型与地址记录会一并清除。",
            "删除服务商",
            arrayOf("删除", "取消"),
            1,
            AllIcons.General.QuestionDialog
        )
        if (confirmed != 0) return

        s.removeCustom(entry.id)
        // 删的如果是当前正在用的那家，回落到第一个预设
        if (s.providerId == entry.id) {
            s.switchProvider(Providers.presets.first().id)
        }
        rebuildProviderEntries()
        providerBox.selectedIndex = providerEntries.indexOfFirst { it.id == s.providerId }.coerceAtLeast(0)
        onProviderSwitched(currentProviderId())
        updateStatusNote("已删除")
    }

    private fun updateStatusNote(text: String) {
        testResultLabel.text = text
        testResultLabel.foreground = UiKit.subtle
    }

    /**
     * 服务商切换：先把当前输入框的内容归档到原服务商，再载入新服务商记住的那份。
     * 这是「多服务商独立存密钥」的核心 —— 用户来回切换不会丢配置。
     */
    private fun onProviderSwitched(newId: String) {
        val s = ZhixueyaoSettings.getInstance()

        if (fieldOwnerId.isNotBlank() && fieldOwnerId != newId) {
            s.rememberKey(fieldOwnerId, String(apiKeyField.password))
            s.rememberModel(fieldOwnerId, modelField.text.trim())
            s.rememberBaseUrl(fieldOwnerId, baseUrlField.text.trim())
        }

        fieldOwnerId = newId
        val custom = s.customById(newId)
        val preset = Providers.byId(newId)

        val rememberedKey = s.keyOf(newId)
        val rememberedModel = s.modelOf(newId)
        val rememberedUrl = s.baseUrlOf(newId)

        baseUrlField.text = rememberedUrl.ifBlank {
            custom?.baseUrl ?: preset?.baseUrl ?: ""
        }
        apiKeyField.text = rememberedKey
        modelField.text = rememberedModel.ifBlank {
            custom?.model ?: preset?.defaultModel ?: ""
        }
        // 自定义服务商的协议格式与思考方言是它自己的属性，不走预设
        val format = custom?.apiFormat ?: preset?.format ?: "openai"
        formatBox.selectedIndex = if (format == "anthropic") 1 else 0

        thinkingLevelBox.selectedIndex =
            com.zhixueyao.llm.ThinkingLevel.entries.indexOfFirst { it.name == s.thinkingLevel }
                .coerceAtLeast(0)
        thinkingStyleBox.selectedIndex = com.zhixueyao.llm.ThinkingStyle.entries
            .indexOfFirst { it.name == (custom?.thinkingStyle ?: preset?.thinkingStyle?.name ?: "NONE") }
            .coerceAtLeast(0)

        updatePresetNote()
        refreshProviderStatus()
        refreshThinkingHint()
        // 切服务商时清单要跟着换 —— 每家的模型集合完全不同
        refreshModelList()
        testResultLabel.text = ""
    }

    private fun updatePresetNote() {
        val custom = ZhixueyaoSettings.getInstance().customById(currentProviderId())
        val p = Providers.byId(currentProviderId())
        presetNote.text = when {
            custom != null -> {
                val model = modelField.text.trim()
                if (model.isBlank()) "· 自建服务商，尚未指定模型" else "· 当前使用模型：$model"
            }
            p != null && p.note.isNotBlank() -> "· ${p.note}"
            else -> ""
        }
        presetNote.foreground = UiKit.faint
        presetNote.font = presetNote.font.deriveFont(presetNote.font.size - 1f)
    }

    /** 显示当前服务商是否已配置密钥，给用户一个明确的状态反馈。 */
    private fun refreshProviderStatus() {
        val pid = currentProviderId()
        val key = String(apiKeyField.password).ifBlank { ZhixueyaoSettings.getInstance().keyOf(pid) }
        val ready = key.isNotBlank() || pid == "ollama"
        providerStatusLabel.text = if (ready) "✓ 已配置密钥" else "尚未填写密钥"
        providerStatusLabel.foreground = if (ready) UiKit.ok else UiKit.warn
        providerStatusLabel.font = providerStatusLabel.font.deriveFont(providerStatusLabel.font.size - 1f)
    }

    /**
     * 思考档位的说明文字。
     *
     * 之所以要写清楚「选了也没用」的情况：开关式方言（DeepSeek/智谱/Kimi）
     * 只有开与关两种状态，用户选了「高」却发现行为没变化会以为是 bug ——
     * 不如直接说明白。
     */
    private fun refreshThinkingHint() {
        val level = com.zhixueyao.llm.ThinkingLevel.entries
            .getOrElse(thinkingLevelBox.selectedIndex) { com.zhixueyao.llm.ThinkingLevel.DEFAULT }
        val style = com.zhixueyao.llm.ThinkingStyle.entries
            .getOrElse(thinkingStyleBox.selectedIndex) { com.zhixueyao.llm.ThinkingStyle.NONE }

        val text = when {
            level == com.zhixueyao.llm.ThinkingLevel.DEFAULT ->
                "不发送任何思考参数，由服务商与模型自行决定"
            style == com.zhixueyao.llm.ThinkingStyle.NONE ->
                "⚠ 当前方言为「不发送」，选了档位也不会生效 —— 请把下方方言改成对应类型"
            style == com.zhixueyao.llm.ThinkingStyle.TOGGLE && level.enabled ->
                "该方言只支持开 / 关：档位会折叠成「开启思考」，细分级别的强度不生效"
            style == com.zhixueyao.llm.ThinkingStyle.QWEN && level.enabled ->
                "将发送 enable_thinking=true 与 thinking_budget=${level.budget}"
            style == com.zhixueyao.llm.ThinkingStyle.EFFORT && level.effortValue != null ->
                "将发送 reasoning_effort=\"${level.effortValue}\""
            else -> level.description
        }
        thinkingHintLabel.text = "· $text"
        thinkingHintLabel.foreground = UiKit.faint
        thinkingHintLabel.font = thinkingHintLabel.font.deriveFont(thinkingHintLabel.font.size - 1f)
    }

    // ---------------- 插件（MCP）页 ----------------

    private fun buildMcpPage(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(14, 16)
        }

        // 竖排：说明 → 表格（限高） → 操作按钮。
        // 用 BoxLayout 而不是 BorderLayout —— 后者会把中间的表格拉满，
        // 把按钮顶到看不见的地方（用户反馈过）。
        val listSection = JBPanel<JBPanel<*>>()
        listSection.layout = BoxLayout(listSection, BoxLayout.Y_AXIS)
        listSection.isOpaque = false
        listSection.add(
            UiKit.hint("外部 MCP 服务器：连接后，其工具会一并提供给 AI 使用").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            }
        )
        listSection.add(Box.createVerticalStrut(4))
        mcpTable.setShowGrid(false)
        mcpTable.rowHeight = 26
        mcpTable.columnModel.getColumn(0).preferredWidth = 56
        mcpTable.columnModel.getColumn(1).preferredWidth = 150
        mcpTable.columnModel.getColumn(2).preferredWidth = 70
        mcpTable.columnModel.getColumn(3).preferredWidth = 360

        val decorator = ToolbarDecorator.createDecorator(mcpTable)
            .setAddAction {
                val config = McpServerDialog.show(null)
                if (config != null) mcpTableModel.add(config)
            }
            .setEditAction {
                val row = mcpTable.selectedRow
                if (row < 0) return@setEditAction
                val edited = McpServerDialog.show(mcpTableModel.get(row))
                if (edited != null) mcpTableModel.update(row, edited)
            }
            .setRemoveAction {
                val row = mcpTable.selectedRow
                if (row >= 0) mcpTableModel.remove(row)
            }
            .disableUpDownActions()
            .createPanel()
        // **表格限高**，装饰工具栏（+ − ✎）在表格正上方。
        //
        // 之前这里用「匿名类覆写 getPreferredSize 限高」，但组件是放进 CENTER 的 ——
        // BorderLayout.CENTER 会无视 preferredSize/maximumSize 把组件拉满，
        // 所以那个写法根本不生效：表格撑满整页，把下面的按钮全顶到屏幕外
        // （用户反馈「要我一直滑很久下去才可以看到按钮」）。
        // 现在交给 TableSection：固定高度 + 放 NORTH，按钮位置不再随数据量漂移。
        listSection.add(
            com.zhixueyao.ui.TableSection.panel(
                table = mcpTable,
                toolbar = decorator,
                height = 240
            ).apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }
        )

        val ioRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        ioRow.add(UiKit.textButton("从 mcp.json 导入") { importConfig() })
        ioRow.add(UiKit.textButton("导出为 mcp.json") { exportConfig() })
        ioRow.add(UiKit.textButton("测试全部连接") { testAllMcp() })
        ioRow.add(UiKit.textButton("查看已连接工具") { showConnectedTools() })
        listSection.add(Box.createVerticalStrut(8))
        listSection.add(ioRow.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })

        val serverSection = UiKit.roundedCard()
        serverSection.add(UiKit.sectionTitle("对外提供 MCP 服务"), BorderLayout.NORTH)

        val serverBody = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply { isOpaque = false }
        val opts = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        opts.add(mcpServerEnabledCheck)
        opts.add(JLabel("端口"))
        opts.add(mcpPortSpinner)
        mcpPortSpinner.preferredSize = Dimension(90, mcpPortSpinner.preferredSize.height)
        serverBody.add(opts, BorderLayout.NORTH)

        mcpStatusLabel.foreground = UiKit.faint
        serverBody.add(mcpStatusLabel, BorderLayout.CENTER)

        configSnippetArea.isEditable = false
        configSnippetArea.font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size - 1)
        configSnippetArea.rows = 7
        configSnippetArea.isOpaque = false
        configSnippetArea.border = JBUI.Borders.empty(6)
        configSnippetArea.rows = 5
        configSnippetArea.lineWrap = false
        serverBody.add(
            object : JBScrollPane(configSnippetArea) {
                init {
                    border = UiKit.cardBorder(UiKit.border, UiKit.radiusSmall)
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                }

                // 限高 110：这段配置是「看一眼、复制走」用的，不需要占半屏。
                // 超出在框内滚动，页面长度就稳定了。
                override fun getPreferredSize(): Dimension =
                    Dimension(super.getPreferredSize().width, 110)

                override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 110)
            },
            BorderLayout.CENTER
        )

        serverSection.add(serverBody, BorderLayout.CENTER)

        // 竖排：上面是服务器列表区，下面是「对外提供 MCP 服务」。
        // 整块靠上排，剩余空间留白 —— 不让任何一块被拉满。
        val center = JBPanel<JBPanel<*>>()
        center.layout = BoxLayout(center, BoxLayout.Y_AXIS)
        center.isOpaque = false
        center.add(listSection)
        center.add(Box.createVerticalStrut(10))
        center.add(serverSection.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        center.add(Box.createVerticalGlue())
        panel.add(center, BorderLayout.NORTH)

        mcpPortSpinner.addChangeListener {
            configSnippetArea.text = McpController.clientConfigJson(
                (mcpPortSpinner.value as Number).toInt()
            )
        }

        return panel
    }

    // ---------------- 表单骨架 ----------------

    private class FormBuilder(val panel: JBPanel<JBPanel<*>>) {
        private val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
            gridx = 0
        }
        private var row = 0

        /** 左标签 + 右控件（可选下方提示） */
        fun row(label: String, component: JComponent, hint: String? = null) {
            gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0.0; gbc.gridwidth = 1
            panel.add(JBLabel(label).apply { foreground = UiKit.text }, gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(component, gbc)
            if (hint != null) {
                gbc.gridy = ++row; gbc.gridx = 1; gbc.weightx = 1.0
                panel.add(UiKit.hint(hint), gbc)
            }
            row++
        }

        /** 跨两列的整行控件（复选框、按钮） */
        fun wideRow(component: JComponent) {
            gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 1.0; gbc.gridwidth = 2
            panel.add(component, gbc)
            gbc.gridwidth = 1
            row++
        }

        /** 右列下的提示行（不占左标签位） */
        fun hintRow(component: JComponent) {
            gbc.gridy = row; gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 1
            panel.add(component, gbc)
            row++
        }

        fun section(title: String) {
            gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 1.0; gbc.gridwidth = 2
            gbc.insets = Insets(if (row == 0) 2 else 18, 4, 6, 4)
            panel.add(UiKit.sectionTitle(title), gbc)
            gbc.gridwidth = 1
            gbc.insets = Insets(4, 4, 4, 4)
            row++
        }

        /** 收尾：底部留白，让内容靠上排布 */
        fun finish() {
            gbc.gridy = row; gbc.gridx = 0; gbc.weighty = 1.0; gbc.gridwidth = 2
            panel.add(Box.createVerticalGlue(), gbc)
        }
    }

    private fun newForm(): FormBuilder {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(14, 18)
        }
        return FormBuilder(panel)
    }

    private fun newFormFor(panel: JBPanel<JBPanel<*>>): FormBuilder = FormBuilder(panel)

    // ---------------- 动作 ----------------

    /**
     * 展示 MCP 服务器暴露的工具清单及其安全属性。
     *
     * 为什么要显示安全提示：只读模式下只有声明 `readOnlyHint` 的 MCP 工具会被放行。
     * 用户会看到某些工具在对话里「消失」，这里给出依据 —— 服务器自己声明了什么。
     *
     * 与「测试全部连接」一样走临时连接：设置页是无 project 的 application 级组件，
     * 拿不到聊天面板那份连接，自建一份查完即断，避免与面板的状态互相干扰。
     */
    private fun showConnectedTools() {
        val servers = mcpTableModel.snapshot().filter { it.enabled }
        if (servers.isEmpty()) {
            Messages.showInfoMessage(
                rootPanel,
                "没有已启用的 MCP 服务器。\n\n先在列表中添加并勾选启用，再回来看工具清单。",
                "已连接工具"
            )
            return
        }

        mcpStatusLabel.text = "正在读取工具清单…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val manager = McpManager()
            val sb = StringBuilder()
            var serverOk = 0
            var totalTools = 0

            for (cfg in servers) {
                try {
                    val live = manager.connect(cfg)
                    serverOk++
                    totalTools += live.tools.size
                    val readonly = live.tools.count { it.readOnlyHint }
                    sb.append("【${cfg.name}】  ${live.tools.size} 个工具")
                    if (readonly > 0) sb.append("（其中 $readonly 个只读）")
                    sb.append('\n')

                    // 说明：服务器下发给模型的使用指引
                    val inst = live.connection.instructions.trim()
                    if (inst.isNotEmpty()) {
                        sb.append("  服务器说明：${inst.replace("\n", " ").take(180)}\n")
                    }
                    sb.append('\n')

                    // 组内先列只读，有副作用的排后面 —— 用户最该注意后者
                    for (t in live.tools.sortedBy { !it.readOnlyHint }) {
                        val label = if (t.title.isNotBlank()) t.title else t.name
                        sb.append("  · $label\n")
                        sb.append("      ${t.name}")
                        val tags = buildList {
                            add(if (t.readOnlyHint) "只读" else "可写入")
                            if (t.destructiveHint) add("破坏性")
                            if (!t.callable) add("需任务增强·当前不可调用")
                        }
                        sb.append("   [${tags.joinToString(" / ")}]\n")
                        if (t.description.isNotBlank()) {
                            sb.append("      ${t.description.replace("\n", " ").take(140)}\n")
                        }
                        sb.append('\n')
                    }
                } catch (e: Exception) {
                    sb.append("【${cfg.name}】 连接失败\n")
                    sb.append("  ${e.message?.replace("\n", " ") ?: "未知错误"}\n\n")
                }
            }
            manager.disconnectAll()

            val header = buildString {
                append("已连接 $serverOk/${servers.size} 个服务器，共 $totalTools 个工具\n")
                append("（只读模式下仅放行标注为「只读」的工具）\n")
                append("─".repeat(62)).append("\n\n")
            }
            val text = header + sb.toString()

            javax.swing.SwingUtilities.invokeLater {
                mcpStatusLabel.text = "读取完成：$totalTools 个工具"
                val area = JBTextArea(text).apply {
                    isEditable = false
                    font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size - 1)
                    border = JBUI.Borders.empty(10)
                    caretPosition = 0
                }
                // 自建对话框：DialogWrapper 的按钮体系在 Kotlin 里不好用
                // （setOKButtonText 是 protected、CancelAction 是内部类），
                // 这里只需要一个能看能复制的滚动文本框，直接用 JDialog 更干净
                val dialog = javax.swing.JDialog(
                    com.intellij.openapi.wm.WindowManager.getInstance().getFrame(null),
                    "MCP 工具清单（共 $totalTools 个）",
                    true
                )
                val pane = JBScrollPane(area).apply {
                    border = UiKit.cardBorder(UiKit.border, UiKit.radiusSmall)
                    preferredSize = Dimension(700, 480)
                }
                val closeBtn = UiKit.primaryButton("关闭") { }
                val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(UiKit.textButton("复制全部") {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(text), null)
                    })
                    add(closeBtn)
                }
                dialog.contentPane.layout = java.awt.BorderLayout(0, 8)
                (dialog.contentPane as JComponent).border = JBUI.Borders.empty(12)
                dialog.contentPane.add(pane, java.awt.BorderLayout.CENTER)
                dialog.contentPane.add(btnRow, java.awt.BorderLayout.SOUTH)
                closeBtn.addActionListener { dialog.dispose() }
                dialog.pack()
                dialog.setLocationRelativeTo(null)
                dialog.isVisible = true
            }
        }
    }

    /**
     * 当前选中的服务商 id。
     *
     * 从 [providerEntries] 取而不是从 `Providers.presets` 按下标取 ——
     * 自定义服务商不在 presets 里，按下标取会错位甚至越界。
     *
     * 哨兵项（「新增」那条）不是服务商，落回第一家预设，
     * 否则它会被当成一个 providerId 存进配置，下次启动指向一个不存在的服务商。
     */
    private fun currentProviderId(): String {
        val entry = providerEntries.getOrNull(providerBox.selectedIndex)
        if (entry != null && !entry.isAddNew) return entry.id
        return Providers.presets.first().id
    }

    private fun currentThinkingStyleName(): String =
        com.zhixueyao.llm.ThinkingStyle.entries
            .getOrElse(thinkingStyleBox.selectedIndex) { com.zhixueyao.llm.ThinkingStyle.NONE }
            .name

    private fun currentThinkingLevelName(): String =
        com.zhixueyao.llm.ThinkingLevel.entries
            .getOrElse(thinkingLevelBox.selectedIndex) { com.zhixueyao.llm.ThinkingLevel.DEFAULT }
            .name

    private fun currentFormat(): String = if (formatBox.selectedIndex == 1) "anthropic" else "openai"

    private fun currentPresetId(): String = AgentPresets.all[presetBox.selectedIndex].id

    private fun currentLanguage(): String = when (languageBox.selectedIndex) {
        1 -> "zh"
        2 -> "en"
        else -> "auto"
    }

    /** 思考过程展示方式：collapsed（默认收起）/ live（实时展开）/ hidden（不显示） */
    private fun currentReasoningView(): String = when (reasoningViewBox.selectedIndex) {
        1 -> "live"
        2 -> "hidden"
        else -> "collapsed"
    }

    /** 打开配置文件位置，方便高级用户直接改。 */
    private fun openConfigFile() {
        val dir = java.io.File(com.intellij.openapi.application.PathManager.getOptionsPath())
        val target = java.io.File(dir, "zhixueyao.xml")
        if (target.exists()) {
            try {
                java.awt.Desktop.getDesktop().open(target.parentFile)
                return
            } catch (_: Exception) {
                // 落到下面的提示
            }
        }
        Messages.showInfoMessage(
            rootPanel,
            "配置文件位置：\n${target.absolutePath}\n\n（首次保存设置后才会生成）",
            "止血药"
        )
    }

    /** 弹出模型选择器，选中后填入「模型名称」框。 */
    private fun pickModel() {
        val base = baseUrlField.text.trim()
        if (base.isBlank()) {
            testResultLabel.text = "请先填写接口地址"
            testResultLabel.foreground = UiKit.danger
            return
        }
        val pid = currentProviderId()
        val picked = ModelPickerDialog.pick(
            parent = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(null) as? java.awt.Frame,
            baseUrl = base,
            apiKey = String(apiKeyField.password),
            format = currentFormat(),
            currentModel = modelField.text.trim(),
            alreadyAdded = ZhixueyaoSettings.getInstance().modelListOf(pid)
        )
        val s = ZhixueyaoSettings.getInstance()
        when (picked) {
            // 直接使用：填进模型框，并顺带记入清单 —— 用过的模型理应出现在菜单里，
            // 否则用户切回来会发现「明明用过却找不到」
            is ModelPickerDialog.PickerResult.Use -> {
                modelField.text = picked.model
                s.addModel(pid, picked.model)
                s.rememberModel(pid, picked.model)
                refreshModelList()
                testResultLabel.text = "已选择：${picked.model}"
                testResultLabel.foreground = UiKit.ok
            }

            // 批量加入：只扩充清单，不动当前正在用的模型
            is ModelPickerDialog.PickerResult.Add -> {
                val n = s.addModels(pid, picked.models)
                refreshModelList()
                testResultLabel.text = if (n > 0) {
                    "已加入 $n 个模型（共 ${s.modelListOf(pid).size} 个）"
                } else {
                    "选中的模型都已经在列表里了"
                }
                testResultLabel.foreground = if (n > 0) UiKit.ok else UiKit.faint
            }

            null -> Unit
        }
    }

    private fun testConnection() {
        val base = baseUrlField.text.trim()
        val key = String(apiKeyField.password)
        val model = modelField.text.trim()
        if (base.isBlank() || model.isBlank()) {
            testResultLabel.text = "请先填写接口地址与模型名称"
            testResultLabel.foreground = UiKit.danger
            return
        }
        testResultLabel.text = "正在测试…"
        testResultLabel.foreground = UiKit.faint

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = ConnectionTester.test(base, key, model, currentFormat())
            javax.swing.SwingUtilities.invokeLater {
                // 失败文案是「结论 + 建议 + 原始信息」三行，直接塞进 JLabel 只会显示一行，
                // 所以转成 HTML 让它在设置页里完整折行展示。
                testResultLabel.text = if (result.second) {
                    result.first
                } else {
                    val safe = result.first
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "<br>")
                    "<html><body style='width:470px'>$safe</body></html>"
                }
                testResultLabel.foreground = if (result.second) UiKit.ok else UiKit.danger
            }
        }
    }

    private fun importConfig() {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "选择 mcp.json 文件"
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JSON 配置", "json")
        if (chooser.showOpenDialog(rootPanel) != javax.swing.JFileChooser.APPROVE_OPTION) return

        try {
            val text = chooser.selectedFile.readText()
            val configs = McpManager.parseConfigJson(text)
            if (configs.isEmpty()) {
                Messages.showInfoMessage(rootPanel, "该文件中没有可用的 MCP 服务器配置", "导入")
                return
            }
            mcpTableModel.setData(mcpTableModel.snapshot() + configs)
            Messages.showInfoMessage(
                rootPanel,
                "已导入 ${configs.size} 个服务器配置：\n" + configs.joinToString("\n") { "· ${it.name}" },
                "导入成功"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(rootPanel, "导入失败：${e.message}", "导入")
        }
    }

    private fun exportConfig() {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "导出 MCP 配置"
        // 默认就指向全局配置目录：这样导出即生效（插件启动会读这个文件）
        com.zhixueyao.agent.ZhixueyaoHome.ensure()
        chooser.selectedFile = com.zhixueyao.agent.ZhixueyaoHome.mcpFile()
        if (chooser.showSaveDialog(rootPanel) != javax.swing.JFileChooser.APPROVE_OPTION) return
        try {
            chooser.selectedFile.writeText(McpManager.exportConfigJson(mcpTableModel.snapshot()))
            val isHome = chooser.selectedFile.absolutePath ==
                com.zhixueyao.agent.ZhixueyaoHome.mcpFile().absolutePath
            Messages.showInfoMessage(
                rootPanel,
                "已导出到 ${chooser.selectedFile.absolutePath}" +
                    if (isHome) "\n\n这是全局配置文件，重启 IDE 后会自动加载。" else "",
                "导出成功"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(rootPanel, "导出失败：${e.message}", "导出")
        }
    }

    private fun testAllMcp() {
        val servers = mcpTableModel.snapshot()
        if (servers.isEmpty()) {
            Messages.showInfoMessage(rootPanel, "尚未配置 MCP 服务器", "测试")
            return
        }
        mcpStatusLabel.text = "正在测试 ${servers.size} 个服务器…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val manager = McpManager()
            val results = StringBuilder()
            for (cfg in servers) {
                if (!cfg.enabled) {
                    results.append("○ ${cfg.name}：已禁用\n")
                    continue
                }
                try {
                    val live = manager.connect(cfg)
                    results.append("✓ ${cfg.name}：${live.tools.size} 个工具\n")
                } catch (e: Exception) {
                    results.append("✗ ${cfg.name}：${e.message}\n")
                }
            }
            manager.disconnectAll()
            javax.swing.SwingUtilities.invokeLater {
                mcpStatusLabel.text = "测试完成"
                Messages.showInfoMessage(rootPanel, results.toString(), "MCP 连接测试")
            }
        }
    }

    companion object {
        /** 左侧导航项：id / 显示名 / 图标（对齐 dsh：通用 / 模型 / 插件 / Agent 预设） */
        private val SECTIONS: List<Triple<String, String, javax.swing.Icon>> = listOf(
            Triple("general", "通用", AllIcons.General.Settings),
            Triple("model", "模型", AllIcons.General.InspectionsOK),
            Triple("plugins", "插件", AllIcons.General.Web),
            Triple("presets", "Agent 预设", AllIcons.General.InspectionsEye),
            Triple("temp", "临时会话", AllIcons.Actions.GC)
        )

        /** 旧 id 兼容映射 */
        private val SECTION_ALIASES: Map<String, String> = mapOf(
            "mcp" to "plugins",
            "about" to "general"
        )

        /**
         * 服务商下拉里「新增自定义服务商」那一项的 id。
         *
         * 用带前缀的哨兵值而不是空串：空串在别处已被当作「没有服务商」的语义，
         * 复用会让 [currentProviderIdIfAny] 之类的判断串味。
         */
        private const val ADD_NEW_SENTINEL = "__add_new__"

        /** 下拉里那条操作的显示文案 */
        private const val ADD_NEW_LABEL = "＋ 新增自定义服务商…"
    }
}

// ---------------- MCP 服务器表格 ----------------

class McpTableModel : AbstractTableModel() {

    private val rows = mutableListOf<McpServerConfig>()
    private val columns = arrayOf("启用", "名称", "类型", "命令 / 地址")

    fun setData(list: List<McpServerConfig>) {
        rows.clear()
        rows.addAll(list.map { it.copy() })
        fireTableDataChanged()
    }

    fun snapshot(): List<McpServerConfig> = rows.map { it.copy() }

    fun add(config: McpServerConfig) {
        rows.add(config)
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun update(index: Int, config: McpServerConfig) {
        rows[index] = config
        fireTableRowsUpdated(index, index)
    }

    fun remove(index: Int) {
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun get(index: Int): McpServerConfig = rows[index]

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            0 -> r.enabled
            1 -> r.name
            2 -> r.type
            else -> if (r.type == "http") r.url else r.command
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0) {
            rows[rowIndex].enabled = aValue as? Boolean ?: true
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}

// ---------------- 辅助类 ----------------

/** 连接测试：发一条最短请求验证配置可用。 */
object ConnectionTester {

    fun test(baseUrl: String, apiKey: String, model: String, format: String): Pair<String, Boolean> {
        return try {
            // 单次上限不能太小：推理型模型会把预算先花在思维链上，
            // 16 token 会让它一个正文字都没输出就 finish_reason=length，
            // 于是「连接明明是通的」却被判成失败。256 足够回一句寒暄。
            val config = com.zhixueyao.llm.LlmConfig(baseUrl, apiKey, model, 0.0, 256)
            val provider = com.zhixueyao.llm.Providers.createProvider(format)
            val msgs = listOf(com.zhixueyao.llm.ChatMessage.user("hi"))
            var reply = ""
            var failure: String? = null
            val flag = java.util.concurrent.atomic.AtomicBoolean(false)

            provider.streamChat(config, msgs, emptyList(), { ev ->
                when (ev) {
                    is com.zhixueyao.llm.LlmEvent.TextDelta -> reply += ev.text
                    is com.zhixueyao.llm.LlmEvent.Completed -> Unit
                    is com.zhixueyao.llm.LlmEvent.Failure -> failure = ev.error
                    else -> Unit
                }
            }, flag)

            if (failure != null) {
                // failure 已经是带建议的多行文案，不要再套一层前缀
                failure to false
            } else {
                val preview = reply.trim().lineSequence().firstOrNull()?.take(24) ?: ""
                if (preview.isEmpty()) "连接正常，模型已响应" to true
                else "连接正常，模型已响应：$preview" to true
            }
        } catch (e: Exception) {
            com.zhixueyao.llm.LlmErrorAdvice.renderNetworkError(e, baseUrl) to false
        }
    }
}

/** 由应用级服务持有 MCP 服务器实例，便于设置变更后热启停。 */
class McpServerController private constructor() {

    private var server: com.zhixueyao.mcp.IdeMcpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    @Synchronized
    fun start(port: Int): Boolean {
        stop()
        val p = ProjectManager.getInstance().openProjects.firstOrNull()
        val srv = com.zhixueyao.mcp.IdeMcpServer(
            projectProvider = { ProjectManager.getInstance().openProjects.firstOrNull() ?: p },
            toolsProvider = { com.zhixueyao.agent.ToolRegistry.all }
        )
        val ok = srv.start(port)
        server = if (ok) srv else null
        return ok
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        fun getInstance(): McpServerController = Holder.INSTANCE
    }

    private object Holder {
        val INSTANCE = McpServerController()
    }
}

/** 供设置界面复用的静态入口。 */
object McpController {
    fun clientConfigJson(port: Int): String =
        com.zhixueyao.mcp.IdeMcpServer.clientConfigJson(port)
}
