package io.github.PctAIGM.procview.monitor

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorWakeLock(
    context: Context,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        ?.apply { setReferenceCounted(false) }
    private var required = false
    private var renewalJob: Job? = null

    val isHeld: Boolean
        get() = wakeLock?.isHeld == true

    fun setRequired(value: Boolean): Boolean {
        if (required == value) {
            if (value && !isHeld) acquireFreshLease()
            return isHeld
        }
        required = value
        if (value) {
            acquireFreshLease()
            renewalJob = scope.launch {
                while (isActive && required) {
                    delay(RENEWAL_INTERVAL_MS)
                    if (required) acquireFreshLease()
                }
            }
        } else {
            renewalJob?.cancel()
            renewalJob = null
            releaseIfHeld()
        }
        return isHeld
    }

    override fun close() {
        required = false
        renewalJob?.cancel()
        renewalJob = null
        releaseIfHeld()
    }

    private fun acquireFreshLease() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
            lock.acquire(LEASE_TIMEOUT_MS)
        }
    }

    private fun releaseIfHeld() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "io.github.PctAIGM.procview:monitor"
        const val LEASE_TIMEOUT_MS = 10 * 60 * 1_000L
        const val RENEWAL_INTERVAL_MS = 5 * 60 * 1_000L
    }
}
