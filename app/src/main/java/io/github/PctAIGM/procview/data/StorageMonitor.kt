package io.github.PctAIGM.procview.data

import android.content.Context
import android.os.StatFs
import io.github.PctAIGM.procview.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageHealth(
    val databaseBytes: Long,
    val warningBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
) {
    val thresholdReached: Boolean get() = databaseBytes >= warningBytes
    val availablePercent: Int
        get() = ((availableBytes.toDouble() / totalBytes.coerceAtLeast(1L)) * 100)
            .toInt()
            .coerceIn(0, 100)
    val deviceLow: Boolean
        get() = availableBytes.toDouble() / totalBytes.coerceAtLeast(1L) < DEVICE_FREE_FRACTION
    val warningActive: Boolean get() = thresholdReached || deviceLow

    companion object {
        private const val BYTES_PER_MEGABYTE = 1_048_576L
        private const val DEVICE_FREE_FRACTION = 0.10
        val Empty = StorageHealth(
            databaseBytes = 0L,
            warningBytes = UserSettings.DEFAULT_STORAGE_WARNING_MB * BYTES_PER_MEGABYTE,
            availableBytes = 1L,
            totalBytes = 1L,
        )
    }
}

class StorageMonitor(
    context: Context,
    private val historyRepository: HistoryRepository,
) {
    private val applicationContext = context.applicationContext

    suspend fun snapshot(warningMegabytes: Int): StorageHealth = withContext(Dispatchers.IO) {
        val stat = StatFs(applicationContext.filesDir.absolutePath)
        StorageHealth(
            databaseBytes = historyRepository.databaseBytes(),
            warningBytes = warningMegabytes.toLong() * BYTES_PER_MEGABYTE,
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes.coerceAtLeast(1L),
        )
    }

    private companion object {
        const val BYTES_PER_MEGABYTE = 1_048_576L
    }
}
