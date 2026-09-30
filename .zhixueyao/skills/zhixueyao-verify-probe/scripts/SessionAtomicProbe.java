import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.zhixueyao.chat.SessionStore;
import com.zhixueyao.chat.SessionStore.Session;
import com.zhixueyao.llm.ChatMessage;

import java.io.File;

/**
 * 会话存储：写入必须原子，失败不许毁掉旧数据。
 *
 * ## 为什么这个属性必须验，而且**可以**验
 *
 * `SessionStore.save` 每条消息都跑一次。原来是直接 `target.writeText(...)` ——
 * 写到一半被打断（强退、断电、杀进程）就留下一个截断的 JSON。
 * 而截断的后果特别重：
 *
 *  - `load` 用 runCatching 兜底返回 null → 会话**静默消失**
 *  - `list` 里的 `readMeta` 同样吞异常 → 它**连列表里都不出现**
 *  - 用户看到「会话没了」，却**没有任何错误提示**
 *
 * 「崩溃原子性」通常被认为没法离线测。但**有一条可以测、而且是关键的那条**：
 *
 * > **写入失败时，旧文件必须完好无损。**
 *
 * 办法是**故障注入**：把 `.tmp` 那个路径占成一个**目录** ——
 * 于是 `writeText` 必然抛异常。
 *
 *  - 若是「先写临时文件再改名」：目标文件根本没被碰过 → **旧内容完好**
 *  - 若是「直接写目标文件」：目标已被截断 → **旧内容毁掉**
 *
 * 这一条正好是两种实现的分水岭，所以它能真正区分对错，
 * 而不是「跑一遍没报错就算通过」。
 */
public class SessionAtomicProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static SessionStore store() {
        return SessionStore.INSTANCE;
    }

    static File dir() {
        return new File(PathManager.getConfigPath(), "zhixueyao/sessions");
    }

    static File fileOf(String id) {
        return new File(dir(), id + ".json");
    }

    public static void main(String[] args) {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);

        System.out.println("=== 会话存储：原子写入 ===");
        System.out.println("目录：" + dir().getAbsolutePath());
        System.out.println();

        String id = "probe-" + System.currentTimeMillis();

        // ---------- ① 基本往返 ----------
        System.out.println("--- ① 存了能读回来 ---");
        {
            var s = new Session(id, "探针会话", System.currentTimeMillis(), System.currentTimeMillis(),
                    new java.util.ArrayList<>(java.util.List.of(
                            ChatMessage.Companion.user("第一条消息", java.util.Collections.emptyList()),
                            ChatMessage.Companion.assistant("第一条回答", java.util.Collections.emptyList()))));

            boolean ok = store().save(s);
            judge("save 返回 true", ok, "");
            judge("文件确实写出来了", fileOf(id).isFile(), fileOf(id).getAbsolutePath());

            var back = store().load(id);
            judge("load 读得回来", back != null, "");
            if (back != null) {
                judge("标题一致", "探针会话".equals(back.getTitle()), back.getTitle());
                judge("消息条数一致", back.getMessages().size() == 2,
                        "期望 2，实际 " + back.getMessages().size());
                judge("首条内容一致",
                        "第一条消息".equals(back.getMessages().get(0).getContent()), "");
            } else {
                judge("标题一致", false, "load 返回 null");
                judge("消息条数一致", false, "");
                judge("首条内容一致", false, "");
            }
        }

        // ---------- ② 不许留下临时文件 ----------
        System.out.println();
        System.out.println("--- ② 成功后不留 .tmp 残骸 ---");
        {
            File tmp = new File(dir(), id + ".json.tmp");
            judge("临时文件已被改名走（不残留）", !tmp.exists(),
                    tmp.exists() ? "残留了：" + tmp.getAbsolutePath() : "");
        }

        // ---------- ③ 反复保存，内容始终是合法 JSON ----------
        System.out.println();
        System.out.println("--- ③ 反复保存：目标文件任何时刻都该是完整 JSON ---");
        {
            boolean allValid = true;
            for (int i = 0; i < 8; i++) {
                var s = new Session(id, "第 " + i + " 版",
                        System.currentTimeMillis(), System.currentTimeMillis(),
                        new java.util.ArrayList<>(java.util.List.of(
                                ChatMessage.Companion.user("内容 " + i, java.util.Collections.emptyList()))));
                store().save(s);
                // 立刻读回来验
                var back = store().load(id);
                if (back == null || !("第 " + i + " 版").equals(back.getTitle())) {
                    allValid = false;
                    System.out.println("   ✗ 第 " + i + " 次保存后读回来不对");
                }
            }
            judge("8 次连续保存每次都完整可读", allValid, "");
        }

        // ---------- ④ 关键：写入失败时旧数据必须完好 ----------
        System.out.println();
        System.out.println("--- ④ 故障注入：写不进去时，旧文件必须完好 ---");
        {
            // 先存一版好数据
            var good = new Session(id, "重要数据-不能丢",
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    new java.util.ArrayList<>(java.util.List.of(
                            ChatMessage.Companion.user("这条必须还在", java.util.Collections.emptyList()))));
            store().save(good);
            String before = fileOf(id).exists() ? readText(fileOf(id)) : "";
            judge("故障注入前有旧数据", !before.isEmpty(), before.length() + " 字符");

            // 把 .tmp 路径占成一个**目录** → writeText 必然失败
            File tmp = new File(dir(), id + ".json.tmp");
            deleteRecursively(tmp);
            boolean made = tmp.mkdirs();
            judge("占位目录建好了（故障注入生效的前提）", made || tmp.isDirectory(), tmp.getAbsolutePath());

            var bad = new Session(id, "这份写不进去",
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    new java.util.ArrayList<>(java.util.List.of(
                            ChatMessage.Companion.user("新内容", java.util.Collections.emptyList()))));
            boolean ok = store().save(bad);
            judge("写不进去时 save 如实返回 false", !ok, "返回了 " + ok);

            String after = fileOf(id).exists() ? readText(fileOf(id)) : "";
            judge("**旧文件内容一字未动**", before.equals(after),
                    before.equals(after) ? "" : "旧内容被破坏！长度 " + before.length() + " → " + after.length());

            var back = store().load(id);
            judge("旧数据仍然读得回来", back != null && "重要数据-不能丢".equals(back.getTitle()),
                    back == null ? "load 返回 null" : back.getTitle());

            // 清场
            deleteRecursively(tmp);
        }

        // ---------- ⑤ 文件坏掉时的行为（如实记录，不是「通过」） ----------
        System.out.println();
        System.out.println("--- ⑤ 文件真被外部破坏时的行为 ---");
        {
            File f = fileOf(id);
            writeText(f, "{ 这不是合法 JSON");
            var back = store().load(id);
            System.out.println("   故意写坏后 load → " + (back == null ? "null" : "有值"));
            judge("坏文件不会让 load 抛异常（静默降级）", true, "");
            System.out.println("   注意：返回 null 意味着**会话静默消失**。");
            System.out.println("   原子写入把「自己写坏」这条路堵住了，但外部破坏（手改、杀毒软件、磁盘坏块）");
            System.out.println("   仍然会走到这里 —— 这是已知的残留风险，不是本次修复的范围。");
        }

        // 清场
        store().delete(id);

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    static String readText(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static void writeText(File f, String s) {
        try {
            java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }
}
