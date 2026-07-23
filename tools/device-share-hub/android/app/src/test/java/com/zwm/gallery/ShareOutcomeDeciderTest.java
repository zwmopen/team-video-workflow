package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ShareOutcomeDeciderTest {
    @Test
    public void resultBeforeChosenCallbackWaitsAndThenMarks() {
        ShareOutcomeDecider decider = new ShareOutcomeDecider();

        assertEquals(ShareOutcomeDecider.Action.WAIT_FOR_CHOSEN_CALLBACK,
                decider.onChooserResult(8_000L));
        assertEquals(ShareOutcomeDecider.Action.MARK_SHARED,
                decider.onTargetChosen(8_120L));
    }

    @Test
    public void missingChosenCallbackCancelsAfterGracePeriod() {
        ShareOutcomeDecider decider = new ShareOutcomeDecider();

        assertEquals(ShareOutcomeDecider.Action.WAIT_FOR_CHOSEN_CALLBACK,
                decider.onChooserResult(8_000L));
        assertEquals(ShareOutcomeDecider.Action.CANCEL,
                decider.onChosenCallbackTimeout());
    }

    @Test
    public void quickReturnAfterNormalCallbackKeepsCloneWait() {
        ShareOutcomeDecider decider = new ShareOutcomeDecider();

        assertEquals(ShareOutcomeDecider.Action.NONE, decider.onTargetChosen(4_000L));
        assertEquals(ShareOutcomeDecider.Action.WAIT_FOR_DEFERRED_TARGET,
                decider.onChooserResult(8_000L));
    }

    @Test
    public void longTargetVisitMarksImmediately() {
        ShareOutcomeDecider decider = new ShareOutcomeDecider();

        assertEquals(ShareOutcomeDecider.Action.NONE, decider.onTargetChosen(1_000L));
        assertEquals(ShareOutcomeDecider.Action.MARK_SHARED,
                decider.onChooserResult(8_000L));
    }
}
