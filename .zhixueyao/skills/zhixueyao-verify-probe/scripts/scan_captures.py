"""
扫「只挂一次的回调捕获了会变的值」。

用法：
    python scan_captures.py [要扫的目录]
    默认扫 src/main/kotlin/com/zhixueyao/ui

## 为什么是这个口径（实测数据支撑）

第一版按最初的设想写成了「扫所有 lambda / 匿名类」，规模是：

    ui/ 下函数总数                  349
    有回调注册的函数（宽口径）        27    回调注册点 42 处
    其中「只挂一次」的（窄口径）       1    守卫点 2 处
    → 口径收窄压掉 96%

宽口径下**几乎每个带交互的函数都会被卷进来**（按钮、列表、输入框都会挂监听），
人工过一遍不现实；窄口径只剩 1 个函数，过一遍是几秒的事。

## 判据：三个条件同时成立才算可疑

  ① 闭包在**点击 / 触发时**才读那个值（不是注册时用一次）
  ② 那个值**在注册之后还会变**
  ③ 闭包**只注册一次**（有 client property 之类的守卫）
     —— 只挂一次的闭包，捕获的值**永远不会被刷新**

**③ 是关键**，也是把误报压下来的关键：闭包若每次渲染都重挂，捕获的就是新值，无害。

## 输出是「待确认清单」，不是 bug 列表

**本脚本永远返回 0**（不 fail）。这是刻意的：

- 做成 CI 门禁的话，人会为了「消警告」而改坏代码 —— 而这里很多捕获是**正确**的
- 它保证的是**不漏**（宁可多报），精度靠另一层补：行为探针
  （`StaleContentProbe` 那种，钉住「内容变了之后回调读的是不是新值」）

## 真实命中长什么样

在当前代码上跑出来是 1 处：

    MessageBubble.kt  applyCollapseIfNeeded  捕获: source

它**不是 bug** —— `source` 是 `currentText().ifBlank { source }` 里的兜底
（缓冲区为空时退回最初那一版）。**这类「有意保留的兜底」正是需要人工确认的典型**，
脚本不该替人做这个判断。
"""

import io
import os
import re
import sys

DEFAULT_UI = "src/main/kotlin/com/zhixueyao/ui"

# ---- 条件 ③：「只挂一次」的守卫写法 ----
ONCE_GUARD = re.compile(
    r"getClientProperty\([^)]*\)\s*==\s*true\)\s*(return|return@\w+)|"
    r"putClientProperty\(\s*[A-Z_]+\s*,\s*true\s*\)"
)

# ---- 条件 ①：回调注册的入口 ----
CALLBACK_REG = re.compile(
    r"addActionListener|addMouseListener|addMouseMotionListener|"
    r"addChangeListener|addDocumentListener|addKeyListener|"
    r"UiKit\.\w+\([^)]*\)\s*\{"
)

FUN_START = re.compile(r"^( {4})?(private |internal |public )?(override )?fun (\w+)")

# 天生的「当前状态读取入口」/ 泛用名，不算问题
SAFE_NAMES = {
    "project", "root", "panel", "parent", "this", "it", "e", "args",
    "bubble", "index", "i", "j", "k",
}


def strip_strings_and_comments(line: str) -> str:
    """去掉字符串字面量与行尾注释。

    必须去：中文文案里会有 `：`、`（）` 这类字符，不去掉会污染标识符匹配，
    也会让 `{` / `}` 的计数失真。
    """
    out, in_str, i = [], False, 0
    while i < len(line):
        ch = line[i]
        if ch == '"' and (i == 0 or line[i - 1] != "\\"):
            in_str = not in_str
            i += 1
            continue
        if not in_str and ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        if not in_str:
            out.append(ch)
        i += 1
    return "".join(out)


def scan_file(path: str):
    raw = io.open(path, encoding="utf-8").read().split("\n")
    lines = [strip_strings_and_comments(l) for l in raw]

    # ---- 按缩进切出函数块 ----
    funcs = []
    for i, l in enumerate(lines):
        m = FUN_START.match(l)
        if m and "{" in l:
            indent = len(l) - len(l.lstrip())
            end = len(lines) - 1
            for j in range(i + 1, len(lines)):
                t = lines[j]
                if t.strip() == "}" and (len(t) - len(t.lstrip())) <= indent:
                    end = j
                    break
            funcs.append((m.group(4), i, end))

    findings = []
    for name, start, end in funcs:
        body = lines[start:end + 1]
        body_raw = raw[start:end + 1]

        # 条件 ③：必须有「只挂一次」守卫，否则跳过（这是压掉 96% 的那一刀）
        if not any(ONCE_GUARD.search(b) for b in body):
            continue

        # 收集「会变的值」候选：函数参数 + 函数级局部 val/var
        head = body_raw[0]
        k = 0
        while "{" not in body_raw[k] and k < len(body_raw) - 1:
            k += 1
            head += " " + body_raw[k]
        head_code = strip_strings_and_comments(head)

        params = []
        if "(" in head_code and ")" in head_code:
            plist = head_code[head_code.index("(") + 1: head_code.rindex(")")]
            for p in plist.split(","):
                p = p.strip()
                if not p:
                    continue
                nm = p.split(":")[0].strip().removeprefix("var ").removeprefix("val ")
                if re.fullmatch(r"\w+", nm):
                    params.append(nm)

        locals_ = []
        for b in body:
            m = re.match(r"\s*(val|var)\s+(\w+)", b)
            if m:
                locals_.append(m.group(2))

        candidates = (set(params) | set(locals_)) - SAFE_NAMES
        if not candidates:
            continue

        # ---- 逐块解析回调体 ----
        i = 0
        while i < len(body):
            if not CALLBACK_REG.search(body[i]):
                i += 1
                continue
            depth, j, block, started = 0, i, [], False
            while j < len(body):
                block.append(body[j])
                depth += body[j].count("{") - body[j].count("}")
                if "{" in body[j]:
                    started = True
                if started and depth <= 0:
                    break
                j += 1

            # 排掉**在回调块内部声明**的局部量 —— 它们不是从外面捕获的
            inner = set()
            for bl in block:
                m2 = re.match(r"\s*(val|var)\s+(\w+)", bl)
                if m2:
                    inner.add(m2.group(2))

            text = "\n".join(block)
            for c in sorted(candidates - inner):
                if re.search(r"\b%s\b" % re.escape(c), text):
                    # 取一行上下文，方便人工判读（脚本不替人下结论）
                    ctx = ""
                    for bl in block:
                        if re.search(r"\b%s\b" % re.escape(c), bl):
                            ctx = bl.strip()
                            break
                    findings.append({
                        "file": os.path.basename(path),
                        "line": start + i + 1,
                        "func": name,
                        "captured": c,
                        "kind": "参数" if c in params else "局部量",
                        "context": ctx[:100],
                    })
            i = j + 1

    seen, uniq = set(), []
    for f in findings:
        key = (f["file"], f["line"], f["captured"])
        if key not in seen:
            seen.add(key)
            uniq.append(f)
    return uniq


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_UI
    if not os.path.isdir(target):
        print("目录不存在：%s" % target)
        return 0

    all_f = []
    for root, _d, files in os.walk(target):
        for f in sorted(files):
            if f.endswith(".kt"):
                all_f += scan_file(os.path.join(root, f))

    print("扫「%s」：%d 处候选" % (target, len(all_f)))
    print()
    if not all_f:
        print("  （无）")
        return 0

    for f in sorted(all_f, key=lambda x: (x["file"], x["line"])):
        print("%s:%d  %s()" % (f["file"], f["line"], f["func"]))
        print("    捕获 %s「%s」" % (f["kind"], f["captured"]))
        print("    %s" % f["context"])
        print()

    print("---")
    print("请人工判读（脚本不替你下结论）：")
    print("  · 捕获的是「代表当前内容」的名字吗？（source / text / content / body / now）")
    print("    有实时读取入口（如 currentText()）的话，它**应该**改成现取")
    print("  · 还是有意保留的兜底 / 固定文案？（固定文案、常量、id 都属正常）")
    print()
    print("本脚本永远返回 0：它是清单生成器，不是门禁。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
