package com.zhixueyao.tools

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 读取项目编译诊断。
 *
 * 这是插件最有价值的能力之一：模型改完代码后能立刻知道自己改坏了什么，
 * 而不是等用户去点编译才发现。
 *
 * 数据源是 IDE 的 Daemon 分析结果（编辑器里的红波浪线），覆盖语法错误与语义检查。
 * 注意 getHighlights 需要已打开文档的 Document 实例，对未打开的文件需先取出其 Document。
 */
class GetDiagnosticsTool : AgentTool {

    override val name = "get_diagnostics"
    override val description =
        "获取项目中当前的编译错误与警告。修改代码后用它验证改动是否正确。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj(
            "path" to jsonObj(
                "type" to "string",
                "description" to "限定检查的文件或目录，留空表示整个项目"
            ),
            "severity" to jsonObj(
                "type" to "string",
                "description" to "筛选级别：error / warning / all，默认 error"
            ),
            "max_results" to jsonObj(
                "type" to "integer",
                "description" to "最多返回条数，默认 80"
            )
        )
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path")
        val severity = args.strOr("severity", "error")
        val maxResults = (args.int("max_results") ?: 80).coerceIn(1, 400)

        val target: VirtualFile? = if (rawPath.isNullOrBlank()) {
            PathGuard.projectBaseDir(project)
        } else {
            PathGuard.toVirtualFile(project, rawPath)
        }
        if (target == null) {
            return ToolResult.error(
                if (rawPath.isNullOrBlank()) "项目根目录未就绪" else "路径不存在：$rawPath"
            )
        }

        return try {
            val problems = collectProblems(project, target, severity, maxResults)
            val scopeText = if (rawPath.isNullOrBlank()) "整个项目" else rawPath

            if (problems.isEmpty()) {
                ToolResult("$scopeText 未发现${severityLabel(severity)}。代码状态良好。")
            } else {
                val sb = StringBuilder()
                sb.append("发现 ${problems.size} 个${severityLabel(severity)}：\n\n")
                problems.forEach { sb.append(it).append('\n') }
                if (problems.size >= maxResults) {
                    sb.append("\n（已达上限 $maxResults 条，可能还有更多问题）")
                }
                ToolResult(sb.toString())
            }
        } catch (e: Exception) {
            ToolResult.error("获取诊断信息失败：${e.message}")
        }
    }

    private fun severityLabel(s: String) = when (s) {
        "warning" -> "警告"
        "all" -> "问题"
        else -> "编译错误"
    }

    private fun collectProblems(
        project: Project,
        target: VirtualFile,
        severity: String,
        maxResults: Int
    ): List<String> {
        val out = mutableListOf<String>()
        val base = PathGuard.projectBaseDir(project)
        val includeErrors = severity == "error" || severity == "all"
        val includeWarnings = severity == "warning" || severity == "all"

        // 收集待检查的文件
        val files = mutableListOf<VirtualFile>()
        if (target.isDirectory) {
            VfsUtilCore.iterateChildrenRecursively(
                target,
                { vf ->
                    if (vf.isDirectory) {
                        vf.name !in SearchCodeTool.IGNORE_DIRS
                    } else {
                        vf.extension?.lowercase() in setOf("kt", "java", "kts", "xml")
                    }
                }
            ) { vf ->
                if (!vf.isDirectory && files.size < 300) files.add(vf)
                true
            }
        } else {
            files.add(target)
        }

        ReadAction.run<RuntimeException> {
            val docManager = FileDocumentManager.getInstance()
            outer@ for (vf in files) {
                if (out.size >= maxResults) break

                // getHighlights 需要 Document；对未打开的文件，这里会创建（并缓存）一个只读 Document
                val doc = docManager.getDocument(vf) ?: continue

                val infos = runCatching {
                    DaemonCodeAnalyzerImpl.getHighlights(doc, HighlightSeverity.WARNING, project)
                }.getOrNull() ?: continue

                for (info in infos) {
                    if (out.size >= maxResults) break@outer

                    val sev = info.getSeverity() ?: continue
                    val isError = sev >= HighlightSeverity.ERROR
                    val isWarning = sev >= HighlightSeverity.WARNING

                    val wanted = when {
                        isError -> includeErrors
                        isWarning -> includeWarnings
                        else -> false
                    }
                    if (!wanted) continue

                    val line = runCatching { doc.getLineNumber(info.getStartOffset()) + 1 }.getOrDefault(0)
                    val rel = if (base != null) {
                        VfsUtilCore.getRelativePath(vf, base, '/') ?: vf.path
                    } else vf.path

                    val tag = if (isError) "错误" else "警告"
                    val desc = info.getDescription()?.replace('\n', ' ')?.take(240) ?: "未知问题"
                    out.add("[$tag] $rel:$line  $desc")
                }
            }
        }
        return out
    }
}

/**
 * 触发项目构建。
 *
 * 相比逐文件分析，真实构建能发现跨模块、资源引用、注解处理等深层问题。
 * 构建耗时长，因此带超时控制：超时后返回提示而非无限阻塞对话。
 */
class RunBuildTool : AgentTool {

    override val name = "run_build"
    override val description =
        "触发项目编译并返回结果。用于验证改动能否真正编译通过，耗时较长，建议必要时才用。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj(
            "timeout_seconds" to jsonObj(
                "type" to "integer",
                "description" to "最长等待秒数，默认 240"
            )
        )
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val timeout = (args.int("timeout_seconds") ?: 240).coerceIn(30, 900)
        val latch = CountDownLatch(1)
        val sb = StringBuilder()

        try {
            com.zhixueyao.ui.UiKit.ui {
                try {
                    val manager = CompilerManager.getInstance(project)
                    // 说明：AS 2026 的 CompilerManager 未提供 createCompileScope，
                    // 且 compile(VirtualFile[], ...) 不接受 null。改用 make(callback)
                    // 做全项目增量编译 —— 语义上正是「验证整个项目能否编译通过」。
                    manager.make(
                        com.intellij.openapi.compiler.CompileStatusNotification { aborted, errors, warnings, ctx ->
                            if (aborted) {
                                sb.append("构建被中止。\n")
                            }
                            sb.append("构建结束：错误 $errors 个，警告 $warnings 个\n")
                            if (errors == 0 && !aborted) {
                                sb.append("编译通过。")
                            } else if (errors > 0) {
                                sb.append("\n错误明细：\n")
                                val messages = ctx.getMessages(CompilerMessageCategory.ERROR) ?: emptyArray()
                                for (msg in messages.take(60)) {
                                    val content = msg.message?.replace('\n', ' ')?.take(220) ?: ""
                                    val loc = msg.virtualFile?.name ?: ""
                                    sb.append("  [").append(loc).append("] ").append(content).append('\n')
                                }
                            }
                            latch.countDown()
                        }
                    )
                } catch (e: Exception) {
                    sb.append("触发构建失败：${e.message}")
                    latch.countDown()
                }
            }

            val finished = latch.await(timeout.toLong(), TimeUnit.SECONDS)
            return if (!finished) {
                ToolResult.error("构建在 $timeout 秒内未完成，可能仍在进行。可稍后用 get_diagnostics 查看结果。")
            } else {
                ToolResult(sb.toString())
            }
        } catch (e: Exception) {
            return ToolResult.error("构建执行异常：${e.message}")
        }
    }
}

/** 输出项目整体结构，帮助模型快速建立对代码库的认知。 */
class ProjectStructureTool : AgentTool {

    override val name = "project_structure"
    override val description =
        "查看项目整体结构：模块划分、源码根目录、构建脚本内容。首次接触项目时建议先调用。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object",
        "properties" to jsonObj()
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        return try {
            val sb = StringBuilder()
            sb.append("项目：${project.name}\n")

            ReadAction.run<RuntimeException> {
                val rootManager = ProjectRootManager.getInstance(project)
                sb.append("内容根：${rootManager.contentRoots.size} 个\n")

                val modules = ModuleManager.getInstance(project).modules
                sb.append("\n模块（${modules.size} 个）：\n")
                for (module in modules) {
                    sb.append("  • ${module.name}")
                    val srcRoots = ModuleRootManager.getInstance(module).sourceRoots
                    if (srcRoots.isNotEmpty()) {
                        sb.append("   源码根：").append(
                            srcRoots.joinToString(", ") { it.path.substringAfterLast('/') }
                        )
                    }
                    sb.append('\n')
                }

                // SDK 信息对 Android 项目尤其重要，模型据此判断可用 API 范围
                rootManager.projectSdk?.let { sb.append("\n项目 SDK：${it.name}\n") }

                sb.append("\n顶层目录：\n")
                val base = PathGuard.projectBaseDir(project)
                if (base != null) {
                    base.children
                        .filter { it.name !in SearchCodeTool.IGNORE_DIRS }
                        .sortedBy { it.name }
                        .take(40)
                        .forEach { child ->
                            sb.append("  ").append(if (child.isDirectory) "[目录] " else "[文件] ")
                            sb.append(child.name).append('\n')
                        }
                }
            }

            // 附带构建脚本，让模型了解依赖与配置
            val base = PathGuard.projectBase(project)
            if (base != null) {
                for (script in listOf(
                    "settings.gradle.kts", "settings.gradle",
                    "build.gradle.kts", "build.gradle",
                    "app/build.gradle.kts", "app/build.gradle",
                    "gradle/libs.versions.toml"
                )) {
                    val p = base.resolve(script)
                    if (!Files.exists(p)) continue
                    val text = runCatching { Files.readString(p) }.getOrNull() ?: continue
                    sb.append("\n──── $script ────\n")
                    sb.append(text.take(2200))
                    if (text.length > 2200) sb.append("\n...（已截断，共 ${text.length} 字符）")
                    sb.append('\n')
                }
            }

            ToolResult(sb.toString())
        } catch (e: Exception) {
            ToolResult.error("读取项目结构失败：${e.message}")
        }
    }
}
