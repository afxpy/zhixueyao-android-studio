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
                // 缺口提示：技能自己标了「哪里还没写」。
                //
                // 借自参考项目 agents-universe 的 gaps 标注 —— 技能是**攒出来的**，
                // 第一版总有盲区。把盲区显式带进上下文，
                // 模型这轮做完了就顺手能补，不用等下次重新踩一遍。
                // 复合技能：这是个流程，按顺序去加载几步。
                //
                // 刻意**不自动展开** —— agents-universe 是自动展开的
                // （`steps` 里的技能会一起加载），但那样一次能把好几个技能的正文
                // 全塞进上下文。这里只给指路：需要哪步自己去取，
                // 用多少上下文由模型按任务判断。
                if (skill.steps.isNotEmpty()) {
                    append("\n\n## 这是一个流程，按顺序加载\n\n")
                    skill.steps.forEachIndexed { i, st ->
                        append(i + 1).append(". `").append(st).append("`\n")
                    }
                    append("\n每一步做完再加载下一步，不要一次全取回来。\n")
                }

                // 交叉引用 + **死链检测**
                if (skill.crossLinks.isNotEmpty()) {
                    val known = Skills.all(project).map { it.name }.toSet()
                    val dead = skill.crossLinks.filter { it !in known && it != skill.name }
                    append("\n\n## 相关技能\n\n")
                    skill.crossLinks.forEach { link ->
                        append("- `").append(link).append("`")
                        if (link in dead) append("　⚠️ **这个技能不存在**（名字写错或还没写）")
                        append("\n")
                    }
                    if (dead.isNotEmpty()) {
                        append("\n上面标了「不存在」的，如果你正好知道该写什么，")
                        append("做完顺手用 `install_skill` 把它建起来（或把引用改对）。\n")
                    }
                }

                if (skill.gaps.isNotEmpty()) {
                    append("\n\n## 这个技能还没写全的地方\n\n")
                    skill.gaps.forEach { append("- ").append(it).append("\n") }
                    append("\n**这一轮如果正好涉及上面某条，做完就把结论补进这个技能**")
                    append("（用 `install_skill` + 同名 + `overwrite=true` 更新），")
                    append("并把补好的那条从 gaps 注释里去掉。\n")
                }
            }
        )
    }
}
