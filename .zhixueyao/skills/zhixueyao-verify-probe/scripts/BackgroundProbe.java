import com.zhixueyao.tools.BackgroundTasks;

import java.util.Arrays;
import java.util.List;

/**
 * 后台任务：命令切分与 shell 语法检出。
 *
 * ## 为什么这两段必须验
 *
 * 「不走 shell」是这套设计的核心决定，代价是**我们自己得把命令行切成参数数组** ——
 * 而这件事看起来简单、实际全是坑：
 *
 *  - `"a b"` 引号里的空格**不能**当分隔符
 *  - 切完引号要**剥掉**（否则程序会收到带引号的参数）
 *  - **反斜杠不能当转义** —— Windows 路径 `C:\foo` 必须原样过去；
 *    处理转义的话 `"C:\new"` 里的 `\n` 会变成换行
 *  - `""` 空参数要**保留**（有些命令靠它区分「传了空串」和「没传」）
 *
 * 切错的后果是「参数传给了错误的程序」，而报错信息通常**完全看不出**是切分的问题 ——
 * 所以只能靠探针钉住。
 *
 * 另一半是 shell 元字符检出：检不出来时，`gradlew test | tail -50` 会变成
 * 「给 gradlew 传一个叫 `|` 的参数」，Gradle 报一个跟管道毫无关系的错。
 * 检出来并收回调用的工具（`run_script`）能扛住这类输入。
 */
public class BackgroundProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static void expectSplit(String input, String... expected) {
        List<String> got = BackgroundTasks.INSTANCE.splitCommand(input);
        List<String> want = Arrays.asList(expected);
        boolean ok = got.equals(want);
        System.out.println("   " + quote(input) + "  →  " + got);
        judge("切分正确", ok, ok ? "" : "期望 " + want);
    }

    static String quote(String s) {
        return "\"" + s + "\"";
    }

    public static void main(String[] args) {
        System.out.println("=== 后台任务：命令切分 ===");

        // ---------- 基本 ----------
        expectSplit("gradlew test", "gradlew", "test");
        expectSplit("gradlew  test   --info", "gradlew", "test", "--info");
        expectSplit("  trimmed  ", "trimmed");
        expectSplit("", new String[0]);

        // ---------- 引号 ----------
        System.out.println();
        System.out.println("--- 引号：里面的空格不算分隔 ---");
        expectSplit("git commit -m \"hello world\"", "git", "commit", "-m", "hello world");
        expectSplit("echo 'a b c'", "echo", "a b c");
        // 引号被剥掉（程序收到的应该是内容，不是带引号的字符串）
        expectSplit("prog \"quoted\"", "prog", "quoted");
        // 相邻的引号段拼成一个参数（shell 也是这个语义）
        expectSplit("prog a\"b\"c", "prog", "abc");

        // ---------- 空参数 ----------
        System.out.println();
        System.out.println("--- 空参数要保留（区分「传了空串」和「没传」）---");
        expectSplit("prog \"\"", "prog", "");
        expectSplit("prog \"\" tail", "prog", "", "tail");

        // ---------- 反斜杠：关键用例 ----------
        System.out.println();
        System.out.println("--- 反斜杠**不做转义**（Windows 路径要原样过去）---");
        // 这条是真正会出事的地方：如果把 \n 当转义，参数会变成带换行的字符串
        expectSplit("cmd C:\\new\\file", "cmd", "C:\\new\\file");
        expectSplit("cmd \"C:\\Program Files\\x\"", "cmd", "C:\\Program Files\\x");
        // 引号内的反斜杠同样原样保留
        expectSplit("cmd \"C:\\temp\"", "cmd", "C:\\temp");

        // ---------- 未闭合引号：不能崩、也不能吞掉后面的内容 ----------
        System.out.println();
        System.out.println("--- 未闭合引号：按「剩余全部当引号内」处理 ---");
        expectSplit("prog \"unclosed rest", "prog", "unclosed rest");
        expectSplit("prog 'unclosed", "prog", "unclosed");

        // ---------- shell 语法检出 ----------
        System.out.println();
        System.out.println("=== shell 元字符检出 ===");
        {
            String[][] cases = {
                {"gradlew test | tail -50", "管道"},
                {"cmd > out.txt", "重定向"},
                {"cmd < in.txt", "重定向"},
                {"a && b", "逻辑连接"},
                {"a || b", "逻辑连接"},
                {"a; b", "分号"},
                {"echo $(date)", "命令替换"},
                {"echo `date`", "命令替换"},
            };
            for (String[] c : cases) {
                String got = BackgroundTasks.INSTANCE.shellMetaOf(c[0]);
                boolean ok = got != null && got.contains(c[1]);
                System.out.println("   " + quote(c[0]) + "  →  " + got);
                judge("检出「" + c[1] + "」", ok, ok ? "" : "没检出来（返回 " + got + "）");
            }
        }

        // ---------- 引号里的元字符不算 ----------
        System.out.println();
        System.out.println("--- 引号**里面**的元字符不该被当成 shell 语法 ---");
        {
            String r1 = BackgroundTasks.INSTANCE.shellMetaOf("git commit -m \"a | b\"");
            judge("引号内的 | 不算", r1 == null, "误报成 " + r1);
            String r2 = BackgroundTasks.INSTANCE.shellMetaOf("grep -n \"a > b\" file.txt");
            judge("引号内的 > 不算", r2 == null, "误报成 " + r2);
            String r3 = BackgroundTasks.INSTANCE.shellMetaOf("grep 'x;y' f.txt");
            judge("引号内的 ; 不算", r3 == null, "误报成 " + r3);
        }

        // ---------- 干净命令不该误报 ----------
        System.out.println();
        System.out.println("--- 正常命令不该被误报 ---");
        {
            String[] clean = {
                "gradlew test --console=plain",
                "npm run build",
                "python -m http.server 8080",
                "git log --oneline -20",
                "adb logcat -v time"
            };
            int bad = 0;
            for (String c : clean) {
                String r = BackgroundTasks.INSTANCE.shellMetaOf(c);
                if (r != null) { bad++; System.out.println("   ✗ 误报：" + c + " → " + r); }
            }
            judge("干净命令零误报", bad == 0, bad + " 条误报");
        }

        // ---------- 真的能起进程并取到输出（端到端）----------
        System.out.println();
        System.out.println("--- 端到端：起一个真进程、取输出、停掉 ---");
        {
            BackgroundTasks.INSTANCE.reset();
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            // 用一个会打印后稍等再退出的命令，好验证「跑到一半能取到输出」
            List<String> argv = win
                    ? Arrays.asList("cmd", "/c", "echo hello-from-bg && timeout /t 3 /nobreak > nul")
                    : Arrays.asList("sh", "-c", "echo hello-from-bg; sleep 3");

            java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"));
            try {
                com.zhixueyao.tools.ProcessRunner.Handle h =
                        com.zhixueyao.tools.ProcessRunner.INSTANCE.start(
                                argv, dir, java.util.Collections.emptyMap());
                com.zhixueyao.tools.BackgroundTasks.Task t =
                        com.zhixueyao.tools.BackgroundTasks.INSTANCE.register("echo hello-from-bg", h);

                judge("注册成功", BackgroundTasks.INSTANCE.get(t.getId()) != null, t.getId());
                judge("起来时是 running", h.getAlive() || h.exitCode() != null, "");

                // 等一会儿再看输出 —— 这就是「随跑随取」
                Thread.sleep(800);
                String out = h.stdout();
                System.out.println("   跑到一半取到的输出：" + out.trim());
                judge("跑到一半就能取到输出", out.contains("hello-from-bg"),
                        "拿不到说明读流没起（那个进程会卡在写上永远不结束）");

                // 立刻停掉，验证 stop 生效
                h.stop();
                Thread.sleep(600);
                judge("stop 之后不再存活", !h.getAlive(), "还在跑说明 killTree 没生效");
                judge("stop 标记置位", h.getStopped(), "");

                // 已结束的任务不该被 task_stop 当成失败
                com.zhixueyao.tools.ToolResult r =
                        new com.zhixueyao.tools.TaskStopTool().execute(proj(), mkObj("task_id", t.getId()));
                judge("对已停止的任务说明「无需停止」而不是报错",
                        r.getText().contains("无需停止") || r.getText().contains("不在跑"), r.getText());
            } catch (Exception e) {
                judge("端到端", false, "异常：" + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Kotlin 非空参数不接受 null */
    static com.intellij.openapi.project.Project proj() {
        return (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                BackgroundProbe.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, a) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == String.class) return "probe";
                    return null;
                });
    }

    static com.zhixueyao.util.Json.Obj mkObj(String k, String v) {
        return com.zhixueyao.util.JsonKt.jsonObj(new kotlin.Pair<>(k, v));
    }
}
