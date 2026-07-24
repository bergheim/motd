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

@RunWith(RobolectricTestRunner::class)
class Migration16To17Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate16To17_addsProtocolAndJid_backfillsIrc() {
        val db = createExportedVersion16()
        db.execSQL(
            """INSERT INTO networks (name, role, host, port, tls, nick, username, realname,
               saslMechanism, autoConnect, ordering)
               VALUES ('libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'n', 'u', 'r', 'NONE', 1, 0)""",
        )
        MIGRATION_16_17.migrate(db)
        db.query("SELECT protocol, jid FROM networks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("IRC", c.getString(0))
            assertTrue(c.isNull(1))
        }
    }

    private fun createExportedVersion16(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion16(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        return helper!!.writableDatabase
    }

    private fun createExportedVersion16(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/16.json"
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val database = schema.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            fun executeTemplate(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
            executeTemplate(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                executeTemplate(index.jsonObject.getValue("createSql").jsonPrimitive.content)
            }
            entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { trigger ->
                db.execSQL(trigger.jsonPrimitive.content)
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
    }

    private companion object {
        const val DB_NAME = "migration-16-17-test.db"
    }
}
