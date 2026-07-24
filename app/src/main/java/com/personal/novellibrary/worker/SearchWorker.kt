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
import com.personal.novellibrary.data.MIGRATION_4_5
import com.personal.novellibrary.data.NovelDatabase
import com.personal.novellibrary.data.PlatformListingEntity
import com.personal.novellibrary.data.PlatformType
import com.personal.novellibrary.data.SearchCandidateEntity
import com.personal.novellibrary.data.SyncJobEntity
import com.personal.novellibrary.platform.PlatformRegistry
import com.personal.novellibrary.platform.SearchCandidate
import com.personal.novellibrary.platform.candidateCompleteness
import com.personal.novellibrary.domain.Candidate
import com.personal.novellibrary.domain.MatchScorer
import com.personal.novellibrary.domain.PlatformSearchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class PlatformSearchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val query = inputData.getString(KEY_QUERY)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val novelId = inputData.getLong(KEY_NOVEL_ID, -1L).takeIf { it > 0L } ?: return Result.failure()
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        val rawPlatforms = inputData.getString(KEY_PLATFORMS)
        val enabledPlatforms = rawPlatforms
            ?.split(',')
            ?.mapNotNull { runCatching { PlatformType.valueOf(it) }.getOrNull() }
            ?.toSet()
            .orEmpty()
        val registry = PlatformRegistry().let { registry ->
            if (rawPlatforms == null) registry else PlatformRegistry(registry.adapters.filter { it.platformType in enabledPlatforms })
        }
        if (registry.adapters.isEmpty()) return Result.success()

        val db = Room.databaseBuilder(applicationContext, NovelDatabase::class.java, NovelDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        return try {
            val dao = db.novelDao()
            dao.cancelSyncJobs(listOf(novelId), reason = "새 검색 작업으로 교체")
            var failures = 0

            registry.adapters.forEachIndexed { adapterIndex, adapter ->
                val startedAt = System.currentTimeMillis()
                val cached = dao.platformListing(novelId, adapter.platformType)
                val hasUsableConfirmation = cached?.lookupStatus != LookupStatus.NEEDS_USER_CONFIRMATION ||
                    dao.pendingCandidateCount(novelId, adapter.platformType) > 0
                if (cached != null &&
                    hasUsableConfirmation &&
                    PlatformSearchPolicy.canReuse(cached.lookupStatus, cached.lastFetchedAt, startedAt, forceRefresh)
                ) {
                    return@forEachIndexed
                }

                val jobId = dao.insertSyncJob(
                    SyncJobEntity(
                        novelId = novelId,
                        platformType = adapter.platformType,
                        jobType = "SEARCH_AND_DETAILS",
                        status = JobStatus.RUNNING,
                        startedAt = startedAt,
                    ),
                )
                dao.clearPendingCandidates(novelId, adapter.platformType)
                dao.setListingState(novelId, adapter.platformType, LookupStatus.LOADING, startedAt)
                try {
                    val candidates = adapter.search(query)
                    if (candidates.isEmpty()) {
                        dao.setListingState(novelId, adapter.platformType, LookupStatus.NO_RESULT, System.currentTimeMillis())
                        dao.updateSyncJob(jobId, JobStatus.NO_RESULT, finished = true)
                    } else {
                        var autoMatched = false
                        var proposed = false
                        candidates
                            .rankForDetailLookup(query)
                            .take(MAX_DETAIL_LOOKUPS_PER_PLATFORM)
                            .forEach candidateLoop@{ rawCandidate ->
                                if (autoMatched) return@candidateLoop
                                val detailed = try {
                                    adapter.fetchDetails(rawCandidate).candidate
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    rawCandidate
                                }
                                if (dao.isCandidateExcluded(novelId, adapter.platformType, detailed.workId, detailed.title)) {
                                    return@candidateLoop
                                }
                                val confidence = MatchScorer.score(query, Candidate(detailed.title, detailed.author, detailed.genre))
                                if (!MatchScorer.needsUserConfirmation(confidence)) {
                                    val fetchedAt = System.currentTimeMillis()
                                    dao.upsertListing(
                                        PlatformListingEntity(
                                            novelId = novelId, platformType = adapter.platformType,
                                            platformWorkId = detailed.workId, platformTitle = detailed.title,
                                            platformAuthor = detailed.author, synopsis = detailed.synopsis,
                                            genre = detailed.genre, coverUrl = detailed.coverUrl, detailUrl = detailed.detailUrl,
                                            matchConfidence = confidence, lookupStatus = LookupStatus.SUCCESS, lastFetchedAt = fetchedAt,
                                        ),
                                    )
                                    dao.applyPlatformMetadataIfMissing(novelId, detailed.author, detailed.synopsis, detailed.coverUrl, fetchedAt)
                                    dao.clearPendingCandidates(novelId, adapter.platformType)
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
                                System.currentTimeMillis(),
                            )
                        }
                        dao.updateSyncJob(jobId, JobStatus.SUCCESS, finished = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures++
                    val message = error.message?.take(MAX_ERROR_MESSAGE_LENGTH)
                    val failedAt = System.currentTimeMillis()
                    dao.setListingState(novelId, adapter.platformType, LookupStatus.FAILED, failedAt, message)
                    dao.updateSyncJob(jobId, JobStatus.FAILED, finished = true, errorMessage = message)
                }
                if (adapterIndex < registry.adapters.lastIndex) delay(REQUEST_SPACING_MS)
            }
            if (failures == registry.adapters.size) {
                if (shouldRetryPlatformSearch(failures, registry.adapters.size, runAttemptCount)) Result.retry() else Result.failure()
            } else {
                Result.success()
            }
        } finally {
            db.close()
        }
    }

    companion object {
        const val KEY_QUERY = "query"
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_PLATFORMS = "platforms"
        const val KEY_FORCE_REFRESH = "forceRefresh"
        private const val REQUEST_SPACING_MS = 750L
        private const val MAX_DETAIL_LOOKUPS_PER_PLATFORM = 3
        private const val MAX_ERROR_MESSAGE_LENGTH = 500

        fun enqueue(
            context: Context,
            novelId: Long,
            query: String,
            enabledPlatforms: Set<PlatformType> = PlatformType.entries.toSet(),
            wifiOnly: Boolean = true,
            forceRefresh: Boolean = false,
        ) {
            val cleanQuery = query.trim().take(100)
            if (cleanQuery.isEmpty() || enabledPlatforms.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<PlatformSearchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkTypeForSearch(wifiOnly))
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_QUERY, cleanQuery)
                        .putLong(KEY_NOVEL_ID, novelId)
                        .putString(KEY_PLATFORMS, enabledPlatforms.joinToString(",") { it.name })
                        .putBoolean(KEY_FORCE_REFRESH, forceRefresh)
                        .build(),
                )
                .addTag("platform-search")
                .addTag("novel-$novelId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                platformSearchWorkName(novelId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

internal fun platformSearchWorkName(novelId: Long): String = "platform-search-$novelId"

internal fun networkTypeForSearch(wifiOnly: Boolean): NetworkType =
    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

internal fun shouldRetryPlatformSearch(failures: Int, platformCount: Int, runAttemptCount: Int): Boolean =
    platformCount > 0 && failures == platformCount && runAttemptCount < 2

/** Prioritizes likely title matches before performing comparatively expensive detail requests. */
internal fun List<SearchCandidate>.rankForDetailLookup(query: String): List<SearchCandidate> =
    sortedWith(
        compareByDescending<SearchCandidate> {
            MatchScorer.score(query, Candidate(it.title, it.author, it.genre))
        }.thenByDescending(::candidateCompleteness),
    )
