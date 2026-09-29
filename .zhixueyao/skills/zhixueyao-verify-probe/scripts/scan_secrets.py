#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提交前扫敏感信息。

## 为什么单独写一个

用户的原话：「**但是别把我的密钥以及账号密码暴露提交上去了**」。

**这件事不能靠「我记得清理一下」** —— 一次提交漏进去，历史里就永久留着了
（而且推送出去之后，别人可能已经抓走了）。所以做成机械检查：

  · 提交前跑一遍，**有任何命中就先停下来人工看**
  · 它扫的是**即将提交的内容**，不是整个磁盘

## 判据

**强特征**（几乎可以确定是密钥，直接报）：
  - `sk-` / `ghp_` / `gho_` / `github_pat_` 这类公认前缀
  - `AKIA` / `LTAI` 这类云厂商 AccessKey 前缀
  - `-----BEGIN ... PRIVATE KEY-----`

**弱特征**（要人工判断，可能是占位符也可能是真东西）：
  - `token` / `secret` / `password` / `apiKey` 后面跟着一长串字面量
  - 长的高熵字符串

**要排除**：
  - 代码里的字段**名**（`var apiKey: String`）—— 那只是声明
  - 占位符（`"your-key-here"`、`"xxx"`、空串）
  - 注释里提到这些词
"""
import io
import os
import re
import sys

# ---------- 强特征：命中即报 ----------
STRONG = [
    (re.compile(r"sk-[A-Za-z0-9_\-]{20,}"), "OpenAI 风格的 key（sk- 开头）"),
    (re.compile(r"ghp_[A-Za-z0-9]{30,}"), "GitHub Personal Access Token"),
    (re.compile(r"gho_[A-Za-z0-9]{30,}"), "GitHub OAuth token"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{30,}"), "GitHub fine-grained PAT"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS Access Key ID"),
    (re.compile(r"LTAI[A-Za-z0-9]{12,}"), "阿里云 AccessKey"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "私钥文件内容"),
    (re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}"), "Slack token"),
]

# ---------- 弱特征：赋值给「像密钥的字段名」的长字面量 ----------
WEAK = re.compile(
    r"""(?:apiKey|api_key|token|secret|password|passwd|pwd|credential|accessKey|access_key)"""
    r"""\s*[:=]\s*"([^"]{12,})" """,
    re.IGNORECASE | re.VERBOSE
)

# 这些是占位符/示例，不算
PLACEHOLDER = re.compile(
    r"^(?:your[-_]?|my[-_]?|xxx|abc|test|demo|example|placeholder|\*{3,}|\.{3,}|<.+>)",
    re.IGNORECASE
)

# 这些目录/文件不扫（不是要提交的内容）
SKIP_DIRS = {".git", "build", ".gradle", "out", ".idea", "node_modules", ".kotlin"}
SKIP_EXT = {".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".zip", ".exe", ".dll", ".so"}


def project_root_from_script():
    here = os.path.dirname(os.path.abspath(__file__))
    for _ in range(4):
        here = os.path.dirname(here)
    return here


def walk(root):
    for dp, dns, fs in os.walk(root):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for f in fs:
            if os.path.splitext(f)[1].lower() in SKIP_EXT:
                continue
            yield os.path.join(dp, f)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else project_root_from_script()
    print("=" * 60)
    print("提交前检查：敏感信息")
    print("扫描：%s" % root)
    print("=" * 60)
    print()

    strong_hits = []
    weak_hits = []

    def should_skip_file(path):
        """这些文件不该进仓库 —— 里面装的本来就是本地状态。"""
        rel = os.path.relpath(path, root).replace("\\", "/")
        if rel.startswith(".workbuddy/"):
            return True
        return False

    n = 0
    for path in walk(root):
        if should_skip_file(path):
            continue
        n += 1
        try:
            text = io.open(path, encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        rel = os.path.relpath(path, root).replace("\\", "/")

        for lineno, line in enumerate(text.split("\n"), 1):
            s = line.strip()
            if s.startswith("//") or s.startswith("*"):
                continue          # 注释
            for pat, why in STRONG:
                if pat.search(line):
                    strong_hits.append((rel, lineno, why, s[:100]))
            for m in WEAK.finditer(line):
                val = m.group(1)
                if PLACEHOLDER.match(val):
                    continue
                if set(val) <= set("abcdefghijklmnopqrstuvwxyz0123456789_-") and "$" in val:
                    continue      # 拼接出来的（`$baseUrl/xxx`）
                weak_hits.append((rel, lineno, "像密钥的赋值", s[:100]))

    print("  扫了 %d 个文件" % n)
    print()
    print("  强特征命中：%d" % len(strong_hits))
    print("  弱特征命中：%d" % len(weak_hits))
    print()

    if strong_hits:
        print("  ⛔ **这些几乎肯定是真密钥，绝不能提交：**")
        for rel, lineno, why, code in strong_hits:
            print("     %s:%d  [%s]" % (rel, lineno, why))
            print("       %s" % code)
        print()

    if weak_hits:
        print("  ⚠️ 这些要人工判断（可能只是占位符）：")
        for rel, lineno, why, code in weak_hits[:20]:
            print("     %s:%d" % (rel, lineno))
            print("       %s" % code)
        if len(weak_hits) > 20:
            print("     …… 还有 %d 处" % (len(weak_hits) - 20))
        print()

    print("-" * 60)
    if strong_hits:
        print("有强特征命中 —— **先别提交**，处理完再来。")
        return 2
    elif weak_hits:
        print("只有弱特征 —— 人工扫一眼上面那些，确认是占位符就可以提交。")
        return 1
    else:
        print("没有发现敏感信息。")
        return 0


if __name__ == "__main__":
    sys.exit(main())
