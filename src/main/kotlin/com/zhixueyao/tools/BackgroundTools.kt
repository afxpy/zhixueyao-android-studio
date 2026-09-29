package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台任务。
 *
 * ## 为什么需要
 *
 * 除了本文件里的三个工具，其余所有「跑命令」都是**阻塞**的：
 * 命令不结束，这一轮就不结束。这对短命令没问题，对这三类就不行了：
 *
 * | 场景 | 为什么不能等 |
 * |---|---|
 * | 开发服务器 / 日志跟踪 | **永远不会自己结束** —— 等它等于把这一轮卡死 |
 * | 完整构建（几分钟） | 等的时候本可以做别的分析 |
 * | 想边跑边看输出 | 阻塞版本只在结束时给一次性结果 |
 *
 * 参考项目 astravia 的 `task-output` / `task-stop` 就是这套做法。
 *
 * ## 一个刻意的限制：**不走 shell**
 *
 * 命令会被 [BackgroundTasks.splitCommand] 切成参数数组直接执行，
 * **不经过 `sh -c` / `cmd /c`**。所以管道 `|`、重定向 `>`、`&&`
 * 都不会生效。这是有意的：
 *
 * - 拼接字符串交给 shell 会引入完整的注入面（引号、重定向、命令替换）
 * - 而且 IDE 里跑的命令**绝大多数不需要**这些（要串起来就分几次调用）
 *
 * 代价是模型可能写出 `a | b` 然后困惑为什么没输出。所以
 * **检测到 shell 元字符时要在返回值里明说**，而不是让它自己猜。
 */
object BackgroundTasks {

    class Task(
        val id: String,
        val commandLine: String,
        val handle: ProcessRunner.Handle
    ) {
        @Volatile
        var activity: String = "刚启动"

        fun displayCommand(): String = handle.command.joinToString(" ")

        val state: String
            get() = when {
                handle.alive -> "running"
                handle.stopped -> "stopped"
                else -> "finished"
            }
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val seq = AtomicInteger(0)

    /** 同时最多几个后台任务 —— 后台进程不会自己消失，放任会攒成一堆 */
    const val MAX_CONCURRENT = 4

    /** 保留的最近已完成任务数（超出的老任务清掉，避免长会话里一直攒） */
    private const val KEEP_FINISHED = 8

    fun register(commandLine: String, handle: ProcessRunner.Handle): Task {
        val id = "bg-" + seq.incrementAndGet()
        val t = Task(id, commandLine, handle)
        tasks[id] = t
        return t
    }

    fun get(id: String): Task? = tasks[id]

    fun all(): List<Task> = tasks.values.sortedBy { it.handle.startedAt }

    fun runningCount(): Int = tasks.values.count { it.handle.alive }

    /** 只留最近若干个已结束的 */
    fun prune() {
        val finished = tasks.values.filter { !it.handle.alive }.sortedBy { it.handle.startedAt }
        val over = tasks.size - KEEP_FINISHED
        if (over > 0) finished.take(over).forEach { tasks.remove(it.id) }
    }

    /** 父代理结束/用户停止时调用：后台进程**必须**一起停，否则它们会一直留着 */
    fun stopAll() {
        tasks.values.filter { it.handle.alive }.forEach {
            it.handle.stop()
            it.activity = "被父任务停止"
        }
    }

    fun reset() = tasks.clear()

    /**
     * 把一行命令切成参数数组。
     *
     * **纯函数**，所以能拿各种引号组合做断言 —— 这段是「不走 shell」方案里最容易错的地方：
     * 切错了参数会传给错误的程序，而错误信息通常看不出是切分的问题。
     *
     * 规则（有意保持简单，不做 shell 展开）：
     *  - 空白分隔
     *  - 单引号 / 双引号内的空白不算分隔符
     *  - **引号本身被剥掉**（`"a b"` → `a b` 一个参数）
     *  - 反斜杠**不做转义**（Windows 路径 `C:\foo` 要能原样过去 ——
     *    引号内再处理转义的话，`"C:\new"` 里的 `\n` 会被吃掉变成换行）
     *  - 空字符串参数（`""`）**保留**（有些命令靠它区分「空参数」和「没这个参数」）
     */
    fun splitCommand(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var started = false   // 用来区分「空的参数」和「还没开始」

        for (c in line) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else cur.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    started = true
                }
                c.isWhitespace() -> {
                    if (started) {
                        out.add(cur.toString())
                        cur.setLength(0)
                        started = false
                    }
                }
                else -> {
                    cur.append(c)
                    started = true
                }
            }
        }
        if (started) out.add(cur.toString())
        return out
    }

    /**
     * 命令里有没有 shell 才认识的东西。
     *
     * 检出就要**明说**，而不是让命令悄悄跑成别的东西 ——
     * 比如 `gradlew test | tail -50` 在不走 shell 时会变成
     * 「给 gradlew 传一个叫 `|` 的参数」，Gradle 会报一个跟管道毫无关系的错。
     */
    fun shellMetaOf(line: String): String? {
        // 先按引号切一遍：引号**里面**的符号不算（那是命令自己要的参数）
        val outside = StringBuilder()
        var quote: Char? = null
        for (c in line) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                else -> outside.append(c)
            }
        }
        val s = outside.toString()
        // **顺序有讲究**：`||` 里含 `|`，所以带 `|` 的那条必须排在后面 ——
        // 否则 `a || b` 会被报成「管道」，把用户引去查一个根本不存在的问题。
        // （探针里专门有一条 `a || b` 就是为这个：第一版确实报错了。）
        return when {
            s.contains("&&") || s.contains("||") -> "逻辑连接 && / ||"
            s.contains("|") -> "管道 |"
            s.contains(">") || s.contains("<") -> "重定向 > / <"
            s.contains(";") -> "分号 ;"
            s.contains("`") || s.contains("$(") -> "命令替换"
            else -> null
        }
    }
}

/** 后台起一条命令，立刻返回，不等它结束。 */
class RunBackgroundTool : AgentTool {

    override val name = "run_background"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "command" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要跑的命令行，例如 \"gradlew test --console=plain\"。" +
                    "**不支持管道 |、重定向 > 、&& 这类 shell 语法** —— " +
                    "要串多步就分几次调用、或者把它写成脚本再用 run_script 跑"
            ),
            "workdir" to jsonObj(
                "type" to "string".toJson(),
                "description" to "工作目录（相对项目根或绝对路径）。不填就是项目根"
            )
        ),
        "required" to jsonArr("command")
    )

    override val description: String
        get() = "在后台起一条命令，**立刻返回**不等它结束。" +
            "适合：启开发服务器、跟踪日志、跑几分钟的完整构建、" +
            "或者任何你想「边跑边看输出」的命令。" +
            "**会自己结束的短命令不要用这个**（比如 git status、ls）—— " +
            "那些直接同步跑更快，还省得再来收结果。" +
            "起了之后用 task_output 看进度和输出、task_stop 停掉它。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val line = args.str("command")?.trim().orEmpty()
        if (line.isBlank()) return ToolResult.error("command 不能为空。")

        // 并发上限：后台进程不会自己消失，放任会攒一堆占资源
        if (BackgroundTasks.runningCount() >= BackgroundTasks.MAX_CONCURRENT) {
            val busy = BackgroundTasks.all().filter { it.handle.alive }
                .joinToString("、") { "${it.id}（${it.handle.command.firstOrNull()}）" }
            return ToolResult.error(
                "已有 ${BackgroundTasks.MAX_CONCURRENT} 个后台任务在跑（$busy）。" +
                    "先 task_stop 掉不用的，或者用 run_script 同步跑。"
            )
        }

        // shell 语法检查 —— 必须**先说**，否则命令会跑成一个奇怪的参数错误
        val meta = BackgroundTasks.shellMetaOf(line)
        if (meta != null) {
            return ToolResult.error(
                "这条命令用了 shell 语法「$meta」，而这里是**直接执行、不经过 shell** 的，" +
                    "所以它不会按你预期的方式跑（那个符号会被当成普通参数传下去）。\n" +
                    "改法：拆成几次调用；要串起来就写成脚本再用 run_script 跑。"
            )
        }

        val basePath = project.basePath
            ?: return ToolResult.error("拿不到项目路径，无法在后台执行命令。")
        val root = File(basePath)
        val dirArg = args.str("workdir")?.trim().orEmpty()
        val workingDir = when {
            dirArg.isBlank() -> root
            else -> File(dirArg).let { if (it.isAbsolute) it else File(root, dirArg) }
        }
        if (!workingDir.isDirectory) {
            return ToolResult.error("工作目录不存在：${workingDir.absolutePath}")
        }

        val argv = BackgroundTasks.splitCommand(line)
        if (argv.isEmpty()) return ToolResult.error("命令解析后为空。")

        return try {
            BackgroundTasks.prune()
            val handle = ProcessRunner.start(argv, workingDir)
            val task = BackgroundTasks.register(line, handle)
            ToolResult(
                buildString {
                    append("已在后台启动 ").append(task.id)
                        .append("（pid ").append(handle.pid).append("）\n")
                    append("命令：").append(line).append('\n')
                    append("目录：").append(workingDir.absolutePath).append("\n\n")
                    append("用 task_output 看它的输出，task_stop 停掉它。")
                    append("**不要凭空猜它的结果。**")
                }
            )
        } catch (e: Exception) {
            ToolResult.error("启动失败：${e.message}（可执行文件不存在或没有执行权限？）")
        }
    }
}

/** 看后台任务的输出与状态。 */
class TaskOutputTool : AgentTool {

    override val name = "task_output"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "task_id" to jsonObj(
                "type" to "string".toJson(),
                "description" to "run_background 返回的 id，例如 bg-1"
            ),
            "tail_lines" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "只看末尾多少行，默认 80。构建/日志输出可能很长，" +
                    "默认取尾巴（最新进展）；要看开头就设大一点"
            )
        ),
        "required" to jsonArr("task_id")
    )

    override val description: String
        get() = "看后台任务跑到哪了、输出是什么。" +
            "**默认只给末尾若干行** —— 长时间跑的进程输出可能几万行，" +
            "全塞进来会把上下文吃光，而最新进展通常才是你要看的。" +
            "要点：输出是**到目前为止**的，还在跑的任务可以反复看。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val id = args.str("task_id")?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("task_id 不能为空。")

        val task = BackgroundTasks.get(id)
            ?: return ToolResult.error(
                "找不到后台任务 $id。用 list 看看有哪些（或者它已经被清掉了）。"
            )

        val tail = (args.int("tail_lines") ?: 80).coerceIn(5, 2000)
        val out = task.handle.stdout()
        val err = task.handle.stderr()

        val sb = StringBuilder()
        sb.append("任务 ").append(id).append("　")
            .append(
                when {
                    task.handle.alive -> "**还在跑**（已 ${task.handle.elapsedMs() / 1000}s）"
                    task.handle.stopped -> "已被停止"
                    else -> "已结束，退出码 ${task.handle.exitCode()}"
                }
            ).append('\n')
        sb.append("命令：").append(task.commandLine).append('\n')

        if (out.isNotBlank()) {
            val lines = out.lines()
            sb.append("\n输出（共 ${lines.size} 行，")
            if (lines.size > tail) {
                sb.append("下面是末尾 $tail 行）:\n")
                sb.append(lines.takeLast(tail).joinToString("\n"))
            } else {
                sb.append("全部）:\n")
                sb.append(out)
            }
        } else {
            sb.append("\n（还没有输出）")
            if (task.handle.alive) {
                sb.append(" —— 有些程序会缓冲输出，跑一会儿才出东西。")
            }
        }

        if (err.isNotBlank()) {
            val lines = err.lines()
            sb.append("\n\nstderr（共 ${lines.size} 行）:\n")
            sb.append(lines.takeLast(minOf(tail, 40)).joinToString("\n"))
        }

        if (task.handle.alive) {
            sb.append("\n\n它还在跑。可以过一会儿再看，或者 task_stop 停掉。")
        }

        return ToolResult(sb.toString())
    }
}

/** 停掉一个后台任务。 */
class TaskStopTool : AgentTool {

    override val name = "task_stop"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "task_id" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要停掉的任务 id；传 all 停掉全部"
            )
        ),
        "required" to jsonArr("task_id")
    )

    override val description: String
        get() = "停掉后台任务（**连同它的子进程一起杀** —— 只杀父进程的话，" +
            "脚本 fork 出来的那些还活着，继续占着端口）。" +
            "常驻服务跑完就该停掉，别留一堆在后台。id 传 all 停掉全部。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val id = args.str("task_id")?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.error("task_id 不能为空；要全停请传 all。")

        if (id == "all") {
            val n = BackgroundTasks.runningCount()
            BackgroundTasks.stopAll()
            return ToolResult(if (n == 0) "当前没有在跑的后台任务。" else "已停掉 $n 个后台任务。")
        }

        val task = BackgroundTasks.get(id)
            ?: return ToolResult.error("找不到后台任务 $id。")
        if (!task.handle.alive) {
            return ToolResult(
                "任务 $id 已经不在跑了（退出码 ${task.handle.exitCode()}），无需停止。"
            )
        }
        task.handle.stop()
        task.activity = "被停止"
        return ToolResult(
            "已停掉 $id（连同子进程）。它到停下为止的输出还在，可以用 task_output 看。"
        )
    }
}