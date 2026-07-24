package com.personal.novellibrary

import com.personal.novellibrary.data.PlatformType
import com.personal.novellibrary.platform.NaverSeriesAdapter
import com.personal.novellibrary.platform.SearchCandidate
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAdapterTest {
    @Test
    fun publicHtmlParserExtractsOnlyRealPlatformWorkLinks() {
        val html = """
            <html><body>
              <li class="book_item">
                <a href="/novel/detail.series?productNo=123"><span class="title">악녀는 두 번 산다</span></a>
                <span class="author">한민트</span><p class="summary">회귀한 주인공의 이야기</p>
              </li>
              <a href="/other/page">메뉴 링크</a>
            </body></html>
        """.trimIndent()

        val candidates = NaverSeriesAdapter().parseSearchDocument(
            Jsoup.parse(html, "https://series.naver.com/search/search.series"),
        )

        assertEquals(1, candidates.size)
        assertEquals(PlatformType.NAVER_SERIES, candidates.single().platformType)
        assertEquals("악녀는 두 번 산다", candidates.single().title)
        assertEquals("한민트", candidates.single().author)
        assertEquals("회귀한 주인공의 이야기", candidates.single().synopsis)
        assertTrue(candidates.single().detailUrl!!.contains("productNo=123"))
    }

    @Test
    fun detailParserExtractsSynopsisFromStructuredBookData() {
        val html = """
            <html><head><script type="application/ld+json">
              {"@context":"https://schema.org","@type":"Book","name":"재벌집 막내아들",
               "author":{"@type":"Person","name":"산경"},
               "description":"재벌가의 비서가 막내아들로 다시 태어난다.",
               "image":{"url":"https://example.test/cover.jpg"}}
            </script></head><body></body></html>
        """.trimIndent()
        val original = SearchCandidate(
            PlatformType.NAVER_SERIES, "임시 제목", null, null, null, null,
            "https://series.naver.com/novel/detail.series?productNo=1",
            "https://series.naver.com/novel/detail.series?productNo=1",
        )

        val result = NaverSeriesAdapter().parseDetailDocument(Jsoup.parse(html), original).candidate

        assertEquals("재벌집 막내아들", result.title)
        assertEquals("산경", result.author)
        assertEquals("재벌가의 비서가 막내아들로 다시 태어난다.", result.synopsis)
        assertEquals("https://example.test/cover.jpg", result.coverUrl)
    }

    @Test
    fun detailParserSupportsGraphTypeArraysAndCleansSynopsisHtml() {
        val html = """
            <script type="application/ld+json">
              {"@graph":[{"@type":["Thing","Book"],"name":"그래프 작품",
              "description":"<p>첫 문장</p><p>두 번째 문장</p>"}]}
            </script>
        """.trimIndent()
        val original = SearchCandidate(PlatformType.NAVER_SERIES, "임시", null, null, null, null, null, null)

        val result = NaverSeriesAdapter().parseDetailDocument(Jsoup.parse(html), original).candidate

        assertEquals("그래프 작품", result.title)
        assertEquals("첫 문장 두 번째 문장", result.synopsis)
    }

    @Test
    fun searchParserRejectsInsecureAndRanksCompleteCandidatesFirst() {
        val html = """
            <li><a href="http://series.naver.com/novel/detail.series?productNo=1">위험한 링크</a></li>
            <li><a href="/novel/detail.series?productNo=2"><span class="title">정보 없는 작품</span></a></li>
            <li><a href="/novel/detail.series?productNo=3"><span class="title">정보 있는 작품</span></a>
              <span class="author">작가</span><p class="summary">줄거리</p></li>
        """.trimIndent()

        val candidates = NaverSeriesAdapter().parseSearchDocument(
            Jsoup.parse(html, "https://series.naver.com/search/search.series"),
        )

        assertEquals(listOf("정보 있는 작품", "정보 없는 작품"), candidates.map { it.title })
    }

    @Test
    fun searchParserRejectsLookalikeLinksOnUntrustedHosts() {
        val html = """
            <a href="https://evil.example/redirect?next=https://series.naver.com/novel/detail.series?productNo=1">
              가짜 외부 링크
            </a>
            <a href="https://series.naver.com/novel/detail.series?productNo=2">정상 링크</a>
        """.trimIndent()

        val candidates = NaverSeriesAdapter().parseSearchDocument(
            Jsoup.parse(html, "https://series.naver.com/search/search.series"),
        )

        assertEquals(listOf("정상 링크"), candidates.map { it.title })
    }

    @Test
    fun detailParserPrefersNestedBookOverGenericCreativeWork() {
        val html = """
            <script type="application/ld+json">
              {"@type":"CreativeWork","name":"사이트 공통 정보","@graph":[
                {"@type":"Book","name":"실제 작품","author":{"name":"실제 작가"},
                 "description":"실제 줄거리"}
              ]}
            </script>
        """.trimIndent()
        val original = SearchCandidate(
            PlatformType.NAVER_SERIES,
            "임시",
            null,
            null,
            null,
            null,
            null,
            null,
        )

        val result = NaverSeriesAdapter().parseDetailDocument(Jsoup.parse(html), original).candidate

        assertEquals("실제 작품", result.title)
        assertEquals("실제 작가", result.author)
        assertEquals("실제 줄거리", result.synopsis)
    }
}
