package io.github.trevarj.motd.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MappedStateFlowTest {
    @Test
    fun `emits only when the mapped value changes and reads stay fresh`() = runTest {
        val source = MutableStateFlow(5 to "a")
        val mapped = MappedStateFlow(source) { (number, _) -> number }
        val emissions = mutableListOf<Int>()
        val job = backgroundScope.launch { mapped.collect { emissions.add(it) } }
        runCurrent()

        // Unrelated field changes must not fan out redundant emissions...
        source.value = 5 to "b"
        runCurrent()
        // ...but a change in the mapped projection must, even when it arrives together with
        // further unrelated churn (the redial sessionSeq case from the review).
        source.value = 6 to "c"
        runCurrent()
        source.value = 6 to "d"
        runCurrent()

        assertEquals(listOf(5, 6), emissions)
        assertEquals(6, mapped.value)
        job.cancel()
    }
}
