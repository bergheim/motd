package io.github.trevarj.motd.xmppbackend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carried over from the fork/xmpp-support prototype's `SanHostnameVerifierTest` (docs/backend-neutral-xmpp-rollout.md
 * "PR 2"): proves the SAN dNSName matching fix behaves identically after the reimplementation.
 */
class SanHostnameVerifierTest {
    @Test
    fun exactMatch_caseInsensitiveViaCallerLowercasing() {
        assertTrue(SanHostnameVerifier.matches("xmpp.glvortex.net", "xmpp.glvortex.net"))
        assertFalse(SanHostnameVerifier.matches("xmpp.glvortex.net", "glvortex.net"))
    }

    @Test
    fun wildcard_matchesSingleLeftmostLabelOnly() {
        assertTrue(SanHostnameVerifier.matches("a.example.net", "*.example.net"))
        assertFalse(SanHostnameVerifier.matches("example.net", "*.example.net"))
        assertFalse(SanHostnameVerifier.matches("a.b.example.net", "*.example.net"))
        assertFalse(SanHostnameVerifier.matches("aexample.net", "*.example.net"))
    }

    @Test
    fun wildcard_neverMatchesEmptyLabel() {
        assertFalse(SanHostnameVerifier.matches(".example.net", "*.example.net"))
    }
}
