package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.xmpp.MucRoomListing
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
        val ready = IrcClientState.Ready("me", emptySet(), mapOf("ELIST" to "U"))

        assertEquals(ready, channelBrowserConnectionState(null, ready))
        assertEquals(ready, channelBrowserConnectionState(IrcClientState.Disconnected, ready))
        assertEquals(
            IrcClientState.Disconnected,
            channelBrowserConnectionState(ready, IrcClientState.Disconnected),
        )
    }

    @Test
    fun `browser does not present offline state before initialization`() {
        assertEquals(
            ChannelBrowserAvailability.INITIALIZING,
            channelBrowserAvailability(false, false, IrcClientState.Disconnected),
        )
        assertEquals(
            ChannelBrowserAvailability.READY,
            channelBrowserAvailability(true, false, IrcClientState.Ready("me", emptySet(), emptyMap())),
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

    // -- MUC room-browse mapping/filtering (xmpp-support) --

    @Test
    fun `toChannelListing uses the room JID as name and disco name as topic`() {
        val named = toChannelListing(MucRoomListing(roomJid = "lobby@conf.example.net", name = "Lobby"))
        assertEquals("lobby@conf.example.net", named.name)
        assertEquals("Lobby", named.topic)
        assertEquals(0, named.userCount)

        val unnamed = toChannelListing(MucRoomListing(roomJid = "random@conf.example.net", name = null))
        assertEquals("", unnamed.topic)
    }

    @Test
    fun `filterChannelListings blank query keeps every listing`() {
        val listings = listOf(ChannelListing("lobby@conf.example.net", 0, "Lobby"))
        assertEquals(listings, filterChannelListings(listings, "  "))
    }

    @Test
    fun `filterChannelListings matches room JID or topic substring case-insensitively`() {
        val listings = listOf(
            ChannelListing("lobby@conf.example.net", 0, "Lobby"),
            ChannelListing("random@conf.example.net", 0, "Off Topic"),
        )

        assertEquals(
            listOf("lobby@conf.example.net"),
            filterChannelListings(listings, "LOBBY").map { it.name },
        )
        assertEquals(
            listOf("random@conf.example.net"),
            filterChannelListings(listings, "topic").map { it.name },
        )
        assertEquals(emptyList<ChannelListing>(), filterChannelListings(listings, "nonexistent"))
    }
}
