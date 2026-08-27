package io.github.trevarj.motd.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Derived state: one cross-buffer keyset query, kept live by Room invalidation.
class FirehoseRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) : FirehoseRepository {
        override fun firehose(spec: MessageVisibilitySpec): Flow<PagingData<SearchHit>> =
            Pager(
                config = FIREHOSE_PAGING_CONFIG,
                pagingSourceFactory = { FirehosePagingSource(db, spec) },
            ).flow
    }

// Placeholders off: a merged stream has no stable positional identity, and a keyset source has no
// row count to place them against.
internal val FIREHOSE_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = false,
    )
