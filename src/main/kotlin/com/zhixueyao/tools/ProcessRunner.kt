package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用子进程执行器。
 *
 * ## 为什么要有它
 *
 * 在这之前工程里**没有任何统一的命令执行封装** —— 只有 `run_build` 走
 * IDE 自己的编译器（不经过子进程），MCP 的 stdio 传输自己写了一套
 * `ProcessBuilder`。于是「想新增一个跑命令的工具」就得从头写一遍
 * 超时、取消、读输出、防死锁这些麻烦事。
 *
 * 参考项目里这类能力都很自然：agents-universe 有 `shell` / `code_executor`，
 * insight-agents 有「沙箱定量计算」，LumeTerm 整个就是终端。
 * 我们这个插件过去只能「读代码、改代码、编译」，**不能算、不能查历史**，
 * 这是最大的一块空白。
 *
 * ## 三个必须处理好的点（都是踩过的坑）
 *
 * 1. **不能一次等满超时**。用户点「停止」时如果线程还阻塞在 `waitFor(120s)` 上，
 *    那个按钮等于没用 —— MCP 那边就吃过这个亏（最长等 2 分钟才停得下来）。
 *    这里用**分片等待**：每 100ms 醒一次看取消标志。
 * 2. **stdout / stderr 必须并发读**。只读一个流的话，另一个流的缓冲区写满，
 *    子进程就卡死在写上了（经典死锁：进程等我们读，我们等进程结束）。
 * 3. **输出要截断**。`git diff` 一个几千行的改动能到几十万字符，
 *    原样塞进上下文直接把预算烧光。按 [maxChars] 留头留尾。
 */
object ProcessRunner {

    /** 默认最长等待：够 git 操作和短脚本，超过这个时长说明它不该在这次调用里做 */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** 输出超过这个长度就截断（留头留尾）。60k 字符 ≈ 中文 2 万 token，已是很重的单条结果 */
    const val DEFAULT_MAX_CHARS = 60_000

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        /** 是否因超时被杀 */
        val timedOut: Boolean = false,
        /** 是否因用户取消被杀 */
        val cancelled: Boolean = false,
        val durationMs: Long = 0
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut && !cancelled

        /** 给模型看的合成输出：命令行的习惯是先看 stdout，出错时补 stderr */
        fun combined(maxChars: Int = DEFAULT_MAX_CHARS): String = buildString {
            if (stdout.isNotBlank()) append(truncate(stdout, maxChars))
            if (stderr.isNotBlank()) {
                if (stdout.isNotBlank()) append("\n\n--- stderr ---\n")
                append(truncate(stderr, maxChars / 2))
            }
            if (timedOut) append("\n\n（命令超时被终止）")
            if (cancelled) append("\n\n（被用户取消）")
        }.trim()

        private fun truncate(s: String, limit: Int): String =
            if (s.length <= limit) s else
                s.take(limit * 2 / 3) +
                    "\n\n……（中间省略 ${s.length - limit} 字符）……\n\n" +
                    s.takeLast(limit / 3)
    }

    /**
     * 跑一条命令。
     *
     * @param command 完整命令行（含可执行文件本身），**不走 shell**。
     *        不走 shell 是刻意的：拼接字符串再交给 shell 会引入引号/重定向/管道
     *        一整套注入面，而工具的参数本来就应该是结构化的。
     * @param workingDir 工作目录（一般是项目根）。
     * @param cancelFlag 用户的取消标志；没有就传 null。
     */
    fun run(
        command: List<String>,
        workingDir: File,
        cancelFlag: AtomicBoolean? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** 额外环境变量（比如把 git 的交互式提示关掉） */
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        if (command.isEmpty()) {
            return Result(-1, "", "命令为空", durationMs = 0)
        }
        val started = System.currentTimeMillis()

        val process = try {
            newBuilder(command, workingDir, extraEnv).start()
        } catch (e: Exception) {
            return Result(
                -1, "",
                "无法启动命令：${e.message}\n（可执行文件不存在或没有执行权限？）",
                durationMs = System.currentTimeMillis() - started
            )
        }
        return awaitAndCollect(process, cancelFlag, timeoutMs, started)
    }

    /**
     * 装配进程构造器。
     *
     * 抽出来是因为 [run] 和 [start] 都要用同一套环境变量 ——
     * 两份的话迟早会分叉（git 那三个变量只要漏一个，命令就会挂在交互提示上
     * 直到超时，而错误信息里完全看不出原因）。
     */
    private fun newBuilder(
        command: List<String>,
        workingDir: File,
        extraEnv: Map<String, String>
    ): ProcessBuilder =
        ProcessBuilder(command)
            .directory(workingDir)
            .apply {
                // 这几个是**必需的**，不是为了好看：
                //  - GIT_TERMINAL_PROMPT=0：否则 git 遇到要密码的场景会**挂在终端上等输入**，
                //    我们这边看不到任何输出，只能等超时（而且它还会占着凭据缓存）
                //  - GIT_PAGER / PAGER=cat：否则 git log/diff 会调分页器，
                //    同样挂住等按键
                //  - LC_ALL/LANG：固定成 UTF-8，避免中文输出变乱码
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_PAGER"] = "cat"
                environment()["PAGER"] = "cat"
                environment()["GIT_OPTIONAL_LOCKS"] = "0"
                environment()["LC_ALL"] = "C.UTF-8"
                environment()["LANG"] = "C.UTF-8"
                environment().putAll(extraEnv)
            }

    /**
     * 并发读两个流 + 分片等待 + 超时/取消。
     *
     * 从 [run] 里抽出来的：`start()` 走的是「不等待」那条路，
     * 但**读流这一步它一样要有**（不读的话子进程会卡在写上）——
     * 所以干脆把「起读流」也封进来，两条路共用。
     */
    private fun awaitAndCollect(
        process: Process,
        cancelFlag: AtomicBoolean?,
        timeoutMs: Long,
        started: Long
    ): Result {
        // 并发读两个流 —— 串行读会死锁（见类注释）
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val outThread = drain(process.inputStream, outBuf)
        val errThread = drain(process.errorStream, errBuf)
        // stdin 直接关掉：不然有些命令会等输入
        runCatching { process.outputStream.close() }

        var cancelled = false
        var timedOut = false
        val deadline = started + timeoutMs
        while (true) {
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) break
            if (cancelFlag?.get() == true) {
                cancelled = true
                killTree(process)
                break
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                killTree(process)
                break
            }
        }
        // 进程已退出，但读流的线程可能还没把最后一段刷完
        runCatching {
            outThread.join(500)
            errThread.join(500)
        }
        // 保险：进程被杀但 waitFor 已经返回时，exitCode 可能还没就绪
        val code = runCatching { if (process.isAlive) -1 else process.exitValue() }.getOrDefault(-1)

        return Result(
            exitCode = code,
            stdout = outBuf.toString().trim(),
            stderr = errBuf.toString().trim(),
            timedOut = timedOut,
            cancelled = cancelled,
            durationMs = System.currentTimeMillis() - started
        )
    }

    /**
     * 结束进程**和它的子进程**。
     *
     * 只 `destroy()` 父进程是不够的：`git` 会 fork 出 `git-remote-*`，
     * 脚本会 fork 出别的进程 —— 父进程没了它们还活着，
     * 继续占着终端/网络（用户点「停止」却发现还在跑）。
     * 先 `destroy()` 给它收尾的机会，等一小会儿再 `destroyForcibly()`。
     */
    internal fun killTree(process: Process) {
        // 实现搬到了 com.zhixueyao.util.ProcessTree —— 因为 MCP 传输那边也要杀进程树
        // （`npx` 起 MCP 服务器时，杀掉 `npx` 不等于杀掉真正的服务）。
        // **一份实现**：下次谁要调策略，只改一处。
        com.zhixueyao.util.ProcessTree.kill(process)
    }

    /** 开一个线程把流读干净（不读的话子进程会卡在写上） */
    internal fun drain(
        stream: java.io.InputStream,
        into: StringBuilder
    ): Thread = Thread {
        runCatching {
            // 用 UTF-8 读；IDE 环境的默认编码在 Windows 上常常是 GBK，会导致中文乱码。
            // 这里不追求完美（少数字节会替换成 ?），但不会整个输出变成乱码。
            java.io.InputStreamReader(stream, Charset.forName("UTF-8")).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    synchronized(into) { into.append(buf, 0, n) }
                }
            }
        }
    }.apply {
        isDaemon = true
        name = "zhixueyao-proc-drain"
        start()
    }

    // ---------------- 后台执行 ----------------

    /**
     * 起一个**不等它结束**的进程。
     *
     * ## 为什么需要这条路
     *
     * [run] 是阻塞的，这在「跑 gradle test」这种几十秒到几分钟的命令上会占满一整轮：
     * 模型只能干等，用户也只能干等。但很多命令**本来就不该等**：
     *
     * - 起一个开发服务器 / 日志跟踪（根本不会自己结束）
     * - 一次完整构建（几分钟，期间可以做别的分析）
     * - `gradlew --watch` 这类常驻任务
     *
     * 走后台之后模型可以「起 → 去干别的 → 回来收结果」，
     * 也可以在跑的同时回应用户。
     *
     * ## 与 [run] 的差别只有一点：**不等待**
     *
     * 读流、杀进程树、环境变量这些**完全共用**（[newBuilder] + [Handle]）——
     * 后台进程如果漏了「并发读流」，它会卡在写上永远不结束，
     * 而表现是「任务一直 running」，很难查。共用就没有这个风险。
     */
    fun start(
        command: List<String>,
        workingDir: File,
        extraEnv: Map<String, String> = emptyMap()
    ): Handle {
        val process = newBuilder(command, workingDir, extraEnv).start()
        val handle = Handle(process, command, workingDir)
        handle.startPumping()
        return handle
    }

    /**
     * 一个正在后台跑的进程的句柄。
     *
     * 输出**随跑随取**：读流线程一直在往缓冲区里追加，
     * [stdout]/[stderr] 拿的是「到目前为止」的内容 ——
     * 这正是「看它跑到哪了」需要的语义。
     */
    class Handle internal constructor(
        private val process: Process,
        val command: List<String>,
        val workingDir: File
    ) {
        private val outBuf = StringBuilder()
        private val errBuf = StringBuilder()

        /** 是否是被主动停掉的（用来和「自己失败退出」区分） */
        @Volatile
        var stopped: Boolean = false
            private set

        val startedAt: Long = System.currentTimeMillis()

        val pid: Long get() = runCatching { process.pid() }.getOrDefault(-1L)

        val alive: Boolean get() = process.isAlive

        /** 已经跑了多久（毫秒） */
        fun elapsedMs(): Long = System.currentTimeMillis() - startedAt

        /** 结束了才有退出码；还在跑返回 null */
        fun exitCode(): Int? =
            if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

        /**
         * 取「到目前为止」的输出。
         *
         * 加锁与 [drain] 里的写入用同一把锁（都是那个 StringBuilder 对象）——
         * 不加锁读的话，正好撞上 append 的中途会读到半截字符串
         * （StringBuilder 不是线程安全的，读到的是什么完全看运气）。
         */
        fun stdout(): String = synchronized(outBuf) { outBuf.toString() }

        fun stderr(): String = synchronized(errBuf) { errBuf.toString() }

        /** 叫停：杀进程**以及它的子孙**（理由见 [killTree]） */
        fun stop() {
            stopped = true
            killTree(process)
        }

        internal fun startPumping() {
            drain(process.inputStream, outBuf)
            drain(process.errorStream, errBuf)
            // stdin 关掉，否则有些命令会等着输入（同样是「一直 running」的经典成因）
            runCatching { process.outputStream.close() }
        }
    }

    // ---------------- 便捷入口 ----------------

    /**
     * 在项目根目录跑一条命令。
     *
     * 项目根取不到时返回失败结果，而不是抛异常 —— 工具层拿到统一结构更好处理。
     */
    fun runInProject(
        project: Project,
        command: List<String>,
        cancelFlag: AtomicBoolean? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result {
        val base = project.basePath
            ?: return Result(-1, "", "项目根目录未就绪，无法执行命令")
        return run(command, File(base), cancelFlag, timeoutMs)
    }
}