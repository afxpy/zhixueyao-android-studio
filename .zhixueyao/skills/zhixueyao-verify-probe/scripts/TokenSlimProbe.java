import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.agent.ContextCompactor;
import com.zhixueyao.llm.ChatMessage;
import com.zhixueyao.settings.ZhixueyaoSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证「简单问题 44k 输入」被压下来了。
 *
 * 机理：工具结果会**一直挂在历史里、每轮重发**。
 * 单条上限原来是 30,000 **字符**（中文 ≈ 3 万 token），
 * 而且只有超预算时才压 —— 之前每一轮都在白花那份钱。
 *
 * 修法：
 *  A. 单条工具结果进上下文上限改成 **按 token 估**（6,000）
 *  B. **每轮都做**一遍旧工具结果瘦身（保留最近 4 条 + 最近 1 轮）
 */
public class TokenSlimProbe {

    static ChatMessage tool(String name, String content) {
        return new ChatMessage(ChatMessage.Role.TOOL, content,
                java.util.Collections.emptyList(), "c1", name,
                java.util.Collections.emptyList(), "", java.util.Collections.emptyList(), 0, 0, 0,
                java.util.Collections.emptyList());   // attachments（见 ChatMessage 的注释）
    }

    static ChatMessage user(String t) {
        return ChatMessage.Companion.user(t, java.util.Collections.emptyList());
    }

    static ChatMessage asst(String t) {
        return ChatMessage.Companion.assistant(t, java.util.Collections.emptyList());
    }

    static String big(int chars) {
        return "这是一段工具返回的长内容，模拟 read_file 读回来的文件。".repeat(chars / 24 + 1);
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        ContextCompactor c = ContextCompactor.INSTANCE;

        System.out.println("=== ① 模拟「盘点了 14 个文件」之后的会话 ===");
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.Companion.system("系统提示词"));
        for (int t = 0; t < 14; t++) {
            history.add(user("看看第 " + t + " 个文件"));
            history.add(asst("我读一下。"));
            // 每次读回来 3 万字符（原来 clipResult 的上限就是这个数）
            history.add(tool("read_file", big(30_000)));
        }
        int before = c.sizeOf(history, null);
        System.out.println("  历史占用: " + before + " tokens（" + history.size() + " 条）");

        System.out.println();
        System.out.println("=== ② 每轮瘦身（slimOldToolResults）===");
        List<ChatMessage> after = c.slimOldToolResults(history, 1);
        int afterTokens = c.sizeOf(after, null);
        System.out.println("  瘦身后: " + afterTokens + " tokens");
        System.out.println("  省下: " + (before - afterTokens) + " tokens（"
                + String.format("%.0f%%", (before - afterTokens) * 100.0 / before) + "）");
        System.out.println("  动过的条数: " + (after.size() == history.size() ? "条数不变，内容被省略"
                : "条数变了？" + after.size()));

        // 最近几条必须原样保留（模型还在用）
        int toolCount = 0;
        for (ChatMessage m : history) if (m.getRole() == ChatMessage.Role.TOOL) toolCount++;
        int keptFull = 0;
        for (ChatMessage m : after) {
            if (m.getRole() == ChatMessage.Role.TOOL && m.getContent().length() > 10_000) keptFull++;
        }
        System.out.println("  工具结果共 " + toolCount + " 条，保持原样的 " + keptFull + " 条"
                + "（应为最近 4 条）");
        System.out.println("  最近 4 条原样保留: " + (keptFull == 4 ? "OK" : "数量不对 ✗"));

        System.out.println();
        System.out.println("=== ③ 旧结果仍然「可发现」——不是凭空消失 ===");
        int omitted = 0;
        for (ChatMessage m : after) {
            if (m.getRole() == ChatMessage.Role.TOOL && m.getContent().contains("已省略")) omitted++;
        }
        System.out.println("  写明「已省略」的条数: " + omitted + "（" + (toolCount - keptFull) + " 条被省略）");
        String sample = null;
        for (ChatMessage m : after) {
            if (m.getContent().contains("已省略")) { sample = m.getContent(); break; }
        }
        System.out.println("  省略后的样子: " + sample);
        System.out.println("  提示了「可以重新调用」: "
                + (sample != null && sample.contains("重新调用") ? "OK" : "没提示 ✗"));

        System.out.println();
        System.out.println("=== ④ 新会话第一轮的固定开销（对比）===");
        System.out.println("  系统提示词 + 工具 schema ≈ 6,850 tokens（每轮都要付，省不掉）");
        System.out.println("  → 修好之后，一个简单问题的输入应该回到 1 万左右，而不是 4 万+");

        boolean ok = afterTokens < before * 0.35 && keptFull == 4 && omitted > 0;
        System.out.println();
        System.out.println("结论：" + (ok ? "有效（省下 2/3 以上，且最近结果保留）" : "没达到预期"));
        System.exit(0);
    }
}