package dev.chuds.stillcal

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.chuds.stillcal.reminders.ReminderReceiver
import dev.chuds.stillcal.ui.theme.StillTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val viewUri = consumeViewIntentIfAny()
        val dateOpen = consumeNotificationDate()

        setContent {
            StillTheme {
                StillCalApp(
                    incomingDateOpen = dateOpen,
                    incomingViewUri = viewUri,
                )
            }
        }
    }

    /**
     * Pull a `text/calendar` ACTION_VIEW URI off the intent so StillCalApp can import it.
     * Marks the intent consumed so a configuration change doesn't re-fire it.
     */
    private fun consumeViewIntentIfAny(): android.net.Uri? {
        val incoming = intent ?: return null
        if (incoming.action != Intent.ACTION_VIEW) return null
        val uri = incoming.data ?: return null
        incoming.action = null
        incoming.data = null
        return uri
    }

    /**
     * Pull the optional date-of-occurrence extra a tapped reminder notification places on its
     * intent so the app can open the right day list.
     */
    private fun consumeNotificationDate(): LocalDate? {
        val incoming = intent ?: return null
        val raw = incoming.getLongExtra(ReminderReceiver.EXTRA_OPEN_DATE_EPOCH_DAY, -1)
        if (raw < 0) return null
        incoming.removeExtra(ReminderReceiver.EXTRA_OPEN_DATE_EPOCH_DAY)
        return LocalDate.ofEpochDay(raw)
    }
}
