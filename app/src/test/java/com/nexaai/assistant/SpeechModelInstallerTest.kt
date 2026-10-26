package com.nexaai.assistant

import android.net.Uri
import com.nexaai.assistant.speech.VoskModelInstaller
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechModelInstallerTest {
    private val requiredFiles = listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf",
        "graph/HCLr.fst", "graph/Gr.fst", "graph/phones/word_boundary.int")

    private fun archive(prefix: String, files: List<String> = requiredFiles): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { relative ->
                zip.putNextEntry(ZipEntry(prefix + relative))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun acceptsRenamedOfficialAndUnwrappedModelFoldersWithoutWordsFile() = runBlocking {
        listOf("Vosk/", "vosk-model-small-en-us-0.15/", "").forEach { prefix ->
            val root = File.createTempFile("nexa-model", "store").apply { delete(); mkdirs() }
            try {
                val installer = VoskModelInstaller(root) { ByteArrayInputStream(archive(prefix)) }
                installer.install(Uri.parse("content://local/vosk.zip"))
                assertTrue(installer.isInstalled())
                assertTrue(File(root, "vosk-model-small-en-us-0.15/graph/HCLr.fst").isFile)
                assertFalse(File(root, "vosk-model-small-en-us-0.15/graph/words.txt").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun missingGraphFileIsRejectedWithoutReplacingInstalledModel() = runBlocking {
        val root = File.createTempFile("nexa-model", "store").apply { delete(); mkdirs() }
        try {
            val valid = VoskModelInstaller(root) { ByteArrayInputStream(archive("Vosk/")) }
            valid.install(Uri.parse("content://local/valid.zip"))
            val incomplete = VoskModelInstaller(root) {
                ByteArrayInputStream(archive("Vosk/", requiredFiles - "graph/Gr.fst"))
            }
            assertThrows(IOException::class.java) {
                runBlocking { incomplete.install(Uri.parse("content://local/incomplete.zip")) }
            }
            assertTrue(valid.isInstalled())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun malformedAndIncompleteArchivesAreRejectedWithoutInstalling() = runBlocking {
        val root = File.createTempFile("nexa-model", "store").apply { delete(); mkdirs() }
        try {
            val installer = VoskModelInstaller(root) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
            assertThrows(IOException::class.java) { runBlocking { installer.install(Uri.parse("content://local/model.zip")) } }
            assertFalse(installer.isInstalled())
        } finally {
            root.deleteRecursively()
        }
    }
}