package com.zhixueyao.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.zhixueyao.llm.ModelCatalog
import com.zhixueyao.llm.ModelListResult
import com.zhixueyao.llm.RemoteModel
import com.zhixueyao.ui.UiKit
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 模型选择器：从服务商拉取可用模型，可单个「使用」或批量「加入模型列表」。
 *
 * 交互设计取向（对齐 ccswitch）：不让用户先看文档再手打模型名。
 * 打开即拉取、边拉边显示、支持关键字过滤、双击即选中。
 *
 * 四个细节是刻意做的：
 *  1. 先弹窗后拉取 —— 网络请求可能要几秒，不能让界面卡住不响应，
 *     所以对话框立刻出现并显示「正在拉取」，结果到了再回填。
 *  2. 默认只显示对话模型 —— 一个服务商常返回几百个模型，大半是向量化 / 语音 / 绘图，
 *     对聊天无用。过滤是黑名单式的（拿不准的保留），且可一键取消。
 *  3. 保留搜索框 —— 即便过滤后剩几十个，直接打字比滚列表快得多。
 *  4. **多选** —— 一家服务商往往要长期用好几个模型（日常用快的、难题用强的），
 *     一次只能加一个会导致反复开关这个窗口。所以支持多选后批量加入。
 */
class ModelPickerDialog private constructor(
    parent: java.awt.Frame?,
    private val baseUrl: String,
    private val apiKey: String,
    private val format: String,
    private val currentModel: String,
    /** 该服务商已添加过的模型，列表里会标出来，避免重复添加 */
    private val alreadyAdded: List<String>
) {

    private val dialog = createDialog(parent)
    private val searchField = JBTextField()
    private val chatOnlyCheck = JCheckBox("只看对话模型", true)
    private val statusLabel = JBLabel()
    private val listModel = DefaultListModel<RemoteModel>()
    private val list = JList(listModel)
    private val addBtn = UiKit.primaryButton("加入模型列表") { acceptAdd() }
    private val useBtn = UiKit.textButton("使用此模型") { acceptUse() }

    /** 全选 / 取消全选，批量加模型时省得一个个点 */
    private val selectAllCheck = JCheckBox("全选")

    /** 列表区用卡片切换：有结果时显示列表，无结果时显示一块说明 */
    private val listCards = java.awt.CardLayout()
    private val listArea = JBPanel<JBPanel<*>>(listCards)
    private val emptyLabel = JBLabel()

    private var allModels: List<RemoteModel> = emptyList()
    private var result: PickerResult? = null

    init {
        buildUi()
        load()
    }

    private fun buildUi() {
        val root = JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(12)
        }

        // ---- 顶部：搜索 + 过滤 ----
        val top = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply { isOpaque = false }

        searchField.emptyText.text = "输入关键字过滤，例如 deepseek、qwen、glm"
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                // 输入即过滤。用 invokeLater 是因为 keyTyped 时 text 还没更新
                javax.swing.SwingUtilities.invokeLater { applyFilter() }
            }

            override fun keyPressed(e: KeyEvent) {
                // 上下键在搜索框里也能移动列表选择，不用先按 Tab 切过去
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        moveSelection(1); e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        moveSelection(-1); e.consume()
                    }
                }
            }
        })
        top.add(searchField, BorderLayout.NORTH)

        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        chatOnlyCheck.addActionListener { applyFilter() }
        filterRow.add(chatOnlyCheck)
        // 全选/取消全选：一次加十几个模型时，一个个点太累
        selectAllCheck.addActionListener {
            if (selectAllCheck.isSelected) list.setSelectionInterval(0, listModel.size() - 1)
            else list.clearSelection()
            refreshButtons()
        }
        filterRow.add(selectAllCheck)
        filterRow.add(UiKit.textButton("重新拉取") { load() })
        top.add(filterRow, BorderLayout.CENTER)

        statusLabel.font = statusLabel.font.deriveFont(statusLabel.font.size - 1f)
        statusLabel.foreground = UiKit.faint
        top.add(statusLabel, BorderLayout.SOUTH)

        root.add(top, BorderLayout.NORTH)

        // ---- 列表 ----
        // 多选：一家服务商常要一次加好几个模型
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = ModelRenderer()
        list.addListSelectionListener { refreshButtons() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 双击 = 直接用这一个模型（最常用的动作）
                if (e.clickCount >= 2) acceptUse()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    // 只选了一个就「使用」，多个则「加入列表」—— 按选择数推断意图
                    if (list.selectedIndices.size > 1) acceptAdd() else acceptUse()
                }
            }
        })

        // CardLayout 的尺寸取「所有子卡的最大值」，所以尺寸必须挂在容器上，
        // 而不是分别写在两张卡里 —— 否则会被另一张卡撑大或压扁。
        listArea.preferredSize = Dimension(560, 380)
        root.add(listArea, BorderLayout.CENTER)

        listArea.add(
            JBScrollPane(list).apply {
                border = UiKit.cardBorder(UiKit.border, UiKit.radiusSmall)
            },
            CARD_LIST
        )

        // 无结果 / 拉取失败时显示这块，比往列表里塞一行假数据干净
        emptyLabel.horizontalAlignment = javax.swing.SwingConstants.CENTER
        emptyLabel.verticalAlignment = javax.swing.SwingConstants.CENTER
        emptyLabel.foreground = UiKit.subtle
        listArea.add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(emptyLabel, BorderLayout.CENTER)
            },
            CARD_EMPTY
        )

        // ---- 底部按钮 ----
        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        btnRow.add(UiKit.textButton("取消") { dialog.dispose() })
        btnRow.add(useBtn)
        btnRow.add(addBtn)
        root.add(btnRow, BorderLayout.SOUTH)

        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(root, BorderLayout.CENTER)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        // 起始显示「等待拉取」，避免对话框刚出现时列表区一片空白
        showEmpty("<html><span style='color:#8C959F'>正在拉取模型列表…</span></html>")
        refreshButtons()

        dialog.pack()
        dialog.setLocationRelativeTo(null)
    }

    /**
     * 按当前选择量刷新按钮文案与可用性。
     *
     * 选 0 个两个按钮都禁用（比点了没反应友好）；选 1 个时
     * 「使用此模型」才有意义，多选时它自动变成「使用首个」——
     * 多选本来就不是「使用」的语义，所以直接禁用更不容易误解。
     */
    private fun refreshButtons() {
        val n = list.selectedIndices.size
        addBtn.text = if (n > 1) "加入列表（$n 个）" else "加入模型列表"
        addBtn.isEnabled = n > 0
        useBtn.isEnabled = n == 1
        useBtn.toolTipText = if (n > 1) {
            "多选时不能「使用」—— 请点右侧「加入列表」，或只选中一个"
        } else {
            "立即切换到这个模型"
        }
        // defaultButton 跟随当前可用的主操作，回车才符合直觉
        dialog.rootPane.defaultButton = if (n > 1) addBtn else useBtn
    }

    private fun moveSelection(delta: Int) {
        if (listModel.size() == 0) return
        val next = (list.selectedIndex + delta).coerceIn(0, listModel.size() - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    /** 在后台线程拉取，结果回主线程填列表。 */
    private fun load() {
        listModel.clear()
        allModels = emptyList()
        statusLabel.foreground = UiKit.faint
        statusLabel.text = "正在从 $baseUrl 拉取模型列表…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val r = ModelCatalog.fetch(baseUrl, apiKey, format)
            javax.swing.SwingUtilities.invokeLater {
                when (r) {
                    is ModelListResult.Ok -> {
                        allModels = r.models
                        statusLabel.foreground = UiKit.faint
                        statusLabel.text = "共 ${r.models.size} 个模型"
                        applyFilter()
                    }

                    is ModelListResult.Err -> {
                        statusLabel.foreground = UiKit.danger
                        // 状态行只有一行，取结论那句即可；完整建议放到中间的空白卡片里，
                        // 那里空间足够把「结论 / 建议 / 原始信息」逐行铺开
                        statusLabel.text = "拉取失败：" + r.message.lineSequence().first().take(80)
                        showEmpty(
                            // 限定宽度：错误详情里常带一整段服务端原文，
                            // 不加宽度约束会把对话框撑得极宽
                            "<html><div style='text-align:center;width:520px'>" +
                                escape(r.message).replace("\n", "<br>") +
                                (if (r.detail.isBlank()) "" else "<br><br>" + escape(r.detail)) +
                                "<br><br><span style='color:#8C959F'>拉不到列表也不影响使用 ——<br>" +
                                "直接在设置页的「模型名称」框里手输模型名即可。</span></div></html>"
                        )
                    }
                }
            }
        }
    }

    private fun applyFilter() {
        val kw = searchField.text.trim().lowercase()
        val chatOnly = chatOnlyCheck.isSelected
        val filtered = allModels.filter { m ->
            (!chatOnly || ModelCatalog.looksLikeChatModel(m.id)) &&
                (kw.isEmpty() || m.id.lowercase().contains(kw) || m.label.lowercase().contains(kw))
        }

        listModel.clear()
        for (m in filtered) listModel.addElement(m)
        // 过滤后选择集失效了，勾选状态也跟着重置，否则「全选」会与实际不符
        selectAllCheck.isSelected = false

        // 当前正在用的模型默认选中，省一次寻找
        if (currentModel.isNotBlank()) {
            val idx = filtered.indexOfFirst { it.id == currentModel }
            if (idx >= 0) {
                list.selectedIndex = idx
                list.ensureIndexIsVisible(idx)
            }
        }
        if (filtered.isEmpty()) list.selectedIndex = -1
        refreshButtons()

        showList()
        if (filtered.isEmpty()) {
            if (allModels.isEmpty()) {
                showEmpty("没有拉到模型")
            } else {
                showEmpty("没有匹配「${escape(searchField.text.trim())}」的模型")
            }
        }

        if (allModels.isNotEmpty()) {
            statusLabel.foreground = UiKit.faint
            statusLabel.text = if (filtered.size == allModels.size) {
                "共 ${allModels.size} 个模型"
            } else {
                "匹配 ${filtered.size} 个 / 共 ${allModels.size} 个模型"
            }
        }
    }

    private fun showList() {
        listCards.show(listArea, CARD_LIST)
        // 列表区在两张卡之间切换，尺寸由当前显示的卡决定
        listArea.revalidate()
    }

    private fun showEmpty(html: String) {
        emptyLabel.text = html
        listCards.show(listArea, CARD_EMPTY)
        listArea.revalidate()
    }

    /** 选中的模型名（按列表顺序），无有效选择时为空 */
    private fun selectedNames(): List<String> =
        list.selectedIndices.toList()
            .filter { it in 0 until listModel.size() }
            .map { listModel.elementAt(it).id }
            .filter { it.isNotBlank() }

    /** 「使用此模型」：单个模型，立即生效 */
    private fun acceptUse() {
        val names = selectedNames()
        val one = names.singleOrNull() ?: return
        result = PickerResult.Use(one)
        dialog.dispose()
    }

    /** 「加入模型列表」：批量加进该服务商的清单，不改变当前正在用的模型 */
    private fun acceptAdd() {
        val names = selectedNames()
        if (names.isEmpty()) return
        result = PickerResult.Add(names)
        dialog.dispose()
    }

    /**
     * 列表项渲染：模型 id 用正文色，补充信息（上下文长度 / 归属）跟在后面用灰字。
     *
     * 之所以做自定义渲染而不是用默认的 toString：模型 id 是用户唯一关心的，
     * 后面那串元信息必须视觉上弱化，否则一屏几十行会花得看不清。
     *
     * 「已添加」的模型额外标一个灰勾 —— 让用户在勾选时就知道哪些已经在清单里了，
     * 不会重复添加，也一眼看出清单现状。
     */
    private inner class ModelRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val m = value as? RemoteModel ?: return this
            val meta = m.metaText().ifBlank { m.label }
            val added = m.id in alreadyAdded
            val mark = if (added) {
                "<span style='color:#1A7F37'>✓ 已添加</span>&nbsp;&nbsp;"
            } else {
                ""
            }
            text = if (meta.isBlank()) {
                "<html>$mark<span>${escape(m.id)}</span></html>"
            } else {
                "<html>$mark<span>${escape(m.id)}</span>" +
                    "&nbsp;&nbsp;<span style='color:#8C959F;font-size:9px'>${escape(meta)}</span></html>"
            }
            if (!isSelected) foreground = UiKit.text
            font = font.deriveFont(Font.PLAIN, font.size.toFloat())
            border = JBUI.Borders.empty(5, 8)
            return this
        }
    }

    /**
     * 用户在这个对话框里做出的选择。
     *
     * 用密封类而不是「返回 String? 外加一个 boolean 标志」：
     * 两种意图的载荷不同（单个 vs 多个），混在一个返回值里迟早用错。
     */
    sealed class PickerResult {
        /** 立即切换到这一个模型 */
        data class Use(val model: String) : PickerResult()

        /** 把这几个模型加入该服务商的清单，当前模型不变 */
        data class Add(val models: List<String>) : PickerResult()
    }

    companion object {
        private const val CARD_LIST = "list"
        private const val CARD_EMPTY = "empty"

        /** HTML 里展示用户/服务商提供的内容前必须转义，否则模型名里的 & < 会破坏布局 */
        private fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /**
         * 弹出选择器。返回用户的选择；取消返回 null。
         *
         * [alreadyAdded] 用来在列表里标出「已添加」，避免用户重复勾选。
         */
        fun pick(
            parent: java.awt.Frame?,
            baseUrl: String,
            apiKey: String,
            format: String,
            currentModel: String,
            alreadyAdded: List<String> = emptyList()
        ): PickerResult? {
            val d = ModelPickerDialog(parent, baseUrl, apiKey, format, currentModel, alreadyAdded)
            d.dialog.isVisible = true
            return d.result
        }
    }
}

/**
 * 创建对话框。
 *
 * 这个构造器接受 null 父窗口（退化为无主窗口的自由对话框），
 * 所以 IDE 主窗口取不到时也无需分支处理。
 */
private fun createDialog(parent: java.awt.Frame?): JDialog =
    JDialog(parent, "选择模型", true)
