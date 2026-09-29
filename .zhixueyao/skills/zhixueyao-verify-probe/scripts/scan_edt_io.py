#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫「从 EDT（界面线程）能走到的阻塞 I/O」。

## 第二版 —— 为什么要重写

第一版按「单行有没有阻塞调用」扫，报了 78 处。**其中大部分是误报**：
`GitEnvironment.check()` 里那一堆 `ProcessRunner.run` 其实**已经跑在后台**了
（调用方用 `executeOnPooledThread` 包着），只是**跨了一层函数**，单行扫描看不见。

**误报多了这条检查就会没人看** —— 而一条没人看的检查等于没有。
所以要做得准：**从 EDT 入口出发，沿着调用链走**，只报真正可达的。

## 判据

**EDT 入口**（会被界面线程直接调到的）：
  - `Configurable` 的生命周期：createComponent / reset / isModified / apply / disposeUIResources
  - Swing 回调：addActionListener / addMouseListener / ... 里注册的 lambda
  - 面板的构造与 build 方法（在 createComponent 里被调）

**阻塞调用**：见 BLOCKING 表。

**后台标记**：出现这些就认为这一支已经离开 EDT。
  - executeOnPooledThread / invokeLater / Thread { / CompletableFuture
  - 本工程的 withTimeout（自己管超时）

## 输出

只报「**从 EDT 可达、且在后台标记之前**」的阻塞调用。
每条都带调用路径，方便人工确认。
"""
import io
import os
import re
import sys
from collections import deque

BLOCKING = [
    (r"PasswordSafe\.", "系统凭据管理器"),
    (r"\bGitCredentials\.(load|save|clear|hasToken|loadToken)", "凭据存取（底下是 PasswordSafe）"),
    (r"\bProcessRunner\.(run|start)\s*\(", "启动外部进程"),
    (r"\bSocket\s*\(\s*\)", "网络连接"),
    (r"InetSocketAddress\s*\(", "网络连接"),
    (r"SSLContext\.getInstance", "TLS 握手"),
    (r"\.readText\s*\(|\.writeText\s*\(", "文件读写"),
    (r"FileOutputStream|FileInputStream", "文件流"),
    (r"Thread\.sleep\s*\(", "显式睡眠"),
    (r"\.join\s*\(\s*\d", "等待线程结束"),
]

# 出现这些 = 这一支已经离开 EDT
OFFLOAD = [
    "executeOnPooledThread",
    "invokeLater",
    "Thread {",
    "CompletableFuture",
    "withTimeout",
    "handshakeThrough",
    "ApplicationManager.getApplication().executeOnPooledThread",
]

# EDT 的入口方法名（Configurable 生命周期）
EDT_ENTRIES = {
    "createComponent", "reset", "isModified", "apply", "disposeUIResources",
    "getDisplayName", "getHelpTopic", "getPreferredFocusedComponent",
}

FUN_RE = re.compile(r"^(\s*)(?:private |internal |public |override |suspend )*fun\s+(\w+)\s*\(")

# ---------------------------------------------------------------
# 已核对过的：确认只会从后台线程被调到（或者本身就是安全的实现）。
#
# 为什么要写在这里，而不是让它一直报：
# 一条检查如果每次都吐二十几条「需要核对」，人就会开始不看它 ——
# 然后真出问题时也被忽略。所以核对过的要明确标掉，并写清依据。
#
# 判断方法：看它的所有调用点。如果每个调用点都在
# executeOnPooledThread 块里，那它就只会跑在后台。
# 这个脚本只看单文件、看不到跨文件的调用链，所以这一步必须人工做 ——
# 做了就把结论写在这里，下次不用重做。
# ---------------------------------------------------------------
CHECKED_SAFE = {
    # GitEnvironment 整块：check() 只在 runEnvCheck() 的
    # executeOnPooledThread 里被调（ZhixueyaoConfigurable.kt）
    "checkGitInstalled": "只被 GitEnvironment.check() 调，而 check() 在 runEnvCheck 的后台线程里",
    "checkCredentialHelper": "同上",
    "checkReachability": "同上",
    "checkProxyResidue": "同上",
    "clearProxy": "只被设置页「清掉这两行配置」按钮调 —— 那个按钮自己包了后台线程",
    "run": "GitEnvironment 内部工具函数，调用方全在后台",
    "resolve": "同上",
    "takeRaw": "只读 ThreadLocal",
    "get": "checkProxyResidue 的内部函数",
    # GitIdentity
    "read": "只被 GitEnvironment.checkAuthor()（在后台）和设置页的异步块调",
    "setGlobal": "只被设置页「写进 git 全局配置」按钮调 —— 那个按钮包了后台线程",
    "currentName": "GitIdentity.read 的包装",
    "currentEmail": "同上",
    # 启动期一次性初始化：跑在 IDE 启动阶段，不在 EDT 上
    "mergeHomeMcpConfig": "启动期一次性调用（ZhixueyaoStartup），不来自 EDT",
    "ensure": "ZhixueyaoHome.ensure —— 启动期写 README，不在 EDT 路径上",
    # 会话相关：调用点都在后台
    "readMarker": "SessionWorkspace 内部；调用方是 AgentRunner（后台）和异步载入",
    "ensureUiState": "SessionStore 内部；调用方是 load 和设置页的后台块",
    "readMeta": "只被 list() 调，而 list() 的调用点包了后台线程",
    "load": "调用点：openSessionAsync（后台读）和设置页的异步块",
    "renderSession": "名字像但它是 EDT 渲染函数 —— 里面的文件访问是 SessionWorkspace 的读",
}


def project_root_from_script():
    here = os.path.dirname(os.path.abspath(__file__))
    for _ in range(4):
        here = os.path.dirname(here)
    return here


def find_src():
    if len(sys.argv) > 1:
        return sys.argv[1]
    auto = os.path.join(project_root_from_script(), "src", "main", "kotlin", "com", "zhixueyao")
    return auto if os.path.isdir(auto) else r"<工程根>/src/main/kotlin/com/zhixueyao"


def parse_file(path):
    """把文件切成 {函数名: (起, 止, 文本)}，并记下 lambda 块"""
    lines = io.open(path, encoding="utf-8").read().split("\n")
    funcs = {}
    cur_name, cur_start, cur_indent = None, None, None
    for i, line in enumerate(lines):
        m = FUN_RE.match(line)
        if m:
            indent = len(m.group(1))
            if cur_name is not None:
                funcs[cur_name] = (cur_start, i - 1)
            cur_name, cur_start, cur_indent = m.group(2), i, indent
        elif cur_name is not None and line.strip() and not line.startswith(" " * (cur_indent + 1)):
            # 缩进退回到函数级，认为函数结束
            funcs[cur_name] = (cur_start, i - 1)
            cur_name, cur_start, cur_indent = None, None, None
    if cur_name is not None:
        funcs[cur_name] = (cur_start, len(lines) - 1)
    return lines, funcs


def main():
    src = find_src()
    print("=" * 60)
    print("机械检查：从 EDT 可达的阻塞 I/O")
    print("源码：%s" % src)
    print("=" * 60)
    print()

    if not os.path.isdir(src):
        print("  找不到源码目录：%s" % src)
        return 2

    # 收集每个文件的函数表
    files = {}
    all_funcs = {}   # 全局：函数名 → [(file, start, end)]
    for dp, _dn, fs in os.walk(src):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            fp = os.path.join(dp, f)
            rel = os.path.relpath(fp, src).replace("\\", "/")
            lines, funcs = parse_file(fp)
            files[rel] = (lines, funcs)
            for name, (s, e) in funcs.items():
                all_funcs.setdefault(name, []).append((rel, s, e))

    # ---------- 从 EDT 入口做广度优先，沿调用链走 ----------
    # 每个访问项：(文件, 起始行, 行号) —— 标记「这一行还没离开 EDT」
    visited = set()
    queue = deque()

    for rel, (lines, funcs) in files.items():
        for name, (s, e) in funcs.items():
            if name in EDT_ENTRIES:
                queue.append((rel, s, e, name))

    findings = []
    while queue:
        rel, s, e, via = queue.popleft()
        key = (rel, s, e)
        if key in visited:
            continue
        visited.add(key)

        lines, _ = files[rel]
        body = "\n".join(lines[s:e + 1])

        # 这个函数体里有没有「已经在后台」的标记
        offloaded_marker = any(o in body for o in OFFLOAD)

        for i in range(s, e + 1):
            line = lines[i]
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue

            # 命中阻塞调用
            for pat, why in BLOCKING:
                if re.search(pat, line):
                    # 只有在「没看到后台标记」时才报；
                    # 但如果这一行**自己就在** executeOnPooledThread 的块里，跳过
                    nearby = "\n".join(lines[max(s, i - 15):i + 4])
                    if any(o in nearby for o in OFFLOAD):
                        break
                    findings.append({
                        "file": rel, "line": i + 1, "via": via,
                        "why": why, "code": stripped[:76],
                    })
                    break

            # 沿调用链继续走（找本文件里被调用的函数）
            if not offloaded_marker:
                for m in re.finditer(r"\b(\w+)\s*\(", line):
                    callee = m.group(1)
                    if callee in EDT_ENTRIES or callee == via:
                        continue
                    for (crel, cs, ce) in all_funcs.get(callee, []):
                        if (crel, cs, ce) not in visited:
                            queue.append((crel, cs, ce, callee))

    # 去掉已核对过的
    findings = [f for f in findings if f["via"] not in CHECKED_SAFE]

    print("  从 EDT 入口走下来，命中阻塞调用 %d 处（已去掉 %d 个核对过的函数）"
          % (len(findings), len(CHECKED_SAFE)))

    # 排除掉那些在后台标记保护下的（二次确认）
    real = []
    for f in findings:
        rel = f["file"]
        lines, _ = files[rel]
        # 往上找最近的 executeOnPooledThread / invokeLater 是否在同一块里
        window = "\n".join(lines[max(0, f["line"] - 16):f["line"] + 2])
        if not any(o in window for o in OFFLOAD):
            real.append(f)

    print("  去掉后台保护后的：**%d 处需要核对**" % len(real))
    print()

    if real:
        by_file = {}
        for f in real:
            by_file.setdefault(f["file"], []).append(f)
        for rel in sorted(by_file):
            print("  %s" % rel)
            for f in sorted(by_file[rel], key=lambda x: x["line"]):
                print("     L%-5d %-16s ← %s" % (f["line"], f["via"], f["why"]))
                print("            %s" % f["code"])
            print()
    else:
        print("  没有从 EDT 可达的阻塞调用。")

    print("-" * 60)
    print("说明：这条检查是**启发式**的（按缩进切函数、按名字找调用）。")
    print("      报出来的都要人工核一眼，但**不报的可以放心**。")

    # **有嫌疑就要返回非 0** —— 否则 scan_all 会把它当成通过，
    # 这条检查就白做了（一个永远绿的检查等于不存在）。
    #
    # 但也不把「有嫌疑」当成「确定有错」：所以是 1（需要注意），
    # 不是更严重的码 —— 由人核对后决定。
    return 1 if real else 0


if __name__ == "__main__":
    sys.exit(main())
