import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.chat.SessionStore;
import com.zhixueyao.llm.ChatMessage;
import com.zhixueyao.settings.ZhixueyaoSettings;
import com.zhixueyao.ui.MessageBubble;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 验证「回答多版本」：
 *  ① 存档往返（重新生成出来的版本不能一关 IDE 就没了）
 *  ② 老存档（没有 variants 字段）照样读得出来
 *  ③ 气泡上出现 ‹ 2/3 › 切换器，点击能回调
 */
public class VariantProbe {

    static class P extends JPanel {
        P() { super(); }
        void vt() { synchronized (getTreeLock()) { validateTree(); } }
    }

    static String findText(Component c, int depth, String needle) {
        if (depth > 5 || c == null) return null;
        if (c instanceof JLabel) {
            String t = ((JLabel) c).getText();
            if (t != null && t.contains(needle)) return t;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                String r = findText(k, depth + 1, needle);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        System.out.println("=== ① 存档往返 ===");
        ChatMessage withVariants = new ChatMessage(
                ChatMessage.Role.ASSISTANT, "第二版回答",
                java.util.Collections.emptyList(), "", "",
                java.util.Collections.emptyList(), "",
                // **注意：ChatMessage 的构造参数还在变长，每次加字段这里都要补。**
                //
                // 已经发生过两次：
                //   1. 加 promptTokens / completionTokens（从 9 个变 11 个）
                //   2. 加 attachments（从 11 个变 12 个）—— 那次四个探针一起编译不过
                //
                // 根因：**Java 不支持 Kotlin 的默认参数**，所以构造器的参数个数
                // 必须完全对得上，一个都不能省。
                //
                // 下次给 ChatMessage 加字段时，先跑：
                //   grep -rn "new ChatMessage(" .zhixueyao/skills/zhixueyao-verify-probe/scripts/
                // 把每一处都补上（位置参数的写法在 Java 里没有别的办法）。
                java.util.Arrays.asList("第一版回答", "第二版回答"), 1, 0, 0,
                java.util.Collections.emptyList());   // attachments
        ChatMessage plain = ChatMessage.Companion.assistant("只有一版", java.util.Collections.emptyList());
        SessionStore.Session s = new SessionStore.Session(
                "probe-session", "探针会话", 1L, 2L, java.util.Arrays.asList(plain, withVariants));
        boolean saved = SessionStore.INSTANCE.save(s);
        SessionStore.Session back = SessionStore.INSTANCE.load("probe-session");
        System.out.println("  保存: " + (saved ? "OK" : "失败"));
        System.out.println("  读回条数: " + (back != null ? back.getMessages().size() : -1));
        ChatMessage v = back != null ? back.getMessages().get(1) : null;
        System.out.println("  版本列表: " + (v != null ? v.getVariants() : null));
        System.out.println("  当前版本号: " + (v != null ? v.getActiveVariant() : -1));
        System.out.println("  正文 == 第 2 版: " + (v != null && v.getContent().equals("第二版回答") ? "OK" : "失败"));
        ChatMessage p = back != null ? back.getMessages().get(0) : null;
        System.out.println("  没版本的普通消息: variants=" + (p != null ? p.getVariants() : null)
                + " active=" + (p != null ? p.getActiveVariant() : -1) + "（应为 []/0）");

        System.out.println();
        System.out.println("=== ② 老存档兼容（手工写一个没有 variants 的 JSON）===");
        java.io.File dir = new java.io.File(com.intellij.openapi.application.PathManager.getConfigPath(), "zhixueyao/sessions");
        dir.mkdirs();
        java.io.File oldFile = new java.io.File(dir, "legacy-session.json");
        java.nio.file.Files.writeString(oldFile.toPath(),
            "{\"id\":\"legacy-session\",\"title\":\"老会话\",\"createdAt\":1,\"updatedAt\":2,\"messageCount\":2,"
            + "\"messages\":[{\"role\":\"USER\",\"content\":\"老问题\"},{\"role\":\"ASSISTANT\",\"content\":\"老回答\"}]}");
        SessionStore.Session legacy = SessionStore.INSTANCE.load("legacy-session");
        System.out.println("  读得出来: " + (legacy != null ? "OK" : "失败"));
        if (legacy != null) {
            ChatMessage lm = legacy.getMessages().get(1);
            System.out.println("  正文: " + lm.getContent());
            System.out.println("  variants 为空 / active 为 0: "
                    + (lm.getVariants().isEmpty() && lm.getActiveVariant() == 0 ? "OK" : "失败"));
        }
        oldFile.delete();
        new java.io.File(dir, "probe-session.json").delete();

        System.out.println();
        System.out.println("=== ③ 气泡上的切换器 ===");
        final String[] out = new String[2];
        final int[] clicked = { -1 };
        SwingUtilities.invokeAndWait(() -> {
            MessageBubble b = new MessageBubble(MessageBubble.Kind.ASSISTANT, null);
            b.appendText("第二版回答");
            b.finalize("第二版回答", (Function0<Unit>) null, (Function0<Unit>) null, false, false);
            b.setVariants(java.util.Arrays.asList("第一版回答", "第二版回答", "第三版回答"), 1,
                    (Function1<Integer, Unit>) i -> { clicked[0] = i; return Unit.INSTANCE; });
            P root2 = new P();
            root2.setLayout(new BoxLayout(root2, BoxLayout.Y_AXIS));
            root2.add(b);
            root2.setSize(900, 600);
            for (int i = 0; i < 2; i++) { root2.invalidate(); root2.vt(); }
            out[0] = findText(b, 0, "/");
            // 点最右边 → 应回调下一版
            JLabel lbl = findLabel(b, 0);
            if (lbl != null) {
                for (java.awt.event.MouseListener ml : lbl.getMouseListeners()) {
                    ml.mouseClicked(new java.awt.event.MouseEvent(lbl, java.awt.event.MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0, lbl.getWidth() - 2, 5, 1, false));
                }
            }
        });
        System.out.println("  切换器文案: " + out[0] + "（应为 ‹ 2/3 ›）");
        System.out.println("  点右侧回调到: " + (clicked[0] == 2 ? "第 3 版 OK" : clicked[0] + "（应为 2）"));
    }

    static JLabel findLabel(Component c, int depth) {
        if (depth > 5 || c == null) return null;
        if (c instanceof JLabel && ((JLabel) c).getText() != null && ((JLabel) c).getText().contains("›")) return (JLabel) c;
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                JLabel r = findLabel(k, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}
