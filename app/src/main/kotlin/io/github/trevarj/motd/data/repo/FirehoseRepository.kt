package io.github.trevarj.motd.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.visibility.FirehoseNetwork
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.firehosePagingQuery
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Read-only cross-buffer conversation stream over the shared messages table. */
interface FirehoseRepository {
    fun firehose(
        spec: MessageVisibilitySpec,
        networks: List<FirehoseNetwork>,
    ): Flow<PagingData<FirehoseRow>>
}

// The firehose is derived state: one cross-buffer Paging query, no new write path. Room's
// invalidation on the observed MessageEntity keeps it live.
class FirehoseRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : FirehoseRepository {
    override fun firehose(
        spec: MessageVisibilitySpec,
        networks: List<FirehoseNetwork>,
    ): Flow<PagingData<FirehoseRow>> = Pager(
        config = FIREHOSE_PAGING_CONFIG,
        pagingSourceFactory = {
            messageDao.firehosePagingSource(firehosePagingQuery(spec, networks))
        },
    ).flow
}

internal val FIREHOSE_PAGING_CONFIG = PagingConfig(
    pageSize = 50,
    prefetchDistance = 25,
    enablePlaceholders = false,
)
