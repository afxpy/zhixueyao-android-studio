"""
一条命令跑完所有机械检查。

## 为什么需要它

今晚做了六个机械检查（列宽、提示词覆盖、非原子写入、重复实现、死代码扫描…），
每个都能找出真问题。但它们**各跑各的** —— 散在技能目录里，
下次要用得先想起来「哦，还有这么个脚本」。

**一个不会被想起来的检查，等于不存在。**

而且有一个更实际的问题：**它们从来没被一起跑过**。
各自跑的时候都通过，一起跑才发现有的报假阳性、有的报噪音
（今晚就撞到：一个把注释当调用，另一个对 6 个文件各报一次「注意」）。

## 设计上的三个决定

1. **自动发现，不列清单**。脚本自己 `scan_*.py` 扫目录。
   手写清单的话，加了新检查却忘了登记 —— 又是一个「静默失效」。

2. **只报「需要表态的」，不复述通过的细节**。通过的说一行就够，
   有发现的才展开。否则输出会淹没在「一切正常」里。

3. **退出码即结论**：0 = 全部干净，非零 = 有需要处理的。
   这样它能直接当提交前的门禁用。

## 它**不**做的事

**不跑探针**（那要先构建，几分钟）。探针是另一条链路（`regress.sh`）。
两者分工：探针验**行为对不对**，这些检查验**代码里有没有可疑形状**。
"""

import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SELF = os.path.basename(__file__)

# 自动定位工程根：脚本在 .zhixueyao/skills/<技能>/scripts/ 下，往上四级即工程根。
# 这样脚本**放到任何一台机器、任何一个克隆目录都能跑**，不需要改任何常量
# —— 之前这里写的是本机绝对路径，同步到仓库时必须脱敏成 <工程根> 占位符，
# 而占位符是跑不了的。**能自动算出来的东西就不要写成常量。**
_HERE = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(_HERE))))
_AUTO_SRC = os.path.join(_PROJECT_ROOT, "src", "main", "kotlin", "com", "zhixueyao")

DEFAULT_SRC = _AUTO_SRC if os.path.isdir(_AUTO_SRC) else DEFAULT_SRC
PY = sys.executable


def discover():
    """找出同目录下的所有 scan_*.py（排除自己）"""
    out = []
    for f in sorted(os.listdir(HERE)):
        if f.startswith("scan_") and f.endswith(".py") and f != SELF:
            out.append(f)
    return out


def run_one(script, src_root):
    """跑一个检查，返回 (名称, 是否干净, 输出)"""
    path = os.path.join(HERE, script)
    name = script[len("scan_"):-len(".py")]
    try:
        p = subprocess.run(
            [PY, path, src_root],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        return name, p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return name, False, "（超时 120 秒被杀）"
    except Exception as e:
        return name, False, "（跑不起来：%s）" % e


def first_line_of_finding(out):
    """从输出里挑一句最有信息量的话，用于摘要行"""
    lines = [l.rstrip() for l in out.split("\n")]
    for i, l in enumerate(lines):
        if l.startswith("**") and l.endswith("**"):
            # 往后找第一条缩进的项目
            for j in range(i + 1, min(i + 6, len(lines))):
                if lines[j].strip().startswith("·") or lines[j].strip().startswith("\u00b7"):
                    return lines[j].strip()
        if "需要表态" in l or "这些位置" in l or "这些工具" in l:
            continue
    # 退一步：找第一个带 "·" 的行
    for l in lines:
        if l.strip().startswith("\u00b7"):
            return l.strip()
    return ""


def main():
    src_root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC

    if not os.path.isdir(src_root):
        print("源码目录不存在：%s" % src_root)
        sys.exit(2)

    scripts = discover()
    if not scripts:
        print("没找到任何 scan_*.py 检查脚本。")
        sys.exit(2)

    print("=" * 60)
    print("机械检查（%d 项）" % len(scripts))
    print("源码：%s" % src_root)
    print("=" * 60)
    print()

    results = []
    for s in scripts:
        name, clean, out = run_one(s, src_root)
        results.append((name, clean, out))
        mark = "✓ 干净" if clean else "✗ 有发现"
        print("  %-22s %s" % (name, mark))
        if not clean:
            hint = first_line_of_finding(out)
            if hint:
                print("      → %s" % hint)

    bad = [r for r in results if not r[1]]

    print()
    if not bad:
        print("-" * 60)
        print("全部干净。")
        print()
        print("（这些是**静态检查** —— 它们看的是「代码里有没有可疑形状」。")
        print("  行为是否正确要跑探针：bash regress.sh）")
        sys.exit(0)

    print("-" * 60)
    print("有 %d 项需要表态。详情：" % len(bad))
    print()
    for name, _clean, out in bad:
        print("─" * 60)
        print("### %s" % name)
        print("─" * 60)
        print(out.rstrip())
        print()

    print("-" * 60)
    print("每一项都有两条路：**改代码**，或者**在脚本里写明「为什么这样没问题」**。")
    print("两条都不做的话，这份清单会一直红着 —— 而红久了就没人看了。")
    sys.exit(1)


main()
