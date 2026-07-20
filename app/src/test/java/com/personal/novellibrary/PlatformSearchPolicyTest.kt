package com.personal.novellibrary

import com.personal.novellibrary.data.LookupStatus
import com.personal.novellibrary.domain.PlatformSearchPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSearchPolicyTest {
    private val now = 1_000_000_000L

    @Test
    fun recentCompletedLookupIsReused() {
        assertTrue(PlatformSearchPolicy.canReuse(LookupStatus.SUCCESS, now - 1_000, now, false))
        assertTrue(PlatformSearchPolicy.canReuse(LookupStatus.NO_RESULT, now - 1_000, now, false))
    }

    @Test
    fun forceExpiredAndFailedLookupsAreNotReused() {
        assertFalse(PlatformSearchPolicy.canReuse(LookupStatus.SUCCESS, now - 1_000, now, true))
        assertFalse(PlatformSearchPolicy.canReuse(LookupStatus.SUCCESS, now - PlatformSearchPolicy.CACHE_TTL_MS, now, false))
        assertFalse(PlatformSearchPolicy.canReuse(LookupStatus.FAILED, now - 1_000, now, false))
    }
}
