package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v35 -> v36 indexes the cross-buffer reverse-chronological scan. Purely additive: no column, row,
 * or existing index changes, and the new index must match the ordering contract exactly or the
 * firehose still sorts the whole table.
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
    fun migrationAddsTheCrossBufferOrderingIndexWithoutTouchingExistingState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(35) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion35(db)

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
               VALUES (1, 'net', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
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
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("PRAGMA index_list(`messages`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun createExportedVersion35(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/35.json"
        val schema =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val database = schema.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content

            fun executeTemplate(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
            executeTemplate(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                executeTemplate(
                    index.jsonObject
                        .getValue("createSql")
                        .jsonPrimitive.content,
                )
            }
            entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { trigger ->
                db.execSQL(trigger.jsonPrimitive.content)
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
    }

    private companion object {
        const val DB_NAME = "migration-35-36-test.db"
        const val INDEX_NAME = "index_messages_serverTime_timelineOrder_id"
    }
}
