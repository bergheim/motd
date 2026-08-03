package io.github.trevarj.motd.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.trevarj.motd.MainActivity
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Writes allowlisted structural diagnostics before closing the activity scenario. */
class E2eFailureArtifactRule(
    private val holder: ScenarioHolder,
    private val milestones: E2eMilestoneRecorder,
) : TestWatcher() {
    private var failure: Throwable? = null
    private var description: Description? = null

    override fun starting(description: Description) {
        this.description = description
        // This is the launcher-visible post-start boundary. It contains only the fixed test id;
        // AGP collects it through AndroidX test storage without relying on app data directories.
        writeOutput("required-e2e/started.jsonl", "{\"test\":\"${safeName()}\"}\n", append = true)
    }

    override fun failed(e: Throwable, description: Description) {
        failure = e
        capture()
    }

    override fun finished(description: Description) {
        try { if (failure != null) capture() } finally { holder.close() }
    }

    /**
     * Directory the running journey's structural artifacts live in, so a journey-owned diagnostic
     * lands beside them under the same collected `required-e2e` tree instead of inventing a path.
     */
    fun artifactPrefix(): String = "required-e2e/${safeName()}"

    private fun capture() {
        val error = failure ?: return
        val prefix = artifactPrefix()
        writeOutput(
            "$prefix/failure.json",
            "{\"test\":\"${safeName()}\",\"throwable\":\"${error::class.java.name}\",\"frames\":[" +
                error.stackTrace.take(20).joinToString(",") { "\"${it.className}.${it.methodName}:${it.lineNumber}\"" } + "]}",
        )
        writeOutput("$prefix/route.json", "{\"screen\":\"unavailable\"}")
        writeOutput("$prefix/semantics.json", "{\"tags\":[],\"bounds\":[]}")
        writeOutput("$prefix/lazy-state.json", "{\"visible\":[]}")
        writeOutput("$prefix/connections.json", "{\"states\":[]}")
        writeOutput("$prefix/milestones.jsonl", milestones.render())
    }

    private fun safeName(): String = (description?.className.orEmpty() + "_" + description?.methodName.orEmpty())
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun writeOutput(path: String, content: String, append: Boolean = false) {
        PlatformTestStorageRegistry.getInstance().openOutputFile(path, append).bufferedWriter().use {
            it.write(content)
        }
    }
}

class ScenarioHolder {
    var scenario: ActivityScenario<MainActivity>? = null
    fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    fun close() {
        val owned = scenario ?: return
        scenario = null
        val finishRequested = runCatching {
            owned.onActivity { it.finishAndRemoveTask() }
        }.isSuccess
        if (finishRequested) {
            // ActivityScenario's monitor can remain RESUMED after recreate even though the current
            // activity accepted a terminal finish request. Waiting for idle is sufficient cleanup;
            // calling close again would wait on the stale monitor until its hard timeout.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } else {
            owned.close()
        }
    }
}
