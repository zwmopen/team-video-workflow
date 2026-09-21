package com.zwm.gallery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the machine-readable multi-platform copy format while keeping legacy TXT usable. */
final class PlatformCopyParser {
    private static final String HEADER_V2 = "<<<COPY_FORMAT:2>>>";
    private static final String HEADER_V3 = "<<<COPY_FORMAT:3>>>";
    private static final String HEADER_MULTI = "<<<COPY_FORMAT:MULTI>>>";
    /** 匹配任意协议标记（COPY_FORMAT / _START / _END / VERSION_START 等）。
     *
     * <p>刻意写成 `&lt;&lt;+[^&lt;&gt;]*&gt;&gt;+`（开头 2+ 个 `&lt;`、结尾 2+ 个 `&gt;`）：
     * 磁盘上确实存在**畸形标记** —— Codex 产线把 `&lt;&lt;&lt;DOUYIN_END&gt;&gt;&gt;` 写成了
     * `&lt;&lt;&lt;DOUYIN_END&gt;&gt;`（少一个 `&gt;`，实测 9 份）。要求正好三个 `&gt;` 的正则识别不了它。
     */
    private static final Pattern ANY_MARKER_PATTERN = Pattern.compile("<<+[^<>]*>>+");
    private PlatformCopyParser() { }

    private static final Pattern GENERIC_BLOCK_PATTERN =
            Pattern.compile("(?s)<<<([A-Za-z0-9_\u4e00-\u9fa5]+)_START>>>[\\r\\n]*(.*?)[\\r\\n]*<<<\\1_END>>>");
    private static final Pattern VERSION_BLOCK_PATTERN =
            Pattern.compile("(?s)<<<VERSION_START:\\s*([^>\\r\\n]+?)\\s*>>>[\\r\\n]*(.*?)[\\r\\n]*<<<VERSION_END>>>");

    enum Platform {
        DOUYIN("规避营销版", "DOUYIN", "douyin", "规避营销版"),
        XHS("种草版", "XHS", "xhs", "种草版"),
        XHS_2("大纲方案版", "XHS_2", "xhs2", "大纲方案版"),
        XHS_3("短文精选版", "XHS_3", "xhs3", "短文精选版"),
        WECHAT("公众号版", "WECHAT", "wechat", "公众号版"),
        HR("HR决策版", "HR", "hr", "HR决策版"),
        GENERAL("参考文案", "GENERAL", "general", "参考文案");

        final String displayName;
        final String marker;
        final String code;
        final String shortLabel;
        final Pattern pattern;

        Platform(String displayName, String marker, String code, String shortLabel) {
            this.displayName = displayName;
            this.marker = marker;
            this.code = code;
            this.shortLabel = shortLabel;
            this.pattern = Pattern.compile("(?s)<<<" + marker + "_START>>>[\\r\\n]*(.*?)[\\r\\n]*<<<"
                    + marker + "_END>>>");
        }
    }

    enum Status { OK, MISSING, UNREADABLE }

    static final class AvailableItem {
        final Platform platform;
        final String buttonLabel;
        final String copyText;

        AvailableItem(Platform platform, String buttonLabel, String copyText) {
            this.platform = platform;
            this.buttonLabel = buttonLabel;
            this.copyText = copyText;
        }
    }

    static String friendlyLabelForMarker(String marker) {
        if (marker == null || marker.trim().isEmpty()) return "参考文案";
        String trimmed = marker.trim();
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if ("XHS_3".equals(upper)) return "短文精选版";
        if ("WECHAT".equals(upper)) return "公众号版";
        if ("HR".equals(upper)) return "HR决策版";
        if (upper.startsWith("VERSION_") || upper.startsWith("V_")) {
            return "版本 " + upper.substring(upper.indexOf('_') + 1);
        }
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        if (trimmed.endsWith("版") && trimmed.length() == 5) {
            return trimmed.substring(0, 4);
        }
        return trimmed.length() > 4 ? trimmed.substring(0, 4) : trimmed;
    }

    static List<AvailableItem> parseAvailablePlatforms(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = stripBom(source);
        boolean isProtocol = text.contains(HEADER_V2) || text.contains(HEADER_V3)
                || text.contains(HEADER_MULTI) || text.contains("<<<VERSION_START:")
                || text.contains("_START>>>");
        if (!isProtocol) {
            // 【2026-09-21 修复】兜底文案也必须剥净 `<<<…>>>`，否则非标准标记会被原样带进剪贴板。
            return Collections.singletonList(new AvailableItem(Platform.XHS, "发布",
                    stripProtocolMarkers(text).trim()));
        }

        // Priority 1: Multi-version syntax (<<<VERSION_START:4字版本名>>> ... <<<VERSION_END>>>)
        List<AvailableItem> multiItems = new ArrayList<>();
        Matcher vMatcher = VERSION_BLOCK_PATTERN.matcher(text);
        while (vMatcher.find()) {
            String vname = vMatcher.group(1).trim();
            String content = stripProtocolMarkers(stripOuterLineBreaks(vMatcher.group(2)));
            if (!content.trim().isEmpty()) {
                String label = friendlyLabelForMarker(vname);
                Platform plat = ("抖音避坑".equals(label) || label.contains("抖音")) ? Platform.DOUYIN : Platform.GENERAL;
                multiItems.add(new AvailableItem(plat, label, content));
            }
        }
        if (!multiItems.isEmpty()) {
            return multiItems;
        }

        List<AvailableItem> items = new ArrayList<>();
        Result douyinRes = parse(text, Platform.DOUYIN);
        Result xhsRes = parse(text, Platform.XHS);
        Result xhs2Res = parse(text, Platform.XHS_2);

        boolean hasXhs2 = xhs2Res.isOk();
        if (douyinRes.isOk()) {
            items.add(new AvailableItem(Platform.DOUYIN, "规避营销版", douyinRes.text));
        }
        if (xhsRes.isOk()) {
            items.add(new AvailableItem(Platform.XHS, "种草版", xhsRes.text));
        }
        if (hasXhs2) {
            items.add(new AvailableItem(Platform.XHS_2, "大纲方案版", xhs2Res.text));
        }

        // Additional known platforms
        Result xhs3Res = parse(text, Platform.XHS_3);
        if (xhs3Res.isOk()) {
            items.add(new AvailableItem(Platform.XHS_3, Platform.XHS_3.displayName, xhs3Res.text));
        }
        Result wechatRes = parse(text, Platform.WECHAT);
        if (wechatRes.isOk()) {
            items.add(new AvailableItem(Platform.WECHAT, Platform.WECHAT.displayName, wechatRes.text));
        }
        Result hrRes = parse(text, Platform.HR);
        if (hrRes.isOk()) {
            items.add(new AvailableItem(Platform.HR, Platform.HR.displayName, hrRes.text));
        }

        // Parse any dynamic / custom markers
        Matcher matcher = GENERIC_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String marker = matcher.group(1).trim();
            String upper = marker.toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("DOUYIN") || upper.equals("XHS") || upper.equals("XHS_2")
                    || upper.equals("XHS_3") || upper.equals("WECHAT") || upper.equals("HR")) {
                continue;
            }
            String content = stripProtocolMarkers(stripOuterLineBreaks(matcher.group(2)));
            if (!content.trim().isEmpty()) {
                String label = friendlyLabelForMarker(marker);
                items.add(new AvailableItem(Platform.GENERAL, label, content));
            }
        }

        if (items.isEmpty()) {
            // 【2026-09-21 修复】Codex 产线会产出「伪协议」文案：带 `<<<COPY_FORMAT:3>>>` 头，
            // 正文却用 `【小红书自然种草版】` 之类中文标题分节、没有任何 `<<<XHS_START>>>` 标记。
            // 此时落到这里，原先直接 `text.trim()` ⇒ 把 `<<<COPY_FORMAT:3>>>` 原样带进剪贴板
            // （实测 46 份作品命中）。剥净标记后再兜底。
            return Collections.singletonList(new AvailableItem(Platform.XHS, "发布",
                    stripProtocolMarkers(text).trim()));
        }
        return items;
    }

    /** 文本里是否含**任意** `<<<…>>>` 协议标记。
     *
     * <p>用来区分「旧版纯文案」（完全无标记，可按平台原样分发）与「协议文本但缺少该平台块」
     * ——后者必须报缺失，绝不能把整篇原文塞给用户。
     */
    static boolean hasAnyProtocolMarker(String source) {
        return source != null && ANY_MARKER_PATTERN.matcher(source).find();
    }

    /** 是否含 V4.5 多版本块（`<<<COPY_FORMAT:MULTI>>>` 或 `<<<VERSION_START:…>>>`）。 */
    static boolean hasMultiVersionBlocks(String source) {
        if (source == null) return false;
        return source.contains(HEADER_MULTI) || source.contains("<<<VERSION_START:");
    }

    /** 剥掉全部 `<<<…>>>` 协议标记并收紧尾部换行，供兜底文案使用。 */
    static String stripProtocolMarkers(String source) {
        if (source == null) return "";
        String clean = ANY_MARKER_PATTERN.matcher(stripBom(source)).replaceAll("");
        int end = clean.length();
        while (end > 0) {
            char c = clean.charAt(end - 1);
            if (c == '\n' || c == '\r') end--; else break;
        }
        return clean.substring(0, end);
    }

    static String extractPlatformCopy(String source, Platform platform) {
        Result res = parse(source, platform);
        return res.isOk() ? res.text : "";
    }

    static Result parse(String source, Platform platform) {
        if (source == null) return new Result(Status.UNREADABLE, "");
        String text = stripBom(source);
        // 【2026-09-21 修复】此前只要不含 V2/V3 头、也不含该平台固定标记，就无条件把
        // **整篇原文**当成该平台文案返回：V4.5 多版本（`<<<COPY_FORMAT:MULTI>>>`）正是这种
        // 形态 ⇒ 点任何平台按钮后，剪贴板里是含全部 `<<<VERSION_START:…>>>` 标记的整份原文
        // （实测 80 份作品命中）。现在只有「完全不含任何协议标记」的旧版纯文案才原样返回。
        if (!text.contains(HEADER_V2) && !text.contains(HEADER_V3)
                && !text.contains("<<<" + platform.marker + "_START>>>")
                && !hasAnyProtocolMarker(text)) {
            return new Result(text.trim().isEmpty() ? Status.UNREADABLE : Status.OK, text);
        }
        Matcher matcher = platform.pattern.matcher(text);
        if (!matcher.find()) {
            String marker = platform.marker;
            if (text.contains("<<<" + marker + "_START>>>")
                    || text.contains("<<<" + marker + "_END>>>")) {
                return new Result(Status.UNREADABLE, "");
            }
            boolean hasOther = false;
            for (Platform other : Platform.values()) {
                if (other != platform && (text.contains("<<<" + other.marker + "_START>>>")
                        || text.contains("<<<" + other.marker + "_END>>>"))) {
                    hasOther = true;
                    break;
                }
            }
            if (!hasOther) {
                return new Result(Status.UNREADABLE, "");
            }
            return new Result(Status.MISSING, "");
        }
        String value = stripOuterLineBreaks(matcher.group(1));
        if (value.trim().isEmpty()) return new Result(Status.UNREADABLE, "");
        // 【2026-09-21 修复】不能假设磁盘上的标记一定规范：已发现 Codex 产线把结束标记
        // 写成 `<<<DOUYIN_END>>`（只有两个 `>`），会被正则当成正文吞进来。
        // 取出的正文再净化一次，保证剪贴板里永远不出现 `<<<…>>`。
        return new Result(Status.OK, stripProtocolMarkers(value));
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String stripOuterLineBreaks(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '\r' || value.charAt(start) == '\n')) start++;
        while (end > start && (value.charAt(end - 1) == '\r' || value.charAt(end - 1) == '\n')) end--;
        return value.substring(start, end);
    }

    static String synthesizeDouyinCopy(String source) {
        if (source == null || source.trim().isEmpty()) return "";
        String clean = stripBom(source);
        clean = clean.replaceAll("(?s)<<<[A-Za-z0-9_]+_START>>>", "");
        clean = clean.replaceAll("(?s)<<<[A-Za-z0-9_]+_END>>>", "");
        clean = clean.replace(HEADER_V2, "").replace(HEADER_V3, "");
        // 兜底：连同 MULTI 头、VERSION_START/END、任意自定义标记一并剥净。
        clean = ANY_MARKER_PATTERN.matcher(clean).replaceAll("");
        // Remove hashtag topic tags commonly used in XHS
        clean = clean.replaceAll("#[^\\s#]+", "");
        // Remove XHS emoji tags like [打卡R] etc
        clean = clean.replaceAll("\\[[^\\]]+R\\]", "");
        clean = clean.trim();
        return clean.isEmpty() ? source.trim() : clean;
    }

    static String synthesizeOutlineCopy(String source) {
        if (source == null || source.trim().isEmpty()) return "";
        String clean = stripBom(source);
        clean = clean.replaceAll("(?s)<<<[A-Za-z0-9_]+_START>>>", "");
        clean = clean.replaceAll("(?s)<<<[A-Za-z0-9_]+_END>>>", "");
        clean = clean.replace(HEADER_V2, "").replace(HEADER_V3, "");
        clean = ANY_MARKER_PATTERN.matcher(clean).replaceAll("").trim();
        return clean.isEmpty() ? source.trim() : clean;
    }

    static final class Result {
        final Status status;
        final String text;

        Result(Status status, String text) {
            this.status = status;
            this.text = text;
        }

        boolean isOk() { return status == Status.OK; }
    }
}
