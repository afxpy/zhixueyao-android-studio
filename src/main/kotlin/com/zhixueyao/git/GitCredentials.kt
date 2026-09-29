package com.zhixueyao.git

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Git 账号凭据的存取 —— **走平台的加密存储，不落明文**。
 *
 * ## 为什么必须用 [PasswordSafe] 而不是普通配置项
 *
 * 这个工程里现有的 API 密钥是这么存的：
 *
 * ```kotlin
 * @State(...)
 * class ZhixueyaoSettings : PersistentStateComponent<ZhixueyaoSettings> {
 *     var apiKey: String = ""      // ← 明文写进 IDE 配置目录的 XML
 * }
 * ```
 *
 * 那是**明文**。任何能读用户配置目录的程序（另一个插件、被入侵的工具、
 * 同步到云端的配置备份）都能直接拿走。Git 的 token 权限通常比一个模型 key
 * 更大 —— **能改代码仓库**，所以更不能照抄这个做法。
 *
 * [PasswordSafe] 是平台提供的凭据存储，底下接的是操作系统的密钥链
 * （Windows 凭据管理器 / macOS Keychain / Linux 的 Secret Service），
 * **加密且按用户隔离**。
 *
 * ## 几个刻意的设计
 *
 * - **用户名走 [PasswordSafe] 一起存**：虽然用户名不算秘密，但把它和 token 放一起，
 *   存取就是一次调用、不会出现「token 换了用户名没换」的错配。
 * - **所有方法都不抛异常**：密钥链在某些环境（headless、远程桌面、
 *   企业策略禁用了凭据管理器）会不可用。这种情况下应该**安静地退化成「没存」**，
 *   而不是让整个设置页崩掉 —— 用户至少还能看到别的设置。
 * - **提供 [clear]**：用户想撤销授权时必须有办法真的删掉它。
 *   只覆盖而不提供删除，等于给了个删不掉的痕迹。
 */
object GitCredentials {

    /** 服务名。`generateServiceName` 会加上 IDE 前缀，避免和别的插件撞名 */
    private val attributes: CredentialAttributes by lazy {
        CredentialAttributes(generateServiceName("zhixueyao", "git"))
    }

    /**
     * 读回凭据。没有存过（或密钥链不可用）时返回 null。
     *
     * ## ⚠️ 这个调用**不能在 EDT 上做**
     *
     * 底下接的是操作系统的凭据管理器（Windows 凭据管理器 / macOS 钥匙串），
     * 那是一层**真实的 I/O** —— 而它在某些环境下会**长时间不返回**
     * （凭据管理器被企业策略禁用、服务没起来、需要弹窗确认）。
     *
     * 用户报的三个症状就是它引起的：
     *  - 「保存到加密存储」点了没反应 —— 就是在 EDT 上直接调了 [save]
     *  - 环境自检永远停在「正在检查…」—— 后台跑完了，但 `invokeLater`
     *    排在一个**被卡住的 EDT 后面**，永远轮不到
     *  - 「测试连接」同样
     *
     * **`runCatching` 只能挡异常，挡不住阻塞** —— 这是我原来漏掉的那一点。
     *
     * 所以：调用方必须放到后台线程（见设置页里的用法）。
     * 这里再加一层保险 —— [withTimeout] 保证最坏情况下也能返回。
     */
    fun load(): Pair<String, String>? = withTimeout {
        val c = PasswordSafe.instance.get(attributes) ?: return@withTimeout null
        val user = c.userName.orEmpty()
        val token = c.getPasswordAsString().orEmpty()
        if (user.isBlank() && token.isBlank()) null else user to token
    }

    /**
     * 给一次凭据存储操作套上时间上限。
     *
     * 超时了就**如实返回 null / false** —— 对调用方来说，
     * 「读不到凭据」和「读超时了」的处理是一样的（都当成没配），
     * 但**界面不会因此卡死**。
     */
    private fun <T> withTimeout(block: () -> T?): T? {
        var result: T? = null
        val t = Thread { runCatching { result = block() } }
        t.isDaemon = true
        t.start()
        t.join(TIMEOUT_MS)
        if (t.isAlive) {
            com.intellij.openapi.diagnostic.Logger.getInstance(GitCredentials::class.java)
                .warn("凭据存储操作超过 ${TIMEOUT_MS}ms 没返回，已放弃等待（界面不会被卡住）")
        }
        return result
    }

    /** 只读 token（用户名从设置里取，两边各自独立） */
    fun loadToken(): String = load()?.second.orEmpty()

    /**
     * 存凭据。
     *
     * @return 是否存进去了。false 表示密钥链不可用 —— 调用方应该**如实告诉用户**
     *   「没存上，下次还要重填」，而不是假装成功了。
     */
    fun save(userName: String, token: String): Boolean {
        if (userName.isBlank() && token.isBlank()) return clear()
        return withTimeout {
            runCatching {
                PasswordSafe.instance.set(attributes, Credentials(userName, token))
                true
            }.getOrDefault(false)
        } ?: false
    }

    /** 删掉。返回是否确实清掉了 */
    fun clear(): Boolean = withTimeout {
        runCatching {
            PasswordSafe.instance.set(attributes, null)
            true
        }.getOrDefault(false)
    } ?: false

    /** 超时上限。凭据管理器正常是毫秒级，给 3 秒已经很宽裕 */
    private const val TIMEOUT_MS = 3000L

    /** 有没有存过 —— 给界面显示状态用（**不返回 token 本身**） */
    fun hasToken(): Boolean = !loadToken().isBlank()
}