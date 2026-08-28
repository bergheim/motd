package io.github.trevarj.motd.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GesturePrefsImpl
import io.github.trevarj.motd.gesture.removeNode
import io.github.trevarj.motd.gesture.updateNode
import kotlinx.coroutines.flow.first
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
    fun credentialsExcludedExportOmitsSecretsAndImportsAsPendingCredentials() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = "device-cert"))

            val raw =
                source.exportToString(
                    mode = BackupExportMode.CREDENTIALS_EXCLUDED,
                    nowEpochMillis = 1_000L,
                )

            assertFalse(raw.contains("sasl-secret"))
            assertFalse(raw.contains("server-secret"))
            assertFalse(raw.contains("nickserv-secret"))
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
            assertNull(imported.nickServPassword)
            assertEquals("PASSWORD_NICK", imported.nickServIdentifySyntax)
            assertTrue(imported.nickServRecoveryEnabled)
            assertEquals("GHOST,REGAIN", imported.nickServRecoverySequence)
            assertNull(imported.obfsLink)
            assertEquals(
                "saslPassword,serverPassword,nickServPassword,obfsLink,clientCertificate",
                imported.pendingCredentialRequirements,
            )
            assertFalse(imported.autoConnect)
            assertEquals(true, imported.restoreAutoConnect)
        }

    @Test
    fun encryptedExportRoundTripsCredentials() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))

            val raw =
                source.exportToString(
                    mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                    password = "correct horse battery",
                    nowEpochMillis = 1_000L,
                )

            assertFalse(raw.contains("sasl-secret"))
            assertFalse(raw.contains("server-secret"))
            assertFalse(raw.contains("nickserv-secret"))
            assertFalse(raw.contains("vless://secret"))

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            val preview =
                target.preview(
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
            assertEquals("nickserv-secret", imported.nickServPassword)
            assertEquals("PASSWORD_NICK", imported.nickServIdentifySyntax)
            assertTrue(imported.nickServRecoveryEnabled)
            assertEquals("GHOST,REGAIN", imported.nickServRecoverySequence)
            assertEquals("vless://secret", imported.obfsLink)
            assertNull(imported.pendingCredentialRequirements)
            assertEquals(true, imported.autoConnect)
        }

    @Test
    fun wrongPasswordRejectsEncryptedImportWithoutMutation() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
            val raw =
                source.exportToString(
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
     * The gesture lab's on/off flag never travels, but an authored menu does — and only once it
     * differs from the shipped tree, so a backup from an untouched install cannot pin a stale menu
     * onto the device it is restored to.
     */
    @Test
    fun gestureMenuTravelsOnlyWhenItDiffersFromTheDefault() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val gesturePrefs = GesturePrefsImpl(context)
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)

            gesturePrefs.setMenu(GestureMenuConfig())
            val untouched = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertFalse(
                source.preview(untouched, importMode = BackupImportMode.MERGE).settingGroups.contains("gesture menu"),
            )

            val edited =
                GestureMenuConfig()
                    .updateNode("default-away") { (it as GestureNode.Leaf).copy(label = "Step out") }
                    .removeNode("default-networks")
            gesturePrefs.setMenu(edited)
            val raw = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            assertTrue(raw.contains("Step out"))
            val target = repository(inMemoryDb())
            assertTrue(target.preview(raw, importMode = BackupImportMode.MERGE).settingGroups.contains("gesture menu"))

            gesturePrefs.setMenu(GestureMenuConfig())
            target.import(raw, importMode = BackupImportMode.MERGE)

            assertEquals(edited, gesturePrefs.menu.first())
            gesturePrefs.setMenu(GestureMenuConfig())
        }

    /** Stage-1 appearance fields (font, timestamps, spacing, bubbles, launcher icon) travel with a backup. */
    @Test
    fun newAppearanceFieldsRoundTripThroughExportAndImport() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appearancePrefs = AppearancePrefsImpl(context)
            val source = repository(inMemoryDb())

            appearancePrefs.setFontChoice(FontChoice.JETBRAINS_MONO)
            appearancePrefs.setShowTimestamps(false)
            appearancePrefs.setTimeFormat(TimeFormat.CUSTOM)
            appearancePrefs.setCustomTimeFormatPattern("yyyy-MM-dd HH:mm:ss")
            appearancePrefs.setMessageSpacing(MessageSpacing.RELAXED)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.SQUARE)
            appearancePrefs.setLauncherIcon(LauncherIcon.GRUVBOX)
            // Only the display name travels; the font binary itself is not part of the backup payload.
            appearancePrefs.setCustomFontName("Iosevka Term.ttf")

            val raw = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            appearancePrefs.setFontChoice(FontChoice.SYSTEM)
            appearancePrefs.setShowTimestamps(true)
            appearancePrefs.setTimeFormat(TimeFormat.AUTO)
            appearancePrefs.setCustomTimeFormatPattern("HH:mm")
            appearancePrefs.setMessageSpacing(MessageSpacing.DEFAULT)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.ROUNDED)
            appearancePrefs.setLauncherIcon(LauncherIcon.DEFAULT)
            appearancePrefs.setCustomFontName("")

            source.import(raw, importMode = BackupImportMode.MERGE)

            val config = appearancePrefs.config.first()
            assertEquals(FontChoice.JETBRAINS_MONO, config.fontChoice)
            assertEquals(false, config.showTimestamps)
            assertEquals(TimeFormat.CUSTOM, config.timeFormat)
            assertEquals("yyyy-MM-dd HH:mm:ss", config.customTimeFormatPattern)
            assertEquals(MessageSpacing.RELAXED, config.messageSpacing)
            assertEquals(BubbleCornerStyle.SQUARE, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.GRUVBOX, config.launcherIcon)
            assertEquals("Iosevka Term.ttf", config.customFontName)
        }

    @Test
    fun oldSettingsBackupDefaultsComposerFormattingToolsOn() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            settings.setShowComposerFormattingTools(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            val oldRaw = raw.replace(Regex(""""showComposerFormattingTools"\s*:\s*false\s*,"""), "")
            assertFalse(oldRaw.contains("showComposerFormattingTools"))

            settings.setShowComposerFormattingTools(true)
            backup.import(oldRaw, importMode = BackupImportMode.MERGE)
            assertTrue(settings.settings.first().showComposerFormattingTools)
        }

    @Test
    fun composerFormattingToolsRoundTripThroughSettingsBackup() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            settings.setShowComposerEmoji(false)
            settings.setShowComposerFormattingTools(false)
            settings.setShowRedactedMessages(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            settings.setShowComposerEmoji(true)
            settings.setShowComposerFormattingTools(true)
            settings.setShowRedactedMessages(true)
            backup.import(raw, importMode = BackupImportMode.MERGE)

            val restored = settings.settings.first()
            assertFalse(restored.showComposerEmoji)
            assertFalse(restored.showComposerFormattingTools)
            assertFalse(restored.showRedactedMessages)

            settings.setShowComposerEmoji(true)
            settings.setShowComposerFormattingTools(true)
            settings.setShowRedactedMessages(true)
        }

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
            voicePrefs = VoicePrefs(context),
            avatarPrefs = AvatarPrefsImpl(context),
            bouncerKindPrefs = BouncerKindPrefsImpl(context),
            gesturePrefs = GesturePrefsImpl(context),
        )
    }

    private fun secretNetwork(clientCertAlias: String?): NetworkEntity =
        NetworkEntity(
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
            nickServPassword = "nickserv-secret",
            nickServIdentifySyntax = "PASSWORD_NICK",
            nickServRecoveryEnabled = true,
            nickServRecoverySequence = "GHOST,REGAIN",
            clientCertAlias = clientCertAlias,
            autoConnect = true,
            obfsMode = ObfsMode.EMBEDDED_REALITY,
            obfsLink = "vless://secret",
        )
}
