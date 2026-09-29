package com.zhixueyao.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 工具窗口工厂。
 * 每个项目持有独立的 [ChatPanel]，项目关闭时一并释放（含 MCP 子进程）。
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // content 释放时会级联 dispose(panel)，进而断开 MCP 子进程
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        installTitleActions(toolWindow)
    }

    /**
     * 把「重新生成 / 清空对话 / 设置」挂到工具窗口标题栏。
     *
     * 用代码而不是 plugin.xml 的扩展点：`ToolWindow.setTitleActions` 是稳定 API，
     * 而标题栏扩展点的注册格式在各平台版本间有变动，直接调 API 更可靠。
     */
    private fun installTitleActions(toolWindow: ToolWindow) {
        val manager = ActionManager.getInstance()
        val actions = mutableListOf<AnAction>()
        for (id in listOf("Zhixueyao.Regenerate", "Zhixueyao.ClearChat", "Zhixueyao.OpenSettings")) {
            manager.getAction(id)?.let { actions.add(it) }
        }
        if (actions.isEmpty()) return

        // 三个动作之间插一个分隔线，避免和平台自己的标题栏按钮糊成一团
        val withSeparator = mutableListOf<AnAction>()
        actions.forEachIndexed { index, action ->
            if (index > 0) withSeparator.add(Separator.getInstance())
            withSeparator.add(action)
        }

        runCatching { toolWindow.setTitleActions(withSeparator) }
    }

    companion object {
        /** 取出指定项目的止血药面板，供动作类调用。 */
        fun panelOf(project: Project): ChatPanel? {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager
                .getInstance(project)
                .getToolWindow("止血药") ?: return null
            return toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? ChatPanel }
        }

        /** 激活工具窗口并返回面板。 */
        fun openPanel(project: Project): ChatPanel? {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager
                .getInstance(project)
                .getToolWindow("止血药") ?: return null
            toolWindow.show()
            return panelOf(project)
        }
    }
}
