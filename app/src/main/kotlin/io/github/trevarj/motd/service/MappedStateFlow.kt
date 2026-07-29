package io.github.trevarj.motd.service

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * StateFlow view mapping [source] on read, deduplicating equal mapped values per collector so a
 * source that republishes with unrelated fields changed does not fan out redundant emissions.
 * Keeps seam types neutral without introducing an extra scope; [value] is always derived from the
 * current source value, so reads never lag the source.
 */
internal class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow never completes")
    }
}
