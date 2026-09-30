package com.zhixueyao.git

import java.io.File

/**
 * 提交前的**守卫**：扫一遍暂存区，把「明显不该提交的东西」拦下来。
 *
 * ## 为什么要有它
 *
 * 真实事故：用户在终端里敲错一条命令（`witch -c restore-readme c69c6ba`，
 * 本想敲 `git switch ...`），误生成一个文件；随后的 `git add -A` 把它卷进提交，
 * 一路推到了 GitHub —— 谁都没发现，直到翻仓库时看到。
 *
 * 这类事故的共同点：**东西在暂存区里，但没人「看」过它**。
 * 所以守卫就做一件事：**在提交前把暂存区看一遍**，可疑的拦下来。
 *
 * ## 检查什么（宁缺毋滥，只拦高置信度的）
 *
 * - 文件名里带空格 —— 正常源码文件极少带空格，而命令敲错生成的文件几乎都带
 * - 0 字节的新文件
 * - 超过 50MB 的文件（GitHub 的提醒线）
 * - 文件名像密钥/凭据（`.env`、`*.pem`、`id_rsa`、`secrets*`……）
 * - 内容里出现高置信度的密钥形状（`ghp_` / `sk-` / `AKIA` / PRIVATE KEY……）
 *
 * ## 拦下之后
 *
 * 不是「禁止提交」，而是**要求看一眼**：把问题列出来，
 * 确认没问题就带 `allow_suspicious=true` 重新提交；
 * 真有问题就把文件撤下来。守卫的价值在于「看这一眼」，不在于禁止。
 */
object CommitGuard {

    enum class Kind { SUSPICIOUS_NAME, LARGE_FILE, SECRET_NAME, SECRET_CONTENT }

    data class Issue(val kind: Kind, val file: String, val detail: String)

    /** 文件名像密钥/凭据的 */
    private val SECRET_NAME_PATTERNS = listOf(
        Regex("(?i)^\\.env($|\\.)"),
        Regex("(?i)\\.(pem|key|p12|jks|keystore|kdbx)$"),
        Regex("(?i)^id_(rsa|dsa|ecdsa|ed25519)"),
        Regex("(?i)^secrets?(\\.|$)"),
        Regex("(?i)^local\\.properties$"),
        Regex("(?i)^\\.(npmrc|netrc|pgpass)$"),
        Regex("(?i)^credentials(\\.|$)"),
    )

    /** 内容里高置信度的密钥形状（只抓这些；宽泛的规则会天天误报，反而没人看） */
    private val SECRET_CONTENT = listOf(
        Regex("ghp_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("sk-[A-Za-z0-9]{24,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
    )

    private const val LARGE_BYTES = 50L * 1024 * 1024

    /** 扫暂存区。没有问题返回空列表 */
    fun check(dir: File): List<Issue> {
        val issues = mutableListOf<Issue>()

        // ---------- 1) 暂存清单逐项检查 ----------
        val ns = run(dir, listOf("git", "diff", "--cached", "--name-status"))
        for (line in ns.lines()) {
            if (line.isBlank()) continue
            val status = line.substringBefore('\t').trim()
            val name = line.substringAfterLast('\t').trim()
            if (name.isEmpty()) continue

            if (' ' in name) {
                issues += Issue(
                    Kind.SUSPICIOUS_NAME, name,
                    "文件名里带空格，像是命令敲错时误生成的文件（正常源码文件几乎不会这样）"
                )
            }

            val base = name.substringAfterLast('/')
            if (SECRET_NAME_PATTERNS.any { it.containsMatchIn(base) }) {
                issues += Issue(Kind.SECRET_NAME, name, "文件名像密钥 / 凭据文件")
            }

            if (status.startsWith("A")) {
                val f = File(dir, name)
                if (f.isFile) {
                    val size = f.length()
                    if (size == 0L) issues += Issue(Kind.SUSPICIOUS_NAME, name, "0 字节的新文件")
                    if (size > LARGE_BYTES) {
                        issues += Issue(Kind.LARGE_FILE, name, "体积约 ${size / 1024 / 1024} MB，超过 GitHub 的 50MB 提醒线")
                    }
                }
            }
        }

        // ---------- 2) 内容扫描：只看新增行 ----------
        val diff = run(dir, listOf("git", "diff", "--cached", "-U0"))
        if (diff.isNotBlank()) {
            var file = "?"
            for (line in diff.lines()) {
                if (line.startsWith("+++ b/")) {
                    file = line.removePrefix("+++ b/").trim()
                    continue
                }
                if (!line.startsWith("+") || line.startsWith("+++")) continue
                for (p in SECRET_CONTENT) {
                    val m = p.find(line) ?: continue
                    // 只留一小截做提示，避免把完整密钥又打印一遍
                    val masked = m.value.take(6) + "…（已截断）"
                    issues += Issue(Kind.SECRET_CONTENT, file, "内容里出现疑似密钥：`$masked`")
                    break
                }
            }
        }

        return issues.distinctBy { Triple(it.kind, it.file, it.detail) }
    }

    /** 把问题渲染成给模型/用户看的中文报告 */
    fun render(issues: List<Issue>): String = buildString {
        append("**提交被拦下了** —— 暂存区里有 ").append(issues.size).append(" 处可疑内容：\n\n")
        issues.forEach { i ->
            val tag = when (i.kind) {
                Kind.SUSPICIOUS_NAME -> "可疑文件"
                Kind.LARGE_FILE -> "超大文件"
                Kind.SECRET_NAME -> "敏感文件名"
                Kind.SECRET_CONTENT -> "疑似密钥"
            }
            append("- [").append(tag).append("] `").append(i.file).append("` — ").append(i.detail).append('\n')
        }
        append("\n**如果这些确实不该提交**：把它们从暂存区撤下来（或让用户处理），再重新 `commit`。\n")
        append("**如果确认没问题**（比如那个带空格的文件名是有意的）：重新调用 `commit` 并带上 `allow_suspicious=true`。")
    }

    private fun run(dir: File, args: List<String>): String =
        com.zhixueyao.tools.ProcessRunner.run(args, dir, null, 15_000).stdout
}
