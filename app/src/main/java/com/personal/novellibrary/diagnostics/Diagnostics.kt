package com.personal.novellibrary.diagnostics

import android.content.Context
import com.personal.novellibrary.data.NovelDao
import kotlinx.coroutines.flow.first
import java.io.File

data class DiagnosticsSnapshot(
    val packageName: String,
    val appVersion: String,
    val dbVersion: Int,
    val dbBytes: Long,
    val novelCount: Int,
    val folderPermissionCount: Int,
)

class DiagnosticsService(private val context: Context, private val dao: NovelDao) {
    suspend fun snapshot(dbVersion: Int): DiagnosticsSnapshot {
        val dbFile = context.getDatabasePath("novel_library.db")
        return DiagnosticsSnapshot(
            packageName = context.packageName,
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("unknown"),
            dbVersion = dbVersion,
            dbBytes = dbFile.sizeOrZero(),
            novelCount = dao.observeCount().first(),
            folderPermissionCount = context.contentResolver.persistedUriPermissions.size,
        )
    }

    fun format(snapshot: DiagnosticsSnapshot): String = buildString {
        appendLine("package=${snapshot.packageName}")
        appendLine("version=${snapshot.appVersion}")
        appendLine("dbVersion=${snapshot.dbVersion}")
        appendLine("dbBytes=${snapshot.dbBytes}")
        appendLine("novelCount=${snapshot.novelCount}")
        appendLine("folderPermissionCount=${snapshot.folderPermissionCount}")
    }

    private fun File.sizeOrZero(): Long = if (exists()) length() else 0L
}
