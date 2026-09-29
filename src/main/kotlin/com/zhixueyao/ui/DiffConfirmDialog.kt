package com.zhixueyao.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 应用改动前的差异确认对话框。
 *
 * 左侧为文件当前内容，右侧为 AI 给出的新内容，用户确认后才写入。
 * 写入走 [CodeBlockSupport.applyToFile]，因此依然可以用 Ctrl+Z 撤销。
 *
 * **顶部那行摘要很关键**：以前打开就是两栏全文，改动在哪一行得自己翻
 * （用户反馈「改动都不知道跳到指定位置，还要自己滑」）。
 * 现在直接写「从第 X 行开始有改动：旧 A 行 → 新 B 行」。
 */
class DiffConfirmDialog(
    private val project: Project,
    private val path: String,
    private val oldText: String,
    private val newText: String
) : DialogWrapper(project, false) {

    /** 摘要 + 片段警告，由 createCenterPanel 算好后写进去 */
    private val summary = DiffSummary.of(oldText, newText)
    private val fragment = !DiffSummary.looksLikeWholeFile(newText, oldText)

    init {
        title = "确认改动 · $path"
        // 片段场景把按钮文案改掉：让用户明确知道自己在做「整体替换」，
        // 而不是以为「只是应用那几行改动」
        setOKButtonText(if (fragment) "仍然整体替换" else "应用改动")
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(oldText)
        val right = factory.create(newText)

        val request = SimpleDiffRequest(
            path,
            left,
            right,
            "当前内容",
            "AI 建议内容"
        )

        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, window)
        panel.setRequest(request)

        val wrapper = JPanel(BorderLayout())

        // 顶部摘要：改动从第几行开始、动了多少行
        val head = JPanel(BorderLayout())
        head.border = javax.swing.BorderFactory.createEmptyBorder(0, 2, 4, 2)
        val summaryLabel = JLabel(summary.render())
        summaryLabel.font = summaryLabel.font.deriveFont(summaryLabel.font.size - 0.5f)
        head.add(summaryLabel, BorderLayout.WEST)
        wrapper.add(head, BorderLayout.NORTH)

        wrapper.add(panel.component, BorderLayout.CENTER)
        wrapper.preferredSize = Dimension(980, 620)

        val hint = JLabel(
            if (fragment) {
                // 这里必须说得很直白：应用改动是**整文件替换**，
                // 拿片段去替换会把整个文件换成片段（右边那些行就是替换后的全部内容）
                "⚠ 右侧看起来只是**片段**（${newText.count { it == '\n' } + 1} 行），" +
                    "而应用改动会把整个文件（${oldText.count { it == '\n' } + 1} 行）替换成它。" +
                    "想只改这几行，请让 AI 用 edit_file 精确修改。"
            } else {
                "确认后写入文件，可用 Ctrl+Z 撤销。"
            }
        )
        if (fragment) hint.foreground = UiKit.warn
        hint.border = javax.swing.BorderFactory.createEmptyBorder(4, 2, 0, 0)
        wrapper.add(hint, BorderLayout.SOUTH)

        return wrapper
    }

    /** 供调用方读取当前文件内容失败时使用：直接弹出纯文本预览。 */
    companion object {
        /** 返回 true 表示用户确认应用。 */
        fun confirm(project: Project, path: String, oldText: String, newText: String): Boolean {
            val dialog = DiffConfirmDialog(project, path, oldText, newText)
            return dialog.showAndGet()
        }
    }
}
