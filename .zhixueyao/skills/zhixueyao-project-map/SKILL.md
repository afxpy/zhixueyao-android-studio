---
name: zhixueyao-project-map
description: 止血药工程的目录地图、构建安装流程、验证探针位置。改这个工程前先读，避免在错的地方改。
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
| JDK | `<本机 JDK 21 路径>` |
| 安装目标 | `%APPDATA%/Google/AndroidStudio2026.1.3/plugins/zhixueyao/lib/zhixueyao-0.2.0.jar` |
| 离线探针 | `<本机临时探针目录>/`（**不放进工程**） |
| 工程日志 | `.workbuddy/memory/2026-09-26.md` |

## 源码结构（看改哪里）

```
com/zhixueyao/
├── agent/          Agent 循环与上下文治理
│   ├── AgentRunner.kt      ← 主循环、systemPrompt、clipResult、steer 注入
│   ├── ContextCompactor.kt ← 上下文压缩 / 工具结果瘦身 / 图片预算 / token 估算
│   ├── Skills.kt           ← 技能扫描与目录（项目级 .zhixueyao/skills + 全局）
│   ├── ReferenceChecker.kt ← 回答里的文件路径校验（防幻觉）
│   └── Sandbox.kt          ← 文件访问档位
├── llm/            Models.kt（ChatMessage）、LlmProvider.kt（SSE 流式）
├── mcp/            McpClient / McpManager / McpTransports（两条传输）
├── tools/          内置工具（21 个），FileTools / BuildTools / SearchTools…
├── ui/             ★ 界面都在这里
│   ├── ChatPanel.kt        主面板（4000+ 行，最大的一个文件）
│   ├── MessageBubble.kt    气泡：正文/思维链/工具卡/代码卡/用量页脚
│   ├── HistoryGrouping.kt  历史 → 界面轮次的分组（纯函数）
│   ├── ImagePreviewDialog.kt 图片预览窗口（缩放 + 上下张）
│   ├── MarkdownRenderer.kt / CodeBlockSupport.kt / ScrollFollow.kt
│   └── UiKit.kt            颜色、圆角、按钮、卡片边框
└── chat/SessionStore.kt    会话存档（JSON 落盘）
```

## 构建与安装

```bash
export JAVA_HOME="<本机 JDK 21 路径>"
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

## 工程约定

- 代码注释写**「为什么」**，不是「做了什么」——踩过的坑要写在注释里，防止别人（或下一轮的自己）改回去
- 不用 write_file 整体重写文件（会丢内容）；改动用精确替换
- 交付时只留工程必需文件；探针、临时脚本一律放 temp 目录
- `.zhixueyao/` 不是缓存，**不要删**（技能目录 + 用户配置都在这）