package com.zhixueyao.mcp

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * MCP Servers 市场（内置精选清单 + 从本地 Claude 配置导入）。
 *
 * 为什么做成「清单 + 一键安装」而不是让用户手填命令：
 * MCP 服务器的启动命令格式对普通用户完全不透明（`npx -y @xxx/mcp-server`、
 * Windows 还要走 `cmd /c`）。让用户复制粘贴 JSON 是最容易出错的一步，
 * 所以内置一份常用清单，点一下就填好；细节仍可在对话框里改。
 *
 * 清单刻意保持**本地静态**：不发任何网络请求 —— 装插件时不该偷偷联网。
 * 想用社区市场的人，自己配 `mcpServers` 指向对应服务器即可。
 */
object McpCatalog {

    private val log = Logger.getInstance(McpCatalog::class.java)

    data class Entry(
        val id: String,
        val name: String,
        val summary: String,
        val command: String,
        val args: List<String>,
        val env: Map<String, String> = emptyMap(),
        /** 需要在环境里填的敏感项（键 → 说明），安装时提示用户补 */
        val requiredEnv: Map<String, String> = emptyMap(),
        val homepage: String = ""
    )

    /**
     * 内置精选。
     *
     * 全部用官方发布的包（官方 scoped 包（@modelcontextprotocol 名下那一批）），
     * 参数里预置好最常见的用法，用户装完只需补 API Key。
     */
    val entries: List<Entry> = listOf(
        Entry(
            id = "filesystem",
            name = "文件系统",
            summary = "读写指定目录下的文件（默认给当前项目）",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "{project}"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "git",
            name = "Git",
            summary = "查看提交历史、diff、分支状态",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-git", "--repository", "{project}"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "fetch",
            name = "网页抓取",
            summary = "抓取网页并转成 Markdown 交给模型",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-fetch"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        // ---- 浏览器类：让 AI 真能打开页面、点元素、读内容 ----
        Entry(
            id = "playwright",
            name = "浏览器（Playwright）",
            summary = "打开网页、截图、点击、填表、读页面内容（首次运行会下载浏览器内核，稍慢）",
            command = "npx",
            args = listOf("-y", "@playwright/mcp@latest"),
            homepage = "https://github.com/microsoft/playwright-mcp"
        ),
        Entry(
            id = "puppeteer",
            name = "浏览器（Puppeteer）",
            summary = "轻量浏览器自动化：导航、截图、执行页面脚本",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-puppeteer"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "memory",
            name = "记忆库",
            summary = "跨会话记住事实与偏好（知识图谱形式）",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-memory"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "sequential-thinking",
            name = "顺序思考",
            summary = "把复杂问题拆成可回退的多步推理",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "github",
            name = "GitHub",
            summary = "读写 Issue / PR / 仓库（需要一个 GitHub Token）",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-github"),
            requiredEnv = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "GitHub 个人访问令牌"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        ),
        Entry(
            id = "sqlite",
            name = "SQLite",
            summary = "查询本地 SQLite 数据库（安装后把路径参数改成你的库文件）",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-sqlite", "{project}/data.db"),
            homepage = "https://github.com/modelcontextprotocol/servers"
        )
    )

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** 当前操作系统是否能用 npx 这类命令（Windows 上要包一层 cmd /c） */
    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * 把清单条目变成一份可直接启用的配置。
     *
     * 两处平台差异：
     *  - Windows 上 `npx` 是 .cmd 批处理，直接 spawn 会失败，必须包 `cmd /c`；
     *  - 参数占位符 `{project}` 换成当前项目路径，避免用户手填。
     */
    fun toConfig(entry: Entry, projectPath: String): McpServerConfig {
        val args = entry.args.map { it.replace("{project}", projectPath) }
        // 这套配置里 command 存的是**整条命令行**（没有独立的 args 字段）：
        // 带空格的参数要自己加引号，否则会被当成分段参数。
        val parts = if (isWindows()) {
            listOf("cmd", "/c", entry.command) + args
        } else {
            listOf(entry.command) + args
        }
        return McpServerConfig(
            id = entry.id,
            name = entry.name,
            type = "stdio",
            command = parts.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it },
            env = entry.env.toMutableMap(),
            enabled = true
        )
    }

    /**
     * 从本地已有的 Claude Desktop 配置里导入。
     *
     * 很多人机器上已经有一份 `claude_desktop_config.json`，重复填一遍很烦。
     * 只读、不写；解析失败就安静地返回空 list（不打扰用户）。
     */
    fun importFromClaude(): List<McpServerConfig> = runCatching {
        val home = System.getProperty("user.home") ?: return emptyList()
        val candidates = listOf(
            File(home, "AppData/Roaming/Claude/claude_desktop_config.json"),
            File(home, "Library/Application Support/Claude/claude_desktop_config.json"),
            File(home, ".config/Claude/claude_desktop_config.json")
        )
        val file = candidates.firstOrNull { it.isFile } ?: return emptyList()
        val root = com.zhixueyao.util.Json.parse(file.readText())
        val servers = root.obj("mcpServers") ?: return emptyList()
        // Obj 内部就是一个 LinkedHashMap<String, Json>，直接遍历 fields
        servers.fields.mapNotNull { (key, value) ->
            val cfg = value.asObjOrNull ?: return@mapNotNull null
            val cmd = cfg.str("command") ?: return@mapNotNull null
            val argList = cfg.arr("args")?.items?.mapNotNull { it.asStringOrNull } ?: emptyList()
            McpServerConfig(
                id = "imported-" + key,
                name = key,
                type = "stdio",
                command = (listOf(cmd) + argList).joinToString(" ") {
                    if (it.contains(' ')) "\"$it\"" else it
                },
                env = cfg.obj("env")?.fields
                    ?.mapNotNull { (k, v) -> v.asStringOrNull?.let { k to it } }
                    ?.toMap()?.toMutableMap() ?: mutableMapOf(),
                enabled = true
            )
        }
    }.onFailure { log.info("未找到可导入的 Claude MCP 配置：${it.message}") }.getOrDefault(emptyList())
}