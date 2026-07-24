package com.personal.novellibrary.data

import androidx.room.*
import androidx.room.migration.Migration
import com.personal.novellibrary.domain.TitleNormalizer
import kotlinx.coroutines.flow.Flow

data class FileScanRecord(
    val documentUri: String,
    val originalFileName: String,
    val normalizedTitle: String,
    val displayTitle: String = normalizedTitle,
    val fileSize: Long,
    val lastModified: Long,
)

data class FileScanDbResult(val inserted: Int, val changed: Int, val unchanged: Int)

@Dao
interface NovelDao {
    @Query(
        """
        SELECT * FROM NovelEntity
        WHERE isDeleted = 0
          AND (:query = ''
               OR normalizedTitle LIKE '%' || :query || '%' ESCAPE '\'
               OR displayTitle LIKE '%' || :query || '%' ESCAPE '\'
               OR author LIKE '%' || :query || '%' ESCAPE '\'
               OR synopsis LIKE '%' || :query || '%' ESCAPE '\'
               OR memo LIKE '%' || :query || '%' ESCAPE '\'
               OR EXISTS (SELECT 1 FROM AliasEntity a WHERE a.novelId = NovelEntity.id AND a.alias LIKE '%' || :query || '%' ESCAPE '\')
               OR EXISTS (SELECT 1 FROM LocalFileEntity f WHERE f.novelId = NovelEntity.id AND f.originalFileName LIKE '%' || :query || '%' ESCAPE '\')
               OR EXISTS (
                   SELECT 1 FROM NovelUserTagCrossRef x
                   JOIN UserTagEntity t ON t.id = x.tagId
                   WHERE x.novelId = NovelEntity.id AND t.name LIKE '%' || :query || '%' ESCAPE '\'
               )
               OR EXISTS (
                   SELECT 1 FROM CollectionItemEntity ci
                   JOIN CollectionEntity c ON c.id = ci.collectionId
                   WHERE ci.novelId = NovelEntity.id AND c.name LIKE '%' || :query || '%' ESCAPE '\'
               ))
        ORDER BY updatedAt DESC
        """
    )
    fun observeNovels(query: String = ""): Flow<List<NovelEntity>>

    @Query("SELECT * FROM NovelEntity WHERE id = :id")
    fun observeNovel(id: Long): Flow<NovelEntity?>

    @Query("SELECT * FROM NovelEntity WHERE normalizedTitle = :title LIMIT 1")
    suspend fun novelByNormalizedTitle(title: String): NovelEntity?

    @Query("SELECT * FROM LocalFileEntity WHERE novelId = :novelId AND isAvailable = 1 ORDER BY id LIMIT 1")
    suspend fun availableFileForNovel(novelId: Long): LocalFileEntity?

    @Query("SELECT * FROM LocalFileEntity WHERE documentUri = :uri LIMIT 1")
    suspend fun fileByUri(uri: String): LocalFileEntity?

    @Query("SELECT * FROM LocalFileEntity")
    suspend fun files(): List<LocalFileEntity>

    @Upsert
    suspend fun upsertNovel(novel: NovelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: LocalFileEntity): Long

    @Query(
        """
        UPDATE LocalFileEntity
        SET fileSize = :fileSize,
            lastModified = :lastModified,
            isAvailable = 1,
            lastScannedAt = :now
        WHERE id = :id
        """
    )
    suspend fun updateFileSnapshot(id: Long, fileSize: Long, lastModified: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET displayTitle = :displayTitle, normalizedTitle = :normalizedTitle, updatedAt = :now WHERE id = :novelId AND confirmedTitle IS NULL AND isInfoLocked = 0")
    suspend fun updateScannedTitle(novelId: Long, displayTitle: String, normalizedTitle: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE LocalFileEntity SET isAvailable = 0, lastScannedAt = :now WHERE documentUri NOT IN (:uris)")
    suspend fun markMissingExcept(uris: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE LocalFileEntity SET isAvailable = 0, lastScannedAt = :now")
    suspend fun markAllMissing(now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun applyFileScan(records: List<FileScanRecord>, now: Long = System.currentTimeMillis()): FileScanDbResult {
        val existingByUri = files().associateBy { it.documentUri }
        markAllMissing(now)
        var inserted = 0
        var changed = 0
        var unchanged = 0
        records.forEach { record ->
            val existing = existingByUri[record.documentUri]
            if (existing == null) {
                val novelId = novelByNormalizedTitle(record.normalizedTitle)?.id ?: upsertNovel(
                    NovelEntity(displayTitle = record.displayTitle.ifBlank { record.originalFileName }, normalizedTitle = record.normalizedTitle),
                )
                upsertFile(
                    LocalFileEntity(
                        novelId = novelId,
                        documentUri = record.documentUri,
                        originalFileName = record.originalFileName,
                        fileSize = record.fileSize,
                        lastModified = record.lastModified,
                        lastScannedAt = now,
                    ),
                )
                inserted++
            } else {
                updateScannedTitle(existing.novelId, record.displayTitle, record.normalizedTitle, now)
                val wasChanged = existing.fileSize != record.fileSize ||
                    existing.lastModified != record.lastModified || !existing.isAvailable
                updateFileSnapshot(existing.id, record.fileSize, record.lastModified, now)
                if (wasChanged) changed++ else unchanged++
            }
        }
        return FileScanDbResult(inserted, changed, unchanged)
    }

    @Query("UPDATE NovelEntity SET isFavorite = :favorite, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET readingStatus = :status, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setReadingStatus(ids: List<Long>, status: ReadingStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET mainGenre = :genre, updatedAt = :now WHERE id IN (:ids) AND isInfoLocked = 0")
    suspend fun setGenreIfUnlocked(ids: List<Long>, genre: Genre, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET author = COALESCE(author, :author), synopsis = COALESCE(synopsis, :synopsis), coverUrl = COALESCE(coverUrl, :coverUrl), lastFetchedAt = :now, updatedAt = :now WHERE id = :id AND isInfoLocked = 0")
    suspend fun applyPlatformMetadataIfMissing(id: Long, author: String?, synopsis: String?, coverUrl: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET displayTitle = :title, confirmedTitle = :title, normalizedTitle = :normalizedTitle, author = :author, synopsis = :synopsis, mainGenre = :genre, isInfoLocked = 1, updatedAt = :now WHERE id = :id")
    suspend fun updateManualMetadata(id: Long, title: String, normalizedTitle: String, author: String?, synopsis: String?, genre: Genre, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET isDeleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET isDeleted = 0, updatedAt = :now WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Insert
    suspend fun insertTrash(item: TrashEntity)

    @Query("DELETE FROM TrashEntity WHERE novelId IN (:novelIds)")
    suspend fun removeTrashRows(novelIds: List<Long>)

    @Insert
    suspend fun insertHistory(item: ChangeHistoryEntity)

    @Query("SELECT id FROM PlatformListingEntity WHERE novelId = :novelId AND platformType = :platformType LIMIT 1")
    suspend fun listingId(novelId: Long, platformType: PlatformType): Long?

    @Query("SELECT * FROM PlatformListingEntity WHERE novelId = :novelId AND platformType = :platformType LIMIT 1")
    suspend fun platformListing(novelId: Long, platformType: PlatformType): PlatformListingEntity?

    @Query("SELECT * FROM PlatformListingEntity WHERE novelId = :novelId ORDER BY platformType")
    suspend fun platformListings(novelId: Long): List<PlatformListingEntity>

    @Upsert
    suspend fun writeListing(item: PlatformListingEntity): Long

    @Transaction
    suspend fun upsertListing(item: PlatformListingEntity): Long =
        writeListing(item.copy(id = listingId(item.novelId, item.platformType) ?: item.id))

    @Query("UPDATE PlatformListingEntity SET lookupStatus = :status, lastFetchedAt = :fetchedAt, errorMessage = :errorMessage WHERE novelId = :novelId AND platformType = :platformType")
    suspend fun updateListingState(novelId: Long, platformType: PlatformType, status: LookupStatus, fetchedAt: Long, errorMessage: String?): Int

    @Transaction
    suspend fun setListingState(novelId: Long, platformType: PlatformType, status: LookupStatus, fetchedAt: Long, errorMessage: String? = null) {
        if (updateListingState(novelId, platformType, status, fetchedAt, errorMessage) == 0) {
            writeListing(
                PlatformListingEntity(
                    novelId = novelId,
                    platformType = platformType,
                    lookupStatus = status,
                    lastFetchedAt = fetchedAt,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(item: AliasEntity): Long

    @Query("SELECT id FROM UserTagEntity WHERE name = :name LIMIT 1")
    suspend fun userTagId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserTag(item: UserTagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNovelUserTag(item: NovelUserTagCrossRef)

    @Query("SELECT t.name FROM UserTagEntity t JOIN NovelUserTagCrossRef x ON x.tagId = t.id WHERE x.novelId = :novelId ORDER BY t.name")
    suspend fun userTagNames(novelId: Long): List<String>

    @Query("DELETE FROM NovelUserTagCrossRef WHERE novelId = :novelId AND tagId = (SELECT id FROM UserTagEntity WHERE name = :name LIMIT 1)")
    suspend fun removeUserTag(novelId: Long, name: String)

    @Transaction
    suspend fun addUserTag(novelId: Long, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val insertedId = insertUserTag(UserTagEntity(name = name))
        val tagId = if (insertedId > 0) insertedId else userTagId(name) ?: return
        insertNovelUserTag(NovelUserTagCrossRef(novelId, tagId))
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExcludedCandidate(item: ExcludedCandidateEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM ExcludedCandidateEntity WHERE novelId = :novelId AND platformType = :platformType AND ((candidateWorkId IS NOT NULL AND candidateWorkId = :workId) OR candidateTitle = :title))")
    suspend fun isCandidateExcluded(novelId: Long, platformType: PlatformType, workId: String?, title: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchCandidate(item: SearchCandidateEntity): Long

    @Query("SELECT * FROM SearchCandidateEntity ORDER BY confidence DESC, id DESC")
    fun observeSearchCandidates(): Flow<List<SearchCandidateEntity>>

    @Query("SELECT * FROM SyncJobEntity ORDER BY createdAt DESC LIMIT 200")
    fun observeSyncJobs(): Flow<List<SyncJobEntity>>

    @Insert
    suspend fun insertSyncJob(item: SyncJobEntity): Long

    @Query("UPDATE SyncJobEntity SET status = :status, startedAt = COALESCE(startedAt, :now), finishedAt = CASE WHEN :finished THEN :now ELSE finishedAt END, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateSyncJob(id: Long, status: JobStatus, finished: Boolean, errorMessage: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE SyncJobEntity SET status = 'CANCELLED', finishedAt = :now, errorMessage = :reason WHERE novelId IN (:novelIds) AND status IN ('QUEUED', 'RUNNING')")
    suspend fun cancelSyncJobs(novelIds: List<Long>, reason: String = "사용자 취소", now: Long = System.currentTimeMillis())

    @Query("UPDATE SearchCandidateEntity SET status = :status WHERE id = :id")
    suspend fun updateCandidateStatus(id: Long, status: LookupStatus)

    @Query("DELETE FROM SearchCandidateEntity WHERE novelId = :novelId AND platformType = :platformType AND status = 'NEEDS_USER_CONFIRMATION'")
    suspend fun clearPendingCandidates(novelId: Long, platformType: PlatformType)

    @Query("SELECT COUNT(*) FROM SearchCandidateEntity WHERE novelId = :novelId AND platformType = :platformType AND status = 'NEEDS_USER_CONFIRMATION'")
    suspend fun pendingCandidateCount(novelId: Long, platformType: PlatformType): Int

    @Query("SELECT * FROM CollectionEntity WHERE name = :name LIMIT 1")
    suspend fun collectionByName(name: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionItem(item: CollectionItemEntity)

    @Query("SELECT * FROM CollectionEntity ORDER BY updatedAt DESC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("UPDATE NovelEntity SET memo = :memo, updatedAt = :now WHERE id = :id")
    suspend fun updateMemo(id: Long, memo: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET personalRating = :rating, updatedAt = :now WHERE id = :id")
    suspend fun updatePersonalRating(id: Long, rating: Float?, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET isInfoLocked = :locked, updatedAt = :now WHERE id = :id")
    suspend fun setInfoLocked(id: Long, locked: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE NovelEntity SET readingStartedAt = :startedAt, completedAt = :completedAt, droppedAt = :droppedAt, lastReadChapter = :lastReadChapter, rereadCount = :rereadCount, readingNote = :readingNote, updatedAt = :now WHERE id = :id")
    suspend fun updateReadingRecord(id: Long, startedAt: Long?, completedAt: Long?, droppedAt: Long?, lastReadChapter: String?, rereadCount: Int, readingNote: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM NovelEntity WHERE isDeleted = 1 ORDER BY updatedAt DESC")
    fun observeTrash(): Flow<List<NovelEntity>>

    @Query("SELECT COUNT(*) FROM NovelEntity WHERE isDeleted = 0")
    fun observeCount(): Flow<Int>

    @Transaction
    suspend fun moveToTrash(ids: List<Long>) {
        softDelete(ids)
        ids.forEach { insertTrash(TrashEntity(novelId = it)) }
    }

    @Transaction
    suspend fun restoreFromTrash(ids: List<Long>) {
        restore(ids)
        removeTrashRows(ids)
    }
}

@Database(
    entities = [
        NovelEntity::class,
        LocalFileEntity::class,
        PlatformListingEntity::class,
        ReviewSnapshotEntity::class,
        AliasEntity::class,
        UserTagEntity::class,
        NovelUserTagCrossRef::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        SearchCandidateEntity::class,
        ExcludedCandidateEntity::class,
        SyncJobEntity::class,
        ChangeHistoryEntity::class,
        TrashEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao

    companion object {
        const val NAME = "novel_library.db"
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_NovelEntity_isInfoLocked ON NovelEntity(isInfoLocked)")
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN readingStartedAt INTEGER")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN completedAt INTEGER")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN droppedAt INTEGER")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN lastReadChapter TEXT")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN rereadCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN readingNote TEXT")
        db.execSQL("ALTER TABLE NovelEntity ADD COLUMN manualSourceNote TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM PlatformListingEntity WHERE id NOT IN " +
                "(SELECT MAX(id) FROM PlatformListingEntity GROUP BY novelId, platformType)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_PlatformListingEntity_novelId_platformType " +
                "ON PlatformListingEntity(novelId, platformType)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val normalizedTitles = buildList {
            db.query("SELECT id, normalizedTitle, displayTitle, confirmedTitle FROM NovelEntity").use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val normalizedIndex = cursor.getColumnIndexOrThrow("normalizedTitle")
                val displayIndex = cursor.getColumnIndexOrThrow("displayTitle")
                val confirmedIndex = cursor.getColumnIndexOrThrow("confirmedTitle")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val original = cursor.getString(normalizedIndex).orEmpty()
                    val display = cursor.getString(displayIndex).orEmpty()
                    val confirmed = if (cursor.isNull(confirmedIndex)) null else cursor.getString(confirmedIndex)
                    val matchingKey = TitleNormalizer.matchingKey(
                        confirmed?.takeIf { it.isNotBlank() }
                            ?: display.takeIf { it.isNotBlank() }
                            ?: original,
                    ).ifBlank { original }
                    add(id to matchingKey)
                }
            }
        }
        db.compileStatement("UPDATE NovelEntity SET normalizedTitle = ? WHERE id = ?").use { statement ->
            normalizedTitles.forEach { (id, matchingKey) ->
                statement.clearBindings()
                statement.bindString(1, matchingKey)
                statement.bindLong(2, id)
                statement.executeUpdateDelete()
            }
        }

        db.execSQL(
            """
            CREATE TEMP TABLE collection_survivors AS
            SELECT name, MAX(id) AS keepId
            FROM CollectionEntity
            GROUP BY name
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO CollectionItemEntity(collectionId, novelId, addedAt, sortOrder)
            SELECT survivors.keepId, items.novelId, items.addedAt, items.sortOrder
            FROM CollectionItemEntity AS items
            JOIN CollectionEntity AS collections ON collections.id = items.collectionId
            JOIN collection_survivors AS survivors ON survivors.name = collections.name
            WHERE items.collectionId != survivors.keepId
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM CollectionItemEntity
            WHERE collectionId IN (
                SELECT collections.id
                FROM CollectionEntity AS collections
                JOIN collection_survivors AS survivors ON survivors.name = collections.name
                WHERE collections.id != survivors.keepId
            )
            """.trimIndent(),
        )
        db.execSQL("DELETE FROM CollectionEntity WHERE id NOT IN (SELECT keepId FROM collection_survivors)")
        db.execSQL("DROP TABLE collection_survivors")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_CollectionEntity_name ON CollectionEntity(name)")
    }
}
