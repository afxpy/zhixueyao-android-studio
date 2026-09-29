package com.zhixueyao.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 全局设置。字段刻意保持简单可序列化，避免自定义序列化器带来的兼容问题。
 */
@State(
    name = "ZhixueyaoSettings",
    storages = [Storage("zhixueyao.xml")]
)
class ZhixueyaoSettings : PersistentStateComponent<ZhixueyaoSettings> {

    /** 当前选中的服务商预设 id，为空表示自定义 */
    var providerId: String = "deepseek"

    var baseUrl: String = "https://api.deepseek.com/v1"
    var model: String = "deepseek-chat"
    var apiKey: String = ""

    /**
     * 各服务商的独立密钥，key 为 providerId。
     *
     * 这样切换服务商时不必反复重填密钥 —— 每个服务商记住自己的那份。
     * [apiKey] 始终是「当前生效」的那一份，两者保持同步。
     */

    /** 各服务商上次用过的模型名，切换时自动恢复。 */

    /**
     * 各服务商「已添加」的模型清单，供菜单里展开二级列表。
     *
     * 存成扁平 `List<String>` 而不是 `Map<String, List<String>>`：
     * 平台的 XML 序列化对嵌套集合支持不稳，扁平列表才是各插件通用的稳妥写法
     * （本工程里 [customProviders] 也走同一路子）。
     *
     * 每条编码为 `providerId|modelName`。读取时用 **limit = 2** 切分，
     * 所以模型名里即便自带 `|` 也会被完整保留在第二段，不会被切错。
     */

    /** 各服务商自定义的接口地址（覆盖预设值，留空表示用预设）。 */

    /**
     * 用户自建的服务商（中转站 / 自建网关），可以并存多个。
     *
     * 与预设的区别：预设是「固定的一家」，这里的每一项都是用户自己加的，
     * 各有名称便于在切换菜单里区分 —— 「公司中转」「某宝额度」这类。
     */
    var customProviders: MutableList<com.zhixueyao.llm.CustomProvider> = mutableListOf()

    /** 思考强度档位，见 ThinkingLevel。默认不干预 */
    var thinkingLevel: String = "DEFAULT"

    /** apiFormat: openai | anthropic */
    var apiFormat: String = "openai"

    var temperature: Double = 0.2
    var maxTokens: Int = 8192

    /** 单轮对话中允许的最大工具调用轮数，防止无限循环 */
    var maxToolRounds: Int = 25

    /** 是否允许 AI 直接写入文件（关闭后仅生成差异预览） */
    var allowDirectWrite: Boolean = false

    /** 是否把 MCP 工具一并暴露给模型 */
    var enableMcp: Boolean = true

    /** 本插件作为 MCP 服务器对外提供服务的开关 */
    var mcpServerEnabled: Boolean = false
    var mcpServerPort: Int = 29170

    // ---------------- 通用设置 ----------------

    /** 当前 Agent 预设 id，见 AgentPresets */
    var agentPreset: String = "standard"

    /** 发送前是否弹出确认（避免误发长上下文） */
    var confirmBeforeSend: Boolean = false

    /** 代码块生成后是否自动展示「应用」按钮 */
    var showApplyButton: Boolean = true

    /** 回复语言偏好：auto | zh | en */
    var replyLanguage: String = "zh"

    /** 是否在状态栏显示 token 用量估算 */
    var showTokenEstimate: Boolean = true

    /**
     * 用户头像。
     *
     * 两种取值：内置预设 id（`u-blue` 等，见 [com.zhixueyao.ui.Avatars.userPresets]）
     * 或自定义图片 `file:<绝对路径>`（图片已复制进 IDE 配置目录，见 Avatars.importUserAvatar）。
     */
    var userAvatar: String = ""

    /** AI 头像预设 id，见 [com.zhixueyao.ui.Avatars.aiPresets]。留空按默认品牌头像 */
    var aiAvatar: String = ""

    /**
     * 思考过程展示方式：
     *  - `live`      实时展开 —— 边生成边显示思维链，正文开始出现后自动收起
     *  - `collapsed` 默认收起 —— 只留一个「显示思考过程」链接
     *  - `hidden`    不显示 —— 连入口都不给，适合不关心推理过程的场景
     *
     * 默认 `live`：思考边生成边显示（淡色「虚」字），正文一出现自动收掉 ——
     * 这是主流 agent 的观感（用户拿 WorkBuddy 对比后要求的）。
     */
    var reasoningView: String = "live"

    /** 「思考展示方式」的一次性迁移标记，见 [loadState] */
    var reasoningViewMigrated: Boolean = false

    /**
     * 在聊天窗口的「思考模式」下拉里**勾选显示**的思考强度档位（存 [ThinkingLevel] 的枚举名）。
     *
     * 只影响下拉里显示哪些选项，不改变当前生效值（那是 [thinkingLevel]）。
     * 一个都没勾时下拉里只有「默认」—— 强度完全交给服务商与模型自己决定。
     */
    var thinkingLevels: MutableList<String> = mutableListOf()

    /**
     * 沙盒权限档位，见 [com.zhixueyao.agent.Sandbox.Mode]：
     * `project`（仅当前项目，默认）/ `full`（全盘）/ `ask`（每次询问）。
     */
    var sandboxMode: String = "project"

    /** 「仅当前项目」的说明弹窗是否已被勾选「下次不再提醒」 */
    var sandboxNoticeAccepted: Boolean = false

    /** 是否让 AI 展示任务清单（关掉后 todo_write 工具会直接告知模型不必调用） */
    var showTaskList: Boolean = true

    /** 是否让 AI 用选项询问用户（关掉后模型须自己决策并说明假设） */
    var allowAskUser: Boolean = true

    /**
     * 是否允许 AI 派子代理（`spawn_agent` 等四个工具）。
     *
     * 默认**开着** —— 它省的是上下文，属于「开着更好」的那类；
     * 但它会**多打接口**（一个子代理就是一条独立的模型调用链），
     * 所以给一个关掉的开关：用户如果按量计费、或者发现费用涨了，可以关。
     *
     * 关掉后 `spawn_agent` 等工具**根本不进工具清单** ——
     * 不是「进了但不让用」，那样模型会反复尝试、白烧几轮。
     */
    var enableSubagent: Boolean = true

    /**
     * 长对话模式：上下文接近窗口上限时自动增量压缩。
     *
     * 关掉就是「高精度模式」—— 信息一点不丢，但聊长了会撞上模型窗口被服务端打回。
     */
    var autoCompact: Boolean = true

    /** 上下文窗口大小（token）。0 = 按模型名自动推断，见 [com.zhixueyao.agent.ModelWindows] */
    var contextWindow: Int = 0

    /** 压缩时最近多少轮**完全不动**（不摘要、不瘦身），保证近期信息准确 */
    var compactKeepTurns: Int = 4

    /**
     * 「已经看过的」旧图片最多保留几张（0 = 全部保留）。
     *
     * 图片 base64 每轮都会重发，一张截图轻松上万 token —— 贴过几张图之后，
     * 哪怕后面聊的全是文字，那份图也一直在烧钱。
     * **模型还没看过的图不受这个限制**（删了会造成幻读）。
     */
    var maxRecentImages: Int = 2

    /**
     * 产物清单，每条编码为 `sessionId|path`。
     *
     * 存扁平 `List<String>` 而不是 `Map<String, List<String>>` —— 与
     * [providerModelEntries] 同一路子：平台 XML 序列化对嵌套集合支持不稳。
     * 读取时用 **limit = 2** 切分，路径里带 `|` 也不会被切错。
     */
    var artifactEntries: MutableList<String> = mutableListOf()

    // ---------------- 按 providerId 索引的三张表 + 一个清单 ----------------
    //
    // 用扁平的 `Map<String, String>` 而不是嵌套结构 ——
    // 平台 XML 序列化对嵌套集合支持不稳（同 artifactEntries 的路子）。
    //
    // ⚠️ 这一段被误删过一次：我用「有没有被别处读」的机械检查判定它们没人用，
    // 而那个检查**把本文件排除在外**（本来是为了避免把「设置页里的存取」
    // 算成「使用」），于是本文件里这一大堆真实用法全都没被看见。
    // **检查的判据错了，不是字段没用。** 恢复时把这条教训写在旁边。
    var providerKeys: MutableMap<String, String> = mutableMapOf()
    var providerModels: MutableMap<String, String> = mutableMapOf()
    var providerBaseUrls: MutableMap<String, String> = mutableMapOf()

    /** 自定义服务商的条目清单（同样是扁平编码，见 [artifactEntries] 的说明） */
    var providerModelEntries: MutableList<String> = mutableListOf()

    override fun getState(): ZhixueyaoSettings = this

    override fun loadState(state: ZhixueyaoSettings) {
        XmlSerializerUtil.copyBean(state, this)
        // 旧版本没有这几个集合，反序列化后可能为 null
        if (providerKeys == null) providerKeys = mutableMapOf()
        if (providerModels == null) providerModels = mutableMapOf()
        if (providerBaseUrls == null) providerBaseUrls = mutableMapOf()
        if (providerModelEntries == null) providerModelEntries = mutableListOf()
        if (customProviders == null) customProviders = mutableListOf()

        // 一次性迁移：老版本把「默认收起」写进了配置，于是新默认「实时展开」对老用户不生效
        // （用户反馈「我没看见有虚字出现」）。迁一次即可。
        if (!reasoningViewMigrated && reasoningView == "collapsed") {
            reasoningView = "live"
            reasoningViewMigrated = true
        }

        repairCustomProviders()

        // 首次升级：把当前 apiKey 归到当前服务商名下，避免用户重填
        if (apiKey.isNotBlank() && providerKeys[providerId].isNullOrBlank()) {
            providerKeys[providerId] = apiKey
        }
    }

    /**
     * 修复自定义服务商数据 —— 兼做「老版本遗留数据」的抢救。
     *
     * 历史故障：早期 [com.zhixueyao.llm.CustomProvider] 用 `val` 声明，
     * 平台序列化器认为属性不可写而全部跳过，配置里落成 `<CustomProvider />` 空元素，
     * 导致**整个设置组件加载失败**（不只是这段配置丢失）。
     * 那些被写坏的空条目必须剔除，否则会在每次启动时把组件重新搞崩。
     *
     * 剔除之后还要**反向重建**：用户当初填的地址/密钥/模型其实都还在
     * [providerBaseUrls] 等表里（那些是普通的 map，序列化正常），
     * 只是 `customProviders` 那份名单没了。仅凭空条目丢掉它们，
     * 等于让用户白填一遍 —— 所以这里把它们按 id 重新拼回服务商。
     * 名称无从恢复，给个能分辨的默认名，用户可在设置页改名。
     */
    private fun repairCustomProviders() {
        // 1) 剔空：id 为空的条目是历史坏数据，留着会让组件在下次启动时再崩一次
        val broken = customProviders.filter { it.id.isBlank() }
        if (broken.isNotEmpty()) {
            customProviders.removeAll { it.id.isBlank() }
        }

        // 2) 重建：把散落在各表里的自定义服务商 id 收集起来
        //    （坏条目连 id 都没留下，恢复不出服务商本体，只能靠下面的地址表）
        val known = customProviders.map { it.id }.toMutableSet()
        val orphanIds = mutableSetOf<String>()
        for (k in providerBaseUrls.keys) {
            if (k.startsWith(CUSTOM_PREFIX) && k !in known) orphanIds.add(k)
        }
        for (k in providerKeys.keys) {
            if (k.startsWith(CUSTOM_PREFIX) && k !in known) orphanIds.add(k)
        }
        for (k in providerModels.keys) {
            if (k.startsWith(CUSTOM_PREFIX) && k !in known) orphanIds.add(k)
        }

        for (id in orphanIds.sorted()) {
            val url = providerBaseUrls[id].orEmpty()
            // 地址也空的话说明这个 id 只是个残渣，没有恢复价值
            if (url.isBlank()) continue
            val n = id.removePrefix(CUSTOM_PREFIX)
            customProviders.add(
                com.zhixueyao.llm.CustomProvider(
                    id = id,
                    name = "服务商 $n",
                    baseUrl = url
                )
            )
            known.add(id)
        }

        // 3) 当前指向的服务商若已彻底不存在，回落到第一个预设，避免界面悬空
        if (providerId.startsWith(CUSTOM_PREFIX) && providerId !in known) {
            providerId = "deepseek"
        }
    }

    // ---------------- 自定义服务商 ----------------

    /** 下一个可用的自定义服务商 id。用递增序号而非随机值，便于在配置文件里读 */
    fun nextCustomId(): String {
        val used = customProviders.mapNotNull {
            it.id.removePrefix(CUSTOM_PREFIX).toIntOrNull()
        }.toSet()
        var n = 1
        while (n in used) n++
        return "$CUSTOM_PREFIX$n"
    }

    fun customById(id: String): com.zhixueyao.llm.CustomProvider? =
        customProviders.firstOrNull { it.id == id }

    /** 新增或更新一个自定义服务商 */
    fun upsertCustom(p: com.zhixueyao.llm.CustomProvider) {
        val idx = customProviders.indexOfFirst { it.id == p.id }
        if (idx >= 0) customProviders[idx] = p else customProviders.add(p)
    }

    /** 删除自定义服务商，连带清掉它名下的密钥、模型、地址与模型清单 */
    fun removeCustom(id: String) {
        customProviders.removeAll { it.id == id }
        providerKeys.remove(id)
        providerModels.remove(id)
        providerBaseUrls.remove(id)
        clearModels(id)
    }

    // ---------------- 多服务商记忆 ----------------

    /** 读取指定服务商的密钥；未记录时回退到当前生效的密钥。 */
    fun keyOf(pid: String): String =
        providerKeys[pid] ?: if (pid == providerId) apiKey else ""

    /** 记录密钥并同步到当前生效值。 */
    // ---------------- Git 助手 ----------------

    /**
     * Git 助手总开关。关掉之后，下面这些设置一律不生效、
     * git 命令也不会带上任何代理参数 —— 等于插件没碰过 git。
     */
    var gitHelperEnabled: Boolean = false

    /**
     * 访问方式。三个值，用字符串而不是枚举：
     * 配置是存成 XML 的，字符串对不上时能**安全降级**（读不认识的值就当 direct），
     * 而枚举的 `valueOf` 会抛异常 —— 那会让升级后的设置页直接打不开。
     *
     *  - `direct`：直连（**并显式清掉代理**，见 GitProxyDetector.directArgs）
     *  - `proxy`：自定义代理，地址在 [gitProxyUrl]
     *  - `vpn`：本地 VPN 工具，端口自动检测（或用 [gitVpnPort]）
     */
    var gitAccessMode: String = "direct"

    /** 自定义代理地址，如 `http://127.0.0.1:7890` */
    var gitProxyUrl: String = ""

    /** VPN 工具端口。0 = 每次自动检测（推荐，VPN 换端口不用改设置） */
    var gitVpnPort: Int = 0

    /**
     * Git 用户名。
     *
     * **这里只存用户名，token 走 [com.zhixueyao.git.GitCredentials] 的加密存储** ——
     * 用户名不是秘密，token 是。
     */
    var gitUserName: String = ""

    /** 提交署名（git config user.name）。和 gitUserName 不是一回事 */
    var gitAuthorName: String = ""

    /** 提交邮箱（git config user.email） */
    var gitAuthorEmail: String = ""

    fun rememberKey(pid: String, key: String) {
        providerKeys[pid] = key
        if (pid == providerId) apiKey = key
    }

    fun modelOf(pid: String): String = providerModels[pid] ?: ""

    fun rememberModel(pid: String, m: String) {
        providerModels[pid] = m
        if (pid == providerId) model = m
    }

    // ---------------- 服务商模型清单 ----------------

    /**
     * 取某个服务商已添加的模型列表（保持添加顺序，已去重）。
     *
     * 若清单为空但有「上次用过的模型」，把它作为唯一一项返回 ——
     * 这样老用户的配置不会因为升级而看起来「一个模型都没有」。
     */
    fun modelListOf(pid: String): List<String> {
        val out = rawModelListOf(pid)
        if (out.isNotEmpty()) return out
        // 兜底：清单空但「上次用过的模型」还在，把它当作唯一一项，
        // 这样老配置升级后不会看起来「一个模型都没有」。
        // 注意只能用在**没有** providerModels 记录的情形 —— 见 removeModel 里的说明。
        return modelOf(pid).takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    /**
     * 只读 [providerModelEntries]，**不带** `providerModels` 兜底。
     *
     * 为什么需要这个「裸版本」：`removeModel` 要把「上次用过的模型」回落到剩下的第一个，
     * 如果它调用带兜底的 [modelListOf]，而 `providerModels` 此时恰好还存着刚被删掉的那个
     * 名字，兜底就会把这个已删除的名字又吐回来 —— 形成自我循环，结果是
     * **删掉最后一个模型后 model 仍然停在它上面**（实测抓到的 bug）。
     */
    private fun rawModelListOf(pid: String): List<String> =
        providerModelEntries.mapNotNull { e ->
            // limit = 2：模型名里的 `|` 会完整留在第二段
            val parts = e.split(ENTRY_SEP, limit = 2)
            if (parts.size == 2 && parts[0] == pid) parts[1] else null
        }.filter { it.isNotBlank() }.distinct()

    /** 往某服务商的清单里放一个模型。已存在则忽略，返回是否真的新增。 */
    fun addModel(pid: String, m: String): Boolean {
        val name = m.trim()
        if (pid.isBlank() || name.isEmpty()) return false
        if (name in modelListOf(pid)) return false
        providerModelEntries.add(encodeEntry(pid, name))
        return true
    }

    /** 批量加入，返回实际新增的条数 */
    fun addModels(pid: String, names: Collection<String>): Int {
        var n = 0
        for (name in names) if (addModel(pid, name)) n++
        return n
    }

    /** 从清单里移除一个模型 */
    fun removeModel(pid: String, m: String) {
        providerModelEntries.removeAll { e ->
            val parts = e.split(ENTRY_SEP, limit = 2)
            parts.size == 2 && parts[0] == pid && parts[1] == m
        }
        // 清掉的可能正是「上次用的」，此时回落到清单里剩下的第一个，避免停在空模型上。
        // 必须用 rawModelListOf（不带 providerModels 兜底），否则会把刚删掉的名字读回来。
        if (providerModels[pid] == m) {
            val remain = rawModelListOf(pid).firstOrNull() ?: ""
            providerModels[pid] = remain
            if (pid == providerId) model = remain
        }
    }

    /** 重置某服务商的模型清单（用于「清空」） */
    fun clearModels(pid: String) {
        providerModelEntries.removeAll { e ->
            e.split(ENTRY_SEP, limit = 2).firstOrNull() == pid
        }
        // 清单没了，「上次用过的模型」也得跟着清 ——
        // 否则 modelListOf 的兜底会把它当成唯一一项又显示出来，看起来像没清干净
        providerModels.remove(pid)
        if (pid == providerId) model = ""
    }

    private fun encodeEntry(pid: String, m: String): String = "$pid$ENTRY_SEP$m"

    /** 自定义地址，空表示使用预设。 */
    fun baseUrlOf(pid: String): String =
        providerBaseUrls[pid]?.takeIf { it.isNotBlank() }
            ?: if (pid == providerId) baseUrl else ""

    fun rememberBaseUrl(pid: String, url: String) {
        providerBaseUrls[pid] = url
        if (pid == providerId) baseUrl = url
    }

    /** 切换当前服务商，并把 baseUrl/model/apiKey 换成该服务商记住的那份。 */
    fun switchProvider(pid: String) {
        providerId = pid
        baseUrl = baseUrlOf(pid)
        model = modelOf(pid)
        apiKey = keyOf(pid)
    }

    companion object {
        /** 自定义服务商 id 的前缀，后面接递增序号 */
        const val CUSTOM_PREFIX = "custom:"

        /**
         * [providerModelEntries] 里 providerId 与模型名之间的分隔符。
         *
         * 选竖线是因为它在模型名里合法的概率极低（`org/model` 这种用斜杠，
         * `qwen3:8b` 用冒号），且读取时 limit = 2 兜住了残留情况。
         */
        const val ENTRY_SEP = "|"

        fun getInstance(): ZhixueyaoSettings =
            ApplicationManager.getApplication().getService(ZhixueyaoSettings::class.java)
    }
}
