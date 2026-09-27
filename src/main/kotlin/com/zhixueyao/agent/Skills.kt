package com.zhixueyao.agent

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 技能库。
 *
 * 思路与 WorkBuddy / Claude 的 Skills 一致：**渐进式加载**。
 * 系统提示词里只放「技能名 + 一句话说明」，模型判断这次任务用得上哪个，
 * 再用 `skill` 工具把它的完整步骤取回来 —— 而不是把所有技能正文都塞进提示词
 * （那样既费 token，又会互相干扰）。
 *
 * 一个技能就是一个目录，目录里必须有 `SKILL.md`：
 *
 * ```
 * my-skill/
 *   SKILL.md          ← 必需：YAML frontmatter 写 name/description，正文写步骤
 *   references 下的 md ← 可选：参考文档，模型用 read_file 按需读
 *   scripts 下的脚本   ← 可选：模型可以照着跑
 * ```
 *
 * 扫描两处（项目内优先，便于随工程走）：
 *  - `<项目>/.zhixueyao/skills/`
 *  - `<IDE 配置目录>/zhixueyao-skills/`（全局，跨项目复用）
 */
object Skills {

    private val log = Logger.getInstance(Skills::class.java)

    data class Skill(
        val name: String,
        val description: String,
        val body: String,
        val dir: File,
        val source: String
    ) {
        /** 技能目录下的附属文件（相对路径），供模型判断要不要去读 */
        fun assets(): List<String> {
            val out = mutableListOf<String>()
            dir.walkTopDown()
                .filter { it.isFile && it.name != SKILL_FILE }
                .take(40)
                .forEach { out.add(it.relativeTo(dir).path.replace('\\', '/')) }
            return out.sorted()
        }
    }

    const val SKILL_FILE = "SKILL.md"

    /** 提示词里最多列多少个技能（每个约 200 字，见 [catalogForPrompt]） */
    const val MAX_CATALOG = 60

    /** 项目级技能目录 */
    fun projectDir(project: Project?): File? =
        project?.basePath?.let { File(File(it, ".zhixueyao"), "skills") }

    /**
     * 全局技能目录：`~/.zhixueyao/skills`。
     *
     * 放在用户主目录下（与其他 agent 的 `~/.claude`、`~/.agents` 一致），
     * 好处是换 IDE / 换机器时把 `.zhixueyao` 整个拷走即可，用户也能直接看懂这个路径。
     */
    fun globalDir(): File = com.zhixueyao.agent.ZhixueyaoHome.skills()

    /** 老版本把全局技能放在 IDE 配置目录下，这里继续兼容扫描（只读） */
    private fun legacyGlobalDir(): File = File(PathManager.getConfigPath(), "zhixueyao-skills")

    /**
     * 其他 agent 用过的技能目录（只读扫描，不写入）。
     *
     * 用户机器上往往已经有一份现成的技能库（比如 `~/.agents/skills/`），
     * 让止血药**直接认出来**比让人复制一遍强得多 —— 模型问「我有哪些 skills」时，
     * 得到的应该是「你现有的这些 + 我自己的」。
     *
     * 只读：扫描进来能用；要新建技能仍写到 [globalDir]，
     * 免得把别人的目录结构搞乱。
     */
    fun externalDirs(): List<File> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val candidates = listOf(
            File(home, ".agents/skills"),
            File(home, ".claude/skills"),
            File(home, ".codebuddy/skills")
        )
        return candidates.filter { it.isDirectory }
    }

    /**
     * 清单缓存：`all()` 会被系统提示词每条消息都调一次，
     * 每次都扫盘没必要。装/删技能后调 [invalidateCache] 清掉。
     */
    @Volatile
    private var cache: List<Skill>? = null

    /** 技能库有变动（安装、删除、改文件）后调用 */
    fun invalidateCache() {
        cache = null
    }

    /** 扫描全部技能。项目内的同名技能覆盖全局的 */
    fun all(project: Project?): List<Skill> {
        cache?.let { return it }
        val found = LinkedHashMap<String, Skill>()
        // 顺序即优先级：项目 → 本插件全局 → 外部技能库（同名以靠前的为准）
        val sources = buildList {
            projectDir(project)?.let { add(it to "项目") }
            add(globalDir() to "全局")
            legacyGlobalDir().takeIf { it.isDirectory }?.let { add(it to "全局(旧)") }
            externalDirs().forEach { add(it to "外部") }
        }
        for ((dir, source) in sources) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val skill = parse(sub, source) ?: return@forEach
                found[skill.name] = skill
            }
        }
        return found.values.sortedBy { it.name }.also { cache = it }
    }

    fun byName(project: Project?, name: String): Skill? =
        all(project).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun parse(dir: File, source: String): Skill? {
        val file = File(dir, SKILL_FILE)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val (meta, body) = splitFrontMatter(text)
            val name = meta["name"]?.takeIf { it.isNotBlank() } ?: dir.name
            val desc = meta["description"]?.takeIf { it.isNotBlank() } ?: "（无说明）"
            Skill(name, desc, body.trim(), dir, source)
        }.onFailure { log.warn("技能解析失败：${dir.path}", it) }.getOrNull()
    }

    /** 切出 `---` 包裹的 YAML frontmatter（只认 name/description 这类简单键值） */
    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return emptyMap<String, String>() to normalized
        val end = normalized.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to normalized
        val meta = LinkedHashMap<String, String>()
        normalized.substring(3, end).lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isNotEmpty()) meta[key] = value
            }
        }
        return meta to normalized.substring(end + 4)
    }

    /**
     * 系统提示词里的技能目录。
     *
     * 只列「名字 + 说明 + 附属文件数」，不贴正文 —— 正文等模型用 skill 工具来取。
     * 一个技能都没有时返回空串（提示词里不出现这一节）。
     */
    fun catalogForPrompt(project: Project?): String {
        val all = all(project)
        if (all.isEmpty()) return ""
        // 目录是**每轮请求都要带**的，所以必须有上限。
        //
        // 没有上限时，用户往里丢一个几千个技能的库（很常见，网上有 6000+ 的合集），
        // 每轮请求会平白多出几百 KB —— 不是「慢一点」，是直接发不出去或贵到离谱。
        // 超出的部分不列出，但**明确告诉模型有多少没列**，
        // 免得它以为「本机只有这些技能」。
        val list = all.take(MAX_CATALOG)
        val skipped = all.size - list.size
        return buildString {
            append("## 可用技能（按需加载）\n\n")
            append("下面是本机可用的技能。它们是你**没见过的专业流程**，")
            append("遇到对应场景时先用 `skill` 工具把正文取回来再动手，不要凭印象编步骤。\n\n")
            if (skipped > 0) {
                append("（技能库共 ").append(all.size).append(" 个，这里只列了前 ")
                append(list.size).append(" 个；")
                append("如果你确信某个技能存在但没列出，可以直接用 `skill` 工具按名字试。)\n\n")
            }
            for (s in list) {
                // 每个技能只留「名字 + 一句话」，尽量压短。
                //
                // 这一段是**每轮都要带**的固定开销（探针实测：7 个技能就有 1400+ tokens）。
                // 原来还列了「附属文件」清单 —— 那个没用：模型真要用某个技能时，
                // 会用 skill 工具把正文取回来，那时它自己就能看到有哪些附属文件。
                // 描述也从 200 字压到 110 字：够判断「这个技能是不是我要的」就停。
                append("- **").append(s.name).append("**：")
                append(s.description.replace("\n", " ").take(110))
                append("\n")
            }
        }
    }

    /** 确保两个目录存在（设置页点「打开目录」时用） */
    fun ensureDirs(project: Project?): List<File> {
        // 只创建「本插件自己的」目录；外部目录只读，不去动它
        val dirs = listOfNotNull(projectDir(project), globalDir())
        dirs.forEach { runCatching { it.mkdirs() } }
        return dirs
    }
}
