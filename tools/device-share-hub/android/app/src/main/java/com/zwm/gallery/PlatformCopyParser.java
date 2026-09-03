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

    enum Platform {
        DOUYIN("规避营销版", "DOUYIN", "douyin", "规避营销版"),
        XHS("种草版", "XHS", "xhs", "种草版"),
        XHS_2("大纲方案版", "XHS_2", "xhs2", "大纲方案版");

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

    static List<AvailableItem> parseAvailablePlatforms(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = stripBom(source);
        boolean isProtocol = text.contains(HEADER_V2) || text.contains(HEADER_V3)
                || text.contains("<<<XHS_START>>>") || text.contains("<<<DOUYIN_START>>>")
                || text.contains("<<<XHS_2_START>>>");
        if (!isProtocol) {
            return Collections.singletonList(new AvailableItem(Platform.XHS, "发小红书", text.trim()));
        }

        List<AvailableItem> items = new ArrayList<>();
        Result douyinRes = parse(text, Platform.DOUYIN);
        Result xhsRes = parse(text, Platform.XHS);
        Result xhs2Res = parse(text, Platform.XHS_2);

        boolean hasXhs2 = xhs2Res.isOk();
        if (douyinRes.isOk()) {
            String label = hasXhs2 ? "规避营销版" : "发抖音";
            items.add(new AvailableItem(Platform.DOUYIN, label, douyinRes.text));
        }
        if (xhsRes.isOk()) {
            String label = hasXhs2 ? "种草版" : "发小红书";
            items.add(new AvailableItem(Platform.XHS, label, xhsRes.text));
        }
        if (hasXhs2) {
            items.add(new AvailableItem(Platform.XHS_2, "大纲方案版", xhs2Res.text));
        }
        return items;
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
