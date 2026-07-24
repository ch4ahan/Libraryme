package com.personal.novellibrary.domain

import com.personal.novellibrary.data.NovelEntity
import com.personal.novellibrary.data.ReadingStatus
import kotlin.random.Random

data class RecommendationRule(
    val unreadOnly: Boolean = true,
    val favoriteOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val requireFileAvailable: Boolean = true,
    val excludedNovelIds: Set<Long> = emptySet(),
)

object RecommendationEngine {
    fun candidates(
        novels: List<NovelEntity>,
        fileAvailability: Map<Long, Boolean>,
        rule: RecommendationRule,
    ): List<NovelEntity> = novels.filter { novel ->
        novel.id !in rule.excludedNovelIds &&
            !novel.isDeleted &&
            !novel.isHidden &&
            (!rule.unreadOnly || novel.readingStatus == ReadingStatus.UNREAD || novel.readingStatus == ReadingStatus.PLAN_TO_READ) &&
            (!rule.favoriteOnly || novel.isFavorite) &&
            (!rule.completedOnly || novel.isCompleted == true) &&
            (!rule.requireFileAvailable || fileAvailability[novel.id] == true)
    }

    fun pick(
        novels: List<NovelEntity>,
        fileAvailability: Map<Long, Boolean>,
        rule: RecommendationRule,
        random: Random = Random.Default,
    ): NovelEntity? = candidates(novels, fileAvailability, rule).randomOrNull(random)
}
