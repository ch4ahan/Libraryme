package com.personal.novellibrary

import androidx.work.NetworkType
import com.personal.novellibrary.worker.networkTypeForSearch
import com.personal.novellibrary.worker.platformSearchWorkName
import com.personal.novellibrary.worker.shouldRetryPlatformSearch
import com.personal.novellibrary.worker.rankForDetailLookup
import com.personal.novellibrary.data.PlatformType
import com.personal.novellibrary.platform.SearchCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchWorkerPolicyTest {
    @Test
    fun individualSearchCanUseAnyConnectedNetwork() {
        assertEquals(NetworkType.CONNECTED, networkTypeForSearch(wifiOnly = false))
    }

    @Test
    fun bulkWifiOnlySearchWaitsForUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, networkTypeForSearch(wifiOnly = true))
    }

    @Test
    fun failedPlatformSearchStopsAfterThreeAttempts() {
        assertEquals(true, shouldRetryPlatformSearch(failures = 5, platformCount = 5, runAttemptCount = 0))
        assertEquals(true, shouldRetryPlatformSearch(failures = 5, platformCount = 5, runAttemptCount = 1))
        assertEquals(false, shouldRetryPlatformSearch(failures = 5, platformCount = 5, runAttemptCount = 2))
        assertEquals(false, shouldRetryPlatformSearch(failures = 4, platformCount = 5, runAttemptCount = 0))
    }

    @Test
    fun everyQueryForTheSameNovelUsesOneReplacementQueue() {
        assertEquals("platform-search-42", platformSearchWorkName(42))
    }

    @Test
    fun detailLookupsPrioritizeTitleMatchThenAvailableMetadata() {
        fun candidate(title: String, author: String? = null) = SearchCandidate(
            PlatformType.NAVER_SERIES, title, author, null, null, null, null, "https://example.test/$title",
        )

        val ranked = listOf(candidate("전혀 다른 작품", "작가"), candidate("악녀는 두 번 산다"))
            .rankForDetailLookup("악녀는 두번 산다")

        assertEquals("악녀는 두 번 산다", ranked.first().title)
    }
}
