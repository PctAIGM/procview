package io.github.PctAIGM.procview

import android.app.Application
import io.github.PctAIGM.procview.sampler.AndroidPackageResolver
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator
import io.github.PctAIGM.procview.shizuku.ShizukuProcBackend

class ProcViewApplication : Application() {
    val shizukuCoordinator: ShizukuCoordinator by lazy {
        ShizukuCoordinator(this)
    }
    val monitorBackend: ShizukuProcBackend by lazy {
        ShizukuProcBackend(shizukuCoordinator)
    }
    val packageResolver: AndroidPackageResolver by lazy {
        AndroidPackageResolver(packageManager)
    }
}
