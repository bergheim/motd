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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration17To18Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate17To18_addsServerTimeIdIndex() {
        val db = createExportedVersion17()
        MIGRATION_17_18.migrate(db)
        assertTrue(hasIndex(db, "index_messages_serverTime_id"))
    }

    @Test
    fun migrate17To18_isIdempotent() {
        val db = createExportedVersion17()
        MIGRATION_17_18.migrate(db)
        // CREATE INDEX IF NOT EXISTS must not fail if the index already exists.
        MIGRATION_17_18.migrate(db)
        assertTrue(hasIndex(db, "index_messages_serverTime_id"))
    }

    private fun hasIndex(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { it.moveToFirst() }

    private fun createExportedVersion17(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion17(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        return helper!!.writableDatabase
    }

    private fun createExportedVersion17(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/17.json"
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
        const val DB_NAME = "migration-17-18-test.db"
    }
}
