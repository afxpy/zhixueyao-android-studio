package com.zhixueyao.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * 止血药的**全局配置目录**：`~/.zhixueyao/`。
 *
 * 为什么放在用户主目录下、而不是 IDE 配置目录里：
 *  - 跟别的 agent 一致（`~/.claude`、`~/.codex`、`~/.agents` 都是这个路子），
 *    用户换了 IDE、换了机器，把 `.zhixueyao` 拷过去就能接着用；
 *  - 用户能直接看懂、直接手改 —— 配置文件放在 IDE 的深层配置目录里，
 *    没人找得到，也就谈不上「自己维护」。
 *
 * 目录结构：
 * ```
 * ~/.zhixueyao/
 *   skills/<技能名>/SKILL.md   ← 全局技能（跨项目可用）
 *   mcp.json                   ← MCP 服务器配置（与 Claude / Cursor 同格式）
 * ```
 *
 * 插件首次启动时会自动建出这些目录（见 `ZhixueyaoStartup`），
 * 这样用户打开文件管理器就能看到、往里放东西。
 */
object ZhixueyaoHome {

    private val log = Logger.getInstance(ZhixueyaoHome::class.java)

    /** 全局根目录 `~/.zhixueyao` */
    fun root(): File = File(System.getProperty("user.home") ?: ".", ".zhixueyao")

    /** 全局技能目录 */
    fun skills(): File = File(root(), "skills")

    /** 全局 MCP 配置（与 Claude/Cursor 的 mcpServers 结构一致，方便互相搬） */
    fun mcpFile(): File = File(root(), "mcp.json")

    /**
     * 确保目录存在。插件启动时调用一次即可 —— 让用户「看得见」这个目录，
     * 而不是等他自己猜路径去建。
     *
     * 首次创建时放一个 README 说明用途：用户翻到这儿至少知道是干什么的。
     */
    fun ensure() {
        runCatching {
            root().mkdirs()
            skills().mkdirs()
            val readme = File(root(), "README.txt")
            if (!readme.isFile) {
                readme.writeText(
                    """
                    止血药 · 全局配置目录
                    ====================

                    这个目录由 Android Studio / IntelliJ 的「止血药」插件自动创建，
                    放的是一些**跨项目复用**的东西。换机器时整个目录拷走即可。

                    skills/     全局技能库。一个技能 = 一个子目录 + 里面的 SKILL.md，
                                第一段的 frontmatter 写 name 与 description，正文写步骤。
                                项目里也可以放一份（<项目>/.zhixueyao/skills/），同名的以项目为准。

                    mcp.json    MCP 服务器配置，结构与 Claude Desktop / Cursor 一致：
                                { "mcpServers": { "名字": { "command": "...", "args": [...] } } }
                                插件启动时会读它；在设置页里改配置也会写回这里。

                    改完不用重启：设置页点「重新扫描」就能刷新。
                    """.trimIndent()
                )
            }
        }.onFailure { log.info("创建全局配置目录失败：${it.message}") }
    }
}
