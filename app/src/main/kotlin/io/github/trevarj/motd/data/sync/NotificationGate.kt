package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType

/**
 * Whether an incoming, already-persisted message is eligible to raise a notification, shared by
 * both protocol writers ([EventProcessor] for IRC, `XmppEventProcessor` for XMPP) so the rule
 * cannot drift between them.
 *
 * A message notifies when it is not our own echo, not a SERVER/console line (a MOTD containing the
 * nick must not fire a mention — plans/16 §5.6.5), and is either a direct message (QUERY) or a
 * channel line that mentions us. Protocol-specific concerns stay at the call site: IRC also gates
 * on `origin.notifies`, and XMPP gates out replayed history (a delay-stamped stanza).
 */
fun shouldNotify(isSelf: Boolean, type: BufferType, hasMention: Boolean): Boolean =
    !isSelf && type != BufferType.SERVER && (type == BufferType.QUERY || hasMention)
