package com.zhixueyao.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * **跨会话记忆**。
 *
 * 借鉴自参考项目 astravia 的 memory-mode：那个项目把记忆分成两层 ——
 * 常驻的 `MEMORY.md`（每轮都带进提示词）和按日期分目录的工作史（按需翻阅）。
 * 这里取它的核心思路，按 IDE 插件的场景简化成两层：
 *
 * ```
 * ~/.zhixueyao/MEMORY.md              全局记忆（跨项目：用户的偏好、习惯）
 * <项目>/.zhixueyao/MEMORY.md         项目记忆（这个工程特有的约定、坑）
 * ```
 *
 * ## 为什么这是刚需
 *
 * 在这个功能之前，**每开一个新会话，AI 对用户和这个项目一无所知**：
 * 上次说过的「别动 xxx 文件」「这个模块用 kotlin 不用 java」
 * 「构建要走腾讯云镜像」—— 全部要重新说一遍。
 *
 * 而「记不住」是用户对 AI 助手最直接的失望来源。技能库解决的是**方法论**的
 * 积累（「这类事该怎么做」），记忆解决的是**事实**的积累
 * （「这个项目是这样的」「用户不喜欢那个」）。两者缺一不可。
 *
 * ## 两个刻意的设计
 *
 * 1. **注入有硬上限**。记忆是常驻的（每轮都发），不封顶的话它会慢慢把提示词吃光 ——
 *    和技能目录一样，这是个「必须有上限」的地方。超了就在提示词里说清
 *    「还有 N 条没显示，可以用 memory 工具读全部」。
 * 2. **写入就去重**。同一件事说两遍很常见（用户重复强调、AI 重复记录），
 *    精确去重是最低成本的防膨胀手段。
 */
object MemoryStore {

    private val log = Logger.getInstance(MemoryStore::class.java)

    /** 注入提示词的字符上限。约 1.5k token —— 和技能目录同量级，可接受 */
    const val PROMPT_LIMIT = 1_500

    /** 单条记忆的字符上限（超过就该拆成几条，或者写进技能里） */
    const val ENTRY_LIMIT = 300

    /** 文件总条数上限，超了从**最旧**的开始丢（新事实比旧事实更可能相关） */
    const val MAX_ENTRIES = 80

    const val FILE_NAME = "MEMORY.md"

    // ---------------- 路径 ----------------

    /** 全局记忆：`~/.zhixueyao/MEMORY.md` */
    fun globalFile(): File = File(ZhixueyaoHome.root(), FILE_NAME)

    /**
     * 项目记忆：`<项目>/.zhixueyao/MEMORY.md`。
     *
     * 放在项目里是**刻意的** —— 这样它能随工程提交进仓库，
     * 同事拉下来就带着同一份约定（和项目技能库同一个理由）。
     */
    fun projectFile(project: Project?): File? =
        project?.basePath?.let { File(File(it, ".zhixueyao"), FILE_NAME) }

    // ---------------- 读写 ----------------

    /**
     * 读全部记忆条目。
     *
     * 格式刻意用极简的 Markdown 列表（`- [2026-09-28] 内容`）而不是 JSON：
     * 用户会直接打开这个文件看、甚至手改 —— 可读性是第一位的。
     */
    fun read(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val t = line.trim()
                    if (!t.startsWith("- ")) return@mapNotNull null
                    val body = t.removePrefix("- ").trim()
                    // `[日期] 内容`；没有日期前缀的也算（用户手写时不一定带）
                    val m = Regex("^\\[(\\d{4}-\\d{2}-\\d{2})]\\s*(.*)$").find(body)
                    if (m != null) Entry(m.groupValues[1], m.groupValues[2].trim())
                    else Entry("", body)
                }
                .filter { it.text.isNotBlank() }
        }.onFailure { log.info("读记忆失败：${file.path} ${it.message}") }.getOrDefault(emptyList())
    }

    /** 全部记忆：项目在前（更相关），全局在后 */
    fun all(project: Project?): List<Entry> =
        read(projectFile(project) ?: File("/nonexistent")) + read(globalFile())

    /**
     * 追加一条。
     *
     * @return null = 成功；否则是失败原因
     */
    fun remember(file: File, text: String, allowGlobal: Boolean = true): String? {
        val clean = text.trim().replace("\n", " ")
        if (clean.isEmpty()) return "内容为空"
        if (clean.length > ENTRY_LIMIT) {
            return "这条太长了（${clean.length} 字 > $ENTRY_LIMIT）。" +
                "记忆只放**一句话能说清的事实**；成体系的东西应该写进技能（install_skill）。"
        }

        val existing = read(file)
        // 精确去重：同一件事说两遍很常见，最低成本的防膨胀手段
        if (existing.any { it.text.equals(clean, ignoreCase = true) }) {
            return "DUPLICATE"   // 调用方据此回一句「已经记过了」，不算错误
        }

        return runCatching {
            file.parentFile?.mkdirs()
            val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            val keep = (existing + Entry(today, clean))
                .takeLast(MAX_ENTRIES)   // 超上限丢最旧的
            com.zhixueyao.util.AtomicFiles.write(file, render(keep))
            null
        }.getOrElse { "写入失败：${it.message}" }
    }

    /** 按关键词或序号删掉一条；返回删掉的条数 */
    fun forget(file: File, keyword: String): Int = runCatching {
        val existing = read(file)
        val remained = existing.filterNot {
            it.text.contains(keyword, ignoreCase = true)
        }
        if (remained.size == existing.size) return 0
        com.zhixueyao.util.AtomicFiles.write(file, render(remained))
        existing.size - remained.size
    }.getOrDefault(0)

    private fun render(entries: List<Entry>): String = buildString {
        append("# 记忆\n\n")
        append("<!-- 这是 AI 助手跨会话记住的事实。可以手工编辑，删掉一行就等于让它忘掉。 -->\n\n")
        entries.forEach { e ->
            append("- ")
            if (e.date.isNotBlank()) append("[").append(e.date).append("] ")
            append(e.text).append('\n')
        }
    }

    // ---------------- 注入提示词 ----------------

    /**
     * 渲染成提示词里的一段。没有记忆时返回空串（不占 token）。
     *
     * 超上限时**明确说出还有多少条没显示** —— 不然模型会以为「本机只有这些」，
     * 于是把已经记过的事又记一遍。
     */
    fun renderForPrompt(project: Project?): String {
        val entries = all(project)
        if (entries.isEmpty()) return ""

        val shown = mutableListOf<Entry>()
        var used = 0
        for (e in entries.asReversed()) {          // 从最新的开始放
            val line = "- " + (if (e.date.isNotBlank()) "[${e.date}] " else "") + e.text
            if (used + line.length > PROMPT_LIMIT) break
            shown.add(e)
            used += line.length + 1
        }
        if (shown.isEmpty()) return ""
        shown.reverse()                            // 显示时按时间正序

        return buildString {
            append("## 记忆（跨会话记住的事实）\n\n")
            shown.forEach { e ->
                append("- ")
                if (e.date.isNotBlank()) append("[").append(e.date).append("] ")
                append(e.text).append('\n')
            }
            val hidden = entries.size - shown.size
            if (hidden > 0) {
                append("\n（还有 ").append(hidden)
                append(" 条较早的记忆没有显示，需要时用 memory 工具读全部）\n")
            }
            append("\n这些是**已经确认过的事实**，直接按它做，不用再问一遍。")
            append("发现哪条过时了就把它改掉（memory 工具的 forget + remember）。\n")
        }
    }

    data class Entry(val date: String, val text: String)
}