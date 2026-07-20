package com.personal.novellibrary.domain

import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelEntity
import kotlin.math.max

object TitleNormalizer {
    private val noisePatterns = listOf(
        Regex("\\.(txt)$", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*완결\\s*]", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*完\\s*\\)"),
        Regex("외전\\s*포함", RegexOption.IGNORE_CASE),
        Regex("\\d+\\s*[-~]\\s*\\d+\\s*화"),
        Regex("\\d+\\s*권"),
        Regex("(?:완결|완)$"),
        Regex("(?:텍본|스캔본|다운로드|完)$", RegexOption.IGNORE_CASE),
    )

    fun initialTitle(fileName: String): String = fileName.substringBeforeLast('.').trim()

    fun normalize(fileName: String): String {
        var title = fileName.trim()
        noisePatterns.forEach { title = title.replace(it, "") }
        return title
            .replace(Regex("[._]+"), " ")
            .replace(Regex("[\\[\\]{}()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '_', '·')
    }

    fun searchKey(novel: NovelEntity, customSearch: String? = null): String =
        novel.confirmedTitle?.takeIf { it.isNotBlank() }
            ?: customSearch?.takeIf { it.isNotBlank() }
            ?: novel.normalizedTitle.ifBlank { novel.displayTitle }
}

object GenreNormalizer {
    fun normalize(raw: String?): Genre {
        val value = raw?.lowercase()?.replace(" ", "") ?: return Genre.UNCLASSIFIED
        return when {
            value in listOf("로판", "로맨스판타지") -> Genre.ROMANCE_FANTASY
            value in listOf("bl", "비엘", "보이즈러브") -> Genre.BL
            value in listOf("gl", "걸즈러브", "백합") -> Genre.GL
            value in listOf("현판", "현대판타지") -> Genre.MODERN_FANTASY
            "무협" in value -> Genre.WUXIA
            "미스터리" in value -> Genre.MYSTERY
            "스릴러" in value -> Genre.THRILLER
            "공포" in value -> Genre.HORROR
            "라이트노벨" in value -> Genre.LIGHT_NOVEL
            "로맨스" in value -> Genre.ROMANCE
            "판타지" in value -> Genre.FANTASY
            else -> Genre.UNCLASSIFIED
        }
    }
}

data class Candidate(
    val title: String,
    val author: String? = null,
    val genre: String? = null,
    val excluded: Boolean = false,
)

object MatchScorer {
    fun score(query: String, candidate: Candidate, author: String? = null, genre: Genre = Genre.UNCLASSIFIED): Int {
        if (candidate.excluded) return 0
        val q = TitleNormalizer.normalize(query)
        val t = TitleNormalizer.normalize(candidate.title)
        var score = 0
        if (q == t) score += 70
        if (candidate.author != null && author != null && candidate.author == author) score += 20
        if (genre != Genre.UNCLASSIFIED && GenreNormalizer.normalize(candidate.genre) == genre) score += 10
        score += max(0, 10 - kotlin.math.abs(q.length - t.length))
        return score.coerceIn(0, 100)
    }

    fun needsUserConfirmation(score: Int): Boolean = score < 85
}

data class SmartCollectionRule(
    val genre: Genre? = null,
    val favoriteOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val unreadOnly: Boolean = false,
    val requireFileAvailable: Boolean = false,
)

object SmartCollectionMatcher {
    fun matches(novel: NovelEntity, rule: SmartCollectionRule, fileAvailable: Boolean = true): Boolean {
        if (rule.genre != null && novel.mainGenre != rule.genre) return false
        if (rule.favoriteOnly && !novel.isFavorite) return false
        if (rule.completedOnly && novel.isCompleted != true) return false
        if (rule.unreadOnly && novel.readingStatus.name != "UNREAD") return false
        if (rule.requireFileAvailable && !fileAvailable) return false
        return true
    }
}
