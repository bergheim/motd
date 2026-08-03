package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every [ConnectionManager] member must be dispatched by [CompositeConnectionManager].
 *
 * The composite is the production binding, so a member it does not override silently resolves to
 * the interface's default — an empty flow or `false` — which reads to callers as "nothing is
 * connected" or "the write failed" instead of failing loudly. That is exactly how `connectionActivity`
 * and `setChannelTopic` were missed when upstream added them: the app compiled, the suite passed
 * (fakes implement the interface directly), and only the running app was wrong.
 *
 * This is a ratchet: add a seam member, dispatch it, or explain it in [intentionallyNotDispatched].
 */
class CompositeSeamCoverageTest {

    /**
     * Members that legitimately need no per-backend dispatch, each with its reason. Keep this
     * short; anything network-, buffer- or event-scoped belongs in the composite instead.
     */
    private val intentionallyNotDispatched = mapOf(
        "dismissCertPrompt" to
            "non-suspending, so no row lookup is possible; broadcast is safe because managers " +
                "ignore prompts they do not own.",
    )

    @Test
    fun `composite dispatches every seam member`() {
        val seamMembers = ConnectionManager::class.java.methods
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name.substringBefore('$') }
            .filterNot { it.startsWith("access") }
            .toSortedSet()

        val dispatched = CompositeConnectionManager::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name.substringBefore('$') }
            .toSortedSet()

        val missing = seamMembers - dispatched - intentionallyNotDispatched.keys
        assertEquals(
            "CompositeConnectionManager does not dispatch these ConnectionManager members, so " +
                "callers silently get the interface default instead of the owning backend's " +
                "behavior. Override them in the composite, or record them in " +
                "intentionallyNotDispatched with a reason. Missing: $missing",
            emptySet<String>(),
            missing,
        )
    }
}
