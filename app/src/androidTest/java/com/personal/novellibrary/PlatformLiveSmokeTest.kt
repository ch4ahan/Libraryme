package com.personal.novellibrary

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.novellibrary.platform.PlatformRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformLiveSmokeTest {
    @Test(timeout = 120_000)
    fun fivePublicPlatformSearchesReturnAtLeastOneVerifiableCandidate() = runBlocking {
        val results = PlatformRegistry().adapters.map { adapter ->
            adapter.platformType to runCatching { adapter.search("악녀는 두 번 산다") }
        }
        results.forEach { (platform, result) ->
            Log.i(
                "NovelLibraryPlatform",
                "$platform candidates=${result.getOrNull()?.size ?: 0} error=${result.exceptionOrNull()?.message}",
            )
        }

        assertEquals(5, results.size)
        val candidates = results.flatMap { it.second.getOrDefault(emptyList()) }
        assertTrue("All five public searches failed or returned no candidates", candidates.isNotEmpty())
        assertTrue(candidates.all { it.detailUrl?.startsWith("https://") == true })
    }
}
