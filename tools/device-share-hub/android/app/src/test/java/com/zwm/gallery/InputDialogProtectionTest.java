package com.zwm.gallery;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 守卫：凡是「带输入框」的 AlertDialog，必须显式禁止点击背景关闭。
 *
 * <p>事故（BUG_LEDGER DSH-084）：Android 的 {@code AlertDialog} 默认
 * {@code setCanceledOnTouchOutside(true)}，用户在「删除并备注」的弹窗里打好字，
 * 手指蹭到弹窗外的界面，弹窗就关了，输入的内容全部丢失且没有任何提示。
 *
 * <p>用户的期望很明确：这类弹窗只应该有三处可点 —— 输入框、取消、确认；
 * 点外面不该有任何反应。
 *
 * <p>本用例直接扫描 {@code MainActivity} 源码，而不是跑 UI，
 * 因为这是一条「以后别再犯」的静态约束，UI 测试抓不到还没被写出来的新弹窗。
 * 判据：块内同时出现 {@code setView(}（自定义布局）和 {@code getText()}
 * （确实在读取用户输入）⇒ 必须出现 {@code setCanceledOnTouchOutside(false)}。
 * 只做展示的自定义布局（图片预览、进度条）不读 {@code getText()}，不受约束。
 */
public final class InputDialogProtectionTest {

    private static final Pattern BUILDER = Pattern.compile("new AlertDialog\\.Builder\\(");

    @Test
    public void dialogsThatReadUserInputMustNotCloseOnOutsideTouch() throws Exception {
        File source = findMainActivity();
        assertNotNull(
                "找不到 MainActivity.java —— 守卫无法自证。已尝试的工作目录："
                        + new File(System.getProperty("user.dir", ".")).getAbsolutePath()
                        + "。请在 Gradle test task 里保证 workingDir 能走到 app/ 或仓库根。",
                source);
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<String>();
        Matcher m = BUILDER.matcher(text);
        int scanned = 0;
        while (m.find()) {
            String block = readBuilderBlock(text, m.start());
            if (block == null) continue;
            scanned += 1;
            boolean customView = block.contains("setView(");
            boolean readsInput = block.contains("getText()");
            boolean protectedAlready = block.contains("setCanceledOnTouchOutside(false)")
                    || block.contains("setCancelable(false)");
            if (customView && readsInput && !protectedAlready) {
                offenders.add(describe(text, m.start()));
            }
        }

        assertTrue(
                "共扫描 " + scanned + " 处 AlertDialog，其中 " + offenders.size()
                        + " 处「带输入框却能被点击背景关闭」，会在用户误触时丢掉已输入的内容：\n  - "
                        + String.join("\n  - ", offenders)
                        + "\n修法：在创建弹窗后调用 setCanceledOnTouchOutside(false)（见 BUG_LEDGER DSH-084）。",
                offenders.isEmpty());
    }

    /**
     * 从 Builder 起点读到该弹窗的收尾 —— 不是读到 .create(); 就停。
     *
     * <p>踩过的坑：禁止误关的 {@code setCanceledOnTouchOutside(false)} 必须拿到
     * {@code Dialog} 实例之后才能调用，所以它**天然写在 .create(); 之后**。
     * 若把块边界画在 .create();，已保护的弹窗会被判成「未保护」，守卫变成常亮的噪声。
     * 因此这里继续向后吃到下一个成员声明之前，把收尾语句一起纳进来。
     */
    private static String readBuilderBlock(String text, int start) {
        Matcher term = Pattern.compile("\\.create\\(\\);|\\.show\\(\\);").matcher(text);
        if (!term.find(start)) return null;
        int end = term.end();
        int limit = Math.min(text.length(), end + 320);
        Matcher nextMember = Pattern.compile("\\n    (?:private|public|protected|static)").matcher(text);
        int stop = limit;
        nextMember.region(end, limit);
        // ⚠️ Matcher.start() 返回的是整个 text 里的绝对下标，不是相对 region 的偏移。
        // 写成 end + start() 会让块一路吞到文件尾，任何弹窗都能"蹭到"某个保护语句，
        // 守卫就永远不响 —— 这是自证时实测抓出来的假闸门，别再写回去。
        if (nextMember.find()) stop = nextMember.start();
        return text.substring(start, stop);
    }

    /** 给出可读定位：行号 + 弹窗标题，便于直接改。 */
    private static String describe(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line += 1;
        }
        String snippet = text.substring(offset, Math.min(text.length(), offset + 400));
        Matcher title = Pattern.compile("setTitle\\(\"([^\"]+)\"\\)").matcher(snippet);
        String label = title.find() ? title.group(1) : "（无标题）";
        return "第 " + line + " 行 " + label;
    }

    /** workingDir 可能是 app/、android/ 或仓库根，逐级向上试三种相对路径。 */
    private static File findMainActivity() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        String tail = "src/main/java/com/zwm/gallery/MainActivity.java";
        String[] candidates = {
                tail,
                "app/" + tail,
                "tools/device-share-hub/android/app/" + tail,
                "device-share-hub/android/app/" + tail,
        };
        for (int up = 0; up < 8 && dir != null; up++) {
            for (String candidate : candidates) {
                File hit = new File(dir, candidate);
                if (hit.isFile()) return hit;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
