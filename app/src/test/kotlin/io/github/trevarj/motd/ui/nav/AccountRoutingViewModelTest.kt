package io.github.trevarj.motd.ui.nav

import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.InertConnectionManager
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Registry-driven navigation dispatch (docs/backend-neutral-xmpp-rollout.md): proves the add-account
 * picker's enumeration and the existing-row edit routing both derive purely from the registered
 * backend/UI sets, with no protocol switch, per slice X7's "unit-test the mapping logic" ask.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountRoutingViewModelTest {

    private class FakeBackend(id: String) : ChatBackend {
        override val protocol = ProtocolId(id)
        override val sessions get() = InertConnectionManager
    }

    private class FakeAccountUi(id: String, override val createRoute: Any) : ProtocolAccountUi {
        override val protocol = ProtocolId(id)
        override val labelRes: Int = 0
        override fun editRoute(networkId: Long): Any = "${protocol.value}-edit-$networkId"
    }

    private class FakeNetworkRepository(private val networks: Map<Long, NetworkEntity>) : NetworkRepository {
        override fun observeNetworks() = flowOf(networks.values.toList())
        override suspend fun addNetwork(n: NetworkEntity): Long = error("unused")
        override suspend fun updateNetwork(n: NetworkEntity) = error("unused")
        override suspend fun deleteNetwork(id: Long) = error("unused")
        override suspend fun networkById(id: Long): NetworkEntity? = networks[id]
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private fun network(id: Long, protocol: String) = NetworkEntity(
        id = id, name = "n$id", role = NetworkRole.DIRECT,
        host = "h", port = 1, nick = "n", username = "u", realname = "r",
        protocol = protocol,
    )

    @Test
    fun `createDestination goes straight to the lone backend's route when only one is registered`() = runTest {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc")),
            uis = setOf(FakeAccountUi("irc", createRoute = AddNetworkRoute)),
        )
        val viewModel = AccountRoutingViewModel(registry, FakeNetworkRepository(emptyMap()))

        assertEquals(AddNetworkRoute, viewModel.createDestination())
    }

    @Test
    fun `createDestination opens the picker when more than one backend is registered`() = runTest {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc"), FakeBackend("xmpp")),
            uis = setOf(
                FakeAccountUi("irc", createRoute = AddNetworkRoute),
                FakeAccountUi("xmpp", createRoute = XmppAccountRoute()),
            ),
        )
        val viewModel = AccountRoutingViewModel(registry, FakeNetworkRepository(emptyMap()))

        assertEquals(AccountPickerRoute, viewModel.createDestination())
        assertEquals(setOf("irc", "xmpp"), viewModel.createChoices.map { it.protocol.value }.toSet())
    }

    @Test
    fun `editRouteFor resolves through the row's persisted protocol`() = runTest {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc"), FakeBackend("xmpp")),
            uis = setOf(
                FakeAccountUi("irc", createRoute = AddNetworkRoute),
                FakeAccountUi("xmpp", createRoute = XmppAccountRoute()),
            ),
        )
        val repo = FakeNetworkRepository(mapOf(1L to network(1, "irc"), 2L to network(2, "xmpp")))
        val viewModel = AccountRoutingViewModel(registry, repo)

        assertEquals("irc-edit-1", viewModel.editRouteFor(1))
        assertEquals("xmpp-edit-2", viewModel.editRouteFor(2))
    }

    @Test
    fun `editRouteFor falls back to the IRC edit screen for a missing row or unregistered protocol`() = runTest {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc")),
            uis = setOf(FakeAccountUi("irc", createRoute = AddNetworkRoute)),
        )
        val repo = FakeNetworkRepository(mapOf(3L to network(3, "carrier-pigeon")))
        val viewModel = AccountRoutingViewModel(registry, repo)

        assertEquals(NetworkSettingsRoute(99), viewModel.editRouteFor(99))
        assertEquals(NetworkSettingsRoute(3), viewModel.editRouteFor(3))
    }
}
