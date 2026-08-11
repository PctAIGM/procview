package io.github.PctAIGM.procview.shizuku.ipc

object IpcCodes {
    const val SOURCE_PROCFS = 1
    const val SOURCE_PS_FALLBACK = 2
    const val PSS_COMMAND_FAILED = 1 shl 0
    const val PSS_OUTPUT_TRUNCATED = 1 shl 1
    const val PSS_IDENTITY_CHANGED = 1 shl 2
}
