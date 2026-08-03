package io.github.trevarj.motd.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.audio.VoicePrefsImpl
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigurationBackupRepositoryTest {

    @Test
    fun credentialsExcludedExportOmitsSecretsAndImportsAsPendingCredentials() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = "device-cert"))

        val raw = source.exportToString(
            mode = BackupExportMode.CREDENTIALS_EXCLUDED,
            nowEpochMillis = 1_000L,
        )

        assertFalse(raw.contains("sasl-secret"))
        assertFalse(raw.contains("server-secret"))
        assertFalse(raw.contains("vless://secret"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(raw, importMode = BackupImportMode.MERGE)
        assertEquals(1, preview.addedNetworks)
        assertEquals(1, preview.missingCredentialNetworks)

        target.import(raw, importMode = BackupImportMode.MERGE)

        val imported = targetDb.networkDao().allNow().single()
        assertNull(imported.saslPassword)
        assertNull(imported.serverPassword)
        assertNull(imported.obfsLink)
        assertEquals(
            "saslPassword,serverPassword,obfsLink,clientCertificate",
            imported.pendingCredentialRequirements,
        )
        assertFalse(imported.autoConnect)
        assertEquals(true, imported.restoreAutoConnect)
    }

    @Test
    fun encryptedExportRoundTripsCredentials() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))

        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        assertFalse(raw.contains("sasl-secret"))
        assertFalse(raw.contains("server-secret"))
        assertFalse(raw.contains("vless://secret"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(
            raw,
            password = "correct horse battery",
            importMode = BackupImportMode.MERGE,
        )
        assertEquals(true, preview.containsSecrets)
        assertEquals(0, preview.missingCredentialNetworks)

        target.import(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)

        val imported = targetDb.networkDao().allNow().single()
        assertEquals("sasl-secret", imported.saslPassword)
        assertEquals("server-secret", imported.serverPassword)
        assertEquals("vless://secret", imported.obfsLink)
        assertNull(imported.pendingCredentialRequirements)
        assertEquals(true, imported.autoConnect)
    }

    @Test
    fun wrongPasswordRejectsEncryptedImportWithoutMutation() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        val targetDb = inMemoryDb()
        val target = repository(targetDb)

        try {
            target.import(raw, password = "wrong horse battery", importMode = BackupImportMode.MERGE)
            fail("wrong password must reject encrypted import")
        } catch (_: Exception) {
            // Expected: GCM authentication fails before any import mutation.
        }
        assertEquals(emptyList<NetworkEntity>(), targetDb.networkDao().allNow())
    }

    /**
     * Review fix: the backup used to serialize only [NetworkEntity], so an XMPP account's
     * JID/password/resource — which live exclusively in the `xmpp_accounts` satellite table — never
     * round-tripped through export/import at all, and every network row (regardless of protocol)
     * imported back as IRC (`protocol` defaults to "irc" and was never carried through
     * `PortableNetwork`).
     */
    @Test
    fun xmppAccountRoundTripsThroughEncryptedExport() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        val networkId = sourceDb.networkDao().insert(xmppNetwork("glvortex"))
        sourceDb.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = networkId, jid = "me@glvortex.net", password = "hunter2", resource = "phone"),
        )

        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )
        assertFalse(raw.contains("hunter2"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)
        assertEquals(0, preview.missingCredentialNetworks)

        target.import(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)

        val importedNetwork = targetDb.networkDao().allNow().single()
        assertEquals("xmpp", importedNetwork.protocol)
        assertNull(importedNetwork.pendingCredentialRequirements)
        assertTrue(importedNetwork.autoConnect)

        val importedAccount = requireNotNull(targetDb.xmppAccountDao().byNetwork(importedNetwork.id))
        assertEquals("me@glvortex.net", importedAccount.jid)
        assertEquals("hunter2", importedAccount.password)
        assertEquals("phone", importedAccount.resource)
    }

    @Test
    fun xmppAccountExcludedExportOmitsPasswordAndImportsAsPendingCredential() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        val networkId = sourceDb.networkDao().insert(xmppNetwork("glvortex"))
        sourceDb.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = networkId, jid = "me@glvortex.net", password = "hunter2"),
        )

        val raw = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
        assertFalse(raw.contains("hunter2"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(raw, importMode = BackupImportMode.MERGE)
        assertEquals(1, preview.missingCredentialNetworks)

        target.import(raw, importMode = BackupImportMode.MERGE)

        val importedNetwork = targetDb.networkDao().allNow().single()
        assertEquals("xmpp", importedNetwork.protocol)
        assertEquals("xmppPassword", importedNetwork.pendingCredentialRequirements)
        assertFalse(importedNetwork.autoConnect)
        assertTrue(importedNetwork.restoreAutoConnect)

        val importedAccount = requireNotNull(targetDb.xmppAccountDao().byNetwork(importedNetwork.id))
        assertEquals("me@glvortex.net", importedAccount.jid)
        assertEquals("", importedAccount.password)
    }

    /**
     * Every XMPP network row shares the identical inert IRC-shaped placeholder identity
     * ([io.github.trevarj.motd.ui.settings.xmpp.buildXmppNetworkEntity]'s KDoc), so
     * [networkIdentityKey] alone cannot distinguish two different accounts. Without the extra JID
     * check in `matchTopLevel`, restoring a backup containing two XMPP accounts onto a device that
     * already has one of them configured would match BOTH incoming accounts to that same single
     * local row — the second one processed would silently overwrite the row (and its `xmpp_accounts`
     * satellite) with its own credentials, discarding the first entirely, instead of landing as its
     * own separate network the way two different IRC accounts already do.
     */
    @Test
    fun twoDistinctXmppAccountsDoNotCollapseOntoOneRow_despiteIdenticalPlaceholderIdentity() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        val aliceId = sourceDb.networkDao().insert(xmppNetwork("alice-account"))
        sourceDb.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = aliceId, jid = "alice@example.org", password = "alice-pw"),
        )
        val bobId = sourceDb.networkDao().insert(xmppNetwork("bob-account"))
        sourceDb.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = bobId, jid = "bob@example.org", password = "bob-pw"),
        )

        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        // The target already has ALICE configured locally — e.g. this device already had that one
        // account set up before restoring a backup that (from some other device) contains both.
        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val existingId = targetDb.networkDao().insert(xmppNetwork("alice-account"))
        targetDb.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = existingId, jid = "alice@example.org", password = "old-alice-pw"),
        )

        target.import(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)

        val networks = targetDb.networkDao().allNow()
        assertEquals(2, networks.size) // alice's existing row updated in place, bob's added as new.
        val accountsByJid = targetDb.xmppAccountDao().allNow().associateBy { it.jid }
        assertEquals(2, accountsByJid.size)
        assertEquals("alice-pw", accountsByJid.getValue("alice@example.org").password)
        assertEquals("bob-pw", accountsByJid.getValue("bob@example.org").password)
    }

    /**
     * XMPP account creation deliberately permits several accounts on the same JID (nothing in
     * `xmpp_accounts` or the account UI forbids it), and those rows are identical on every field
     * matching looks at — placeholder identity, protocol, role, JID. Review fix, P2 finding: the
     * non-consuming `firstOrNull` match therefore resolved every one of them to the same single
     * local candidate, so importing over an existing database overwrote that one row repeatedly and
     * (in REPLACE) deleted the other as "not imported", collapsing two accounts into one.
     */
    @Test
    fun duplicateJidAccountsSurviveAReplaceRoundTrip_insteadOfCollapsingOntoOneRow() = runTest {
        val database = inMemoryDb()
        val repository = repository(database)
        val workId = database.networkDao().insert(xmppNetwork("work"))
        database.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = workId, jid = "me@example.org", password = "pw-work", resource = "laptop"),
        )
        val phoneId = database.networkDao().insert(xmppNetwork("phone"))
        database.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = phoneId, jid = "me@example.org", password = "pw-phone", resource = "phone"),
        )

        val raw = repository.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        val preview = repository.preview(
            raw,
            password = "correct horse battery",
            importMode = BackupImportMode.REPLACE,
        )
        assertEquals(2, preview.updatedNetworks)
        assertEquals(0, preview.addedNetworks)
        assertEquals(0, preview.removedNetworks)

        repository.import(raw, password = "correct horse battery", importMode = BackupImportMode.REPLACE)

        assertEquals(2, database.networkDao().allNow().size)
        val accounts = database.xmppAccountDao().allNow()
        assertEquals(2, accounts.size)
        assertEquals(
            setOf("laptop" to "pw-work", "phone" to "pw-phone"),
            accounts.map { it.resource to it.password }.toSet(),
        )
        assertEquals(setOf("me@example.org"), accounts.map { it.jid }.toSet())
        database.close()
    }

    private fun xmppNetwork(name: String): NetworkEntity = NetworkEntity(
        name = name,
        role = NetworkRole.DIRECT,
        host = "unused.invalid",
        port = 5222,
        tls = true,
        nick = "unused",
        username = "unused",
        realname = "unused",
        autoConnect = true,
        protocol = "xmpp",
    )

    private fun repository(db: io.github.trevarj.motd.data.db.MotdDatabase): ConfigurationBackupRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = DataStoreSettingsRepository(context)
        return ConfigurationBackupRepositoryImpl(
            db = db,
            settingsRepository = settings,
            appearancePrefs = AppearancePrefsImpl(context),
            contentPreviewPrefs = ContentPreviewPrefsImpl(context),
            replyPrefs = ReplyPrefsImpl(context),
            attachmentPrefs = AttachmentPrefsImpl(context),
            voicePrefs = VoicePrefsImpl(context),
            avatarPrefs = AvatarPrefsImpl(context),
            bouncerKindPrefs = BouncerKindPrefsImpl(context),
            pushProviderPrefs = settings,
        )
    }

    private fun secretNetwork(clientCertAlias: String?): NetworkEntity = NetworkEntity(
        name = "libera",
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        tls = true,
        nick = "me",
        username = "me",
        realname = "Me",
        saslMechanism = "PLAIN",
        saslUser = "me",
        saslPassword = "sasl-secret",
        serverPassword = "server-secret",
        clientCertAlias = clientCertAlias,
        autoConnect = true,
        obfsMode = ObfsMode.EMBEDDED_REALITY,
        obfsLink = "vless://secret",
    )
}
