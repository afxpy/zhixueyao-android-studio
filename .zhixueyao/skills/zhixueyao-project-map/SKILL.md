---
name: zhixueyao-project-map
description: 止血药工程的目录地图、构建安装流程、验证探针位置。改这个工程前先读，避免在错的地方改。
triggers: 工程结构, 在哪改, 怎么构建, 装插件, 打包
---

# 止血药 · 工程地图与操作流程

## 这是什么

Android Studio 2026.1.3 的编程助手插件（IntelliJ Platform 261+）。
面板式聊天 UI + Agent 循环 + 内置工具 + MCP 客户端。

## 关键路径

| 用途 | 路径 |
|---|---|
| 工程根 | `<工程根>` |
| 编译平台 | `D:/Android studio`（local 依赖，不下载 IDE 发行包） |
| JDK | `D:/tools/jdk-21.0.5+11` |
| 安装目标 | `%APPDATA%/Google/AndroidStudio2026.1.3/plugins/zhixueyao/lib/zhixueyao-0.2.0.jar` |
| 离线探针 | `<本机临时探针目录>/`（**不放进工程**） |
| 工程日志 | `.workbuddy/memory/2026-09-26.md` |

## 源码结构（看改哪里）

```
com/zhixueyao/
├── agent/          Agent 循环与上下文治理
│   ├── AgentRunner.kt      ← 主循环、systemPrompt、clipResult、steer 注入
│   ├── ContextCompactor.kt ← 上下文压缩 / 工具结果瘦身 / 图片预算 / token 估算
│   ├── SessionWorkspace.kt ← 会话临时产物目录（全局，不在工程内）
│   ├── Skills.kt           ← 技能扫描与目录（项目级 .zhixueyao/skills + 全局）
│   ├── ReferenceChecker.kt ← 回答里的文件路径校验（防幻觉）
│   └── Sandbox.kt          ← 文件访问档位
├── llm/            Models.kt（ChatMessage）、LlmProvider.kt（SSE 流式）
│                   ScriptedProvider.kt ← 脚本化假模型（离线回归用，见 verify-probe）
├── mcp/            McpClient / McpManager / McpTransports（两条传输）
├── tools/          内置工具（25 个）
│   ├── ProcessRunner.kt   ★ 所有跑命令的地方都走它（超时/取消/并发读流/杀子孙）
│   ├── DevOpsTools.kt     git 工具 + run_script
│   ├── WebTools.kt        web_fetch + SsrfGuard
│   └── MemoryTool.kt      memory 工具
├── agent/MemoryStore.kt   跨会话记忆（MEMORY.md，注入提示词）
├── ui/             ★ 界面都在这里
│   ├── ChatPanel.kt        主面板（4000+ 行，最大的一个文件）
│   ├── MessageBubble.kt    气泡：正文/思维链/工具卡/代码卡/用量页脚
│   ├── HistoryGrouping.kt  历史 → 界面轮次的分组（纯函数）
│   ├── ImagePreviewDialog.kt 图片预览窗口（缩放 + 上下张）
│   ├── ImageHoverPreview.kt  附件胶囊悬停浮出大图
│   ├── WrapLayout.kt         会正确折行的流式布局（高度算得对）
│   ├── TableSection.kt       设置页「表格限高 + 工具栏紧跟」
│   ├── MarkdownRenderer.kt / CodeBlockSupport.kt / ScrollFollow.kt
│   └── UiKit.kt            颜色、圆角、按钮、卡片边框
└── chat/SessionStore.kt    会话存档（JSON 落盘）
```

## 构建与安装

```bash
export JAVA_HOME="D:/tools/jdk-21.0.5+11"
./gradlew buildPlugin --offline
```

产物 `build/libs/zhixueyao-0.2.0.jar`。安装用**临时名 + 原子替换**（IDE 跑着时直接覆盖会失败）：

```bash
DEST="$APPDATA/Google/AndroidStudio2026.1.3/plugins/zhixueyao/lib"
cp build/libs/zhixueyao-0.2.0.jar "$DEST/zhixueyao-0.2.0.jar.new"
mv -f "$DEST/zhixueyao-0.2.0.jar.new" "$DEST/zhixueyao-0.2.0.jar"
md5sum "$DEST/zhixueyao-0.2.0.jar"    # 核对确实换了新的
```

**装完必须重启 Android Studio**，否则跑的还是旧类。

## 产物放哪里（重要，用户明确要求过）

**临时产物一律进全局目录，不进用户工程**：

```
~/.zhixueyao/Conversation/Product/<会话创建时间 yyyy-MM-dd-HH.mm>/<会话名>/
```

判断标准只有一句：**这是 App 要用的，还是我们俩看着玩的？**
- 看着玩的（试验图、草稿、预览、导出物）→ 全局临时目录
- App 要用的（`res/`、`assets/`、源码）→ 工程里

实现见 `com.zhixueyao.agent.SessionWorkspace`；提示词里的规则在
`ToolRegistry.workspaceRule()`（不写这条，模型会把试验产物丢进 `res/drawable/`，
用户的工程里就会多出一堆不是 App 需要的文件 —— 已经发生过）。

工具侧：
- `generate_svg` 的 `keep_svg` 落全局临时目录（不再写 `res/drawable/`）
- `save_asset` 加 `temp` 开关：`true` = 临时目录，`false/不传` = 进 `res/`

清理入口：**设置 → 临时会话**（表格 + 全选/单选删除）。

## 依赖注入约定（便于离线验证）

**凡是「纯计算」的函数，不要自己去读全局状态。**

真实教训：`ToolRegistry.skillCatalog()` 和 `sandboxRule()` 原来各自调
`ProjectManager.getInstance().openProjects.firstOrNull()` 拿项目 ——
在离线探针里 `getInstance()` 是 **null**，直接 NPE，
于是「系统提示词到底写了什么」这件事**永远验不了**。
后来改成 `project` 由参数传入（ChatPanel 本来就持有 project）。

同理：
- `AgentRunner` 的 `mcpManager` 是**可空**的 ——
  `McpManager` 是 final 类，既不能继承也不能代理，硬造假对象做不到；
  而「没有 MCP 的运行器」本身就是合理状态
- `AgentRunner.run()` 有 `providerOverride: LlmProvider? = null`，
  默认 null 时行为逐字节不变

**判断标准**：如果一个函数在探针里跑不起来，先问「它是不是自己去找了全局状态」，
而不是给探针加一堆 mock 服务。

## 工程约定

- 代码注释写**「为什么」**，不是「做了什么」——踩过的坑要写在注释里，防止别人（或下一轮的自己）改回去
- 不用 write_file 整体重写文件（会丢内容）；改动用精确替换
- 交付时只留工程必需文件；探针、临时脚本一律放 temp 目录
- `.zhixueyao/` 不是缓存，**不要删**（技能目录 + 用户配置都在这）