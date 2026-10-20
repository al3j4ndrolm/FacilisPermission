package io.github.al3j4ndrolm.facilis.permission

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * An invisible screen that shows the explanation (if any) and the system permission dialog, then
 * reports back to [PermissionSession]. Internal: apps never start it directly.
 */
internal class PermissionActivity : ComponentActivity() {

    private var resultSent = false
    private var waitingForSettings = false
    private var requested: List<String> = emptyList()

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Denied without the system being willing to ask again = blocked until Settings.
        val denied = results.filterValues { !it }.keys
        BlockedPermissions.update(
            blocked = denied.filter { !shouldShowRequestPermissionRationale(it) },
            allowed = results.filterValues { it }.keys,
        )
        finishWithResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PermissionSession.current
        if (request == null) {
            finishQuietly()
            return
        }
        waitingForSettings = savedInstanceState?.getBoolean(STATE_SETTINGS) ?: false
        requested = request.permissions
            .flatMap { it.permissionsToRequest(Build.VERSION.SDK_INT, FacilisPermission.declaredPermissions(this)) }
            .distinct()
        // After a recreation the system dialog (and its result) survives on its own.
        if (savedInstanceState == null) start(request)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForSettings) {
            waitingForSettings = false
            finishWithResult()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS, waitingForSettings)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !resultSent) sendResult()
    }

    private fun start(request: PermissionSession.Request) {
        val missing = requested.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            finishWithResult()
            return
        }
        val reason = request.reason
        when {
            reason == null -> launcher.launch(missing.toTypedArray())
            missing.any { FacilisPermission.isBlocked(it) } -> explain(
                message = reason + "\n\n" + getString(R.string.facilis_permission_blocked_hint),
                positive = R.string.facilis_permission_open_settings,
            ) {
                waitingForSettings = true
                startActivity(FacilisPermission.settingsIntent(this))
            }
            missing.any { shouldShowRequestPermissionRationale(it) } -> explain(
                message = reason,
                positive = R.string.facilis_permission_continue,
            ) { launcher.launch(missing.toTypedArray()) }
            // First time: the system dialog says enough on its own.
            else -> launcher.launch(missing.toTypedArray())
        }
    }

    private fun explain(message: String, positive: Int, onPositive: () -> Unit) {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(this, theme)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton(R.string.facilis_permission_not_now) { _, _ -> finishWithResult() }
            .setOnCancelListener { finishWithResult() }
            .show()
    }

    private fun sendResult() {
        if (resultSent) return
        resultSent = true
        val request = PermissionSession.current ?: return
        PermissionSession.complete(FacilisPermission.check(*request.permissions.toTypedArray()))
    }

    private fun finishWithResult() {
        sendResult()
        finishQuietly()
    }

    private fun finishQuietly() {
        resultSent = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val STATE_SETTINGS = "facilis_permission.settings"
    }
}
