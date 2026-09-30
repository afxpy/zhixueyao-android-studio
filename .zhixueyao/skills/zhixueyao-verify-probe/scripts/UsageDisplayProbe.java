import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.chat.SessionStore;
import com.zhixueyao.llm.ChatMessage;
import com.zhixueyao.settings.ZhixueyaoSettings;
import com.zhixueyao.ui.MessageBubble;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * 验证「看得到用了多少 token」：
 *  ① 气泡页脚显示用量（跟消息走，不是一闪而过的状态栏）
 *  ② 服务商没回报时退回估算并标注
 *  ③ 用量存进消息，重载会话后还在
 */
public class UsageDisplayProbe {

    static class P extends JPanel {
        P() { super(); }
        void vt() { synchronized (getTreeLock()) { validateTree(); } }
    }

    static JLabel findMeta(Component c, int depth) {
        if (depth > 6 || c == null) return null;
        if (c instanceof JLabel) {
            String t = ((JLabel) c).getText();
            if (t != null && t.contains("tokens")) return (JLabel) c;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                JLabel r = findMeta(k, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    static MessageBubble build(int prompt, int completion, int estimated) {
        MessageBubble b = new MessageBubble(MessageBubble.Kind.ASSISTANT, null);
        b.appendText("回答正文。");
        b.finalize("回答正文。", (Function0<Unit>) null, (Function0<Unit>) null, false, false);
        b.setUsage(prompt, completion, estimated, 2);
        P root = new P();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.add(b);
        root.setSize(900, 600);
        for (int i = 0; i < 2; i++) { root.invalidate(); root.vt(); }
        return b;
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        final String[] out = new String[3];
        final String[] tips = new String[3];
        SwingUtilities.invokeAndWait(() -> {
            JLabel a = findMeta(build(129861, 1280, 0), 0);
            out[0] = a != null ? a.getText() : "（没找到）";
            tips[0] = a != null ? a.getToolTipText() : null;

            JLabel b = findMeta(build(0, 0, 8600), 0);
            out[1] = b != null ? b.getText() : "（没找到）";
            tips[1] = b != null ? b.getToolTipText() : null;

            JLabel c = findMeta(build(1200, 340, 0), 0);
            out[2] = c != null ? c.getText() : "（没找到）";
            tips[2] = c != null ? c.getToolTipText() : null;
        });

        System.out.println("=== ① 真实用量（13 万）===");
        System.out.println("  页脚: " + out[0]);
        System.out.println("  悬停: " + tips[0]);
        System.out.println("=== ② 服务商没回报 → 估算 ===");
        System.out.println("  页脚: " + out[1]);
        System.out.println("  悬停: " + tips[1]);
        System.out.println("=== ③ 小用量 ===");
        System.out.println("  页脚: " + out[2]);
        System.out.println("  悬停: " + tips[2]);

        System.out.println();
        System.out.println("=== ④ 存进消息 → 重载后还在 ===");
        ChatMessage m = new ChatMessage(ChatMessage.Role.ASSISTANT, "回答", java.util.Collections.emptyList(),
                "", "", java.util.Collections.emptyList(), "", java.util.Collections.emptyList(), 0,
                129861, 1280,
                java.util.Collections.emptyList());   // attachments（见 ChatMessage 的注释）
        SessionStore.Session s = new SessionStore.Session(
                "usage-probe", "用量探针", 1L, 2L, Arrays.asList(m));
        SessionStore.INSTANCE.save(s);
        SessionStore.Session back = SessionStore.INSTANCE.load("usage-probe");
        ChatMessage bm = back != null ? back.getMessages().get(0) : null;
        System.out.println("  读回输入: " + (bm != null ? bm.getPromptTokens() : -1));
        System.out.println("  读回输出: " + (bm != null ? bm.getCompletionTokens() : -1));
        new java.io.File(new java.io.File(com.intellij.openapi.application.PathManager.getConfigPath(),
                "zhixueyao/sessions"), "usage-probe.json").delete();

        System.out.println();
        boolean ok1 = out[0].contains("131k") || out[0].contains("131.1k");
        boolean ok2 = out[1].contains("估算");
        boolean ok3 = bm != null && bm.getPromptTokens() == 129861 && bm.getCompletionTokens() == 1280;
        System.out.println("判定：");
        System.out.println("  页脚显示总量（131k）      : " + (ok1 ? "OK" : "失败 " + out[0]));
        System.out.println("  估算时明确标注「估算」    : " + (ok2 ? "OK" : "失败 " + out[1]));
        System.out.println("  重载后用量还在            : " + (ok3 ? "OK" : "失败"));
    }
}
