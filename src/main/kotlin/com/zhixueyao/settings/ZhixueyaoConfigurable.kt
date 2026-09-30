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

    // ---------------- Git 助手 ----------------

    /** 总开关。关掉之后下面所有设置都不生效，git 命令也不带任何代理参数 */
    private val gitEnabledCheck = JCheckBox("启用 Git 助手（让 AI 能推送 / 拉取，并自动处理代理）")

    /** 三种访问方式。用单选而不是下拉：只有三个选项，铺开更省一次点击 */
    private val gitModeDirect = javax.swing.JRadioButton("直连")
    private val gitModeProxy = javax.swing.JRadioButton("自定义代理")
    private val gitModeVpn = javax.swing.JRadioButton("本地 VPN 工具")

    private val gitProxyField = com.intellij.ui.components.JBTextField()
    private val gitVpnPortField = com.intellij.ui.components.JBTextField()
    private val gitUserField = com.intellij.ui.components.JBTextField()

    /**
     * Token 用 [JPasswordField]：界面上不明文显示。
     *
     * (它不是安全防线 —— 存在内存里的字符串照样能被读。真正的保护是
     * [com.zhixueyao.git.GitCredentials] 把它加密存在系统密钥链里。
     * 用密码框只是**避免肩窥和录屏泄露**。)
     */
    private val gitTokenField = JPasswordField()

    /**
     * 本次构建的时间戳。
     *
     * ## 为什么界面里要有这个
     *
     * 这一轮反复出现「用户报的症状和我以为的代码版本对不上」——
     * 而**从截图看不出跑的是哪一版**。插件改了要重启 IDE 才生效，
     * 于是「没重启」和「改了没用」在界面上长得一模一样。
     *
     * 有了这行时间戳，**一眼就能确认装的是哪一版**。
     *
     * （值是 build 时由 Gradle 写进来的；拿不到就显示「未知」。）
     */
    private val BUILD_STAMP: String = try {
        // 读**这个类所在的 jar 文件**的修改时间 —— 那就是构建时间。
        //
        // 比「build 时写个资源文件」简单：不用改 Gradle、不用往 src 里塞文件，
        // 而且它天然是对的（jar 被替换了它就变）。
        val src = javaClass.protectionDomain?.codeSource?.location?.toURI()
        val jar = src?.let { java.io.File(it) }
        if (jar != null && jar.isFile) {
            java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(jar.lastModified()))
        } else {
            "开发环境"
        }
    } catch (e: Exception) {
        "未知"
    }

    /** 本次自检是什么时候开始的 —— 用来算耗时 */
    private var envStartedAt = 0L

    /** 环境自检的结果区（每次检查重建里面的行） */
    private val gitEnvBody = JBPanel<JBPanel<*>>().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }

    /** 署名修复行 —— **只在自检发现没配署名时才显示** */
    private var gitAuthorFixRow: javax.swing.JComponent? = null
    private val gitAuthorNameField2 = com.intellij.ui.components.JBTextField()
    private val gitAuthorEmailField2 = com.intellij.ui.components.JBTextField()

    /**
     * 「当前选的这种方式是什么意思、什么时候该用它」—— 跟着单选项变。
     *
     * 做成动态的原因：三种方式的区别不是一句话能并列写清的，
     * 特别是「我到底属于哪种」这件事 —— 那需要按当前选择展开讲。
     */
    /**
     * 「代理地址」那一整行（标签 + 输入框 + 提示）和「VPN 端口」那一整行。
     *
     * 收集起来是为了**整行显隐** —— 只灰掉输入框的话，用户还是看到一堆灰框，
     * 不知道哪些该填。连标签和提示一起收掉，页面才是「当前这种方式要填的东西」。
     */
    private val gitProxyRow = mutableListOf<java.awt.Component>()
    private val gitVpnRow = mutableListOf<java.awt.Component>()

    private val gitModeHelp = com.intellij.ui.components.JBLabel().apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }

    /** 检测结果 / 状态提示。文案会动态改，所以是字段不是固定标签 */
    private val gitStatusLabel = com.intellij.ui.components.JBLabel()
    private val gitTokenStatusLabel = com.intellij.ui.components.JBLabel()
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

    /** 知识库状态文字（页数 / 标签数） */
    private val kbInfoLabel = JBLabel()

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
    private val enableSubagentCheck = JCheckBox("允许 AI 派只读子代理去调研（读很多文件但只带回结论，省上下文）")

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
            s.enableSubagent != enableSubagentCheck.isSelected ||
            s.allowAskUser != allowAskUserCheck.isSelected ||
            s.confirmBeforeSend != confirmSendCheck.isSelected ||
            s.showApplyButton != showApplyCheck.isSelected ||
            s.gitHelperEnabled != gitEnabledCheck.isSelected ||
            s.gitAccessMode != currentGitMode() ||
            s.gitProxyUrl != gitProxyField.text.trim() ||
            s.gitVpnPort != (gitVpnPortField.text.trim().toIntOrNull() ?: 0) ||
            s.gitUserName != gitUserField.text.trim() ||
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
        s.enableSubagent = enableSubagentCheck.isSelected
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
        s.gitHelperEnabled = gitEnabledCheck.isSelected
        s.gitAccessMode = currentGitMode()
        s.gitProxyUrl = gitProxyField.text.trim()
        s.gitVpnPort = gitVpnPortField.text.trim().toIntOrNull() ?: 0
        s.gitUserName = gitUserField.text.trim()
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

    /**
     * 按当前设置启停 IDE 内置的 MCP 服务。
     *
     * ## 为什么整体挪到后台
     *
     * 这个函数在 `reset()` 里被调（也就是**打开设置页时**），原来是同步的 ——
     * 于是「**在 EDT 上启动一个 HTTP 服务器**」。而 `McpServerController.start()`
     * 第一步是 `stop()`，那里**会等线程池终止**：
     *
     * > 讽刺的是，那个「等终止」正是我上一轮修「线程池不关」时加的 ——
     * > 修好了线程泄漏，却**给 EDT 加了一个新的阻塞点**。
     * > 两次改动各自都对，合起来就不对了。
     *
     * 所以整段挪到后台，界面标签用 `invokeLater` 回填。
     *
     * **注意 `wasEnabled` 的比较要留在 EDT 侧读**（它来自 check 组件的状态），
     * 不能等到后台再读 —— 那时用户可能已经又点了。
     */
    /**
     * 按当前设置启停 IDE 内置的 MCP 服务。
     *
     * ## 为什么整段挪到后台
     *
     * 这个函数在 `reset()` 里被调（也就是**打开设置页时**），原来是同步的 ——
     * 于是「**在 EDT 上启动一个 HTTP 服务器**」。而 `McpServerController.start()`
     * 第一步是 `stop()`，那里**会等线程池终止**。
     *
     * > 讽刺的是，那个「等终止」正是我上一轮修「线程池不关」时加的 ——
     * > 修好了线程泄漏，却**给 EDT 加了一个新的阻塞点**。
     * > 两次改动各自都对，合起来就不对了。
     *
     * 所以整段挪到后台，界面标签用 `invokeLater` 回填。
     *
     * **注意设置值要在 EDT 侧先读出来**（`wantEnabled` / `port`）——
     * 不能等后台再读，那时用户可能已经又改动过了。
     */
    private fun applyMcpServerState(wasEnabled: Boolean) {
        val s = ZhixueyaoSettings.getInstance()
        val wantEnabled = s.mcpServerEnabled
        val port = s.mcpServerPort
        val basePath = com.zhixueyao.tools.KnowledgeBase.root().absolutePath

        mcpStatusLabel.text = "正在处理…"
        mcpStatusLabel.foreground = UiKit.subtle

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val service = McpServerController.getInstance()
            // 后台只碰服务，**不碰任何 Swing 组件**
            val ok = try {
                if (wantEnabled) service.start(port) else { service.stop(); true }
            } catch (t: Throwable) {
                false
            }
            UiKit.ui {
                if (!wantEnabled) {
                    mcpStatusLabel.text = if (wasEnabled) "已停止" else "未启用"
                    mcpStatusLabel.foreground = UiKit.faint
                } else if (!ok) {
                    mcpStatusLabel.text = "启动失败，端口 $port 可能已被占用"
                    mcpStatusLabel.foreground = UiKit.danger
                } else {
                    mcpStatusLabel.text = "运行中：http://127.0.0.1:$port/"
                    mcpStatusLabel.foreground = UiKit.ok
                }
                configSnippetArea.text = McpController.clientConfigJson(port)
            }
        }
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
        gitEnabledCheck.isSelected = s.gitHelperEnabled
        // 读不认识的值就当 direct 安全降级（见设置项那边的注释）
        when (s.gitAccessMode) {
            "proxy" -> gitModeProxy.isSelected = true
            "vpn" -> gitModeVpn.isSelected = true
            else -> gitModeDirect.isSelected = true
        }
        gitProxyField.text = s.gitProxyUrl
        gitVpnPortField.text = if (s.gitVpnPort > 0) s.gitVpnPort.toString() else ""
        gitUserField.text = s.gitUserName
        gitTokenField.text = ""
        refreshGitVisibility()
        runEnvCheck()
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
        refreshKbInfo()

        // AI 助手行为
        sandboxModeBox.selectedIndex = Sandbox.Mode.entries.indexOf(Sandbox.Mode.byId(s.sandboxMode))
        showTaskListCheck.isSelected = s.showTaskList
        enableSubagentCheck.isSelected = s.enableSubagent
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
        /**
         * 卡片容器。**重写了 `getPreferredSize` —— 这是这一页大量空白的根因。**
         *
         * ## 问题
         *
         * `CardLayout` 的默认 `preferredSize` 是**所有卡片里最大的那个**（它要保证
         * 随便切到哪张都不被裁）。而这个设置页里 MCP 页最长 ——
         * 于是**每一页都被按 MCP 页的高度算**，短页面（Git 助手、临时会话）
         * 后面就拖着一大片空白，而且**能一直往下滑、下面却没东西**。
         *
         * 用户的原话：「每个分类里都有大量的空白，可以往下滑但下面没有内容」。
         *
         * ## 为什么不是去掉 `form.finish()`
         *
         * 那个 glue 的 `preferredSize` 是 0（它只吃剩余空间），**不是它撑高的**。
         * 我去查了才敢下结论 —— 不然又是一次「修好一个不是问题的问题」。
         *
         * ## 修法
         *
         * 只按**当前可见的那张卡片**算高度。切页时 `revalidate()` 会重新问一次，
         * 于是每页各有各的高度。
         */
        val cards = object : JBPanel<JBPanel<*>>(cardLayout) {
            override fun getPreferredSize(): java.awt.Dimension {
                // 先问当前可见的那张卡片 —— 这才是用户真正在看的内容
                components.firstOrNull { it.isVisible }?.let { return it.preferredSize }
                return super.getPreferredSize()
            }

            // 最小宽度仍然取所有卡片的最大值：太窄会让表格横向滚动条乱跳
            override fun getMinimumSize(): java.awt.Dimension {
                val w = components.maxOfOrNull { it.minimumSize.width } ?: 0
                val h = components.firstOrNull { it.isVisible }?.minimumSize?.height ?: 0
                return java.awt.Dimension(w, h)
            }
        }.apply { isOpaque = false }

        /**
         * 建一页，**崩了也不连累别的页**。
         *
         * ## 为什么必须隔离（用户报的 bug 就是这个）
         *
         * 原来是一串裸的 `cards.add(buildXxxPage(), "xxx")`。
         * 只要**中间某一页抛异常**，`buildBody` 就断在那里 ——
         * 它和它**后面所有页**都不会被加进卡片容器。
         *
         * 而 `CardLayout.show(容器, "git")` 在容器里找不到那个名字时，
         * **表现就是一片空白**：不报错、不提示、导航项还在。
         * 用户看到的是「点了 Git 助手，什么都没有」，
         * 而真正崩掉的地方可能在**别的页**里。
         *
         * 这个失败模式特别难查：**症状的位置和原因的位置不重合**。
         *
         * ## 隔离之后
         *
         * 一页崩了 → 那一页显示一段可读的错误 + 堆栈摘要，其余页照常。
         * 这样用户能**直接把原因念出来**，而不是只能说「空白」。
         */
        fun addPage(name: String, title: String, build: () -> JComponent) {
            val page = try {
                build()
            } catch (t: Throwable) {
                // 不吞异常：把消息和最关键的两行堆栈显示出来。
                // 「安静地降级」在这里是错的 —— 用户需要知道有个页面坏了。
                val msg = t.message ?: t.javaClass.simpleName
                val where = t.stackTrace.take(3).joinToString("\n") { "    at $it" }
                com.intellij.openapi.diagnostic.Logger.getInstance(
                    ZhixueyaoConfigurable::class.java
                ).warn("设置页「$title」构建失败", t)

                JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(18)
                    add(
                        com.intellij.ui.components.JBLabel(
                            "<html><b>「$title」这一页没能加载出来。</b><br><br>" +
                                "原因：${msg.replace("<", "&lt;")}<br><br>" +
                                "<font color='gray'>其余设置不受影响，可以直接用。<br>" +
                                "这段信息可以直接反馈给插件作者：</font>" +
                                "<pre style='font-size:9px'>$where</pre></html>"
                        ).apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT },
                        java.awt.BorderLayout.NORTH
                    )
                }
            }
            cards.add(page, name)
        }

        addPage("general", "通用") { buildGeneralPage() }
        addPage("model", "模型") { buildModelPage() }
        addPage("plugins", "插件") { buildMcpPage() }
        addPage("git", "Git 助手") { buildGitPage() }
        addPage("presets", "Agent 预设") { buildPresetsPage() }
        // 临时会话管理：全局临时产物的「看得见 + 能删」入口。
        // 那些目录不在任何工程里，不给入口就等于永远不会被清理。
        addPage("temp", "临时会话") { tempSessionsPanel.build() }
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
            navPanel.add(UiKit.strut(2))
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
        contentPanel?.let {
            cardLayout.show(it, SECTIONS[index].first)
            // 切页后必须重算 —— 高度是按「当前可见的那张卡」算的（见 cards 的注释），
            // 不重新验证的话还会用上一页的高度
            it.revalidate()
            it.repaint()
        }
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

        // ---- 知识库 ----
        //
        // 知识库是「跨项目共用的资料库」，和技能一样属于**用户掌管的数据**，
        // 所以也要摆到设置页里：能看见有多少页、能一键打开目录去放文件。
        // 只给工具不给入口的话，用户根本不知道要往哪儿放东西 —— 功能等于不存在。
        form.section("知识库")
        form.wideRow(kbInfoLabel)
        val kbButtons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        kbButtons.add(UiKit.textButton("打开知识库目录", "把 .md 资料放进去，AI 就能按相关度检索到") {
            openKbDir()
        })
        kbButtons.add(UiKit.textButton("重新扫描", "重新读取知识库目录") { refreshKbInfo() })
        form.wideRow(kbButtons)
        form.wideRow(
            UiKit.hint(
                "和「搜代码」的区别：搜代码要给出**确切的字符串**，而知识库能按**相关度**找 —— " +
                    "适合「记得有这回事、但想不起原话」的场景。\n" +
                    "正文用 # 分小节：小节是检索的最小单位，一个标题下写一件完整的事，检索会准很多。"
            )
        )

        // ---- 记忆 ----
        //
        // 对应参考项目 kemo-agent 的「查看与编辑记忆」。
        // 记忆本来只是磁盘上的两个 MEMORY.md（用户得自己找路径），
        // 摆到设置页里才叫「用户掌管的数据」—— 能看见、能删、能打开改。
        form.section("记忆")
        // `project` 不是这个类的字段 —— 设置页在「没有打开工程」时也要能显示，
        // 所以就地取一次，取不到就只显示全局记忆
        val settingsProject =
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        val memEntries = com.zhixueyao.agent.MemoryStore.all(settingsProject)
        val projMem = com.zhixueyao.agent.MemoryStore.projectFile(settingsProject)
        val globalMem = com.zhixueyao.agent.MemoryStore.globalFile()
        form.wideRow(
            JBLabel(
                if (memEntries.isEmpty())
                    "还没有记忆。AI 听到「以后都这样」「记住」这类话时会自己记下来，你也可以打开文件手写。"
                else
                    "共 ${memEntries.size} 条。下面列出最近的一些；完整内容在文件里，可以直接编辑。"
            ).apply { foreground = UiKit.subtle; font = font.deriveFont(font.size - 1f) }
        )

        if (memEntries.isNotEmpty()) {
            val memBody = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            // 只列最近 12 条：设置页不是编辑器，看个大概就行，细节去文件里
            memEntries.takeLast(12).reversed().forEach { e ->
                val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(3, 0)
                    // 行高固定：内容长短不一，不固定的话列表会参差不齐
                    maximumSize = Dimension(Int.MAX_VALUE, 26)
                }
                row.add(
                    JBLabel(
                        "<html><body style='width:420px'>" +
                            (if (e.date.isNotBlank()) "<span style='color:gray'>[${e.date}]</span> " else "") +
                            e.text.replace("<", "&lt;") + "</body></html>"
                    ).apply { font = font.deriveFont(font.size - 1f) },
                    BorderLayout.CENTER
                )
                row.add(
                    UiKit.iconButton(AllIcons.Actions.GC, "删掉这条记忆") {
                        val owner = if (com.zhixueyao.agent.MemoryStore.read(projMem ?: globalMem)
                                .any { it.text == e.text }
                        ) projMem else globalMem
                        if (owner != null) com.zhixueyao.agent.MemoryStore.forget(owner, e.text)
                        rootPanel?.let {
                            Messages.showInfoMessage(
                                it,
                                "已删掉这条记忆。它不会再出现在提示词里。",
                                "记忆"
                            )
                        }
                        // 面板重建代价高，先提示用户重进设置页；直接刷新列表也行但会跳滚动位置
                        refreshSkillsInfo()
                    },
                    BorderLayout.EAST
                )
                memBody.add(row)
            }
            val memCard = UiKit.roundedCard(pad = JBUI.insets(8, 10, 8, 10))
            memCard.add(
                object : JBScrollPane(memBody) {
                    init {
                        setBorder(JBUI.Borders.empty())
                        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                        isOpaque = false
                        viewport.isOpaque = false
                    }

                    override fun getPreferredSize(): Dimension =
                        Dimension(super.getPreferredSize().width, 200)

                    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 200)
                },
                BorderLayout.CENTER
            )
            form.wideRow(memCard)
        }

        val memButtons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        memButtons.add(UiKit.textButton("打开项目记忆", "随工程走，能提交进仓库") {
            openFileInEditor(projMem)
        })
        memButtons.add(UiKit.textButton("打开全局记忆", "跨项目，跟着你走") {
            openFileInEditor(globalMem)
        })
        memButtons.add(UiKit.textButton("打开记忆所在目录", "两个文件都在这里") {
            runCatching {
                java.awt.Desktop.getDesktop().open(
                    (projMem?.parentFile ?: globalMem.parentFile)
                )
            }
        })
        form.wideRow(memButtons)
        form.wideRow(
            UiKit.hint(
                "记忆放的是**一句话能说清的事实**（这个项目用哪个技术栈、你的偏好、某条约定）；" +
                    "成体系的方法论请让 AI 写成技能。项目记忆会随工程提交，同事拉下来就带着同一份约定。"
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
        form.wideRow(enableSubagentCheck)
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

        // **「关于」贴底** —— 中间的空白由这个 glue 吃掉。
        //
        // 它同时解决两件事：
        //   1. 原来「关于」紧跟在设置项后面，下面拖着一大片空白（用户觉得浪费）
        //   2. 现在那片空白被「关于」自己占住，页面看起来是满的
        form.glue()

        form.section("关于")
        form.row("版本", JBLabel("0.2.0"))
        form.row("平台", JBLabel("Android Studio / IntelliJ 261+"))
        form.row("内置工具", JBLabel("${com.zhixueyao.agent.ToolRegistry.all.size} 个"))
        form.row("选中代码提问", JBLabel("Ctrl+Alt+Z"))
        form.wideRow(UiKit.hint("所有配置都存在本机 IDE 配置目录，不会上传到任何服务器。"))
        form.wideRow(UiKit.hint("API 密钥仅保存在 zhixueyao.xml 中，随 IDE 配置一同管理。"))

        // **这里不再调 form.finish()。**
        // 上面「关于」之前已经有一个 glue 在吃剩余空间了；
        // 两个 glue 会**平分**那片空白 —— 表现是「关于」只被推到一半，
        // 下面还漏出一截。一个页面只需要一个。
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
        // 和 refreshKbInfo 同样的毛病：`Skills.all()` 会**扫多个技能目录、
        // 逐个解析 SKILL.md**，而这里是在 EDT 上同步做的。
        //
        // 技能目录可能装着几十个技能（尤其是「外部（只读）」那种指向别人仓库的），
        // 每次打开设置页全扫一遍 —— 用户没报，是因为他的技能还不多。
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        skillsInfoLabel.text = "正在读取…"
        skillsInfoLabel.foreground = UiKit.subtle
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val list = runCatching { com.zhixueyao.agent.Skills.all(project) }.getOrDefault(emptyList())
            UiKit.ui {
                renderSkillsInfo(project, list)
            }
        }
    }

    /** 把扫好的技能列表铺到界面上。**必须在 EDT 上跑。** */
    private fun renderSkillsInfo(
        project: com.intellij.openapi.project.Project?,
        list: List<com.zhixueyao.agent.Skills.Skill>
    ) {
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
                // 60 而不是 52：现在有两行（描述 + 元信息）
                maximumSize = Dimension(Int.MAX_VALUE, 60)
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

            // 第二行：描述 + 这一技能的「元信息」。
            //
            // 触发词/流程/缺口/引用都是**看不见就等于不存在**的东西 ——
            // 不给用户看到，他既不知道技能为什么会被想起来，也不知道哪条还没写全。
            val desc = JBLabel(
                "<html><body style='width:400px'>" + skill.description.replace("<", "&lt;") + "</body></html>"
            ).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UiKit.subtle
            }
            val metaBits = buildList {
                if (skill.triggers.isNotEmpty()) add("触发：" + skill.triggers.joinToString("、"))
                if (skill.steps.isNotEmpty()) add("流程 " + skill.steps.size + " 步")
                if (skill.crossLinks.isNotEmpty()) add("引用 " + skill.crossLinks.size + " 处")
                if (skill.gaps.isNotEmpty()) add("待补 " + skill.gaps.size + " 处")
            }
            val body = JBPanel<JBPanel<*>>()/* BorderLayout */ .apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            body.add(desc)
            if (metaBits.isNotEmpty()) {
                body.add(
                    JBLabel(metaBits.joinToString("　·　")).apply {
                        font = font.deriveFont(font.size - 2f)
                        foreground = if (skill.gaps.isNotEmpty()) UiKit.warn else UiKit.faint
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        toolTipText = buildString {
                            if (skill.gaps.isNotEmpty()) {
                                append("还没写全的地方：\n")
                                skill.gaps.forEach { append("· ").append(it).append('\n') }
                            }
                            if (skill.steps.isNotEmpty()) {
                                append("流程步骤：").append(skill.steps.joinToString(" → "))
                            }
                        }
                    }
                )
            }
            row.add(body, BorderLayout.CENTER)
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
     * 刷新知识库状态。
     *
     * 只统计**页数与标签数**，不列出全部页 —— 知识库可能几十上百页，
     * 全列出来会把设置页撑爆，而用户真正需要的是「里面有没有东西」。
     * （要看具体有什么，用 list_agents 那种思路的工具更合适，这里只做盘面。）
     */
    private fun refreshKbInfo() {
        // **先给一句话，再去后台读。**
        //
        // 这里原来是同步的：`KnowledgeBase.allPages()` 会**把知识库目录里的 .md
        // 全部读出来解析一遍**。而它被三个地方调，**全在 EDT 上**：
        //   1. 设置页构建时（browse 到这一页就触发）
        //   2. 「重新扫描」按钮
        //   3. 「打开知识库目录」之后
        //
        // 知识库几十上百页时，每次打开设置页就是一次**在界面线程上的全目录扫描**。
        // 这是「EDT 上的阻塞 I/O」扫描查出来的 —— 用户还没报，因为他的知识库还小。
        kbInfoLabel.text = "正在读取…"
        kbInfoLabel.foreground = UiKit.subtle
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // 后台只做「读 + 算」，**不碰任何 Swing 组件**
            val result = runCatching {
                val pages = com.zhixueyao.tools.KnowledgeBase.allPages()
                val tags = com.zhixueyao.tools.KnowledgeBase.allTags()
                Triple(pages.size, tags.size, com.zhixueyao.tools.KnowledgeBase.root().absolutePath)
            }
            UiKit.ui {
                result.onSuccess { (pageCount, tagCount, path) ->
                    kbInfoLabel.text = if (pageCount == 0) {
                        "还没有内容 —— 点「打开知识库目录」把 .md 资料放进去"
                    } else if (tagCount == 0) {
                        "共 $pageCount 页（$path）"
                    } else {
                        "共 $pageCount 页，$tagCount 个标签（$path）"
                    }
                    kbInfoLabel.foreground = if (pageCount == 0) UiKit.warn else UiKit.text
                }.onFailure {
                    kbInfoLabel.text = "读取知识库失败：" + it.message
                    kbInfoLabel.foreground = UiKit.warn
                }
            }
        }
    }

    /** 打开知识库目录（不存在就建出来） */
    private fun openKbDir() {
        val dir = com.zhixueyao.tools.KnowledgeBase.root()
        if (!dir.exists()) dir.mkdirs()
        // 和 openSkillsDir 走同一条路（Desktop.open）—— 那两个按钮用了很久没出过问题，
        // 而 Browsers.openInBrowser 在部分平台对目录不生效。
        // **同一个动作只留一种实现**，免得两个入口行为不一致。
        runCatching {
            java.awt.Desktop.getDesktop().open(dir)
        }.onFailure {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                rootPanel,
                "知识库目录：" + dir.path,
                "止血药 · 知识库"
            )
        }
        refreshKbInfo()
    }

    /**
     * 打开技能目录（不存在就建出来，省得用户自己找路径）。
     *
     * @param projectFirst true = 项目技能库（`<工程>/.zhixueyao/skills`），
     *        false = 全局技能库（`~/.zhixueyao/skills`）。
     *        两个库的用途不一样，所以给两个按钮 —— 只给一个的话，
     *        用户想往全局放东西会被带到项目目录里，反之亦然。
     */
    /**
     * 在 IDE 的编辑器里打开一个文件（文件不存在就先建出来）。
     *
     * 记忆文件是给用户手改的，所以「打开」比「做个编辑界面」更合适 ——
     * Markdown 列表用 IDE 自带的编辑器改最舒服。
     */
    private fun openFileInEditor(file: java.io.File?) {
        val panel = rootPanel ?: return
        if (file == null) {
            Messages.showWarningDialog(panel, "拿不到项目路径，无法定位记忆文件。", "记忆")
            return
        }
        runCatching {
            if (!file.isFile) {
                file.parentFile?.mkdirs()
                file.writeText("# 记忆\n\n<!-- AI 跨会话记住的事实。删掉一行就等于让它忘掉。 -->\n", Charsets.UTF_8)
            }
            val anyProject =
                com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: return@runCatching
            // FileEditorManager 的方法叫 openFile(vf, focus)，不是 openFileInEditor
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(file) ?: return@runCatching
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(anyProject)
                .openFile(vf, true)
        }.onFailure {
            Messages.showWarningDialog(panel, "打不开文件：${it.message}", "记忆")
        }
    }

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
        listSection.add(UiKit.strut(4))
        mcpTable.setShowGrid(false)
        mcpTable.rowHeight = 26
        // 和「临时会话」那张表同一个毛病：四列全写死，合计 636px，
        // 设置页窄一点就横向溢出（用户反馈「插件页被挤压了」）。
        // 同样改成「窄列固定 + 最后一列吸收剩余 + 下限兜底」。
        mcpTable.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
        mcpTable.columnModel.getColumn(0).apply {
            preferredWidth = 48
            maxWidth = 48
        }
        mcpTable.columnModel.getColumn(1).preferredWidth = 140
        mcpTable.columnModel.getColumn(2).preferredWidth = 64
        // 最后一列（描述/工具数这类）自适应；下限 160 保证内容还能读
        mcpTable.columnModel.getColumn(3).apply {
            preferredWidth = 300
            minWidth = 160
        }

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
        listSection.add(UiKit.strut(8))
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
        center.add(UiKit.strut(10))
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

    /**
     * 「Git 助手」页。
     *
     * ## 这一页要解决的具体问题
     *
     * 这台机器的 git 配置里写着 `http.proxy = 127.0.0.1:57567`，
     * 而那个代理**根本没开**。于是每次推送都在敲一扇没人应的门：
     *
     * ```
     * fatal: unable to access '...': Failed to connect to github.com:443
     *        over proxy 127.0.0.1 after 2095 ms: Could not connect to server
     * ```
     *
     * 而这个报错**很容易被读成「网络不通」** —— 很可能去查 VPN、查防火墙、
     * 查 DNS，全查一遍才发现是配置里一个过期的代理地址。
     *
     * 所以这一页的核心不是「填个代理地址」，而是**让这件事可检测**：
     * 点一下就知道当前配置到底通不通、卡在哪一步。
     *
     * ## 为什么检测是机械的，不用 AI
     *
     * 详见 [com.zhixueyao.git.GitProxyDetector] 的注释。一句话：
     * **「通没通」有唯一答案，让模型猜只会更慢、更贵、更不准。**
     * 模型的位置在「检测失败之后**解释原因**」，不在检测本身。
     */
    private fun buildGitPage(): JComponent {
        val form = newForm()

        // **一进来就给个「这是干什么的」的入口。**
        //
        // 用户的原话：「总要有一些检测和提醒吧，我建议加个 git 助理说明的按钮，
        // 用来展示 git 助理所有功能的详细介绍」。
        //
        // 他点中的问题：这一页全是「状态」和「开关」，**没有一处说清这东西是干什么的**。
        // 于是用户看到一个「启用 Git 助手」的开关时，没法判断该不该打开 ——
        // 因为他不知道打开之后会发生什么。而试错成本可能是「AI 拿我的仓库乱搞」。
        form.wideRow(
            JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(UiKit.textButton("Git 助手说明") {
                    try {
                        GitHelpDialog(ProjectManager.getInstance().openProjects.firstOrNull()).show()
                    } catch (t: Throwable) {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            rootPanel, "打不开说明窗口：" + (t.message ?: t.javaClass.simpleName), "止血药"
                        )
                    }
                })
                add(UiKit.hint("它做什么、不做什么、每一项设置怎么选 —— 都在里面"))
            }
        )

        form.section("启用")
        form.wideRow(gitEnabledCheck)
        form.hintRow(
            UiKit.hint(
                "关掉时插件不碰任何 git 设置，也不给 git 命令加参数 —— " +
                    "相当于没装过这个功能。"
            )
        )

        form.section("访问方式")
        gitModeDirect.toolTipText = "不走代理。会**显式清掉** git 配置里残留的代理（那正是常见故障源）"
        gitModeProxy.toolTipText = "手填一个代理地址，例如 http://127.0.0.1:7890"
        gitModeVpn.toolTipText = "扫描本机常见代理端口，自动认出来 —— VPN 换端口也不用改设置"

        // 每个选项**自带一句「什么时候选它」**，而不是只写个名字。
        //
        // 用户的原话：「这个访问方式怎么弄都没有说明」。
        // 第一版我只给了 toolTipText —— 那要**鼠标悬停才出现**，
        // 而且没人会想到去悬停一个单选按钮。**看得见的说明才算说明。**
        gitModeDirect.toolTipText = "不走代理"
        gitModeProxy.toolTipText = "手填代理地址"
        gitModeVpn.toolTipText = "自动扫端口"

        val modeRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 16, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(gitModeDirect); add(gitModeProxy); add(gitModeVpn)
        }
        javax.swing.ButtonGroup().apply {
            add(gitModeDirect); add(gitModeProxy); add(gitModeVpn)
        }
        form.wideRow(modeRow)

        // **跟着选项变的说明。**
        //
        // 比在每个选项旁边写一小句更有效：选项多起来时旁边写不下，
        // 而这里可以写整段 —— 包括「怎么判断自己属于哪种」这种真正有用的信息。
        form.wideRow(gitModeHelp)

        // 代理地址（仅「自定义代理」时可见）
        gitProxyField.columns = 28
        gitProxyField.emptyText.text = "http://127.0.0.1:7890"
        val proxyBefore = form.panel.componentCount
        form.row("代理地址", gitProxyField, "形如 http://主机:端口；不填 http:// 也能认")
        gitProxyRow.addAll(form.panel.components.drop(proxyBefore).toList())

        // VPN 端口（仅「本地 VPN 工具」时可见）
        gitVpnPortField.columns = 10
        gitVpnPortField.emptyText.text = "留空 = 自动检测"
        val vpnBefore = form.panel.componentCount
        form.row("VPN 端口", gitVpnPortField, "留空则自动探测常见端口（60 秒内只探一次，不会频繁打扰代理）")
        gitVpnRow.addAll(form.panel.components.drop(vpnBefore).toList())
        // 这一条是用户实测之后补的 —— 见下面的说明。
        form.hintRow(
            UiKit.hint(
                "**注意：只有「本地开代理端口」的客户端才适用。**\n" +
                    "Clash / v2rayN / Clash Verge 这类会在本机开一个 HTTP 代理端口" +
                    "（7890 / 10809 / 7897 等），能扫到。\n" +
                    "而一键加速类客户端（如 FastConnect、绝大多数手机搬过来的 VPN）走的是" +
                    "**全局 TUN 模式** —— 它直接把系统流量接管了，**本机没有代理端口可以扫**。\n" +
                    "**后者请选「直连」**：流量已经被它接管，再配代理反而绕一圈。"
            )
        )

        val testBtn = UiKit.primaryButton("测试连接") { runGitSelfTest() }
        val testRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(testBtn)
            add(gitStatusLabel)
        }
        form.wideRow(testRow)
        form.hintRow(
            UiKit.hint(
                "「测试连接」做的事：先找到代理 → 再确认它确实是个 HTTP 代理 → " +
                    "最后用它真的去连一次 github.com。三步都过才算通。"
            )
        )

        form.section("账号（决定「能不能推上去」）")
        gitUserField.columns = 24
        gitUserField.emptyText.text = "GitHub 登录名，例如 afxpy"
        form.row("用户名", gitUserField, "GitHub 的登录名。不是秘密，可以直接存")
        form.hintRow(
            UiKit.hint(
                "这一节是**凭它进门**的东西：用户名 + Token 一起用来向 GitHub 证明「你是你」。\n" +
                    "填错或没填 → 推送会被拒绝。"
            )
        )

        gitTokenField.columns = 24
        form.row("Token", gitTokenField, "个人访问令牌。会加密存进系统凭据管理器，不落明文")

        // 获取入口 —— 用户提的：光说「填 Token」而不给去哪儿拿，等于让人自己摸。
        //
        // 做成一排链接：点开就是浏览器对应的页面，省掉「搜一下 github token 在哪」这一步。
        // 顺带把**该勾哪些权限**写清楚 —— GitHub 的 token 权限页有十几项，
        // 全勾了权限过大（等于给了整个账号），不勾又推不上去。
        val tokenLinks = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(UiKit.linkLabel("① 去 GitHub 创建 Token →") {
                browse("https://github.com/settings/tokens/new?scopes=repo&description=Android%20Studio%20%E6%AD%A2%E8%A1%80%E8%8D%AF")
            })
            // **两个链接必须落在同一个 token 类型上。**
            // 用户反馈：创建那个进的是「代币（经典）」，查看那个进的是「细粒度令牌」——
            // 两边不一致，进去找不着刚建的那个。
            // `?type=classic` 显式钉住经典类型。
            add(UiKit.linkLabel("查看已有 Token") {
                browse("https://github.com/settings/tokens?type=classic")
            })
        }
        form.wideRow(tokenLinks)
        form.hintRow(
            UiKit.hint(
                "上面那个链接已经把权限预勾好了（**只勾 repo**，够推送用）。\n" +
                    "**别勾全选** —— Token 的权限等于账号的权限，给多了泄露时损失更大。\n" +
                    "生成后那串 `ghp_...` 只显示一次，复制过来粘到上面即可。"
            )
        )

        // 提交署名。和上面的「认证」是两件事 —— **用户问过这个区别**，
        // 所以这里不是一句话带过，而是把两者的关系讲清楚。
        // ---------------- Git 环境自检 ----------------
        //
        // 用户的原话：「我都感觉可以直接内置脚本不需要 ai 了都直接就是填好信息
        // 检查设备环境配置等」。
        //
        // **他说得对，而且这纠正了我一直以来的做法**：我给了三个文本框让他填，
        // 但新用户**根本不知道自己缺什么** —— 填表的前提是「先知道要填什么」。
        // 自检把这件事反过来：**我来查，查完告诉你、并给你修**。
        //
        // 这些检查没有一项需要 AI（git 装没装、署名配没配、有没有凭据管理器、
        // 连不连得上、配置里有没有残留代理）—— 全是有唯一答案的问题。
        // 和「VPN 检测不该用 AI」是同一条判据。
        form.section("环境自检")
        val recheckBtn = UiKit.textButton("重新检查") { runEnvCheck() }
        val envHead = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(recheckBtn)
            add(UiKit.hint("每项都是本地命令，几秒出结果。不需要联网服务。"))
        }
        form.wideRow(envHead)
        form.wideRow(gitEnvBody)

        // **没配署名时才展开输入框。**
        //
        // 之前是无条件显示两个框让用户填 —— 而绝大多数人早就配过了
        // （用户的原话：「我们本地提交到存储库里面都是要填写 github 所填写的名称和邮箱」，
        // 实测这台机器上确实已经配好了）。
        // 但对**新用户**又不能不给入口 —— 所以「有问题才显示」。
        val authorFix = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            gitAuthorNameField2.columns = 14
            gitAuthorNameField2.emptyText.text = "GitHub 上的名字"
            gitAuthorEmailField2.columns = 20
            gitAuthorEmailField2.emptyText.text = "GitHub 注册邮箱"
            add(gitAuthorNameField2)
            add(gitAuthorEmailField2)
            add(UiKit.textButton("写进 git 全局配置") {
                val n = gitAuthorNameField2.text.trim()
                val e = gitAuthorEmailField2.text.trim()
                if (n.isBlank() || e.isBlank()) {
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        rootPanel, "名字和邮箱都要填 —— 缺一个 git 提交会失败。", "设置署名"
                    )
                } else {
                    val ok = com.zhixueyao.git.GitIdentity.setGlobal(n, e)
                    if (ok) {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            rootPanel, "已写入全局 git 配置：\n  user.name  = $n\n  user.email = $e",
                            "设置署名"
                        )
                        runEnvCheck()
                    } else {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            rootPanel,
                            "写入失败。可以自己在终端执行：\n" +
                                "git config --global user.name \"$n\"\n" +
                                "git config --global user.email \"$e\"",
                            "设置署名"
                        )
                    }
                }
            })
        }
        form.wideRow(authorFix)
        gitAuthorFixRow = authorFix
        // **初始隐藏。** 原来是默认可见 —— 于是「署名填好」的用户也会看到
        // 两个空输入框摆在「环境自检」下面，像是要他填什么。
        // 它应该只在自检发现署名没配时才出现（`needAuthorFix` 控制）。
        authorFix.isVisible = false

        val tokenRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            // **凭据操作一律丢后台。**
            //
            // 用户报「保存到加密存储没反应」—— 因为这里原来直接在 EDT 上
            // 调了 PasswordSafe，而它要走系统凭据管理器（真实 I/O）。
            // 那一步一慢，**整个 EDT 就卡住**，于是连「环境自检」的结果
            // 都回不来（invokeLater 排在后面永远轮不到）。
            //
            // 现在：点一下先给「正在保存…」，后台做完再回报结果。
            add(UiKit.textButton("保存到加密存储") {
                val u = gitUserField.text.trim()
                val t = String(gitTokenField.password)
                gitTokenStatusLabel.text = "正在保存…"
                gitTokenStatusLabel.foreground = UiKit.subtle
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .executeOnPooledThread {
                        val ok = com.zhixueyao.git.GitCredentials.save(u, t)
                        UiKit.ui {
                                gitTokenStatusLabel.text =
                                    if (ok) "已保存" else "保存失败（系统凭据管理器不可用或超时）"
                                gitTokenStatusLabel.foreground =
                                    if (ok) UiKit.subtle else UiKit.danger
                            }
                    }
            })
            add(UiKit.textButton("清除") {
                gitTokenStatusLabel.text = "正在清除…"
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .executeOnPooledThread {
                        com.zhixueyao.git.GitCredentials.clear()
                        UiKit.ui {
                                gitTokenField.text = ""
                                gitTokenStatusLabel.text = "已清除"
                                gitTokenStatusLabel.foreground = UiKit.subtle
                            }
                    }
            })
            add(gitTokenStatusLabel)
        }
        form.wideRow(tokenRow)
        form.hintRow(
            UiKit.hint(
                "Token 存在操作系统的凭据管理器里（Windows 凭据管理器 / macOS 钥匙串），" +
                    "**加密且按用户隔离** —— 和配置文件里那些明文密钥不是一回事。"
            )
        )

        // 模式切换时，把不适用的输入框藏起来 —— 免得用户对着灰色的框猜「该填哪个」
        gitModeDirect.addActionListener { refreshGitVisibility() }
        gitModeProxy.addActionListener { refreshGitVisibility() }
        gitModeVpn.addActionListener { refreshGitVisibility() }
        gitEnabledCheck.addActionListener { refreshGitVisibility() }

        // **收尾必须调 finish()**：它加一个 weighty=1 的 VerticalGlue，
        // 让 GridBagLayout 把内容**靠上排布**。
        // 少了它，内容会被垂直居中/拉伸 —— 而这一页内容少，看起来就是「一片空白」。
        // 其他三个页面都有这一句，我第一版漏了。
        form.finish()
        return form.panel
    }

    /** 按当前模式和总开关，决定哪些行该露出来 */
    private fun refreshGitVisibility() {
        val on = gitEnabledCheck.isSelected
        val m = currentGitMode()

        // **整个模式相关的整块都显示/隐藏，而不是只置灰。**
        //
        // 第一版是把不相关的输入框设成 disabled —— 理由是「隐藏会让高度跳变」。
        // 但用户看到的是一堆灰框，**反而更困惑**：不知道哪些要填、哪些是摆设。
        // 「高度稳定」在「看不懂」面前不值一提。
        gitProxyRow.forEach { it.isVisible = on && m == "proxy" }
        gitVpnRow.forEach { it.isVisible = on && m == "vpn" }

        gitUserField.isEnabled = on
        gitTokenField.isEnabled = on
        if (!on) gitStatusLabel.text = ""

        // 模式说明跟着选中的选项走
        gitModeHelp.text = if (!on) "" else when (m) {
            "proxy" -> "<html><font color='gray'>走你填的代理。适合：<b>知道代理端口</b>的情况 " +
                "（Clash / v2rayN 的界面里能看到端口号，常见 7890 / 10809）。</font></html>"
            "vpn" -> "<html><font color='gray'>自动扫描本机端口找出代理。适合：" +
                "<b>客户端确实开了本地端口、但你不知道是哪个</b>的情况。<br>" +
                "如果你的加速器是<b>全局 / TUN 模式</b>（FastConnect 等一键加速类），" +
                "它<b>不提供本地端口</b> —— 那种情况请选「直连」。</font></html>"
            else -> "<html><font color='gray'>不走代理，直连。" +
                "<b>如果你的加速器是全局模式，选这个就对了</b> —— " +
                "它已经接管了系统流量，再配代理反而绕一圈。<br>" +
                "插件会同时<b>清掉 git 配置里残留的代理</b>" +
                "（那是「明明有网却推不上去」的常见原因）。</font></html>"
        }

        // **token 状态改成异步查。**
        //
        // 原来这里是 `GitCredentials.hasToken()` —— 同步、在 EDT 上、
        // 而它在 `reset()` 里被调用，于是**设置页一打开就去碰系统凭据管理器**。
        // 那一步一卡，整页就没反应了。
        //
        // 现在先显示「查询中…」，后台查完再改文字。用户看到的是一个
        // 短暂的「查询中」，而不是整个页面假死。
        gitTokenStatusLabel.text = "查询中…"
        gitTokenStatusLabel.foreground = UiKit.subtle
        val tokStart = System.currentTimeMillis()
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                // 即使 hasToken 内部有超时（3 秒），这里再包一层 try ——
                // 后台一抛异常，下面那句 invokeLater 就永远不执行，
                // 界面会永远停在「查询中…」而**不报任何错**。
                val has = try {
                    com.zhixueyao.git.GitCredentials.hasToken()
                } catch (t: Throwable) {
                    false
                }
                val tokMs = System.currentTimeMillis() - tokStart
                UiKit.ui {
                        gitTokenStatusLabel.text = if (has) {
                            "已保存（查询用时 ${tokMs} ms）"
                        } else {
                            "未保存（查询用时 ${tokMs} ms）"
                        }
                        gitTokenStatusLabel.foreground = UiKit.subtle
                    }
            }
        rootPanel?.revalidate()
        rootPanel?.repaint()
    }

    /**
     * 跑一遍 Git 环境自检，把结果铺到界面上。
     *
     * 每行 = 一个 ✓/!/✗ + 标题 + 说明 + （如果能修）一个修复按钮。
     * **能看到「缺什么」和「怎么补」，用户就不用自己去搜了。**
     */
    private fun runEnvCheck() {
        val dir = com.intellij.openapi.project.ProjectManager.getInstance()
            .openProjects.firstOrNull()?.basePath?.let { java.io.File(it) }
        gitEnvBody.removeAll()
        // **把「开始」和「结束」都写进界面** —— 这样才能区分两种失败：
        //  - 一直停在「正在检查…」→ 后台没回来（或者结果没回到 EDT）
        //  - 出现了结果行但没内容 → 是渲染的问题
        envStartedAt = System.currentTimeMillis()
        gitEnvBody.add(UiKit.hint("正在检查…（若超过 15 秒未变，请看下方诊断）"))
        gitEnvBody.revalidate()

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // **后台块必须整个包住。**
            //
            // 用户报「重新检查了几分钟没有完成」—— 界面上永远是「正在检查…」。
            // 原因就是这里少了个 try：**后台一抛异常，下面的 invokeLater 就永远不执行**，
            // 界面停在一句「正在检查…」上，而且**不报任何错**。
            //
            // 这个形状很难查：症状是「没反应」，而原因藏在后台线程的堆栈里 ——
            // 用户看不到，日志里也只有一行。**所以宁可显示一条丑一点的错误，
            // 也不要留一个安静的「正在…」。**
            val results = try {
                com.zhixueyao.git.GitEnvironment.check(dir)
            } catch (t: Throwable) {
                UiKit.ui {
                    gitEnvBody.removeAll()
                    gitEnvBody.add(envErrorRow("检查失败：" + (t.message ?: t.javaClass.simpleName), t))
                    gitEnvBody.revalidate()
                    gitEnvBody.repaint()
                    rootPanel?.revalidate()
                }
                return@executeOnPooledThread
            }
            UiKit.ui {
                gitEnvBody.removeAll()
                val elapsed = System.currentTimeMillis() - envStartedAt
                gitEnvBody.add(UiKit.hint("检查完成，用时 ${elapsed} ms。构建版本：$BUILD_STAMP"))
                var needAuthorFix = false
                for (c in results) {
                    val mark = when (c.level) {
                        com.zhixueyao.git.GitEnvironment.Level.OK -> "✓"
                        com.zhixueyao.git.GitEnvironment.Level.WARN -> "!"
                        else -> "✗"
                    }
                    val color = when (c.level) {
                        com.zhixueyao.git.GitEnvironment.Level.OK -> UiKit.subtle
                        com.zhixueyao.git.GitEnvironment.Level.WARN -> UiKit.warn
                        else -> UiKit.danger
                    }
                    // **按钮跟在文字后面，不顶到最右边。**
                    //
                    // 原来是 BorderLayout，按钮扔进 EAST —— 于是它被推到
                    // 面板最右侧，和它要操作的那条说明**隔着大半屏**。
                    // 用户看到的是「一行说明 + 远处一个孤零零的按钮」，
                    // 根本看不出它们是一起的。
                    //
                    // 改成一列：第一行是检查项文字，第二行是按钮（左对齐）。
                    val row = JBPanel<JBPanel<*>>().apply {
                        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                        isOpaque = false
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.empty(3, 0, 3, 0)
                    }
                    row.add(
                        com.intellij.ui.components.JBLabel(
                            "<html><div style='width:640px'>" +
                                "<font color='${if (c.level == com.zhixueyao.git.GitEnvironment.Level.OK) "gray" else "#c0392b"}'>" +
                                "<b>$mark ${c.title}</b></font>　" +
                                "<font color='gray'>${c.detail.replace("<", "&lt;")}</font></div></html>"
                        ).apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }
                    )
                    fixFor(c)?.let {
                        it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        row.add(javax.swing.Box.createVerticalStrut(2))
                        row.add(it)
                    }
                    gitEnvBody.add(row)
                    // 每项之间留一点间距，避免挤成一坨
                    gitEnvBody.add(javax.swing.Box.createVerticalStrut(6))

                    // **原始信息：命令 + 输出。** 只在不通过时显示 ——
                    // 正常时铺一堆命令会淹没重点，出问题时它才是最有用的东西。
                    if (c.level != com.zhixueyao.git.GitEnvironment.Level.OK && c.raw != null) {
                        gitEnvBody.add(
                            com.intellij.ui.components.JBLabel(
                                "<html><pre style='font-size:9px;color:gray'>" +
                                    c.raw.replace("<", "&lt;") + "</pre></html>"
                            ).apply {
                                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.empty(0, 16, 4, 0)
                            }
                        )
                    }
                    if (c.fixKind == com.zhixueyao.git.GitEnvironment.FixKind.EDIT_AUTHOR) needAuthorFix = true
                }
                // 署名那一行只在有问题时露出来
                gitAuthorFixRow?.isVisible = needAuthorFix
                gitEnvBody.revalidate()
                gitEnvBody.repaint()
                rootPanel?.revalidate()
            }
        }
    }

    /** 自检崩了时显示的一行 —— 带上堆栈前几行，让用户能直接把它念出来 */
    private fun envErrorRow(msg: String, t: Throwable): JComponent =
        com.intellij.ui.components.JBLabel(
            "<html><font color='#c0392b'><b>✗ $msg</b></font><br>" +
                "<font color='gray'>这不影响其它设置。把下面这段发出来就能定位：</font>" +
                "<pre style='font-size:9px'>" +
                t.stackTrace.take(4).joinToString("\n") { "at $it" }.replace("<", "&lt;") +
                "</pre></html>"
        ).apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }

    /** 按检查项的 fixKind 给一个修复按钮；没有可修的返回 null */
    private fun fixFor(c: com.zhixueyao.git.GitEnvironment.Check): JComponent? {
        val label = c.fixLabel ?: return null
        // 先声明、后赋值，让下面的 onClick 闭包能拿到这个按钮 ——
        // 要改它的文字和可用状态（「正在安装…」+ 置灰），
        // 否则用户点了之后几十秒里没有任何反馈，会以为没点上。
        lateinit var btn: JComponent
        btn = UiKit.textButton(label) {
            when (c.fixKind) {
                com.zhixueyao.git.GitEnvironment.FixKind.CLEAR_PROXY -> {
                    val ok = com.zhixueyao.git.GitEnvironment.clearProxy(null)
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        rootPanel,
                        if (ok) "已清掉配置里的代理。\n\n注意：这只影响插件看到的 git 配置。" +
                            "如果那个代理还在用，请自己在终端重新配。"
                        else "没清掉，可能要手动执行：\n${c.fixCommand.orEmpty()}",
                        "清除代理配置"
                    )
                    runEnvCheck()
                }
                com.zhixueyao.git.GitEnvironment.FixKind.INSTALL_GCM -> {
                    // 安装要下载/装包，**必须放后台** —— 它会跑几十秒
                    (btn as? javax.swing.JButton)?.apply {
                        isEnabled = false
                        text = "正在安装…"
                    }
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .executeOnPooledThread {
                            val (ok, msg) = com.zhixueyao.git.GitEnvironment.installGcm()
                            com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater(
                                    {
                                        (btn as? javax.swing.JButton)?.apply {
                                            isEnabled = true
                                            text = "自动安装"
                                        }
                                        com.intellij.openapi.ui.Messages.showInfoMessage(
                                            rootPanel, msg,
                                            if (ok) "安装完成" else "安装失败"
                                        )
                                        // 装完如果找到了，顺手启用 —— 用户点一次就全好了
                                        if (ok) {
                                            val (eok, emsg) = com.zhixueyao.git.GitEnvironment.enableGcm()
                                            if (eok) {
                                                com.intellij.openapi.ui.Messages.showInfoMessage(
                                                    rootPanel, emsg, "已同时启用"
                                                )
                                            }
                                        }
                                        runEnvCheck()
                                    },
                                    com.intellij.openapi.application.ModalityState.any()
                                )
                        }
                }
                com.zhixueyao.git.GitEnvironment.FixKind.ENABLE_GCM -> {
                    val (ok, msg) = com.zhixueyao.git.GitEnvironment.enableGcm()
                    com.intellij.openapi.ui.Messages.showInfoMessage(
                        rootPanel, msg, if (ok) "凭据管理器已启用" else "启用失败"
                    )
                    runEnvCheck()
                }
                com.zhixueyao.git.GitEnvironment.FixKind.OPEN_GCM_PAGE ->
                    browse("https://github.com/git-ecosystem/git-credential-manager/releases/latest")
                com.zhixueyao.git.GitEnvironment.FixKind.OPEN_GIT_PAGE ->
                    browse("https://git-scm.com/downloads")
                com.zhixueyao.git.GitEnvironment.FixKind.EDIT_AUTHOR -> {
                    // 展开输入框并滚到它
                    gitAuthorFixRow?.isVisible = true
                    rootPanel?.revalidate()
                }
                else -> {}
            }
        }
        return btn
    }

    /** 用系统默认浏览器打开一个链接。打不开时**如实提示**，不静默。 */
    private fun browse(url: String) {
        val ok = runCatching {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }.isSuccess
        if (!ok) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                rootPanel, "打不开浏览器，请手动访问：\n$url", "打开链接"
            )
        }
    }

    /** 当前选中的访问方式 */
    private fun currentGitMode(): String = when {
        gitModeProxy.isSelected -> "proxy"
        gitModeVpn.isSelected -> "vpn"
        else -> "direct"
    }

    /**
     * 自检：从「当前设置」出发，一步步验证到「能不能真的连上 GitHub」。
     *
     * 刻意**做一个长流程**而不是一个布尔判断 —— 因为它要回答的不是「通不通」，
     * 而是「**卡在哪一步**」。用户看到「端口 7890 开着但不是代理」和看到
     * 「连不上」是完全不同的两种信息：前者去改端口，后者去查 VPN。
     */
    private fun runGitSelfTest() {
        val mode = currentGitMode()
        gitStatusLabel.foreground = UiKit.subtle
        // **说清要等多久。** 原来是干巴巴的「正在检测…」——
        // 扫十几个端口加一次目标站连接，最多要十几秒，
        // 用户不知道是在跑还是卡死了（他原话：「检测很久都没反应」）。
        gitStatusLabel.text = when (mode) {
            "vpn" -> "正在检测…（要扫一圈本机端口，最多十几秒）"
            "proxy" -> "正在检测…"
            else -> "正在检测…（要连一次 github）"
        }

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // 同 runEnvCheck：**不包住就会留一句永远不变的「正在检测…」**
            val (text, ok) = try {
                when (mode) {
                    "proxy" -> testOneProxy(gitProxyField.text.trim())
                    "vpn" -> testVpnMode()
                    else -> "直连模式：不经过代理。" to true
                }
            } catch (t: Throwable) {
                ("检测失败：" + (t.message ?: t.javaClass.simpleName)) to false
            }

            // 直连模式下再往前走一步 —— 真的连一次 github，别只报「设置了直连」
            //
            // （这里原来还有一行 `val r = testThroughProxy("http://127.0.0.1:1")`，
            //   **r 从没被用过** —— 一次白跑的网络调用。已删。）
            val finalPair = if (mode == "direct" && ok) {
                val reachable = try {
                    directReachable()
                } catch (t: Throwable) {
                    false
                }
                if (reachable) "直连模式：不走代理，能连上 github" to true
                else "直连模式：不走代理，但连不上 github（可能是网络本身的问题）" to false
            } else text to ok

            UiKit.ui {
                gitStatusLabel.text = finalPair.first
                gitStatusLabel.foreground = if (finalPair.second) UiKit.subtle else UiKit.danger
            }
        }
    }

    /** 验一个具体的代理地址：是不是代理 + 能不能真的走过去 */
    private fun testOneProxy(url: String): Pair<String, Boolean> {
        if (url.isBlank()) return "请先填代理地址" to false
        val port = com.zhixueyao.git.GitProxyDetector.parsePort(url)
            ?: return "地址看不懂：$url（应该是 http://127.0.0.1:7890 这种）" to false

        val p = com.zhixueyao.git.GitProxyDetector.probeHttpProxy(port)
        if (!p.ok) return "端口 $port：${p.detail}" to false
        val t = com.zhixueyao.git.GitProxyDetector.testThroughProxy(url)
        return t.detail to t.ok
    }

    /** VPN 模式：扫一圈，报告「试了哪些、结果如何」 */
    private fun testVpnMode(): Pair<String, Boolean> {
        val explicit = gitVpnPortField.text.trim().toIntOrNull()
        val d = com.zhixueyao.git.GitProxyDetector.detectLocalProxy(
            extraPorts = if (explicit != null && explicit > 0) listOf(explicit) else emptyList()
        )
        if (!d.found) {
            // **把扫过哪些端口列出来。** 只报「没找到」会让用户怀疑是不是没执行，
            // 而这个功能恰恰是要**消除不确定感**的。
            val tried = d.scanned.take(6).joinToString("、") { "${it.port}(${it.detail})" }
            return "没找到可用的本地代理。已试：$tried" to false
        }
        val t = com.zhixueyao.git.GitProxyDetector.testThroughProxy(d.proxyUrl!!)
        return "找到代理 ${d.proxyUrl}；${t.detail}" to t.ok
    }

    /** 不走代理能不能连上 github —— 用系统 DNS + 直连，给「直连模式」一个真实结论 */
    private fun directReachable(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("github.com", 443), 8000)
            true
        }
    }.getOrDefault(false)

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
        /**
         * 加一段**可被拉伸**的空白，把后面的内容推到底部。
         *
         * 用在「关于」这类「应该贴底」的小节之前：
         * 上面是设置项，下面吊着版本信息，中间的空白由它吃掉 ——
         * **那片空白就从「页面没填满」变成了「有意的间隔」。**
         *
         * 用户的原话：「留到的空间不存储内容属实浪费，所以还不如去掉这一大空白空间，
         * 让关于做底部」。
         */
        fun glue() {
            gbc.gridy = row; gbc.gridx = 0; gbc.weighty = 1.0; gbc.gridwidth = 2
            panel.add(Box.createVerticalGlue(), gbc)
            row++
            // **关键：用完把 weighty 清掉。**
            // 不清的话后面每个组件都会跟着分剩余空间，那些行会被撑开
            //（表现是「行与行之间莫名其妙有空隙」）。
            gbc.weighty = 0.0
            gbc.gridwidth = 1
        }

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
            Triple("git", "Git 助手", AllIcons.Vcs.Branch),
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
