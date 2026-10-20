package io.github.al3j4ndrolm.facilis.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Runtime permissions with no setup. Available everywhere (Activity, Fragment, Compose, ViewModel):
 *
 * ```
 * permissions.request(Permission.Location) { granted -> }
 * if (permissions.check(Permission.Camera)) { ... }
 * ```
 */
val permissions: FacilisPermission get() = FacilisPermission

object FacilisPermission {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True if every one of [permissions] is granted (fully or [PermissionStatus.Limited]). */
    fun check(vararg permissions: Permission): Boolean = permissions.all { status(it).isGranted }

    /** The detailed status of [permission]. */
    fun status(permission: Permission): PermissionStatus {
        val context = ActivityTracker.appContext
        if (permission == Permission.Notifications && Build.VERSION.SDK_INT < 33) {
            // Nothing to ask before Android 13: only the Settings switch matters.
            return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                PermissionStatus.Granted
            } else {
                PermissionStatus.PermanentlyDenied
            }
        }
        val all = permission.androidPermissions(Build.VERSION.SDK_INT)
        val requested = all.filter { it in declaredPermissions(context) }.ifEmpty { all }
        return resolveStatus(
            permission = permission,
            requested = requested,
            granted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            blocked = { isBlocked(it) },
        )
    }

    /** The overall status of several permissions: the least-allowed one wins. */
    fun status(vararg permissions: Permission): PermissionStatus = combine(permissions.map { status(it) })

    /**
     * Asks for [permissions] if they aren't granted yet, then calls [onResult] with whether all of
     * them were granted. Calls back right away (no dialog) if they already are.
     *
     * @param reason optional explanation. Shown before asking again after a denial, and with an
     *   "Open settings" button if the user blocked the permission.
     * @throws IllegalStateException if a permission isn't declared in your AndroidManifest.xml.
     */
    fun request(vararg permissions: Permission, reason: String? = null, onResult: (granted: Boolean) -> Unit) {
        require(permissions.isNotEmpty()) { "Pass at least one Permission." }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { request(*permissions, reason = reason, onResult = onResult) }
            return
        }
        val list = permissions.distinct()
        // Fails fast on a missing manifest line, on the caller's stack.
        list.forEach { it.permissionsToRequest(Build.VERSION.SDK_INT, declaredPermissions(ActivityTracker.appContext)) }

        if (check(*permissions)) {
            mainHandler.post { onResult(true) }
            return
        }
        PermissionSession.enqueue(PermissionSession.Request(list, reason, onResult))
    }

    /** Coroutine version of [request]. Returns whether all [permissions] were granted. */
    suspend fun request(vararg permissions: Permission, reason: String? = null): Boolean =
        suspendCancellableCoroutine { continuation ->
            request(*permissions, reason = reason) { granted -> if (continuation.isActive) continuation.resume(granted) }
        }

    /** Opens this app's page in system Settings, where the user can turn permissions on. */
    fun openSettings() {
        val activity = ActivityTracker.resumedActivity
        val context: Context = activity ?: ActivityTracker.appContext
        val intent = settingsIntent(context)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // region Internal

    internal fun settingsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    @Volatile
    private var declared: Set<String>? = null

    internal fun declaredPermissions(context: Context): Set<String> = declared ?: run {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions.orEmpty().toSet().also { declared = it }
    }

    /** Blocked = Android said no without asking, so only Settings can change it. */
    internal fun isBlocked(androidPermission: String): Boolean {
        if (!BlockedPermissions.contains(androidPermission)) return false
        val activity = ActivityTracker.resumedActivity ?: return true
        return !activity.shouldShowRequestPermissionRationale(androidPermission)
    }

    // endregion
}
