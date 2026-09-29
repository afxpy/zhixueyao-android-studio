package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.agent.ZhixueyaoHome
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.io.File
import kotlin.math.ln

/**
 * 知识库。
 *
 * ## 它和已有的东西差在哪（不然就是第三套重复机制）
 *
 * | 已有 | 检索方式 | 你什么时候用得上 |
 * |---|---|---|
 * | `search_code` | **字面匹配**（正则/子串） | 记得关键字 ——「搜 `PathGuard`」 |
 * | `memory` | 全量注入提示词 | 少量、必须永远在眼前的约定 |
 * | `skills` | 按名字/触发词加载 | 成套的方法论 |
 * | **本知识库** | **BM25 相关度排序** | **记得有这回事、但想不起原话** |
 *
 * 最后一行是它唯一存在的理由。真实的场景是：
 *
 * > 「上次记过 Gradle 依赖冲突怎么处理来着？」
 *
 * 你不知道当时写的是「依赖冲突」「版本仲裁」还是「resolutionStrategy」——
 * 于是 `search_code` 搜不到（你不知道搜什么词），
 * 而这里能把**相关度最高的几段**按顺序端出来。
 *
 * 换句话说：**grep 解决「找得到」，它解决「找不全」。**
 *
 * ## 为什么是 BM25 而不是向量检索
 *
 * 向量检索（嵌入）确实更强，但：
 *
 * - 要调外部接口 —— **费用、延迟、断网就废**
 * - 索引要持久化与失效管理 —— 一套生命周期
 * - **最关键：那些代码我没办法离线验证**
 *
 * BM25 是纯计算，**打分是纯函数**，可以拿构造好的语料断言排序结果。
 * 一个能验证的 BM25 胜过一个验不了的向量库 ——
 * 后者出问题时，你连「它到底是检索错了还是模型没用好」都分不清。
 *
 * 真需要语义检索时，把知识库文件交给带向量能力的 MCP 服务器即可，
 * 不必在这里内建。
 *
 * ## 中文分词：字符 bigram
 *
 * 中文没有空格，按空格切等于整句一个 token（几乎匹配不上任何东西）。
 * 这里用信息检索里的常规做法：**汉字取相邻二元组**（「依赖冲突」→ 依赖/赖冲/冲突），
 * 拉丁文按词切。这样就**不需要引入分词器**，也不需要词典，
 * 而召回效果对「找相关段落」这个目标是够的。
 *
 * 单字也一并保留：查一个字的场景（「锁」）靠 bigram 匹配不到。
 */
object KnowledgeBase {

    /** 知识库根目录：`~/.zhixueyao/kb`，跨项目共用 */
    fun root(): File = File(ZhixueyaoHome.root(), "kb")

    /** 一个知识页 */
    data class Page(
        val source: String,      // 相对 kb/ 的路径
        val title: String,
        val tags: List<String>,
        val body: String
    )

    /** 检索的最小单位：一个标题小节 */
    data class Chunk(
        val pageTitle: String,
        val source: String,
        val heading: String,
        val text: String,
        val tokens: Map<String, Int>,
        val titleTokens: Set<String>
    ) {
        val length: Int get() = tokens.values.sum()
    }

    data class Hit(val chunk: Chunk, val score: Double)

    // ---------------- 纯函数：解析 ----------------

    /**
     * 解析一页。front matter 是可选的 —— 没写就全部当正文，
     * 标题取第一个 `#` 标题，再不行取文件名。
     *
     * 为什么容忍「没有 front matter」：用户可能直接往 kb/ 目录里丢一个
     * 普通的 .md 文件。要求他们必须先写元数据，等于这个功能没人用。
     */
    fun parse(raw: String, source: String): Page {
        val text = raw.replace("\r\n", "\n")
        var title = ""
        var tags = emptyList<String>()
        var body = text

        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) {
                val fm = text.substring(3, end).trim()
                body = text.substring(end + 4).trimStart('\n')
                for (line in fm.lines()) {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    when (k) {
                        "title" -> title = v
                        "tags", "tag" -> tags = v.split(',', '，', ' ')
                            .map { it.trim().removePrefix("#") }
                            .filter { it.isNotBlank() }
                    }
                }
            }
        }

        if (title.isBlank()) {
            title = body.lines().firstOrNull { it.trimStart().startsWith("#") }
                ?.trimStart()?.trimStart('#')?.trim()
                ?: source.substringAfterLast('/').substringBeforeLast('.')
        }

        return Page(source, title, tags, body)
    }

    /**
     * 把正文切成小节。
     *
     * 按 `#` 标题切 —— 因为**一个标题小节就是「一件完整的事」**，
     * 检索到它的一半没有意义。切太碎会把上下文打断，切太粗则一段里混好几件事
     * （排序就失去了分辨率）。
     *
     * 太长的小节（超过 [MAX_CHUNK_CHARS]）按空行再切一次：
     * 一两万字的小节在 BM25 里会被长度归一化压得几乎不相关，
     * 而且真检索出来也没法整段塞进上下文。
     */
    fun chunks(page: Page): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val titleTokens = tokenize(page.title).toSet() + tokenize(page.tags.joinToString(" ")).toSet()

        var heading = page.title
        val buf = StringBuilder()

        fun flush() {
            val text = buf.toString().trim()
            buf.setLength(0)
            if (text.isBlank()) return
            for (piece in splitLong(text)) {
                out.add(
                    Chunk(
                        pageTitle = page.title,
                        source = page.source,
                        heading = heading,
                        text = piece,
                        tokens = counts(tokenize("$heading\n$piece")),
                        titleTokens = titleTokens
                    )
                )
            }
        }

        for (line in page.body.lines()) {
            val t = line.trimStart()
            if (t.startsWith("#")) {
                flush()
                heading = t.trimStart('#').trim().ifBlank { page.title }
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    private const val MAX_CHUNK_CHARS = 1200

    /** 过长的小节按空行切段再拼到上限附近 */
    private fun splitLong(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (para in text.split("\n\n")) {
            if (cur.length + para.length > MAX_CHUNK_CHARS && cur.isNotEmpty()) {
                out.add(cur.toString().trim())
                cur.setLength(0)
            }
            cur.append(para).append("\n\n")
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out.ifEmpty { listOf(text) }
    }

    /**
     * 分词。**中文按字符 bigram + 单字，拉丁按词**。
     *
     * 为什么不做真正的分词：引一个中文分词器要么带词典（体积）、
     * 要么调服务（依赖网络）。而检索场景下 bigram 的召回已经够用 ——
     * 「依赖冲突」切成 依赖/赖冲/冲突，查「冲突」也能命中。
     *
     * 拉丁词**过滤掉单字母**（`a` `i` 这种噪声太大），中文单字保留。
     */
    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()

        fun flushLatin() {
            if (latin.length >= 2) out.add(latin.toString().lowercase())
            latin.setLength(0)
        }

        fun flushCjk() {
            val chars = cjk.toString()
            for (i in chars.indices) {
                out.add(chars[i].toString())                       // 单字
                if (i + 1 < chars.length) {
                    out.add("" + chars[i] + chars[i + 1])          // 相邻二元组
                }
            }
            cjk.setLength(0)
        }

        for (c in s) {
            when {
                isCjk(c) -> { flushLatin(); cjk.append(c) }
                c.isLetterOrDigit() || c == '_' -> { flushCjk(); latin.append(c) }
                else -> { flushCjk(); flushLatin() }
            }
        }
        flushCjk()
        flushLatin()
        return out
    }

    /** 汉字与中文标点之外的 CJK（日文假名、韩文）也算，避免混排文本被整段丢掉 */
    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF ||      // 汉字
            c.code in 0x3400..0x4DBF ||  // 扩展 A
            c.code in 0x3040..0x30FF ||  // 假名
            c.code in 0xAC00..0xD7AF     // 韩文

    private fun counts(tokens: List<String>): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (t in tokens) m[t] = (m[t] ?: 0) + 1
        return m
    }

    // ---------------- 纯函数：排序 ----------------

    /**
     * 从查询里挑出用于打分的词。
     *
     * ## 为什么必须做这一步（探针抓出来的真问题）
     *
     * 中文单字（`应` `效` `一` `个`）**噪声极大**：任何一段中文里都散落着这些字。
     * 于是一个完全无关的查询——比如拿「量子隧穿效应」去搜一个只讲 Gradle 和咖啡机的
     * 知识库——也会因为某个单字撞上而在某一段里得到非零分，
     * **结果就是「找到 1 条相关内容」，指向一段毫不相干的话**。
     *
     * 这比返回空更糟：空结果模型会换个说法再试，而一条错误内容会被当真。
     * （探针里 `量子隧穿效应` 那条断言就是为这个写的，第一版确实返回了 1 条。）
     *
     * ## 规则
     *
     * 查询里**只要有长词（bigram 或拉丁词），就只用长词打分**。
     * 单字只在「查询本身就是一个字」时才启用——那正是它该起作用的场景
     * （查「锁」）。
     *
     * 单字仍然保留在**索引侧**的 token 里，因为查询侧才需要过滤；
     * 索引侧去掉的话，单字查询就永远匹配不上了。
     */
    fun queryTerms(query: String): List<String> {
        val all = tokenize(query).distinct()
        val strong = all.filter { it.length >= 2 }
        return if (strong.isNotEmpty()) strong else all
    }

    /**
     * BM25 排序。
     *
     * 公式里的三项各有分工，都不是可调参数随便设的：
     *
     * - **IDF**：让「的」「是」这种到处都有的词几乎不贡献分数。
     *   用带 `+1` 的平滑形式 —— 不然出现在所有文档里的词会算出负值。
     * - **饱和（k1）**：同一个词出现 20 次不该比 2 次好 10 倍。
     *   词频的作用要**递减**，否则复读机式的段落永远排第一。
     * - **长度归一（b）**：长段落天然命中更多词，不归一的话
     *   长文档永远赢。b=0.75 是文献里的常规取值。
     *
     * ## 试过「命中覆盖率门槛」，撤回了
     *
     * 端到端测试里出现过一次假阳性：拿「量子纠缠的退相干时间怎么算」去搜，
     * 只因为 shell 那页有一句「在有时间压力下执行」，就返回了 1 条。
     *
     * 当时的修法是「查询词至少要命中 1/3」。**结果把 6 条真实模糊查询里的 5 条打成了空。**
     *
     * 原因是我把方向搞反了：自然语言问句里有大量功能词 bigram
     * （`为什么` `还是` `明明` `怎么`），它们**本来就不该命中任何东西**。
     * 要求覆盖率，惩罚的恰恰是「用自然语言提问」——
     * 而那正是这个知识库存在的理由。
     *
     * 用「修 1 个假阳性」换「5 个真命中丢失」是不划算的交易。
     * 现在改成：**不做门槛，但把低分结果如实标出来**（见 [KbSearchTool] 里的提示），
     * 让模型自己判断「这条到底靠不靠谱」，而不是替它把结果删掉。
     *
     * 另外加一条**标题加权**：命中小节标题或页面标签时乘 [TITLE_BOOST]。
     * 理由很实际 —— 标题是作者对「这段在讲什么」的总结，
     * 命中它比命中正文里的同一个词更有信息量。
     */
    fun rank(query: String, chunks: List<Chunk>, topK: Int): List<Hit> {
        if (chunks.isEmpty()) return emptyList()
        val q = queryTerms(query)
        if (q.isEmpty()) return emptyList()

        val n = chunks.size
        val df = HashMap<String, Int>()
        for (c in chunks) for (t in c.tokens.keys) df[t] = (df[t] ?: 0) + 1

        val avgdl = chunks.sumOf { it.length }.toDouble() / n
        if (avgdl <= 0.0) return emptyList()

        val hits = chunks.mapNotNull { c ->
            var score = 0.0
            for (term in q) {
                val f = c.tokens[term] ?: continue
                val dfq = df[term] ?: 0
                // 平滑 IDF：+1 保证非负（出现在全部段落里的词得 0 分而不是负分）
                val idf = ln((n - dfq + 0.5) / (dfq + 0.5) + 1.0)
                val denom = f + K1 * (1 - B + B * c.length / avgdl)
                score += idf * (f * (K1 + 1)) / denom
            }
            if (score <= 0.0) return@mapNotNull null

            // 标题/标签命中：乘一个固定倍数。不做加权的话，
            // 一个在正文里啰嗦提到十次的段落会压过标题就写着答案的那段。
            if (q.any { it in c.titleTokens }) score *= TITLE_BOOST

            Hit(c, score)
        }
        // 分数相同时按来源路径排 —— 保证结果**稳定可复现**。
        // 不稳定的话探针没法断言，用户也会看到「同一个查询两次顺序不一样」。
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.chunk.source })
            .take(topK)
    }

    private const val K1 = 1.2
    private const val B = 0.75
    private const val TITLE_BOOST = 1.6

    // ---------------- 读写 ----------------

    fun allPages(): List<Page> {
        val base = root()
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "markdown", "txt") }
            .map { f ->
                val rel = base.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                runCatching { parse(f.readText(), rel) }.getOrNull()
            }
            .filterNotNull()
            .sortedBy { it.source }
            .toList()
    }

    fun allChunks(): List<Chunk> = allPages().flatMap { chunks(it) }

    fun allTags(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (p in allPages()) for (t in p.tags) m[t] = (m[t] ?: 0) + 1
        return m
    }

    /** 按标签过滤页面（大小写不敏感；任一命中即算） */
    fun filterByTags(tags: List<String>): List<Page> {
        if (tags.isEmpty()) return allPages()
        val want = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return allPages().filter { p ->
            val own = p.tags.map { it.lowercase() }
            want.any { w -> own.any { it == w || it.contains(w) } }
        }
    }

    /**
     * 写一页。文件名由标题生成，**已存在则覆盖**。
     *
     * 覆盖而不是新建同名副本：知识库最常见的动作是「把上次那条补一下」，
     * 每次都建新文件会让同一个主题散成好几份，检索时互相稀释。
     */
    fun writePage(title: String, body: String, tags: List<String>): File {
        val dir = root()
        dir.mkdirs()
        val slug = slugify(title).ifBlank { "untitled" }
        val file = File(dir, "$slug.md")
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("title: ").append(title.trim()).append('\n')
        if (tags.isNotEmpty()) sb.append("tags: ").append(tags.joinToString(", ")).append('\n')
        sb.append("---\n\n")
        sb.append(body.trim()).append('\n')
        // 原子写入。知识页是**用户积累下来的东西** ——
        // 写到一半被打断（强退、断电）就留下截断的 md，
        // 而读取端（allPages）是 runCatching 兜底的 → 那一页**静默消失**，
        // 用户只会觉得「我记的那条怎么没了」。
        com.zhixueyao.util.AtomicFiles.write(file, sb.toString())
        return file
    }

    /** 标题 → 文件名。中文保留（能看懂比好看重要），只剥掉路径与非法字符 */
    /**
     * 标题 → 文件名。
     *
     * 标 `public` 而**不是** `internal`：Java 探针看不见 internal 成员
     * （Kotlin 会给它们做名字改写），而这个函数有真实的可错点
     * （路径分隔符、非法字符、超长截断），值得被断言钉住。
     * **可验证性优先于可见性收紧** —— 反正它本来就不是什么危险接口。
     */
    fun slugify(title: String): String =
        title.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "-")
            .replace(Regex("\\s+"), "-")
            .take(60)
}

// ---------------- 工具 ----------------

/** 往知识库里写一页。 */
class KbWriteTool : AgentTool {

    override val name = "kb_write"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "title" to jsonObj(
                "type" to "string".toJson(),
                "description" to "这一页的标题。**同一个主题请沿用同一个标题** —— " +
                    "同名会覆盖更新，而换个名字写会变成两份、检索时互相稀释"
            ),
            "content" to jsonObj(
                "type" to "string".toJson(),
                "description" to "正文，Markdown。**用 # 分小节** —— 小节是检索的最小单位，" +
                    "一个标题下写一件完整的事。别把十件事塞进一个标题"
            ),
            "tags" to jsonObj(
                "type" to "array".toJson(),
                "items" to jsonObj("type" to "string".toJson()),
                "description" to "标签，用于按主题归类与过滤，例如 [\"gradle\", \"依赖\"]"
            )
        ),
        "required" to jsonArr("title", "content")
    )

    override val description: String
        get() = "把一条知识写进**跨项目共用**的知识库（存到 ~/.zhixueyao/kb/）。" +
            "适合：「这次踩的坑记下来，以后别的项目也用得上」「把这段调研结论存一下」。\n" +
            "注意跟另外两个别搞混：**只对当前项目成立**的写进技能（install_skill scope=project）；" +
            "**必须永远在眼前**的短约定写进 memory；" +
            "而「以后可能想起来查」的成篇资料写这里。\n" +
            "写完后用 kb_search 能按**相关度**（不只是字面）搜到它。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val title = args.str("title")?.trim().orEmpty()
        val content = args.str("content")?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.error("title 不能为空。")
        if (content.isBlank()) return ToolResult.error("content 不能为空。")

        val tags = args.arr("tags")?.items
            ?.mapNotNull { it.asStringOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return try {
            val existed = File(KnowledgeBase.root(), KnowledgeBase.slugify(title) + ".md").isFile
            val f = KnowledgeBase.writePage(title, content, tags)
            val sections = KnowledgeBase.chunks(KnowledgeBase.parse(f.readText(), f.name)).size
            ToolResult(
                buildString {
                    append(if (existed) "已更新知识库页面「" else "已写入知识库页面「")
                        .append(title).append("」\n")
                    append("文件：").append(f.absolutePath).append('\n')
                    append("切成 ").append(sections).append(" 个检索小节")
                    if (tags.isNotEmpty()) append("，标签：").append(tags.joinToString("、"))
                    append('\n')
                    if (sections <= 1 && content.length > 1200) {
                        append("\n提示：这页只切出一个小节，说明**没写 # 标题**。" +
                            "加上标题分段后检索会准很多（小节是检索的最小单位）。")
                    }
                }
            )
        } catch (e: Exception) {
            ToolResult.error("写入失败：${e.message}")
        }
    }
}

/** 按相关度检索知识库。 */
class KbSearchTool : AgentTool {

    companion object {
        /**
         * 「相关度偏低」的分界。
         *
         * ## 这个数是从真实数据里量出来的，不是拍的
         *
         * 端到端测试（真实知识页 + 自然语言查询）实测首条分数：
         *
         * | 类型 | 首条分数 |
         * |---|---|
         * | 真命中（6 条模糊查询） | 9.613 / 20.620 / 9.592 / 8.327 / **5.998** / 10.247 |
         * | 噪声（无关查询） | **2.438** |
         *
         * 中间空着 5.998 ~ 2.438 这一大段，取 **4.0** 落在中间。
         *
         * **第一版设的 2.0 是错的** —— 那样噪声的 2.438 就不会被标出来，
         * 提示形同虚设。是探针把这条打出来的（断言「噪声不能超过分界线」）。
         *
         * 它**只用来加一句提示**，不用来过滤 —— 阈值过滤试过，误伤太严重：
         * 加「命中覆盖率 ≥ 1/3」把 6 条真查询里的 5 条打成了空。
         *
         * 偏保守是有意的：多标一句「相关度偏低」没有代价（模型自己会判断），
         * 而漏标会让模型把噪声当真。
         */
        private const val WEAK_SCORE_HINT = 4.0
    }

    override val name = "kb_search"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "query" to jsonObj(
                "type" to "string".toJson(),
                "description" to "用**自然语言描述你想找什么**，不必凑关键词 —— " +
                    "这正是它比 search_code 强的地方"
            ),
            "tags" to jsonObj(
                "type" to "array".toJson(),
                "items" to jsonObj("type" to "string".toJson()),
                "description" to "可选：只在带这些标签的页面里搜"
            ),
            "top_k" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "返回几个小节，默认 5"
            )
        ),
        "required" to jsonArr("query")
    )

    override val description: String
        get() = "在知识库里按**相关度**检索，返回最相关的几个小节（带来源）。" +
            "什么时候用它而不是 search_code：**你记得有这回事、但想不起原话**的时候。" +
            "search_code 要你给出确切的字符串，而这里可以直接描述意思。" +
            "如果知识库是空的，先看看用户是不是还没往里放过东西。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isBlank()) return ToolResult.error("query 不能为空。")

        val tagFilter = args.arr("tags")?.items
            ?.mapNotNull { it.asStringOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val topK = (args.int("top_k") ?: 5).coerceIn(1, 20)

        val chunks = if (tagFilter.isEmpty()) {
            KnowledgeBase.allChunks()
        } else {
            KnowledgeBase.filterByTags(tagFilter).flatMap { KnowledgeBase.chunks(it) }
        }

        if (chunks.isEmpty()) {
            return ToolResult(
                if (tagFilter.isEmpty()) {
                    "知识库现在是空的（${KnowledgeBase.root().absolutePath} 下没有可用文件）。" +
                        "可以用 kb_write 往里写，或者让用户直接把 .md 放进去。"
                } else {
                    "没有带标签「${tagFilter.joinToString("、")}」的知识页。" +
                        "用 kb_tags 看看现在有哪些标签。"
                }
            )
        }

        val hits = KnowledgeBase.rank(query, chunks, topK)
        if (hits.isEmpty()) {
            return ToolResult(
                "知识库里没有和「$query」相关的内容（搜了 ${chunks.size} 个小节）。" +
                    "换个说法再试，或者这确实还没记过。"
            )
        }

        // 低分要如实说。不替模型删结果（那样会误伤真命中），
        // 但也不能让它以为「返回了就一定相关」——
        // 小语料里常见词撞上会产生弱命中，标出来让它自己权衡。
        val weak = hits.first().score < WEAK_SCORE_HINT

        return ToolResult(
            buildString {
                append("在 ").append(chunks.size).append(" 个小节里找到 ")
                    .append(hits.size).append(" 条相关内容")
                if (weak) append("（**相关度都偏低**）")
                append("：\n")
                if (weak) {
                    append("注意：分数低通常意味着「只是词面偶尔撞上」，未必真的相关。\n")
                    append("换个说法再搜一次，或者确认知识库里到底有没有记过这件事 —— ")
                    append("**不要仅凭这条弱命中就下结论**。\n")
                }
                for ((i, h) in hits.withIndex()) {
                    val c = h.chunk
                    append("\n──────── ").append(i + 1).append("　相关度 ")
                        .append("%.2f".format(h.score)).append(" ────────\n")
                    append("来源：").append(c.source)
                    if (c.heading.isNotBlank() && c.heading != c.pageTitle) {
                        append("　›　").append(c.heading)
                    }
                    append('\n')
                    append(c.text.take(1400))
                    if (c.text.length > 1400) append("\n……（小节较长，已截断）")
                    append('\n')
                }
                append("\n需要某一节的完整内容，用 read_file 读它的来源文件。")
            }
        )
    }
}

/** 列出现有标签。 */
class KbTagsTool : AgentTool {

    override val name = "kb_tags"

    override val parameters: Json.Obj = jsonObj("type" to "object".toJson(), "properties" to jsonObj())

    override val description: String
        get() = "列出知识库里所有标签及各自页数，用来了解里面大概有什么、或者给 kb_search 挑标签。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val pages = KnowledgeBase.allPages()
        if (pages.isEmpty()) {
            return ToolResult("知识库是空的（${KnowledgeBase.root().absolutePath}）。")
        }
        val tags = KnowledgeBase.allTags().entries.sortedByDescending { it.value }
        val noTag = pages.count { it.tags.isEmpty() }

        return ToolResult(
            buildString {
                append("知识库共 ").append(pages.size).append(" 页")
                if (tags.isNotEmpty()) append("，").append(tags.size).append(" 个标签")
                append("：\n\n")
                if (tags.isEmpty()) {
                    append("（所有页面都没打标签）\n")
                } else {
                    for ((t, n) in tags) append("- ").append(t).append("　").append(n).append(" 页\n")
                }
                if (noTag > 0) append("\n未打标签的有 ").append(noTag).append(" 页。")
                append("\n\n各页：\n")
                for (p in pages.take(40)) {
                    append("- ").append(p.title)
                    if (p.tags.isNotEmpty()) append("　[").append(p.tags.joinToString(", ")).append(']')
                    append("　(").append(p.source).append(")\n")
                }
                if (pages.size > 40) append("……（还有 ").append(pages.size - 40).append(" 页）\n")
            }
        )
    }
}