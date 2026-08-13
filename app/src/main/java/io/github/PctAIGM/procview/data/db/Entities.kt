package io.github.PctAIGM.procview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "capability_reports",
    indices = [Index("probedAtWallTimeMs")],
)
data class CapabilityReportEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val probedAtWallTimeMs: Long,
    val quality: String,
    val metricCoverage: Double,
    val backendMode: String,
    val reportJson: String,
)

@Entity(
    tableName = "sessions",
    indices = [Index("startWallTimeMs"), Index("status"), Index("capabilityReportId")],
    foreignKeys = [
        ForeignKey(
            entity = CapabilityReportEntity::class,
            parentColumns = ["id"],
            childColumns = ["capabilityReportId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class SessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val note: String = "",
    val status: String,
    val pauseReason: String? = null,
    val startWallTimeMs: Long,
    val endWallTimeMs: Long? = null,
    val startElapsedRealtimeNanos: Long,
    val bootId: String,
    val samplingProfile: String,
    val deviceModel: String,
    val androidVersion: String,
    val romDisplay: String,
    val procViewVersion: String,
    val shizukuVersion: String,
    val backendMode: String,
    val capabilityReportId: Long? = null,
    val lastHeartbeatWallTimeMs: Long,
    val lastSampleSequence: Long? = null,
    val peakSystemCpuBasisPoints: Int? = null,
    val minimumAvailableMemoryKb: Long? = null,
    val maximumBatteryTemperatureDeciC: Int? = null,
    val maximumThermalStatus: Int? = null,
    val elapsedDurationMs: Long = 0L,
)

@Entity(
    tableName = "system_samples",
    primaryKeys = ["sessionId", "sequence"],
    indices = [Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SystemSampleEntity(
    val sessionId: String,
    val sequence: Long,
    val elapsedOffsetMs: Long,
    val wallTimeMs: Long,
    val cpuPercentBasisPoints: Int?,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val batteryLevelPercent: Int?,
    val batteryTemperatureDeciC: Int?,
    val chargingState: String,
    val thermalStatus: Int?,
    val thermalValueMilliC: Int? = null,
    val thermalSensorName: String? = null,
    val screenInteractive: Boolean,
    val samplingIntervalMs: Long,
    val collectionDurationMs: Long,
    val dataSource: String,
    val frameFlags: Int,
)

@Entity(
    tableName = "process_identities",
    indices = [
        Index(value = ["sessionId", "pid", "startTimeTicks"], unique = true),
        Index("sessionId"),
        Index("packageName"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProcessIdentityEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val pid: Int,
    val startTimeTicks: Long,
    val parentPid: Int,
    val uid: Int?,
    val packageName: String?,
    val processName: String,
    val displayName: String,
    val commandLine: String,
    val isSystem: Boolean,
    val isNative: Boolean,
    val firstSeenOffsetMs: Long,
    val lastSeenOffsetMs: Long,
)

@Entity(
    tableName = "process_package_candidates",
    primaryKeys = ["processIdentityId", "packageName"],
    indices = [Index("packageName")],
    foreignKeys = [
        ForeignKey(
            entity = ProcessIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["processIdentityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProcessPackageCandidateEntity(
    val processIdentityId: Long,
    val packageName: String,
    val isPrimary: Boolean,
)

@Entity(
    tableName = "process_samples",
    primaryKeys = ["sessionId", "systemSampleSequence", "processIdentityId"],
    indices = [Index("processIdentityId"), Index(value = ["sessionId", "systemSampleSequence"])],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProcessIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["processIdentityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProcessSampleEntity(
    val sessionId: String,
    val systemSampleSequence: Long,
    val processIdentityId: Long,
    val cpuPercentBasisPoints: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
    val pssSampleElapsedRealtimeNanos: Long?,
    val processState: String,
    val rank: Int?,
    val reasonKept: Int,
)

@Entity(
    tableName = "session_events",
    indices = [Index(value = ["sessionId", "elapsedOffsetMs"])],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val elapsedOffsetMs: Long,
    val wallTimeMs: Long,
    val type: String,
    val payloadJson: String? = null,
)

@Entity(
    tableName = "pinned_targets",
    indices = [Index("kind"), Index("packageName")],
)
data class PinnedTargetEntity(
    @androidx.room.PrimaryKey val stableKey: String,
    val kind: String,
    val packageName: String?,
    val processName: String?,
    val uid: Int?,
    val createdAtWallTimeMs: Long,
    val alias: String? = null,
)

@Entity(
    tableName = "process_summaries",
    primaryKeys = ["sessionId", "processIdentityId"],
    indices = [Index("processIdentityId")],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProcessIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["processIdentityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProcessSummaryEntity(
    val sessionId: String,
    val processIdentityId: Long,
    val sampleCount: Long,
    val peakCpuPercentBasisPoints: Int?,
    val peakRssKb: Long?,
    val peakPssKb: Long?,
    val bestRank: Int?,
)
