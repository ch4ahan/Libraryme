package com.personal.novellibrary

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.novellibrary.data.MIGRATION_3_4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-3-4-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationDeduplicatesListingsAndAddsUniqueIndex() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE PlatformListingEntity (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, novelId INTEGER NOT NULL, platformType TEXT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO PlatformListingEntity(novelId, platformType) VALUES (1, 'RIDI')")
        db.execSQL("INSERT INTO PlatformListingEntity(novelId, platformType) VALUES (1, 'RIDI')")

        MIGRATION_3_4.migrate(db)

        db.query("SELECT COUNT(*) FROM PlatformListingEntity").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        val duplicateRejected = runCatching {
            db.execSQL("INSERT INTO PlatformListingEntity(novelId, platformType) VALUES (1, 'RIDI')")
        }.isFailure
        assertTrue(duplicateRejected)
        helper.close()
    }
}
