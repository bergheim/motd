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
class Migration23To24Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds protocol discriminator defaulting existing rows to irc`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL
                            )""",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("INSERT INTO networks(id, name) VALUES (1, 'libera')")

        MIGRATION_23_24.migrate(db)

        db.query("SELECT protocol FROM networks WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("irc", cursor.getString(0))
        }

        // New rows keep defaulting without naming a protocol, and the value set stays open.
        db.execSQL("INSERT INTO networks(id, name) VALUES (2, 'oftc')")
        db.execSQL("INSERT INTO networks(id, name, protocol) VALUES (3, 'other', 'some-future-proto')")
        db.query("SELECT id, protocol FROM networks ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("irc", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("irc", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("some-future-proto", cursor.getString(1))
        }
    }

    private companion object { const val DB_NAME = "migration-23-24-test.db" }
}
