# 止血药

面向 Android Studio / IntelliJ 平台的 AI 编程助手插件。它把模型对话、IDE 工程上下文、文件工具、构建诊断、Git、MCP、技能库、知识库和跨会话记忆放在同一个工具窗口中。

当前版本：`0.2.0`

## 能力概览

- 接入 OpenAI 兼容协议或 Anthropic 原生协议的模型。
- 读取当前工程、搜索代码、查找符号、读取编辑器上下文。
- 通过对话执行文件读取、写入、编辑、目录浏览、文件匹配和受控删除。
- 运行构建、测试和 IDE 诊断，并把结果返回到对话中。
- 支持图片、文件、SVG 和资源产物；试验性产物默认写入全局临时会话目录。
- 支持 MCP 客户端，也可以把当前 IDE 暴露为 MCP 服务。
- 支持只读子代理、任务清单、用户确认和多轮 Agent 执行。

## 安装插件

### 安装已有插件包

1. 在 GitHub Releases 下载插件包 `zhixueyao-<version>.jar`。
2. 打开 Android Studio：`Settings` → `Plugins` → 右上角齿轮 → `Install Plugin from Disk...`。
3. 选择插件包并确认。
4. 重启 Android Studio。
5. 重启后，从右侧工具窗口打开「止血药」。

也可以把插件包放入 Android Studio 配置目录下的 `plugins/zhixueyao/lib/`，然后重启 IDE。

### 从源码构建

环境要求：

| 项目 | 要求 |
| --- | --- |
| JDK | 21 |
| Android Studio | 2026.1（build 261）或更高 |
| Gradle | 使用仓库内的 Gradle Wrapper |
| 系统 | Windows、macOS 或 Linux；构建脚本需要本机 Android Studio 安装目录 |

构建脚本按以下顺序查找 Android Studio：

1. `-PstudioPath=...`
2. `gradle.properties` 中的 `studioPath=...`
3. `ANDROID_STUDIO_HOME` 环境变量
4. 常见默认安装目录

Windows PowerShell 示例：

```powershell
./gradlew.bat buildPlugin -PstudioPath="D:/Android studio"
```

Linux/macOS 示例：

```bash
./gradlew buildPlugin -PstudioPath="/opt/android-studio"
```

插件包输出在：

```text
build/libs/zhixueyao-0.2.0.jar
```

`build/`、`.gradle/`、`.intellijPlatform/` 和本机配置不会进入版本库。

## 首次配置

1. 打开「止血药」工具窗口。
2. 打开设置页，在「模型」中选择服务商，填写接口地址、API Key 和模型名。
3. 根据需要选择模型协议、思考强度、最大 token、最大工具轮数和上下文压缩策略。
4. 在「通用」中选择 Agent 预设、沙盒权限、回复语言、代码应用按钮和思考过程显示方式。
5. 保存设置后开始对话。

插件不会把模型 API Key 写入项目源码。Git 账号凭据使用 IDE 的 `PasswordSafe`，不通过 README、源码或普通项目文件保存。

## Git 助手

Git 助手默认关闭。开启后，AI 才能在用户明确允许的范围内执行 `push` 和 `pull`，并使用设置页中的访问方式；未开启时不会因为普通对话自动推送或拉取。

支持的 Git 操作包括：

- 只读：`status`、`diff`、`log`、`show`、`blame`、`branch`、`stash list`。
- 写入：`add`、`commit`、`stash push`、`stash pop`。
- 远程操作：`push`、`pull`，需要先开启 Git 助手。

以下高风险操作不会由内置 Git 工具执行：

- `git reset --hard`
- `git clean`
- 未经开启 Git 助手的 `push` / `pull`

写入操作会记录操作日志；只读 Agent 预设不能执行会改变仓库状态的 Git 操作。

设置页提供「Git 助手说明」窗口，里面会显示开关行为、访问方式、凭据管理器和安全边界。

## Git 访问方式检测

环境自检不依赖 AI 猜测，而是执行确定性检查：

- Git 是否安装以及实际版本。
- 全局提交署名是否包含 `user.name` 和 `user.email`。
- Git Credential Manager 是否已安装、已配置或需要安装。
- Git 全局和当前仓库是否存在失效的代理残留配置。
- 国内站点和 GitHub 是否可以访问。

访问方式支持：

- 直连。
- 用户填写的自定义 HTTP/HTTPS 代理。
- Clash、Clash Verge、v2rayN、SpeedCloud 等本地代理工具。
- `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY` 环境变量。
- Windows 系统代理设置。
- 系统实际监听的回环端口，以及常见代理端口兜底扫描。

代理确认不是只看端口是否打开，也不是只看 `CONNECT 200`。检测会进一步在代理隧道中完成 GitHub 的 TLS 握手，避免把只能访问国内站点或只返回伪成功响应的端口误判为可用代理。

## Git Credential Manager

环境自检会区分三种状态：

- 已安装且已配置：显示正常。
- 已安装但未配置：提供「一键启用」。
- 未安装：优先尝试 `winget`，失败时尝试下载便携版并安装到用户目录。

凭据由系统凭据存储管理，插件不要求把 GitHub Token 写进项目文件。自动安装依赖网络和系统权限；如果网络无法访问 GitHub 且 `winget` 不可用，需要手动安装 Git Credential Manager。

## 技能库、知识库和记忆

### 技能库

技能是带有 `SKILL.md` 的目录，支持渐进式加载：提示词只列出名称和说明，真正使用时才读取正文。

扫描位置：

```text
<项目>/.zhixueyao/skills/
~/.zhixueyao/skills/
~/.workbuddy/skills/（只读兼容扫描）
~/.agents/skills/、~/.claude/skills/、~/.codebuddy/skills/（存在时只读扫描）
```

项目技能优先于全局同名技能。技能还支持触发词、缺口标记、交叉引用和复合步骤描述。

### 知识库

知识库是跨项目共用的 Markdown 资料目录：

```text
~/.zhixueyao/kb/
```

内置工具提供：

- `kb_write`：写入或更新知识页。
- `kb_search`：按相关度检索小节。
- `kb_tags`：查看标签和页数。

它与代码搜索不同：代码搜索用于找确定的文件或字符串，知识库用于找已经沉淀但不一定记得原文的经验和结论。

### 跨会话记忆

记忆分为全局和项目两层：

```text
~/.zhixueyao/MEMORY.md
<项目>/.zhixueyao/MEMORY.md
```

记忆用于保存用户偏好、项目约定和已确认事实。写入会去重并限制单条长度和总条数；项目记忆可以随项目共享，但请不要把密钥、Token、密码或个人隐私写入其中。

## 临时会话和产物

试验性图片、SVG、草稿和导出物默认写入：

```text
~/.zhixueyao/Conversation/Product/
└── <创建时间>/<会话名>/
```

真正需要进入 App 或项目的资源，仍应由用户明确应用到项目中。设置页的「临时会话」可以：

- 查看会话名、创建时间、文件数、大小和路径。
- 全选或单选会话。
- 删除选中的临时产物，并清理空的时间分组目录。
- 打开临时产物总目录。

## 安全边界

- 文件路径受项目沙盒校验，不能借工具访问项目外路径，除非用户选择了相应权限模式。
- 覆盖写入和编辑前会保留备份，可通过恢复工具找回上一版文件。
- 关闭「允许直接写文件」后，AI 不能直接修改文件，只能返回明确的阻止结果。
- Git Token 通过 IDE 的加密凭据存储处理，不落入 README、源码或普通配置文件。
- Git 工具不提供 `reset --hard` 和 `clean`。
- MCP 外部服务器由用户在设置中配置和启停；插件不会替用户猜测或填入凭据。
- 项目仓库的 `.gitignore` 已排除 IDE 状态、构建缓存、本机配置、环境文件和常见密钥文件。

## 异步和设置页行为

目录扫描、Git 检查、网络探测、凭据读取、知识库读取、技能扫描、临时会话统计和 MCP 服务启停都在后台执行。Swing 组件更新会回到 IDE 的 UI 线程，并使用兼容模态设置窗口的调度方式，避免出现「正在检查」「查询中」「读取中」长期不结束的问题。

模型目录请求也有异常保护，网络失败会显示错误而不是永久停留在加载状态。

## 验证工具链

项目内的验证技能位于：

```text
.zhixueyao/skills/zhixueyao-verify-probe/
```

其中包含：

- EDT 阻塞 I/O 静态扫描。
- 死设置和无效设置检查。
- 静默失败检查。
- 敏感信息扫描。
- Git 代理、TLS、动态端口和环境自检探针。
- 知识库、临时会话、MCP 生命周期、原子写入和工具卡片探针。

真实环境回归时，部分 UI 探针需要 Android Studio 图形环境或真实 IDE 实例；`run10` 在无图形环境时会跳过，这不等同于插件功能失败。

## 目录结构

```text
src/main/kotlin/com/zhixueyao/
├── agent/       Agent 循环、技能、记忆、沙盒和会话工作区
├── chat/        会话存档
├── git/         Git 环境、代理和凭据
├── llm/         模型协议和流式请求
├── mcp/         MCP 客户端与 IDE MCP 服务
├── settings/    设置页和临时会话管理
├── tools/       文件、构建、搜索、知识库、媒体和 Git 工具
└── ui/          Swing 工具窗口和对话界面
```

## 已知限制

- GitHub 上传需要用户自己的远程仓库地址和有效认证；本地工程不会自动猜测目标仓库。
- GitHub 或其他外部模型服务不可达时，网络相关功能会失败并显示原因，不能保证通过插件自动修复网络。
- GCM 便携版兜底安装依赖能访问 GitHub；无法访问时请使用系统包管理器或手动安装。
- UI 探针不能在没有图形环境的机器上完整运行。
- SVG 预览受 IntelliJ 平台渲染能力限制；视频文件不在插件内嵌播放。

## 许可

见 [LICENSE](LICENSE)。
