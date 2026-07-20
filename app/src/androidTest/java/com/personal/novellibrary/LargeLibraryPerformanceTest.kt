package com.personal.novellibrary

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.novellibrary.data.FileScanRecord
import com.personal.novellibrary.data.NovelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class LargeLibraryPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test(timeout = 300_000)
    fun importsTwoThousandThenTenThousandAndIncrementallyRescans() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.novelDao()

        val firstBatch = records(2_000)
        val firstMs = measureTimeMillis {
            val result = dao.applyFileScan(firstBatch)
            assertEquals(2_000, result.inserted)
        }
        val fullBatch = records(10_000)
        val fullMs = measureTimeMillis {
            val result = dao.applyFileScan(fullBatch)
            assertEquals(8_000, result.inserted)
            assertEquals(2_000, result.unchanged)
        }
        val incrementalMs = measureTimeMillis {
            val changed = fullBatch.toMutableList().also { it[5_000] = it[5_000].copy(fileSize = 999) }
            val result = dao.applyFileScan(changed)
            assertEquals(1, result.changed)
            assertEquals(9_999, result.unchanged)
        }
        val stabilityMs = measureTimeMillis {
            repeat(10) { cycle ->
                val repeated = fullBatch.toMutableList().also {
                    val index = cycle * 997 % it.size
                    it[index] = it[index].copy(fileSize = 20_000L + cycle)
                }
                val result = dao.applyFileScan(repeated)
                assertEquals(10_000, result.changed + result.unchanged)
                assertEquals(0, result.inserted)
            }
        }

        Log.i("NovelLibraryPerf", "2k=${firstMs}ms 10k=${fullMs}ms incremental10k=${incrementalMs}ms stability10x=${stabilityMs}ms")
        assertEquals(10_000, dao.files().size)
        db.close()
    }

    private fun records(count: Int): List<FileScanRecord> = (1..count).map { index ->
        FileScanRecord(
            documentUri = "content://performance/novel-$index.txt",
            originalFileName = "[완결] 성능 테스트 소설 $index 1-200화.txt",
            normalizedTitle = "성능 테스트 소설 $index",
            fileSize = index.toLong(),
            lastModified = 1_700_000_000_000L + index,
        )
    }
}
