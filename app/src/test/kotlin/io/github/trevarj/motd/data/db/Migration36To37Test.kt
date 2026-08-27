package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v36 -> v37 re-keys the cross-buffer ordering index on `(serverTime, id)`. The v36 index led with
 * `timelineOrder`, which is only comparable within one buffer.
 */
@RunWith(RobolectricTestRunner::class)
class Migration36To37Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationSwapsTheCrossBufferOrderingIndexAndKeepsEveryRowAndOtherIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(36) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 36)

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
               VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
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
        assertTrue("fixture must start on the v36 index", OLD_INDEX in before)

        MIGRATION_36_37.migrate(db)

        val after = indexNames(db)
        assertFalse("the per-buffer-ordered index must be gone", OLD_INDEX in after)
        assertTrue("cross-buffer index missing", NEW_INDEX in after)
        assertTrue("no other index may be dropped", after.containsAll(before - OLD_INDEX))
        db.query("PRAGMA index_info(`$NEW_INDEX`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
            assertEquals(listOf("serverTime", "id"), columns)
        }
        // Index-only change: rows are untouched.
        db.query("SELECT text FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("PRAGMA index_list(`messages`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private companion object {
        const val DB_NAME = "migration-36-37-test.db"
        const val OLD_INDEX = "index_messages_serverTime_timelineOrder_id"
        const val NEW_INDEX = "index_messages_serverTime_id"
    }
}
