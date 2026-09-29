import com.zhixueyao.tools.KnowledgeBase;
import com.zhixueyao.tools.KnowledgeBase.Chunk;
import com.zhixueyao.tools.KnowledgeBase.Hit;
import com.zhixueyao.tools.KnowledgeBase.Page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库：分词、切段、BM25 排序。
 *
 * ## 为什么排序必须验
 *
 * 这个工具的**全部价值就是「排序对不对」** —— 存储部分是几行读写文件，
 * 而如果排出来的第一条不是用户想要的那条，这个功能就等于不存在
 * （甚至更糟：它会占掉一次工具调用，还给出误导性的内容）。
 *
 * 而排序的错法是**静默的**：它不会报错、不会崩，只会给出一个「看起来有道理但不对」
 * 的顺序。所以只能靠构造语料断言。
 *
 * ## 中文分词是这里最容易整段错掉的地方
 *
 * 按空格切的话，中文整句会变成**一个 token** —— 那就意味着任何查询都匹配不上，
 * 整个知识库对中文用户完全失效。而且**它不会报错**，只是永远返回空结果。
 * 所以「中文能被切开」这一条要单独钉死。
 *
 * ## 反向断言
 *
 * 除了「A 应该排第一」，还断言「不相关的**不应该**出现在结果里」——
 * 只断言前者的话，一个「把所有段落都返回」的坏实现也能过。
 */
public class KnowledgeProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static Page page(String source, String raw) {
        return KnowledgeBase.INSTANCE.parse(raw, source);
    }

    static List<Chunk> chunksOf(String source, String raw) {
        return KnowledgeBase.INSTANCE.chunks(page(source, raw));
    }

    /** 取排序结果里第一条的来源 */
    static String topSource(String query, List<Chunk> corpus) {
        List<Hit> hits = KnowledgeBase.INSTANCE.rank(query, corpus, 5);
        return hits.isEmpty() ? "(空)" : hits.get(0).getChunk().getSource();
    }

    static List<String> sourcesOf(String query, List<Chunk> corpus, int k) {
        List<String> out = new ArrayList<>();
        for (Hit h : KnowledgeBase.INSTANCE.rank(query, corpus, k)) out.add(h.getChunk().getSource());
        return out;
    }

    public static void main(String[] args) {
        System.out.println("=== 知识库：中文分词 ===");

        // ---------- 分词：中文必须被切开 ----------
        System.out.println("--- ① 中文不能整句当一个 token ---");
        {
            List<String> t = KnowledgeBase.INSTANCE.tokenize("依赖冲突");
            System.out.println("   「依赖冲突」→ " + t);
            judge("切出了 bigram（依赖/赖冲/冲突）",
                    t.contains("依赖") && t.contains("冲突"), "");
            judge("保留了单字（查一个字时要用）", t.contains("依") && t.contains("冲"), "");
            judge("**不是**整句一个 token", t.size() > 1, "size=" + t.size());

            List<String> t2 = KnowledgeBase.INSTANCE.tokenize("Gradle 依赖冲突");
            judge("中英混排：英文按词切", t2.contains("gradle"), t2.toString());
            judge("中英混排：中文照切", t2.contains("依赖"), "");
            judge("英文大小写归一", KnowledgeBase.INSTANCE.tokenize("Gradle").contains("gradle"), "");
        }

        System.out.println();
        System.out.println("--- ② 单字母噪声要滤掉 ---");
        {
            List<String> t = KnowledgeBase.INSTANCE.tokenize("a i x ab");
            judge("单字母不进去", !t.contains("a") && !t.contains("i") && !t.contains("x"), t.toString());
            judge("两个字母的词保留", t.contains("ab"), t.toString());
        }

        System.out.println();
        System.out.println("--- ③ 标点不能把词粘在一起 ---");
        {
            List<String> t = KnowledgeBase.INSTANCE.tokenize("依赖冲突，怎么办？");
            judge("逗号切断", t.contains("冲突"), "");
            judge("问号切断，不产生「办？」这种 token",
                    !t.stream().anyMatch(s -> s.contains("？") || s.contains("？")), "");
        }

        // ---------- 解析 ----------
        System.out.println();
        System.out.println("=== 解析 ===");
        {
            Page p = page("gradle.md",
                "---\ntitle: Gradle 依赖冲突\ntags: gradle, 依赖, 冲突\n---\n\n# 排查步骤\n\n先看 dependencyInsight。");
            System.out.println("   title=" + p.getTitle() + "  tags=" + p.getTags());
            judge("读出了 title", "Gradle 依赖冲突".equals(p.getTitle()), "");
            judge("读出了 tags（3 个）", p.getTags().size() == 3, p.getTags().toString());
            judge("正文里不含 front matter", !p.getBody().contains("tags:"), "");

            // 没有 front matter 也要能用 —— 用户可能直接丢一个普通 md 进来
            Page p2 = page("note.md", "# 随手记的\n\n内容是这些。");
            judge("无 front matter：标题取第一个 #", "随手记的".equals(p2.getTitle()), p2.getTitle());
            judge("无 front matter：正文完整", p2.getBody().contains("内容是这些"), "");

            // 连 # 也没有 → 用文件名
            Page p3 = page("dir/plain-note.md", "就是一段话，没有标题。");
            judge("都没写：标题退回文件名", "plain-note".equals(p3.getTitle()), p3.getTitle());

            // tags 用中文逗号也要认（用户很容易打错）
            Page p4 = page("x.md", "---\ntags: 依赖，冲突\n---\n正文");
            judge("中文逗号也能分隔标签", p4.getTags().size() == 2, p4.getTags().toString());
        }

        // ---------- 切段 ----------
        System.out.println();
        System.out.println("=== 切段 ===");
        {
            List<Chunk> cs = chunksOf("a.md", "# 一\n内容一\n\n# 二\n内容二\n\n# 三\n内容三");
            judge("按 # 切成 3 节", cs.size() == 3, "切成 " + cs.size());
            judge("小节标题记了下来", cs.get(1).getHeading().equals("二"), cs.get(1).getHeading());

            // 超长小节要再切：不切的话长度归一化会把它压得不相关，而且塞不进上下文
            StringBuilder long_ = new StringBuilder("# 超长\n\n");
            for (int i = 0; i < 60; i++) long_.append("这是一段填充文字用来把小节撑长。段落内容第").append(i).append("条。\n\n");
            List<Chunk> cs2 = chunksOf("b.md", long_.toString());
            System.out.println("   超长小节（约 " + long_.length() + " 字符）→ " + cs2.size() + " 段");
            judge("超长小节被再切", cs2.size() > 1, "");
            int maxLen = cs2.stream().mapToInt(c -> c.getText().length()).max().orElse(0);
            judge("每段都在上限附近（不超过太多）", maxLen < 2400, "最长 " + maxLen);
        }

        // ---------- 排序：核心 ----------
        System.out.println();
        System.out.println("=== BM25 排序 ===");

        List<Chunk> corpus = new ArrayList<>();
        corpus.addAll(chunksOf("gradle.md",
            "---\ntitle: Gradle 依赖冲突\ntags: gradle\n---\n"
            + "# 依赖冲突怎么排查\n\n先跑 dependencyInsight 看是谁把版本顶上去了。\n"
            + "常见原因是两个库传递依赖了同一个库的不同版本。\n"
            + "# 强制版本\n\n可以用 resolutionStrategy 强制指定版本。\n"));
        corpus.addAll(chunksOf("keyboard.md",
            "---\ntitle: 快捷键冲突\ntags: ide\n---\n"
            + "# 快捷键冲突\n\n插件之间抢同一个快捷键时会不生效，去 Keymap 里看冲突。\n"));
        corpus.addAll(chunksOf("coffee.md",
            "# 咖啡机使用说明\n\n按下按钮，等三十秒，取杯。别忘清洗滤网。\n"));

        {
            // 【核心用例】用户描述的是「一件事」，不是关键词 —— 这正是本工具存在的理由
            String q = "我上次记过依赖冲突怎么处理";
            List<String> top = sourcesOf(q, corpus, 3);
            System.out.println("   查询：「" + q + "」");
            for (Hit h : KnowledgeBase.INSTANCE.rank(q, corpus, 3)) {
                System.out.printf("     %.3f  %s › %s%n", h.getScore(),
                        h.getChunk().getSource(), h.getChunk().getHeading());
            }
            judge("gradle 页排第一", top.get(0).equals("gradle.md"), top.toString());
            judge("键盘页排第二（它有「冲突」）", top.size() > 1 && top.get(1).equals("keyboard.md"), top.toString());
            judge("**不相关的咖啡页没进前三**", !top.contains("coffee.md"), top.toString());
        }

        {
            // 精确一点的查询
            String q = "强制指定版本";
            System.out.println("   查询：「" + q + "」→ " + sourcesOf(q, corpus, 2));
            judge("按内容找到「强制版本」那一节", "gradle.md".equals(topSource(q, corpus)), "");
        }

        {
            // 完全无关的查询必须返回空 —— 不能「矮子里拔将军」硬凑几条
            String q = "量子隧穿效应";
            List<Hit> hits = KnowledgeBase.INSTANCE.rank(q, corpus, 5);
            System.out.println("   查询：「" + q + "」→ 命中 " + hits.size() + " 条");
            judge("无关查询返回空（不硬凑）", hits.isEmpty(), "返回了 " + hits.size() + " 条");
        }

        // ---------- 标题加权 ----------
        System.out.println();
        System.out.println("--- 标题命中要加权 ---");
        {
            List<Chunk> c2 = new ArrayList<>();
            // A：标题就写着答案，正文简单
            c2.addAll(chunksOf("title-hit.md", "# 依赖冲突的解决办法\n\n见下。\n"));
            // B：正文啰嗦提了很多次，但标题无关
            StringBuilder many = new StringBuilder("# 随便的标题\n\n");
            for (int i = 0; i < 12; i++) many.append("这里又提到了依赖冲突，依赖冲突，还是依赖冲突。\n");
            c2.addAll(chunksOf("body-only.md", many.toString()));

            String q = "依赖冲突";
            List<String> top = sourcesOf(q, c2, 2);
            System.out.println("   标题命中 vs 正文复读 12 次 → " + top);
            judge("标题命中排在前面（复读机不该赢）", top.get(0).equals("title-hit.md"), top.toString());
        }

        // ---------- 长度归一 ----------
        System.out.println();
        System.out.println("--- 长段落不该天然占优 ---");
        {
            List<Chunk> c3 = new ArrayList<>();
            c3.addAll(chunksOf("short.md", "# 冲突\n\n冲突。\n"));
            StringBuilder longBody = new StringBuilder("# 长文\n\n");
            for (int i = 0; i < 40; i++) longBody.append("这是一段和主题无关的填充内容，用来把段落撑得很长很长。\n");
            longBody.append("冲突。\n");
            c3.addAll(chunksOf("long.md", longBody.toString()));

            String q = "冲突";
            List<String> top = sourcesOf(q, c3, 2);
            System.out.println("   短而准 vs 长而散 → " + top);
            judge("短而准的排在前面", top.get(0).equals("short.md"), top.toString());
        }

        // ---------- 稳定性 ----------
        System.out.println();
        System.out.println("--- 同一查询结果必须可复现 ---");
        {
            List<Hit> a = KnowledgeBase.INSTANCE.rank("依赖 版本 冲突", corpus, 5);
            List<Hit> b = KnowledgeBase.INSTANCE.rank("依赖 版本 冲突", corpus, 5);
            List<String> sa = new ArrayList<>(), sb = new ArrayList<>();
            for (Hit h : a) sa.add(h.getChunk().getSource() + "/" + h.getChunk().getHeading());
            for (Hit h : b) sb.add(h.getChunk().getSource() + "/" + h.getChunk().getHeading());
            judge("两次结果完全一致", sa.equals(sb), "");
        }

        // ---------- 边界 ----------
        System.out.println();
        System.out.println("--- 边界 ---");
        {
            judge("空查询返回空", KnowledgeBase.INSTANCE.rank("", corpus, 5).isEmpty(), "");
            judge("空语料返回空", KnowledgeBase.INSTANCE.rank("依赖", new ArrayList<>(), 5).isEmpty(), "");
            judge("标点查询返回空",
                    KnowledgeBase.INSTANCE.rank("，。！", corpus, 5).isEmpty(), "");
            // 单字查询：bigram 匹配不到，靠单字兜住
            List<Hit> one = KnowledgeBase.INSTANCE.rank("锁", corpus, 5);
            judge("单字查询不崩", one != null, "");
        }

        // ---------- 文件名 ----------
        System.out.println();
        System.out.println("--- 文件名生成 ---");
        {
            judge("中文标题保留", KnowledgeBase.INSTANCE.slugify("Gradle 依赖冲突")
                    .contains("依赖冲突"), KnowledgeBase.INSTANCE.slugify("Gradle 依赖冲突"));
            String s = KnowledgeBase.INSTANCE.slugify("a/b:c*d?e\"f<g>h|i");
            judge("路径与非法字符被替换", !s.contains("/") && !s.contains(":") && !s.contains("*")
                    && !s.contains("?") && !s.contains("\""), s);
            judge("过长标题被截断", KnowledgeBase.INSTANCE.slugify("甲".repeat(200)).length() <= 60, "");
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
