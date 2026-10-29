package com.nexaai.assistant.data

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConfigurationDocuments(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): Configuration = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(ConfigurationCodec.MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val count = stream.read(buffer, total, buffer.size - total)
                if (count == -1) break
                if (count == 0) throw IOException("Document stream stalled")
                total += count
            }
            buffer.copyOf(total)
        }
            ?: throw IOException("Document could not be opened")
        require(bytes.size <= ConfigurationCodec.MAX_BYTES) { "Configuration exceeds 64 KiB" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        ConfigurationCodec.decode(text)
    }

    suspend fun write(uri: Uri, configuration: Configuration) = withContext(Dispatchers.IO) {
        val bytes = ConfigurationCodec.encode(configuration).toByteArray(Charsets.UTF_8)
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Document could not be written")
    }

    suspend fun writeDiagnosticReport(uri: Uri, report: String) = withContext(Dispatchers.IO) {
        val bytes = report.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "Diagnostic report is too large" }
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Diagnostic report could not be written")
    }
}