package com.personal.novellibrary.domain

import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelEntity
import kotlin.math.max
import java.text.Normalizer

object TitleNormalizer {
    private val noisePatterns = listOf(
        Regex("\\.(txt|text)$", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*완결\\s*]", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*完\\s*\\)"),
        Regex("외전\\s*포함", RegexOption.IGNORE_CASE),
        Regex("\\s*(?:전\\s*)?\\d+\\s*화?\\s*[-~～_]\\s*\\d+\\s*화?(?:\\s*(?:완결|완))?\\s*$"),
        Regex("\\s*[-~～]\\s*\\d+\\s*화?(?:\\s*(?:완결|완))?\\s*$"),
        Regex("\\s*\\d+\\s*화\\s*(?:완결|완)?\\s*$"),
        Regex("\\s*\\d+\\s*권\\s*$"),
        Regex("\\s*(?:완결|완|텍본|스캔본|다운로드|完)\\s*$", RegexOption.IGNORE_CASE),
    )

    fun initialTitle(fileName: String): String = fileName.substringBeforeLast('.').trim()

    fun normalize(fileName: String): String {
        var title = Normalizer.normalize(fileName.trim(), Normalizer.Form.NFKC)
        noisePatterns.forEach { title = title.replace(it, "") }
        title = title.replace(Regex("^\\s*\\[[^]]+]\\s*"), "")
        return title
            .replace(Regex("[._·]+"), " ")
            .replace(Regex("[\\[\\]{}()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '_', '·')
    }

    /** Stable comparison key: spacing and punctuation in downloaded filenames are unreliable. */
    fun matchingKey(rawTitle: String): String = normalize(rawTitle)
        .lowercase()
        .filter { it.isLetterOrDigit() }

    /** Korean platform search works best with the same spacing-insensitive key used for matching. */
    fun platformQuery(rawTitle: String): String {
        val cleaned = normalize(rawTitle)
        val hasHangul = cleaned.any { it in '\uAC00'..'\uD7A3' }
        val hasLatin = cleaned.any { it in 'a'..'z' || it in 'A'..'Z' }
        return if (hasHangul && !hasLatin) matchingKey(cleaned) else cleaned
    }

    fun searchKey(novel: NovelEntity, customSearch: String? = null): String =
        customSearch?.takeIf { it.isNotBlank() }
            ?: novel.confirmedTitle?.takeIf { it.isNotBlank() }
            ?: platformQuery(novel.normalizedTitle.ifBlank { novel.displayTitle })
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
        val q = TitleNormalizer.matchingKey(query)
        val t = TitleNormalizer.matchingKey(candidate.title)
        var score = 0
        if (q == t && q.isNotBlank()) score += 85
        else if (q.length >= 4 && t.length >= 4 && (q in t || t in q)) score += 60
        if (candidate.author != null && author != null && TitleNormalizer.matchingKey(candidate.author) == TitleNormalizer.matchingKey(author)) score += 15
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
