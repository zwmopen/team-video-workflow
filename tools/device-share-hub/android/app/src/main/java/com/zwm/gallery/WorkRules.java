package com.zwm.gallery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class WorkRules {
    private static final String[] IMAGE_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"
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
        for (String name : names) {
            if ("文案.txt".equalsIgnoreCase(name)) return name;
        }
        ArrayList<String> textFiles = new ArrayList<>();
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) textFiles.add(name);
        }
        textFiles.sort(WorkRules::compareNatural);
        return textFiles.isEmpty() ? "" : textFiles.get(0);
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
