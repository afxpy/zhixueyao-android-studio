package com.zhixueyao.git

import java.io.File

/**
 * Git 远端操作的**诊断器**：把「失败」翻译成「事实 + 下一步」。
 *
 * ## 为什么要有它
 *
 * 之前 push 失败时，工具只是把 git 的英文报错原样丢回来 ——
 * 「Failed to connect to github.com port 443 via 127.0.0.1」「fetch first」
 * 这些句子既没说「哪里坏了」，也没说「接下来做什么」。
 *
 * 而这一类问题**全都有确定性的答案**：端口有没有在听、凭据对不对、
 * 两边历史是什么关系 —— 都能查出来。所以这里把每一步都查掉：
 *
 *  - [classify]        失败属于哪一类（连接 / 认证 / 非快进 / 冲突……）
 *  - [probeRemote]     远端探测（ls-remote：同时验证连通与凭据）
 *  - [mergeBaseOk] / [aheadBehind]   两边历史关系
 *  - [remoteDefaultBranch]           远端默认分支（报告「推到了哪」用）
 *  - [connectionHelp] / [authHelp] / [divergenceHelp]   渲染成人话
 *
 * 命令一律走 [com.zhixueyao.tools.ProcessRunner]（List 形式、不进 shell），
 * 代理等参数用 `-c` 临时传 —— 和 GitTool 的约定一致：**不碰用户的 git 配置**。
 *
 * ## 一条设计原则（用户明确要求）
 *
 * **只诊断、只报告 —— 不自动改用其它代理。**
 * 用户原话：「不要总是打开我的本地代理」。所以这里没有「探测到可用代理就
 * 自动换过去重试」这类动作：用什么通道完全按用户在设置里的选择，
 * 失败时如实报告并给出他能自行执行的下一步。端口探测只发生在
 * 用户主动点「测试连接」的时候。
 */
object GitDoctor {

    /** 失败分类 */
    enum class FailureKind { NOT_A_REPO, NO_REMOTE, NON_FAST_FORWARD, CONFLICT, AUTH, CONNECTION, OTHER }

    /**
     * 远端探测结果。
     *
     * `ok` 与 `hash` 是**两件事**：
     *  - `ok = false` → 命令本身失败（连不上 / 认证失败），原因在 [output]
     *  - `ok = true, hash = null` → 命令成功，但这个分支在远端不存在（push 会创建它）
     */
    data class RemoteProbe(val ok: Boolean, val hash: String?, val output: String)

    // ---------------- 失败分类 ----------------

    /**
     * 从 git 的输出里判断失败类型。
     *
     * 顺序有讲究：先排掉「明确且互斥」的类别，最后才是兜底。
     * 这里**只做分类，不做网络探测** —— 探测由调用方按需触发（避免每次失败都去扫端口）。
     */
    fun classify(output: String): FailureKind {
        val t = output.lowercase()
        fun has(vararg keys: String) = keys.any { it in t }
        return when {
            has("not a git repository") -> FailureKind.NOT_A_REPO
            has(
                "no configured push destination",
                "no remote repository specified",
                "does not appear to be a git repository",
                "no such remote",
            ) -> FailureKind.NO_REMOTE
            has("non-fast-forward", "fetch first", "updates were rejected", "not possible to fast-forward") ->
                FailureKind.NON_FAST_FORWARD
            has("automatic merge failed", "merge conflict", "conflict (", "fix conflicts") ->
                FailureKind.CONFLICT
            has(
                "authentication failed",
                "invalid username or token",
                "invalid username or password",
                "could not read username",
                "could not read password",
                "terminal prompts disabled",
                "support for password authentication was removed",
                "403", "401",
            ) -> FailureKind.AUTH
            has(
                "failed to connect",
                "could not connect to server",
                "connection refused",
                "connection timed out",
                "timed out",
                "could not resolve host",
                "unable to resolve host",
                "network is unreachable",
                "connection reset",
                "over proxy",
                "ssl_error", "ssl connect error",
                "empty reply from server",
                "remote end hung up",
                "early eof",
            ) -> FailureKind.CONNECTION
            else -> FailureKind.OTHER
        }
    }

    // ---------------- 命令执行 ----------------

    /**
     * 跑一条 git 命令。`extra` 是插在 `git` 和子命令之间的 `-c` 参数
     * （代理等由调用方决定；后出现的同名 `-c` 覆盖先出现的，所以重试时追加即可覆盖）。
     */
    fun run(
        dir: File,
        args: List<String>,
        extra: List<String> = emptyList(),
        timeout: Long = 15_000
    ): com.zhixueyao.tools.ProcessRunner.Result {
        val cmd = if (args.firstOrNull() == "git" && extra.isNotEmpty()) {
            listOf("git") + extra + args.drop(1)
        } else args
        return com.zhixueyao.tools.ProcessRunner.run(cmd, dir, null, timeout)
    }

    // ---------------- 仓库信息 ----------------

    /** 当前分支；detached HEAD 时返回 null */
    fun currentBranch(dir: File): String? =
        run(dir, listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), timeout = 8_000).let { r ->
            r.stdout.trim().takeIf { r.ok && it.isNotEmpty() && it != "HEAD" }
        }

    /** 本地 HEAD 的完整 hash */
    fun localHead(dir: File): String? =
        run(dir, listOf("git", "rev-parse", "HEAD"), timeout = 8_000).let { r ->
            r.stdout.trim().takeIf { r.ok && it.isNotEmpty() }
        }

    /** 本地有没有这个提交对象（没有就说明得先 fetch 才能比较） */
    fun hasCommit(dir: File, rev: String): Boolean =
        run(dir, listOf("git", "cat-file", "-e", "$rev^{commit}"), timeout = 8_000).ok

    /** 短 hash，用于展示 */
    fun short(hash: String?): String = hash?.take(7) ?: "?"

    // ---------------- 远端探测 ----------------

    /**
     * 读远端某个分支的 hash —— **一条命令同时验证「连通」和「凭据」**。
     * 这是推送预检的核心：它成功 = 网络与认证都没问题。
     */
    fun probeRemote(
        dir: File,
        remote: String,
        branch: String,
        extra: List<String> = emptyList(),
        timeout: Long = 20_000
    ): RemoteProbe {
        val r = run(dir, listOf("git", "ls-remote", "--heads", remote, "refs/heads/$branch"), extra, timeout)
        val out = r.combined()
        if (!r.ok) return RemoteProbe(false, null, out)
        // 输出形如：<hash>\trefs/heads/main
        val hash = r.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.substringBefore('\t')
            ?.trim()
            ?.takeIf { it.length >= 7 }
        return RemoteProbe(true, hash, out)
    }

    /** 远端默认分支（HEAD 指向哪），拿不到返回 null */
    fun remoteDefaultBranch(
        dir: File,
        remote: String,
        extra: List<String> = emptyList(),
        timeout: Long = 20_000
    ): String? {
        val r = run(dir, listOf("git", "ls-remote", "--symref", remote, "HEAD"), extra, timeout)
        if (!r.ok) return null
        // 输出形如：ref: refs/heads/main\tHEAD
        return Regex("ref:\\s+refs/heads/(\\S+)").find(r.stdout)?.groupValues?.get(1)
    }

    // ---------------- 历史关系 ----------------

    /** 两条历史有共同祖先吗（没有 = 无关联历史，合并需要 --allow-unrelated-histories） */
    fun mergeBaseOk(dir: File, upstream: String): Boolean =
        run(dir, listOf("git", "merge-base", "HEAD", upstream), timeout = 10_000).let {
            it.ok && it.stdout.trim().isNotEmpty()
        }

    /** 本地相对 upstream：ahead / behind。拿不到返回 null */
    fun aheadBehind(dir: File, upstream: String): Pair<Int, Int>? {
        val r = run(dir, listOf("git", "rev-list", "--left-right", "--count", "HEAD...$upstream"), timeout = 10_000)
        if (!r.ok) return null
        val parts = r.stdout.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        return a to b
    }

    /** 当前合并里处于冲突状态的文件 */
    fun conflictedFiles(dir: File): List<String> =
        run(dir, listOf("git", "diff", "--name-only", "--diff-filter=U"), timeout = 10_000).let { r ->
            if (r.ok) r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        }

    // ---------------- 配置 ----------------

    /**
     * git 配置里现在生效的代理（local 会覆盖 global，取最后一条非空值）。
     *
     * 这一项是「旧代理残留」那个坑的探测器：报错读起来像网络不通，
     * 实际是配置里指着一个早就关掉的端口。
     */
    fun configuredProxy(dir: File): String? {
        val r = run(dir, listOf("git", "config", "--show-scope", "--get-regexp", "^https?\\.proxy$"), timeout = 8_000)
        if (!r.ok) return null
        var best: String? = null
        for (line in r.stdout.lines()) {
            // 输出形如：local\thttp.proxy http://127.0.0.1:57567
            val value = line.substringAfter(' ', "").trim()
            if (value.isNotEmpty()) best = value
        }
        return best
    }

    // ---------------- 设置读取（不涉网络） ----------------

    /**
     * 用户当前选择的访问方式，一句话。
     *
     * **只读设置，不探测、不连端口** —— 用于失败报告里告诉用户
     * 「你现在配的是哪种」，让下一步更具体。
     */
    fun currentModeText(): String = try {
        val s = com.zhixueyao.settings.ZhixueyaoSettings.getInstance()
        if (!s.gitHelperEnabled) "Git 助手未开启"
        else when (s.gitAccessMode) {
            "proxy" -> "自定义代理（" + s.gitProxyUrl.trim().ifBlank { "未填地址" } + "）"
            "vpn" -> if (s.gitVpnPort > 0) "本地 VPN 工具（端口 ${s.gitVpnPort}）"
            else "本地 VPN 工具（端口自动探测）"
            else -> "直连（不走代理）"
        }
    } catch (e: Exception) {
        "未知（读设置失败）"
    }

    // ---------------- 人话渲染 ----------------

    /**
     * 连接类失败：查配置里的代理残留，给出用户可执行的下一步。
     *
     * **刻意不做任何自动探测** —— 用户明确要求过「不要总是打开我的本地代理」。
     * 这里只读用户的 git 配置（不解网络、不连端口），剩下的路让用户自己选。
     */
    fun connectionHelp(dir: File, output: String): String {
        val configured = configuredProxy(dir)
        return buildString {
            append("**连不上远程**（网络 / 代理问题）。\n\n")
            append("原始报错：\n```\n").append(output.trim().take(600)).append("\n```\n\n")
            if (!configured.isNullOrBlank()) {
                append("检测到你的 git 配置里有一个代理：`").append(configured).append("` —— ")
                append("刚才就是走它失败的。如果这个代理软件已经关了，")
                append("可以到「设置 → Git 助手 → 环境自检 → 代理配置」把它清掉。\n\n")
            }
            append("你当前设置的访问方式是：**").append(currentModeText()).append("**。\n")
            append("插件**不会自动改用其它代理** —— 要不要换、换成哪个，由你决定：\n")
            append("① 去「设置 → Git 助手 → 访问方式」检查选得对不对")
            append("（Clash 这类本机开代理端口的选「本地 VPN 工具」，")
            append("一键加速类全局 TUN 的选「直连」）；\n")
            append("② 在那一页点「测试连接」，按你选的方式真的连一次 github")
            append("（这一步由你主动触发，插件平时不会去扫你的端口）；\n")
            append("③ 改完设置后重试这条命令。\n")
        }
    }

    /** 认证类失败：给出「凭据管理器」与「Token 当密码」两条路 */
    fun authHelp(output: String): String = buildString {
        append("**认证失败** —— GitHub 不再接受账号密码。\n\n")
        append("原始报错：\n```\n").append(output.trim().take(400)).append("\n```\n\n")
        append("两条路，任选其一：\n")
        append("① **启用凭据管理器**（推荐）：设置页 → Git 助手 → 凭据管理器 → 「一键启用」或「自动安装」。")
        append("之后第一次推送会弹一次登录，以后你和终端都不用再输账号。\n")
        append("② **用 Token 当密码**：GitHub → Settings → Developer settings → Personal access tokens 生成一个（勾 `repo` 权限）；")
        append("推送时**用户名填 GitHub 用户名、密码粘贴 Token**。\n\n")
        append("⚠️ 不要把 Token 直接写进命令、地址或提交进仓库 —— 它等于账号权限。")
    }

    /** 非快进 / 分叉：把两边关系讲清楚 + 给出「先合并再推」的具体操作 */
    fun divergenceHelp(upstream: String, ahead: Int, behind: Int, unrelated: Boolean): String = buildString {
        append("**远程有本地没有的提交，直接推会被拒绝**（这就是 `fetch first` 的意思）。\n\n")
        if (unrelated) {
            append("而且两边**没有共同历史**（像是分别初始化过的两个仓库）。\n\n")
        } else {
            append("- 远程 `").append(upstream).append("` 领先本地 **").append(behind).append("** 个提交\n")
            append("- 本地领先远程 **").append(ahead).append("** 个提交\n\n")
        }
        append("推荐做法：先合并远程再推 ——\n")
        append("① `merge` 动作，`rev` 填 `").append(upstream).append("`")
        if (unrelated) append("，并带上 `allow_unrelated=true`")
        append("；\n")
        append("② 合并成功后再 `push`。\n\n")
        append("如果合并出现冲突：处理完文件后 `add` + `commit`；想放弃这次合并用 `merge_abort`。")
    }

    /** 冲突：列出冲突文件 + 下一步 */
    fun conflictHelp(files: List<String>): String = buildString {
        append("**合并有冲突**，这些文件需要处理：\n")
        files.take(20).forEach { append("- `").append(it).append("`\n") }
        if (files.size > 20) append("… 还有 ").append(files.size - 20).append(" 个\n")
        append("\n处理方式：编辑这些文件（去掉冲突标记、保留要的内容）→ `add` + `commit`；")
        append("想放弃这次合并：`merge_abort`。")
    }

    /** 推送后的「去向报告」：推的不是默认分支时说清楚 */
    fun destinationNote(dir: File, remote: String, branch: String, extra: List<String>): String {
        val def = remoteDefaultBranch(dir, remote, extra) ?: return ""
        if (def == branch) return ""
        return "\n\n⚠️ 注意：远程的**默认分支是 `" + def + "`**（GitHub 首页展示的是它）。" +
            "你刚推的是 `" + branch + "` —— 要让首页更新，需要把改动合并进 `" + def + "`（或直接推 `" + def + "`）。"
    }
}
