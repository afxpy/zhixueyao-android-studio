package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.agent.MemoryStore
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson

/**
 * **记忆工具** —— 让 AI 记住用户和这个项目的事实。
 *
 * 对应参考项目 astravia 的 memory-mode（`MEMORY.md` + `memory 工具`）。
 *
 * 与技能的分工要说清楚，否则两边都会写乱：
 *
 * | | 记什么 | 形态 |
 * |---|---|---|
 * | **记忆** | 事实：「这个项目用 compose 不用 xml」「用户不喜欢 emoji」 | 一句话 |
 * | **技能** | 方法：「改 UI 前要对照哪些坑」 | 成体系的步骤 |
 *
 * 判断标准：**「这是一句我能直接照做的话，还是一套要读一遍的流程？」**
 */
class MemoryTool : AgentTool {

    override val name = "memory"

    override val description =
        "跨会话记住事实。**听到「以后都这样」「下次别」「记住」这类话就现在记下来**，" +
            "否则下次开新会话又要用户重说一遍 —— 那是用户对 AI 最直接的失望来源。\n" +
            "记的是**一句话能说清的事实**（项目用哪个技术栈、用户的偏好、某条特殊约定）；" +
            "成体系的方法论请用 install_skill 写成技能。\n" +
            "scope=project（默认）随工程走、能提交进仓库；scope=global 跨项目（用户个人偏好用这个）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "action" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("remember".toJson(), "read".toJson(), "forget".toJson()),
                "description" to "remember=记一条（默认）；read=读全部；forget=按关键词删掉"
            ),
            "text" to jsonObj(
                "type" to "string".toJson(),
                "description" to "remember 用：要记住的事实。**一句话**，别写成段落（超过 300 字会被拒）"
            ),
            "keyword" to jsonObj(
                "type" to "string".toJson(),
                "description" to "forget 用：删掉包含这个词的所有记忆"
            ),
            "scope" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("project".toJson(), "global".toJson()),
                "description" to "project=这个工程的事（默认）；global=用户的个人偏好（跨项目）"
            )
        ),
        "required" to jsonArr("action".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val action = args.str("action")?.trim()?.lowercase() ?: "remember"
        val useGlobal = args.str("scope")?.equals("global", ignoreCase = true) == true

        val file = if (useGlobal) {
            MemoryStore.globalFile()
        } else {
            MemoryStore.projectFile(project)
                // 没有项目时退回全局，而不是失败 —— 否则「记住我的偏好」
                // 在没打开工程的时候做不了
                ?: MemoryStore.globalFile()
        }

        return when (action) {
            "read" -> {
                val entries = MemoryStore.all(project)
                if (entries.isEmpty()) {
                    ToolResult("现在还没有任何记忆。听到值得记住的事就用 action=remember 记下来。")
                } else {
                    ToolResult(
                        buildString {
                            append("共 ").append(entries.size).append(" 条记忆：\n\n")
                            MemoryStore.read(MemoryStore.projectFile(project) ?: java.io.File("/nonexistent"))
                                .forEach { append("- [项目] ").append(it.text).append('\n') }
                            MemoryStore.read(MemoryStore.globalFile())
                                .forEach { append("- [全局] ").append(it.text).append('\n') }
                        }
                    )
                }
            }

            "forget" -> {
                val kw = args.str("keyword")?.trim().orEmpty()
                if (kw.isEmpty()) return ToolResult.error("forget 需要 keyword 参数")
                val n = MemoryStore.forget(file, kw)
                if (n == 0) {
                    ToolResult("没有找到包含「$kw」的记忆。可以先 action=read 看看现在有哪些。")
                } else {
                    ToolResult("已删掉 $n 条包含「$kw」的记忆。")
                }
            }

            else -> {
                val text = args.str("text")?.trim().orEmpty()
                if (text.isEmpty()) {
                    return ToolResult.error(
                        "remember 需要 text 参数。要记的是一句能直接照做的事实，" +
                            "比如「这个项目用 Compose 不用 XML 布局」。"
                    )
                }
                when (val err = MemoryStore.remember(file, text)) {
                    null -> ToolResult(
                        "已记住（${if (useGlobal) "全局" else "项目"}）：$text\n" +
                            "下一条消息起它就在系统提示词的「记忆」一节里，不用用户再重复。"
                    )
                    "DUPLICATE" -> ToolResult("这条已经记过了，不用重复记。")
                    else -> ToolResult.error(err)
                }
            }
        }
    }
}