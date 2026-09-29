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

# 受监视的「危险操作」模式 —— 每条都是今晚真的出过问题的
WATCH = [
    {
        "name": "杀进程",
        "pattern": r"destroyForcibly\s*\(|\.descendants\s*\(\s*\)",
        "why": "只 destroy 父进程会漏掉子进程（npx 起的服务、脚本 fork 的进程）",
        "canonical": "util/ProcessTree.kt",
    },
    # 注意：这里**没有**「写文件落盘」这一条。
    #
    # 第一版加了它，结果对 6 个文件各报一次「注意」—— 而它们**绝大多数是正当的**
    # （一次性产物、用户选的导出路径、可重建的临时文件）。
    # 那种噪音会让整份报告失去可信度：看的人要么去加一堆假理由，要么干脆不再看它。
    #
    # 「写文件该不该原子化」由 `scan_atomic_writes.py` 负责 —— 它有白名单和逐条理由，
    # 判据也更准（**读取端会不会静默兜底**）。
    #
    # **同一条线索不做两个检查** —— 这正是在验的那件事本身。
]

# 允许出现在别处的例外 —— 每条都要能说出理由
EXCEPTIONS = {
    ("杀进程", "util/ProcessTree.kt"): "这就是那份共用实现",
    ("写文件落盘", "util/AtomicFiles.kt"): "这就是那份共用实现",
}


def scan(src_root):
    """返回 {检查名: [(相对路径, 行号, 原文)]}"""
    result = {w["name"]: [] for w in WATCH}
    for root, _dirs, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, src_root).replace("\\", "/")
            lines = io.open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines):
                stripped = line.strip()
                # 跳过注释行（它们只是提到这个词，不是真的在调用）
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                for w in WATCH:
                    if re.search(w["pattern"], line):
                        result[w["name"]].append((rel, i + 1, stripped))
    return result


def main():
    src_root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC

    found = scan(src_root)
    problems = []

    print("=== 同一件事是不是有多份实现 ===")
    print()

    for w in WATCH:
        hits = found[w["name"]]
        canonical = w["canonical"]
        # 统计「哪些文件里有」——同一个文件里出现多次很常见（同一处逻辑的几行），
        # 真正关心的是**分散在几个文件里**
        files = sorted({rel for rel, _ln, _t in hits})
        others = [f for f in files if f != canonical]

        print("· %s" % w["name"])
        print("    共用实现：%s" % canonical)
        print("    出现于 %d 个文件：%s" % (len(files), "、".join(files) if files else "（无）"))

        for f in others:
            key = (w["name"], f)
            if key in EXCEPTIONS:
                print("      ✓ %s —— %s" % (f, EXCEPTIONS[key]))
            else:
                problems.append((w["name"], f, w["why"]))
                print("      **注意 %s**" % f)

        if files and canonical not in files:
            print("      **共用实现 %s 里一处都没有 —— 是不是没人用它？**" % canonical)
            problems.append((w["name"], canonical, "共用实现没被使用"))
        print()

    if not problems:
        print("---")
        print("通过：每类操作都只有一份实现（或已声明理由）。")
        sys.exit(0)

    print("---")
    print("需要表态的地方：")
    for name, f, why in problems:
        print("  · %s 里的「%s」" % (f, name))
        print("      风险：%s" % why)
    print()
    print("两种处理：")
    print("  1. 合并到共用实现")
    print("  2. 加进 EXCEPTIONS 并写明分离的理由")
    print()
    print("判据：**新代码会抄哪一个？** 说不清 → 就该合并。")
    sys.exit(1)


main()
