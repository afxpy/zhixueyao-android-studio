package com.zhixueyao.llm

import com.zhixueyao.util.HttpSupport
import com.zhixueyao.util.Json

/**
 * 从服务商接口拉取可用模型清单。
 *
 * 为什么要这么写：实测各家返回结构并不统一（见下表），如果只按 OpenAI 官方格式解析，
 * 换一家服务商就拉不到东西。所以这里做的是「多形状识别 + 逐项降级」，
 * 任何一种能认出模型 id 的结构都能吃下。
 *
 *   OpenAI / DeepSeek / Kimi / 通义 / 智谱 / 硅基流动
 *       { "data": [ { "id": "deepseek-chat", "owned_by": "deepseek" } ] }
 *   Anthropic
 *       { "data": [ { "id": "claude-sonnet-4-5", "display_name": "Claude Sonnet 4.5" } ] }
 *   OpenRouter
 *       { "data": [ { "id": "x/y", "name": "X: Y", "context_length": 1000000 } ] }
 *   Ollama 原生 /api/tags
 *       { "models": [ { "name": "qwen3:8b", "details": { "parameter_size": "8.0B" } } ] }
 *
 * 另外还要接受「根就是数组」「数组里直接是字符串」这类极简实现 —— 中转站经常这么返回。
 */
data class RemoteModel(
    val id: String,
    /** 服务商给的人类可读名，多数家不返回 */
    val label: String = "",
    /** 上下文窗口，0 表示服务商未提供 */
    val contextLength: Long = 0,
    /** 归属方 / 模型族，0 长度表示没有 */
    val owner: String = "",
    /** 参数量等补充信息（Ollama 的 details.parameter_size） */
    val sizeHint: String = ""
) {
    /** 界面上跟在模型名后面的那行灰字；没有可展示信息时返回空串 */
    fun metaText(): String = buildList {
        if (contextLength > 0) add("${formatContext(contextLength)} 上下文")
        if (owner.isNotBlank()) add(owner)
        if (sizeHint.isNotBlank()) add(sizeHint)
    }.joinToString("  ·  ")

    private fun formatContext(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1000 -> "${n / 1000}K"
        else -> n.toString()
    }
}

sealed class ModelListResult {
    /** [endpoint] 记录实际命中的地址，便于用户排查（有些服务商的地址要带 /v1，有些不要） */
    data class Ok(val models: List<RemoteModel>, val endpoint: String) : ModelListResult()
    data class Err(val message: String, val detail: String = "") : ModelListResult()
}

object ModelCatalog {

    /**
     * 拉取顺序按「最可能成功」排列，逐个尝试，全失败才报错。
     *
     * Ollama 单列一条：它的 OpenAI 兼容层在 /v1/models，但原生列表在去掉 /v1 的 /api/tags，
     * 用户填了 /v1 就只命中前者，早期版本没有 /v1/models 时就得靠后者兜底。
     */
    private fun endpoints(baseUrl: String, format: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return emptyList()
        val out = mutableListOf("$base/models")
        // 用户可能只填了 https://api.deepseek.com，没有 /v1
        if (!base.endsWith("/v1")) out.add("$base/v1/models")
        // Ollama 原生接口
        val noV1 = base.removeSuffix("/v1")
        if (noV1 != base) out.add("$noV1/api/tags")
        return out.distinct()
    }

    private fun headers(apiKey: String, format: String): Map<String, String> = buildMap {
        if (apiKey.isBlank()) return@buildMap
        if (format == "anthropic") {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
        } else {
            put("Authorization", "Bearer $apiKey")
        }
    }

    /**
     * 依次尝试各候选地址。任何一家成功即返回。
     *
     * 之所以不「失败就立刻报错」：/v1/models 在某些服务商上是 404，
     * 但去掉 /v1 的地址能通 —— 这属于正常情况，不该让用户看到错误。
     */
    fun fetch(baseUrl: String, apiKey: String, format: String): ModelListResult {
        val urls = endpoints(baseUrl, format)
        if (urls.isEmpty()) {
            return ModelListResult.Err("请先填写接口地址")
        }

        var lastErr: ModelListResult.Err? = null
        for (url in urls) {
            try {
                val text = HttpSupport.get(url, headers(apiKey, format), timeoutSeconds = 20)
                val models = parse(text)
                if (models.isNotEmpty()) {
                    return ModelListResult.Ok(models, url)
                }
                lastErr = ModelListResult.Err(
                    "接口返回了内容，但没有解析出模型",
                    "${url}\n${text.take(300)}"
                )
            } catch (e: HttpSupport.HttpError) {
                lastErr = ModelListResult.Err(advise(e, baseUrl), url)
                // 401/403 是鉴权问题，换地址也没用，直接返回更省时间
                if (e.status == 401 || e.status == 403) {
                    return ModelListResult.Err(
                        if (apiKey.isBlank()) {
                            LlmErrorAdvice.explain("该服务商需要先填 API 密钥", status = e.status).render()
                        } else {
                            advise(e, baseUrl)
                        },
                        url
                    )
                }
            } catch (e: Exception) {
                // 连接类异常：走网络错误的专用建议（域名解析 / 端口 / 超时 / 证书），
                // 详情写「主机名 + 原因」而不是最后的候选地址，
                // 否则用户会看到 /api/tags 这种他没填过的地址，反而困惑
                lastErr = ModelListResult.Err(
                    LlmErrorAdvice.renderNetworkError(e, baseUrl),
                    "$baseUrl（网络层异常，未能发出请求）"
                )
            }
        }
        return lastErr ?: ModelListResult.Err("拉取失败")
    }

    /**
     * 拉取失败时给用户的完整建议（含余额 / 密钥 / 网络等常见原因）。
     *
     * 原来这里另写了一套「404 → 没有列表接口」之类的简短文案，结果同一个 401
     * 在「测试连接」和「拉取模型」里说法完全不同。现在统一走 [LlmErrorAdvice]。
     */
    private fun advise(e: HttpSupport.HttpError, baseUrl: String): String =
        LlmErrorAdvice.explain(
            LlmErrorAdvice.extractServerMessage(e.body),
            model = "",
            status = e.status
        ).render()

    /**
     * 解析模型列表。故意写得容错：认不出 id 的条目跳过而不是整体失败 ——
     * 一个条目格式怪异不该让整个列表拿不到。
     */
    fun parse(text: String): List<RemoteModel> {
        val root = runCatching { Json.parse(text) }.getOrNull() ?: return emptyList()

        val items: List<Json> = when {
            root.arr("data") != null -> root.arr("data")!!.items
            root.arr("models") != null -> root.arr("models")!!.items
            root.asArrOrNull != null -> root.asArrOrNull!!.items
            // 有些实现把列表埋在 result / list 里
            root.arr("result") != null -> root.arr("result")!!.items
            root.arr("list") != null -> root.arr("list")!!.items
            else -> return emptyList()
        }

        val seen = HashSet<String>()
        val out = ArrayList<RemoteModel>(items.size)
        for (item in items) {
            val m = readOne(item) ?: continue
            if (m.id.isBlank()) continue
            if (!seen.add(m.id)) continue
            out.add(m)
        }
        // 固定按字母序，让同一服务商每次拉取的顺序一致，便于用户找
        return out.sortedBy { it.id.lowercase() }
    }

    private fun readOne(item: Json): RemoteModel? {
        // 极简实现：数组里直接是字符串
        if (item.asStringOrNull != null) return RemoteModel(item.asStringOrNull!!)

        val obj = item.asObjOrNull ?: return null
        val id = obj.str("id")
            ?: obj.str("name")          // Ollama /api/tags
            ?: obj.str("model")         // Ollama 另一种写法
            ?: obj.str("model_name")
            ?: return null

        return RemoteModel(
            id = id,
            label = obj.str("display_name")?.takeIf { it.isNotBlank() && it != id } ?: "",
            contextLength = obj.long("context_length")
                ?: obj.obj("top_provider")?.long("context_length")
                ?: 0L,
            owner = obj.str("owned_by") ?: obj.str("owner") ?: "",
            sizeHint = obj.obj("details")?.str("parameter_size") ?: ""
        )
    }

    /**
     * 判断是不是「能对话的模型」。
     *
     * 用途：一个服务商往往返回几百个模型，其中大半是向量化 / 语音 / 绘图模型，
     * 对话根本用不上。默认把这些藏掉，让列表能直接看 —— 但只做黑名单式的保守判断，
     * 拿不准的一律保留（宁可多显示，不可漏掉）。
     */
    fun looksLikeChatModel(id: String): Boolean {
        val s = id.lowercase()
        for (token in NON_CHAT_TOKENS) {
            if (s.contains(token)) return false
        }
        return true
    }

    /**
     * 只挑确定不是对话模型的词。
     *
     * 刻意不含 "vision" / "image"：前者都是能对话的多模态模型（gpt-4o 一类），
     * 后者会误伤名字里带 image 的对话模型。
     */
    private val NON_CHAT_TOKENS = listOf(
        "embedding", "embed-", "-embed", "_embed",
        "rerank",
        "whisper",
        "tts-", "-tts", "text-to-speech", "speech",
        "moderation",
        "dall-e", "dalle",
        "stable-diffusion", "sdxl",
        "flux-", "-flux",
        "bge-", "-bge", "bce-",
        "text-similarity",
        "colpali",
        "sam-", "grounding-dino",
        "wanx", "cogvideo", "kling"
    )
}
