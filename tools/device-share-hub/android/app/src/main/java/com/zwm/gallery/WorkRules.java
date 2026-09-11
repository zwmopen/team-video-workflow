package com.zwm.gallery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class WorkRules {
    private static final String[] IMAGE_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"
    };
    private static final String[] CAPTION_FILE_NAMES = {
            "文案.txt", "小红书文案.txt", "抖音文案.txt"
    };
    private static final String[] METADATA_TEXT_FILE_NAMES = {
            "会话追踪.txt", "生产对话轨迹.txt", "生产记录.txt", "质量报告.txt", "作品标签.txt",
            "标签.txt", "元数据.txt", "metadata.txt", "manifest.txt",
            "日志.txt", "log.txt", "production-turns.txt", "session-tracking.txt"
    };
    private static final String[] METADATA_TEXT_PREFIXES = {
            "会话追踪", "生产对话轨迹", "生产记录", "质量报告", "作品标签", "标签", "元数据",
            "metadata", "manifest", "日志", "log", "production-turns", "session-tracking"
    };

    private WorkRules() {
    }

    static boolean isSupportedImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    static String chooseCaption(List<String> names) {
        for (String preferred : CAPTION_FILE_NAMES) {
            for (String name : names) {
                if (preferred.equalsIgnoreCase(name)) return name;
            }
        }
        ArrayList<String> textFiles = new ArrayList<>();
        for (String name : names) {
            if (isCaptionCandidate(name)) textFiles.add(name);
        }
        textFiles.sort(WorkRules::compareNatural);
        return textFiles.isEmpty() ? "" : textFiles.get(0);
    }

    static int countCaptionCandidates(List<String> names) {
        int count = 0;
        for (String name : names) if (isCaptionCandidate(name)) count++;
        return count;
    }

    static boolean isCaptionCandidate(String name) {
        if (name == null) return false;
        String normalized = name.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".txt")) return false;
        return !isMetadataTextName(normalized);
    }

    private static boolean isMetadataTextName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (String metadataName : METADATA_TEXT_FILE_NAMES) {
            if (metadataName.equalsIgnoreCase(normalized)) return true;
        }
        String stem = normalized.substring(0, normalized.length() - 4);
        for (String prefix : METADATA_TEXT_PREFIXES) {
            if (stem.equals(prefix) || stem.startsWith(prefix + "-") || stem.startsWith(prefix + "_")
                    || stem.startsWith(prefix + " ") || stem.startsWith(prefix + "(")
                    || stem.startsWith(prefix + "（")) return true;
        }
        return false;
    }

    /** Repairs only the unmistakable old workflow-summary shape. */
    static boolean isWorkflowMetadataText(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String normalized = text.replace("\uFEFF", "").replace('\r', '\n');
        String[] markers = {
                "母版URL", "母版 URL", "分支URL", "分支 URL",
                "执行账号", "生成卡片数", "归档目录", "完成时间"
        };
        int matches = 0;
        for (String marker : markers) if (normalized.contains(marker)) matches++;
        return matches >= 3 && normalized.contains("完成时间");
    }

    static int compareNatural(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = digitEnd(left, leftIndex);
                int rightEnd = digitEnd(right, rightIndex);
                String leftNumber = trimLeadingZeros(left.substring(leftIndex, leftEnd));
                String rightNumber = trimLeadingZeros(right.substring(rightIndex, rightEnd));
                int lengthResult = Integer.compare(leftNumber.length(), rightNumber.length());
                if (lengthResult != 0) return lengthResult;
                int numberResult = leftNumber.compareTo(rightNumber);
                if (numberResult != 0) return numberResult;
                leftIndex = leftEnd;
                rightIndex = rightEnd;
                continue;
            }
            int charResult = Character.compare(Character.toLowerCase(leftChar), Character.toLowerCase(rightChar));
            if (charResult != 0) return charResult;
            leftIndex++;
            rightIndex++;
        }
        return Integer.compare(left.length(), right.length());
    }

    static Comparator<String> naturalComparator() {
        return WorkRules::compareNatural;
    }

    private static int digitEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
        return index;
    }

    private static String trimLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') index++;
        return value.substring(index);
    }
}
