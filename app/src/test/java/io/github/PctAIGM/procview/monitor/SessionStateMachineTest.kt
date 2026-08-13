package io.github.PctAIGM.procview.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun happyPathSupportsStartPauseResumeAndStop() {
        var state = SessionMachineState.initial()
        state = reduce(state, SessionEvent.BackendAvailable(sameBoot = true))
        assertEquals(MonitorPhase.READY, state.phase)
        state = reduce(state, SessionEvent.StartRequested)
        assertEquals(MonitorPhase.STARTING, state.phase)
        state = reduce(state, SessionEvent.UserPauseRequested)
        state = reduce(
            state,
            SessionEvent.UserResumeRequested(waitingForFirstFrame = true),
        )
        assertEquals(MonitorPhase.STARTING, state.phase)
        state = reduce(state, SessionEvent.FirstFrameReceived)
        assertEquals(MonitorPhase.RUNNING, state.phase)
        state = reduce(state, SessionEvent.UserPauseRequested)
        assertEquals(PauseReason.USER, state.pauseReason)
        state = reduce(state, SessionEvent.UserResumeRequested())
        assertEquals(MonitorPhase.RUNNING, state.phase)
        state = reduce(state, SessionEvent.StopRequested)
        assertEquals(MonitorPhase.COMPLETED, state.phase)
        assertFalse(state.hasActiveSession)
    }

    @Test
    fun shizukuLossPausesAndSameBootReturnAutomaticallyResumes() {
        var state = runningState()
        state = reduce(state, SessionEvent.BackendUnavailable)
        assertEquals(MonitorPhase.PAUSED, state.phase)
        assertEquals(PauseReason.SHIZUKU, state.pauseReason)
        assertFalse(state.backendReady)

        state = reduce(state, SessionEvent.BackendAvailable(sameBoot = true))
        assertEquals(MonitorPhase.RUNNING, state.phase)
        assertTrue(state.backendReady)

        var waiting = SessionMachineState.initial(backendReady = true)
        waiting = reduce(waiting, SessionEvent.StartRequested)
        waiting = reduce(waiting, SessionEvent.BackendUnavailable)
        waiting = reduce(
            waiting,
            SessionEvent.BackendAvailable(
                sameBoot = true,
                waitingForFirstFrame = true,
            ),
        )
        assertEquals(MonitorPhase.STARTING, waiting.phase)
    }

    @Test
    fun userPauseWhileWaitingPreventsAutomaticResume() {
        var state = reduce(runningState(), SessionEvent.BackendUnavailable)
        state = reduce(state, SessionEvent.UserPauseRequested)
        state = reduce(state, SessionEvent.BackendAvailable(sameBoot = true))

        assertEquals(MonitorPhase.PAUSED, state.phase)
        assertEquals(PauseReason.USER, state.pauseReason)
        assertTrue(state.backendReady)
    }

    @Test
    fun changedBootInterruptsAnActiveSession() {
        val interrupted = reduce(
            reduce(runningState(), SessionEvent.BackendUnavailable),
            SessionEvent.BackendAvailable(sameBoot = false),
        )

        assertEquals(MonitorPhase.INTERRUPTED, interrupted.phase)
        assertFalse(interrupted.hasActiveSession)
    }

    @Test
    fun resumeWithoutBackendWaitsForShizuku() {
        var state = reduce(runningState(), SessionEvent.UserPauseRequested)
        state = reduce(state, SessionEvent.BackendUnavailable)
        state = reduce(state, SessionEvent.UserResumeRequested())

        assertEquals(MonitorPhase.PAUSED, state.phase)
        assertEquals(PauseReason.SHIZUKU, state.pauseReason)
    }

    @Test
    fun storageFailureCannotBeMistakenForAUserPause() {
        var state = reduce(runningState(), SessionEvent.StorageFailed)
        assertEquals(PauseReason.STORAGE, state.pauseReason)
        state = reduce(
            state,
            SessionEvent.StorageRecovered(MonitorPhase.RUNNING, previousPauseReason = null),
        )
        assertEquals(MonitorPhase.RUNNING, state.phase)
    }

    @Test
    fun storageRecoveryPreservesAUsersExistingPause() {
        val userPaused = reduce(runningState(), SessionEvent.UserPauseRequested)
        val storagePaused = reduce(userPaused, SessionEvent.StorageFailed)
        val recovered = reduce(
            storagePaused,
            SessionEvent.StorageRecovered(userPaused.phase, userPaused.pauseReason),
        )

        assertEquals(MonitorPhase.PAUSED, recovered.phase)
        assertEquals(PauseReason.USER, recovered.pauseReason)
    }

    @Test
    fun storageRecoveryWaitsForShizukuWhenBackendWasLost() {
        val running = runningState()
        var state = reduce(running, SessionEvent.StorageFailed)
        state = reduce(state, SessionEvent.BackendUnavailable)
        state = reduce(
            state,
            SessionEvent.StorageRecovered(running.phase, running.pauseReason),
        )

        assertEquals(MonitorPhase.PAUSED, state.phase)
        assertEquals(PauseReason.SHIZUKU, state.pauseReason)
    }

    @Test
    fun repeatedNotificationActionsAreIdempotent() {
        val running = runningState()
        val paused = reduce(running, SessionEvent.UserPauseRequested)
        assertEquals(paused, reduce(paused, SessionEvent.UserPauseRequested))
        assertEquals(running, reduce(running, SessionEvent.UserResumeRequested()))
        val completed = reduce(running, SessionEvent.StopRequested)
        assertEquals(completed, reduce(completed, SessionEvent.StopRequested))
    }

    @Test
    fun resetOnlyMovesTerminalStatesBackToReadiness() {
        val running = runningState()
        assertEquals(running, reduce(running, SessionEvent.Reset))
        val completed = reduce(running, SessionEvent.StopRequested)
        assertEquals(MonitorPhase.READY, reduce(completed, SessionEvent.Reset).phase)
    }

    @Test
    fun interruptedTerminalCanPauseForAStorageRetry() {
        val interrupted = reduce(runningState(), SessionEvent.Interrupted)
        val storagePaused = reduce(interrupted, SessionEvent.StorageFailed)

        assertEquals(MonitorPhase.PAUSED, storagePaused.phase)
        assertEquals(PauseReason.STORAGE, storagePaused.pauseReason)
    }

    @Test
    fun everyEventIsIdempotentAcrossEveryReachableStateShape() {
        val states = listOf(
            SessionMachineState.initial(false),
            SessionMachineState.initial(true),
            SessionMachineState(MonitorPhase.STARTING, backendReady = true),
            SessionMachineState(MonitorPhase.RUNNING, backendReady = true),
            SessionMachineState(MonitorPhase.PAUSED, PauseReason.USER, backendReady = true),
            SessionMachineState(MonitorPhase.PAUSED, PauseReason.USER, backendReady = false),
            SessionMachineState(MonitorPhase.PAUSED, PauseReason.SHIZUKU, backendReady = false),
            SessionMachineState(MonitorPhase.PAUSED, PauseReason.STORAGE, backendReady = true),
            SessionMachineState(MonitorPhase.PAUSED, PauseReason.STORAGE, backendReady = false),
            SessionMachineState(MonitorPhase.COMPLETED, backendReady = true),
            SessionMachineState(MonitorPhase.INTERRUPTED, backendReady = false),
        )
        val events = listOf(
            SessionEvent.BackendAvailable(sameBoot = true),
            SessionEvent.BackendAvailable(sameBoot = false),
            SessionEvent.BackendUnavailable,
            SessionEvent.StartRequested,
            SessionEvent.FirstFrameReceived,
            SessionEvent.UserPauseRequested,
            SessionEvent.UserResumeRequested(),
            SessionEvent.StopRequested,
            SessionEvent.StorageFailed,
            SessionEvent.StorageRecovered(MonitorPhase.RUNNING, previousPauseReason = null),
            SessionEvent.Interrupted,
            SessionEvent.Reset,
        )

        states.forEach { state ->
            events.forEach { event ->
                val once = reduce(state, event)
                assertEquals("$state + $event", once, reduce(once, event))
            }
        }
    }

    private fun runningState(): SessionMachineState {
        var state = SessionMachineState.initial(backendReady = true)
        state = reduce(state, SessionEvent.StartRequested)
        return reduce(state, SessionEvent.FirstFrameReceived)
    }

    private fun reduce(state: SessionMachineState, event: SessionEvent) =
        SessionStateMachine.reduce(state, event)
}
