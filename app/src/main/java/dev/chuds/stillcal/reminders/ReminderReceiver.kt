package dev.chuds.stillcal.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import dev.chuds.stillcal.MainActivity
import dev.chuds.stillcal.R
import dev.chuds.stillcal.data.EventsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives a fired alarm and posts a notification. Then asks the scheduler to queue the
 * following occurrence — that's how recurring events get their alarms without enumerating
 * thousands of them up front.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(RemindersScheduler.EXTRA_EVENT_ID) ?: return
        val occurrenceMs = intent.getLongExtra(RemindersScheduler.EXTRA_OCCURRENCE_MS, 0L)
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val repository = EventsRepository(context.applicationContext)
                repository.load()
                val event = repository.events.value.firstOrNull { it.id == eventId }
                if (event != null) {
                    postNotification(
                        context = context,
                        eventId = eventId,
                        title = event.title,
                        occurrenceMs = occurrenceMs,
                        reminder = event.reminder,
                    )
                    // Schedule the *following* occurrence of this same event.
                    RemindersScheduler.scheduleNext(context, event, repository)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(
        context: Context,
        eventId: String,
        title: String,
        occurrenceMs: Long,
        reminder: dev.chuds.stillcal.data.ReminderOffset?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        RemindersScheduler.ensureChannel(context)
        val zone = ZoneId.systemDefault()
        // Spec §7.4: body is "HH:mm plus the reminder lead (in 15 minutes)" — start time
        // first, then a quiet lead phrase so the user can tell the notification apart
        // from the event itself.
        val occurrenceText = if (occurrenceMs > 0) {
            val time = DateTimeFormatter.ofPattern("HH:mm").format(
                Instant.ofEpochMilli(occurrenceMs).atZone(zone).toLocalTime()
            )
            val lead = reminder?.let { leadPhrase(it) }
            if (lead != null) "at $time · $lead" else "at $time"
        } else {
            ""
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_DATE_EPOCH_DAY, Instant.ofEpochMilli(occurrenceMs)
                .atZone(zone).toLocalDate().toEpochDay())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            eventId.hashCode() and 0x7FFFFFFF,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(context, RemindersScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_still_cal_notification)
            .setContentTitle(title.ifBlank { "untitled" })
            .setContentText(occurrenceText)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(eventId.hashCode() and 0x7FFFFFFF, notification)
    }

    private fun leadPhrase(reminder: dev.chuds.stillcal.data.ReminderOffset): String =
        when (reminder) {
            dev.chuds.stillcal.data.ReminderOffset.AtStart -> "starting now"
            dev.chuds.stillcal.data.ReminderOffset.FiveMin -> "in 5 minutes"
            dev.chuds.stillcal.data.ReminderOffset.FifteenMin -> "in 15 minutes"
            dev.chuds.stillcal.data.ReminderOffset.OneHour -> "in 1 hour"
            dev.chuds.stillcal.data.ReminderOffset.OneDay -> "in 1 day"
        }

    companion object {
        const val EXTRA_OPEN_DATE_EPOCH_DAY = "open_date_epoch_day"
    }
}
