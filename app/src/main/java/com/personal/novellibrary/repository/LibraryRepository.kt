package com.personal.novellibrary.repository

import com.personal.novellibrary.backup.RestoreMode
import com.personal.novellibrary.data.ChangeHistoryEntity
import com.personal.novellibrary.data.CollectionEntity
import com.personal.novellibrary.data.CollectionItemEntity
import com.personal.novellibrary.data.ExcludedCandidateEntity
import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.LookupStatus
import com.personal.novellibrary.data.PlatformListingEntity
import com.personal.novellibrary.data.SearchCandidateEntity
import com.personal.novellibrary.data.NovelDao
import com.personal.novellibrary.data.NovelEntity
import com.personal.novellibrary.data.ReadingStatus
import com.personal.novellibrary.domain.TitleNormalizer

data class ReadingRecordUpdate(
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val droppedAt: Long? = null,
    val lastReadChapter: String? = null,
    val rereadCount: Int = 0,
    val readingNote: String? = null,
)

class LibraryRepository(private val dao: NovelDao) {
    fun observeNovels(query: String = "") = dao.observeNovels(escapeLikeQuery(query))
    fun observeCount() = dao.observeCount()
    fun observeTrash() = dao.observeTrash()
    fun observeCollections() = dao.observeCollections()
    fun observeSearchCandidates() = dao.observeSearchCandidates()
    fun observeSyncJobs() = dao.observeSyncJobs()

    suspend fun markSearchCancelled(novelIds: List<Long>) = dao.cancelSyncJobs(novelIds)

    suspend fun restoreNovels(imported: List<NovelEntity>, mode: RestoreMode): Int {
        if (mode == RestoreMode.COLLECTIONS_ONLY) return 0
        var restored = 0
        imported.forEach { backup ->
            val normalizedBackup = backup.copy(
                normalizedTitle = TitleNormalizer.matchingKey(
                    backup.confirmedTitle ?: backup.displayTitle.ifBlank { backup.normalizedTitle },
                ).ifBlank { backup.normalizedTitle },
            )
            val existing = dao.novelByNormalizedTitle(normalizedBackup.normalizedTitle)
            if (existing == null) {
                if (mode != RestoreMode.USER_DATA_ONLY) {
                    dao.upsertNovel(normalizedBackup.copy(id = 0, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
                    restored++
                }
            } else {
                val merged = if (mode == RestoreMode.USER_DATA_ONLY) {
                    existing.copy(
                        isFavorite = normalizedBackup.isFavorite,
                        readingStatus = normalizedBackup.readingStatus,
                        personalRating = normalizedBackup.personalRating,
                        memo = normalizedBackup.memo,
                        readingStartedAt = normalizedBackup.readingStartedAt,
                        completedAt = normalizedBackup.completedAt,
                        droppedAt = normalizedBackup.droppedAt,
                        lastReadChapter = normalizedBackup.lastReadChapter,
                        rereadCount = normalizedBackup.rereadCount,
                        readingNote = normalizedBackup.readingNote,
                        isInfoLocked = normalizedBackup.isInfoLocked,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    normalizedBackup.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = System.currentTimeMillis())
                }
                dao.upsertNovel(merged)
                restored++
            }
        }
        return restored
    }

    suspend fun availableFileUri(novelId: Long): String? = dao.availableFileForNovel(novelId)?.documentUri
    suspend fun platformListings(novelId: Long) = dao.platformListings(novelId)

    suspend fun updateManualMetadata(novelId: Long, title: String, author: String?, synopsis: String?, genre: Genre) {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "제목을 입력하세요." }
        dao.updateManualMetadata(
            novelId,
            cleanTitle,
            TitleNormalizer.matchingKey(cleanTitle).ifBlank { cleanTitle },
            author?.trim()?.takeIf { it.isNotEmpty() },
            synopsis?.trim()?.takeIf { it.isNotEmpty() },
            genre,
        )
        dao.insertHistory(ChangeHistoryEntity(novelId = novelId, actionType = "MANUAL_METADATA", afterValue = cleanTitle))
    }

    suspend fun userTags(novelId: Long): List<String> = dao.userTagNames(novelId)

    suspend fun addUserTag(novelId: Long, name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        dao.addUserTag(novelId, cleanName)
        dao.insertHistory(ChangeHistoryEntity(novelId = novelId, actionType = "USER_TAG_ADD", afterValue = cleanName))
    }

    suspend fun removeUserTag(novelId: Long, name: String) {
        dao.removeUserTag(novelId, name)
        dao.insertHistory(ChangeHistoryEntity(novelId = novelId, actionType = "USER_TAG_REMOVE", beforeValue = name))
    }

    suspend fun favorite(ids: List<Long>, favorite: Boolean) {
        dao.setFavorite(ids, favorite)
        ids.forEach { dao.insertHistory(ChangeHistoryEntity(novelId = it, actionType = "FAVORITE", afterValue = favorite.toString())) }
    }

    suspend fun readingStatus(ids: List<Long>, status: ReadingStatus) {
        dao.setReadingStatus(ids, status)
        ids.forEach { dao.insertHistory(ChangeHistoryEntity(novelId = it, actionType = "READING_STATUS", afterValue = status.name)) }
    }

    suspend fun genre(ids: List<Long>, genre: Genre) {
        dao.setGenreIfUnlocked(ids, genre)
        ids.forEach { dao.insertHistory(ChangeHistoryEntity(novelId = it, actionType = "GENRE", afterValue = genre.name)) }
    }


    suspend fun memo(id: Long, memo: String?) {
        dao.updateMemo(id, memo)
        dao.insertHistory(ChangeHistoryEntity(novelId = id, actionType = "MEMO", afterValue = memo))
    }

    suspend fun personalRating(id: Long, rating: Float?) {
        require(rating == null || rating in 0f..5f) { "rating must be null or 0..5" }
        dao.updatePersonalRating(id, rating)
        dao.insertHistory(ChangeHistoryEntity(novelId = id, actionType = "PERSONAL_RATING", afterValue = rating?.toString()))
    }

    suspend fun infoLock(id: Long, locked: Boolean) {
        dao.setInfoLocked(id, locked)
        dao.insertHistory(ChangeHistoryEntity(novelId = id, actionType = "INFO_LOCK", afterValue = locked.toString()))
    }

    suspend fun readingRecord(id: Long, update: ReadingRecordUpdate) {
        dao.updateReadingRecord(
            id = id,
            startedAt = update.startedAt,
            completedAt = update.completedAt,
            droppedAt = update.droppedAt,
            lastReadChapter = update.lastReadChapter,
            rereadCount = update.rereadCount.coerceAtLeast(0),
            readingNote = update.readingNote,
        )
        dao.insertHistory(ChangeHistoryEntity(novelId = id, actionType = "READING_RECORD", afterValue = update.toString()))
    }



    suspend fun createSmartCollection(name: String, filterJson: String) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "smart collection name must not be blank" }
        val now = System.currentTimeMillis()
        val existing = dao.collectionByName(cleanName)
        dao.upsertCollection(
            existing?.copy(
                collectionType = com.personal.novellibrary.data.CollectionType.SMART,
                filterJson = filterJson,
                updatedAt = now,
            ) ?: CollectionEntity(
                name = cleanName,
                collectionType = com.personal.novellibrary.data.CollectionType.SMART,
                filterJson = filterJson,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun addToCollection(ids: List<Long>, collectionName: String) {
        val cleanName = collectionName.trim()
        require(cleanName.isNotBlank()) { "collectionName must not be blank" }
        val collectionId = dao.collectionByName(cleanName)?.id
            ?: dao.upsertCollection(CollectionEntity(name = cleanName))
        ids.forEachIndexed { index, novelId ->
            dao.upsertCollectionItem(
                CollectionItemEntity(
                    collectionId = collectionId,
                    novelId = novelId,
                    sortOrder = index,
                ),
            )
            dao.insertHistory(ChangeHistoryEntity(novelId = novelId, actionType = "COLLECTION_ADD", afterValue = cleanName))
        }
    }


    suspend fun acceptCandidate(candidate: SearchCandidateEntity) {
        dao.upsertListing(
            PlatformListingEntity(
                novelId = candidate.novelId,
                platformType = candidate.platformType,
                platformWorkId = candidate.candidateWorkId,
                platformTitle = candidate.candidateTitle,
                platformAuthor = candidate.candidateAuthor,
                synopsis = candidate.candidateSynopsis,
                genre = candidate.candidateGenre,
                coverUrl = candidate.candidateCoverUrl,
                detailUrl = candidate.candidateWorkId,
                matchConfidence = candidate.confidence,
                lookupStatus = LookupStatus.SUCCESS,
                lastFetchedAt = System.currentTimeMillis(),
            ),
        )
        dao.applyPlatformMetadataIfMissing(
            candidate.novelId,
            candidate.candidateAuthor,
            candidate.candidateSynopsis,
            candidate.candidateCoverUrl,
        )
        dao.updateCandidateStatus(candidate.id, LookupStatus.SUCCESS)
        dao.clearPendingCandidates(candidate.novelId, candidate.platformType)
        dao.insertHistory(ChangeHistoryEntity(novelId = candidate.novelId, actionType = "CANDIDATE_ACCEPT", afterValue = candidate.candidateTitle))
    }

    suspend fun excludeCandidate(candidate: SearchCandidateEntity) {
        dao.insertExcludedCandidate(
            ExcludedCandidateEntity(
                novelId = candidate.novelId,
                platformType = candidate.platformType,
                candidateWorkId = candidate.candidateWorkId,
                candidateTitle = candidate.candidateTitle,
                reason = "USER_EXCLUDED",
            ),
        )
        dao.updateCandidateStatus(candidate.id, LookupStatus.EXCLUDED)
        dao.insertHistory(ChangeHistoryEntity(novelId = candidate.novelId, actionType = "CANDIDATE_EXCLUDE", afterValue = candidate.candidateTitle))
    }

    suspend fun trash(ids: List<Long>) {
        dao.moveToTrash(ids)
        ids.forEach { dao.insertHistory(ChangeHistoryEntity(novelId = it, actionType = "TRASH", afterValue = "true")) }
    }

    suspend fun restore(ids: List<Long>) {
        dao.restoreFromTrash(ids)
        ids.forEach { dao.insertHistory(ChangeHistoryEntity(novelId = it, actionType = "RESTORE", afterValue = "true")) }
    }
}

internal fun escapeLikeQuery(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
