package com.zhixueyao.llm

import com.zhixueyao.util.Json

/**
 * 把服务商返回的原始错误翻译成「用户照着就能改」的中文提示。
 *
 * 为什么单独抽一个模块：同一类错误在 OpenAI / Anthropic / 各家聚合站里的措辞完全不同，
 * 有的给 `error.message`，有的只给 `detail`，有的甚至只给一段纯文本。散落在各个 Provider
 * 里逐处判断会迅速失控 —— 集中一处才能把规则补全，也才方便以后继续加规则。
 *
 * 两条底线：
 * 1. 认不出来的错误一律**原样透传**，绝不吞掉服务端信息。用户至少得看到原始报错才能去搜。
 * 2. 给出的建议必须是**可执行的下一步动作**，而不是「请检查配置」这种正确的废话。
 */
object LlmErrorAdvice {

    /** 一条翻译结果：结论 + 可执行建议 + 服务端原文。 */
    data class Advice(val title: String, val action: String?, val raw: String) {

        /** 渲染成多行文本。界面层负责把 `\n` 转成换行。 */
        fun render(): String = buildString {
            append(title)
            if (!action.isNullOrBlank()) {
                append('\n')
                append("建议：")
                append(action)
            }
            val clean = raw.trim()
            if (clean.isNotEmpty()) {
                append('\n')
                append("原始信息：")
                append(clean.lines().first().take(300))
            }
        }
    }

    /**
     * 从 HTTP 响应体里抠出给人看的错误文案。
     *
     * 各家字段名不统一：OpenAI 系是 `{"error":{"message":...}}`，
     * 有的中转站是 `{"message":...}` 或 `{"detail":...}`，
     * 极端的直接返回纯文本。按覆盖面从宽到窄依次尝试。
     */
    fun extractServerMessage(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        val parsed = runCatching { Json.parse(text) }.getOrNull()
        if (parsed != null) {
            val candidates = listOf(
                parsed.obj("error")?.str("message"),
                parsed.obj("error")?.str("detail"),
                parsed.str("error"),
                parsed.str("message"),
                parsed.str("detail"),
                parsed.str("msg")
            )
            candidates.firstOrNull { !it.isNullOrBlank() }?.let { return it }
        }
        // 不是 JSON 或结构不认识：原文照抄，但截断，避免把整页 HTML 塞进界面
        return text.take(600)
    }

    /**
     * 生成建议。不认识的错误返回带原文的兜底结论。
     *
     * @param raw 服务端原始文案（已由 [extractServerMessage] 处置过）
     * @param model 当前请求用的模型名，用于把「模型不支持」的建议说具体
     * @param status HTTP 状态码；SSE 流内错误事件没有状态码，传 null
     */
    fun explain(raw: String, model: String = "", status: Int? = null): Advice {
        val s = raw.lowercase()
        fun has(vararg keys: String) = keys.any { s.contains(it) }

        // ---- 1. 余额 / 额度 ----
        // 放在最前面：这条最常见，而且用户最容易误判成「插件坏了」。
        // 中转站的额度文案五花八门（余额不足 / 欠费 / 套餐到期），关键词要放宽。
        if (has(
                "insufficient", "balance", "quota", "credit", "arrears", "billing",
                "not enough", "out of credit", "insufficient_quota", "exceeded your current quota",
                "欠费", "余额", "额度", "套餐"
            )
        ) {
            return Advice(
                title = "【余额不足】服务商账户额度已用尽".with(status),
                action = "到服务商控制台充值或续费；若用的是聚合站/中转站，先确认套餐是否到期。" +
                    "也可以先换一家有额度的服务商，或把模型换成更便宜的档位再试。",
                raw = raw
            )
        }

        // ---- 2. 密钥 / 鉴权 ----
        if (status == 401 || status == 403 || has(
                "api key", "apikey", "unauthorized", "authentication", "invalid token",
                "no permission", "permission denied", "forbidden", "密钥", "鉴权", "认证", "无权限"
            )
        ) {
            return Advice(
                title = "【密钥无效】服务商拒绝了这次鉴权".with(status),
                action = "到「设置 → 工具 → 止血药 · 模型」重新粘贴 API 密钥（注意各家的密钥是分开记忆的，" +
                    "切换服务商后要各填一次）；确认密钥没有多余空格、也未被控制台禁用或删除。",
                raw = raw
            )
        }

        // ---- 3. 模型名不被接受 ----
        // 必须同时命中「模型」相关词才算，避免把「文件不存在」误解成模型问题。
        if ((has("model", "模型")) && has(
                "not found", "not exist", "does not exist", "invalid", "unknown",
                "unsupported", "no such", "不存在", "不支持", "无效"
            )
        ) {
            return Advice(
                title = "【模型名不支持】服务商没有「$model」这个模型".with(status),
                action = "点「拉取模型」按服务商真实返回的清单核对名称（大小写和连字符都要一致，" +
                    "例如 DeepSeek 只有 deepseek-chat / deepseek-reasoner）；" +
                    "确认「协议格式」与该服务商匹配；聚合站的模型名通常带前缀，如 deepseek-ai/DeepSeek-V3。",
                raw = raw
            )
        }

        // ---- 4. 限流 ----
        if (status == 429 || has("rate limit", "too many requests", "rpm", "tpm", "频率", "限流", "过于频繁")) {
            return Advice(
                title = "【请求过于频繁】触发服务商限流".with(status),
                action = "等十几秒再发一次；若持续触发，说明当前档位 QPS/并发不够，需要升级套餐，" +
                    "或降低「工具调用上限」减少单轮请求次数。",
                raw = raw
            )
        }

        // ---- 5. 上下文超长 ----
        if (has(
                "context length", "maximum context", "too long", "reduce the length",
                "token limit", "exceeds the maximum", "上下文", "超长", "过长"
            )
        ) {
            return Advice(
                title = "【上下文过长】请求内容超出模型的窗口上限".with(status),
                action = "在工具栏新建一个会话（历史越长越容易触发），或把「单次最大输出」调小；" +
                    "如果刚读取过大文件，让它改用 search_code 精确定位而不是整份读取。",
                raw = raw
            )
        }

        // ---- 6. 地址写错 ----
        if (status == 404 || has("no such host", "not found", "cannot post", "unknown url")) {
            return Advice(
                title = "【接口地址不对】服务商找不到该端点".with(status),
                action = "检查「接口地址」是否少了或多了 /v1（OpenAI 兼容端点通常以 /v1 结尾，" +
                    "例：https://api.deepseek.com/v1）；若已是自定义地址，确认没有拼错路径。",
                raw = raw
            )
        }

        // ---- 7. 服务端自身异常 ----
        if (status != null && status in 500..599) {
            return Advice(
                title = "【服务商异常】对方服务器出错".with(status),
                action = "这不是本地配置问题，等几分钟重试；若长期如此，去服务商状态页确认是否在维护。",
                raw = raw
            )
        }

        // ---- 兜底：不猜，原样给出 ----
        return Advice(
            title = "【请求失败】服务商拒绝了这次调用".with(status),
            action = "把下面的原始信息连同「接口地址 + 模型名」一起核对一遍；" +
                "也可以用设置页的「测试连接」快速验证当前配置是否可用。",
            raw = raw
        )
    }

    /**
     * 流式响应结束却一个字都没拿到。
     *
     * 这类情况服务端往往**什么都不说**（空 choices、空 delta），是最难排查的一种，
     * 所以这里按「从最可能到最不可能」给出完整排查顺序，让用户一次走完。
     */
    fun renderEmptyStream(model: String, baseUrl: String, finishReason: String? = null): String = when (finishReason) {
        "length", "max_tokens" -> Advice(
            title = "【输出被截断】达到单次最大输出上限",
            action = "把「单次最大输出」调大（例如 8192），或让模型分几次回答。",
            raw = ""
        ).render()

        "content_filter", "refusal" -> Advice(
            title = "【内容被拦截】服务商的安全策略拒绝了这次回答",
            action = "换一种说法重发；若涉及代码，把需求拆得更具体一些再试。",
            raw = ""
        ).render()

        else -> Advice(
            title = "【空响应】服务商本次调用没有返回任何内容（模型：$model）",
            action = "按顺序排查 —— ① 用设置页「测试连接」验证密钥与模型名；" +
                "② 到服务商控制台确认余额/额度；③ 若用的是聚合站，换官方直连地址再试一次；" +
                "④ 核对「协议格式」与服务商一致（缺 /v1、或该用 Anthropic 却选了 OpenAI 兼容）；" +
                "⑤ 当前地址：$baseUrl",
            raw = ""
        ).render()
    }

    /**
     * 网络层异常 → 可执行建议。
     *
     * 连接类错误和「服务商拒绝」是两回事：前者根本没能把请求发出去，
     * 说「请检查密钥」是误导，得按具体异常区分「域名解析不了」「端口没人听」「被墙/被代理拦」。
     */
    fun renderNetworkError(e: Throwable, baseUrl: String): String {
        val name = e.javaClass.simpleName
        val msg = e.message ?: name
        return when (name) {
            "UnknownHostException" -> Advice(
                "【域名解析失败】连不上 $baseUrl",
                "检查接口地址是否拼错；若用了公司网络/VPN，确认 DNS 能解析该域名；" +
                    "国内直连不了 api.openai.com / api.anthropic.com 属正常现象，需自备网络环境。",
                msg
            ).render()

            "ConnectException", "NoRouteToHostException" -> Advice(
                "【连接被拒绝】$baseUrl 没有响应",
                "确认服务商地址与端口正确；若是本地模型（Ollama 等），先确认它已经启动并监听该端口。",
                msg
            ).render()

            "HttpTimeoutException", "TimeoutException", "SocketTimeoutException" -> Advice(
                "【请求超时】服务商迟迟没有回应",
                "稍后重试；若每次都超时，多为网络链路问题，检查代理设置或换直连地址。",
                msg
            ).render()

            "SSLHandshakeException", "SSLException" -> Advice(
                "【HTTPS 证书校验失败】",
                "常见于代理/抓包工具改写了证书；关闭此类工具，或让 IDE 信任其根证书后重试。",
                msg
            ).render()

            else -> Advice(
                "【请求失败】$name",
                "先确认网络可达，再用设置页的「测试连接」验证当前配置；地址：$baseUrl",
                msg
            ).render()
        }
    }

    /**
     * 附上 HTTP 状态码，但要跳过 2xx。
     *
     * 原因是流内错误（HTTP 200 的 SSE 里带的 error 事件）也会走到这里，
     * 显示「（HTTP 200）」会让用户彻底懵 —— 200 恰恰说明 HTTP 层是成功的。
     */
    private fun String.with(status: Int?): String =
        if (status == null || status in 200..299) this else "$this（HTTP $status）"
}