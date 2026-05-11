package dev.chuds.stillcal.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.chuds.stillcal.data.EventsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager forgets every scheduled alarm across a reboot, so without this BOOT_COMPLETED
 * handler every reminder dies on first reboot. Direct-boot friendly via LOCKED_BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val repository = EventsRepository(context.applicationContext)
                RemindersScheduler.rescheduleAll(context, repository)
            } finally {
                pending.finish()
            }
        }
    }
}
