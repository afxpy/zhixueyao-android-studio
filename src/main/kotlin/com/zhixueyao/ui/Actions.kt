package com.zhixueyao.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor

/**
 * 用选中的代码提问。
 *
 * 关键设计：只把选中的片段和文件名放进输入框，不自动发送 ——
 * 让用户补充具体想问什么，避免 AI 面对一段无上下文的代码瞎猜。
 */
class AskSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selected = getSelectedCode(editor)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "(当前文件)"

        val panel = ChatToolWindowFactory.openPanel(project) ?: return

        val prefill = buildString {
            append("请帮我看看这段代码")
            append("（来自 ").append(fileName).append("）：\n\n")
            append("```\n")
            append(selected)
            append("\n```\n\n")
        }
        panel.prefill(prefill)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            e.project != null && editor != null && editor.selectionModel.hasSelection()
    }

    private fun getSelectedCode(editor: Editor): String {
        val selection = editor.selectionModel
        return selection.selectedText?.replace('\u2028', '\n') ?: ""
    }
}

/** 从菜单打开对话窗口。 */
class OpenChatAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatToolWindowFactory.openPanel(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * 重新生成上一条回答。
 *
 * 挂在工具窗口标题栏，不用回到聊天面板里去找小链接。
 */
class RegenerateAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatToolWindowFactory.openPanel(project)?.regenerateLast()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/** 清空对话历史。 */
class ClearChatAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatToolWindowFactory.openPanel(project)?.clearChat()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/** 打开止血药设置对话框。 */
class OpenSettingsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatToolWindowFactory.openPanel(project)?.openSettings()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
