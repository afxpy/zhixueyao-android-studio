import com.zhixueyao.tools.BinaryHints;

/**
 * 读到二进制文件时的提示文案。
 *
 * ## 为什么这段文案值得探针
 *
 * 它是这类工具**唯一的产出** —— 「读不了」这个事实本身不需要我们告诉模型
 * （它自己也知道失败了），我们真正要提供的是**下一步该怎么办**。
 * 文案写偏了，等于这个工具没做。
 *
 * 有两条断言是「反向」的，特别值得留下：
 *
 *  - **不许出现「无法读取」这种没有出路的说法**（那正是改造前的样子）
 *  - **图片必须引导到「让用户作为附件发过来」** —— 因为我们有视觉能力，
 *    这是一条真实可行的路，漏掉它用户就白折腾了
 *
 * 第二条尤其重要：写这段代码时很容易把「图片」也归到「读不了」里，
 * 而实际上**图片是唯一一类我们换个通道就能完全读懂的格式**。
 */
public class BinaryHintProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static String hint(String name, long size) {
        return BinaryHints.INSTANCE.explain(name, size);
    }

    public static void main(String[] args) {
        System.out.println("=== 二进制文件提示 ===");

        // ---------- 反例：不能只说「读不了」 ----------
        System.out.println("--- ① 不许只说「读不了」 ---");
        {
            String[] all = {
                hint("a.pdf", 1000), hint("a.png", 1000), hint("a.docx", 1000),
                hint("a.zip", 1000), hint("a.class", 1000), hint("a.bin", 1000)
            };
            int bare = 0;
            for (String h : all) {
                // 「无法按文本读取」是被替换掉的那句老文案
                if (h.contains("无法按文本读取") || h.trim().endsWith("读不出文本。")) bare++;
            }
            judge("没有一条只说「读不出」就完了", bare == 0, bare + " 条没有下一步");
        }

        // ---------- 图片：必须引导到「我能看图」 ----------
        System.out.println();
        System.out.println("--- ② 图片：要告诉它「我有视觉能力」---");
        {
            String[] imgs = {"shot.png", "shot.jpg", "shot.jpeg", "shot.gif", "shot.webp", "shot.bmp"};
            int bad = 0;
            for (String f : imgs) {
                String h = hint(f, 2048);
                if (!h.contains("看图") || !h.contains("附件")) {
                    bad++;
                    System.out.println("   ✗ " + f + " 没引导到视觉通道");
                }
            }
            judge("所有图片格式都引导到视觉通道", bad == 0, bad + " 条漏了");
            System.out.println("   示例：" + hint("shot.png", 2048).replace("\n", " | "));
        }

        // ---------- 扩展名大小写 ----------
        System.out.println();
        System.out.println("--- ③ 扩展名大小写要认（.PNG / .Pdf）---");
        {
            boolean a = hint("SHOT.PNG", 100).contains("看图");
            boolean b = hint("Doc.PDF", 100).contains("PDF");
            boolean c = hint("A.JpEg", 100).contains("看图");
            judge(".PNG 认得出", a, "");
            judge(".PDF 认得出", b, "");
            judge(".JpEg 认得出", c, "");
        }

        // ---------- 路径分隔符 ----------
        System.out.println();
        System.out.println("--- ④ 不管传进来的路径用哪种分隔符 ---");
        {
            judge("正斜杠路径", hint("app/src/main/res/logo.png", 100).contains("看图"), "");
            judge("反斜杠路径", hint("app\\res\\drawable\\logo.png", 100).contains("看图"), "");
            judge("只有文件名", hint("logo.png", 100).contains("看图"), "");
        }

        // ---------- PDF 要说清限制与出路 ----------
        System.out.println();
        System.out.println("--- ⑤ PDF：说清为什么读不了 + 给几条出路 ---");
        {
            String h = hint("需求文档.pdf", 3_500_000);
            System.out.println("   " + h.replace("\n", " | "));
            judge("说明了是 PDF", h.contains("PDF"), "");
            judge("解释了原因（嵌入字体）", h.contains("字体") || h.contains("文本层"), "");
            judge("给了「截图」这条路", h.contains("截图"), "");
            judge("给了转文本这条路", h.contains("转成文本") || h.contains("Markdown"), "");
            judge("人读的大小正确（3.5MB）", h.contains("3.3 MB"), "应显示 3.3 MB");
        }

        // ---------- 大小格式化 ----------
        System.out.println();
        System.out.println("--- ⑥ 大小要人读得懂 ---");
        {
            judge("小文件用字节", hint("a.bin", 512).contains("512 字节"), "");
            judge("中等用 KB", hint("a.bin", 2048).contains("2.0 KB"), "");
            judge("大文件用 MB", hint("a.bin", 5L * 1024 * 1024).contains("5.0 MB"), "");
        }

        // ---------- 未知类型也要留个口子 ----------
        System.out.println();
        System.out.println("--- ⑦ 认不出的类型：要留互动的余地，不是死路 ---");
        {
            String h = hint("mystery.xyz", 100);
            System.out.println("   " + h.replace("\n", " | "));
            judge("没有硬猜它是什么", !h.contains("这是一张图片") && !h.contains("这是 PDF"), "");
            judge("留了「告诉我你想得到什么」的余地",
                    h.contains("你想") || h.contains("告诉我"), "");
        }

        // ---------- 文件名带点号 ----------
        System.out.println();
        System.out.println("--- ⑧ 文件名里多点号 / 没扩展名不能崩 ---");
        {
            try {
                String a = hint("my.report.v2.pdf", 100);
                judge("多点号取最后一段", a.contains("PDF"), a.lines().findFirst().orElse(""));
                String b = hint("noext", 100);
                judge("没扩展名不崩", b != null && !b.isBlank(), "");
                String c = hint("", 100);
                judge("空路径不崩", c != null && !c.isBlank(), "");
            } catch (Exception e) {
                judge("边界不崩", false, "异常：" + e);
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
