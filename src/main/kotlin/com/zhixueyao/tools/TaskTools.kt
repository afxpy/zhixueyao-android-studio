package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.agent.AgentUiBridge
import com.zhixueyao.agent.AskResult
import com.zhixueyao.agent.TaskItem
import com.zhixueyao.settings.ZhixueyaoSettings
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson

/**
 * 任务清单工具。
 *
 * 为什么值得单独做一个工具：多步任务里，用户最需要知道的是「它现在做到哪了、
 * 还剩几步」。让模型把计划写出来并逐步勾掉，比它一口气闷头做完再汇报要好得多 ——
 * 中途方向不对时用户能立刻叫停，而不是等十分钟后发现做错了。
 *
 * 清单只是**展示**，不参与流程控制：模型忘记更新也不影响执行，只是界面上不刷新。
 */
class TodoWriteTool : AgentTool {

    override val name = "todo_write"

    override val description =
        "把当前任务拆成一份清单展示给用户（界面上会渲染成待办列表）。" +
            "做需要三步以上的任务时用它：开始时列出全部步骤，之后每完成一步就整份更新一次状态。" +
            "状态用 pending（待做）/ in_progress（正在做）/ done（已完成）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "tasks" to jsonObj(
                "type" to "array".toJson(),
                "description" to "完整任务清单，按执行顺序排列。每次调用都传全量，不要只传变化的部分",
                "items" to jsonObj(
                    "type" to "object".toJson(),
                    "properties" to jsonObj(
                        "title" to jsonObj(
                            "type" to "string".toJson(),
                            "description" to "这一步要做什么，一句话说清"
                        ),
                        "status" to jsonObj(
                            "type" to "string".toJson(),
                            "enum" to jsonArr("pending", "in_progress", "done"),
                            "description" to "pending=待做，in_progress=正在做，done=已完成"
                        )
                    ),
                    "required" to jsonArr("title", "status")
                )
            )
        ),
        "required" to jsonArr("tasks")
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        if (!ZhixueyaoSettings.getInstance().showTaskList) {
            return ToolResult("任务清单功能已在设置里关闭，不必再调用本工具，直接继续执行即可。")
        }

        val raw = args.arr("tasks")?.items ?: return ToolResult.error("缺少 tasks 参数")
        val items = raw.mapNotNull { item ->
            val obj = item.asObjOrNull ?: return@mapNotNull null
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            // 状态容错：模型偶尔会写 running / finished 这类同义词，一律归一到三态
            val status = when (obj.str("status")?.lowercase()) {
                "done", "completed", "finished" -> "done"
                "in_progress", "doing", "running", "active" -> "in_progress"
                else -> "pending"
            }
            TaskItem(title, status)
        }
        if (items.isEmpty()) return ToolResult.error("tasks 不能为空，每项至少要有 title")

        AgentUiBridge.updateTasks(project, items)

        val done = items.count { it.isDone }
        val active = items.firstOrNull { it.isActive }?.title
        // 回给模型的话要短：它只需要知道「记下了」，不需要把清单再读一遍
        return ToolResult(
            buildString {
                append("任务清单已更新：共 ${items.size} 项，已完成 $done 项")
                if (active != null) append("，当前进行中：$active")
                append("。")
            }
        )
    }
}

/**
 * 让用户拍板的工具。
 *
 * 为什么需要它：模型遇到「用 A 方案还是 B 方案」时，过去的做法是二选一 ——
 * 要么自己替用户决定（可能猜错），要么在正文里问一句然后停下（用户得再打字）。
 * 前者风险高，后者打断节奏。这个工具把选项直接渲染成按钮，点一下就能继续。
 */
class AskUserTool : AgentTool {

    override val name = "ask_user"

    override val description =
        "遇到需要用户拍板的选择时用它：给出 2-4 个具体选项让用户点选，而不是自己替用户决定，" +
            "也不要在正文里提问后停下来。用户选完你会拿到结果并继续执行。" +
            "选项要**短**（尽量不超过 12 个字，像「允许一次」「改成全盘沙盒」这种），" +
            "把背景、理由、影响写在 question 里 —— 选项太长会变成一整条大按钮，很难看。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "question" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要问用户的问题。背景、理由、每个选项的后果都写在这里，可以长一点"
            ),
            "options" to jsonObj(
                "type" to "array".toJson(),
                "description" to "2-4 个互斥的**短**选项（尽量不超过 12 个字，如「允许一次」「拒绝」）。" +
                    "不要在这里写解释 —— 选项会渲染成一行文字，写长了就变成一整条大按钮",
                "items" to jsonObj("type" to "string".toJson())
            )
        ),
        "required" to jsonArr("question", "options")
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        if (!ZhixueyaoSettings.getInstance().allowAskUser) {
            return ToolResult.error(
                "「让 AI 询问选择」已在设置里关闭。请自己选一个最稳妥的方案继续，" +
                    "并在回复里说明你做了哪个假设、为什么这么选。"
            )
        }

        val question = args.str("question")?.trim().orEmpty()
        val options = args.arr("options")?.items
            ?.mapNotNull { it.asStringOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

        if (question.isEmpty()) return ToolResult.error("缺少 question 参数")
        if (options.size < 2) return ToolResult.error("options 至少要有两项，否则不算选择题")
        if (options.size > 4) return ToolResult.error("options 最多四项，选项太多用户反而难选")

        val chosen = AgentUiBridge.askUser(project, question, options)
        return when (chosen) {
            is AskResult.Picked -> ToolResult("用户选择了：${chosen.value}")

            // 用户**明确点了取消**：这是「不要做」，不是「没回答」。
            // 以前这里和超时共用一个分支，告诉模型「按你认为最稳妥的方案继续」——
            // 于是用户点了取消，AI 反而照做了（用户反馈「居然点击取消还执行了」）。
            AskResult.Cancelled -> ToolResult(
                "**用户点了「取消」**：明确表示这次不要做。请立刻停止，" +
                    "不要再调用任何工具、也不要自己替用户选一个方案继续，" +
                    "用一句话说明已取消即可。"
            )

            AskResult.NoAnswer -> ToolResult(
                "用户没有做出选择（对话窗口可能已关闭或超时）。" +
                    "请按你认为最稳妥的方案继续，并在回复开头说明你的假设。"
            )
        }
    }
}
