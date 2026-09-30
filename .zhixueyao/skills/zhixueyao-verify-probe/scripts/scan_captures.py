import io
import os
import re
import sys

# 自动定位工程根：脚本在 .zhixueyao/skills/<技能>/scripts/ 下，往上四级即工程根。
#
# 这样脚本**放到任何一台机器、任何一个克隆目录都能跑**，不需要改任何常量。
# 原来这里写的是本机绝对路径，同步到仓库时必须脱敏成占位符 ——
# 而占位符是**跑不了**的，等于给克隆的人留了个坏掉的脚本。
#
# **能自动算出来的东西就不要写成常量。**
_HERE = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(_HERE))))
_AUTO_SRC = os.path.join(_PROJECT_ROOT, "src", "main", "kotlin", "com", "zhixueyao")

DEFAULT_SRC = _AUTO_SRC if os.path.isdir(_AUTO_SRC) else r"<工程根>/src/main/kotlin/com/zhixueyao"

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
