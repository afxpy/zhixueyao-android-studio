---
name: zhixueyao-verify-probe
description: 这个工程怎么离线验证改动——探针怎么写、跑什么、headless 环境哪些验不了。改完代码要证明改动生效时读。
triggers: 验证, 探针, 怎么证明, 回归, 发布前检查
---

# 止血药 · 离线验证方法论

## 为什么不用 IDE 里手点

插件装到 AS 里再手点验一次，一轮几分钟。离线探针是**秒级**的，
而且能覆盖「边界值、异常路径、并发」这些手点很难触发的场景。
本工程积累了 40+ 个探针，改完必跑全量回归。

## 探针放哪、怎么跑

- 目录：`<本机临时探针目录>/`（**绝不放工程里**，用户明确不要）
- 每个探针一个 `.java` + 一个 `runN.sh`，`runN.sh` 负责编译 + 设置类路径 + 执行
- 类路径 = 插件 jar + `D:/Android studio/lib/*.jar` + `plugins/{java,Kotlin,android}/lib/*.jar`
- 模板：从任意一个已有 `runN.sh` 复制（`sed 's/旧探针名/新探针名/' run5.sh > run42.sh`）

## 探针要验什么

**验「不依赖真实显示」的东西**：布局尺寸、首选高度、文本内容、纯函数逻辑、
状态机流转、并发语义、路径校验。

标准结构（每个探针都该有）：

```
=== ① 正常路径 ===      ← 改动应该生效的场景
=== ② 关键边界 ===      ← 最容易写错、也最值得断言的那一条
=== ③ 边界/异常 ===     ← 空输入、只有一条、超限、并发
=== ④ 无法离线验证的部分 ===  ← 如实标注，别假装验过
判定：...
```

## headless 环境：有一整类行为验不了

探针里 `new JFrame()` 会抛 `HeadlessException` —— 本机没有图形环境。
**所有依赖「真实显示」的 Swing 行为都验证不了**：
caret → `scrollRectToVisible` 冒泡、滚动条交互、焦点、真实绘制。

```java
if (java.awt.GraphicsEnvironment.isHeadless()) {
    System.out.println("headless → 这段无法验证，跳过（需真机确认）");
} else { /* 真机才跑 */ }
```

**必须先判断再动手**，不要先建窗口再 catch：
上一轮探针就是先 `new JFrame()` 才报错，白跑一轮。

依赖显示的一律标注「**未能验证，需真机确认**」，
代码注释里也要如实写 —— 否则下一轮会把推断当事实，在错的前提上继续改。

## 探针取值的两条经验

1. **优先反射读私有字段**，别用「找第一个某类型控件」。
   真实翻车：「找第一个 JTextArea」拿到了别的控件，于是得出「内容丢了」的**错误结论** ✗
   ```java
   static Object field(Object target, String name) { /* 沿父类链找私有字段 */ }
   JTextArea body = (JTextArea) field(bubble, "bodyArea");
   ```
2. **单元级验证**（改一行跑一次）比端到端更可靠 —— 端到端一出问题要排查整条链。

## 断言写法

- 断言要**能区分「没做」和「做错了」**，例如
  「节流生效」不能只断言 `count < 1000`，还要断言**内容完整**
  （`最终长度 == 期望长度`）——否则「把内容丢了」也会让 `count` 很小，假通过
- 期望值要先想清楚语义再写。我自己犯过两次「探针期望写错、误报失败」：
  图片预算那题，预算 2 就是**保留最新 2 张**，不是「全部砍掉」
- 探针里记得 `System.exit(0)`：起了 `HttpServer` 之类非守护线程，JVM 不会自己退

## ★★ 对照组：防止「假通过」（最重要的纪律）

**背景**：有两个场景曾经**绿着但什么都没验到**。

```java
// 原意：验证「超时后进程会被杀掉」
var r = run(slowCmd, timeout = 1.5s);
judge("按时超时", ms < 6000);        // ← 用的却是 git help -a（183ms 就跑完）
```
命令根本没慢到触发超时，`timedOut` 是 false，但「耗时 < 6 秒」**照样成立**。
取消场景同样：取消标志还没设置，命令已经结束了。

根因：**只断言了「结果」，没断言「前提」**。

### 规矩

> 凡是测**超时 / 取消 / 并发 / 缓存 / 性能**的场景，
> 必须先跑一次**对照组**证明「不加干预时，它确实会慢 / 会冲突」。
> **对照组不过，实验组的结果一律不算数。**

```java
// 对照组：不干预，证明它真的要跑好几秒
long plainMs = ProbeKit.timed(() -> run(slowCmd, timeout = 30s));
ProbeKit.control("慢命令确实要跑 >6 秒", plainMs > 6_000, plainMs + "ms");

// 实验组：设 1.5 秒超时，证明被终止了
var r = run(slowCmd, timeout = 1.5s);
ProbeKit.judge("按时超时且被终止", r.timedOut() && ms < plainMs / 2, "");
```

`ProbeKit`（探针目录下的 `ProbeKit.java`，已编译成 class）提供：
`judge` / **`control`** / `timed` / `timedValue` / `summary`。
`summary()` **用退出码表达成败**（0 通过 / 1 不过），比让人去 grep 输出可靠得多。

### 对照组失败不等于产品有 bug

这点要分清：**对照组失败 = 测试设计有问题**（前提不成立），
不是被测代码坏了。所以 `ProbeKit` 把它们**分开计数**，
否则会被当成产品 bug 去查半天。

### 附带要求：断言要落在**状态字段**上

时间断言只能作为**辅助**。比如「取消生效了」应该断言
`r.cancelled() == true`，而不是只断言「耗时 < 2 秒」。
只断言时间的话，命令快一点、机器快一点都会让它假通过。

### 还有一条：断言要符合**真实契约**，别凭想当然

写「取消后应该没有任何产出」时被我写成了 `finalText == null`，
结果失败 —— 查代码才发现 `AgentRunner` 的取消分支**确实会调 `onComplete`**
（带已累积的部分文本），因为界面要靠它把气泡收尾。
**这是正确的**，错的是我的想象。

遇到「断言失败」，先分清是**代码错了**还是**你的预期错了**。
探针的价值之一就是逼你把契约看清楚。

## 🔴 「测试通过」≠「测试真的跑了」（本项目最严重的一次过程事故）

做 `regress.sh` 之后第一次跑，汇总里出现 **13 个退出码非零**，
而当时 grep 判定说它们是「干净的」。逐个查下来：

```
错误: 编译失败
    原因: 实际参数列表和形式参数列表长度不同
```

**10 个真实探针编译失败 → 什么都没执行 → 而我前几轮一直说的「全量回归零失败」是假的。**
31 个探针里有 10 个是摆设。

### 为什么一直没发现

判定一直是「grep 输出里的失败关键字」，而「错误: 编译失败」**确实在关键词列表里**。
但 `grep` 在 Windows 下因为中文输出**被当成二进制**，匹配**静默失效**了 ——
没有报错、没有提示，判定直接返回「干净」。
（这正是我在 `regress.sh` 注释里当「理论风险」写下的那条，结果是正在发生的现实。）

### 根因：改公开 API 签名之后没跑回归

| 探针 | 缺什么 |
|---|---|
| 10 个界面探针 | `MessageBubble.finalize` 加了 `stopped` / `replay` |
| VariantProbe | `ChatMessage` 构造加了 `promptTokens` / `completionTokens` |
| ToolCardsCapProbe | `ToolResult` 构造加了 `attachments` |
| ReferenceCheckProbe | `ReferenceChecker.check` 加了 `extraKnownNames` |
| InputHeightProbe | `MockProject` 的平台构造签名变了（`app` → `PicoContainer`） |

**Java 调 Kotlin 时默认参数不存在，必须全给 —— 所以「加一个带默认值的参数」
就是一次破坏性变更。** 每次都会静默杀掉一批探针。

### 三条纪律

1. **判定以退出码为准**。`ProbeKit.summary()` 会用 0/1 表达成败；
   没迁过去的探针至少也要 `System.exit(0)` 明确表示通过。
   **grep 只能当兜底，且不要在中文环境下信它。**
2. **改任何公开方法的签名后，立刻跑一次全量回归。**
   这是唯一能发现「探针批量失效」的时机。
3. **探针不要依赖平台的内部测试类**（`MockProject` 这类）。
   它们的构造签名会随平台版本变 —— 用 `Project` 动态代理，
   探针通常只需要 `basePath` 可用。

## 静态扫描：「只挂一次的回调捕获了会变的值」

脚本：`scripts/scan_captures.py`（随本技能走）。

```bash
python .zhixueyao/skills/zhixueyao-verify-probe/scripts/scan_captures.py
```

### 口径是怎么定下来的（实测，不是猜）

最初的想法是「扫 ui/ 下所有 lambda / 匿名类，标出捕获了会变字段的地方」。
真去数了一遍：

| 口径 | 候选 |
|---|---|
| `ui/` 下函数总数 | 349 |
| **有回调注册的函数**（宽口径 = 最初的想法） | **27 个 / 42 个注册点** |
| **其中「只挂一次」的**（窄口径） | **1 个 / 2 个守卫点** |

**宽口径下几乎每个带交互的函数都会被卷进来**（按钮、列表、输入框都会挂监听），
人工过一遍不现实。**加上「只挂一次」这一个条件，压掉 96%。**

### 判据：三个条件同时成立

1. 闭包在**点击 / 触发时**才读那个值（不是注册时用一次）
2. 那个值**在注册之后还会变**
3. 闭包**只注册一次**（`getClientProperty(...) == true) return` 这类守卫）

**第 3 条是关键** —— 闭包若每次渲染都重挂，捕获的就是新值，无害。

### 它是**清单生成器**，不是门禁

- **永远返回 0**，不进 `regress.sh` 的失败判定
- 做成 CI 门禁的话，人会为了「消警告」改坏代码 —— 而这里**很多捕获是正确的**
- 它保证的是**不漏**（宁可多报）；精度靠另一层补：行为探针
  （`StaleContentProbe` 那种，钉住「内容变了之后回调读的到底是不是新值」）

### 真实命中长什么样

```
MessageBubble.kt:1525  applyCollapseIfNeeded()
    捕获 参数「source」
    val now = currentText().ifBlank { source }
```

**这一处不是 bug** —— `source` 是缓冲区为空时的兜底。
脚本输出**带上那一行上下文**，就是为了让人两秒判读完，而不是去翻代码。

> 「有意保留的兜底」正是需要人工确认的典型。**脚本不该替人下结论。**

### 扫描器自己也会误报

第一版把 `val now = currentText()` 报成了「捕获」—— 而 `now` 是在**回调块内部**
声明的局部量，根本没被捕获。修法：在回调块里再排一次内部声明的名字。

**通用规则**：写静态扫描时，先拿**已知的正确代码**跑一遍，
看它会不会把正确的东西报出来。**扫描器的误报也要修**，
否则清单会慢慢没人看（和「门禁被人绕过」是同一个下场）。

## 「跳过」要单独统计，不能算通过

`InputHeightProbe` 要 `new` 一个真实的 `ChatPanel`，而它的构造函数里装 `DropTarget`
—— headless 下直接抛 `HeadlessException`。**它从写出来那天起就没执行过。**

处理方式：探针开头显式判断并打印 `[SKIP]` 标记，`regress.sh` 见到就计入**单独的跳过栏**。

> **跳过既不是通过也不是失败。**
> 算成通过 = 假装验过了；算成失败 = 让人去找一个根本不存在的 bug。

汇总长这样：

```
通过 52 / 不过 0 / 跳过 1（共 53 条）

跳过的探针（需真机或在有图形环境的地方复跑）：
  - run10
```

## 回归怎么跑

`bash regress.sh`（在探针目录）：
- **退出码为准**（`ProbeKit.summary()` 的 0/1）
- **grep 兜底**（给还没迁到 ProbeKit 的探针）
- 两个信号任一报警就算不过 —— 宁可多报，不可漏报

判定关键词**只放在明确表示失败的短语上**（`失败 ✗` / `前提不成立` / `编译失败`）。
别用 `Exception` / `错误` 这种词：探针会**刻意制造异常并如实打印**
（「坏 SVG 抛了 WFCException」），那是**说明**不是**失败** —— 这个误判踩过。

## 字节码复核（不能跑探针时）

只有「接线是否真的接上」这类问题，可以用字节码复核兜底：

```python
import zipfile
z = zipfile.ZipFile(安装目录的 jar)
d = z.read('com/zhixueyao/ui/ChatPanel.class')
print('OK' if '新加的中文提示'.encode() in d else 'MISS')
```

注意：**lambda / 匿名类里的字符串在单独的 class 文件里**
（如 `MessageBubble$cardShell$card$1.class`）。
全类扫描：

```python
for n in z.namelist():
    if n.endswith('.class') and '要找的字符串'.encode() in z.read(n):
        print(n)
```

## ★ Agent 级行为回归（单元探针覆盖不到的那一层）

**26 个探针原来全是单元级** —— 测函数、测布局、测解析。
但历史上最贵的 bug 一条都不在那儿，全在 Agent 循环里：

| 出过的事故 | 在哪一层 |
|---|---|
| steer 的追加输入模型没看到 | Agent 循环 |
| 点「停止」要等 2 分钟 | Agent 循环（MCP 分片等待） |
| 工具报错整轮崩掉 | Agent 循环（错误恢复） |
| 一句话渲染成好几个气泡 | 历史 → 界面 |

**做法**（借自 insight-agents 的 `evals/fault_inject/`）：

1. `AgentRunner.run()` 加 `providerOverride: LlmProvider? = null`
   —— 默认 null，行为**逐字节不变**（mavis 的 "Off by default" 原则）
2. `ScriptedProvider`：脚本化假模型。
   `Turn.textOf("...")` / `toolOf(name, args)` / `errorOf(msg)` / `slowOf(ms, text)`
   - `errorOf` **真的抛异常**（不是回一个优雅的错误码）—— 真实世界的断网就是抛异常
   - `slowOf` 期间检查 cancelFlag —— 这样才测得出取消够不够快
   - `Exhausted.FAIL`（默认）：脚本用完还被请求 → **说明循环多跑了一轮，要响亮地失败**
3. `AgentLoopProbe`：8 个场景驱动真实循环（工具编排 / 工具失败后继续 /
   模型抛异常 / 取消立刻生效 / steer 注入 / 脚本用完 / usage 累加 / 流式拼接）

**Java 调 Kotlin 的两个坑**：
- Kotlin 的 vararg + 默认参数在 Java 里调不动 → 给 `Turn` 加一组
  `@JvmStatic` 的不带默认参数工厂（`textOf` / `toolOf` / `errorOf` / `slowOf`）
- `AgentRunner.run` 的参数是 `Function0` / `Function1`，Java 里要写
  `kotlin.jvm.functions.Function0<List<String>>`
- **依赖是 final 类时换个思路**：`McpManager` 既不能继承也不能代理，
  与其硬造假对象，不如把参数改成**可空**（「没有 MCP 的运行器」本身就是合理状态）

**探针抓出来的两个真 bug**：
1. `AgentRunner` 对 `provider.streamChat` **没有 try/catch 兜底** ——
   现有 provider 各自内部 catch 了，但那是实现细节不是接口契约，
   漏一处就直接冒到界面。已补兜底（取消导致的异常不算错误）。
2. 探针自己的 `onError(String, Throwable)` 写成了**重载**而不是覆写 ——
   接口里只有一个参数。表现是两个场景都误报「没有报错」。
   **写监听器实现时先看清楚接口签名**，多一个参数编译器不会拦你。

## ★ 沙箱逃逸回归集

借自 insight-agents 的 `evals/golden/sandbox_security.yaml`（6 类逃逸）。
`SandboxEscapeProbe` 覆盖 22 条，分三组：

- **必须被拒**：`..` 上跳、绝对路径系统目录（大小写）、
  **同前缀的兄弟目录**（`/proj` vs `/proj-evil`）、父目录本身
- **必须放行**：项目内相对/绝对/新文件/`./a/../` 归一化/项目根
- **不能过严**：项目内叫 `WindowsBackup`、`etc` 的目录**不能**被误挡
  （过严和安全一样是 bug）；空路径拒绝、引号包裹的合法路径放行
- **软链接绕过**：项目内 `link -> C:/Windows`，写 `link/System32/...`

**抓出来的真 bug**：黑名单**在 Windows 上是死代码**。
常量和比较串的分隔符不一致 ——
`blockedPrefixes` 写的是 `"c:\\windows"`（Kotlin 字面量带反斜杠），
而比较前已把路径统一成 `/`，**永远匹配不上**。Linux 的 `/etc` 没反斜杠所以没事。
后果：用户一切到「全盘沙盒」，系统目录就完全没保护。
修法：常量统一写 `/`，并且**按路径段匹配**（`==` 或 `prefix + "/"`）而不是裸前缀 ——
后者会把 `WindowsBackup` 一起拒掉。

**通用规则**：内置的黑名单/白名单常量，写完要**真跑一次打招呼的用例**；
这类「字符串比较」的错误编译器一个都不会拦。

## 发布 / 分享前的泄漏检查（**用真实值验，别只靠正则**）

用户会问「你不会把我的 API key 也打包进去了吧」——
这时候**不能只回答「没放」**，要拿**真实的值**去搜一遍才算数。

做法（两步，缺一不可）：

1. **从真实配置里把值提取出来**。本插件的配置在
   `<IDE 配置目录>/options/zhixueyao.xml`（`@Storage("zhixueyao.xml")` 声明），
   不是 `%APPDATA%/.../plugins/` 下面。
   把 `baseUrl` / `apiKey` 以及形如 `sk-xxx` 的串全部抓出来。
2. **逐条在交付目录里搜**（**包括 jar 内部**，要解 zip 逐条目搜）。
   正则扫「像密钥的东西」只能算辅助 —— 自定义中转站地址不长成 `sk-` 的样子，
   正则一定漏。

判定时注意区分：
- **内置服务商的公开地址**（`api.openai.com` / `api.deepseek.com` 等）本来就写在源码里，
  是给用户选的预设，命中它们**不算泄漏**
- **用户自己配的地址**（中转站域名）命中才是真问题

顺带把「会被打包的目录」列一遍：`build/`、`.gradle/`、`.idea/`、`.workbuddy/`（对话日志）
这些本来就不该进版本库，`.gitignore` 和打包脚本都要挡住。

## 每轮收尾动作

1. 跑全量回归（把所有 `runN.sh` 挨个执行，grep 出失败关键词）
2. `md5sum` 核对装进插件目录的 jar 确实是新构建的
3. 更新工程日志 `.workbuddy/memory/YYYY-MM-DD.md`
4. 把新踩的坑写进技能（就是这些文件）

---

<!-- gaps: ["真机交互类验证（点击、滚动、悬停）还没找到自动化办法", "多线程竞态只覆盖了取消路径", "LLM 真实输出的质量无法离线评判（只能靠 golden case + 真机）"] -->
