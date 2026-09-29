package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.agent.AgentUiBridge
import com.zhixueyao.media.SvgToVector
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/**
 * Android 资源目录的归类规则。
 *
 * 为什么不让模型自己填路径：它经常写成 `res/drawable/xxx.png` 而工程的实际模块
 * 叫 `app` 或 `core`（多模块工程尤其乱），填错了文件就落在没人引用的地方。
 * 这里按「找到的第一个 res 目录」自动归类，模型只说「我要一张 launcher 图标」。
 */
object AndroidRes {

    /** 密度目录后缀（按优先级从高到低找） */
    private val DENSITIES = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi")

    /**
     * 找模块的 `res` 目录。
     *
     * 优先 `<模块>/src/main/res`；找不到就退回任意深度下的第一个 `res` 目录。
     */
    fun resDir(project: Project): Path? {
        val root = project.basePath?.let { Paths.get(it) } ?: return null
        // ① 标准 Android 结构
        for (module in listOf("app", "")) {
            val p = if (module.isEmpty()) root.resolve("src/main/res") else root.resolve("$module/src/main/res")
            if (Files.isDirectory(p)) return p
        }
        // ② 多模块：任意一级下的 src/main/res
        return runCatching {
            Files.walk(root, 4).use { s ->
                s.filter { Files.isDirectory(it) }
                    .filter { it.endsWith("src/main/res") }
                    .findFirst().orElse(null)
            }
        }.getOrNull()
    }

    /** 图片落到哪个目录：launcher 图标进 mipmap（按密度），其余进 drawable */
    fun imageDir(project: Project, launcher: Boolean, density: String?): Path? {
        val res = resDir(project) ?: return null
        if (!launcher) return res.resolve("drawable").also { Files.createDirectories(it) }
        val d = density?.lowercase()?.takeIf { it in DENSITIES } ?: "xxhdpi"
        return res.resolve("mipmap-$d").also { Files.createDirectories(it) }
    }

    fun drawableDir(project: Project): Path? =
        resDir(project)?.resolve("drawable")?.also { Files.createDirectories(it) }

    fun rawDir(project: Project): Path? =
        resDir(project)?.resolve("raw")?.also { Files.createDirectories(it) }

    /** 视频/大文件进 assets（res/raw 会被资源表索引，视频放那儿不合适） */
    fun assetsDir(project: Project): Path? {
        val root = project.basePath?.let { Paths.get(it) } ?: return null
        val res = resDir(project)
        val moduleRoot = res?.parent?.parent?.parent   // res → main → src → 模块根
        val candidates = listOfNotNull(moduleRoot?.resolve("assets"), root.resolve("app/src/main/assets"), root.resolve("assets"))
        val hit = candidates.firstOrNull { Files.isDirectory(it) }
        return (hit ?: candidates.first()).also { Files.createDirectories(it) }
    }
}

/** 多媒体落盘的公共部分：大小上限、格式校验、覆盖前备份 */
private object MediaIo {

    const val MAX_SVG_BYTES = 512 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 64 * 1024 * 1024

    /** 按魔数判断真实格式 —— 不能只信扩展名，模型给的 base64 经常名不副实 */
    fun sniff(bytes: ByteArray): String? = when {
        bytes.size < 12 -> null
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> "mp4"
        bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte() -> "webm"
        else -> null
    }

    /** 写文件；覆盖前自动备份（复用 FileSafety，和 write_file 一套退路） */
    fun write(project: Project, target: Path, bytes: ByteArray): String? {
        val existed = Files.exists(target)
        val backed = if (existed) FileSafety.backup(project, target) else null
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return if (existed && backed == null) "（原文件备份失败，无法还原）" else null
    }

    fun rel(project: Project, p: Path): String =
        project.basePath?.let { base ->
            runCatching {
                Paths.get(base).toAbsolutePath().normalize()
                    .relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/')
            }.getOrNull()
        } ?: p.toString()

    /** 只允许字母数字下划线，避免路径穿越和非法资源名（R.drawable.xxx 要求全小写） */
    fun safeName(raw: String?, fallback: String): String {
        val cleaned = (raw ?: "").trim().substringAfterLast('/').substringBeforeLast('.')
            .lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        return cleaned.ifBlank { fallback }
    }
}

/**
 * 生成 SVG 矢量图。
 *
 * 默认**直接转成 VectorDrawable** 落到 `res/drawable/<name>.xml` ——
 * Android 项目要的就是这个，存一份 .svg 还得用户自己转，等于没做完。
 * 需要保留原始 SVG 时传 `keep_svg=true`。
 */
class GenerateSvgTool : AgentTool {
    override val name = "generate_svg"
    override val description =
        "生成矢量图（图标 / 矢量素材）。传入标准 SVG 源码，会自动转换成 Android 的 " +
            "VectorDrawable 并保存到 res/drawable/<name>.xml，可直接用 R.drawable.<name> 引用。" +
            "请写简洁的 SVG：只用 path/rect/circle/ellipse/line/polygon/g，颜色用 #RRGGBB，" +
            "viewBox 用 0 0 24 24 这类整数（渐变、文字、滤镜不支持）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "name" to jsonObj("type" to "string".toJson(), "description" to "资源名（小写字母数字下划线）"),
            "svg" to jsonObj("type" to "string".toJson(), "description" to "完整的 SVG 源码"),
            "keep_svg" to jsonObj("type" to "boolean".toJson(), "description" to "是否额外保留 .svg 原文件，默认 false")
        ),
        "required" to jsonArr("name".toJson(), "svg".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val svg = args.str("svg") ?: return ToolResult.error("缺少 svg 参数")
        if (svg.toByteArray(Charsets.UTF_8).size > MediaIo.MAX_SVG_BYTES) {
            return ToolResult.error("SVG 太大（超过 ${MediaIo.MAX_SVG_BYTES / 1024}KB），请简化图形")
        }
        val name = MediaIo.safeName(args.str("name"), "ic_generated")
        val dir = AndroidRes.drawableDir(project)
            ?: return ToolResult.error("项目里找不到 res 目录，无法归类资源。请确认这是个 Android 工程。")

        val result = SvgToVector.convert(svg)
        if (!result.ok) return ToolResult.error("SVG 转换失败：${result.warnings.firstOrNull() ?: "未知原因"}")

        val target = dir.resolve("$name.xml")
        val note = MediaIo.write(project, target, result.xml!!.toByteArray(Charsets.UTF_8))
            ?: ""
        // 保留 .svg 原文件时**写进会话临时目录，不写 res/drawable/** ——
        // 那里只该放「App 真正要用的资源」；一个只是留着看看的 .svg 混在资源目录里，
        // 既不是 App 需要的，又要用户自己去删（用户反馈过）。
        var extra = ""
        // 临时产物的**绝对路径**要一起回报给界面 —— 否则气泡里既没有卡片、
        // 也没有可点的预览入口，用户会以为「压根没生成图片」（用户反馈过）。
        val tempAttachments = mutableListOf<String>()
        if (args.bool("keep_svg") == true) {
            val ws = com.zhixueyao.agent.SessionWorkspace.currentFor(project)
            if (ws != null) {
                val svgTarget = ws.resolve("$name.svg")
                runCatching { svgTarget.writeBytes(svg.toByteArray(Charsets.UTF_8)) }
                    .onSuccess {
                        extra = "，SVG 原文件保存在会话临时目录：${svgTarget.absolutePath}"
                        AgentUiBridge.recordArtifact(project, svgTarget.toString())
                        tempAttachments.add(svgTarget.absolutePath)
                    }
                    .onFailure { extra = "（SVG 原文件写入临时目录失败：${it.message}）" }
            } else {
                extra = "（未取到会话临时目录，SVG 原文件未保存）"
            }
        }
        PathGuard.toVirtualFile(project, MediaIo.rel(project, target))
        AgentUiBridge.recordArtifact(project, target.toString())

        val warn = if (result.warnings.isEmpty()) "" else
            "\n注意：" + result.warnings.distinct().take(3).joinToString("；")
        return ToolResult(
            "已生成 ${MediaIo.rel(project, target)}$extra$note\n" +
                "尺寸 ${result.widthDp}×${result.heightDp}dp，代码里用 R.drawable.$name 引用。$warn",
            attachments = listOf(MediaIo.rel(project, target)) + tempAttachments
        )
    }
}

/**
 * 把项目里已有的 SVG 转成 VectorDrawable。
 *
 * 和 [GenerateSvgTool] 的区别：那个是「模型刚生成的 SVG 直接落盘」，
 * 这个是「工程里已经有 .svg（设计师给的、下载的），帮我转一下」。
 */
class ExportDrawableTool : AgentTool {
    override val name = "export_drawable"
    override val description =
        "把项目里已有的 .svg 文件转换成 Android VectorDrawable（res/drawable/<name>.xml）。" +
            "也支持直接传 svg 源码。转换不了的元素会在结果里说明。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "path" to jsonObj("type" to "string".toJson(), "description" to "项目内的 .svg 文件路径"),
            "svg" to jsonObj("type" to "string".toJson(), "description" to "或直接给 SVG 源码"),
            "name" to jsonObj("type" to "string".toJson(), "description" to "输出的资源名，默认沿用原文件名")
        ),
        "required" to jsonArr()
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val rawPath = args.str("path")
        val inline = args.str("svg")
        val svg: String
        val fallbackName: String
        if (!inline.isNullOrBlank()) {
            svg = inline
            fallbackName = "ic_exported"
        } else if (!rawPath.isNullOrBlank()) {
            val path = try {
                PathGuard.resolve(project, rawPath)
            } catch (e: Exception) {
                return ToolResult.error(e.message ?: "路径无效")
            }
            if (!Files.exists(path)) return ToolResult.error("文件不存在：$rawPath")
            if (Files.size(path) > MediaIo.MAX_SVG_BYTES) {
                return ToolResult.error("SVG 太大（超过 ${MediaIo.MAX_SVG_BYTES / 1024}KB）")
            }
            svg = runCatching { Files.readString(path) }.getOrElse {
                return ToolResult.error("读不出来：${it.message}")
            }
            fallbackName = path.fileName.toString().substringBeforeLast('.')
        } else {
            return ToolResult.error("需要 path（项目内的 .svg）或 svg（源码）其中之一")
        }

        val name = MediaIo.safeName(args.str("name") ?: fallbackName, "ic_exported")
        val dir = AndroidRes.drawableDir(project)
            ?: return ToolResult.error("项目里找不到 res 目录")

        val result = SvgToVector.convert(svg)
        if (!result.ok) return ToolResult.error("转换失败：${result.warnings.firstOrNull() ?: "未知原因"}")

        val target = dir.resolve("$name.xml")
        val note = MediaIo.write(project, target, result.xml!!.toByteArray(Charsets.UTF_8)) ?: ""
        PathGuard.toVirtualFile(project, MediaIo.rel(project, target))
        AgentUiBridge.recordArtifact(project, target.toString())

        val warn = if (result.warnings.isEmpty()) "" else
            "\n未能转换的部分：" + result.warnings.distinct().joinToString("；")
        return ToolResult(
            "已导出 ${MediaIo.rel(project, target)}$note\n用 R.drawable.$name 引用。$warn",
            attachments = listOf(MediaIo.rel(project, target))
        )
    }
}

/**
 * 把生成好的位图 / 视频落盘到 Android 资源目录。
 *
 * 为什么需要它：图/视频**不是模型能直接产出的**（它只吐文本），
 * 真正的来源是 MCP 的文生图工具、或者用户在别处生成好的文件。
 * 这个工具负责把「已经拿到的二进制」按 Android 规范放对位置并校验合法性 ——
 * 这是整条链路上唯一需要写代码的一环。
 */
class SaveAssetTool : AgentTool {
    override val name = "save_asset"
    override val description =
        "把图片/视频保存到 Android 资源目录（自动归类）。内容来源二选一：" +
            "base64（不含 data: 前缀）或项目内已有的文件路径。" +
            "launcher 图标传 launcher=true 会进 mipmap-<密度>，其余进 res/drawable；" +
            "视频进 assets。会校验真实格式（按魔数，不信扩展名）。"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "name" to jsonObj("type" to "string".toJson(), "description" to "资源名（小写字母数字下划线）"),
            "base64" to jsonObj("type" to "string".toJson(), "description" to "文件内容的 base64（不含 data: 前缀）"),
            "source_path" to jsonObj("type" to "string".toJson(), "description" to "或项目内已有文件的路径"),
            "launcher" to jsonObj("type" to "boolean".toJson(), "description" to "是否 launcher 图标（进 mipmap）"),
            "density" to jsonObj("type" to "string".toJson(), "description" to "mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi，默认 xxhdpi"),
            "temp" to jsonObj(
                "type" to "boolean".toJson(),
                "description" to "true = 存到会话临时目录（试验性产出用这个），" +
                    "false/不传 = 作为 App 资源写进 res/。判断标准：这是 App 要用的，还是看着玩的？"
            )
        ),
        "required" to jsonArr("name".toJson())
    )

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val name = MediaIo.safeName(args.str("name"), "asset")
        val b64 = args.str("base64")
        val srcPath = args.str("source_path")
        if (b64.isNullOrBlank() && srcPath.isNullOrBlank()) {
            return ToolResult.error("需要 base64 或 source_path 其中之一")
        }

        // ---- 取字节 ----
        val bytes: ByteArray = if (!b64.isNullOrBlank()) {
            val clean = b64.trim().removePrefix("data:").substringAfter(",", b64.trim())
            runCatching { Base64.getMimeDecoder().decode(clean) }.getOrElse {
                return ToolResult.error("base64 解码失败：${it.message}")
            }
        } else {
            val src = try {
                PathGuard.resolve(project, srcPath!!)
            } catch (e: Exception) {
                return ToolResult.error(e.message ?: "路径无效")
            }
            if (!Files.exists(src)) return ToolResult.error("源文件不存在：$srcPath")
            if (Files.size(src) > MediaIo.MAX_VIDEO_BYTES) {
                return ToolResult.error("源文件超过 ${MediaIo.MAX_VIDEO_BYTES / 1024 / 1024}MB 上限")
            }
            runCatching { Files.readAllBytes(src) }.getOrElse {
                return ToolResult.error("读不出来：${it.message}")
            }
        }

        // ---- 校验 ----
        val format = MediaIo.sniff(bytes)
            ?: return ToolResult.error(
                "内容不是可识别的图片/视频（支持 PNG / JPG / WebP / GIF / MP4 / WebM）。" +
                    "如果这是别的东西，请用 write_file。"
            )
        val isVideo = format == "mp4" || format == "webm"
        val limit = if (isVideo) MediaIo.MAX_VIDEO_BYTES else MediaIo.MAX_IMAGE_BYTES
        if (bytes.size > limit) {
            return ToolResult.error("文件 ${bytes.size / 1024}KB 超过上限 ${limit / 1024}KB，已拒绝写入")
        }

        // ---- 归类 ----
        // temp=true：试验性产出，进会话临时目录，**不碰用户工程**
        val toTemp = args.bool("temp") == true
        if (toTemp) {
            val ws = com.zhixueyao.agent.SessionWorkspace.currentFor(project)
                ?: return ToolResult.error("取不到会话临时目录，暂时无法保存；可以先存进工程再说明")
            val target = java.io.File(ws, "$name.$format")
            return runCatching {
                target.writeBytes(bytes)
                AgentUiBridge.recordArtifact(project, target.toString())
                ToolResult(
                    "已保存到会话临时目录：${target.absolutePath}\n" +
                        "格式 $format，${bytes.size / 1024}KB。这是**试验性产出**，" +
                        "没有写进工程；确认要进 App 再用 temp=false 存到 res/。",
                    // 绝对路径 —— 界面上据此挂预览卡片（临时产物不在工程内，
                    // 用不了「工程相对路径」那套）
                    attachments = listOf(target.absolutePath)
                )
            }.getOrElse { ToolResult.error("写入失败：${it.message}") }
        }

        val dir = if (isVideo) {
            AndroidRes.assetsDir(project)
        } else {
            AndroidRes.imageDir(project, args.bool("launcher") == true, args.str("density"))
        } ?: return ToolResult.error("项目里找不到合适的资源目录，请确认这是个 Android 工程")

        val target = dir.resolve("$name.$format")
        val note = MediaIo.write(project, target, bytes) ?: ""
        PathGuard.toVirtualFile(project, MediaIo.rel(project, target))
        AgentUiBridge.recordArtifact(project, target.toString())

        val usage = when {
            isVideo -> "放在 assets/ 下，用 \"$name.$format\" 作为 asset 名读取"
            args.bool("launcher") == true -> "用 R.mipmap.$name 引用"
            else -> "用 R.drawable.$name 引用"
        }
        return ToolResult(
            "已保存 ${MediaIo.rel(project, target)}$note\n" +
                "格式 $format，${bytes.size / 1024}KB。$usage",
            attachments = listOf(MediaIo.rel(project, target))
        )
    }
}
