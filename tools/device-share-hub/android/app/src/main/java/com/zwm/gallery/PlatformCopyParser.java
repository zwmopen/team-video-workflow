package com.zwm.gallery;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the machine-readable multi-platform copy format while keeping legacy TXT usable. */
final class PlatformCopyParser {
    private static final String HEADER = "<<<COPY_FORMAT:2>>>";
    private PlatformCopyParser() { }

    enum Platform {
        XHS("小红书", "XHS"),
        DOUYIN("抖音", "DOUYIN");

        final String displayName;
        final Pattern pattern;

        Platform(String displayName, String marker) {
            this.displayName = displayName;
            this.pattern = Pattern.compile("(?s)<<<" + marker + "_START>>>[\\r\\n]*(.*?)[\\r\\n]*<<<"
                    + marker + "_END>>>");
        }
    }

    enum Status { OK, MISSING, UNREADABLE }

    static Result parse(String source, Platform platform) {
        if (source == null) return new Result(Status.UNREADABLE, "");
        String text = stripBom(source);
        if (!text.contains(HEADER)) {
            return new Result(text.trim().isEmpty() ? Status.UNREADABLE : Status.OK, text);
        }
        Matcher matcher = platform.pattern.matcher(text);
        if (!matcher.find()) {
            String marker = platform == Platform.XHS ? "XHS" : "DOUYIN";
            if (text.contains("<<<" + marker + "_START>>>")
                    || text.contains("<<<" + marker + "_END>>>")) {
                return new Result(Status.UNREADABLE, "");
            }
            String other = platform == Platform.XHS ? "DOUYIN" : "XHS";
            if (!text.contains("<<<" + other + "_START>>>")
                    && !text.contains("<<<" + other + "_END>>>")) {
                return new Result(Status.UNREADABLE, "");
            }
            // A well-formed other-platform section is not a fallback for this platform.
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
