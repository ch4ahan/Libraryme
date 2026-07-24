package com.personal.novellibrary

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.novellibrary.data.MIGRATION_4_5
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-4-5-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationBackfillsTitleKeysAndMergesDuplicateCollections() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE NovelEntity (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                normalizedTitle TEXT NOT NULL,
                                displayTitle TEXT NOT NULL,
                                confirmedTitle TEXT
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE CollectionEntity (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE CollectionItemEntity (
                                collectionId INTEGER NOT NULL,
                                novelId INTEGER NOT NULL,
                                addedAt INTEGER NOT NULL,
                                sortOrder INTEGER NOT NULL,
                                PRIMARY KEY(collectionId, novelId)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO NovelEntity(id, normalizedTitle, displayTitle, confirmedTitle) VALUES (1, '내가 너에게 갈게', '내가 너에게 갈게', NULL)",
        )
        db.execSQL("INSERT INTO CollectionEntity(id, name) VALUES (10, '읽을 것')")
        db.execSQL("INSERT INTO CollectionEntity(id, name) VALUES (20, '읽을 것')")
        db.execSQL("INSERT INTO CollectionItemEntity VALUES (10, 1, 100, 0)")
        db.execSQL("INSERT INTO CollectionItemEntity VALUES (20, 2, 200, 1)")

        MIGRATION_4_5.migrate(db)

        db.query("SELECT normalizedTitle FROM NovelEntity WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("내가너에게갈게", it.getString(0))
        }
        db.query("SELECT id FROM CollectionEntity WHERE name = '읽을 것'").use {
            assertTrue(it.moveToFirst())
            assertEquals(20, it.getInt(0))
            assertTrue(!it.moveToNext())
        }
        db.query("SELECT novelId FROM CollectionItemEntity WHERE collectionId = 20 ORDER BY novelId").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertTrue(it.moveToNext())
            assertEquals(2, it.getInt(0))
        }
        val duplicateRejected = runCatching {
            db.execSQL("INSERT INTO CollectionEntity(name) VALUES ('읽을 것')")
        }.isFailure
        assertTrue(duplicateRejected)
        helper.close()
    }
}
