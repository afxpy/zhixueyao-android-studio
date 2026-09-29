import com.zhixueyao.git.GitProxyDetector;

import java.util.List;

/**
 * 动态端口发现能不能找到用户那个 7892。
 *
 * ## 背景
 *
 * 用户用的是 `D:\speedcloud`，**代理端口 7892**。
 * 我原来硬编码的列表是 `7890, 7897, 7891, 10809, …` —— **就差一个数字**，
 * 于是怎么扫都扫不到，界面一直「检测不到」，而他代理明明开着、也真能连上 github。
 *
 * 所以改成了「先问系统哪些端口在监听」。这个探针验证那件事真的管用。
 */
public class DynamicPortProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        System.out.println("=== 动态端口发现 ===");
        System.out.println();

        System.out.println("--- ① 系统里有哪些端口在监听 ---");
        List<Integer> listening = GitProxyDetector.INSTANCE.listListeningPorts();
        System.out.println("   共 " + listening.size() + " 个：" + listening);
        judge("**问到了系统监听端口**（不是拿到了空列表）", !listening.isEmpty(),
                listening.isEmpty() ? "netstat 没解析出来 —— 过滤规则可能不对" : "");
        judge("过滤掉了 1023 以下的系统端口",
                listening.stream().allMatch(p -> p > 1023), "");
        judge("没有重复", listening.size() == listening.stream().distinct().count(), "");

        System.out.println();
        System.out.println("--- ② 自动检测能不能找到代理 ---");
        long t0 = System.currentTimeMillis();
        GitProxyDetector.Detection d = GitProxyDetector.INSTANCE.detectLocalProxy(
                java.util.Collections.emptyList(), 500);
        long ms = System.currentTimeMillis() - t0;

        System.out.println("   耗时 " + ms + " ms");
        System.out.println("   找到：" + d.getFound() + "　端口：" + d.getPort() + "　地址：" + d.getProxyUrl());
        judge("**找到了代理**", d.getFound(),
                d.getFound() ? ("端口 " + d.getPort()) : "没找到 —— 用户的代理可能没开，或者发现逻辑有问题");
        judge("**耗时在 3 秒内**（并发扫描的意义）", ms < 3000, ms + " ms");

        if (d.getFound()) {
            judge("命中的不是用户填的（说明是自动发现的）", !d.getFromUserInput(), "");
            System.out.println();
            System.out.println("--- ③ 用找到的代理真的连一次 github ---");
            GitProxyDetector.Probe t = GitProxyDetector.INSTANCE.testThroughProxy(
                    d.getProxyUrl(), "github.com", 8000);
            System.out.println("   " + t.getOk() + "：" + t.getDetail());
            judge("**通过它真能连上 github**（这才叫「可用」）", t.getOk(), t.getDetail());
        } else {
            System.out.println();
            System.out.println("   没找到就不验第 ③ 步 —— 但**不因此算通过**，");
            System.out.println("   否则会掩盖「发现逻辑坏了」这件事。");
            fail++;
        }

        System.out.println();
        System.out.println("--- ④ 扫了哪些端口（用户要能看懂）---");
        System.out.println("   共 " + d.getScanned().size() + " 条");
        d.getScanned().stream().limit(8).forEach(p ->
                System.out.println("     " + p.getPort() + " → " + p.getDetail()));
        judge("每个端口都有说明", d.getScanned().stream()
                .allMatch(p -> p.getDetail() != null && !p.getDetail().isEmpty()), "");

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
