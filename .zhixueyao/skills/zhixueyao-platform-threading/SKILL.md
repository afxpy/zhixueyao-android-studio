---
name: zhixueyao-platform-threading
description: Android Studio 2026 平台 API 用法：read action、线程归属、模块化 jar、SVG 渲染入口、路径校验绕过。碰到平台 API 报错或要调新 API 时读。
triggers: read action, 平台 API, 线程报错, 取消, 超时, SVG 渲染
---

# 止血药 · 平台 API 与线程

## 1. 铁律：碰平台 API 先问「在哪个线程？要不要 read action？」

真实事故（用户贴过完整栈）：

```
RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only
  at FilenameIndex.getAllFilenames
  at ReferenceChecker.knownNames(ReferenceChecker.kt:60)
  at ChatPanel.runAgent$2$1.onComplete$lambda$9(ChatPanel.kt:2349)
  at ChatPanel.runAgent$withBubble$lambda$0(ChatPanel.kt:2261)   ← withBubble = edt
```

**索引 / PSI / VFS / 文档模型**的读取必须在 read action 里，EDT 上直接调会抛异常。
下面这段是**反面教材**：注释写着「放在后台线程做」，代码却写在 `withBubble { }`（= `edt`）里。
**注释和实现不一致时，代码说了算** —— 这类 bug 编译期和探针都抓不到，只能真机跑。

三条纪律：

1. **包在工具/校验器自己内部**（`ReadAction.compute { ... }`），
   而不是指望调用方记得包 —— 这样 API 无论谁调、在哪个线程调都不会错
2. **同时把调用挪出 EDT**：即便有 read action，索引查询也是重活
3. **写完 grep 一遍全工程的索引访问**：
   `FilenameIndex` / `PsiManager` / `FileBasedIndex` / `ProjectFileIndex` / `PsiDocumentManager`，
   逐个确认有没有 read action

**已知修好的三处**：`ReferenceChecker.knownNames`、`CodeBlockSupport.locateTargets`、
`ChatPanel.currentEditorFileName`。`SearchTools` / `BuildTools` 本来就包了。

## 2. 阻塞等待要能被主动放掉

`askChoice` 用 `latch.await(5, MINUTES)` 等用户点按钮。工具窗口一关，
**没人能点那个按钮**，后台线程还在原地等满 5 分钟 ——
`dispose()` 里置的 `cancelFlag` 在 `await` 里根本看不到。

```kotlin
private val pendingChoices = CopyOnWriteArrayList<CountDownLatch>()
// askChoice：等待前 add，等待后 remove
// dispose()：forEach { it.countDown() } + clear()
```

**规则**：任何「等外部事件」的阻塞等待都要能被主动放掉。
光设一个取消标志不够 —— 阻塞中的线程读不到它。

## 3. 长超时的调用要「分片等待」，否则取消等于没做

用户点「停止」却要干等 2 分钟（MCP 调用），是能直接感觉到的 bug。

```kotlin
// ✗ 一次等满：取消标志在 await 里根本看不到
call.latch.await(timeoutMillis, MILLISECONDS)
// ✓ 分片等待：每 100ms 醒一次看取消标志
val deadline = System.currentTimeMillis() + timeoutMillis
while (true) {
    if (cancelFlag?.get() == true) { pending.remove(id); throw McpException("已取消") }
    val remain = deadline - System.currentTimeMillis()
    if (remain <= 0) { pending.remove(id); throw McpException("等待响应超时（…）") }
    if (call.latch.await(minOf(100L, remain), MILLISECONDS)) break
}
```

HTTP 侧同理：`HttpClient.send()` 换成 `sendAsync()` + `future.get(100ms)` 轮询，
取消时 `future.cancel(true)`。**标志要一路传下去**
（`executeToolSafely` → `McpManager.callTool` → `McpClient.callTool` → `request` → `transport.exchange`），
中间任何一层漏掉，取消就在那层失效。

**Kotlin 没有 `break <值>`**（那是 Java 的）：写成 `val x = while(true) { break v }`
会报 `Only expressions are allowed here`。改成循环外 `var got: T? = null` 接。

## 4. 同一个问题不能有两种提示

超时时用户看到的提示取决于 `HttpClient` 自己的请求超时和我们的 deadline
**谁先到**（竞态）—— 结果同一个问题有时是中文提示、有时是 JDK 的英文 `request timed out`。
单跑一次很容易以为对了。

修法：把 `ExecutionException` 里的超时（`HttpTimeoutException` 或 message 含 `timed out`）
也统一成同一句提示。
**凡是「两个来源都能产生同一个错误」的地方，都要把对外表现统一。**

## 5. 编译类路径（AS 2026 模块化布局）

从 2026 版起平台被拆成 `intellij.platform.*.jar`，`local()` 只把 `app.jar` 等聚合包放进类路径，
`Project` / `PsiFile` / `CompilerManager` 都解析不了。

`build.gradle.kts` 里已用 `compileOnly(fileTree("D:/Android studio/lib") { include("*.jar") })`
补上（另含 `plugins/java`、`plugins/Kotlin`、`plugins/android`）。
用 `compileOnly` 而非 `implementation` —— 运行时这些类由 IDE 提供，不该打进插件包。

排查工具：`./gradlew dumpClasspath -q` 打印实际生效的 jar 列表。

## 6. SVG 渲染：用 `SVGLoader`，不要自己写渲染器

**曾经写过一条错注释**：「JDK 里没有 SVG 渲染器，硬做预览只能写个只支持子集的渲染器，
比不给更误导」—— 那是**按 JDK 说话，忘了平台**。

平台自带 SVG 渲染（底层 jsvg），和 IDE 画图标是同一条路径：

```kotlin
com.intellij.util.SVGLoader.load(inputStream, scale)   // 返回 java.awt.Image
```

**选这个入口的理由**：它是 Kotlin `object` 上的 `@JvmStatic` 函数，签名 Java 可见、
不带名字改写。而 `com.intellij.ui.svg.SvgKt.renderSvg` 会被编译成
`renderSvg-0e6sKCk`（value class 改写），且 Kotlin **不能按文件门面类名（`XxxKt`）引用**
—— 写了会报 `Unresolved reference 'SvgKt'`。

**两遍渲染**：先按 `scale = 1f` 探固有尺寸，再按「目标约 1200px」算倍率重渲。
必须这么做 —— 图标类 SVG 固有尺寸常常只有 24×24，1:1 渲染出来放大就是马赛克。

`SVGLoader` 返回 `java.awt.Image`，尺寸要 `getWidth(null)` 取，Kotlin 里没有 `.width`；
要算缩放比例就先落成 `BufferedImage`。

## 7. 路径校验：字符串前缀骗不过软链接

```kotlin
absolute.startsWith(base)   // ✗ 项目里放一个 link -> C:/Windows 就废了
```

修法：对「看起来在项目内」的路径按**真实路径**（`toRealPath()`）再核一次；
新建文件还不存在时，用**最近的已存在祖先**判断。提示里要写出真实路径，
否则用户不知道为什么被拒。

## 10. Git Bash 会改写参数里的 `:` —— 别用它验证「文件在不在远端」

**症状**：`git cat-file -e "origin/main:path/to/file"` 报「缺」，
但 `git ls-tree -r origin/main` 明明列着这个文件。

**原因**：Git Bash（MSYS2）对参数做 POSIX 路径转换，
`a:b` 在它眼里像「盘符路径」，被改写成 `a;b`。报错信息里能看到轨迹：

```
fatal: ambiguous argument 'origin\main;.zhixueyao\skills\...':
unknown revision or path not in the working tree
        ^^^^^   ^^^^^^   ← 冒号变分号、斜杠变反斜杠
```

**危险点**：它**不报错、只改内容**，于是验证结果是**假阴性** ——
你会以为推送失败、去重推一遍，或者白查半天。

**规避**：
1. 用 `export MSYS_NO_PATHCONV=1`（本条最省事）
2. 或者换一个不含 `:` 的验证方式：`git ls-tree -r origin/main --name-only | grep 路径`
3. `git show` / `git cat-file` 这类带 `rev:path` 的写法都要小心

**通用规则**：**在 Windows 的 Git Bash 里，任何含 `:` 的参数都可能被偷偷改写。**
（这个坑和「改文件别用 bash 内联字符串」是同一族：shell 会先动你的字符串。）

## 9. 改文件用**脚本文件**，别用 bash 内联字符串（被咬了三次）

用 `python -c "..."` 或 heredoc 改文件时，**shell 会先做命令替换**：
字符串里的反引号 `` ` `` 会被当成命令执行，`$` 会被当变量展开。
后果不是报错，而是**内容被静默替换成空**或**凭空多出一段命令输出**。

真实事故（同一天踩了三次）：

| 怎么坏的 | 表现 |
|---|---|
| 技能文档里的 `` `ProbeKit` `` | 被替换成空 → 文字变成「**① （探针目录，不进工程）**」 |
| 记忆里写 `` `javac` `` | **真的执行了 javac**，帮助文本几十行被插进记忆文件 |
| 修 skill 时说 `` `InputHeightProbe` `` | 又一批反引号内容变空 |

**规矩**：
1. 要写含反引号 / `$` / 中文 的内容 → **用 Write 工具写一个 .py 脚本，再执行它**
   （Write 不经 shell，原样落盘）
2. 必须内联时，用**单引号**包住整个字符串（单引号里 shell 不做替换）
3. 出事后**立刻 grep 检查有没有留下空洞**
   —— 这类损坏不报错，只会让文档**读起来还算通顺但缺了关键字**，
   比语法错误难发现得多

**通用规则**：凡是「shell 先解析、再交给目标程序」的写法，都要问一句
「这个字符串里有 shell 的元字符吗」。

## 8. 产物要在编辑器里打开的正确写法

```kotlin
val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/'))
FileEditorManager.getInstance(project).openFile(vf, true)
```

对 `.svg` 这只会打开 XML 原文 —— **不是预览**。
能预览的格式（png/jpg/gif/bmp/webp/svg）应走 `ImagePreviewDialog`，
双击才在编辑器里打开。