---
name: zhixueyao-agent-context
description: 会话隔离、上下文成本（token 预算）、图片预算、steer 追加输入、历史与界面轮次不一致。改会话/上下文/气泡渲染前必读。
---

# 止血药 · 会话、上下文与轮次

## 一句话背景

这个工程最贵的一类 bug 都出在「**一个状态该属于谁**」：
属于会话还是全局？属于这条消息还是整个面板？属于这一轮还是整段历史？
下面每一条都是踩过之后总结的。

## 1. 会话隔离：换会话要清的东西不止 history

**症状**（用户原话）：多个会话共用一个上下文。

`newSession()` / `openSession()` 里只换 `history` 是不够的。界面上一堆状态跟着会话走，
漏一个就串场。统一走 `ChatPanel.resetPerSessionState()`：

| 要清 | 漏了会怎样 |
|---|---|
| `attachments` + `refreshAttachmentBar()` | A 会话选的图，切到 B 还挂着 |
| `queued` + `refreshQueuedStrip()` | 排队的内容跨会话生效 |
| `steeringInbox` | 运行中追加的话插到别的会话 |
| `lastTasks` + `renderTasks(emptyList())` | 挂着上个会话的「已完成 3/5」 |
| `artifacts` + `loadArtifacts()` | **上个会话的产物会按新会话 id 写回去，正式污染** |
| `summaryState.reset()` | 新对话「记得」没发生过的事 |
| `regenVariants` / `liveBubble` / `trimmedRows` / `followState` / `cancelRequested` | 残留状态影响下一轮 |

**顺序陷阱**：`loadArtifacts()` 按 `currentSessionId` 取内容，
所以**必须先更新 `currentSessionId` 再调**，否则加载的还是旧的。

## 2. 上下文成本：先量清单，别猜

实测一个简单问题的固定开销（`TokenBudgetProbe`）：

| 项 | 量 |
|---|---|
| 系统提示词模板 | ~2,000 tokens |
| 技能目录（7 个技能） | ~700 tokens（瘦身前 1,432） |
| 工具 schema（21 个工具） | ~3,000 tokens |
| **固定开销合计** | **~6,000 tokens/轮** |

固定开销只占一小部分。**大头是历史里的工具结果** ——
`read_file` 读回来的文件内容会一直挂在历史里、每轮重发。

三条硬规则：

1. **工具结果上限按 token 估，不按字符数**。
   `30,000 字符`对中文就是 3 万 token（1 字≈1 token，字符数严重低估 3 倍）。
   现为 `AgentRunner.MAX_TOOL_RESULT_TOKENS = 6_000`，超了**留头留尾**
   （开头是结构/声明，结尾是收尾内容，中间省略 + 提示可分段读）。

2. **「等超预算再压缩」太晚**。
   压缩阈值按模型窗口算（如 100k×70%），44k 根本不会触发 ——
   而**在那之前的每一轮都在白花这份钱**。
   所以 `ContextCompactor.slimOldToolResults` 是**每轮无条件**执行的
   （保留最近 4 条工具结果 + 最近 1 轮）。实测 43 万 → 12 万 token（-71%）。

3. **技能目录是每轮固定开销**。
   别在目录里列「附属文件清单」这类用到时自然能看到的信息，
   描述压到 110 字够判断「是不是我要的」就停。

## 3. 图片预算：「看过的留 N 张，没看过的一张不动」

`ContextCompactor.slimOldImages`（借鉴 astravia 的 image budget）。
图片 base64 每轮重发，一张截图轻松上万 token。

- 规则：「看过的」旧图只留最新 `maxRecentImages`（默认 2）张，
  「没看过的」**无条件保留**
- 「看过没看过」的判定是**确定性、无状态**的：位于**最后一条 assistant 之后**的图
  就是这次调用第一次看到的
- **不能一刀切删**：删了模型还没看的图会造成「读了等于没读」的幻读
- **按「张」去留，不是按「消息」** —— 一条消息可以带多张图（第一版按消息粒度写错了）
- 设置项「保留旧图片张数」，0 = 不限制

## 4. 历史条数 ≠ 界面轮数

**症状**（用户原话）：我就发了一句话，为什么回复多个带框的内容。

历史按 API 协议组织：一次带工具调用的回答有**两条** assistant
（第一条「我打算怎么做」+ toolCalls，第二条最终结论）——协议要求，不能合并。
但界面上一轮对话**只是一个**回答框。

`HistoryGrouping.group(history)` 是纯函数：按「USER 到下一个 USER 之间」为一轮，
组内多条 assistant 正文**直接拼接**（口径必须和流式时的 `finalText.append(...)` 一致，
否则重建出来的和刚才看到的又不一样）。`renderHistory` 按轮渲染。

**顺带**：重建的气泡不要显示耗时 —— 没有真实起始时间，会算出个假的「1 秒」。
`MessageBubble.finalize(replay = true)` 跳过 `markTurnFinished`。

## 5. steer：运行中追加输入

用户看到 Agent 跑偏时，不该只能「停止 → 等收尾 → 重说一遍」——
中间做的工作全废，而且 MCP 调用最长要等 2 分钟才停得下来。

- `ChatPanel.steeringInbox`（`ConcurrentLinkedQueue`，EDT 写、Agent 后台读）
- `AgentRunner.run(steering = { ... })` 在**每个 turn 边界**取一次（poll，取走不重复）
- 取到就 `history.add(ChatMessage.user(text))`，模型带着新信息重新规划，**已做的工作保留**

与「排队等本轮结束」是**两条不同通道**：Enter = 插入本轮（纠偏），Alt+Enter = 排队（新话题）。

## 6. 不要重复实现

`openSession` 曾经自己抄了一份渲染循环，结果给它加 token 页脚时只改了 `renderHistory` ——
载入会话后页脚和版本切换器都不显示。**修法是删掉重复那份、两条路径共用**，不是再补一遍。

## 7. 产物放到工程外之后，会连带出三个「以为文件不存在」的问题

临时产物改到 `~/.zhixueyao/Conversation/...` 之后（见 project-map），
所有**按工程内文件判断**的地方都会误判：

| 症状 | 原因 | 修法 |
|---|---|---|
| 气泡里没有附件卡片 / 显示「读不出来」 | `File(projectBase, relPath)` 拼绝对路径 | 先判 `isAbsolute` |
| 黄条警告「这些文件在项目里没找到：xxx.svg」 | `ReferenceChecker` 只查工程内文件名 | `check(project, text, extraKnownNames)` 把本会话产物名传进去 |
| 点产物没反应 | 产物面板只认工程内路径 | 同上，走 `isAbsolute` |

**通用规则**：一处存储位置变了，要把所有「假设它在哪」的代码都过一遍 ——
这类问题不会报错，只会**静静地少显示一个卡片、多报一个警告**。

## 相关探针

`TokenBudgetProbe` / `TokenSlimProbe` / `ImageBudgetProbe` /
`HistoryGroupingProbe` / `SteerProbe`（都在 `%TEMP%/zx-probe/`）