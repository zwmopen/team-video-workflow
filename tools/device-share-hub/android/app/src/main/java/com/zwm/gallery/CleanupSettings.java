package com.zwm.gallery;

import android.content.Context;
import android.content.SharedPreferences;

final class CleanupSettings {
    static final String PREFS = "device_share";
    static final String MOVE_AFTER_MINUTES = "autoTrashAfterMinutes";
    static final String DELETE_AFTER_MINUTES = "autoDeleteAfterMinutes";
    static final String MANUAL_DEFAULTS_MIGRATED = "manualCleanupDefaultsV1";
    static final int MANUAL = -1;
    static final int DEFAULT_MOVE_MINUTES = MANUAL;
    static final int DEFAULT_DELETE_MINUTES = MANUAL;
    private static final int MAX_MINUTES = 30 * 24 * 60;

    private CleanupSettings() {
    }

    static Values read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(MANUAL_DEFAULTS_MIGRATED, false)) {
            prefs.edit()
                    .putInt(MOVE_AFTER_MINUTES, MANUAL)
                    .putInt(DELETE_AFTER_MINUTES, MANUAL)
                    .putBoolean(MANUAL_DEFAULTS_MIGRATED, true)
                    .apply();
            return new Values(MANUAL, MANUAL);
        }
        int move = clamp(prefs.getInt(MOVE_AFTER_MINUTES, DEFAULT_MOVE_MINUTES));
        int delete = clamp(prefs.getInt(DELETE_AFTER_MINUTES, DEFAULT_DELETE_MINUTES));
        if (move >= 0 && delete >= 0 && delete < move) delete = move;
        return new Values(move, delete);
    }

    static boolean save(Context context, int moveMinutes, int deleteMinutes) {
        if (!isValid(moveMinutes) || !isValid(deleteMinutes)
                || (moveMinutes >= 0 && deleteMinutes >= 0 && deleteMinutes < moveMinutes)) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(MOVE_AFTER_MINUTES, moveMinutes)
                .putInt(DELETE_AFTER_MINUTES, deleteMinutes)
                .apply();
        return true;
    }

    private static int clamp(int value) {
        if (value == MANUAL) return MANUAL;
        return Math.max(0, Math.min(MAX_MINUTES, value));
    }

    private static boolean isValid(int value) {
        return value == MANUAL || (value >= 0 && value <= MAX_MINUTES);
    }

    static final class Values {
        final int moveMinutes;
        final int deleteMinutes;

        Values(int moveMinutes, int deleteMinutes) {
            this.moveMinutes = moveMinutes;
            this.deleteMinutes = deleteMinutes;
        }

        int moveHours() {
            return moveMinutes == MANUAL ? MANUAL : moveMinutes / 60;
        }

        int deleteHours() {
            return deleteMinutes == MANUAL ? MANUAL : deleteMinutes / 60;
        }

        long moveAfterMs() {
            return moveMinutes == MANUAL ? -1L : moveMinutes * 60_000L;
        }

        long deleteAfterMs() {
            return deleteMinutes == MANUAL ? -1L : deleteMinutes * 60_000L;
        }
    }
}
