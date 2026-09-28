package com.zhixueyao.tools

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zhixueyao.agent.Skills
import com.zhixueyao.mcp.McpCatalog
import com.zhixueyao.mcp.McpManager
import com.zhixueyao.mcp.McpServerConfig
import com.zhixueyao.mcp.McpServerRegistry
import com.zhixueyao.settings.McpServerController
import com.zhixueyao.settings.ZhixueyaoSettings
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.io.File

/**
 * 技能安装工具。
 *
 * 为什么要有它：技能的价值全在「流程知识」上，而用户往往说不清自己机器上
 * 该配什么路径、frontmatter 该写什么格式。让模型**自己把技能写进技能库**，
 * 用户只需一句话「给我装个写 Jetpack Compose 页面的技能」，剩下的它自己办 ——
 * 这才是「能干活」和「只能聊」的区别。
 *
 * 默认装到**全局**技能目录（`<IDE 配置>/zhixueyao-skills/`），跨项目可用；
 * 需要跟工程走时传 `scope=project`。
 */
class InstallSkillTool : AgentTool {

    private val log = Logger.getInstance(InstallSkillTool::class.java)

    override val name = "install_skill"

    override val description =
        "技能库的读写入口：安装 / 更新 / 删除 / 列出技能。装完立刻能在「可用技能」里看到。" +
            "**scope 怎么选**：本项目的做法、约定、踩过的坑 → project（随工程走，别人拉到仓库就有）；" +
            "跨项目通用的方法论 → global（默认）。" +
            "同一份经验只会越攒越准 —— 发现已有同名技能时用 action=install + overwrite=true 更新它，" +
            "不要另起一个新名字（否则技能库会越长越乱）。" +
            "frontmatter 由本工具自动生成 —— 你给 name / description / body，" +
            "另外建议填 triggers（让它在对的时候被自动想起来）；" +
            "如果是「一串流程」，用 steps 列出它由哪几个技能组成。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "name" to jsonObj(
                "type" to "string".toJson(),
                "description" to "技能名，短横线风格（如 compose-screen-scaffold），会作为目录名"
            ),
            "description" to jsonObj(
                "type" to "string".toJson(),
                "description" to "一句话说明「什么时候该用它」。这句话是模型日后唯一能看到的线索，要具体"
            ),
            "body" to jsonObj(
                "type" to "string".toJson(),
                "description" to "技能正文：可执行的步骤、判据、常见坑。写命令与检查清单，不要写空话"
            ),
            "scope" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("global".toJson(), "project".toJson()),
                "description" to "global=跨项目可用（默认）；project=只给当前项目"
            ),
            "files" to jsonObj(
                "type" to "object".toJson(),
                "description" to "可选的附属文件：{相对路径: 内容}，比如模板、示例代码"
            ),
            "action" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("install".toJson(), "delete".toJson(), "list".toJson()),
                "description" to "install（默认）= 写入或更新；delete = 删掉这个技能；list = 列出全部技能" +
                    "（含各自在哪个技能库里，用于判断该更新还是新建）"
            ),
            "overwrite" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "已存在同名技能时必须显式传 true 才覆盖 —— 防止手滑把别人写好的技能冲掉。" +
                    "更新自己之前装的技能就传 true"
            ),
            "triggers" to jsonObj(
                "type" to "string".toJson(),
                "description" to "触发短语，逗号分隔（2-5 条）。**强烈建议填**：" +
                    "用户说出这些词时，这个技能会被自动匹配到并提示你加载。" +
                    "写「用户可能的不同说法」而不是把技能名换个说法；" +
                    "**别写单字**（「改」「图」这种会误命中每一句话）"
            ),
            "steps" to jsonObj(
                "type" to "string".toJson(),
                "description" to "复合技能用：这个流程由哪几个技能按顺序组成（逗号分隔的技能名）。" +
                    "比如「发布流程」= 「构建, 泄漏检查, 打包」。普通技能不要填"
            )
        ),
        "required" to jsonArr("name".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        when (args.str("action")?.lowercase()) {
            "list" -> return listSkills(project)
            "delete" -> return deleteSkill(project, args)
        }

        val rawName = args.str("name")?.trim().orEmpty()
        if (rawName.isEmpty()) return ToolResult.error("缺少 name 参数")

        // 目录名安全化：技能名会直接当目录名用，必须挡住 ../ 与路径分隔符
        val name = rawName.lowercase()
            .replace(Regex("[^a-z0-9\\-_]"), "-")
            .trim('-')
            .take(48)
        if (name.isEmpty()) return ToolResult.error("name 里没有可用字符（只允许字母、数字、- 和 _）")

        val desc = args.str("description")?.trim().orEmpty()
        val body = args.str("body")?.trim().orEmpty()
        if (desc.isEmpty()) return ToolResult.error("缺少 description：模型日后只靠它判断该不该用这个技能")
        if (body.isEmpty()) return ToolResult.error("缺少 body：技能正文是它的全部价值")

        val useProject = args.str("scope")?.equals("project", ignoreCase = true) == true
        val root = if (useProject) Skills.projectDir(project) else Skills.globalDir()
        if (root == null) return ToolResult.error("当前没有打开项目，无法安装到项目技能库；改用 scope=global")

        val dir = File(root, name)
        // 覆盖保护：已存在就必须显式 overwrite=true。
        // 不设这道闸的话，模型很容易因为「描述写得差不多」就把别人（或用户自己）
        // 写的技能冲掉 —— 那是不可恢复的。
        val exists = File(dir, Skills.SKILL_FILE).isFile
        if (exists && args.bool("overwrite") != true) {
            return ToolResult.error(
                "技能「$name」已存在（${dir.path}）。要更新它就在参数里加 overwrite=true；" +
                    "想保留旧的另起一个名字。也可以先用 action=list 看看现有技能都有什么。"
            )
        }
        return try {
            dir.mkdirs()
            File(dir, Skills.SKILL_FILE).writeText(
                buildString {
                    append("---\n")
                    append("name: ").append(name).append('\n')
                    append("description: ").append(desc.replace("\n", " ")).append('\n')
                    // 触发短语**必须能写进来**。
                    //
                    // 之前只有解析没有写入 —— 能读不能写，等于这个字段
                    // 只能靠用户手工编辑 SKILL.md 才有。而自动匹配恰恰是它的价值所在：
                    // 模型自己攒的技能，应该自己顺手把触发词填上，
                    // 不然后面永远匹配不到（写了等于白写）。
                    val trig = args.str("triggers")?.trim().orEmpty()
                    if (trig.isNotEmpty()) {
                        append("triggers: ")
                        append(trig.split(',', '，').map { it.trim() }
                            .filter { it.isNotEmpty() }.take(6).joinToString(", "))
                        append('\n')
                    }
                    val stepsArg = args.str("steps")?.trim().orEmpty()
                    if (stepsArg.isNotEmpty()) {
                        append("steps: ")
                        append(stepsArg.split(',', '，').map { it.trim() }
                            .filter { it.isNotEmpty() }.take(12).joinToString(", "))
                        append('\n')
                    }
                    append("---\n\n")
                    append(body).append('\n')
                }
            )

            // 附属文件：逐层建目录再写，路径里出现 ../ 直接拒绝
            val extras = mutableListOf<String>()
            // Obj 内部是 LinkedHashMap<String, Json>，直接遍历 fields
            args.obj("files")?.fields?.forEach { (rel, value) ->
                val text = value.asStringOrNull ?: return@forEach
                val safe = rel.replace('\\', '/')
                if (safe.contains("..")) return@forEach
                val target = File(dir, safe)
                if (!target.canonicalPath.startsWith(dir.canonicalPath)) return@forEach
                target.parentFile?.mkdirs()
                target.writeText(text)
                extras.add(safe)
            }

            // 清掉技能列表缓存，让新技能立刻出现在「可用技能」里
            Skills.invalidateCache()

            ToolResult(
                buildString {
                    append("技能「").append(name).append("」已").append(if (exists) "更新到" else "安装到")
                    append(if (useProject) "项目技能库" else "全局技能库")
                    append("：").append(dir.path).append('\n')
                    if (extras.isNotEmpty()) append("附属文件：").append(extras.joinToString("、")).append('\n')
                    val trigWritten = args.str("triggers")?.trim().orEmpty()
                    if (trigWritten.isNotEmpty()) {
                        append("触发词：").append(trigWritten).append('\n')
                        append("（用户说到这些词时它会被自动匹配到并提示加载）\n")
                    } else {
                        // 没写触发词时提醒一句 —— 这一条最容易被漏，而漏了就等于没用
                        append("⚠️ 没填 triggers：这个技能只能靠你自己想起来用它。")
                        append("下次更新时建议补上（逗号分隔，2-5 条）。\n")
                    }
                    val stepsWritten = args.str("steps")?.trim().orEmpty()
                    if (stepsWritten.isNotEmpty()) append("流程步骤：").append(stepsWritten).append('\n')
                    append("下一条消息起它就会出现在系统提示词的「可用技能」清单里。")
                }
            )
        } catch (e: Exception) {
            log.warn("安装技能失败", e)
            ToolResult.error("写入技能目录失败：${e.message}")
        }
    }

    /**
     * 列出全部技能（含来源）。
     *
     * 为什么需要它：系统提示词里的「可用技能」**有截断**
     * （上限 60 个、描述截到 110 字）。模型想确认「这个技能是不是已经装过」
     * 时，靠提示词里那份清单可能看不全，于是会新建一个同义的重复技能 ——
     * 技能库就是这样慢慢变乱的。
     */
    private fun listSkills(project: Project): ToolResult {
        val all = Skills.all(project)
        if (all.isEmpty()) return ToolResult("技能库是空的。可以直接用 action=install 装一个。")
        return ToolResult(
            buildString {
                append("共 ").append(all.size).append(" 个技能：\n\n")
                all.groupBy { it.source }.forEach { (source, list) ->
                    append("## ").append(source).append("（").append(list.size).append("）\n")
                    list.forEach { append("- ").append(it.name).append("　").append(it.description.take(80)).append('\n') }
                    append('\n')
                }
                append("要更新某个技能：action=install + 同名 + overwrite=true。")
            }
        )
    }

    /**
     * 删除一个技能。
     *
     * **只让自己删得动这几类目录**（项目技能库 / 全局技能库）；
     * 「外部」目录是别人家的（比如 `~/.workbuddy/skills`），只读扫描，不许动。
     * 另外删之前必须**确认名字完全对得上**，避免模型手滑删错。
     */
    private fun deleteSkill(project: Project, args: Json.Obj): ToolResult {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult.error("删除需要指定 name")

        val skill = Skills.byName(project, name)
            ?: return ToolResult.error("没有找到技能「$name」。可以先用 action=list 看看有哪些。")

        // 只允许删本插件自己的两个库
        val writableRoots = listOfNotNull(Skills.projectDir(project), Skills.globalDir())
        val canonical = runCatching { skill.dir.canonicalPath }.getOrDefault(skill.dir.path)
        val ok = writableRoots.any { root ->
            val rc = runCatching { root.canonicalPath }.getOrDefault(root.path)
            canonical.startsWith(rc)
        }
        if (!ok) {
            return ToolResult.error(
                "「$name」来自外部技能目录（${skill.dir.parentFile?.path}），那里是别的工具在管，" +
                    "本插件只读不改。要删请用户自己去那个目录处理。"
            )
        }

        return try {
            skill.dir.walkBottomUp().forEach { runCatching { it.delete() } }
            if (skill.dir.exists()) {
                ToolResult.error("删除失败：${skill.dir.path} 可能被占用")
            } else {
                Skills.invalidateCache()
                ToolResult("技能「$name」已从${skill.source}技能库删除：${skill.dir.path}")
            }
        } catch (e: Exception) {
            log.warn("删除技能失败", e)
            ToolResult.error("删除失败：${e.message}")
        }
    }
}

/**
 * MCP 服务器管理工具。
 *
 * 让模型能自己装 MCP：用户说「给我接个浏览器」时，它可以直接从内置清单里
 * 挑 `playwright` 装上并启用，而不是回一句「请你去设置里手动配置」。
 *
 * 安全边界：只操作**插件自己的配置**，不执行任何安装命令 ——
 * 真正的 `npx` 启动由插件在连接时完成，与用户手填配置等价。
 */
class ManageMcpTool : AgentTool {

    override val name = "manage_mcp"

    override val description =
        "查询和增删 MCP 服务器。用户想接外部能力（浏览器、GitHub、数据库、网页抓取等）时用它：" +
            "action=catalog 看内置清单，action=install 一键装上并启用，action=list 看当前配置。" +
            "install/enable 会**当场连接并返回结果**：连上了会给出加载到的工具名，" +
            "连不上会给出具体错误 —— 必须如实转述，**不要**在连接失败时说「已安装成功」。" +
            "装好的工具本轮对话接下来的步骤就能直接调用。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "action" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr(
                    "list".toJson(), "catalog".toJson(), "install".toJson(),
                    "enable".toJson(), "disable".toJson(), "remove".toJson(), "import".toJson()
                ),
                "description" to "要做的操作"
            ),
            "id" to jsonObj(
                "type" to "string".toJson(),
                "description" to "服务器 id（catalog 里的 id，或 list 里的现有 id）；install/enable/disable/remove 必填"
            )
        ),
        "required" to jsonArr("action".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val action = args.str("action")?.lowercase().orEmpty()
        val registry = McpServerRegistry.getInstance()

        return when (action) {
            "list" -> {
                if (registry.servers.isEmpty()) {
                    ToolResult("当前没有配置任何 MCP 服务器。可以用 action=catalog 看内置清单。")
                } else {
                    ToolResult(
                        buildString {
                            append("当前 MCP 服务器（").append(registry.servers.size).append(" 个）：\n")
                            registry.servers.forEach {
                                append("- ").append(it.id).append("（").append(it.name).append("）")
                                append(if (it.enabled) "  已启用" else "  已停用")
                                if (it.command.isNotBlank()) append("  ").append(it.command)
                                if (it.url.isNotBlank()) append("  ").append(it.url)
                                if (it.lastError.isNotBlank()) append("  上次错误：").append(it.lastError.take(120))
                                append('\n')
                            }
                        }
                    )
                }
            }

            "catalog" -> ToolResult(
                buildString {
                    append("内置 MCP 清单（用 action=install + id 安装）：\n")
                    McpCatalog.entries.forEach {
                        append("- ").append(it.id).append("（").append(it.name).append("）：").append(it.summary)
                        if (it.requiredEnv.isNotEmpty()) {
                            append("　需要环境变量：").append(it.requiredEnv.keys.joinToString("、"))
                        }
                        append('\n')
                    }
                }
            )

            "install" -> {
                val id = args.str("id")?.trim().orEmpty()
                val entry = McpCatalog.byId(id)
                    ?: return ToolResult.error(
                        "清单里没有「$id」。可选：" + McpCatalog.entries.joinToString("、") { it.id }
                    )
                val projectPath = project.basePath ?: ""
                val config = McpCatalog.toConfig(entry, projectPath)
                if (registry.servers.any { it.id == config.id }) {
                    return ToolResult("「${entry.name}」已经在配置里了。用 action=enable 启用，或先 remove。")
                }
                registry.servers.add(config)
                connectAndReport(config, entry.name, entry.requiredEnv)
            }

            "enable", "disable" -> {
                val id = args.str("id")?.trim().orEmpty()
                val cfg = registry.servers.firstOrNull { it.id == id }
                    ?: return ToolResult.error("没有找到服务器「$id」，先用 action=list 看现有的")
                cfg.enabled = action == "enable"
                if (cfg.enabled) {
                    connectAndReport(cfg, cfg.name, emptyMap())
                } else {
                    McpManager.getInstance().disconnect(cfg.id)
                    ToolResult("「${cfg.name}」已停用，连接已断开。")
                }
            }

            "remove" -> {
                val id = args.str("id")?.trim().orEmpty()
                val cfg = registry.servers.firstOrNull { it.id == id }
                    ?: return ToolResult.error("没有找到服务器「$id」")
                McpManager.getInstance().disconnect(cfg.id)
                registry.servers.remove(cfg)
                ToolResult("已移除「${cfg.name}」并断开连接。")
            }

            "import" -> {
                val imported = McpCatalog.importFromClaude()
                if (imported.isEmpty()) {
                    ToolResult("没有找到可导入的 Claude Desktop 配置（claude_desktop_config.json）")
                } else {
                    val added = imported.filter { c -> registry.servers.none { it.id == c.id } }
                    registry.servers.addAll(added)
                    val lines = added.map { connectAndReport(it, it.name, emptyMap()).text }
                    ToolResult("从 Claude 配置导入 ${added.size} 个：\n\n" + lines.joinToString("\n\n"))
                }
            }

            else -> ToolResult.error("未知 action：$action（可选 list / catalog / install / enable / disable / remove / import）")
        }
    }

    /**
     * 连上服务器并**如实汇报结果**。
     *
     * 以前这里只做 `registry.servers.add(config)` 就返回「已安装并启用」——
     * 既没真的去连，也没验证。模型拿到「成功」自然就回答用户「装好了」，
     * 用户完全不知道到底连上没有（反馈原话：「都不知道有没有安装好就给我回答了」）。
     *
     * 现在真连一次，把**连上几个工具**或**具体错误**原样交给模型，
     * 它才有东西可汇报。连不上时明确要求它别报成功。
     */
    private fun connectAndReport(
        config: McpServerConfig,
        name: String,
        requiredEnv: Map<String, String>
    ): ToolResult {
        val envHint = if (requiredEnv.isNotEmpty()) {
            "这个服务器需要环境变量：" +
                requiredEnv.entries.joinToString("、") { it.key + "（" + it.value + "）" } +
                "。要先在设置 → 插件里补上，否则连不上。"
        } else ""

        return try {
            val server = McpManager.getInstance().connect(config)
            val toolNames = server.tools.take(12).joinToString("、") { it.name }
            ToolResult(
                buildString {
                    append("已连接「").append(name).append("」，加载 ").append(server.tools.size)
                        .append(" 个工具")
                    if (toolNames.isNotBlank()) append("：").append(toolNames)
                    append("。\n")
                    append("这些工具名带 mcp__ 前缀，本轮对话接下来的步骤就能直接调用。")
                }
            )
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            ToolResult.error(
                buildString {
                    append("「").append(name).append("」配置已写入，但**连接失败**：\n")
                    append(detail).append('\n')
                    if (envHint.isNotBlank()) append(envHint).append('\n')
                    append("请如实告诉用户「配置已保存但没连上」，并把上面的错误转述给他，")
                    append("不要报成功。")
                }
            )
        }
    }
}