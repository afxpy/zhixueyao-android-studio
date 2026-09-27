package com.zhixueyao.agent

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具 ↔ 界面 的桥。
 *
 * 为什么需要这一层：工具跑在后台线程，而下面这些都是**界面的事**：
 *  - 这次越界访问要不要放行（需要弹选项，还要阻塞等用户点）
 *  - 任务清单长什么样
 *  - AI 想请用户在几个选项里挑一个
 *  - 这次写文件产出的是什么（产物清单）
 *
 * 工具不该直接摸 ChatPanel（那是 UI 实现），也不该自己弹窗口。于是中间放一层：
 * 工具侧调用（可阻塞），界面侧注册处理器。
 *
 * 用 `ConcurrentHashMap<Project, Handler>` 而不是平台 service：与
 * `McpServerController` 同一路子 —— 不需要在 plugin.xml 注册，
 * 也就不会踩「注解不等于注册」那个坑。工具窗口没打开时没有处理器，
 * 此时**一律按拒绝处理**（安全默认：没人看着就不放行）。
 */
object AgentUiBridge {

    private val handlers = ConcurrentHashMap<Project, Handler>()

    /** 界面侧要实现的四件事 */
    interface Handler {
        /** 请求放行。实现方应当在界面上给出选项并**阻塞**到用户选择 */
        fun requestApproval(request: ApprovalRequest): ApprovalDecision

        /** 更新任务清单 */
        fun updateTasks(tasks: List<TaskItem>)

        /** 让用户在若干选项里选一个 */
        fun askUser(question: String, options: List<String>): AskResult

        /** 记录一个产物（被写入/修改的文件） */
        fun recordArtifact(path: String)
    }

    fun register(project: Project, handler: Handler) {
        handlers[project] = handler
    }

    fun unregister(project: Project) {
        handlers.remove(project)
    }

    fun requestApproval(project: Project, request: ApprovalRequest): ApprovalDecision =
        handlers[project]?.requestApproval(request) ?: ApprovalDecision.DENY

    fun updateTasks(project: Project, tasks: List<TaskItem>) {
        handlers[project]?.updateTasks(tasks)
    }

    fun askUser(project: Project, question: String, options: List<String>): AskResult =
        handlers[project]?.askUser(question, options) ?: AskResult.NoAnswer

    fun recordArtifact(project: Project, path: String) {
        handlers[project]?.recordArtifact(path)
    }
}

/**
 * 「让用户选一个」的结果。
 *
 * **必须把「用户明确取消」和「没等到人」分开** —— 它们的语义是相反的：
 *  - [Cancelled]：用户看过、并且**明确否掉了**。必须停，不能再自己拿主意。
 *  - [NoAnswer]：没人看（窗口关了 / 超时）。这时才可以让模型按最稳妥的假设继续。
 *
 * 以前两者都返回 null，工具统一按「没选」处理并告诉模型「按你认为最稳妥的方案继续」——
 * 于是用户点了取消，AI 反而**照做了**（用户反馈：「居然点击取消还执行了」）。
 */
sealed interface AskResult {
    /** 用户选了某一项 */
    data class Picked(val value: String) : AskResult

    /** 用户明确点了取消：不要执行任何操作 */
    data object Cancelled : AskResult

    /** 没等到（超时 / 窗口已关）：可以按最稳妥的假设继续 */
    data object NoAnswer : AskResult
}

/**
 * 任务清单的一项。
 *
 * [status] 取 `pending` / `in_progress` / `done` —— 用字符串而不是枚举，
 * 是因为它来自模型返回的 JSON，非法值需要能容错成 pending 而不是崩掉。
 */
data class TaskItem(val title: String, val status: String) {
    val isDone: Boolean get() = status == "done"
    val isActive: Boolean get() = status == "in_progress"
}
