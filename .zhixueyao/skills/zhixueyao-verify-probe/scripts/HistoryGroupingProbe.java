import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.llm.ChatMessage;
import com.zhixueyao.settings.ZhixueyaoSettings;
import com.zhixueyao.ui.HistoryGrouping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 验证「一句话发出去，重载后变成好几个回答框」被修好了。
 *
 * 机理：历史里一次带工具调用的回答有**两条** assistant（协议要求），
 * 而旧的 renderHistory 按条数渲染 → 两个气泡，且各只有半截内容。
 */
public class HistoryGroupingProbe {

    static ChatMessage sys(String t) {
        return ChatMessage.Companion.system(t);
    }

    static ChatMessage user(String t) {
        return ChatMessage.Companion.user(t, java.util.Collections.emptyList());
    }

    /** 带工具调用的中间轮：有文本 + 有 toolCalls */
    static ChatMessage midTurn(String text) {
        com.zhixueyao.llm.ToolCall call = new com.zhixueyao.llm.ToolCall("c1", "read_file", "{}");
        return ChatMessage.Companion.assistant(text, Arrays.asList(call));
    }

    /** 收尾轮：有文本、无工具调用 */
    static ChatMessage finalTurn(String text) {
        return ChatMessage.Companion.assistant(text, java.util.Collections.emptyList());
    }

    static ChatMessage tool(String name, String content) {
        return new ChatMessage(ChatMessage.Role.TOOL, content,
                java.util.Collections.emptyList(), "c1", name,
                java.util.Collections.emptyList(), "", java.util.Collections.emptyList(), 0, 0, 0,
                java.util.Collections.emptyList());   // attachments（见 ChatMessage 的注释）
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        System.out.println("=== 这个 case 就是用户截图里的那轮 ===");
        List<ChatMessage> h = new ArrayList<>();
        h.add(sys("你是止血药…"));
        h.add(user("看看我们的项目怎么样，仅观察不修改"));
        h.add(midTurn("我会只读检查项目结构、构建配置、核心源码与当前诊断信息，不修改任何文件。"));
        h.add(tool("project_structure", "app/\n  build.gradle"));
        h.add(tool("read_file", "…"));
        h.add(finalTurn("项目是一个根目录单模块 Android 应用，当前使用 Java 17、compileSdk/targetSdk 36。接下来我会查看 src/main…"));
        h.add(tool("read_file", "…"));
        h.add(finalTurn("整体结构清晰，但有几点风险。"));
        h.add(user("那先改第一条"));
        h.add(midTurn("好的，我先看看那个文件。"));
        h.add(tool("read_file", "…"));
        h.add(finalTurn("已经改好了。"));

        List<HistoryGrouping.Turn> turns = HistoryGrouping.INSTANCE.group(h);
        System.out.println("  历史条数: " + h.size());
        System.out.println("  分组后轮数: " + turns.size() + "（应为 2 轮：两个用户提问）");
        System.out.println();

        for (int i = 0; i < turns.size(); i++) {
            HistoryGrouping.Turn t = turns.get(i);
            System.out.println("  ── 第 " + (i + 1) + " 轮 ──");
            System.out.println("    用户: " + (t.getUserMessage() != null ? t.getUserMessage().getContent() : "（无）"));
            String a = t.getAssistantText();
            System.out.println("    助手文本长度: " + a.length());
            System.out.println("    助手文本: " + (a.length() > 80 ? a.substring(0, 80) + "…" : a));
            System.out.println("    有内容: " + t.getHasAssistantText());
            System.out.println("    linkedMessage 是最后一条 assistant: "
                    + (t.getLastAssistant() != null && a.endsWith(t.getLastAssistant().getContent()) ? "OK" : "失败"));
        }

        System.out.println();
        System.out.println("=== 判定 ===");
        boolean ok1 = turns.size() == 2;
        HistoryGrouping.Turn t0 = turns.get(0);
        HistoryGrouping.Turn t1 = turns.get(1);
        // 第一轮：两条 assistant 都要在里面（中间那条计划 + 最后结论）
        boolean ok2 = t0.getAssistantText().contains("我会只读检查")
                && t0.getAssistantText().contains("根目录单模块")
                && t0.getAssistantText().contains("整体结构清晰");
        // 第二轮只含第二轮的内容，不能串到上一轮
        boolean ok3 = t1.getAssistantText().contains("已经改好了")
                && !t1.getAssistantText().contains("根目录单模块");
        // 与流式时同口径：直接拼接（无分隔符）
        String expected = "我会只读检查项目结构、构建配置、核心源码与当前诊断信息，不修改任何文件。"
                + "项目是一个根目录单模块 Android 应用，当前使用 Java 17、compileSdk/targetSdk 36。接下来我会查看 src/main…"
                + "整体结构清晰，但有几点风险。";
        boolean ok4 = expected.equals(t0.getAssistantText());

        System.out.println("  2 个用户提问 → 2 轮（不再是 4 个回答框）: " + (ok1 ? "OK" : "失败 " + turns.size()));
        System.out.println("  同一轮的多条 assistant 合并进 1 轮  : " + (ok2 ? "OK" : "失败"));
        System.out.println("  轮与轮之间不串内容                  : " + (ok3 ? "OK" : "失败"));
        System.out.println("  拼接口径与流式一致（无分隔符）      : " + (ok4 ? "OK" : "失败"));

        System.out.println();
        System.out.println("=== 边界情况 ===");
        // ① 用户发了但还没答（被中断）→ 用户气泡仍要在
        List<ChatMessage> h2 = Arrays.asList(sys("s"), user("我问了一句"));
        List<HistoryGrouping.Turn> t2 = HistoryGrouping.INSTANCE.group(h2);
        System.out.println("  只有用户消息（被中断）: 轮数 " + t2.size()
                + "，有用户气泡 " + (t2.get(0).getUserMessage() != null)
                + "，无助手内容 " + (!t2.get(0).getHasAssistantText()) + " -> "
                + (t2.size() == 1 && !t2.get(0).getHasAssistantText() ? "OK" : "失败"));

        // ② 开场白（还没有用户消息）
        List<HistoryGrouping.Turn> t3 = HistoryGrouping.INSTANCE.group(Arrays.asList(sys("s"), finalTurn("欢迎使用")));
        System.out.println("  开场白: 轮数 " + t3.size()
                + "，用户为 null " + (t3.get(0).getUserMessage() == null)
                + " -> " + (t3.size() == 1 && t3.get(0).getUserMessage() == null ? "OK" : "失败"));

        // ③ 纯工具调用轮（assistant 无正文）不该产生空气泡
        List<HistoryGrouping.Turn> t4 = HistoryGrouping.INSTANCE.group(
                Arrays.asList(sys("s"), user("u"), midTurn(""), tool("read_file", "x"), finalTurn("答完了")));
        System.out.println("  中间轮无正文: 轮数 " + t4.size()
                + "，助手文本 = " + t4.get(0).getAssistantText()
                + " -> " + (t4.get(0).getAssistantText().equals("答完了") ? "OK（没多出空框）" : "失败"));

        System.out.println();
        boolean all = ok1 && ok2 && ok3 && ok4;
        System.out.println("结论：" + (all ? "全部通过" : "有不通过项"));
        System.exit(0);
    }
}