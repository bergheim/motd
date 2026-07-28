package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingReducerTest {

    private fun reduce(state: OnboardingState, vararg actions: OnboardingAction): OnboardingState =
        actions.fold(state) { s, a -> onboardingReducer(s, a) }

    @Test
    fun `welcome advances to choice`() {
        val s = onboardingReducer(OnboardingState(), OnboardingAction.Next)
        assertEquals(OnboardingStep.CHOICE, s.step)
    }

    @Test
    fun `choice does not advance until a choice is made`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.Next,
        )
        // No choice yet -> stays put.
        assertEquals(OnboardingStep.CHOICE, s.step)
    }

    @Test
    fun `choosing network then next advances to server`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.NETWORK),
            OnboardingAction.Next,
        )
        assertEquals(OnboardingStep.SERVER, s.step)
        assertEquals(NetworkRole.DIRECT, s.role)
    }

    @Test
    fun `soju choice yields bouncer root role`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
        )
        assertTrue(s.isSoju)
        assertEquals(NetworkRole.BOUNCER_ROOT, s.role)
    }

    @Test
    fun `soju login maps to SASL PLAIN without mutating direct auth`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
        )
        assertEquals(AuthMode.NONE, s.auth.mode)
        assertEquals(AuthMode.PLAIN, s.activeAuth.mode)
    }

    @Test
    fun `soju AUTH advance requires both username and password`() {
        // After choosing soju, mode is PLAIN so AUTH validity gates on both fields.
        val base = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
        ).copy(step = OnboardingStep.AUTH)

        assertFalse(base.canAdvance)
        assertFalse(base.copy(sojuLogin = SojuLoginForm(username = "u")).canAdvance)
        val complete = base.copy(sojuLogin = SojuLoginForm("u", "p"))
        assertTrue(complete.canAdvance)
        assertEquals(OnboardingStep.CONNECT, onboardingReducer(complete, OnboardingAction.Next).step)
    }

    @Test
    fun `bouncer and direct credential drafts stay independent`() {
        val soju = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
        )
        val none = onboardingReducer(soju, OnboardingAction.EditAuth(AuthForm(mode = AuthMode.NONE)))
        assertEquals(AuthMode.NONE, none.auth.mode)
        val external = onboardingReducer(
            soju,
            OnboardingAction.EditAuth(AuthForm(mode = AuthMode.EXTERNAL, saslUser = "u", saslPassword = "p")),
        )
        assertEquals(AuthMode.EXTERNAL, external.auth.mode)
        assertEquals("u", external.auth.saslUser)
        assertEquals("p", external.auth.saslPassword)
    }

    @Test
    fun `ZNC selection remains direct and requires separate login fields`() {
        val base = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
            OnboardingAction.ChooseBouncerKind(BouncerKind.ZNC),
        ).copy(step = OnboardingStep.AUTH)
        assertTrue(base.isZnc)
        assertEquals(NetworkRole.DIRECT, base.role)
        assertFalse(base.canAdvance)
        val complete = base.copy(zncLogin = ZncLoginForm("motd", "libera", "pw"))
        assertTrue(complete.canAdvance)
        assertEquals("motd/libera", complete.activeAuth.saslUser)
    }

    @Test
    fun `network EditAuth preserves submitted mode`() {
        val network = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.NETWORK),
        )
        val s = onboardingReducer(
            network,
            OnboardingAction.EditAuth(AuthForm(mode = AuthMode.EXTERNAL, certAlias = "a")),
        )
        assertEquals(AuthMode.EXTERNAL, s.auth.mode)
    }

    @Test
    fun `network choice leaves auth mode untouched`() {
        // Direct path keeps the full picker: NONE stays valid, EXTERNAL still selectable.
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.NETWORK),
        )
        assertEquals(AuthMode.NONE, s.auth.mode)
        val none = s.copy(step = OnboardingStep.AUTH)
        assertTrue(none.canAdvance)
        val external = none.copy(auth = none.auth.copy(mode = AuthMode.EXTERNAL, certAlias = "a"))
        assertTrue(external.canAdvance)
    }

    @Test
    fun `libera preset fills host port tls and selects network path`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.SelectPreset(NetworkPresetId.LIBERA),
        )
        assertEquals(ConnectionChoice.NETWORK, s.choice)
        assertEquals("irc.libera.chat", s.server.host)
        assertEquals("6697", s.server.port)
        assertTrue(s.server.tls)
    }

    @Test
    fun `libera preset preserves already-typed nick`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.EditServer(ServerForm(nick = "trev")),
            OnboardingAction.SelectPreset(NetworkPresetId.LIBERA),
        )
        assertEquals("trev", s.server.nick)
        assertEquals("irc.libera.chat", s.server.host)
    }

    @Test
    fun `legacy preset uses plaintext and requires confirmation`() {
        val selected = onboardingReducer(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.SelectPreset(NetworkPresetId.QUAKENET),
        )

        assertEquals(ConnectionChoice.NETWORK, selected.choice)
        assertEquals(NetworkPresetId.QUAKENET, selected.presetId)
        assertFalse(selected.server.tls)
        assertFalse(selected.plaintextConfirmed)
        assertTrue(
            onboardingReducer(selected, OnboardingAction.ShowPlaintextWarning)
                .showPlaintextWarning,
        )
    }

    @Test
    fun `editing a preset endpoint falls back to custom without losing identity`() {
        val selected = onboardingReducer(
            OnboardingState(server = ServerForm(nick = "trev", username = "t")),
            OnboardingAction.SelectPreset(NetworkPresetId.OFTC),
        )
        val edited = onboardingReducer(
            selected,
            OnboardingAction.EditServer(selected.server.copy(host = "irc.example.org")),
        )

        assertEquals(NetworkPresetId.CUSTOM, edited.presetId)
        assertEquals("trev", edited.server.nick)
        assertEquals("t", edited.server.username)
    }

    @Test
    fun `server invalid blocks advance, valid allows it`() {
        val invalid = OnboardingState(step = OnboardingStep.SERVER)
        assertFalse(invalid.canAdvance)
        assertEquals(OnboardingStep.SERVER, onboardingReducer(invalid, OnboardingAction.Next).step)

        val valid = invalid.copy(server = ServerForm(host = "irc.libera.chat", nick = "me"))
        assertTrue(valid.canAdvance)
        assertEquals(OnboardingStep.AUTH, onboardingReducer(valid, OnboardingAction.Next).step)
    }

    @Test
    fun `server invalid on bad port`() {
        val s = ServerForm(host = "h", nick = "n", port = "70000")
        assertFalse(s.isValid)
        assertTrue(s.copy(port = "6697").isValid)
    }

    @Test
    fun `soju server now requires host, valid port, and a nick`() {
        // soju collects a nick on SERVER (the IRC NICK the bouncer registers with); the bouncer
        // SASL username/password are gathered on AUTH.
        val soju = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.BOUNCER),
        ).copy(step = OnboardingStep.SERVER)

        assertFalse(soju.canAdvance) // blank host + nick
        val hostOnly = soju.copy(server = ServerForm(host = "bnc.example.org"))
        assertTrue(hostOnly.server.hostAndPortValid) // transport is fine
        assertFalse(hostOnly.server.isValid) // but a nick is now required
        assertFalse(hostOnly.canAdvance)
        val withNick = hostOnly.copy(server = hostOnly.server.copy(nick = "trev"))
        assertTrue(withNick.server.isValid)
        assertTrue(withNick.canAdvance)
        // Bad port blocks even with a nick.
        assertFalse(withNick.copy(server = withNick.server.copy(port = "70000")).canAdvance)
    }

    @Test
    fun `direct server still requires a nick`() {
        val direct = reduce(
            OnboardingState(step = OnboardingStep.CHOICE),
            OnboardingAction.ChooseConnection(ConnectionChoice.NETWORK),
        ).copy(step = OnboardingStep.SERVER, server = ServerForm(host = "irc.example.org"))
        assertFalse(direct.canAdvance) // no nick yet
        assertTrue(direct.copy(server = direct.server.copy(nick = "me")).canAdvance)
    }

    @Test
    fun `tls toggle re-defaults port but not a custom one`() {
        // Default TLS port -> plaintext default when TLS turned off, and back.
        val tlsDefault = ServerForm()
        assertEquals(PORT_TLS, tlsDefault.port)
        val plain = tlsDefault.withTls(false)
        assertFalse(plain.tls)
        assertEquals(PORT_PLAIN, plain.port)
        assertEquals(PORT_TLS, plain.withTls(true).port)

        // A user-entered custom port survives the toggle.
        val custom = ServerForm(port = "6789").withTls(false)
        assertEquals("6789", custom.port)
        assertEquals("6789", custom.withTls(true).port)
    }

    @Test
    fun `external auth needs a cert but no password`() {
        // EXTERNAL identity is the client cert: no saslUser/saslPassword required.
        assertFalse(AuthForm(AuthMode.EXTERNAL).isValid)
        val withCert = AuthForm(AuthMode.EXTERNAL, certAlias = "alias")
        assertTrue(withCert.isValid)
        // A password is neither required nor consulted for EXTERNAL validity.
        assertTrue(AuthForm(AuthMode.EXTERNAL, certAlias = "alias", saslPassword = "").isValid)
    }

    @Test
    fun `effective username falls back to nick`() {
        assertEquals("me", ServerForm(nick = "me").effectiveUsername)
        assertEquals("bot", ServerForm(nick = "me", username = "bot").effectiveUsername)
    }

    @Test
    fun `auth plain requires user and password`() {
        assertTrue(AuthForm(AuthMode.NONE).isValid)
        assertFalse(AuthForm(AuthMode.PLAIN, saslUser = "u").isValid)
        assertTrue(AuthForm(AuthMode.PLAIN, saslUser = "u", saslPassword = "p").isValid)
        assertFalse(AuthForm(AuthMode.EXTERNAL).isValid)
        assertTrue(AuthForm(AuthMode.EXTERNAL, certAlias = "alias").isValid)
    }

    @Test
    fun `back moves to previous step and does not underflow`() {
        assertEquals(
            OnboardingStep.WELCOME,
            onboardingReducer(OnboardingState(step = OnboardingStep.CHOICE), OnboardingAction.Back).step,
        )
        assertEquals(
            OnboardingStep.WELCOME,
            onboardingReducer(OnboardingState(), OnboardingAction.Back).step,
        )
    }

    @Test
    fun `next does not overflow past finish`() {
        assertEquals(
            OnboardingStep.FINISH,
            onboardingReducer(OnboardingState(step = OnboardingStep.FINISH), OnboardingAction.Next).step,
        )
    }

    @Test
    fun `conn state changes accumulate a log and surface failure reason`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.ConnStateChanged(ConnectionState.Connecting),
            OnboardingAction.ConnStateChanged(ConnectionState.Authenticating),
            OnboardingAction.ConnStateChanged(ConnectionState.Failed("bad password", fatal = true)),
        )
        assertEquals(3, s.stateLog.size)
        assertEquals("bad password", s.error)
        assertFalse(s.isReady)
        assertFalse(s.canAdvance)
    }

    @Test
    fun `ready allows advance from connect`() {
        val s = onboardingReducer(
            OnboardingState(step = OnboardingStep.CONNECT),
            OnboardingAction.ConnStateChanged(
                ConnectionState.Ready("me"),
            ),
        )
        assertTrue(s.isReady)
        assertTrue(s.canAdvance)
        assertEquals(OnboardingStep.FINISH, onboardingReducer(s, OnboardingAction.Next).step)
    }

    @Test
    fun `bouncer list loads and toggles selection`() {
        val listed = reduce(
            OnboardingState(step = OnboardingStep.CONNECT, networkId = 1L),
            OnboardingAction.BouncerListLoading(1L, 1L, 1L),
            OnboardingAction.BouncerListed(
                1L, 1L, 1L,
                listOf(
                    BouncerNetworkRow("1", "Libera", selected = false),
                    BouncerNetworkRow("2", "OFTC", selected = false),
                ),
            ),
        )
        assertTrue(listed.bouncerDiscovery is BouncerDiscoveryState.Loaded)
        assertEquals(2, listed.bouncerNetworks.size)

        val toggled = onboardingReducer(listed, OnboardingAction.ToggleBouncerNetwork("1"))
        assertTrue(toggled.bouncerNetworks.first { it.netId == "1" }.selected)
        assertFalse(toggled.bouncerNetworks.first { it.netId == "2" }.selected)
    }

    @Test
    fun `passive bouncer snapshot preserves selected imports`() {
        val selected = reduce(
            OnboardingState(step = OnboardingStep.CONNECT, networkId = 1L),
            OnboardingAction.BouncerListLoading(1L, 1L, 1L),
            OnboardingAction.BouncerListed(
                1L, 1L, 1L,
                listOf(
                    BouncerNetworkRow("1", "Libera", selected = false),
                    BouncerNetworkRow("2", "OFTC", selected = false),
                ),
            ),
            OnboardingAction.ToggleBouncerNetwork("1"),
        )

        val refreshed = onboardingReducer(
            selected,
            OnboardingAction.BouncerSnapshot(
                1L, 1L,
                listOf(
                    BouncerNetworkRow("1", "Libera.Chat", selected = false),
                    BouncerNetworkRow("3", "ExampleNet", selected = false),
                ),
            ),
        )

        assertTrue(refreshed.bouncerNetworks.first { it.netId == "1" }.selected)
        assertFalse(refreshed.bouncerNetworks.first { it.netId == "3" }.selected)
    }

    @Test
    fun `failed discovery retains selected imports and passive snapshots retain the error`() {
        val selected = reduce(
            OnboardingState(step = OnboardingStep.CONNECT, networkId = 1L),
            OnboardingAction.BouncerListLoading(1L, 1L, 1L),
            OnboardingAction.BouncerListed(
                1L, 1L, 1L,
                listOf(BouncerNetworkRow("libera", "Libera", selected = false)),
            ),
            OnboardingAction.ToggleBouncerNetwork("libera"),
        )

        val refreshed = onboardingReducer(
            selected,
            OnboardingAction.BouncerListFailed(1L, 1L, 1L, BouncerOperationError.ConnectionLost),
        )

        assertEquals(listOf("libera"), refreshed.bouncerNetworks.map { it.netId })
        assertTrue(refreshed.bouncerNetworks.single().selected)
        val passive = onboardingReducer(
            refreshed,
            OnboardingAction.BouncerSnapshot(1L, 1L, listOf(BouncerNetworkRow("2", "OFTC", false))),
        )
        assertTrue(passive.bouncerDiscovery is BouncerDiscoveryState.Failed)
        assertTrue(passive.bouncerNetworks.single { it.netId == "libera" }.selected)
    }

    @Test
    fun `bouncer add preserves drafts on failure and clears them once on success`() {
        val s = reduce(
            OnboardingState(step = OnboardingStep.CONNECT, networkId = 1L),
            OnboardingAction.BouncerListLoading(1L, 1L, 1L),
            OnboardingAction.BouncerListed(1L, 1L, 1L, emptyList()),
            OnboardingAction.EditBouncerAddDraft(BouncerAddDraft("New", "irc.new.example")),
            OnboardingAction.BouncerAddSubmitting(1L, 1L),
            OnboardingAction.BouncerAddFailed(1L, 1L, BouncerOperationError.ConnectionLost),
        )
        assertEquals(BouncerAddDraft("New", "irc.new.example"), s.bouncerAddDraft)
        assertTrue(s.bouncerAdd is BouncerAddState.Failed)
        val accepted = onboardingReducer(
            s,
            OnboardingAction.BouncerAdded(1L, 1L, BouncerNetworkRow("9", "New", selected = true)),
        )
        assertEquals(BouncerAddDraft(), accepted.bouncerAddDraft)
        assertTrue(accepted.bouncerAdd is BouncerAddState.Success)
        assertEquals(1, accepted.bouncerNetworks.size)
        assertEquals(accepted, onboardingReducer(accepted, OnboardingAction.BouncerSnapshot(1L, 1L, emptyList())))
    }

    @Test
    fun `stale list and add results from replaced root are ignored`() {
        val replacement = reduce(
            OnboardingState(networkId = 2L),
            OnboardingAction.BouncerListLoading(2L, 3L, 4L),
        )
        val afterList = onboardingReducer(
            replacement,
            OnboardingAction.BouncerListed(1L, 1L, 1L, listOf(BouncerNetworkRow("old", "Old", false))),
        )
        val afterAdd = onboardingReducer(
            afterList,
            OnboardingAction.BouncerAdded(1L, 1L, BouncerNetworkRow("old", "Old", true)),
        )
        assertTrue(afterAdd.bouncerNetworks.isEmpty())
        assertTrue(afterAdd.bouncerAdd is BouncerAddState.Idle)
    }

    @Test
    fun `discovery retry does not invalidate pending add`() {
        val submitting = reduce(
            OnboardingState(networkId = 1L),
            OnboardingAction.BouncerListLoading(1L, 7L, 1L),
            OnboardingAction.EditBouncerAddDraft(BouncerAddDraft("New", "irc.new.example")),
            OnboardingAction.BouncerAddSubmitting(1L, 7L),
            OnboardingAction.BouncerListLoading(1L, 7L, 2L),
        )
        val added = onboardingReducer(
            submitting,
            OnboardingAction.BouncerAdded(1L, 7L, BouncerNetworkRow("9", "New", true)),
        )
        assertTrue(added.bouncerAdd is BouncerAddState.Success)
        assertEquals("9", added.bouncerNetworks.single().netId)
    }

    @Test
    fun `network created records id`() {
        val s = onboardingReducer(OnboardingState(), OnboardingAction.NetworkCreated(42L))
        assertEquals(42L, s.networkId)
    }

    @Test
    fun `error clears`() {
        val s = reduce(
            OnboardingState(error = "x"),
            OnboardingAction.Error(null),
        )
        assertNull(s.error)
    }

    @Test
    fun `goto jumps directly`() {
        assertEquals(
            OnboardingStep.CONNECT,
            onboardingReducer(OnboardingState(), OnboardingAction.GoTo(OnboardingStep.CONNECT)).step,
        )
    }
}
