package com.personal.novellibrary.worker

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.NetworkType
import com.personal.novellibrary.data.LookupStatus
import com.personal.novellibrary.data.JobStatus
import com.personal.novellibrary.data.MIGRATION_1_2
import com.personal.novellibrary.data.MIGRATION_2_3
import com.personal.novellibrary.data.MIGRATION_3_4
import com.personal.novellibrary.data.NovelDatabase
import com.personal.novellibrary.data.PlatformListingEntity
import com.personal.novellibrary.data.PlatformType
import com.personal.novellibrary.data.SearchCandidateEntity
import com.personal.novellibrary.data.SyncJobEntity
import com.personal.novellibrary.platform.PlatformRegistry
import com.personal.novellibrary.domain.Candidate
import com.personal.novellibrary.domain.MatchScorer
import com.personal.novellibrary.domain.PlatformSearchPolicy
import kotlinx.coroutines.delay

class PlatformSearchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val query = inputData.getString(KEY_QUERY)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val novelId = inputData.getLong(KEY_NOVEL_ID, -1L).takeIf { it > 0L } ?: return Result.failure()
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        val db = Room.databaseBuilder(applicationContext, NovelDatabase::class.java, NovelDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        val dao = db.novelDao()
        val rawPlatforms = inputData.getString(KEY_PLATFORMS)
        val enabledPlatforms = rawPlatforms
            ?.split(',')
            ?.mapNotNull { runCatching { PlatformType.valueOf(it) }.getOrNull() }
            ?.toSet()
            .orEmpty()
        val registry = PlatformRegistry().let { registry ->
            if (rawPlatforms == null) registry else PlatformRegistry(registry.adapters.filter { it.platformType in enabledPlatforms })
        }
        if (registry.adapters.isEmpty()) {
            db.close()
            return Result.success()
        }
        dao.cancelSyncJobs(listOf(novelId), reason = "새 검색 작업으로 교체")
        var failures = 0
        val now = System.currentTimeMillis()

        registry.adapters.forEach adapterLoop@{ adapter ->
            val cached = dao.platformListing(novelId, adapter.platformType)
            if (cached != null && PlatformSearchPolicy.canReuse(cached.lookupStatus, cached.lastFetchedAt, now, forceRefresh)) {
                return@adapterLoop
            }
            val jobId = dao.insertSyncJob(
                SyncJobEntity(
                    novelId = novelId,
                    platformType = adapter.platformType,
                    jobType = "SEARCH_AND_DETAILS",
                    status = JobStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                ),
            )
            dao.clearPendingCandidates(novelId, adapter.platformType)
            dao.setListingState(novelId, adapter.platformType, LookupStatus.LOADING, now)
            runCatching { adapter.search(query) }
                .onSuccess { candidates ->
                    if (candidates.isEmpty()) {
                        dao.setListingState(novelId, adapter.platformType, LookupStatus.NO_RESULT, now)
                        dao.updateSyncJob(jobId, JobStatus.NO_RESULT, finished = true)
                    } else {
                        var autoMatched = false
                        var proposed = false
                        candidates.forEach candidateLoop@{ rawCandidate ->
                            val detailed = runCatching { adapter.fetchDetails(rawCandidate).candidate }.getOrDefault(rawCandidate)
                            if (dao.isCandidateExcluded(novelId, adapter.platformType, detailed.workId, detailed.title)) {
                                return@candidateLoop
                            }
                            val confidence = MatchScorer.score(query, Candidate(detailed.title, detailed.author, detailed.genre))
                            if (!autoMatched && !MatchScorer.needsUserConfirmation(confidence)) {
                                dao.upsertListing(
                                    PlatformListingEntity(
                                        novelId = novelId, platformType = adapter.platformType,
                                        platformWorkId = detailed.workId, platformTitle = detailed.title,
                                        platformAuthor = detailed.author, synopsis = detailed.synopsis,
                                        genre = detailed.genre, coverUrl = detailed.coverUrl, detailUrl = detailed.detailUrl,
                                        matchConfidence = confidence, lookupStatus = LookupStatus.SUCCESS, lastFetchedAt = now,
                                    ),
                                )
                                dao.applyPlatformMetadataIfMissing(novelId, detailed.author, detailed.synopsis, detailed.coverUrl, now)
                                autoMatched = true
                            } else {
                                proposed = true
                                dao.upsertSearchCandidate(
                                    SearchCandidateEntity(
                                        novelId = novelId, platformType = adapter.platformType,
                                        candidateTitle = detailed.title, candidateAuthor = detailed.author,
                                        candidateSynopsis = detailed.synopsis, candidateGenre = detailed.genre,
                                        candidateCoverUrl = detailed.coverUrl, candidateWorkId = detailed.workId,
                                        confidence = confidence, status = LookupStatus.NEEDS_USER_CONFIRMATION,
                                    ),
                                )
                            }
                        }
                        if (!autoMatched) {
                            dao.setListingState(
                                novelId,
                                adapter.platformType,
                                if (proposed) LookupStatus.NEEDS_USER_CONFIRMATION else LookupStatus.EXCLUDED,
                                now,
                            )
                        }
                        dao.updateSyncJob(jobId, JobStatus.SUCCESS, finished = true)
                    }
                }
                .onFailure { error ->
                    failures++
                    dao.setListingState(novelId, adapter.platformType, LookupStatus.FAILED, now, error.message)
                    dao.updateSyncJob(jobId, JobStatus.FAILED, finished = true, errorMessage = error.message)
                }
            delay(REQUEST_SPACING_MS)
        }
        db.close()
        return if (failures == registry.adapters.size) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_QUERY = "query"
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_PLATFORMS = "platforms"
        const val KEY_FORCE_REFRESH = "forceRefresh"
        private const val REQUEST_SPACING_MS = 750L

        fun enqueue(
            context: Context,
            novelId: Long,
            query: String,
            enabledPlatforms: Set<PlatformType> = PlatformType.entries.toSet(),
            wifiOnly: Boolean = true,
            forceRefresh: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<PlatformSearchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_QUERY, query)
                        .putLong(KEY_NOVEL_ID, novelId)
                        .putString(KEY_PLATFORMS, enabledPlatforms.joinToString(",") { it.name })
                        .putBoolean(KEY_FORCE_REFRESH, forceRefresh)
                        .build(),
                )
                .addTag("platform-search")
                .addTag("novel-$novelId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "platform-search-$novelId-${query.lowercase()}",
                if (forceRefresh) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
