package com.zhixueyao.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import java.nio.file.Files

/**
 * 全文搜索。
 *
 * 实现策略说明：优先自实现扫描，而非依赖 IDE 的 FindInProject 内部 API。
 * 原因是在开发过程中发现这些内部 API 在平台版本间签名变动频繁
 * （如 FindModel 的 setter 命名、FindInProjectUtil 的消费者接口都变过），
 * 而插件需要跨版本稳定。自实现扫描虽然略慢，但行为完全可控且不会随 IDE 升级而失效。
 */
class SearchCodeTool : AgentTool {

    override val name = "search_code"
    override val description =
        "在整个项目中搜索文本或正则表达式，返回匹配的文件与行号。这是定位代码最常用的工具。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj(
            "query" to jsonObj(
                "type" to "string",
                "description" to "搜索内容"
            ),
            "regex" to jsonObj(
                "type" to "boolean",
                "description" to "是否按正则表达式处理，默认 false（普通文本）"
            ),
            "case_sensitive" to jsonObj(
                "type" to "boolean",
                "description" to "是否区分大小写，默认 false"
            ),
            "file_pattern" to jsonObj(
                "type" to "string",
                "description" to "限定文件类型，如 *.kt、*.java，可选"
            ),
            "max_results" to jsonObj(
                "type" to "integer",
                "description" to "最多返回的匹配数，默认 60"
            )
        ),
        "required" to jsonArr("query")
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val query = args.str("query") ?: return ToolResult.error("缺少 query 参数")
        if (query.isBlank()) return ToolResult.error("搜索内容不能为空")

        val isRegex = args.bool("regex") ?: false
        val caseSensitive = args.bool("case_sensitive") ?: false
        val filePattern = args.str("file_pattern")
        val maxResults = (args.int("max_results") ?: 60).coerceIn(1, 300)

        val base = PathGuard.projectBase(project)
            ?: return ToolResult.error("项目根目录未就绪")

        // 先编译正则（若需要），再统一构造匹配函数。
        // 注意不要把 lambda 直接写在 getOrElse{} 之后 —— Kotlin 会把它解析成尾随参数。
        val compiledRegex: Regex? = if (isRegex) {
            runCatching {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { return ToolResult.error("正则表达式非法：${it.message}") }
        } else {
            null
        }

        val matcher: (String) -> Boolean = { line ->
            when {
                compiledRegex != null -> compiledRegex.containsMatchIn(line)
                caseSensitive -> line.contains(query)
                else -> line.contains(query, ignoreCase = true)
            }
        }

        val namePattern = filePattern?.let { GlobSupport.toRegex(it) }

        return try {
            val results = mutableListOf<Hit>()
            var truncated = false

            Files.walk(base).use { stream ->
                val iter = stream
                    .filter { p -> base.relativize(p).none { it.toString() in IGNORE_DIRS } }
                    .filter { Files.isRegularFile(it) }
                    .filter { p ->
                        val name = p.fileName.toString()
                        val ext = name.substringAfterLast('.', "").lowercase()
                        ext in TEXT_EXTS && (namePattern?.matches(name) ?: true)
                    }
                    .iterator()

                outer@ while (iter.hasNext()) {
                    val p = iter.next()
                    val rel = base.relativize(p).toString().replace('\\', '/')
                    // 单文件过大时跳过，避免拖垮整个搜索
                    if (runCatching { Files.size(p) }.getOrDefault(0L) > 4L * 1024 * 1024) continue

                    val lines = runCatching { Files.readAllLines(p) }.getOrNull() ?: continue
                    for ((idx, lineText) in lines.withIndex()) {
                        if (matcher(lineText)) {
                            results.add(Hit(rel, idx + 1, lineText.trim().take(200)))
                            if (results.size >= maxResults) {
                                truncated = true
                                break@outer
                            }
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                return ToolResult("未找到匹配 \"$query\" 的内容")
            }

            val sb = StringBuilder()
            val byFile = results.groupBy { it.file }
            sb.append("搜索 \"$query\" 命中 ${results.size} 处，分布在 ${byFile.size} 个文件：\n\n")
            for ((file, hits) in byFile) {
                sb.append("▶ $file\n")
                for (h in hits.take(15)) {
                    sb.append("  L${h.line}: ${h.text}\n")
                }
                if (hits.size > 15) sb.append("  ...（该文件另有 ${hits.size - 15} 处）\n")
                sb.append('\n')
            }
            if (truncated) {
                sb.append("已截断，仅显示前 $maxResults 条。可用 file_pattern 缩小范围或提高 max_results。")
            }
            ToolResult(sb.toString().trim())
        } catch (e: Exception) {
            ToolResult.error("搜索失败：${e.message}")
        }
    }

    private class Hit(val file: String, val line: Int, val text: String)

    companion object {
        val IGNORE_DIRS = setOf(
            ".git", ".gradle", "build", ".idea", "node_modules", ".cxx",
            ".kotlin", "captures", ".externalNativeBuild", "intermediates", "generated"
        )

        val TEXT_EXTS = setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt",
            "toml", "yml", "yaml", "pro", "c", "cpp", "h", "hpp", "js", "ts", "jsx", "tsx",
            "html", "css", "scss", "sql", "sh", "bat", "py", "rb", "go", "rs", "smali"
        )
    }
}

/**
 * 按名称查找文件。
 *
 * 先用 IDE 的文件索引（速度快），索引不可用时降级为遍历。
 */
class FindSymbolTool : AgentTool {

    override val name = "find_symbol"
    override val description =
        "按文件名查找文件。适合在不知道具体路径时快速定位，例如查找 MainActivity、build.gradle。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj(
            "name" to jsonObj(
                "type" to "string",
                "description" to "要查找的文件名（不含路径），支持部分匹配，如 MainActivity"
            )
        ),
        "required" to jsonArr("name")
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val query = args.str("name") ?: return ToolResult.error("缺少 name 参数")
        if (query.isBlank()) return ToolResult.error("查找名称不能为空")

        val base = PathGuard.projectBase(project)
            ?: return ToolResult.error("项目根目录未就绪")

        // 先从索引取（快），失败或为空再遍历
        val fromIndex = runCatching { findByIndex(project, query) }.getOrDefault(emptyList())
        if (fromIndex.isNotEmpty()) {
            return ToolResult(
                "匹配 \"$query\" 的文件（${fromIndex.size} 个）：\n" + fromIndex.sorted().joinToString("\n")
            )
        }

        return try {
            val hits = mutableListOf<String>()
            Files.walk(base).use { stream ->
                stream
                    .filter { p -> base.relativize(p).none { it.toString() in SearchCodeTool.IGNORE_DIRS } }
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().contains(query, ignoreCase = true) }
                    .limit(120)
                    .forEach { hits.add(base.relativize(it).toString().replace('\\', '/')) }
            }
            if (hits.isEmpty()) {
                ToolResult("未找到名称含 \"$query\" 的文件")
            } else {
                val sorted = hits.sorted()
                ToolResult("匹配 \"$query\" 的文件（${sorted.size} 个）：\n" + sorted.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult.error("查找失败：${e.message}")
        }
    }

    /** 借助 IDE 的文件名索引，命中速度远快于遍历。 */
    private fun findByIndex(project: Project, query: String): List<String> {
        val base = PathGuard.projectBaseDir(project) ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val out = mutableListOf<String>()

        ReadAction.run<RuntimeException> {
            val index = FilenameIndex::class.java
            // 直接按名称查询命中率最高；对部分匹配则遍历全部文件名集合
            val allNames = FilenameIndex.getAllFilenames(project)
            for (name in allNames) {
                if (out.size >= 120) break
                if (name.contains(query, ignoreCase = true)) {
                    val files = FilenameIndex.getVirtualFilesByName(name, scope)
                    for (vf in files) {
                        if (out.size >= 120) break
                        val rel = VfsUtilCore.getRelativePath(vf, base, '/')
                        if (rel != null) out.add(rel)
                    }
                }
            }
        }
        return out.distinct()
    }
}

/**
 * 读取当前编辑器状态：打开的文件、光标位置、选中代码。
 * 用户说「这段代码」「当前文件」时，模型应调用它来对齐上下文。
 */
class GetEditorContextTool : AgentTool {

    override val name = "get_editor_context"
    override val description =
        "获取用户当前正在编辑的文件、光标位置以及选中的代码。当用户提到「这段代码」「当前文件」时使用。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj()
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        return try {
            var out: String? = null

            ReadAction.run<RuntimeException> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor == null) {
                    out = "当前没有打开的文件"
                    return@run
                }
                val doc = editor.document
                val vf = FileDocumentManager.getInstance().getFile(doc)
                val path = if (vf != null) PathGuard.displayPath(project, vf) else "(未保存的文档)"

                val sb = StringBuilder()
                sb.append("当前文件：$path\n")
                val caret = editor.caretModel.logicalPosition
                sb.append("光标位置：第 ${caret.line + 1} 行，第 ${caret.column + 1} 列\n")
                sb.append("文件总行数：${doc.lineCount}\n")

                val selection = editor.selectionModel
                if (selection.hasSelection()) {
                    val text = selection.selectedText?.replace('\u2028', '\n') ?: ""
                    val startLine = doc.getLineNumber(selection.selectionStart) + 1
                    val endLine = doc.getLineNumber(selection.selectionEnd) + 1
                    sb.append("\n用户选中了第 $startLine-$endLine 行的代码：\n```\n")
                    sb.append(text)
                    sb.append("\n```")
                } else {
                    sb.append("\n当前没有选中代码。\n")
                    // 给出光标附近上下文，帮助模型理解用户所指
                    val from = (caret.line - 15).coerceAtLeast(0)
                    val to = (caret.line + 15).coerceAtMost(doc.lineCount - 1)
                    sb.append("光标附近代码（第 ${from + 1}-${to + 1} 行）：\n```\n")
                    for (i in from..to) {
                        val lineText = doc.getText(
                            TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i))
                        )
                        sb.append(lineText).append('\n')
                    }
                    sb.append("```")
                }
                out = sb.toString()
            }

            ToolResult(out ?: "无法读取编辑器状态")
        } catch (e: Exception) {
            ToolResult.error("读取编辑器状态失败：${e.message}")
        }
    }
}
