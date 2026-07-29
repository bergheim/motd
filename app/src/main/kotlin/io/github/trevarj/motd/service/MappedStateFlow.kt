package io.github.trevarj.motd.service

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

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
        var emitted = false
        var last: R? = null
        source.collect { upstream ->
            val next = transform(upstream)
            if (!emitted || next != last) {
                emitted = true
                last = next
                collector.emit(next)
            }
        }
    }
}
