package io.github.trevarj.motd.ircbackend

import io.github.trevarj.motd.irc.client.IrcClient

/**
 * Live IRC session accessor for IRC-owned feature surfaces: DCC, bouncer administration, avatar
 * METADATA, server-admin tools, and webpush registration (docs/backend-neutral-xmpp-rollout.md).
 * General chat, notification, history, and connection code must not inject this; those surfaces
 * consume neutral seam contracts instead. Callers must treat the returned client as valid only for
 * the current session: re-resolve and compare identity (`sessionFor(id) === client`) around
 * suspension points exactly as the previous ConnectionManager.clientFor contract required.
 */
interface IrcSessions {
    /** Live client for a connected IRC network, null otherwise. */
    fun sessionFor(networkId: Long): IrcClient?
}
