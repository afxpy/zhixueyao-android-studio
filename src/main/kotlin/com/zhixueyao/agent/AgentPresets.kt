package com.zhixueyao.agent

/**
 * Agent 运行预设。
 *
 * 不同预设决定两件事：
 *  1. 哪些工具对模型可见 —— 例如「研究」模式下写文件的工具直接不暴露，
 *     从能力层面杜绝误改，比在提示词里叮嘱「不要改文件」可靠得多。
 *  2. 系统提示词追加的行为准则 —— 例如「代码」模式要求改完必须自检。
 */
object AgentPresets {

    data class Preset(
        val id: String,
        val label: String,
        val summary: String,
        /** 返回 true 表示该工具在此预设下可用 */
        val allowTool: (String) -> Boolean,
        /** 追加到系统提示词的行为准则 */
        val rules: String,
        /**
         * 是否为纯只读预设。
         *
         * 影响 MCP 工具放行：为 true 时，只允许服务器**明确声明**
         * `readOnlyHint=true` 的 MCP 工具。内置工具靠 [allowTool] 白名单约束，
         * 但 MCP 工具来自外部、名单事先未知，只能依赖服务器声明的安全提示。
         */
        val readOnlyOnly: Boolean = false
    )

    /** 只读类工具：不改动任何文件 */
    private val READ_ONLY = setOf(
        "read_file", "list_directory", "glob_files",
        "search_code", "find_symbol", "get_editor_context",
        "get_diagnostics", "project_structure",
        // 取时间当然是只读的，而且**研究模式一样需要** ——
        // 「这周的改动」「三天前的提交」在研究任务里很常见
        "current_time",
        // web_fetch 只读网络、不碰工程 —— 研究模式正需要它查资料
        "web_fetch",
        // git 放这里：它**绝大多数用途是看历史**（这在研究模式里价值很高），
        // 而写动作（commit/add/stash）由 GitTool 自己在运行期再挡一道
        // （见 GitTool.execute 里的 readOnlyOnly 检查）。
        // 只靠这份名单挡的话，要么研究模式看不了历史，要么能被提交 —— 两头不讨好。
        "git"
    )

    /** 写类工具 */
    private val WRITE = setOf(
        "write_file", "edit_file", "delete_file", "restore_file",
        "generate_svg", "export_drawable", "save_asset",
        // run_script 是**任意代码**：它能读也能改文件，
        // 所以按「写」归类（研究模式下不该能跑脚本）
        "run_script"
    )

    /** 执行类工具 */
    /**
     * 执行类工具。
     *
     * `run_tests` 归这里而不是只读：**测试是有副作用的** ——
     * 它们会写临时文件、连数据库、发网络请求。研究模式的承诺是「不改变任何东西」，
     * 所以测试也拦在外面。（想跑测试请切到代码或标准模式。）
     */
    private val EXEC = setOf("run_build", "run_tests", "run_background", "task_output", "task_stop")

    /**
     * 交互类工具：只跟用户打交道，不碰文件系统。
     *
     * 单独一类的原因：只读预设（研究）按 [READ_ONLY] 白名单过滤，如果把它们漏掉，
     * 「列个任务清单」「让我选一下」这些**不改动任何东西**的能力在只读模式下就没了 ——
     * 而它们恰恰是只读研究最需要的（研究任务往往步骤多、岔路多）。
     */
    private val INTERACTION = setOf(
        "todo_write", "ask_user", "skill", "memory",
        // 知识库：读写的是自己的资料目录，不碰工程。研究模式一样放行 ——
        // 「读了很多资料之后沉淀成一页」正是研究任务该做的事
        "kb_write", "kb_search", "kb_tags"
    )

    val all: List<Preset> = listOf(
        Preset(
            id = "standard",
            label = "标准",
            summary = "读取、搜索、修改、构建全部开放，适合日常问答与改代码",
            allowTool = { true },
            rules = """
                按需使用工具：需要信息就查，需要改动就改，改完视情况验证。
            """.trimIndent()
        ),
        Preset(
            id = "code",
            label = "代码",
            summary = "专注代码改动：强制先读后改、最小化替换、改完必检",
            allowTool = { true },
            rules = """
                ## 代码模式（严格遵守）

                1. 改动任何文件前，必须先用 read_file 读取完整内容。禁止凭猜测写代码。
                2. 优先用 edit_file 做最小替换，不要用 write_file 整体重写。
                3. 每次改动后必须调用 get_diagnostics 确认没有引入编译错误；
                   有错就继续修，直到干净为止再汇报。
                4. 不要在回复里重复贴出你已经通过工具写入的完整文件内容，只说明改了什么、为什么。
            """.trimIndent()
        ),
        Preset(
            id = "research",
            label = "研究",
            summary = "只读：可以读代码、搜项目、看结构，但不会改动任何文件",
            allowTool = { it in READ_ONLY || it in INTERACTION },
            readOnlyOnly = true,
            rules = """
                ## 研究模式（严格遵守）

                你处于只读模式，写文件的工具不可用，MCP 工具中也只保留声明为只读的那些。

                1. 任务是读懂并解释清楚，不是修改。
                2. 需要给出改进建议时，用 Markdown 代码块展示建议代码，
                   让用户自己决定是否采纳，不要尝试写入。
                3. 尽量多读几处相关代码再下结论，不要基于单个文件猜测整体设计。
            """.trimIndent()
        ),
        Preset(
            id = "minimal",
            label = "极简",
            summary = "只用最少的工具，直接回答，适合快问快答",
            allowTool = { it in setOf("read_file", "get_editor_context", "search_code") },
            rules = """
                ## 极简模式（严格遵守）

                1. 尽量直接回答，少调用工具。
                2. 只在确实需要确认事实时才读文件或搜索。
                3. 回复要短，不要展开成报告。
            """.trimIndent()
        )
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()

    /** 供界面展示用的工具可见性说明 */
    fun toolSummary(id: String): String = when (byId(id).id) {
        "research" -> "可用：读取文件、搜索代码、查看结构、检查诊断"
        "minimal" -> "可用：读取文件、读取编辑器上下文、搜索代码"
        else -> "可用：全部内置工具（读 / 写 / 搜索 / 构建）"
    }

    /** 该预设下是否有写入能力 */
    fun canWrite(id: String): Boolean = WRITE.any { byId(id).allowTool(it) }

    /** 该预设下是否能跑构建 */
    fun canBuild(id: String): Boolean = EXEC.any { byId(id).allowTool(it) }
}
