package com.zhixueyao.agent

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zhixueyao.mcp.McpManager
import com.zhixueyao.mcp.McpServerRegistry
import com.intellij.openapi.diagnostic.Logger

/**
 * 插件启动时做的事。
 *
 * 目前两件，都是「用户不该被要求手动做」的：
 *  1. 建出全局配置目录 `~/.zhixueyao/`（含 skills/ 与 README）；
 *  2. 把 `~/.zhixueyao/mcp.json` 里的服务器合并进注册表 ——
 *     这样用户手改配置文件也能生效，跟别的 agent 一个体验。
 *
 * 放在 `postStartupActivity` 里而不是插件 `init`：启动阶段做 IO 会拖慢 IDE，
 * 要等项目框架起来、不在关键路径上时再做。
 */
class ZhixueyaoStartup : ProjectActivity {

    private val log = Logger.getInstance(ZhixueyaoStartup::class.java)

    override suspend fun execute(project: Project) {
        ZhixueyaoHome.ensure()
        mergeHomeMcpConfig()
    }

    /**
     * 合并 `~/.zhixueyao/mcp.json`。
     *
     * 规则：**以文件为准补齐缺失项**，不删除 IDE 里已有的同名项 ——
     * 文件是用户手写的（他可能只写了一半），IDE 存储是插件管的，
     * 谁也别覆盖谁，缺什么补什么。
     */
    private fun mergeHomeMcpConfig() {
        runCatching {
            val file = ZhixueyaoHome.mcpFile()
            if (!file.isFile) return
            val configs = McpManager.parseConfigJson(file.readText())
            if (configs.isEmpty()) return
            val registry = McpServerRegistry.getInstance()
            val existing = registry.servers.map { it.id }.toSet()
            val added = configs.filter { it.id !in existing }
            if (added.isNotEmpty()) {
                registry.servers.addAll(added)
                log.info("已从 ${file.path} 合并 ${added.size} 个 MCP 服务器")
            }
        }.onFailure { log.info("读取全局 mcp.json 失败：${it.message}") }
    }
}
