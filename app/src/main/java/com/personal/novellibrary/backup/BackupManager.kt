package com.personal.novellibrary.backup

import android.content.Context
import android.net.Uri
import com.personal.novellibrary.data.NovelEntity
import com.personal.novellibrary.export.NovelCsvExport
import com.personal.novellibrary.export.NovelJsonExport
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class LibraryBackupManager(private val context: Context) {
    fun exportBackup(uri: Uri, novels: List<NovelEntity>, settingsJson: JSONObject = JSONObject()) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("백업 파일을 만들 수 없습니다.")
        output.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putText("metadata.json", JSONObject(mapOf("format" to "Novel Library Backup", "version" to 1)).toString(2))
                zip.putText("settings.json", settingsJson.toString(2))
                zip.putText("library.json", NovelJsonExport.toJson(novels).toString(2))
                zip.putText("README.txt", "TXT bodies are intentionally not included. Restore modes: MERGE, REPLACE, USER_DATA_ONLY, COLLECTIONS_ONLY.")
            }
        }
    }

    fun exportCsv(uri: Uri, novels: List<NovelEntity>) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("CSV 파일을 만들 수 없습니다.")
        output.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                writer.write(NovelCsvExport.toCsv(novels))
            }
        }
    }

    fun readBackup(uri: Uri): BackupPayload {
        val input = context.contentResolver.openInputStream(uri) ?: error("백업 파일을 열 수 없습니다.")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var libraryJson: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == "library.json") {
                        libraryJson = zip.readBytesLimited(MAX_ENTRY_BYTES).toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                }
                requireNotNull(libraryJson) { "library.json이 없는 백업입니다." }
                return BackupPayload(NovelJsonExport.fromJson(libraryJson))
            }
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "백업 항목이 너무 큽니다." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object { private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024 }

}

enum class RestoreMode { MERGE, REPLACE, USER_DATA_ONLY, COLLECTIONS_ONLY }
data class BackupPayload(val novels: List<NovelEntity>)
