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
    private PlatformCopyParser() { }

    private static final Pattern GENERIC_BLOCK_PATTERN =
            Pattern.compile("(?s)<<<([A-Za-z0-9_\u4e00-\u9fa5]+)_START>>>[\\r\\n]*(.*?)[\\r\\n]*<<<\\1_END>>>");

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
        String upper = marker.trim().toUpperCase(java.util.Locale.ROOT);
        if ("XHS_3".equals(upper)) return "短文精选版";
        if ("WECHAT".equals(upper)) return "公众号版";
        if ("HR".equals(upper)) return "HR决策版";
        if (upper.startsWith("VERSION_") || upper.startsWith("V_")) {
            return "版本 " + upper.substring(upper.indexOf('_') + 1);
        }
        if (marker.endsWith("版")) {
            return marker;
        }
        return marker + "版";
    }

    static List<AvailableItem> parseAvailablePlatforms(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = stripBom(source);
        boolean isProtocol = text.contains(HEADER_V2) || text.contains(HEADER_V3)
                || text.contains("_START>>>");
        if (!isProtocol) {
            return Collections.singletonList(new AvailableItem(Platform.XHS, "发布", text.trim()));
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
            String content = stripOuterLineBreaks(matcher.group(2));
            if (!content.trim().isEmpty()) {
                String label = friendlyLabelForMarker(marker);
                items.add(new AvailableItem(Platform.GENERAL, label, content));
            }
        }

        if (items.isEmpty()) {
            return Collections.singletonList(new AvailableItem(Platform.XHS, "发布", text.trim()));
        }
        return items;
    }

    static String extractPlatformCopy(String source, Platform platform) {
        Result res = parse(source, platform);
        return res.isOk() ? res.text : "";
    }

    static Result parse(String source, Platform platform) {
        if (source == null) return new Result(Status.UNREADABLE, "");
        String text = stripBom(source);
        if (!text.contains(HEADER_V2) && !text.contains(HEADER_V3)
                && !text.contains("<<<" + platform.marker + "_START>>>")) {
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
        return new Result(Status.OK, value);
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
        clean = clean.replace(HEADER_V2, "").replace(HEADER_V3, "").trim();
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
