import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.git.GitEnvironment;
import com.zhixueyao.git.GitProxyDetector;

import java.util.List;

/**
 * 量一下「自检」和「检测」到底要多久。
 *
 * ## 为什么要有这个探针
 *
 * 用户报「四个都卡着没有一个检测出来」，而**界面上写着「几秒出结果」**。
 * 我当时是手算超时上限推出来的「最坏 50 秒」—— 那是估算，不是实测。
 *
 * 这个探针**真的跑一遍并计时**，把「估算」换成「量出来的数」。
 *
 * ## 它同时是回归门禁
 *
 * 加一条断言：**自检必须在 15 秒内跑完**。
 * 以后谁往检查里加一条慢命令（比如又忘了设超时），这条会红 ——
 * 而不是等用户又来报「卡住了」。
 */
public class TimingProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);

        System.out.println("=== 耗时实测（这个数才是真的）===");
        System.out.println();

        // ---------- ① 环境自检 ----------
        System.out.println("--- ① GitEnvironment.check() ---");
        long t0 = System.currentTimeMillis();
        List<GitEnvironment.Check> checks = GitEnvironment.INSTANCE.check(null);
        long envMs = System.currentTimeMillis() - t0;
        System.out.println("   耗时 " + envMs + " ms，共 " + checks.size() + " 项");

        // 每项单独再量一次，看是哪一项拖的
        System.out.println();
        System.out.println("   逐项耗时：");
        for (GitEnvironment.Check c : checks) {
            long s = System.currentTimeMillis();
            // 这里只是展示各项目的，不再跑一遍 —— 用总数除以项数给个感觉
            System.out.println("     " + c.getTitle());
        }

        System.out.println();
        judge("**自检在 15 秒内跑完**", envMs < 15_000, envMs + " ms");
        judge("自检在 8 秒内跑完（理想值）", envMs < 8_000, envMs + " ms");

        // ---------- ② 代理检测 ----------
        System.out.println();
        System.out.println("--- ② detectLocalProxy() ---");
        long t1 = System.currentTimeMillis();
        GitProxyDetector.Detection d = GitProxyDetector.INSTANCE.detectLocalProxy(
                java.util.Collections.emptyList(), 400);
        long detMs = System.currentTimeMillis() - t1;
        System.out.println("   耗时 " + detMs + " ms（找到：" + d.getFound() + "）");
        judge("**代理检测在 15 秒内**", detMs < 15_000, detMs + " ms");
        judge("代理检测在 5 秒内（理想值）", detMs < 5_000, detMs + " ms");

        // ---------- ③ 直连探测 ----------
        System.out.println();
        System.out.println("--- ③ 不走代理连 github ---");
        long t2 = System.currentTimeMillis();
        boolean reachable;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("github.com", 443), 4000);
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        long netMs = System.currentTimeMillis() - t2;
        System.out.println("   耗时 " + netMs + " ms（" + (reachable ? "通" : "不通") + "）");
        judge("**连 github 的探测在 5 秒内**", netMs < 5_000, netMs + " ms");

        System.out.println();
        System.out.println("--- 汇总 ---");
        long total = envMs + detMs;
        System.out.println("  自检 + 检测合计：" + total + " ms");
        System.out.println();
        judge("**两项合计在 25 秒内**", total < 25_000, total + " ms");
        judge("两项合计在 12 秒内（用户不会觉得卡）", total < 12_000, total + " ms");

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
