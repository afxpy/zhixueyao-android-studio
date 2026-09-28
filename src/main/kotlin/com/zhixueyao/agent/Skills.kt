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
        val source: String,
        /**
         * 触发短语：用户说出这类话时，这个技能大概率用得上。
         *
         * 借鉴自参考项目 agents-universe 的 skill 设计（它 frontmatter 里有 `triggers`）。
         * 为什么有用：目录里原来只有「名字 + 描述」，模型得靠**猜**该不该加载 ——
         * 有了触发短语，匹配从「语义猜测」变成「对照检查」，命中率明显不一样。
         *
         * 写在 frontmatter 里，逗号分隔：`triggers: 输入框, 粘贴图片, 附件`
         */
        val triggers: List<String> = emptyList(),
        /**
         * 技能自己标注的**待补缺口**（正文里写 `<!-- gaps: ["缺 X", "Y 没写"] -->`）。
         *
         * 同样是 agents-universe 的做法。价值在于：技能是**攒出来的**，
         * 第一版总有不全的地方；把「哪里还没写」显式标出来，
         * 模型下次更新它时就知道该补什么，而不是重写一遍或干脆不管。
         */
        val gaps: List<String> = emptyList(),
        /**
         * 正文里 `[[别的技能名]]` 形式的交叉引用。
         *
         * 借自参考项目 agents-universe（它用 `[[slug]]` 连接知识文件，
         * 还把「连接密度」当成完整度评分的一项）。
         * 我们取它最有价值的那个用法：**死链检测** ——
         * 引用了不存在的技能，说明要么名字写错、要么那个技能还没写，
         * 两种都该被发现而不是悄悄烂在那儿。
         */
        val crossLinks: List<String> = emptyList(),
        /**
         * 复合技能的构成步骤（frontmatter 里的 `steps: A, B`）。
         *
         * agents-universe 把技能分四型（guidance / template / executable / composite），
         * 其中 composite 是「技能编排技能」。我们取**轻量版**：
         * 不自动展开（展开会把上下文撑爆），只在加载时提示
         * 「这是个流程，按顺序去加载这几步」。
         */
        val steps: List<String> = emptyList()
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

    /** 正文里的缺口标注：gaps 注释 */
    private val GAPS_RE = Regex("""<!--\s*gaps\s*:\s*\[(.*?)]\s*-->""", RegexOption.DOT_MATCHES_ALL)

    /** 交叉引用：`[[技能名]]` */
    private val CROSS_LINK_RE = Regex("""\[\[([^\[\]\n]{1,60})]]""")

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

    /**
     * 用用户这句话去匹配技能的触发词，返回命中的技能。
     *
     * ## 为什么要有它
     *
     * 之前 `triggers` 只是**列在目录里**，等模型自己判断该不该加载 —— 那是被动的。
     * 参考项目 agents-universe 的做法是**每轮拿用户消息主动匹配**，
     * 命中的技能正文直接注入提示词（`matching_triggers(user_message)[:3]`）。
     *
     * 这里取它的**思路**但不注入正文（正文太贵）：命中后只在提示词里给一条强指令，
     * 让模型「先加载再动手」。信号从「列表里的一个词」变成「明确的一句话」，
     * 成本只有几十个 token。
     *
     * ## 三条护栏（都是他们踩出来的坑，照抄）
     *
     * 1. **触发词不能为空**。`"" in text` 恒为 true —— 一个 `triggers: [""]`
     *    的手误会让这个技能**命中每一句话**。
     * 2. **单字触发词直接丢弃**。中文里「图」「改」这种单字几乎必误命中
     *    （用户说「改一下」就命中了「改」）。
     * 3. **限量**。触发词写得宽时可能同时命中好几个，全注入就把提示词挤爆了。
     *
     * @return 命中的技能 + 各自命中的词，按「命中词数量」降序（命中的越多越相关）
     */
    fun matchTriggers(
        project: Project?,
        text: String,
        limit: Int = 3
    ): List<TriggerHit> {
        if (text.isBlank()) return emptyList()

        // 显式出口：`/技能名` 强制加载。
        //
        // 触发词是「猜用户想干什么」，猜错很正常；用户明确要点某个技能时必须能点到。
        // 借鉴自 agents-universe 的 `/slug` 命令（它的最高优先级分支）。
        val trimmed = text.trimStart()
        if (trimmed.startsWith("/")) {
            val cmd = trimmed.substring(1).split(' ', '\n', '\t').firstOrNull()?.trim().orEmpty()
            if (cmd.isNotEmpty()) {
                byName(project, cmd)?.let { return listOf(TriggerHit(it, listOf("/$cmd"))) }
                // 命令写了但没这个技能：也返回一个「命中」，让提示词里能说清
                // 「你写的这个技能不存在，现有的是这些」—— 比静默不命中好
                return listOf(TriggerHit(MISSING, listOf("/$cmd")))
            }
        }

        val lower = text.lowercase()
        val hits = mutableListOf<TriggerHit>()
        for (skill in all(project)) {
            if (skill.triggers.isEmpty()) continue
            val matched = skill.triggers.filter { t ->
                // 护栏 1 + 2：非空、且至少两个字
                t.isNotBlank() && t.length >= 2 && t.lowercase() in lower
            }
            if (matched.isNotEmpty()) hits.add(TriggerHit(skill, matched))
        }
        return hits.sortedByDescending { it.matched.size }.take(limit)
    }

    /**
     * 哨兵：用户用 `/xxx` 点名了一个**不存在**的技能。
     *
     * 用哨兵而不是 null，是因为调用方需要区别「没命中」和「点名点错了」——
     * 后者必须说出来（用户以为有这个技能，不说他会一直试）。
     */
    val MISSING = Skill("", "", "", File("."), "缺失")

    /** 一次触发词命中：哪个技能、命中了哪几个词 */
    data class TriggerHit(val skill: Skill, val matched: List<String>)

    /** 这次命中是不是「点名不存在」 */
    fun isMissingPointed(hit: TriggerHit): Boolean = hit.skill === MISSING

    /** 把命中结果渲染成提示词里的一段指令；没有命中就返回空串（不占 token） */
    fun renderTriggerHint(hits: List<TriggerHit>): String {
        if (hits.isEmpty()) return ""
        return buildString {
            append("## 这条消息命中了这些技能（**动手前先加载**）\n\n")
            hits.forEach { h ->
                if (isMissingPointed(h)) {
                    // 点名点错了要说清楚，否则用户会一直以为技能在但没生效
                    append("- ⚠️ 你点了 `").append(h.matched.joinToString("、"))
                    append("`，但技能库里**没有**这个技能。")
                    append("先用 `install_skill` 的 `action=list` 看看现有哪些。\n")
                } else {
                    append("- `").append(h.skill.name).append("`")
                    append("　命中：").append(h.matched.joinToString("、"))
                    append("\n")
                }
            }
            append("\n用 `skill` 工具把它们的正文取回来再动手 —— ")
            append("这些技能里写的就是「这个项目上已经踩过的坑」，照着做能少走弯路。\n")
        }
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
            val triggers = meta["triggers"]
                ?.split(',', '，')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(6)
                ?: emptyList()
            val trimmed = body.trim()
            Skill(
                name = name,
                description = desc,
                body = trimmed,
                dir = dir,
                source = source,
                triggers = triggers,
                gaps = parseGaps(body),
                crossLinks = parseCrossLinks(trimmed),
                steps = meta["steps"]?.split(',', '，', ' ')
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(12) ?: emptyList()
            )
        }.onFailure { log.warn("技能解析失败：${dir.path}", it) }.getOrNull()
    }

    /**
     * 解析正文里的缺口标注。
     *
     * 用 HTML 注释而不是新 frontmatter 字段：它描述的是**正文缺什么**，
     * 属于正文的一部分；而且注释渲染时天然不可见，不干扰阅读。
     */
    private fun parseGaps(body: String): List<String> {
        val m = GAPS_RE.find(body) ?: return emptyList()
        return m.groupValues[1]
            .split(',')
            .map { it.trim().trim('"', '\'', ' ') }
            .filter { it.isNotEmpty() }
            .take(8)
    }

    /** 正文里的 `[[技能名]]` 交叉引用 */
    private fun parseCrossLinks(body: String): List<String> =
        CROSS_LINK_RE.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)
            .toList()

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
                // 触发短语：**值得这点开销** —— 它是「该不该加载」最直接的判据，
                // 比让模型从描述里猜准得多。压到 40 字以内，避免目录膨胀。
                if (s.triggers.isNotEmpty()) {
                    append("　［命中就加载：")
                    append(s.triggers.joinToString("、").take(40))
                    append("］")
                }
                // 有缺口就标出来：提醒模型「用它的同时，顺手把缺的补上」
                if (s.gaps.isNotEmpty()) {
                    append("　（有 ").append(s.gaps.size).append(" 处待补）")
                }
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
