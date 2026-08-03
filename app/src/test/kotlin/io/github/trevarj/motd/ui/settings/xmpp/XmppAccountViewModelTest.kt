package io.github.trevarj.motd.ui.settings.xmpp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import io.github.trevarj.motd.data.repo.XmppAccountRepository
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * XMPP's account create/edit ViewModel, exercised against a real in-memory Room database (mirroring
 * [io.github.trevarj.motd.xmppbackend.XmppProcessorTest]'s bootstrap) rather than a hand-rolled fake
 * repository, since the behavior under test — cross-DAO transaction atomicity and FK cascade — can
 * only be proven against real SQL (docs/backend-neutral-xmpp-rollout.md baseline "account creation
 * and edits").
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class XmppAccountViewModelTest {

    private lateinit var db: MotdDatabase
    private lateinit var networkRepository: NetworkRepository
    private lateinit var xmppAccountRepository: XmppAccountRepository
    private lateinit var connectionManager: FakeConnectionManager

    private class FakeConnectionManager : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
        val connected = mutableListOf<Long>()
        val disconnected = mutableListOf<Long>()
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) { connected += networkId }
        override suspend fun disconnect(networkId: Long) { disconnected += networkId }
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    /** Mirrors XmppProcessorTest.bootstrap: Room and the ViewModel's viewModelScope share the
     *  TestScope's scheduler so both advance deterministically under advanceUntilIdle(). */
    private fun TestScope.bootstrap() {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        networkRepository = NetworkRepositoryImpl(db.networkDao())
        xmppAccountRepository = XmppAccountRepository(db)
        connectionManager = FakeConnectionManager()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm() = XmppAccountViewModel(xmppAccountRepository, networkRepository, connectionManager)

    @Test
    fun `create persists both rows atomically with protocol=xmpp and connects`() = runTest {
        bootstrap()
        val viewModel = vm()
        viewModel.init(null)
        viewModel.editDisplayName("Home")
        viewModel.editJid("alice@example.org")
        viewModel.editPassword("hunter2")
        viewModel.editResource("phone")

        var done = false
        viewModel.save { done = true }
        advanceUntilIdle()

        assertTrue(done)
        val network = db.networkDao().allNow().single()
        assertEquals("xmpp", network.protocol)
        assertEquals("Home", network.name)
        val account = requireNotNull(db.xmppAccountDao().byNetwork(network.id))
        assertEquals("alice@example.org", account.jid)
        assertEquals("hunter2", account.password)
        assertEquals("phone", account.resource)
        assertEquals(listOf(network.id), connectionManager.connected)
    }

    @Test
    fun `blank display name falls back to the jid, blank resource stays null`() = runTest {
        bootstrap()
        val viewModel = vm()
        viewModel.init(null)
        viewModel.editJid("bob@example.org")
        viewModel.editPassword("pw")

        viewModel.save {}
        advanceUntilIdle()

        val network = db.networkDao().allNow().single()
        assertEquals("bob@example.org", network.name)
        assertNull(db.xmppAccountDao().byNetwork(network.id)?.resource)
    }

    @Test
    fun `deleting the account cascades away the xmpp_accounts row`() = runTest {
        bootstrap()
        val createViewModel = vm()
        createViewModel.init(null)
        createViewModel.editJid("carol@example.org")
        createViewModel.editPassword("pw")
        createViewModel.save {}
        advanceUntilIdle()
        val networkId = db.networkDao().allNow().single().id
        assertNotNull(db.xmppAccountDao().byNetwork(networkId))

        val editViewModel = vm()
        editViewModel.init(networkId)
        advanceUntilIdle()
        editViewModel.delete {}
        advanceUntilIdle()

        assertNull(db.networkDao().byId(networkId))
        assertNull(db.xmppAccountDao().byNetwork(networkId))
        assertEquals(listOf(networkId), connectionManager.disconnected)
    }

    @Test
    fun `edit loads the existing row and round-trips changes to both rows`() = runTest {
        bootstrap()
        val createViewModel = vm()
        createViewModel.init(null)
        createViewModel.editDisplayName("Original")
        createViewModel.editJid("dave@example.org")
        createViewModel.editPassword("first-password")
        createViewModel.save {}
        advanceUntilIdle()
        val networkId = db.networkDao().allNow().single().id
        connectionManager.connected.clear()   // isolate the create-time connect() from the assertion below

        val editViewModel = vm()
        editViewModel.init(networkId)
        advanceUntilIdle()
        assertTrue(editViewModel.state.value.isEdit)
        assertEquals("Original", editViewModel.state.value.displayName)
        assertEquals("dave@example.org", editViewModel.state.value.jid)
        assertEquals("first-password", editViewModel.state.value.password)

        editViewModel.editDisplayName("Renamed")
        editViewModel.editPassword("second-password")
        editViewModel.editResource("laptop")
        var done = false
        editViewModel.save { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertTrue("edit must not connect-on-save", connectionManager.connected.isEmpty())
        val network = requireNotNull(db.networkDao().byId(networkId))
        assertEquals("Renamed", network.name)
        assertEquals("xmpp", network.protocol)
        val account = requireNotNull(db.xmppAccountDao().byNetwork(networkId))
        assertEquals("dave@example.org", account.jid)
        assertEquals("second-password", account.password)
        assertEquals("laptop", account.resource)
    }

    @Test
    fun `blank jid or password is rejected, and jid must contain at`() = runTest {
        bootstrap()
        val viewModel = vm()
        viewModel.init(null)
        assertFalse(viewModel.state.value.canSave)

        viewModel.editPassword("pw")
        assertFalse("no jid at all", viewModel.state.value.canSave)

        viewModel.editJid("nodomain")
        assertFalse("jid without @ is rejected", viewModel.state.value.canSave)

        viewModel.editJid("user@example.org")
        viewModel.editPassword("")
        assertFalse("blank password is rejected", viewModel.state.value.canSave)

        // save() no-ops while invalid: nothing is committed.
        viewModel.save {}
        advanceUntilIdle()
        assertEquals(0, db.networkDao().allNow().size)

        viewModel.editPassword("pw")
        assertTrue(viewModel.state.value.canSave)
    }
}
