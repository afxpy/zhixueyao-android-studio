package com.zhixueyao.tools

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 改文件前的备份 / 删除时的回收站。
 *
 * **为什么不按「同目录 .bak」「项目内 .recycle」来放**：那会把 `.bak` 散落到工程各处、
 * 把回收站混进源码树 —— 交付出去的工程就不干净了。统一放到 IDE 的 system 目录下、
 * 按项目分目录，项目里一个多余文件都不留（目录结构仍按相对路径保留，便于人工翻）。
 *
 * 位置：`<IDE system>/zhixueyao/recovery/<项目名>-<路径哈希>/`
 *  - `backup/`  每个文件只留**最近 1 个**版本，覆盖写之前存
 *  - `recycle/` 软删除的文件，按时间戳分目录，**7 天**后自动清理
 *
 * 全部操作都是「尽力而为」：备份失败**不会**阻断写入（否则一个只读的临时目录
 * 就能让 AI 完全没法改代码），只在工具返回里带一句提示。
 */
object FileSafety {

    /** 回收站保留天数 */
    const val RECYCLE_KEEP_DAYS = 7L

    /** 项目根目录，取不到就不做任何事 */
    private fun rootOf(project: Project): Path? =
        project.basePath?.let { Paths.get(it) }

    /** 相对路径（用 / 统一分隔，Windows 上也能跨平台还原） */
    private fun relOf(root: Path, path: Path): String? = runCatching {
        root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
            .toString().replace('\\', '/')
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }

    private fun projectDir(project: Project): Path? {
        val root = rootOf(project) ?: return null
        val key = root.toAbsolutePath().normalize().toString()
        // 名字里带上项目名，人翻的时候一眼能认出来；哈希保证不撞
        val hash = Integer.toHexString(key.hashCode())
        val name = (root.fileName?.toString() ?: "project").take(40)
        return Paths.get(PathManager.getSystemPath(), "zhixueyao", "recovery", "$name-$hash")
    }

    // ---------------- 备份 ----------------

    /**
     * 覆盖写之前存一份原文件。**每个文件只保留最近 1 个版本** ——
     * 留多了会无限增长，而「刚刚改坏之前是什么样」才是真正要救的场景。
     *
     * @return 备份文件路径；没备份（文件不存在 / 失败）返回 null
     */
    fun backup(project: Project, path: Path): Path? {
        val root = rootOf(project) ?: return null
        if (!Files.isRegularFile(path)) return null
        val rel = relOf(root, path) ?: return null
        return runCatching {
            val dest = projectDir(project)!!.resolve("backup").resolve(rel)
            Files.createDirectories(dest.parent)
            Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
            dest
        }.getOrNull()
    }

    /**
     * 把某个文件的最近一次备份还原回去。
     *
     * @param relPath 项目内的相对路径（用 / 分隔）
     */
    fun restore(project: Project, relPath: String): Boolean {
        val root = rootOf(project) ?: return false
        return runCatching {
            val src = projectDir(project)!!.resolve("backup").resolve(relPath)
            if (!Files.isRegularFile(src)) return false
            val dest = root.resolve(relPath)
            Files.createDirectories(dest.parent)
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }

    /** 有备份的文件清单（相对路径），供还原工具提示用 */
    fun listBackups(project: Project): List<String> {
        val dir = projectDir(project)?.resolve("backup") ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val base = dir.toAbsolutePath().normalize()
        return runCatching {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { base.relativize(it.toAbsolutePath().normalize()).toString().replace('\\', '/') }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 回收站 ----------------

    /**
     * 软删除：把文件移进回收站，**不做物理删除**。
     *
     * @return 回收站里的落点；失败返回 null
     */
    fun recycle(project: Project, path: Path): Path? {
        val root = rootOf(project) ?: return null
        val rel = relOf(root, path) ?: return null
        return runCatching {
            val stamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(':', '-')
            val dest = projectDir(project)!!.resolve("recycle").resolve(stamp).resolve(rel)
            Files.createDirectories(dest.parent)
            Files.move(path, dest, StandardCopyOption.REPLACE_EXISTING)
            cleanupRecycle(project)
            dest
        }.getOrNull()
    }

    /** 清理超过 [RECYCLE_KEEP_DAYS] 天的回收站内容。每次软删除顺手做一次，不另开定时器 */
    fun cleanupRecycle(project: Project) {
        val dir = projectDir(project)?.resolve("recycle") ?: return
        if (!Files.isDirectory(dir)) return
        val deadline = Instant.now().minus(RECYCLE_KEEP_DAYS, ChronoUnit.DAYS)
        runCatching {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { runCatching { Files.getLastModifiedTime(it).toInstant() }.getOrDefault(Instant.now()) .isBefore(deadline) }
                    .forEach { old -> runCatching { Files.walk(old).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } } }
            }
        }
    }

    /** 回收站里现有的条目（`时间戳/相对路径`） */
    fun listRecycled(project: Project): List<String> {
        val dir = projectDir(project)?.resolve("recycle") ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val out = mutableListOf<String>()
        // 用显式循环而不是嵌套流：嵌套 flatMap 里再开 walk，类型推断和资源关闭都容易出错
        runCatching {
            Files.list(dir).use { stamps ->
                for (stamp in stamps) {
                    if (!Files.isDirectory(stamp)) continue
                    val base = stamp.toAbsolutePath().normalize()
                    val stampName = stamp.fileName.toString()
                    Files.walk(stamp).use { w ->
                        for (f in w) {
                            if (!Files.isRegularFile(f)) continue
                            out.add(stampName + "/" + base.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                        }
                    }
                }
            }
        }
        return out
    }

    /** 备份/回收站的根目录，供设置页显示「东西放哪了」 */
    fun recoveryRoot(project: Project): String? = projectDir(project)?.toString()
}
