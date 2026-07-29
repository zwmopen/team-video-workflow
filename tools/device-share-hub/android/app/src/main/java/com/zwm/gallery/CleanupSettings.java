package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

final class CleanupSettings {
    static final String PREFS = "device_share";
    static final String MOVE_AFTER_MINUTES = "autoTrashAfterMinutes";
    static final String DELETE_AFTER_MINUTES = "autoDeleteAfterMinutes";
    static final int DEFAULT_MOVE_MINUTES = 60;
    static final int DEFAULT_DELETE_MINUTES = 60;
    private static final int MAX_MINUTES = 30 * 24 * 60;

    private CleanupSettings() {
    }

    static Values read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int move = clamp(prefs.getInt(MOVE_AFTER_MINUTES, DEFAULT_MOVE_MINUTES));
        int delete = clamp(prefs.getInt(DELETE_AFTER_MINUTES, DEFAULT_DELETE_MINUTES));
        if (delete < move) delete = move;
        return new Values(move, delete);
    }

    static boolean save(Context context, int moveMinutes, int deleteMinutes) {
        if (moveMinutes < 0 || deleteMinutes < moveMinutes || deleteMinutes > MAX_MINUTES) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(MOVE_AFTER_MINUTES, moveMinutes)
                .putInt(DELETE_AFTER_MINUTES, deleteMinutes)
                .apply();
        return true;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_MINUTES, value));
    }

    static final class Values {
        final int moveMinutes;
        final int deleteMinutes;

        Values(int moveMinutes, int deleteMinutes) {
            this.moveMinutes = moveMinutes;
            this.deleteMinutes = deleteMinutes;
        }

        long moveAfterMs() {
            return moveMinutes * 60_000L;
        }

        long deleteAfterMs() {
            return deleteMinutes * 60_000L;
        }
    }
}
