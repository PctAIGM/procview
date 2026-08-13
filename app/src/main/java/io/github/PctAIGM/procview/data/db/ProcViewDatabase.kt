package io.github.PctAIGM.procview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CapabilityReportEntity::class,
        SessionEntity::class,
        SystemSampleEntity::class,
        ProcessIdentityEntity::class,
        ProcessPackageCandidateEntity::class,
        ProcessSampleEntity::class,
        SessionEventEntity::class,
        PinnedTargetEntity::class,
        ProcessSummaryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ProcViewDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun pinnedTargetDao(): PinnedTargetDao

    companion object {
        const val DATABASE_NAME = "procview.db"

        @Volatile
        private var instance: ProcViewDatabase? = null

        fun get(context: Context): ProcViewDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProcViewDatabase::class.java,
                DATABASE_NAME,
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                        }
                    },
                )
                .build()
                .also { instance = it }
        }
    }
}
