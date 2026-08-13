package io.github.PctAIGM.procview.monitor

enum class SamplingPreset(
    val foregroundIntervalMs: Long,
    val backgroundIntervalMs: Long,
    val pssIntervalMs: Long,
    val holdsScreenOffWakeLock: Boolean,
) {
    FINE(
        foregroundIntervalMs = 1_000L,
        backgroundIntervalMs = 2_000L,
        pssIntervalMs = 10_000L,
        holdsScreenOffWakeLock = true,
    ),
    BALANCED(
        foregroundIntervalMs = 1_000L,
        backgroundIntervalMs = 5_000L,
        pssIntervalMs = 15_000L,
        holdsScreenOffWakeLock = true,
    ),
    POWER_SAVER(
        foregroundIntervalMs = 2_000L,
        backgroundIntervalMs = 15_000L,
        pssIntervalMs = 60_000L,
        holdsScreenOffWakeLock = false,
    ),
    ;

    fun effectiveIntervalMs(appForeground: Boolean, screenInteractive: Boolean): Long =
        if (appForeground && screenInteractive) foregroundIntervalMs else backgroundIntervalMs
}

data class MonitorEnvironment(
    val appForeground: Boolean,
    val screenInteractive: Boolean,
    val batteryLevelPercent: Int? = null,
    val batteryTemperatureDeciC: Int? = null,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    val thermalStatus: Int? = null,
)

enum class ChargingState {
    UNKNOWN,
    DISCHARGING,
    CHARGING,
    FULL,
}

object WakeLockPolicy {
    fun shouldHold(
        machineState: SessionMachineState,
        preset: SamplingPreset,
        environment: MonitorEnvironment,
    ): Boolean = machineState.phase == MonitorPhase.RUNNING &&
        !environment.screenInteractive &&
        preset.holdsScreenOffWakeLock
}
