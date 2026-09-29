# 止血药 · Android Studio AI 编程助手

一个嵌进 Android Studio / IntelliJ 的 AI 助手：能读工程、改代码、跑构建诊断，
也能生成图片和矢量图。重点是**把「安全」和「可追溯」当第一约束** ——
所有改动可回退，所有工具调用留痕，所有产物有明确归属。

## 功能

**Agent 能力**

- 21 个内置工具：文件读写改动、代码检索、构建诊断、图标生成、任务清单、技能库、MCP 管理
- **多步自主执行**：自己定计划、调工具、看结果、继续做，直到任务完成
- **运行中追加输入（steer）**：AI 跑偏时直接在输入框打字，它下一步就会看到并调整方向，
  已经做完的工作不会丢
- **沙盒权限**三档：仅项目内 / 项目+指定目录 / 全盘，随时可切

**安全与可追溯**

- **软删除 + 备份还原**：AI 能改能删，但永远有退路（走系统回收站，可一键还原）
- **引用校验**：回答里提到的文件路径会逐个核对，编造出来的路径会被标出来
- **操作留痕**：每次工具调用都记录参数与结果，事后能回看「当时到底做了什么」
- **改完必验**：改代码后自动跑编译诊断，有错继续修

**上下文治理**（省 token，也保证答得准）

- 每轮自动瘦身旧工具结果、旧图片（看过的留 N 张，**没看过的无条件保留**）
- 超预算时 LLM 摘要压缩，保留最近若干轮原样不动
- 回答里显示本轮 token 用量（悬停看输入/输出明细）

**界面**

- 流式回答 + 思维链折叠 + 工具调用卡片 + 代码块一键「应用到文件」
- 消息版本切换（重新生成不丢旧版）、长回答折叠、历史会话存档
- **图片预览窗口**：缩放、适应窗口、左右翻页看整个会话的图
- **临时产物隔离**：试验性的东西进 `~/.zhixueyao/Conversation/`，**不会污染你的工程**

**技能库（可积累的工程经验）**

- 两处技能目录：`<工程>/.zhixueyao/skills/`（随工程走）与 `~/.zhixueyao/skills/`（跨项目）
- AI 可以**自己读写**技能：一轮任务里踩到的坑，它会固化成技能，下次不再犯
- 渐进式加载：提示词只列「名字 + 一句话」，用到才取正文，几十个技能也不占上下文

**MCP 支持**：接外部工具服务器（浏览器、数据库等），一键导入 `mcp.json`；本插件也能对外提供 MCP 服务

## 安装

### 方式一：直接装插件包（推荐普通用户）

1. 到 [Releases](../../releases) 下载 `zhixueyao-0.2.0.jar`
2. Android Studio → `Settings` → `Plugins` → 右上角齿轮 → **Install Plugin from Disk...**
3. 选中刚才下载的 jar → 确定 → **重启 Android Studio**
4. 重启后左侧边栏会出现「止血药」工具窗口

手动安装的替代做法（装不上的时候用）：
把 jar 放进 `<Android Studio 配置目录>/plugins/zhixueyao/lib/` 后重启。
配置目录一般是：

- Windows：`%APPDATA%\Google\AndroidStudio<版本>`
- macOS：`~/Library/Application Support/Google/AndroidStudio<版本>`
- Linux：`~/.config/Google/AndroidStudio<版本>`

### 方式二：从源码构建

**环境要求**

| 项 | 要求 |
|---|---|
| JDK | 21 |
| Android Studio / IntelliJ | 2026.1（build 261）或更高 |
| 网络 | 首次构建需联网拉 Gradle 与插件依赖 |

```bash
# 1) 告诉构建脚本你的 Android Studio 装在哪（三种方式挑一种）
#    a. 写进 gradle.properties（推荐，一次配好）
echo "studioPath=D:/Android studio" >> gradle.properties
#    b. 环境变量
export ANDROID_STUDIO_HOME="D:/Android studio"
#    c. 命令行临时指定
./gradlew buildPlugin -PstudioPath="D:/Android studio"

# 2) 构建
./gradlew buildPlugin

# 3) 产物在这里
#    build/libs/zhixueyao-0.2.0.jar
```

> 常见默认安装位置脚本会自动探测（`C:/Program Files/Android/Android Studio`、
> `/Applications/Android Studio.app/Contents`、`/opt/android-studio` 等），
> 装在默认位置的话不用配。

构建完成后再按「方式一」的第 2 步安装 jar 即可。

## 首次使用

1. 点左侧「止血药」打开面板
2. 点顶栏的模型胶囊 → 填入服务商地址、API Key、模型名
   （兼容任何 OpenAI 格式的接口：官方、中转站、本地模型都行）
3. 直接提问即可，例如「看看这个项目的结构」「给登录页加个加载状态」

**权限提醒**：默认沙盒是「仅项目内」，AI 只能动当前工程的文件。
需要它读工程外的东西时，点底部的沙盒胶囊切档位。

## 目录说明

```
~/.zhixueyao/                    全局配置（跨项目复用，换机器拷走即可）
├── skills/                      全局技能库
├── mcp.json                     MCP 服务器配置
└── Conversation/Product/        会话临时产物
    └── <日期-时间>/<会话名>/      按会话隔离，可在设置页批量清理

<你的工程>/.zhixueyao/
└── skills/                      项目技能库（可以提交进仓库，团队共享）
```

## 工程结构

```
src/main/kotlin/com/zhixueyao/
├── agent/        Agent 循环、上下文压缩、技能、沙盒、引用校验
├── llm/          模型接入（OpenAI 兼容 SSE 流式）
├── mcp/          MCP 客户端与服务器
├── tools/        内置工具
├── ui/           界面（Swing）
├── chat/         会话存档
└── settings/     设置页
```

主要技术点见工程内的 `.zhixueyao/skills/` —— 那是开发过程中积累的踩坑记录与方法论。

## 已知限制

- 视频不做内嵌播放（用系统默认播放器打开）
- SVG 预览走 IntelliJ 平台的渲染器，不支持渐变以外的滤镜
- 界面基于 Swing，跟随 IDE 的明暗主题

## 许可

见 [LICENSE](LICENSE)。
