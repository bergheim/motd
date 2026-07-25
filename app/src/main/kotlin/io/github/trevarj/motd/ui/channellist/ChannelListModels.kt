package io.github.trevarj.motd.ui.channellist

import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.xmpp.MucRoomListing

/** Server-side user-count floor when the network advertises ELIST 'U'. */
const val DEFAULT_MIN_USERS = 50
const val POPULAR_CHANNEL_LIMIT = 100
const val CHANNEL_SEARCH_LIMIT = 2000

/**
 * Sort listings by user count descending, stable for ties (plans/16 §5.7).
 * Kotlin's [sortedByDescending] is a stable sort, so equal counts keep input order.
 */
fun sortListings(listings: List<ChannelListing>): List<ChannelListing> =
    listings.sortedByDescending { it.userCount }

/** Prefer the scoped client's live state, especially when the manager snapshot has not caught up. */
fun channelBrowserConnectionState(
    managerState: IrcClientState?,
    clientState: IrcClientState?,
): IrcClientState = when {
    clientState != null -> clientState
    managerState != null -> managerState
    else -> IrcClientState.Disconnected
}

enum class ChannelBrowserAvailability {
    INITIALIZING,
    ROOT_UNAVAILABLE,
    CONNECTING,
    READY,
    OFFLINE,
    FAILED,
}

fun channelBrowserAvailability(
    initialized: Boolean,
    isRoot: Boolean,
    connection: IrcClientState,
): ChannelBrowserAvailability = when {
    !initialized -> ChannelBrowserAvailability.INITIALIZING
    isRoot -> ChannelBrowserAvailability.ROOT_UNAVAILABLE
    connection is IrcClientState.Ready -> ChannelBrowserAvailability.READY
    connection is IrcClientState.Connecting || connection is IrcClientState.Registering ->
        ChannelBrowserAvailability.CONNECTING
    connection is IrcClientState.Failed -> ChannelBrowserAvailability.FAILED
    else -> ChannelBrowserAvailability.OFFLINE
}

/**
 * Resolve the LIST arguments for a fetch (plans/16 §5.7).
 *
 * A non-blank [query] fetches with a `*query*` substring mask and no min-users floor. A blank
 * query auto-fetches the busiest channels ([DEFAULT_MIN_USERS] floor, applied server-side only
 * when ELIST 'U' is present — the `:irc` layer gates the `>n` param itself).
 */
data class ListArgs(val mask: String?, val minUsers: Int?)

fun listArgsFor(query: String): ListArgs =
    if (query.isBlank()) {
        ListArgs(mask = null, minUsers = DEFAULT_MIN_USERS)
    } else {
        ListArgs(mask = "*${query.trim()}*", minUsers = null)
    }

/** Popular browsing is deliberately compact; explicit searches may return a larger result set. */
fun channelListLimit(query: String): Int =
    if (query.isBlank()) POPULAR_CHANNEL_LIMIT else CHANNEL_SEARCH_LIMIT

/**
 * MUC discovery has no server-side mask/floor equivalent to IRC's LIST/ELIST, so a room's JID
 * becomes the browser [ChannelListing.name] and its optional disco#items name becomes the topic
 * line; [ChannelListing.userCount] has no XMPP counterpart and is always 0 (sortListings is then a
 * no-op stable pass-through, preserving discovery order).
 */
fun toChannelListing(listing: MucRoomListing): ChannelListing =
    ChannelListing(name = listing.roomJid, userCount = 0, topic = listing.name.orEmpty())

/** MUC discovery returns every room up front; a non-blank query filters client-side by substring
 *  against both the room JID and its disco#items name, mirroring IRC's mask-based LIST filtering. */
fun filterChannelListings(listings: List<ChannelListing>, query: String): List<ChannelListing> =
    if (query.isBlank()) {
        listings
    } else {
        listings.filter { it.name.contains(query, ignoreCase = true) || it.topic.contains(query, ignoreCase = true) }
    }

enum class ChannelJoinStatus { JOIN, JOINING, JOINED }

/** Applies the current server CASEMAPPING to persisted or optimistic channel names. */
fun normalizeChannelNames(channels: Collection<String>, identityRules: IrcIdentityRules): Set<String> =
    channels.map(identityRules::normalize).toSet()

/** Drops pending spellings confirmed joined by Room, or all pending names after Ready is lost. */
fun reconcilePendingChannelNames(
    pendingChannelNames: Set<String>,
    joinedChannels: Set<String>,
    identityRules: IrcIdentityRules,
    isReady: Boolean,
): Set<String> = if (isReady) {
    pendingChannelNames.filterTo(linkedSetOf()) { identityRules.normalize(it) !in joinedChannels }
} else {
    emptySet()
}

/** Removes every raw spelling equivalent to the target under the current server CASEMAPPING. */
fun removePendingChannelName(
    pendingChannelNames: Set<String>,
    channel: String,
    identityRules: IrcIdentityRules,
): Set<String> {
    val normalized = identityRules.normalize(channel)
    return pendingChannelNames.filterTo(linkedSetOf()) { identityRules.normalize(it) != normalized }
}

/** A rejection matters only for an outstanding join on the currently Ready connection. */
fun pendingChannelNamesAfterJoinRejection(
    pendingChannelNames: Set<String>,
    channel: String,
    identityRules: IrcIdentityRules,
    isReady: Boolean,
): Set<String>? {
    if (!isReady || pendingChannelNames.none { identityRules.normalize(it) == identityRules.normalize(channel) }) {
        return null
    }
    return removePendingChannelName(pendingChannelNames, channel, identityRules)
}

/** Pending JOIN is optimistic only; Room self-JOIN remains the sole joined confirmation. */
fun channelJoinStatus(
    channel: String,
    pendingChannels: Set<String>,
    joinedChannels: Set<String>,
    identityRules: IrcIdentityRules,
): ChannelJoinStatus = when (identityRules.normalize(channel)) {
    in joinedChannels -> ChannelJoinStatus.JOINED
    in pendingChannels -> ChannelJoinStatus.JOINING
    else -> ChannelJoinStatus.JOIN
}

/** Confirmation and connection loss are the only paths that clear optimistic pending names. */
fun reconcilePendingChannels(
    pendingChannelNames: Set<String>,
    joinedChannels: Set<String>,
    identityRules: IrcIdentityRules,
    isReady: Boolean,
): Set<String> = normalizeChannelNames(
    reconcilePendingChannelNames(pendingChannelNames, joinedChannels, identityRules, isReady),
    identityRules,
)
