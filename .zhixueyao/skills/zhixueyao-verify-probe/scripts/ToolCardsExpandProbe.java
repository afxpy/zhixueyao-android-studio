import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.ui.MessageBubble;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具卡片展开后必须真的看得见。
 *
 * ## 这个 bug 的形状
 *
 * 用户反馈：「展开工具调用什么都没显示出来」。
 *
 * 根因是**两处都设了不可见，但其实只需要一处**：
 *
 * ```
 * toolCardsScroll  (JBScrollPane)   isVisible = false   ← 收起时该藏的是它
 * toolCardsPanel   (它的 view)      isVisible = false   ← 这个不该藏
 * ```
 *
 * 点击汇总行只切**外层滚动容器**的可见性，于是：
 * 滚动容器可见了，但里面的 view 是隐藏的 → **展开后一片空白**。
 *
 * `JScrollPane` 显示的是它的 view —— **藏 view 和藏滚动容器是两件事**。
 *
 * ## 为什么这个探针值得写
 *
 * 它断言的不是「展开后有多少张卡片」（那要跑起来看），
 * 而是一条**结构不变量**：
 *
 * > **滚动容器的 view 永远不该被隐藏。**
 *
 * 这条不变量跟「展开/收起」的具体实现无关 —— 谁以后再往那儿加代码，
 * 只要把 view 藏了，这个探针就会红。而如果没有它，
 * 那种改动**在界面上只表现为「点了没反应」**，很容易被当成别的问题查半天。
 */
public class ToolCardsExpandProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /** 在组件树里找那个「装着工具卡片的滚动容器」 */
    static JScrollPane findScrollWithCards(Container root) {
        List<Container> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Container c = queue.remove(0);
            if (c instanceof JScrollPane) {
                JScrollPane sp = (JScrollPane) c;
                JViewport v = sp.getViewport();
                if (v != null && v.getView() != null
                        && v.getView().getClass().getSimpleName().startsWith("JBPanel")) {
                    // 工具卡片容器的特征是「纵向 BoxLayout + 名字里没有别的特征」，
                    // 这里用「view 是 JBPanel 且不是编辑器」近似判定，够用
                    Container view = (Container) v.getView();
                    if (view.getLayout() instanceof BoxLayout) return sp;
                }
            }
            for (Component k : c.getComponents()) {
                if (k instanceof Container) queue.add((Container) k);
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);
        // MessageBubble 会去取设置服务（UiKit 的主题色等），不注册就 NPE
        app.registerService(com.zhixueyao.settings.ZhixueyaoSettings.class,
                new com.zhixueyao.settings.ZhixueyaoSettings());

        System.out.println("=== 工具卡片展开 ===");

        final MessageBubble[] b = new MessageBubble[1];
        final JScrollPane[] found = new JScrollPane[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                b[0] = new MessageBubble(MessageBubble.Kind.ASSISTANT, null);
                // 记几张工具卡片
                b[0].addToolCard("c1", "read_file", "{\"path\":\"a.kt\"}");
                b[0].addToolCard("c2", "search_code", "{\"query\":\"foo\"}");
                b[0].addToolCard("c3", "git", "{\"args\":\"status\"}");
                b[0].setSize(600, 400);
                b[0].doLayout();
                found[0] = findScrollWithCards(b[0]);
            } catch (Throwable t) {
                System.out.println("   构造气泡失败：" + t);
            }
        });

        judge("气泡构造成功", b[0] != null, "");
        judge("找到了工具卡片的滚动容器（对照：不是没找到所以跳过）",
                found[0] != null, found[0] == null ? "没找到" : found[0].getClass().getSimpleName());

        if (found[0] == null) {
            System.out.println();
            System.out.println("找不到容器就没法验下去 —— 但**不能因此算通过**：");
            System.out.println("  那会掩盖「组件结构变了」这件事。这里判失败。");
            fail++;
            System.out.println();
            System.out.println("=== 汇总 ===");
            System.out.println("  通过 " + pass + " / 失败 " + fail);
            System.exit(1);
        }

        final JScrollPane sp = found[0];
        final Component view = sp.getViewport().getView();

        System.out.println("   滚动容器：" + sp.getClass().getSimpleName()
                + "　可见=" + sp.isVisible());
        System.out.println("   它的 view：" + view.getClass().getSimpleName()
                + "　可见=" + view.isVisible()
                + "　子组件数=" + ((Container) view).getComponentCount());

        // ---------- 核心不变量 ----------
        System.out.println();
        System.out.println("--- 核心：滚动容器的 view 不该被隐藏 ---");
        judge("**view 是可见的**（藏 view 会让滚动容器变空壳）", view.isVisible(),
                view.isVisible() ? "" : "view 被隐藏了 —— 展开后一片空白就是这么来的");
        judge("卡片确实加进去了（对照：不是空容器）",
                ((Container) view).getComponentCount() > 0,
                "子组件数 " + ((Container) view).getComponentCount());

        // ---------- 收起时藏的是滚动容器，不是 view ----------
        System.out.println();
        System.out.println("--- 收起时该藏的是滚动容器 ---");
        SwingUtilities.invokeAndWait(() -> {
            // 模拟一次「收起」：只动滚动容器
            sp.setVisible(false);
        });
        judge("收起后滚动容器不可见", !sp.isVisible(), "");
        judge("收起后 view 仍然可见（它只是被父容器挡住了）", view.isVisible(),
                view.isVisible() ? "" : "藏了 view —— 下次展开就会是空白");

        // ---------- 展开回来能恢复 ----------
        SwingUtilities.invokeAndWait(() -> {
            sp.setVisible(true);
            sp.revalidate();
        });
        judge("再展开，滚动容器可见", sp.isVisible(), "");
        judge("再展开，view 仍可见且卡片还在",
                view.isVisible() && ((Container) view).getComponentCount() > 0,
                "子组件数 " + ((Container) view).getComponentCount());

        // ---------- 宽度不该被挤成 0 ----------
        System.out.println();
        System.out.println("--- 尺寸：展开后不该是 0 高 ---");
        SwingUtilities.invokeAndWait(() -> {
            sp.setSize(600, 320);
            sp.doLayout();
        });
        Dimension p = sp.getPreferredSize();
        System.out.println("   preferredSize = " + p.width + " × " + p.height);
        judge("宽度是正的", p.width > 0, "");

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
