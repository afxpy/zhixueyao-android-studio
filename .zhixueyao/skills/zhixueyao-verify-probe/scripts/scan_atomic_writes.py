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

# 明确「不需要原子写入」的位置 —— 每条都要能说出理由
ALLOWED = {
    # AtomicFiles 自己内部：它就是那个「写 tmp」的实现
    "util/AtomicFiles.kt": "这是原子写入的实现本身，tmp 这一步本来就该直接写",

    # 一次性 / 可重建的产物
    "agent/ZhixueyaoHome.kt": "首次运行生成 README，丢了下次启动会重建",
    "tools/DevOpsTools.kt": "写的是模型生成的目标文件（用户要的产物本身），"
                            "而且 write_file 有自己的备份机制（FileSafety）",
    "settings/ZhixueyaoConfigurable.kt": "一处是首次生成记忆模板；"
                                        "另一处是用户显式选的导出路径（用户自己指定，坏了重选）",
}

# 曾经这里有一条 "tools/KnowledgeTools.kt" 的白名单，理由是「见下面 TODO」——
# **那不是理由，是拖延。** 而知识库页面丢了正是最可惜的（用户积累的东西）。
# 后来那一处改成了原子写入，白名单里这一条也就删掉了。
#
# 留这段注释是想说明：**白名单里的每一句理由都要经得起看**。
# 「暂时先这样」「以后再说」这种话混进来，白名单就变成了垃圾桶 ——
# 而垃圾桶一多，这个检查就没人信了。

# 允许的写法模式：出现在这些上下文里就不算问题
OK_PATTERNS = [
    r"AtomicFiles\.write",     # 走了共用实现
    r"\.tmp",                  # 临时文件
    r"//\s*ok:",               # 显式标了可接受
]

SUSPECT_HINT = "（丢了会被静默吞掉？读取端是不是 runCatching？）"


def find_suspects(src_root):
    """找出「直接写文件、且没走 AtomicFiles」的位置"""
    hits = []
    for root, _dirs, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, src_root).replace("\\", "/")
            if rel in ALLOWED:
                continue
            lines = io.open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines):
                # **先跳注释。** 第一版忘了这一步，于是把
                # `chat/SessionStore.kt` 里那句「原来是一行 fileOf(id).writeText(...) 」
                # 当成了真实调用 —— 报了一个不存在的发现。
                #
                # 这类「注释里提到危险 API」在 KDoc 里特别常见（解释「为什么不用它」时必然要提它），
                # 所以注释跳过不是可选项。
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                if not re.search(r"\.writeText\s*\(", line):
                    continue
                # 同一行或前两行里有豁免模式就不算
                ctx = "\n".join(lines[max(0, i - 2):i + 1])
                if any(re.search(p, ctx) for p in OK_PATTERNS):
                    continue
                hits.append((rel, i + 1, line.strip()))
    return hits


def main():
    src_root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC

    suspects = find_suspects(src_root)

    print("=== 直接写文件（未走 AtomicFiles）===")
    print()

    if not suspects:
        print("没有可疑的直接写入。")
        print()
        print("白名单里有 %d 个位置已声明理由：" % len(ALLOWED))
        for k, v in sorted(ALLOWED.items()):
            print("  · %s —— %s" % (k, v))
        sys.exit(0)

    print("**这些位置直接写文件，且没走 AtomicFiles：**")
    print()
    for rel, ln, text in suspects:
        print("  %s:%d" % (rel, ln))
        print("      %s" % text)
    print()
    print(SUSPECT_HINT)
    print()
    print("两种处理：")
    print("  1. 改用 com.zhixueyao.util.AtomicFiles.write(file, text)")
    print("  2. 加进本脚本的 ALLOWED，并写明「为什么丢了不可惜」")
    print()
    print("判据：**读取端会不会静默兜底？** 会 → 就必须原子写入。")
    print("      （坏文件 + runCatching = 数据静默消失，用户只会觉得「怎么又忘了」）")
    sys.exit(1)


main()
