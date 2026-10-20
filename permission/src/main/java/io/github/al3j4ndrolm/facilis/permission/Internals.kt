package io.github.al3j4ndrolm.facilis.permission

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.startup.Initializer
import java.lang.ref.WeakReference

/**
 * Knows the app's current activity, so requests work without passing a Context.
 * Installed automatically at app start by [FacilisPermissionInitializer].
 */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {

    private var appContextOrNull: Context? = null
    private var resumed: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val appContext: Context
        get() = appContextOrNull ?: error(
            "FacilisPermission isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    /** The activity on screen right now, if any (never the permission screen itself). */
    val resumedActivity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(context: Context) {
        if (appContextOrNull != null) return
        val app = context.applicationContext as Application
        appContextOrNull = app
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is PermissionActivity) return
        resumed = WeakReference(activity)
        // Post so results arrive after the screen has fully resumed.
        mainHandler.post { PermissionSession.deliverPending() }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/**
 * Runs one request at a time and hands each result back once the caller's screen is resumed, so
 * callers can safely navigate in their callback. Only touched from the main thread.
 */
internal object PermissionSession {

    class Request(
        val permissions: List<Permission>,
        val reason: String?,
        val onResult: (Boolean) -> Unit,
    )

    private val queue = ArrayDeque<Request>()
    private val results = ArrayDeque<Pair<Request, Boolean>>()

    /** The request the permission screen is working on. */
    var current: Request? = null
        private set

    fun enqueue(request: Request) {
        queue.addLast(request)
        if (current == null && results.isEmpty()) startNext()
    }

    fun complete(granted: Boolean) {
        val request = current ?: return
        current = null
        results.addLast(request to granted)
    }

    fun deliverPending() {
        while (results.isNotEmpty()) {
            val (request, granted) = results.removeFirst()
            request.onResult(granted)
        }
        if (current == null) startNext()
    }

    private fun startNext() {
        val next = queue.removeFirstOrNull() ?: return
        val activity = ActivityTracker.resumedActivity
        if (activity == null) {
            // Can't show a dialog with nothing on screen (e.g. called from the background).
            results.addLast(next to FacilisPermission.check(*next.permissions.toTypedArray()))
            return
        }
        current = next
        activity.startActivity(Intent(activity, PermissionActivity::class.java))
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}

/**
 * Remembers which Android permissions the system refused without asking. Android doesn't expose
 * this directly: before the first request, "never asked" and "blocked" look the same.
 */
internal object BlockedPermissions {
    private const val PREFS = "facilis_permission"
    private const val KEY = "blocked"

    private val prefs get() = ActivityTracker.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun contains(permission: String): Boolean = permission in prefs.getStringSet(KEY, emptySet()).orEmpty()

    fun update(blocked: Collection<String>, allowed: Collection<String>) {
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val next = current - allowed.toSet() + blocked
        if (next != current) prefs.edit().putStringSet(KEY, next).apply()
    }
}

internal class FacilisPermissionInitializer : Initializer<Unit> {
    override fun create(context: Context) = ActivityTracker.install(context)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
