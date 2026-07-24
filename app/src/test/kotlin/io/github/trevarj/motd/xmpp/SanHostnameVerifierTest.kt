package io.github.trevarj.motd.xmpp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
