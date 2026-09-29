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

SRC = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC

# 明确「不需要提示词判据」的工具 —— 每一个都要能说出理由。
# 加新工具时如果落到这之外，脚本会报错，逼你去补提示词或来解释。
NOT_NEEDED = {
    # 读/写文件：名字即判据，没有相近替代品
    "read_file": "看名字就知道",
    "write_file": "看名字就知道",
    "edit_file": "看名字就知道",
    "delete_file": "看名字就知道",
    "restore_file": "看名字就知道",
    "list_directory": "看名字就知道",
    "project_structure": "看名字就知道",
    "get_diagnostics": "看名字就知道",
    # 媒体产物：由任务决定，不是由「选哪个」决定
    "generate_svg": "任务本身决定，无选择歧义",
    "export_drawable": "任务本身决定",
    "save_asset": "任务本身决定",
    # 时间：描述已够
    "current_time": "单义，描述已够",
    # 子代理/后台任务的「配套」工具：主工具在提示词里有说明，
    # 这几个是被主工具带出来的（起了之后自然会用）
    "wait_agent": "配套 wait_agent/spawn_agent 的主工具已在提示词里说明",
    "list_agents": "配套，无需独立判据",
    "interrupt_agent": "配套，无需独立判据",
    # 有开关控制，且「要不要用」由开关决定而非模型判断
    "todo_write": "开关控制（showTaskList），提示词另有一节",
    "ask_user": "开关控制（allowAskUser），描述已给出判据",
}


# 提示词的**来源文件** —— 不只是 AgentRunner 里那段静态文本。
#
# 这一点第一版搞错了：只扫 AgentRunner.kt，于是把 `skill` 报成「提示词没提」。
# 实际上它在 Skills.catalogForPrompt() 里写着（「遇到对应场景时先用 skill 工具
# 把正文取回来」）—— 只是那段文字是**运行时拼**进去的，不在静态字符串里。
#
# **这个检查的全部意义是「模型真正看到的提示词」**，所以动态片段必须算进来。
# 漏掉它们的后果是**假阳性**：报一个其实已经说了的工具，
# 然后人为了让它变绿，要么去加一句重复的话、要么往 NOT_NEEDED 里塞个假理由 ——
# 两种都在污染这份检查的可信度。
#
# 局限（如实写在这里，别假装没有）：
# 这是**按源码里的字符串字面量**判定的，不是真的把提示词拼出来跑一遍。
# 由变量拼出来的文字扫不到。
# 真要做严格版，得让插件把拼好的提示词 dump 出来再扫 —— 留作以后的改进。
#
# 但即使有这个局限，它也已经能挡住最常见的退化：
# **加了工具、忘了往提示词里加判据。**
PROMPT_SOURCES = [
    "agent/AgentRunner.kt",   # 静态主体
    "agent/Skills.kt",        # 技能目录（运行时拼）
    "agent/MemoryStore.kt",   # 记忆片段（运行时拼）
    "mcp/McpManager.kt",      # MCP 使用说明（运行时拼）
]


def string_literals(src):
    """
    从 Kotlin 源码里抽出**字符串字面量**，丢掉注释与代码。

    ## 为什么必须做这一步

    第一版直接把整个源文件拼起来扫，结果是 84245 字符 —— 里面绝大部分是代码和注释。
    那样扫的话，**代码注释里提一句工具名就会被算成「提示词里说了」**，
    这个检查就形同虚设（假阴性：真正没写的也能过）。

    而太窄也不行：只扫 AgentRunner 里那段静态提示词，会把 `skill` 这种
    「在 Skills.catalogForPrompt() 里写的」误报成缺失（假阳性）。

    所以取中间：**只扫字符串字面量** —— 提示词是由字面量拼出来的，
    而注释和代码不是。这样两个方向都对了。
    """
    out = []
    # 先剥掉行注释，免得字面量里的引号被注释里的引号配对错
    src = re.sub(r"^\s*//.*$", "", src, flags=re.M)
    # 三引号原始串（提示词主体都是这种）
    out.extend(re.findall(r'"""(.*?)"""', src, flags=re.S))
    # 普通双引号串
    out.extend(re.findall(r'"((?:[^"\\\n]|\\.)*)"', src))
    return "\n".join(out)


def prompt_text(src_root):
    """把「会进提示词」的片段合起来（只取字符串字面量）"""
    parts = []
    for rel in PROMPT_SOURCES:
        f = os.path.join(src_root, rel)
        if os.path.isfile(f):
            parts.append(string_literals(io.open(f, encoding="utf-8").read()))
    return "\n".join(parts)


def collect_tools(src_root):
    """从注册点反查每个工具的名字"""
    runner = os.path.join(src_root, "agent/AgentRunner.kt")
    ar = io.open(runner, encoding="utf-8").read()
    classes = sorted(set(re.findall(r"com\.zhixueyao\.tools\.(\w+Tool)\(\)", ar)))

    found = {}
    tools_dir = os.path.join(src_root, "tools")
    files = {}
    for f in os.listdir(tools_dir):
        if f.endswith(".kt"):
            files[f] = io.open(os.path.join(tools_dir, f), encoding="utf-8").read()

    for cls in classes:
        for _fname, txt in files.items():
            m = re.search(r"class " + cls + r"\b[\s\S]{0,600}?override val name = \"([a-z_]+)\"", txt)
            if m:
                found[m.group(1)] = cls
                break
    return ar, found


def main():
    ar, tools = collect_tools(SRC)

    # 切提示词 —— **自检**：切完先用几个已知存在的条目验一下，
    # 免得切片失败却报出「点名 0 个」这种看起来很像结论的假结果
    # （第一版就是这么错的：两个下标顺序反了，切出空串）
    start = ar.index('你是「止血药」')
    static_prompt = ar[start:ar.index('""".trimIndent()', start)]
    # 静态主体 + 所有「会拼进提示词」的片段
    prompt = static_prompt + "\n" + prompt_text(SRC)
    probes = ["`git`", "`run_script`", "`memory`"]
    if not all(p in prompt for p in probes):
        print("扫描器自检失败：提示词切片不对（切出 %d 字符）" % len(prompt))
        sys.exit(2)

    named = {n for n in tools if ("`%s`" % n) in prompt}

    print("提示词来源：静态 %d 字符 + 动态片段，合计 %d 字符"
          % (len(static_prompt), len(prompt)))
    print("工具 %d 个：提示词点名 %d 个" % (len(tools), len(named)))
    print()

    silent = sorted(n for n in tools if n not in named)
    unaccounted = [n for n in silent if n not in NOT_NEEDED]

    if not unaccounted:
        print("全部有交代：")
        print("  · %d 个在提示词里有判据" % len(named))
        print("  · %d 个已声明「不需要判据」" % len(silent))
        print()
        print("---")
        print("通过。")
        sys.exit(0)

    print("**这些工具既不在提示词里，也没声明不需要判据：**")
    for n in unaccounted:
        print("   · " + n)
    print()
    print("两种处理方式，选一个：")
    print("  1. 去 AgentRunner 的提示词里补一条判据 —— 尤其当它有相近替代品时")
    print("     （判据是「什么时候用这个而不是那个」，不是复述它能做什么）")
    print("  2. 在本脚本的 NOT_NEEDED 里加一行，并写明「为什么不需要」")
    print()
    print("为什么强制表态：见文件头。**做对了但没人用，等于没做。**")
    sys.exit(1)


main()
