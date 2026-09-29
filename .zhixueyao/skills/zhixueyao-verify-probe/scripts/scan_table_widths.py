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

# 常见设置页的可用宽度（IDE 内嵌设置页，去掉左侧导航后的净宽）。
# 取 640 是一个偏保守的值 —— 宁可早报警，也不要等用户反馈才发现。
PANEL_WIDTH = 640

# 每个表格至少要留多少给「自适应列」，否则内容读不了
MIN_FLEX = 100


def scan(path):
    """返回这个文件里每张表的列配置"""
    text = io.open(path, encoding="utf-8").read()
    lines = text.split("\n")
    tables = {}

    # 匹配 `xxx.columnModel.getColumn(N).preferredWidth = 123`
    pat = re.compile(r"(\w+)\.columnModel\.getColumn\((\d+)\)\.(preferredWidth|minWidth|maxWidth)\s*=\s*(\d+)")
    # 也匹配 `.apply { preferredWidth = ...; minWidth = ... }` 这种块写法
    apply_pat = re.compile(r"(\w+)\.columnModel\.getColumn\((\d+)\)\.apply\s*\{")
    resize_pat = re.compile(r"(\w+)\.autoResizeMode\s*=\s*JTable\.(AUTO_RESIZE_\w+)|"
                            r"(\w+)\.autoResizeMode\s*=\s*javax\.swing\.JTable\.(AUTO_RESIZE_\w+)")

    for i, line in enumerate(lines):
        m = pat.search(line)
        if m:
            t, idx, kind, val = m.group(1), int(m.group(2)), m.group(3), int(m.group(4))
            tables.setdefault(t, {}).setdefault(idx, {})[kind] = val
            continue
        m2 = apply_pat.search(line)
        if m2:
            t, idx = m2.group(1), int(m2.group(2))
            # 往后找这个 apply 块里的赋值（最多看 8 行）
            for j in range(i + 1, min(i + 9, len(lines))):
                m3 = re.search(r"(preferredWidth|minWidth|maxWidth)\s*=\s*(\d+)", lines[j])
                if m3:
                    tables.setdefault(t, {}).setdefault(idx, {})[m3.group(1)] = int(m3.group(2))
                if "}" in lines[j]:
                    break
            continue
        m4 = resize_pat.search(line)
        if m4:
            t = m4.group(1) or m4.group(3)
            mode = m4.group(2) or m4.group(4)
            tables.setdefault(t, {}).setdefault("_resize", {})["mode"] = mode

    return tables


findings = []
scanned = 0
for root, _d, files in os.walk(SRC):
    for f in files:
        if not f.endswith(".kt"):
            continue
        path = os.path.join(root, f)
        tables = scan(path)
        for t, cols in tables.items():
            numeric = {k: v for k, v in cols.items() if isinstance(k, int)}
            if not numeric:
                continue
            scanned += 1

            # `_resize` 是给这张表打的标记，不是某一列 —— 早先写成
            # `if "_resize" in cols: continue`，结果**把设了自适应模式的表整个跳过了**，
            # 扫出 0 张表还报「全部通过」。典型的「扫描器自己错了却报绿灯」。
            has_resize = "_resize" in cols
            mode = cols.get("_resize", {}).get("mode", "AUTO_RESIZE_SUBSEQUENT_COLUMNS")
            lastCol = max(numeric.keys())

            # 最坏情况宽度的算法**取决于 resize 模式** —— 这点第一版搞错了，
            # 于是把「只有最后一列会被调整」的表当成了「所有列都可能被压扁」。
            #
            # `AUTO_RESIZE_LAST_COLUMN`：只有最后一列随视口伸缩，其余保持原宽。
            #   所以最坏宽度 = 前面各列的 preferredWidth + 最后一列的 minWidth。
            # 其他模式：所有列都可能被压缩，逐列取 minWidth（没设则退回 preferredWidth）。
            worst = 0
            no_min = []
            for idx, cfg in sorted(numeric.items()):
                if mode == "AUTO_RESIZE_LAST_COLUMN":
                    if idx == lastCol:
                        if "minWidth" in cfg:
                            worst += cfg["minWidth"]
                        else:
                            worst += cfg.get("preferredWidth", cfg.get("maxWidth", 0))
                            no_min.append(idx)
                    else:
                        worst += cfg.get("preferredWidth", cfg.get("maxWidth", 0))
                else:
                    if "minWidth" in cfg:
                        worst += cfg["minWidth"]
                    elif "preferredWidth" in cfg:
                        worst += cfg["preferredWidth"]
                        no_min.append(idx)
                    elif "maxWidth" in cfg:
                        worst += cfg["maxWidth"]
            findings.append({
                "file": os.path.basename(path),
                "table": t,
                "cols": len(numeric),
                "worst": worst,
                "hasResize": has_resize,
                "noMin": no_min,
                "detail": {k: v for k, v in sorted(numeric.items())},
            })

print("扫了 %d 张写死列宽的表格" % scanned)
print()

bad = 0
for f in sorted(findings, key=lambda x: -x["worst"]):
    problems = []
    if f["worst"] > PANEL_WIDTH:
        problems.append("最坏宽度 %d > 面板 %d" % (f["worst"], PANEL_WIDTH))
    if not f["hasResize"]:
        problems.append("没设 AUTO_RESIZE_*（默认 SUBSEQUENT 时容易溢出）")
    if f["noMin"]:
        problems.append("自适应列（第 %s 列）没设 minWidth —— 窄窗口下会被压成一条线，比截断更难用" % f["noMin"])

    flag = "✗" if problems else "OK"
    if problems:
        bad += 1
    print("  %s %-24s %-14s 列数=%d  最坏宽度=%d  %s"
          % (flag, f["file"], f["table"], f["cols"], f["worst"],
             "自适应末列" if f["hasResize"] else "**未设自适应**"))
    print("      %s" % f["detail"])
    for p in problems:
        print("      → %s" % p)

print()
print("---")
if bad == 0:
    print("全部通过：没有表格会在常见设置页宽度下横向溢出。")
else:
    print("有 %d 张表需要注意（见上面的 → 行）。" % bad)
print()
print("判据说明：")
print("  · 最坏宽度按 resize 模式算：AUTO_RESIZE_LAST_COLUMN 下只有末列会伸缩，")
print("    所以 = 前面各列 preferredWidth + 末列 minWidth；其他模式下逐列取 minWidth")
print("  · 阈值 %dpx 取的是偏僻保守的值：宁可早报警，也别等用户反馈" % PANEL_WIDTH)
print("  · 最后一列应显式设 minWidth，否则窄窗口下会被压成一条线（比截断更糟）")
print()
print("这个脚本永远返回 0：它是清单生成器，不是门禁 —— 列宽有时确实需要为内容让步。")
sys.exit(0)
