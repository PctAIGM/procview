package io.github.PctAIGM.procview.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator

class ShizukuProbeViewModel(
    private val coordinator: ShizukuCoordinator,
) : ViewModel() {
    val state = coordinator.state

    fun performPrimaryAction() {
        when (state.value.phase) {
            ShizukuPhase.NOT_INSTALLED,
            ShizukuPhase.NOT_RUNNING,
            -> coordinator.openShizukuOrDownload()

            ShizukuPhase.PERMISSION_REQUIRED,
            ShizukuPhase.PERMISSION_DENIED,
            -> coordinator.requestPermission()

            ShizukuPhase.INCOMPATIBLE,
            ShizukuPhase.AVAILABLE,
            ShizukuPhase.PARTIAL,
            ShizukuPhase.ERROR,
            -> coordinator.refresh()

            ShizukuPhase.CHECKING,
            ShizukuPhase.CONNECTING,
            ShizukuPhase.PROBING,
            -> Unit
        }
    }

    class Factory(
        private val coordinator: ShizukuCoordinator,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(ShizukuProbeViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ShizukuProbeViewModel(coordinator) as T
        }
    }
}
