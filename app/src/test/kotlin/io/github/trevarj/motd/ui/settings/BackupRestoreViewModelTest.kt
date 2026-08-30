package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.backup.BackupExportMode
import io.github.trevarj.motd.data.backup.BackupFormatException
import io.github.trevarj.motd.data.backup.BackupImportMode
import io.github.trevarj.motd.data.backup.ConfigurationBackupRepository
import io.github.trevarj.motd.data.backup.ConfigurationImportPreview
import io.github.trevarj.motd.data.backup.ConfigurationImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupRestoreViewModelTest {
    @Test
    fun `cancelled destination leaves export idle and successful write reports persistent outcome`() =
        runTest {
            withViewModel { vm, repository ->
                assertEquals(BackupExportPhase.IDLE, vm.state.value.exportPhase)
                assertNull(vm.state.value.exportOutcome)
                exportDestinationSelected(null) { vm.export(it, BackupExportMode.CREDENTIALS_EXCLUDED, "") }
                advanceUntilIdle()
                assertEquals(0, repository.exportCalls)

                var written = ""
                vm.writeDocument = { _, text -> written = text }
                vm.export(uri(), BackupExportMode.CREDENTIALS_EXCLUDED, "")
                advanceUntilIdle()

                assertEquals("export-CREDENTIALS_EXCLUDED", written)
                assertEquals(1, repository.exportCalls)
                assertEquals(BackupExportOutcome.Success, vm.state.value.exportOutcome)
            }
        }

    @Test
    fun `write failure is categorized without exposing provider message`() =
        runTest {
            withViewModel { vm, _ ->
                vm.writeDocument = { _, _ -> throw IOException("provider secret path") }
                vm.export(uri(), BackupExportMode.CREDENTIALS_EXCLUDED, "")
                advanceUntilIdle()

                assertEquals(BackupExportOutcome.Failure(BackupFailure.WRITE), vm.state.value.exportOutcome)
            }
        }

    @Test
    fun `plain backup auto previews and mode change recomputes preview`() =
        runTest {
            withViewModel { vm, repository ->
                vm.readDocument = { "plain.motdconfig" to "plain" }
                vm.loadImport(uri())
                advanceUntilIdle()

                assertEquals(BackupImportPhase.PREVIEW, vm.state.value.importPhase)
                assertEquals("plain.motdconfig", vm.state.value.selectedFilename)
                assertFalse(vm.state.value.importEncrypted)
                assertEquals(BackupImportMode.MERGE, repository.previewModes.single())

                vm.setImportMode(BackupImportMode.REPLACE)
                advanceUntilIdle()

                assertEquals(listOf(BackupImportMode.MERGE, BackupImportMode.REPLACE), repository.previewModes)
                assertEquals(
                    2,
                    vm.state.value.preview
                        ?.removedNetworks,
                )
            }
        }

    @Test
    fun `encrypted backup requires password and wrong password is safely categorized`() =
        runTest {
            withViewModel { vm, _ ->
                vm.readDocument = { "private.motdconfig" to "encrypted" }
                vm.loadImport(uri())
                advanceUntilIdle()
                assertEquals(BackupImportPhase.PASSWORD_REQUIRED, vm.state.value.importPhase)
                assertTrue(vm.state.value.importEncrypted)

                vm.previewImport("wrong")
                advanceUntilIdle()

                assertEquals(BackupImportPhase.PASSWORD_REQUIRED, vm.state.value.importPhase)
                assertEquals(
                    BackupImportOutcome.Failure(BackupFailure.WRONG_PASSWORD_OR_CORRUPT),
                    vm.state.value.importOutcome,
                )

                vm.previewImport("correct password")
                advanceUntilIdle()
                assertEquals(BackupImportPhase.PREVIEW, vm.state.value.importPhase)
            }
        }

    @Test
    fun `repeated encrypted preview taps start one repository call`() =
        runTest {
            withViewModel { vm, repository ->
                vm.readDocument = { "private.motdconfig" to "encrypted" }
                vm.loadImport(uri())
                advanceUntilIdle()
                repository.previewGate = CompletableDeferred()

                vm.previewImport("correct password")
                vm.previewImport("correct password")
                runCurrent()
                vm.previewImport("correct password")

                assertEquals(BackupImportPhase.READING, vm.state.value.importPhase)
                assertEquals(1, repository.previewCalls)
                repository.previewGate?.complete(Unit)
                advanceUntilIdle()
                assertEquals(BackupImportPhase.PREVIEW, vm.state.value.importPhase)
            }
        }

    @Test
    fun `plain preview failures are invalid without exposing arbitrary details`() =
        runTest {
            withViewModel { vm, repository ->
                repository.previewFailure = IllegalStateException("provider password path and payload")
                vm.readDocument = { "plain.motdconfig" to "plain" }
                vm.loadImport(uri())
                advanceUntilIdle()

                assertEquals(
                    BackupImportOutcome.Failure(BackupFailure.UNSUPPORTED_OR_INVALID),
                    vm.state.value.importOutcome,
                )
            }
        }

    @Test
    fun `typed format failure and operation IO failures use stable categories`() =
        runTest {
            withViewModel { vm, repository ->
                repository.exportFailure = IOException("invalid provider path is too large")
                vm.export(uri(), BackupExportMode.CREDENTIALS_EXCLUDED, "")
                advanceUntilIdle()
                assertEquals(BackupExportOutcome.Failure(BackupFailure.EXPORT), vm.state.value.exportOutcome)

                repository.exportFailure = null
                vm.readDocument = { throw IOException("unsupported invalid payload") }
                vm.loadImport(uri())
                advanceUntilIdle()
                assertEquals(BackupImportOutcome.Failure(BackupFailure.READ), vm.state.value.importOutcome)

                vm.readDocument = { "private.motdconfig" to "encrypted" }
                vm.loadImport(uri())
                advanceUntilIdle()
                repository.previewFailure = BackupFormatException()
                vm.previewImport("correct password")
                advanceUntilIdle()
                assertEquals(
                    BackupImportOutcome.Failure(BackupFailure.UNSUPPORTED_OR_INVALID),
                    vm.state.value.importOutcome,
                )
            }
        }

    @Test
    fun `replace with removals gates import behind exact confirmation`() =
        runTest {
            withViewModel { vm, repository ->
                vm.readDocument = { "plain.motdconfig" to "plain" }
                vm.loadImport(uri())
                advanceUntilIdle()
                vm.setImportMode(BackupImportMode.REPLACE)
                advanceUntilIdle()

                vm.requestApply("")
                advanceUntilIdle()
                assertTrue(vm.state.value.confirmReplace)
                assertEquals(0, repository.importCalls)

                vm.confirmReplace("")
                advanceUntilIdle()
                assertEquals(1, repository.importCalls)
                assertEquals(BackupImportPhase.COMPLETE, vm.state.value.importPhase)
            }
        }

    @Test
    fun `success clears document and password keeps missing credential summary and blocks duplicate apply`() =
        runTest {
            withViewModel { vm, repository ->
                vm.readDocument = { "private.motdconfig" to "encrypted" }
                vm.loadImport(uri())
                advanceUntilIdle()
                vm.previewImport("correct password")
                advanceUntilIdle()
                vm.requestApply("correct password")
                vm.requestApply("correct password")
                advanceUntilIdle()

                val outcome = vm.state.value.importOutcome as BackupImportOutcome.Success
                assertEquals(3, outcome.result.missingCredentialNetworks)
                assertEquals("private.motdconfig", outcome.sourceFilename)
                assertTrue(outcome.encrypted)
                assertEquals(BackupImportMode.MERGE, outcome.mode)
                assertEquals(1, repository.importCalls)
                assertNull(vm.state.value.selectedFilename)
                assertNull(vm.state.value.preview)
                assertFalse(vm.state.value.importEncrypted)

                vm.requestApply("correct password")
                advanceUntilIdle()
                assertEquals(1, repository.importCalls)

                vm.clearImportOutcome()
                assertNull(vm.state.value.importOutcome)
            }
        }

    @Test
    fun `import failure retains preview for retry`() =
        runTest {
            withViewModel { vm, repository ->
                repository.importFailure = IllegalStateException("database detail")
                vm.readDocument = { "plain.motdconfig" to "plain" }
                vm.loadImport(uri())
                advanceUntilIdle()
                vm.requestApply("")
                advanceUntilIdle()

                assertEquals(BackupImportPhase.PREVIEW, vm.state.value.importPhase)
                assertTrue(vm.state.value.preview != null)
                assertEquals(BackupImportOutcome.Failure(BackupFailure.IMPORT), vm.state.value.importOutcome)
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(
        block: suspend (BackupRestoreViewModel, FakeBackupRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeBackupRepository()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val vm = BackupRestoreViewModel(repository, context, dispatcher)
            block(vm, repository)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun uri() = Uri.parse("content://backup/document")

    private class FakeBackupRepository : ConfigurationBackupRepository {
        var exportCalls = 0
        var previewCalls = 0
        var importCalls = 0
        var exportFailure: Exception? = null
        var previewFailure: Exception? = null
        var importFailure: Exception? = null
        var previewGate: CompletableDeferred<Unit>? = null
        val previewModes = mutableListOf<BackupImportMode>()

        override suspend fun exportToString(
            mode: BackupExportMode,
            password: String?,
            nowEpochMillis: Long,
        ): String {
            exportCalls++
            exportFailure?.let { throw it }
            return "export-$mode"
        }

        override suspend fun preview(
            rawDocument: String,
            password: String?,
            importMode: BackupImportMode,
        ): ConfigurationImportPreview {
            previewCalls++
            previewFailure?.let { throw it }
            if (rawDocument == "encrypted" && password != "correct password") throw AEADBadTagException("crypto detail")
            previewGate?.await()
            previewModes += importMode
            return ConfigurationImportPreview(
                appVersion = "1.0",
                exportedAtEpochMillis = 1_000,
                containsSecrets = rawDocument == "encrypted",
                networkCount = 4,
                addedNetworks = 1,
                updatedNetworks = 1,
                removedNetworks = if (importMode == BackupImportMode.REPLACE) 2 else 0,
                retainedLocalCredentials = 0,
                missingCredentialNetworks = 3,
                settingGroups = listOf("general"),
            )
        }

        override suspend fun import(
            rawDocument: String,
            password: String?,
            importMode: BackupImportMode,
        ): ConfigurationImportResult {
            importCalls++
            importFailure?.let { throw it }
            return ConfigurationImportResult(1, 1, if (importMode == BackupImportMode.REPLACE) 2 else 0, 3)
        }

        override fun isEncrypted(rawDocument: String) = rawDocument == "encrypted"
    }
}
