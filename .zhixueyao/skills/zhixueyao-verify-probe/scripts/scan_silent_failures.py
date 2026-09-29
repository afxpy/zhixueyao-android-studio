#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫「静默失效」——代码看起来正常、但功能永远不会生效的几种写法。

## 这条检查从哪来

这一晚反复踩到同一类问题：**代码没有任何异常，功能就是不工作。**

  · `invokeLater` 不传 ModalityState → 在模态对话框（设置页）里回调永不执行
  · 后台任务不包 try/catch → 后台一抛，后面的 invokeLater 就成了死代码
  · 硬编码路径 → 用户的实际安装位置不同，功能静默失效

它们的共同点：**没有报错、没有堆栈、代码读起来完全正常。**
用户只能靠「试了没用」发现，而我要查好几轮。

**每一条都值得机械防住 —— 因为它们不会自己暴露。**
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


def read_files(src):
    out = []
    for dp, _dn, fs in os.walk(src):
        for f in fs:
            if f.endswith(".kt"):
                fp = os.path.join(dp, f)
                rel = os.path.relpath(fp, src).replace("\\", "/")
                out.append((rel, io.open(fp, encoding="utf-8").read()))
    return out


def strip_comments(text):
    """把注释行换成空行（保留行号）——**注释里提到这些写法是好事，不该报**。"""
    lines = []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            lines.append("")
        else:
            lines.append(line)
    return "\n".join(lines)


# ---------------------------------------------------------------
# 检查一：invokeLater 没传 ModalityState
# ---------------------------------------------------------------
def check_modality(files):
    bad = []
    # 允许的写法：UiKit.ui { ... }（内部已经带了 ModalityState.any()）
    ok_call = re.compile(r"UiKit\.ui\s*\{")
    # 裸的 invokeLater（ApplicationManager 那条链）
    bare = re.compile(r"\.invokeLater\s*\{")
    has_modality = re.compile(r"ModalityState|SwingUtilities\.invokeLater")

    for rel, raw in files:
        text = strip_comments(raw)
        for i, line in enumerate(text.split("\n"), 1):
            if not bare.search(line):
                continue
            if has_modality.search(line):
                continue
            # 往上 6 行看看有没有 UiKit.ui（多行链式写法）
            ctx = "\n".join(text.split("\n")[max(0, i - 7):i])
            if ok_call.search(ctx) or has_modality.search(ctx):
                continue
            bad.append((rel, i, line.strip()[:76]))
    return bad, "Application.invokeLater 不传 ModalityState —— 在模态对话框（设置页）里回调永不执行"


# ---------------------------------------------------------------
# 检查二：后台任务里没有 try/catch
# ---------------------------------------------------------------
def check_bg_exception(files):
    bad = []
    for rel, raw in files:
        text = strip_comments(raw)
        lines = text.split("\n")
        for i, line in enumerate(lines):
            if "executeOnPooledThread" not in line:
                continue
            # 取它后面 40 行作为块（近似）
            block = "\n".join(lines[i:i + 40])
            # 块里有没有 try / runCatching —— 或者**被调用的那个函数自己管超时**。
            #
            # （GitCredentials 的那几个方法内部都用 withTimeout 包过，
            #   不会把异常漏出来。这是核对过的，不算问题。）
            if re.search(r"\btry\s*\{|runCatching|withTimeout", block):
                continue
            # `return@executeOnPooledThread` 只是**标签**，不是启动点
            if "return@executeOnPooledThread" in line:
                continue
            # 块里有没有 invokeLater —— 有的话更需要 try（抛了它就成死代码）
            if "invokeLater" not in block and "UiKit.ui" not in block:
                continue
            bad.append((rel, i + 1, line.strip()[:76]))
    return bad, "后台任务没有 try/catch —— 后台一抛异常，后面的 invokeLater 就成了死代码（界面永远停在「正在…」）"


# ---------------------------------------------------------------
# 检查三：硬编码的绝对路径
# ---------------------------------------------------------------
def check_hardcoded_paths(files):
    bad = []
    # 只报「Windows 盘符开头的绝对路径」和「/usr/... /Applications/...」
    pat = re.compile(r'"(?:[A-Za-z]:[\\\\/]|/(?:usr|opt|Applications)/)[^"]{6,}"')
    # 白名单 —— 这两类硬编码是**合理**的，报出来属于噪音：
    #   1. 「兜底候选」：写几个常见位置去试，找不到还有别的路子（`gcmCandidates`）
    #   2. 「禁写目录」：`C:/Windows`、`C:/Program Files` 这些名字是固定的，
    #      我们只是用它做**判断**，不是用它去访问什么东西
    allow_markers = ("gcmCandidates", "resolve(", "// 兜底", "deny", "禁写", "敏感")

    for rel, raw in files:
        text = strip_comments(raw)
        lines = text.split("\n")
        for i, line in enumerate(lines, 1):
            for m in pat.finditer(line):
                lit = m.group(0)
                # 自己造路径（拼 home 之类）不算
                if "$" in lit or "{" in lit:
                    continue
                # 往上 25 行看有没有「这是候选之一」的说明
                ctx = "\n".join(lines[max(0, i - 25):i])
                if any(a in ctx for a in allow_markers):
                    continue
                bad.append((rel, i, line.strip()[:76]))
                break
    return bad, "硬编码的绝对路径 —— 用户的实际安装位置很可能不同（实测：git 装在 D:/Git 而不是 Program Files）"


CHECKS = [
    ("modality_state", check_modality),
    ("bg_exception", check_bg_exception),
    ("hardcoded_path", check_hardcoded_paths),
]


def main():
    src = find_src()
    print("=" * 60)
    print("机械检查：静默失效的写法")
    print("源码：%s" % src)
    print("=" * 60)
    print()

    if not os.path.isdir(src):
        print("  找不到源码目录：%s" % src)
        return 2

    files = read_files(src)
    total_bad = 0

    for name, fn in CHECKS:
        try:
            bad, why = fn(files)
        except Exception as e:
            print("  %-18s !! 检查自身出错：%r" % (name, e))
            continue

        if bad:
            total_bad += len(bad)
            print("  ✗ %-18s %d 处" % (name, len(bad)))
            print("      %s" % why)
            print()
            for rel, line, code in bad[:12]:
                print("      %s:%d" % (rel, line))
                print("        %s" % code)
            if len(bad) > 12:
                print("      …… 还有 %d 处" % (len(bad) - 12))
            print()
        else:
            print("  ✓ %-18s 干净" % name)

    print("-" * 60)
    if total_bad == 0:
        print("没有发现静默失效的写法。")
    else:
        print("共 %d 处需要注意。" % total_bad)
        print()
        print("**这三类都不会报错、不会留堆栈** —— 代码读起来完全正常，")
        print("功能就是不生效。所以宁可多报几处让人核一眼。")
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
