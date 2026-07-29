package io.github.trevarj.motd.data.repo

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.XmppAccountEntity
import javax.inject.Inject

/**
 * XMPP's per-protocol account repository (docs/backend-neutral-xmpp-rollout.md "account creation
 * and edits"). The shared `networks` row and its `xmpp_accounts` satellite detail are always
 * written together, atomically, via the same `db.withTransaction` idiom already used for other
 * cross-DAO writes ([io.github.trevarj.motd.data.sync.CanonicalTimelineStore],
 * [io.github.trevarj.motd.data.backup.ConfigurationBackup]) — `NetworkDao`/`XmppAccountDao` cannot
 * call into each other directly since Room only lets a `@Transaction` default method call sibling
 * methods on its *own* DAO interface.
 *
 * Deliberately bypasses [NetworkRepositoryImpl.addNetwork]'s IRC-shaped duplicate check: that
 * dedup keys DIRECT rows on `(host, port, nick)`, which for an XMPP row are documented inert
 * placeholders (see [io.github.trevarj.motd.ui.settings.xmpp.buildXmppNetworkEntity]) shared by
 * every XMPP account, not real per-account identity — reusing it here would risk silently
 * collapsing two different JIDs (or discarding a corrected password on re-add) onto one row. XMPP
 * account identity is the JID; this baseline does not dedup by JID, matching how it does not dedup
 * anything else the create screen does not explicitly ask for (see [createAccount]'s KDoc).
 */
class XmppAccountRepository @Inject constructor(
    private val db: MotdDatabase,
) {
    suspend fun account(networkId: Long): XmppAccountEntity? = db.xmppAccountDao().byNetwork(networkId)

    /**
     * Insert [network] and its [XmppAccountEntity] detail in one transaction; returns the new
     * network id. Always inserts a fresh row — see this class's KDoc for why create intentionally
     * skips [NetworkRepositoryImpl.addNetwork]'s identity dedup. Caller-side double-submit
     * protection (e.g. disabling Save while a create is in flight) is the UI's job, not this
     * repository's.
     */
    suspend fun createAccount(
        network: NetworkEntity,
        jid: String,
        password: String,
        resource: String?,
    ): Long = db.withTransaction {
        val networkId = db.networkDao().insert(network)
        db.xmppAccountDao().upsert(XmppAccountEntity(networkId, jid, password, resource))
        networkId
    }

    /** Update both [network] and its account detail in one transaction. [network.id] must already exist. */
    suspend fun updateAccount(
        network: NetworkEntity,
        jid: String,
        password: String,
        resource: String?,
    ) {
        db.withTransaction {
            db.networkDao().update(network)
            db.xmppAccountDao().upsert(XmppAccountEntity(network.id, jid, password, resource))
        }
    }
}
