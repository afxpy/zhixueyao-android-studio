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
            "**不做 reset --hard / clean**（这类操作会丢东西，需要用户自己在终端决定）。" +
            "push / pull 默认也不做 —— 只有用户**主动在设置里开启 Git 助手**之后才可用，" +
            "那时插件会按他配好的代理与账号去连，也不会改动他的 git 配置。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "action" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr(
                    "status".toJson(), "diff".toJson(), "log".toJson(), "show".toJson(),
                    "blame".toJson(), "branch".toJson(), "stash_list".toJson(),
                    "add".toJson(), "commit".toJson(), "stash_push".toJson(), "stash_pop".toJson(),
                    // push / pull 需要先在设置里开启「Git 助手」—— 没开时调它们会被挡回来，
                    // 并明确告诉你该去哪儿开。默认姿态没变：**不主动推**。
                    "push".toJson(), "pull".toJson()
                ),
                "description" to "要做什么。diff/show/blame 都需要 path 参数" +
                    "。push/pull 需先在设置里启用 Git 助手"
            ),
            "remote" to jsonObj(
                "type" to "string".toJson(),
                "description" to "push/pull 用：远端名，默认 origin"
            ),
            "branch" to jsonObj(
                "type" to "string".toJson(),
                "description" to "push 用：分支名，默认当前分支"
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

        // **闸门放在这里**，而不是藏在 buildCommand 里 ——
        // buildCommand 是纯函数（给定 action 出命令），把权限判断混进去会让它
        // 既难测也难读。校验和构造分开，各自单一职责。
        //
        // 注意：只有 push / pull 会被挡（见 HELPER_REQUIRED）。只读动作一律放行。
        requireHelper(action)?.let { return ToolResult.error(it) }

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
        // 所有 git 命令都套上「Git 助手」的访问参数（没开助手时是空操作）。
        //
        // push / pull 额外带上凭据 —— 而且**临时文件在 finally 里删掉**，
        // 所以必须把执行包在 withCredentialFile 里，不能只把参数拼好就扔出去。
        val r = if (action == "push" || action == "pull") {
            withCredentialFile { credArgs ->
                ProcessRunner.run(withAccess(cmd, credArgs), java.io.File(base), null, timeout)
            }
        } else {
            ProcessRunner.run(withAccess(cmd), java.io.File(base), null, timeout)
        }

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
    /**
     * 把「Git 助手」里配的访问方式，变成 git 命令上的 `-c` 参数。
     *
     * ## 为什么必须做这一步
     *
     * 设置页里填了代理、点了「测试连接」显示通过 —— **如果命令不带这些参数，
     * 那一切就只是装饰**。用户配完还是推不上去，而插件看起来「明明说了能通」。
     *
     * 这正是这个工程里反复出现的那个形状：**做对了但没接上**。
     * 所以配置一落地，就必须有一条路径让它真的流到命令上。
     *
     * ## 三种模式
     *
     * - 助手**没开** → 一个参数都不加（等于没装过这个功能）
     * - `direct` → **显式清空**代理。这一条最关键：用户 git 配置里残留的代理
     *   （这台机器上就是 `57567`，一个早就没开的端口）会继续生效，
     *   而报错读起来像「网络不通」
     * - `proxy` / `vpn` → 带上解析出来的代理地址
     */
    private fun accessArgs(): List<String> {
        val s = try {
            com.zhixueyao.settings.ZhixueyaoSettings.getInstance()
        } catch (e: Exception) {
            return emptyList()
        }
        if (!s.gitHelperEnabled) return emptyList()

        return when (s.gitAccessMode) {
            "proxy" -> com.zhixueyao.git.GitProxyDetector.proxyArgs(s.gitProxyUrl)
            "vpn" -> {
                val url = if (s.gitVpnPort > 0) "http://127.0.0.1:${s.gitVpnPort}"
                else com.zhixueyao.git.GitProxyDetector.detectLocalProxy().proxyUrl
                com.zhixueyao.git.GitProxyDetector.proxyArgs(url)
            }
            else -> com.zhixueyao.git.GitProxyDetector.directArgs()
        }
    }

    /**
     * 把「Git 助手」里存的账号，变成 git 能用的凭据参数。
     *
     * ## 为什么必须补这一步（我漏过）
     *
     * 第一版把 token 加密存好了、设置页也显示「已保存」—— **但 push 时根本没用它**。
     * 于是用户填完账号去推送，照样失败。而且失败得很安静：
     * [ProcessRunner] 里设了 `GIT_TERMINAL_PROMPT=0`（这是对的，否则 git 会挂在
     * 终端上等输入），所以**要密码时它直接返回失败，不会提示「请输入密码」**。
     *
     * 用户看到的就是「推不上去」，而设置页明明写着「已保存」。
     *
     * ## 凭据怎么传给 git —— 三个方案里选了这个
     *
     * | 方案 | 问题 |
     * |---|---|
     * | URL 内嵌 `https://user:token@...` | **token 出现在命令行**，同机任何进程都能读到；还会被写进 reflog 的风险 |
     * | `-c credential.helper=!f(){ echo ...; }` | 同样把 token 塞进命令行 |
     * | **临时凭据文件 + `store --file=`** | 命令行里**只有路径**；文件短命且权限收紧 |
     *
     * 选了第三个。`store` 是 git 自带的凭据助手，`--file` 让它读一个指定文件，
     * 格式就是普通的 `https://user:token@host` 一行。
     *
     * 文件**用完即删**（[withCredentialFile] 里 try/finally）：
     * 权威副本是 [com.zhixueyao.git.GitCredentials] 里的加密存储，
     * 明文只在这几秒内、且只在这一个文件里存在。
     *
     * ## 没有凭据时不报错，而是什么都不加
     *
     * 用户可能用 SSH、或者系统里已经有凭据助手。**没有插件存的凭据 ≠ 不能认证**。
     * 所以这里安静地返回空，让 git 用自己的方式去试 ——
     * 硬塞一个「请先填账号」的错误会挡住本来能用的场景。
     */
    private fun credentialArgs(): Pair<List<String>, java.io.File?> {
        val settings = try {
            com.zhixueyao.settings.ZhixueyaoSettings.getInstance()
        } catch (e: Exception) {
            return emptyList<String>() to null
        }
        val user = settings.gitUserName.trim()
        val token = com.zhixueyao.git.GitCredentials.loadToken()
        if (user.isBlank() || token.isBlank()) return emptyList<String>() to null

        return try {
            val f = java.io.File.createTempFile("zx-gitcred-", ".txt")
            f.deleteOnExit()
            f.writeText("https://$user:$token@github.com\n", Charsets.UTF_8)
            // 收紧权限：只有本人可读（Windows 上这个调用是 no-op，靠用户目录本身的 ACL）
            runCatching {
                f.setReadable(false, false); f.setReadable(true, true)
                f.setWritable(false, false); f.setWritable(true, true)
            }
            listOf("-c", "credential.helper=store --file=${f.absolutePath}") to f
        } catch (e: Exception) {
            // 写不出临时文件不该让推送失败 —— 退化成「不带凭据」，让 git 自己想办法
            emptyList<String>() to null
        }
    }

    /**
     * 带着凭据参数跑一段逻辑，跑完**无论如何**把临时凭据文件删掉。
     *
     * `finally` 不能省：推送超时、用户取消、git 报错 —— 每条路径都要清干净。
     * 漏一条就等于把明文 token 留在了临时目录里。
     */
    private fun <T> withCredentialFile(block: (List<String>) -> T): T {
        val (args, file) = credentialArgs()
        return try {
            block(args)
        } finally {
            if (file != null) runCatching { file.delete() }
        }
    }

    /**
     * 提交署名（`-c user.name= / user.email=`）。
     *
     * ## 为什么必须接上 —— 我差点又犯同一个错
     *
     * 设置页里加了「提交署名」两个输入框，**但第一版设置项存了就没人读**。
     * 这和「token 存了却没接到 push 上」是**完全一样的形状** ——
     * 用户填了、界面显示已保存、实际一点作用都没有。
     *
     * 所以这次是**同一次改动里就把它接上**，而不是等用户回来说「没用」。
     *
     * ## 为什么只在 commit 时加，不写进 git config
     *
     * 和代理一样用 `-c` 临时传参：关掉插件就等于没装过，
     * 也不会覆盖用户在自己终端里配好的身份。
     */
    private fun authorArgs(): List<String> {
        val s = try {
            com.zhixueyao.settings.ZhixueyaoSettings.getInstance()
        } catch (e: Exception) {
            return emptyList()
        }
        if (!s.gitHelperEnabled) return emptyList()
        val name = s.gitAuthorName.trim()
        val email = s.gitAuthorEmail.trim()
        val out = mutableListOf<String>()
        if (name.isNotEmpty()) { out += "-c"; out += "user.name=$name" }
        if (email.isNotEmpty()) { out += "-c"; out += "user.email=$email" }
        return out
    }

    /**
     * 把 `-c` 参数插到 `git` 和子命令之间。
     *
     * 位置不能随便放：`git -c k=v <子命令>` 是合法写法，
     * 而 `git <子命令> -c k=v` **不是** —— 那样 git 会把 `-c` 当成子命令的参数，
     * 轻则报错，重则被当成路径。
     */
    private fun withAccess(cmd: List<String>, extraCredentialArgs: List<String> = emptyList()): List<String> {
        // 署名只在 commit 时有意义 —— 给别的命令加上去只会让命令行变长
        val author = if (cmd.size > 1 && cmd[1] == "commit") authorArgs() else emptyList()
        val extra = accessArgs() + author + extraCredentialArgs
        if (extra.isEmpty()) return cmd
        if (cmd.isEmpty() || cmd[0] != "git") return cmd
        return listOf("git") + extra + cmd.drop(1)
    }

    /**
     * 需要「Git 助手已开启」才允许的动作。
     *
     * ## 为什么只有这两个
     *
     * 只有 **push / pull 会对外产生副作用**（改远端仓库），所以要一道明确的授权。
     * 其余全是只读或纯本地操作 —— `status` / `log` / `diff` 是 GitTool 最常用的功能
     * （「改代码前先看看它为什么变成现在这样」），**把它们也挡掉会让整个 git 工具废掉**。
     *
     * ## 我在这里踩过一次，探针抓出来的
     *
     * 第一版把闸门放在了**所有 action 之前**，于是没开助手时连 `git status` 都返回
     * 「需要先开启 Git 助手」。`DevToolsProbe` 立刻红了 —— 它测的就是
     * 「git 六个动作都对」。
     *
     * 这个错误的形状值得记：**加权限时把范围划大了**。
     * 想的是「给新功能加个开关」，实际做成了「给整个工具加个开关」。
     * 而它**编译能过、跑起来也不崩** —— 只是把一半功能静默关掉了。
     */
    private val HELPER_REQUIRED = setOf("push", "pull")

    /** 需要助手但没开时返回提示语；不需要或已开则返回 null */
    private fun requireHelper(action: String): String? {
        if (action !in HELPER_REQUIRED) return null
        val on = try {
            com.zhixueyao.settings.ZhixueyaoSettings.getInstance().gitHelperEnabled
        } catch (e: Exception) {
            false
        }
        if (on) return null
        return "「$action」需要先在设置里开启 Git 助手（设置 → Git 助手 → 启用）。" +
            "开启后插件会按你配置的代理去连，不会动你的 git 配置。"
    }

    private fun buildCommand(action: String, args: Json.Obj, path: String?): List<String>? =
        when (action) {
            "status" -> listOf("git", "status", "--short", "--branch")
            // push / pull —— 只在助手开启时可达（调用点先查 requireHelper）。
            //
            // 原来这两个是**刻意不做**的（「这类操作需要用户自己在终端决定」）。
            // 加上它们的前提是有了一道明确的闸门：用户主动开启助手、填好凭据，
            // 那才算他授权 AI 替他推。**默认仍然是关着的**，姿态没变。
            "push" -> listOf("git", "push") +
                (args.str("remote")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()) +
                (args.str("branch")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList())
            "pull" -> listOf("git", "pull") +
                (args.str("remote")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList())
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