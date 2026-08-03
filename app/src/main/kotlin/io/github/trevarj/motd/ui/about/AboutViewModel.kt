package io.github.trevarj.motd.ui.about

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AboutDiagnosticsUiState(
    val enabled: Boolean = false,
    val agentwireEnabled: Boolean = false,
    val exporting: Boolean = false,
    val exportResult: ExportResult? = null,
)

enum class ExportResult { SUCCESS, FAILURE }

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticLogger: DiagnosticLogger,
    private val agentwirePrefs: AgentwirePrefs,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val exportState = MutableStateFlow(AboutDiagnosticsUiState())

    val state: StateFlow<AboutDiagnosticsUiState> = combine(
        diagnosticLogger.enabled,
        agentwirePrefs.enabled,
        exportState,
    ) { enabled, agentwireEnabled, export -> export.copy(enabled = enabled, agentwireEnabled = agentwireEnabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AboutDiagnosticsUiState())

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        diagnosticLogger.setEnabled(enabled)
        exportState.update { it.copy(exportResult = null) }
    }

    fun setAgentwireEnabled(enabled: Boolean) {
        viewModelScope.launch { agentwirePrefs.setEnabled(enabled) }
    }

    fun export(uri: Uri) {
        if (exportState.value.exporting) return
        exportState.update { it.copy(exporting = true, exportResult = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Unable to open the selected document")
                    output.use { diagnosticLogger.exportTo(it) }
                }
            }
            diagnosticLogger.record("diagnostics", "export_finished") {
                mapOf("success" to result.isSuccess)
            }
            exportState.update {
                it.copy(
                    exporting = false,
                    exportResult = if (result.isSuccess) ExportResult.SUCCESS else ExportResult.FAILURE,
                )
            }
        }
    }
}
