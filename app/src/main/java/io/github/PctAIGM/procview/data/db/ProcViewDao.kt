package io.github.PctAIGM.procview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCapabilityReport(report: CapabilityReportEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startWallTimeMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeSession(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun session(sessionId: String): SessionEntity?

    @Query(
        """
        UPDATE sessions
        SET status = :status, pauseReason = :pauseReason,
            endWallTimeMs = CASE WHEN :terminal THEN :wallTimeMs ELSE endWallTimeMs END,
            lastHeartbeatWallTimeMs = :wallTimeMs,
            elapsedDurationMs = CASE
                WHEN :elapsedOffsetMs > elapsedDurationMs THEN :elapsedOffsetMs
                ELSE elapsedDurationMs END
        WHERE id = :sessionId
        """,
    )
    suspend fun updateSessionState(
        sessionId: String,
        status: String,
        pauseReason: String?,
        wallTimeMs: Long,
        elapsedOffsetMs: Long,
        terminal: Boolean,
    )

    @Query(
        """
        UPDATE sessions
        SET lastHeartbeatWallTimeMs = :wallTimeMs,
            elapsedDurationMs = CASE
                WHEN :elapsedOffsetMs > elapsedDurationMs THEN :elapsedOffsetMs
                ELSE elapsedDurationMs END,
            lastSampleSequence = :sequence,
            peakSystemCpuBasisPoints = CASE
                WHEN :cpuBasisPoints IS NULL THEN peakSystemCpuBasisPoints
                WHEN peakSystemCpuBasisPoints IS NULL OR :cpuBasisPoints > peakSystemCpuBasisPoints
                    THEN :cpuBasisPoints ELSE peakSystemCpuBasisPoints END,
            minimumAvailableMemoryKb = CASE
                WHEN :memoryAvailableKb IS NULL THEN minimumAvailableMemoryKb
                WHEN minimumAvailableMemoryKb IS NULL OR :memoryAvailableKb < minimumAvailableMemoryKb
                    THEN :memoryAvailableKb ELSE minimumAvailableMemoryKb END,
            maximumBatteryTemperatureDeciC = CASE
                WHEN :batteryTemperatureDeciC IS NULL THEN maximumBatteryTemperatureDeciC
                WHEN maximumBatteryTemperatureDeciC IS NULL OR
                    :batteryTemperatureDeciC > maximumBatteryTemperatureDeciC
                    THEN :batteryTemperatureDeciC ELSE maximumBatteryTemperatureDeciC END,
            maximumThermalStatus = CASE
                WHEN :thermalStatus IS NULL THEN maximumThermalStatus
                WHEN maximumThermalStatus IS NULL OR :thermalStatus > maximumThermalStatus
                    THEN :thermalStatus ELSE maximumThermalStatus END
        WHERE id = :sessionId
        """,
    )
    suspend fun updateHeartbeatAndSummary(
        sessionId: String,
        wallTimeMs: Long,
        elapsedOffsetMs: Long,
        sequence: Long,
        cpuBasisPoints: Int?,
        memoryAvailableKb: Long?,
        batteryTemperatureDeciC: Int?,
        thermalStatus: Int?,
    )

    @Query("UPDATE sessions SET name = :name, note = :note WHERE id = :sessionId")
    suspend fun updateNameAndNote(sessionId: String, name: String, note: String)

    @Query("DELETE FROM sessions WHERE id = :sessionId AND status IN ('COMPLETED', 'INTERRUPTED')")
    suspend fun deleteSession(sessionId: String): Int

    @Query(
        """
        DELETE FROM capability_reports
        WHERE id = :capabilityReportId
            AND NOT EXISTS (
                SELECT 1 FROM sessions WHERE capabilityReportId = :capabilityReportId
            )
        """,
    )
    suspend fun deleteCapabilityReportIfOrphaned(capabilityReportId: Long): Int

    @Query("SELECT * FROM sessions WHERE status IN ('STARTING', 'RUNNING', 'PAUSED')")
    suspend fun openSessions(): List<SessionEntity>

    @Query(
        """
        SELECT s.id FROM sessions s
        WHERE s.status IN ('COMPLETED', 'INTERRUPTED')
            AND EXISTS (
                SELECT 1 FROM process_samples ps WHERE ps.sessionId = s.id
            )
            AND NOT EXISTS (
                SELECT 1 FROM process_summaries summary WHERE summary.sessionId = s.id
            )
        """,
    )
    suspend fun sessionsNeedingSummaryRepair(): List<String>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM system_samples WHERE sessionId = :sessionId) AS systemSampleCount,
            (SELECT COUNT(*) FROM process_samples WHERE sessionId = :sessionId) AS processSampleCount,
            (SELECT COUNT(*) FROM process_identities WHERE sessionId = :sessionId) AS identityCount,
            (SELECT COUNT(*) FROM session_events WHERE sessionId = :sessionId) AS eventCount
        """,
    )
    suspend fun storageCounts(sessionId: String): SessionStorageCounts
}

data class SessionStorageCounts(
    val systemSampleCount: Long,
    val processSampleCount: Long,
    val identityCount: Long,
    val eventCount: Long,
) {
    val estimatedBytes: Long
        get() = systemSampleCount * 144L + processSampleCount * 80L +
            identityCount * 384L + eventCount * 128L
}

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSystemSamples(samples: List<SystemSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentity(identity: ProcessIdentityEntity): Long

    @Query(
        """
        SELECT id FROM process_identities
        WHERE sessionId = :sessionId AND pid = :pid AND startTimeTicks = :startTimeTicks
        LIMIT 1
        """,
    )
    suspend fun identityId(sessionId: String, pid: Int, startTimeTicks: Long): Long?

    @Query(
        """
        UPDATE process_identities
        SET lastSeenOffsetMs = CASE
                WHEN :lastSeenOffsetMs > lastSeenOffsetMs THEN :lastSeenOffsetMs
                ELSE lastSeenOffsetMs END,
            parentPid = :parentPid,
            uid = COALESCE(:uid, uid),
            packageName = CASE
                WHEN :packageCandidatesAvailable THEN :packageName
                WHEN :uid IS NULL OR :uid = uid THEN packageName
                ELSE NULL END,
            processName = CASE
                WHEN :processName != '' THEN :processName ELSE processName END,
            displayName = CASE
                WHEN :displayName = '' THEN displayName
                WHEN NOT :packageCandidatesAvailable AND packageName IS NOT NULL
                    AND (:uid IS NULL OR :uid = uid) THEN displayName
                ELSE :displayName END,
            commandLine = CASE
                WHEN :commandLine != '' THEN :commandLine ELSE commandLine END,
            isSystem = CASE
                WHEN NOT :packageCandidatesAvailable
                    AND (:uid IS NULL OR :uid = uid) THEN isSystem
                ELSE :isSystem END,
            isNative = CASE
                WHEN NOT :packageCandidatesAvailable
                    AND (:uid IS NULL OR :uid = uid) THEN isNative
                ELSE :isNative END
        WHERE id = :identityId
        """,
    )
    suspend fun updateIdentity(
        identityId: Long,
        lastSeenOffsetMs: Long,
        parentPid: Int,
        uid: Int?,
        packageName: String?,
        packageCandidatesAvailable: Boolean,
        processName: String,
        displayName: String,
        commandLine: String,
        isSystem: Boolean,
        isNative: Boolean,
    )

    @Query(
        "UPDATE process_package_candidates SET isPrimary = 0 " +
            "WHERE processIdentityId = :identityId",
    )
    suspend fun clearPackageCandidatePrimaries(identityId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPackageCandidates(candidates: List<ProcessPackageCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProcessSamples(samples: List<ProcessSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<SessionEventEntity>)

    @Query("DELETE FROM process_summaries WHERE sessionId = :sessionId")
    suspend fun deleteSummaries(sessionId: String)

    @Query(
        """
        INSERT INTO process_summaries (
            sessionId, processIdentityId, sampleCount,
            peakCpuPercentBasisPoints, peakRssKb, peakPssKb, bestRank
        )
        SELECT sessionId, processIdentityId, COUNT(*),
            MAX(cpuPercentBasisPoints), MAX(rssKb), MAX(pssKb), MIN(rank)
        FROM process_samples
        WHERE sessionId = :sessionId
        GROUP BY sessionId, processIdentityId
        """,
    )
    suspend fun rebuildSummaries(sessionId: String)

    @Query("SELECT * FROM system_samples WHERE sessionId = :sessionId ORDER BY sequence")
    fun observeSystemSamples(sessionId: String): Flow<List<SystemSampleEntity>>

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY elapsedOffsetMs, id")
    fun observeEvents(sessionId: String): Flow<List<SessionEventEntity>>

    @Query(
        """
        SELECT MAX(elapsedOffsetMs) FROM (
            SELECT elapsedOffsetMs FROM system_samples WHERE sessionId = :sessionId
            UNION ALL
            SELECT elapsedOffsetMs FROM session_events WHERE sessionId = :sessionId
        )
        """,
    )
    suspend fun latestElapsedOffsetMs(sessionId: String): Long?

    @Query(
        """
        SELECT
            ps.processIdentityId AS identityId,
            pi.pid AS pid,
            pi.startTimeTicks AS startTimeTicks,
            pi.uid AS uid,
            pi.packageName AS packageName,
            (SELECT GROUP_CONCAT(packageName, '|') FROM (
                SELECT candidate.packageName AS packageName
                FROM process_package_candidates candidate
                WHERE candidate.processIdentityId = pi.id
                ORDER BY candidate.packageName COLLATE NOCASE
            )) AS packageCandidates,
            pi.processName AS processName,
            pi.displayName AS displayName,
            ps.cpuPercentBasisPoints AS cpuPercentBasisPoints,
            ps.rssKb AS rssKb,
            ps.pssKb AS pssKb,
            ps.processState AS processState,
            ps.rank AS "rank",
            ps.reasonKept AS reasonKept
        FROM process_samples ps
        JOIN process_identities pi ON pi.id = ps.processIdentityId
        WHERE ps.sessionId = :sessionId AND ps.systemSampleSequence = :sequence
        ORDER BY CASE WHEN ps.rank IS NULL THEN 2147483647 ELSE ps.rank END,
            ps.cpuPercentBasisPoints DESC, pi.displayName COLLATE NOCASE, pi.pid
        """,
    )
    suspend fun processRowsAt(sessionId: String, sequence: Long): List<HistoryProcessRow>

    @Query(
        """
        SELECT ps.systemSampleSequence AS sequence,
            CASE WHEN COUNT(ps.cpuPercentBasisPoints) = COUNT(*)
                THEN CAST(SUM(ps.cpuPercentBasisPoints) AS INTEGER)
                ELSE NULL END AS cpuPercentBasisPoints,
            CASE WHEN COUNT(ps.rssKb) = COUNT(*)
                THEN SUM(ps.rssKb) ELSE NULL END AS rssKb,
            CASE WHEN COUNT(ps.pssKb) = COUNT(*)
                THEN SUM(ps.pssKb) ELSE NULL END AS pssKb
        FROM process_samples ps
        JOIN process_identities pi ON pi.id = ps.processIdentityId
        WHERE ps.sessionId = :sessionId
            AND (ps.reasonKept & 4) != 0
            AND (
                (:kind = 'PACKAGE' AND (
                    pi.packageName = :packageName OR EXISTS (
                        SELECT 1 FROM process_package_candidates candidate
                        WHERE candidate.processIdentityId = pi.id
                            AND candidate.packageName = :packageName
                    )
                )) OR
                (:kind = 'PACKAGE_PROCESS' AND pi.processName = :processName AND (
                    pi.packageName = :packageName OR EXISTS (
                        SELECT 1 FROM process_package_candidates candidate
                        WHERE candidate.processIdentityId = pi.id
                            AND candidate.packageName = :packageName
                    )
                )) OR
                (:kind = 'UID' AND pi.uid = :uid) OR
                (:kind = 'COMMAND_UID' AND pi.uid = :uid AND pi.processName = :processName)
            )
        GROUP BY ps.systemSampleSequence
        ORDER BY ps.systemSampleSequence
        """,
    )
    suspend fun targetSamples(
        sessionId: String,
        kind: String,
        packageName: String?,
        processName: String?,
        uid: Int?,
    ): List<HistoryTargetSample>

    @Query("SELECT * FROM process_identities WHERE sessionId = :sessionId ORDER BY displayName, pid")
    suspend fun identities(sessionId: String): List<ProcessIdentityEntity>

    @Query("SELECT * FROM process_samples WHERE sessionId = :sessionId ORDER BY systemSampleSequence")
    suspend fun processSamples(sessionId: String): List<ProcessSampleEntity>

    @Query(
        """
        SELECT pi.displayName AS displayName, pi.packageName AS packageName,
            s.peakCpuPercentBasisPoints AS peakCpuPercentBasisPoints,
            s.peakRssKb AS peakRssKb, s.peakPssKb AS peakPssKb,
            s.bestRank AS bestRank
        FROM process_summaries s
        JOIN process_identities pi ON pi.id = s.processIdentityId
        WHERE s.sessionId = :sessionId
        ORDER BY CASE WHEN s.bestRank IS NULL THEN 2147483647 ELSE s.bestRank END,
            s.peakCpuPercentBasisPoints DESC, pi.displayName COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun topSummaries(sessionId: String, limit: Int): Flow<List<HistoryProcessSummary>>
}

data class HistoryProcessRow(
    val identityId: Long,
    val pid: Int,
    val startTimeTicks: Long,
    val uid: Int?,
    val packageName: String?,
    val packageCandidates: String?,
    val processName: String,
    val displayName: String,
    val cpuPercentBasisPoints: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
    val processState: String,
    val rank: Int?,
    val reasonKept: Int,
)

data class HistoryProcessSummary(
    val displayName: String,
    val packageName: String?,
    val peakCpuPercentBasisPoints: Int?,
    val peakRssKb: Long?,
    val peakPssKb: Long?,
    val bestRank: Int?,
)

data class HistoryTargetSample(
    val sequence: Long,
    val cpuPercentBasisPoints: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
)

@Dao
interface PinnedTargetDao {
    @Query("SELECT * FROM pinned_targets ORDER BY createdAtWallTimeMs, stableKey")
    fun observeTargets(): Flow<List<PinnedTargetEntity>>

    @Query("SELECT * FROM pinned_targets WHERE stableKey = :stableKey LIMIT 1")
    suspend fun target(stableKey: String): PinnedTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(target: PinnedTargetEntity)

    @Query("DELETE FROM pinned_targets WHERE stableKey = :stableKey")
    suspend fun delete(stableKey: String): Int
}
