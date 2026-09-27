package com.zhixueyao.settings

import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.zhixueyao.agent.SessionWorkspace
import com.zhixueyao.ui.UiKit
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * 「临时会话管理」页。
 *
 * ## 用户要的是什么
 *
 * 原话：「还需要在设置里面添加一个全局临时会话管理的功能，用来清理需要清理的临时会话缓存之类的，
 * 必须要列出表来，且可以全选或者单选删除等」。
 *
 * 所以这张表必须做到：**看得见**（会话名 / 时间 / 文件数 / 占多少空间）、
 * **能选**（单选、全选、全不选）、**能删**（删选中，删前确认）。
 *
 * ## 为什么要有这个页面
 *
 * 会话的临时产物现在都堆在 `~/.zhixueyao/Conversation/Product/` 下面
 * （每个会话一个目录，见 [SessionWorkspace]）。那是**全局**目录、不在任何工程里，
 * 用户平时根本看不到它 —— 于是它会一直涨，直到把磁盘吃满也没人知道。
 * 给一个能看能删的入口，是这个设计能不能长期成立的前提。
 */
class TempSessionsPanel {

    private val model = TempSessionTableModel()
    private val table = JBTable(model)

    /** 合计占用，删完要刷新 */
    private val totalLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = UiKit.faint
    }

    fun build(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(14, 16)
        }

        // 说明 + 合计：放最上面
        val header = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(
                UiKit.hint(
                    "会话的临时产物目录（全局，不在工程里）：试验性的图、草稿、导出物都存在这，" +
                        "可以随时删掉。工程里只保留 App 真正要用的资源"
                ),
                BorderLayout.NORTH
            )
            add(totalLabel, BorderLayout.SOUTH)
        }

        // **表格限高 + 工具栏紧跟其下**。
        //
        // 之前把表格放在 CENTER，它会把整页剩余高度全吃掉 ——
        // 结果「删除选中」这些按钮被顶到屏幕外，用户根本看不到（用户反馈：
        // 「怎么没看到删除按钮，可能是你的列表框不是一个可滑动的框框，所以导致按钮都在很下面」）。
        // 放到 NORTH 并按固定高度排布，无论表里几行，按钮永远在表格正下方。
        panel.add(
            com.zhixueyao.ui.TableSection.panel(
                table = table,
                toolbar = buildToolbar(),
                header = header
            ),
            BorderLayout.NORTH
        )

        table.setShowGrid(false)
        table.rowHeight = 26
        table.emptyText.text = "还没有临时产物（用过 generate_svg / save_asset 之后才会出现）"
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        // 列宽：勾选窄，路径宽
        table.columnModel.getColumn(0).preferredWidth = 52
        table.columnModel.getColumn(1).preferredWidth = 200
        table.columnModel.getColumn(2).preferredWidth = 130
        table.columnModel.getColumn(3).preferredWidth = 70
        table.columnModel.getColumn(4).preferredWidth = 80
        table.columnModel.getColumn(5).preferredWidth = 320
        // 双击 = 在系统文件管理器里打开那个目录（用户想自己看看是什么）
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) openInExplorer(model.entryAt(row).dir.absolutePath)
                }
            }
        })

        refresh()
        return panel
    }

    private fun buildToolbar(): JComponent = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(smallButton("全选") { model.setAllSelected(true) })
        add(smallButton("全不选") { model.setAllSelected(false) })
        add(smallButton("刷新") { refresh() })
        add(smallButton("删除选中", danger = true) { deleteSelected() })
        add(javax.swing.Box.createHorizontalStrut(8))
        add(smallButton("打开总目录") { openInExplorer(SessionWorkspace.root().absolutePath) })
    }

    private fun smallButton(label: String, danger: Boolean = false, onClick: () -> Unit): JButton =
        JButton(label).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusable = false
            putClientProperty("JButton.buttonType", "roundRect")
            if (danger) foreground = UiKit.danger
            addActionListener { onClick() }
        }

    private fun refresh() {
        model.setData(SessionWorkspace.list())
        val total = model.totalBytes()
        val dir = SessionWorkspace.root()
        totalLabel.text = buildString {
            append("共 ").append(model.rowCount).append(" 个会话目录，合计 ")
            append(if (total >= 1024L * 1024) "%.1f MB".format(total / 1024.0 / 1024.0) else "%.0f KB".format(total / 1024.0))
            append("　·　").append(dir.absolutePath)
        }
    }

    /**
     * 删除选中的会话目录。
     *
     * **必须二次确认，且把要删的东西列出来** —— 这是真删磁盘文件，
     * 而且删的是用户可能还没看过的东西（它们不在工程里，平时看不到）。
     * 只说「确定删除吗」是不够的，用户得知道删的是什么、多少量。
     */
    private fun deleteSelected() {
        val chosen = model.selectedEntries()
        if (chosen.isEmpty()) {
            Messages.showInfoMessage(table, "先勾选要删除的会话（或点「全选」）。", "临时会话管理")
            return
        }
        val bytes = chosen.sumOf { it.bytes }
        val detail = chosen.take(12).joinToString("\n") { "· ${it.title}　${it.timeText}　${it.sizeText}" } +
            if (chosen.size > 12) "\n… 另有 ${chosen.size - 12} 个" else ""
        val answer = Messages.showYesNoDialog(
            table,
            "将删除 ${chosen.size} 个会话的临时产物，共 ${
                if (bytes >= 1024L * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0) else "%.0f KB".format(bytes / 1024.0)
            }：\n\n$detail\n\n" +
                "这些是会话的临时产物（试验性产出、草稿、导出物），删掉不影响工程源码。\n" +
                "确定删除？",
            "删除临时产物",
            Messages.getWarningIcon()
        )
        if (answer != Messages.YES) return

        var ok = 0
        val failed = mutableListOf<String>()
        chosen.forEach { if (SessionWorkspace.delete(it)) ok++ else failed.add(it.title) }
        val pruned = SessionWorkspace.pruneEmptyBuckets()
        refresh()
        Messages.showInfoMessage(
            table,
            buildString {
                append("已删除 $ok 个会话目录")
                if (pruned > 0) append("，并清掉 $pruned 个空的时间分组")
                if (failed.isNotEmpty()) append("\n\n以下删不掉（可能被占用）：\n").append(failed.joinToString("\n"))
            },
            "临时会话管理"
        )
    }

    private fun openInExplorer(path: String) {
        runCatching {
            val f = java.io.File(path)
            if (!f.exists()) {
                Messages.showInfoMessage(table, "目录不存在：$path", "临时会话管理")
                return
            }
            java.awt.Desktop.getDesktop().open(f)
        }.onFailure {
            Messages.showWarningDialog(table, "打不开：${it.message}", "临时会话管理")
        }
    }
}

/** 临时会话表：勾选列 + 会话名 / 时间 / 文件数 / 大小 / 路径 */
class TempSessionTableModel : AbstractTableModel() {

    private val rows = mutableListOf<SessionWorkspace.Entry>()
    private val checked = mutableSetOf<String>()
    private val columns = arrayOf("选择", "会话", "创建时间", "文件", "大小", "路径")

    fun setData(list: List<SessionWorkspace.Entry>) {
        rows.clear()
        rows.addAll(list)
        // 刷新时丢掉已经不存在的勾选，避免「删完了还显示勾着」
        checked.retainAll(rows.map { it.path }.toSet())
        fireTableDataChanged()
    }

    fun entryAt(row: Int): SessionWorkspace.Entry = rows[row]

    fun totalBytes(): Long = rows.sumOf { it.bytes }

    fun selectedEntries(): List<SessionWorkspace.Entry> = rows.filter { it.path in checked }

    fun setAllSelected(selected: Boolean) {
        if (selected) rows.forEach { checked.add(it.path) } else checked.clear()
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            0 -> r.path in checked
            1 -> r.title
            2 -> r.timeText
            3 -> r.fileCount.toString()
            4 -> r.sizeText
            else -> r.path
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 0) return
        if (value == true) checked.add(rows[rowIndex].path) else checked.remove(rows[rowIndex].path)
        fireTableRowsUpdated(rowIndex, rowIndex)
    }
}