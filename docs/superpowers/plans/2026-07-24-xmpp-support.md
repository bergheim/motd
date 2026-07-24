# XMPP Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Native XMPP core messaging (login, roster, 1:1, MUC, 1:1 typing) in MOTD alongside IRC, per `docs/superpowers/specs/2026-07-24-xmpp-support-design.md`.

**Architecture:** Parallel vertical slice. IRC pipeline untouched except two protocol filters. New `io.github.trevarj.motd.xmpp` package: Smack wrapped behind an `XmppSession` seam, events funneled through one channel per account into `XmppEventProcessor` (sole writer of XMPP state to the shared Room tables). A `RoutingConnectionManager` decorator keeps the existing `ConnectionManager` interface as the only seam the UI sees.

**Tech Stack:** Kotlin, Hilt (KSP), Room v16, Smack 4.4.8 (`smack-android`, `smack-tcp`, `smack-extensions`), JUnit4 + Robolectric + coroutines-test.

## Global Constraints

- All Gradle commands run as `nix develop -c ./gradlew ...`. Never a host SDK.
- Unit test command: `nix develop -c ./gradlew :app:testFossDebugUnitTest --stacktrace` (add `--tests "..."` to scope).
- Lint warnings are errors. FOSS flavor only; never run Google tasks.
- KSP only — never kapt. Dependencies only via `gradle/libs.versions.toml`.
- No destructive migrations. Schema v15 → v16 is additive; export `16.json` and commit it.
- `XmppEventProcessor` is the SOLE writer of XMPP-derived Room state (mirror of the IRC invariant).
- Bare-JID normalization everywhere; resources are transient. Dedup via new `XMPP_MSGID` alias namespace scoped by buffer + sender bare JID.
- New XMPP accounts default to STARTTLS on 5222 (`tls=false` on XMPP rows means STARTTLS; `tls=true` means direct TLS/5223). Plaintext never offered.
- XEP-0198 acks in scope, stream resumption disabled. Smack `ReconnectionManager` disabled.
- Live-test credentials only via env vars (`MOTD_XMPP_LIVE_*`); tests self-skip when unset. Never commit credentials.
- Commit after every task with a `feat(xmpp): ...`/`test(xmpp): ...` conventional message ending in the `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` trailer.

---

### Task 1: Smack dependency catalog entries

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts` (dependencies block, near `implementation(libs.okhttp)` entries)

**Interfaces:**
- Produces: `libs.smack.android`, `libs.smack.tcp`, `libs.smack.extensions` catalog accessors for later tasks.

- [ ] **Step 1: Add catalog entries**

In `[versions]` (alphabetical position):

```toml
smack = "4.4.8"
```

In `[libraries]`:

```toml
smack-android = { group = "org.igniterealtime.smack", name = "smack-android", version.ref = "smack" }
smack-extensions = { group = "org.igniterealtime.smack", name = "smack-extensions", version.ref = "smack" }
smack-tcp = { group = "org.igniterealtime.smack", name = "smack-tcp", version.ref = "smack" }
```

- [ ] **Step 2: Add app dependencies**

In `app/build.gradle.kts` `dependencies {}`:

```kotlin
implementation(libs.smack.android)
implementation(libs.smack.extensions)
implementation(libs.smack.tcp)
```

- [ ] **Step 3: Verify resolution and build**

Run: `nix develop -c ./gradlew :app:assembleFossDebug --stacktrace`
Expected: BUILD SUCCESSFUL (Smack resolves from Maven Central; smack-android brings minidns transitively).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(xmpp): add Smack 4.4.8 to the version catalog"
```

Justification for the catalog change (record in the commit body): protocol layer for native XMPP per the approved spec; smack-android (platform init + DNS), smack-tcp (XMPPTCPConnection), smack-extensions (MUC, chat states). Release minification is disabled repo-wide, so no proguard rules.

---

### Task 2: Schema v16 — `Protocol` discriminator, `jid` column, `XMPP_MSGID` namespace

**Files:**
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/data/db/Entities.kt` (NetworkEntity ~line 46, EventAliasNamespace line 35)
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/data/db/Converters.kt`
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/data/db/MotdDatabase.kt` (version, new migration after MIGRATION_14_15 at line ~533)
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/di/DbModule.kt` (addMigrations list)
- Create: `app/src/test/kotlin/io/github/trevarj/motd/data/db/Migration15To16Test.kt`
- Modify: `app/src/test/kotlin/io/github/trevarj/motd/data/db/AllMigrationsTest.kt` (append `MIGRATION_15_16` to its migrations list)

**Interfaces:**
- Produces: `enum class Protocol { IRC, XMPP }`; `NetworkEntity.protocol: Protocol`, `NetworkEntity.jid: String?`; `EventAliasNamespace.XMPP_MSGID`; `MIGRATION_15_16`.

- [ ] **Step 1: Write the failing migration test**

Copy the structure of `Migration14To15Test.kt` (Robolectric runner, `FrameworkSQLiteOpenHelperFactory`, seed prior schema from exported resource `io.github.trevarj.motd.data.db.MotdDatabase/15.json`). Test body:

```kotlin
@Test
fun migrate15To16_addsProtocolAndJid_backfillsIrc() {
    val db = createExportedVersion15()  // same helper pattern as createExportedVersion14
    db.execSQL(
        """INSERT INTO networks (name, role, host, port, tls, nick, username, realname,
           saslMechanism, autoConnect, ordering)
           VALUES ('libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'n', 'u', 'r', 'NONE', 1, 0)"""
    )
    MIGRATION_15_16.migrate(db)
    db.query("SELECT protocol, jid FROM networks").use { c ->
        assertTrue(c.moveToFirst())
        assertEquals("IRC", c.getString(0))
        assertTrue(c.isNull(1))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `nix develop -c ./gradlew :app:testFossDebugUnitTest --tests "io.github.trevarj.motd.data.db.Migration15To16Test" --stacktrace`
Expected: FAIL — `MIGRATION_15_16` unresolved.

- [ ] **Step 3: Implement entities, converter, migration, wiring**

`Entities.kt` — below `enum class ObfsMode` (line 21):

```kotlin
/** Chat protocol of a network/account row. IRC is the pre-v16 implicit default. */
enum class Protocol { IRC, XMPP }
```

`NetworkEntity` — add after `val name: String,`:

```kotlin
    val protocol: Protocol = Protocol.IRC,
```

and after `val obfsLink: String? = null,`:

```kotlin
    /** Bare JID for XMPP rows (user@domain); null for IRC rows. */
    val jid: String? = null,
```

`EventAliasNamespace` (line 35) — append variant:

```kotlin
enum class EventAliasNamespace { MSGID, LABEL, EXACT_FINGERPRINT, BATCH_POSITION, TYPED_EVENT, XMPP_MSGID }
```

`Converters.kt` — add the standard pair (non-null, bare `valueOf`, matching NetworkRole style):

```kotlin
@TypeConverter fun protocolToString(v: Protocol): String = v.name
@TypeConverter fun stringToProtocol(v: String): Protocol = Protocol.valueOf(v)
```

`MotdDatabase.kt` — bump `version = 16`; append after `MIGRATION_14_15`:

```kotlin
/** v15 -> v16: additive protocol discriminator + bare JID on networks; existing rows stay IRC. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networks ADD COLUMN protocol TEXT NOT NULL DEFAULT 'IRC'")
        db.execSQL("ALTER TABLE networks ADD COLUMN jid TEXT")
    }
}
```

`DbModule.kt` — import and append `MIGRATION_15_16,` to `.addMigrations(...)`.
`AllMigrationsTest.kt` — append `MIGRATION_15_16` to its migration array so the full-path test covers v16.

- [ ] **Step 4: Run migration tests + full unit suite; commit the schema export**

Run: `nix develop -c ./gradlew :app:testFossDebugUnitTest --stacktrace`
Expected: PASS; KSP writes `app/schemas/io.github.trevarj.motd.data.db.MotdDatabase/16.json`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/data/db/ app/src/main/kotlin/io/github/trevarj/motd/di/DbModule.kt \
  app/src/test/kotlin/io/github/trevarj/motd/data/db/ "app/schemas/io.github.trevarj.motd.data.db.MotdDatabase/16.json"
git commit -m "feat(xmpp): add protocol discriminator and jid column (schema v16)"
```

---

### Task 3: `XmppEvent` model + `XmppSession` seam + fake

**Files:**
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppEvent.kt`
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppSession.kt`
- Create: `app/src/test/kotlin/io/github/trevarj/motd/xmpp/FakeXmppSession.kt`

**Interfaces:**
- Produces (exact, used by Tasks 4–7):

```kotlin
package io.github.trevarj.motd.xmpp

data class RosterContact(val bareJid: String, val name: String?)

sealed interface XmppEvent {
    /** Authenticated and initial roster loaded — the account is Ready. */
    data class Ready(val selfBareJid: String) : XmppEvent
    data class RosterUpdated(val contacts: List<RosterContact>) : XmppEvent
    data class ChatMessage(
        val fromBareJid: String, val text: String, val stanzaId: String?, val delayedAtMs: Long?,
    ) : XmppEvent
    data class ChatState(val fromBareJid: String, val composing: Boolean) : XmppEvent
    data class MucMessage(
        val roomJid: String, val occupantNick: String, val text: String, val stanzaId: String?,
        val delayedAtMs: Long?,
    ) : XmppEvent
    data class MucSubject(val roomJid: String, val subject: String, val byNick: String?) : XmppEvent
    data class MucOccupantJoined(val roomJid: String, val nick: String) : XmppEvent
    data class MucOccupantLeft(val roomJid: String, val nick: String) : XmppEvent
    data class MucSelfJoined(val roomJid: String, val occupants: List<String>) : XmppEvent
    data class MucJoinFailed(val roomJid: String, val reason: String) : XmppEvent
    data class MucKicked(val roomJid: String, val reason: String?) : XmppEvent
    /** XEP-0198 server ack for an outbound stanza we sent with [originId]. */
    data class SendConfirmed(val originId: String) : XmppEvent
    data class Disconnected(val reason: String?, val fatal: Boolean) : XmppEvent
}

/** Config subset the session needs; derived from NetworkEntity by the actor. */
data class XmppAccountConfig(
    val bareJid: String, val password: String, val host: String, val port: Int,
    val directTls: Boolean, val mucNick: String,
)

/**
 * Protocol seam over one Smack connection. Implementations MUST register all account-level
 * listeners before login and room listeners before join; every callback is surfaced only
 * through [events]. One instance = one connection attempt; create a fresh session per (re)connect.
 */
interface XmppSession {
    val events: kotlinx.coroutines.channels.ReceiveChannel<XmppEvent>
    suspend fun connectAndLogin()
    suspend fun joinMuc(roomJid: String, nick: String)
    suspend fun leaveMuc(roomJid: String)
    suspend fun sendChat(toBareJid: String, text: String, originId: String)
    suspend fun sendMuc(roomJid: String, text: String, originId: String)
    suspend fun sendChatState(toBareJid: String, composing: Boolean)
    suspend fun close()
}

fun interface XmppSessionFactory {
    fun create(config: XmppAccountConfig): XmppSession
}
```

- [ ] **Step 1: Write the interfaces file exactly as above** (`XmppEvent.kt` holds the events + `RosterContact`; `XmppSession.kt` holds config, session, factory).

- [ ] **Step 2: Write `FakeXmppSession`**

```kotlin
package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.Channel

class FakeXmppSession : XmppSession {
    private val channel = Channel<XmppEvent>(Channel.UNLIMITED)
    override val events = channel
    val sentChats = mutableListOf<Triple<String, String, String>>()   // to, text, originId
    val sentMuc = mutableListOf<Triple<String, String, String>>()     // room, text, originId
    val joinedRooms = mutableListOf<String>()
    var connectCalls = 0; var closed = false
    var failLoginWith: Exception? = null

    suspend fun emit(event: XmppEvent) = channel.send(event)
    override suspend fun connectAndLogin() { connectCalls++; failLoginWith?.let { throw it } }
    override suspend fun joinMuc(roomJid: String, nick: String) { joinedRooms += roomJid }
    override suspend fun leaveMuc(roomJid: String) { joinedRooms -= roomJid }
    override suspend fun sendChat(toBareJid: String, text: String, originId: String) {
        sentChats += Triple(toBareJid, text, originId)
    }
    override suspend fun sendMuc(roomJid: String, text: String, originId: String) {
        sentMuc += Triple(roomJid, text, originId)
    }
    override suspend fun sendChatState(toBareJid: String, composing: Boolean) = Unit
    override suspend fun close() { closed = true; channel.close() }
}
```

- [ ] **Step 3: Compile check**

Run: `nix develop -c ./gradlew :app:compileFossDebugKotlin :app:compileFossDebugUnitTestKotlin --stacktrace`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/xmpp/ app/src/test/kotlin/io/github/trevarj/motd/xmpp/
git commit -m "feat(xmpp): add XmppEvent model and XmppSession seam"
```

---

### Task 4: `SmackXmppSession` (real Smack implementation)

**Files:**
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/SmackXmppSession.kt`

**Interfaces:**
- Consumes: Task 3 seam. Produces: `class SmackXmppSession(config: XmppAccountConfig) : XmppSession` and `object SmackXmppSessionFactory : XmppSessionFactory`.

This class is verified by the env-gated live tests in Task 12 (it is a thin adapter; unit-testing it would mean mocking Smack itself — don't).

- [ ] **Step 1: Implement**

Key requirements (implement all; skeleton below is the real shape, fill nothing in later):

```kotlin
package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import javax.net.ssl.SSLSocketFactory

class SmackXmppSession(private val config: XmppAccountConfig) : XmppSession {
    private val channel = Channel<XmppEvent>(Channel.UNLIMITED)
    override val events = channel

    private val connection: XMPPTCPConnection = XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppAddressAndPassword(config.bareJid, config.password)
            .setHost(config.host).setPort(config.port)
            .setResource(Resourcepart.from("motd"))
            .apply {
                if (config.directTls) {
                    setSocketFactory(SSLSocketFactory.getDefault())
                    setSecurityMode(ConnectionConfiguration.SecurityMode.disabled) // TLS already on the socket
                } else {
                    setSecurityMode(ConnectionConfiguration.SecurityMode.required) // STARTTLS mandatory
                }
            }
            .build(),
    ).apply {
        setUseStreamManagement(true)
        setUseStreamManagementResumption(false)
    }

    override suspend fun connectAndLogin() = withContext(Dispatchers.IO) {
        ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
        registerAccountListeners()   // BEFORE connect/login — spec invariant
        connection.connect()
        connection.login()
        val roster = Roster.getInstanceFor(connection)
        if (!roster.isLoaded) roster.reloadAndWait()
        channel.trySend(XmppEvent.RosterUpdated(roster.entries.map {
            RosterContact(it.jid.asBareJid().toString(), it.name)
        }))
        channel.trySend(XmppEvent.Ready(connection.user.asBareJid().toString()))
    }
    // registerAccountListeners(): ChatManager incoming listener -> ChatMessage (bare JID from,
    //   OriginIdElement/stanzaId, DelayInformation timestamp) and ChatStateExtension -> ChatState;
    //   Roster.addRosterListener -> RosterUpdated (full snapshot re-read);
    //   connection.addConnectionListener(connectionClosed/connectionClosedOnError -> Disconnected,
    //   fatal = SASLErrorException).
    // joinMuc(): MultiUserChatManager.getMultiUserChat(room); addMessageListener (MucMessage /
    //   MucSubject via subjectUpdatedListener) and ParticipantStatusListener (joined/left/kicked)
    //   BEFORE muc.join(); on join success emit MucSelfJoined(room, muc.occupants nicks); on
    //   XMPPErrorException emit MucJoinFailed(room, err.condition.toString()).
    // sendChat()/sendMuc(): MessageBuilder with setStanzaId(originId) + addExtension(OriginIdElement(originId));
    //   after send, connection.addStanzaIdAcknowledgedListener(originId) { channel.trySend(SendConfirmed(originId)) }.
    // sendChatState(): chat.send(Message with ChatStateExtension(composing/active)).
    // close(): connection.disconnect(Presence(Presence.Type.unavailable)); channel.close().
}

object SmackXmppSessionFactory : XmppSessionFactory {
    override fun create(config: XmppAccountConfig): XmppSession = SmackXmppSession(config)
}
```

Also call `org.jivesoftware.smack.android.AndroidSmackInitializer.initialize(context)` once — done in Task 6's `XmppConnectionManager` init (it has the injected `@ApplicationContext`).

- [ ] **Step 2: Compile + lint**

Run: `nix develop -c ./gradlew :app:compileFossDebugKotlin :app:lintFossDebug --stacktrace`
Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/xmpp/SmackXmppSession.kt
git commit -m "feat(xmpp): implement SmackXmppSession adapter"
```

---

### Task 5: `XmppEventProcessor`

**Files:**
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppEventProcessor.kt`
- Create: `app/src/test/kotlin/io/github/trevarj/motd/xmpp/XmppEventProcessorTest.kt`

**Interfaces:**
- Consumes: `XmppEvent` (Task 3), DAOs (`db.bufferDao().byName/insertIgnore/update`, `db.messageDao().insertAll`, `db.userDao()`, `db.memberDao()`, event-alias DAO used by EventProcessor — reuse `db.canonicalTimelineDao()`'s alias insert or raw `@Insert(onConflict = IGNORE)` on `EventAliasEntity` via a small new DAO method if none is directly reusable).
- Produces (used by Tasks 6–7):

```kotlin
@Singleton
class XmppEventProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val typing: TypingTrackerImpl,
    private val notifier: MessageNotifier,
) {
    suspend fun process(networkId: Long, event: XmppEvent)
    /** Durable pending row for an outbound message; returns event id. Null buffer -> null. */
    suspend fun createPending(networkId: Long, bufferId: Long, text: String, originId: String): TimelineEventId?
    suspend fun confirmSend(networkId: Long, originId: String)
    /** Flip all still-pending XMPP rows of this network to failed (reconnect / timeout). */
    suspend fun failAllPending(networkId: Long)
    suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long
    suspend fun ensureServerBuffer(networkId: Long): Long
}
```

Behavior rules (each is a test): JID normalization = lowercase bare JID (`XmppStringUtils`/`JidCreate` parse, drop resource). Dedup alias value = `"$bufferId $senderBareJid $stanzaId".toByteArray()` under namespace `XMPP_MSGID`; a duplicate alias insert (IGNORE returns -1) skips the message. `ChatMessage` → ensure QUERY buffer (displayName = roster name if known else bare JID), insert `PRIVMSG` row (`serverTime` = `delayedAtMs` ?: now, `serverTimeAuthoritative` = delayedAtMs != null). `MucMessage` from own nick with a stanzaId matching a pending row confirms it instead of inserting. `MucSubject` → update `topic` + insert `TOPIC` row. `MucOccupantJoined/Left` → members upsert/delete + `JOIN`/`PART` rows. `MucSelfJoined` → set `joined=true`, wholesale-replace members. `MucJoinFailed` → `ERROR` row + `joined=false`. `MucKicked` → `KICK`-kind row, `joined=false`, `membershipCycle+1`. `RosterUpdated` → upsert `users` rows (nick = bare JID, realname = roster name) and refresh QUERY buffer displayNames. `ChatState` → `typing.onTyping(bufferId, bareJid, if (composing) "active" else "done")`. `SendConfirmed` → clear `pendingLabel`. Mentions: `hasMention` = text contains MUC nick (word-boundary), MUC buffers only.

- [ ] **Step 1: Write failing tests** (Robolectric + in-memory DB, exactly the `EventProcessorTest` pattern — `inMemoryDb()` from `DbTestSupport.kt`, `TypingTrackerImpl()`, `MessageNotifier.Noop`). Minimum set:

```kotlin
@Test fun chatMessage_createsQueryBuffer_andRow() = runTest {
    p.process(nid, XmppEvent.ChatMessage("Alice@Example.net", "hi", "s1", null))
    val buf = db.bufferDao().byName(nid, "alice@example.net")!!
    assertEquals(BufferType.QUERY, buf.type)
    assertEquals("hi", rows(buf.id).single().text)
}
@Test fun duplicateStanzaId_sameSender_isDeduped() = runTest { /* process twice, one row */ }
@Test fun sameStanzaId_differentSenders_bothKept() = runTest { /* two rows */ }
@Test fun pending_confirmedBySendConfirmed() = runTest {
    val buf = p.ensureQueryBuffer(nid, "bob@x.net")
    val id = p.createPending(nid, buf, "yo", "o1")!!
    p.process(nid, XmppEvent.SendConfirmed("o1"))
    assertNull(db.messageDao().byId(id)!!.pendingLabel)
}
@Test fun mucReflection_confirmsPending_notDuplicated() = runTest { /* MucMessage(own nick, o1) */ }
@Test fun failAllPending_flipsToFailed() = runTest { /* createPending; failAllPending; failed=true */ }
@Test fun mucSelfJoined_setsJoined_andReplacesMembers() = runTest { /* joined=true, members swapped */ }
@Test fun mucJoinFailed_writesError_clearsJoined() = runTest { /* ERROR row, joined=false */ }
@Test fun mucKicked_incrementsMembershipCycle() = runTest { /* cycle+1, joined=false */ }
@Test fun rosterName_updatesQueryDisplayName() = runTest { /* displayName = roster name */ }
```

- [ ] **Step 2: Run to verify failure**

Run: `nix develop -c ./gradlew :app:testFossDebugUnitTest --tests "io.github.trevarj.motd.xmpp.XmppEventProcessorTest" --stacktrace`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement `XmppEventProcessor`** per the behavior rules above. Serialize writes with a per-network `Mutex` (`ConcurrentHashMap<Long, Mutex>`) — same effect as the IRC `NetworkEventSequencer` without depending on it. If no existing DAO exposes plain `EventAliasEntity` insert, add to `Daos.kt`:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAliasIgnore(alias: EventAliasEntity): Long   // -1 = duplicate
```

on the DAO that owns event aliases (follow where `EventAliasEntity` is currently inserted from `EventProcessor` and put it beside that).

- [ ] **Step 4: Run tests to green, then commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppEventProcessor.kt \
  app/src/test/kotlin/io/github/trevarj/motd/xmpp/XmppEventProcessorTest.kt \
  app/src/main/kotlin/io/github/trevarj/motd/data/db/Daos.kt
git commit -m "feat(xmpp): add XmppEventProcessor as sole writer of XMPP state"
```

---

### Task 6: `XmppAccountActor` + `XmppConnectionManager`

**Files:**
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppAccountActor.kt`
- Create: `app/src/main/kotlin/io/github/trevarj/motd/xmpp/XmppConnectionManager.kt`
- Create: `app/src/test/kotlin/io/github/trevarj/motd/xmpp/XmppConnectionManagerTest.kt`
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/data/db/Daos.kt` (BufferDao: joined-channels query, if absent)

**Interfaces:**
- Consumes: `XmppSessionFactory`, `XmppEventProcessor`, `NetworkDao.observeAll()`, `IrcClientState` (from `io.github.trevarj.motd.irc.event`).
- Produces (used by Task 7):

```kotlin
@Singleton
class XmppConnectionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: MotdDatabase,
    private val processor: XmppEventProcessor,
    private val sessionFactory: XmppSessionFactory,     // Hilt-provided; SmackXmppSessionFactory in prod
    @ApplicationScope private val scope: CoroutineScope,
) {
    val connectionStates: StateFlow<Map<Long, IrcClientState>>
    suspend fun startAll(); suspend fun stopAll()
    suspend fun connect(networkId: Long); suspend fun disconnect(networkId: Long)
    suspend fun reconnectStale()
    suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun joinChannel(networkId: Long, roomJid: String)
    suspend fun partChannel(bufferId: Long, reason: String?)
    suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long
    suspend fun ensureServerBuffer(networkId: Long): Long
}
```

Actor rules (each tested through the manager with `FakeXmppSession`): reconcile actors against `networkDao.observeAll()` filtered to `protocol == Protocol.XMPP && autoConnect`. Config mapping: `XmppAccountConfig(jid!!, saslPassword.orEmpty(), host, port, directTls = tls, mucNick = nick)`. Per connect cycle: fresh session from factory → `connectAndLogin()` → state `Connecting` → on `XmppEvent.Ready` state `Ready(nick = selfBareJid, caps = emptySet(), isupport = emptyMap())` → `processor.failAllPending(networkId)` runs BEFORE each fresh login (clean-reconnect rule) → rejoin all buffers from `bufferDao` where `networkId` matches, `type = CHANNEL`, `joined = 1` (add `@Query("SELECT * FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND joined = 1") suspend fun joinedChannels(networkId: Long): List<BufferEntity>` if no equivalent exists). All events from `session.events` consumed by ONE coroutine calling `processor.process`. On `Disconnected(fatal=false)` → exponential backoff (1s, 2s, 4s … cap 60s) then new session; `fatal=true` → state `Failed(reason, fatal=true)`, stop. `sendMessage`: generate `originId = UUID.randomUUID().toString()`, `processor.createPending(...)` (null → `Rejected(BUFFER_NOT_FOUND)`), then `session.sendChat`/`sendMuc` by buffer type, 30s timeout coroutine flips still-pending row via processor → returns `Accepted(listOf(eventId))`. `sendTyping`: QUERY buffers only, maps `"active"`→composing, else paused/done→inactive. `reconnectStale`: restart actors whose state is `Failed(fatal=false)`/`Disconnected`.

- [ ] **Step 1: Write failing tests** (plain JVM like `AddNetworkViewModelTest` — hand-rolled fakes, `StandardTestDispatcher`; DB via Robolectric in-memory since processor needs it, so use the Robolectric runner like `EventProcessorTest`):

```kotlin
@Test fun ready_onlyAfterRosterLoaded() = runTest { /* emit Ready; state becomes Ready */ }
@Test fun reconnect_createsFreshSession_failsPending_rejoinsMucs() = runTest {
    // seed joined=true CHANNEL buffer + pending row; emit Disconnected(fatal=false);
    // advance past backoff; assert second FakeXmppSession created, joinedRooms contains room,
    // pending row failed=true
}
@Test fun fatalAuthFailure_doesNotRetry() = runTest { /* Disconnected(fatal=true); one connectCalls */ }
@Test fun sendMessage_unknownBuffer_rejected_noPendingRow() = runTest { /* Rejected(BUFFER_NOT_FOUND) */ }
@Test fun sendMessage_writesPending_thenSendsWithSameOriginId() = runTest { /* row msgid == sentChats originId */ }
@Test fun ircRows_areIgnored() = runTest { /* IRC network row spawns no actor */ }
```

- [ ] **Step 2: Run to verify failure** (same test command pattern; expect unresolved classes).

- [ ] **Step 3: Implement actor + manager** per rules; call `AndroidSmackInitializer.initialize(appContext)` in the manager's `init`, wrapped in `runCatching` (no-op under Robolectric where Smack is unused because the factory is faked).

- [ ] **Step 4: Run tests to green; run full `:app:testFossDebugUnitTest`; commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/xmpp/ app/src/test/kotlin/io/github/trevarj/motd/xmpp/ \
  app/src/main/kotlin/io/github/trevarj/motd/data/db/Daos.kt
git commit -m "feat(xmpp): add XmppConnectionManager with per-account actors"
```

---

### Task 7: `RoutingConnectionManager`, DI rewiring, IRC-side protocol filters

**Files:**
- Create: `app/src/main/kotlin/io/github/trevarj/motd/service/RoutingConnectionManager.kt`
- Create: `app/src/test/kotlin/io/github/trevarj/motd/service/RoutingConnectionManagerTest.kt`
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/di/IrcModule.kt` (ConnectionManager binding)
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/service/ConnectionManagerImpl.kt` (reconcile + boot/push predicates: filter to `protocol == Protocol.IRC`)
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/service/BootReceiver.kt` (shouldRun)

**Interfaces:**
- Consumes: `ConnectionManagerImpl` (IRC), `XmppConnectionManager` (Task 6), full `ConnectionManager` interface from `ServiceSeam.kt`.
- Produces: `@Singleton class RoutingConnectionManager @Inject constructor(private val irc: ConnectionManagerImpl, private val xmpp: XmppConnectionManager, private val db: MotdDatabase, @ApplicationScope scope: CoroutineScope) : ConnectionManager` — bound as THE `ConnectionManager`.

Routing rules: `private suspend fun protocolOf(networkId: Long): Protocol = db.networkDao().byId(networkId)?.protocol ?: Protocol.IRC`; buffer-scoped calls resolve `db.bufferDao().rawById(bufferId)?.networkId` first — unresolvable rows: sends return `SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)`, other calls no-op. `connectionStates = combine(irc.connectionStates, xmpp.connectionStates) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyMap())`. `startAll`/`stopAll`/`reconnectStale` call both (IRC first). IRC-only members (`sendReact`, `clientFor`, cert prompts, `markRead`, invites, `retryMessage`, `requestMembers`, `evaluatePushMode`, `partChannelForClose`) delegate to `irc` unconditionally EXCEPT: `sendReact`/`requestMembers`/`markRead` no-op for XMPP buffers, and `partChannel` routes by protocol. `sendTyping` routes by protocol. `ensureQueryBuffer`/`ensureServerBuffer`/`joinChannel` route by the network's protocol.

IRC-side filters (the ONLY two touches of existing pipeline code, both one-liners):
1. `ConnectionManagerImpl`: wherever `networkDao.observeAll()` / `connectable()` results feed `reconcile`/`maybeStopForPush`/`wantedNetworkIds`, filter `it.protocol == Protocol.IRC` at the collection site (`reconcile(all.filter { it.protocol == Protocol.IRC })`).
2. `BootReceiver.onReceive`: after computing `shouldRun`, add `val hasXmpp = networks.any { it.protocol == Protocol.XMPP }` and use `if (shouldRun || hasXmpp) startService(context)` (XMPP always wants the persistent socket; `connectable()` already filters `autoConnect`).

- [ ] **Step 1: Write failing router tests** (fake `ConnectionManager`-shaped recorder for IRC + a recording `XmppConnectionManager` seam — introduce `interface XmppConnectionSurface` implemented by `XmppConnectionManager` if constructor injection of the concrete class is awkward to fake; keep the router depending on the interface):

```kotlin
@Test fun sendMessage_ircBuffer_goesToIrc() = runTest { /* buffer on IRC network -> irc.sendMessage called */ }
@Test fun sendMessage_xmppBuffer_goesToXmpp() = runTest { /* xmpp recorder hit */ }
@Test fun sendMessage_deletedBuffer_rejected() = runTest { /* Rejected(BUFFER_NOT_FOUND), neither hit */ }
@Test fun startAll_fansOutToBoth() = runTest { /* both recorders hit */ }
@Test fun states_merge() = runTest { /* map contains ids from both source flows */ }
@Test fun sendReact_xmppBuffer_isNoop() = runTest { /* neither irc.sendReact nor xmpp touched */ }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement router; rebind in `IrcModule`:**

```kotlin
@Binds @Singleton
abstract fun connectionManager(impl: RoutingConnectionManager): ConnectionManager
```

Apply the two IRC-side filters. Search for every `ConnectionManagerImpl` injection site expecting the concrete type (e.g. `RequiredE2eEntryPoint`, `CertPromptViewModel` paths) — they keep working because the router delegates; only the interface binding changes.

- [ ] **Step 4: Run router tests + FULL unit suite** (`:app:testFossDebugUnitTest`) — existing `FakeConnectionManager` test fakes implement the interface and are unaffected. Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/service/ app/src/main/kotlin/io/github/trevarj/motd/di/IrcModule.kt \
  app/src/test/kotlin/io/github/trevarj/motd/service/RoutingConnectionManagerTest.kt
git commit -m "feat(xmpp): route ConnectionManager calls by network protocol"
```

---

### Task 8: Account setup UI — XMPP path in NetworkForm/AddNetworkViewModel

**Files:**
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/ui/settings/NetworkForm.kt` (new `XmppAccountForm` composable + `buildXmppNetworkEntity`)
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/ui/settings/addnetwork/AddNetworkViewModel.kt` + its UI state/screen (add-network kind chooser)
- Modify: `app/src/main/res/values/strings.xml`
- Modify/Create: `app/src/test/kotlin/io/github/trevarj/motd/ui/settings/AddNetworkViewModelTest.kt` (extend)

**Interfaces:**
- Produces:

```kotlin
data class XmppForm(
    val jid: String = "", val password: String = "", val mucNick: String = "",
    val host: String = "", val port: String = "5222", val directTls: Boolean = false,
) {
    val jidValid: Boolean   // localpart@domain, both non-blank, single '@'
    val isValid: Boolean    // jidValid && password.isNotBlank()
    val effectiveMucNick: String  // mucNick.ifBlank { jid.substringBefore('@') }
    val effectiveHost: String     // host.ifBlank { jid.substringAfter('@') }
    val effectivePort: Int        // port.toIntOrNull() ?: if (directTls) 5223 else 5222
}
fun buildXmppNetworkEntity(form: XmppForm): NetworkEntity
```

`buildXmppNetworkEntity` mapping (spec §3): `protocol = Protocol.XMPP`, `jid = form.jid.trim().lowercase()`, `name = jid`, `role = DIRECT`, `host = effectiveHost`, `port = effectivePort`, `tls = directTls` (false = STARTTLS default), `nick = effectiveMucNick`, `username = jid.substringBefore('@')`, `realname = ""`, `saslMechanism = "NONE"`, `saslPassword = form.password`, everything else defaults.

- [ ] **Step 1: Write failing ViewModel/entity tests** (plain-JVM `AddNetworkViewModelTest` conventions):

```kotlin
@Test fun xmppForm_jidValidation() { assertFalse(XmppForm(jid = "nodomain").jidValid); assertTrue(XmppForm(jid = "a@b.c").jidValid) }
@Test fun buildXmppNetworkEntity_defaults_toStartTls5222() { val e = buildXmppNetworkEntity(XmppForm(jid = "A@B.net", password = "p")); assertEquals(Protocol.XMPP, e.protocol); assertEquals("a@b.net", e.jid); assertFalse(e.tls); assertEquals(5222, e.port); assertEquals("b.net", e.host); assertEquals("a", e.nick) }
@Test fun submitXmpp_insertsRow_andConnects() = runTest { /* FakeNetworkRepository row has protocol XMPP; FakeConnectionManager.connect called */ }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**: `XmppForm` + `buildXmppNetworkEntity` in `NetworkForm.kt` beside `buildNetworkEntity` (line ~554); `XmppAccountForm` composable (JID, password, MUC nickname, collapsed "Advanced" host/port/direct-TLS section — reuse the file's existing text-field composables); extend `AddNetworkUiState.kind` with an XMPP case and the add-network screen's kind chooser with an "XMPP account" option; `AddNetworkViewModel.submit` branches to `buildXmppNetworkEntity` for that kind. New strings (convention `network_settings_*` / `add_network_*`):

```xml
<string name="add_network_kind_xmpp">XMPP account</string>
<string name="network_settings_xmpp_jid">Address (user@domain)</string>
<string name="network_settings_xmpp_password">Password</string>
<string name="network_settings_xmpp_muc_nick">Group chat nickname</string>
<string name="network_settings_xmpp_direct_tls">Direct TLS (port 5223)</string>
```

- [ ] **Step 4: Tests green + `lintFossDebug`; commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/ui/settings/ app/src/main/res/values/strings.xml \
  app/src/test/kotlin/io/github/trevarj/motd/ui/settings/
git commit -m "feat(xmpp): add XMPP account setup form"
```

---

### Task 9: New-conversation + chat capability gating

**Files:**
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/ui/chatlist/NewConversationSheet.kt`
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatScreen.kt`
- Create: `app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ProtocolCapabilities.kt`
- Create/extend tests: `app/src/test/kotlin/io/github/trevarj/motd/ui/chat/ProtocolCapabilitiesTest.kt`, extend existing ChatViewModel tests

**Interfaces:**
- Produces:

```kotlin
package io.github.trevarj.motd.ui.chat

data class ProtocolCapabilities(
    val slashCommands: Boolean, val reactions: Boolean, val replies: Boolean, val typing: Boolean,
) {
    companion object {
        val IRC = ProtocolCapabilities(slashCommands = true, reactions = true, replies = true, typing = true)
        val XMPP = ProtocolCapabilities(slashCommands = false, reactions = false, replies = false, typing = true)
        fun forProtocol(p: Protocol) = if (p == Protocol.XMPP) XMPP else IRC
        /** Commands still meaningful on XMPP; everything else is rejected client-side. */
        fun xmppAllowed(cmd: ChatCommand): Boolean =
            cmd is ChatCommand.Message || cmd is ChatCommand.Join || cmd is ChatCommand.Part ||
            cmd is ChatCommand.Query || cmd is ChatCommand.Msg || cmd is ChatCommand.None
    }
}
```

Wiring rules:
- `ChatViewModel`: load the buffer's network protocol once (`networkDao`-equivalent via `bufferRepository`/`db.networkDao().byId(buffer.networkId)` — the VM already has DAO access patterns; add `NetworkDao` injection if needed) into `val capabilities: StateFlow<ProtocolCapabilities>`; include in `ChatState`. `/me` text passes through as a `Message` (XEP-0245 — send literally, existing IRC path unchanged). In `submit()`: when `!capabilities.value.slashCommands` and `!ProtocolCapabilities.xmppAllowed(cmd)`, enqueue the existing unsupported-action `ChatUiEvent` (reuse the `ReactionBlocked`-style event channel with a new `CommandUnsupported` variant). `react()`: early-return with `ReactionBlocked` when `!capabilities.value.reactions`.
- `ChatScreen`: hide reply swipe/affordance and reaction UI when the respective capability is false; filter `COMMAND_HINTS` to `listOf("/me", "/join", "/part", "/msg", "/query")` when `!slashCommands`.
- `NewConversationSheet`: it already receives `networks: List<NetworkEntity>` — for a selected network with `protocol == Protocol.XMPP`, the join tab labels/hints switch to room-JID strings, `channelJoinTarget` is NOT applied (use the raw trimmed value; add `fun joinTarget(net: NetworkEntity, value: String) = if (net.protocol == Protocol.XMPP) value.trim() else channelJoinTarget(value)`), and message-user validates a JID.

New strings: `new_sheet_room_jid_hint` ("room@conference.example.net"), `new_sheet_jid_hint` ("user@example.net"), `chat_command_unsupported` ("This command isn’t available in XMPP chats.").

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun capabilities_forProtocol() { assertFalse(ProtocolCapabilities.forProtocol(Protocol.XMPP).reactions); assertTrue(ProtocolCapabilities.forProtocol(Protocol.IRC).slashCommands) }
@Test fun xmppAllowed_filtersCommands() { assertTrue(ProtocolCapabilities.xmppAllowed(parseCommand("/join room@c.x"))); assertFalse(ProtocolCapabilities.xmppAllowed(parseCommand("/whois bob"))) }
@Test fun joinTarget_xmpp_noHashPrefix() { /* joinTarget(xmppNet, "room@c.x") == "room@c.x"; joinTarget(ircNet, "chan") == "#chan" */ }
```

Plus a ChatViewModel test in the existing style asserting `/whois` on an XMPP buffer emits `CommandUnsupported` and calls nothing on the fake ConnectionManager.

- [ ] **Step 2: Run to verify failure.** — **Step 3: Implement** per wiring rules. — **Step 4: Tests + `lintFossDebug` green.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/github/trevarj/motd/ui/ app/src/main/res/values/strings.xml app/src/test/kotlin/io/github/trevarj/motd/ui/
git commit -m "feat(xmpp): protocol-aware conversations and capability gating"
```

---

### Task 10: Seeded fuzz generator for `XmppEventProcessor`

**Files:**
- Create: `app/src/test/kotlin/io/github/trevarj/motd/fuzz/XmppEventProcessorFuzzTest.kt`

**Interfaces:** Consumes `SeededFuzz.runSuspending` (`fuzz/SeededFuzz.kt:73`) and Task 5's processor.

- [ ] **Step 1: Write the generator test** — mirror `EventProcessorStateMachineFuzzTest`: fresh Robolectric in-memory DB per case; generate a shuffled sequence (via `fuzz.random`) of `XmppEvent`s over 2 senders × 2 stanza-ids × {ChatMessage, MucMessage, SendConfirmed, RosterUpdated, MucSelfJoined, Disconnected-then-replay}; `fuzz.record(...)` each op; invariants: no duplicate `(bufferId, senderScope, stanzaId)` message rows, every confirmed origin-id has `pendingLabel == null`, member rows always match the last `MucSelfJoined`/join/left fold.

```kotlin
SeededFuzz.runSuspending(
    target = "xmpp-event-processor", version = 1,
    prCases = 8, nightlyCases = 200,
    replayTest = XmppEventProcessorFuzzTest::class.java.name,
) { fuzz -> /* per-case body as above */ }
```

- [ ] **Step 2: Run** (`--tests "...XmppEventProcessorFuzzTest"`) — PASS deterministically.
- [ ] **Step 3: Commit** — `git commit -m "test(xmpp): seeded generator coverage for XmppEventProcessor"`.

---

### Task 11: Env-gated live tests against ejabberd

**Files:**
- Create: `app/src/test/kotlin/io/github/trevarj/motd/xmpp/XmppLiveTest.kt`

**Interfaces:** Consumes `SmackXmppSession` (Task 4) directly — this is the task that proves the Smack adapter.

Env contract (document in the test's KDoc): `MOTD_XMPP_LIVE_DOMAIN` (e.g. `xmpp.glvortex.net`), `MOTD_XMPP_LIVE_USER1=motd-test`, `MOTD_XMPP_LIVE_PASS1`, `MOTD_XMPP_LIVE_USER2=motd-peer`, `MOTD_XMPP_LIVE_PASS2`. All five unset → every test skips via `Assume.assumeTrue`.

- [ ] **Step 1: Write the tests**

```kotlin
class XmppLiveTest {
    private fun env(k: String): String? = System.getenv(k)
    private fun liveConfig(userVar: String, passVar: String): XmppAccountConfig? {
        val d = env("MOTD_XMPP_LIVE_DOMAIN") ?: return null
        val u = env(userVar) ?: return null; val p = env(passVar) ?: return null
        return XmppAccountConfig("$u@$d", p, d, 5222, directTls = false, mucNick = u)
    }
    @Before fun gate() = Assume.assumeTrue(liveConfig("MOTD_XMPP_LIVE_USER1", "MOTD_XMPP_LIVE_PASS1") != null)

    @Test fun loginAndReady() = runTest(timeout = 30.seconds) {
        val s = SmackXmppSession(liveConfig("MOTD_XMPP_LIVE_USER1", "MOTD_XMPP_LIVE_PASS1")!!)
        s.connectAndLogin()
        assertTrue(s.events.receiveUntil<XmppEvent.Ready>() != null)   // helper: drain w/ timeout
        s.close()
    }
    @Test fun oneToOneRoundtrip() = runTest(timeout = 60.seconds) {
        // login both accounts; peer.sendChat(test JID, "ping-<uuid>", originId);
        // assert test session receives ChatMessage with that text AND peer receives SendConfirmed(originId)
    }
    @Test fun mucRoundtrip() = runTest(timeout = 60.seconds) {
        // both join "motd-e2e@conference.$domain" (auto-created); peer sends; test receives MucMessage;
        // peer receives its own reflection as MucMessage(occupantNick = peer nick)
    }
    @Test fun directTls5223_alsoWorks() = runTest(timeout = 30.seconds) { /* same login, port 5223, directTls=true */ }
}
```

- [ ] **Step 2: Run WITHOUT env vars** — expected: 4 skipped, suite green (proves CI safety).
- [ ] **Step 3: Run WITH env vars** (operator supplies real values in the shell):

```bash
MOTD_XMPP_LIVE_DOMAIN=xmpp.glvortex.net MOTD_XMPP_LIVE_USER1=motd-test MOTD_XMPP_LIVE_PASS1=... \
MOTD_XMPP_LIVE_USER2=motd-peer MOTD_XMPP_LIVE_PASS2=... \
nix develop -c ./gradlew :app:testFossDebugUnitTest --tests "io.github.trevarj.motd.xmpp.XmppLiveTest" --stacktrace
```

Expected: 4 passed. Fix `SmackXmppSession` until they do — this is the adapter's acceptance gate.
- [ ] **Step 4: Commit** — `git commit -m "test(xmpp): env-gated live tests against a real ejabberd"`.

---

### Task 12: Docs + full release-parity verification

**Files:**
- Modify: `ARCHITECTURE.md` (add an XMPP subsystem paragraph: parallel slice, single-writer XmppEventProcessor, router seam, capability gating, persistent-socket rule)
- Modify: `README.md` (features table: one XMPP row describing core-messaging support and the v1 limitations — no history sync, no E2EE, no multi-client carbons)

- [ ] **Step 1: Write the doc updates** (match each file's existing tone; keep the limitation caveats from spec §1).
- [ ] **Step 2: Full verification**

```bash
nix develop -c ./gradlew :irc:build \
  :app:testFossDebugUnitTest :app:testFossReleaseUnitTest \
  :app:lintFossDebug :app:lintFossRelease :app:assembleFossRelease \
  --stacktrace --no-daemon --max-workers=1
```

Expected: BUILD SUCCESSFUL, zero lint errors.
- [ ] **Step 3: Commit** — `git commit -m "docs: describe the XMPP subsystem"`.

---

## Self-review notes (already applied)

- Spec coverage: §3 → Tasks 2/5; §4 → Tasks 4/6/7; §5 → Tasks 8/9; §6 → Tasks 5/10/11/12; Smack dep → Task 1; BootReceiver/push predicate → Task 7; typing → Tasks 5/6/9; STARTTLS default → Tasks 8/11.
- Type consistency: `Protocol`, `XmppEvent.*`, `XmppSession`, `XmppAccountConfig`, `XmppEventProcessor` method names, and `ProtocolCapabilities` are each defined once above and referenced identically across tasks.
- Known judgment points left to the implementer (intentional, small): exact placement of the alias-insert DAO method (beside the existing `EventAliasEntity` writer), the add-network kind-chooser UI shape (follow the existing kind/preset chooser), and the `receiveUntil` drain helper in the live test.
