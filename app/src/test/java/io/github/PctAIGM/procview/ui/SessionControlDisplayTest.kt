package io.github.PctAIGM.procview.ui

import io.github.PctAIGM.procview.monitor.MonitorPhase
import io.github.PctAIGM.procview.monitor.SessionMachineState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControlDisplayTest {
    @Test
    fun `idle display follows the current capability result`() {
        assertEquals(
            MonitorPhase.READY,
            resolveDisplayedMonitorPhase(
                SessionMachineState.initial(backendReady = false),
                backendReady = true,
            ),
        )
        assertEquals(
            MonitorPhase.NOT_READY,
            resolveDisplayedMonitorPhase(
                SessionMachineState.initial(backendReady = true),
                backendReady = false,
            ),
        )
    }

    @Test
    fun `active and terminal phases remain service owned`() {
        assertEquals(
            MonitorPhase.RUNNING,
            resolveDisplayedMonitorPhase(
                SessionMachineState(MonitorPhase.RUNNING, backendReady = true),
                backendReady = false,
            ),
        )
        assertEquals(
            MonitorPhase.COMPLETED,
            resolveDisplayedMonitorPhase(
                SessionMachineState(MonitorPhase.COMPLETED, backendReady = true),
                backendReady = false,
            ),
        )
    }
}
