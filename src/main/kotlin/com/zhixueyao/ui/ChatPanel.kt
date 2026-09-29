package com.zhixueyao.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.zhixueyao.agent.AgentPresets
import com.zhixueyao.agent.ContextCompactor
import com.zhixueyao.agent.AgentRunner
import com.zhixueyao.agent.Skills
import com.zhixueyao.agent.AgentUiBridge
import com.zhixueyao.agent.ApprovalDecision
import com.zhixueyao.agent.ApprovalRequest
import com.zhixueyao.agent.AskResult
import com.zhixueyao.agent.Sandbox
import com.zhixueyao.agent.TaskItem
import com.zhixueyao.agent.ToolRegistry
import com.zhixueyao.llm.ChatMessage
import com.zhixueyao.llm.Usage
import com.zhixueyao.llm.Providers
import com.zhixueyao.llm.ThinkingLevel
import com.zhixueyao.llm.ToolCall
import com.zhixueyao.mcp.McpManager
import com.zhixueyao.mcp.McpServerRegistry
import com.zhixueyao.settings.ZhixueyaoConfigurable
import com.zhixueyao.settings.ZhixueyaoSettings
import com.zhixueyao.tools.ToolResult
import com.zhixueyao.util.AttachmentSupport
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLayeredPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 聊天面板。
 *
 * 结构自外向内三层：
 *  1. 顶栏 —— 品牌标识、模型切换器、设置与常用操作入口
 *  2. 消息区 —— 可滚动的对话气泡列表
 *  3. 输入区 —— 附件条 + 输入框 + 发送按钮
 *
 * 所有网络与工具执行都在后台线程，界面更新统一通过 [edt] 切回 EDT。
 */
class ChatPanel(private val project: Project) : JBPanel<ChatPanel>(BorderLayout()), Disposable {

    private val log = Logger.getInstance(ChatPanel::class.java)

    private companion object {
        /** 气泡最多占消息区可用宽度的比例 —— 留出对侧空白，一眼能分出谁在说话 */
        const val BUBBLE_WIDTH_RATIO = 0.82

        /**
         * 消息区最多常驻多少个「行」（气泡 + 分隔条各算一个）。
         * 几百轮的会话全量渲染会有明显的布局开销，这里给个上限兜住。
         */
        const val MAX_MESSAGE_ROWS = 240

        /** 气泡宽度下限：窗口很窄时不再按比例缩，否则会挤成一条 */
        const val MIN_BUBBLE_WIDTH = 200

        /**
         * 附件胶囊里缩略图的边长。
         *
         * 18 是权衡值：再小就看不出是张什么图，再大一个胶囊就要 160px+、
         * 一行放不下几个（用户要的是「能一次挂好几张」）。
         */
        const val THUMB_PX = 18

        /**
         * 附件名最多显示几个字，超出用 … 截断（完整名在 tooltip 里）。
         *
         * 必须截断：文件名一长，**一个胶囊就能占满整行**，
         * 后面的附件被挤到看不见（用户报过这个）。
         */
        const val ATTACH_NAME_MAX = 14

        /** 输入区两张卡片的标识：正常输入 / 需要用户拍板 */
        const val CARD_INPUT = "input"
        const val CARD_CHOICE = "choice"

        /**
         * [askChoice] 里「用户点了取消」的哨兵值。
         *
         * 不能和「没人点过」（初值 -1）共用：取消 = 用户看过并否掉了，必须停；
         * 没人点过 = 超时，可以让模型按假设继续。两者语义相反。
         */
        const val CANCELLED = -2
    }

    /** [askChoice] 的三态结果。见 [CANCELLED] 的注释：取消和超时必须分开 */
    private sealed interface ChoiceResult {
        data class Picked(val index: Int) : ChoiceResult
        data object Cancelled : ChoiceResult
        data object NoAnswer : ChoiceResult
    }

    private val history = mutableListOf<ChatMessage>()

    /**
     * MCP 连接池用**全局共享**的那一份，不要自己 new。
     *
     * 装 MCP 的工具（`manage_mcp`）在后台线程跑，它只能拿到共享实例；
     * 面板这边要是自己 new 一个，两边连的东西就分家了 ——
     * 「装完 MCP，工具却在下一轮还是没出现」就是这么来的。
     */
    private val mcpManager = McpManager.getInstance()
    private val runner = AgentRunner(project, mcpManager)
    private val cancelFlag = AtomicBoolean(false)

    /**
     * 生成期间用户打好、等待发出的内容（「预发送」队列）。
     *
     * 本轮结束时由 [flushQueued] 自动取队首发出去；那一轮结束再取下一队首，
     * 于是**按顺序逐条接续执行**，用户不用盯着。
     *
     * 以前只有一个 `String?` 槽位：排队第二句会把第一句**覆盖掉**，
     * 而且覆盖是静默的（只在状态栏飘一句），等于悄悄丢消息。
     */
    private val queued = mutableListOf<String>()

    /**
     * 「运行中追加输入」的收件箱（steer）。
     *
     * 和 [queued]（本轮结束后才发）**是两条不同的通道**，各有各的用途：
     *  - `queued`：等这一轮答完再说 → 适合「新话题」（比如「答完了再帮我做另一件事」）
     *  - 这里：**下一个 turn 边界就插进去**，模型带着新信息重新规划，
     *    **已经做完的工作全部保留** → 适合「纠正方向」（「别改那个文件，改这个」）
     *
     * 后者以前完全没有：用户想纠偏只能「停止 → 等收尾 → 重说一遍」，
     * 而 MCP 调用最长要等 2 分钟才停得下来，中间做的工作也全废。
     * 借鉴自参考项目 astravia 的 steer。
     *
     * 用**线程安全队列**：写入来自 EDT，读取来自 Agent 的后台线程。
     */
    private val steeringInbox = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * 待发送队列的预览条（在输入框上方，只在有排队内容时出现）。
     *
     * 每条都带一个 × 可以单独删；右上角一个「全部清空」。
     * 以前队列不可见：排了什么、排了几条，用户只能从状态栏那句话猜。
     */
    private val queuedStrip = JBPanel<JBPanel<*>>().apply {
        layout = StackLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }

    /** 待发送的附件 */
    private val attachments = mutableListOf<PendingAttachment>()

    /**
     * 一次 Agent 任务所处的阶段。
     *
     * 为什么要显式建模（而不是继续用一个 `isRunning` 布尔）：
     * 布尔说不出「在思考」和「在调工具」的区别，更要命的是**说不出「被停止」**——
     * 用户点停止之后，`onComplete` 照常走完成流程，状态栏那句「正在停止…」
     * 还会被收尾时的 token 用量覆盖掉，界面上完全分不出「答完了」和「被停了」。
     * 收敛到一处之后，每个阶段的界面表现（状态栏、停止按钮、气泡页脚）才有唯一出处。
     */
    private enum class Phase(val label: String) {
        IDLE("空闲"),
        THINKING("思考中"),
        TOOL_CALLING("执行工具"),
        ANSWERING("回答中"),
        ERROR("出错"),
        CANCELLED("已停止");

        /** 只有这三个阶段算「正在跑」 */
        val running: Boolean get() = this == THINKING || this == TOOL_CALLING || this == ANSWERING
    }

    private var phase = Phase.IDLE
        private set

    /** 用户点过停止。用来把「被停止的收尾」和「正常答完」分开 */
    private var cancelRequested = false

    /**
     * 正在等用户点击的 latch（见 [askChoice]）。
     *
     * 关窗口时要把它们放掉：否则后台线程会**白等满 5 分钟** ——
     * 界面都没了，没人能点那个按钮。
     */
    private val pendingChoices =
        java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CountDownLatch>()

    /**
     * 当前正在生成的那条气泡。
     *
     * [setPhase] 靠它把「思考中 / 执行工具 / 回答中」实时写到气泡头部 ——
     * 否则流式中的气泡和「已经答完」长得一模一样，扫一眼会以为做完了。
     */
    private var liveBubble: MessageBubble? = null

    /**
     * 「重新生成」时暂存旧回答的版本列表。
     *
     * 生成完把新回答**追加**进去（而不是让旧版消失）—— 想「还是上一版好」时能切回去。
     * 非空 = 本轮是重新生成。
     */
    private var regenVariants: List<String> = emptyList()

    private var isRunning = false

    // ---------------- 顶栏控件 ----------------

    /**
     * 顶栏只留两个元素：品牌 + 状态点/齿轮。
     *
     * 这是修「顶栏文字重叠」的根治手段。之前顶栏塞了品牌、标题、模型胶囊、
     * 预设胶囊、状态点、状态文字、MCP 徽标、分隔线、四个按钮 —— 共十一个元素。
     * 工具窗口一拖窄，Swing 会压缩容器但**不裁剪子组件**，内容直接溢出压到隔壁，
     * 于是出现「止血药」和模型名叠在一起。换 GridBagLayout 也没用，
     * 因为溢出的根源是子组件的最小尺寸之和超过了可用宽度。
     *
     * 解法参照 ProxyAI：它的工具栏只有一个按钮，模型选择器放在输入面板里。
     * 我们也把选择器下移到输入卡片 —— 那里宽度更宽裕，也更符合「先选模型再说话」的顺序。
     */
    private val connDot = UiKit.statusDot(UiKit.faint)
    private val settingsButton = UiKit.iconButton(UiKit.gear, "打开止血药设置") { openSettings() }

    /** 模型与模式选择器，挂在输入卡片顶部而非顶栏 */
    private val modelPill = UiKit.pillButton("未配置", UiKit.arrowDown) { showModelMenu() }
    private val presetPill = UiKit.pillButton("标准", UiKit.arrowDownSmall, "Agent 运行模式") { showPresetMenu() }
    private val mcpBadge = UiKit.badge("", UiKit.subtle) { reconnectMcp() }

    /**
     * 思考模式：点开是一个下拉，列出**设置里勾选过**的思考强度档位。
     *
     * 为什么只显示勾选过的：档位有 7 档（默认/关闭/最低/低/中/高/最高），全塞进菜单
     * 会让人每次都要在一堆选项里找。设置里勾选 = 「我常用的就这几档」，
     * 菜单只列这几档；一个都没勾时只有「默认」，此时强度跟随服务商与模型自己决定。
     */
    private val thinkingPill = UiKit.pillButton(
        "思考·默认",
        UiKit.arrowDownSmall,
        "选择思考强度（在设置 → 模型的「思考强度」里勾选要显示哪些档位）"
    ) { showThinkingMenu() }

    /**
     * 沙盒权限：点开切换「仅当前项目 / 全盘沙盒 / 每次询问」。
     *
     * 放在输入区的选择器行而不是顶栏：它和模型、运行模式属于同一类东西
     * （「这一轮按什么规矩干」），放一起更好找；顶栏宽度也已经很紧张。
     */
    private val sandboxPill = UiKit.pillButton(
        "沙盒·项目",
        UiKit.arrowDownSmall,
        "选择沙盒权限：仅当前项目 / 全盘沙盒 / 每次询问"
    ) { showSandboxMenu() }

    /** 产物面板开关（顶栏） */
    private val artifactsButton = UiKit.iconButton(
        UiKit.file,
        "查看本次会话产出的文件"
    ) { toggleArtifacts() }

    // ---------------- 任务清单 / 选择题 / 产物 ----------------

    /** 输入区的两张卡片：正常输入 / 需要用户拍板时的选择题 */
    private val inputStackLayout = CardLayout()
    private lateinit var inputStack: JBPanel<JBPanel<*>>
    private lateinit var choicePanel: JBPanel<JBPanel<*>>
    private val choiceTitle = JBLabel()

    /**
     * 选择题的说明文字。
     *
     * 用可折行的只读文本区而不是 HTML 标签：HTML 的 width 是写死的像素值，
     * 窗口拉宽/收窄时它不会跟着变（「拉伸时说明不跟着动」就是这类写死宽度造成的）。
     */
    private val choiceNote = javax.swing.JTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        font = font.deriveFont(font.size - 0.5f)
        foreground = UiKit.subtle
        alignmentX = Component.LEFT_ALIGNMENT
        isFocusable = false
    }
    /**
     * 选项列表。用 [StackLayout] 纵向堆叠、每行撑满宽度 ——
     * 选项文案由模型生成，长度不可控，只有「整行 + 折行」才不会被撑成一整条大色块。
     */
    private val choiceButtons = JBPanel<JBPanel<*>>().apply {
        layout = StackLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /**
     * 任务清单（AI 用 todo_write 写入，显示在输入区上方）。
     *
     * 高度必须覆写 getMaximumSize() 动态求值 —— 它被放进纵向 BoxLayout，
     * 不限制的话会被拉满整屏，把输入框挤出去（详见 UiKit 里 alignmentX 的说明）。
     */
    private val taskPanel = object : JBPanel<JBPanel<*>>(BorderLayout(0, 4)) {
        init {
            isOpaque = true
            background = UiKit.card
            border = JBUI.Borders.compound(UiKit.cardBorder(), JBUI.Borders.empty(7, 9))
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            isVisible = false
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val taskBody = JBPanel<JBPanel<*>>()

    /** 产物面板：本次会话写出/改过哪些文件 */
    private val artifactsPanel = object : JBPanel<JBPanel<*>>(BorderLayout(0, 4)) {
        init {
            isOpaque = true
            background = UiKit.card
            border = JBUI.Borders.compound(UiKit.cardBorder(), JBUI.Borders.empty(7, 9))
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            isVisible = false
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val artifactsBody = JBPanel<JBPanel<*>>()
    private val artifacts = mutableListOf<String>()
    private var artifactsVisible = false

    /**
     * 顶栏右侧的「会话」与「清空」入口，紧挨设置按钮。
     *
     * 原先这两个是底部状态行里的文字按钮 —— 状态行在最下方，视线要跨过整个输入区
     * 才看得到，而它们都是「一打开面板就想点」的操作，所以上移到顶栏。
     *
     * 做成**图标按钮**而不是文字按钮：顶栏宽度是稀缺资源，文字按钮一多，
     * 窗口拖窄时两列会互相压盖（顶栏历史上正是这么踩过坑，见 [buildHeader] 注释）。
     * 语义靠 tooltip 承载，视觉上三个图标按钮同尺寸同风格，和齿轮连成一组。
     */
    private val sessionButton = UiKit.iconButton(
        UiKit.history,
        "会话：新建、保存、切换历史对话"
    ) { showSessionMenu() }

    private val clearButton = UiKit.iconButton(
        UiKit.clearAll,
        "清空当前对话，重新开始"
    ) { clearHistory() }

    // ---------------- 消息区 ----------------

    /**
     * 消息流容器。
     *
     * 实现 [javax.swing.Scrollable] 并把 `getScrollableTracksViewportWidth()` 设为 true，
     * 是为了**把宽度钉死在视口宽度上**。
     *
     * 不这么写会怎样：`JViewport` 对普通组件是按「preferredSize 与视口取大者」来摆放的，
     * 消息区里只要有一条宽内容（长代码行、长 URL、HTML 文本块）把 preferredSize 撑大，
     * 整个面板就会比视口宽 —— 而横向滚动条被策略关掉了，于是右侧被静默裁掉，
     * 同时纵向 BoxLayout 会以这个偏宽的宽度重新计算横向对齐，内容看起来「整体跑偏」。
     * 钉住宽度后，横向永远等于视口宽，只保留纵向滚动。
     */
    private val messagesPanel = object : JBPanel<JBPanel<*>>(), javax.swing.Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 10)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        /**
         * 挂进来的东西一律左对齐。
         *
         * 这是纵向 BoxLayout 的硬要求：横向位置只按各子组件的 alignmentX 算，
         * 而 JComponent 的默认值是 **0.5（居中）**。哪怕只有一个子组件漏设，
         * 同一个容器里其余组件都会被推右、并被压窄到约 2/3
         * —— 表现就是「气泡整体右移、左边空一大片」。
         * 把规则钉在 add 这一步，就不用指望每个调用点都记得手写。
         */
        override fun addImpl(comp: java.awt.Component, constraints: Any?, index: Int) {
            super.addImpl(comp, constraints, index)
            if (comp is JComponent) comp.alignmentX = Component.LEFT_ALIGNMENT
        }

        /** 宽度始终等于视口宽度 —— 不允许横向溢出 */
        override fun getScrollableTracksViewportWidth(): Boolean = true

        /** 高度按内容走，保留纵向滚动 */
        override fun getScrollableTracksViewportHeight(): Boolean = false

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 16

        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 40
    }
    /**
     * 浮在消息区底部的「回到底部」按钮。
     *
     * 为什么需要：AI 思考/回答时用户往往停在上面看，新内容在下面长出来，
     * 想看最新就得手动往下拖（用户反馈「需要下滑到最底下才知道」）。
     * 主流 agent 的做法都是浮一个圆形按钮（见 WorkBuddy）。
     */
    /**
     * 「回到最新」。
     *
     * 之前是浮在消息区底部的一个圆形浮层 —— 悬浮必然**盖住内容**，
     * 用户反馈「直接覆盖掉了聊天框看不见」，而且那个圆底方框也确实不好看。
     * 现在改成和「模型 / 沙盒 / 思考」同款的小胶囊，放在**标识行**里（MCP 徽标左边）：
     * 位置固定、不挡消息、样式也统一。
     *
     * 默认隐藏，只有「不在底部」时才出现（滚动监听里切换）。
     */
    private val scrollToBottomButton: JButton =
        UiKit.pillButton("回到最新", UiKit.arrowDownSmall, "跳到最新消息") { scrollToBottom() }
            .apply { isVisible = false }

    /** 因为界面窗口化而被省略的消息条数 */
    private var trimmedRows = 0

    /** 「已省略更早消息」提示（挂在消息区顶部） */
    private val trimHint = JBLabel().apply {
        font = font.deriveFont(font.size - 1.5f)
        foreground = UiKit.faint
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 4, 6, 4)
    }

    /** 欢迎页引用：发出第一条消息后要收掉它，见 [addUserBubble] */
    private var welcomePage: JComponent? = null

    private val messagesScroll = JBScrollPane(messagesPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        isOpaque = false
        viewport.isOpaque = false
    }

    // ---------------- 输入区 ----------------

    /**
     * 输入历史的游标：-1 = 不在翻历史（正在打新内容）。
     * 翻历史时把「正在打的内容」暂存到 [historyDraft]，翻回来再还给你。
     */
    private var historyCursor = -1
    private var historyDraft = ""

    /**
     * 草稿落盘防抖：打字时别每敲一个键就写一次配置。
     *
     * **显式标注类型**是必须的：它的 lambda 里用到 `inputArea`，而 `inputArea` 的
     * 初始化里又要 `draftSaveTimer.restart()` —— 不标注的话 Kotlin 推不出类型
     * （报 "recursive problem"）。
     */
    private val draftSaveTimer: javax.swing.Timer = javax.swing.Timer(1500) {
        com.zhixueyao.chat.SessionStore.saveDraft(currentSessionId, inputArea.text)
    }.apply { isRepeats = false }

    private val inputArea = object : JBTextArea() {
        init {
            lineWrap = true
            wrapStyleWord = true
            rows = 3
            border = JBUI.Borders.empty(8, 10)
            font = font.deriveFont(font.size + 0.5f)
            isOpaque = false
            // 草稿：切会话/重启 IDE 都不该把打了一半的内容弄丢
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = draftSaveTimer.restart()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = draftSaveTimer.restart()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = draftSaveTimer.restart()
            })
            // 上下键翻输入历史。只在**单行**时接管 —— 多行内容里上下键要留给光标移动，
            // 否则改不了上一行的错字。
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode != java.awt.event.KeyEvent.VK_UP && e.keyCode != java.awt.event.KeyEvent.VK_DOWN) return
                    if (inputText().contains('\n')) return
                    val list = com.zhixueyao.chat.SessionStore.inputHistory()
                    if (list.isEmpty()) return
                    if (e.keyCode == java.awt.event.KeyEvent.VK_UP) {
                        if (historyCursor == -1) {
                            historyDraft = inputText()
                            historyCursor = list.size - 1
                        } else if (historyCursor > 0) {
                            historyCursor--
                        } else {
                            return
                        }
                    } else {
                        if (historyCursor == -1) return
                        if (historyCursor < list.size - 1) historyCursor++ else {
                            // 翻到最新再往下 → 回到「正在打的内容」
                            historyCursor = -1
                            setInputText(historyDraft)
                            e.consume()
                            return
                        }
                    }
                    setInputText(list[historyCursor])
                    e.consume()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && !hasFocus()) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.color = UiKit.faint
                    g2.font = font
                    g2.drawString("描述你想做的事，Enter 发送 / Shift+Enter 换行", 11, font.size + 9)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * 附件条（有附件时才出现）。
     *
     * 它和任务清单、产物面板同处一个纵向 BoxLayout —— 所以必须
     * ① 显式左对齐（默认 0.5 会和兄弟组件混用，把整块推偏）；
     * ② 覆写 getMaximumSize 限制高度。不限制的话 BoxLayout 会把多余纵向空间
     *    全分给它（最大高度是 32767），任务清单和产物面板会被压成一条缝。
     */
    /**
     * 附件条。
     *
     * 布局必须用 [WrapLayout] 而**不是**标准 FlowLayout —— 原因见那个类的注释：
     * 标准 FlowLayout 按「自己的当前宽度」算高度，首次布局时宽度还是 0，
     * 于是按单行算高度；外面 BoxLayout 又按这个高度分配，
     * **换行到第二排的芯片直接被裁掉**（用户报的「多张图只看到第一张」）。
     */
    private val attachmentBar = object : JBPanel<JBPanel<*>>(WrapLayout(hgap = 6, vgap = 4)) {
        init {
            isVisible = false
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * 输入区的两个方形按钮，参照 Pylon 的 composer：
     * 上传是**回形针**（语义比上传箭头贴切），发送是**向上箭头**加品牌实底。
     * 两者同尺寸同圆角，靠 [UiKit.Variant] 区分外观。
     */
    private val attachButton = UiKit.squareButton(
        UiKit.attach,
        "上传文件（也可直接拖入或 Ctrl+V 粘贴图片）",
        UiKit.Variant.QUIET
    ) { chooseFiles() }

    private val sendButton = UiKit.squareButton(
        UiKit.sendUp,
        "发送（Enter）",
        UiKit.Variant.BRAND
    ) { onSend() }

    private val stopButton = UiKit.squareButton(
        UiKit.stop,
        "停止生成",
        UiKit.Variant.DANGER
    ) { onStop() }.apply { isVisible = false }

    private val statusLabel = UiKit.hint("")

    /**
     * 摘要的跨轮状态（增量摘要用）。
     *
     * **必须跟着会话走**：换会话、清空对话时要 [AgentRunner.SummaryState.reset]，
     * 否则新会话会带着上一段对话的摘要，模型会「记得」根本没发生过的事。
     */
    private val summaryState = AgentRunner.SummaryState()

    private class PendingAttachment(
        val path: Path?,
        val processed: AttachmentSupport.Processed,
        val displayName: String
    )

    init {
        isOpaque = true
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        installScrollIntentTracking()
        buildUi()
        installShortcuts()
        installDropTarget()
        addWelcomeMessage()
        // 把界面接到工具层：沙盒授权、任务清单、AI 提问、产物记录都走这条线
        AgentUiBridge.register(project, UiBridgeHandler())
        // 「回到底部」浮层的显隐：只要不在底部（思考中、回答完、自己往上翻了）就浮出来
        messagesScroll.verticalScrollBar.addAdjustmentListener {
            val bar = messagesScroll.verticalScrollBar
            scrollToBottomButton.isVisible = bar.value < bar.maximum - bar.visibleAmount - 24
        }
        refreshSandboxPill()
        loadArtifacts()
        refreshHeader()
        updateStatus()
        installInputWatcher()
    }

    /**
     * 输入框内容变化时同步发送按钮的可用状态。
     *
     * 挂 DocumentListener 而不是 KeyListener：粘贴、拖入、程序化写入
     * （`inputArea.text = ...`）都不产生按键事件，只有文档变更事件能全覆盖。
     */
    private fun installInputWatcher() {
        inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshSendState()

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshSendState()

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshSendState()
        })
        refreshSendState()
    }

    /**
     * 刷新发送按钮的可用态。
     *
     * 规则：**有文字或有附件**才可发送，且生成中不可发送。
     * 空输入时按钮变灰且不可点 —— 之前它是常亮的，点了没反应（onSend 里直接 return），
     * 用户会以为是坏的。
     */
    private fun refreshSendState() {
        val hasContent = inputArea.text.isNotBlank() || attachments.isNotEmpty()
        // 生成中**也可点**：点了就是排队（预发送），本轮结束自动发出
        sendButton.isEnabled = hasContent
        // 生成中：一旦有内容就把发送放出来（和停止按钮并排），没内容只留停止按钮。
        // 否则用户打了字却找不到发送入口 —— 这也是「无法输入」观感的一部分。
        sendButton.isVisible = !isRunning || hasContent
        sendButton.toolTipText = when {
            isRunning -> "插入本轮（Enter）：模型下一步就会看到，已做的工作不丢\n" +
                "想等这一轮答完再说，用 Alt+Enter（排队）"
            !hasContent -> "输入内容或添加附件后即可发送"
            else -> "发送（Enter）"
        }
        sendButton.repaint()
    }

    // ---------------- 界面搭建 ----------------

    private fun buildUi() {
        add(buildHeader(), BorderLayout.NORTH)
        add(messagesScroll, BorderLayout.CENTER)
        add(buildInputSection(), BorderLayout.SOUTH)
    }

    /**
     * 顶栏：品牌 + 连接状态点 + 设置按钮，只有这些。
     *
     * 布局用 GridBagLayout：左侧 weightx=1 吃掉多余宽度，右侧 weightx=0 保持自然尺寸。
     * 由于两边内容都极简（左 3 个元素、右 2 个元素），任何宽度下都不存在压缩溢出。
     * 状态点的 tooltip 承载了原先 connLabel 的文字信息，宽度不够时不会挤成重影。
     */
    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = true
            background = UiKit.header
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(UiKit.border),
                JBUI.Borders.empty(6, 10)
            )
        }

        // 左：品牌图标 + 名称 + 连接状态点。
        //
        // 状态点从右侧移到标题旁边：右侧要放 4 个操作按钮 + 一个胶囊，宽度本来就紧张，
        // 而状态点只有 8px、语义上又和「这是谁」绑在一起（一眼看到「止血药 ●」就够），
        // 放左边既省了右列宽度，也让「当前连没连上」第一眼就有答案。
        val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        left.add(JLabel(UiKit.brandIcon))
        left.add(UiKit.title("止血药"))
        left.add(connDot)

        header.add(left, GridBagConstraints().apply {
            gridx = 0; gridy = 0
            weightx = 1.0; weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        })

        // 右：会话 / 产物 / 清空 / 设置。
        // 全是小尺寸图标按钮：窄窗口下最小宽度也只有百来像素，不会把左列挤爆。
        // （思考模式那个胶囊原先也在这里，但它带文字、宽 200px 出头，是顶栏被挤的主因，
        //   已下移到输入区的选择器行 —— 那里和模型、运行模式、沙盒是一组，语义也更顺。）
        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply { isOpaque = false }
        right.add(sessionButton)
        right.add(artifactsButton)
        right.add(clearButton)
        right.add(settingsButton)

        header.add(right, GridBagConstraints().apply {
            gridx = 1; gridy = 0
            weightx = 0.0; weighty = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.EAST
        })

        return header
    }

    /**
     * 输入区。
     *
     * 三行结构，自上而下：
     *  1. 选择器行 —— 模型/服务商 + Agent 模式 + MCP 徽标（从顶栏下移到这里）
     *  2. 输入卡片 —— 输入框在上，方形按钮行贴在卡片底沿
     *  3. 状态行 —— 左侧提示文字 + 右侧清空
     *
     * 选择器放这里而不是顶栏有两个原因：一是顶栏宽度不够（见 [buildHeader] 注释），
     * 二是「先选模型、再打字、再发送」本身就是从上到下的顺序，放输入框上方更顺。
     *
     * 按钮布局参照 Pylon 的 composer：**左回形针、右发送**，都是 34×34 圆角方形，
     * 中间留白。发送是品牌实底（视觉焦点），上传是透明的（安静待命）。
     */
    private fun buildInputSection(): JComponent {
        val root = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            /**
             * 输入区高度**封顶**。
             *
             * 它是根布局的 SOUTH，高度直接取首选高度 —— 只要首选高度失控，
             * 它就会把整个窗口吃掉、消息区被压成 0（用户截图的现象：
             * 输入框占满全屏、还输不进东西）。这里按面板高度的 45% 封顶，
             * 内容再多也只在内部滚动，绝不挤掉消息区。
             */
            override fun getPreferredSize(): Dimension {
                val d = super.getPreferredSize()
                val avail = parent?.height ?: 0
                if (avail <= 0) return d
                val cap = maxOf(avail * 45 / 100, JBUI.scale(160))
                return Dimension(d.width, minOf(d.height, cap))
            }
        }.apply {
            isOpaque = true
            background = com.intellij.util.ui.UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(UiKit.border),
                JBUI.Borders.empty(6, 10, 8, 10)
            )
        }

        // ---- 1. 选择器行 ----
        val selectorRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(5)
        }
        val selectorLeft = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        selectorLeft.add(modelPill)
        selectorLeft.add(presetPill)
        selectorLeft.add(sandboxPill)
        selectorLeft.add(thinkingPill)
        selectorRow.add(selectorLeft, BorderLayout.CENTER)

        // MCP 徽标必须包一层容器再放 EAST。
        //
        // 直接放会出问题：BorderLayout 先满足 CENTER（选择器行），EAST 只拿到
        // 剩余宽度，而剩余宽度在窗口偏窄时可能是 0 或负数 —— 徽标被裁掉一半，
        // 显示成半个「MCP」（用户截图里的现象）。包一层并给它一个左内边距，
        // 相当于给它一块保底的面板，Swing 就不会把它压没了。
        val badgeBox = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(scrollToBottomButton)
            add(mcpBadge)
        }
        selectorRow.add(badgeBox, BorderLayout.EAST)
        root.add(selectorRow, BorderLayout.NORTH)

        // 输入区上方堆叠三块，各自独立显隐：产物面板 / 任务清单 / 附件条
        val center = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        buildArtifactsPanel()
        buildTaskPanel()
        val stackTop = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        stackTop.add(artifactsPanel)
        stackTop.add(taskPanel)
        stackTop.add(attachmentBar)
        center.add(stackTop, BorderLayout.NORTH)

        // ---- 2. 输入卡片 ----
        val inputCard = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UiKit.input
            border = JBUI.Borders.compound(
                UiKit.cardBorder(),
                JBUI.Borders.empty(4, 5, 5, 5)
            )
        }

        // 队列预览条挂在输入区**上方**（BorderLayout.NORTH）：
        // 它出现/消失时输入框只是往下挪，不会把已打好的内容顶走
        inputCard.add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(queuedStrip, BorderLayout.CENTER)
            },
            BorderLayout.NORTH
        )
        inputCard.add(
            JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                isOpaque = false
                viewport.isOpaque = false
            },
            BorderLayout.CENTER
        )

        // 按钮行：左回形针（安静），右 停止/发送（停顿时才出现停止）
        val buttonRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
        }
        buttonRow.add(attachButton, BorderLayout.WEST)

        val rightButtons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        rightButtons.add(stopButton)
        rightButtons.add(sendButton)
        buttonRow.add(rightButtons, BorderLayout.EAST)
        inputCard.add(buttonRow, BorderLayout.SOUTH)

        // 输入卡片与「选择题」共用同一个卡位：需要用户拍板时整块换成选择题
        // （对齐 Codex 的做法 —— 问题出现在你正要输入的地方，不用另找窗口）
        //
        // 卡槽的首选高度**只按当前显示的那张卡算**。CardLayout 默认取所有卡片的
        // 较大值 —— 选择题卡片比输入卡片高的时候，输入框会被撑到选择题那么高，
        // 甚至吃掉整个窗口（用户截图里输入框占满全屏就是这个）。
        // 卡片槽就该是「谁在显示、按谁算」。
        inputStack = object : JBPanel<JBPanel<*>>(inputStackLayout) {
            override fun getPreferredSize(): Dimension {
                val shown = components.firstOrNull { it.isVisible } ?: return super.getPreferredSize()
                val d = shown.preferredSize
                val ins = insets
                return Dimension(d.width + ins.left + ins.right, d.height + ins.top + ins.bottom)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply { isOpaque = false }
        inputStack.add(inputCard, CARD_INPUT)
        inputStack.add(buildChoicePanel(), CARD_CHOICE)
        center.add(inputStack, BorderLayout.CENTER)
        root.add(center, BorderLayout.CENTER)

        // ---- 3. 状态行：只剩提示文字（会话 / 清空已上移到顶栏）----
        val bottom = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(5)
        }
        // 状态文字允许被压缩，且过长时自身截断
        statusLabel.minimumSize = Dimension(0, statusLabel.preferredSize.height)
        bottom.add(statusLabel, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        return root
    }

    // ---------------- 沙盒 / 任务清单 / 选择题 / 产物 ----------------

    /** 选择题面板 —— 用户拍板的地方（沙盒授权、AI 提问共用） */
    private fun buildChoicePanel(): JComponent {
        choiceTitle.font = choiceTitle.font.deriveFont(Font.BOLD)
        choiceTitle.foreground = UiKit.warn
        choiceNote.foreground = UiKit.subtle
        choiceNote.font = choiceNote.font.deriveFont(choiceNote.font.size - 0.5f)

        choicePanel = object : JBPanel<JBPanel<*>>(BorderLayout(0, 6)) {
            init {
                isOpaque = true
                background = UiKit.brandSoft
                border = JBUI.Borders.compound(
                    UiKit.cardBorder(UiKit.warn, UiKit.radius),
                    JBUI.Borders.empty(9, 11)
                )
            }
        }

        val textStack = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        choiceTitle.alignmentX = Component.LEFT_ALIGNMENT
        choiceNote.alignmentX = Component.LEFT_ALIGNMENT
        // 间距条也是组件：Box.Filler 默认 alignmentX = 0.5，
        // 混进纵向 BoxLayout 同样会把兄弟推右，所以一并压平
        val gap = Box.createVerticalStrut(2)
        (gap as JComponent).alignmentX = Component.LEFT_ALIGNMENT
        textStack.add(choiceTitle)
        textStack.add(gap)
        textStack.add(choiceNote)
        choicePanel.add(textStack, BorderLayout.CENTER)
        choicePanel.add(choiceButtons, BorderLayout.SOUTH)
        return choicePanel
    }

    private fun buildTaskPanel() {
        taskPanel.add(
            JBLabel("任务清单").apply {
                font = font.deriveFont(Font.BOLD, font.size - 0.5f)
                foreground = UiKit.sectionLabel
            },
            BorderLayout.NORTH
        )
        taskBody.layout = BoxLayout(taskBody, BoxLayout.Y_AXIS)
        taskBody.isOpaque = false
        taskPanel.add(taskBody, BorderLayout.CENTER)
    }

    private fun buildArtifactsPanel() {
        artifactsPanel.add(
            JBLabel("本次会话的产物").apply {
                font = font.deriveFont(Font.BOLD, font.size - 0.5f)
                foreground = UiKit.sectionLabel
            },
            BorderLayout.NORTH
        )
        artifactsBody.layout = BoxLayout(artifactsBody, BoxLayout.Y_AXIS)
        artifactsBody.isOpaque = false
        artifactsPanel.add(
            JBScrollPane(artifactsBody).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                isOpaque = false
                viewport.isOpaque = false
                preferredSize = Dimension(0, 110)
            },
            BorderLayout.CENTER
        )
    }

    /**
     * 弹出一条选择题并**阻塞**等待用户点击。
     *
     * 阻塞是刻意的：调用方是正在执行工具的后台线程，它必须拿到答案才能继续。
     * 5 分钟超时按「没选」处理 —— 不能把后台线程永远挂住，用户走开了也得能收尾。
     *
     * 返回值三态（[ChoiceResult]）：「选了第几项」/「明确取消」/「没等到」。
     * **取消和超时必须分开**：取消要停下整个回合，超时才可以按假设继续。
     */
    private fun askChoice(title: String, note: String, options: List<String>): ChoiceResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        // 登记一下：工具窗口被关掉时要把这些 latch 放掉（见 pendingChoices）
        pendingChoices.add(latch)
        // -1 还没点；-2 点了取消；>=0 选中的序号
        val picked = java.util.concurrent.atomic.AtomicInteger(-1)

        edt {
            choiceTitle.text = title
            // 纯文本直接给文本区：它自己按容器宽度折行，不需要（也不能用）HTML ——
            // 文本区不解析 HTML，塞进去会把标签原样显示出来
            choiceNote.text = note
            choiceButtons.removeAll()
            options.forEachIndexed { index, label ->
                // 第一个是主选项（允许/推荐），用淡品牌底 + 加粗区分
                choiceButtons.add(
                    UiKit.optionRow(label, primary = index == 0) {
                        picked.set(index)
                        latch.countDown()
                    }
                )
            }
            // 「取消」必须永远在。以前只有选项，不想选的话只能干等 5 分钟超时
            // —— 用户反馈「居然没有取消的选项」。
            // 置 -2（不是 -1）：-1 是「没人点过」，两者语义相反，必须能区分。
            choiceButtons.add(
                UiKit.optionRow("取消（先不做，等你再想清楚）", primary = false) {
                    picked.set(CANCELLED)
                    latch.countDown()
                }
            )
            inputStackLayout.show(inputStack, CARD_CHOICE)
            choicePanel.revalidate()
            choicePanel.repaint()
            // 选项行是**折行文本**，高度依赖宽度。第一次排的时候宽度还没定下来，
            // choiceButtons 的首选高度会偏小（选项被裁掉一截）。补一轮，等宽度定下来重算。
            SwingUtilities.invokeLater {
                choiceButtons.revalidate()
                choicePanel.revalidate()
                choicePanel.repaint()
            }
        }

        val answered = runCatching {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        }.getOrDefault(false)
        pendingChoices.remove(latch)

        edt {
            inputStackLayout.show(inputStack, CARD_INPUT)
            inputArea.requestFocusInWindow()
        }
        return when {
            !answered -> ChoiceResult.NoAnswer
            picked.get() == CANCELLED -> ChoiceResult.Cancelled
            else -> ChoiceResult.Picked(picked.get())
        }
    }

    /**
     * 工具 ↔ 界面 的接线。
     *
     * 用内部类而不是匿名对象：它要访问一堆私有字段与方法。
     * 全部回调都可能从后台线程进来，所以除了 [askChoice]（它自己负责切线程）
     * 之外，其余一律先 `edt {}` 再动界面。
     */
    private inner class UiBridgeHandler : AgentUiBridge.Handler {

        override fun requestApproval(request: ApprovalRequest): ApprovalDecision {
            return when (val r = askChoice(
                "需要你确认：${request.action}",
                "${request.target}\n${request.reason}",
                listOf("允许一次", "始终允许（改为全盘沙盒）", "拒绝")
            )) {
                is ChoiceResult.Picked -> when (r.index) {
                    0 -> ApprovalDecision.ALLOW_ONCE
                    1 -> {
                        Sandbox.setMode(Sandbox.Mode.FULL)
                        edt {
                            refreshSandboxPill()
                            updateStatus("已切到「全盘沙盒」，之后不再逐次询问")
                        }
                        ApprovalDecision.ALLOW_ALWAYS
                    }

                    else -> ApprovalDecision.DENY
                }
                // 取消 / 超时一律拒绝：没人明确放行就不放行（安全默认）
                ChoiceResult.Cancelled, ChoiceResult.NoAnswer -> ApprovalDecision.DENY
            }
        }

        override fun updateTasks(tasks: List<TaskItem>) = edt { renderTasks(tasks) }

        override fun askUser(question: String, options: List<String>): AskResult {
            return when (val r = askChoice("AI 想请你确认", question, options)) {
                is ChoiceResult.Picked -> AskResult.Picked(options.getOrNull(r.index).orEmpty())
                ChoiceResult.Cancelled -> {
                    // 用户明确说了不做 —— 光告诉模型「别做了」不够保险，
                    // 直接把这一轮**停掉**：cancelFlag 一置，agent 循环下一步就会收尾，
                    // 绝不会再执行后面的工具（用户反馈「点了取消还执行了」）。
                    cancelFlag.set(true)
                    edt { updateStatus("已取消：本轮不再继续执行") }
                    AskResult.Cancelled
                }

                ChoiceResult.NoAnswer -> AskResult.NoAnswer
            }
        }

        override fun recordArtifact(path: String) = edt { addArtifact(path) }
    }

    /** 渲染任务清单。只做展示，不参与流程控制 */
    /** 最近一次收到的任务清单，用于回合结束时「收摊」（见 [settleTasksOnTurnEnd]） */
    private var lastTasks: List<TaskItem> = emptyList()

    /**
     * 本轮结束时把还标着「进行中」的任务改成「已中断」。
     *
     * 不做这一步的话，界面会一直挂着一个「▸ 进行中」的任务，而实际上**什么都没在跑**——
     * 用户看到「回答已完成 + 任务还在转」会觉得自相矛盾（用户反馈「任务清单还在，
     * 结果显示完成了这就有点怪了」）。
     *
     * 改成「已中断」而不是直接抹掉：这些任务确实没做完，
     * 下一轮模型继续时会把它们改回进行中，信息不丢。
     */
    private fun settleTasksOnTurnEnd() {
        if (lastTasks.none { it.isActive }) return
        renderTasks(lastTasks.map { if (it.isActive) it.copy(status = "interrupted") else it })
    }

    private fun renderTasks(tasks: List<TaskItem>) {
        if (!ZhixueyaoSettings.getInstance().showTaskList) {
            taskPanel.isVisible = false
            return
        }
        lastTasks = tasks
        taskBody.removeAll()
        val done = tasks.count { it.isDone }
        val interrupted = tasks.count { it.status == "interrupted" }
        (taskPanel.getComponent(0) as? JBLabel)?.text = buildString {
            append("任务清单 · 已完成 ").append(done).append("/").append(tasks.size)
            if (interrupted > 0) append("（本轮结束，还有 ").append(interrupted).append(" 项没做完）")
        }

        for (task in tasks) {
            val row = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val interrupted = task.status == "interrupted"
            val mark = when {
                task.isDone -> "✓"
                task.isActive -> "▸"
                interrupted -> "‖"
                else -> "○"
            }
            row.add(
                JBLabel(mark).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = when {
                        task.isDone -> UiKit.ok
                        task.isActive -> UiKit.brand
                        interrupted -> UiKit.warn
                        else -> UiKit.faint
                    }
                },
                BorderLayout.WEST
            )
            row.add(
                JBLabel(task.title).apply {
                    // 完成的用灰字：一眼能分清「做完的」和「还没做的」
                    foreground = if (task.isDone) UiKit.faint else UiKit.text
                },
                BorderLayout.CENTER
            )
            taskBody.add(row)
        }
        taskPanel.isVisible = tasks.isNotEmpty()
        taskPanel.revalidate()
        taskPanel.repaint()
    }

    // ---- 产物 ----

    private fun toggleArtifacts() {
        artifactsVisible = !artifactsVisible
        if (artifactsVisible) renderArtifacts()
        artifactsPanel.isVisible = artifactsVisible
        artifactsPanel.revalidate()
        artifactsPanel.repaint()
        updateArtifactsButton()
        if (artifactsVisible && artifacts.isEmpty()) updateStatus("本次会话还没有产出文件")
    }

    /** 记录一个产物：去重 → 落盘 → 刷新（面板开着时才重画列表） */
    private fun addArtifact(path: String) {
        if (path.isBlank()) return
        if (!artifacts.contains(path)) artifacts.add(path)
        persistArtifacts()
        if (artifactsVisible) renderArtifacts()
        updateArtifactsButton()
    }

    private fun renderArtifacts() {
        artifactsBody.removeAll()
        if (artifacts.isEmpty()) {
            artifactsBody.add(
                JBLabel("本次会话还没有产出文件").apply {
                    foreground = UiKit.faint
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
        }
        for (path in artifacts) {
            val row = object : JBPanel<JBPanel<*>>(BorderLayout()) {
                init {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "$path\n单击在编辑器中打开"
                    add(
                        JBLabel(shortPath(path)).apply {
                            foreground = UiKit.link
                            font = font.deriveFont(font.size - 0.5f)
                        },
                        BorderLayout.CENTER
                    )
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) = openArtifact(path)
                    })
                }
            }
            artifactsBody.add(row)
        }
        artifactsBody.revalidate()
        artifactsBody.repaint()
    }

    private fun updateArtifactsButton() {
        artifactsButton.toolTipText = when {
            artifacts.isEmpty() -> "本次会话还没有产出文件"
            artifactsVisible -> "本次会话产出 ${artifacts.size} 个文件（点击收起）"
            else -> "本次会话产出 ${artifacts.size} 个文件（点击展开）"
        }
    }

    /** 相对项目根目录的短路径 —— 面板里显示全路径会立刻被挤成一串省略号 */
    private fun shortPath(path: String): String {
        val base = project.basePath
        return if (base != null && path.startsWith(base)) {
            path.removePrefix(base).trimStart('\\', '/')
        } else {
            path
        }
    }

    /**
     * 本会话里所有能预览的文件 —— 预览窗口左右翻页的范围。
     *
     * 两个来源合起来才全：
     *  - `artifacts`：工具主动登记过的产物（可能在任何位置）
     *  - 会话临时目录：有些产物没走登记（比如模型自己用 write_file 写的草稿）
     *
     * 用户要的效果是「点开一张，能左右翻着看这次生成的所有图」。
     */
    private fun previewGallery(): List<java.io.File> {
        val out = LinkedHashSet<java.io.File>()
        artifacts.map { java.io.File(it) }
            .filter { it.isFile && ImagePreviewDialog.canPreview(it) }
            .forEach { out.add(it) }
        runCatching {
            sessionWorkspace()?.walkTopDown()
                ?.filter { it.isFile && ImagePreviewDialog.canPreview(it) }
                ?.forEach { out.add(it) }
        }
        return out.toList().sortedBy { it.name.lowercase() }
    }

    /** 打开预览（带上本会话的图库，可左右翻页） */
    private fun openPreviewWithGallery(file: java.io.File) {
        // 视频不进预览窗口（Swing 里放播放器要拖整套解码器，不划算），
        // 交给系统默认播放器 —— 用户想看的就是「一眼效果」。见 ImagePreviewDialog.VIDEO_EXTS
        if (ImagePreviewDialog.isVideo(file)) {
            val err = ImagePreviewDialog.openExternally(file)
            updateStatus(err ?: "已用系统默认播放器打开 ${file.name}")
            return
        }
        val err = ImagePreviewDialog.show(project, file, previewGallery())
        if (err != null) updateStatus(err)
    }

    private fun openArtifact(path: String) {
        // 能预览的格式（含 svg）先开预览窗口 —— 对 svg 来说「在编辑器里打开」
        // 就是打开一堆 XML 原文，等于没有预览（用户反馈过两次）。
        val file = java.io.File(path)
        if (ImagePreviewDialog.isMedia(file)) {
            openPreviewWithGallery(file)
            return
        }
        runCatching {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(path.replace('\\', '/'))
            if (vf != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            } else {
                updateStatus("文件已不在：$path")
            }
        }.onFailure { log.warn("打开产物失败：$path", it) }
    }

    private fun persistArtifacts() {
        val s = ZhixueyaoSettings.getInstance()
        val prefix = "$currentSessionId|"
        s.artifactEntries.removeAll { it.startsWith(prefix) }
        artifacts.forEach { s.artifactEntries.add(prefix + it) }
    }

    /** 切会话时载入该会话的产物（产物按会话隔离，不会串到别的对话里） */
    private fun loadArtifacts() {
        val prefix = "$currentSessionId|"
        artifacts.clear()
        artifacts.addAll(
            ZhixueyaoSettings.getInstance().artifactEntries
                .filter { it.startsWith(prefix) }
                .map { it.substring(prefix.length) }
        )
        if (artifactsVisible) renderArtifacts()
        updateArtifactsButton()
    }

    // ---- 沙盒 ----

    private fun showSandboxMenu() {
        val current = Sandbox.mode()
        val menu = JPopupMenu()
        menu.add(sectionHeader("沙盒权限"))
        for (mode in Sandbox.Mode.entries) {
            menu.add(javax.swing.JMenuItem(mode.label).apply {
                font = font.deriveFont(font.size - 1f)
                if (mode == current) icon = UiKit.check
                toolTipText = mode.description
                addActionListener { pickSandboxMode(mode) }
            })
        }
        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("权限说明…").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.help
            addActionListener { showSandboxNotice(force = true) }
        })
        menu.show(sandboxPill, 0, sandboxPill.height + 2)
    }

    private fun pickSandboxMode(mode: Sandbox.Mode) {
        Sandbox.setMode(mode)
        refreshSandboxPill()
        // 切到「仅当前项目」时给一次说明：这个档位的边界在哪、越界会怎样，
        // 不解释清楚用户会以为是插件坏了（模型报「沙盒拦截」却不知道为什么）
        if (mode == Sandbox.Mode.PROJECT && !ZhixueyaoSettings.getInstance().sandboxNoticeAccepted) {
            showSandboxNotice(force = false)
        }
        updateStatus("沙盒权限已切到「${mode.label}」")
    }

    private fun showSandboxNotice(force: Boolean) {
        val base = project.basePath ?: "（项目目录尚未就绪）"
        val (confirmed, checked) = NoticeDialog.show(
            title = "止血药 · 沙盒权限说明",
            message = buildString {
                append("「仅当前项目」= AI 只能读写下面这个目录里的文件：\n\n$base\n\n")
                append("访问项目外的路径（其他盘符、桌面、用户主目录等）会被直接拦下，")
                append("并在对话里说明「被沙盒拦了、可以怎么改权限」。\n\n")
                append("需要临时放宽时，随时在这里切成「全盘沙盒」（不拦）或「每次询问」（逐次授权）。")
            },
            checkboxText = "下次不再提醒",
            okText = "知道了"
        )
        // force=true 是用户主动点「权限说明」进来的，此时不该把「不再提醒」写死
        if (!force && confirmed && checked) {
            ZhixueyaoSettings.getInstance().sandboxNoticeAccepted = true
        }
    }

    private fun refreshSandboxPill() {
        val mode = Sandbox.mode()
        sandboxPill.text = "沙盒·${mode.shortLabel}"
        sandboxPill.foreground = if (mode == Sandbox.Mode.FULL) UiKit.warn else UiKit.text
        sandboxPill.toolTipText = "${mode.label} —— ${mode.description}\n点击切换"
        sandboxPill.revalidate()
        sandboxPill.repaint()
    }

    private fun installShortcuts() {
        // Enter 发送，Shift+Enter 换行
        inputArea.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = onSend()
            }
        )
        inputArea.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    inputArea.insert("\n", inputArea.caretPosition)
                }
            }
        )
        // Alt+Enter：生成中时改成「排队等本轮答完」，而不是插进当前这轮
        inputArea.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK),
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = onSend(queueInstead = true)
            }
        )
        // 粘贴：剪贴板里是图片就当附件，否则走普通文本粘贴。
        // 三个绑定都要（Ctrl+V / Cmd+V / Shift+Insert），顺手对齐各家习惯。
        val pasteAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                when (val r = pasteImageOrNull()) {
                    PasteResult.ImageAdded -> {
                        // 明确告诉用户「收下了」—— 以前静默处理，看起来像没粘进去
                        updateStatus("已把剪贴板图片加为附件（${attachments.size} 个）")
                    }

                    PasteResult.NoImage -> inputArea.paste()

                    is PasteResult.Failed -> {
                        // **不能静默**：用户按了 Ctrl+V 却什么都没发生，只会反复按
                        updateStatus("粘贴图片失败：${r.reason}")
                        // 还是试一下文本粘贴 —— 万一剪贴板里同时有文本
                        runCatching { inputArea.paste() }
                    }
                }
            }
        }
        for (stroke in listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK)
        )) {
            inputArea.inputMap.put(stroke, pasteAction)
        }
    }

    private fun installDropTarget() {
        dropTarget = java.awt.dnd.DropTarget().apply {
            addDropTargetListener(object : java.awt.dnd.DropTargetAdapter() {
                override fun drop(evt: java.awt.dnd.DropTargetDropEvent) {
                    try {
                        evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = evt.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                            as? List<File> ?: emptyList()
                        for (f in files) addAttachment(f.toPath())
                        evt.dropComplete(true)
                    } catch (ex: Exception) {
                        log.warn("拖拽处理失败", ex)
                        evt.dropComplete(false)
                    }
                }
            })
        }
    }

    // ---------------- 状态刷新与模型切换 ----------------

    /**
     * 刷新模型胶囊、模式胶囊、状态点、MCP 徽标。
     *
     * 顶栏极简后，状态文字改由状态点的 tooltip 承载 —— 鼠标悬停看详情，
     * 平时只占一个小圆点，任何宽度都不会挤压。
     */
    private fun refreshHeader() {
        val s = ZhixueyaoSettings.getInstance()
        val configured = s.baseUrl.isNotBlank() && s.model.isNotBlank()

        modelPill.text = if (configured) s.model else "未配置模型"
        modelPill.foreground = if (configured) UiKit.text else UiKit.warn
        refreshThinkingPill()

        // 自定义服务商显示用户起的名字，否则看不出用的是哪家中转站
        val providerLabel = Providers.displayNameOf(s.providerId)
        val thinkingNote = com.zhixueyao.llm.ThinkingLevel.byId(s.thinkingLevel)
            .takeIf { it != com.zhixueyao.llm.ThinkingLevel.DEFAULT }
            ?.let { "\n思考强度：${it.label}" } ?: ""
        // 把这家已添加的模型数报出来，用户才知道菜单里能展开出几个
        val listCount = s.modelListOf(s.providerId).size
        val listNote = if (listCount > 1) "\n已添加 $listCount 个可选模型" else ""

        modelPill.toolTipText = if (configured) {
            "$providerLabel · ${s.model}$thinkingNote$listNote\n点击切换服务商或模型"
        } else {
            "尚未配置模型接口，点击选择服务商"
        }

        // 连接状态：配好了绿点，没配好橙点。文字信息全部进 tooltip。
        connDot.color = if (configured) UiKit.ok else UiKit.warn
        connDot.toolTipText = if (configured) {
            "已配置：$providerLabel · ${s.model}$thinkingNote"
        } else {
            "尚未配置模型，点击右上角齿轮"
        }

        // 当前运行模式
        val preset = AgentPresets.byId(s.agentPreset)
        presetPill.text = preset.label
        presetPill.toolTipText = "${preset.label}模式\n${preset.summary}\n${AgentPresets.toolSummary(preset.id)}"
        presetPill.foreground = if (AgentPresets.canWrite(preset.id)) UiKit.text else UiKit.warn

        val mcpCount = if (s.enableMcp) {
            runCatching { mcpManager.allTools().size }.getOrDefault(0)
        } else 0
        mcpBadge.text = if (mcpCount > 0) "MCP $mcpCount" else "MCP"
        mcpBadge.foreground = if (mcpCount > 0) UiKit.ok else UiKit.faint
        mcpBadge.toolTipText = if (mcpCount > 0) {
            "已连接 MCP，共 $mcpCount 个工具\n点击重新连接"
        } else {
            "尚未连接 MCP 工具\n点击配置或重新连接"
        }
    }

    /** Agent 预设切换菜单。只读模式会标出来，避免用户以为能改文件。 */
    /**
     * 思考强度菜单。
     *
     * 列表 = 「默认」+ 设置里勾选过的档位（见 [ZhixueyaoSettings.thinkingLevels]）。
     * 一个档位都没勾时只有「默认」—— 此时不发任何思考参数，
     * 由服务商与模型自己决定（这是最安全的行为，见 ThinkingParams）。
     */
    private fun showThinkingMenu() {
        val s = ZhixueyaoSettings.getInstance()
        val menu = JPopupMenu()

        menu.add(sectionHeader("思考强度"))
        val current = ThinkingLevel.byId(s.thinkingLevel)
        for (level in thinkingChoices()) {
            val isCurrent = level == current
            menu.add(javax.swing.JMenuItem(level.label).apply {
                font = font.deriveFont(font.size - 1f)
                if (isCurrent) icon = UiKit.check
                toolTipText = level.description
                addActionListener {
                    s.thinkingLevel = level.name
                    refreshHeader()
                    updateStatus(
                        if (level == ThinkingLevel.DEFAULT) "思考强度已改为「跟随模型默认」，下一条消息生效"
                        else "思考强度已改为「${level.label}」，下一条消息生效"
                    )
                }
            })
        }

        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("勾选要显示的档位…").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.gear
            addActionListener { openSettings(section = "model") }
        })

        // 下拉挂在胶囊右下方，和另外两个胶囊菜单的位置一致
        menu.show(thinkingPill, 0, thinkingPill.height + 2)
    }

    /** 菜单里要显示的档位：「默认」永远在，其余按设置里勾选的顺序 */
    private fun thinkingChoices(): List<ThinkingLevel> {
        val checked = ZhixueyaoSettings.getInstance().thinkingLevels
        val out = mutableListOf(ThinkingLevel.DEFAULT)
        for (level in ThinkingLevel.entries) {
            if (level != ThinkingLevel.DEFAULT && checked.contains(level.name)) out.add(level)
        }
        return out
    }

    /** 刷新思考胶囊的文案与提示 */
    private fun refreshThinkingPill() {
        val level = ThinkingLevel.byId(ZhixueyaoSettings.getInstance().thinkingLevel)
        // 用 shortLabel：「跟随模型默认」这种全称会让胶囊宽到 200px+，
        // 在顶栏会把别的按钮挤走（已因此把它挪到输入区）
        thinkingPill.text = "思考·${level.shortLabel}"
        val checked = ZhixueyaoSettings.getInstance().thinkingLevels.size
        val note = if (checked == 0) "尚未在设置里勾选档位，菜单里只有「默认」" else "设置里已勾选 $checked 档"
        thinkingPill.toolTipText = "当前：${level.label} —— ${level.description}\n$note\n点击切换"
        thinkingPill.revalidate()
        thinkingPill.repaint()
    }

    private fun showPresetMenu() {
        val s = ZhixueyaoSettings.getInstance()
        val menu = JPopupMenu()

        menu.add(sectionHeader("运行模式"))
        for (preset in AgentPresets.all) {
            val current = preset.id == s.agentPreset
            val label = buildString {
                append(preset.label)
                if (!AgentPresets.canWrite(preset.id)) append("   （只读）")
            }
            menu.add(javax.swing.JMenuItem(label).apply {
                font = font.deriveFont(font.size - 1f)
                if (current) icon = UiKit.check else isEnabled = true
                toolTipText = "${preset.summary}\n${AgentPresets.toolSummary(preset.id)}"
                addActionListener {
                    s.agentPreset = preset.id
                    refreshHeader()
                    updateStatus()
                }
            })
        }

        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("操作日志…").apply {
            font = font.deriveFont(font.size - 1f)
            addActionListener { showAgentLog() }
        })
        menu.add(javax.swing.JMenuItem("模拟流式输出（调试）").apply {
            font = font.deriveFont(font.size - 1f)
            toolTipText = "不调用模型，按脚本跑一遍「思考 → 调工具 → 回答」，用来验证界面"
            addActionListener { runSimulation() }
        })
        menu.add(javax.swing.JMenuItem("更多设置…").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.gear
            addActionListener { openSettings(section = "presets") }
        })

        menu.show(presetPill, 0, presetPill.height + 2)
    }

    /**
     * 模型切换菜单。
     *
     * 列出所有可选的服务商 —— 预设的在前、用户自建的中转站排后面。
     * 每一项都带「服务商名 · 模型名」，因为中转站可以有好几家，
     * 光看「自定义」两个字分不清用的是哪家、跑的哪个模型。
     */
    private fun showModelMenu() {
        val s = ZhixueyaoSettings.getInstance()
        val menu = JPopupMenu()

        /** 一个可选服务商：id + 显示名 + 该家已添加的模型 + 备注 */
        data class Entry(
            val id: String,
            val label: String,
            val models: List<String>,
            val note: String
        )

        val all = buildList {
            for (p in Providers.presets) {
                // 预设的兜底模型也视为「已添加」，否则全新安装的菜单里一家都展不开
                val ms = s.modelListOf(p.id).ifEmpty { listOf(p.defaultModel) }
                add(Entry(p.id, p.label, ms, p.note))
            }
            for (c in s.customProviders) {
                val name = c.name.ifBlank { "未命名服务商" }
                val ms = s.modelListOf(c.id).ifEmpty {
                    listOfNotNull(c.model.takeIf { it.isNotBlank() })
                }
                add(Entry(c.id, "★ $name", ms, "自建服务商 · ${c.baseUrl}"))
            }
        }

        // 已配好密钥的（Ollama 不需要密钥）排前面
        val ready = all.filter { s.keyOf(it.id).isNotBlank() || it.id == "ollama" }
        val rest = all.filter { it !in ready }

        /** 切到「某家的某个模型」 */
        fun activate(e: Entry, m: String) {
            s.switchProvider(e.id)
            s.rememberModel(e.id, m)
            refreshHeader()
            refreshSetupCard()
            updateStatus()
            if (s.keyOf(e.id).isBlank() && e.id != "ollama") {
                Messages.showInfoMessage(
                    project,
                    "已切换到 $m（${e.label}）。\n\n该服务商还没有填写密钥，请点击右上角齿轮填写。",
                    "止血药"
                )
            }
        }

        /**
         * 一个服务商 = 一个子菜单。
         *
         * 为什么用子菜单而不是平铺：一家服务商下面常挂十几个模型，
         * 平铺出来整屏都是模型名，反而看不出「有哪几家」。
         * 子菜单把「选哪家」和「选哪个模型」分成两步，也是主流 Agent 应用的做法。
         */
        fun addProvider(e: Entry) {
            val isCurrentProvider = e.id == s.providerId
            val currentModel = if (isCurrentProvider) s.model else ""

            val sub = javax.swing.JMenu(e.label).apply {
                font = font.deriveFont(font.size - 1f)
                toolTipText = if (e.note.isBlank()) {
                    "已添加 ${e.models.size} 个模型"
                } else {
                    "${e.note}\n已添加 ${e.models.size} 个模型"
                }
                // 当前服务的这家给个品牌色，一眼看出现在用的是谁
                if (isCurrentProvider) foreground = UiKit.brand
            }

            if (e.models.isEmpty()) {
                sub.add(javax.swing.JMenuItem("（还没有添加模型）").apply {
                    font = font.deriveFont(font.size - 1f)
                    isEnabled = false
                })
            } else {
                for (m in e.models) {
                    val chosen = isCurrentProvider && m == currentModel
                    sub.add(javax.swing.JMenuItem(m).apply {
                        font = font.deriveFont(font.size - 1f)
                        if (chosen) {
                            icon = UiKit.check
                            isEnabled = false
                        }
                        toolTipText = if (chosen) {
                            "正在使用"
                        } else {
                            "切换到 ${e.label} 的 $m"
                        }
                        addActionListener { activate(e, m) }
                    })
                }
            }

            sub.addSeparator()
            sub.add(javax.swing.JMenuItem("继续添加模型…").apply {
                font = font.deriveFont(font.size - 1f)
                icon = UiKit.gear
                toolTipText = "打开设置页，为 ${e.label} 拉取或手动添加更多模型"
                addActionListener { openSettings(section = "model") }
            })

            menu.add(sub)
        }

        if (ready.isNotEmpty()) {
            menu.add(sectionHeader("已配置"))
            ready.forEach { addProvider(it) }
        }
        if (rest.isNotEmpty()) {
            menu.add(sectionHeader("其他服务商"))
            rest.forEach { addProvider(it) }
        }

        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("管理服务商与密钥…").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.gear
            addActionListener { openSettings() }
        })
        menu.add(javax.swing.JMenuItem("MCP 服务器设置…").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.gear
            addActionListener { openSettings(section = "plugins") }
        })

        // 模型项可能非常多（中转站几十个），一律走可滚动浮层；
        // 再兜一个「全部模型…」入口 —— 设置页的模型清单也能点选，那条路永远走得通
        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("全部模型…（在设置里）").apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.gear
            addActionListener { openSettings(section = "model") }
        })
        UiKit.showScrollableMenu(menu, modelPill)
    }

    private fun sectionHeader(text: String): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UiKit.header
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(UiKit.border),
                JBUI.Borders.empty(4, 10, 4, 8)
            )
            add(JBLabel(text).apply {
                font = font.deriveFont(Font.BOLD, font.size - 1.5f)
                foreground = UiKit.sectionLabel
            }, BorderLayout.WEST)
        }

    /** 打开止血药设置。用独立对话框弹出，比让用户翻设置树友好得多。 */
    fun openSettings(section: String? = null) {
        try {
            val configurable = ZhixueyaoConfigurable()
            if (section != null) configurable.selectSection(section)
            ShowSettingsUtil.getInstance().editConfigurable(project, configurable)
        } catch (e: Throwable) {
            // 设置页构造失败时不要往用户脸上甩 IDE 内部错误框（带堆栈的那种），
            // 那会让人以为插件崩了。兜底：记日志 + 一句人话，并把异常摘要给出来。
            log.error("打开设置页失败", e)
            Messages.showErrorDialog(
                project,
                "设置页没能打开：${e.message ?: e.javaClass.simpleName}\n\n" +
                    "详细堆栈已写入 idea.log（Help → Show Log in Explorer）。",
                "止血药"
            )
            return
        }
        // 设置可能改了模型或预设，回到聊天窗口后刷新顶栏
        refreshHeader()
        // 顺带重新求值未配置引导卡：刚配好模型的话，它应该立刻消失
        refreshSetupCard()
        updateStatus()
    }

    /**
     * 操作日志（给用户看的「台账」）。
     *
     * 插件是「无确认、直接改」的模式，出了事必须能回看当时做了什么 ——
     * 这个面板就是那条退路。只放内存、重启清空，需要长期记录看 IDE 自己的日志。
     */
    private fun showAgentLog() {
        val entries = com.zhixueyao.agent.AgentLog.snapshot()
        val dialog = javax.swing.JDialog(
            com.intellij.openapi.wm.WindowManager.getInstance().getFrame(null) as? java.awt.Frame,
            "操作日志 · ${entries.size} 条（最近的在最下面）",
            true
        )
        val area = JBTextArea(
            if (entries.isEmpty()) "还没有操作记录。"
            else com.zhixueyao.agent.AgentLog.asText()
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size - 1)
            border = JBUI.Borders.empty(8)
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(780), JBUI.scale(460))
            border = JBUI.Borders.empty()
        }
        val bottom = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(
                javax.swing.JButton("清空").apply {
                    addActionListener {
                        com.zhixueyao.agent.AgentLog.clear()
                        area.text = "还没有操作记录。"
                        updateStatus("操作日志已清空")
                    }
                }
            )
            add(
                javax.swing.JButton("复制全部").apply {
                    addActionListener {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(com.zhixueyao.agent.AgentLog.asText()), null
                        )
                        updateStatus("操作日志已复制到剪贴板")
                    }
                }
            )
        }
        dialog.contentPane = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(scroll, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        dialog.defaultCloseOperation = javax.swing.JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        // 打开就停在最新一条上
        area.caretPosition = area.document.length
        dialog.isVisible = true
    }

    // ---------------- 附件处理 ----------------

    private fun chooseFiles() {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            dialogTitle = "选择要发送给 AI 的文件"
            fileFilter = FileNameExtensionFilter(
                "代码、文本与图片",
                "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt",
                "toml", "yml", "yaml", "pro", "c", "cpp", "h", "js", "ts", "py", "sql",
                "log", "png", "jpg", "jpeg", "webp", "gif", "bmp"
            )
        }
        project.basePath?.let { chooser.currentDirectory = File(it) }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.forEach { addAttachment(it.toPath()) }
        }
    }

    private fun addAttachment(path: Path) {
        // **每一条提前返回都要说话**。
        // 以前 `if (!Files.exists(path)) return` 是静默的 ——
        // 用户粘了个不存在的路径、或者拖进来的东西解析成空路径，界面上什么都没发生，
        // 只能反复重试（这和剪贴板粘贴那次是同一类问题：静默失败最坑）。
        if (attachments.size >= 8) {
            Messages.showInfoMessage(project, "单次最多附加 8 个文件", "止血药")
            return
        }
        if (!Files.exists(path)) {
            updateStatus("加不了附件：文件不在了（${path.fileName}）")
            return
        }
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > 20L * 1024 * 1024) {
            Messages.showWarningDialog(project, "文件过大（超过 20 MB）：${path.fileName}", "止血药")
            return
        }
        val processed = runCatching { AttachmentSupport.process(path) }.getOrElse {
            updateStatus("加不了附件：${it.message ?: it.javaClass.simpleName}")
            return
        }
        attachments.add(PendingAttachment(path, processed, path.fileName.toString()))
        refreshAttachmentBar()
    }

    private fun addClipboardAttachment(image: java.awt.Image) {
        if (attachments.size >= 8) return
        val processed = AttachmentSupport.fromClipboardImage(image) ?: return
        attachments.add(PendingAttachment(null, processed, "剪贴板图片"))
        refreshAttachmentBar()
    }

    /** 一次粘贴尝试的结果。**必须能区分「没有图」和「有图但读不出来」** —— 见 [pasteImageOrNull] */
    private sealed interface PasteResult {
        /** 剪贴板里确实有图片，而且已经加成附件了 */
        data object ImageAdded : PasteResult
        /** 剪贴板里没有图片 → 调用方走普通文本粘贴 */
        data object NoImage : PasteResult
        /** 有图片但读失败（剪贴板被别的程序占着等）→ 要**告诉用户**，不能静默 */
        data class Failed(val reason: String) : PasteResult
    }

    /**
     * 试着把剪贴板里的图片变成附件。
     *
     * 之前的写法有三个问题，导致「截图粘贴不进去」且**没有任何提示**：
     *
     * 1. 只信 `isDataFlavorAvailable(imageFlavor)` —— Windows 上截图工具给的
     *    往往是 DIB 或 `image/png`，这个方法可能返回 false，但它其实**能读**。
     *    所以现在**遍历全部 flavor**，看有没有任何一个是图像。
     * 2. 剪贴板被别的程序占着时 `getData` 会抛异常（Windows 上很常见，尤其刚截完图）。
     *    之前被 `runCatching` 一口吞掉 → 走文本粘贴 → 剪贴板里没文本 → 什么都没发生。
     *    现在**重试几次**。
     * 3. 失败**不给任何反馈** —— 用户只能反复按 Ctrl+V。现在会在状态栏说明原因。
     *
     * 另外顺手支持「剪贴板里是图片**文件**」（在文件管理器里复制了张图）——
     * 那是 fileList flavor，和像素数据是两条路。
     */
    private fun pasteImageOrNull(): PasteResult {
        val clip = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
            ?: return PasteResult.Failed("读不到系统剪贴板")

        // 剪贴板被占用时重试：Windows 上「刚截图完立刻粘贴」经常撞上
        repeat(3) { attempt ->
            val result = runCatching {
                val flavors = clip.availableDataFlavors

                // ① 优先当「图片文件」处理：在资源管理器里复制的图走这条
                val fileFlavor = flavors.firstOrNull {
                    it == java.awt.datatransfer.DataFlavor.javaFileListFlavor
                }
                if (fileFlavor != null) {
                    @Suppress("UNCHECKED_CAST")
                    val files = clip.getData(fileFlavor) as? List<java.io.File>
                    val images = files?.filter { it.isFile && isImageFile(it) }.orEmpty()
                    if (images.isNotEmpty()) {
                        val before = attachments.size
                        images.forEach { addAttachment(it.toPath()) }
                        return if (attachments.size > before) PasteResult.ImageAdded
                        else PasteResult.Failed("附件已满（最多 8 个）")
                    }
                }

                // ② 像素数据：**遍历全部 flavor**，别只信 isDataFlavorAvailable
                val imageFlavor = flavors.firstOrNull { f ->
                    f == java.awt.datatransfer.DataFlavor.imageFlavor ||
                        (f.mimeType?.startsWith("image/") == true &&
                            java.awt.Image::class.java.isAssignableFrom(f.representationClass))
                }
                if (imageFlavor == null) return PasteResult.NoImage

                val image = clip.getData(imageFlavor) as? java.awt.Image ?: return PasteResult.NoImage
                val before = attachments.size
                addClipboardAttachment(image)
                if (attachments.size > before) PasteResult.ImageAdded
                else PasteResult.Failed("附件已满（最多 8 个），或这张图读不出来")
            }
            if (result.isSuccess) return result.getOrThrow()
            // 失败 → 等一下再试（剪贴板锁通常很快就释放）
            if (attempt < 2) runCatching { Thread.sleep(80) }
        }
        return PasteResult.Failed("系统剪贴板被占用，稍后再试一次")
    }

    private fun isImageFile(f: java.io.File): Boolean =
        f.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    /**
     * 附件条。
     *
     * 每条芯片：缩略图（图片才有）+ 文件名 + 大小时长 + 移除按钮。
     * 图片缩略图**可点开看大图** —— 粘贴截图时尤其需要，
     * 24px 的方块根本认不出是哪张。
     */
    /**
     * 附件条：**紧凑胶囊** + 悬停看大图。
     *
     * ## 为什么推倒重写
     *
     * 用户反馈（对着 WorkBuddy 的输入框比的）：
     * 「粘贴在输入框的图片会压缩成一个小图标+文字（一个椭圆形括起来的），
     * 鼠标移到这个图片上还能显示出图片，移除则不显示」；
     * 「上传的图片是不是占地太大了？这个框让我无法上传多个图片文件等」。
     *
     * 旧版有三个毛病：
     *  1. 芯片里塞了「缩略图 + 完整文件名 + 类型说明（图片 / N 字符）+ 关闭」四段，
     *     文件名一长就**一个芯片占满整行** —— 多张图根本排不下；
     *  2.  +  +  这个组合是**假圆角**：
     *     实底还是矩形，只有描边是圆的（本工程记过这条坑）；
     *  3. 想知道「这张图是什么」只能点开大图，鼠标划过看不到。
     *
     * 现在：**18px 缩略图 + 截断的文件名 + 关闭**，圆角自绘；
     * 类型说明挪进 tooltip，鼠标悬停直接浮出大图（见 [ImageHoverPreview]）。
     */
    private fun refreshAttachmentBar() {
        attachmentBar.removeAll()
        for (att in attachments.toList()) {
            attachmentBar.add(buildAttachmentCapsule(att))
        }
        attachmentBar.isVisible = attachments.isNotEmpty()
        attachmentBar.revalidate()
        attachmentBar.repaint()
        refreshSendState()
    }

    /**
     * 一个附件胶囊。
     *
     * 圆角底是**自绘**的：`isOpaque = false` + 在 `paintComponent` 里画圆角实底。
     * 只设 `background` + 圆角描边的话，实底仍是矩形，四角会露出直角（本工程的老坑）。
     */
    private fun buildAttachmentCapsule(att: PendingAttachment): JComponent {
        val preview = att.processed.preview
        val thumb: java.awt.Image? = when {
            preview != null -> AttachmentSupport.thumbnailOf(preview, THUMB_PX).image
            att.processed.isImage && att.path != null -> AttachmentSupport.thumbnail(att.path, THUMB_PX)?.image
            else -> null
        }
        val meta = attachmentMeta(att)
        val fullName = att.displayName
        val fullTip = buildString {
            append(att.path?.toString() ?: fullName)
            append('\n').append(meta)
            when {
                att.processed.isImage -> append("\n悬停看大图，单击打开")
                att.processed.rawText != null -> append("\n单击查看内容")
            }
        }

        val capsule = object : JBPanel<JBPanel<*>>(BorderLayout(5, 0)) {
            private var hovered = false

            init {
                isOpaque = false            // 必须：交给 paintComponent 画圆角实底
                toolTipText = fullTip
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(3, 7, 3, 3)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true; repaint()
                        // 有图才浮预览；文本附件悬停弹大图没意义。
                        // 锚点用 `e.component`（就是这个胶囊自己）—— 匿名对象里没有
                        // 可用的 `this@` 标签，而事件本来就带着它
                        thumb?.let { ImageHoverPreview.schedule(e.component, it, att) }
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false; repaint()
                        ImageHoverPreview.hide()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        ImageHoverPreview.hide()
                        when {
                            att.processed.isImage -> showImagePreview(att)
                            att.processed.rawText != null -> showTextPreview(att)
                        }
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    // 胶囊 = 高度一半的圆角
                    val r = height
                    g2.color = if (hovered) UiKit.hover else UiKit.card
                    g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
                    g2.color = UiKit.border
                    g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }

        // 左：缩略图（没有就退回文件类型图标）
        capsule.add(
            if (thumb != null) {
                JLabel(javax.swing.ImageIcon(thumb)).apply { preferredSize = Dimension(THUMB_PX, THUMB_PX) }
            } else {
                JLabel(if (att.processed.isImage) AllIcons.FileTypes.Image else AllIcons.FileTypes.Text)
            },
            BorderLayout.WEST
        )

        // 中：截断的文件名
        capsule.add(
            JBLabel(ellipsize(fullName, ATTACH_NAME_MAX)).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UiKit.text
            },
            BorderLayout.CENTER
        )

        // 右：移除
        capsule.add(
            UiKit.iconButton(AllIcons.General.InlineClose, "移除", pad = 1) {
                ImageHoverPreview.hide()
                attachments.remove(att)
                refreshAttachmentBar()
            }.apply {
                // 移除按钮自己也要参与尺寸计算，否则胶囊宽度算不准
                preferredSize = Dimension(16, 16)
            },
            BorderLayout.EAST
        )
        return capsule
    }

    /** 超长文件名截断：中文按「字」数，末尾加省略号（完整名在 tooltip 里） */
    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 附件芯片右侧的元信息：图片给尺寸，文本给字符数，其他给类型说明。 */
    private fun attachmentMeta(att: PendingAttachment): String {
        val p = att.processed
        if (p.isImage) {
            val w = p.preview?.width ?: 0
            val h = p.preview?.height ?: 0
            return if (w > 0) "图片" else "图片"
        }
        val chars = p.rawText?.length ?: 0
        return when {
            chars >= 10_000 -> "%.1f 万字符".format(chars / 10_000.0)
            chars > 0 -> "$chars 字符"
            else -> "文件"
        }
    }

    /**
     * 文本附件的内容预览，**默认折叠**。
     *
     * 折叠在这里的价值和消息区不同：这是「确认要发出去的内容」的场景，
     * 用户多半只想扫一眼开头，不需要几千行全展开占满屏幕。
     * 所以超过 80 行就先给前 40 行 + 一个展开按钮。
     */
    private fun showTextPreview(att: PendingAttachment) {
        val full = att.processed.rawText ?: return
        val lines = full.lines()
        val limit = CollapsePolicy.PREVIEW_DIALOG_LINE_LIMIT

        val dialog = javax.swing.JDialog(
            com.intellij.openapi.wm.WindowManager.getInstance().getFrame(null) as? java.awt.Frame,
            "内容 · ${att.displayName}",
            true
        )

        val area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = java.awt.Font(
                java.awt.Font.MONOSPACED,
                java.awt.Font.PLAIN,
                com.intellij.util.ui.UIUtil.getLabelFont().size
            )
            border = JBUI.Borders.empty(8, 10)
            text = if (lines.size > limit) {
                lines.take(limit).joinToString("\n")
            } else full
        }

        val scroll = JBScrollPane(area).apply {
            border = UiKit.cardBorder(UiKit.border, UiKit.radiusSmall)
            preferredSize = Dimension(680, 420)
            verticalScrollBar.unitIncrement = 16
        }

        val root = JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(12)
        }
        root.add(scroll, BorderLayout.CENTER)

        val bottom = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        bottom.add(
            JBLabel(
                "${lines.size} 行 · ${full.length} 字符" +
                    (if (att.processed.textBlock.contains("已截断")) "　⚠ 超出上限的部分已截断" else "")
            ).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UiKit.subtle
            },
            BorderLayout.WEST
        )

        val btns = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        if (lines.size > limit) {
            var expanded = false
            val toggle = UiKit.textButton("展开全文", "显示全部 ${lines.size} 行") {}
            toggle.addActionListener {
                expanded = !expanded
                area.text = if (expanded) full else lines.take(limit).joinToString("\n")
                // 重新按新内容估算高度，否则展开后滚动区高度还是旧的
                area.caretPosition = 0
                scroll.verticalScrollBar.value = 0
                toggle.text = if (expanded) "收起" else "展开全文"
            }
            btns.add(toggle)
        }
        btns.add(UiKit.textButton("复制全部") {
            val sel = java.awt.datatransfer.StringSelection(full)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
        })
        btns.add(UiKit.textButton("关闭") { dialog.dispose() })
        bottom.add(btns, BorderLayout.EAST)

        root.add(bottom, BorderLayout.SOUTH)
        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(root, BorderLayout.CENTER)
        dialog.defaultCloseOperation = javax.swing.JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    /**
     * 弹出图片大图预览。
     *
     * 优先用内存里的预览图（剪贴板图片只有这份），有路径时按需读原图，
     * 这样粘贴的截图和大图文件都能看。
     */
    private fun showImagePreview(att: PendingAttachment) {
        val image: java.awt.image.BufferedImage? = when {
            att.path != null -> runCatching {
                javax.imageio.ImageIO.read(att.path.toFile())
            }.getOrNull() ?: att.processed.preview
            else -> att.processed.preview
        }
        if (image == null) {
            updateStatus("这张图读不出来了")
            return
        }

        val dialog = javax.swing.JDialog(
            com.intellij.openapi.wm.WindowManager.getInstance().getFrame(null) as? java.awt.Frame,
            "预览 · ${att.displayName}",
            true
        )

        // 超过屏幕时按屏幕的 80% 缩放，避免大图弹窗超出显示器
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val maxW = (screen.width * 0.8).toInt()
        val maxH = (screen.height * 0.8).toInt()
        val ratio = minOf(1.0, maxW.toDouble() / image.width, maxH.toDouble() / image.height)
        val w = (image.width * ratio).toInt().coerceAtLeast(1)
        val h = (image.height * ratio).toInt().coerceAtLeast(1)

        val canvas = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun paintComponent(g: java.awt.Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    )
                    g2.drawImage(image, 0, 0, width, height, null)
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = true
            background = UiKit.card
            preferredSize = Dimension(w, h)
        }
        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(canvas, BorderLayout.CENTER)

        val info = JBLabel(
            "${att.displayName}　${image.width}×${image.height}" +
                (if (ratio < 1.0) "（显示为 ${w}×$h）" else "")
        ).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = UiKit.subtle
            border = JBUI.Borders.empty(6, 10)
        }
        dialog.contentPane.add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(info, BorderLayout.WEST)
                add(
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                        isOpaque = false
                        add(UiKit.textButton("关闭") { dialog.dispose() })
                    },
                    BorderLayout.EAST
                )
            },
            BorderLayout.SOUTH
        )

        dialog.defaultCloseOperation = javax.swing.JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    // ---------------- 发送与执行 ----------------

    /**
     * @param queueInstead 生成中时**排队等本轮答完**，而不是插进当前这轮。
     *        默认是插入本轮（steer）—— 用户在这里打字多是「方向不对，调整一下」，
     *        等答完再发等于让他看着 AI 白跑完。要「答完再说」的语义用 Alt+Enter。
     */
    private fun onSend(queueInstead: Boolean = false) {
        // 生成中不丢弃、也不打断：要么插入本轮（steer），要么排队等这轮结束。
        // 以前这里直接 return，用户打了字按 Enter 毫无反应，只能等 —— 很像卡死。
        if (isRunning) {
            val text = inputArea.text.trim()
            if (text.isEmpty()) return
            if (queueInstead) {
                queued.add(text)
                inputArea.text = ""
                refreshQueuedStrip()
                refreshSendState()
                updateStatus("已排队第 ${queued.size} 条（${text.length} 字），本轮结束后自动发出")
                return
            }
            // **直接插进正在跑的这一轮**（steer），而不是等它答完。
            //
            // 用户在这里打字，绝大多数是「你现在跑的方向不对，调整一下」——
            // 等这一轮答完再发，等于让他眼睁睁看着 AI 白跑完才纠正。
            // 而且 steer 是「带着新信息重新规划，已做的工作保留」，比停下来重说划算得多。
            steeringInbox.add(text)
            inputArea.text = ""
            refreshQueuedStrip()
            refreshSendState()
            updateStatus("已插入本轮：模型在下一步就会看到（${text.length} 字）")
            return
        }
        val text = inputArea.text.trim()
        if (text.isEmpty() && attachments.isEmpty()) return

        // 发出去了就把草稿清掉，并把这条记进输入历史（上下键能翻回来）。
        // 注意放在**这里**而不是 runAgent：text 是 onSend 的局部变量。
        com.zhixueyao.chat.SessionStore.saveDraft(currentSessionId, "")
        com.zhixueyao.chat.SessionStore.rememberInput(text)

        val settings = ZhixueyaoSettings.getInstance()
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            val chosen = Messages.showDialog(
                project,
                "还没有配置模型接口，现在去设置吗？",
                "止血药 · 尚未配置",
                arrayOf("打开设置", "取消"),
                0,
                AllIcons.General.GearPlain
            )
            if (chosen == 0) openSettings()
            return
        }

        // 组装用户消息：附件内容拼在正文前，图片单独走多模态字段
        val attachmentBlock = AttachmentSupport.buildAttachmentBlock(attachments.map { it.processed })
        val images = AttachmentSupport.collectImages(attachments.map { it.processed })
        val fullText = buildString {
            if (attachmentBlock.isNotBlank()) {
                append(attachmentBlock)
                if (text.isNotEmpty()) append("\n\n")
            }
            append(text)
        }

        // 「发送前确认」以前只是个摆设（存了但没人读）。这里接上：
        // 内容较长时先让用户看一眼，避免误发一大段上下文
        if (settings.confirmBeforeSend && fullText.length > 600) {
            val preview = fullText.lineSequence().first().take(60)
            val ok = Messages.showDialog(
                project,
                "这条消息有 " + fullText.length + " 个字符（约 " + (fullText.length / 500 + 1) +
                    " 屏），确认发送？\n\n开头：" + preview,
                "止血药 · 发送前确认",
                arrayOf("发送", "取消"),
                0,
                AllIcons.General.Information
            )
            if (ok != 0) return
        }

        history.add(ChatMessage.user(fullText, images))
        addUserBubble(text, attachments.map { it.displayName })

        inputArea.text = ""
        attachments.clear()
        refreshAttachmentBar()

        ensureSystemPrompt()
        runAgent()
    }

    private fun ensureSystemPrompt() {
        val settings = ZhixueyaoSettings.getInstance()
        // 把 MCP 服务器下发的使用说明一并注入 —— 那是服务器作者写给模型的指引，
        // 比让模型自己摸索怎么用这些外部工具可靠得多
        val mcpInstructions =
            if (settings.enableMcp) mcpManager.allInstructions() else emptyList()
        // 把本会话的临时产物目录写进提示词 —— 不写的话模型会把试验产物丢进用户工程
        val workspace = runCatching { sessionWorkspace()?.absolutePath ?: "" }.getOrDefault("")
        // 拿**用户最新那句话**去匹配技能触发词，命中就在提示词里给一条强指令。
        //
        // 为什么要主动匹配：技能目录是每轮都带的固定开销，但「该不该加载某个技能」
        // 原来全靠模型自己从「名字 + 描述」里猜 —— 猜漏了就等于白写。
        // 主动匹配把这一步变成确定性的对照检查（借鉴 agents-universe 的做法）。
        val triggerHint = runCatching {
            val lastUser = history.lastOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
            Skills.renderTriggerHint(Skills.matchTriggers(project, lastUser))
        }.getOrDefault("")
        val prompt = ToolRegistry.systemPrompt(
            projectName = project.name,
            presetId = settings.agentPreset,
            // 显式传 project：不然技能库只能列全局的，项目技能看不见
            project = project,
            mcpInstructions = mcpInstructions,
            workspacePath = workspace,
            triggerHint = triggerHint
        )
        if (history.isEmpty() || history.first().role != ChatMessage.Role.SYSTEM) {
            history.add(0, ChatMessage.system(prompt))
        } else {
            history[0] = ChatMessage.system(prompt)
        }
    }

    /**
     * 重新生成。
     *
     * 做法：把历史回退到**最后一条用户消息之前**，丢弃它之后的所有内容
     * （上一条回答、工具调用结果、错误），再给它一次机会。
     *
     * 用 `lastUserIndex` 而不是「删除末尾 N 条」，是因为一轮对话可能产生
     * 多条 assistant / tool 消息（多步工具调用），条数不固定；
     * 以上一条用户消息为界才准确。
     */
    private fun regenerate() {
        if (isRunning) {
            Messages.showInfoMessage(project, "正在生成中，请先等待结束或点击停止", "止血药")
            return
        }
        val lastUserIndex = history.indexOfLast { it.role == ChatMessage.Role.USER }
        if (lastUserIndex < 0) {
            updateStatus("没有可以重新生成的内容")
            return
        }

        // 先把「要被换掉的那条回答」的版本存下来：生成完追加成新版本，
        // 而不是让旧版消失（用户反馈过「重新生成后还是上一版好，但已经没了」）
        val lastAssistantIndex = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        regenVariants = if (lastAssistantIndex > lastUserIndex) {
            val m = history[lastAssistantIndex]
            if (m.variants.isNotEmpty()) m.variants else listOf(m.content)
        } else {
            emptyList()
        }

        // 回退历史：保留系统提示 + 到该用户消息为止
        val kept = history.take(lastUserIndex + 1).toMutableList()
        // 历史被改写/换会话：摘要必须跟着作废，否则新一段对话会「记得」没发生过的事
        summaryState.reset()
        history.clear()
        history.addAll(kept)

        // 界面上：移除最后一条用户气泡之后的所有组件
        removeBubblesAfterLastUser()

        ensureSystemPrompt()
        runAgent()
    }

    /**
     * 切换某条回答的版本。
     *
     * 换的是 [ChatMessage.content] 本身（不只是界面）—— 后续对话要以这一版为准，
     * 否则「切回上一版」只改了个显示，模型看到的还是新版，等于没切。
     */
    private fun switchVariant(bubble: MessageBubble, index: Int) {
        val msg = bubble.linkedMessage
        // 按**身份**找；找不到时退回「最后一条 assistant」。
        // 因为历史里任何一次 `history[i] = history[i].copy(...)` 都会换掉实例，
        // 光靠 === 太脆 —— 而这条回答是哪条，在只有一个活动气泡的场景下没有歧义。
        val idx = when {
            msg != null -> history.indexOfFirst { it === msg }
            else -> -1
        }.takeIf { it >= 0 } ?: history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx < 0) {
            updateStatus("找不到这条回答对应的记录，无法切换版本")
            return
        }
        val m = history[idx]
        val text = m.variants.getOrNull(index) ?: run {
            updateStatus("这一版的内容读不出来了")
            return
        }
        val updated = m.copy(content = text, activeVariant = index)
        history[idx] = updated
        // 引用要跟着换成新实例，否则下次切换按身份找不到它
        bubble.linkedMessage = updated
        bubble.replaceContent(text)
        bubble.setVariants(m.variants, index) { v -> switchVariant(bubble, v) }
        saveCurrentSession(manual = false)
        updateStatus("已切到第 ${index + 1}/${m.variants.size} 版，后续对话以这一版为准")
    }

    /** 把消息区里最后一条用户气泡之后的内容全部移除，配合历史回退。 */
    private fun removeBubblesAfterLastUser() {
        val components = messagesPanel.components
        var lastUserBubbleIndex = -1
        for (i in components.indices) {
            if (bubbleOf(components[i])?.messageKind == MessageBubble.Kind.USER) lastUserBubbleIndex = i
        }
        if (lastUserBubbleIndex < 0) return

        // 连同其后的支撑条（Strut）一起删掉
        for (i in components.size - 1 downTo lastUserBubbleIndex + 1) {
            messagesPanel.remove(i)
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * 取出消息区某个子项里的气泡。
     *
     * 气泡外面套了一层「分侧用的行容器」（见 [addBubble]），所以不能直接判断子项类型，
     * 要往里找一层 —— 否则重新生成时的历史回退会定位不到上一条用户消息。
     */
    private fun bubbleOf(component: Component?): MessageBubble? = when (component) {
        is MessageBubble -> component
        is java.awt.Container -> component.components.firstOrNull() as? MessageBubble
        else -> null
    }

    private fun runAgent() {
        isRunning = true
        cancelFlag.set(false)
        cancelRequested = false
        historyCursor = -1
        historyDraft = ""
        setPhase(Phase.THINKING)
        // 主动发新消息 = 想看最新进展，恢复跟随
        followState = ScrollFollow.State()

        // 助手气泡**按需创建**。
        //
        // 以前一进来就 add 一个空气泡，用户先看到一个空白框、过一会儿才填内容
        // （反馈「AI 还没回复就先出现空白对话框」）。现在等第一段正文 / 思维链 /
        // 工具调用真的到了再建 —— 没内容就不该有框。
        var bubble: MessageBubble? = null
        fun withBubble(action: (MessageBubble) -> Unit) = edt {
            val b = bubble ?: MessageBubble(MessageBubble.Kind.ASSISTANT, project).also {
                bubble = it
                liveBubble = it
                addBubble(it)
            }
            action(b)
            // 用 autoScroll 而不是 scrollToBottom：用户正在往上翻看历史时，
            // 每来一个增量就把人拽回底部是最烦的（用户反馈「上滑时又被拉到最底部」）
            autoScroll()
        }

        // **立刻**把气泡建出来并挂上「正在思考…（已 N 秒）」占位。
        //
        // 以前气泡是「收到第一段内容才建」的 —— 而开了思考档位之后首字可能要几十秒，
        // 这段时间界面上**什么都没有**，用户只能盯着状态栏，看起来就像卡死了
        // （用户反馈「AI 框都不实时显示」）。
        // 有文字 + 走秒的占位，就不会再被误认为「一进来就是个空白框」。
        // 注意：必须放在 withBubble 定义**之后** —— 局部函数要先声明后使用。
        withBubble { it.markPending() }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runner.run(
                    history = history,
                    tools = ToolRegistry.all,
                    cancelFlag = cancelFlag,
                    summaryState = summaryState,
                    // 每个 turn 边界取一次：有就立刻插进历史，让模型带着新信息重新规划
                    steering = {
                        val out = mutableListOf<String>()
                        while (true) out.add(steeringInbox.poll() ?: break)
                        out
                    },
                    listener = object : AgentRunner.Listener {
                        override fun onTextDelta(text: String) {
                            edt { setPhase(Phase.ANSWERING) }
                            withBubble { it.appendText(text) }
                        }

                        override fun onReasoningDelta(text: String) {
                            edt { setPhase(Phase.THINKING) }
                            withBubble { it.appendReasoning(text) }
                        }

                        override fun onToolStart(call: ToolCall) {
                            edt { setPhase(Phase.TOOL_CALLING) }
                            withBubble { it.addToolCard(call.id, call.name, call.arguments) }
                        }

                        override fun onToolFinish(call: ToolCall, result: ToolResult) {
                            // 工具完了模型还要接着想，回到思考阶段
                            edt { setPhase(Phase.THINKING) }
                            withBubble { b ->
                                b.finishToolCard(call.id, result)
                                // 产出了多媒体就顺手挂上预览卡（只带路径，不带内容）
                                result.attachments.forEach { b.addAttachment(it) }
                            }
                        }

                        override fun onCompact(report: com.zhixueyao.agent.ContextCompactor.Report) {
                            // 压缩是**静默**的：不能弹窗打断推理。只在状态栏说一句，
                            // 用户想知道「为什么 AI 突然忘了前面」时有据可查。
                            edt {
                                val way = if (report.usedSummary) "已摘要早期对话" else "已精简早期工具结果"
                                updateStatus(
                                    "上下文接近上限，$way：${report.beforeTokens} → ${report.afterTokens} tokens" +
                                        "（省下 ${report.savedTokens}）"
                                )
                            }
                        }

                        override fun onComplete(finalText: String, steps: Int, usage: Usage?) {
                            withBubble { b ->
                                b.finalize(
                                    finalText,
                                    onRegenerate = { regenerate() },
                                    onEdit = { editReply(b) },
                                    stopped = cancelRequested
                                )
                                // 用量写进气泡页脚 —— 跟着消息走，翻历史也看得到。
                                // 服务商没回报时退回估算并标注（状态栏那行只显示一会儿，
                                // 用户反馈「为什么我看不到使用了多少 token」）
                                b.setUsage(
                                    prompt = usage?.promptTokens ?: 0,
                                    completion = usage?.completionTokens ?: 0,
                                    estimated = com.zhixueyao.agent.ContextCompactor.estimateTokens(finalText),
                                    steps = steps
                                )
                                // **把附件路径存进这条消息。**
                                //
                                // 不加这一步的话，气泡上的预览卡只活在内存里 ——
                                // 重开会话就全没了，只剩正文里那句「已生成一张图片」。
                                //
                                // 位置就在「重新生成」那段旁边：它用的
                                // `history.indexOfLast { ASSISTANT }` 和 `copy(...)`
                                // 正是这里要用的同一个模式，照抄即可。
                                val attachIdx = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                val paths = b.attachedPathsSnapshot()
                                if (attachIdx >= 0 && paths.isNotEmpty()) {
                                    history[attachIdx] = history[attachIdx].copy(attachments = paths)
                                }

                                // 「重新生成」：把新回答追加成新版本，旧版留着可切回
                                if (regenVariants.isNotEmpty()) {
                                    val idx = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                    val b = bubble
                                    if (idx >= 0 && b != null) {
                                        val merged = regenVariants + finalText
                                        history[idx] = history[idx].copy(
                                            content = finalText,
                                            variants = merged,
                                            activeVariant = merged.lastIndex
                                        )
                                        b.setVariants(merged, merged.lastIndex) { v -> switchVariant(b, v) }
                                    }
                                    regenVariants = emptyList()
                                }

                                // 用量也写进消息本身：重载会话后页脚还能显示出来
                                if (usage != null && usage.total > 0) {
                                    val idx = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                    if (idx >= 0) {
                                        history[idx] = history[idx].copy(
                                            promptTokens = usage.promptTokens,
                                            completionTokens = usage.completionTokens
                                        )
                                    }
                                }

                                // **把气泡挂到最终的那条历史实例上**。
                                //
                                // 上面几处 `history[idx] = history[idx].copy(...)` 每次都产生**新对象**，
                                // 而流式气泡是 `addBubble(it)` 建的、`linkedMessage` 本来就是 null ——
                                // 于是 `switchVariant` 第一行 `linkedMessage ?: return` 直接静默返回，
                                // 表现就是「版本切换器（‹ 2/2 ›）点了没反应」（用户反馈）。
                                // 每次改完历史都要重新挂一次，这是唯一可靠的时机。
                                run {
                                    val idx = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                    val b = bubble
                                    if (idx >= 0 && b != null) b.linkedMessage = history[idx]
                                }

                                // 引用校验：模型编造路径是最误导人的幻觉，
                                // 编出来的文件你照着找半天也找不到。
                                //
                                // **必须在后台线程做**：它要走 IDE 的索引，而这里是 EDT
                                // （withBubble 就是 edt）。之前写在 EDT 上，直接抛
                                // "Read access is allowed from inside read-action only"，
                                // 用户看到一个 IDE 错误弹窗。
                                val target = b
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    val missing = runCatching {
                                        // 把本会话的产物文件名一并传进去 —— 临时产物不在工程里，
                                        // 不传的话它们会被误报成「项目里没找到」（用户看到过这个黄条）
                                        com.zhixueyao.agent.ReferenceChecker.check(
                                            project, finalText, previewGallery().map { it.name }.toSet()
                                        ).missing
                                    }.getOrDefault(emptyList())
                                    if (missing.isNotEmpty()) edt { target.showReferenceHint(missing) }
                                }
                            }
                            edt {
                                val stopped = cancelRequested
                                setPhase(if (stopped) Phase.CANCELLED else Phase.IDLE)
                                // 状态栏 token 用量。
                                //
                                // **优先用服务商回报的真实用量**（provider 一直有解析，
                                // 只是以前在这里被丢掉了），拿不到才退回估算 ——
                                // 估算按字符类型分开算，不再用一个统一系数。
                                // 被停止的一轮不写 token 用量：那会把「已停止」盖掉，
                                // 用户点完停止看不到任何确认，像是没生效
                                if (stopped) {
                                    // setPhase 已经写了「已停止」，这里不再动状态栏
                                } else if (ZhixueyaoSettings.getInstance().showTokenEstimate) {
                                    updateStatus(tokenStatusText(finalText, steps, usage))
                                } else {
                                    updateStatus()
                                }
                                // 一轮结束就落盘，不必等用户手动保存 ——
                                // 工具窗口关闭、IDE 崩溃都不会再丢掉对话
                                saveCurrentSession(manual = false)
                                // 本轮正常结束：把「预发送」的内容发出去
                                flushQueued()
                            }
                        }

                        override fun onError(message: String) {
                            withBubble {
                                it.showError(message)
                                it.finalize("", onRegenerate = { regenerate() })
                            }
                            edt {
                                setPhase(Phase.ERROR)
                                // 状态栏只有一行，多行建议塞进去会被截断得只剩首行的一部分，
                                // 完整内容已经在气泡里，这里取结论那行即可
                                updateStatus("出错了：" + message.lineSequence().first().take(120))
                                saveCurrentSession(manual = false)
                                // 出错不自动重发，把排队内容还给用户
                                restoreQueued()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                log.warn("对话执行失败", e)
                withBubble { it.showError("执行失败：${e.message ?: e.javaClass.simpleName}") }
                edt {
                    setPhase(Phase.ERROR)
                    restoreQueued()
                }
            }
        }
    }

    /**
     * 模拟一次完整的流式输出（**不调用模型**）。
     *
     * 为什么要它：界面上的问题（气泡跳动、思考块不销毁、工具卡片不收尾、停止没反应）
     * 都得走一遍真实链路才能看出来，而每次真调模型既慢又费 token，
     * 还很难复现「工具调用完没回到思考」这种时序问题。
     *
     * 走的是**和真实回调同一套** `setPhase` / 气泡接口 —— 如果这里正常、
     * 真实链路不正常，那问题一定在网络或解析层，排查范围一下就小了。
     * 停止按钮同样有效（共用 cancelFlag）。
     */
    private fun runSimulation() {
        if (isRunning) {
            updateStatus("正在生成中，先停止再试模拟")
            return
        }
        val steps = listOf(
            "让我先看看项目结构。" to 26,
            "这是个 Android 工程，主模块是 app。" to 26,
            "我需要读一下 MainActivity。" to 26
        )
        val answer = """
            |我看完了 `MainActivity`，有三点可以改：
            |
            |1. **启动页不该做业务初始化** —— 把它挪到 `Application.onCreate`，
            |   或者干脆用 `androidx.startup`，启动页只负责跳转。
            |2. `findViewById` 全部换成 ViewBinding，少一堆空指针。
            |3. 网络请求没有超时，建议统一在 OkHttp 客户端上配。
            |
            |要我先动手改第 1 条吗？
        """.trimMargin()

        ApplicationManager.getApplication().executeOnPooledThread {
            cancelRequested = false
            cancelFlag.set(false)
            edt { setPhase(Phase.THINKING) }

            var bubble: MessageBubble? = null
            fun withBubble(action: (MessageBubble) -> Unit) = edt {
                val b = bubble ?: MessageBubble(MessageBubble.Kind.ASSISTANT, project).also {
                    bubble = it
                    addBubble(it)
                }
                action(b)
                autoScroll()
            }

            fun feed(text: String, delayMs: Long = 18) {
                for (ch in text) {
                    if (cancelFlag.get()) return
                    withBubble { it.appendReasoning(ch.toString()) }
                    Thread.sleep(delayMs)
                }
            }

            try {
                // ① 思考流
                for ((line, delay) in steps) {
                    if (cancelFlag.get()) break
                    feed(line, delay.toLong())
                }

                // ② 一次工具调用（含运行中 → 完成）
                if (!cancelFlag.get()) {
                    edt { setPhase(Phase.TOOL_CALLING) }
                    val callId = "sim-" + System.currentTimeMillis()
                    withBubble { it.addToolCard(callId, "read_file", "{\"path\":\"app/src/main/java/MainActivity.kt\"}") }
                    Thread.sleep(700)
                    withBubble {
                        it.finishToolCard(
                            callId,
                            com.zhixueyao.tools.ToolResult("已读取 128 行（模拟）")
                        )
                    }
                    edt { setPhase(Phase.THINKING) }
                }

                // ③ 再想一下
                feed("看完了，我列一下问题。", 20)

                // ④ 正式回答（逐字）
                if (!cancelFlag.get()) {
                    edt { setPhase(Phase.ANSWERING) }
                    val chunk = 3
                    var i = 0
                    while (i < answer.length && !cancelFlag.get()) {
                        val piece = answer.substring(i, minOf(i + chunk, answer.length))
                        withBubble { it.appendText(piece) }
                        i += chunk
                        Thread.sleep(12)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                val stopped = cancelRequested
                edt {
                    withBubble { b ->
                        b.finalize(
                            b.currentText(),
                            onRegenerate = { regenerate() },
                            onEdit = { editReply(b) },
                            stopped = stopped
                        )
                    }
                    setPhase(if (stopped) Phase.CANCELLED else Phase.IDLE)
                    if (!stopped) updateStatus("模拟输出结束（没有调用模型）")
                }
            }
        }
    }

    /**
     * 把当前会话的草稿放回输入框。
     *
     * 换会话时必须调用：否则上一个会话打了一半的内容会跟到新会话里，
     * 或者新会话的草稿被覆盖掉。
     */
    /**
     * 读写输入框文本。
     *
     * 为什么要绕一层方法：inputArea 的初始化里（按键监听）要用到它自己，
     * 直接写 inputArea.text 会让 Kotlin 推不出类型（报 "recursive problem"）。
     * 方法体不参与属性初始化式的类型推断，所以这样写就绕开了。
     */
    private fun inputText(): String = inputArea.text

    private fun setInputText(text: String) {
        inputArea.text = text
        inputArea.caretPosition = text.length
    }

    private fun restoreDraft() {
        draftSaveTimer.stop()
        historyCursor = -1
        historyDraft = ""
        inputArea.text = com.zhixueyao.chat.SessionStore.draftOf(currentSessionId)
        inputArea.caretPosition = inputArea.text.length
        refreshSendState()
    }

    private fun onStop() {
        cancelRequested = true
        cancelFlag.set(true)
        steeringInbox.clear()   // 终止 = 不想要了，半路加的话也没理由再插进去
        if (queued.isNotEmpty()) {
            // 终止 = 用户不想要了，排队的内容也没理由继续发
            val n = queued.size
            queued.clear()
            refreshQueuedStrip()
            updateStatus("正在停止…（已清空 $n 条排队内容）")
        } else {
            updateStatus("正在停止…")
        }
    }

    /**
     * 本轮正常结束 → 把排队的内容自动发出（「预发送」）。
     *
     * 走 [onSend] 而不是自己拼历史：附件、发送前确认、气泡挂载这些逻辑都在那儿，
     * 复用它才不会漏步骤。
     */
    /**
     * 重建队列预览条。
     *
     * 整体重建而不是增量改：队列最多几条，重建的代价可以忽略，
     * 而「哪条被删了要重排后面的序号」用增量写很容易错位。
     */
    private fun refreshQueuedStrip() {
        queuedStrip.removeAll()
        if (queued.isEmpty()) {
            queuedStrip.isVisible = false
        } else {
            queuedStrip.add(
                JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(
                        UiKit.hint("待发送 ${queued.size} 条（本轮结束后按顺序自动发出）"),
                        BorderLayout.WEST
                    )
                    add(
                        UiKit.linkLabel("全部清空") {
                            val n = queued.size
                            queued.clear()
                            refreshQueuedStrip()
                            refreshSendState()
                            updateStatus("已清空 $n 条待发送内容")
                        },
                        BorderLayout.EAST
                    )
                }
            )
            queued.forEachIndexed { index, text -> queuedStrip.add(queuedRow(index, text)) }
            queuedStrip.isVisible = true
        }
        queuedStrip.revalidate()
        queuedStrip.repaint()
    }

    /** 队列里的一条：序号 + 首行摘要 + 单独的删除按钮 */
    private fun queuedRow(index: Int, text: String): JComponent {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val brief = if (firstLine.length > 48) firstLine.take(48) + "…" else firstLine
        val suffix = if (text.lines().size > 1 || firstLine.length > 48) " …" else ""
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
            add(
                JBLabel("${index + 1}. $brief$suffix").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = font.deriveFont(font.size - 1f)
                    foreground = UiKit.subtle
                    toolTipText = text.take(400)
                },
                BorderLayout.CENTER
            )
            add(
                UiKit.linkLabel("×") {
                    if (index in queued.indices) {
                        queued.removeAt(index)
                        refreshQueuedStrip()
                        refreshSendState()
                        updateStatus("已移除第 ${index + 1} 条待发送内容")
                    }
                },
                BorderLayout.EAST
            )
        }
    }

    private fun flushQueued() {
        val q = queued.removeFirstOrNull() ?: return
        refreshQueuedStrip()
        inputArea.text = q
        inputArea.caretPosition = q.length
        refreshSendState()
        onSend()
    }

    /**
     * 本轮出错 → 排队的内容**放回输入框**，不自动发。
     *
     * 出错时自动重发只会立刻再失败一次（比如额度用尽），把内容还给用户更稳妥。
     */
    private fun restoreQueued() {
        if (queued.isEmpty()) return
        // 出错时自动往下发只会立刻再失败一次（额度用尽、断网）。
        // 整队退回输入框：一条都不丢，要不要继续由用户决定。
        val all = queued.joinToString("\n\n")
        val n = queued.size
        queued.clear()
        refreshQueuedStrip()
        inputArea.text = all
        inputArea.caretPosition = all.length
        refreshSendState()
        updateStatus("上一轮出错，$n 条排队内容已放回输入框")
    }

    /**
     * 切阶段。
     *
     * **所有跟阶段有关的界面表现都从这里出去**，别在回调里零散地改 ——
     * 以前就是散的，才会出现「停止后状态栏被覆盖」这种自相矛盾的表现。
     *
     * 必须 EDT 调用（回调都在后台线程，记得包 `edt {}`）。
     */
    private fun setPhase(next: Phase) {
        if (phase == next) return
        phase = next
        val running = next.running
        isRunning = running
        // 停止按钮只在生成中出现；发送按钮的显隐交给 refreshSendState
        // （生成中一旦有内容也要放出来，那是「预发送」入口）
        stopButton.isVisible = running
        // 生成中不允许再挂附件，否则会攒进下一轮造成困惑
        attachButton.isEnabled = !running
        // 输入框**始终保持可用**：生成期间用户可以继续打字，按 Enter 就是预发送
        // （内容先排队，本轮结束自动发出）。用户明确要求别把输入框锁上。
        inputArea.isEnabled = true
        inputArea.repaint()
        attachButton.repaint()
        refreshSendState()
        // 气泡头部跟着走：流式中的气泡必须和「已答完」看得出区别
        if (running) liveBubble?.setLiveStatus(next.label)
        when (next) {
            Phase.THINKING -> updateStatus("正在思考…")
            // 这两个阶段的状态栏由具体动作写（工具行、token 用量），这里不抢
            Phase.TOOL_CALLING, Phase.ANSWERING, Phase.ERROR -> Unit
            Phase.CANCELLED -> updateStatus("已停止：本轮不再继续执行")
            Phase.IDLE -> updateStatus()
        }
        // 收尾阶段由 finalize 写「已完成 / 已停止」，这里不再碰头部
        if (!running && next != Phase.THINKING) {
            liveBubble = null
            // 任务清单也要「收摊」：没有东西在跑了，就不该还挂着「进行中」
            settleTasksOnTurnEnd()
        }
    }

    /**
     * 状态栏的 token 用量文案。
     *
     * 有真实用量就用真实值（`输入 / 输出` 分开列，便于判断是上下文大还是回答长）；
     * 服务商没回报时才估算，并明确写「估算」。
     */
    private fun tokenStatusText(finalText: String, steps: Int, usage: Usage?): String {
        if (usage != null && usage.total > 0) {
            return "本轮 ${usage.total} tokens（输入 ${usage.promptTokens} · 输出 ${usage.completionTokens}）" +
                " · 共 $steps 步"
        }
        // 估算**复用 ContextCompactor 的那一份**，不要在这里另写一个：
        // 它按码点分（CJK 1 字≈1 token、其余 4 字符≈1 token，且正确处理代理对），
        // 上下文压缩用的也是它 —— 两处口径一致，状态栏显示的才和压缩判断对得上。
        // 以前这里是 `长度 × 0.7`：纯中文低估 30%、纯英文高估近 3 倍。
        return "本次回答约 ${ContextCompactor.estimateTokens(finalText)} tokens（估算）· 共 $steps 步"
    }

    private fun updateStatus(text: String? = null) {
        if (text != null) {
            statusLabel.text = text
            return
        }
        val s = ZhixueyaoSettings.getInstance()
        statusLabel.text = when {
            s.baseUrl.isBlank() || s.model.isBlank() -> "尚未配置模型 · 点右上角齿轮，或上方「未配置模型」"
            isRunning -> "正在思考…"
            else -> {
                // 有会话主题就显示它：用户发送后最想确认的是「这轮在聊什么」
                val title = currentSessionTitle()
                if (title.isNotEmpty()) "会话：$title" else "Enter 发送 · Shift+Enter 换行 · 可拖入文件"
            }
        }
    }

    // ---------------- 消息渲染 ----------------

    /**
     * 把气泡按角色分侧放进消息区：**助手贴左、用户贴右**，且最多占可用宽度的
     * [BUBBLE_WIDTH_RATIO]。
     *
     * 为什么套一层「行容器」，而不是直接给气泡设 `alignmentX = RIGHT_ALIGNMENT`：
     * 实测（JBR 25 纯 Swing 探针）BoxLayout 在混用 LEFT/RIGHT 子组件、且子组件
     * 有最大宽度限制时摆放不可靠 —— 行容器拿到的宽度会缩成 0，气泡直接消失。
     * 行容器方案用「按当前宽度现算的对侧内边距」把气泡挤到另一边，位置是确定的：
     * 内边距随宽度自动伸缩，不需要监听尺寸变化，也不需要知道父容器宽多少。
     */
    private fun addBubble(bubble: MessageBubble, message: ChatMessage? = null) {
        // 记住对应消息，供「编辑」定位历史位置（见 editMessage）
        bubble.linkedMessage = message
        // 预览出口：气泡点图/点链接都从这里走。
        // 左右翻页的范围必须是**整个会话的图**，而气泡只知道自己的附件，
        // 所以由面板统一处理（见 previewGallery）。
        bubble.onPreviewRequest = { f -> openPreviewWithGallery(f) }
        val toRight = bubble.messageKind == MessageBubble.Kind.USER
        val row = object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

            /** 上次算内边距时的行宽，用于判断「宽度变了、需要补一轮重排」 */
            private var insetsForWidth = -1

            /**
             * 对侧留白：气泡占 [BUBBLE_WIDTH_RATIO]，其余挤到另一边。
             *
             * 但**不一律拉满**：短消息要贴合内容。以前无条件用 82%，
             * 两个字的「你好」也是一整条蓝色，文字贴在左边、名字贴在右边，
             * 中间空一大片 —— 用户反馈的「挤压难看」。
             * 现在宽度取「内容自然宽度」，上限仍是 82%。
             *
             * 注意这里读 `bubble.naturalWidth()` 不会递归：它只按字体度量算文本宽度，
             * 不读任何跟行宽有关的值。
             */
            override fun getInsets(): Insets {
                val avail = width.coerceAtLeast(1)
                // 宽度一变，气泡宽度就变 → 正文折行数变 → 气泡首选高度也变。
                // 而一次校验只排一轮，高度会停留在「按上一次宽度算出来」的值上，
                // 正文最后一行就被挤到圆角卡外面（实测：两行的消息气泡矮 16px）。
                // 这里在宽度变化时补一次重排，两轮之后收敛。
                if (avail != insetsForWidth) {
                    insetsForWidth = avail
                    SwingUtilities.invokeLater { revalidate() }
                }
                val cap = maxOf((avail * BUBBLE_WIDTH_RATIO).toInt(), minOf(avail, MIN_BUBBLE_WIDTH))
                val floor = minOf(avail, MIN_BUBBLE_WIDTH)
                val natural = bubble.naturalWidth()
                val bubbleWidth =
                    if (natural <= 0) cap
                    else natural.coerceIn(floor, cap)
                val pad = (avail - bubbleWidth).coerceAtLeast(0)
                return if (toRight) Insets(0, pad, 0, 0) else Insets(0, 0, 0, pad)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        row.add(bubble, BorderLayout.CENTER)
        messagesPanel.add(row)
        messagesPanel.add(UiKit.strut(8))
        trimMessages()
    }

    /**
     * 消息区窗口化。
     *
     * Swing 没有虚拟列表（DOM 那套在这儿不存在），能做的是**限制常驻的气泡数量**：
     * 超出上限就从顶部丢弃最旧的几对（气泡 + 分隔条）。
     * 丢的只是**界面节点** —— 历史仍在 `history` 里、上下文与持久化都不受影响，
     * 所以「往回翻看不到更早的消息」不会影响模型看到的内容。
     *
     * 顶部会挂一行提示，明确告诉用户「更早的消息只是没显示，不是丢了」。
     */
    private fun trimMessages() {
        var removed = 0
        while (messagesPanel.componentCount > MAX_MESSAGE_ROWS) {
            messagesPanel.remove(0)
            messagesPanel.remove(0)
            removed++
        }
        if (removed == 0) return
        trimmedRows += removed
        showTrimHint()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /** 顶部提示：已省略多少条更早的消息 */
    private fun showTrimHint() {
        trimHint.text = "已省略更早的 $trimmedRows 条消息（仍在对话上下文里，不影响回答）"
        if (trimHint.parent == null) {
            messagesPanel.add(trimHint, 0)
        }
        trimHint.revalidate()
    }

    private fun addUserBubble(text: String, attachmentNames: List<String>) {
        // 第一条消息发出后就把欢迎页收掉：它只是开场引导，对话开始后还挂在上方
        // 会把刚发出的消息顶下去（用户反馈「我发会话了还显示」）
        welcomePage?.let {
            messagesPanel.remove(it)
            welcomePage = null
        }
        val bubble = MessageBubble(MessageBubble.Kind.USER, project)
        if (attachmentNames.isNotEmpty()) bubble.appendAttachmentNote(attachmentNames)
        bubble.appendText(text)
        // 用户气泡给「编辑」——助手气泡给「重新生成」，各取所需
        bubble.finalize(text, onEdit = { editMessage(bubble) })
        addBubble(bubble, history.lastOrNull { it.role == ChatMessage.Role.USER })
        scrollToBottom()
    }

    /**
     * 编辑一条用户消息：把文本放回输入框，并丢弃这条消息及其之后的所有内容。
     *
     * 这是**不可逆**操作（后面的回答、工具调用结果都会丢），所以先弹确认 ——
     * 与「清空对话」同一套规矩：破坏性操作必须二次确认，不能点一下就执行。
     *
     * 只支持编辑用户消息：助手消息的内容是模型产出，改它没有意义（该做的是重新生成）。
     */
    private fun editMessage(bubble: MessageBubble) {
        if (isRunning) {
            Messages.showInfoMessage(project, "请先等待当前对话结束或点击停止", "止血药")
            return
        }
        val message = bubble.linkedMessage ?: run {
            updateStatus("这条消息已经不在当前对话里了，无法编辑")
            return
        }
        val index = history.indexOfFirst { it === message }
        if (index < 0) {
            updateStatus("这条消息已经不在当前对话里了，无法编辑")
            return
        }

        val chosen = Messages.showDialog(
            project,
            "编辑这条消息会丢弃它之后的全部回复与工具调用结果，确定继续吗？",
            "止血药 · 编辑消息",
            arrayOf("继续编辑", "取消"),
            0,
            UiKit.warning
        )
        if (chosen != 0) return

        // 历史回退到这条消息之前（系统提示保留在最前面）
        val kept = history.take(index).toMutableList()
        // 历史被改写/换会话：摘要必须跟着作废，否则新一段对话会「记得」没发生过的事
        summaryState.reset()
        history.clear()
        history.addAll(kept)

        // 界面：**按历史重建消息区**，而不是按控件索引去删。
        // 索引删除太脆：气泡外面套了「分侧行容器」、行与行之间还有支撑条，
        // 只要有一处对不上（比如流式生成出来的助手气泡没有 linkedMessage），
        // 删除就会静默失败 —— 表现就是「内容回到输入框了，但问题和回答都还在」
        // （用户反馈的正是这个）。重建是幂等的，界面必然与 history 一致。
        renderHistory()
        trimmedRows = 0

        val text = message.content
        inputArea.text = text
        inputArea.caretPosition = text.length
        inputArea.requestFocusInWindow()
        updateStatus("已放回输入框，改完按 Enter 重新发送")
        saveCurrentSession(manual = false)
    }

    /**
     * 修改助手的回答。
     *
     * 与用户消息的「编辑」是两回事：那条是「回退 + 重发」，这条是**就地改文本**
     * （纠正措辞、删掉多余段落），改完替换历史里对应的那条 —— 后续对话以新内容为准。
     * 用平台自带的多行输入框，不另造对话框。
     */
    private fun editReply(bubble: MessageBubble) {
        if (isRunning) {
            Messages.showInfoMessage(project, "请先等待当前对话结束或点击停止", "止血药")
            return
        }
        val current = bubble.currentText()
        if (current.isBlank()) return

        val edited = Messages.showMultilineInputDialog(
            project,
            "修改这条回答（保存后会替换对话历史里的内容，后续对话以新内容为准）",
            "止血药 · 修改回答",
            current,
            null,
            null
        ) ?: return
        if (edited.isBlank() || edited == current) return

        bubble.replaceContent(edited)
        val index = history.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (index >= 0) {
            history[index] = history[index].copy(content = edited)
            saveCurrentSession(manual = false)
            updateStatus("已修改这条回答")
        }
    }

    /** 移除消息区里指定气泡及其后的所有内容（配合历史回退）。 */
    /**
     * 按当前 `history` 重建整个消息区。
     *
     * 与「按控件索引删」相比，代价是重绘全部气泡，但换来的是**界面与历史必然一致** ——
     * 编辑消息、回退分支这类操作本来就不频繁，值得用一个确定性换掉一堆边界判断。
     */
    /**
     * 移除消息区全部气泡，并**停掉它们内部的定时器**。
     *
     * 直接 `removeAll()` 的话，气泡里的 Timer 不会被回收，
     * 会继续按秒触发（占位计时、阶段计时、工具卡旋转）——
     * 反复切会话/清空就会攒下一堆空转的定时器。
     */
    private fun clearBubbles() {
        for (c in messagesPanel.components) bubbleOf(c)?.disposeTimers()
        messagesPanel.removeAll()
    }

    private fun renderHistory() {
        clearBubbles()
        var shown = 0

        // **按「一轮对话」渲染，而不是按历史条数渲染。**
        //
        // 历史是给模型看的：一次带工具调用的回答会有**两条** assistant
        // （第一条是「我打算怎么做」+ 工具调用，第二条是最终结论），这是 API 协议要求的。
        // 但界面上那是**一轮** —— 直接按条数渲染就会出现
        // 「我只发了一句话，却出来好几个回答框」（用户反馈），
        // 而且每个框还只有半截内容。
        //
        // 更别扭的是：流式过程中界面是对的（一个气泡、文本累积），
        // 只有重载会话/编辑重发之后才变成多个 —— 同一轮对话两种长相。
        // 分组逻辑见 [HistoryGrouping]，它是纯函数、有离线探针。
        for (turn in HistoryGrouping.group(history)) {
            turn.userMessage?.let { user ->
                val bubble = MessageBubble(MessageBubble.Kind.USER, project)
                bubble.appendText(user.content)
                bubble.finalize(user.content, onEdit = { editMessage(bubble) }, replay = true)
                addBubble(bubble, user)
                shown++
            }

            if (!turn.hasAssistantText) continue
            val last = turn.lastAssistant
            val bubble = MessageBubble(MessageBubble.Kind.ASSISTANT, project)
            // 思维链只取最后一条：中间轮次的思维链是「当时怎么想的」，
            // 拼起来会变成一大段前后矛盾的推理，显示价值低、还容易误导
            if (turn.reasoning.isNotBlank()) bubble.appendReasoning(turn.reasoning)
            bubble.appendText(turn.assistantText)
            // replay = true：重建的气泡**不显示耗时**（没有真实起始时间，算出来是假的）
            bubble.finalize(turn.assistantText, onRegenerate = { regenerate() }, replay = true)
            // **把存档里的附件重新挂回气泡。**
            //
            // 不重建的话，重开会话后缩略图就没了（用户反馈「怎么图片不见了」）——
            // 而正文里那句「已生成一张图片」还在，看起来像插件把图弄丢了。
            //
            // 直接调 addAttachment：它会自己判断文件在不在（不在就退化成文件卡片），
            // 所以临时产物被清理过的情况也不会炸。
            turn.lastAssistant?.attachments?.forEach { bubble.addAttachment(it) }
            // 重载后页脚也要有用量（否则「翻历史看不到 token」又回来了）
            if (turn.promptTokens > 0 || turn.completionTokens > 0) {
                bubble.setUsage(turn.promptTokens, turn.completionTokens)
            }
            // 重新生成过的消息，重载后也要能切版本（否则切回来一看，切换器没了）
            if (last != null && last.variants.size > 1) {
                val active = last.activeVariant.coerceIn(0, last.variants.lastIndex)
                bubble.setVariants(last.variants, active) { v -> switchVariant(bubble, v) }
            }
            // linkedMessage 必须挂**该轮最后一条** assistant ——
            // switchVariant / 重新生成都按它定位历史位置
            addBubble(bubble, last)
            shown++
        }
        if (shown == 0) addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollToBottom()
    }

    private fun removeBubblesFrom(bubble: MessageBubble) {
        val components = messagesPanel.components
        val index = components.indexOfFirst { bubbleOf(it) === bubble }
        if (index < 0) return
        for (i in components.size - 1 downTo index) {
            // 移除前先停掉气泡里的定时器，否则它们会继续空转
            bubbleOf(components[i])?.disposeTimers()
            messagesPanel.remove(i)
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * 欢迎页。
     *
     * 两块内容，交互性质**严格区分**：
     *  - 「快捷动作」用 actionRow，点了真做事（读选区、开文件选择器、开设置、填提示词）
     *  - 「能力说明」用 infoRow，不可点、无悬停 —— 它只是介绍，长得像按钮就是骗点击
     *
     * 这个区分来自用户反馈「很多按钮都没用」：原来是把所有项都做成了可点动作行，
     * 但「我能做的事」那几个点下去只是把标签文字填进输入框，用户当然觉得是坏的。
     */
    private fun addWelcomeMessage() {
        val page = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 4, 8, 4)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // 品牌标识。高度动态求值，不写死像素 —— 大字号/高 DPI 下图标行会变高，
        // 写死 30 会把内容裁掉（和 buildSetupCard 当初写死 140 是同一类坑）。
        val brandRow = object : JBPanel<JBPanel<*>>(BorderLayout(9, 0)) {
            init {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(2)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        brandRow.add(JLabel(UiKit.brandIcon), BorderLayout.WEST)
        brandRow.add(JBLabel("止血药").apply {
            font = font.deriveFont(Font.BOLD, font.size + 2.5f)
            foreground = UiKit.brand
        }, BorderLayout.CENTER)
        page.add(brandRow)

        page.add(JBLabel("Android Studio 里的编码助手").apply {
            font = font.deriveFont(font.size - 0.5f)
            foreground = UiKit.subtle
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })

        // ---- 快捷动作：每一条都真的执行某个操作 ----
        page.add(UiKit.groupLabel("快捷动作"))

        page.add(UiKit.actionRow(UiKit.edit, "解释选中的代码", "读编辑器选区") {
            val selection = currentEditorSelection()
            if (selection.isNullOrBlank()) {
                Messages.showInfoMessage(
                    project,
                    "编辑器里还没有选中内容。\n\n先在代码中选中一段，再点这里。",
                    "止血药"
                )
            } else {
                val name = currentEditorFileName() ?: "(当前文件)"
                inputArea.text = "请帮我看看这段代码（来自 $name）：\n\n```\n$selection\n```\n\n"
                inputArea.caretPosition = inputArea.text.length
                inputArea.requestFocusInWindow()
                updateStatus("已把选中的代码放进输入框，补充你想问的问题后按 Enter")
            }
        })

        page.add(UiKit.actionRow(UiKit.search, "在工程里搜索", null) {
            inputArea.text = "在整个工程里搜索："
            inputArea.caretPosition = inputArea.text.length
            inputArea.requestFocusInWindow()
            updateStatus("在冒号后面写上你要找的内容，按 Enter 我就去搜")
        })

        page.add(UiKit.actionRow(UiKit.compile, "检查编译错误", null) {
            inputArea.text = "检查当前工程的编译错误，并说明怎么修"
            inputArea.caretPosition = inputArea.text.length
            inputArea.requestFocusInWindow()
            updateStatus("按 Enter 开始检查")
        })

        page.add(UiKit.actionRow(UiKit.upload, "上传文件作为上下文", "也可直接拖入") {
            chooseFiles()
        })

        page.add(UiKit.actionRow(UiKit.folder, "打开 MCP 服务器设置", "让 AI 调用外部工具") {
            openSettings(section = "plugins")
        })

        // ---- 能力说明：静态行，不可点 ----
        page.add(UiKit.groupLabel("我能做的事"))
        page.add(UiKit.infoRow(UiKit.file, "读文件、图片和截图", "拖入或 Ctrl+V 粘贴"))
        page.add(UiKit.infoRow(UiKit.folder, "读取上传的文件", "作为上下文一并发送"))
        page.add(UiKit.infoRow(UiKit.run, "改代码、跑构建", "改动可一键应用 / Ctrl+Z 撤销"))
        page.add(UiKit.infoRow(UiKit.help, "调用外部 MCP 工具", "浏览器、数据库等"))

        page.add(JBLabel("拖入文件，或按 Ctrl+V 粘贴截图，即可作为上下文发送。").apply {
            font = font.deriveFont(font.size - 1f)
            foreground = UiKit.faint
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(10)
        })

        welcomePage = page
        messagesPanel.add(page)
        messagesPanel.add(UiKit.strut(8))

        // 未配置模型时的引导卡片，放在一个「槽」里而不是直接 add ——
        // 这样设置改完之后可以重新求值（见 refreshSetupCard）。
        //
        // 原来的写法是 `if (未配置) messagesPanel.add(buildSetupCard())`：
        // 而这个方法只在 init 与清空对话时调用一次，于是用户去设置里配好了模型
        // 再回来，那张「还没有配置模型」的卡片还挂在那里 —— 用户截图反馈的正是这个。
        setupCardSlot = object : JBPanel<JBPanel<*>>() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // 高度动态求值：BoxLayout 下若不限制 maximumSize，这张「槽」会被拉满
            // 整屏，把下面的内容挤出可视区
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        messagesPanel.add(setupCardSlot)
        refreshSetupCard()
    }

    /** 引导卡片的容器。见 [refreshSetupCard] */
    private var setupCardSlot: JBPanel<JBPanel<*>>? = null

    /**
     * 重新求值「要不要显示未配置引导卡」。
     *
     * 触发时机：欢迎页渲染时、从设置对话框返回时、切换模型后。
     * 判断依据每次都现读设置 —— 缓存状态正是原来那个 bug 的根源。
     */
    private fun refreshSetupCard() {
        val slot = setupCardSlot ?: return
        // 只在欢迎页（没有对话历史）上显示：一旦开始聊天，
        // 中间插一张配置卡会打断阅读
        val needCard = history.isEmpty() && !isConfigured()

        if (needCard) {
            if (slot.componentCount == 0) {
                slot.add(buildSetupCard())
                slot.add(UiKit.strut(8))
            }
        } else {
            slot.removeAll()
        }
        slot.isVisible = needCard
        slot.revalidate()
        slot.repaint()
    }

    private fun isConfigured(): Boolean {
        val s = ZhixueyaoSettings.getInstance()
        return s.baseUrl.isNotBlank() && s.model.isNotBlank()
    }

    /** 读取当前编辑器选区，没有编辑器或没有选中时返回 null。 */
    private fun currentEditorSelection(): String? {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            ?: return null
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        return selection.selectedText?.replace('\u2028', '\n')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 当前编辑器打开的文件名。
     *
     * **PSI 访问必须包 read action**：这个方法由快捷键触发（EDT 上），
     * 直接调 `getPsiFile` 会抛 "Read access is allowed from inside read-action only"
     * （和引用校验那次是同一个坑，只是位置不同）。
     */
    private fun currentEditorFileName(): String? {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            ?: return null
        return com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
            com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.name
        }
    }

    /**
     * 未配置模型时的引导卡片。
     *
     * 用品牌淡底 + 图标标题，比一行灰字显眼；主按钮直达服务商选择，
     * 用户反馈过「找不到能自定义的地方」，入口就该摆在眼前。
     *
     * 最大高度**动态求值**，不写死数字 —— 写死 140 时，如果用户放大了
     * IDE 字号或系统 DPI 较高，文本换行变多、实际需要的高度超过 140，
     * 按钮行就会被裁掉看不见。
     */
    private fun buildSetupCard(): JComponent =
        object : JBPanel<JBPanel<*>>(BorderLayout(0, 8)) {
            init {
                isOpaque = true
                background = UiKit.brandSoft
                border = JBUI.Borders.compound(
                    UiKit.cardBorder(UiKit.warn, UiKit.radius),
                    JBUI.Borders.empty(11, 13)
                )
                alignmentX = Component.LEFT_ALIGNMENT

                val titleRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                titleRow.add(JLabel(UiKit.warning))
                titleRow.add(JBLabel("还没有配置模型").apply {
                    font = font.deriveFont(Font.BOLD, font.size + 0.5f)
                    foreground = UiKit.warn
                })
                add(titleRow, BorderLayout.NORTH)

                add(
                    JBLabel(
                        "<html><div width='300'>支持 DeepSeek、通义千问、Kimi、智谱、硅基流动、" +
                            "OpenAI、Claude、Ollama 本地模型，也可以填任意自定义端点。</div></html>"
                    ).apply {
                        font = font.deriveFont(font.size - 0.5f)
                        foreground = UiKit.subtle
                    },
                    BorderLayout.CENTER
                )

                val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                row.add(UiKit.primaryButton("选择服务商") { showModelMenu() })
                row.add(UiKit.textButton("打开完整设置") { openSettings() })
                add(row, BorderLayout.SOUTH)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun clearHistory() {
        if (isRunning) {
            Messages.showInfoMessage(project, "请先等待当前对话结束或点击停止", "止血药")
            return
        }
        if (history.isEmpty()) {
            updateStatus("对话本来就是空的")
            return
        }
        val confirmed = Messages.showDialog(
            project,
            "确定清空当前对话吗？\n\n清空后 AI 将不再记得之前聊过的内容。",
            "止血药 · 清空对话",
            arrayOf("清空", "取消"),
            1,
            AllIcons.General.QuestionDialog
        )
        if (confirmed != 0) return

        // 历史被改写/换会话：摘要必须跟着作废，否则新一段对话会「记得」没发生过的事
        summaryState.reset()
        history.clear()
        clearBubbles()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        updateStatus("对话已清空，可以重新开始")
    }

    // ---------------- 会话管理 ----------------

    /** 当前会话 id。新建时生成，保存后固定 */
    private var currentSessionId: String = com.zhixueyao.chat.SessionStore.newId()

    /** 当前会话的创建时间，用于排序 */
    private var currentSessionCreatedAt: Long = System.currentTimeMillis()

    /** 上次保存时的消息条数，用于判断「有没有未保存的改动」 */
    private var savedMessageCount: Int = -1

    /**
     * 本会话的**临时产物目录**（`~/.zhixueyao/Conversation/Product/<时间>/<会话名>`）。
     *
     * 懒建：第一次用到时才 mkdir，这样「只看了一眼没提问」的会话不会留下空目录。
     * 换会话时置空，下次用到会按新会话重新算（见 [resetPerSessionState]）。
     */
    private var workspaceDir: java.io.File? = null

    /**
     * 取本会话的临时产物目录（必要时创建）。
     *
     * 会话名可能被改（模型自动起标题），所以每次都按当前标题重算，
     * 并在名字变了时把目录**改名同步**过去 —— 否则用户按名字找自己的产物会找不到。
     */
    private fun sessionWorkspace(): java.io.File? {
        val base = project?.basePath ?: return null
        val cached = workspaceDir
        if (cached != null) {
            com.zhixueyao.agent.SessionWorkspace.renameIfNeeded(
                cached, currentSessionCreatedAt, currentSessionId, currentSessionTitle()
            )
            if (cached.isDirectory) return cached
            workspaceDir = null
        }
        val dir = com.zhixueyao.agent.SessionWorkspace.ensure(
            currentSessionCreatedAt, currentSessionId, currentSessionTitle()
        )
        workspaceDir = dir
        // 登记给工具层：它们只有 Project，拿不到会话状态，
        // 但需要知道「临时产物该写哪」（见 SessionWorkspace.remember）
        com.zhixueyao.agent.SessionWorkspace.remember(project, dir)
        return dir
    }

    /**
     * 会话菜单。
     *
     * 三条动作 + 一列历史会话：
     *  - 新建会话：把当前会话存盘后清空界面，开始新的一段
     *  - 保存会话：显式落盘（平时也会自动存，这里给一个心理上的确定感）
     *  - 历史会话：点行载入；每行右侧常驻一个垃圾桶按钮可删除（见 [sessionRow]）
     *
     * 为什么要有「自动保存 + 手动保存」两套：自动保存是防止意外丢失的兜底，
     * 但用户看不见它，心里没底；手动的存在主要是给这份确定感。
     */
    private fun showSessionMenu() {
        val menu = JPopupMenu()
        val store = com.zhixueyao.chat.SessionStore

        menu.add(sectionHeader("当前会话"))
        menu.add(javax.swing.JMenuItem(
            if (history.isEmpty()) "新建会话" else "新建会话（当前这段会先存起来）"
        ).apply {
            font = font.deriveFont(font.size - 1f)
            icon = UiKit.add
            addActionListener { newSession() }
        })

        menu.add(javax.swing.JMenuItem("保存会话").apply {
            font = font.deriveFont(font.size - 1f)
            icon = AllIcons.Actions.MenuSaveall
            isEnabled = history.isNotEmpty()
            toolTipText = "存到 IDE 配置目录下的 zhixueyao/sessions/"
            addActionListener { saveCurrentSession(manual = true) }
        })

        val saved = store.list()
        if (saved.isNotEmpty()) {
            menu.addSeparator()
            menu.add(sectionHeader("历史会话（${saved.size}）"))
            // 只列最近 15 条：菜单太长反而找不到东西，
            // 完整列表留给重新打开工具窗口时的自动恢复
            val rows = saved.take(15).map { sessionRow(menu, it, it.id == currentSessionId) }
            // 每行都是「标题撑开 + 删除按钮贴右」。行宽不统一的话，删除按钮会参差不齐 ——
            // 先各自算 preferredSize，再把所有行拉齐到最宽的那一条。
            val rowWidth = rows.maxOf { it.preferredSize.width }
            for (row in rows) {
                row.preferredSize = Dimension(rowWidth, row.preferredSize.height)
                menu.add(row)
            }
        }

        menu.show(sessionButton, 0, sessionButton.height + 2)
    }

    /**
     * 历史会话的一行：左边「标题 · 时间 · 条数」，右边一个删除按钮。
     *
     * 为什么不用 `JMenuItem`：它整行只能绑一个动作，放不下「点行载入」+「点垃圾桶删除」
     * 两个语义。之前整列都是 JMenuItem，所以**根本没有删除入口** —— 用户反馈的
     * 「怎么没有删除会话的按钮」就是这里（方法注释里写着「含删除入口」，实际没实现）。
     *
     * 点行载入、点垃圾桶删除，两个动作互不干扰：垃圾桶是独立组件，事件不会冒泡到行上。
     *
     * **两个动作都要自己关菜单**：`JMenuItem` 被点中时弹窗会自动收起，
     * 而自定义面板不是菜单元素，不关的话菜单会一直挂在那儿。
     */
    private fun sessionRow(
        menu: JPopupMenu,
        meta: com.zhixueyao.chat.SessionStore.Meta,
        isCurrent: Boolean
    ): JComponent =
        object : JBPanel<JBPanel<*>>(BorderLayout(6, 0)) {
            private var hovered = false

            init {
                isOpaque = false
                border = JBUI.Borders.empty(2, 6, 2, 2)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = if (isCurrent) "当前会话" else "点击载入这个会话"
                alignmentX = Component.LEFT_ALIGNMENT

                val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                // 当前会话打勾。其余用同宽的空占位，保证各行的标题左边缘对齐
                left.add(JLabel(if (isCurrent) UiKit.check else null).apply {
                    preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                })
                left.add(JBLabel(
                    "${meta.title}　·　${com.zhixueyao.chat.SessionStore.formatTime(meta.updatedAt)}" +
                        "　·　${meta.messageCount} 条"
                ).apply {
                    font = font.deriveFont(font.size - 1f)
                    foreground = if (isCurrent) UiKit.text else UiKit.subtle
                })
                add(left, BorderLayout.WEST)

                // 删除按钮**常驻显示**，不做悬停才出现：菜单里悬停显隐要靠鼠标位置轮询，
                // 而且用户已经明确「找不到删除入口」，藏起来只会更找不到。
                add(UiKit.iconButton(UiKit.remove, "删除这个会话") {
                    confirmDeleteSession(menu, meta)
                }, BorderLayout.EAST)

                // 悬停/点击挂到整棵子树：AWT 鼠标事件**不冒泡**，只挂行容器的话
                // 点在标题文字上不会响应（行内按钮由 attachRowInteractions 自动跳过）
                UiKit.attachRowInteractions(
                    root = this,
                    onHover = { h -> hovered = h; repaint() },
                    onClick = {
                        menu.isVisible = false
                        // 见 openSessionAsync 的注释：这里原来是在 EDT 上直接读文件的
                        if (!isCurrent) openSessionAsync(meta.id)
                    }
                )
            }

            override fun paintComponent(g: Graphics) {
                UiKit.paintRoundedFill(g, if (hovered) UiKit.hover else null, UiKit.radiusSmall, width, height)
                super.paintComponent(g)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /**
     * 删除一个历史会话。
     *
     * 删除不可撤销，所以先确认；确认文案里带上标题与条数，避免点错行。
     * 删掉的如果是当前会话，就顺带把当前会话标记清掉（历史与界面不动）——
     * 否则下一次自动保存又会把这个 id 写回去，看起来像「删了又自己回来了」。
     *
     * 删完重开一次菜单，让列表立刻反映结果（菜单内容在弹出状态下不会自己刷新）。
     */
    private fun confirmDeleteSession(
        menu: JPopupMenu,
        meta: com.zhixueyao.chat.SessionStore.Meta
    ) {
        val chosen = Messages.showDialog(
            project,
            "删除会话「${meta.title}」（${meta.messageCount} 条）？\n\n这条记录会从磁盘上永久删除，无法恢复。",
            "止血药 · 删除会话",
            arrayOf("删除", "取消"),
            1,
            UiKit.warning
        )
        if (chosen != 0) return

        val ok = com.zhixueyao.chat.SessionStore.delete(meta.id)
        if (!ok) {
            Messages.showWarningDialog(project, "删除失败，文件可能正被占用。", "止血药 · 删除会话")
            return
        }
        // 删掉的是当前会话时，给它换一个新 id：内存里的这段对话还在，
        // 下次自动保存会以新 id 落盘，不会把刚删掉的那个文件又写回来
        // （否则看起来就是「删了又自己回来了」）
        if (meta.id == currentSessionId) {
            currentSessionId = com.zhixueyao.chat.SessionStore.newId()
            restoreDraft()
        }
        updateStatus("已删除会话：${meta.title}")

        menu.isVisible = false
        // 等当前这轮事件处理完再弹，避免在菜单关闭过程中又开一个
        SwingUtilities.invokeLater { showSessionMenu() }
    }

    /**
     * 新建会话。
     *
     * 先把当前这段存起来再清空 —— 「新建」不该等于「丢掉刚才聊的」。
     * 只有当前会话是空的（还没说过话）才直接清，不必产生一条空存档。
     */
    private fun newSession() {
        if (isRunning) {
            Messages.showInfoMessage(project, "正在生成中，请先等待结束或点击停止", "止血药")
            return
        }
        if (history.isNotEmpty()) saveCurrentSession(manual = false)

        // 历史被改写/换会话：摘要必须跟着作废，否则新一段对话会「记得」没发生过的事
        summaryState.reset()
        history.clear()
        clearBubbles()
        currentSessionId = com.zhixueyao.chat.SessionStore.newId()
        currentSessionCreatedAt = System.currentTimeMillis()
        savedMessageCount = -1
        // 所有跟着会话走的状态（附件 / 队列 / 任务 / 产物 / 临时量）都要清 ——
        // 必须在 currentSessionId 更新之后调（产物按 id 取）
        resetPerSessionState()
        restoreDraft()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        updateStatus("已新建会话")
    }

    /**
     * 切换会话时**把所有「属于某个会话」的状态清干净**。
     *
     * 这个函数存在的理由：会话隔离不是「把 history 换掉」就完事了。
     * 界面上有一堆状态是**跟着会话走的**，漏掉任何一个都会串场 ——
     * 用户看到的现象就是「多个会话共用一个上下文」（用户反馈的原话）。
     *
     * 已经踩到过的具体漏项：
     *  - **产物列表**：`loadArtifacts()` 只在 `init` 里调过一次，
     *    换会话后 `artifacts` 还是上一个会话的；更糟的是下一次 `persistArtifacts()`
     *    会把这个列表**按新会话 id 写回去** → 上一个会话的产物正式「污染」到新会话 ✗
     *  - **待发送附件**：在 A 会话选了图，切到 B 会话附件还挂着 ✗
     *  - **排队内容 / 运行中追加**：同样会跨会话生效 ✗
     *  - **任务清单**：一直挂着上一个会话的「已完成 3/5」✗
     *
     * 注意调用时机：
     *  - `loadArtifacts()` **必须在 currentSessionId 更新之后**调（它按 id 取）
     *  - 附件条 / 队列条 / 任务面板都要 `refresh*()` 才会真的从界面消失
     */
    private fun resetPerSessionState() {
        // 待发送的东西一律不跨会话
        attachments.clear()
        refreshAttachmentBar()
        queued.clear()
        refreshQueuedStrip()
        steeringInbox.clear()

        // 任务清单：上一个会话的进度不该出现在新会话里
        lastTasks = emptyList()
        renderTasks(emptyList())

        // 产物：按新会话 id 重新载入（**调用前必须先更新 currentSessionId**）
        artifacts.clear()
        loadArtifacts()
        artifactsVisible = false
        artifactsPanel.isVisible = false
        updateArtifactsButton()

        // 一轮对话内的临时状态
        workspaceDir = null          // 下一个会话有自己的产物目录，别复用
        regenVariants = emptyList()
        liveBubble = null
        trimmedRows = 0
        welcomePage = null
        setupCardSlot = null
        historyCursor = -1
        historyDraft = ""
        followState = ScrollFollow.State()
        cancelRequested = false
        cancelFlag.set(false)
        setPhase(Phase.IDLE)
    }

    /**
     * 保存当前会话。
     *
     * 自动保存（[manual] = false）在新建会话与发送消息后触发；
     * 手动保存会给一句状态反馈，否则用户点了没动静会以为坏了。
     */
    private fun saveCurrentSession(manual: Boolean): Boolean {
        if (history.isEmpty()) {
            if (manual) updateStatus("当前会话还没有内容，不必保存")
            return false
        }
        val now = System.currentTimeMillis()
        val session = com.zhixueyao.chat.SessionStore.Session(
            id = currentSessionId,
            title = com.zhixueyao.chat.SessionStore.titleFrom(history),
            createdAt = currentSessionCreatedAt,
            updatedAt = now,
            // **先在 EDT 上取快照**（只是复制引用，很便宜），
            // 后台线程再去序列化 —— 否则后台写的时候历史可能又变了
            messages = history.toList()
        )

        // 落盘是**磁盘 IO + 整个会话的 JSON 序列化**，长会话带图片能到几 MB。
        // 放在 EDT 上就是实打实的界面卡顿（每轮结束都存一次，卡得很规律）。
        // 所有调用方都不关心返回值，所以直接丢后台。
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = com.zhixueyao.chat.SessionStore.save(session)
            edt {
                if (ok) savedMessageCount = session.messages.size
                if (manual) {
                    updateStatus(
                        if (ok) "已保存：${session.title}"
                        else "保存失败，请检查 IDE 配置目录是否可写"
                    )
                }
            }
        }
        return true
    }

    /**
     * 载入一个历史会话。
     *
     * 载入时把消息列表重建成气泡。工具调用消息（TOOL 角色）跳过不显示 ——
     * 它们是给模型看的中间结果，界面上显示一串原始 JSON 只会干扰阅读；
     * 但它们**保留在 history 里**，否则模型会失去上下文。
     */
    /**
     * 正在载入哪个会话 —— 用来挡一个竞态。
     *
     * 用户在读盘期间又点了另一条时，两个后台任务回来的**顺序不保证**，
     * 不挡的话可能把新选的那条覆盖成旧的。
     */
    private var loadingTargetId: String? = null

    /**
     * 异步载入会话：**读文件在后台，重建界面在 EDT**。
     *
     * ## 这是在「EDT 上的阻塞 I/O」扫描里查出来的
     *
     * 原来点一下会话是这么走的：
     *
     * ```
     * onClick（Swing 回调 = EDT）
     *   └─ SessionStore.load(id)     同步读 JSON
     *   └─ 重建所有气泡              长循环
     * ```
     *
     * **整段都压在 EDT 上。** 小会话几毫秒感觉不出来，
     * 但会话会随使用时间变大 —— 几百条消息加附件之后，点一下就是肉眼可见的一卡。
     *
     * 用户还没报过，因为他的会话还不够大。**这类 bug 是长出来的** ——
     * 等它自己暴露时，用户已经卡惯了。
     *
     * ## 拆开的界线
     *
     * 不是「哪边方便」，而是 **「碰不碰 Swing 组件」**：
     * 读写文件在后台，动组件必须回 EDT（Swing 只在 EDT 上安全）。
     */
    private fun openSessionAsync(id: String) {
        if (isRunning) {
            Messages.showInfoMessage(project, "正在生成中，请先等待结束或点击停止", "止血药")
            return
        }
        updateStatus("正在载入会话…")
        loadingTargetId = id
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // 只有这一步能在后台：读文件。异常也在这里挡掉，
            // 否则后台一抛，下面那句 invokeLater 就永远不执行 —— 界面停在「正在载入会话…」
            val loaded = try {
                com.zhixueyao.chat.SessionStore.load(id)
            } catch (t: Throwable) {
                null
            }
            com.zhixueyao.ui.UiKit.ui {
                if (loaded == null) {
                    updateStatus("这个会话读不出来了，可能已被删除")
                } else {
                    renderSession(loaded)
                }
            }
        }
    }

    /** 把读好的会话铺到界面上。**必须在 EDT 上跑。** */
    private fun renderSession(session: com.zhixueyao.chat.SessionStore.Session) {
        // 读盘期间用户又点了别的 → 这次的结果已经过时，丢掉
        if (loadingTargetId != null && loadingTargetId != session.id) return
        loadingTargetId = session.id

        // 历史被改写/换会话：摘要必须跟着作废，否则新一段对话会「记得」没发生过的事
        summaryState.reset()
        history.clear()
        history.addAll(session.messages)
        currentSessionId = session.id
        // 先清干净再渲染：否则上一个会话的附件 / 任务清单 / 产物会留在界面上
        resetPerSessionState()

        // **走统一的渲染函数**，不要在这里再抄一份。
        //
        // 这里原来是一份**重复的渲染循环**，结果每次给气泡加东西（版本切换器、
        // token 页脚）都只改了 renderHistory，忘了这一份 —— 载入会话后那些就都不显示。
        // 两份长得几乎一样的渲染代码，迟早会有一份落后。
        renderHistory()
        restoreDraft()
        currentSessionCreatedAt = session.createdAt
        savedMessageCount = session.messages.size
        updateStatus("已载入「${session.title}」，共 ${session.messageCount} 条消息")

        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollToBottom()
    }

    private fun reconnectMcp() {
        val registry = McpServerRegistry.getInstance()
        if (registry.servers.isEmpty()) {
            val chosen = Messages.showDialog(
                project,
                "还没有配置 MCP 服务器。\n\nMCP 能让 AI 调用外部工具（浏览器、数据库、文件系统等）。现在去配置吗？",
                "止血药 · MCP",
                arrayOf("打开设置", "取消"),
                0,
                AllIcons.General.GearPlain
            )
            if (chosen == 0) openSettings(section = "plugins")
            return
        }
        updateStatus("正在连接 MCP 服务器…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val failures = mcpManager.connectAll(registry.servers)
            edt {
                refreshHeader()
                if (failures.isEmpty()) {
                    val count = runCatching { mcpManager.allTools().size }.getOrDefault(0)
                    updateStatus("MCP 已连接，加载 $count 个工具")
                    Messages.showInfoMessage(project, "MCP 连接成功，共加载 $count 个工具", "止血药")
                } else {
                    updateStatus("MCP 部分连接失败（${failures.size} 个）")
                    val detail = failures.entries.joinToString("\n\n") { "${it.key}：\n${it.value}" }
                    Messages.showWarningDialog(project, "部分 MCP 服务器连接失败：\n\n$detail", "止血药")
                }
            }
        }
    }

    /**
     * 供外部（选中代码提问等）注入一段内容到输入框。
     *
     * 会顺带更新状态行 —— 用户从编辑器菜单触发后，视线在工具窗口上，
     * 得让他立刻知道「内容已经进来了，接下来按 Enter」。
     */
    fun prefill(text: String, autoSend: Boolean = false) {
        inputArea.text = text
        inputArea.caretPosition = text.length
        inputArea.requestFocusInWindow()
        inputArea.repaint()
        if (autoSend) {
            onSend()
        } else {
            updateStatus("已放入输入框，补充你想问的问题后按 Enter 发送")
        }
    }

    /** 供外部动作调用的重新生成入口。 */
    fun regenerateLast() = regenerate()

    /** 供外部动作调用的清空入口。 */
    fun clearChat() = clearHistory()

    /** 断开所有 MCP 连接，释放子进程。 */
    override fun dispose() {
        cancelFlag.set(true)
        // 放掉还在等用户点击的后台线程：界面都没了，再等下去只是白等
        pendingChoices.forEach { it.countDown() }
        pendingChoices.clear()
        // 关窗口时把草稿落盘（防抖定时器可能还没到点）
        draftSaveTimer.stop()
        com.zhixueyao.chat.SessionStore.saveDraft(currentSessionId, inputArea.text)
        // 注销界面桥：工具窗口关了之后，工具侧再请求授权会按「拒绝」处理（安全默认）
        AgentUiBridge.unregister(project)
        mcpManager.disconnectAll()
    }

    /** 本次会话主题：取第一条用户消息的首行（与会话列表的标题口径一致） */
    private fun currentSessionTitle(): String {
        val first = history.firstOrNull { it.role == ChatMessage.Role.USER }?.content?.trim().orEmpty()
        return if (first.isEmpty()) "" else first.lineSequence().first().take(28)
    }

    /**
     * 流式输出时的自动跟随：**只有已经贴着底部才跟**。
     *
     * 与 [scrollToBottom] 的区别：那个是无条件回到底部（用户主动发消息、点浮层时用），
     * 这个是「跟随模式」—— 用户往上翻看历史时不打扰他。
     */
    /**
     * 流式期间自动跟随到底部 —— **但用户一旦往上翻就立刻停止跟随**。
     *
     * 这里踩过两次坑，都是「用距离阈值猜用户意图」造成的：
     *
     * 1. 先判后排队：排队到执行之间用户可能已经上滑，旧结论把他拽回底部。
     * 2. 只按「距底部 48px 以内就跟」：滚轮一格大约 30~50px，
     *    用户往上滚一格**仍在阈值内**，于是下一个增量又把他拽回底部 ——
     *    表现就是「上下滑动卡住、滑不动」（用户反馈）。
     *
     * 正解是**记住我们上次把滚动条放哪儿**：现在的位置明显高于那个位置，
     * 就说明是用户在往上翻 → 关掉跟随；他自己滚回底部 → 再打开。
     * 这样不依赖任何距离阈值，也不需要在事件里分辨「是用户还是程序在滚」。
     */
    private fun autoScroll() {
        SwingUtilities.invokeLater {
            val bar = messagesScroll.verticalScrollBar
            // 判定抽在 ScrollFollow 里（纯函数，可离线验证）
            val d = ScrollFollow.decide(followState, bar.value, bar.maximum, bar.visibleAmount)
            followState = d.state
            d.scrollTo?.let { bar.value = it }
        }
    }

    /** 跟随状态（见 [ScrollFollow]） */
    private var followState = ScrollFollow.State()

    /**
     * 把「用户主动滚动」这件事挂到**真实事件**上。
     *
     * 不能用「value 变小了」来判断 —— 内容增长时 maximum 变大而 value 不变，
     * 和「用户往上滚」在数值上长得一样，会把跟随误关掉。
     * 滚轮 / 拖动滚动条这两类事件才是可靠信号。
     */
    private fun installScrollIntentTracking() {
        messagesScroll.addMouseWheelListener {
            followState = ScrollFollow.onUserScroll(followState)
        }
        messagesScroll.verticalScrollBar.addAdjustmentListener { e ->
            // valueIsAdjusting = 用户正拖着滚动条
            if (e.valueIsAdjusting) followState = ScrollFollow.onUserScroll(followState)
        }
    }

    private fun scrollToBottom() {
        // 主动跳到底部的场景（切会话、清空、载入历史），理应恢复跟随
        followState = ScrollFollow.State()
        SwingUtilities.invokeLater {
            val bar = messagesScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun edt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
