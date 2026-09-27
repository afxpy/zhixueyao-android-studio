---
name: zhixueyao-verify-probe
description: 这个工程怎么离线验证改动——探针怎么写、跑什么、headless 环境哪些验不了。改完代码要证明改动生效时读。
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