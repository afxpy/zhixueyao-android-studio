package com.zhixueyao.util

import java.util.concurrent.TimeUnit

/**
 * 结束一个进程**以及它拉起来的子孙进程**。
 *
 * ## 为什么必须单独做这件事，不能只 `destroy()`
 *
 * 我们在两个地方要杀子进程：
 *
 * | 场景 | 命令 | 谁拉起了谁 |
 * |---|---|---|
 * | 跑命令 | `git`、`gradlew`、用户脚本 | `git` 会 fork `git-remote-*`；脚本会 fork 别的 |
 * | MCP 服务器 | `npx -y @xxx/mcp-server` | **`npx` 只是一层壳，真正的服务是它拉起来的子进程** |
 *
 * 只 `destroy()` 父进程的后果是**父进程没了，子进程还活着**：
 *
 * - 继续占着端口（下次启动报「端口被占用」）
 * - 继续占着内存和句柄
 * - 用户点了「断开」，进程列表里却还能看到它 —— 而且**再也不会被回收**
 *
 * MCP 那一侧尤其明显：目录里所有服务器都是 `npx` 起的，
 * 而 `npx` 在 Windows 上还是个 `.cmd` 包装 —— **杀掉包装，node 照跑**。
 *
 * ## 为什么抽成一份共用实现
 *
 * 这段逻辑原先只在 `ProcessRunner` 里（私有）。MCP 那边自己写了个
 * `destroy() + waitFor + destroyForcibly()`，**漏了「杀子孙」这一步**。
 *
 * 两份实现分叉这件事，这个工程里已经吃过一次亏（代码块的两套解析）。
 * 所以这里不复制，改成共用 —— **下次谁要改杀进程的策略，只有一处要改。**
 *
 * ## 策略
 *
 * 1. 先给所有子孙 + 自己发 `destroy()`（**给收尾的机会**，不是上来就一刀）
 * 2. 等 [graceMillis]；还没死就 `destroyForcibly()`
 *
 * 顺序上先子孙后自己：反过来的话父进程先死了，`descendants()` 可能就取不到它们了。
 */
object ProcessTree {

    /** 默认优雅退出的等待时间 */
    const val DEFAULT_GRACE_MS = 1500L

    /**
     * 杀掉 [process] 及其所有子孙。
     *
     * 不会抛异常 —— 清理路径上抛异常只会掩盖真正的问题
     * （而且调用方多半在 `close()` / `finally` 里，那儿也不该再往外抛）。
     */
    fun kill(process: Process?, graceMillis: Long = DEFAULT_GRACE_MS) {
        if (process == null) return

        // 先留一份子孙快照：等到父进程退出之后再去问，可能已经问不到了
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())

        runCatching { descendants.forEach { it.destroy() } }
        runCatching { process.destroy() }

        // 给它们一点时间自己收尾；没死透再强杀
        val exited = runCatching { process.waitFor(graceMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(true)
        if (exited) return

        runCatching { descendants.forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }

    /**
     * 判断 [process] 是否还活着；[process] 为 null 时算「已结束」。
     *
     * 单独抽出来的原因：`process?.isAlive == true` 这种写法在 Kotlin 里
     * 后面紧跟着 `process!!` 会被读成「这里可能为 null」，
     * 而实际上前面已经判过了。用一次函数把判断收进来，
     * **调用点就不用再写 `!!`** —— 也就不会有人去怀疑那个 `!!` 到底安不安全。
     */
    fun isAlive(process: Process?): Boolean =
        process != null && runCatching { process.isAlive }.getOrDefault(false)
}
