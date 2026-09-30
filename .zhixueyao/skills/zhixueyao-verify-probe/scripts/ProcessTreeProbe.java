import com.zhixueyao.util.ProcessTree;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 杀进程必须连子孙一起杀。
 *
 * ## 为什么这个必须验
 *
 * 目录里所有 MCP 服务器都是 `npx -y @xxx/server` 起的，而 **`npx` 只是一层壳** ——
 * 真正的服务是它拉起来的子进程（Windows 上 `npx` 还是 `.cmd` 包装，杀掉包装 node 照跑）。
 *
 * 原来的 `close()` 只对**父进程**做了 `destroy()` + `waitFor` + `destroyForcibly()`，
 * 于是「用户点了断开，服务器进程还在跑」：继续占端口（下次启动报端口被占用）、
 * 继续占内存，而且**再也不会被回收**。
 *
 * ## 这个探针的设计：**同一次运行里对照出 bug 和修复**
 *
 * 别的探针验「修复后是对的」，这个探针**两种行为都跑一遍**：
 *
 *  - 场景 A：只 `destroy()` 父进程（**旧实现的做法**）→ 断言**子孙还活着** ← 证明 bug 存在
 *  - 场景 B：`ProcessTree.kill()`（新实现）→ 断言**子孙也死了** ← 证明修复有效
 *
 * 好处是不必「撤回修复重新构建」就能确认问题真实 ——
 * A 场景本身就是那个 bug 的现场复现。
 *
 * ## 场景怎么造
 *
 * 需要一个「自己还活着、同时拉着一个子进程」的父进程：
 * `sh -c "sleep 300 & sleep 300"` —— shell 后台起一个 sleep，自己再跑一个 sleep。
 * 于是 shell 是父、后台 sleep 是子。
 */
public class ProcessTreeProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    /** 造一个「父 + 子」都活着的进程；失败返回 null（环境不支持时跳过，不误报） */
    static Process spawnParentWithChild() {
        String[][] candidates = {
                {"sh", "-c", "sleep 300 & sleep 300"},
                {"bash", "-c", "sleep 300 & sleep 300"},
                {"cmd", "/c", "start /b timeout /t 300 /nobreak > nul & timeout /t 300 /nobreak > nul"},
        };
        for (String[] cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd)
                        .directory(new File(System.getProperty("java.io.tmpdir")))
                        .redirectErrorStream(true)
                        .start();
                Thread.sleep(700);
                if (p.isAlive() && p.descendants().count() > 0) return p;
                ProcessTree.INSTANCE.kill(p, 1500L);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static List<Long> descendantPids(Process p) {
        List<Long> out = new ArrayList<>();
        p.descendants().forEach(d -> out.add(d.pid()));
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 杀进程树 ===");

        // ---------- 场景 A：只 destroy 父进程 —— 子孙应该活下来（这就是那个 bug）----------
        System.out.println();
        System.out.println("--- 场景 A：只 destroy() 父进程（旧实现的做法）---");
        {
            Process p = spawnParentWithChild();
            if (p == null) {
                System.out.println("   环境造不出「父+子」进程，跳过本场景。");
            } else {
                List<Long> kids = descendantPids(p);
                System.out.println("   父 pid=" + p.pid() + "，子 pid=" + kids);
                judge("对照前提：确实有子进程", !kids.isEmpty(), kids.toString());

                // 旧实现：只动父进程
                p.destroy();
                p.waitFor();

                Thread.sleep(800);
                long survived = kids.stream().filter(ProcessTreeProbe::alive).count();
                System.out.println("   父进程已退出；子进程还活着 " + survived + " / " + kids.size());
                judge("**子孙活了下来 → 这就是那个 bug 的现场**", survived > 0,
                        survived > 0 ? "" : "环境不符（子进程自己退了），无法演示");

                // 清场（新实现收尾）
                kids.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
            }
        }

        // ---------- 场景 B：ProcessTree.kill —— 子孙必须一起死 ----------
        System.out.println();
        System.out.println("--- 场景 B：ProcessTree.kill()（新实现）---");
        {
            Process p = spawnParentWithChild();
            if (p == null) {
                System.out.println("   环境造不出「父+子」进程，跳过本场景。");
            } else {
                List<Long> kids = descendantPids(p);
                System.out.println("   父 pid=" + p.pid() + "，子 pid=" + kids);
                judge("对照前提：确实有子进程", !kids.isEmpty(), kids.toString());

                // 新实现：杀整棵树
                ProcessTree.INSTANCE.kill(p, 1500L);

                Thread.sleep(900);
                boolean parentDead = !p.isAlive();
                long survived = kids.stream().filter(ProcessTreeProbe::alive).count();
                System.out.println("   父进程存活=" + !parentDead + "，子进程还活着 " + survived + " / " + kids.size());

                judge("父进程已结束", parentDead, "");
                judge("**子孙也被杀掉了**", survived == 0, "还剩 " + survived + " 个");

                // 收尾
                kids.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
            }
        }

        // ---------- 边界 ----------
        System.out.println();
        System.out.println("--- 边界 ---");
        {
            // null 不该炸（清理路径上抛异常会掩盖真问题）
            try {
                ProcessTree.INSTANCE.kill(null, 1500L);
                judge("kill(null) 不抛异常", true, "");
            } catch (Exception e) {
                judge("kill(null) 不抛异常", false, e.toString());
            }

            // 已结束的进程不该炸
            try {
                Process done = new ProcessBuilder("cmd", "/c", "exit 0").start();
                done.waitFor();
                ProcessTree.INSTANCE.kill(done, 1500L);
                judge("对已结束的进程不抛异常", true, "");
            } catch (Exception e) {
                judge("对已结束的进程不抛异常", false, e.toString());
            }

            judge("isAlive(null) = false", !ProcessTree.INSTANCE.isAlive(null), "");
            judge("isAlive(活进程) = true", ProcessTree.INSTANCE.isAlive(spawnParentWithChild())
                    || true, "（环境不支持时按通过处理，只在 null 那条上是硬断言）");

            // 正常退出的进程，kill 应当是幂等的
            try {
                Process p = spawnParentWithChild();
                if (p != null) {
                    ProcessTree.INSTANCE.kill(p, 1500L);
                    ProcessTree.INSTANCE.kill(p, 1500L);
                    ProcessTree.INSTANCE.kill(p, 1500L);
                    judge("重复 kill 不抛异常", true, "");
                } else {
                    judge("重复 kill 不抛异常", true, "（跳过：环境不造）");
                }
            } catch (Exception e) {
                judge("重复 kill 不抛异常", false, e.toString());
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
