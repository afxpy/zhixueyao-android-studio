package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.agent.Skills
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson

/**
 * 技能加载工具。
 *
 * 与系统提示词里的「可用技能」列表配合，构成渐进式加载：
 * 提示词只给名字 + 一句话说明，模型判断这次用得上哪个，再用本工具取回完整步骤。
 *
 * 好处有两层：
 *  - 省 token：几十个技能也不会把提示词撑爆；
 *  - 少干扰：模型不会把 A 技能的步骤套到 B 场景上。
 */
class SkillTool : AgentTool {

    override val name = "skill"

    override val description =
        "加载一个技能的完整内容。技能名来自系统提示词里「可用技能」那一节。" +
            "遇到对应场景时**先加载再动手**，不要凭印象自己编流程；" +
            "技能还可能带附属文件（参考文档、脚本），加载后按提示用 read_file 去读。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "name" to jsonObj(
                "type" to "string".toJson(),
                "description" to "技能名，取自「可用技能」列表"
            )
        ),
        "required" to jsonArr("name".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val name = args.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult.error("缺少 name 参数")

        val skill = Skills.byName(project, name) ?: run {
            val available = Skills.all(project).joinToString("、") { it.name }
            return ToolResult.error(
                if (available.isEmpty()) "当前没有任何技能。可以让用户把 SKILL.md 放进技能目录。"
                else "没有找到技能「$name」。当前可用：" + available
            )
        }

        return ToolResult(
            buildString {
                append("# 技能：").append(skill.name).append("\n\n")
                append(skill.body)
                val assets = skill.assets()
                if (assets.isNotEmpty()) {
                    append("\n\n## 附属文件\n\n")
                    append("这个技能还带了以下文件，需要时用 read_file 读（用下面的完整路径）：\n")
                    assets.forEach { append("- ").append(skill.dir.path).append("/").append(it).append("\n") }
                }
            }
        )
    }
}
