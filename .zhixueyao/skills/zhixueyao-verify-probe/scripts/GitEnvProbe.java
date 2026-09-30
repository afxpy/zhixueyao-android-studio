import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.zhixueyao.git.GitEnvironment;

import java.util.List;

/**
 * Git 环境自检。
 *
 * ## 这个功能来自用户的一句话
 *
 * > 我都感觉可以直接内置脚本不需要 ai 了都直接就是填好信息检查设备环境配置等
 *
 * 他点破了我一直在做的事：**我给的是三个文本框让用户填，但新用户根本不知道
 * 自己缺什么**。填表的前提是「先知道要填什么」—— 而新手恰恰不知道。
 *
 * 自检把这件事反过来：我来查，查完告诉你、并给你怎么修。
 *
 * ## 探针要验什么
 *
 * 1. **五项检查都能跑完，不抛异常** —— 这是底线，一项炸了整块就空白
 * 2. **每项都有标题和非空说明** —— 只报错不说人话等于没报
 * 3. **有问题的项必须给出「怎么办」**（修复按钮或确切命令）——
 *    只报问题不给解法，用户还是得自己搜
 * 4. **在本机上的实际结论**打出来，人工核对是否符合事实
 *    （这台机器上 git 装了、署名配好了、代理配置里有个连不上的残留）
 */
public class GitEnvProbe {

    static int pass = 0, fail = 0;

    static void judge(String name, boolean ok, String detail) {
        System.out.println("   判定：" + (ok ? "OK" : "失败 ✗") + (detail.isEmpty() ? "" : "　" + detail));
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        Disposable root = () -> { };
        MockApplication app = new MockApplication(root);
        ApplicationManager.setApplication(app, root);

        System.out.println("=== Git 环境自检 ===");
        System.out.println();

        List<GitEnvironment.Check> results;
        try {
            results = GitEnvironment.INSTANCE.check(null);
        } catch (Throwable t) {
            System.out.println("   自检本身抛异常了 —— 那设置页会整块空白：");
            t.printStackTrace(System.out);
            System.exit(1);
            return;
        }

        judge("自检跑完没抛异常", true, "");
        judge("返回值不为空", results != null && !results.isEmpty(),
                "共 " + (results == null ? 0 : results.size()) + " 项");

        if (results == null || results.isEmpty()) { System.exit(1); }

        System.out.println();
        System.out.println("--- 本机的实际检查结果（人工核对是否符合事实）---");
        boolean allHaveTitle = true, allHaveDetail = true, problemsHaveFix = true;
        int okCount = 0, warnCount = 0, missCount = 0;

        for (GitEnvironment.Check c : results) {
            String mark = c.getLevel() == GitEnvironment.Level.OK ? "✓"
                    : c.getLevel() == GitEnvironment.Level.WARN ? "!" : "✗";
            System.out.println("   " + mark + " " + c.getTitle());
            System.out.println("       " + c.getDetail().replace("\n", "\n       "));
            if (c.getFixLabel() != null) System.out.println("       → [" + c.getFixLabel() + "]");
            if (c.getFixCommand() != null) System.out.println("       → 命令：" + c.getFixCommand());

            if (c.getTitle() == null || c.getTitle().isBlank()) allHaveTitle = false;
            if (c.getDetail() == null || c.getDetail().isBlank()) allHaveDetail = false;
            if (c.getLevel() != GitEnvironment.Level.OK
                    && c.getFixLabel() == null && c.getFixCommand() == null) {
                problemsHaveFix = false;
                System.out.println("       **这一项报了问题但没说怎么办**");
            }

            if (c.getLevel() == GitEnvironment.Level.OK) okCount++;
            else if (c.getLevel() == GitEnvironment.Level.WARN) warnCount++;
            else missCount++;
        }

        System.out.println();
        System.out.println("--- 断言 ---");
        judge("每一项都有标题", allHaveTitle, "");
        judge("每一项都有说明（只报状态不说人话等于没报）", allHaveDetail, "");
        judge("**每个有问题的项都给了「怎么办」**（按钮或确切命令）", problemsHaveFix,
                problemsHaveFix ? "" : "有项只报了问题没给解法");

        System.out.println("   统计：通过 " + okCount + "，注意 " + warnCount + "，缺失 " + missCount);

        // 本机必然成立的两条 —— 如果这两条不成立，说明探测逻辑坏了
        System.out.println();
        System.out.println("--- 对照：本机已知的事实 ---");
        boolean gitOk = results.stream().anyMatch(
                c -> c.getTitle().contains("Git 已安装") && c.getLevel() == GitEnvironment.Level.OK);
        judge("本机装了 git（对照：不是所有项都判失败）", gitOk,
                gitOk ? "" : "**这说明探测逻辑可能整个是坏的**");

        boolean sawAllFive = results.size() >= 5;
        judge("五项检查都在（git / 署名 / 凭据管理器 / 代理 / 连通性）", sawAllFive,
                "实际 " + results.size() + " 项");

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        System.out.println("结论：" + (fail == 0 ? "全部通过" : "有 " + fail + " 项不通过"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
