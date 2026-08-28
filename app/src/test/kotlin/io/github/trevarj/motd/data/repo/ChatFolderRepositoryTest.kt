package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.sync.BufferStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatFolderRepositoryTest {
    @Test
    fun namesMovesReorderExpansionAndDeleteAreDurable() =
        runTest {
            val db = inMemoryDb()
            val networkId = network(db)
            val store = BufferStore(db)
            val one = store.getOrCreate(networkId, "#one", "#one", BufferType.CHANNEL)
            val two = store.getOrCreate(networkId, "#two", "#two", BufferType.CHANNEL)
            val repository = ChatFolderRepository(db)
            val first = repository.create(" Dev ")
            val second = repository.create("Social")

            assertTrue(runCatching { repository.create("dev") }.isFailure)
            repository.assign(listOf(one.id, two.id), first)
            assertEquals(
                setOf(first),
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .mapNotNull { it.folderId }
                    .toSet(),
            )
            repository.assign(listOf(two.id), second)
            assertEquals(first, db.bufferDao().observeById(one.id)?.folderId)
            assertEquals(second, db.bufferDao().observeById(two.id)?.folderId)

            repository.reorder(listOf(second, first))
            repository.setExpanded(second, false)
            assertEquals(listOf(second, first), repository.folders().map { it.id })
            assertEquals(false, repository.folders().first().expanded)

            repository.delete(second)
            assertNull(db.bufferDao().observeById(two.id)?.folderId)

            db.bufferDao().markPendingClose(one.id, 1L)
            assertNull(db.bufferDao().observeById(one.id)?.folderId)
            val query = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
            repository.assign(listOf(query.id), first)
            db.bufferDao().deleteBuffer(query.id)
            assertNull(db.bufferDao().observeById(query.id)?.folderId)
        }

    @Test
    fun restoreMergeKeepsUnmatchedFoldersAndReplaceClearsThem() =
        runTest {
            val db = inMemoryDb()
            val repository = ChatFolderRepository(db)
            repository.create("Local")
            val imported = listOf(FolderPortableDefinition("remote", "Imported", FolderIconRef(), 0, false))

            repository.restore(imported, emptyList(), emptyList(), replace = false)
            assertEquals(listOf("Imported", "Local"), repository.folders().map { it.displayName })

            repository.restore(imported, emptyList(), emptyList(), replace = true)
            assertEquals(listOf("Imported"), repository.folders().map { it.displayName })
            assertEquals(false, repository.folders().single().expanded)
        }

    @Test
    fun canonicalMergeKeepsWinnerAssignmentOrTransfersLoserAssignment() =
        runTest {
            val db = inMemoryDb()
            val networkId = network(db)
            val store = BufferStore(db)
            val repository = ChatFolderRepository(db)
            val firstFolder = repository.create("First")
            val secondFolder = repository.create("Second")
            val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
            val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
            repository.assign(listOf(winner.id), firstFolder)
            repository.assign(listOf(loser.id), secondFolder)

            store.mergeRooms(winner.id, loser.id)
            assertEquals(firstFolder, db.bufferDao().observeById(winner.id)?.folderId)

            val nextWinner = store.getOrCreate(networkId, "carol", "Carol", BufferType.QUERY)
            val nextLoser = store.getOrCreate(networkId, "dave", "Dave", BufferType.QUERY)
            repository.assign(listOf(nextLoser.id), secondFolder)
            store.mergeRooms(nextWinner.id, nextLoser.id)
            assertEquals(secondFolder, db.bufferDao().observeById(nextWinner.id)?.folderId)
        }

    @Test
    fun restoredAccountIntentWinsNickAndManualAssignmentWinsPending() =
        runTest {
            val db = inMemoryDb()
            val networkId = network(db)
            val repository = ChatFolderRepository(db)
            val accountFolder = repository.create("Account")
            val nickFolder = repository.create("Nick")
            val definitions = repository.backupSnapshot().folders
            val ids = definitions.associateBy { it.name }
            repository.restore(
                folders = definitions,
                assignments =
                    listOf(
                        FolderPortableAssignment(networkId, ids.getValue("Nick").exportId, BufferType.QUERY, FolderIdentityKind.NICK, "alice"),
                        FolderPortableAssignment(networkId, ids.getValue("Account").exportId, BufferType.QUERY, FolderIdentityKind.ACCOUNT, "acct"),
                    ),
                ignored = emptyList(),
                replace = false,
            )
            val room = BufferStore(db).getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
            db.roomAliasDao().insertIgnore(RoomAliasEntity(networkId = networkId, namespace = RoomAliasNamespace.ACCOUNT, value = "acct", roomId = room.id, verified = true))

            repository.claimPending(room.id, account = "acct", normalizedNick = "alice")
            assertEquals(accountFolder, db.bufferDao().observeById(room.id)?.folderId)

            repository.assign(listOf(room.id), nickFolder)
            repository.restore(
                folders = definitions,
                assignments = listOf(FolderPortableAssignment(networkId, ids.getValue("Account").exportId, BufferType.QUERY, FolderIdentityKind.NICK, "future-nick")),
                ignored = emptyList(),
                replace = false,
            )
            repository.claimPending(room.id, normalizedNick = "future-nick")
            assertEquals(nickFolder, db.bufferDao().observeById(room.id)?.folderId)
        }

    private suspend fun network(db: io.github.trevarj.motd.data.db.MotdDatabase): Long =
        db.networkDao().insert(
            NetworkEntity(
                name = "net",
                role = NetworkRole.DIRECT,
                host = "irc.example",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
}
