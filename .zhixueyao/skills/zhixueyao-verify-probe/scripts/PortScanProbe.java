import java.io.*;
import java.net.*;
import java.util.*;

/**
 * 探测本机到底哪个端口能当 HTTP 代理用。
 *
 * 用户说他用的 VPN 是 `D:\speedcloud`，端口「找到的是 7832」——
 * 但 7832 当时没有在监听。而实际扫出来在听的是 7892 / 12824 / 18488 / 8554。
 *
 * 这个探针把每个端口都**真的发一次 CONNECT 请求**（不是只看端口开没开），
 * 直接问出结论 —— 比在界面里点「测试连接」再等快得多，而且能看到中间过程。
 */
public class PortScanProbe {

    static String probe(int port, int timeoutMs) {
        // 第 1 步：连得上吗
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
        } catch (Exception e) {
            return "端口没开（" + e.getClass().getSimpleName() + "）";
        }
        // 第 2 步：发 CONNECT，看它是不是代理
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write(
                    ("CONNECT github.com:443 HTTP/1.1\r\nHost: github.com:443\r\n" +
                            "Proxy-Connection: keep-alive\r\n\r\n").getBytes("US-ASCII"));
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "US-ASCII"));
            String line = r.readLine();
            if (line == null) return "端口开着，但没回应";
            if (line.contains(" 200")) return "**是 HTTP 代理**：" + line;
            if (line.contains(" 407")) return "是代理但需要认证：" + line;
            if (line.startsWith("HTTP/")) return "端口开着，但拒绝代理请求：" + line;
            return "端口开着，不像代理（回应：" + line.substring(0, Math.min(40, line.length())) + "）";
        } catch (Exception e) {
            return "端口开着，探测出错：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        int[] candidates = { 7832, 7890, 7891, 7892, 7897, 10809, 10808, 1080, 12824, 18488, 8554, 7893 };
        System.out.println("=== 逐个探测（真的发 CONNECT 请求）===");
        System.out.println();
        List<Integer> isProxy = new ArrayList<>();
        for (int p : candidates) {
            String r = probe(p, 900);
            String mark = r.contains("是 HTTP 代理") ? "★" : (r.startsWith("端口没开") ? " " : "?");
            System.out.printf("  %s %-6d %s%n", mark, p, r);
            if (r.contains("**是 HTTP 代理**")) isProxy.add(p);
        }

        System.out.println();
        if (isProxy.isEmpty()) {
            System.out.println("没有找到可用的代理端口。");
            System.out.println("（用户提到的 7832 不在监听 —— 可能客户端没开，或端口记错了）");
        } else {
            System.out.println("可用的代理端口：" + isProxy);
            // 用找到的那个真的去连一次 github
            for (int p : isProxy) {
                System.out.println();
                System.out.println("--- 用 " + p + " 连一次 github.com:443 ---");
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", p), 8000);
                    s.setSoTimeout(8000);
                    s.getOutputStream().write(
                            ("CONNECT github.com:443 HTTP/1.1\r\nHost: github.com:443\r\n\r\n")
                                    .getBytes("US-ASCII"));
                    s.getOutputStream().flush();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "US-ASCII"));
                    System.out.println("  回应：" + r.readLine());
                } catch (Exception e) {
                    System.out.println("  失败：" + e);
                }
            }
        }
    }
}
