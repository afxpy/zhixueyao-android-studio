package com.zhixueyao.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 「Git 助手说明」—— 一个纯展示的窗口。
 *
 * ## 为什么要有它
 *
 * 用户的原话：
 *
 * > 「总要有一些检测和提醒吧，我建议加个 git 助理说明的按钮用来展示 git 助理
 * >  所有功能的详细介绍，点击这个按钮后会弹出一个 android studio 的窗口用于展示说明」
 *
 * 他的判断很准：**Git 助手这一页里现在全是「状态」和「开关」，
 * 但没有一处说清「这东西到底是干什么的」。**
 *
 * 而这恰恰是最容易让人卡住的地方 —— 用户看到一个开关叫「启用 Git 助手」，
 * 他没法判断该不该打开，因为他不知道打开之后会发生什么。
 * 后面那一堆「直连 / 本机工具 / 指定代理」也是，
 * **不解释清楚就得靠试**，而试错成本可能是「AI 拿我的仓库乱搞」。
 *
 * ## 内容原则
 *
 * 1. **说清「会」和「不会」** —— 尤其是不做的事。用户最担心的是
 *    「AI 会不会把我仓库搞坏」，所以「不做 reset --hard」「不碰你的 git 配置」
 *    这类话要写明白，而不是藏在代码注释里。
 * 2. **每一条都对应代码里真实存在的行为** —— 不给用户讲没有的功能，
 *    也不漏掉会实际发生的事。
 * 3. **解释「为什么」而不只是「是什么」** —— 比如为什么推荐凭据管理器，
 *    原因是「你自己在终端里 push 也不用再输账号」。
 */
class GitHelpDialog(project: Project?) : DialogWrapper(project) {

    init {
        title = "Git 助手 · 功能说明"
        setSize(720, 640)
        isResizable = true
        centerRelativeToParent()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 10, 10, 10)
        }

        body.add(intro())
        body.add(Box.createVerticalStrut(14))

        for ((heading, lines) in SECTIONS) {
            body.add(sectionTitle(heading))
            for (line in lines) body.add(bodyLine(line))
            body.add(Box.createVerticalStrut(14))
        }

        body.add(sectionTitle("一句话总结"))
        body.add(
            bodyLine(
                "**它让 AI 能看懂你的仓库历史、能替你提交，也能在开启后推送 —— " +
                    "但所有会丢东西的操作（reset --hard / clean）它都不做，也不会改动你的 git 配置。**" +
                    "  开之前先跑一次上面的「环境自检」，那里会告诉你缺什么、并直接帮你补上。"
            )
        )
        body.add(Box.createVerticalGlue())

        val scroll = JBScrollPane(body).apply {
            border = null
            setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
            verticalScrollBar.unitIncrement = 16
        }
        return scroll
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)

    private fun intro(): JComponent = JBLabel(
        "<html><div style='width:620px'>" +
            "<font color='gray'>下面按「你想知道什么」分成几块。每一块都是这个插件里**真实存在**的行为，</font>" +
            "<font color='gray'>不是设想 —— 描述和代码是同一份东西。</font>" +
            "</div></html>"
    )

    private fun sectionTitle(text: String): JComponent = JBLabel(
        "<html><div style='width:620px'><b>$text</b></div></html>"
    ).apply {
        border = JBUI.Borders.empty(0, 0, 4, 0)
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }

    private fun bodyLine(md: String): JComponent {
        // 极简的 **粗体** 与 `代码` 处理 —— 够用了，不值得为这个引一个 markdown 库
        val html = md
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("`(.+?)`"), "<code>$1</code>")
        return JBLabel("<html><div style='width:620px'>$html</div></html>").apply {
            border = JBUI.Borders.empty(2, 8, 2, 0)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
    }

    companion object {

        /**
         * 说明内容。
         *
         * **这里每一条都对应代码里真实存在的行为** —— 改功能时记得回来改这里，
         * 否则这个窗口会变成一份「过期的漂亮话」，那比没有更糟：
         * 用户会照着它去期待一个不存在的功能。
         */
        private val SECTIONS: List<Pair<String, List<String>>> = listOf(

            "一、这个助手能做什么" to listOf(
                "**让 AI 看懂你的改动历史。** 改代码前先看 `log` / `blame`，" +
                    "知道这段为什么变成现在这样 —— 这往往比读代码本身更有用。",
                "**替你做日常操作**：`status`、`diff`、`log`、`show`、`blame`、" +
                    "`branch`、`stash` 的查看与暂存。",
                "**提交**：`add` 和 `commit`。提交信息由 AI 根据实际改动写，不是你手打的。",
                "**推送 / 拉取**：**默认关闭**，需要你在这里打开「启用 Git 助手」才可用。",
                "**远端诊断与合并**：`fetch`、`remote`、`diagnose`（一次把仓库/远端状态全查出来）、" +
                    "`merge` / `merge_abort`（合并远端分支、放弃合并）。",
                "**提交前检查**：暂存区里的文件会先过一遍 —— 文件名像命令敲错的（带空格）、" +
                    "0 字节的新文件、超过 50MB 的、疑似密钥/凭据的，都会被拦下来并说明原因。"
            ),

            "二、它**不会**做什么（这一节请务必看完）" to listOf(
                "**不做 `reset --hard`、不做 `clean`。** 这两类会真的丢东西，" +
                    "助手会直接拒绝 —— 如果你确实需要，请自己在终端里决定。",
                "**不改你的 git 配置。** 推送时需要的代理和账号是**临时**传给那一次命令的，" +
                    "用完不落盘。你原来的 `git config` 不会被写坏。",
                "**不碰你的 token。** 凭据交给系统的凭据管理器保管（见第五节），" +
                    "插件自己不存一份。",
                "**不会在你没开开关的时候推送。** 开关关着时，`push` / `pull` 会被直接挡回来。",
                "**不会自动改用其它代理。** 用哪个通道完全按你在设置里的选择；" +
                    "连不上时如实报告原因，不会自己去探测、也不会悄悄换到别的代理。",
                "**不会强推。** 远程有本地没有的提交时，它会先停下来把两边关系讲清楚、" +
                    "让你决定要不要合并 —— 不存在「被强行覆盖」的情况。"
            ),

            "三、环境自检 —— 它替你查什么" to listOf(
                "进这一页就会自动跑一次，五项检查都在**一两秒内**出结果。每一项只有三种状态：" +
                    "通过、需要注意、缺失。",
                "**Git 已安装** —— 找不到 git 时，它会去常见安装位置（`C:\\Program Files\\Git`、" +
                    "`D:/Git` 等）自己找一遍，而不是干等着报错。",
                "**提交署名** —— `user.name` / `user.email`。没配的话提交会失败或者署名成机器名，" +
                    "这里会直接给你填的地方。",
                "**凭据管理器** —— 见第五节。",
                "**代理配置** —— 查你的 `git config` 里有没有残留的代理。" +
                    "**这一项最容易踩**：代理软件装完又卸了，配置却留着，之后所有 git 命令都会失败，" +
                    "而错误信息完全看不出是这个原因。查到了会给「清掉这两行」。",
                "**外网可达性** —— 真的连一次 github 和一次国内站，然后告诉你结论。" +
                    "只测 github 的话，「不通」既可能是「没开加速器」也可能是「根本没联网」，" +
                    "**而这两者的处置完全不同**，所以国内也测一次。"
            ),

            "四、访问方式 —— 我该选哪个" to listOf(
                "**先看第三节的「外网可达性」怎么说的** —— 它会直接告诉你选哪个。",
                "**直连**：加速器开着（Clash / 速界这类接管了流量），或者你的网络本身就能上 github。" +
                    "**这种情况下选直连就行，不用配代理** —— 流量已经被系统层接管了，" +
                    "再走一层代理反而绕远。",
                "**本机 VPN 工具**：客户端在本机开了一个 HTTP 代理端口（Clash 的 7890、" +
                    "v2rayN 的 10809、以及各家自定义的端口）。点「测试连接」会自动找。" +
                    "**它是真的找**：先把本机所有在监听的端口列出来，逐个发一次代理请求，" +
                    "再**真的握一次 TLS 握手** —— 只有握手成功才算「可用」。",
                "**为什么不能只看「端口开着」**：一个只能访问国内的代理照样会回 `200 Established`，" +
                    "然后什么都不给。所以一定要走到握手那一步。",
                "**找端口的方法不止一种**：① 你填的；② 环境变量（`HTTPS_PROXY` 这类）；" +
                    "③ Windows 系统代理设置；④ 本机实际在监听的端口；⑤ 常见端口列表。" +
                    "**按确定性排序，任何一种命中都算。**"
            ),

            "五、凭据管理器 —— 为什么值得开" to listOf(
                "Git Credential Manager（GCM）让 git 把账号密码交给**操作系统的加密存储**保管，" +
                    "而不是明文写在某个文件里。",
                "**开了之后你自己在终端里 push 也不用再输账号** —— 这是最实际的好处。" +
                    "不开也能用，但插件得自己存一份 token，**那你的终端里还要再填一次**。",
                "**三种状态，三种按钮**：没装 → 「自动安装」；装了没配 → 「一键启用」；" +
                    "都好了 → 直接显示出来。",
                "**自动安装有两条路**：优先用 winget（装到系统位置，以后好升级）；" +
                    "没有 winget 就下载便携版解压到用户目录（**不需要管理员权限**）。",
                "**装完会顺手启用** —— 你点一次按钮就全好了，不用「装完再点另一个按钮」。"
            ),

            "六、点一次 push，背后发生的事" to listOf(
                "**先预检**：`ls-remote` 探一次远端 —— 这一步同时验证「网络通不通」和「凭据对不对」，" +
                    "有问题会在推送之前就告诉你原因。",
                "**连不上时不会擅自换通道**：它会如实报告失败原因（比如 git 配置里残留着" +
                    "一个早就关掉的代理端口），但**不会自动探测、也不会自动改用其它代理** —— " +
                    "要不要换、换成哪个，由你在设置里决定。",
                "**推送前看分叉**：远程有本地没有的提交时（就是 `fetch first` 那个报错），" +
                    "它会停下来把两边关系讲清楚（谁领先几个提交、是不是无共同历史），" +
                    "并给出「先 merge 再 push」的具体操作。",
                "**推完核对**：推完再查一次远端 hash，和本地对齐了才说「已推送」；" +
                    "被远端 hook / 保护规则拦下会明确告诉你。",
                "**报告去向**：推的不是默认分支时，它会提醒「GitHub 首页显示的是 main」这类事 —— " +
                    "不用再猜「为什么推了却没变化」。"
            ),

            "七、开启前后的区别" to listOf(
                "**关着（默认）**：AI 可以看历史、可以做提交。" +
                    "**它连不上远端** —— push / pull 会被挡回来。",
                "**开着**：额外允许 push / pull。插件会按你配好的访问方式和账号去连，" +
                    "并按第六节那样先预检、再推送、最后核对。"
            )
        )
    }
}
