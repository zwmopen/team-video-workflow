package com.zwm.gallery;

/** Shared small-screen rules for the latest clipboard preview. */
final class ClipboardDisplayPolicy {
    static final int COLLAPSED_LINES = 3;
    private static final int COLLAPSED_CHARACTER_HINT = 140;

    private ClipboardDisplayPolicy() {
    }

    static boolean shouldCollapse(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.length() > COLLAPSED_CHARACTER_HINT) return true;
        int lines = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n' && ++lines > COLLAPSED_LINES) return true;
        }
        return false;
    }
}
