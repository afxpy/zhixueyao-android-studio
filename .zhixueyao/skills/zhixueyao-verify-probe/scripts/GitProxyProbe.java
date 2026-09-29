import com.zhixueyao.git.GitProxyDetector;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Git 助手的代理检测。
 *
 * ## 这段代码解决的是一个真实故障
 *
 * 这台机器的 git 配置里写着 `http.proxy = 127.0.0.1:57567`，而**那个代理根本没开**。
 * 每次推送的报错是：
 *
 * ```
 * fatal: unable to access '...': Failed to connect to github.com:443
 *        over proxy 127.0.0.1 after 2095 ms: Could not connect to server
 * ```
 *
 * 这个报错**很容易被读成「网络不通」** —— 于是去查 VPN、查防火墙、查 DNS，
 * 全查一遍才发现是配置里一个过期的代理地址。
 *
 * ## 为什么探针要「自己造一个假代理」
 *
 * 「端口开着但不是代理」这个情况**在真实机器上撞不到**（谁没事占着 7890 端口）。
 * 而它恰恰是最容易造成误报的情况 —— 只做 TCP connect 的实现在这里会报「找到代理」，
 * 然后用户拿一个假的代理地址去推送，失败得更莫名其妙。
 *
 * 所以探针**自己起一个最小 HTTP 服务器**扮演「不是代理的普通服务」，
 * 再起一个**会说 CONNECT 200 的假代理**扮演真代理。
 * 两种都造出来，才能确认检测代码真的能区分它们。
 *
 * 这也是这个工程里反复用过的手法：**没法在真实环境里遇到的边界，
 * 就把它造出来。**
 */
public class GitProxyProbe {

    static int pass = 0, fail = 0;
    static final AtomicInteger PORT_SEQ = new AtomicInteger(19500);

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /** 起一个「不是代理」的普通服务：连上不回应，或回一个非 HTTP 的东西 */
    static ServerSocket startNonProxy() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    // 回一句不是 HTTP 状态行的东西 —— 模拟「端口开着但不是代理」
                    s.getOutputStream().write("WHAT?\r\n".getBytes("US-ASCII"));
                    s.getOutputStream().flush();
                    s.close();
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /** 起一个最小假代理：对 CONNECT 回 200 */
    static ServerSocket startFakeProxy() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "US-ASCII"));
                    String line = r.readLine();          // CONNECT github.com:443 HTTP/1.1
                    while (line != null && !line.isEmpty()) line = r.readLine();  // 吃掉头部
                    if (line != null || true) {
                        s.getOutputStream().write(
                                "HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));
                        s.getOutputStream().flush();
                    }
                    s.close();
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Git 助手：代理检测 ===");

        // ---------- ① 纯工具函数 ----------
        System.out.println();
        System.out.println("--- ① 地址解析 ---");
        {
            judge("parsePort(http://127.0.0.1:7890) = 7890",
                    GitProxyDetector.INSTANCE.parsePort("http://127.0.0.1:7890") == 7890, "");
            judge("parsePort(不带 http:// 也能认)",
                    GitProxyDetector.INSTANCE.parsePort("127.0.0.1:7897") == 7897, "");
            judge("parsePort(socks5://127.0.0.1:1080) = 1080",
                    GitProxyDetector.INSTANCE.parsePort("socks5://127.0.0.1:1080") == 1080, "");
            judge("parsePort(乱写) = null",
                    GitProxyDetector.INSTANCE.parsePort("这不是地址") == null, "");
            judge("parseHost 默认回环",
                    "127.0.0.1".equals(GitProxyDetector.INSTANCE.parseHost("127.0.0.1:7890")), "");
        }

        // ---------- ② git 参数拼装（这是「不改用户配置」的关键）----------
        System.out.println();
        System.out.println("--- ② 给 git 命令拼参数 ---");
        {
            List<String> p = GitProxyDetector.INSTANCE.proxyArgs("http://127.0.0.1:7890");
            System.out.println("   走代理：" + p);
            judge("走代理时给了 -c http.proxy", p.contains("http.proxy=http://127.0.0.1:7890"), "");
            judge("https 也一起给了", p.contains("https.proxy=http://127.0.0.1:7890"), "");
            judge("用的是 -c 临时传参（不写配置文件）", p.contains("-c"), "");

            List<String> d = GitProxyDetector.INSTANCE.directArgs();
            System.out.println("   直连：" + d);
            judge("**直连时显式清空代理**（这是常见故障源）",
                    d.contains("http.proxy=") && d.contains("https.proxy="), d.toString());
            judge("空字符串（git 认这个写法）",
                    d.stream().noneMatch(x -> x.equals("http.proxy=null")), "");

            judge("proxyArgs(null) 返回空列表",
                    GitProxyDetector.INSTANCE.proxyArgs(null).isEmpty(), "");
        }

        // ---------- ③ 「端口开着但不是代理」必须被判为不可用 ----------
        System.out.println();
        System.out.println("--- ③ 关键：端口开着但不是代理，不能误报 ---");
        {
            ServerSocket fake = startFakeProxy();
            ServerSocket notProxy = startNonProxy();
            try {
                int proxyPort = fake.getLocalPort();
                int otherPort = notProxy.getLocalPort();

                GitProxyDetector.Probe p1 = GitProxyDetector.INSTANCE.probeHttpProxy(proxyPort, 800);
                System.out.println("   真代理端口 " + proxyPort + " → " + p1.getOk() + "：" + p1.getDetail());
                judge("**真代理被认出来**", p1.getOk(), p1.getDetail());

                GitProxyDetector.Probe p2 = GitProxyDetector.INSTANCE.probeHttpProxy(otherPort, 800);
                System.out.println("   非代理端口 " + otherPort + " → " + p2.getOk() + "：" + p2.getDetail());
                judge("**端口开着但不是代理 → 判不可用**", !p2.getOk(), p2.getDetail());
                judge("而且说明里点出了「不像代理」",
                        p2.getDetail().contains("不像代理") || p2.getDetail().contains("没回应")
                                || p2.getDetail().contains("拒绝"),
                        p2.getDetail());

                // 对照组：确认「真代理」和「非代理」的判定确实不同
                // —— 光看两边都「有结果」不够，要看它们**结论相反**
                judge("[对照组] 两个端口的结论确实相反", p1.getOk() != p2.getOk(),
                        "真代理=" + p1.getOk() + "，非代理=" + p2.getOk());
            } finally {
                fake.close();
                notProxy.close();
            }
        }

        // ---------- ④ 没开的端口 ----------
        System.out.println();
        System.out.println("--- ④ 端口没开 ---");
        {
            int dead = 19999;
            GitProxyDetector.Probe p = GitProxyDetector.INSTANCE.probeHttpProxy(dead, 300);
            System.out.println("   " + dead + " → " + p.getDetail());
            judge("端口没开时报「端口没开」", !p.getOk() && p.getDetail().contains("端口没开"),
                    p.getDetail());
        }

        // ---------- ⑤ 自动扫描：扫不到时要如实列出试过哪些 ----------
        System.out.println();
        System.out.println("--- ⑤ 自动扫描的结果要可解释 ---");
        {
            // 指定一个必然没有的端口，避免依赖本机环境（本机可能真开着代理）
            GitProxyDetector.Detection d = GitProxyDetector.INSTANCE.detectLocalProxy(
                    List.of(19998), 200);
            System.out.println("   扫了 " + d.getScanned().size() + " 个端口");
            judge("扫过哪些端口有记录（不能只报「没找到」）", !d.getScanned().isEmpty(),
                    "共 " + d.getScanned().size() + " 条");
            judge("每条都有说明文字",
                    d.getScanned().stream().allMatch(x -> x.getDetail() != null && !x.getDetail().isEmpty()),
                    "");
            judge("用户指定的端口排在第一个（先信用户填的）",
                    d.getScanned().get(0).getPort() == 19998,
                    "第一个是 " + d.getScanned().get(0).getPort());
        }

        // ---------- ⑥ 走代理连目标站 ----------
        System.out.println();
        System.out.println("--- ⑥ 通过代理连 github ---");
        {
            ServerSocket fake = startFakeProxy();
            try {
                int port = fake.getLocalPort();
                GitProxyDetector.Probe p = GitProxyDetector.INSTANCE.testThroughProxy(
                        "http://127.0.0.1:" + port, "github.com", 3000);
                System.out.println("   假代理 " + port + " → " + p.getOk() + "：" + p.getDetail());
                // **断言反过来了 —— 因为被测行为变了，而且变得更对了。**
                //
                // 原来这里是「假代理回 200 → 判为能连上」。那个假代理只会回
                // `HTTP/1.1 200 Connection established`，然后什么都不转发。
                //
                // 用户给我看了他的系统代理设置（一个**只能上国内**的代理），
                // 我才意识到：**CONNECT 的 200 只表示「我同意建隧道」，
                // 不表示隧道里跑得通** —— 只能上国内的代理照样会回 200。
                //
                // 所以现在加了真实 TLS 握手，这个「只答应不转发」的假代理
                // **必须被判为不通**。断言跟着反过来。
                judge("**假代理只回 200 但不真转发 → 必须判为不通**（这是新逻辑的价值）",
                        !p.getOk(), p.getDetail());
                judge("而且说明里点出了「答应了但连不通」",
                        p.getDetail().contains("答应") || p.getDetail().contains("连不通"),
                        p.getDetail());
            } finally {
                fake.close();
            }

            // 走一个必然没开的代理 → 必须报失败（不能默认成功）
            GitProxyDetector.Probe bad = GitProxyDetector.INSTANCE.testThroughProxy(
                    "http://127.0.0.1:19997", "github.com", 800);
            System.out.println("   死代理 → " + bad.getOk() + "：" + bad.getDetail());
            judge("**连不上时必须报失败**（不能默认成功）", !bad.getOk(), bad.getDetail());
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}