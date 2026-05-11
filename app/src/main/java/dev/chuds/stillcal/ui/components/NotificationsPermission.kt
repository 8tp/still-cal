package dev.chuds.stillcal.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Reactive POST_NOTIFICATIONS permission state. On API < 33 the permission is implicit
 * and [granted] is always true. The caller invokes [request] when the user first opts
 * into a reminder; if the system dialog dismisses with a denial, [granted] flips false
 * and EventEditScreen surfaces the "notifications disabled" caption per spec §7.5.
 */
class NotificationsPermissionState internal constructor(
    private val grantedState: MutableState<Boolean>,
    val request: () -> Unit,
) {
    val granted: Boolean get() = grantedState.value
}

@Composable
fun rememberNotificationsPermissionState(): NotificationsPermissionState {
    val context = LocalContext.current
    val grantedState = remember {
        mutableStateOf(currentlyGranted(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        grantedState.value = granted
    }
    return remember {
        NotificationsPermissionState(
            grantedState = grantedState,
            request = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!grantedState.value) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    grantedState.value = true
                }
            },
        )
    }
}

private fun currentlyGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ActivityCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}
