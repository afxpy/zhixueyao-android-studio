import com.zhixueyao.mcp.StdioTransport;
import com.zhixueyao.mcp.StreamableHttpTransport;
import com.zhixueyao.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 连接的状态一致性。
 *
 * ## 问题的形状
 *
 * 服务器进程会**自己死掉**（崩溃、被系统回收）。而 `McpManager.live` 里的记录
 * **不会自动消失**，于是出现「界面在说谎」：
 *
 *  - `isConnected(id)` 只查 map → 仍说「已连接」
 *  - `stats()` 仍报「3 个服务器、35 个工具」
 *  - `allTools()` **仍把死服务器的工具发给模型**，模型会反复去调
 *
 * 修法的核心是「已连接 = map 里有记录 **且** 传输还活着」，
 * 而这条又依赖一个前提：**进程死掉时 `isAlive()` 真的会变 false**。
 *
 * ## 这个探针就验那个前提
 *
 * 前半段验「进程退出 → isAlive 转 false」（整个修复的地基），
 * 后半段验「转 false 之后调用会**快速失败**而不是等满 2 分钟超时」——
 * 后者是个体验指标：等 2 分钟和立刻报错，用户感受完全不同。
 *
 * ## 我差点报了个不存在的 bug
 *
 * 一开始 grep `alive.set` 只看到 `close()` 里有一处，就认定
 * 「进程崩了之后 alive 还是 true」。
 *
 * 实际上读流线程的 `finally` 里调了 `failAllPending`，而**它会置 false**。
 * 追下去才发现真正的缺口在 `McpManager` 那一层（map 不清理），不在传输层。
 *
 * **教训：`grep` 只能告诉你符号在哪，不能告诉你控制流怎么走。**
 * 判定「某个状态有没有被维护」必须顺着调用链读完整。
 */
public class McpStateProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    static StdioTransport stdio(List<String> cmd) {
        return new StdioTransport(cmd, new java.util.HashMap<>(), null);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MCP 连接状态一致性 ===");

        // ---------- ① 进程退出 → isAlive 必须转 false ----------
        System.out.println();
        System.out.println("--- ① 地基：进程退出后 isAlive() 要转 false ---");
        {
            // 一个立刻退出的「服务器」—— 模拟进程自己崩掉
            StdioTransport t = stdio(List.of("cmd", "/c", "exit 0"));
            t.start();
            judge("启动后短暂为活", t.isAlive() || true, "（时序相关，不作硬断言）");

            // 等读流线程发现 EOF 并跑完 finally
            boolean turnedFalse = false;
            for (int i = 0; i < 30; i++) {
                Thread.sleep(100);
                if (!t.isAlive()) { turnedFalse = true; break; }
            }
            System.out.println("   进程退出后 isAlive() = " + t.isAlive());
            judge("**进程退出后 isAlive() 转为 false**", turnedFalse,
                    turnedFalse ? "" : "一直说还活着 —— 那么界面就会一直说谎");
        }

        // ---------- ② 转 false 之后要快速失败，不是等满超时 ----------
        System.out.println();
        System.out.println("--- ② 已断开时调用要快速失败（而不是等满 2 分钟）---");
        {
            StdioTransport t = stdio(List.of("cmd", "/c", "exit 0"));
            t.start();
            for (int i = 0; i < 30 && t.isAlive(); i++) Thread.sleep(100);
            judge("前置：已转为不活", !t.isAlive(), "");

            long t0 = System.currentTimeMillis();
            String msg = null;
            try {
                t.exchange(emptyMsg(), 120_000L, null);
            } catch (Exception e) {
                msg = e.getMessage();
            }
            long ms = System.currentTimeMillis() - t0;
            System.out.println("   抛错耗时 " + ms + "ms，信息：" + msg);
            judge("确实抛了异常", msg != null, "");
            judge("**在 2 秒内失败**（不是等满 120 秒超时）", ms < 2000, "耗时 " + ms + "ms");
            judge("错误信息说明了原因",
                    msg != null && (msg.contains("未运行") || msg.contains("断开") || msg.contains("退出")),
                    msg);
        }

        // ---------- ③ close 之后必须立即 not alive ----------
        System.out.println();
        System.out.println("--- ③ close() 之后立刻不是活的 ---");
        {
            StdioTransport t = stdio(List.of("cmd", "/c", "timeout /t 30 /nobreak > nul"));
            t.start();
            Thread.sleep(300);
            t.close();
            judge("close 后 isAlive() = false", !t.isAlive(), "");
            judge("重复 close 不抛异常", closeTwice(t), "");
        }

        // ---------- ④ HTTP 传输：closed 标记必须在提前 return 之前置位 ----------
        System.out.println();
        System.out.println("--- ④ HTTP 传输的 closed 标记 ---");
        {
            StreamableHttpTransport h = new StreamableHttpTransport("http://127.0.0.1:1/nope",
                    java.util.Collections.emptyMap());
            judge("未关闭时算活", h.isAlive(), "");

            // 关键：这个传输**从没握过手**（sessionId == null），
            // 而 close() 里有 `val sid = sessionId ?: return` 的提前返回。
            // 如果 closed = true 写在 return 之后，这里就会永远说「还活着」。
            h.close();
            judge("**没握过手就直接 close，也要变成不活**", !h.isAlive(),
                    h.isAlive() ? "提前 return 把标记跳过去了" : "");
        }

        // ---------- ⑤ 边界 ----------
        System.out.println();
        System.out.println("--- ⑤ 边界 ---");
        {
            // 不 start 就 close
            try {
                StdioTransport t = stdio(List.of("cmd", "/c", "exit 0"));
                t.close();
                judge("没启动就 close 不抛异常", true, "");
                judge("没启动时 isAlive() = false", !t.isAlive(), "");
            } catch (Exception e) {
                judge("没启动就 close 不抛异常", false, e.toString());
            }

            // 命令不存在：start 要抛清晰错误（而不是留个半启动状态）
            try {
                StdioTransport bad = stdio(List.of("这个命令肯定不存在-zzz"));
                bad.start();
                judge("不存在的命令要抛异常", false, "居然没抛");
            } catch (Exception e) {
                judge("不存在的命令抛清晰异常", e.getMessage() != null
                        && e.getMessage().contains("无法启动"), e.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }

    static Json.Obj emptyMsg() {
        return com.zhixueyao.util.JsonKt.jsonObj(
                new kotlin.Pair<>("jsonrpc", "2.0"),
                new kotlin.Pair<>("id", 1L),
                new kotlin.Pair<>("method", "tools/list"));
    }

    static boolean closeTwice(StdioTransport t) {
        try {
            t.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
