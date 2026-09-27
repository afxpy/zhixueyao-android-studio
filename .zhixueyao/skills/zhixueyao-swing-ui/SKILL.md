---
name: zhixueyao-swing-ui
description: 这个工程里 Swing 界面反复踩的坑：圆角卡片、BoxLayout 对齐、caret 拽滚动、流式节流、Timer 泄漏、静默失败。改 ui/ 下任何文件前必读。
---

# 止血药 · Swing 界面避坑清单

改 `com/zhixueyao/ui/` 下任何文件前，对照这份清单。

## 1. 圆角卡片：`isOpaque = true` 会把底色填成矩形

`background` + `RoundedLineBorder` 只画描边，**实底还是方的**，看起来仍是方框。

```kotlin
isOpaque = false                    // 必须
override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.color = UiKit.card
    g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
    g2.color = UiKit.border
    g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
    g2.dispose()
    super.paintComponent(g)
}
```

气泡、工具卡、代码块卡、附件芯片都是同一个毛病 —— **要一次改全**，别只改看到的那一个。

## 2. 纵向 BoxLayout

- 所有子组件显式 `alignmentX = LEFT_ALIGNMENT`。`JComponent` 默认是 `0.5`（居中），
  混用会把整块内容推偏
- `maximumSize` 必须**覆写方法动态求值**，**不能**在 `init {}` 里赋值
  （那时候 `preferredSize` 还是 0，之后内容变高就被压扁）
- 左右分侧（用户气泡靠右）**不要用 alignmentX** ——
  用「行容器 + 按宽度现算的对侧内边距」（见 `ChatPanel.addBubble` 里的 `Insets` 计算）

## 3. `JScrollPane` 隐藏了还会占高度

内部的 `JEditorPane` 设 `isVisible = false` 没用 ——
外层滚动容器照样贡献 ~28px。**必须整体隐藏滚动容器**（`richScroll.isVisible = false`）。

历史上这就是「正文被挤到气泡底部、没跟头部对齐」的直接原因。

## 4. caret 会拽走外层滚动条 → 一律设 `NEVER_UPDATE`

`setText()` 复位 caret → caret 变化触发 `scrollRectToVisible` →
它会**向上找可滚动的祖先**（在聊天界面里就是外层消息列表）→
每来一片流式内容都可能把用户的滚动位置拽走一次。

```kotlin
(area.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
```

展示型文本区全都要设（bodyArea / richArea / thinkArea / 工具卡详情）。零代价、无语义损失。

> 诚实边界：这条链只在组件 `isShowing`（真的显示在屏幕上）时才生效，
> 而离线探针跑在 headless 环境（`isShowing` 恒为 false），**没能实测**。
> 属于「消除已知隐患」，不是「已复现问题的根治」。滚动跟随那边的事件驱动改造仍然要保留。

## 5. 流式正文：每片全量 setText 是 O(n²)

```kotlin
// ✗ 每来一片就整文档替换 + 重新折行，一次回答上千片
bodyArea.text = textBuffer.toString()
```

改成**节流 + 落尾定时器**（`PLAIN_INTERVAL_MS = 80ms`）：

```kotlin
if (now - lastPlainAt >= PLAIN_INTERVAL_MS) { flushPlainNow(); return }
// 距上次太近 → 排一个一次性 Timer 延后刷新
if (plainTimer == null) {
    plainTimer = javax.swing.Timer(PLAIN_INTERVAL_MS.toInt()) { ... }.apply { isRepeats = false; start() }
}
```

**落尾定时器必须有**：只按时间戳丢帧的话，最后一片可能永远不落地，
正文停在半句话上（探针要断言「最终内容长度 == 期望长度」）。

切到富文本之后就别再刷纯文本了（`if (streamRich) return`）。
实测：1000 片 → 文档变更 7 次，内容完整。

注意 `javax.swing.Timer` 的延时参数是 **Int**，传 Long 编译不过。

## 6. Timer 不随组件消失 —— 移除气泡前必须停

`javax.swing.Timer` 是独立的，组件 `remove()` 之后照样按秒触发。
一个长会话反复清空/切会话会攒下一堆空转定时器。

- `MessageBubble.disposeTimers()` 停 `pendingTimer` / `liveTimer` / `plainTimer`
  + 遍历工具卡 `stopAnimation()`
- `ChatPanel.clearBubbles()` / `removeBubblesFrom()` 移除前统一调
- 别指望每个 remove 点都记得 —— **在移除入口处统一处理**

验证手法：Timer 停没停看不出来，就**看它驱动的数字还会不会涨**
（让计时器驱动一个「已 N 秒」文案，停掉后等 1.4 秒再看数字变没变）。

## 7. 状态要在回合结束时「收摊」

界面上的状态来自模型/工具，它们**不更新了怎么办**？三类都踩过：

| 状态 | 不收摊的表现 | 收摊做法 |
|---|---|---|
| 任务清单 | 回答已完成，还挂着「▸ 进行中」 | `settleTasksOnTurnEnd()` 改成 `interrupted`（标记 `‖`），**不直接抹掉**（下一轮模型会改回来） |
| 工具卡 | 取消后永远「运行中」，spinner 110ms 空转 | 取消路径也要发 `listener.onToolFinish(call, ToolResult("已取消", ok = false))` |
| 耗时 | 重建的气泡算出一个假的「1 秒」 | `finalize(replay = true)` 跳过 `markTurnFinished` |

**通用原则**：凡是「外部输进来、然后就不管了」的状态，都要有一个收尾动作。

## 8. 静默失败是最糟的失败

三类踩过的静默失败：

1. **剪贴板粘贴图片**：`if (!isDataFlavorAvailable(imageFlavor)) return false` 太窄
   （Windows 截图给的是 DIB / `image/png`，它可能返回 false 但其实能读）；
   `getData` 抛异常被 `runCatching` 吞掉；剪贴板里只有图没文本 → `paste()` 什么都不做 →
   **界面零反馈**，用户只能反复按 Ctrl+V。
   修法：遍历全部 flavor + 失败重试 3 次 + 三态结果（`ImageAdded` / `NoImage` / `Failed`）
   + 成功和失败**都要有提示**。也要支持 fileList flavor（复制的图片文件）。
2. **加附件**：`if (!Files.exists(path)) return` 是静默的 → 改成 `updateStatus("加不了附件：…")`
3. **预览打不开**：弹窗说明原因 + 提示可以「用系统默认程序打开」

**规则：凡是「用户主动触发、但可能什么都不发生」的操作，都必须有反馈。**
成功也要说一句（「已把剪贴板图片加为附件（2 个）」），否则用户分不清「收下了」和「没反应」。

## 9. 设置页：想固定左侧导航必须实现 `Configurable.NoScroll`

平台默认会给整个 configurable 再套一层滚动容器，左侧导航会跟着滚走。

## 10. 历史实例会被 `copy()` 换掉，别用身份长期挂引用

`history[i] = history[i].copy(...)`（改用量、改版本、改内容都会这么写）
**每次产生新对象**。所以任何长期持有的引用（比如气泡的 `linkedMessage`）
都会在下一次 copy 之后失效。

真实事故：流式气泡是 `addBubble(it)` 建的、`linkedMessage` 本来就是 null，
而 `switchVariant` 第一行 `linkedMessage ?: return` 直接**静默返回** ——
表现是「版本切换器（‹ 2/2 ›）点了没反应」。

两条修法一起上：
1. **每次改完历史，把 `bubble.linkedMessage` 重新指向最终的实例**（唯一可靠时机）
2. 查找时**别只靠 `===`**：找不到就退回「最后一条 assistant」，
   并且失败时**给出状态栏提示**，不要静默 return

## 11. 删除函数的返回值别拿 `delete()` 当结论

```kotlin
// ✗ walkBottomUp 已经把目录删掉了，再 delete() 必然返回 false
dir.walkBottomUp().forEach { it.delete() }
return dir.delete()
// ✓ 判据只能是「它还在不在」
dir.walkBottomUp().forEach { runCatching { it.delete() } }
return !dir.exists()
```
探针抓出来的：删成功了却全部报「删除失败 0 个」。

## 12. 自己写的元数据文件别算进用户的统计

临时目录里放了个 `.session.json` 标记（记会话 id / 标题 / 创建时间，供清理页用）。
统计「文件数 / 占用」时要 `if (f.name != MARKER)` 排除它，
否则用户看到的数字永远比实际多 1 个。

## 13. `BorderLayout.CENTER` 会**无视** preferredSize / maximumSize

这条坑了两次，而且是同一个根因的两次表现（用户都反馈了）：

> 「怎么没看到删除按钮，可能是你的列表框不是一个可滑动的框框，
> 所以导致按钮都在很下面」；
> 「插件这里也是列表框太长了导致的，要我一直滑很久下去才可以看到按钮，
> 但是这非常不方便，万一我选错了但是没注意怎么办？」

```kotlin
// ✗ 放进 CENTER：容器有多大，表格就多高
panel.add(table, BorderLayout.CENTER)
// ✗ 更隐蔽：用匿名类覆写 preferredSize「限高」也**没用**，CENTER 不读它
object : JPanel(BorderLayout()) {
    override fun getPreferredSize() = Dimension(0, 200)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 200)
}
```

**探针实测**：容器高 2000 时，`CENTER` 里的表格高度 = **2000**；
换成 `NORTH` + 固定高度的滚动容器 = **238**（且 1 行 / 50 行都是 238）。

修法：用 `com.zhixueyao.ui.TableSection.panel(table, toolbar, header)`
—— 它把表格放进**固定高度**的 `JBScrollPane`，整块按 `NORTH` 排布，
工具栏永远紧贴表格下方（探针验证 `表格底 y=220 / 工具栏 y=228`）。

**通用规则**：想让一个组件「就占这么高」，**必须放 NORTH/SOUTH 或 BoxLayout**，
不能放 CENTER。放 CENTER 的一切限高代码都是自欺欺人。

## 14. 文件路径：绝对路径不能拼到工程根下面

```kotlin
// ✗ 临时产物在工程外（~/.zhixueyao/Conversation/...），
//   拼出来是「工程根\C://Users//...」，自然找不到 → 卡片显示「读不出来」
val file = File(project.basePath, relPath)
// ✓ 先看是不是绝对路径
val file = File(relPath).let { if (it.isAbsolute) it else File(base, relPath) }
```

**通用规则**：任何「用户/工具给的路径」都要先判 `isAbsolute` 再决定是否拼根目录。

## 15. 正文里的链接要接住（模型爱写 `[下载 xxx.svg](...)`）

`JEditorPane` 默认**不处理链接**，点了毫无反应。挂一个 `HyperlinkListener`：
本地图片/视频 → 开预览；其余 → 交给 IDE 打开。
链接可能写成 `file:///C:/x.svg`、`C:/x.svg`、或工程相对路径 —— 三种都要认。

## 相关探针

`TimerLeakProbe` / `RenderQualityProbe` / `PasteWiringProbe` /
`HistoryGroupingProbe`（都在 `%TEMP%/zx-probe/`）