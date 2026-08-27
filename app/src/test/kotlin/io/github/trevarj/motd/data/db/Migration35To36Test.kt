package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v35 -> v36: purely additive index plus a console-row repair. The index must match the firehose
 * ordering exactly.
 */
@RunWith(RobolectricTestRunner::class)
class Migration35To36Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationAddsTheCrossBufferOrderingIndexAndRepairsBouncerRootConsoleRowsOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(35) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 35)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val db = helper!!.writableDatabase
        db.execSQL(
            """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                saslMechanism, autoConnect, ordering, restoreAutoConnect)
               VALUES (1, 'soju', 'BOUNCER_ROOT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
        )
        db.execSQL(
            """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                saslMechanism, autoConnect, ordering, restoreAutoConnect)
               VALUES (2, 'plain', 'DIRECT', 'irc.other', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 1, 1)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (2, 1, 'bouncerserv', 'BouncerServ', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (3, 1, 'alice', 'alice', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (4, 2, 'bouncerserv', 'BouncerServ', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                soundHandled)
               VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'kept', 0, 0, 0, 'd1', 1, 1, 1,
                'SERVER_TAG', 0, 0, 0)""",
        )
        val before = indexNames(db)

        MIGRATION_35_36.migrate(db)

        assertTrue("existing indices must survive", indexNames(db).containsAll(before))
        assertTrue("cross-buffer index missing", INDEX_NAME in indexNames(db))
        db.query("PRAGMA index_info(`$INDEX_NAME`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
            assertEquals(listOf("serverTime", "timelineOrder", "id"), columns)
        }
        db.query("SELECT text FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
        }
        // One-time repair: soju's console becomes SERVER; no other query is touched.
        assertEquals("SERVER", bufferType(db, id = 2))
        assertEquals("QUERY", bufferType(db, id = 3))
        // Off a bouncer root that nick is a real user, and retyping their DM would hide it.
        assertEquals("QUERY", bufferType(db, id = 4))
    }

    private fun bufferType(
        db: SupportSQLiteDatabase,
        id: Long,
    ): String =
        db.query("SELECT type FROM buffers WHERE id = $id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("PRAGMA index_list(`messages`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private companion object {
        const val DB_NAME = "migration-35-36-test.db"
        const val INDEX_NAME = "index_messages_serverTime_timelineOrder_id"
    }
}
