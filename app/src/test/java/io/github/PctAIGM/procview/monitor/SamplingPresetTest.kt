package io.github.PctAIGM.procview.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingPresetTest {
    @Test
    fun balancedUsesOneSecondOnlyForVisibleInteractiveApp() {
        assertEquals(1_000L, SamplingPreset.BALANCED.effectiveIntervalMs(true, true))
        assertEquals(5_000L, SamplingPreset.BALANCED.effectiveIntervalMs(false, true))
        assertEquals(5_000L, SamplingPreset.BALANCED.effectiveIntervalMs(true, false))
    }

    @Test
    fun wakeLockPolicyRequiresRunningScreenOffAndSupportedPreset() {
        val running = SessionMachineState(
            phase = MonitorPhase.RUNNING,
            backendReady = true,
        )
        val screenOff = MonitorEnvironment(appForeground = false, screenInteractive = false)
        assertTrue(WakeLockPolicy.shouldHold(running, SamplingPreset.BALANCED, screenOff))
        assertFalse(WakeLockPolicy.shouldHold(running, SamplingPreset.POWER_SAVER, screenOff))
        assertFalse(
            WakeLockPolicy.shouldHold(
                running.copy(phase = MonitorPhase.PAUSED, pauseReason = PauseReason.USER),
                SamplingPreset.BALANCED,
                screenOff,
            ),
        )
        assertFalse(
            WakeLockPolicy.shouldHold(
                running,
                SamplingPreset.BALANCED,
                screenOff.copy(screenInteractive = true),
            ),
        )
    }
}
