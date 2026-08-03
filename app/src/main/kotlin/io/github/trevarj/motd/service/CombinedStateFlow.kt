package io.github.trevarj.motd.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * StateFlow view combining [sources] through [transform], deduplicating per collector like
 * [MappedStateFlow]. With a single source it degenerates to a mapped view of that source, so a
 * one-backend composite preserves the underlying flow's emission behavior exactly.
 */
internal class CombinedStateFlow<T, R>(
    private val sources: List<StateFlow<T>>,
    private val transform: (List<T>) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(sources.map { it.value })
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        // combine over an empty list completes immediately, which would turn a StateFlow that must
        // never complete into a thrown error. With no backends the combined value can never change,
        // so emit it once and stay open like any other StateFlow.
        if (sources.isEmpty()) {
            collector.emit(transform(emptyList()))
            awaitCancellation()
        }
        val upstream: Flow<R> = if (sources.size == 1) {
            sources.single().map { transform(listOf(it)) }
        } else {
            // The arbitrary-arity combine reifies its element type, which a class-level T cannot
            // satisfy; erase to Any? for the call and restore T for the transform.
            @Suppress("UNCHECKED_CAST")
            combine(sources as List<Flow<Any?>>) { values -> transform(values.map { it as T }) }
        }
        // distinctUntilChanged reproduces the per-collector dedup once for both arities.
        upstream.distinctUntilChanged().collect(collector)
        error("combine over StateFlows never completes")
    }
}
