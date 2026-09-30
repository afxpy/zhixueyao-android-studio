import com.zhixueyao.mcp.IdeMcpServer;
import com.zhixueyao.tools.AgentTool;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务的启停不能泄漏线程。
 *
 * ## 这个 bug 是怎么被发现的
 *
 * 扫「资源没关」时看到 `IdeMcpServer` 建了 `Executors.newFixedThreadPool(4)`，
 * 但 `stop()` 里只有 `server?.stop(0)` —— **线程池引用根本没存成字段**，
 * 想关也关不了。
 *
 * 这踩的是 `HttpServer` 一个很容易误会的点：**`stop()` 不会关掉用户传入的 executor**
 * （JDK 文档写得很明确，但两个 API 名字太像了）。
 * 而固定线程池的核心线程**永不超时** —— 于是每启停一次就永久多挂 4 个线程。
 *
 * ## 为什么必须「真跑一遍」而不是只看代码
 *
 * 这类泄漏**不会有任何可见症状**：不报错、不崩、功能也正常。
 * 只有量线程数才看得出来。而且它**是累积的** ——
 * 用户反复按那个开关按钮，线程就一路往上涨。
 *
 * 所以本探针的判据是**数量**：连续启停 N 轮后，
 * 存活线程数必须回到基线附近，而不是每轮 +4。
 *
 * ## 对照组
 *
 * 光看「线程没涨」还不够 —— 万一服务**根本没起来**，线程当然不会涨，
 * 那是假通过。所以每组都要先断言「服务确实起来了」（isRunning + 端口能连上）。
 */
public class McpServerLifecycleProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /** 数还活着、且名字属于本服务的线程 */
    static int liveServerThreads() {
        Thread[] all = new Thread[Thread.activeCount() * 4 + 32];
        int n = Thread.enumerate(all);
        int count = 0;
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t != null && t.isAlive() && t.getName() != null
                    && t.getName().startsWith("zhixueyao-mcp-server")) {
                count++;
            }
        }
        return count;
    }

    /** 找一个空闲端口：从 18700 往上试，能被 ServerSocket 绑上就算空闲 */
    static int freePort() {
        for (int p = 18700; p < 18800; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(p)) {
                return p;
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    static IdeMcpServer newServer() {
        return new IdeMcpServer(
                () -> null,
                () -> new ArrayList<AgentTool>()
        );
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MCP 服务启停：线程泄漏 ===");

        int port = freePort();
        if (port < 0) {
            System.out.println("找不到空闲端口，跳过。");
            System.exit(0);
        }
        System.out.println("使用端口：" + port);
        System.out.println();

        int baseline = liveServerThreads();
        System.out.println("基线线程数：" + baseline);
        System.out.println();

        // ---------- 单轮：起来了 → 停掉 → 线程回落 ----------
        System.out.println("--- ① 一轮启停 ---");
        {
            IdeMcpServer s = newServer();
            boolean ok = s.start(port);
            judge("服务起来了", ok, "start 返回 " + ok);
            judge("isRunning 为真", s.isRunning(), "");

            // **必须先 poke** —— 线程池懒创建，不驱动它就没有工作线程可数
            poke(port);
            Thread.sleep(300);
            // **对照组**：证明真的在跑，而不是「没起来所以没线程」
            int afterStart = liveServerThreads();
            System.out.println("   起来后线程数：" + afterStart);
            judge("确实建了工作线程（对照组：不是空转）", afterStart > baseline,
                    "多了 " + (afterStart - baseline) + " 个");
            judge("端口能连上（对照组：真的在监听）", canConnect(port), "");

            s.stop();
            Thread.sleep(400);
            int afterStop = liveServerThreads();
            System.out.println("   停掉后线程数：" + afterStop);
            judge("停掉后线程回落到基线", afterStop <= baseline,
                    "还剩 " + (afterStop - baseline) + " 个没退");
            judge("isRunning 为假", !s.isRunning(), "");
        }

        // ---------- 多轮：这才是泄漏的真正暴露方式 ----------
        System.out.println();
        System.out.println("--- ② 连续 6 轮启停（泄漏会累积，单轮看不出来）---");
        {
            for (int round = 1; round <= 6; round++) {
                IdeMcpServer s = newServer();
                s.start(port);
                poke(port);          // 驱动出工作线程，否则这一轮等于没测
                Thread.sleep(120);
                s.stop();
                Thread.sleep(150);
            }
            Thread.sleep(500);
            int after = liveServerThreads();
            System.out.println("   6 轮之后线程数：" + after + "（基线 " + baseline + "）");
            judge("6 轮启停后没有线程堆积", after <= baseline,
                    "多出 " + (after - baseline) + " 个 —— 每轮泄漏 4 个的话这里应该是 +24");
        }

        // ---------- 启动失败也不能漏 ----------
        System.out.println();
        System.out.println("--- ③ 启动失败（端口被占）也不能建了池就不管 ---");
        {
            // 先占住端口
            try (java.net.ServerSocket hold = new java.net.ServerSocket(port)) {
                IdeMcpServer s = newServer();
                boolean ok = s.start(port);
                judge("端口被占时启动失败（不抛异常）", !ok, "start 返回 " + ok);
                judge("失败后 isRunning 仍为假", !s.isRunning(), "");
            }
            Thread.sleep(300);
            int after = liveServerThreads();
            judge("失败路径没留下线程", after <= baseline, "多出 " + (after - baseline) + " 个");
        }

        // ---------- 重复 start 不该叠加 ----------
        System.out.println();
        System.out.println("--- ④ 幂等：连点两次启动不该建两个服务 ---");
        {
            IdeMcpServer s = newServer();
            judge("第一次启动成功", s.start(port), "");
            int t1 = liveServerThreads();
            judge("第二次启动仍是成功（幂等）", s.start(port), "");
            int t2 = liveServerThreads();
            judge("线程数没有翻倍", t2 <= t1 + 1, t1 + " → " + t2);
            s.stop();
            Thread.sleep(300);
            judge("停一次就干净了", liveServerThreads() <= baseline, "");
        }

        // ---------- stop 可以重复调用 ----------
        System.out.println();
        System.out.println("--- ⑤ stop 重复调用不炸 ---");
        {
            IdeMcpServer s = newServer();
            s.start(port);
            s.stop();
            try {
                s.stop();
                s.stop();
                judge("重复 stop 不抛异常", true, "");
            } catch (Exception e) {
                judge("重复 stop 不抛异常", false, e.toString());
            }
            // 没启动过就 stop
            try {
                newServer().stop();
                judge("未启动就 stop 不抛异常", true, "");
            } catch (Exception e) {
                judge("未启动就 stop 不抛异常", false, e.toString());
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * 真发一个 HTTP 请求过去。
     *
     * **这一步是必须的，而且我第一版漏了它。**
     *
     * `newFixedThreadPool` 的线程是**懒创建**的：没有任务提交就一个都不建。
     * 所以「起完服务立刻数线程」永远是 0 —— 那个断言会**假失败**；
     * 更糟的是，如果只看「6 轮之后线程没涨」，一个真泄漏也会被**假通过**掉
     * （因为压根没有线程可涨）。
     *
     * 发一个请求逼它建线程，这样「停掉之后线程该回落」才是有意义的断言。
     */
    static void poke(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
            s.setSoTimeout(1500);
            String req = "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 2\r\n"
                    + "Connection: close\r\n\r\n{}";
            s.getOutputStream().write(req.getBytes("UTF-8"));
            s.getOutputStream().flush();
            // 读一点，确保请求确实被处理了（不能只连不发）
            byte[] buf = new byte[256];
            try { s.getInputStream().read(buf); } catch (Exception ignored) { }
        } catch (Exception ignored) {
            // 探针不关心响应内容，只关心「是否驱动了工作线程」
        }
    }

    static boolean canConnect(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
