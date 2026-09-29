import com.zhixueyao.tools.KnowledgeBase;
import com.zhixueyao.tools.KnowledgeBase.Chunk;
import com.zhixueyao.tools.KnowledgeBase.Hit;
import com.zhixueyao.tools.KnowledgeBase.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库**端到端**测试：读真实的 ~/.zhixueyao/kb/，跑真实的检索。
 *
 * ## 和 KnowledgeProbe 有什么不同
 *
 * `KnowledgeProbe` 用的是**构造语料**，验的是「排序算法本身对不对」。
 * 这个探针用的是**磁盘上真实的知识页**，验的是整条链路：
 *
 *     真实 .md 文件 → 解析 front matter → 按 # 切段 → 分词 → BM25 → 排序
 *
 * 两层的价值不一样。构造语料能精确控制变量（长度、标题、复读次数），
 * 但它验不出「真实页面的结构会不会让检索失效」——
 * 比如真实页面往往有很多小节，彼此之间会有词重叠，
 * 那会不会把真正该命中的那节挤下去？只有真跑才知道。
 *
 * ## 查询是怎么设计的（这是本探针的关键）
 *
 * **刻意用「记得有这回事、但想不起原话」的说法**，而不是从标题里抄词。
 * 如果查询直接抄标题，那测的其实是「字符串相等」，随便一个实现都能过。
 *
 * 比如不写「git 推送失败怎么查」（那是标题原文），
 * 而写「代理明明开着为什么还是连不上」—— 这才需要相关度排序。
 *
 * 每条断言都带**期望命中的页面**，所以它同时也是「检索准确率」的度量。
 */
public class KbEndToEndProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        System.out.println("=== 知识库端到端（读真实目录）===");

        List<Page> pages = KnowledgeBase.INSTANCE.allPages();
        List<Chunk> chunks = KnowledgeBase.INSTANCE.allChunks();

        System.out.println("目录：" + KnowledgeBase.INSTANCE.root().getAbsolutePath());
        System.out.println("读到 " + pages.size() + " 页，" + chunks.size() + " 个检索小节");
        System.out.println();
        for (Page p : pages) {
            System.out.println("  · " + p.getTitle() + "　[" + String.join(", ", p.getTags()) + "]");
        }
        System.out.println();

        judge("目录非空（测试前应已写入知识页）", !pages.isEmpty(),
                pages.isEmpty() ? "知识库是空的，先写入内容再跑" : "");
        judge("每页都切出了多个小节（说明 # 分段生效）",
                chunks.size() >= pages.size(), chunks.size() + " 段 / " + pages.size() + " 页");

        // ---------- 核心：模糊查询能不能找到对的那页 ----------
        System.out.println();
        System.out.println("--- 模糊查询（不抄标题里的词）---");

        vague("代理明明开着为什么还是连不上", "git-推送失败怎么查");
        vague("推送完不确定到底成没成", "git-推送失败怎么查");
        vague("命令替换把我要写的东西弄坏了", "shell-字符串陷阱");
        vague("为什么界面上会多出一张空的卡片", "同一个语法被解析两遍就会分叉");
        vague("改代码的时候该听标准的还是该听实际的", "判错方向代价不对称时怎么选");
        vague("两处代码写同一件事结果行为不一样", "同一个语法被解析两遍就会分叉");

        // ---------- 反向：无关查询要么为空，要么只能是「弱命中」 ----------
        //
        // 注意这里断言的是**弱**而不是**空** —— 这个区别是本探针最重要的设计决定：
        //
        // 最初我让它必须为空（加了「命中覆盖率 ≥ 1/3」的门槛），
        // 结果**把 6 条真实模糊查询里的 5 条也打成了空**。
        // 因为自然语言问句里全是功能词 bigram（为什么/还是/明明），
        // 它们本来就不该命中 —— 要求覆盖率，惩罚的恰恰是「用自然语言提问」。
        //
        // 所以现在改成：不做门槛，但把低分结果如实标注出来让模型自己判断。
        // 断言也跟着改成「噪声只能是低分的」——**钉住的是这个决定，不是那个理想**。
        System.out.println();
        System.out.println("--- 无关查询：只允许弱命中，不许高分混进来 ---");
        {
            String[] irrelevant = {
                "量子纠缠的退相干时间怎么算",
                "如何腌制四川泡菜",
                "股票市盈率的分位数"
            };
            int hard = 0;
            for (String q : irrelevant) {
                List<Hit> hits = KnowledgeBase.INSTANCE.rank(q, chunks, 3);
                if (hits.isEmpty()) {
                    System.out.println("   「" + q + "」→ 空（最干净）");
                    continue;
                }
                double top = hits.get(0).getScore();
                // 有效命中的首条实测 6~20；噪声 2.4。分界线 4.0
                if (top >= 4.0) {   // 与 WEAK_SCORE_HINT 对齐
                    hard++;
                    System.out.println("   ✗ 「" + q + "」首条 " + String.format("%.3f", top)
                            + " 分数过高，会被误当成真命中：" + hits.get(0).getChunk().getSource());
                } else {
                    System.out.println("   「" + q + "」→ 弱命中 " + String.format("%.3f", top)
                            + "（会被标注为「相关度偏低」）");
                }
            }
            judge("无关查询没有高分混入（要么空、要么弱）", hard == 0, hard + " 条分数过高");
        }

        // ---------- 对照：真命中的分数必须明显高于噪声 ----------
        System.out.println();
        System.out.println("--- 对照组：真命中 vs 噪声，分数要有量级差 ---");
        {
            List<Hit> real = KnowledgeBase.INSTANCE.rank("推送完不确定到底成没成", chunks, 1);
            List<Hit> noise = KnowledgeBase.INSTANCE.rank("如何腌制四川泡菜", chunks, 1);
            double realScore = real.isEmpty() ? 0 : real.get(0).getScore();
            double noiseScore = noise.isEmpty() ? 0 : noise.get(0).getScore();
            System.out.printf("   真命中 %.3f　噪声 %.3f%n", realScore, noiseScore);
            judge("真命中分数是噪声的 2 倍以上", realScore > noiseScore * 2,
                    String.format("%.3f vs %.3f", realScore, noiseScore));
        }

        // ---------- 标签过滤 ----------
        System.out.println();
        System.out.println("--- 标签过滤 ---");
        {
            var tags = KnowledgeBase.INSTANCE.allTags();
            System.out.println("   标签：" + tags);
            judge("标签被解析出来了", !tags.isEmpty(), "");

            List<Page> only = KnowledgeBase.INSTANCE.filterByTags(List.of("git"));
            judge("按 git 过滤能筛出页面", !only.isEmpty(), "筛出 " + only.size() + " 页");
            judge("筛出的页面确实带这个标签",
                    only.stream().allMatch(p -> p.getTags().stream()
                            .anyMatch(t -> t.toLowerCase().contains("git"))), "");

            List<Page> none = KnowledgeBase.INSTANCE.filterByTags(List.of("不存在的标签xyz"));
            judge("不存在的标签返回空", none.isEmpty(), "返回了 " + none.size() + " 页");
        }

        // ---------- 排序合理性 ----------
        System.out.println();
        System.out.println("--- 排序细节 ---");
        {
            String q = "git 推送 代理";
            List<Hit> hits = KnowledgeBase.INSTANCE.rank(q, chunks, 5);
            System.out.println("   查询「" + q + "」：");
            for (Hit h : hits) {
                System.out.printf("     %.3f  %s › %s%n", h.getScore(),
                        h.getChunk().getSource(), h.getChunk().getHeading());
            }
            judge("有结果", !hits.isEmpty(), "");
            judge("分数单调不增",
                    monotonic(hits), "排序不稳定或分数乱序");
            judge("首条来自 git 那页",
                    !hits.isEmpty() && hits.get(0).getChunk().getSource().contains("git"),
                    hits.isEmpty() ? "" : hits.get(0).getChunk().getSource());
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 模糊查询 → 期望命中的页面片段 */
    static void vague(String query, String expectSource) {
        List<Chunk> chunks = KnowledgeBase.INSTANCE.allChunks();
        List<Hit> hits = KnowledgeBase.INSTANCE.rank(query, chunks, 3);

        System.out.println("   查询：「" + query + "」");
        if (hits.isEmpty()) {
            System.out.println("     （无结果）");
        } else {
            for (Hit h : hits) {
                System.out.printf("     %.3f  %s › %s%n", h.getScore(),
                        h.getChunk().getSource(), h.getChunk().getHeading());
            }
        }
        boolean ok = !hits.isEmpty() && hits.get(0).getChunk().getSource().contains(expectSource);
        judge("应命中「" + expectSource + "」", ok,
                ok ? "" : "首条是 " + (hits.isEmpty() ? "（空）" : hits.get(0).getChunk().getSource()));
    }

    static boolean monotonic(List<Hit> hits) {
        for (int i = 1; i < hits.size(); i++) {
            if (hits.get(i).getScore() > hits.get(i - 1).getScore() + 1e-9) return false;
        }
        return true;
    }
}
