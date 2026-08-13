package io.github.PctAIGM.procview.monitor

enum class MonitorPhase {
    NOT_READY,
    READY,
    STARTING,
    RUNNING,
    PAUSED,
    COMPLETED,
    INTERRUPTED,
}

enum class PauseReason {
    USER,
    SHIZUKU,
    STORAGE,
}

data class SessionMachineState(
    val phase: MonitorPhase,
    val pauseReason: PauseReason? = null,
    val backendReady: Boolean = false,
) {
    init {
        require((phase == MonitorPhase.PAUSED) == (pauseReason != null)) {
            "pause reason must exist only for a paused session"
        }
        require(phase != MonitorPhase.READY || backendReady) {
            "ready phase requires an available backend"
        }
        require(phase != MonitorPhase.NOT_READY || !backendReady) {
            "not-ready phase requires an unavailable backend"
        }
    }

    val hasActiveSession: Boolean
        get() = phase == MonitorPhase.STARTING ||
            phase == MonitorPhase.RUNNING ||
            phase == MonitorPhase.PAUSED

    val samplesBackend: Boolean
        get() = phase == MonitorPhase.STARTING || phase == MonitorPhase.RUNNING

    companion object {
        fun initial(backendReady: Boolean = false): SessionMachineState = SessionMachineState(
            phase = if (backendReady) MonitorPhase.READY else MonitorPhase.NOT_READY,
            backendReady = backendReady,
        )
    }
}

sealed interface SessionEvent {
    data class BackendAvailable(
        val sameBoot: Boolean,
        val waitingForFirstFrame: Boolean = false,
    ) : SessionEvent
    data object BackendUnavailable : SessionEvent
    data object StartRequested : SessionEvent
    data object FirstFrameReceived : SessionEvent
    data object UserPauseRequested : SessionEvent
    data class UserResumeRequested(
        val waitingForFirstFrame: Boolean = false,
    ) : SessionEvent
    data object StopRequested : SessionEvent
    data object StorageFailed : SessionEvent
    data class StorageRecovered(
        val previousPhase: MonitorPhase,
        val previousPauseReason: PauseReason?,
    ) : SessionEvent
    data object Interrupted : SessionEvent
    data object Reset : SessionEvent
}

object SessionStateMachine {
    fun reduce(state: SessionMachineState, event: SessionEvent): SessionMachineState = when (event) {
        is SessionEvent.BackendAvailable -> backendAvailable(state, event)
        SessionEvent.BackendUnavailable -> backendUnavailable(state)
        SessionEvent.StartRequested -> if (state.phase == MonitorPhase.READY) {
            state.copy(phase = MonitorPhase.STARTING)
        } else {
            state
        }
        SessionEvent.FirstFrameReceived -> if (
            state.phase == MonitorPhase.STARTING && state.backendReady
        ) {
            state.copy(phase = MonitorPhase.RUNNING)
        } else {
            state
        }
        SessionEvent.UserPauseRequested -> when {
            state.phase == MonitorPhase.STARTING || state.phase == MonitorPhase.RUNNING ->
                state.copy(phase = MonitorPhase.PAUSED, pauseReason = PauseReason.USER)
            state.phase == MonitorPhase.PAUSED && state.pauseReason == PauseReason.SHIZUKU ->
                state.copy(pauseReason = PauseReason.USER)
            else -> state
        }
        is SessionEvent.UserResumeRequested -> if (
            state.phase == MonitorPhase.PAUSED && state.pauseReason == PauseReason.USER
        ) {
            if (state.backendReady) {
                state.copy(
                    phase = if (event.waitingForFirstFrame) {
                        MonitorPhase.STARTING
                    } else {
                        MonitorPhase.RUNNING
                    },
                    pauseReason = null,
                )
            } else {
                state.copy(pauseReason = PauseReason.SHIZUKU)
            }
        } else {
            state
        }
        SessionEvent.StopRequested -> if (state.hasActiveSession) {
            state.copy(phase = MonitorPhase.COMPLETED, pauseReason = null)
        } else {
            state
        }
        SessionEvent.StorageFailed -> if (
            state.hasActiveSession ||
            state.phase == MonitorPhase.COMPLETED ||
            state.phase == MonitorPhase.INTERRUPTED
        ) {
            state.copy(phase = MonitorPhase.PAUSED, pauseReason = PauseReason.STORAGE)
        } else {
            state
        }
        is SessionEvent.StorageRecovered -> storageRecovered(state, event)
        SessionEvent.Interrupted -> if (state.hasActiveSession) {
            state.copy(phase = MonitorPhase.INTERRUPTED, pauseReason = null)
        } else {
            state
        }
        SessionEvent.Reset -> if (
            state.phase == MonitorPhase.COMPLETED || state.phase == MonitorPhase.INTERRUPTED
        ) {
            initialFor(state.backendReady)
        } else {
            state
        }
    }

    private fun backendAvailable(
        state: SessionMachineState,
        event: SessionEvent.BackendAvailable,
    ): SessionMachineState {
        if (!event.sameBoot && state.hasActiveSession) {
            return state.copy(
                phase = MonitorPhase.INTERRUPTED,
                pauseReason = null,
                backendReady = true,
            )
        }
        return when {
            state.phase == MonitorPhase.NOT_READY ->
                SessionMachineState.initial(backendReady = true)
            state.phase == MonitorPhase.PAUSED && state.pauseReason == PauseReason.SHIZUKU ->
                state.copy(
                    phase = if (event.waitingForFirstFrame) {
                        MonitorPhase.STARTING
                    } else {
                        MonitorPhase.RUNNING
                    },
                    pauseReason = null,
                    backendReady = true,
                )
            else -> state.copy(backendReady = true)
        }
    }

    private fun backendUnavailable(state: SessionMachineState): SessionMachineState = when {
        state.phase == MonitorPhase.READY -> SessionMachineState.initial(backendReady = false)
        state.phase == MonitorPhase.STARTING || state.phase == MonitorPhase.RUNNING -> state.copy(
            phase = MonitorPhase.PAUSED,
            pauseReason = PauseReason.SHIZUKU,
            backendReady = false,
        )
        else -> state.copy(backendReady = false)
    }

    private fun storageRecovered(
        state: SessionMachineState,
        event: SessionEvent.StorageRecovered,
    ): SessionMachineState {
        if (state.phase != MonitorPhase.PAUSED || state.pauseReason != PauseReason.STORAGE) {
            return state
        }
        if (event.previousPauseReason == PauseReason.USER) {
            return state.copy(pauseReason = PauseReason.USER)
        }
        if (!state.backendReady) {
            return state.copy(pauseReason = PauseReason.SHIZUKU)
        }
        return state.copy(
            phase = if (event.previousPhase == MonitorPhase.STARTING) {
                MonitorPhase.STARTING
            } else {
                MonitorPhase.RUNNING
            },
            pauseReason = null,
        )
    }

    private fun initialFor(backendReady: Boolean): SessionMachineState =
        SessionMachineState.initial(backendReady)
}
