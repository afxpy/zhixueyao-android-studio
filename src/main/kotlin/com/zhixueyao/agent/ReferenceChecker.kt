package com.zhixueyao.agent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 回答里文件路径的真实性校验。
 *
 * 为什么要做：模型编造路径是**最常见也最误导人**的幻觉 ——
 * 它会把 `NetworkClient.kt` 说得像真的存在一样，你照着去找，找半天没有。
 * 校验一遍并明确标出来，比让用户自己一个个试便宜得多。
 *
 * 判定用**文件名索引**（[FilenameIndex]）而不是「按路径拼接」：
 * 模型给的路径前缀经常是错的（写 `app/src/main/...` 而工程实际在 `core/...`），
 * 按路径拼会大面积误报。只要项目里**存在同名文件**就认为它没编造，
 * 前缀对不对另说 —— 宁可漏报，不可误报，误报几次之后用户就不看这个提示了。
 */
object ReferenceChecker {

    /**
     * 会被当成「文件路径」来看的扩展名。
     *
     * 刻意收窄：只认源码/配置/资源这几类。像 `MainActivity.kt` 这样的一定要认，
     * 而 `1.2.3` 这种版本号、`v1.0` 这种标签不能被误认。
     */
    private val EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "gradle", "json", "md", "txt", "pro", "properties",
        "toml", "yml", "yaml", "svg", "png", "webp", "jpg", "jpeg", "gif", "mp4", "ttf", "otf"
    )

    private val CANDIDATE = Regex(
        """(?<![\w/.\-])((?:[\w.\-]+/)*[\w\-]+\.(?:${EXTENSIONS.joinToString("|")}))(?![\w/])""",
        RegexOption.IGNORE_CASE
    )

    data class Result(
        /** 一共看了几个路径 */
        val checked: Int,
        /** 项目里找不到的（很可能就是编造的） */
        val missing: List<String>
    ) {
        val suspicious: Boolean get() = missing.isNotEmpty()
    }

    /** 每个项目缓存一次「项目里所有文件的名字」，避免每轮都全量扫 */
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    private fun knownNames(project: Project): Set<String> {
        val key = project.basePath ?: return emptySet()
        val now = System.currentTimeMillis()
        nameCache[key]?.let { (at, names) -> if (now - at < 60_000) return names }

        // **索引访问必须在 read action 里**。
        //
        // 之前没包，从 EDT 调时直接抛
        // "Read access is allowed from inside read-action only"，
        // 用户看到的是一个 IDE 错误弹窗（真实事故）。
        // 包在这里而不是调用方，是为了让这个 API **无论谁调、在哪个线程调都不会错**。
        val names = ReadAction.compute<MutableSet<String>, RuntimeException> {
            val acc = java.util.HashSet<String>()
            runCatching {
                FilenameIndex.getAllFilenames(project).forEach { acc.add(it.lowercase()) }
            }
            if (acc.isEmpty()) {
                // 索引拿不到（例如无头环境）时退回遍历工程目录，跳过 build 等产物目录
                runCatching {
                    Files.walk(Paths.get(key), 8).use { s ->
                        s.filter { Files.isRegularFile(it) }.forEach { f ->
                            val n = f.fileName.toString().lowercase()
                            if (n.substringAfterLast('.', "") in EXTENSIONS) acc.add(n)
                        }
                    }
                }
            }
            acc
        }
        nameCache[key] = now to names
        return names
    }

    /** 清缓存（文件增删后由调用方按需调用；60 秒自然过期也能兜住） */
    fun invalidate() = nameCache.clear()

    /**
     * 校验一段文本里提到的文件路径。
     *
     * @return 看过的路径数 + 找不到的那些（去重、保持出现顺序）
     */
    /**
     * @param extraKnownNames 额外认为「存在」的文件名（小写）。
     *
     * 为什么需要：会话的**临时产物现在不在工程里**（在
     * `~/.zhixueyao/Conversation/Product/<时间>/<会话名>/`），
     * 而模型在回答里会写它们的文件名（「已生成 abstract_4k_landscape.svg」）。
     * 只按工程内文件判断的话会全部误报成「这些文件在项目里没找到」——
     * 用户看到的是一个**吓人的黄色警告，而文件其实好好地在临时目录里**。
     * 调用方把本会话已知的产物文件名传进来即可（见 ChatPanel.previewGallery）。
     */
    fun check(project: Project, text: String, extraKnownNames: Set<String> = emptySet()): Result {
        if (text.isBlank()) return Result(0, emptyList())
        val known = knownNames(project)
        val knownFold = known.map { it.lowercase() }.toHashSet()
        knownFold.addAll(extraKnownNames.map { it.lowercase() })

        val seen = LinkedHashSet<String>()
        var checked = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            // `import` / `package` 后面跟的是包名不是文件路径，认了会大面积误报
            if (t.startsWith("import ") || t.startsWith("package ")) continue
            // 整行是 URL 的跳过
            if (t.startsWith("http://") || t.startsWith("https://")) continue
            for (m in CANDIDATE.findAll(line)) {
                val raw = m.groupValues[1].trimEnd('.', ',', ';', ':')
                if (raw.isBlank()) continue
                checked++
                val name = raw.substringAfterLast('/').lowercase()
                if (name in knownFold) continue
                // 文件名索引里没有 → 再按路径实打实找一次（索引可能被排除规则挡住）
                val abs = project.basePath?.let { Paths.get(it).resolve(raw) }
                if (abs != null && Files.exists(abs)) continue
                seen.add(raw)
            }
        }
        return Result(checked, seen.toList())
    }
}
