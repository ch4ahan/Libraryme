package com.personal.novellibrary.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.personal.novellibrary.data.FileScanRecord
import com.personal.novellibrary.data.NovelDao
import com.personal.novellibrary.domain.TitleNormalizer

data class ScanSummary(
    val discovered: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val missingMarked: Boolean,
)

class TxtFileScanner(private val context: Context, private val dao: NovelDao) {
    suspend fun scanTree(
        treeUri: Uri,
        includeSubfolders: Boolean = true,
        onProgress: (discovered: Int) -> Unit = {},
    ): ScanSummary {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ScanSummary(discovered = 0, inserted = 0, changed = 0, unchanged = 0, missingMarked = false)
        require(root.exists() && root.isDirectory && root.canRead()) {
            "선택한 폴더를 읽을 수 없습니다. 폴더 접근 권한을 다시 허용하세요."
        }

        val records = mutableListOf<FileScanRecord>()

        suspend fun visit(dir: DocumentFile) {
            dir.listFiles().forEach { document ->
                when {
                    document.isDirectory && includeSubfolders -> visit(document)
                    document.isFile && document.name?.endsWith(".txt", ignoreCase = true) == true -> {
                        val displayTitle = TitleNormalizer.normalize(document.name.orEmpty())
                        records += FileScanRecord(
                            documentUri = document.uri.toString(),
                            originalFileName = document.name.orEmpty(),
                            normalizedTitle = TitleNormalizer.matchingKey(displayTitle),
                            displayTitle = displayTitle,
                            fileSize = document.length(),
                            lastModified = document.lastModified(),
                        )
                        if (records.size == 1 || records.size % PROGRESS_BATCH_SIZE == 0) {
                            onProgress(records.size)
                        }
                    }
                }
            }
        }

        visit(root)
        onProgress(records.size)
        val dbResult = dao.applyFileScan(records)
        return ScanSummary(
            discovered = records.size,
            inserted = dbResult.inserted,
            changed = dbResult.changed,
            unchanged = dbResult.unchanged,
            missingMarked = true,
        )
    }

    private companion object { const val PROGRESS_BATCH_SIZE = 25 }
}
