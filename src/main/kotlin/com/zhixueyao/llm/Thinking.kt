package com.zhixueyao.llm

import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson

/**
 * 思考（推理）强度。
 *
 * 这里定义的是**中立档位**，由各 Provider 翻译成自家的参数 —— 因为各家叫法和取值完全不同：
 *
 *  - OpenAI / 通义 3.8 系：`reasoning_effort`，但取值集合不同（OpenAI 有 none/minimal，通义最高只到 xhigh）
 *  - 通义 Qwen3 系：`enable_thinking` + `thinking_budget`（按 token 数控深度）
 *  - DeepSeek / 智谱 GLM / Kimi / MiniMax：`thinking: {type: enabled|disabled}`，**只有开关没有档位**
 *  - Anthropic：`thinking: {type: enabled, budget_tokens: N}`
 *
 * 所以同一档「高」在不同家会落成不同东西，[budget] 就是给预算型接口用的映射值。
 */
enum class ThinkingLevel(val label: String, val shortLabel: String, val description: String, val budget: Int) {

    /** 不发任何思考参数，完全交给服务商与模型自己决定。不选档位时就是它 */
    DEFAULT("跟随模型默认", "默认", "不干预，由服务商决定是否思考", 0),

    LOW("低", "低", "轻量思考，适合简单问答与查改", 2048),

    MEDIUM("中", "中", "标准思考，适合日常编码与排错", 8192),

    HIGH("高", "高", "深入思考，适合方案设计与复杂调试", 16384),

    XHIGH("超高", "超高", "很深的思考，适合跨模块重构与疑难排查", 32768),

    EXTREME("极致", "极致", "最大强度，慢但最可靠，适合难题攻关", 65536);

    /** 开关型接口（只有 enabled/disabled 的厂商）下，这一档算不算「开」 */
    val enabled: Boolean get() = this != DEFAULT

    /**
     * 发给 OpenAI 系 `reasoning_effort` 的值。
     *
     * 「超高」和「极致」都发 `xhigh`：`reasoning_effort` 的合法取值里没有比 xhigh
     * 更高的档，硬塞一个自造值服务商直接 400。两者的区别体现在**预算式**接口上
     * （通义的 thinking_budget、Anthropic 的 budget_tokens）—— 那里它们是 32K / 64K。
     */
    val effortValue: String?
        get() = when (this) {
            DEFAULT -> null
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            XHIGH, EXTREME -> "xhigh"
        }

    companion object {
        fun byId(id: String): ThinkingLevel = entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

/**
 * 思考参数的**方言**。
 *
 * 为什么需要它：`reasoning_effort` 并不是所有家都认。往智谱发 `reasoning_effort`
 * 或往 OpenAI 发 `enable_thinking` 都会 400。所以得按服务商选对参数名。
 *
 * 自动判断靠不住（聚合站后面的模型五花八门），所以这件事由服务商预设显式声明，
 * 自定义服务商则让用户自己选 —— 比猜准。
 */
enum class ThinkingStyle(val label: String, val hint: String) {

    /** 不发思考参数 */
    NONE("不发送思考参数", "服务商或模型不支持思考时选它，最安全"),

    /** OpenAI 标准：`reasoning_effort: "low" | "medium" | ...` */
    EFFORT("OpenAI 标准（reasoning_effort）", "OpenAI、通义 3.8 系、以及多数兼容实现"),

    /** 通义 Qwen 系：`enable_thinking` + `thinking_budget` */
    QWEN("通义 Qwen（enable_thinking + thinking_budget）", "按 token 预算控制思考深度"),

    /** 开关式：`thinking: {type: "enabled" | "disabled"}` —— 只有开关，没有档位 */
    TOGGLE("开关式（thinking.type）", "DeepSeek、智谱 GLM、Kimi 等，只支持开/关");

    companion object {
        fun byId(id: String): ThinkingStyle = entries.firstOrNull { it.name == id } ?: NONE
    }
}

/**
 * 把中立的思考档位翻译成具体厂商的请求参数。
 *
 * 独立成对象而不是塞进各 Provider，是为了能**单独实测** ——
 * 这段逻辑只要有一个字段名写错，用户就会收到 400，而错误信息通常很含糊
 * （"invalid request" 之类），排查成本高。所以它值得被单独验证。
 *
 * 每条规则的依据都是各家官方文档，不是猜的：
 *
 * | 方言 | 参数 | 取值 |
 * |---|---|---|
 * | EFFORT | `reasoning_effort` | none/minimal/low/medium/high/xhigh |
 * | QWEN | `enable_thinking` + `thinking_budget` | true/false + token 数 |
 * | TOGGLE | `thinking.type` | "enabled"/"disabled" |
 */
object ThinkingParams {

    /**
     * 把思考配置写进请求体。
     *
     * [ThinkingStyle.NONE] 或 [ThinkingLevel.DEFAULT] 时**什么都不写** ——
     * 这很重要：给不支持的服务商硬塞参数会导致请求失败，
     * 而「跟随模型默认」正是最安全的默认行为。
     */
    fun apply(root: Json.Obj, config: LlmConfig) {
        val level = config.thinking
        if (level == ThinkingLevel.DEFAULT) return

        when (config.thinkingStyle) {
            ThinkingStyle.NONE -> return

            ThinkingStyle.EFFORT -> {
                // 关闭思考时 OpenAI 用 "none"；某些实现在关思考时反而不认这个字段，
                // 但由于用户显式选了「关闭」，按文档发是对的
                level.effortValue?.let { root["reasoning_effort"] = it.toJson() }
            }

            ThinkingStyle.QWEN -> {
                root["enable_thinking"] = level.enabled.toJson()
                // 预算只在开启时给。注意：通义要求 thinking_budget 与 reasoning_effort
                // 不能同时出现，所以这里刻意不发 reasoning_effort
                if (level.enabled && level.budget > 0) {
                    root["thinking_budget"] = level.budget.toJson()
                }
            }

            ThinkingStyle.TOGGLE -> {
                root["thinking"] = jsonObj(
                    "type" to (if (level.enabled) "enabled" else "disabled").toJson()
                )
            }
        }
    }

    /**
     * Anthropic 的思考参数结构不同（`thinking.budget_tokens`，且必须是数字），
     * 单独处理。
     *
     * 约束：Anthropic 要求 `budget_tokens < max_tokens`，否则直接 400。
     * 所以这里要夹一下 —— 用户把 maxTokens 设成 2048 又选了「最高」档时，
     * 不夹就会必然失败。
     */
    fun applyAnthropic(root: Json.Obj, config: LlmConfig) {
        val level = config.thinking
        if (level == ThinkingLevel.DEFAULT || config.thinkingStyle == ThinkingStyle.NONE) return

        if (!level.enabled) {
            // Anthropic 没有「关闭」这个显式开关，不传就是默认行为
            return
        }

        val budget = level.budget.coerceAtLeast(1024)
        val maxTokens = config.maxTokens
        // 给最终回答留出空间：预算最多占 max_tokens 的一半，且至少留 1024
        val safeBudget = if (maxTokens > 2048) {
            minOf(budget, (maxTokens / 2)).coerceAtLeast(1024)
        } else {
            // max_tokens 太小，无法开启思考
            return
        }

        root["thinking"] = jsonObj(
            "type" to "enabled".toJson(),
            "budget_tokens" to safeBudget.toJson()
        )
    }
}

/**
 * 用户自定义的服务商（中转站 / 自建网关）。
 *
 * 为什么单独建模而不是复用预设：预设是「固定的一家」，而中转站可能有多个 ——
 * 不同中转站后面挂的模型、密钥、计费都不一样，必须能并存。
 * 所以每个自定义服务商有自己的 id，并在设置里按 [name] 区分。
 *
 * 字段一律 `var` 且**全部带默认值**，这不是随手写的，是平台序列化的硬性要求：
 *  - `val` 只生成 getter、没有 setter，平台的 `BeanBinding` 便认为该属性不可写，
 *    直接**跳过不序列化** —— 整个对象被写成 `<CustomProvider />` 空元素；
 *  - 下回启动读到空元素，`KotlinAwareBeanBinding` 用反射按参数名取值，
 *    `id` 这类**无默认值**的参数取不到就抛 `IllegalArgumentException`，
 *    结果是**整个 ZhixueyaoSettings 组件加载失败**、所有设置被打回默认值。
 * 两件事串联起来就是「保存后重开，自定义服务商整个消失」的完整故障链。
 * 对照 [com.zhixueyao.mcp.McpServerConfig]：它全用 `var` + 默认值，从未出过问题。
 */
data class CustomProvider(
    /** 内部 id，形如 `custom:1`，用于各项配置的键，用户看不到 */
    var id: String = "",
    /** 用户起的名称，例如「公司中转」「某宝买的额度」 */
    var name: String = "",
    var baseUrl: String = "",
    var apiFormat: String = "openai",
    var thinkingStyle: String = "NONE",
    /** 直接绑定的模型名，切换服务商时自动带入 */
    var model: String = ""
)
