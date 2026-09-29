import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.agent.ContextCompactor;
import com.zhixueyao.llm.ChatMessage;
import com.zhixueyao.settings.ZhixueyaoSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证长会话压缩真的能跑通（以前 ContextCompactor 零调用点 = 死代码）。
 *
 * 场景：模拟一段很长的对话（含大块工具结果），压缩后应当
 *   ① 落进预算内  ② 最近 N 轮原封不动  ③ 系统提示仍在最前
 *   ④ 第二次压缩**只把新增部分**送给摘要器（增量，不重摘全文）
 */
public class CompactProbe {

    static String big() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("这是一段工具返回的长内容，用来把上下文撑大。第 ").append(i).append(" 行。\n");
        return sb.toString();
    }

    static ChatMessage user(String t) { return ChatMessage.Companion.user(t, java.util.Collections.emptyList()); }
    static ChatMessage asst(String t) { return ChatMessage.Companion.assistant(t, java.util.Collections.emptyList()); }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        ContextCompactor c = ContextCompactor.INSTANCE;
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.Companion.system("你是 Android 开发助手。"));
        for (int t = 0; t < 20; t++) {
            history.add(user("第 " + t + " 个问题：帮我看看这块代码"));
            history.add(asst("我先读一下文件。"));
            history.add(ChatMessage.Companion.tool("c" + t, "read_file", big()));
            history.add(asst("这个文件有 " + t + " 处问题，建议这样改。"));
        }
        int budget = 8000;
        System.out.println("构造历史 " + history.size() + " 条，估算占用 " + c.sizeOf(history, null) + " tokens，预算 " + budget);
        System.out.println();

        // ---- 第一次压缩 ----
        List<List<ChatMessage>> captured = new ArrayList<>();
        ContextCompactor.Summarizer summarizer = msgs -> {
            captured.add(new ArrayList<>(msgs));
            return "【摘要】用户在做 Android 项目，已排查到第 12 个问题。";
        };

        ContextCompactor.Result r1 = c.compact(history, budget, 4, summarizer);
        System.out.println("=== 第一次压缩 ===");
        System.out.println("  压缩前 " + r1.getReport().getBeforeTokens() + " → 压缩后 " + r1.getReport().getAfterTokens()
                + " tokens（省 " + r1.getReport().getSavedTokens() + "）");
        System.out.println("  摘要了 " + r1.getReport().getSummarizedTurns() + " 轮，瘦身工具结果 "
                + r1.getReport().getSlimmedToolResults() + " 条，整轮丢弃 " + r1.getReport().getDroppedTurns());
        System.out.println("  用了摘要: " + r1.getReport().getUsedSummary());
        System.out.println("  送给摘要器的消息数: " + captured.get(0).size());
        System.out.println("  结果条数 " + history.size() + " → " + r1.getMessages().size());
        System.out.println("  结果首条是系统提示: " + (r1.getMessages().get(0).getRole() == ChatMessage.Role.SYSTEM));
        System.out.println("  落进预算: " + (r1.getReport().getAfterTokens() <= budget
                ? "OK " + r1.getReport().getAfterTokens() : "没落进 " + r1.getReport().getAfterTokens()));

        // 最近 4 轮是否原封不动（按对象身份）
        List<ChatMessage> lastTurn = new ArrayList<>();
        int n = history.size();
        for (int i = n - 4; i < n; i++) lastTurn.add(history.get(i));
        boolean kept = r1.getMessages().containsAll(lastTurn);
        System.out.println("  最近 4 条原封不动: " + (kept ? "OK" : "被动了"));

        // ---- 第二次压缩：追加更多内容，检查增量 ----
        System.out.println();
        System.out.println("=== 第二次压缩（追加 8 轮后）===");
        int prevCovered = captured.get(0).size();
        for (int t = 20; t < 28; t++) {
            history.add(user("第 " + t + " 个问题：继续"));
            history.add(asst("好的。"));
            history.add(ChatMessage.Companion.tool("c" + t, "read_file", big()));
            history.add(asst("第 " + t + " 个问题也改好了。"));
        }
        // 模拟 AgentRunner 的增量状态
        int covered = 0;
        // 上一次压缩覆盖到哪：用第一次摘要器收到的最后一条在 history 里的位置
        ChatMessage firstLast = captured.get(0).get(captured.get(0).size() - 1);
        for (int i = 0; i < history.size(); i++) if (history.get(i) == firstLast) covered = i + 1;

        final int cov = covered;
        ContextCompactor.Summarizer summarizer2 = msgs -> {
            captured.add(new ArrayList<>(msgs));
            return "【摘要】用户在做 Android 项目，已排查到第 20 个问题。";
        };
        ContextCompactor.Result r2 = c.compact(history, budget, 4, summarizer2);
        System.out.println("  上次摘要覆盖到第 " + cov + " 条");
        System.out.println("  这次送给摘要器的消息数: " + captured.get(1).size()
                + "（整段旧历史约 " + (history.size() - 4) + " 条）");
        System.out.println("  第二次压缩后 " + r2.getReport().getAfterTokens() + " tokens，用摘要: " + r2.getReport().getUsedSummary());

        // 增量区间纯函数
        System.out.println();
        System.out.println("=== 增量区间计算（纯函数，AgentRunner 用它）===");
        List<ChatMessage> fakeFull = new ArrayList<>(history);
        List<ChatMessage> fakeOld = new ArrayList<>(history.subList(0, cov));
        kotlin.ranges.IntRange rg = c.incrementalRange(fakeFull, fakeOld, cov);
        System.out.println("  covered=" + cov + " → 区间 " + (rg == null ? "null" : rg.getFirst() + ".." + rg.getLast())
                + "（应为 " + cov + ".." + (cov - 1) + " 之类：即「没有新增」或很少）");
        kotlin.ranges.IntRange rg2 = c.incrementalRange(fakeFull, new ArrayList<>(history.subList(0, history.size() - 4)), cov);
        System.out.println("  追加后再算 → 区间 " + (rg2 == null ? "null" : rg2.getFirst() + ".." + rg2.getLast())
                + "（first 应 == " + cov + "，即只送新增的那段）");
        System.out.println();
        boolean incOk = rg2 != null && rg2.getFirst() == cov;
        System.out.println("判定：第二次只送新增部分（增量）-> " + (incOk ? "OK" : "失败"));
        if (incOk) pass++; else fail++;

        summaryMustSurvive();
        boundaries();

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static final String HEADER = "【历史对话摘要】";

    static boolean hasSummary(List<ChatMessage> msgs) {
        for (ChatMessage m : msgs) {
            if (ContextCompactor.INSTANCE.isSummaryMessage(m)) return true;
        }
        return false;
    }

    /**
     * 摘要不能被当成「第 1 轮」丢掉。
     *
     * 摘要是一条 role=USER 的消息（正文以 HEADER 开头），而
     * `turnStartIndices` 原来的判据是「USER 就是一轮的开始」——
     * 于是摘要被算成第 1 轮，第 3 级「整轮丢弃」**从它开始丢**。
     *
     * 这是最不该丢的一条：它是整段历史的唯一记录、体积最小、信息密度最高。
     * 丢它 = 一句话清空全部历史，却只省几百 token。
     */
    static void summaryMustSurvive() {
        System.out.println();
        System.out.println("=== 摘要必须活下来（第 3 级整轮丢弃）===");

        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.Companion.system("你是助手。"));
        h.add(user(HEADER + "早前对话摘要：用户在做 Android 插件，"
                + "已确定用 BM25 而不是向量检索，因为要能离线验证。"));
        for (int i = 1; i <= 6; i++) {
            h.add(user("第 " + i + " 个问题。" + big()));
            h.add(asst("第 " + i + " 个回答。" + big()));
        }

        int before = ContextCompactor.INSTANCE.sizeOf(h, null);
        int budget = before / 6;
        System.out.println("   压缩前 " + before + " token，预算 " + budget);

        ContextCompactor.Result r = ContextCompactor.INSTANCE.compact(h, budget, 2, null);
        System.out.println("   压缩后 " + r.getMessages().size() + " 条，"
                + r.getReport().getAfterTokens() + " token，"
                + "丢弃 " + r.getReport().getDroppedTurns() + " 轮");

        judge("摘要仍在", hasSummary(r.getMessages()),
                hasSummary(r.getMessages()) ? "" : "摘要被当成第 1 轮丢掉了");
        judge("确实压了（对照：不是没压）", r.getMessages().size() < h.size(),
                h.size() + " → " + r.getMessages().size());
        judge("确实走到了第 3 级（丢弃轮次 > 0）",
                r.getReport().getDroppedTurns() > 0, "丢了 " + r.getReport().getDroppedTurns());
        judge("系统提示仍在最前",
                r.getMessages().get(0).getRole() == ChatMessage.Role.SYSTEM, "");
        judge("摘要仍在正文之前（顺序没乱）",
                indexOfSummary(r.getMessages()) < r.getMessages().size() - 1,
                "摘要在第 " + indexOfSummary(r.getMessages()) + " 条");
    }

    static int indexOfSummary(List<ChatMessage> msgs) {
        for (int i = 0; i < msgs.size(); i++) {
            if (ContextCompactor.INSTANCE.isSummaryMessage(msgs.get(i))) return i;
        }
        return -1;
    }

    /** 边界：没超预算不动、极端窄预算不卡死、摘要识别不误判 */
    static void boundaries() {
        System.out.println();
        System.out.println("=== 边界 ===");

        // 没超预算 → 原样返回
        {
            List<ChatMessage> h = new ArrayList<>();
            h.add(ChatMessage.Companion.system("系统"));
            h.add(user("你好"));
            h.add(asst("你好。"));
            ContextCompactor.Result r = ContextCompactor.INSTANCE.compact(h, 100_000, 3, null);
            judge("没超预算时条数不变", r.getMessages().size() == h.size(),
                    h.size() + " → " + r.getMessages().size());
            judge("没超预算时不做任何动作",
                    r.getReport().getDroppedTurns() == 0 && !r.getReport().getUsedSummary(), "");
        }

        // 极端窄预算：不许卡死、不许把最后一条也丢了
        {
            List<ChatMessage> h = new ArrayList<>();
            h.add(ChatMessage.Companion.system("系统"));
            h.add(user("唯一一轮。" + big()));
            h.add(asst("回答。" + big()));
            long t0 = System.currentTimeMillis();
            ContextCompactor.Result r = ContextCompactor.INSTANCE.compact(h, 10, 1, null);
            long ms = System.currentTimeMillis() - t0;
            judge("极端预算下不卡死", ms < 1000, ms + "ms");
            judge("至少留了两条（系统 + 用户）", r.getMessages().size() >= 2,
                    "剩 " + r.getMessages().size() + " 条");
            judge("开头仍是系统消息",
                    r.getMessages().get(0).getRole() == ChatMessage.Role.SYSTEM, "");
        }

        // 摘要识别本身：不能误判
        {
            judge("认得出摘要",
                    ContextCompactor.INSTANCE.isSummaryMessage(user(HEADER + "内容")), "");
            judge("普通消息不误判",
                    !ContextCompactor.INSTANCE.isSummaryMessage(user("普通消息")), "");
            judge("助手消息不误判（只有 USER 才算）",
                    !ContextCompactor.INSTANCE.isSummaryMessage(asst(HEADER + "x")), "");
            judge("正文中间出现这个字样的不算",
                    !ContextCompactor.INSTANCE.isSummaryMessage(
                            user("你刚才说的" + HEADER + "是什么意思")), "");
        }
    }
}
