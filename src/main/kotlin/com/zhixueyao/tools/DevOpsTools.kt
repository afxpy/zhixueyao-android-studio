package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Git 工具** —— 读提交历史、看改动、做提交。
 *
 * ## 为什么这个工具价值最高
 *
 * 参考项目里 git 相关的工具都很自然（agents-universe 有 `git_repo` / `github`）。
 * 而在一个 **IDE 插件**里，git 的价值比在通用 agent 里还大：
 *
 * - 「这段代码是什么时候、为什么变成这样的」→ `git log` / `git blame`
 * - 「我这次到底改了什么」→ `git diff`
 * - 「改动前后对比一下」→ `git show`
 * - 「帮我提交」→ `git commit`
 *
 * 之前这个插件只能读当前文件内容 —— 「历史」这个维度完全是空的，
 * 而理解一段代码**最有效**的线索往往就是它的历史。
 *
 * ## 安全设计
 *
 * 分两档（用 [READ_ONLY_ACTIONS] 判定）：
 * - **只读**：status / diff / log / show / blame / branch / stash list …
 *   只读预设下也放行 —— 看历史不改任何东西
 * - **写**：commit / add / checkout / stash push|pop …
 *   会改动仓库状态，只在可写预设下可用，且**提交信息由工具自己拼**
 *   （不让模型拼 shell 字符串）
 *
 * 另外：**不提供 `push` / `reset --hard` / `clean -fd`**。
 * 前两个会把用户的远程仓库或本地工作区搞乱且难以挽回，
 * 第三个会删掉未跟踪的文件（那些文件可能从没被提交过，删了就真没了）。
 * 需要这些的时候，让用户自己去终端做 —— 这类操作不该由一个 AI 顺手完成。
 */
class GitTool : AgentTool {

    override val name = "git"

    override val description =
        "查看 git 历史与改动（status/diff/log/show/blame/branch/stash list），也能做提交（commit/add/stash）。" +
            "理解一段代码最有效的线索常常是它的历史 —— 改代码前先看看它为什么变成现在这样。" +
            "**不做 push / reset --hard / clean**（这类操作需要用户自己在终端决定）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "action" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr(
                    "status".toJson(), "diff".toJson(), "log".toJson(), "show".toJson(),
                    "blame".toJson(), "branch".toJson(), "stash_list".toJson(),
                    "add".toJson(), "commit".toJson(), "stash_push".toJson(), "stash_pop".toJson()
                ),
                "description" to "要做什么。diff/show/blame 都需要 path 参数"
            ),
            "path" to jsonObj(
                "type" to "string".toJson(),
                "description" to "文件或目录（项目内相对路径）。diff / show / blame 用它限定范围"
            ),
            "n" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "log 条数，默认 15，最多 100"
            ),
            "rev" to jsonObj(
                "type" to "string".toJson(),
                "description" to "修订号，如 HEAD~3、abc1234、上次提交用 HEAD。show / blame 用"
            ),
            "message" to jsonObj(
                "type" to "string".toJson(),
                "description" to "commit 的提交信息。一句话说清「改了什么、为什么」"
            ),
            "staged_only" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "diff 用：只看已暂存的改动（等价 git diff --cached）"
            ),
            "stat_only" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "diff 用：只要「哪个文件改了几行」的概览。" +
                    "默认 false = 连具体改动一起给（多数情况你要的是这个）"
            )
        ),
        "required" to jsonArr("action".toJson())
    )

    /** 只读动作 —— 只读预设下也放行 */
    private val READ_ONLY_ACTIONS = setOf(
        "status", "diff", "log", "show", "blame", "branch", "stash_list"
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val action = args.str("action")?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) return ToolResult.error("缺少 action 参数")

        val base = project.basePath
            ?: return ToolResult.error("项目根目录未就绪，无法执行 git")

        // 先确认这是个 git 仓库。不先查的话，每条命令都会返回
        // 「fatal: not a git repository」，模型会误以为是命令写错了。
        val probe = ProcessRunner.run(listOf("git", "rev-parse", "--is-inside-work-tree"),
            java.io.File(base), null, 10_000)
        if (!probe.ok) {
            val hint = if (probe.stderr.contains("not found", true) || probe.exitCode == -1)
                "系统里找不到 git 命令（它不在 PATH 上）"
            else
                "这个目录不是 git 仓库（还没有 git init，或者项目根不对）"
            return ToolResult.error("$hint\n\n${probe.stderr.take(300)}")
        }

        val path = args.str("path")?.trim()?.takeIf { it.isNotEmpty() }
        // path 要过沙盒：不能借 git 去看项目外的文件
        path?.let {
            runCatching { PathGuard.resolve(project, it, "查看") }
                .onFailure { e -> return ToolResult.error(e.message ?: "路径无效") }
        }

        // commit 缺 message 要**明确说清缺什么**，不能落到「不认识的动作」那条分支
        if (action == "commit" && args.str("message").isNullOrBlank()) {
            return ToolResult.error(
                "commit 需要 message 参数：一句话说清「改了什么、为什么」。\n" +
                    "另外记得先 add（或者用 git add 之后再 commit）。"
            )
        }

        val cmd = buildCommand(action, args, path)
            ?: return ToolResult.error(
                "不认识的动作：$action\n" +
                    "支持的是：status / diff / log / show / blame / branch / stash_list / " +
                    "add / commit / stash_push / stash_pop"
            )

        // **写动作在只读预设下要再挡一道**。
        //
        // 预设过滤是按工具名做的（`AgentPresets.READ_ONLY`），而 `git` 这个名字
        // 同时包含读和写两种动作 —— 名单层面挡不住。
        // 与其把 git 整个归到「写」（那样研究模式就看不了历史，损失很大），
        // 不如在工具内部按动作精确判断。这是「纵深防御」：
        // 名单是粗粒度的第一道，这里是精确的第二道。
        if (action !in READ_ONLY_ACTIONS) {
            val preset = com.zhixueyao.settings.ZhixueyaoSettings.getInstance().agentPreset
            if (com.zhixueyao.agent.AgentPresets.byId(preset).readOnlyOnly) {
                return ToolResult.error(
                    "当前是只读模式，不能执行会改动仓库的 git $action。\n" +
                        "看历史（status / diff / log / show / blame）不受限制。" +
                        "需要提交的话，请用户把预设切到「标准」或「代码」。"
                )
            }
        }

        // 写动作前先说清楚要做什么（用户的仓库状态要变，得让他能看出来）
        if (action !in READ_ONLY_ACTIONS) {
            com.zhixueyao.agent.AgentLog.record(
                com.zhixueyao.agent.AgentLog.Kind.SESSION, "git $action",
                (cmd.drop(1).joinToString(" ") + (path?.let { " $it" } ?: "")).take(200)
            )
        }

        val timeout = when (action) {
            // 提交/暂存可能涉及大量文件，给长一点
            "commit", "add", "stash_push", "stash_pop" -> 60_000L
            // blame 在大文件上很慢
            "blame" -> 90_000L
            else -> 30_000L
        }
        val r = ProcessRunner.run(cmd, java.io.File(base), null, timeout)

        val body = r.combined()
        return when {
            r.cancelled -> ToolResult.error("已取消")
            r.timedOut -> ToolResult.error(
                "git $action 超时（${timeout / 1000}s）。仓库可能很大，" +
                    "试试用 path 限定到具体文件，或减少 n。"
            )
            // git 的很多「正常信息」走 stderr（比如 status 的提示），
            // 所以只要 exitCode 是 0 就算成功，不因为 stderr 有内容就报错
            r.exitCode != 0 -> ToolResult.error(
                "git $action 失败（exit ${r.exitCode}）：\n${body.take(2000)}"
            )
            body.isBlank() -> ToolResult("git $action 没有输出（通常表示没有需要报告的内容）")
            else -> ToolResult(body, ok = true)
        }
    }

    /**
     * 把 action + 参数拼成命令行。
     *
     * **永远是 List 形式**，不做字符串拼接再交给 shell ——
     * 那样提交信息里的引号、`$`、换行都会变成注入面。
     */
    private fun buildCommand(action: String, args: Json.Obj, path: String?): List<String>? =
        when (action) {
            "status" -> listOf("git", "status", "--short", "--branch")
            "diff" -> buildList {
                add("git"); add("diff")
                if (args.bool("staged_only") == true) add("--cached")
                add("--no-color")
                if (args.bool("stat_only") == true) {
                    add("--stat")
                    path?.let { add("--"); add(it) }
                    return@buildList
                }
                // 默认给**完整改动**，不只是 --stat。
                //
                // 这里一开始只加了 `--stat`，探针立刻暴露了问题：
                // 模型调 diff 是想知道「改了什么」，而 --stat 只告诉它
                // 「哪个文件动了几行」—— 拿到这个还得再调一次才能看到内容。
                // 现在两个都给：先 stat 给规模，再 patch 给内容。
                // 太长的话由 ProcessRunner 的输出截断兜住，不会撑爆上下文。
                add("--stat")
                add("--patch")
                path?.let { add("--"); add(it) }
            }
            "log" -> buildList {
                add("git"); add("log")
                add("--max-count=${(args.int("n") ?: 15).coerceIn(1, 100)}")
                add("--date=short")
                // 一行一条，带日期和作者。比默认格式省一半以上字符
                add("--pretty=format:%h %ad %an: %s")
                path?.let { add("--"); add(it) }
            }
            "show" -> buildList {
                add("git"); add("show")
                add(args.str("rev")?.trim()?.takeIf { it.isNotEmpty() } ?: "HEAD")
                add("--stat")
                add("--patch")
                add("--no-color")
                path?.let { add("--"); add(it) }
            }
            "blame" -> buildList {
                add("git"); add("blame")
                add("--date=short")
                add("--no-color")
                // blame 的行数可能很多，用 -L 让调用方自己限定范围会更精确，
                // 但接口里没这个参数 —— 交给输出截断兜住
                add(args.str("rev")?.trim()?.takeIf { it.isNotEmpty() } ?: "HEAD")
                add("--")
                add(path ?: return null)
            }
            "branch" -> listOf("git", "branch", "--all", "--verbose", "--no-color")
            "stash_list" -> listOf("git", "stash", "list")
            "add" -> buildList {
                add("git"); add("add")
                // 有 path 就只加那个 path（更可控）；没给就加全部改动
                if (path != null) add("--") else add("-A")
                path?.let { add(it) }
            }
            "commit" -> {
                val msg = args.str("message")?.trim().orEmpty()
                if (msg.isEmpty()) return null
                listOf("git", "commit", "-m", msg)
            }
            "stash_push" -> buildList {
                add("git"); add("stash"); add("push")
                args.str("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { add("-m"); add(it) }
            }
            "stash_pop" -> listOf("git", "stash", "pop")
            else -> null
        }

    companion object {
        /** 写动作集合，供预设过滤用 */
        val WRITE_ACTIONS = setOf("add", "commit", "stash_push", "stash_pop")
    }
}

/**
 * **跑脚本做定量计算**。
 *
 * 对应参考项目里的两个东西：agents-universe 的 `code_executor` /
 * `script_writer`，以及 insight-agents 的「沙箱定量计算」。
 *
 * ## 为什么需要它
 *
 * 模型算数是不可靠的 —— 这是公开的常识，也是它自己最容易犯错的地方。
 * 「这个改动会影响多少个文件」「这段数据的增长率是多少」
 * 「这个正则到底能不能匹配那 200 行日志」—— 这类问题**不该靠心算**，
 * 应该写几行脚本跑一下。
 *
 * 而且它的输出是**真实执行结果**，不是模型的估计值 ——
 * 这对「回答要可追溯」这件事是直接加成。
 *
 * ## 安全
 *
 * - 脚本写到**会话临时目录**（不在用户工程里）
 * - 命令走 [ProcessRunner]（不走 shell）
 * - 默认超时 60 秒，输出截断
 * - **不装任何依赖**：只用标准库。装包要联网 + 改用户环境，
 *   那是另一个量级的授权，不在这个工具里做
 */
class RunScriptTool : AgentTool {

    override val name = "run_script"

    override val description =
        "跑一小段 Python 或 Node 脚本做**定量计算或数据处理**，返回真实执行结果。" +
            "适合：「统计有多少个文件匹配这个模式」「解析这段 JSON 算个值」" +
            "「跑一下这个正则看能不能匹配」「算增长率/占比」。" +
            "**别用它猜结果 —— 它的价值正是「不靠心算」**。" +
            "脚本存在会话临时目录，不污染工程；只能用标准库（不装依赖）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "language" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("python".toJson(), "node".toJson()),
                "description" to "用哪个运行时，默认 python"
            ),
            "code" to jsonObj(
                "type" to "string".toJson(),
                "description" to "脚本正文。用 print 输出结果（stdout 会返回给你）。" +
                    "读项目文件请用绝对路径"
            ),
            "timeout_seconds" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "最长秒数，默认 60，最多 300"
            ),
            "args" to jsonObj(
                "type" to "array".toJson(),
                "items" to jsonObj("type" to "string".toJson()),
                "description" to "传给脚本的命令行参数（可选）"
            )
        ),
        "required" to jsonArr("language".toJson(), "code".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val code = args.str("code")?.trim().orEmpty()
        if (code.isEmpty()) return ToolResult.error("缺少 code 参数")
        if (code.length > 200_000) {
            return ToolResult.error("脚本太长了（${code.length} 字符），超过 20 万字符上限")
        }
        // 脚本是任意代码，只读预设下不该能跑（它能改文件）
        if (com.zhixueyao.agent.AgentPresets
                .byId(com.zhixueyao.settings.ZhixueyaoSettings.getInstance().agentPreset)
                .readOnlyOnly
        ) {
            return ToolResult.error(
                "当前是只读模式，不能执行脚本（脚本可以读写任意文件）。\n" +
                    "需要计算的话，请用户把预设切到「标准」。"
            )
        }

        val lang = args.str("language")?.trim()?.lowercase() ?: "python"
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(5, 300) * 1000L

        // 脚本落到会话临时目录 —— 不在用户工程里留任何文件
        val dir = com.zhixueyao.agent.SessionWorkspace.currentFor(project)
            ?: return ToolResult.error(
                "拿不到会话临时目录，无法放脚本。可以先在对话里说一句别的让它建出来，或者用 write_file 自己写。"
            )
        val ext = if (lang == "node") "mjs" else "py"
        val file = java.io.File(dir, "script_${System.currentTimeMillis()}.$ext")

        runCatching { file.writeText(code, Charsets.UTF_8) }
            .onFailure { return ToolResult.error("写脚本失败：${it.message}") }

        val exe = when (lang) {
            "node" -> listOf("node")
            else -> listOf(pythonExecutable())
        }
        val cmd = buildList {
            addAll(exe)
            // -u：Python 不缓冲 stdout，否则超时被杀时拿不到已打印的内容
            if (lang != "node") add("-u")
            add(file.absolutePath)
            // Json 里数组要手动遍历（没有 strArr 辅助）
            args.arr("args")?.items?.forEach { it.asStringOrNull?.let { s -> add(s) } }
        }

        val r = ProcessRunner.run(cmd, java.io.File(project.basePath ?: dir.absolutePath), null, timeout)

        return when {
            r.exitCode == -1 && r.stderr.contains("无法启动命令") -> ToolResult.error(
                "找不到 $lang 运行时（${exe.first()}）。\n\n${r.stderr}\n\n" +
                    "可以改用另一种语言，或者用 read_file / search_code 等工具直接查。"
            )
            r.cancelled -> ToolResult.error("已取消")
            r.timedOut -> ToolResult.error(
                "脚本超时（${timeout / 1000}s）被终止。\n\n已输出的部分：\n${r.stdout.take(3000)}\n\n" +
                    "提示：缩小数据范围，或者把结果分批打印。"
            )
            r.exitCode != 0 -> ToolResult(
                "脚本执行出错（exit ${r.exitCode}），${r.durationMs}ms\n\n" +
                    "stdout:\n${r.stdout.take(2000)}\n\nstderr:\n${r.stderr.take(3000)}",
                ok = false
            )
            else -> ToolResult(
                buildString {
                    append("脚本执行成功（${r.durationMs}ms）\n\n")
                    append(r.stdout.ifBlank { "（脚本没有输出任何内容 —— 记得用 print 打印结果）" })
                    if (r.stderr.isNotBlank()) {
                        append("\n\n--- stderr（警告，不影响结果）---\n")
                        append(r.stderr.take(1000))
                    }
                }
            )
        }
    }

    /**
     * 找一个可用的 Python。
     *
     * 逐个探测而不是写死 `python`：Windows 上 `python` 有时是 Microsoft Store
     * 的占位程序（运行它会弹应用商店而不是执行脚本）。
     * IDE 自带的 JBR 目录下没有 python，所以只能靠 PATH。
     */
    private fun pythonExecutable(): String {
        val candidates = if (isWindows()) listOf("python", "py", "python3") else listOf("python3", "python")
        for (c in candidates) {
            val probe = ProcessRunner.run(listOf(c, "--version"), java.io.File("."), null, 8000)
            if (probe.exitCode == 0) return c
        }
        return candidates.first()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}