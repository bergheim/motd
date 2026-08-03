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
class Migration21To22Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration adds an empty durable multi-gap table without changing buffers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE buffers (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
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

        MIGRATION_21_22.migrate(db)

        db.query("SELECT name FROM buffers WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("#kept", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM history_gaps").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("PRAGMA table_info(history_gaps)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
            var recoverableDefault: String? = null
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                columns += name
                if (name == "recoverable") {
                    recoverableDefault = cursor.getString(defaultColumn)
                }
            }
            assertEquals("1", recoverableDefault)
            assertTrue(
                columns.containsAll(
                    setOf(
                        "olderEventId",
                        "olderTimelineOrder",
                        "newerEventId",
                        "newerTimelineOrder",
                    ),
                ),
            )
        }
        db.query("PRAGMA index_list(history_gaps)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
            assertTrue("index_history_gaps_roomId_olderServerTime" in names)
            assertTrue("index_history_gaps_roomId_newerServerTime" in names)
        }
    }

    private companion object {
        const val DB_NAME = "migration-21-22-test.db"
    }
}
