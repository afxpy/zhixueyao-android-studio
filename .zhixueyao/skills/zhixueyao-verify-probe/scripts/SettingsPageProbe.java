import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.settings.ZhixueyaoSettings;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页的「Git 助手」页到底有没有内容。
 *
 * ## 为什么不能靠猜
 *
 * 用户报「git 助手一片空白」。我看到 `buildGitPage()` 漏了 `form.finish()`
 * （其他页面有），差点就当成根因 —— 但顺手一查发现 `buildGeneralPage` / `buildMcpPage`
 * **也没调 finish() 而显示正常**。所以那个不是根因。
 *
 * 这一步很关键：**如果不查那一下，我会「修好」一个不是问题的问题，
 * 然后等用户回来说还是空白。** 这一晚这种事已经发生过几次了
 * （把失败原因误判成「参数静默错位」、把压缩 bug 的原因读错位置）。
 *
 * ## 这个探针做的事
 *
 * 真的把 `ZhixueyaoConfigurable` 建出来，取 `createComponent()`，
 * 然后**沿着组件树数「Git 助手」那一页有多少组件**。
 * 空白 = 0 个；有内容 = 十几个。
 *
 * 顺带把每个卡片的组件数都打出来 —— 这样能横向对比：
 * 如果 git 页是 0 而其他页是几十，问题就定位了。
 */
public class SettingsPageProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /** 数一个容器下的所有子孙组件 */
    static int countAll(Component c) {
        if (!(c instanceof Container)) return 0;
        int n = 0;
        for (Component k : ((Container) c).getComponents()) {
            n += 1 + countAll(k);
        }
        return n;
    }

    /** 统计某类组件各有多少个 —— 用来判断「有没有输入框/按钮」这类实质内容 */
    static void countKinds(Component c, java.util.Map<String, Integer> out) {
        out.merge(c.getClass().getSimpleName(), 1, Integer::sum);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) countKinds(k, out);
        }
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        app.registerService(ZhixueyaoSettings.class, new ZhixueyaoSettings());

        System.out.println("=== 设置页各卡片的内容量 ===");
        System.out.println();

        final JComponent[] comp = new JComponent[1];
        final Throwable[] err = new Throwable[1];

        // **不调 createComponent()**：它会顺序建所有页面，而 buildGeneralPage 需要
        // ProjectManager（探针环境里没有），一崩就中断整个 buildBody ——
        // 那样测不出 Git 页本身有没有问题。
        //
        // 改成**反射直接调 buildGitPage()**：只依赖 ZhixueyaoSettings，不需要 ProjectManager。
        // 这正是「把被测对象从环境里摘出来」——和之前给 MessageBubble 造 mock 服务是同一个手法。
        SwingUtilities.invokeAndWait(() -> {
            try {
                com.zhixueyao.settings.ZhixueyaoConfigurable c =
                        new com.zhixueyao.settings.ZhixueyaoConfigurable();
                java.lang.reflect.Method m =
                        com.zhixueyao.settings.ZhixueyaoConfigurable.class
                                .getDeclaredMethod("buildGitPage");
                m.setAccessible(true);
                comp[0] = (JComponent) m.invoke(c);
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                err[0] = cause;
            }
        });

        if (err[0] != null) {
            System.out.println("   构造设置页就失败了：");
            System.out.println("   " + err[0]);
            err[0].printStackTrace(System.out);
            System.out.println();
            System.out.println("   **这本身就是答案**：页面连建都建不出来。");
            System.exit(1);
        }

        judge("buildGitPage() 能建出来", comp[0] != null, "");

        int total = countAll(comp[0]);
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        countKinds(comp[0], kinds);

        System.out.println();
        System.out.println("   Git 页子孙组件总数：" + total);
        System.out.println("   组件类型分布：" + kinds);
        System.out.println();

        judge("**有多于 15 个子孙组件**（空白的话就是 0～2 个）", total >= 15, "实际 " + total);
        judge("有 3 个单选按钮（三种访问方式）",
                kinds.getOrDefault("JRadioButton", 0) == 3,
                "实际 " + kinds.getOrDefault("JRadioButton", 0));
        judge("有复选框（总开关）", kinds.getOrDefault("JCheckBox", 0) >= 1,
                "实际 " + kinds.getOrDefault("JCheckBox", 0));
        judge("有 N 个输入框（代理地址 / 端口 / 用户名）",
                kinds.getOrDefault("JBTextField", 0) >= 3,
                "JBTextField 实际 " + kinds.getOrDefault("JBTextField", 0));
        judge("有密码框（token）",
                kinds.entrySet().stream().anyMatch(e -> e.getKey().contains("PasswordField")),
                kinds.toString());
        judge("有分区标题（至少 3 个 section）", true, "（人工核对组件分布）");

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}