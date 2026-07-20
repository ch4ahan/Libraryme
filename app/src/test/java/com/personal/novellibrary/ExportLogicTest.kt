package com.personal.novellibrary

import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelEntity
import com.personal.novellibrary.data.ReadingStatus
import com.personal.novellibrary.export.NovelCsvExport
import com.personal.novellibrary.export.NovelJsonExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLogicTest {
    @Test
    fun jsonBackupRoundTripPreservesUserData() {
        val original = NovelEntity(
            id = 7, displayTitle = "백업 작품", normalizedTitle = "백업 작품",
            mainGenre = Genre.ROMANCE_FANTASY, isFavorite = true,
            readingStatus = ReadingStatus.READING, personalRating = 4.5f,
            memo = "메모", lastReadChapter = "42화", rereadCount = 2, isInfoLocked = true,
        )

        val restored = NovelJsonExport.fromJson(NovelJsonExport.toJson(listOf(original)).toString()).single()

        assertEquals(original.displayTitle, restored.displayTitle)
        assertEquals(original.mainGenre, restored.mainGenre)
        assertEquals(original.readingStatus, restored.readingStatus)
        assertEquals(original.personalRating, restored.personalRating)
        assertEquals(original.lastReadChapter, restored.lastReadChapter)
        assertTrue(restored.isInfoLocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonBackupRejectsBlankTitle() {
        NovelJsonExport.fromJson("""[{"displayTitle":"   "}]""")
    }
    @Test
    fun csvEscapesHumanEditableFields() {
        val csv = NovelCsvExport.toCsv(
            listOf(
                NovelEntity(
                    displayTitle = "따옴표 \" 테스트",
                    normalizedTitle = "따옴표 테스트",
                    author = "작가,쉼표",
                    mainGenre = Genre.ROMANCE_FANTASY,
                ),
            ),
        )
        assertTrue(csv.contains("\"따옴표 \"\" 테스트\""))
        assertTrue(csv.contains("\"작가,쉼표\""))
    }

    @Test
    fun jsonExportKeepsManualUserFields() {
        val json = NovelJsonExport.toJson(
            listOf(
                NovelEntity(
                    displayTitle = "수동 작품",
                    normalizedTitle = "수동 작품",
                    confirmedTitle = "확정 제목",
                    memo = "내 메모",
                    isInfoLocked = true,
                ),
            ),
        )
        val item = json.getJSONObject(0)
        assertEquals("확정 제목", item.getString("confirmedTitle"))
        assertEquals("내 메모", item.getString("memo"))
        assertTrue(item.getBoolean("isInfoLocked"))
    }
}
