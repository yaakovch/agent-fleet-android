package com.termux.app;

import com.termux.shared.data.UrlUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;

public class TermuxActivityTest {

    private void assertUrlsAre(String text, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, UrlUtils.extractUrls(text));
    }

    @Test
    public void testExtractUrls() {
        assertUrlsAre("hello http://example.com world", "http://example.com");

        assertUrlsAre("http://example.com\nhttp://another.com", "http://example.com", "http://another.com");

        assertUrlsAre("hello http://example.com world and http://more.example.com with secure https://more.example.com",
            "http://example.com", "http://more.example.com", "https://more.example.com");

        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar",
            "https://example.com/#bar", "https://example.com/foo#bar");
    }

    @Test
    public void testAgentFleetKeySequences() {
        Assert.assertEquals("\t", TermuxActivity.agentFleetKeySequence("TAB"));
        Assert.assertEquals("\033[Z", TermuxActivity.agentFleetKeySequence("SHIFT_TAB"));
        Assert.assertEquals("\033[A", TermuxActivity.agentFleetKeySequence("UP"));
        Assert.assertEquals("\033[B", TermuxActivity.agentFleetKeySequence("DOWN"));
        Assert.assertNull(TermuxActivity.agentFleetKeySequence("UNKNOWN"));
    }

    @Test
    public void testManagedPresentationRecoveryPolicy() {
        Assert.assertTrue(TermuxActivity.shouldRestoreAgentFleetPresentation(false, true, true, false));
        Assert.assertTrue(TermuxActivity.shouldRestoreAgentFleetPresentation(false, false, false, true));
        Assert.assertFalse(TermuxActivity.shouldRestoreAgentFleetPresentation(true, true, false, true));
        Assert.assertFalse(TermuxActivity.shouldRestoreAgentFleetPresentation(false, false, true, true));
        Assert.assertFalse(TermuxActivity.shouldRestoreAgentFleetPresentation(false, false, false, false));
    }

    @Test
    public void testManagedReconnectUsesTheIntentTargetDuringDirectEntry() {
        Assert.assertTrue(TermuxActivity.shouldReconnectFinishedManagedSession(
            true, "gaming:wtmux", "gaming:wtmux", 255));
        Assert.assertFalse(TermuxActivity.shouldReconnectFinishedManagedSession(
            false, "gaming:wtmux", "gaming:wtmux", 255));
        Assert.assertFalse(TermuxActivity.shouldReconnectFinishedManagedSession(
            true, "gaming:wtmux", "work:other", 255));
        Assert.assertFalse(TermuxActivity.shouldReconnectFinishedManagedSession(
            true, "gaming:wtmux", "gaming:wtmux", 0));
        Assert.assertFalse(TermuxActivity.shouldReconnectFinishedManagedSession(
            true, "gaming:wtmux", "gaming:wtmux", 130));
    }

}
