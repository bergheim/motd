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

@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration lifts legacy recoverable poison without touching sibling tables`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE buffers (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                        db.execSQL(
                            """CREATE TABLE history_gaps (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                roomId INTEGER NOT NULL,
                                olderMsgid TEXT,
                                olderServerTime INTEGER NOT NULL,
                                newerMsgid TEXT,
                                newerServerTime INTEGER NOT NULL,
                                recoverable INTEGER NOT NULL DEFAULT 1,
                                olderEventId INTEGER,
                                olderTimelineOrder INTEGER,
                                newerEventId INTEGER,
                                newerTimelineOrder INTEGER
                            )""",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("INSERT INTO buffers(id, name) VALUES (7, '#kept')")
        // Legacy poison: a timestamp-only saturated page wrongly stamped unrecoverable.
        db.execSQL(
            """INSERT INTO history_gaps
                (id, roomId, olderMsgid, olderServerTime, newerMsgid, newerServerTime, recoverable)
                VALUES (1, 7, NULL, 1000, 'newer-a', 2000, 0)""",
        )
        // Already-recoverable gap must remain recoverable and otherwise untouched.
        db.execSQL(
            """INSERT INTO history_gaps
                (id, roomId, olderMsgid, olderServerTime, newerMsgid, newerServerTime, recoverable)
                VALUES (2, 7, 'older-b', 3000, 'newer-b', 4000, 1)""",
        )

        MIGRATION_22_23.migrate(db)

        // Both gaps end recoverable; the poisoned row is finally allowed to page again.
        db.query("SELECT id, recoverable FROM history_gaps ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals(2, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
        }
        // Non-recoverable columns are untouched: only the flag was repaired.
        db.query(
            "SELECT olderMsgid, olderServerTime, newerMsgid, newerServerTime FROM history_gaps WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(1000, cursor.getLong(1))
            assertEquals("newer-a", cursor.getString(2))
            assertEquals(2000, cursor.getLong(3))
        }
        // Sibling tables are left exactly as seeded.
        db.query("SELECT name FROM buffers WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("#kept", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM history_gaps").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-22-23-test.db"
    }
}
