package io.github.PctAIGM.procview.data

import android.content.Context
import androidx.room.withTransaction
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.data.db.HistoryProcessRow
import io.github.PctAIGM.procview.data.db.HistoryProcessSummary
import io.github.PctAIGM.procview.data.db.HistoryTargetSample
import io.github.PctAIGM.procview.data.db.ProcViewDatabase
import io.github.PctAIGM.procview.data.db.SessionEntity
import io.github.PctAIGM.procview.data.db.SessionEventEntity
import io.github.PctAIGM.procview.data.db.SystemSampleEntity
import io.github.PctAIGM.procview.model.PinnedTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class HistoryRepository(
    context: Context,
    private val database: ProcViewDatabase,
    private val canReclaimStorage: () -> Boolean = { true },
) {
    private val applicationContext = context.applicationContext

    fun sessions(): Flow<List<SessionEntity>> = database.sessionDao().observeSessions()

    fun session(sessionId: String): Flow<SessionEntity?> =
        database.sessionDao().observeSession(sessionId)

    fun systemSamples(sessionId: String): Flow<List<SystemSampleEntity>> =
        database.sampleDao().observeSystemSamples(sessionId)

    fun events(sessionId: String): Flow<List<SessionEventEntity>> =
        database.sampleDao().observeEvents(sessionId)

    suspend fun processRowsAt(sessionId: String, sequence: Long): List<HistoryProcessRow> =
        database.sampleDao().processRowsAt(sessionId, sequence)

    fun topSummaries(
        sessionId: String,
        limit: Int = 3,
    ): Flow<List<HistoryProcessSummary>> = database.sampleDao().topSummaries(sessionId, limit)

    suspend fun targetSamples(
        sessionId: String,
        target: PinnedTarget,
    ): List<HistoryTargetSample> = database.sampleDao().targetSamples(
        sessionId = sessionId,
        kind = target.kind.name,
        packageName = target.packageName,
        processName = target.processName,
        uid = target.uid,
    )

    suspend fun estimatedSessionBytes(sessionId: String): Long =
        database.sessionDao().storageCounts(sessionId).estimatedBytes

    suspend fun databaseBytes(): Long = withContext(Dispatchers.IO) {
        val databaseFile = applicationContext.getDatabasePath(ProcViewDatabase.DATABASE_NAME)
        listOf(
            databaseFile,
            java.io.File(databaseFile.path + "-wal"),
            java.io.File(databaseFile.path + "-shm"),
        ).sumOf { file -> file.takeIf { it.isFile }?.length() ?: 0L }
    }

    suspend fun updateNameAndNote(sessionId: String, name: String, note: String) {
        val safeName = name.trim().take(MAX_SESSION_NAME_CHARS)
            .ifBlank { applicationContext.getString(R.string.default_session_name) }
        val safeNote = note.trim().take(MAX_SESSION_NOTE_CHARS)
        database.withTransaction {
            val previous = database.sessionDao().session(sessionId) ?: return@withTransaction
            database.sessionDao().updateNameAndNote(sessionId, safeName, safeNote)
            if (previous.note != safeNote) {
                database.sampleDao().insertEvents(
                    listOf(
                        SessionEventEntity(
                            sessionId = sessionId,
                            elapsedOffsetMs = maxOf(
                                previous.elapsedDurationMs,
                                database.sampleDao().latestElapsedOffsetMs(sessionId) ?: 0L,
                            ),
                            wallTimeMs = System.currentTimeMillis(),
                            type = USER_NOTE_EVENT,
                            payloadJson = null,
                        ),
                    ),
                )
            }
        }
    }

    suspend fun deleteSession(sessionId: String): Boolean {
        val deleted = database.withTransaction {
            val capabilityReportId = database.sessionDao().session(sessionId)?.capabilityReportId
            val removed = database.sessionDao().deleteSession(sessionId) > 0
            if (removed && capabilityReportId != null) {
                database.sessionDao().deleteCapabilityReportIfOrphaned(capabilityReportId)
            }
            removed
        }
        if (deleted && canReclaimStorage()) reclaimUnusedPages()
        return deleted
    }

    private suspend fun reclaimUnusedPages() = withContext(Dispatchers.IO) {
        runCatching {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .close()
            database.openHelper.writableDatabase.execSQL("PRAGMA incremental_vacuum(2048)")
        }
    }

    private companion object {
        const val MAX_SESSION_NAME_CHARS = 80
        const val MAX_SESSION_NOTE_CHARS = 2_000
        const val USER_NOTE_EVENT = "USER_NOTE"
    }
}
