package io.github.trevarj.motd.data.backup

import androidx.room.withTransaction
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.SelfAvatarSetting
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.networkIdentityKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val FORMAT_VERSION = 1
private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
private const val MAX_DECRYPTED_BYTES = 2 * 1024 * 1024
private const val MAX_NETWORKS = 512
private const val PBKDF2_ITERATIONS = 600_000
private const val AES_KEY_BITS = 256
private const val GCM_TAG_BITS = 128

enum class BackupExportMode { CREDENTIALS_EXCLUDED, ENCRYPTED_WITH_CREDENTIALS }
enum class BackupImportMode { MERGE, REPLACE }

data class ConfigurationImportPreview(
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val containsSecrets: Boolean,
    val networkCount: Int,
    val addedNetworks: Int,
    val updatedNetworks: Int,
    val removedNetworks: Int,
    val retainedLocalCredentials: Int,
    val missingCredentialNetworks: Int,
    val settingGroups: List<String>,
)

data class ConfigurationImportResult(
    val addedNetworks: Int,
    val updatedNetworks: Int,
    val removedNetworks: Int,
    val missingCredentialNetworks: Int,
)

interface ConfigurationBackupRepository {
    suspend fun exportToString(
        mode: BackupExportMode,
        password: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String

    suspend fun preview(rawDocument: String, password: String? = null, importMode: BackupImportMode): ConfigurationImportPreview
    suspend fun import(rawDocument: String, password: String? = null, importMode: BackupImportMode): ConfigurationImportResult
    fun isEncrypted(rawDocument: String): Boolean
}

@Singleton
class ConfigurationBackupRepositoryImpl @Inject constructor(
    private val db: MotdDatabase,
    private val settingsRepository: SettingsRepository,
    private val appearancePrefs: AppearancePrefs,
    private val contentPreviewPrefs: ContentPreviewPrefs,
    private val replyPrefs: ReplyPrefs,
    private val attachmentPrefs: AttachmentPrefs,
    private val voicePrefs: VoicePrefs,
    private val avatarPrefs: AvatarPrefs,
    private val bouncerKindPrefs: BouncerKindPrefs,
    private val pushProviderPrefs: PushProviderPrefs,
) : ConfigurationBackupRepository {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val compactJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val random = SecureRandom()

    override suspend fun exportToString(
        mode: BackupExportMode,
        password: String?,
        nowEpochMillis: Long,
    ): String {
        val includeSecrets = mode == BackupExportMode.ENCRYPTED_WITH_CREDENTIALS
        if (includeSecrets) require(!password.isNullOrBlank() && password.length in 12..128) {
            "Encrypted exports require a 12-128 character password."
        }
        val payload = snapshotPayload(includeSecrets)
        val envelope = if (includeSecrets) {
            val encryptedPayload = encryptPayload(
                compactJson.encodeToString(payload).encodeToByteArray(),
                password.orEmpty(),
                BuildConfig.VERSION_NAME,
                nowEpochMillis,
            )
            BackupEnvelope(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAtEpochMillis = nowEpochMillis,
                mode = BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
                encryptedPayload = encryptedPayload,
            )
        } else {
            BackupEnvelope(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAtEpochMillis = nowEpochMillis,
                mode = BackupEnvelopeMode.CREDENTIALS_EXCLUDED,
                payload = payload,
            )
        }
        return json.encodeToString(envelope)
    }

    override fun isEncrypted(rawDocument: String): Boolean =
        decodeEnvelope(rawDocument).mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS

    override suspend fun preview(
        rawDocument: String,
        password: String?,
        importMode: BackupImportMode,
    ): ConfigurationImportPreview {
        val decoded = decodeDocument(rawDocument, password)
        val plan = planImport(decoded.payload, importMode)
        return ConfigurationImportPreview(
            appVersion = decoded.envelope.appVersion,
            exportedAtEpochMillis = decoded.envelope.exportedAtEpochMillis,
            containsSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
            networkCount = decoded.payload.networks.size,
            addedNetworks = plan.added,
            updatedNetworks = plan.updated,
            removedNetworks = plan.removed,
            retainedLocalCredentials = plan.retainedLocalCredentials,
            missingCredentialNetworks = plan.missingCredentialNetworks,
            settingGroups = decoded.payload.settings.groupNames(),
        )
    }

    override suspend fun import(
        rawDocument: String,
        password: String?,
        importMode: BackupImportMode,
    ): ConfigurationImportResult {
        val decoded = decodeDocument(rawDocument, password)
        validatePayload(decoded.payload)
        val plan = planImport(decoded.payload, importMode)

        applySettings(decoded.payload.settings)
        val idMap = mutableMapOf<String, Long>()
        val importedIds = mutableSetOf<Long>()
        val includeSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS
        db.withTransaction {
            val current = db.networkDao().allNow()
            // XMPP satellite rows, keyed by their owning network id, for the same retain-on-merge /
            // missing-credential logic the shared saslPassword/serverPassword/obfsLink fields already
            // get (see PortableXmppAccount.toEntity and PortableNetwork.missingRequirements), and for
            // matchTopLevel's JID-based disambiguation below.
            val localXmppAccounts = db.xmppAccountDao().allNow().associateBy { it.networkId }
            val rootsAndDirect = decoded.payload.networks.filter { it.role != NetworkRole.BOUNCER_CHILD }
                .sortedBy { it.ordering }
            rootsAndDirect.forEach { portable ->
                val local = matchTopLevel(portable, current, localXmppAccounts)
                val resolved = portable.toEntity(
                    includeSecrets = includeSecrets,
                    parentId = null,
                    local = local,
                    localXmppAccount = localXmppAccounts[local?.id],
                )
                val id = upsertResolvedNetwork(resolved)
                idMap[portable.exportId] = id
                importedIds += id
                upsertXmppSatellite(portable, id, includeSecrets, localXmppAccounts[local?.id])
            }
            decoded.payload.networks.filter { it.role == NetworkRole.BOUNCER_CHILD }
                .sortedBy { it.ordering }
                .forEach { portable ->
                    val parentId = idMap[portable.parentExportId] ?: return@forEach
                    val local = current.firstOrNull {
                        it.role == NetworkRole.BOUNCER_CHILD &&
                            it.parentId == parentId &&
                            it.bouncerNetId == portable.bouncerNetId
                    }
                    val resolved = portable.toEntity(
                        includeSecrets = includeSecrets,
                        parentId = parentId,
                        local = local,
                    )
                    val id = upsertResolvedNetwork(resolved)
                    idMap[portable.exportId] = id
                    importedIds += id
                }
            if (importMode == BackupImportMode.REPLACE) {
                current.asSequence()
                    .filter { it.id !in importedIds }
                    .filter { it.parentId == null || it.parentId !in importedIds }
                    .forEach { db.networkDao().deleteLocalTree(it.id) }
            }
        }
        applyRemappedNetworkPrefs(decoded.payload, idMap, importMode)
        return ConfigurationImportResult(
            addedNetworks = plan.added,
            updatedNetworks = plan.updated,
            removedNetworks = plan.removed,
            missingCredentialNetworks = plan.missingCredentialNetworks,
        )
    }

    /** [portable.xmppAccount] is null for every non-XMPP network, making this a safe no-op call for them. */
    private suspend fun upsertXmppSatellite(
        portable: PortableNetwork,
        networkId: Long,
        includeSecrets: Boolean,
        localAccount: XmppAccountEntity?,
    ) {
        val account = portable.xmppAccount ?: return
        db.xmppAccountDao().upsert(account.toEntity(networkId, includeSecrets, localAccount))
    }

    private suspend fun snapshotPayload(includeSecrets: Boolean): BackupPayload {
        val networks = db.networkDao().allNow().sortedWith(compareBy<NetworkEntity> { it.parentId ?: 0L }.thenBy { it.ordering })
        val exportIds = networks.associate { it.id to "network-${it.id}" }
        val zncIds = bouncerKindPrefs.zncNetworkIds.first()
        val xmppAccounts = db.xmppAccountDao().allNow().associateBy { it.networkId }
        val selfAvatars = networks.mapNotNull { network ->
            val setting = avatarPrefs.selfSetting(network.id).first()
            if (setting == SelfAvatarSetting.Unmanaged) null else PortableSelfAvatar(
                networkExportId = exportIds.getValue(network.id),
                setting = setting.toPortable(),
            )
        }
        return BackupPayload(
            networks = networks.map { it.toPortable(exportIds, includeSecrets, zncIds, xmppAccounts[it.id]) },
            settings = PortableSettings(
                general = settingsRepository.settings.first(),
                appearance = appearancePrefs.config.first(),
                contentPreviews = contentPreviewPrefs.config.first(),
                replies = replyPrefs.config.first(),
                attachments = attachmentPrefs.config.first(),
                voice = voicePrefs.config.first(),
                showSharedAvatars = avatarPrefs.config.first().showSharedAvatars,
                selfAvatars = selfAvatars,
                pushProvider = pushProviderPrefs.provider.first(),
            ),
        )
    }

    private fun decodeDocument(rawDocument: String, password: String?): DecodedDocument {
        val envelope = decodeEnvelope(rawDocument)
        require(envelope.formatVersion == FORMAT_VERSION) { "Unsupported backup format ${envelope.formatVersion}." }
        val payload = when (envelope.mode) {
            BackupEnvelopeMode.CREDENTIALS_EXCLUDED -> envelope.payload
                ?: error("Backup payload is missing.")
            BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS -> {
                require(!password.isNullOrBlank()) { "This backup requires its export password." }
                val encryptedPayload = envelope.encryptedPayload ?: error("Encrypted backup payload is missing.")
                val decrypted = decryptPayload(encryptedPayload, password, envelope.appVersion, envelope.exportedAtEpochMillis)
                require(decrypted.size <= MAX_DECRYPTED_BYTES) { "Backup payload is too large." }
                compactJson.decodeFromString<BackupPayload>(decrypted.decodeToString())
            }
        }
        validatePayload(payload)
        return DecodedDocument(envelope, payload)
    }

    private fun decodeEnvelope(rawDocument: String): BackupEnvelope {
        require(rawDocument.encodeToByteArray().size <= MAX_DOCUMENT_BYTES) { "Backup file is too large." }
        return compactJson.decodeFromString<BackupEnvelope>(rawDocument)
    }

    private fun validatePayload(payload: BackupPayload) {
        require(payload.version == FORMAT_VERSION) { "Unsupported payload version ${payload.version}." }
        require(payload.networks.size <= MAX_NETWORKS) { "Too many networks in backup." }
        val ids = payload.networks.map { it.exportId }
        require(ids.size == ids.toSet().size) { "Backup contains duplicate network ids." }
        val idSet = ids.toSet()
        payload.networks.forEach { network ->
            require(network.exportId.isNotBlank()) { "Backup contains a network without an id." }
            require(network.name.isNotBlank()) { "Backup contains a network without a name." }
            require(network.host.isNotBlank()) { "Backup contains a network without a host." }
            require(network.port in 1..65535) { "Backup contains an invalid port." }
            require(network.nick.isNotBlank()) { "Backup contains a network without a nick." }
            if (network.role == NetworkRole.BOUNCER_CHILD) {
                require(!network.parentExportId.isNullOrBlank() && network.parentExportId in idSet) {
                    "Backup contains a bouncer child without a valid parent."
                }
                require(!network.bouncerNetId.isNullOrBlank()) {
                    "Backup contains a bouncer child without a bouncer network id."
                }
            }
            network.wsUrl?.let { require(it.startsWith("wss://")) { "Backup contains an invalid WebSocket URL." } }
            network.proxyPort?.let { require(it in 1..65535) { "Backup contains an invalid proxy port." } }
            if (network.protocol == XMPP_PROTOCOL_ID) {
                require(network.xmppAccount != null && network.xmppAccount.jid.isNotBlank()) {
                    "Backup contains an XMPP network without an account."
                }
            }
        }
    }

    private suspend fun planImport(payload: BackupPayload, importMode: BackupImportMode): ImportPlan {
        val current = db.networkDao().allNow()
        val localXmppAccounts = db.xmppAccountDao().allNow().associateBy { it.networkId }
        val matched = mutableSetOf<Long>()
        var added = 0
        var updated = 0
        var retainedLocalCredentials = 0
        var missingCredentialNetworks = 0
        val parentMatches = mutableMapOf<String, Long>()
        payload.networks.filter { it.role != NetworkRole.BOUNCER_CHILD }.forEach { portable ->
            val local = matchTopLevel(portable, current, localXmppAccounts)
            if (local == null) added++ else {
                updated++
                matched += local.id
                parentMatches[portable.exportId] = local.id
                if (portable.retainsAnyLocalSecret(local, localXmppAccounts[local.id])) retainedLocalCredentials++
            }
            if (portable.missingCredentials(local, localXmppAccounts[local?.id])) missingCredentialNetworks++
        }
        payload.networks.filter { it.role == NetworkRole.BOUNCER_CHILD }.forEach { portable ->
            val parentId = parentMatches[portable.parentExportId]
            val local = current.firstOrNull {
                parentId != null && it.role == NetworkRole.BOUNCER_CHILD &&
                    it.parentId == parentId && it.bouncerNetId == portable.bouncerNetId
            }
            if (local == null) added++ else {
                updated++
                matched += local.id
                if (portable.retainsAnyLocalSecret(local, localXmppAccounts[local.id])) retainedLocalCredentials++
            }
            if (portable.missingCredentials(local, localXmppAccounts[local?.id])) missingCredentialNetworks++
        }
        val removed = if (importMode == BackupImportMode.REPLACE) current.count { it.id !in matched } else 0
        return ImportPlan(added, updated, removed, retainedLocalCredentials, missingCredentialNetworks)
    }

    /**
     * [networkIdentityKey] alone is not enough to tell two XMPP accounts apart: every XMPP row
     * shares the identical inert IRC-shaped placeholder identity
     * ([io.github.trevarj.motd.ui.settings.xmpp.buildXmppNetworkEntity]'s KDoc — same host, port,
     * and nick for every account), so without the extra JID check below, importing a backup with
     * two XMPP accounts onto a device that already has one configured would match BOTH incoming
     * accounts to that same single local row, silently clobbering one with the other's credentials
     * instead of adding the second as its own network. [protocol] is compared first as a general
     * safeguard against a coincidental cross-protocol key collision, even though today's IRC-shaped
     * key makes that vanishingly unlikely on its own.
     */
    private fun matchTopLevel(
        portable: PortableNetwork,
        current: List<NetworkEntity>,
        localXmppAccounts: Map<Long, XmppAccountEntity>,
    ): NetworkEntity? {
        val probe = portable.toEntity(includeSecrets = true, parentId = null, local = null)
        return current.firstOrNull { candidate ->
            candidate.role == portable.role && candidate.role != NetworkRole.BOUNCER_CHILD &&
                candidate.protocol == portable.protocol &&
                networkIdentityKey(candidate) == networkIdentityKey(probe) &&
                (portable.xmppAccount == null || localXmppAccounts[candidate.id]?.jid == portable.xmppAccount.jid)
        }
    }

    private suspend fun upsertResolvedNetwork(network: NetworkEntity): Long {
        return if (network.id == 0L) db.networkDao().insert(network) else {
            db.networkDao().update(network)
            network.id
        }
    }

    private suspend fun applySettings(settings: PortableSettings) {
        settings.general?.let {
            val current = settingsRepository.settings.first()
            settingsRepository.setThemeMode(it.themeMode)
            settingsRepository.setDynamicColor(it.dynamicColor)
            settingsRepository.setDeliveryMode(it.deliveryMode)
            settingsRepository.setLayoutDensity(it.layoutDensity)
            settingsRepository.setNickColorsEnabled(it.nickColorsEnabled)
            settingsRepository.setNickColorPalette(it.nickColorPalette)
            current.nickColorOverrides.keys.filter { nick -> nick !in it.nickColorOverrides }
                .forEach { nick -> settingsRepository.setNickColorOverride(nick, null) }
            it.nickColorOverrides.forEach { (nick, hue) -> settingsRepository.setNickColorOverride(nick, hue) }
            current.friends.filter { nick -> nick !in it.friends }
                .forEach { nick -> settingsRepository.setFriend(nick, false) }
            it.friends.forEach { nick -> settingsRepository.setFriend(nick, true) }
            current.fools.filter { nick -> nick !in it.fools }
                .forEach { nick -> settingsRepository.setFool(nick, false) }
            it.fools.forEach { nick -> settingsRepository.setFool(nick, true) }
            settingsRepository.setFoolsMode(it.foolsMode)
            settingsRepository.setShowJoinPartQuit(it.showJoinPartQuit)
            settingsRepository.setAvatarStyle(it.avatarStyle)
            settingsRepository.setChatWallpaper(it.chatWallpaper)
            settingsRepository.setShowComposerEmoji(it.showComposerEmoji)
            settingsRepository.setChatSoundsEnabled(it.chatSoundsEnabled)
        }
        settings.appearance?.let {
            appearancePrefs.setTheme(it.theme)
            appearancePrefs.setTrueBlack(it.trueBlack)
            appearancePrefs.setFollowSystem(it.followSystem)
            appearancePrefs.setWallpaper(it.wallpaper)
            appearancePrefs.setUiFontScale(it.uiFontScalePercent)
            appearancePrefs.setConversationFontScale(it.conversationFontScalePercent)
        }
        settings.contentPreviews?.let {
            contentPreviewPrefs.setShowImages(it.showImages)
            contentPreviewPrefs.setShowLinkPreviews(it.showLinkPreviews)
        }
        settings.replies?.let { replyPrefs.setVisibleChannelPrefix(it.visibleChannelPrefix) }
        settings.attachments?.let { attachmentPrefs.setConfig(it) }
        settings.voice?.let {
            voicePrefs.replace(it)
        }
        settings.showSharedAvatars?.let { avatarPrefs.setShowSharedAvatars(it) }
        settings.pushProvider?.let { pushProviderPrefs.setProvider(it) }
    }

    private suspend fun applyRemappedNetworkPrefs(
        payload: BackupPayload,
        idMap: Map<String, Long>,
        importMode: BackupImportMode,
    ) {
        if (importMode == BackupImportMode.REPLACE) {
            bouncerKindPrefs.zncNetworkIds.first().forEach { bouncerKindPrefs.clear(it) }
        }
        payload.networks.forEach { portable ->
            val localId = idMap[portable.exportId] ?: return@forEach
            if (portable.znc) bouncerKindPrefs.markZnc(localId) else bouncerKindPrefs.clear(localId)
        }
        payload.settings.selfAvatars.forEach { entry ->
            val localId = idMap[entry.networkExportId] ?: return@forEach
            avatarPrefs.setSelfSetting(localId, entry.setting.toSelfAvatarSetting())
        }
    }

    private fun encryptPayload(plainText: ByteArray, password: String, appVersion: String, exportedAt: Long): EncryptedPayload {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(encryptionAad(appVersion, exportedAt).encodeToByteArray())
        val ciphertext = cipher.doFinal(plainText)
        return EncryptedPayload(
            kdf = "PBKDF2WithHmacSHA256",
            iterations = PBKDF2_ITERATIONS,
            salt = salt.b64(),
            cipher = "AES-256-GCM",
            nonce = nonce.b64(),
            ciphertext = ciphertext.b64(),
        )
    }

    private fun decryptPayload(encrypted: EncryptedPayload, password: String, appVersion: String, exportedAt: Long): ByteArray {
        require(encrypted.kdf == "PBKDF2WithHmacSHA256" && encrypted.cipher == "AES-256-GCM") {
            "Unsupported backup encryption."
        }
        require(encrypted.iterations in 100_000..PBKDF2_ITERATIONS) { "Unsupported backup work factor." }
        val salt = encrypted.salt.fromB64()
        val nonce = encrypted.nonce.fromB64()
        val ciphertext = encrypted.ciphertext.fromB64()
        val key = deriveKey(password, salt, encrypted.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(encryptionAad(appVersion, exportedAt).encodeToByteArray())
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun encryptionAad(appVersion: String, exportedAt: Long): String =
        "motdconfig-v1|$appVersion|$exportedAt|${BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS.name}"
}

@Serializable
private data class BackupEnvelope(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val mode: BackupEnvelopeMode,
    val payload: BackupPayload? = null,
    val encryptedPayload: EncryptedPayload? = null,
)

@Serializable
private enum class BackupEnvelopeMode { CREDENTIALS_EXCLUDED, ENCRYPTED_WITH_CREDENTIALS }

@Serializable
private data class EncryptedPayload(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val cipher: String,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
private data class BackupPayload(
    val version: Int = FORMAT_VERSION,
    val networks: List<PortableNetwork>,
    val settings: PortableSettings,
)

@Serializable
private data class PortableSettings(
    val general: Settings? = null,
    val appearance: AppearanceConfig? = null,
    val contentPreviews: ContentPreviewConfig? = null,
    val replies: ReplyConfig? = null,
    val attachments: PasteBackendConfig? = null,
    val voice: VoiceConfig? = null,
    val showSharedAvatars: Boolean? = null,
    val selfAvatars: List<PortableSelfAvatar> = emptyList(),
    val pushProvider: PushProvider? = null,
) {
    fun groupNames(): List<String> = buildList {
        if (general != null) add("general")
        if (appearance != null) add("appearance")
        if (contentPreviews != null) add("content previews")
        if (replies != null) add("replies")
        if (attachments != null) add("uploads")
        if (voice != null) add("voice")
        if (showSharedAvatars != null || selfAvatars.isNotEmpty()) add("avatars")
        if (pushProvider != null) add("delivery")
    }
}

@Serializable
private data class PortableSelfAvatar(
    val networkExportId: String,
    val setting: PortableSelfAvatarSetting,
)

@Serializable
private data class PortableSelfAvatarSetting(
    val mode: PortableSelfAvatarMode,
    val url: String? = null,
)

@Serializable
private enum class PortableSelfAvatarMode { UNMANAGED, CLEARED, SET }

@Serializable
private data class PortableNetwork(
    val exportId: String,
    val name: String,
    val role: NetworkRole,
    val parentExportId: String? = null,
    val bouncerNetId: String? = null,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val nick: String,
    val username: String,
    val realname: String,
    val saslMechanism: String,
    val saslUser: String? = null,
    val saslPassword: String? = null,
    val hadSaslPassword: Boolean = false,
    val serverPassword: String? = null,
    val hadServerPassword: Boolean = false,
    val initialAwayMessage: String? = null,
    val hadClientCertificate: Boolean = false,
    val autoConnect: Boolean,
    val ordering: Int,
    val wsUrl: String? = null,
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val obfsLink: String? = null,
    val hadObfsLink: Boolean = false,
    val znc: Boolean = false,
    /** Backend discriminator; defaults to IRC so backups written before it existed still decode. */
    val protocol: String = "irc",
    /** Present only for [protocol] == "xmpp" — the `xmpp_accounts` satellite row for this network
     *  (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership": a backend persists
     *  its own account/protocol detail in its own per-protocol table, not nullable columns on the
     *  shared row, and that split carries through to its backup representation too). */
    val xmppAccount: PortableXmppAccount? = null,
) {
    override fun toString(): String =
        "PortableNetwork(exportId=$exportId, name=$name, role=$role, host=$host:$port)"
}

/** Portable form of [XmppAccountEntity] (review fix: the backup used to serialize only
 *  [NetworkEntity], so an XMPP account's JID/password/resource — which live exclusively in this
 *  satellite table — never round-tripped through export/import at all). [password] follows the
 *  exact same include-secrets policy [PortableNetwork.saslPassword]/[hadSaslPassword] already do. */
@Serializable
private data class PortableXmppAccount(
    val jid: String,
    val password: String? = null,
    val hadPassword: Boolean = false,
    val resource: String? = null,
) {
    override fun toString(): String = "PortableXmppAccount(jid=$jid)"
}

/** Must match [io.github.trevarj.motd.xmppbackend.XmppChatBackend.XMPP_PROTOCOL]'s value. Not
 *  imported directly from the XMPP adapter package to keep this shared backup file free of any
 *  adapter dependency; see this file's satellite-handling KDocs for why it has one anyway. */
private const val XMPP_PROTOCOL_ID = "xmpp"

private data class DecodedDocument(
    val envelope: BackupEnvelope,
    val payload: BackupPayload,
)

private data class ImportPlan(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val retainedLocalCredentials: Int,
    val missingCredentialNetworks: Int,
)

private fun NetworkEntity.toPortable(
    exportIds: Map<Long, String>,
    includeSecrets: Boolean,
    zncIds: Set<Long>,
    xmppAccount: XmppAccountEntity?,
): PortableNetwork = PortableNetwork(
    exportId = exportIds.getValue(id),
    name = name,
    role = role,
    protocol = protocol,
    parentExportId = parentId?.let(exportIds::get),
    bouncerNetId = bouncerNetId,
    host = host,
    port = port,
    tls = tls,
    nick = nick,
    username = username,
    realname = realname,
    saslMechanism = saslMechanism,
    saslUser = saslUser,
    saslPassword = saslPassword.takeIf { includeSecrets },
    hadSaslPassword = !saslPassword.isNullOrBlank(),
    serverPassword = serverPassword.takeIf { includeSecrets },
    hadServerPassword = !serverPassword.isNullOrBlank(),
    initialAwayMessage = initialAwayMessage,
    hadClientCertificate = !clientCertAlias.isNullOrBlank(),
    autoConnect = restoreAutoConnect.takeIf { pendingCredentialRequirements != null } ?: autoConnect,
    ordering = ordering,
    wsUrl = wsUrl,
    obfsMode = obfsMode,
    proxyHost = proxyHost,
    proxyPort = proxyPort,
    obfsLink = obfsLink.takeIf { includeSecrets },
    hadObfsLink = !obfsLink.isNullOrBlank(),
    znc = id in zncIds,
    xmppAccount = xmppAccount?.toPortable(includeSecrets),
)

private fun XmppAccountEntity.toPortable(includeSecrets: Boolean): PortableXmppAccount = PortableXmppAccount(
    jid = jid,
    password = password.takeIf { includeSecrets },
    hadPassword = password.isNotBlank(),
    resource = resource,
)

private fun PortableNetwork.toEntity(
    includeSecrets: Boolean,
    parentId: Long?,
    local: NetworkEntity?,
    localXmppAccount: XmppAccountEntity? = null,
): NetworkEntity {
    val retainedSasl = if (!includeSecrets && saslPassword == null) local?.saslPassword else saslPassword
    val retainedServerPassword = if (!includeSecrets && serverPassword == null) local?.serverPassword else serverPassword
    val retainedObfsLink = if (!includeSecrets && obfsLink == null) local?.obfsLink else obfsLink
    val retainedXmppPassword = xmppAccount?.retainedPassword(includeSecrets, localXmppAccount)
    val requirements = missingRequirements(
        localSaslPassword = retainedSasl,
        localServerPassword = retainedServerPassword,
        localObfsLink = retainedObfsLink,
        localXmppPassword = retainedXmppPassword,
    )
    return NetworkEntity(
        id = local?.id ?: 0L,
        name = name,
        role = role,
        protocol = protocol,
        parentId = parentId,
        bouncerNetId = bouncerNetId,
        host = host,
        port = port,
        tls = tls,
        nick = nick,
        username = username,
        realname = realname,
        saslMechanism = saslMechanism,
        saslUser = saslUser,
        saslPassword = retainedSasl,
        serverPassword = retainedServerPassword,
        initialAwayMessage = initialAwayMessage,
        clientCertAlias = null,
        autoConnect = autoConnect && requirements.isEmpty(),
        ordering = ordering,
        wsUrl = wsUrl,
        obfsMode = obfsMode,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        obfsLink = retainedObfsLink,
        pendingCredentialRequirements = requirements.takeIf { it.isNotEmpty() }?.joinToString(","),
        restoreAutoConnect = autoConnect,
    )
}

/** The password [PortableXmppAccount.toEntity] would resolve to, without building a full
 *  [XmppAccountEntity] — shared by [PortableNetwork.toEntity]'s missing-requirements check so the
 *  two stay in exact agreement about what the imported account's password will actually be. */
private fun PortableXmppAccount.retainedPassword(includeSecrets: Boolean, local: XmppAccountEntity?): String? =
    if (!includeSecrets && password == null) local?.password else password

/** Resolve the [XmppAccountEntity] to upsert for this network id, following the identical
 *  include-secrets retention policy [PortableNetwork.toEntity]'s `retainedSasl`/etc. already use:
 *  an excluded-secrets import falls back to whatever this same network already has locally, rather
 *  than blanking out a real, already-present password. */
private fun PortableXmppAccount.toEntity(
    networkId: Long,
    includeSecrets: Boolean,
    local: XmppAccountEntity?,
): XmppAccountEntity = XmppAccountEntity(
    networkId = networkId,
    jid = jid,
    password = retainedPassword(includeSecrets, local).orEmpty(),
    resource = resource,
)

private fun PortableNetwork.retainsAnyLocalSecret(local: NetworkEntity, localXmppAccount: XmppAccountEntity?): Boolean =
    (hadSaslPassword && saslPassword == null && !local.saslPassword.isNullOrBlank()) ||
        (hadServerPassword && serverPassword == null && !local.serverPassword.isNullOrBlank()) ||
        (hadObfsLink && obfsLink == null && !local.obfsLink.isNullOrBlank()) ||
        (xmppAccount?.hadPassword == true && xmppAccount.password == null && !localXmppAccount?.password.isNullOrBlank())

private fun PortableNetwork.missingCredentials(local: NetworkEntity?, localXmppAccount: XmppAccountEntity?): Boolean =
    missingRequirements(
        localSaslPassword = local?.saslPassword,
        localServerPassword = local?.serverPassword,
        localObfsLink = local?.obfsLink,
        localXmppPassword = localXmppAccount?.password,
    ).isNotEmpty()

private fun PortableNetwork.missingRequirements(
    localSaslPassword: String?,
    localServerPassword: String?,
    localObfsLink: String?,
    localXmppPassword: String? = null,
): List<String> = buildList {
    if (hadSaslPassword && saslPassword.isNullOrBlank() && localSaslPassword.isNullOrBlank()) add("saslPassword")
    if (hadServerPassword && serverPassword.isNullOrBlank() && localServerPassword.isNullOrBlank()) add("serverPassword")
    if (hadObfsLink && obfsLink.isNullOrBlank() && localObfsLink.isNullOrBlank()) add("obfsLink")
    if (hadClientCertificate) add("clientCertificate")
    if (xmppAccount?.hadPassword == true && xmppAccount.password.isNullOrBlank() && localXmppPassword.isNullOrBlank()) {
        add("xmppPassword")
    }
}

private fun SelfAvatarSetting.toPortable(): PortableSelfAvatarSetting = when (this) {
    SelfAvatarSetting.Unmanaged -> PortableSelfAvatarSetting(PortableSelfAvatarMode.UNMANAGED)
    SelfAvatarSetting.ExplicitlyCleared -> PortableSelfAvatarSetting(PortableSelfAvatarMode.CLEARED)
    is SelfAvatarSetting.Set -> PortableSelfAvatarSetting(PortableSelfAvatarMode.SET, url)
}

private fun PortableSelfAvatarSetting.toSelfAvatarSetting(): SelfAvatarSetting = when (mode) {
    PortableSelfAvatarMode.UNMANAGED -> SelfAvatarSetting.Unmanaged
    PortableSelfAvatarMode.CLEARED -> SelfAvatarSetting.ExplicitlyCleared
    PortableSelfAvatarMode.SET -> url?.let(SelfAvatarSetting::Set) ?: SelfAvatarSetting.Unmanaged
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
