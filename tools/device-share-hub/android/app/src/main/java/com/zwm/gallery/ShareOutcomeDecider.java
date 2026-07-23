package com.zwm.gallery;

final class ShareOutcomeDecider {
    static final long QUICK_TARGET_RETURN_MS = 6_000L;

    enum Action {
        NONE,
        WAIT_FOR_CHOSEN_CALLBACK,
        WAIT_FOR_DEFERRED_TARGET,
        MARK_SHARED,
        CANCEL
    }

    private boolean targetChosen;
    private boolean chooserResultReturned;
    private long targetChosenAtMs;

    Action onTargetChosen(long nowMs) {
        targetChosen = true;
        targetChosenAtMs = nowMs;
        // HarmonyOS/EMUI can deliver the chooser result before the selected-target
        // broadcast. The late broadcast still proves that the user picked a target.
        return chooserResultReturned ? Action.MARK_SHARED : Action.NONE;
    }

    Action onChooserResult(long nowMs) {
        chooserResultReturned = true;
        if (!targetChosen) return Action.WAIT_FOR_CHOSEN_CALLBACK;
        return nowMs - targetChosenAtMs < QUICK_TARGET_RETURN_MS
                ? Action.WAIT_FOR_DEFERRED_TARGET
                : Action.MARK_SHARED;
    }

    Action onChosenCallbackTimeout() {
        return chooserResultReturned && !targetChosen ? Action.CANCEL : Action.NONE;
    }
}
