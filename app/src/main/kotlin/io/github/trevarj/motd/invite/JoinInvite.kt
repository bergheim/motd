package io.github.trevarj.motd.invite

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val INVITE_VERSION_V1 = 1
private const val INVITE_VERSION_V2 = 2
private const val MAX_ENCODED_PAYLOAD = 2_048
private const val FALLBACK_URL = "https://github.com/trevarj/motd/releases/latest"
private val PIN_PATTERN = Regex("[0-9a-fA-F]{64}")
private val CHANNEL_PREFIXES = setOf('#', '&', '+', '!')

sealed interface JoinInvite {
    val v: Int
    val networkName: String
    val host: String
    val port: Int
    val tls: Boolean
    val certSha256: String?
}

/** Original channel invitation wire shape. Kept unchanged so existing QR codes remain valid. */
@Serializable
data class JoinInviteV1(
    override val v: Int = INVITE_VERSION_V1,
    override val networkName: String,
    override val host: String,
    override val port: Int,
    override val tls: Boolean = true,
    val channel: String,
    val channelKey: String? = null,
    override val certSha256: String? = null,
) : JoinInvite

/** Network onboarding that opens a DM target; [contactNick] is routing data, not identity proof. */
@Serializable
data class JoinInviteV2(
    override val v: Int = INVITE_VERSION_V2,
    override val networkName: String,
    override val host: String,
    override val port: Int,
    override val tls: Boolean = true,
    val contactNick: String,
    override val certSha256: String? = null,
) : JoinInvite

class InvalidJoinInviteException(
    message: String,
) : IllegalArgumentException(message)

/** Strict codec for QR/deep-link data. No field can contain IRC commands or line breaks. */
object JoinInviteCodec {
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    fun encode(invite: JoinInvite): String {
        val serialized =
            when (val valid = validate(invite)) {
                is JoinInviteV1 -> json.encodeToString(valid)
                is JoinInviteV2 -> json.encodeToString(valid)
            }
        val encoded =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                serialized.toByteArray(StandardCharsets.UTF_8),
            )
        require(encoded.length <= MAX_ENCODED_PAYLOAD) { "invite is too large" }
        return encoded
    }

    fun decode(encoded: String): JoinInvite {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_PAYLOAD) invalid("invalid invite size")
        val data =
            try {
                Base64.getUrlDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                invalid("invalid invite encoding")
            }
        val invite =
            try {
                val objectValue = json.parseToJsonElement(data) as? JsonObject ?: invalid("invalid invite data")
                when (objectValue["v"]?.jsonPrimitive?.intOrNull ?: INVITE_VERSION_V1) {
                    INVITE_VERSION_V1 -> json.decodeFromString<JoinInviteV1>(data)
                    INVITE_VERSION_V2 -> json.decodeFromString<JoinInviteV2>(data)
                    else -> invalid("unsupported invite version")
                }
            } catch (invalid: InvalidJoinInviteException) {
                throw invalid
            } catch (_: Exception) {
                invalid("invalid invite data")
            }
        return validate(invite)
    }

    fun appUri(invite: JoinInvite): String = "motd://invite?v=${encode(invite)}"

    /** HTTPS works in generic QR readers; fragment stays local to browser and carries invite for second scan. */
    fun installUri(invite: JoinInvite): String = "$FALLBACK_URL#motd-invite=${encode(invite)}"

    /** Scanner/paste fallback also accepts the compact payload code by itself. */
    fun parseScanned(raw: String): JoinInvite = runCatching { parse(raw) }.getOrElse { decode(raw.trim()) }

    /** Accepts canonical motd URI or exact GitHub Releases HTTPS envelope emitted by [installUri]. */
    fun parse(raw: String): JoinInvite {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_ENCODED_PAYLOAD + 512) invalid("invite link is too large")
        val uri = runCatching { URI(trimmed) }.getOrElse { invalid("invalid invite link") }
        return when {
            uri.scheme.equals("motd", ignoreCase = true) && uri.host.equals("invite", ignoreCase = true) -> {
                val values = parseQuery(uri.rawQuery)
                if (values.keys != setOf("v") || uri.rawFragment != null) invalid("invalid invite parameters")
                decode(values.getValue("v"))
            }

            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) -> {
                if (uri.rawPath != "/trevarj/motd/releases/latest" || uri.rawQuery != null || uri.userInfo != null || uri.port != -1) {
                    invalid("not a motd invite")
                }
                val marker = "motd-invite="
                val fragment = uri.rawFragment ?: invalid("missing invite payload")
                if (!fragment.startsWith(marker)) invalid("invalid invite parameters")
                decode(fragment.removePrefix(marker))
            }

            else -> {
                invalid("not a motd invite")
            }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) invalid("missing invite payload")
        val pairs =
            raw.split('&').map { item ->
                val key = item.substringBefore('=', "")
                if (key.isEmpty() || '=' !in item) invalid("invalid invite parameters")
                key to URLDecoder.decode(item.substringAfter('='), StandardCharsets.UTF_8.name())
            }
        if (pairs.map { it.first }.distinct().size != pairs.size) invalid("invalid invite parameters")
        return pairs.toMap()
    }

    private fun validate(invite: JoinInvite): JoinInvite {
        val expectedVersion =
            when (invite) {
                is JoinInviteV1 -> INVITE_VERSION_V1
                is JoinInviteV2 -> INVITE_VERSION_V2
            }
        if (invite.v != expectedVersion) invalid("unsupported invite version")
        val networkName = cleanText(invite.networkName, 80, "network name")
        val host = validateHost(invite.host)
        if (invite.port !in 1..65535) invalid("invalid server port")
        val pin = invite.certSha256?.lowercase()?.also { if (!PIN_PATTERN.matches(it)) invalid("invalid certificate pin") }
        if (!invite.tls && pin != null) invalid("plaintext invite cannot carry a certificate pin")
        return when (invite) {
            is JoinInviteV1 -> {
                val channel = cleanToken(invite.channel, 200, "channel")
                if (channel.firstOrNull() !in CHANNEL_PREFIXES) invalid("invalid channel")
                val key = invite.channelKey?.takeIf(String::isNotEmpty)?.let { cleanToken(it, 300, "channel key") }
                invite.copy(
                    networkName = networkName,
                    host = host,
                    channel = channel,
                    channelKey = key,
                    certSha256 = pin,
                )
            }

            is JoinInviteV2 -> {
                val contactNick = cleanToken(invite.contactNick, 100, "contact nickname")
                if (contactNick.firstOrNull() in CHANNEL_PREFIXES || ':' in contactNick) invalid("invalid contact nickname")
                invite.copy(
                    networkName = networkName,
                    host = host,
                    contactNick = contactNick,
                    certSha256 = pin,
                )
            }
        }
    }

    private fun validateHost(raw: String): String {
        val host = cleanToken(raw.trim().removePrefix("[").removeSuffix("]"), 253, "server host")
        if (host.any { it in "/@?#" }) invalid("invalid server host")
        if (':' !in host) {
            val ascii = runCatching { IDN.toASCII(host) }.getOrElse { invalid("invalid server host") }
            if (ascii.isEmpty() || ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) {
                invalid("invalid server host")
            }
        } else if (!host.matches(Regex("[0-9A-Fa-f:.%]+"))) {
            invalid("invalid server host")
        }
        return host
    }

    private fun cleanText(
        raw: String,
        maxBytes: Int,
        label: String,
    ): String {
        val value = raw.trim()
        if (value.isEmpty() || value.hasControls() || value.toByteArray().size > maxBytes) invalid("invalid $label")
        return value
    }

    private fun cleanToken(
        raw: String,
        maxBytes: Int,
        label: String,
    ): String {
        val value = raw.trim()
        if (value.isEmpty() || value.hasControls() || value.any(Char::isWhitespace) || ',' in value || value.toByteArray().size > maxBytes) {
            invalid("invalid $label")
        }
        return value
    }

    private fun String.hasControls(): Boolean = any { it.isISOControl() || it == '\r' || it == '\n' || it == '\u0000' }

    private fun invalid(message: String): Nothing = throw InvalidJoinInviteException(message)
}
