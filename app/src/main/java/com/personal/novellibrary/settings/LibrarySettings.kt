package com.personal.novellibrary.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.novellibrary.data.PlatformType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("library_settings")

data class LibrarySettings(
    val libraryTreeUri: String? = null,
    val includeSubfolders: Boolean = true,
    val wifiOnlyBulkSearch: Boolean = true,
    val collectReviews: Boolean = false,
    val maxReviewsPerWork: Int = 5,
    val enabledPlatforms: Set<PlatformType> = PlatformType.entries.toSet(),
)

class LibrarySettingsStore(private val context: Context) {
    val settings: Flow<LibrarySettings> = context.dataStore.data.map { preferences ->
        LibrarySettings(
            libraryTreeUri = preferences[Keys.LIBRARY_TREE_URI],
            includeSubfolders = preferences[Keys.INCLUDE_SUBFOLDERS] ?: true,
            wifiOnlyBulkSearch = preferences[Keys.WIFI_ONLY_BULK_SEARCH] ?: true,
            collectReviews = preferences[Keys.COLLECT_REVIEWS] ?: false,
            maxReviewsPerWork = preferences[Keys.MAX_REVIEWS_PER_WORK] ?: 5,
            enabledPlatforms = preferences[Keys.ENABLED_PLATFORMS]
                ?.split(',')
                ?.mapNotNull { runCatching { PlatformType.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: PlatformType.entries.toSet(),
        )
    }

    suspend fun saveLibraryTree(uri: Uri, includeSubfolders: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.LIBRARY_TREE_URI] = uri.toString()
            preferences[Keys.INCLUDE_SUBFOLDERS] = includeSubfolders
        }
    }

    suspend fun setPlatformEnabled(platformType: PlatformType, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.ENABLED_PLATFORMS]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: PlatformType.entries.map { it.name }.toMutableSet()
            if (enabled) current += platformType.name else current -= platformType.name
            preferences[Keys.ENABLED_PLATFORMS] = current.joinToString(",")
        }
    }

    suspend fun setIncludeSubfolders(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INCLUDE_SUBFOLDERS] = enabled }
    }

    suspend fun setWifiOnlyBulkSearch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY_BULK_SEARCH] = enabled }
    }

    suspend fun setCollectReviews(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COLLECT_REVIEWS] = enabled }
    }

    private object Keys {
        val LIBRARY_TREE_URI = stringPreferencesKey("library_tree_uri")
        val INCLUDE_SUBFOLDERS = booleanPreferencesKey("include_subfolders")
        val WIFI_ONLY_BULK_SEARCH = booleanPreferencesKey("wifi_only_bulk_search")
        val COLLECT_REVIEWS = booleanPreferencesKey("collect_reviews")
        val MAX_REVIEWS_PER_WORK = intPreferencesKey("max_reviews_per_work")
        val ENABLED_PLATFORMS = stringPreferencesKey("enabled_platforms")
    }
}
