package com.zhixueyao.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.zhixueyao.util.Json

/**
 * 把 AI 回答里的代码块解析出来，并支持写入文件。
 *
 * 解析时应记录代码块「属于哪个文件」—— 优先取代码块前一行提到的文件路径，
 * 例如「修改 app/src/main/java/MainActivity.kt:」这种写法。
 */
object CodeBlockSupport {

    /** 一个被识别出的代码块。 */
    data class Block(
        val language: String,
        val code: String,
        /** 推断出的目标文件路径，可能为空 */
        val suggestedPath: String?
    )

    /**
     * 从 Markdown 文本中提取代码块，并尝试为每个块推断目标文件。
     */
    /**
     * 把围栏代码块从 Markdown 里**摘掉**，换成一行占位提示。
     *
     * 为什么需要：气泡会把代码块**单独做成卡片**（带语法高亮和「应用」按钮），
     * 而 Markdown 渲染出的 HTML 里也带着 `<pre><code>` ——
     * 不摘的话同一段代码在气泡里**显示两遍**（一遍在正文、一遍在卡片），
     * 长回答里非常浪费空间、看着也乱。
     *
     * 口径必须和 [extractBlocks] 完全一致（同一套 ``` 围栏判定）——
     * 否则会出现「摘掉的」和「做成卡片的」不是同一批，那就更乱。
     * 探针里专门断言两者的块数相等。
     *
     * 只处理**围栏块**，不碰行内 `` `code` `` —— 行内的那点代码留在正文里读着更顺。
     */
    fun stripBlocks(markdown: String): String {
        val lines = markdown.lines()
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val fence = lines[i].trimStart()
            if (!fence.startsWith("```")) {
                out.append(lines[i]).append('\n')
                i++
                continue
            }
            // 整块跳过（含首尾围栏），只留一行说明
            i++
            var nonEmpty = 0
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (lines[i].isNotBlank()) nonEmpty++
                i++
            }
            if (i < lines.size) i++   // 跳过收尾围栏
            if (nonEmpty > 0) {
                out.append("（代码 $nonEmpty 行，见下方代码卡片）").append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    fun extractBlocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.lines()
        var i = 0
        var lastPathHint: String? = null

        val pathRegex = Regex(
            """(?:^|\s)([\w./\\-]+\.(?:kt|kts|java|xml|gradle|properties|json|toml|pro|md|txt|c|cpp|h|js|ts|py|sql|yml|yaml))"""
        )

        while (i < lines.size) {
            val line = lines[i]

            // 记录最近一次出现的文件路径提示，供紧随其后的代码块使用
            pathRegex.find(line)?.let { m ->
                val candidate = m.groupValues[1]
                // 排除明显的 URL 与依赖坐标
                if (!candidate.startsWith("http") && !candidate.contains("://")) {
                    lastPathHint = candidate
                }
            }

            val fence = line.trimStart()
            if (fence.startsWith("```")) {
                val lang = fence.removePrefix("```").trim().substringBefore(' ')
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                // 跳过收尾的 ```
                i++
                if (body.isNotEmpty()) {
                    blocks.add(
                        Block(
                            language = lang,
                            code = body.toString().trimEnd('\n'),
                            suggestedPath = lastPathHint
                        )
                    )
                }
                continue
            }
            i++
        }
        return blocks
    }

    /**
     * 把代码块内容写入文件。
     *
     * 通过 Document 写入而非直接改磁盘，这样用户可以直接 Ctrl+Z 撤销。
     */
    fun applyToFile(project: Project, path: String, code: String): Result<String> {
        return try {
            val base = com.zhixueyao.tools.PathGuard.projectBase(project)
            val resolved = com.zhixueyao.tools.PathGuard.resolve(project, path)
            val vf = LocalFileSystem.getInstance()
                .findFileByPath(resolved.toString().replace('\\', '/'))
                ?: return Result.failure(
                    IllegalStateException("文件不存在：$path\n如需新建，请让 AI 使用 write_file 工具")
                )

            val doc: Document = FileDocumentManager.getInstance().getDocument(vf)
                ?: return Result.failure(IllegalStateException("无法获取文件文档：$path"))

            WriteCommandAction.runWriteCommandAction(project, "应用止血药改动", null, {
                doc.setText(code)
                FileDocumentManager.getInstance().saveDocument(doc)
            })

            val display = base?.let {
                runCatching { it.relativize(resolved).toString().replace('\\', '/') }.getOrNull()
            } ?: path
            Result.success("已应用到 $display（可用 Ctrl+Z 撤销）")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 弹出差异确认框后应用。需在 EDT 调用。 */
    fun confirmAndApply(
        project: Project,
        block: Block,
        parentComponent: java.awt.Component?
    ): String? {
        // 目标文件的确定顺序（前一条命中就不问用户）：
        //  1. 代码块附近提到的路径（extractBlocks 已经推出来了）
        //  2. **按代码内容在项目里反查** —— 命中唯一就直接用，命中多个让用户点一下
        //  3. 都查不到，才给文件选择器
        // 之所以要第 2 条：模型给的往往是**片段**（改两行），不带路径，
        // 直接弹「请选择文件」既突兀又难选（用户反馈过）。
        val path = block.suggestedPath ?: run {
            val candidates = locateTargets(project, block)
            when {
                candidates.size == 1 -> candidates.first()

                candidates.size in 2..7 -> {
                    val labels = candidates.map { relative(project, it) }.toTypedArray()
                    val picked = Messages.showDialog(
                        project,
                        "这段代码在下面几个文件里都能找到，写进哪一个？（选中的会先给差异预览）",
                        "应用到文件 · 选择目标",
                        labels,
                        0,
                        Messages.getQuestionIcon()
                    )
                    if (picked < 0) return null
                    candidates[picked]
                }

                else -> {
                    val chooser = javax.swing.JFileChooser(project.basePath).apply {
                        dialogTitle = "选择这段代码要写入的文件"
                        fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                        isMultiSelectionEnabled = false
                    }
                    if (chooser.showOpenDialog(parentComponent) != javax.swing.JFileChooser.APPROVE_OPTION) return null
                    val chosen = chooser.selectedFile ?: return null
                    relative(project, chosen.path)
                }
            }
        }

        // 读取文件当前内容作为差异对比的左侧；文件不存在时左侧留空
        val oldText = readCurrentText(project, path)
        val confirmed = if (oldText != null) {
            DiffConfirmDialog.confirm(project, path, oldText, block.code)
        } else {
            // 文件不存在或无法读取：退化为文本预览确认
            textPreviewConfirm(project, path, block)
        }
        if (!confirmed) return null

        return applyToFile(project, path, block.code).fold(
            onSuccess = { it },
            onFailure = { "应用失败：${it.message}" }
        )
    }

    /** 转成相对项目根的路径，便于展示与写入 */
    private fun relative(project: Project, absolute: String): String {
        val base = project.basePath ?: return absolute
        return if (absolute.startsWith(base)) {
            absolute.removePrefix(base).trimStart('\\', '/')
        } else {
            absolute
        }
    }

    /**
     * 按**代码内容**在项目里反查目标文件。
     *
     * 做法：取代码块里最有辨识度的一行（跳过空行、括号、注释），
     * 扫项目内的文本文件，看谁包含这一行；命中多个就都返回。
     *
     * 为什么不用 PSI 的单词索引：那要装语言插件、也只对已建立索引的文件有效，
     * 而这里要的是「任意文本文件里都能找」—— 直接读内容最稳。
     * 代价是扫盘，所以有三道闸：只扫项目内、跳过构建产物与二进制、单文件 2MB、
     * 总文件数 6000。真的超出就返回空，自动落到文件选择器那条路，不会卡死。
     */
    private fun locateTargets(project: Project, block: Block): List<String> {
        val needle = block.code.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length >= 8 &&
                    !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") &&
                    !line.startsWith("#") && !line.startsWith("@") &&
                    line.count { it.isLetter() } >= 4
            } ?: return emptyList()

        // 用 **ProjectFileIndex**（`com.intellij.openapi.roots`，注意不是 openapi 根下）
        // 遍历项目内容；只扫文本类、跳过构建产物，并给总文件数设上限。
        // 真超出上限就返回已找到的（可能为空），自动落到「让用户选」那条路，不会卡死。
        // ProjectFileIndex 也是**索引访问**，必须在 read action 里 ——
        // 这个方法由「应用改动」按钮触发（EDT），不包会抛
        // "Read access is allowed from inside read-action only"。
        val index = com.intellij.openapi.application.ReadAction.compute<
            com.intellij.openapi.roots.ProjectFileIndex, RuntimeException
            > { com.intellij.openapi.roots.ProjectFileIndex.getInstance(project) }
        val hits = mutableListOf<String>()
        var scanned = 0

        index.iterateContent { vf ->
            if (scanned++ > 6000 || hits.size > 7) return@iterateContent false
            if (vf.isDirectory || !vf.isValid) return@iterateContent true
            if (!vf.fileType.isBinary && vf.length < 2L * 1024 * 1024) {
                val path = vf.path
                if (!path.contains("/build/") && !path.contains("/.git/") && !path.contains("/gradle/")) {
                    val text = runCatching { String(vf.contentsToByteArray(), vf.charset) }.getOrNull()
                    if (text != null && text.contains(needle)) hits.add(path)
                }
            }
            true
        }
        return hits
    }

    /** 读取目标文件当前内容；失败返回 null。 */
    private fun readCurrentText(project: Project, path: String): String? = runCatching {
        val resolved = com.zhixueyao.tools.PathGuard.resolve(project, path)
        val vf = LocalFileSystem.getInstance()
            .findFileByPath(resolved.toString().replace('\\', '/')) ?: return null
        FileDocumentManager.getInstance().getDocument(vf)?.text
            ?: String(vf.contentsToByteArray(), vf.charset)
    }.getOrNull()

    /** 无法做差异对比时的兜底确认。 */
    private fun textPreviewConfirm(project: Project, path: String, block: Block): Boolean {
        val preview = buildString {
            append("目标文件：").append(path).append("\n\n")
            append("该文件当前不存在或无法读取，将新建/覆盖为以下内容：\n\n")
            append(block.code.lines().take(40).joinToString("\n"))
            if (block.code.lines().size > 40) {
                append("\n...(共 ").append(block.code.lines().size).append(" 行)")
            }
            append("\n\n确认继续？")
        }
        return Messages.showYesNoDialog(
            project,
            preview,
            "应用改动到 $path",
            "应用",
            "取消",
            Messages.getWarningIcon()
        ) == Messages.YES
    }
}
