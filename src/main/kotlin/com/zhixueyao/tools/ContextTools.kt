package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 取当前时间。
 *
 * ## 为什么必须有这个工具
 *
 * 加它之前，**我们既没有把日期写进提示词、也没有任何地方能让模型知道今天几号**。
 * 后果是很具体的：
 *
 * - `git log` 里看到 `2026-09-25`，模型没法判断那是「三天前」还是「三个月前」
 * - 「最近一周改了哪些文件」这类请求，模型只能挑个数字去猜
 * - 用户说「下周三之前搞定」，模型算不出那是哪天
 *
 * 这类错误不报错、不崩，只是**答得不对** —— 最难发现的那种。
 *
 * ## 为什么不只靠提示词注入日期
 *
 * 提示词里也注入了当天日期（见 [com.zhixueyao.agent.AgentRunner.todayLine]），
 * 两边都要，因为覆盖的场景不同：
 *
 * | | 提示词注入 | 本工具 |
 * |---|---|---|
 * | 成本 | 零（每轮重发时就在） | 一次往返 |
 * | 新鲜度 | **本轮请求开始时** | 调用那一刻 |
 * | 精度 | 只到日 | 到秒 |
 *
 * 实际写这段注释时查了一下：提示词是**每轮用户消息都会重建**的
 * （`ChatPanel.ensureSystemPrompt`），所以它不是「会话开始时那份」那么旧。
 * 那工具的价值在哪？在三件注入做不到的事：
 *
 * 1. **一轮能跑很久**：一次任务跑十几分钟很正常，跨零点就换天了
 * 2. **要精确到秒/分**：注入的只有日期
 * 3. **要 epoch 秒**：和 `git log --format=%ct` 对齐时必须用它
 */
class CurrentTimeTool : AgentTool {

    override val name = "current_time"

    override val parameters: Json.Obj = jsonObj("type" to "object".toJson(), "properties" to jsonObj())

    override val description: String
        get() = "取当前日期与时间（含星期、时区、epoch 秒）。" +
            "需要判断「几天前/几天后」、换算相对日期、与 git 时间戳对齐、" +
            "或给结论标注时间时用它。" +
            "系统提示词里写了日期，但只到「日」而且是一轮开始时取的 —— " +
            "要精确到分钟、或这一轮已经跑了很久，就用本工具。"

    override fun execute(project: Project, args: Json.Obj): ToolResult =
        ToolResult(render(ZonedDateTime.now()))

    /**
     * 把「给定时刻」渲染成给模型看的文本。
     *
     * **时间从外面传进来，不在内部取 `now()`** —— 理由和
     * [com.zhixueyao.agent.AgentRunner] 里那些「不自己去找全局状态」的改动一样：
     * 内部取当前时间的话，这个函数的输出**每次都不同**，探针就没法断言具体内容，
     * 只能断言「非空」那种废话。传进来之后，探针可以用固定时刻跑，
     * 于是「跨月怎么算」「跨年怎么算」这些真正会错的地方才验得了。
     */
    fun render(now: ZonedDateTime): String {
        val sb = StringBuilder()

        // 人读的一行：模型最常引用的就是这一行
        sb.append("现在是 ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append("，星期").append(weekdayCn(now.dayOfWeek.value)).append("。\n")

        // 机器读的一行：方便和 git 的时间戳直接对齐。
        // `git log --format=%ct` 给的是 **epoch 秒**而不是毫秒 —— 两个都给，
        // 免得模型自己乘 1000（乘错一次就要重跑一遍命令，这种换算错得起）。
        sb.append("\n")
            .append("ISO 8601：").append(now.toOffsetDateTime()).append('\n')
            .append("时区：").append(now.zone.id).append("（UTC").append(offsetText(now)).append("）\n")
            .append("epoch 毫秒：").append(now.toInstant().toEpochMilli()).append('\n')
            .append("epoch 秒：").append(now.toInstant().epochSecond)
            .append("　← 与 `git log --format=%ct` 可直接比\n")

        // 相对日期速查。看着啰嗦，但省掉的是模型**最容易算错**的那类算术：
        // 跨月、跨年的「N 天前」是高频出错点，给一张表比让它心算可靠得多。
        val d = now.toLocalDate()
        sb.append("\n相对日期速查（按自然日加减，已处理跨月跨年）：\n")
            .append("  昨天 = ").append(d.minusDays(1)).append('\n')
            .append("  三天前 = ").append(d.minusDays(3)).append('\n')
            .append("  一周前 = ").append(d.minusDays(7)).append('\n')
            .append("  一个月前 = ").append(d.minusMonths(1)).append('\n')
            .append("  明天 = ").append(d.plusDays(1)).append('\n')
            .append("  一周后 = ").append(d.plusDays(7)).append('\n')
            .append("  本月初 = ").append(d.withDayOfMonth(1)).append('\n')
            .append("  本月末 = ").append(d.withDayOfMonth(d.lengthOfMonth()))

        // 单独提一句：「上周五」这种说法在中文里歧义很大 ——
        // 是「上一个自然周的周五」，还是「最近的那个周五」（可能就在昨天）？
        // 模型按哪种理解都可能出错，但**说清按哪种算了**比默默猜一个强。
        sb.append("\n\n说明：「N 天前/后」均按自然日加减。")
            .append("若要表达「上周五」这类相对说法，先与用户确认指的是 ")
            .append(lastWeekday(d, DayOfWeek.FRIDAY))
            .append("（最近的那个周五）还是上一个自然周内的周五 —— 中文里两种理解都常见。")

        return sb.toString()
    }

    private fun weekdayCn(v: Int): String =
        listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(v - 1) { "?" }

    /** `+08:00` 这种偏移文本 */
    private fun offsetText(z: ZonedDateTime): String {
        val sec = z.offset.totalSeconds
        val sign = if (sec < 0) "-" else "+"
        val abs = abs(sec)
        return "%s%02d:%02d".format(sign, abs / 3600, (abs % 3600) / 60)
    }

    /** 最近的那个指定星期几（含今天） */
    internal fun lastWeekday(from: LocalDate, target: DayOfWeek): LocalDate {
        var d = from
        while (d.dayOfWeek != target) d = d.minusDays(1)
        return d
    }
}