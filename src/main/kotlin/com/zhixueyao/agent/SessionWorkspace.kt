package com.zhixueyao.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话的**临时产物目录**。
 *
 * ## 目录形状
 *
 * ```
 * ~/.zhixueyao/Conversation/Product/
 *   └── 2026-09-27-23.38/          ← 会话创建时间（到分钟）
 *       └── 抽象日出图标/           ← 会话名（去掉非法字符）
 *           ├── .session.json      ← 标记：id / 标题 / 创建时间（供清理页读取）
 *           ├── abstract_sunrise.svg
 *           └── draft.png
 * ```
 *
 * ## 为什么要有它
 *
 * 用户的原话：「临时产物请放在全局的 .zhixueyao/Conversation/Product/今日的年月日时分…
 * 除非是我用止血药做项目之类的且该项目需要的才会放在项目里面，不然都不会放进项目里面」。
 *
 * 之前的问题是：试验性的产物（比如「生成一张日出图标看看」）会直接落进用户的工程目录，
 * 一个无关的 `.svg` 混在 `res/drawable/` 里，既不是 App 需要的，又得用户自己去删。
 * 现在这类东西一律进这里，**工程的目录只放「确实要进 App 的东西」**。
 *
 * 这条思路与参考项目 astravia 一致（`~/.astravia/conversation/<sessionId>/`）——
 * 它把「对话」类 session 的工作目录放到全局，只有用户手建的项目才用用户自己的 cwd。
 * 差别是我们用「时间 + 会话名」（用户明确要求的可读命名），
 * 并额外落一个 `.session.json` 保证**改名/重名后仍能可靠清理**。
 */
object SessionWorkspace {

    private val log = Logger.getInstance(SessionWorkspace::class.java)

    /** 每个会话目录里的标记文件，记录来源会话（供清理页展示与去重） */
    const val MARKER = ".session.json"

    /** 会话名里不能出现在 Windows 路径中的字符 */
    private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")

    /** 会话名最长保留多少字符（太长会让路径超限） */
    private const val MAX_NAME = 40

    /** 产物根目录：`~/.zhixueyao/Conversation/Product` */
    fun root(): File = File(File(ZhixueyaoHome.root(), "Conversation"), "Product")

    /**
     * 算出一个会话的工作目录。
     *
     * @param createdAt 会话创建时间（毫秒）—— 决定那层「年月日时分」目录
     * @param sessionId 会话 id，仅用于重名时区分
     * @param title     会话名（用户和模型都会看到，所以用可读名字而不是 hash）
     */
    fun dirFor(createdAt: Long, sessionId: String, title: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH.mm", Locale.US).format(Date(createdAt))
        val bucket = File(root(), stamp)
        val safe = sanitize(title).ifBlank { "未命名会话" }
        var candidate = File(bucket, safe)
        // 目录已存在但属于**别的**会话时加后缀。
        // 不加的话两个同名会话会共用目录，清理其中一个就会误删另一个的产物。
        val owner = readSessionId(candidate)
        if (owner != null && owner != sessionId) {
            candidate = File(bucket, "$safe-${sessionId.takeLast(4)}")
        }
        return candidate
    }

    /** 确保目录存在，并写入会话标记 */
    fun ensure(createdAt: Long, sessionId: String, title: String): File {
        val dir = dirFor(createdAt, sessionId, title)
        runCatching {
            dir.mkdirs()
            val marker = File(dir, MARKER)
            if (!marker.isFile) writeMarker(marker, sessionId, title, createdAt)
        }.onFailure { log.info("创建会话临时目录失败：${it.message}") }
        return dir
    }

    /** 会话改名后同步目录名（尽力而为；失败保持原样，不影响使用） */
    fun renameIfNeeded(dir: File, createdAt: Long, sessionId: String, title: String) {
        runCatching {
            if (!dir.isDirectory) return
            val want = dirFor(createdAt, sessionId, title)
            if (want.name != dir.name) {
                val target = uniqueTarget(want)
                if (dir.renameTo(target)) writeMarker(File(target, MARKER), sessionId, title, createdAt)
            }
        }.onFailure { log.info("同步会话目录名失败：${it.message}") }
    }

    // ---------------- 当前会话目录（供工具查） ----------------

    /**
     * 「当前会话的临时目录」，按项目记住。
     *
     * 工具层（`generate_svg` / `save_asset`）只拿得到 `Project`，**拿不到聊天面板的会话状态**，
     * 所以这里开一个极小的登记处：面板在算好目录时登记一次，工具按项目查。
     * 一个项目同时只有一个活动会话，所以按项目 key 就够，不需要更复杂的结构。
     */
    private val currentByProject = java.util.concurrent.ConcurrentHashMap<String, File>()

    fun remember(project: com.intellij.openapi.project.Project, dir: File) {
        val key = project.basePath ?: return
        currentByProject[key] = dir
    }

    fun currentFor(project: com.intellij.openapi.project.Project): File? {
        val dir = currentByProject[project.basePath ?: return null] ?: return null
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    // ---------------- 清理页用的扫描 ----------------

    /** 一个会话的临时目录 */
    data class Entry(
        val dir: File,
        val title: String,
        val createdAt: Long,
        val fileCount: Int,
        val bytes: Long,
        val lastModified: Long
    ) {
        val path: String get() = dir.absolutePath
        val sizeText: String
            get() = when {
                bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
                bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        val timeText: String
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAt))
    }

    /**
     * 列出所有会话临时目录。
     *
     * 按会话创建时间倒序（最近的在前）—— 清理时最常删的是旧的，
     * 而用户第一眼想看到的是「我刚弄出来的那些」。
     */
    fun list(): List<Entry> {
        val out = mutableListOf<Entry>()
        val buckets = root().listFiles { f -> f.isDirectory } ?: return emptyList()
        for (bucket in buckets) {
            val sessions = bucket.listFiles { f -> f.isDirectory } ?: continue
            for (s in sessions) {
                val meta = readMarker(s)
                var files = 0
                var bytes = 0L
                var newest = s.lastModified()
                s.walkTopDown().forEach { f ->
                    // **不把标记文件算进产物** —— 它是我们自己的元数据，
                    // 算进去会让「文件数 / 占用」永远比用户看到的多 1 个（探针抓出来的）
                    if (f.isFile && f.name != MARKER) {
                        files++
                        bytes += f.length()
                    }
                    if (f.lastModified() > newest) newest = f.lastModified()
                }
                out.add(
                    Entry(
                        dir = s,
                        // 标记文件是权威来源；没有标记（用户手放的目录）就退回目录名
                        title = meta?.second ?: s.name,
                        createdAt = meta?.third ?: parseStamp(bucket.name) ?: s.lastModified(),
                        fileCount = files,
                        bytes = bytes,
                        lastModified = newest
                    )
                )
            }
        }
        // 空目录也列出来 —— 它就是「这个会话没产出东西」，用户可以选择删掉
        return out.sortedByDescending { it.createdAt }
    }

    fun totalBytes(): Long = list().sumOf { it.bytes }

    /**
     * 删除一个会话目录；返回是否删干净。
     *
     * 注意**不能**用 `dir.delete()` 的返回值当结论：`walkBottomUp` 已经把它自己删掉了，
     * 再删一次必然返回 false —— 于是「删成功了却报失败」（探针抓出来的）。
     * 判据只能是「它还在不在」。
     */
    fun delete(entry: Entry): Boolean = runCatching {
        entry.dir.walkBottomUp().forEach { runCatching { it.delete() } }
        !entry.dir.exists()
    }.getOrDefault(false)

    /**
     * 删除空的时间分组目录。
     *
     * 删掉会话后，`2026-09-27-23.38/` 这种只剩空壳的分组还留着就是垃圾 ——
     * 清理完要顺手扫一遍，不然用户会觉得「删了怎么还有」。
     */
    fun pruneEmptyBuckets(): Int {
        var n = 0
        root().listFiles { f -> f.isDirectory }?.forEach { bucket ->
            val left = bucket.listFiles()
            if (left == null || left.isEmpty()) {
                if (bucket.delete()) n++
            }
        }
        return n
    }

    // ---------------- 内部 ----------------

    private fun sanitize(raw: String): String {
        val cleaned = raw.replace(ILLEGAL, "")
            .trim()
            .trim('.')                       // Windows 不允许目录名以点结尾
            .replace(Regex("\\s+"), " ")
        return cleaned.take(MAX_NAME).trim()
    }

    private fun uniqueTarget(want: File): File {
        if (!want.exists()) return want
        var i = 2
        while (i < 50) {
            val f = File(want.parentFile, "${want.name}-$i")
            if (!f.exists()) return f
            i++
        }
        return want
    }

    private fun writeMarker(file: File, id: String, title: String, createdAt: Long) {
        file.writeText(
            """{"id":"${id.replace("\"", "")}","title":"${title.replace("\"", "")}","createdAt":$createdAt}""",
            Charsets.UTF_8
        )
    }

    private fun readMarker(dir: File): Triple<String, String, Long>? {
        val f = File(dir, MARKER)
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            val id = field(text, "id") ?: return null
            val title = field(text, "title") ?: dir.name
            val at = field(text, "createdAt")?.toLongOrNull() ?: dir.lastModified()
            Triple(id, title, at)
        }.getOrNull()
    }

    private fun readSessionId(dir: File): String? = readMarker(dir)?.first

    /** 极简 JSON 取值：标记文件是我们自己写的固定三个字段，不值得为它上解析器 */
    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]*)\"?").find(json)?.groupValues?.get(1)?.trim()

    private fun parseStamp(name: String): Long? =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd-HH.mm", Locale.US).parse(name)?.time
        }.getOrNull()
}