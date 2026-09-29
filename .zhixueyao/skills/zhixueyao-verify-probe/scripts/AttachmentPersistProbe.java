import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.chat.SessionStore;
import com.zhixueyao.chat.SessionStore.Session;
import com.zhixueyao.llm.ChatMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件路径要能存下来、读回来。
 *
 * ## 为什么
 *
 * 用户反馈「怎么图片不见了」——查下来是**附件从来没被存过**：
 * `addAttachment` 只把预览卡加进内存里的气泡，会话存档里一个字段都没有。
 * 于是重开会话，图片全没了，只剩正文那句「已生成一张图片」。
 *
 * ## 这个探针验三件事
 *
 * 1. **往返**：存了带附件的会话 → 读回来，路径还在
 * 2. **向后兼容**：**老存档没有 `attachments` 字段** → 读回来是空列表，不报错
 * 3. **空值不产生垃圾**：没附件的消息不该写出一个空数组占地方
 *
 * 第 2 条特别重要：用户机器上**已经有一堆没有这个字段的老会话**。
 * 如果读取端假设字段一定存在，升级之后那些会话会全部打不开 ——
 * 那比「看不到图片」严重得多。
 */
public class AttachmentPersistProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /**
     * 构造一条带附件的助手消息。
     *
     * **attachments 在最后一个参数位** —— 这是刻意的，见 ChatMessage 里那段注释：
     * 新字段加在中间会让所有位置参数的调用方一起错位
     * （代价是 4 个探针变红）。连这个探针自己也得遵守。
     */
    static ChatMessage asst(String text, List<String> attachments) {
        return new ChatMessage(
                ChatMessage.Role.ASSISTANT, text,
                java.util.Collections.emptyList(),   // toolCalls
                "",                                  // toolCallId
                "",                                  // toolName
                java.util.Collections.emptyList(),   // images
                "",                                  // reasoning
                java.util.Collections.emptyList(),   // variants
                0,                                   // activeVariant
                0,                                   // promptTokens
                0,                                   // completionTokens
                attachments);                        // attachments（最后）
    }

    static Session session(String id, List<ChatMessage> msgs) {
        long now = System.currentTimeMillis();
        return new Session(id, "附件探针", now, now, msgs);
    }

    public static void main(String[] args) {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);

        SessionStore store = SessionStore.INSTANCE;
        String id = "attach-probe-" + System.currentTimeMillis();

        System.out.println("=== 附件路径的持久化 ===");

        List<String> paths = List.of(
                "C:/Users/x/.zhixueyao/Conversation/Product/2026-09-29-03.47/random-night-city.svg",
                "C:/Users/x/.zhixueyao/Conversation/Product/2026-09-29-03.47/second.png"
        );

        // ---------- ① 往返 ----------
        System.out.println();
        System.out.println("--- ① 存了能读回来 ---");
        {
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(ChatMessage.Companion.user("生成一张图", java.util.Collections.emptyList()));
            msgs.add(asst("已生成：月夜城市", paths));

            boolean ok = store.save(session(id, msgs));
            judge("save 返回 true", ok, "");

            Session back = store.load(id);
            judge("load 读得回来", back != null, "");
            if (back != null) {
                ChatMessage a = back.getMessages().get(1);
                System.out.println("   读回的附件数：" + a.getAttachments().size());
                for (String p : a.getAttachments()) System.out.println("      " + p);
                judge("**附件路径条数一致**", a.getAttachments().size() == paths.size(),
                        "期望 " + paths.size() + "，实际 " + a.getAttachments().size());
                judge("路径内容逐条一致", a.getAttachments().equals(paths), a.getAttachments().toString());
                judge("正文也还在", a.getContent().contains("月夜城市"), a.getContent());
            } else {
                judge("附件路径条数一致", false, "load 返回 null");
                judge("路径内容逐条一致", false, "");
                judge("正文也还在", false, "");
            }
        }

        // ---------- ② 没有附件的消息不该被影响 ----------
        System.out.println();
        System.out.println("--- ② 没有附件的消息读回来是空列表 ---");
        {
            Session back = store.load(id);
            if (back != null) {
                ChatMessage u = back.getMessages().get(0);
                judge("用户消息附件为空", u.getAttachments().isEmpty(), u.getAttachments().toString());
            } else {
                judge("用户消息附件为空", false, "load 返回 null");
            }
        }

        // ---------- ③ 向后兼容：老存档没有这个字段 ----------
        System.out.println();
        System.out.println("--- ③ 老存档（没有 attachments 字段）必须读得开 ---");
        {
            String oldId = id + "-old";
            File f = new File(new File(com.intellij.openapi.application.PathManager.getConfigPath(),
                    "zhixueyao/sessions"), oldId + ".json");
            f.getParentFile().mkdirs();
            // 手写一份「升级前」格式的存档：完全没有 attachments 字段
            String legacy = "{\n"
                    + "  \"id\": \"" + oldId + "\",\n"
                    + "  \"title\": \"老会话\",\n"
                    + "  \"createdAt\": 1,\n"
                    + "  \"updatedAt\": 1,\n"
                    + "  \"messages\": [\n"
                    + "    {\"role\":\"USER\",\"content\":\"老问题\",\"toolCallId\":\"\",\"toolName\":\"\",\n"
                    + "     \"reasoning\":\"\",\"toolCalls\":[],\"images\":[],\"variants\":[],\n"
                    + "     \"activeVariant\":-1,\"promptTokens\":0,\"completionTokens\":0},\n"
                    + "    {\"role\":\"ASSISTANT\",\"content\":\"老回答\",\"toolCallId\":\"\",\"toolName\":\"\",\n"
                    + "     \"reasoning\":\"\",\"toolCalls\":[],\"images\":[],\"variants\":[],\n"
                    + "     \"activeVariant\":-1,\"promptTokens\":0,\"completionTokens\":0}\n"
                    + "  ]\n"
                    + "}";
            try {
                java.nio.file.Files.write(f.toPath(), legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                judge("写老存档", false, e.toString());
            }

            Session back = store.load(oldId);
            System.out.println("   读老存档 → " + (back == null ? "null（**升级后打不开了！**）" : "成功"));
            judge("**老存档仍然读得开**", back != null, back == null ? "这会让用户升级后丢会话" : "");
            if (back != null) {
                judge("老存档正文完好", back.getMessages().get(1).getContent().equals("老回答"),
                        back.getMessages().get(1).getContent());
                judge("老存档附件为空的列表（不是 null）",
                        back.getMessages().get(1).getAttachments() != null
                                && back.getMessages().get(1).getAttachments().isEmpty(), "");
            } else {
                judge("老存档正文完好", false, "");
                judge("老存档附件为空的列表", false, "");
            }
            store.delete(oldId);
        }

        // ---------- ④ 序列化里确实写了那个字段 ----------
        System.out.println();
        System.out.println("--- ④ 存档文件里真的写了 attachments ---");
        {
            File f = new File(new File(com.intellij.openapi.application.PathManager.getConfigPath(),
                    "zhixueyao/sessions"), id + ".json");
            String text = "";
            try {
                text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            judge("文件里有 attachments 字段", text.contains("\"attachments\""), "");
            judge("文件里有那条路径", text.contains("random-night-city.svg"), "");
            judge("不是把 base64 塞进去了（只存路径）",
                    text.length() < 20_000, "存档 " + text.length() + " 字符");
        }

        store.delete(id);

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
