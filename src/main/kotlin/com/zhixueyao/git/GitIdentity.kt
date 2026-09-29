package com.zhixueyao.git

import java.io.File

/**
 * 读写 git 的**提交署名**（`user.name` / `user.email`）。
 *
 * ## 为什么要有这个类 —— 它取代了两个输入框
 *
 * 我原本在设置页放了「署名 / 邮箱」两个输入框让用户填。用户回了一句：
 *
 * > 「如果不需要提交署名的话就去除吧，因为我看我们本地提交到存储库里面
 * >  都是要填写 github 所填写的名称和 github 注册邮箱」
 *
 * **他说得对，而且理由比「多余」更硬：绝大多数人早就配过了** ——
 * 装 Git 的第一步、或者第一次提交时 git 自己就会要求配。再问一遍是重复劳动，
 * 而且**用户填的和 git 里已有的可能不一致**，那会让提交历史出现两个身份。
 *
 * 所以改成**读现状**：
 *  - 配好了 → 显示出来，用户不用管
 *  - 没配 → 才提示（否则 AI 提交会失败，而用户不知道原因）
 *
 * ## 为什么读的是**全局**配置
 *
 * `git config user.name`（不带 --global）会先查仓库级、再回落到全局 ——
 * 那正是提交时实际生效的那个，所以显示它最准。
 * 但**写**的时候写全局：写进某个仓库的 `.git/config` 是给那个仓库打补丁，
 * 而用户想要的是「以后都别再问了」。
 */
object GitIdentity {

    private const val TIMEOUT = 4000L

    /** 当前生效的 user.name（仓库级优先，回落全局）。没配返回空串 */
    fun currentName(): String = read("user.name")

    /** 当前生效的 user.email。没配返回空串 */
    fun currentEmail(): String = read("user.email")

    /**
     * 读一个 git 配置项。
     *
     * 用 `git config --get` 而不是自己去解析 `.gitconfig` 文本：
     * 配置文件有多层（系统级 / 全局 / 仓库级）、还有 includeIf 这种条件包含，
     * **自己解析一定会漏**。让 git 自己回答，答案就是提交时真正会用的那个。
     */
    private fun read(key: String): String = runCatching {
        val r = com.zhixueyao.tools.ProcessRunner.run(
            listOf("git", "config", "--get", key),
            File(System.getProperty("user.home") ?: "."),
            null,
            TIMEOUT
        )
        if (r.ok) r.stdout.trim() else ""
    }.getOrDefault("")

    /**
     * 写进**全局**配置。
     *
     * @return 是否成功。失败时调用方应如实告诉用户 —— 而不是假装设好了。
     */
    fun setGlobal(name: String, email: String): Boolean = runCatching {
        var ok = true
        if (name.isNotBlank()) {
            ok = ok && com.zhixueyao.tools.ProcessRunner.run(
                listOf("git", "config", "--global", "user.name", name),
                File(System.getProperty("user.home") ?: "."), null, TIMEOUT
            ).exitCode == 0
        }
        if (email.isNotBlank()) {
            ok = ok && com.zhixueyao.tools.ProcessRunner.run(
                listOf("git", "config", "--global", "user.email", email),
                File(System.getProperty("user.home") ?: "."), null, TIMEOUT
            ).exitCode == 0
        }
        ok
    }.getOrDefault(false)
}
