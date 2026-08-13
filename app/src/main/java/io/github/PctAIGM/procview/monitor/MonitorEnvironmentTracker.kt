package io.github.PctAIGM.procview.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonitorEnvironmentTracker(context: Context) : DefaultLifecycleObserver, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val processLifecycle = ProcessLifecycleOwner.get().lifecycle
    private val mutableState = MutableStateFlow(readEnvironment())
    val state = mutableState.asStateFlow()
    private var started = false
    private var receiverRegistered = false
    private var batteryIntent: Intent? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { publish() }

    private val environmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) batteryIntent = intent
            publish()
        }
    }

    fun start() {
        if (started) return
        started = true
        processLifecycle.addObserver(this)
        try {
            batteryIntent = ContextCompat.registerReceiver(
                applicationContext,
                environmentReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                },
                // These are protected framework broadcasts. Android's receiver guidance uses
                // EXPORTED for broadcasts originating outside this app so OEM/system senders
                // that do not run under the system UID are not silently excluded.
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (_: RuntimeException) {
            // Keep the foreground session alive with point-in-time PowerManager values.
            // Battery/screen change events are optional when an OEM rejects registration.
            receiverRegistered = false
            batteryIntent = null
        }
        runCatching {
            powerManager?.addThermalStatusListener(
                ContextCompat.getMainExecutor(applicationContext),
                thermalListener,
            )
        }
        publish()
    }

    override fun onStart(owner: LifecycleOwner) = publish()

    override fun onStop(owner: LifecycleOwner) = publish()

    override fun close() {
        if (!started) return
        started = false
        processLifecycle.removeObserver(this)
        if (receiverRegistered) {
            runCatching { applicationContext.unregisterReceiver(environmentReceiver) }
            receiverRegistered = false
        }
        runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
    }

    private fun publish() {
        mutableState.value = readEnvironment()
    }

    private fun readEnvironment(): MonitorEnvironment {
        val battery = batteryIntent
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            Int.MIN_VALUE,
        ) ?: Int.MIN_VALUE
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return MonitorEnvironment(
            appForeground = processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            screenInteractive = powerManager?.isInteractive == true,
            batteryLevelPercent = if (level >= 0 && scale > 0) {
                ((level.toLong() * 100L) / scale.toLong()).toInt().coerceIn(0, 100)
            } else {
                null
            },
            batteryTemperatureDeciC = temperature.takeIf { it != Int.MIN_VALUE },
            chargingState = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> ChargingState.CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> ChargingState.FULL
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> ChargingState.DISCHARGING
                else -> ChargingState.UNKNOWN
            },
            thermalStatus = powerManager?.currentThermalStatus,
        )
    }
}
