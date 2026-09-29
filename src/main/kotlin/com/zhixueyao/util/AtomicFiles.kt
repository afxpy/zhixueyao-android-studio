package com.zhixueyao.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子写文件：**先写临时文件，再原子改名**。
 *
 * ## 为什么值得单独抽一个工具
 *
 * 直接 `file.writeText(...)` 的问题是：**写到一半被打断，文件就是坏的**。
 * 强退 IDE、断电、进程被杀，都会留下一个截断的文件。
 *
 * 而坏了之后的后果往往特别重，因为读取端**基本都会 `runCatching` 兜底**
 * （这是对的 —— 不该因为一个坏文件就让整个功能崩），于是表现为：
 *
 * | 数据 | 坏掉的后果 |
 * |---|---|
 * | 会话（[com.zhixueyao.chat.SessionStore]） | 会话**静默消失**，连列表里都不出现 |
 * | 记忆（[com.zhixueyao.agent.MemoryStore]） | AI 悄悄忘掉用户明确要求记住的事 |
 * | 草稿 / 输入历史 | 打了一半的内容没了 |
 *
 * **没有错误提示，用户只会觉得「怎么又忘了」。**
 *
 * ## 这个模式工程里早就有，但没抽出来
 *
 * `FileSafety` 的备份恢复、`SessionStore.save` 都已经在用「临时文件 + `Files.move`」。
 * 每次都在原地重写一遍的话，**下一次有人加一处新写入，极大概率还是写 `writeText`**
 * —— 因为他不知道有这回事。
 *
 * 抽出来之后，这件事就有了一个**名字**：需要原子性时找 `AtomicFiles.write`。
 *
 * ## 实现上的两个细节
 *
 * 1. **`ATOMIC_MOVE` 失败要退回普通替换**：跨文件系统时原子改名会抛
 *    `AtomicMoveNotSupportedException`。这时普通 `REPLACE_EXISTING` 仍比直接写目标安全
 *    （少了原子性，但不是退化成裸写）。
 * 2. **失败时清掉临时文件**：不然会攒下一堆 `.tmp` 残骸，
 *    下次写入还可能撞上同名文件。
 */
object AtomicFiles {

    /**
     * 原子地把 [text] 写到 [target]。
     *
     * @return 成功与否。失败时目标文件**保持原样**（这是这个函数的核心承诺）。
     */
    fun write(target: File, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): Boolean {
        val dir = target.parentFile ?: return false
        runCatching { dir.mkdirs() }

        val tmp = File(dir, target.name + ".tmp")
        return try {
            tmp.writeText(text, charset)
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (e: Exception) {
            // 失败时把临时文件收掉 —— 留着它没有任何用处，还会攒成垃圾
            runCatching { tmp.delete() }
            false
        }
    }
}
