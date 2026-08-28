package io.github.trevarj.motd.data.repo

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.db.IgnoredAutoGroupPatternEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.PendingFolderAssignmentEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.RoomEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_FOLDER_NAME = 64
private const val MAX_ICON_REFERENCE = 128

data class FolderIconRef(
    val kind: FolderIconKind = FolderIconKind.GENERIC,
    val key: String = "folder",
)

data class FolderPortableDefinition(
    val exportId: String,
    val name: String,
    val icon: FolderIconRef,
    val ordering: Int,
    val expanded: Boolean,
)

data class FolderPortableAssignment(
    val networkId: Long,
    val folderExportId: String,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
)

data class FolderBackupSnapshot(
    val folders: List<FolderPortableDefinition>,
    val assignments: List<FolderPortableAssignment>,
    val ignored: List<IgnoredAutoGroupPatternEntity>,
)

@Singleton
class ChatFolderRepository
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) {
        fun observeFolders(): Flow<List<ChatFolderEntity>> = db.chatFolderDao().observeFolders()

        fun observeIgnored(): Flow<List<IgnoredAutoGroupPatternEntity>> = db.chatFolderDao().observeIgnored()

        suspend fun folders(): List<ChatFolderEntity> = db.chatFolderDao().allFolders()

        suspend fun create(
            name: String,
            icon: FolderIconRef = FolderIconRef(),
        ): Long =
            db.withTransaction {
                val displayName = validateFolderName(name)
                validateIcon(icon)
                val dao = db.chatFolderDao()
                val normalized = normalizeFolderName(displayName)
                require(dao.folderByName(normalized) == null) { "Folder name already exists." }
                dao.insertFolder(
                    ChatFolderEntity(
                        displayName = displayName,
                        normalizedName = normalized,
                        iconKind = icon.kind,
                        iconKey = icon.key,
                        ordering = dao.allFolders().size,
                    ),
                )
            }

        suspend fun createAndAssign(
            name: String,
            icon: FolderIconRef,
            bufferIds: Collection<Long>,
        ): Long =
            db.withTransaction {
                val id = create(name, icon)
                assignInternal(bufferIds, id)
                id
            }

        suspend fun save(
            folderId: Long,
            name: String,
            icon: FolderIconRef,
            memberIds: Collection<Long>,
        ): Long =
            db.withTransaction {
                val dao = db.chatFolderDao()
                val displayName = validateFolderName(name)
                validateIcon(icon)
                val normalized = normalizeFolderName(displayName)
                val conflict = dao.folderByName(normalized)
                require(conflict == null || conflict.id == folderId) { "Folder name already exists." }
                val id =
                    if (folderId == 0L) {
                        dao.insertFolder(
                            ChatFolderEntity(
                                displayName = displayName,
                                normalizedName = normalized,
                                iconKind = icon.kind,
                                iconKey = icon.key,
                                ordering = dao.allFolders().size,
                            ),
                        )
                    } else {
                        val current = requireNotNull(dao.folder(folderId)) { "Folder no longer exists." }
                        dao.updateFolder(
                            current.copy(
                                displayName = displayName,
                                normalizedName = normalized,
                                iconKind = icon.kind,
                                iconKey = icon.key,
                            ),
                        )
                        folderId
                    }
                val selected = memberIds.toSet()
                dao.assignedRooms().filter { it.folderId == id && it.id !in selected }.forEach { dao.assign(it.id, null) }
                assignInternal(selected, id)
                id
            }

        suspend fun assign(
            bufferIds: Collection<Long>,
            folderId: Long?,
        ) = db.withTransaction {
            if (folderId != null) requireNotNull(db.chatFolderDao().folder(folderId)) { "Folder no longer exists." }
            assignInternal(bufferIds, folderId)
        }

        private suspend fun assignInternal(
            bufferIds: Collection<Long>,
            folderId: Long?,
        ) {
            bufferIds.distinct().forEach { bufferId ->
                val room = db.chatFolderDao().canonicalRoom(bufferId) ?: return@forEach
                if (room.type == BufferType.SERVER) return@forEach
                db.chatFolderDao().assign(room.id, folderId)
                clearPendingFor(room)
            }
        }

        suspend fun setExpanded(
            folderId: Long,
            expanded: Boolean,
        ) {
            db.chatFolderDao().setExpanded(folderId, expanded)
        }

        suspend fun delete(folderId: Long) =
            db.withTransaction {
                db.chatFolderDao().deleteFolder(folderId)
                normalizeOrder()
            }

        suspend fun reorder(orderedIds: List<Long>) =
            db.withTransaction {
                val dao = db.chatFolderDao()
                val known = dao.allFolders().map(ChatFolderEntity::id)
                val requested = orderedIds.filterTo(LinkedHashSet(), known::contains)
                (requested + known.filterNot(requested::contains)).forEachIndexed { index, id -> dao.setOrdering(id, index) }
            }

        suspend fun rejectAutoGroup(
            networkId: Long,
            normalizedPrefix: String,
        ) {
            db.chatFolderDao().upsertIgnored(IgnoredAutoGroupPatternEntity(networkId, normalizedPrefix))
        }

        suspend fun resetIgnored() = db.chatFolderDao().clearIgnored()

        suspend fun backupSnapshot(): FolderBackupSnapshot =
            db.withTransaction {
                val folders = db.chatFolderDao().allFolders()
                val exportIds = folders.associate { it.id to "folder-${it.id}" }
                val aliases = db.roomAliasDao().allNow().groupBy { it.roomId }
                val assignments =
                    db.chatFolderDao().assignedRooms().mapNotNull { room ->
                        val folderExportId = room.folderId?.let(exportIds::get) ?: return@mapNotNull null
                        val identity = portableIdentity(room, aliases[room.id].orEmpty()) ?: return@mapNotNull null
                        FolderPortableAssignment(
                            networkId = room.networkId,
                            folderExportId = folderExportId,
                            chatType = room.type,
                            identityKind = identity.first,
                            identityValue = identity.second,
                        )
                    }
                FolderBackupSnapshot(
                    folders =
                        folders.map { folder ->
                            FolderPortableDefinition(
                                exportId = exportIds.getValue(folder.id),
                                name = folder.displayName,
                                icon = FolderIconRef(folder.iconKind, folder.iconKey),
                                ordering = folder.ordering,
                                expanded = folder.expanded,
                            )
                        },
                    assignments = assignments,
                    ignored = db.chatFolderDao().allIgnored(),
                )
            }

        suspend fun restore(
            folders: List<FolderPortableDefinition>,
            assignments: List<FolderPortableAssignment>,
            ignored: List<IgnoredAutoGroupPatternEntity>,
            replace: Boolean,
        ) = db.withTransaction {
            validatePortable(folders, assignments, ignored)
            val dao = db.chatFolderDao()
            if (replace) {
                dao.clearAssignments()
                dao.clearPending()
                dao.clearIgnored()
                dao.clearFolders()
            }
            val folderIds = mutableMapOf<String, Long>()
            folders.sortedBy(FolderPortableDefinition::ordering).forEachIndexed { index, portable ->
                val displayName = validateFolderName(portable.name)
                validateIcon(portable.icon)
                val normalized = normalizeFolderName(displayName)
                val local = dao.folderByName(normalized)
                val id =
                    if (local == null) {
                        dao.insertFolder(
                            ChatFolderEntity(
                                displayName = displayName,
                                normalizedName = normalized,
                                iconKind = portable.icon.kind,
                                iconKey = portable.icon.key,
                                ordering = index,
                                expanded = portable.expanded,
                            ),
                        )
                    } else {
                        dao.updateFolder(
                            local.copy(
                                displayName = displayName,
                                iconKind = portable.icon.kind,
                                iconKey = portable.icon.key,
                                ordering = index,
                                expanded = portable.expanded,
                            ),
                        )
                        local.id
                    }
                folderIds[portable.exportId] = id
            }
            assignments.forEach { assignment ->
                val folderId = folderIds[assignment.folderExportId] ?: return@forEach
                val room = resolveAssignment(assignment)
                if (room != null) {
                    dao.assign(room.id, folderId)
                } else {
                    dao.upsertPending(
                        PendingFolderAssignmentEntity(
                            networkId = assignment.networkId,
                            chatType = assignment.chatType,
                            identityKind = assignment.identityKind,
                            identityValue = assignment.identityValue,
                            folderId = folderId,
                        ),
                    )
                }
            }
            ignored.forEach { pattern -> dao.upsertIgnored(pattern) }
            val importedOrder = folderIds.values.toList()
            val unmatched = dao.allFolders().map(ChatFolderEntity::id).filterNot(importedOrder::contains)
            (importedOrder + unmatched).forEachIndexed { index, id -> dao.setOrdering(id, index) }
        }

        suspend fun claimPending(
            roomId: Long,
            account: String? = null,
            normalizedNick: String? = null,
            normalizedChannel: String? = null,
        ): Long? =
            db.withTransaction {
                val dao = db.chatFolderDao()
                val room = dao.canonicalRoom(roomId) ?: return@withTransaction null
                val candidates =
                    when (room.type) {
                        BufferType.CHANNEL -> {
                            listOfNotNull(normalizedChannel?.let { FolderIdentityKind.CHANNEL to it })
                        }

                        BufferType.QUERY -> {
                            listOfNotNull(
                                account?.let { FolderIdentityKind.ACCOUNT to it },
                                normalizedNick?.let { FolderIdentityKind.NICK to it },
                            )
                        }

                        BufferType.SERVER -> {
                            emptyList()
                        }
                    }
                val pending =
                    candidates.firstNotNullOfOrNull { (kind, value) ->
                        dao.pending(room.networkId, room.type, kind, value)
                    } ?: return@withTransaction null
                candidates.forEach { (kind, value) -> dao.deletePending(room.networkId, room.type, kind, value) }
                if (room.folderId == null) dao.assign(room.id, pending.folderId)
                pending.folderId.takeIf { room.folderId == null }
            }

        private suspend fun resolveAssignment(assignment: FolderPortableAssignment): RoomEntity? {
            val aliasNamespace =
                when (assignment.identityKind) {
                    FolderIdentityKind.CHANNEL -> RoomAliasNamespace.CHANNEL
                    FolderIdentityKind.ACCOUNT -> RoomAliasNamespace.ACCOUNT
                    FolderIdentityKind.NICK -> RoomAliasNamespace.VERIFIED_NICK
                }
            val alias =
                db.roomAliasDao().byValue(assignment.networkId, aliasNamespace, assignment.identityValue)
                    ?: assignment.identityKind.takeIf { it == FolderIdentityKind.NICK }?.let {
                        db.roomAliasDao().byValue(
                            assignment.networkId,
                            RoomAliasNamespace.PROVISIONAL_NICK,
                            assignment.identityValue,
                        )
                    }
            return alias
                ?.let { db.chatFolderDao().canonicalRoom(it.roomId) }
                ?.takeIf { it.type == assignment.chatType }
        }

        private suspend fun clearPendingFor(room: RoomEntity) {
            val aliases = db.roomAliasDao().forRoom(room.id)
            aliases.forEach { alias ->
                val kind =
                    when (alias.namespace) {
                        RoomAliasNamespace.CHANNEL -> FolderIdentityKind.CHANNEL

                        RoomAliasNamespace.ACCOUNT -> FolderIdentityKind.ACCOUNT

                        RoomAliasNamespace.VERIFIED_NICK,
                        RoomAliasNamespace.PROVISIONAL_NICK,
                        -> FolderIdentityKind.NICK

                        else -> null
                    } ?: return@forEach
                db.chatFolderDao().deletePending(room.networkId, room.type, kind, alias.value)
            }
        }

        private suspend fun normalizeOrder() {
            db.chatFolderDao().allFolders().forEachIndexed { index, folder ->
                if (folder.ordering != index) db.chatFolderDao().setOrdering(folder.id, index)
            }
        }
    }

private fun portableIdentity(
    room: RoomEntity,
    aliases: List<io.github.trevarj.motd.data.db.RoomAliasEntity>,
): Pair<FolderIdentityKind, String>? =
    when (room.type) {
        BufferType.CHANNEL -> {
            aliases
                .firstOrNull { it.namespace == RoomAliasNamespace.CHANNEL }
                ?.let { FolderIdentityKind.CHANNEL to it.value }
                ?: (FolderIdentityKind.CHANNEL to room.name.substringBefore('\u0000'))
        }

        BufferType.QUERY -> {
            aliases
                .firstOrNull { it.namespace == RoomAliasNamespace.ACCOUNT && it.verified }
                ?.let { FolderIdentityKind.ACCOUNT to it.value }
                ?: aliases
                    .firstOrNull { it.namespace == RoomAliasNamespace.VERIFIED_NICK }
                    ?.let { FolderIdentityKind.NICK to it.value }
                ?: aliases
                    .firstOrNull { it.namespace == RoomAliasNamespace.PROVISIONAL_NICK }
                    ?.let { FolderIdentityKind.NICK to it.value }
        }

        BufferType.SERVER -> {
            null
        }
    }

fun validateFolderName(raw: String): String {
    val name = raw.trim()
    require(name.length in 1..MAX_FOLDER_NAME) { "Folder name must be 1-64 characters." }
    require(name.none { it.isISOControl() || it == '\n' || it == '\r' }) { "Folder name contains control characters." }
    return name
}

fun normalizeFolderName(name: String): String = name.lowercase(Locale.ROOT)

private fun validateIcon(icon: FolderIconRef) {
    require(icon.key.isNotBlank() && icon.key.length <= MAX_ICON_REFERENCE) { "Invalid folder icon reference." }
}

private fun validatePortable(
    folders: List<FolderPortableDefinition>,
    assignments: List<FolderPortableAssignment>,
    ignored: List<IgnoredAutoGroupPatternEntity>,
) {
    require(folders.size <= 512 && assignments.size <= 100_000 && ignored.size <= 10_000) { "Folder backup is too large." }
    require(folders.map(FolderPortableDefinition::exportId).distinct().size == folders.size) { "Duplicate folder ids." }
    require(folders.map { normalizeFolderName(validateFolderName(it.name)) }.distinct().size == folders.size) { "Duplicate folder names." }
    val folderIds = folders.mapTo(mutableSetOf(), FolderPortableDefinition::exportId)
    assignments.forEach {
        require(it.folderExportId in folderIds && it.identityValue.isNotBlank() && it.identityValue.length <= 512) {
            "Invalid folder assignment."
        }
    }
    ignored.forEach { require(it.normalizedPrefix.isNotBlank() && it.normalizedPrefix.length <= 512) { "Invalid ignored Auto-group prefix." } }
}
