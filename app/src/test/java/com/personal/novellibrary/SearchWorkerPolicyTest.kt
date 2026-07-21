package com.personal.novellibrary

import androidx.work.NetworkType
import com.personal.novellibrary.worker.networkTypeForSearch
import com.personal.novellibrary.worker.shouldRetryPlatformSearch
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
}
