package com.personal.novellibrary.domain

import com.personal.novellibrary.data.Genre
import com.personal.novellibrary.data.NovelEntity

data class LibraryFilter(
    val genre: Genre? = null,
    val favoriteOnly: Boolean = false,
    val includeHidden: Boolean = false,
)

object LibraryFilterEngine {
    fun apply(novels: List<NovelEntity>, filter: LibraryFilter): List<NovelEntity> = novels.filter { novel ->
        (filter.includeHidden || !novel.isHidden) &&
            (filter.genre == null || novel.mainGenre == filter.genre) &&
            (!filter.favoriteOnly || novel.isFavorite)
    }
}
