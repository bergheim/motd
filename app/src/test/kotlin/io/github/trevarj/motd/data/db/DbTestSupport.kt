package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared helper for Robolectric in-memory DB tests.
 *
 * [directCommit] runs queries on the caller's thread, so a write commits and releases its
 * invalidation inline and its effect on a live observer is observable without awaiting an executor.
 */
internal fun inMemoryDb(directCommit: Boolean = false): MotdDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room
        .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
        .allowMainThreadQueries()
        .apply { if (directCommit) setQueryExecutor(Runnable::run) }
        .build()
}

internal fun network(name: String = "libera"): NetworkEntity =
    NetworkEntity(
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

internal fun buffer(
    networkId: Long,
    name: String,
    type: BufferType = BufferType.CHANNEL,
    readMarkerTime: Long? = null,
    pinned: Boolean = false,
): BufferEntity =
    BufferEntity(
        networkId = networkId,
        name = name,
        displayName = name,
        type = type,
        readMarkerTime = readMarkerTime,
        localReadAnchorTime = readMarkerTime,
        localReadAnchorEventId = readMarkerTime?.let { 0L },
        pinned = pinned,
    )

internal fun message(
    bufferId: Long,
    text: String,
    sender: String = "alice",
    serverTime: Long,
    dedupKey: String,
    kind: MessageKind = MessageKind.PRIVMSG,
    isSelf: Boolean = false,
    hasMention: Boolean = false,
    msgid: String? = null,
    pendingLabel: String? = null,
): MessageEntity =
    MessageEntity(
        bufferId = bufferId,
        msgid = msgid,
        serverTime = serverTime,
        sender = sender,
        kind = kind,
        text = text,
        isSelf = isSelf,
        hasMention = hasMention,
        pendingLabel = pendingLabel,
        dedupKey = dedupKey,
    )

/** Creates the real tables, indices, FTS triggers, and Room identity of one tracked schema JSON. */
internal fun createExportedVersion(
    db: SupportSQLiteDatabase,
    version: Int,
) {
    val resource = "${MotdDatabase::class.java.canonicalName}/$version.json"
    val schema =
        checkNotNull(MotdDatabase::class.java.classLoader?.getResourceAsStream(resource)) {
            "missing checked-in Room schema resource $resource"
        }.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val database = schema.getValue("database").jsonObject
    database.getValue("entities").jsonArray.forEach { element ->
        val entity = element.jsonObject
        val tableName = entity.getValue("tableName").jsonPrimitive.content

        fun executeTemplate(sql: String) {
            db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
        }
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
    database.getValue("setupQueries").jsonArray.forEach { query ->
        db.execSQL(query.jsonPrimitive.content)
    }
}
