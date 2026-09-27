package com.zhixueyao.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * 带「下次不再提醒」复选框的说明/确认框。
 *
 * 为什么不用平台自带的 `Messages.showCheckboxMessageDialog`：它最后一个参数是
 * `PairFunction<Integer, JCheckBox, Integer>`，在 Kotlin 侧写起来别扭且不好读；
 * 而这里只需要「一段说明 + 一个复选框 + 一个按钮」，自己包一个 DialogWrapper
 * 反而更短、语义更清楚。
 *
 * 用法：`NoticeDialog.show(title, message, "下次不再提醒")` → `(是否确认, 是否勾选)`。
 */
class NoticeDialog(
    private val dialogTitle: String,
    private val message: String,
    private val checkboxText: String,
    private val okText: String = "知道了"
) : DialogWrapper(true) {

    private val checkbox = JCheckBox(checkboxText)

    /** 用户是否勾选了「下次不再提醒」 */
    var checked: Boolean = false
        private set

    init {
        title = dialogTitle
        isResizable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(4, 2)
        }
        // 说明文案是多行的，必须转成 <br> 才能真的换行；同时转义 & < >，
        // 否则路径里出现 < 会把 HTML 结构冲掉
        val html = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        panel.add(JBLabel("<html><body style='width:430px'>$html</body></html>"), BorderLayout.CENTER)
        panel.add(checkbox, BorderLayout.SOUTH)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapper.DialogWrapperAction(okText) {
            override fun doAction(e: ActionEvent?) {
                checked = checkbox.isSelected
                close(OK_EXIT_CODE)
            }
        }
    )

    companion object {
        /**
         * 弹一个说明框。
         *
         * @return `first` = 用户点了确认；`second` = 勾选了「下次不再提醒」
         */
        fun show(title: String, message: String, checkboxText: String, okText: String = "知道了"): Pair<Boolean, Boolean> {
            val dialog = NoticeDialog(title, message, checkboxText, okText)
            val confirmed = dialog.showAndGet()
            return confirmed to dialog.checked
        }
    }
}
