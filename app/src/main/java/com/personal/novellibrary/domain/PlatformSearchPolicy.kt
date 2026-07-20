package com.personal.novellibrary.domain

import com.personal.novellibrary.data.LookupStatus

object PlatformSearchPolicy {
    const val CACHE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun canReuse(status: LookupStatus, lastFetchedAt: Long?, now: Long, forceRefresh: Boolean): Boolean {
        if (forceRefresh || lastFetchedAt == null) return false
        val age = now - lastFetchedAt
        return age in 0 until CACHE_TTL_MS && status in setOf(
            LookupStatus.SUCCESS,
            LookupStatus.NO_RESULT,
            LookupStatus.NEEDS_USER_CONFIRMATION,
        )
    }
}
