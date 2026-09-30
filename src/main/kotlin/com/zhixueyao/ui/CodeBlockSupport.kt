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
     * ## 一套解析，两处消费
     *
     * 这里原本有**两套独立实现**解析同一个围栏语法：`extractBlocks` 抽卡片、
     * `stripBlocks` 把代码从正文里摘掉。文档里写着「口径必须完全一致」，
     * 但**没有任何机制保证** —— 于是它们悄悄分叉了：
     *
     * | | extractBlocks | stripBlocks |
     * |---|---|---|
     * | 跳收尾围栏 | `i++` 无守卫 | `if (i < lines.size) i++` |
     * | 空块判定 | `body.isNotEmpty()`（含空行也算） | `nonEmpty > 0`（只数非空行） |
     * | 围栏长度 | 不区分 | 不区分 |
     *
     * 探针（`CodeFenceProbe`）一跑就现原形：
     *
     * - **只含空行的块**：正文侧说「没有内容」，卡片侧却**生成一张空卡片**
     * - **四个反引号包住三反引号**（写 Markdown 教程时的必然输入）：
     *   围栏被吃掉、`val z = 3` 掉进正文、**一张卡片都没生成**
     *
     * 修法不是「把两套改得一样」—— 那只是把下次分叉推迟。而是**只留一套**：
     * 先解析成分段序列，两个函数各自消费它。这样它们**在构造上就一致**，
     * 再也不会漂移。（同一个思路：`richRendered` 那次也是「让做决定的那一步
     * 把结果记下来，别处别自己再判断一次」。）
     */
    private sealed interface Seg {
        data class Text(val text: String) : Seg
        data class Code(val language: String, val code: String) : Seg
    }

    /**
     * 这一行开头的连续反引号个数；不足 3 个返回 0。
     *
     * ## 这里刻意**不按 CommonMark 的「最多 3 空格缩进」**
     *
     * 标准规定缩进 4 格以上算「缩进代码块」而不是围栏。第一版按标准实现了，
     * **探针立刻报了一个回归**：4 空格缩进的围栏不再被识别，
     * ` ```kotlin ` 原样留在正文里（而摘不干净围栏正是本文件要解决的问题）。
     *
     * 宽松在这里是对的：输入是**模型的输出**，不是人手写的规范 Markdown。
     * 模型在列表项里写代码块时很容易缩进 4 格以上：
     *
     * ```
     * 1. 这样做：
     *     ```kotlin        ← 4 空格，按标准不算围栏，但那明显是个围栏
     *     val x = 1
     *     ```
     * ```
     *
     * 判错方向的代价不对称：
     *  - 宽松误判：把一小段缩进文本当成代码卡片（可接受）
     *  - 严格漏判：**围栏漏进正文**，富文本会把后面一大段吃成代码样式（难看且难查）
     *
     * 所以取宽松。这条约束是**探针钉住的**，不是靠注释提醒。
     */
    private fun fenceRun(line: String): Int {
        val t = line.trimStart()
        return t.takeWhile { it == '`' }.length.takeIf { it >= 3 } ?: 0
    }

    /**
     * 把 Markdown 切成「正文段」与「代码段」。
     *
     * 围栏规则按 CommonMark 来：
     *  - 开围栏：≥3 个反引号，后面可以带语言标注
     *  - 闭围栏：反引号**不少于**开围栏，且**后面没有别的内容**
     *
     * 第二条是修 bug 的关键：` ````markdown ` 开、` ``` ` 收，那三反引号
     * **不算闭合**（它带着 `kotlin` 标注，而且比开围栏短），于是它连同里面的内容
     * 一起成为代码 —— 这才符合「用四个反引号包住三反引号」的写法意图。
     *
     * **未闭合**时把剩下的全部当代码（流式输出中途、或模型漏了收尾）。
     * 这是刻意容忍的：宁可多显示一张卡片，也不要把半截代码甩回正文。
     */
    private fun parse(markdown: String): List<Seg> {
        val lines = markdown.lines()
        val out = mutableListOf<Seg>()
        val pending = StringBuilder()
        var i = 0

        fun flushText() {
            if (pending.isNotEmpty()) {
                out.add(Seg.Text(pending.toString()))
                pending.setLength(0)
            }
        }

        while (i < lines.size) {
            val open = fenceRun(lines[i])
            if (open == 0) {
                pending.append(lines[i]).append('\n')
                i++
                continue
            }

            // 开围栏后面的第一个词是语言标注
            val lang = lines[i].trimStart().drop(open).trim().substringBefore(' ')

            val body = StringBuilder()
            i++
            while (i < lines.size) {
                val close = fenceRun(lines[i])
                val rest = lines[i].trimStart().drop(close).trim()
                if (close >= open && rest.isEmpty()) {
                    i++                       // 吃掉收尾围栏
                    break
                }
                body.append(lines[i]).append('\n')
                i++
            }

            // **空块不生成代码段** —— 原文里出现 ` ``` ` / ` ``` ` 这一对时
            // （模型偶尔会这么写），不该在界面上多出一个空卡片。
            // 判据是 isNotBlank（只数非空行），和占位文案的口径保持一致。
            if (body.isNotBlank()) {
                flushText()
                out.add(Seg.Code(lang, body.toString().trimEnd('\n')))
            }
        }
        flushText()
        return out
    }

    /**
     * 把围栏代码块从 Markdown 里**摘掉**，换成一行占位提示。
     *
     * 为什么需要：气泡会把代码块**单独做成卡片**（带语法高亮和「应用」按钮），
     * 而 Markdown 渲染出的 HTML 里也带着 `<pre><code>` ——
     * 不摘的话同一段代码在气泡里**显示两遍**（一遍在正文、一遍在卡片），
     * 长回答里非常浪费空间、看着也乱。
     *
     * 只处理**围栏块**，不碰行内 `` `code` `` —— 行内的那点代码留在正文里读着更顺。
     */
    fun stripBlocks(markdown: String): String {
        val out = StringBuilder()
        for (seg in parse(markdown)) {
            when (seg) {
                is Seg.Text -> out.append(seg.text)
                is Seg.Code -> {
                    val n = seg.code.lines().count { it.isNotBlank() }
                    out.append("（代码 ").append(n).append(" 行，见下方代码卡片）").append('\n')
                }
            }
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * 从 Markdown 文本中提取代码块，并尝试为每个块推断目标文件。
     */
    fun extractBlocks(markdown: String): List<Block> {
        val pathRegex = Regex(
            """(?:^|\s)([\w./\\-]+\.(?:kt|kts|java|xml|gradle|properties|json|toml|pro|md|txt|c|cpp|h|js|ts|py|sql|yml|yaml))"""
        )

        val blocks = mutableListOf<Block>()
        var lastPathHint: String? = null

        for (seg in parse(markdown)) {
            when (seg) {
                is Seg.Text -> {
                    // 记录最近一次出现的文件路径提示，供紧随其后的代码块使用
                    pathRegex.find(seg.text)?.let { m ->
                        val candidate = m.groupValues[1]
                        // 排除明显的 URL 与依赖坐标
                        if (!candidate.startsWith("http") && !candidate.contains("://")) {
                            lastPathHint = candidate
                        }
                    }
                }
                is Seg.Code -> blocks.add(
                    Block(
                        language = seg.language,
                        code = seg.code,
                        suggestedPath = lastPathHint
                    )
                )
            }
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
