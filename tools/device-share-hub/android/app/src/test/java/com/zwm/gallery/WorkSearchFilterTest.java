package com.zwm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WorkSearchFilterTest {

    @Test
    public void matchesEmptyOrNullTokens() {
        assertTrue(MainActivity.matchesSearchTokens("安吉秋季团建", "安吉", null));
        assertTrue(MainActivity.matchesSearchTokens("安吉秋季团建", "安吉", new String[0]));
        assertTrue(MainActivity.matchesSearchTokens("安吉秋季团建", "安吉", new String[]{""}));
    }

    @Test
    public void matchesFolderOrTitleSingleToken() {
        String title = "20260914 Codex-安吉秋季团建住进山里慢下来";
        String folder = "安吉";

        // Match by folder name
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"安吉"}));
        // Match by title keyword
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"慢下来"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"住进山里"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"20260914"}));
        // Non-match
        assertFalse(MainActivity.matchesSearchTokens(title, folder, new String[]{"莫干山"}));
    }

    @Test
    public void matchesMultipleTokensAcrossFolderAndTitle() {
        String title = "20260914 Codex-安吉秋季团建住进山里慢下来";
        String folder = "安吉";

        // One token matches folder ("安吉"), other token matches title ("慢下来")
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"安吉", "慢下来"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"安吉", "codex", "慢下来"}));

        // One token matches, but other token does not match
        assertFalse(MainActivity.matchesSearchTokens(title, folder, new String[]{"安吉", "千岛湖"}));
    }

    @Test
    public void matchesCaseInsensitive() {
        String title = "Codex-AUTUMN_A-莫干山轻奢私汤";
        String folder = "莫干山";

        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"codex"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"CODEX"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"autumn_a"}));
        assertTrue(MainActivity.matchesSearchTokens(title, folder, new String[]{"AUTUMN_A"}));
    }

    @Test
    public void handlesNullSafely() {
        assertFalse(MainActivity.matchesSearchTokens(null, null, new String[]{"安吉"}));
        assertTrue(MainActivity.matchesSearchTokens(null, "安吉", new String[]{"安吉"}));
        assertTrue(MainActivity.matchesSearchTokens("安吉团建", null, new String[]{"安吉"}));
    }
}
