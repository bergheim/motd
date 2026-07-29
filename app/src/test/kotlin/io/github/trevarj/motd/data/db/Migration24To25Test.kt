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

@RunWith(RobolectricTestRunner::class)
class Migration24To25Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds the xmpp account satellite table with cascade delete`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                protocol TEXT NOT NULL DEFAULT 'irc'
                            )""",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("INSERT INTO networks(id, name, protocol) VALUES (1, 'jabber', 'xmpp')")

        MIGRATION_24_25.migrate(db)

        db.execSQL(
            "INSERT INTO xmpp_accounts(networkId, jid, password) VALUES (1, 'me@example.org', 's3cret')",
        )
        db.query("SELECT jid, password, resource FROM xmpp_accounts WHERE networkId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("me@example.org", cursor.getString(0))
            assertEquals("s3cret", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }

        // The satellite row follows its network row, so a deleted account leaves no credentials.
        db.execSQL("DELETE FROM networks WHERE id = 1")
        db.query("SELECT COUNT(*) FROM xmpp_accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // IRC rows carry no satellite row: nothing forces one to exist.
        db.execSQL("INSERT INTO networks(id, name) VALUES (2, 'libera')")
        db.query("SELECT protocol FROM networks WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("irc", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private companion object { const val DB_NAME = "migration-24-25-test.db" }
}
