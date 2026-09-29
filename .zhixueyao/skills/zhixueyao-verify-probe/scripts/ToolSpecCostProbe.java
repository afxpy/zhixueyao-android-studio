import com.zhixueyao.agent.ToolRegistry;
import com.zhixueyao.tools.AgentTool;
import com.zhixueyao.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 量一下「把全部工具定义发给模型」到底要多少 token。
 *
 * ## 为什么先量再决定做不做 tool_search
 *
 * 参考项目 astravia 有 `tool-search`（只给核心工具，其余按关键词检索取出）。
 * 看起来是个该补的能力 —— 但**「工具多了就该做检索」是个假设，不是结论**。
 * 先量一下：如果全部工具定义只占几千 token，那做检索纯属给自己加复杂度
 * （多一次往返、多一个可能选错的环节），反而更差。
 *
 * 参考项目 insight-agents 的教训正好相反方向：他们做「全量加载、无向量检索」
 * 是**有意**的选择，理由是「检索一旦漏召回，模型就永远看不到那个工具」。
 *
 * 所以这个探针的输出不是「通过/失败」，而是**一组数字**，用来做决定。
 * 但它仍然要进回归 —— 因为**工具数量会一直涨**，涨到某个阈值时结论会翻转，
 * 那时需要一个会响的警报。
 */
public class ToolSpecCostProbe {

    /** 超过这个字符数，就值得考虑 tool_search 了 */
    static final int WARN_CHARS = 60_000;

    public static void main(String[] args) {
        List<AgentTool> all = ToolRegistry.INSTANCE.getAll();

        System.out.println("=== 工具定义的成本 ===");
        System.out.println("工具总数：" + all.size());
        System.out.println();

        List<Object[]> rows = new ArrayList<>();
        int totalName = 0, totalDesc = 0, totalParams = 0;

        for (AgentTool t : all) {
            String desc = t.getDescription() == null ? "" : t.getDescription();
            String params = t.getParameters() == null ? "{}" : t.getParameters().stringify();
            int n = t.getName().length();
            totalName += n;
            totalDesc += desc.length();
            totalParams += params.length();
            rows.add(new Object[]{t.getName(), n, desc.length(), params.length()});
        }

        rows.sort(Comparator.comparingInt((Object[] r) -> -((int) r[1] + (int) r[2] + (int) r[3])));

        System.out.printf("  %-22s %6s %8s %8s%n", "工具", "名字", "描述", "参数");
        for (int i = 0; i < Math.min(12, rows.size()); i++) {
            Object[] r = rows.get(i);
            System.out.printf("  %-22s %6d %8d %8d%n", r[0], r[1], r[2], r[3]);
        }
        if (rows.size() > 12) {
            System.out.println("  ……（其余 " + (rows.size() - 12) + " 个更小）");
        }

        int total = totalName + totalDesc + totalParams;
        System.out.println();
        System.out.println("合计字符：" + total);
        System.out.println("  名字 " + totalName + "（" + pct(totalName, total) + "%）");
        System.out.println("  描述 " + totalDesc + "（" + pct(totalDesc, total) + "%）  ← 大头是这里");
        System.out.println("  参数 " + totalParams + "（" + pct(totalParams, total) + "%）");
        System.out.println();
        // 中英混排按 1 字符 ≈ 0.6 token 粗估（中文约 1 字 1 token，英文约 4 字符 1 token）
        System.out.println("粗估 token：" + (int) (total * 0.6) + "（按 0.6 token/字符，中英混排的经验值）");
        System.out.println();

        int passed = 0, failed = 0;
        if (total < WARN_CHARS) {
            System.out.println("判定：OK　工具定义总成本 " + total + " 字符，低于警戒线 " + WARN_CHARS);
            System.out.println("结论：**现在不需要 tool_search** —— 全量发出去的成本可以接受，");
            System.out.println("      而检索会引入「漏召回 → 模型永远看不到某个工具」的风险，");
            System.out.println("      收益不抵风险。等这个数字越过警戒线再说。");
            passed++;
        } else {
            System.out.println("判定：注意　工具定义已到 " + total + " 字符，越过警戒线 " + WARN_CHARS);
            System.out.println("结论：**该做 tool_search 了** —— 全量发送开始明显吃预算。");
            failed++;
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + passed + " / 需注意 " + failed);
        System.out.println("（这个探针的作用是**在工具数量涨到阈值时报警**，不是判断功能对错）");
        System.exit(0);
    }

    static String pct(int part, int total) {
        return total == 0 ? "0" : String.valueOf(Math.round(part * 100.0 / total));
    }
}
