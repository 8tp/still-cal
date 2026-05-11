package dev.chuds.stillcal.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.EventsRepository

/**
 * Owns the AlarmManager interaction. Schedules the *next* reminder for one event at a time —
 * recurring events lazily schedule the following occurrence from inside [ReminderReceiver],
 * so we never have to enumerate years of alarms.
 */
object RemindersScheduler {

    const val CHANNEL_ID = "still-cal-reminders"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_OCCURRENCE_MS = "occurrence_ms"

    /**
     * Schedule the next reminder for [event] if one is due in the future. Cancels any prior
     * pending intent for this event id first so re-saves replace, not stack.
     */
    fun scheduleNext(context: Context, event: Event, repository: EventsRepository) {
        cancel(context, event.id)
        val reminder = event.reminder ?: return
        val now = System.currentTimeMillis()
        val occurrenceMs = repository.nextOccurrenceMs(event, fromMs = now - 60_000) ?: return
        val triggerMs = occurrenceMs - reminder.minutesBefore * 60_000L
        if (triggerMs <= now) {
            // The reminder for this occurrence has already passed; try the one after.
            val nextOccurrence = repository.nextOccurrenceMs(event, fromMs = occurrenceMs + 60_000)
                ?: return
            val nextTrigger = nextOccurrence - reminder.minutesBefore * 60_000L
            if (nextTrigger > now) scheduleAt(context, event.id, nextTrigger, nextOccurrence)
            return
        }
        scheduleAt(context, event.id, triggerMs, occurrenceMs)
    }

    private fun scheduleAt(context: Context, eventId: String, triggerMs: Long, occurrenceMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, eventId, occurrenceMs) ?: return
        // On Android 12+ the user can revoke exact-alarm permission. Falling back to
        // setAndAllowWhileIdle keeps reminders working ±10–15min instead of silently
        // dropping them entirely (the user already saw the Toast at save time).
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canBeExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun cancel(context: Context, eventId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // The occurrence extra isn't part of identity; the request code is the event id hash.
        val pi = pendingIntent(
            context = context,
            eventId = eventId,
            occurrenceMs = 0L,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) alarmManager.cancel(pi)
    }

    /**
     * Reschedule every event's next reminder. Called from BootReceiver after a reboot,
     * since AlarmManager forgets every alarm across reboots.
     */
    suspend fun rescheduleAll(context: Context, repository: EventsRepository) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel(context)
        }
        repository.load()
        repository.events.value.forEach { event -> scheduleNext(context, event, repository) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "event reminders, scheduled locally"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntent(
        context: Context,
        eventId: String,
        occurrenceMs: Long,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "dev.chuds.stillcal.REMINDER"
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_MS, occurrenceMs)
        }
        return PendingIntent.getBroadcast(context, requestCodeFor(eventId), intent, flags)
    }

    private fun requestCodeFor(eventId: String): Int = eventId.hashCode() and 0x7FFFFFFF
}
