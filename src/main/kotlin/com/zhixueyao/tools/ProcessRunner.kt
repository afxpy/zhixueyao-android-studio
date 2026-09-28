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

        val pb = ProcessBuilder(command)
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

        val process = try {
            pb.start()
        } catch (e: Exception) {
            return Result(
                -1, "",
                "无法启动命令：${e.message}\n（可执行文件不存在或没有执行权限？）",
                durationMs = System.currentTimeMillis() - started
            )
        }

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
    private fun killTree(process: Process) {
        runCatching {
            process.descendants().forEach { runCatching { it.destroy() } }
            process.destroy()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach { runCatching { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }

    /** 开一个线程把流读干净（不读的话子进程会卡在写上） */
    private fun drain(
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