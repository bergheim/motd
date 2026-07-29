package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelListModelsTest {

    @Test
    fun `join state uses normalized authoritative joined names and independent pending names`() {
        val rules = IrcIdentityRules()
        val pending = setOf("#one", "#two")
        val joined = setOf("#joined")

        assertEquals(ChannelJoinStatus.JOINING, channelJoinStatus("#ONE", pending, joined, rules))
        assertEquals(ChannelJoinStatus.JOINED, channelJoinStatus("#JOINED", pending, joined, rules))
        assertEquals(ChannelJoinStatus.JOIN, channelJoinStatus("#other", pending, joined, rules))
    }

    @Test
    fun `persisted joined and pending names can be renormalized for updated identity rules`() {
        val persisted = setOf("#[CHANNEL]")
        val pending = setOf("#[PENDING]")

        assertEquals(
            setOf("#{channel}"),
            normalizeChannelNames(persisted, IrcIdentityRules()),
        )
        assertEquals(
            setOf("#[channel]"),
            normalizeChannelNames(persisted, IrcIdentityRules.from("ascii", null)),
        )
        assertEquals(
            setOf("#{pending}"),
            reconcilePendingChannels(pending, emptySet(), IrcIdentityRules(), isReady = true),
        )
        assertEquals(
            setOf("#[pending]"),
            reconcilePendingChannels(pending, emptySet(), IrcIdentityRules.from("ascii", null), isReady = true),
        )
    }

    @Test
    fun `pending joins clear only after confirmation or loss of ready state`() {
        assertEquals(
            setOf("#two"),
            reconcilePendingChannels(
                setOf("#one", "#two"),
                setOf("#one"),
                IrcIdentityRules(),
                isReady = true,
            ),
        )
        assertEquals(
            emptySet<String>(),
            reconcilePendingChannels(
                setOf("#one", "#two"),
                emptySet(),
                IrcIdentityRules(),
                isReady = false,
            ),
        )
    }

    @Test
    fun `ready join rejection clears only its matching pending channel`() {
        val rules = IrcIdentityRules()
        val remaining = pendingChannelNamesAfterJoinRejection(
            pendingChannelNames = setOf("#one", "#two"),
            channel = "#ONE",
            identityRules = rules,
            isReady = true,
        )

        assertEquals(setOf("#two"), remaining)
    }

    @Test
    fun `live ready client wins while manager snapshot is absent or stale`() {
        val ready = ConnectionState.Ready("me")

        assertEquals(ready, channelBrowserConnectionState(null, ready))
        assertEquals(ready, channelBrowserConnectionState(ConnectionState.Disconnected, ready))
        assertEquals(
            ConnectionState.Disconnected,
            channelBrowserConnectionState(ready, ConnectionState.Disconnected),
        )
    }

    @Test
    fun `browser does not present offline state before initialization`() {
        assertEquals(
            ChannelBrowserAvailability.INITIALIZING,
            channelBrowserAvailability(false, false, ConnectionState.Disconnected),
        )
        assertEquals(
            ChannelBrowserAvailability.READY,
            channelBrowserAvailability(true, false, ConnectionState.Ready("me")),
        )
    }

    /**
     * Review fix (P2 finding): a backend with no room-discovery capability at all (XMPP's baseline)
     * must settle on [ChannelBrowserAvailability.UNSUPPORTED] even once Ready, rather than presenting
     * [ChannelBrowserAvailability.READY] and letting the fetch pipeline poll an IRC-owned accessor
     * that backend never registers with.
     */
    @Test
    fun `browser presents unsupported for a backend with no room-discovery capability`() {
        assertEquals(
            ChannelBrowserAvailability.UNSUPPORTED,
            channelBrowserAvailability(true, false, ConnectionState.Ready("me"), supportsDiscovery = false),
        )
        // The default (omitted) parameter preserves every existing 3-arg caller's behavior.
        assertEquals(
            ChannelBrowserAvailability.READY,
            channelBrowserAvailability(true, false, ConnectionState.Ready("me")),
        )
    }

    @Test
    fun `ready server without ELIST still auto-fetches locally bounded popular channels`() {
        assertEquals(
            true,
            shouldAutoFetchPopularChannels(
                connection = ConnectionState.Ready("me"),
                loaded = false,
                isRoot = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFetchPopularChannels(
                connection = ConnectionState.Disconnected,
                loaded = false,
                isRoot = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFetchPopularChannels(
                connection = ConnectionState.Ready("me"),
                loaded = true,
                isRoot = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFetchPopularChannels(
                connection = ConnectionState.Ready("me"),
                loaded = false,
                isRoot = true,
            ),
        )
    }

    @Test
    fun `sortListings orders by userCount descending`() {
        val input = listOf(
            ChannelListing("#a", 10, ""),
            ChannelListing("#b", 500, ""),
            ChannelListing("#c", 42, ""),
        )
        val sorted = sortListings(input)
        assertEquals(listOf("#b", "#c", "#a"), sorted.map { it.name })
    }

    @Test
    fun `sortListings is stable for ties`() {
        val input = listOf(
            ChannelListing("#first", 100, ""),
            ChannelListing("#second", 100, ""),
            ChannelListing("#third", 100, ""),
        )
        // Equal counts keep input order (Kotlin's sort is stable).
        assertEquals(listOf("#first", "#second", "#third"), sortListings(input).map { it.name })
    }

    // -- LIST arg resolution --

    @Test
    fun `listArgsFor blank query auto-fetches busiest with default min-users`() {
        val args = listArgsFor("   ")
        assertNull(args.mask)
        assertEquals(DEFAULT_MIN_USERS, args.minUsers)
    }

    @Test
    fun `listArgsFor non-blank query uses substring mask and no min-users`() {
        val args = listArgsFor("kotlin")
        assertEquals("*kotlin*", args.mask)
        assertNull(args.minUsers)
    }

    @Test
    fun `listArgsFor trims the query`() {
        assertEquals("*linux*", listArgsFor("  linux  ").mask)
    }

    @Test
    fun `blank query keeps a compact popular list`() {
        assertEquals(POPULAR_CHANNEL_LIMIT, channelListLimit(""))
        assertEquals(POPULAR_CHANNEL_LIMIT, channelListLimit("   "))
    }

    @Test
    fun `search query allows the larger result cap`() {
        assertEquals(CHANNEL_SEARCH_LIMIT, channelListLimit("kotlin"))
    }

    @Test
    fun `active fetch queues only changed search queries`() {
        assertEquals(false, shouldQueueChannelListFetch(null, ""))
        assertEquals(false, shouldQueueChannelListFetch("", ""))
        assertEquals(true, shouldQueueChannelListFetch("", "linux"))
        assertEquals(true, shouldQueueChannelListFetch("linux", "guix"))
    }

    @Test
    fun `late channel list results apply only to the query that requested them`() {
        assertEquals(true, shouldApplyChannelListFetchResult("", ""))
        assertEquals(true, shouldApplyChannelListFetchResult("linux", "linux"))
        assertEquals(false, shouldApplyChannelListFetchResult("", "linux"))
        assertEquals(false, shouldApplyChannelListFetchResult("linux", "guix"))
    }
}
