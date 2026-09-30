package com.zhixueyao.tools

import com.intellij.openapi.project.Project
import com.zhixueyao.util.Json
import com.zhixueyao.util.jsonArr
import com.zhixueyao.util.jsonObj
import com.zhixueyao.util.toJson
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跑测试。
 *
 * ## 为什么单独做一个工具，而不是让模型自己 `run_script`
 *
 * 因为它**不能只是「跑个命令」**。测试输出有两个特点，必须专门处理：
 *
 * 1. **成功时几乎全是噪音** —— Gradle 跑一轮单元测试的输出能有几千行，
 *    而结论就一句「BUILD SUCCESSFUL」。整段塞进上下文等于白烧几千 token。
 * 2. **失败时关键信息埋在中间** —— 哪个用例挂了、为什么挂、在哪一行，
 *    散在几十行日志里，而前后都是无关的编译/任务进度输出。
 *
 * 所以这个工具的核心不是「执行」，是 [summarize]：把输出压成
 * 「几个通过、几个失败、每个失败的原因」。那一小段才是模型要看的。
 *
 * ## 为什么默认跑单元测试而不是全部
 *
 * Android 的 `connectedAndroidTest` 需要**连着设备或模拟器**才跑得动。
 * 默认跑它的话，没插设备的用户每次调用都会等一个必然失败的长时间超时。
 * 所以默认 `testDebugUnitTest`（纯 JVM，随时可跑），
 * 要跑设备测试必须显式指定 —— 让它是个**有意识的选择**。
 */
class RunTestsTool : AgentTool {

    override val name = "run_tests"

    override val parameters: Json.Obj = jsonObj(
        "type" to "object".toJson(),
        "properties" to jsonObj(
            "filter" to jsonObj(
                "type" to "string".toJson(),
                "description" to "只跑匹配的测试类/方法（Gradle 的 --tests 语法），" +
                    "例如 com.foo.BarTest 或 *BarTest.testXxx。不填就跑全部单元测试"
            ),
            "kind" to jsonObj(
                "type" to "string".toJson(),
                "enum" to jsonArr("unit", "instrumented"),
                "description" to "unit=本机 JVM 单元测试（默认，随时可跑）；" +
                    "instrumented=设备/模拟器上的测试，**需要已连接设备**，否则必然失败"
            ),
            "timeout_seconds" to jsonObj(
                "type" to "integer".toJson(),
                "description" to "最长等待秒数，默认 300。测试通常比编译慢"
            )
        )
    )

    override val description: String
        get() = "跑项目的测试。**改完代码后验证「有没有把别的功能改坏」就用它** —— " +
            "编译通过只说明类型对，不说明行为对。" +
            "默认跑本机单元测试；要跑设备测试请显式指定 kind=instrumented（需要连着设备）。" +
            "返回的是**失败摘要**而不是完整日志，所以直接看返回值就行。"

    override fun execute(project: Project, args: Json.Obj): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult.error("拿不到项目路径，无法跑测试。")
        val root = File(basePath)

        val kind = args.str("kind")?.trim()?.lowercase() ?: "unit"
        val filter = args.str("filter")?.trim().orEmpty()
        val timeout = (args.int("timeout_seconds") ?: 300).coerceIn(30, 1800)

        val plan = detect(root, kind)
            ?: return ToolResult.error(
                "在 $basePath 下没找到认识的测试入口。" +
                    "目前支持：Gradle 项目（有 gradlew）、Python（有 pytest 配置或 tests/）、" +
                    "Node（package.json 里有 test 脚本）。" +
                    "如果是别的构建系统，用 run_script 直接跑测试命令。"
            )

        val command = buildCommand(root, plan, kind, filter)

        val result = ProcessRunner.run(
            command = command,
            workingDir = root,
            cancelFlag = null,
            timeoutMs = timeout * 1000L
        )

        val summary = summarize(result.stdout, result.stderr, result.exitCode, result.timedOut)

        val head = buildString {
            append("执行：").append(plan.displayName)
                .append(if (filter.isBlank()) "" else "　筛选：$filter").append('\n')
            append("命令：").append(command.joinToString(" ")).append('\n')
            append("耗时：").append(result.durationMs / 1000).append("s　")
            append("退出码：").append(result.exitCode).append('\n')
            plan.warning?.let { append("\n注意：").append(it).append('\n') }
            append('\n')
        }

        return ToolResult(
            text = head + summary,
            ok = result.ok
        )
    }

    /** 认出来的测试入口 */
    internal data class Plan(
        val kind: String,
        val displayName: String,
        /** 相对项目根的可执行文件（Gradle 用 wrapper） */
        val launcher: List<String> = emptyList(),
        val warning: String? = null
    )

    /**
     * 认构建系统。
     *
     * 顺序有讲究：**先看 Gradle** —— 一个 Android 项目里同时有 `package.json`
     * （前端脚本、文档工具）很常见，先认 Node 会认错。
     */
    internal fun detect(root: File, kind: String): Plan? {
        val gradlew = if (isWindows()) File(root, "gradlew.bat") else File(root, "gradlew")
        if (gradlew.isFile) {
            val isAndroid = File(root, "app/build.gradle").isFile ||
                File(root, "app/build.gradle.kts").isFile ||
                File(root, "settings.gradle.kts").readTextSafe().contains("com.android.application") ||
                File(root, "settings.gradle").readTextSafe().contains("com.android.application")
            val task = if (kind == "instrumented") {
                if (isAndroid) "connectedAndroidTest" else "test"
            } else {
                if (isAndroid) "testDebugUnitTest" else "test"
            }
            return Plan(
                kind = kind,
                displayName = "Gradle　$task",
                launcher = listOf(gradlew.absolutePath),
                warning = if (kind == "instrumented") {
                    "设备测试需要已连接真机或模拟器，没有设备时这轮会跑很久然后失败。"
                } else null
            )
        }
        if (kind == "instrumented") {
            // 非 Gradle 项目没有「设备测试」这个概念，别悄悄跑成别的
            return null
        }
        if (File(root, "pytest.ini").isFile || File(root, "pyproject.toml").isFile ||
            File(root, "tests").isDirectory
        ) {
            return Plan("unit", "pytest", launcher = listOf("python", "-m", "pytest"))
        }
        val pkg = File(root, "package.json")
        if (pkg.isFile && pkg.readTextSafe().contains("\"test\"")) {
            return Plan("unit", "npm test", launcher = listOf(npmCmd(), "test"))
        }
        return null
    }

    private fun buildCommand(root: File, plan: Plan, kind: String, filter: String): List<String> =
        buildList {
            addAll(plan.launcher)
            when {
                plan.displayName.startsWith("Gradle") -> {
                    val task = plan.displayName.substringAfterLast(' ')
                    add(task)
                    // --offline 不加：测试可能需要下载依赖，离线会直接失败。
                    // 但 --console=plain 要加 —— 否则 Gradle 会输出带光标控制字符的
                    // 进度条，那些字符会污染摘要（而且占大量字符数）
                    add("--console=plain")
                    if (filter.isNotBlank()) {
                        add("--tests")
                        add(filter)
                    }
                }
                plan.displayName == "pytest" -> {
                    // 注意 `add("-k", filter)` 是 list.add(index, element) 那个重载，
                    // 不是「加两个元素」—— 会报类型不匹配。要分两次 add。
                    if (filter.isNotBlank()) {
                        add("-k")
                        add(filter)
                    }
                    // -q：减少每个用例一行的那种输出
                    add("-q")
                    add("--no-header")
                }
                plan.displayName == "npm test" -> {
                    // npm test 通常不接筛选参数，交给脚本自己处理
                }
            }
        }

    /** 平台判断单独抽出来，方便探针在任意平台断言两条分支 */
    internal fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun npmCmd(): String = if (isWindows()) "npm.cmd" else "npm"

    private fun File.readTextSafe(): String =
        runCatching { if (isFile) readText() else "" }.getOrDefault("")

    companion object {
        /**
         * 把测试输出压成摘要。
         *
         * **纯函数**（不碰文件系统、不碰控制台），所以可以拿真实日志片段做断言。
         * 之所以要抽出来：这段是「测试工具好不好用」的全部所在 ——
         * 执行部分三行就写完了。
         *
         * 输出分三块：
         *  1. 结果行（通过/失败个数）—— 从输出里找，找不到就说「没解析出结果」
         *  2. 失败用例清单 —— 每条一行，带文件行号
         *  3. 出错原因 —— 抓每个失败后面的异常首行
         *
         * 有意**不保留**「成功时的完整日志」：几千行任务进度对模型毫无价值。
         * 失败时也不全给 —— 只给失败相关的那几行，加一句「完整日志在哪」，
         * 需要细节时模型可以自己 read_file。
         */
        fun summarize(stdout: String, stderr: String, exitCode: Int, timedOut: Boolean): String {
            if (timedOut) {
                return "测试超时被杀，没有完整结果。" +
                    "如果只是慢（不是卡住），把 timeout_seconds 调大再试；" +
                    "如果是挂住了，检查是否有测试在等外部服务（数据库、网络）。"
            }

            val all = (stdout + "\n" + stderr)
            val lines = all.lines()

            // ---- 找结果行 ----
            // Gradle：`12 tests completed, 2 failed`
            // pytest：`3 failed, 10 passed in 1.23s`
            val gradleCount = Regex("""(\d+)\s+tests?\s+completed(?:,\s*(\d+)\s+failed)?""")
                .find(all)
            val pytestCount = Regex("""(\d+)\s+failed.*?(\d+)\s+passed""").find(all)
            val pytestOnly = Regex("""(\d+)\s+passed(?:.*?(\d+)\s+failed)?""").find(all)

            val resultLine = when {
                gradleCount != null -> {
                    val total = gradleCount.groupValues[1]
                    val failed = gradleCount.groupValues[2].ifBlank { "0" }
                    "结果：共 $total 个用例，失败 $failed 个"
                }
                pytestCount != null ->
                    "结果：通过 ${pytestCount.groupValues[2]}，失败 ${pytestCount.groupValues[1]}"
                pytestOnly != null -> {
                    val f = pytestOnly.groupValues[2].ifBlank { "0" }
                    "结果：通过 ${pytestOnly.groupValues[1]}，失败 $f"
                }
                exitCode == 0 -> "结果：命令成功退出（没能从输出里解析出用例数）"
                else -> "结果：命令失败退出（退出码 $exitCode），但输出里没有标准的结果行"
            }

            // ---- 找失败用例 ----
            val failedCases = lines.filter { l ->
                val t = l.trim()
                // **先排掉 Gradle 的任务行**：`> Task :app:testDebugUnitTest FAILED`
                // 同时含 ">" 和 "Test"，第一版把它当成用例列出来了 ——
                // 那行说的是「这个任务失败了」，不是「哪个用例失败了」
                if (t.startsWith("> Task") || t.startsWith("> ")) return@filter false

                // Gradle：`FooTest > testBar FAILED`
                (t.contains(" FAILED") && t.contains(" > ")) ||
                    // pytest：`FAILED tests/test_x.py::test_y - AssertionError`
                    t.startsWith("FAILED ")
            }.map { it.trim() }.distinct().take(25)

            // ---- 找错误原因 ----
            // 抓异常首行。只看「看起来是异常」的行，避免把任务进度也抓进来。
            val casesSet = failedCases.toSet()
            val reasons = lines.filter { l ->
                val t = l.trim()
                (t.contains("Exception") || t.contains("Error:") || t.contains("AssertionError") ||
                    t.contains("expected:") || t.contains("Assertion")) &&
                    // 排除 Gradle 自己贴的那几行脚手架信息
                    !t.startsWith("> Task") && !t.startsWith("Execution failed") &&
                    // **排掉已经作为「失败用例」列过的行**：
                    // pytest 的 `FAILED tests/x.py::test_y - AssertionError: ...`
                    // 同时满足两边的条件，第一版把它列了两遍
                    t !in casesSet &&
                    t.length in 8..400
            }.map { it.trim() }.distinct().take(12)

            val sb = StringBuilder()
            sb.append(resultLine).append('\n')

            if (failedCases.isNotEmpty()) {
                sb.append("\n失败的用例：\n")
                failedCases.forEach { sb.append("  ").append(it).append('\n') }
            }

            if (reasons.isNotEmpty()) {
                sb.append("\n出错原因（去重后）：\n")
                reasons.forEach { sb.append("  ").append(it).append('\n') }
            }

            // **只有真的解析出「失败 0 个」才敢说全过。**
            //
            // 第一版写的是「退出码 0 且没抓到失败行 → 测试全过」，探针立刻打脸：
            // 构建成功**不代表测试跑了** —— 任务被跳过（UP-TO-DATE / SKIPPED）、
            // 一个测试源都没有、或者输出被截断，这三种情况都会「成功退出但一个用例没跑」。
            // 那时说一句「测试全过」，用户会以为验证过了。**谎报比不报糟得多。**
            val parsedCount = gradleCount != null || pytestCount != null || pytestOnly != null
            val zeroFailures =
                (gradleCount?.groupValues?.get(2).isNullOrBlank() ||
                    gradleCount.groupValues[2] == "0") &&
                    (pytestCount == null) &&
                    (pytestOnly?.groupValues?.get(2).isNullOrBlank() == true)

            if (exitCode == 0) {
                if (parsedCount && zeroFailures && failedCases.isEmpty()) {
                    sb.append("\n测试全过。")
                } else if (!parsedCount) {
                    sb.append("\n命令成功退出，但**没能从输出里确认跑了多少用例** —— " +
                        "不要据此认为测试都过了（任务可能被跳过、或输出被截断）。" +
                        "需要确认的话，看 build/reports/tests/ 下的报告。")
                }
            } else {
                sb.append("\n完整报告在 build/reports/tests/ 下（HTML），" +
                    "单条用例的详细堆栈可以用 read_file 看对应的 build/test-results XML。")
            }

            return sb.toString().trimEnd()
        }
    }
}
