package dev.chuds.stillcal

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chuds.stillcal.reminders.ReminderReceiver
import dev.chuds.stillcal.ui.theme.StillTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private var viewUriRequest by mutableStateOf<android.net.Uri?>(null)
    private var dateOpenRequest by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        consumeLaunchIntent(intent)

        setContent {
            StillTheme {
                StillCalApp(
                    incomingDateOpen = dateOpenRequest,
                    incomingViewUri = viewUriRequest,
                    onIncomingDateOpenHandled = { dateOpenRequest = null },
                    onIncomingViewUriHandled = { viewUriRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(incoming: Intent?) {
        val uri = consumeViewIntentIfAny(incoming)
        if (uri != null) {
            viewUriRequest = uri
        }
        val date = consumeNotificationDate(incoming)
        if (date != null) {
            dateOpenRequest = date
        }
    }

    /**
     * Pull a `text/calendar` ACTION_VIEW URI off the intent so StillCalApp can import it.
     * Marks the intent consumed so a configuration change doesn't re-fire it.
     */
    private fun consumeViewIntentIfAny(incoming: Intent?): android.net.Uri? {
        incoming ?: return null
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
    private fun consumeNotificationDate(incoming: Intent?): LocalDate? {
        incoming ?: return null
        val raw = incoming.getLongExtra(ReminderReceiver.EXTRA_OPEN_DATE_EPOCH_DAY, -1)
        if (raw < 0) return null
        incoming.removeExtra(ReminderReceiver.EXTRA_OPEN_DATE_EPOCH_DAY)
        return LocalDate.ofEpochDay(raw)
    }
}
