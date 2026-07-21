package com.personal.novellibrary.platform

import com.personal.novellibrary.data.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchCandidate(
    val platformType: PlatformType,
    val title: String,
    val author: String?,
    val synopsis: String?,
    val genre: String?,
    val coverUrl: String?,
    val workId: String?,
    val detailUrl: String?,
)

data class PlatformWorkInfo(
    val candidate: SearchCandidate,
    val rating: Float? = null,
    val ratingCount: Int? = null,
    val reviewCount: Int? = null,
    val tags: List<String> = emptyList(),
)

data class ReviewSnapshot(val text: String, val rating: Float?, val reviewer: String?)
data class PlatformCapabilities(val search: Boolean = true, val details: Boolean = true, val reviews: Boolean = false, val cover: Boolean = true)

interface PlatformAdapter {
    val platformType: PlatformType
    val capabilities: PlatformCapabilities
    suspend fun search(query: String): List<SearchCandidate>
    suspend fun fetchDetails(candidate: SearchCandidate): PlatformWorkInfo
    suspend fun fetchReviews(candidate: SearchCandidate, limit: Int): List<ReviewSnapshot> = emptyList()
}

/**
 * Searches public, non-login HTML only. A changed/blocked response throws so the
 * worker records FAILED rather than inventing a NO_RESULT or synthetic metadata.
 */
abstract class PublicHtmlAdapter(
    override val platformType: PlatformType,
    private val searchUrl: (String) -> String,
    private val workLink: Regex,
) : PlatformAdapter {
    override val capabilities = PlatformCapabilities(reviews = platformType == PlatformType.RIDI)

    override suspend fun search(query: String): List<SearchCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().take(100)
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val document = PlatformHttp.getDocument(searchUrl(cleanQuery))
        parseSearchDocument(document)
    }

    internal fun parseSearchDocument(document: Document): List<SearchCandidate> =
        document.select("a[href]")
            .asSequence()
            .mapNotNull { it.toCandidate(document.location()) }
            .distinctBy { it.detailUrl }
            .take(MAX_CANDIDATES)
            .toList()

    override suspend fun fetchDetails(candidate: SearchCandidate): PlatformWorkInfo = withContext(Dispatchers.IO) {
        val url = candidate.detailUrl ?: return@withContext PlatformWorkInfo(candidate)
        parseDetailDocument(PlatformHttp.getDocument(url), candidate)
    }

    internal fun parseDetailDocument(document: Document, candidate: SearchCandidate): PlatformWorkInfo {
        val structured = document.structuredWorkData()
        val title = document.firstContent("meta[property=og:title]")
            ?: document.firstText("h1", ".title")
            ?: structured?.title
            ?: candidate.title
        val synopsis = document.firstContent("meta[property=og:description]", "meta[name=description]")
            ?: document.firstText(".synopsis", ".summary", ".book_intro", ".introduce", "[class*=synopsis]", "[class*=description]")
            ?: structured?.synopsis
            ?: candidate.synopsis
        val image = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.ifBlank { null }
            ?: structured?.coverUrl
            ?: candidate.coverUrl
        val author = document.firstText("[class*=author]", "[class*=writer]") ?: structured?.author ?: candidate.author
        return PlatformWorkInfo(
            candidate.copy(
                title = title.clean(),
                author = author?.clean(),
                synopsis = synopsis?.let(::cleanSynopsis),
                coverUrl = image,
            ),
        )
    }

    private fun Element.toCandidate(baseUrl: String): SearchCandidate? {
        val absoluteUrl = absUrl("href").ifBlank { runCatching { java.net.URI(baseUrl).resolve(attr("href")).toString() }.getOrNull().orEmpty() }
        if (!workLink.containsMatchIn(absoluteUrl)) return null
        val card = closest("li, article, [class*=item], [class*=book], [class*=content]") ?: parent()
        val title = attr("title").ifBlank {
            selectFirst("[class*=title]")?.text().orEmpty().ifBlank { ownText().ifBlank { text() } }
        }.clean()
        if (title.length !in 2..200) return null
        val author = card?.selectFirst("[class*=author], [class*=writer]")?.text()?.clean()?.takeIf { it != title }
        val synopsis = card?.selectFirst("[class*=summary], [class*=synopsis], [class*=description]")?.text()?.clean()
        val cover = card?.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.ifBlank { null }
        return SearchCandidate(platformType, title, author, synopsis, null, cover, absoluteUrl, absoluteUrl)
    }

    companion object { private const val MAX_CANDIDATES = 5 }
}

private object PlatformHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun getDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) NovelLibrary/0.1")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.5")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("빈 응답")
            if (body.isBlank()) error("빈 응답")
            return Jsoup.parse(body, response.request.url.toString())
        }
    }
}

private fun encoded(query: String): String = URLEncoder.encode(query, Charsets.UTF_8.name())
private fun String.clean(): String = replace(Regex("\\s+"), " ").trim()
private fun cleanSynopsis(value: String): String = Jsoup.parse(value).text().clean()
private fun Document.firstText(vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selectFirst(it)?.text()?.clean()?.ifBlank { null } }
private fun Document.firstContent(vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selectFirst(it)?.attr("content")?.clean()?.ifBlank { null } }

private data class StructuredWorkData(val title: String?, val author: String?, val synopsis: String?, val coverUrl: String?)

private fun Document.structuredWorkData(): StructuredWorkData? =
    select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
        runCatching {
            val root: Any = script.html().trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
            findBookObject(root)?.let { book ->
                StructuredWorkData(
                    title = book.optString("name").ifBlank { book.optString("headline") }.ifBlank { null },
                    author = jsonName(book.opt("author")),
                    synopsis = book.optString("description").ifBlank { null },
                    coverUrl = jsonImage(book.opt("image")),
                )
            }
        }.getOrNull()
    }

private fun findBookObject(value: Any?): JSONObject? = when (value) {
    is JSONObject -> {
        if (isWorkType(value.opt("@type"))) value
        else value.keys().asSequence().mapNotNull { findBookObject(value.opt(it)) }.firstOrNull()
    }
    is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findBookObject(value.opt(it)) }.firstOrNull()
    else -> null
}

private fun isWorkType(value: Any?): Boolean = when (value) {
    is JSONArray -> (0 until value.length()).any { isWorkType(value.opt(it)) }
    is String -> value.equals("Book", true) || value.equals("CreativeWork", true) || value.equals("Novel", true)
    else -> false
}

private fun jsonName(value: Any?): String? = when (value) {
    is JSONObject -> value.optString("name").ifBlank { null }
    is JSONArray -> (0 until value.length()).asSequence().mapNotNull { jsonName(value.opt(it)) }.firstOrNull()
    is String -> value.ifBlank { null }
    else -> null
}

private fun jsonImage(value: Any?): String? = when (value) {
    is JSONObject -> value.optString("url").ifBlank { null }
    is JSONArray -> (0 until value.length()).asSequence().mapNotNull { jsonImage(value.opt(it)) }.firstOrNull()
    is String -> value.ifBlank { null }
    else -> null
}

class NovelpiaAdapter : PublicHtmlAdapter(PlatformType.NOVELPIA, { "https://novelpia.com/search/all/${encoded(it)}" }, Regex("novelpia\\.com/(novel|viewer|comic)/", RegexOption.IGNORE_CASE))
class MunpiaAdapter : PublicHtmlAdapter(PlatformType.MUNPIA, { "https://www.munpia.com/search?keyword=${encoded(it)}" }, Regex("munpia\\.com/(page/novel|novel/)", RegexOption.IGNORE_CASE))
class NaverSeriesAdapter : PublicHtmlAdapter(PlatformType.NAVER_SERIES, { "https://series.naver.com/search/search.series?t=all&fs=all&q=${encoded(it)}" }, Regex("series\\.naver\\.com/novel/detail\\.series", RegexOption.IGNORE_CASE))
class KakaoPageAdapter : PublicHtmlAdapter(PlatformType.KAKAO_PAGE, { "https://page.kakao.com/search/result?keyword=${encoded(it)}" }, Regex("page\\.kakao\\.com/content/\\d+", RegexOption.IGNORE_CASE))
class RidiAdapter : PublicHtmlAdapter(PlatformType.RIDI, { "https://ridibooks.com/search?q=${encoded(it)}" }, Regex("(ridibooks|ridi)\\.com/books/\\d+", RegexOption.IGNORE_CASE))

class PlatformRegistry(
    val adapters: List<PlatformAdapter> = listOf(NovelpiaAdapter(), MunpiaAdapter(), NaverSeriesAdapter(), KakaoPageAdapter(), RidiAdapter()),
)
