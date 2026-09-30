package com.zhixueyao.git

import java.io.File

/**
 * Git 环境的**机械自检**。
 *
 * ## 为什么是「自检」而不是「填表」
 *
 * 用户的原话：
 *
 * > 我都感觉可以直接内置脚本不需要 ai 了都直接就是填好信息检查设备环境配置等
 *
 * **他说得对，而且这句话点破了我一直在做的事**：我给的是三个文本框让用户填，
 * 但用户真正需要的是**知道自己的环境缺什么、然后一键补上**。
 *
 * 这两者的区别很大：
 *  - 「填表」要求用户**先知道**自己缺什么 —— 而新手恰恰不知道
 *  - 「自检」把这件事反过来：**我来查，查完告诉你、并给你修**
 *
 * 而且这些检查**没有一项需要 AI**：
 * git 装没装、署名配没配、有没有凭据管理器、连不连得上 github、配置里有没有残留代理 ——
 * 全是**有唯一答案**的问题。让模型去判断只会更慢更不准
 * （这和「VPN 检测不该用 AI」是同一条判据）。
 *
 * ## 每项检查都带一个「怎么修」
 *
 * 只报问题不给解法，用户还是要自己搜。所以每项要么能**一键修**，
 * 要么给一个**确切的命令**让他复制。
 */
object GitEnvironment {

    /**
     * 单条命令的超时。
     *
     * ## 从 6 秒砍到 1.5 秒 —— 因为界面承诺了「几秒出结果」
     *
     * 用户报「四个都卡着没有一个检测出来」。算了一下实际耗时：
     *
     * ```
     * 5 项检查 × 6 秒超时
     *   其中「代理配置」要调 4 次 git config  → 最多 24 秒
     *   「连通性」连 github 再 8 秒         → 合计 50 秒以上
     * ```
     *
     * **而界面上写着「每项都是本地命令，几秒出结果」。** 承诺和实际差了十倍 ——
     * 用户等几秒当然以为卡住了。
     *
     * 而这些命令**全是本地的**（`git --version`、`git config --get`、`netstat`）：
     * 正常在 50-200 毫秒返回。1.5 秒已经是非常宽的余量 ——
     * **超过这个时间基本就是真出问题了，早点报出来比死等有用。**
     */
    private const val TIMEOUT = 1500L

    /** 连 github 的探测。网络确实可能慢，所以比本地命令宽，但也不该到 8 秒 */
    private const val NET_TIMEOUT_MS = 4000

    /** 一项检查的结果 */
    data class Check(
        val title: String,
        /** 通过 / 有问题 / 需要注意 */
        val level: Level,
        /** 人话说明，直接显示 */
        val detail: String,
        /** 一键修复的动作描述（null = 没有一键修，看 [fixCommand]） */
        val fixLabel: String? = null,
        /** 修复动作的类型，界面按它分派 */
        val fixKind: FixKind = FixKind.NONE,
        /** 没有一键修时给的确切命令，用户可复制 */
        val fixCommand: String? = null,
        /**
         * **这项检查实际执行的命令和拿到的输出。**
         *
         * ## 为什么要显示原始信息
         *
         * 用户报「自检检测不到全局的 Github 用户名和邮箱」——
         * 但同样的检查在探针里是能读到 `afxpy <1695650649@qq.com>` 的。
         *
         * 差别在于：**探针跑在我的 Git Bash 里，插件跑在 IDE 的 JVM 里** ——
         * 两者的 PATH、工作目录、环境变量都可能不同。
         *
         * 这时候继续猜「是 PATH 问题吧」「是 user.home 不对吧」会浪费很多轮，
         * 而**把命令和原始输出摆出来，一眼就能看出是哪一层断了**：
         *  - 命令没跑起来（"无法启动命令"）→ PATH 问题
         *  - 跑起来了但输出空 → 工作目录 / 配置层面问题
         *  - 输出有值但界面没显示 → 那是我界面层的 bug
         */
        val raw: String? = null
    )

    enum class Level { OK, WARN, MISSING }

    enum class FixKind {
        NONE,
        /** 展开输入框让用户填署名 */
        EDIT_AUTHOR,
        /** 清掉 git 配置里残留的代理 */
        CLEAR_PROXY,
        /** 一键启用 GCM（已经装了，只是没配） */
        ENABLE_GCM,
        /** 自动安装 GCM（真的没装）—— winget 优先，便携版兜底 */
        INSTALL_GCM,
        /** 打开 GCM 的下载页（真的没装） */
        OPEN_GCM_PAGE,
        /** 打开 git 下载页 */
        OPEN_GIT_PAGE
    }

    /**
     * 跑一遍全部检查。
     *
     * 全部是**本地命令 + 一次网络探测**，不依赖任何外部服务，几秒内出结果。
     */
    fun check(projectDir: File?): List<Check> = listOf(
        checkGitInstalled(),
        checkAuthor(),
        checkCredentialHelper(),
        checkProxyResidue(projectDir),
        checkReachability(),
    )

    // ---------------- 各项 ----------------

    /** git 装了没有、什么版本 */
    private fun checkGitInstalled(): Check {
        val r = run(listOf("git", "--version"), null)
        if (r == null || !r.ok) {
            return Check(
                "Git 已安装", Level.MISSING,
                "找不到 git 命令。插件靠它做提交与推送，先装一个。" +
                    "**如果下面显示「命令没能启动」，那是 IDE 的 PATH 里没有 git —— " +
                    "重启 IDE 通常能读到新 PATH。**",
                fixLabel = "打开 Git 下载页", fixKind = FixKind.OPEN_GIT_PAGE,
                raw = takeRaw()
            )
        }
        val v = r.stdout.trim()
        return Check("Git 已安装", Level.OK, v.ifBlank { "已找到 git" }, raw = takeRaw())
    }

    /**
     * 提交署名配了没有。
     *
     * **这项对「换了一台机器」和「新用户」特别重要** ——
     * 老用户多半早就配过了（装 git 时就配），但新用户很可能没有，
     * 而没配的话提交会失败或署名成机器名。
     */
    private fun checkAuthor(): Check {
        // **分两次读，中间把 raw 取出来** —— 否则第二次读会把第一次的信息覆盖掉，
        // 而「name 读到了、email 没读到」这种情况恰恰最需要看到两次的原始输出。
        val n = GitIdentity.currentName()
        val rawName = takeRaw()
        val e = GitIdentity.currentEmail()
        val rawEmail = takeRaw()
        if (n.isNotBlank() && e.isNotBlank()) {
            return Check("提交署名", Level.OK, "$n <$e>", raw = rawName)
        }
        val missing = buildList {
            if (n.isBlank()) add("user.name")
            if (e.isBlank()) add("user.email")
        }.joinToString(" 和 ")
        return Check(
            "提交署名", Level.MISSING,
            "缺 $missing。不配的话提交会失败，或者署名成机器名（历史里出现一串莫名的用户名）。",
            fixLabel = "填上并写进 git 配置", fixKind = FixKind.EDIT_AUTHOR,
            raw = rawName + "\n\n" + rawEmail
        )
    }

    /**
     * 有没有凭据管理器 —— **这是 GitHub 官方推荐的认证方式**。
     *
     * 比「在插件里存 token」更好：一次登录，**用户在自己终端里 push 也不用再填**，
     * 而且凭据由操作系统加密保管，插件完全不碰 token。
     */
    private fun checkCredentialHelper(): Check {
        val v = run(listOf("git", "config", "--global", "--get", "credential.helper"), null)
        val raw = takeRaw()
        val helper = v?.stdout?.trim().orEmpty()

        // **先看它装没装，再决定按钮写什么。**
        //
        // 原来这个按钮写的是「了解 Git Credential Manager」（跳下载页）——
        // 但对**绝大多数用户**来说，Git for Windows 从 2.39 起就**自带 GCM**，
        // 只是安装时那一项没勾、或者后来被改过，于是 `credential.helper` 是空的。
        //
        // 实测这台机器：`D:/Git/mingw64/bin/git-credential-manager.exe` 好端端在那儿，
        // 而配置里什么都没有。用户看到「了解 GCM」跳出去下载一个**自己已经有的东西**，
        // 这才是真的绕路。
        //
        // 所以：**找到 exe 就给「一键启用」，找不到才给下载入口。**
        val exe = findGcm()
        return when {
            helper.isNotEmpty() && !helper.contains("manager") -> Check(
                "凭据管理器", Level.OK, "已配置：$helper", raw = raw
            )
            helper.contains("manager") -> Check(
                "凭据管理器", Level.OK,
                "已装 Git Credential Manager（$helper）—— 官方推荐的方式，" +
                    "凭据由系统加密保管，插件不用碰你的 token。",
                raw = raw
            )
            exe != null -> Check(
                "凭据管理器", Level.WARN,
                "**已经装了，只是没启用。**\n" +
                    "找到：`$exe`\n" +
                    "启用之后，你在自己的终端里 push 也不用再输账号 —— " +
                    "凭据由系统加密保管，插件完全不用碰你的 token。\n" +
                    "（不启用也能用，只是插件得自己存一份 token —— 那你的终端里还要再填一次。）",
                fixLabel = "一键启用", fixKind = FixKind.ENABLE_GCM,
                raw = raw
            )
            else -> Check(
                "凭据管理器", Level.WARN,
                "**没装。** 这样每次推送都要手输账号，插件也只能自己存一份 token" +
                    "（那你自己的终端里还要再填一次）。\n" +
                    "点下面的按钮可以直接装上 —— 有 winget 就用它，" +
                    "没有就下载便携版放到用户目录，**不需要管理员权限**。",
                fixLabel = "自动安装", fixKind = FixKind.INSTALL_GCM,
                fixCommand = "winget install Git.GitCredentialManager",
                raw = raw
            )
        }
    }

    /**
     * 找 `git-credential-manager.exe`。
     *
     * 从 **git 自己的安装位置**推导，而不是硬编码 `C:\Program Files\Git` ——
     * 实测这台机器 git 装在 `D:/Git`：硬编码的话找不到，
     * 就会给用户推一个「去下载你已有的东西」。
     */
    fun findGcm(): String? = gcmCandidates().firstOrNull { it.isFile }?.absolutePath

    /**
     * 所有可能放着 `git-credential-manager.exe` 的位置，**按可能性排序**。
     *
     * 顺序有讲究：先看 git 自己的目录（绝大多数人走这条），
     * 再看我们**自己装过的那份**（用户上一次点了「自动安装」），
     * 最后才是各家的常见位置。
     */
    private fun gcmCandidates(): List<File> {
        val out = mutableListOf<File>()

        // ① 从 git 自己的安装位置推导 —— **不硬编码 C:\\Program Files\\Git**。
        //    实测这台机器 git 装在 D:/Git，硬编码会找不到，
        //    然后给用户推一个「去下载你已有的东西」。
        val execPath = runCatching {
            run(listOf("git", "--exec-path"), null)?.stdout?.trim().orEmpty()
        }.getOrDefault("")
        if (execPath.isNotEmpty()) {
            val mingw = File(execPath).parentFile?.parentFile      // .../mingw64
            mingw?.let { out.add(File(it, "bin/git-credential-manager.exe")) }
            mingw?.parentFile?.let { out.add(File(it, "mingw64/bin/git-credential-manager.exe")) }
        }

        // ② 我们上一轮「自动安装」放过的地方
        out.add(File(installedGcmDir(), "git-credential-manager.exe"))
        out.add(File(installedGcmDir(), "bin/git-credential-manager.exe"))

        // ③ 各家包管理器的常见落点
        out.add(File("C:/Program Files/Git/mingw64/bin/git-credential-manager.exe"))
        out.add(File("C:/Program Files (x86)/Git/mingw64/bin/git-credential-manager.exe"))
        System.getenv("LOCALAPPDATA")?.let {
            out.add(File("$it/Programs/Git/mingw64/bin/git-credential-manager.exe"))
            out.add(File("$it/Microsoft/WinGet/Links/git-credential-manager.exe"))
        }
        System.getenv("ProgramFiles")?.let {
            out.add(File("$it/Git Credential Manager/git-credential-manager.exe"))
        }
        return out
    }

    /** 我们自己装 GCM 时用的目录 */
    private fun installedGcmDir(): File =
        File(System.getProperty("user.home") ?: ".", ".zhixueyao/tools/gcm")

    /**
     * 自动安装 GCM。返回 (成功, 说明)。
     *
     * ## 两条路，先试标准的那条
     *
     * **① winget** —— 最标准，装到系统的位置，以后升级也方便。
     *    `winget install --id Git.GitCredentialManager -e`
     *
     * **② 下载便携版解压到用户目录** —— winget 不可用时兜底
     *    （精简版系统、老版本 Windows、或者被策略禁用）。
     *    装到 `~/.zhixueyao/tools/gcm/`，**不需要管理员权限**。
     *
     * ## 为什么不直接给下载链接
     *
     * 用户的原话：「可以检查是否安装，**如果没安装就安装**，如果安装了没配置就一键配置」。
     * 三段都要自动 —— 给链接让人自己下载、自己点安装向导，那不叫自动化。
     *
     * **而且工具自己装的东西放用户目录**：不污染系统、
     * 不需要管理员权限、卸载就是删目录。
     */
    fun installGcm(): Pair<Boolean, String> {
        // ---- ① winget ----
        val winget = run(listOf("winget", "--version"), null)
        if (winget?.ok == true) {
            val r = run(
                listOf(
                    "winget", "install", "--id", "Git.GitCredentialManager", "-e",
                    "--accept-source-agreements", "--accept-package-agreements",
                    "--disable-interactivity"
                ),
                null
            )
            if (r?.ok == true) {
                // 装完重新找一遍 —— **不要假设它装在哪个位置**
                findGcm()?.let { return true to "已通过 winget 安装：$it" }
                return true to "winget 报告安装成功，但没找到可执行文件。" +
                    "可能需要重开 IDE 让 PATH 生效。"
            }
            // winget 失败就继续走第 ② 条，不直接放弃
        }

        // ---- ② 便携版 ----
        return installGcmPortable()
    }

    /**
     * 下载 GCM 的便携版并解压到用户目录。
     *
     * 用 GitHub 的 `latest/download` 固定链接，**不写死版本号** ——
     * 写死的话过几个月链接就 404 了。
     */
    private fun installGcmPortable(): Pair<Boolean, String> {
        val dir = installedGcmDir()
        return try {
            dir.mkdirs()
            val zip = File(dir, "gcm.zip")
            val url = "https://github.com/git-ecosystem/git-credential-manager/" +
                "releases/latest/download/git-credential-manager-win-x64.zip"

            java.net.URI(url).toURL().openConnection().apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "zhixueyao-plugin")
            }.getInputStream().use { input ->
                zip.outputStream().use { input.copyTo(it) }
            }

            // 解压（zip 里是平铺的可执行文件，没有子目录）
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name      // 去掉可能的路径前缀
                    if (name.isNotEmpty() && !entry.isDirectory) {
                        File(dir, name).outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zip.delete()

            val exe = findGcm()
            if (exe != null) {
                true to "已下载并解压到：$dir\n\n可执行文件：$exe"
            } else {
                false to "解压完成，但没在 $dir 里找到 git-credential-manager.exe。"
            }
        } catch (e: Exception) {
            false to ("自动下载失败：" + (e.message ?: e.javaClass.simpleName) +
                "\n（这台机器可能连不上 GitHub —— 可以用 winget 手动装：" +
                " winget install Git.GitCredentialManager）")
        }
    }

    /**
     * 一键启用凭据管理器。返回 (成功, 说明)。
     *
     * 写配置时**指向找到的那个 exe 的目录**，而不是笼统的 "manager" ——
     * 如果 GCM 是我们装到用户目录的（不在 PATH 里），
     * 只写 `manager` 的话 git 根本找不到它。
     */
    fun enableGcm(): Pair<Boolean, String> {
        val exe = findGcm()
        val helper = if (exe != null) {
            // 用绝对路径形式：`!"<路径>"` （git 的语法，感叹号表示这是命令而不是 helper 名）
            "!\"$exe\""
        } else {
            "manager"
        }
        val r = run(listOf("git", "config", "--global", "credential.helper", helper), null)
        val ok = r?.ok == true
        return if (ok) {
            true to ("已启用（credential.helper = $helper）\n\n" +
                "以后第一次 push 会弹一次 GitHub 登录，之后凭据由系统保管 —— " +
                "你在自己终端里 push 也不用再输账号了。")
        } else {
            false to "写配置失败：" + (r?.stderr?.trim()?.take(120) ?: "命令没跑起来")
        }
    }

    /** 旧的实现（保留签名兼容），实际走上面那个 */

    /**
     * git 配置里有没有**残留的代理**。
     *
     * 这一项是这台机器的真实故障留下的：配置里写着 `http.proxy = 127.0.0.1:57567`，
     * 而那个代理早就没开了。报错是
     * `Failed to connect to github.com:443 over proxy 127.0.0.1` ——
     * **读起来像「网络不通」**，于是去查 VPN、查防火墙、查 DNS，全查一遍才发现是配置的问题。
     *
     * 所以这一项的价值不在「发现问题」，而在**把「网络不通」和「配置有问题」分开**。
     */
    private fun checkProxyResidue(projectDir: File?): Check {
        // **两处都要查。**
        //
        // 探针先报了「git 配置里没有代理」—— 而这个仓库里明明写着 127.0.0.1:57567。
        // 原因：它只在全局配置里找，而那条是**仓库级**的。
        //
        // 反过来也一样：只查仓库级会漏掉全局的。而设置页在**没打开工程**时也能用
        // （那时没有 projectDir 可查）。
        //
        // 所以两个层面各查一次 —— 报告时也说清是哪个层面的，
        // 因为「清掉」的动作对两者不一样。
        // **一次问完，别问四遍。**
        //
        // 原来为了区分「仓库级」和「全局」，对 http.proxy / https.proxy
        // 各查了两次（带 dir 和不带 dir）= **4 次进程启动**。
        // 每次最坏一个 TIMEOUT，加起来就是这一项独占了整个自检大半的时间。
        //
        // `git config --get-regexp` 一次就能把两个键都取出来，而且
        // `--show-scope` 直接把「这条是哪一级配的」告诉了我们 ——
        // 正好是原本要靠「查两遍」才推出来的信息。
        val cfg = run(
            listOf("git", "config", "--show-scope", "--get-regexp", "^https?\\.proxy$"),
            projectDir
        )
        val raw = takeRaw()

        var fromProject = ""
        var fromGlobal = ""
        cfg?.stdout?.lineSequence()?.forEach { line ->
            // 形如：`global  http.proxy http://127.0.0.1:7890`
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) return@forEach
            val scope = parts[0]
            val value = parts[2].trim()
            if (value.isEmpty()) return@forEach
            if (scope == "local") {
                if (fromProject.isEmpty()) fromProject = value
            } else if (fromGlobal.isEmpty()) {
                fromGlobal = value
            }
        }
        val raws = mutableListOf<String?>(raw)
        val scope = if (fromProject.isNotBlank()) "（仓库级）" else if (fromGlobal.isNotBlank()) "（全局）" else ""
        val p = fromProject.ifBlank { fromGlobal }
        if (p.isBlank()) {
            return Check("代理配置", Level.OK, "git 配置里没有代理",
                raw = raws.filterNotNull().joinToString("\n\n").ifBlank { null })
        }
        // 有代理配置 —— 试着连一下，能连上就是正常的
        val port = GitProxyDetector.parsePort(p)
        val alive = port != null && GitProxyDetector.probeHttpProxy(port, 400).ok
        return if (alive) {
            Check("代理配置", Level.OK, "$p$scope（探测正常）",
                raw = raws.filterNotNull().joinToString("\n\n").ifBlank { null })
        } else {
            Check(
                "代理配置", Level.WARN,
                "**配置里写着代理 `$p`$scope，但它连不上。** 这会让所有 git 命令都失败，" +
                    "而报错是「Failed to connect … over proxy」—— 看起来像网络不通，" +
                    "其实只是这个地址过期了。直连模式下插件会自动绕开它。",
                fixLabel = "清掉这两行配置", fixKind = FixKind.CLEAR_PROXY,
                fixCommand = "git config --global --unset http.proxy && " +
                    "git config --global --unset https.proxy",
                raw = raws.filterNotNull().joinToString("\n\n").ifBlank { null }
            )
        }
    }

    /**
     * 外网可达性 —— **这是整页自检里最有判断价值的一项**。
     *
     * ## 用户点破的判据
     *
     * > 「可以根据地区 IP 等去观察是否开启了，因为**国内是不能访问 github 或者外网的**」
     *
     * 他说得对，而且这比「有没有代理端口」**本质得多**：
     * 用户真正关心的不是「我的代理配置对不对」，而是「**我现在能不能上外网**」。
     *
     * ## 而原来这一项只报「直连 github.com:443 正常」，然后就不管了
     *
     * 问题在于**用户看不懂这句话是好是坏**：
     * 看到「直连」两个字，很容易理解成「没走加速器、有问题」——
     * 而实际恰恰相反：**能连上就说明通道通着**（不管是加速器接管还是本身能通）。
     *
     * ## 所以改成三分法，并直接给出「你该选什么」
     *
     * ```
     * 国内通 + 国外通  →  通道已生效 → 选「直连」就行，不用配代理
     * 国内通 + 国外不通 →  典型的「加速器没开」→ 要么开加速器，要么填代理端口
     * 国内也不通       →  网络本身有问题（断网 / 网卡 / 路由）
     * ```
     *
     * **多测一个国内站是关键** —— 只测 github 的话，
     * 「github 不通」既可能是「没开加速器」，也可能是「根本没联网」，
     * 而这两者的处置完全不同。
     */
    private fun checkReachability(): Check {
        val github = reachable("github.com", 443)
        val domestic = reachable("www.baidu.com", 443)

        return when {
            github && domestic -> Check(
                "外网可达性", Level.OK,
                "**能访问 github** —— 说明通道是通着的（加速器在接管，或者直连本身就能通）。\n" +
                    "这种情况下**选「直连」就可以**，不用再配代理 —— " +
                    "流量已经被系统层接管了，再走一层代理反而绕远。"
            )
            domestic && !github -> Check(
                "外网可达性", Level.WARN,
                "**国内能上，但连不上 github。** 这是典型的「加速器没开」的样子。\n" +
                    "两个办法：① 打开你的加速器（Clash / 速界这类，TUN 或系统代理都行）；" +
                    "② 或者在下面「访问方式」里填本机代理端口。",
                fixLabel = null, fixCommand = null
            )
            !domestic && !github -> Check(
                "外网可达性", Level.MISSING,
                "**连国内网站都连不上** —— 网络本身有问题（断网 / 网卡 / DNS），" +
                    "先把这个解决掉，再来管 git。",
                fixLabel = null, fixCommand = null
            )
            else -> Check("外网可达性", Level.WARN, "探测结果异常")
        }
    }

    /** 能不能连上某个 host:port */
    private fun reachable(host: String, port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), NET_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    // ---------------- 工具 ----------------

    /** 上一次执行的命令与结果 —— 给界面显示用。见 [Check.raw] 的注释 */
    private val lastRaw = ThreadLocal<String?>()

    /**
     * 把命令名换成绝对路径（如果能找到）。
     *
     * ## 为什么需要这个
     *
     * 用户报「自检检测不到用户名和邮箱」，而**同一套检查在我的 Git Bash 里是通的**。
     * 差别在环境：**IDE 进程的 PATH 常常和终端里的不一样** ——
     * 尤其是「装完 git 没重启过 IDE」或者「IDE 从某个精简的启动器起来」的情况。
     * 那时 `git` 这个词在 IDE 眼里根本不存在，而报错只有一句
     * 「无法启动命令」，**完全看不出是 PATH 的问题**。
     *
     * 所以这里主动去几个常见安装位置找一遍。找不到就原样返回，
     * 让上层如实报「找不到 git」——**兜底不是为了掩盖问题，是为了少一类无谓的失败**。
     */
    private fun resolve(exe: String): String {
        if (exe != "git") return exe
        val candidates = listOf(
            "C:////Program Files////Git////cmd////git.exe",
            "C:////Program Files (x86)////Git////cmd////git.exe",
            "C:////Program Files////Git////bin////git.exe",
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git\\cmd\\git.exe" },
            System.getenv("ProgramFiles")?.let { "$it\\Git\\cmd\\git.exe" },
        )
        for (c in candidates) {
            if (c != null && File(c).isFile) return c
        }
        return exe
    }

    private fun run(cmd: List<String>, dir: File?): com.zhixueyao.tools.ProcessRunner.Result? {
        val wd = dir ?: File(System.getProperty("user.home") ?: ".")
        val real = cmd.mapIndexed { i, a -> if (i == 0) resolve(a) else a }
        val r = runCatching {
            com.zhixueyao.tools.ProcessRunner.run(real, wd, null, TIMEOUT)
        }.getOrNull()
        lastRaw.set(
            "$ ${real.joinToString(" ")}" +
                "\n  工作目录：${wd.absolutePath}" +
                "\n  退出码：${r?.exitCode ?: "未执行"}" +
                (r?.let {
                    "\n  stdout：${it.stdout.trim().ifBlank { "（空）" }.take(200)}" +
                        (if (it.stderr.isNotBlank()) "\n  stderr：${it.stderr.trim().take(200)}" else "")
                } ?: "\n  （命令没能启动）")
        )
        return r
    }

    /** 取走上一次的原始信息（取完清掉，避免串到下一条检查上） */
    private fun takeRaw(): String? = lastRaw.get().also { lastRaw.set(null) }

    /** 一键清除代理配置。返回是否成功 */
    fun clearProxy(projectDir: File?): Boolean {
        var ok = true
        for (k in listOf("http.proxy", "https.proxy")) {
            val r = run(listOf("git", "config", "--global", "--unset", k), projectDir)
            // unset 一个不存在的 key 会返回非 0 —— 那不算失败
            if (r != null && r.exitCode != 0 && !r.stderr.contains("not found", true)
                && !r.stderr.contains("没有找到", true)
            ) ok = false
        }
        return ok
    }
}
