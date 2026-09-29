package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * **网页抓取**。
 *
 * 对应参考项目里的 `web_fetch`（agents-universe）和 `tavily_tool`（insight-agents）。
 *
 * ## 为什么需要
 *
 * 之前这个插件**完全不能上网**。于是「这个报错什么意思」「这个库的 API 怎么用」
 * 「这个版本有什么变化」这类问题，模型只能靠训练时的记忆答 ——
 * 而库的 API 是最容易过时的东西，记错一个参数名就会让用户白改半天。
 *
 * ## 安全：SSRF 是这里唯一真正危险的地方
 *
 * 「让 AI 去访问一个 URL」听起来无害，但服务端请求伪造（SSRF）是真实攻击面：
 * 攻击者（或提示词注入）可以构造 `http://169.254.169.254/...`
 * 去读云主机的实例元数据（那里面有临时凭据），或者 `http://localhost:8080`
 * 去探用户本机的管理接口。
 *
 * 所以这里的策略是**默认只允许公网**，并且**先解析 DNS 再校验 IP**——
 * 只校验域名字符串是没用的（`127.0.0.1.nip.io` 这种域名会解析到环回地址）。
 *
 * 借鉴 agents-universe 的 `_ssrf.py`。
 */
object SsrfGuard {

    /** 明确禁止的主机名（字面量，DNS 解析之前先拦一道） */
    private val BLOCKED_HOSTS = setOf(
        "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback"
    )

    /**
     * 校验一个 URL 是否可以访问；返回 null 表示放行，否则返回拒因。
     *
     * @param allowPrivate 用户显式放行内网时传 true（比如他就是要看公司内网的接口文档）
     */
    fun check(url: String, allowPrivate: Boolean = false): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return "URL 格式不对：$url"

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "只支持 http / https（当前是 ${scheme ?: "空"}）。" +
                "file: / ftp: 这类协议不能通过这个工具访问"
        }

        val host = uri.host?.lowercase()?.trim('[', ']')
            ?: return "URL 里没有主机名：$url"

        if (host in BLOCKED_HOSTS) {
            return "拒绝访问本机地址（$host）：这个工具只用于抓公网资料"
        }

        // **先解析 DNS，再按 IP 判断**。
        // 只比域名字符串会被骗：`127.0.0.1.nip.io`、`localtest.me` 都会解析到环回地址。
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
            ?: return "域名解析失败：$host（网络不通或域名不存在）"
        if (addresses.isEmpty()) return "域名解析不到任何地址：$host"

        if (!allowPrivate) {
            for (addr in addresses) {
                blockedIpReason(addr)?.let { return "$host 解析到 $it" }
            }
        }
        return null
    }

    /**
     * 这个 IP 是不是「不该被访问的」。
     *
     * 覆盖的几类（按危险程度）：
     *  - **云元数据** `169.254.169.254`（AWS/GCP/阿里云…的实例凭据就在这）
     *  - 环回 `127.0.0.0/8`、`::1` —— 用户本机的各种服务
     *  - 私有网段 `10/8`、`172.16/12`、`192.168/16` —— 内网的其他机器
     *  - 链路本地 `169.254/16`、`fe80::/10`
     *  - 组播 / 保留段
     */
    private fun blockedIpReason(addr: InetAddress): String? {
        val ip = addr.hostAddress ?: return null
        // IPv4 映射地址（::ffff:1.2.3.4）要看它映射到的 v4
        val v4 = ip.removePrefix("::ffff:")

        if (addr.isLoopbackAddress) return "环回地址（$ip）"
        if (addr.isAnyLocalAddress) return "通配地址（$ip）"
        if (addr.isMulticastAddress) return "组播地址（$ip）"
        if (addr.isLinkLocalAddress) return "链路本地地址（$ip）"

        v4.split('.').takeIf { it.size == 4 }?.let { parts ->
            val b = parts.mapNotNull { it.toIntOrNull() }
            if (b.size == 4) {
                val (a, c) = b[0] to b[1]
                when {
                    // 云元数据：单独点名，因为它是这类攻击最常打的目标
                    a == 169 && c == 254 -> return "云元数据/链路本地网段（$ip）"
                    a == 10 -> return "私有网段（$ip）"
                    a == 192 && c == 168 -> return "私有网段（$ip）"
                    a == 172 && c in 16..31 -> return "私有网段（$ip）"
                    a == 127 -> return "环回网段（$ip）"
                    a == 0 -> return "保留网段（$ip）"
                    a >= 224 -> return "组播/保留网段（$ip）"
                }
            }
        }
        return null
    }

    /** 是不是云元数据地址 —— 单独拎出来，便于在提示里说清为什么危险 */
    fun isCloudMetadata(url: String): Boolean =
        url.contains("169.254.169.254") || url.contains("metadata.google.internal")
}

/**
 * 抓一个网页，把 HTML 转成纯文本交给模型。
 *
 * 只抓**一个 URL**、不跟随站内链接 —— 那是爬虫的活，
 * 而这个工具的目标是「读一页文档」。需要多页时模型可以多调几次
 * （每次都有明确的来源，也便于它自己判断要不要继续）。
 */
class WebFetchTool : AgentTool {

    override val name = "web_fetch"

    override val description =
        "抓取一个网页并转成纯文本。查库的官方文档、看某个报错的具体含义、" +
            "确认某个 API 的最新用法时用它 —— **不要凭记忆回答 API 细节**，那是最容易过时的东西。" +
            "只抓公网 http/https，不访问本机与内网地址。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "url" to jsonObj("type" to "string".toJson(), "description" to "完整 URL，含 https://"),
            "max_chars" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "最多返回多少字符，默认 20000（约中文 7 千 token）"
            ),
            "allow_private" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "只在用户明确要求访问内网/本机时传 true。默认 false（拦截环回与私有网段）"
            )
        ),
        "required" to jsonArr("url".toJson())
    )

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            // 不自动跟随跳转：跳转是 SSRF 绕过最常用的手法
            //（先给一个公网 URL，302 到 169.254.169.254）。
            // 这里手动跟，且**每一跳都重新过 SsrfGuard**。
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        var url = args.str("url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.error("缺少 url 参数")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        val maxChars = (args.int("max_chars") ?: 20_000).coerceIn(1_000, 200_000)
        val allowPrivate = args.bool("allow_private") == true

        SsrfGuard.check(url, allowPrivate)?.let { reason ->
            return ToolResult.error(
                "$reason\n\n" +
                    (if (SsrfGuard.isCloudMetadata(url))
                        "这个地址是云主机的实例元数据接口，里面有临时凭据 —— 绝不能读。\n\n"
                    else "") +
                    "如果确实想访问内网/本机地址（比如查公司内部接口文档），" +
                    "请用户明确要求后用 allow_private=true 再来一次。"
            )
        }

        // 手动跟跳转，每跳都重新校验（见 client 的注释）
        var current = url
        var hops = 0
        while (true) {
            val resp = fetchOnce(current, maxChars)
                ?: return ToolResult.error("请求失败：$current")
            val (status, location, body) = resp

            if (status in 300..399 && location != null) {
                if (++hops > 5) return ToolResult.error("跳转次数过多（>5），已停止")
                val next = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                    ?: return ToolResult.error("跳转目标解析失败：$location")
                SsrfGuard.check(next, allowPrivate)?.let { reason ->
                    return ToolResult.error("跳转目标被拦下：$reason\n（跳转是 SSRF 绕过最常用的手法，所以每一跳都要重新校验）")
                }
                current = next
                continue
            }

            if (status !in 200..299) {
                return ToolResult.error(
                    "HTTP $status：$current\n\n" +
                        when (status) {
                            401, 403 -> "这个页面需要登录或不允许抓取。让用户自己打开看，或者换一个公开来源。"
                            404 -> "页面不存在。检查 URL 有没有拼错。"
                            else -> "服务器拒绝了这次请求。"
                        }
                )
            }

            val text = htmlToText(body)
            return ToolResult(
                buildString {
                    append("# ").append(current).append("\n\n")
                    append(text.take(maxChars))
                    if (text.length > maxChars) {
                        append("\n\n……（原文还有 ").append(text.length - maxChars).append(" 字符，已截断）")
                    }
                }
            )
        }
    }

    private data class Resp(val status: Int, val location: String?, val body: String)

    private fun fetchOnce(url: String, maxChars: Int): Resp? = runCatching {
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            // 带一个正常的 UA：不少文档站会拒绝没有 UA 的请求
            .header("User-Agent", "Mozilla/5.0 (compatible; Zhixueyao-IDE-Agent)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .GET()
            .build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        Resp(
            status = r.statusCode(),
            location = r.headers().firstValue("location").orElse(null),
            // 抓取上限按 maxChars 的若干倍 —— HTML 里有大量标签，
            // 转成文本后会短很多，所以原始体要留够余量
            body = r.body().take(maxChars * 6)
        )
    }.getOrNull()

    /**
     * HTML → 纯文本。
     *
     * 刻意**不用正则去解析 HTML**（那是出了名的不可靠），而是：
     * 1. 先摘掉整块不产生正文的标签（script / style / nav / footer…）
     * 2. 再把剩下的标签换成空白
     * 3. 最后解实体、压缩空行
     *
     * 对文档页效果足够好，而且没有依赖、不会因为畸形 HTML 崩掉。
     */
    private fun htmlToText(html: String): String {
        var s = html
        // 整块丢弃的标签（连同内容）
        val dropBlocks = listOf(
            "script", "style", "noscript", "nav", "footer", "header", "svg",
            "iframe", "form", "aside"
        )
        for (tag in dropBlocks) {
            s = s.replace(
                Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                " "
            )
        }
        // 注释
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        // 换行语义的标签 → 真换行
        s = s.replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr)\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // 其余标签 → 空格
        s = s.replace(Regex("<[^>]+>"), " ")
        // 实体（只处理最常见的几个，够用）
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        // 压缩空白：连续空行留一个，行内多空格留一个
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }
}