package com.personal.novellibrary

import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelEntity
import com.personal.novellibrary.domain.Candidate
import com.personal.novellibrary.domain.GenreNormalizer
import com.personal.novellibrary.domain.LibraryFilter
import com.personal.novellibrary.domain.LibraryFilterEngine
import com.personal.novellibrary.domain.MatchScorer
import com.personal.novellibrary.domain.SmartCollectionMatcher
import com.personal.novellibrary.domain.SmartCollectionRule
import com.personal.novellibrary.domain.RecommendationEngine
import com.personal.novellibrary.domain.RecommendationRule
import com.personal.novellibrary.domain.TitleNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    @Test
    fun titleNormalizationRemovesNoise() {
        assertEquals("악녀는 두 번 산다", TitleNormalizer.normalize("[완결] 악녀는 두 번 산다 1-200화 외전포함.txt"))
    }

    @Test
    fun genreNormalizationSupportsRofanAndBl() {
        assertEquals(Genre.ROMANCE_FANTASY, GenreNormalizer.normalize("로판"))
        assertEquals(Genre.BL, GenreNormalizer.normalize("보이즈러브"))
        assertEquals(Genre.MODERN_FANTASY, GenreNormalizer.normalize("현판"))
    }

    @Test
    fun matchScoringAvoidsExcludedCandidates() {
        assertEquals(0, MatchScorer.score("악녀는 두 번 산다", Candidate("악녀는 두 번 산다", excluded = true)))
        assertTrue(MatchScorer.score("악녀는 두 번 산다", Candidate("악녀는 두 번 산다")) >= 70)
    }

    @Test
    fun smartCollectionChecksGenreFavoriteAndFileAvailability() {
        val novel = NovelEntity(
            displayTitle = "테스트",
            normalizedTitle = "테스트",
            mainGenre = Genre.BL,
            isFavorite = true,
        )
        val rule = SmartCollectionRule(genre = Genre.BL, favoriteOnly = true, requireFileAvailable = true)
        assertTrue(SmartCollectionMatcher.matches(novel, rule, fileAvailable = true))
        assertFalse(SmartCollectionMatcher.matches(novel, rule, fileAvailable = false))
    }

    @Test
    fun recommendationFiltersHiddenDeletedAndUnavailableItems() {
        val available = NovelEntity(id = 1, displayTitle = "가능", normalizedTitle = "가능")
        val unavailable = NovelEntity(id = 2, displayTitle = "없음", normalizedTitle = "없음")
        val hidden = NovelEntity(id = 3, displayTitle = "숨김", normalizedTitle = "숨김", isHidden = true)
        val candidates = RecommendationEngine.candidates(
            listOf(available, unavailable, hidden),
            fileAvailability = mapOf(1L to true, 2L to false, 3L to true),
            rule = RecommendationRule(requireFileAvailable = true),
        )
        assertEquals(listOf(available), candidates)
    }


    @Test
    fun libraryFilterSupportsGenreAndFavorite() {
        val rofan = NovelEntity(id = 10, displayTitle = "로판", normalizedTitle = "로판", mainGenre = Genre.ROMANCE_FANTASY, isFavorite = true)
        val bl = NovelEntity(id = 11, displayTitle = "비엘", normalizedTitle = "비엘", mainGenre = Genre.BL, isFavorite = false)
        assertEquals(
            listOf(rofan),
            LibraryFilterEngine.apply(listOf(rofan, bl), LibraryFilter(genre = Genre.ROMANCE_FANTASY, favoriteOnly = true)),
        )
    }

}
