package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子代理。
 *
 * ## 为什么要这个
 *
 * IDE 场景里最耗上下文的动作是**「搜一遍 + 读一堆文件」**：为了回答「这个功能在哪实现的」，
 * 模型可能读十几个文件、几万 token，而**其中九成的内容在结论里用不到**。
 * 这些废内容会一直留在上下文里，把后面的对话挤出去（然后就触发压缩，信息进一步损失）。
 *
 * 子代理解决的就是这件事：给它一个独立上下文去读，**只把结论带回来**。
 * 参考项目 astravia 的 subagents 是这套做法，我们按 IDE 场景做了裁剪。
 *
 * ## 三个刻意的设计取舍
 *
 * ### 1. 子代理**只读**
 *
 * 工具箱是父代理那一份与 [SUBAGENT_ALLOWED] 的**交集**，里面没有任何写操作。
 * 理由不是保守，是「委派」这个动作本身的性质：
 *
 * - 读操作**没有中间态**：读错了没有代价，结论对了就行 → 适合放手
 * - 写操作**有中间态**：改到一半要人看、要回滚、要判断风格 → 不适合「丢出去自己跑」
 *
 * 而且省上下文这个收益**完全来自读**。让子代理写文件既不省 token（改动要复核），
 * 又多出一条绕过沙盒的路径。
 *
 * ### 2. **只允许一层**，子代理不能再派子代理
 *
 * `spawn_agent` 不在 [SUBAGENT_ALLOWED] 里，所以嵌套在**工具层面就走不通**。
 * 递归深度限制靠「工具箱不包含自己」比靠计数器可靠 ——
 * 计数器会因为异常路径漏减，工具箱不会。
 *
 * ### 3. **异步**：spawn 立刻返回 id，不等结果
 *
 * 同步版本写起来简单，但会让 `interrupt_agent` 变成死代码（父代理被卡住，
 * 根本没机会调它）。异步还顺带带来一个真正的收益：**可以同时派几个并行读** ——
 * 「分别看这三块代码」这类任务从串行变并行。
 *
 * ## 与父代理的关系
 *
 * | | 父代理 | 子代理 |
 * |---|---|---|
 * | 上下文 | 主对话历史 | **全新独立历史** |
 * | 能否写文件 | 按预设 | **永远不能** |
 * | 输出 | 流式进界面 | 只回一段结论文本 |
 * | 用户可见性 | 全程可见 | 只在 list/wait 时看到状态 |
 *
 * 最后一条是有意的：子代理的中间过程**不进主对话**（否则省上下文就白省了），
 * 但状态可查、可中断 —— 「看不见」和「不可控」是两回事。
 */
object SubagentRegistry {

    /** 一个正在跑（或已结束）的子代理 */
    class Job(
        val id: String,
        val task: String,
        val startedAt: Long = System.currentTimeMillis()
    ) {
        /** 置位后子代理尽快退出 */
        val cancel = AtomicBoolean(false)

        /** 当前状态：running / done / failed / cancelled */
        @Volatile
        var state: String = "running"

        /** 每步更新一次，让父代理/用户知道「它还在动，在干什么」 */
        @Volatile
        var activity: String = "启动中"

        /** 结束后的结论文本 */
        @Volatile
        var result: String? = null

        fun elapsedMs(): Long = System.currentTimeMillis() - startedAt
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val seq = AtomicInteger(0)

    /** 同时最多几个子代理 —— 太多会同时打满接口限流 */
    const val MAX_CONCURRENT = 3

    /** 单个子代理最多跑多少轮（防止它自己绕进死循环） */
    const val MAX_STEPS = 12

    fun spawn(task: String): Job {
        val id = "sa-" + seq.incrementAndGet()
        val job = Job(id, task)
        jobs[id] = job
        return job
    }

    fun get(id: String): Job? = jobs[id]

    fun runningCount(): Int = jobs.values.count { it.state == "running" }

    fun all(): List<Job> = jobs.values.sortedBy { it.startedAt }

    /** 只保留最近的若干个，避免长会话里越攒越多 */
    fun prune() {
        val finished = jobs.values.filter { it.state != "running" }.sortedBy { it.startedAt }
        if (jobs.size <= 12) return
        finished.take((jobs.size - 12).coerceAtLeast(0)).forEach { jobs.remove(it.id) }
    }

    /** 父代理结束时调用：把所有还在跑的停掉，不让它们成为孤儿线程 */
    fun cancelAll() {
        jobs.values.filter { it.state == "running" }.forEach { it.cancel.set(true) }
    }

    fun reset() = jobs.clear()

    /**
     * 子代理能用的工具名。
     *
     * 都是**只读**的：读文件、搜代码、查结构、看诊断、取时间、抓网页、看 git 历史。
     * 注意 `git` 在这里 —— 它的写动作（commit/stash）由 `GitTool` 自己在运行期挡，
     * 因为研究预设也是这么处理的（同一个名下一半读一半写）。
     * 子代理不传预设信息，所以这里靠 `GitTool` 的**默认只读档**兜底 ——
     * 见 [GitTool] 里对「子代理调用」的判定。
     */
    val SUBAGENT_ALLOWED: Set<String> = setOf(
        // 注意：**不含 run_tests / run_build**。
        // 它们是「执行」，不是「读」—— 子代理的定位是侦察兵，
        // 让它去跑测试会带来两个问题：一轮跑几分钟（父代理等不起）、
        // 而且测试结果对「找出代码在哪」这个目标没有帮助。
        "read_file", "list_directory", "glob_files",
        "search_code", "find_symbol", "get_editor_context",
        "get_diagnostics", "project_structure",
        "current_time", "web_fetch", "git",
        // 知识库**只放检索**、不放写入：子代理是侦察兵，
        // 让它直接往知识库里写会绕过「父代理确认过再落盘」这一关
        "kb_search", "kb_tags"
    )

    /** 按白名单裁剪父代理的工具集 */
    fun scopeTools(parent: List<AgentTool>): List<AgentTool> =
        parent.filter { it.name in SUBAGENT_ALLOWED }
}

/**
 * 派一个子代理去干活，**立刻返回**，不等它做完。
 *
 * 返回值里带 id，后续用 `wait_agent` 取结果、`list_agents` 看进度、
 * `interrupt_agent` 叫停。
 */
class SpawnAgentTool(
    /** 父代理这一轮能用的工具 —— 子代理只能用它与只读白名单的交集 */
    private val parentTools: List<AgentTool>,
    /**
     * 真正的启动动作，由 [com.zhixueyao.agent.AgentRunner] 注入。
     *
     * 为什么用回调注入而不是让这个类自己去 new 一个运行器：
     * 跑子代理需要 `LlmConfig`、`provider`、`cancelFlag` 这些**只有 run() 内部才有的东西**。
     * 让工具类自己去取，就又变成「读全局状态」——那正是之前
     * `ToolRegistry` 那几处无法离线验证的原因。
     */
    private val launch: (job: SubagentRegistry.Job, tools: List<AgentTool>) -> Unit
) : AgentTool {

    override val name = "spawn_agent"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "task" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要子代理完成的任务，写清「要找什么、判断标准是什么、" +
                    "结论要覆盖哪几点」。它是独立上下文，看不到我们正在聊的内容 —— " +
                    "所有必要背景都要写在这一句里"
            )
        ),
        "required" to jsonArr("task")
    )

    override val description: String
        get() = "派一个子代理去独立跑一个**只读**任务（读文件、搜代码、看结构），" +
            "**立刻返回**，不等结果。适合「需要读很多文件但结论只要几句话」的活：" +
            "比如「找出这个功能的完整调用链」「扫一遍所有 ViewModel 看谁没做空值处理」。" +
            "子代理读的东西**不进我们的对话**，所以这样做能省下大量上下文。" +
            "它**不能改文件**，也不能再派子代理。派完记得用 wait_agent 取结果；" +
            "多个互不依赖的任务可以一起派，它们会并行跑。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val task = args.str("task")?.trim().orEmpty()
        if (task.isBlank()) return ToolResult.error("task 不能为空。要子代理做什么？")

        // 并发上限：不是怕跑不动，是怕把接口限流打满 ——
        // 一次派五个子代理，五个请求同时出去，很容易一起 429
        if (SubagentRegistry.runningCount() >= SubagentRegistry.MAX_CONCURRENT) {
            val busy = SubagentRegistry.all().filter { it.state == "running" }
                .joinToString("、") { "${it.id}（${it.activity}）" }
            return ToolResult.error(
                "已有 ${SubagentRegistry.MAX_CONCURRENT} 个子代理在跑（$busy）。" +
                    "先 wait_agent 收一个，或者不要派这么多。"
            )
        }

        val tools = SubagentRegistry.scopeTools(parentTools)
        if (tools.isEmpty()) {
            return ToolResult.error("当前模式下子代理没有可用工具，无法委派。")
        }

        SubagentRegistry.prune()
        val job = SubagentRegistry.spawn(task)
        launch(job, tools)

        return ToolResult(
            buildString {
                append("已派出子代理 ").append(job.id).append("，任务：").append(task).append("\n\n")
                append("它拿到的是**独立上下文**，可用工具：")
                    .append(tools.joinToString("、") { it.name }).append('\n')
                append("\n请继续做别的，或用 wait_agent 等它。**不要凭印象猜它的结论。**")
            }
        )
    }
}

/** 看子代理在干什么 / 还在不在跑。 */
class ListAgentsTool : AgentTool {

    override val name = "list_agents"

    override val parameters: Json.Obj = jsonObj("type" to "object".toJson(), "properties" to jsonObj())

    override val description: String
        get() = "列出这次会话派出去的子代理及其状态（在跑什么、跑完没有、用了多久）。" +
            "等结果之前想确认一下进度时用它。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val list = SubagentRegistry.all()
        if (list.isEmpty()) return ToolResult("这次会话还没有派出过子代理。")

        val running = list.count { it.state == "running" }
        return ToolResult(
            buildString {
                append("共 ${list.size} 个，其中 $running 个在跑：\n\n")
                for (j in list) {
                    append("- ").append(j.id).append("　")
                        .append(stateCn(j.state)).append("　")
                        .append(j.elapsedMs() / 1000).append("s")
                    if (j.state == "running") append("　正在：").append(j.activity)
                    append('\n')
                    append("  任务：").append(j.task.take(90))
                        .append(if (j.task.length > 90) "…" else "").append('\n')
                }
            }
        )
    }

    private fun stateCn(s: String) = when (s) {
        "running" -> "在跑"
        "done" -> "已完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        else -> s
    }
}

/** 等子代理跑完并取回它的结论。 */
class WaitAgentTool : AgentTool {

    override val name = "wait_agent"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "agent_id" to jsonObj(
                "type" to "string".toJson(),
                "description" to "spawn_agent 返回的 id，例如 sa-1"
            ),
            "timeout_seconds" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "最多等多少秒，默认 120。超时会返回当前状态，可以再等一次"
            )
        ),
        "required" to jsonArr("agent_id")
    )

    override val description: String
        get() = "等某个子代理结束，并取回它的结论。" +
            "**必须用它取结论** —— 子代理读的内容不在我们的对话里，凭印象猜一定是错的。" +
            "超时不是失败，只是它还没跑完，可以再 wait 一次或先做别的。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val id = args.str("agent_id")?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("agent_id 不能为空。")

        val job = SubagentRegistry.get(id)
            ?: return ToolResult.error(
                "找不到子代理 $id。用 list_agents 看当前有哪些。"
            )

        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(5, 600)
        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (job.state == "running" && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return when (job.state) {
            "running" -> ToolResult(
                "子代理 $id 还在跑（已 ${job.elapsedMs() / 1000}s，正在：${job.activity}）。" +
                    "可以再 wait_agent 一次，或先做别的。"
            )
            "done" -> ToolResult(
                "子代理 $id 的结论：\n\n${job.result ?: "（它没有产出任何文本）"}"
            )
            "cancelled" -> ToolResult("子代理 $id 已被取消，没有结论。")
            else -> ToolResult.error("子代理 $id 失败了：${job.result ?: "原因不明"}")
        }
    }
}

/** 叫停一个还在跑的子代理。 */
class InterruptAgentTool : AgentTool {

    override val name = "interrupt_agent"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "agent_id" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要停掉的子代理 id；传 all 停掉全部"
            )
        ),
        "required" to jsonArr("agent_id")
    )

    override val description: String
        get() = "叫停还在跑的子代理。派错了任务、或者发现方向不对时用它 —— " +
            "别让它在错的方向上一直读下去。id 传 all 停掉全部。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val id = args.str("agent_id")?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("agent_id 不能为空；要全停请传 all。")

        if (id == "all") {
            val n = SubagentRegistry.runningCount()
            SubagentRegistry.cancelAll()
            return ToolResult(if (n == 0) "当前没有在跑的子代理。" else "已叫停 $n 个子代理。")
        }

        val job = SubagentRegistry.get(id)
            ?: return ToolResult.error("找不到子代理 $id。用 list_agents 看当前有哪些。")
        if (job.state != "running") {
            return ToolResult("子代理 $id 已经不在跑了（状态：${job.state}），无需叫停。")
        }
        job.cancel.set(true)
        return ToolResult(
            "已给子代理 $id 发停止信号。它会在当前这一步结束时退出 —— " +
                "正在读的大文件不会读一半，但这一步本身要跑完。"
        )
    }
}