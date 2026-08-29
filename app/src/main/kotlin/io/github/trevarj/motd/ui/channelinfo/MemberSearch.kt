package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.data.db.MemberEntity

/**
 * Pure fuzzy member search. No Android deps — plain JUnit testable (mirrors [MemberSectioning.kt]
 * and `Autocomplete.kt`).
 *
 * Matches are scored in tiers (lower wins): 0 exact (normalized equality), 1 prefix, 2 substring,
 * 3 subsequence. Within a tier, earlier and tighter matches win; ties fall back to last-spoke
 * recency (most recent first, never-spoke last), then alphabetical. Blank query yields no results.
 *
 * The subsequence kernel is local: nicks are plain tokens, not the underscore-compacted names
 * `EmojiCatalog.compactSubsequenceMatch` targets, so it is reimplemented here.
 */
fun rankMembersFuzzy(
    query: String,
    members: List<MemberEntity>,
    normalize: (String) -> String,
    lastSpokeAt: (MemberEntity) -> Long? = { null },
): List<MemberEntity> {
    val needle = normalize(query)
    if (needle.isEmpty()) return emptyList()

    data class Scored(
        val member: MemberEntity,
        val tier: Int,
        val firstOffset: Int,
        val span: Int,
        val key: String,
    )

    val scored = ArrayList<Scored>(members.size)
    for (member in members) {
        val hay = normalize(member.nick)
        var tier = 0
        var firstOffset = 0
        var span = needle.length
        when {
            hay == needle -> {}

            hay.startsWith(needle) -> {
                tier = 1
            }

            hay.indexOf(needle).also { firstOffset = it } >= 0 -> {
                tier = 2
            }

            else -> {
                val match = subsequenceMatch(hay, needle) ?: continue
                tier = 3
                firstOffset = match.first
                span = match.second
            }
        }
        scored.add(Scored(member, tier, firstOffset, span, hay))
    }
    if (scored.isEmpty()) return emptyList()

    return scored
        .sortedWith(
            compareBy<Scored> { it.tier }
                .thenBy { it.firstOffset }
                .thenBy { it.span }
                // Most recent speaker first; null maps to Long.MIN_VALUE so never-spoke sorts last.
                .thenByDescending { lastSpokeAt(it.member) ?: Long.MIN_VALUE }
                .thenBy { it.key }
                .thenBy { it.member.nick },
        ).map { it.member }
}

/**
 * Greedy subsequence match: returns (firstMatchOffset, inclusiveSpan) when [needle] occurs as a
 * subsequence of [haystack] (in order, not necessarily contiguous), or null otherwise. Inclusive
 * span is `lastMatch - firstMatch + 1`; smaller is tighter.
 */
private fun subsequenceMatch(
    haystack: String,
    needle: String,
): Pair<Int, Int>? {
    var ni = 0
    var first = -1
    var last = -1
    haystack.forEachIndexed { i, c ->
        if (ni < needle.length && c == needle[ni]) {
            if (first < 0) first = i
            last = i
            ni++
        }
    }
    if (ni != needle.length) return null
    return first to (last - first + 1)
}
