package io.github.PctAIGM.procview.model

import kotlinx.serialization.Serializable

@Serializable
enum class PinnedTargetKind {
    PACKAGE,
    PACKAGE_PROCESS,
    UID,
    COMMAND_UID,
}

@Serializable
data class PinnedTarget(
    val kind: PinnedTargetKind,
    val packageName: String? = null,
    val processName: String? = null,
    val uid: Int? = null,
) {
    val stableKey: String
        get() = when (kind) {
            PinnedTargetKind.PACKAGE -> "package:${packageName.orEmpty()}"
            PinnedTargetKind.PACKAGE_PROCESS ->
                "process:${packageName.orEmpty()}:${processName.orEmpty()}"
            PinnedTargetKind.UID -> "uid:${uid ?: -1}"
            PinnedTargetKind.COMMAND_UID -> "command:${uid ?: -1}:${processName.orEmpty()}"
        }

    fun isValid(): Boolean = when (kind) {
        PinnedTargetKind.PACKAGE -> !packageName.isNullOrBlank()
        PinnedTargetKind.PACKAGE_PROCESS ->
            !packageName.isNullOrBlank() && !processName.isNullOrBlank()
        PinnedTargetKind.UID -> uid != null && uid >= 0
        PinnedTargetKind.COMMAND_UID -> uid != null && uid >= 0 && !processName.isNullOrBlank()
    }

    companion object {
        fun packageTarget(packageName: String) = PinnedTarget(
            kind = PinnedTargetKind.PACKAGE,
            packageName = packageName.trim(),
        )

        fun packageProcessTarget(packageName: String, processName: String) = PinnedTarget(
            kind = PinnedTargetKind.PACKAGE_PROCESS,
            packageName = packageName.trim(),
            processName = processName.trim(),
        )

        fun uidTarget(uid: Int) = PinnedTarget(
            kind = PinnedTargetKind.UID,
            uid = uid,
        )

        fun commandUidTarget(processName: String, uid: Int) = PinnedTarget(
            kind = PinnedTargetKind.COMMAND_UID,
            processName = processName.trim(),
            uid = uid,
        )
    }
}
