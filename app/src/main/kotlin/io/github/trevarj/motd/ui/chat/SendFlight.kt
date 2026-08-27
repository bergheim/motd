package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.chatBubbleWidth
import io.github.trevarj.motd.ui.components.messageBubbleRoleColors
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlin.math.max
import kotlin.math.min

/*
 * How a sent message reaches the timeline: the finished bubble rises from the composer into its
 * slot, and over the first part of that flight the typed line stays put while a bubble grows
 * around it (the morph). The morph is a presentation layer on the same flight transport, not an
 * alternative to it. It needs a bare line of text to grow around, so the layouts that have none
 * skip it (see SendFlightOverlay) and show the plain bubble ghost riding the identical flight.
 */

/**
 * Where a send flight starts and where it ends, in the coordinates of the chat surface that hosts
 * the overlay.
 *
 * Both rects are reported from `onGloballyPositioned`, which runs on every layout pass. They live
 * in snapshot state that only [SendFlightOverlay] reads, so a composer growing a line or a
 * timeline settling never recomposes the timeline itself.
 */
@Stable
internal class SendFlightAnchors {
    /**
     * Every rect is reported in window coordinates, because the composer and a timeline row have
     * no common local space. [hostOrigin] is the overlay's own window position, which turns them
     * back into the offsets the ghost is placed with.
     */
    var hostOrigin by mutableStateOf(Offset.Zero)
    var composerField by mutableStateOf<Rect?>(null)

    /**
     * The composer field's inner text origin (window coords): where the first glyph of the draft
     * is drawn. The morph presentation aligns its own text here on the tap frame so the typed
     * line visually never moves when the field clears.
     */
    var composerTextOrigin by mutableStateOf<Offset?>(null)

    /** The landing row, keyed by event id so a report can be traced back to the row that made it. */
    var landingRow by mutableStateOf<Pair<Long, Rect>?>(null)

    /**
     * The ghost's measured height, written from the overlay's layout pass. Sizes the runway the
     * timeline opens under the flight; 0 until the overlay has laid out (the same frame's draw
     * already sees the real value, so at worst the runway's first frame targets only the gap).
     */
    var ghostHeight by mutableStateOf(0f)

    /**
     * The composer field's height on the tap frame, pinned alongside the motion reset. A
     * multi-line draft collapses the field to one line when it clears, which grows the timeline
     * pane and drops the list foot by the difference on that same frame; [composerShrink] feeds
     * that drop into the runway and hover so the vacated-space math survives the collapse.
     */
    var launchFieldHeight by mutableStateOf(0f)

    /** How much the composer has shrunk since the tap frame (0 while it has not). */
    fun composerShrink(): Float {
        val current = composerField?.height ?: return 0f
        return max(0f, launchFieldHeight - current)
    }

    fun reportLandingRow(
        eventId: Long,
        bounds: Rect,
    ) {
        landingRow = eventId to bounds
    }

    fun local(bounds: Rect): Rect = bounds.translate(-hostOrigin.x, -hostOrigin.y)
}

/**
 * One tap's motion, shared by the bubble in flight and the gap it is flying into.
 *
 * A single progress value drives both, which is the whole point: the conversation opens up
 * underneath the arriving bubble instead of jumping open first and leaving the bubble to chase a
 * hole that is already there. Both sides read it from deferred lambdas, so the spring invalidates
 * one layer and one row's layout per frame and never recomposes the timeline.
 */
@Stable
internal class SendFlightMotion {
    /** The flight proper: the bubble's travel into its slot and the gap opening beneath it. */
    val progress = Animatable(0f)

    /**
     * The immediate pre-landing rise, started on the tap frame before the pending row exists.
     * It carries the ghost's birth over the input box -- the flight replica's materialization
     * fade ([sendFlightEntryFade]) and the hover rise both read it -- so a slow send stays
     * visibly in flight for however long persistence takes. See [sendFlightGhostTop] for how it
     * blends with [progress].
     */
    val lift = Animatable(0f)
}

/**
 * The flight replica's materialization over the composer, from the lift fraction. The overlay
 * draws above the input bar, so without this the finished bubble would pop in fully opaque on
 * the tap frame; instead it fades in across the lift's first stretch, while still over the
 * input box, and then rides up whole while persistence prepares its landing row.
 */
internal fun sendFlightEntryFade(liftFraction: Float): Float {
    val t = (liftFraction / 0.35f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How far up the timeline slides while a flight is airborne: the runway.
 *
 * The runway is what makes full-height in-flight feedback safe. The landing row -- and with it
 * the gap the flight aims for -- can only exist once persistence completes, so on the tap frame
 * there is nowhere vacated for the bubble to rise into. Sliding the whole list up by the
 * predicted landing-row height ([runwayHeight] = measured ghost height + predicted group gap),
 * on the same spring as the lift, opens that space in lockstep with the rising bubble.
 *
 * Once the row lands its gap starts absorbing the runway: the reveal grows inside the list
 * while the shift shrinks by the same amount, so the neighbour's edge moves continuously and
 * ownership of the vacated space transfers to the ordinary gap mechanism without a seam. A
 * mispredicted runway height self-corrects here too -- both terms are animated, so the error
 * drains through the springs instead of jumping.
 *
 * [footDrop] is how far the list foot fell when a multi-line composer collapsed on the tap
 * frame ([SendFlightAnchors.composerShrink]). It is absorbed into the shift IMMEDIATELY -- not
 * on the spring -- so the neighbour never visibly drops toward the collapsed bar; from there
 * the spring carries the remaining (runway - drop) of travel, and the reveal drains the whole
 * shift exactly as before. Without this term a long message's ghost outran the vacated space
 * by the collapse amount and rode over the neighbour on both presentations.
 *
 * The lift fraction is capped at 1 so the flight spring's deliberate bounce stays on the
 * bubble; an underdamped shift would nod the entire conversation.
 */
internal fun sendFlightListShift(
    runwayHeight: Float,
    liftFraction: Float,
    revealedGap: Float,
    footDrop: Float = 0f,
): Float = max(0f, footDrop + (runwayHeight - footDrop) * min(liftFraction, 1f) - revealedGap)

/**
 * The ghost's absolute top for one frame.
 *
 * While the landing row has not reported (and while the flight is still short of the hover
 * line), the lift holds the bubble in-flight feedback above the composer. The hover rise is the
 * ghost height LESS [footDrop]: a collapsing multi-line composer already moved the resting slot
 * down by the drop, so a full-height rise from the pinned (pre-collapse) field top would
 * overshoot the slot by exactly that amount and poke into the neighbour above. The runway
 * ([sendFlightListShift]) opens beneath the reduced rise on the same spring and always faster,
 * so the hover sits in vacated space by construction. min keeps whichever of hover and flight
 * is higher, so the flight takes over smoothly once it climbs past the hover line.
 *
 * The flight aims at the row's *resting* foot: the reported [landingBottom] is a visual
 * coordinate that rides the runway shift, so [listShift] is added back to keep the target
 * stationary while the shift drains. The flight term is floored at [landingTop] (the reported
 * rect is clipped to the opened gap, and its coordinates already include the shift): a safety
 * net that only bites when the spring's overshoot would poke the bubble past the vacated edge
 * into the neighbour, and then by at most the overshoot itself.
 */
internal fun sendFlightGhostTop(
    startTop: Float,
    ghostHeight: Float,
    listShift: Float,
    landingTop: Float?,
    landingBottom: Float?,
    flightFraction: Float,
    liftFraction: Float,
    footDrop: Float = 0f,
): Float {
    val hoverTop = startTop - max(0f, ghostHeight - footDrop) * liftFraction
    val restingFoot = landingBottom?.plus(listShift) ?: return hoverTop
    val flightTop = startTop + (restingFoot - ghostHeight - startTop) * flightFraction
    val flooredFlight = landingTop?.let { max(flightTop, it) } ?: flightTop
    return min(hoverTop, flooredFlight)
}

/**
 * The bubble that rises from the composer into the timeline after a send.
 *
 * The ghost does not imitate the row it becomes -- it *is* that row, rendered by the same
 * [MessageBubble] the timeline uses. That renderer dispatches on layout density, resolves the
 * grouped-corner silhouette from [showSender], and builds the same linkified body, quoted reply,
 * and status/time line, so the replica cannot drift from its landing row by construction. A
 * hand-copied bubble had already drifted on three of those axes.
 *
 * The replica animates `translationY` and a birth fade only. Nothing scales, so the text is
 * rasterised once and never distorts, and the handoff to the real row is a swap between
 * identical pixels.
 *
 * The ghost is invisible to the semantics tree ([clearAndSetSemantics], which clears the whole
 * subtree including the bubble's own click semantics). Its text duplicates a real row's, and a
 * second match would make every `onNodeWithText` assertion in chat ambiguous.
 */

/**
 * How much of the morph stand-in has been replaced by the real bubble replica, from the flight
 * fraction. Smoothstepped over the flight's BACK half: the stand-in is where the transformation
 * plays out, and an earlier window dissolved it before the growth registered as a transformation
 * at all. Complete by 0.85 so the replica is whole before it must pixel-match the landing row,
 * with the still-moving bubble masking the metadata line's arrival.
 */
internal fun sendFlightMorphSwap(flightFraction: Float): Float {
    val t = ((flightFraction - 0.45f) / 0.4f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun BoxScope.SendFlightOverlay(
    flight: OutgoingFlight?,
    anchors: SendFlightAnchors,
    motion: SendFlightMotion,
    listShift: () -> Float,
    selfNick: String,
    showSender: Boolean,
    networkId: Long?,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
) {
    // Read nothing while idle: an overlay that sampled the anchors unconditionally would recompose
    // on every composer layout pass for the whole life of the screen.
    if (flight == null) return
    val field = anchors.composerField?.let(anchors::local) ?: return
    // Pinned for the life of the tap. The composer empties on the same frame the flight launches,
    // so a live rect would shrink under the ghost and make it jump on its first step.
    val start = remember(flight.token) { field }
    // The row shows its Room timestamp; the ghost shows the clock for the moment it launched, built
    // with the timeline's own formatter so 12/24-hour and locale can never disagree. The launch
    // instant comes from the flight, which is also what row matching and grouping are decided by.
    val formatTime = rememberMessageTimeFormatter()
    val launchedAt = flight.launchedAtMs
    val time = remember(flight.token, formatTime) { formatTime(launchedAt) }
    val spacing = LocalSpacing.current
    // The morph needs a bubble to grow around a bare line of text: COMPACT and TWO_LINE render
    // text rows rather than bubbles, and a reply puts a quote block above the body that the
    // stand-in cannot represent. Those flights show the plain bubble ghost instead.
    val morph = !spacing.compact && !spacing.twoLine && flight.replyText == null

    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // Layout-phase write: sizes the runway the timeline opens under this flight.
                .onSizeChanged { anchors.ghostHeight = it.height.toFloat() }
                .graphicsLayer {
                    // Re-read the landing every frame rather than snapshotting it: the row's rect
                    // moves while the keyboard and the list settle, and a stale target lands crooked.
                    val landing = anchors.landingRow?.second?.let(anchors::local)
                    translationY =
                        sendFlightGhostTop(
                            startTop = start.top,
                            ghostHeight = size.height,
                            listShift = listShift(),
                            landingTop = landing?.top,
                            landingBottom = landing?.bottom,
                            flightFraction = motion.progress.value,
                            liftFraction = motion.lift.value,
                            footDrop = anchors.composerShrink(),
                        )
                }.clearAndSetSemantics {},
    ) {
        // The real row's replica. Under the morph it dissolves in mid-flight over the stand-in;
        // under the plain flight it materializes over the composer on the lift's first stretch.
        // It is always the layer that lands, so the handoff to the real row stays a swap
        // between identical pixels in both presentations.
        Box(
            modifier =
                if (morph) {
                    Modifier.graphicsLayer { alpha = sendFlightMorphSwap(motion.progress.value) }
                } else {
                    Modifier.graphicsLayer { alpha = sendFlightEntryFade(motion.lift.value) }
                },
        ) {
            MessageBubble(
                sender = selfNick,
                text = flight.text,
                timeMs = launchedAt,
                isSelf = true,
                kind = MessageKind.PRIVMSG,
                showSender = showSender,
                networkId = networkId,
                formattedTime = time,
                pending = true,
                reply =
                    flight.replyText?.let {
                        ReplyPreviewData(flight.replySender.orEmpty(), it, flight.replyIrcFormattedText)
                    },
                knownNicks = knownNicks,
                identityRules = identityRules,
            )
        }
        if (morph) {
            MorphingGhost(
                flight = flight,
                anchors = anchors,
                motion = motion,
                showSender = showSender,
            )
        }
    }
}

/**
 * The morph presentation's stand-in: the typed line itself, with the bubble growing around it.
 *
 * On the tap frame the stand-in's text is pinned glyph-for-glyph over the composer field's text
 * (both render the same `bodyLarge` under [ConversationTypography]), so clearing the field does
 * not visibly remove the line -- ownership just changes. The transformation runs on its own
 * clock ([MotdMotion.sendMorphGrow], slower than the flight springs): the text slides from the
 * field's left-aligned origin to the bubble's resting alignment while the bubble surface
 * inflates in beneath it (alpha leading scale, so the growth is visible rather than a plain
 * fade) and the text color crossfades from field ink to bubble ink. In the flight's back half
 * the whole stand-in dissolves into the real [MessageBubble] replica ([sendFlightMorphSwap]),
 * which brings the metadata line and linkified body and owns the landing.
 *
 * Every animated value is read in a draw-phase lambda; the stand-in never recomposes per frame.
 * A multi-line draft may re-wrap where the bubble is narrower than the field; the first glyph
 * stays pinned, which keeps the illusion for the dominant single-line send.
 */
@Composable
private fun MorphingGhost(
    flight: OutgoingFlight,
    anchors: SendFlightAnchors,
    motion: SendFlightMotion,
    showSender: Boolean,
) {
    val spacing = LocalSpacing.current
    // The transformation's own clock, started on the tap frame like the lift. Riding the lift
    // spring compressed slide, tint, and growth into ~300ms alongside the rise, which read as
    // "the bubble flies" rather than "the text becomes a bubble".
    val morph = remember(flight.token) { Animatable(0f) }
    LaunchedEffect(flight.token) {
        morph.animateTo(1f, MotdMotion.sendMorphGrow)
    }
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMotdSemanticColors.current
    // Keyed by color value, not scheme identity: Material3 mutates its retained ColorScheme in place.
    val roles =
        remember(scheme.primaryContainer, scheme.primary, scheme.onPrimaryContainer, semantic) {
            messageBubbleRoleColors(scheme, isSelf = true, mentionHighlighted = false, kind = MessageKind.PRIVMSG, semantic = semantic)
        }
    val fieldInk = MaterialTheme.colorScheme.onSurface
    val plainText = remember(flight.text) { plainIrcText(flight.text) }
    val topCorner = if (showSender) spacing.bubbleCorner else spacing.bubbleGroupedCorner
    val shape =
        RoundedCornerShape(
            topStart = spacing.bubbleCorner,
            topEnd = topCorner,
            bottomEnd = spacing.bubbleGroupedCorner,
            bottomStart = spacing.bubbleCorner,
        )
    // Field-text origin minus the stand-in text's own untranslated origin, pinned on the first
    // laid-out frame (the slide layer is still at identity then, so the measurement is clean).
    var textDelta by remember(flight.token) { mutableStateOf<Offset?>(null) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.messageOuterHPad, vertical = spacing.bubbleRowVPad)
                .graphicsLayer { alpha = 1f - sendFlightMorphSwap(motion.progress.value) },
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier =
                Modifier
                    .chatBubbleWidth()
                    .graphicsLayer {
                        // Slide from the field's text origin to the bubble's natural alignment.
                        val delta = textDelta
                        if (delta != null) {
                            val remaining = 1f - min(1f, morph.value)
                            translationX = delta.x * remaining
                            translationY = delta.y * remaining
                        }
                    }.drawBehind {
                        // The bubble surface inflating around the line; drawn, not composed, so a
                        // frame costs one layer invalidation. Alpha leads the scale (fully opaque by
                        // ~60% of the morph) so the eye reads a surface GROWING to its final size,
                        // not a finished bubble fading in.
                        val m = min(1f, morph.value)
                        scale(0.85f + 0.15f * m) {
                            drawOutline(
                                outline = shape.createOutline(size, layoutDirection, this@drawBehind),
                                color = roles.container,
                                alpha = min(1f, m * 1.6f),
                            )
                        }
                    }.padding(horizontal = spacing.bubbleInnerHPad, vertical = spacing.bubbleInnerVPad),
        ) {
            // Two identical layouts crossfading ink: text cannot recolor in the draw phase, and
            // the pair keeps the glyphs themselves perfectly still while the color transfers.
            Text(
                text = plainText,
                style = MaterialTheme.typography.bodyLarge,
                color = fieldInk,
                modifier =
                    Modifier
                        .onGloballyPositioned {
                            if (textDelta == null) {
                                anchors.composerTextOrigin?.let { origin ->
                                    textDelta = origin - it.positionInWindow()
                                }
                            }
                        }.graphicsLayer { alpha = 1f - min(1f, morph.value) },
            )
            Text(
                text = plainText,
                style = MaterialTheme.typography.bodyLarge,
                color = roles.content,
                modifier = Modifier.graphicsLayer { alpha = min(1f, morph.value) },
            )
        }
    }
}
