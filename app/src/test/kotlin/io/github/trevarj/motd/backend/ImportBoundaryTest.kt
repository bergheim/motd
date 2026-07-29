package io.github.trevarj.motd.backend

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 1 acceptance-gate boundary enforcement (docs/backend-neutral-xmpp-rollout.md): "shared
 * packages have no forbidden IRC or XMPP imports." This is a ratchet, not a snapshot: [exemptions]
 * records today's audited reality (grep-verified against `app/src/main` when this test was written)
 * so today's owned IRC coupling keeps compiling, while any *new* forbidden import anywhere else
 * fails the build. Plain JUnit, no Robolectric/Room — this only walks the filesystem.
 */
class ImportBoundaryTest {

    private data class ForbiddenPrefix(val prefix: String, val reason: String)
    private data class ForbiddenImport(val fqName: String, val reason: String)
    private data class Exemption(val path: String, val reason: String)

    private val packageRoot = "io.github.trevarj.motd"
    private val packageRootPath = packageRoot.replace('.', '/')
    private val forbiddenSubstring = "smack"
    private val forbiddenSubstringReason =
        "XMPP/Smack must not exist before PR 2 (docs/backend-neutral-xmpp-rollout.md)."

    /** "FORBIDDEN import prefixes anywhere in app/src/main" — no adapter-owned exception applies. */
    private val forbiddenPrefixes = listOf(
        ForbiddenPrefix(
            "org.jivesoftware",
            "XMPP/Smack must not exist before PR 2 (docs/backend-neutral-xmpp-rollout.md).",
        ),
        ForbiddenPrefix(
            "org.igniterealtime",
            "XMPP/Smack must not exist before PR 2 (docs/backend-neutral-xmpp-rollout.md).",
        ),
    )

    /**
     * "FORBIDDEN wire-type imports outside adapter-owned packages" — matched as a whole imported
     * name (word boundary) so e.g. `IrcClientConfig` never trips the `IrcClient` rule.
     */
    private val forbiddenWireImports = listOf(
        ForbiddenImport(
            "io.github.trevarj.motd.irc.client.IrcClient",
            "shared code must not hold the raw IRC client handle; request the specific capability " +
                "or state instead (docs/backend-neutral-xmpp-rollout.md \"Remove the client escape hatch\").",
        ),
        ForbiddenImport(
            "io.github.trevarj.motd.irc.event.IrcClientState",
            "shared code must consume the neutral ConnectionState, not wire-level client state.",
        ),
        ForbiddenImport(
            "io.github.trevarj.motd.irc.event.IrcEvent",
            "shared code must consume canonical TimelineObservation/IngestResult, not wire events.",
        ),
        ForbiddenImport(
            "io.github.trevarj.motd.irc.proto.Prefix",
            "shared code must use a neutral participant identity, not the IRC prefix type.",
        ),
        ForbiddenImport(
            "io.github.trevarj.motd.irc.proto.IrcMessage",
            "shared code must not parse or hold raw IRC wire messages.",
        ),
    )

    /** Adapter-owned package directories (relative to io/github/trevarj/motd/): fully allowed. */
    private val adapterOwnedDirs = setOf("service", "ircbackend", "dcc", "bouncer", "avatar", "push")

    /**
     * Per-file exemptions: today's audited reality, each with its reason. Extending this table is
     * the sanctioned escape hatch for a genuinely new case — do so with the same justification
     * rigor as the entries below, not as a way to silence an unrelated new violation.
     */
    private val exemptions = listOf(
        Exemption(
            "data/sync/EventProcessor.kt",
            "the IRC backend's processor; writes only through shared canonical repositories.",
        ),
        Exemption(
            "data/sync/EventOrigin.kt",
            "the IRC backend's processor; writes only through shared canonical repositories.",
        ),
        Exemption(
            "data/repo/NetworkIgnorePolicy.kt",
            "IRC hostmask ignore semantics; subsumed by neutral participant identity later.",
        ),
        Exemption(
            "ui/channelinfo/ChannelInfoViewModel.kt",
            "IRC command/WHOIS surfaces embedded in shared UI, reached only via the IRC-owned " +
                "IrcSessions accessor; neutral capabilities arrive with the XMPP feedback loop.",
        ),
        Exemption(
            "ui/channellist/ChannelListModels.kt",
            "ELIST 'U' bounded-listing capability read via the IRC-owned IrcSessions accessor; " +
                "neutral capabilities arrive with the XMPP feedback loop.",
        ),
        Exemption(
            "ui/settings/NetworkToolsViewModel.kt",
            "IRC command/WHOIS surfaces embedded in shared UI, reached only via the IRC-owned " +
                "IrcSessions accessor; neutral capabilities arrive with the XMPP feedback loop.",
        ),
        Exemption(
            "ui/settings/NetworkSettingsViewModel.kt",
            "IRC command/WHOIS surfaces embedded in shared UI, reached only via the IRC-owned " +
                "IrcSessions accessor; neutral capabilities arrive with the XMPP feedback loop.",
        ),
    ).associateBy { it.path }

    /**
     * `src/main/kotlin` from the Gradle unit-test working directory (the `:app` project dir),
     * falling back to a repo-root-relative path. A location miss fails loudly instead of letting
     * every scan below report a vacuous "no violations found".
     */
    private fun sourceRoot(): File {
        val direct = File("src/main/kotlin")
        val fallback = File("app/src/main/kotlin")
        val root = if (direct.exists()) direct else fallback
        check(root.exists()) {
            "Could not locate the app main source root from working directory " +
                "${File(".").absoluteFile.normalize()}; tried ${direct.absolutePath} and " +
                "${fallback.absolutePath}. Fix sourceRoot() in ImportBoundaryTest rather than " +
                "letting it pass vacuously."
        }
        return root
    }

    private fun ktFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Null for non-import lines; the imported FQN (alias/comment/`;` stripped) otherwise. */
    private fun importedFqName(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("import ")) return null
        var rest = trimmed.removePrefix("import ").trim()
        val commentIndex = rest.indexOf("//")
        if (commentIndex >= 0) rest = rest.substring(0, commentIndex).trim()
        val asIndex = rest.indexOf(" as ")
        if (asIndex >= 0) rest = rest.substring(0, asIndex).trim()
        return rest.removeSuffix(";").trim()
    }

    private fun packageRelativePath(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath.removePrefix("$packageRootPath/")

    private fun isAdapterOwned(packageRelativePath: String): Boolean =
        packageRelativePath.substringBefore('/') in adapterOwnedDirs

    @Test
    fun `main sources contain no XMPP library imports`() {
        val root = sourceRoot()
        val violations = mutableListOf<String>()

        ktFiles(root).forEach { file ->
            val relativePath = packageRelativePath(root, file)
            file.readLines().forEachIndexed { index, line ->
                val fq = importedFqName(line) ?: return@forEachIndexed
                val reason = forbiddenPrefixes.firstOrNull { fq.startsWith(it.prefix) }?.reason
                    ?: forbiddenSubstringReason.takeIf { fq.lowercase().contains(forbiddenSubstring) }
                    ?: return@forEachIndexed
                violations += "$relativePath:${index + 1}: `$fq` — $reason " +
                    "Fix: remove the XMPP/Smack dependency; PR 1 contains no XMPP source " +
                    "(docs/backend-neutral-xmpp-rollout.md)."
            }
        }

        assertTrue(
            "Forbidden XMPP/Smack imports found in app/src/main (PR 1 must contain zero XMPP source):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `shared packages do not import IRC wire types outside adapter-owned code or the exemption table`() {
        val root = sourceRoot()
        val violations = mutableListOf<String>()

        ktFiles(root).forEach { file ->
            val relativePath = packageRelativePath(root, file)
            if (isAdapterOwned(relativePath)) return@forEach
            val exempt = exemptions.containsKey(relativePath)

            file.readLines().forEachIndexed { index, line ->
                val fq = importedFqName(line) ?: return@forEachIndexed
                val forbidden = forbiddenWireImports.firstOrNull { it.fqName == fq } ?: return@forEachIndexed
                if (exempt) return@forEachIndexed
                violations += "$relativePath:${index + 1}: `$fq` — ${forbidden.reason} " +
                    "Fix: move this file into an adapter-owned package (service/, ircbackend/, dcc/, " +
                    "bouncer/, avatar/, push/), consume a neutral contract instead, or — with " +
                    "justification — extend the exemption table in ImportBoundaryTest.kt."
            }
        }

        assertTrue(
            "Forbidden IRC wire-type imports found outside adapter-owned packages and the exemption " +
                "table:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `every exemption table entry still matches a real forbidden import in that file`() {
        // Keeps the exemption table honest as a ratchet in both directions: it must shrink once a
        // file is cleaned up, not accumulate stale entries that silently widen the allowed surface.
        val root = sourceRoot()
        val violations = mutableListOf<String>()

        exemptions.values.forEach { exemption ->
            val file = File(root, "$packageRootPath/${exemption.path}")
            if (!file.exists()) {
                violations += "${exemption.path}: exempted file no longer exists; remove this entry."
                return@forEach
            }
            val importedNames = file.readLines().mapNotNull(::importedFqName)
            val stillForbidden = forbiddenWireImports.any { it.fqName in importedNames }
            if (!stillForbidden) {
                violations += "${exemption.path}: no longer contains a forbidden IRC wire-type " +
                    "import; remove this now-stale entry from the exemption table."
            }
        }

        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `backend package imports nothing from the IRC adapter`() {
        val root = sourceRoot()
        val backendDir = File(root, "$packageRootPath/backend")
        check(backendDir.exists()) { "Expected ${backendDir.absolutePath} to exist." }
        val violations = mutableListOf<String>()

        ktFiles(backendDir).forEach { file ->
            val relativePath = packageRelativePath(root, file)
            file.readLines().forEachIndexed { index, line ->
                val fq = importedFqName(line) ?: return@forEachIndexed
                if (fq.startsWith("$packageRoot.irc.")) {
                    violations += "$relativePath:${index + 1}: `$fq` — the neutral backend contracts " +
                        "must stay pure. Fix: move this to an adapter package, or express it through " +
                        "a neutral contract instead of the IRC type."
                }
            }
        }

        assertTrue(
            "backend/ must not import from io.github.trevarj.motd.irc.:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
