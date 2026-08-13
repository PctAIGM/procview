package io.github.PctAIGM.procview.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.PctAIGM.procview.MainActivity
import io.github.PctAIGM.procview.R
import java.util.Locale

class MonitorNotificationFactory(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.monitor_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.monitor_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager?.createNotificationChannel(channel)
    }

    fun build(snapshot: MonitorRuntimeSnapshot, nowElapsedRealtimeNanos: Long): Notification {
        val sessionName = (snapshot.sessionName ?: context.getString(R.string.default_session_name))
            .take(MAX_SESSION_NAME_CHARS)
        val status = statusText(snapshot)
        val duration = formatDuration(
            startedElapsedRealtimeNanos = snapshot.startedElapsedRealtimeNanos,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        val cpu = snapshot.lastFrame?.systemCpuPercentBasisPoints?.let { basisPoints ->
            context.getString(R.string.value_percent, basisPoints / 100.0)
        } ?: "—"
        val memory = snapshot.lastFrame?.let { frame ->
            val total = frame.memoryTotalKb
            val available = frame.memoryAvailableKb
            if (total != null && total > 0 && available != null) {
                context.getString(
                    R.string.value_percent,
                    ((total - available).coerceAtLeast(0L).toDouble() / total.toDouble()) * 100.0,
                )
            } else {
                null
            }
        } ?: "—"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.monitor_notification_title, sessionName))
            .setContentText(
                context.getString(
                    R.string.monitor_notification_content,
                    status,
                    duration,
                    cpu,
                    memory,
                    snapshot.effectiveIntervalMs,
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.monitor_notification_content,
                        status,
                        duration,
                        cpu,
                        memory,
                        snapshot.effectiveIntervalMs,
                    ),
                ),
            )
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(snapshot.machineState.hasActiveSession)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPublicVersion(buildPublicVersion(status))

        when {
            snapshot.machineState.phase == MonitorPhase.PAUSED &&
                snapshot.machineState.pauseReason == PauseReason.USER -> builder.addAction(
                action(
                    icon = R.drawable.ic_notification_play,
                    title = context.getString(R.string.action_resume_session),
                    action = MonitorServiceActions.RESUME,
                    requestCode = REQUEST_RESUME,
                ),
            )
            snapshot.machineState.phase == MonitorPhase.PAUSED &&
                snapshot.machineState.pauseReason == PauseReason.STORAGE -> builder.addAction(
                action(
                    icon = R.drawable.ic_notification_play,
                    title = context.getString(R.string.action_retry_storage),
                    action = MonitorServiceActions.RESUME,
                    requestCode = REQUEST_RESUME,
                ),
            )
            snapshot.machineState.phase == MonitorPhase.STARTING ||
                snapshot.machineState.phase == MonitorPhase.RUNNING -> builder.addAction(
                action(
                    icon = R.drawable.ic_notification_pause,
                    title = context.getString(R.string.action_pause_session),
                    action = MonitorServiceActions.PAUSE,
                    requestCode = REQUEST_PAUSE,
                ),
            )
        }
        if (snapshot.machineState.hasActiveSession) {
            builder.addAction(
                action(
                    icon = R.drawable.ic_notification_stop,
                    title = context.getString(R.string.action_stop_session),
                    action = MonitorServiceActions.STOP,
                    requestCode = REQUEST_STOP,
                ),
            )
        }
        return builder.build()
    }

    private fun buildPublicVersion(status: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.monitor_notification_public_title))
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(icon: Int, title: String, action: String, requestCode: Int) =
        NotificationCompat.Action.Builder(
            icon,
            title,
            PendingIntent.getForegroundService(
                context,
                requestCode,
                Intent(context, MonitorService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun statusText(snapshot: MonitorRuntimeSnapshot): String = when (snapshot.machineState.phase) {
        MonitorPhase.NOT_READY -> context.getString(R.string.monitor_status_not_ready)
        MonitorPhase.READY -> context.getString(R.string.monitor_status_ready)
        MonitorPhase.STARTING -> context.getString(R.string.monitor_status_starting)
        MonitorPhase.RUNNING -> context.getString(R.string.monitor_status_running)
        MonitorPhase.COMPLETED -> context.getString(R.string.monitor_status_completed)
        MonitorPhase.INTERRUPTED -> context.getString(R.string.monitor_status_interrupted)
        MonitorPhase.PAUSED -> when (snapshot.machineState.pauseReason) {
            PauseReason.USER -> context.getString(R.string.monitor_status_paused_user)
            PauseReason.SHIZUKU -> context.getString(R.string.monitor_status_paused_shizuku)
            PauseReason.STORAGE -> context.getString(R.string.monitor_status_paused_storage)
            null -> context.getString(R.string.monitor_status_paused_user)
        }
    }

    private fun formatDuration(
        startedElapsedRealtimeNanos: Long?,
        nowElapsedRealtimeNanos: Long,
    ): String {
        val seconds = startedElapsedRealtimeNanos?.let { started ->
            ((nowElapsedRealtimeNanos - started).coerceAtLeast(0L) / NANOS_PER_SECOND)
        } ?: 0L
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val remainder = seconds % SECONDS_PER_MINUTE
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, remainder)
    }

    companion object {
        const val CHANNEL_ID = "monitoring_sessions"
        const val NOTIFICATION_ID = 10_031
        private const val REQUEST_OPEN_APP = 20_001
        private const val REQUEST_PAUSE = 20_002
        private const val REQUEST_RESUME = 20_003
        private const val REQUEST_STOP = 20_004
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L
        private const val MAX_SESSION_NAME_CHARS = 80
    }
}

object MonitorServiceActions {
    const val START = "io.github.PctAIGM.procview.action.START_MONITOR"
    const val PAUSE = "io.github.PctAIGM.procview.action.PAUSE_MONITOR"
    const val RESUME = "io.github.PctAIGM.procview.action.RESUME_MONITOR"
    const val STOP = "io.github.PctAIGM.procview.action.STOP_MONITOR"
    const val EXTRA_SESSION_NAME = "session_name"
    const val EXTRA_PRESET = "sampling_preset"
}
