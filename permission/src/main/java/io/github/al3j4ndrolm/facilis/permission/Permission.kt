package io.github.al3j4ndrolm.facilis.permission

import android.Manifest

/**
 * What your app wants to do. Each one maps to the right Android permissions for the device's
 * Android version, so you never deal with version checks.
 */
enum class Permission {
    Camera,
    Microphone,

    /** Precise and approximate location. If the user only allows approximate, the status is [PermissionStatus.Limited]. */
    Location,

    /** Posting notifications. Android 13+ asks; older versions only need notifications switched on in Settings. */
    Notifications,

    /** Reading the user's photos. On Android 14+ the user may share only some photos: [PermissionStatus.Limited]. */
    Photos,
    Contacts,

    /** Finding and connecting to Bluetooth devices. Android 12+ asks; older versions need nothing. */
    Bluetooth,
}

enum class PermissionStatus {
    /** Fully allowed. */
    Granted,

    /** Partly allowed: approximate location only, or only some photos. Counts as granted. */
    Limited,

    /** Not allowed yet. Requesting shows the system dialog. */
    Denied,

    /** Blocked ("Don't allow" more than once). Only Settings can change it: see [FacilisPermission.openSettings]. */
    PermanentlyDenied;

    /** True for [Granted] and [Limited]. */
    val isGranted: Boolean get() = this == Granted || this == Limited
}

// region Pure logic (unit tested)

/**
 * The Android permissions behind [this] on Android [sdk], in the order they should be asked.
 * Empty means nothing has to be asked on that version.
 */
internal fun Permission.androidPermissions(sdk: Int): List<String> = when (this) {
    Permission.Camera -> listOf(Manifest.permission.CAMERA)
    Permission.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
    Permission.Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    Permission.Notifications -> if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    Permission.Photos -> when {
        sdk >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk == 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    Permission.Contacts -> listOf(Manifest.permission.READ_CONTACTS)
    Permission.Bluetooth -> if (sdk >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }
}

/**
 * The permissions to actually ask for: the ones the app declared in its manifest.
 *
 * @throws IllegalStateException if the app declared none of them, with the lines to add.
 */
internal fun Permission.permissionsToRequest(sdk: Int, declared: Set<String>): List<String> {
    val all = androidPermissions(sdk)
    val toRequest = all.filter { it in declared }
    check(all.isEmpty() || toRequest.isNotEmpty()) {
        "Permission.$name needs to be declared in your AndroidManifest.xml. Add:\n" +
            all.joinToString("\n") { "<uses-permission android:name=\"$it\" />" }
    }
    return toRequest
}

/**
 * The status of [permission], given the Android permissions that apply ([requested]) and what
 * Android reports for each of them.
 *
 * @param blocked whether the permission was denied in a way the system won't ask about again.
 */
internal fun resolveStatus(
    permission: Permission,
    requested: List<String>,
    granted: (String) -> Boolean,
    blocked: (String) -> Boolean,
): PermissionStatus {
    if (requested.isEmpty()) return PermissionStatus.Granted

    when (permission) {
        Permission.Location -> {
            val fine = Manifest.permission.ACCESS_FINE_LOCATION
            val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
            if (fine in requested && granted(fine)) return PermissionStatus.Granted
            if (coarse in requested && granted(coarse)) {
                return if (fine in requested) PermissionStatus.Limited else PermissionStatus.Granted
            }
        }
        Permission.Photos -> {
            val full = requested.firstOrNull { it != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
            val partial = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            if (full != null && granted(full)) return PermissionStatus.Granted
            if (partial in requested && granted(partial)) return PermissionStatus.Limited
        }
        else -> if (requested.all(granted)) return PermissionStatus.Granted
    }

    return if (requested.any { !granted(it) && blocked(it) }) PermissionStatus.PermanentlyDenied else PermissionStatus.Denied
}

/** The overall status of several permissions: the least-allowed one wins. */
internal fun combine(statuses: Collection<PermissionStatus>): PermissionStatus = when {
    statuses.isEmpty() -> PermissionStatus.Granted
    PermissionStatus.PermanentlyDenied in statuses -> PermissionStatus.PermanentlyDenied
    PermissionStatus.Denied in statuses -> PermissionStatus.Denied
    PermissionStatus.Limited in statuses -> PermissionStatus.Limited
    else -> PermissionStatus.Granted
}

// endregion
