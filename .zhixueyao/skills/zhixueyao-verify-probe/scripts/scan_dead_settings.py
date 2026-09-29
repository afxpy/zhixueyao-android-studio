#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫「死设置」—— 存了但没人读的设置项。

## 这条检查从哪来

用户的原话：

> 「请确保所有设置都是有用的功能，**拒绝伪代码**」

而这一整晚反复出现的就是这个形状：

  - token 存进加密存储了，**但 push 时没用它**
  - 提交署名存了，**但 commit 时没用它**
  - tooltip 写了，**但没人会去悬停**
  - 一个选项加进设置页了，**但代码里从来没读过**

**它们的共同点**：用户填了、界面显示「已保存」、实际一点作用都没有。
而**用户是最后一个知道的** —— 他只能靠「试了没用」来发现。

## 判据

一个设置项算「活」的，必须满足：
  1. 在 `ZhixueyaoSettings` 里声明（`var xxx: T = ...`）
  2. **在设置页之外**至少有一处**读**它（`s.xxx` 或 `Settings.getInstance().xxx`）

只在设置页里出现（读 `s.xxx` 用来初始化输入框 / 写 `s.xxx = 输入框的值`）**不算** ——
那只是「存取」，不是「使用」。

## 输出

- 有读方的：列出来（可折叠）
- **完全没有读方的：单独报出来** —— 那些就是伪功能
"""
import io
import os
import re
import sys

def project_root_from_script():
    here = os.path.dirname(os.path.abspath(__file__))
    for _ in range(4):
        here = os.path.dirname(here)
    return here

def find_src():
    auto = os.path.join(project_root_from_script(), "src", "main", "kotlin", "com", "zhixueyao")
    return auto if os.path.isdir(auto) else r"<工程根>/src/main/kotlin/com/zhixueyao"

# 设置页文件（在这里出现不算「被使用」）
SETTINGS_PAGE = "settings/ZhixueyaoConfigurable.kt"
SETTINGS_FILE = "settings/ZhixueyaoSettings.kt"

# ---------------------------------------------------------------
# ⚠️ 这条检查的判据错过一次，代价是**误删了 4 个还在用的字段**。
#
# 原来我把「设置声明文件」整个排除在「读方」之外 —— 想避开
# `loadState()` 里那种「null 兜底」的假阳性。
#
# 结果：`providerKeys` / `providerModels` / `providerBaseUrls` /
# `providerModelEntries` 在这个文件里有一大堆**真实用法**
#（被 providerId 索引的存储、序列化迁移、切换服务商时写入），
# 全都没被看见 —— 于是它们被判成「死设置」，我照着删了。
#
# **声明文件本身可以既声明又使用。** 排除整个文件是把「假阳性」
# 换成了更糟的「假阴性」。
#
# 现在改成：**只在同一个文件里排除「纯声明行」**，
# 其他行的用法照常算数。
# ---------------------------------------------------------------

# 声明行：`var xxx: Type = ...`
DECL_RE = re.compile(r"^\s*var\s+(\w+)\s*:\s*([\w<>?, .]+)\s*=")

# ---------------------------------------------------------------
# 已核对过、确认「是被设置页用的」的项。
#
# 设条检查把设置页排除在「读方」之外 —— 因为「页面上摆个输入框」不算
# 「这个设置起了作用」。但设置页里**也有真实的业务逻辑**
#（比如保存时启停服务），那些读法是算数的。
#
# 核对过就记在这里，并写清依据 —— 免得每次都重新判一遍，
# 也免得输出里一直挂着一堆「疑似」把人训练成不看它。
# ---------------------------------------------------------------
USED_IN_SETTINGS_PAGE = {
    "mcpServerEnabled": "applyMcpServerState() 用它决定启不启用内置 MCP 服务",
    "mcpServerPort": "同上 —— 启动时用的端口",
    "showApplyButton": "在 MessageBubble 里读（不在设置页）—— 列在这里是因为判据放宽前的残留",
    "replyLanguage": "AgentRunner 读",
    "userAvatar": "Avatars 读",
    "aiAvatar": "Avatars 读",
    "showTokenEstimate": "ChatPanel 读",
    "reasoningViewMigrated": "loadState 里的一次性迁移标记",
    "sandboxNoticeAccepted": "Sandbox 读",
    "allowAskUser": "TaskTools 读",
    "showTaskList": "TaskTools / ChatPanel 读",
    "maxRecentImages": "AgentRunner 读",
    "sandboxMode": "Sandbox 读",
}


def main():
    src = find_src()
    print("=" * 60)
    print("机械检查：死设置（存了但没人读）")
    print("源码：%s" % src)
    print("=" * 60)
    print()

    settings_path = os.path.join(src, *SETTINGS_FILE.split("/"))
    if not os.path.isfile(settings_path):
        print("  找不到 %s" % SETTINGS_FILE)
        return 2

    # ---------- ① 列出所有设置项 ----------
    stext = io.open(settings_path, encoding="utf-8").read()
    decls = []
    for line in stext.split("\n"):
        m = DECL_RE.match(line)
        if not m:
            continue
        name, typ = m.group(1), m.group(2).strip()
        # 跳过明显的内部字段
        if name.startswith("_") or name in ("serialVersionUID",):
            continue
        decls.append((name, typ))

    if not decls:
        print("  没有解析到任何设置项 —— 声明写法可能变了")
        return 2

    # ---------- ② 对每个设置项，找「读」的地方 ----------
    files = []
    for dp, _dn, fs in os.walk(src):
        for f in fs:
            if f.endswith(".kt"):
                fp = os.path.join(dp, f)
                rel = os.path.relpath(fp, src).replace("\\", "/")
                files.append((rel, io.open(fp, encoding="utf-8").read()))

    dead = []
    alive = []
    known = []
    for name, typ in decls:
        readers = []
        for rel, text in files:
            # 设置页只是「存取」的展示层，在那里出现不算被使用 —— 这条保留。
            # 但**声明文件本身不排除**（见上面那段教训）。
            if rel == SETTINGS_PAGE:
                continue
            # **朴素的子串匹配**，不用正则。
            #
            # 上一版用的是 `[\w.]*\.name\b` 这种模式，结果把
            # `showApplyButton`（在 MessageBubble 里真实使用）之类判成了死设置 ——
            # **正则的边界情况太多，而这里的判据本来就不需要那么精细**：
            # 只要这个标识符在任何**非声明行**上出现过，就说明有人碰它。
            #
            # 宁可宽一点（少报几个），也不要窄（误报会导致误删）。
            for line in text.split("\n"):
                if name not in line:
                    continue
                stripped = line.strip()
                # 跳过注释
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                # 跳过「声明行」：`var name: T =` 或 `val name: T =`
                if re.match(r"\s*(var|val)\s+%s\s*:" % re.escape(name), line):
                    continue
                readers.append(rel)
                break
        if readers:
            alive.append((name, typ, sorted(set(readers))))
        elif name in USED_IN_SETTINGS_PAGE:
            known.append((name, typ, USED_IN_SETTINGS_PAGE[name]))
        else:
            dead.append((name, typ))

    # ---------- ③ 报告 ----------
    print("  设置项共 %d 个" % len(decls))
    print("    有读方的：%d" % len(alive))
    print("    核对过是在设置页里用的：%d" % len(known))
    print("    **疑似死设置：%d**" % len(dead))
    print()

    if known:
        print("  已核对（不报为问题）：")
        for name, typ, why in known:
            print("     %-24s %s" % (name, why))
        print()

    if dead:
        print("  ⚠️ 下面这些**存了但没有任何地方读**：")
        print()
        for name, typ in dead:
            print("     %-28s %s" % (name, typ))
        print()
        print("  （也要人工核对：可能是被反射/序列化读到，或者刚加还没来得及用）")
        print()

    print("-" * 60)
    print("有读方的（供核对）：")
    for name, typ, readers in alive:
        where = readers[0] if len(readers) == 1 else "%s 等 %d 处" % (readers[0], len(readers))
        print("     %-28s ← %s" % (name, where))

    print()
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main())
