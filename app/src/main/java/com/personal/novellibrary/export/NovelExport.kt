package com.personal.novellibrary.export

import com.personal.novellibrary.data.NovelEntity
import org.json.JSONArray
import org.json.JSONObject

object NovelJsonExport {
    fun toJson(novels: List<NovelEntity>): JSONArray = JSONArray().also { array ->
        novels.forEach { novel ->
            array.put(
                JSONObject()
                    .put("id", novel.id)
                    .put("displayTitle", novel.displayTitle)
                    .put("normalizedTitle", novel.normalizedTitle)
                    .put("confirmedTitle", novel.confirmedTitle)
                    .put("author", novel.author)
                    .put("mainGenre", novel.mainGenre.name)
                    .put("synopsis", novel.synopsis)
                    .put("isFavorite", novel.isFavorite)
                    .put("readingStatus", novel.readingStatus.name)
                    .put("personalRating", novel.personalRating)
                    .put("memo", novel.memo)
                    .put("readingStartedAt", novel.readingStartedAt)
                    .put("completedAt", novel.completedAt)
                    .put("droppedAt", novel.droppedAt)
                    .put("lastReadChapter", novel.lastReadChapter)
                    .put("rereadCount", novel.rereadCount)
                    .put("readingNote", novel.readingNote)
                    .put("manualSourceNote", novel.manualSourceNote)
                    .put("isInfoLocked", novel.isInfoLocked),
            )
        }
    }

    fun fromJson(json: String): List<NovelEntity> {
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val title = item.getString("displayTitle").trim()
                require(title.isNotEmpty()) { "작품 제목이 비어 있습니다." }
                add(NovelEntity(
                    id = item.optLong("id", 0), displayTitle = title,
                    normalizedTitle = item.optString("normalizedTitle", title),
                    confirmedTitle = item.nullableString("confirmedTitle"), author = item.nullableString("author"),
                    mainGenre = enumOrDefault(item.optString("mainGenre"), com.personal.novellibrary.data.Genre.UNCLASSIFIED),
                    synopsis = item.nullableString("synopsis"), isFavorite = item.optBoolean("isFavorite"),
                    readingStatus = enumOrDefault(item.optString("readingStatus"), com.personal.novellibrary.data.ReadingStatus.UNREAD),
                    personalRating = if (item.isNull("personalRating")) null else item.optDouble("personalRating").toFloat(),
                    memo = item.nullableString("memo"), readingStartedAt = item.nullableLong("readingStartedAt"),
                    completedAt = item.nullableLong("completedAt"), droppedAt = item.nullableLong("droppedAt"),
                    lastReadChapter = item.nullableString("lastReadChapter"), rereadCount = item.optInt("rereadCount").coerceAtLeast(0),
                    readingNote = item.nullableString("readingNote"), manualSourceNote = item.nullableString("manualSourceNote"),
                    isInfoLocked = item.optBoolean("isInfoLocked"),
                ))
            }
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}

object NovelCsvExport {
    fun toCsv(novels: List<NovelEntity>): String = buildString {
        appendLine("id,title,author,genre,favorite,readingStatus,rating,lastReadChapter,rereadCount,locked")
        novels.forEach { novel ->
            appendLine(
                listOf(
                    novel.id.toString(),
                    novel.displayTitle.csv(),
                    novel.author.orEmpty().csv(),
                    novel.mainGenre.name,
                    novel.isFavorite.toString(),
                    novel.readingStatus.name,
                    novel.personalRating?.toString().orEmpty(),
                    novel.lastReadChapter.orEmpty().csv(),
                    novel.rereadCount.toString(),
                    novel.isInfoLocked.toString(),
                ).joinToString(","),
            )
        }
    }

    private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""
}
