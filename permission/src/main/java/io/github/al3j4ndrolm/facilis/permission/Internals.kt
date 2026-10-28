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
import java.util.Collections
import java.util.WeakHashMap

/**
 * Knows the app's current activity, so requests work without passing a Context.
 * Installed automatically at app start by [FacilisPermissionInitializer].
 */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {

    private var appContextOrNull: Context? = null
    private var resumed: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Activities created or started but not stopped yet: on screen, or about to be. */
    private val showing: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    val appContext: Context
        get() = appContextOrNull ?: error(
            "FacilisPermission isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    /** The activity on screen right now, if any (never the permission screen itself). */
    val resumedActivity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /**
     * Whether a screen is on its way, e.g. called from `onCreate` or a ViewModel's `init` before
     * the first frame, or while switching screens. False when the app is in the background.
     */
    val screenComing: Boolean
        get() = showing.any { !it.isFinishing }

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
        showing -= activity
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is PermissionActivity) showing += activity
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity !is PermissionActivity) showing += activity
    }

    override fun onActivityStopped(activity: Activity) {
        showing -= activity
    }

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
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val activity = ActivityTracker.resumedActivity
        if (activity == null) {
            // Ask once the screen is resumed: onActivityResumed calls deliverPending.
            if (ActivityTracker.screenComing) return
            // Nothing on screen or on its way (called from the background): a dialog can't show, so
            // answer with the current state now rather than wait for the app to be opened again.
            while (queue.isNotEmpty()) {
                val request = queue.removeFirst()
                val granted = FacilisPermission.check(*request.permissions.toTypedArray())
                mainHandler.post { request.onResult(granted) }
            }
            return
        }
        val next = queue.removeFirstOrNull() ?: return
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
