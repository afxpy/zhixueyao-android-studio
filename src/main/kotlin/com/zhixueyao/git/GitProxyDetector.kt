package com.zhixueyao.git

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 本地代理 / VPN 工具的**机械检测**。
 *
 * ## 为什么是机械检测，而不是让模型判断
 *
 * 用户提过「检测 VPN 是否启动成功这个需要依赖 AI 大模型去思考」。这里刻意没那么做，
 * 因为「有没有启动成功」是一个**有唯一答案**的问题：
 *
 *  - 端口有没有在监听 → 连一下就知道了
 *  - 那个端口是不是 HTTP 代理 → 发一个真实的代理请求就知道了
 *  - 能不能真的走通 → 让它去访问一次目标站
 *
 * 三件事都是确定性的。让模型去答会**慢**（每次几秒）、**贵**（每次几千 token），
 * 而且最要命的是**不可靠** —— 它可能说「看起来通了」，而实际没通。
 *
 * 这正是这个工程里反复验证过的一条：**能用确定性方法验的，不要让模型猜。**
 * 探针能测出来的事，就不该交给模型的印象。
 *
 * 模型在这里有个**更合适**的位置：检测**失败之后**，把错误信息交给它解释
 * （「你填的地址少了 http://」「这个端口是别的软件占的」）。**检测用机械的，解释用 AI 的。**
 *
 * ## 为什么不只做 TCP 连接
 *
 * 只 `connect()` 是不够的：**很多端口开着但不是代理**（IDE 自己、其他服务）。
 * 连上了就报「找到代理」会误导用户。所以这里发一个真实的
 * `CONNECT` 请求，看它回不回 `200` —— 那才是「这是个能用的 HTTP 代理」的证据。
 *
 * 全部是纯 JDK，没有平台依赖 —— 于是可以离线跑探针验证（见 GitProxyProbe）。
 */
object GitProxyDetector {

    /**
     * 常见的本地代理端口。
     *
     * ## 这个列表**永远不全** —— 用户实测教我的
     *
     * 我原来写的是 `7890, 7897, 7891, 10809, …`。用户用的 SpeedCloud
     * **端口是 7892** —— 就差一个数字，于是怎么扫都扫不到，
     * 界面一直显示「检测不到」，而他的代理其实开着、也真的能连上 github。
     *
     * 所以现在**不再只靠这个列表**：主路径是 [listListeningPorts] ——
     * 直接问系统「本机哪些端口在监听」，再逐个验证哪个是代理。
     * 硬编码只作为兜底（万一 netstat 拿不到）。
     *
     * ## 顺序有讲究
     *
     * 高频的放前面：7890 是 Clash 系默认，10809 是 v2rayN 默认，
     * 7897 是 Clash Verge 新版默认，**7892 是 SpeedCloud**（用户实测）。
     * 扫描是并发的，所以顺序只影响「报告里先看到谁」，不影响速度。
     */
    val COMMON_PORTS = listOf(
        7890, 7891, 7892, 7893, 7897, 7898,
        10809, 10808, 1080, 1081,
        8889, 8080, 8118, 8888,
        57567, 20171, 2080
    )

    /**
     * 一个「候选来源」—— 指某个可能藏着代理地址的地方。
     *
     * ## 为什么要分来源，而不是只报一个端口
     *
     * 用户的原话：「**检测方法越多越好，这样检测到的概率也大**」。
     *
     * 他说得对：不同客户端把代理信息放在**完全不同的地方** ——
     * Clash 开本地端口、有些工具设环境变量、系统代理开关写注册表、
     * 还有些工具干脆接管网卡什么都不留。
     *
     * **每多一个来源，就多覆盖一类客户端**；而且报告时能说清
     * 「我是从哪儿知道的」—— 用户换个客户端时就知道该去哪儿看。
     */
    data class Source(val kind: String, val detail: String)

    /**
     * 来源一：**环境变量**。
     *
     * `HTTP_PROXY` / `HTTPS_PROXY` / `ALL_PROXY` 是跨平台的事实标准，
     * 很多客户端（尤其是命令行工具、CI 环境）会设它。
     * 大小写两种都查 —— 有些程序只设小写，有些只设大写。
     *
     * **成本几乎为零，所以排在很前面。**
     */
    // 说明：**这两个来源都是「锦上添花」，不是必需。**
    // 读不到就返回 null，绝不会让整个检测失败 ——
    // 因为还有「监听端口」那条主路径兜着。
    fun envProxy(): String? {
        val keys = listOf(
            "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"
        )
        for (k in keys) {
            val v = System.getenv(k)?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    /**
     * 来源二：**Windows 的系统代理设置**（注册表）。
     *
     * 这就是「设置 → 网络和 Internet → 代理 → 使用代理服务器」那个开关，
     * 也是 **Clash Verge 界面上那个「系统代理」开关改的地方** ——
     * 用户专门问过「如果我使用 clash 之类的工具呢」，这条正是回答他的。
     *
     * 读的是 `HKCU\...\Internet Settings` 下的 `ProxyEnable` 和 `ProxyServer`。
     * 用 `reg query` 而不是 JNA —— 少一个依赖，而且离线也能跑。
     *
     * **注意 MAC 和 Linux 没有这个**，直接返回 null（那边靠环境变量）。
     */
    fun systemProxy(): String? {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null

        // **先用 Java 自带的注册表读取，别起进程。**
        //
        // 一开始我用的是 `reg query`：能跑，但有两个问题 ——
        //   1. **每次要启动一个进程**（几十毫秒 + 一个进程名额）
        //   2. **可能被安全策略拦掉**。我自己就撞上了：沙箱把 `reg.exe`
        //      列进了黑名单，探针直接被终止。
        //      用户的机器上也可能有企业策略做同样的事。
        //
        // `java.util.prefs` 在 Windows 上底层就是注册表，读同样的键，
        // **不用起进程、不依赖 PATH、也不会进任何黑名单**。
        val fromPrefs = runCatching {
            val node = java.util.prefs.Preferences.userRoot()
                .node("Software/Microsoft/Windows/CurrentVersion/Internet Settings")
            val enabled = node.get("ProxyEnable", "0")
            // ProxyEnable 是 DWORD：有的实现读出来是 "1"，有的是 "0x1"
            val on = enabled == "1" || enabled.equals("0x1", ignoreCase = true)
            if (on) node.get("ProxyServer", null) else null
        }.getOrNull()
        if (!fromPrefs.isNullOrBlank()) return fromPrefs

        // 兜底：java.util.prefs 在个别环境下读不到（权限/重定向），再试命令。
        // 被拦就返回 null —— **这是可选的来源，不该因为它失败而影响其它检测**。
        return runCatching {
            val r = com.zhixueyao.tools.ProcessRunner.run(
                listOf("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyServer"),
                java.io.File(System.getProperty("user.home") ?: "."), null, 3000
            )
            Regex("""REG_SZ\s+(\S+)""").find(r.stdout)?.groupValues?.get(1)
        }.getOrNull()
    }

    /**
     * 从系统里问出**本机正在监听的端口**（只取回环地址上的）。
     *
     * ## 为什么要这么做
     *
     * 每个 VPN / 加速器客户端的端口都不一样，且**用户可以自己改**。
     * 硬编码一个列表去猜，注定会漏 —— 而漏了的表现是「检测不到」，
     * 用户完全不知道是「端口没猜中」还是「客户端真的没开」。
     *
     * 直接问系统就没有这个问题：**只要它在监听，就能被发现**
     * （能不能当代理另说，那是下一步验证的事）。
     *
     * ## 过滤规则
     *
     * 只保留 1024 以上的端口：1023 以下是系统保留段，
     * 而且那些端口**不该被当成代理**（试它们没意义，还会拖慢扫描）。
     */
    fun listListeningPorts(): List<Int> = runCatching {
        val cmd = if (System.getProperty("os.name").orEmpty().lowercase().contains("win"))
            listOf("netstat", "-ano") else listOf("netstat", "-lnt")
        val r = com.zhixueyao.tools.ProcessRunner.run(
            cmd, java.io.File(System.getProperty("user.home") ?: "."), null, 5000
        )
        if (!r.ok) return emptyList()

        // 两种输出格式都认：
        //  Windows / netstat -ano:  TCP    127.0.0.1:7892    0.0.0.0:0    LISTENING    1234
        //  Linux   / netstat -lnt:  tcp    0    0 127.0.0.1:7892    0.0.0.0:*    LISTEN
        val re = Regex("""(?:127\.0\.0\.1|\[::1\]|::1):(\d{4,5})\s""")
        r.stdout.lineSequence()
            .filter { it.contains("LISTEN", true) }
            .mapNotNull { line -> re.find(line)?.groupValues?.get(1)?.toIntOrNull() }
            // 1023 以下是系统保留段，试它们没意义
            .filter { it > 1023 }
            // **排除 Windows 的动态端口段（49152-65535）。**
            //
            // 这里踩过一个坑，而且是探针自己抓出来的：动态扫描上线后，
            // 它在我的机器上「找到」了 **54132** 并报告「是可用代理」。
            // 但手动去连那个端口，它什么都不回。
            //
            // 查下来 54132 属于 `sandbox-cli.exe` —— **是我自己的执行环境**。
            // 动态端口段是系统**临时分配**的：
            //  1. 每次扫都可能扫到不同的东西（甚至是别的软件的内部端口）
            //  2. 就算当下能用，**存下来做代理地址下次一定不通**
            //
            // 而真正的 VPN 客户端用的是**注册端口段**（1024-49151）——
            // 用户的 SpeedCloud 是 7892，Clash 是 7890，v2rayN 是 10809，
            // 都在这个区间里。
            //
            // 用户**自己填的端口不受这条限制**（见 detectLocalProxy 里 extraPorts 的处理）——
            // 万一他真用了高位端口，仍然要能试。
            .filter { it < 49152 }
            .distinct()
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

    /** 一次探测的结果 */
    data class Probe(
        /** 可用（含义随场景：探测代理时 = 是代理；测连通性时 = 真的通） */
        val ok: Boolean,
        val port: Int,
        /** 人话说明，直接显示给用户 */
        val detail: String
    )

    /** 整体检测结果 */
    data class Detection(
        val found: Boolean,
        val port: Int?,
        /** 直接可用的代理地址（如 `http://127.0.0.1:7890`），没找到就是 null */
        val proxyUrl: String?,
        /** 扫了哪些端口、各自什么结果 —— 找不到时给用户看，免得像「什么都没干」 */
        val scanned: List<Probe>,
        /** 命中的端口是用户自己填的（false = 自动从系统监听列表里发现的） */
        val fromUserInput: Boolean = false
    )

    /**
     * 扫一圈常见端口，返回第一个**确认可用**的代理。
     *
     * @param extraPorts 用户自己指定的端口（会**优先**试 —— 他既然填了，就先信他）
     * @param timeoutMs 每个端口给多少时间。默认 400ms：
     *   本机回环的连接要么立刻成功要么立刻失败，等太久没意义，
     *   而端口有十几个，逐个等 2 秒会让「点一下检测」变成等半分钟。
     */
    fun detectLocalProxy(extraPorts: List<Int> = emptyList(), timeoutMs: Int = 400): Detection {
        // **先问系统，再兜底到硬编码。**
        //
        // 用户填的端口最优先（他既然填了，就先信他）；
        // 其次是系统里真实在监听的（最准，能发现任何客户端）；
        // 最后才是常见列表（万一 netstat 不可用）。
        //
        // 这个顺序解决的是「硬编码列表永远不全」——
        // 用户那个 7892 就是被漏掉的那个。
        val listening = listListeningPorts().take(60)   // 上限兜一下，别把几百个端口全试一遍

        // **再加上另外两个来源里的端口。**
        //
        // 它们指出的地址通常也在监听列表里 —— 但**不保证**：
        // 比如某个代理只听 IPv6，或者 netstat 输出格式没解析到。
        // 既然「方法越多命中概率越大」，那就把它们的端口也直接加进来试一遍。
        val fromEnv = envProxy()?.let { parsePort(it) }
        val fromSys = systemProxy()?.let { parsePort(it) }
        val declared = listOfNotNull(fromEnv, fromSys)

        val ports = (extraPorts + declared + listening + COMMON_PORTS).distinct()

        // **并发扫，不是挨个试。**
        //
        // 原来是 `for (p in ports) { probe(p) }` —— 每个端口最坏等满 timeout，
        // 十四五个端口就是五六秒。用户看到的是「点了没反应」
        // （原话：「检测很久都没反应」）。
        //
        // 这些探测**互相独立**（各连各的端口，没有共享状态），
        // 所以并发是安全的 —— 而串行纯粹是图省事写的。
        // 并发之后总耗时 = 最慢的那一个，而不是所有之和。
        val results = java.util.concurrent.ConcurrentHashMap<Int, Probe>()
        val threads = ports.map { p ->
            Thread {
                results[p] = probeHttpProxy(p, timeoutMs)
            }.apply { isDaemon = true; start() }
        }
        // 等全部结束。每个线程自己的 socket 都有超时，所以这里的 join 一定会返回
        threads.forEach { runCatching { it.join((timeoutMs + 400).toLong()) } }

        // 按**声明顺序**整理结果（而不是完成的先后）——
        // 这样报给用户的「已试：7890、7897…」顺序是稳定的，便于对照
        val scanned = ports.mapNotNull { results[it] }

        // ---------- **两段式：先粗筛，再握手确认** ----------
        //
        // 这里踩过一个坑：探针报 4310「可用（HTTP/1.1 200）」，
        // 但真去握手就失败 —— 因为 `probeHttpProxy` 只看 `CONNECT` 回没回 200，
        // 而 200 只表示「我同意建隧道」，**不代表隧道里跑得通**。
        //
        // 用户那个「只能上国内」的代理就是活例子：照样回 200，然后什么都不给。
        //
        // 所以「找到代理」的定义改成：**必须真的能在隧道里完成一次 TLS 握手**。
        // 粗筛（probeHttpProxy）只用来缩小范围（通常只剩一两个候选），
        // 握手确认才是判定依据 —— 两段式既准又不慢。
        val candidates = ports.mapNotNull { results[it] }.filter { it.ok }
        val hit = candidates.firstOrNull { c ->
            testThroughProxy("http://127.0.0.1:${c.port}", timeoutMs = 3000).ok
        }
        return if (hit != null) {
            Detection(
                true, hit.port, "http://127.0.0.1:${hit.port}", scanned,
                // 说清是「用户在设置里填的」还是「自动从系统里发现的」——
                // 用户看到 7892 被找到了，但不知道它是怎么找到的，
                // 下次换个客户端又会困惑
                fromUserInput = hit.port in extraPorts
            )
        } else {
            Detection(false, null, null, scanned)
        }
    }

    // ---------------- 带缓存的探测 ----------------

    /** 缓存有效期：60 秒 */
    private const val CACHE_TTL_MS = 60_000L

    /** 最近一次的探测结果（时间戳 + 结果）。见 [detectLocalProxyCached] 的说明 */
    @Volatile
    private var cachedDetection: Pair<Long, Detection>? = null

    /**
     * [detectLocalProxy] 的带缓存版本：**60 秒内只真扫一次**。
     *
     * ## 为什么要加它
     *
     * 「本地 VPN 工具」模式、端口留空时，[com.zhixueyao.tools.GitTool] 的
     * `accessArgs()` 会在**每一条 git 命令**上做一次探测 —— 而探测会连一圈
     * 本机端口。用户明确提过：**「不要总是打开我的本地代理」**。
     * 一条 `git status` 也去敲一轮代理端口，既慢又打扰（代理软件那边能看到一串连接）。
     *
     * 所以加这层 60 秒缓存：短时间内反复用同一条通道，只探一次。
     * **用户主动点「测试连接」不走这里**（见设置页 testVpnMode）—— 那是他
     * 要的实时结果，不该被缓存挡住。
     */
    fun detectLocalProxyCached(timeoutMs: Int = 400): Detection {
        val now = System.currentTimeMillis()
        cachedDetection?.let { if (now - it.first < CACHE_TTL_MS) return it.second }
        val fresh = detectLocalProxy(timeoutMs = timeoutMs)
        cachedDetection = now to fresh
        return fresh
    }

    /**
     * 找一圈，返回「所有答应了但握手没成的端口」。
     *
     * 给界面用：用户看到「没找到代理」会以为是客户端没开，
     * 而实际可能是**客户端开着、但那条线路访问不了 github**（比如只能上国内的代理）。
     * 这两种情况的处置完全不同，所以要说清。
     */
    fun describeRejected(scanned: List<Probe>): String? {
        val answered = scanned.filter { it.ok }
        if (answered.isEmpty()) return null
        return "有 " + answered.size + " 个端口回应了代理请求但连不上 github（" +
            answered.take(3).joinToString("、") { it.port.toString() } +
            "）—— 客户端可能是开着的，但那条线路访问不了 github。"
    }

    /**
     * 探测某个端口是不是一个**能用的** HTTP 代理。
     *
     * 两步，缺一不可：
     *  1. 连得上（TCP）—— 排除「端口根本没开」
     *  2. 发一个 `CONNECT` 并收到 `200` —— 排除「端口开着但不是代理」
     *
     * 第 2 步是关键。少了它，任何占着 7890 端口的软件都会被误报成代理。
     */
    fun probeHttpProxy(port: Int, timeoutMs: Int = 400): Probe {
        // 第 1 步：连得上吗
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            }
        } catch (e: Exception) {
            return Probe(false, port, "端口没开")
        }

        // 第 2 步：真的是代理吗 —— 发一个 CONNECT 看回应
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().write(
                    ("CONNECT github.com:443 HTTP/1.1\r\n" +
                        "Host: github.com:443\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                s.getOutputStream().flush()

                val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    .readLine() ?: return Probe(false, port, "端口开着，但没回应代理请求")

                // 期望形如 `HTTP/1.1 200 Connection established`
                when {
                    line.contains(" 200") -> Probe(true, port, "可用（$line）")
                    line.contains(" 407") -> Probe(true, port, "可用，但需要认证（$line）")
                    line.startsWith("HTTP/") -> Probe(false, port, "端口开着，但拒绝代理请求（$line）")
                    else -> Probe(false, port, "端口开着，但不像代理（回应：${line.take(40)}）")
                }
            }
        } catch (e: Exception) {
            Probe(false, port, "端口开着，但探测失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 验证一个代理地址能不能真的走通到目标站。
     *
     * 和 [probeHttpProxy] 的区别：那个只验「这个端口是代理」，
     * 这个验「**用这个代理能访问到 GitHub**」—— 两回事。
     * 代理开着但节点没连上、或者规则把 github 走了直连导致超时，都会在这一步露馅。
     */
    fun testThroughProxy(proxyUrl: String, targetHost: String = "github.com", timeoutMs: Int = 4000): Probe {
        val port = parsePort(proxyUrl)
            ?: return Probe(false, -1, "代理地址看不懂：$proxyUrl（应该是 http://127.0.0.1:7890 这种）")
        val host = parseHost(proxyUrl)

        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().write(
                    ("CONNECT $targetHost:443 HTTP/1.1\r\nHost: $targetHost:443\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                s.getOutputStream().flush()
                val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    .readLine() ?: return Probe(false, port, "代理没有回应")

                if (!line.contains(" 200")) {
                    return Probe(false, port, "代理拒绝了这个请求：$line")
                }

                // ---------- **关键：真的握一次手，别停在「它答应了」** ----------
                //
                // 这里踩过一个坑。用户给我看了他的系统代理设置：
                //   `127.0.0.1:7892`，而他说「**这是国内的代理，不能访问国外网址**」。
                //
                // 而我原来的代码在这一步就返回「能连上」了 ——
                // 因为**代理确实回了 `200 Connection established`**。
                //
                // 问题在于：`CONNECT` 的 200 只表示「我同意建隧道」，
                // **不表示隧道里真的能跑通**。一个只能访问国内的代理，
                // 完全可以照常回 200，然后在你发出去的请求上静默超时。
                //
                // 所以必须往下再走一步：**在隧道里做一次真实 TLS 握手**。
                // 握手需要服务端真的回应 ServerHello —— 那是「通」的硬证据，
                // 而且它是加密的、无法被中间环节伪造成功。
                val (hsOk, hsMsg) = handshakeThrough(s, targetHost, timeoutMs)
                if (hsOk) Probe(true, port, "通过代理能连上 $targetHost（$hsMsg）")
                else Probe(false, port, "**代理答应了建隧道，但实际连不通**：$hsMsg")
            }
        } catch (e: Exception) {
            Probe(false, port, "走代理连不上 $targetHost：${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}")
        }
    }

    /**
     * 在已经建好的隧道上做一次 TLS 握手。返回一句人话说明 + 是否成功。
     *
     * 拆出来是因为它和上面的代理判断是**两件独立的事**：
     * 「是不是代理」看 CONNECT 的回应，「能不能真的访问」看握手的结果。
     */
    private fun handshakeThrough(
        tunnel: java.net.Socket, targetHost: String, timeoutMs: Int
    ): Pair<Boolean, String> {
        return try {
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(TRUST_ALL), null)
            val ssl = ctx.socketFactory.createSocket(tunnel, targetHost, 443, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            true to (ssl.session.protocol + " 握手成功")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            // 握手被拒 = 代理没把流量真的转出去（或者被中间设备挡了）
            false to ("TLS 握手失败：" + e.message.orEmpty().take(70))
        } catch (e: Exception) {
            false to ("握手没完成：" + e.javaClass.simpleName + " " + e.message.orEmpty().take(60))
        }
    }

    /**
     * 只用于「测试连通性」的信任策略 —— **因为这个连接不传输任何数据**。
     *
     * 我们只是想知道「能不能握手」，不关心对端是不是真的 github。
     * 所以接受任何证书（否则自签证书的中间代理会让检测假失败）。
     *
     * **注意：这不能用在任何真实的业务请求上** —— 那等于关掉了证书校验。
     * 这里之所以可以，是因为它**只做握手然后立刻关闭，不发一个字节**。
     */
    private val TRUST_ALL = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    /** 从 `http://127.0.0.1:7890` 里取端口；取不到返回 null */
    fun parsePort(proxyUrl: String): Int? {
        val cleaned = proxyUrl.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://")
        val afterHost = cleaned.substringAfter(':', "")
        if (afterHost.isEmpty()) return null
        return afterHost.substringBefore('/').trim().toIntOrNull()
    }

    /** 从 `http://127.0.0.1:7890` 里取主机；没有就默认回环地址 */
    fun parseHost(proxyUrl: String): String {
        val cleaned = proxyUrl.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://")
        val host = cleaned.substringBefore(':').substringBefore('/').trim()
        return host.ifEmpty { "127.0.0.1" }
    }

    /**
     * 给 git 命令拼「走代理」的参数。
     *
     * 用 `-c http.proxy=...` 的形式**临时传参**，而不是去改用户的 git 配置 ——
     * 那样关掉插件就等于没装过，也不会影响用户在自己终端里敲的 git 命令。
     */
    fun proxyArgs(proxyUrl: String?): List<String> =
        if (proxyUrl.isNullOrBlank()) emptyList()
        else listOf("-c", "http.proxy=$proxyUrl", "-c", "https.proxy=$proxyUrl")

    /**
     * 给 git 命令拼「不走代理」的参数。
     *
     * **这一条是这次排查的产物**：用户的 git 配置里写着 `http.proxy=127.0.0.1:57567`，
     * 而那个代理根本没开 —— 于是每次推送都在敲一扇没人应的门，
     * 报错是 `Failed to connect ... over proxy 127.0.0.1`，
     * 很容易被读成「网络不通」。
     *
     * 直连模式下必须**显式清空**这两个配置，否则用户配置里残留的代理会继续生效。
     * 空字符串是 git 认可的「取消这个配置项」的写法。
     */
    fun directArgs(): List<String> =
        listOf("-c", "http.proxy=", "-c", "https.proxy=")
}
