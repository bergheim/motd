package io.github.trevarj.motd.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.visibility.FirehoseNetwork
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.firehosePagingQuery
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Read-only cross-buffer conversation stream over the shared messages table. */
interface FirehoseRepository {
    fun firehose(
        spec: MessageVisibilitySpec,
        networks: List<FirehoseNetwork>,
    ): Flow<PagingData<FirehoseRow>>
}

// Derived state: one cross-buffer paging query, no write path of its own. Room's invalidation on
// the observed entities is what keeps the stream live.
class FirehoseRepositoryImpl
    @Inject
    constructor(
        private val messageDao: MessageDao,
    ) : FirehoseRepository {
        override fun firehose(
            spec: MessageVisibilitySpec,
            networks: List<FirehoseNetwork>,
        ): Flow<PagingData<FirehoseRow>> =
            Pager(
                config = FIREHOSE_PAGING_CONFIG,
                pagingSourceFactory = { messageDao.firehosePagingSource(firehosePagingQuery(spec, networks)) },
            ).flow
    }

// Placeholders are off: the merged stream has no positional identity worth reserving, and a global
// COUNT over every room would be paid on each invalidation.
internal val FIREHOSE_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = false,
    )
