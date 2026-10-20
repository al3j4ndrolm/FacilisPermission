package io.github.al3j4ndrolm.facilis.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Permission state for Compose. Updates on its own when the user comes back from Settings.
 *
 * ```
 * val location = rememberPermission(Permission.Location)
 * if (location.granted) Map() else Button(onClick = { location.request() }) { Text("Allow location") }
 * ```
 *
 * @param reason optional explanation shown before asking again after a denial.
 */
@Composable
fun rememberPermission(vararg permissions: Permission, reason: String? = null): PermissionState {
    val key = permissions.toList()
    val state = remember(key) { PermissionState(key) }
    state.reason = reason
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}

@Stable
class PermissionState internal constructor(private val permissions: List<Permission>) {

    internal var reason: String? = null

    /** The overall status: the least-allowed of the permissions. */
    var status: PermissionStatus by mutableStateOf(FacilisPermission.status(*permissions.toTypedArray()))
        private set

    /** True when every permission is granted (fully or [PermissionStatus.Limited]). */
    val granted: Boolean get() = status.isGranted

    /** Asks for the permissions; [onResult] is optional. */
    fun request(onResult: (granted: Boolean) -> Unit = {}) {
        FacilisPermission.request(*permissions.toTypedArray(), reason = reason) { granted ->
            refresh()
            onResult(granted)
        }
    }

    /** Opens this app's page in system Settings. */
    fun openSettings() = FacilisPermission.openSettings()

    internal fun refresh() {
        status = FacilisPermission.status(*permissions.toTypedArray())
    }
}
