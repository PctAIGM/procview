package io.github.PctAIGM.procview

import android.app.Application
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator

class ProcViewApplication : Application() {
    val shizukuCoordinator: ShizukuCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ShizukuCoordinator(this)
    }
}
