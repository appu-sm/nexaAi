package com.nexaai.assistant

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.AllowedApp
import com.nexaai.assistant.ui.AssistantViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigurationFlowTest {
    @Test fun appAllowlistPersistsSeparatelyAndCorruptionFailsClosed() = runBlocking {
        val file = File.createTempFile("nexa-apps", ".preferences_pb").apply { delete() }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val apps = listOf(AllowedApp("trusted.maps", listOf("maps", "navigation")))
        try {
            val firstStore = PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            val first = AppAllowlistRepository(firstStore)
            first.save(apps.single())
            assertEquals(apps, first.apps())
            assertEquals(Configuration(), ConfigurationRepository(firstStore).snapshots.first().configuration)
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            val secondStore = PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file })
            val second = AppAllowlistRepository(secondStore)
            assertEquals(apps, second.apps())
            secondStore.edit { it[stringPreferencesKey("app_allowlist_v1")] = "{broken" }
            assertTrue(second.apps().isEmpty())
            assertNotNull(second.snapshots.first().warning)
            second.reset()
            assertTrue(second.apps().isEmpty())
            assertNull(second.snapshots.first().warning)
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun persistenceSurvivesClosingAndReopeningStore() = runBlocking {
        val file = File.createTempFile("nexa-reopen", ".preferences_pb").apply { delete() }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
            first.update { it.copy(settings = it.settings.copy(displayName = "Persistent", assistantEnabled = false, wakePhrase = "Hey Radio",
                grammarEnabled = true, grammarCustomWords = listOf("navigation", "cabin music"), asrEngineId = "moonshine_base_en")) }
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            val second = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
            assertEquals("Persistent", second.snapshots.first().configuration.settings.displayName)
            assertFalse(second.snapshots.first().configuration.settings.assistantEnabled)
            assertEquals("Hey Radio", second.snapshots.first().configuration.settings.wakePhrase)
            assertTrue(second.snapshots.first().configuration.settings.grammarEnabled)
            assertEquals(listOf("navigation", "cabin music"), second.snapshots.first().configuration.settings.grammarCustomWords)
            assertEquals("moonshine_base_en", second.snapshots.first().configuration.settings.asrEngineId)
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun importRequiresPreviewAndConfirmationAndExportRoundTrips() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File.createTempFile("nexa-flow", ".preferences_pb").apply { delete() }
        val document = File.createTempFile("nexa-config", ".json")
        val viewModels = ViewModelStore()
        val repository = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val documents = ConfigurationDocuments(context.contentResolver)
        val viewModel = AssistantViewModel(repository, documents)
        viewModels.put("assistant", viewModel)
        val collector = launch(Dispatchers.Unconfined) { viewModel.state.collect { } }
        try {
            withTimeout(10_000) { viewModel.state.first { it.snapshot.ready } }
            val imported = Configuration(settings = AssistantSettings(displayName = "Imported", assistantEnabled = false, wakePhrase = "Hello Cabin",
                grammarEnabled = true, grammarCustomWords = listOf("navigation")))
            documents.write(Uri.fromFile(document), imported)
            assertEquals(imported, documents.read(Uri.fromFile(document)))
            viewModel.importDocument(Uri.fromFile(document))
            withTimeout(10_000) { viewModel.state.first { it.importPreview != null && !it.busy } }
            assertEquals(Configuration(), repository.snapshots.first().configuration)
            viewModel.cancelImport()
            withTimeout(10_000) { viewModel.state.first { it.importPreview == null } }
            assertEquals(Configuration(), repository.snapshots.first().configuration)
            viewModel.importDocument(Uri.fromFile(document))
            withTimeout(10_000) { viewModel.state.first { it.importPreview != null && !it.busy } }
            viewModel.applyImport()
            withTimeout(10_000) { viewModel.state.first { it.message == "Configuration imported" && !it.busy } }
            assertEquals(imported, repository.snapshots.first().configuration)
            viewModel.resetAll()
            withTimeout(10_000) { viewModel.state.first { it.message == "Safe defaults restored" && !it.busy } }
            assertEquals(Configuration(), repository.snapshots.first().configuration)
            document.writeText("x".repeat(ConfigurationCodec.MAX_BYTES + 1))
            viewModel.importDocument(Uri.fromFile(document))
            withTimeout(10_000) { viewModel.state.first { it.message == "Configuration exceeds 64 KiB" && !it.busy } }
            assertNull(viewModel.state.value.importPreview)
            assertEquals(Configuration(), repository.snapshots.first().configuration)
            val metrics = com.nexaai.assistant.diagnostics.VoiceDiagnostics().apply { setEnabled(true) }
            metrics.record(com.nexaai.assistant.diagnostics.VoiceEvent.ERROR, com.nexaai.assistant.diagnostics.DiagnosticCode.ASR)
            val report = metrics.export()
            documents.writeDiagnosticReport(Uri.fromFile(document), report)
            assertEquals(report, document.readText())
            val json = kotlinx.serialization.json.Json.parseToJsonElement(document.readText()).toString()
            assertTrue(json.contains("ERROR:ASR"))
            assertFalse(json.contains("Imported"))
            assertTrue(runCatching { documents.writeDiagnosticReport(Uri.fromFile(document), "x".repeat(512 * 1024 + 1)) }.isFailure)
        } finally {
            collector.cancelAndJoin()
            viewModels.clear()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
            file.delete()
            document.delete()
        }
    }
}