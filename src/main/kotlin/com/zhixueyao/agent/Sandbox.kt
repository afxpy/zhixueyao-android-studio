package com.zhixueyao.agent

import com.intellij.openapi.project.Project
import com.zhixueyao.settings.ZhixueyaoSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 沙盒 —— 决定 AI 能碰哪些路径。
 *
 * 三种模式（思路对齐 Codex 的权限档位）：
 *
 * | 模式 | 行为 | 适用 |
 * |---|---|---|
 * | [Mode.PROJECT] | 项目目录之外一律拦截 | 默认。最安全，AI 只在自己工程里活动 |
 * | [Mode.FULL] | 不限制盘符（C:/D:/E:…） | 用户明确授权后使用，比如让 AI 去读别处的日志 |
 * | [Mode.ASK] | 越界时弹选项让用户当场决定 | 「允许一次 / 始终允许 / 拒绝」三选一 |
 *
 * 为什么默认是「仅当前项目」：模型有时会「顺手」去读用户主目录下的配置、
 * 或者把文件写到桌面 —— 那些路径对当前任务毫无必要，却可能带出隐私或误改文件。
 * 默认收紧、需要时显式放开，比事后追责靠谱。
 */
object Sandbox {

    enum class Mode(val id: String, val label: String, val shortLabel: String, val description: String) {
        PROJECT(
            "project", "仅当前项目", "项目",
            "只能读写当前项目目录内的文件；越界会被拦下并提示你切换权限"
        ),
        FULL(
            "full", "全盘沙盒", "全盘",
            "不限路径，任意盘符（C:/D:/E:/…）都能读写。只在信任任务时使用"
        ),
        ASK(
            "ask", "每次询问", "询问",
            "越界时在输入区弹出「允许一次 / 始终允许 / 拒绝」，由你当场决定"
        );

        companion object {
            fun byId(id: String): Mode = entries.firstOrNull { it.id == id } ?: PROJECT
        }
    }

    fun mode(): Mode = Mode.byId(ZhixueyaoSettings.getInstance().sandboxMode)

    fun setMode(mode: Mode) {
        ZhixueyaoSettings.getInstance().sandboxMode = mode.id
    }

    /** 项目根目录（规范化后的绝对路径） */
    fun projectBase(project: Project): Path? =
        project.basePath?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }

    fun isInsideProject(project: Project, path: Path): Boolean {
        val base = projectBase(project) ?: return false
        return runCatching { path.toAbsolutePath().normalize().startsWith(base) }.getOrDefault(false)
    }

    /**
     * 检查一次访问是否放行。
     *
     * @param action 动作名（读文件 / 写入文件 / 搜索…），用于提示文案
     * @param rawPath 用户/模型给的原始路径，用于提示文案
     * @param resolved 已解析的绝对路径
     * @return null 表示放行；非 null 是给模型看的拒绝原因
     *         —— 返回文本而不是抛异常，是为了让模型知道「为什么不行、该怎么办」，
     *         而不是整轮对话被一个异常打断
     */
    fun check(project: Project, action: String, rawPath: String, resolved: Path): String? {
        if (isInsideProject(project, resolved)) return null
        return when (mode()) {
            Mode.FULL -> null

            Mode.PROJECT -> buildString {
                append("【沙盒拦截】$action 超出了当前项目范围：$rawPath\n")
                append("当前权限是「仅当前项目」。如果确实需要访问项目外的路径，")
                append("请在输入区的「沙盒」里切成「全盘沙盒」（不再拦截）或「每次询问」（逐次授权）。")
            }

            Mode.ASK -> when (AgentUiBridge.requestApproval(project, ApprovalRequest(action, rawPath, "该路径不在当前项目内"))) {
                ApprovalDecision.ALLOW_ONCE -> null

                ApprovalDecision.ALLOW_ALWAYS -> {
                    // 「始终允许」= 以后不再问，直接切成全盘沙盒（并持久化）
                    setMode(Mode.FULL)
                    null
                }

                ApprovalDecision.DENY -> "【已拒绝】用户拒绝了这次 $action：$rawPath"
            }
        }
    }
}

/** 一次权限请求 */
data class ApprovalRequest(val action: String, val target: String, val reason: String)

/** 用户的决定 */
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

/** 沙盒拒绝。单独一个类型，便于上层把它转成干净的提示而不是堆栈 */
class SandboxDeniedException(message: String) : RuntimeException(message)
