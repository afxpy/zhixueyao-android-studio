package com.zhixueyao.tools

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonArrOf
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工具执行结果。
 *
 * [ok] 为 false 时内容仍是给模型看的说明文本 —— 让模型知道失败原因并自行纠正，
 * 比直接抛异常中断整轮对话更有用。
 */
class ToolResult(
    val text: String,
    val ok: Boolean = true,
    /**
     * 这次产出/改动的**文件相对路径**（项目内）。
     *
     * 只放路径，**不放内容** —— 二进制绝不能进对话上下文（那会把 token 吃光，
     * 而且模型也读不懂）。界面按这些路径渲染预览卡片。
     */
    val attachments: List<String> = emptyList()
) {
    companion object {
        fun error(message: String) = ToolResult(message, ok = false)
    }
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: Json.Obj

    /** 工具在后台线程执行，实现中无需再做线程切换。 */
    fun execute(project: Project, args: Json.Obj): ToolResult
}

/**
 * 路径安全校验。
 *
 * 两道关卡，职责不同：
 *  1. **系统目录黑名单** —— 无条件拒绝（Windows/Program Files、/etc 这类），
 *     任何权限档位都不放开。碰这些目录不会有正当理由。
 *  2. **沙盒**（见 [com.zhixueyao.agent.Sandbox]）—— 按用户选的权限档位决定
 *     项目外的路径放不放行。默认「仅当前项目」，越界直接拦下。
 */
object PathGuard {

    private val blockedPrefixes = listOf(
        "c:\\windows", "c:\\program files", "c:\\program files (x86)",
        "/etc", "/usr/bin", "/bin", "/sbin", "/boot", "/sys", "/proc"
    )

    fun resolve(project: Project, rawPath: String, action: String = "访问"): Path {
        val cleaned = rawPath.trim().trim('"', '\'')
        if (cleaned.isEmpty()) throw IllegalArgumentException("路径为空")

        val p = runCatching { Paths.get(cleaned) }.getOrElse {
            throw IllegalArgumentException("路径格式非法：$cleaned")
        }

        val absolute = if (p.isAbsolute) p else {
            val base = projectBase(project)
                ?: throw IllegalArgumentException("项目根目录未就绪，请传入绝对路径")
            base.resolve(p)
        }.normalize()

        val lower = absolute.toString().lowercase().replace('\\', '/')
        if (blockedPrefixes.any { lower.startsWith(it) }) {
            throw IllegalArgumentException("出于安全考虑，禁止访问系统目录：$absolute")
        }

        // 沙盒检查。抛的是专用异常，工具的 catch 会把 message 直接当成给模型的说明，
        // 所以文案要写清「为什么不行 + 怎么改权限」
        com.zhixueyao.agent.Sandbox.check(project, action, cleaned, absolute)?.let {
            throw com.zhixueyao.agent.SandboxDeniedException(it)
        }

        // 软链接绕过：上面只比了**路径字符串**的前缀，骗不过 `link -> C:/Windows` 这种链接 ——
        // 项目里放一个指向外面的软链接，写 `link/hosts` 就能落到系统目录。
        // 所以「看起来在项目内」的路径要再按**真实路径**核一次：
        // 真身跑到项目外，说明前缀检查被骗了，直接拒。
        // （沙盒是「全盘」时用户明确要访问项目外，那条路走不到这里，不受影响。）
        val base = projectBase(project)
        if (base != null && absolute.startsWith(base)) {
            // 新建的文件还不存在 → 用最近的已存在祖先来判断真身
            val probe = generateSequence<Path>(absolute) { it.parent }
                .firstOrNull { java.nio.file.Files.exists(it) }
            val real = probe?.let { runCatching { it.toRealPath() }.getOrNull() }
            val realBase = runCatching { base.toRealPath() }.getOrNull()
            if (real != null && realBase != null && !real.startsWith(realBase)) {
                throw IllegalArgumentException(
                    "这个路径经软链接指向了项目外（${real}），已拒绝：$cleaned"
                )
            }
        }
        return absolute
    }

    /**
     * 项目根目录。
     *
     * 注意：AS 2026 已移除 ProjectUtil.guessProjectDir，改用 Project.getBasePath()。
     * 后者直接返回路径字符串，语义稳定且不受平台版本影响。
     */
    fun projectBase(project: Project): Path? =
        project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }

    /** 项目根目录的 VirtualFile，供需要 VFS 的调用方使用。 */
    fun projectBaseDir(project: Project): VirtualFile? = project.baseDir


    fun toVirtualFile(project: Project, rawPath: String): VirtualFile? = runCatching {
        val path = resolve(project, rawPath)
        val direct = LocalFileSystem.getInstance().findFileByPath(path.toString().replace('\\', '/'))
        if (direct != null) return@runCatching direct
        // 尚未被 VFS 感知的新文件，触发一次同步刷新
        val parent = path.parent ?: return@runCatching null
        val parentVf = LocalFileSystem.getInstance()
            .findFileByPath(parent.toString().replace('\\', '/')) ?: return@runCatching null
        parentVf.refresh(false, false)
        parentVf.findChild(path.fileName.toString())
    }.getOrNull()

    /** 相对项目根的展示路径，便于在界面与工具输出中保持简洁。 */
    fun displayPath(project: Project, file: VirtualFile): String {
        val base = projectBaseDir(project) ?: return file.path
        val rel = VfsUtilCore.getRelativePath(file, base, '/')
        return rel ?: file.path
    }
}

// ============================================================
// 文件系统工具
// ============================================================

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "读取项目中的文件内容。返回带行号的文本，便于后续精确引用。大文件可用 offset/limit 分段读取。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj(
                "type" to "string".toJson(),
                "description" to "文件路径，相对于项目根目录或绝对路径"
            ),
            "offset" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "起始行号（从 1 开始），可选"
            ),
            "limit" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "最多读取的行数，可选，默认 800"
            )
        ),
        "required" to jsonArr("path".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("缺少 path 参数")
        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }

        if (!Files.exists(path)) return ToolResult.error("文件不存在：$rawPath")
        if (Files.isDirectory(path)) return ToolResult.error("$rawPath 是目录，请使用 list_directory")

        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > 8L * 1024 * 1024) {
            return ToolResult.error("文件过大（${size / 1024 / 1024} MB），请改用 search_code 定位关键片段")
        }

        // 优先读 VFS 中的文档，这样能拿到用户尚未保存的编辑内容
        val vf = PathGuard.toVirtualFile(project, rawPath)
        val fromVfs: String? = if (vf != null && !vf.isDirectory) {
            // 文档模型属于平台模型，读它要 read action。
            // 工具跑在后台线程，包一层是稳妥做法（这一层出问题会让整个 read_file 挂掉）
            com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
                val doc = FileDocumentManager.getInstance().getDocument(vf)
                doc?.text ?: runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
            }
        } else {
            null
        }
        val text: String = fromVfs
            ?: runCatching { Files.readString(path) }.getOrElse {
                return ToolResult.error("读取失败：${it.message}")
            }

        // 二进制检测：出现 NUL 字节基本可断定非文本
        if (text.contains('\u0000')) {
            return ToolResult.error("$rawPath 是二进制文件，无法按文本读取（大小 $size 字节）")
        }

        val lines = text.split('\n')
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: 800).coerceIn(1, 4000)
        val start = offset - 1
        if (start >= lines.size) {
            return ToolResult.error("起始行 $offset 超出文件范围（共 ${lines.size} 行）")
        }
        val end = minOf(start + limit, lines.size)

        val sb = StringBuilder()
        sb.append("文件：${PathGuard.displayPath(project, vf ?: return ToolResult.error("无法定位文件"))}\n")
        sb.append("总行数：${lines.size}，当前显示 ${start + 1}-$end\n")
        sb.append("```\n")
        for (i in start until end) {
            sb.append((i + 1).toString().padStart(5)).append(" | ").append(lines[i]).append('\n')
        }
        sb.append("```")
        if (end < lines.size) {
            sb.append("\n（文件未读完，可继续用 offset=${end + 1} 读取）")
        }
        return ToolResult(sb.toString())
    }
}

/**
 * 写成文件之后「界面上值得给张预览卡」的扩展名。
 *
 * 单列一份而不是复用 `ImagePreviewDialog.PREVIEW_EXTS`：那边在 ui 包里，
 * 工具包不该反向依赖界面；而且这里要连视频一起算上。
 */
private val MEDIA_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg",
    "mp4", "webm", "mov", "mkv", "avi"
)

class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description =
        "创建新文件或覆盖已有文件的完整内容。修改已有文件时建议优先使用 edit_file，以便保留未改动的部分。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj("type" to "string".toJson(), "description" to "目标文件路径"),
            "content" to jsonObj("type" to "string".toJson(), "description" to "完整的文件内容")
        ),
        "required" to jsonArr("path".toJson(), "content".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("缺少 path 参数")
        val content = args.str("content") ?: return ToolResult.error("缺少 content 参数")

        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }

        return try {
            path.parent?.let { Files.createDirectories(it) }
            val existed = Files.exists(path)
            // 覆盖写之前留一份原文件。改坏了能还原，而且**不阻断**写入 ——
            // 备份失败（磁盘满/权限）不该让 AI 完全没法改代码，只在返回里提一句。
            val backed = if (existed) FileSafety.backup(project, path) else null
            Files.writeString(path, content)
            // 让 IDE 感知磁盘变化，否则编辑器里看到的还是旧内容
            PathGuard.toVirtualFile(project, rawPath)
            // 记进产物清单（产物面板按会话列出这次产出/改动了哪些文件）
            com.zhixueyao.agent.AgentUiBridge.recordArtifact(project, path.toString())
            val action = if (existed) "已覆盖" else "已创建"
            val note = if (existed && backed == null) "（原文件备份失败，无法还原）" else ""
            // 写出来的如果本身就是图片/视频（模型有时直接 write_file 一个 svg），
            // 也回报成附件 —— 否则气泡里既没卡片也没预览入口，
            // 用户会以为「压根没生成图片」（用户反馈过）。
            // path 是 java.nio.Path，没有 .extension —— 自己从文件名取
            val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
            val media = if (ext in MEDIA_EXTS) listOf(path.toString()) else emptyList()
            ToolResult(
                "$action ${rawPath}（${content.length} 字符，${content.lines().size} 行）$note",
                attachments = media
            )
        } catch (e: Exception) {
            ToolResult.error("写入失败：${e.message}")
        }
    }
}

class EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description =
        "精确替换文件中的一段文本。old_text 必须在文件中唯一出现，否则会被拒绝以防误改。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj("type" to "string".toJson(), "description" to "文件路径"),
            "old_text" to jsonObj(
                "type" to "string".toJson(),
                "description" to "要被替换的原文，需在文件中唯一出现，建议包含足够上下文"
            ),
            "new_text" to jsonObj("type" to "string".toJson(), "description" to "替换后的新内容")
        ),
        "required" to jsonArr("path".toJson(), "old_text".toJson(), "new_text".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("缺少 path 参数")
        val oldText = args.str("old_text") ?: return ToolResult.error("缺少 old_text 参数")
        val newText = args.str("new_text") ?: return ToolResult.error("缺少 new_text 参数")

        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }
        if (!Files.exists(path)) return ToolResult.error("文件不存在：$rawPath")

        return try {
            val original = Files.readString(path)
            val count = countOccurrences(original, oldText)
            when {
                count == 0 -> ToolResult.error(
                    "未找到要替换的内容。请先调用 read_file 确认原文，注意缩进与空格需完全一致。"
                )
                count > 1 -> ToolResult.error(
                    "原文在文件中出现了 $count 次，无法确定替换目标。请在 old_text 中加入更多上下文使其唯一。"
                )
                else -> {
                    // 同上：改之前先留一份，改坏了能还原
                    val backed = FileSafety.backup(project, path)
                    Files.writeString(path, original.replace(oldText, newText))
                    PathGuard.toVirtualFile(project, rawPath)
                    com.zhixueyao.agent.AgentUiBridge.recordArtifact(project, path.toString())
                    val note = if (backed == null) "（原文件备份失败，无法还原）" else ""
                    ToolResult("已修改 $rawPath（替换 ${oldText.lines().size} 行 → ${newText.lines().size} 行）$note")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("修改失败：${e.message}")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + 1)
        }
        return count
    }
}

class ListDirectoryTool : AgentTool {
    override val name = "list_directory"
    override val description =
        "列出目录内容。用于了解项目结构，或确认某个模块下有哪些文件。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj(
                "type" to "string".toJson(),
                "description" to "目录路径，留空表示项目根目录"
            ),
            "recursive" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "是否递归列出子目录，默认 false"
            )
        )
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path").orEmpty().ifBlank { "." }
        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }
        if (!Files.exists(path)) return ToolResult.error("目录不存在：$rawPath")
        if (!Files.isDirectory(path)) return ToolResult.error("$rawPath 不是目录")

        val recursive = args.bool("recursive") ?: false
        val ignoreDirs = setOf(
            ".git", ".gradle", "build", ".idea", "node_modules", ".cxx",
            "intermediates", "generated", ".kotlin", "captures", ".externalNativeBuild"
        )

        return try {
            val sb = StringBuilder()
            sb.append("目录：${PathGuard.displayPath(project, PathGuard.toVirtualFile(project, rawPath) ?: return ToolResult.error("无法定位目录"))}\n\n")
            val entries = mutableListOf<String>()
            if (recursive) {
                Files.walk(path).use { stream ->
                    stream.filter { p ->
                        val rel = path.relativize(p)
                        val depth = rel.nameCount
                        depth in 1..4 && rel.none { it.toString() in ignoreDirs }
                    }
                        .limit(500)
                        .forEach { p ->
                            val rel = path.relativize(p).toString().replace('\\', '/')
                            val suffix = if (Files.isDirectory(p)) "/" else ""
                            val size = if (Files.isDirectory(p)) "" else " (${Files.size(p)}B)"
                            entries.add("$rel$suffix$size")
                        }
                }
            } else {
                Files.list(path).use { stream ->
                    stream.sorted().limit(300).forEach { p ->
                        val nameStr = p.fileName.toString()
                        val isDir = Files.isDirectory(p)
                        if (isDir && nameStr in ignoreDirs) return@forEach
                        val suffix = if (isDir) "/" else ""
                        val size = if (isDir) "" else " (${Files.size(p)}B)"
                        entries.add("$nameStr$suffix$size")
                    }
                }
            }
            if (entries.isEmpty()) {
                sb.append("(空目录)")
            } else {
                sb.append(entries.joinToString("\n"))
                sb.append("\n\n共 ${entries.size} 项")
            }
            ToolResult(sb.toString())
        } catch (e: Exception) {
            ToolResult.error("列出目录失败：${e.message}")
        }
    }
}

class GlobFilesTool : AgentTool {
    override val name = "find_files"
    override val description =
        "按文件名或扩展名模式查找文件，例如 \"*.kt\"、\"Main*.java\"、\"build.gradle*\"。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "pattern" to jsonObj(
                "type" to "string".toJson(),
                "description" to "匹配模式，支持 * 与 ? 通配符"
            )
        ),
        "required" to jsonArr("pattern".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.error("缺少 pattern 参数")
        val base = PathGuard.projectBase(project) ?: return ToolResult.error("项目根目录未就绪")

        val ignoreDirs = setOf(
            ".git", ".gradle", "build", ".idea", "node_modules", ".cxx", ".kotlin"
        )
        val regex = GlobSupport.toRegex(pattern)

        return try {
            val hits = mutableListOf<String>()
            Files.walk(base).use { stream ->
                stream.filter { p ->
                    val rel = base.relativize(p)
                    rel.none { it.toString() in ignoreDirs }
                }
                    .filter { Files.isRegularFile(it) }
                    .filter { regex.matches(it.fileName.toString()) }
                    .limit(200)
                    .forEach { hits.add(base.relativize(it).toString().replace('\\', '/')) }
            }
            if (hits.isEmpty()) {
                ToolResult("未找到匹配 \"$pattern\" 的文件")
            } else {
                val sorted = hits.sorted()
                ToolResult("匹配 \"$pattern\" 的文件（共 ${sorted.size} 个）：\n" + sorted.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult.error("查找失败：${e.message}")
        }
    }
}

/** 通配符转正则，供工具内部复用。 */
object GlobSupport {
    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

/**
 * 删除文件（**软删除**）。
 *
 * 为什么不物理删除：AI 判断错「这个文件没人用」的代价是不可逆的，
 * 而项目里删掉的文件往往正是用户还要的东西。移进回收站后，7 天内随时能捞回来。
 */
class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description =
        "删除项目内的文件。文件会被移进回收站（不是物理删除），7 天内可恢复。" +
            "删除前请确认该文件确实没有被引用，拿不准就先搜一下。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj("type" to "string".toJson(), "description" to "要删除的文件路径")
        ),
        "required" to jsonArr("path".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("缺少 path 参数")
        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }
        if (!Files.exists(path)) return ToolResult.error("文件不存在：$rawPath")
        if (Files.isDirectory(path)) {
            return ToolResult.error("这是个目录。本工具只删单个文件；目录请自行确认后处理。")
        }

        val recycled = FileSafety.recycle(project, path)
            ?: return ToolResult.error("移入回收站失败，未做任何删除：$rawPath")
        // 让 IDE 感知文件消失：刷**父目录** —— 文件本身已经不在了，去找它没用
        refreshParent(path)
        return ToolResult("已删除 $rawPath（已移入回收站，可恢复；回收站保留 ${FileSafety.RECYCLE_KEEP_DAYS} 天）")
    }
}

/**
 * 从备份还原文件。
 *
 * 配合 [FileSafety.backup]：每次覆盖写/编辑之前都会自动留一份原文件，
 * 所以「AI 改坏了」永远有退路 —— 这是「无确认直接改」能成立的前提。
 */
class RestoreFileTool : AgentTool {
    override val name = "restore_file"
    override val description =
        "把文件还原成**上一次修改前**的样子（自动备份的那一份）。" +
            "当你的修改把文件改坏了、或者用户说「改回去」时用它。" +
            "不带 path 则列出所有有备份的文件。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj("type" to "string".toJson(), "description" to "要还原的文件路径（相对项目根）")
        ),
        "required" to jsonArr()
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path")
        if (rawPath.isNullOrBlank()) {
            val all = FileSafety.listBackups(project)
            if (all.isEmpty()) return ToolResult("当前项目还没有任何备份。备份在每次修改文件之前自动生成。")
            val shown = all.take(40).joinToString("\n")
            val more = if (all.size > 40) "\n…（还有 ${all.size - 40} 个）" else ""
            return ToolResult("有备份的文件（共 ${all.size} 个）：\n$shown$more")
        }

        val path = try {
            PathGuard.resolve(project, rawPath)
        } catch (e: Exception) {
            return ToolResult.error(e.message ?: "路径无效")
        }
        val root = project.basePath ?: return ToolResult.error("拿不到项目根目录")
        val rel = runCatching {
            java.nio.file.Paths.get(root).toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        }.getOrNull() ?: return ToolResult.error("路径无效：$rawPath")

        if (!FileSafety.restore(project, rel)) {
            return ToolResult.error("这个文件没有备份，无法还原：$rawPath")
        }
        // 文件通常**已经存在**（只是内容被改过），toVirtualFile 会因为找到了就直接返回、
        // 不触发刷新，编辑器里还是旧内容 —— 所以这里必须强制刷父目录
        refreshParent(path)
        return ToolResult("已把 $rawPath 还原成上一次修改前的版本")
    }
}

/**
 * 让 IDE 感知某个路径所在的**目录**变了。
 *
 * 删除/还原之后用它，而不是 `toVirtualFile`：后者的语义是「找到这个文件的 VF」，
 * 文件已经不存在时会返回 null 且不刷新，文件存在时又会因为找到而跳过刷新 ——
 * 两种情况都刷不到，编辑器里看到的还是旧状态。
 */
private fun refreshParent(path: java.nio.file.Path) {
    runCatching {
        val parent = path.parent ?: return
        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(parent.toString().replace('\\', '/'))
            ?.refresh(false, false)
    }
}
