package io.github.PctAIGM.procview.shizuku.user

internal object ProbeErrorFlags {
    const val PROC_STAT = 1 shl 0
    const val PROC_MEMINFO = 1 shl 1
    const val BOOT_ID = 1 shl 2
    const val PROC_ENUMERATION = 1 shl 3
    const val PS_COMMAND = 1 shl 4
    const val PSS_COMMAND = 1 shl 5
    const val PSS_PARSE = 1 shl 6
    const val THERMAL = 1 shl 7
    const val COMMAND_OUTPUT_TRUNCATED = 1 shl 8
    const val FALLBACK_PARSE = 1 shl 9
}
