package io.github.al3j4ndrolm.facilis.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLogicTest {

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val selected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val storage = "android.permission.READ_EXTERNAL_STORAGE"
    private val camera = "android.permission.CAMERA"
    private val notifications = "android.permission.POST_NOTIFICATIONS"

    // region Android versions

    @Test
    fun notificationsOnlyNeedAskingOnAndroid13() {
        assertEquals(emptyList<String>(), Permission.Notifications.androidPermissions(32))
        assertEquals(listOf(notifications), Permission.Notifications.androidPermissions(33))
    }

    @Test
    fun photosUseTheRightPermissionPerVersion() {
        assertEquals(listOf(storage), Permission.Photos.androidPermissions(32))
        assertEquals(listOf(images), Permission.Photos.androidPermissions(33))
        assertEquals(listOf(images, selected), Permission.Photos.androidPermissions(34))
    }

    @Test
    fun bluetoothOnlyNeedsAskingOnAndroid12() {
        assertEquals(emptyList<String>(), Permission.Bluetooth.androidPermissions(30))
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            Permission.Bluetooth.androidPermissions(31),
        )
    }

    @Test
    fun locationAsksForPreciseAndApproximate() {
        assertEquals(listOf(fine, coarse), Permission.Location.androidPermissions(37))
    }

    // endregion

    // region Manifest

    @Test
    fun onlyDeclaredPermissionsAreRequested() {
        assertEquals(listOf(coarse), Permission.Location.permissionsToRequest(37, declared = setOf(coarse)))
        assertEquals(listOf(images), Permission.Photos.permissionsToRequest(34, declared = setOf(images)))
    }

    @Test
    fun missingDeclarationGivesTheLinesToAdd() {
        val error = assertThrows(IllegalStateException::class.java) {
            Permission.Camera.permissionsToRequest(37, declared = emptySet())
        }
        assertTrue(error.message!!.contains("<uses-permission android:name=\"$camera\" />"))
    }

    @Test
    fun nothingToDeclareWhenTheVersionDoesNotAsk() {
        assertEquals(emptyList<String>(), Permission.Notifications.permissionsToRequest(32, declared = emptySet()))
    }

    // endregion

    // region Status

    private fun status(
        permission: Permission,
        requested: List<String>,
        granted: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
    ) = resolveStatus(permission, requested, { it in granted }, { it in blocked })

    @Test
    fun grantedWhenAllAreGranted() {
        assertEquals(PermissionStatus.Granted, status(Permission.Camera, listOf(camera), granted = setOf(camera)))
    }

    @Test
    fun deniedVersusPermanentlyDenied() {
        assertEquals(PermissionStatus.Denied, status(Permission.Camera, listOf(camera)))
        assertEquals(PermissionStatus.PermanentlyDenied, status(Permission.Camera, listOf(camera), blocked = setOf(camera)))
    }

    @Test
    fun approximateLocationIsLimited() {
        assertEquals(PermissionStatus.Granted, status(Permission.Location, listOf(fine, coarse), granted = setOf(fine, coarse)))
        assertEquals(PermissionStatus.Limited, status(Permission.Location, listOf(fine, coarse), granted = setOf(coarse)))
        // Only approximate was asked for, so approximate is everything.
        assertEquals(PermissionStatus.Granted, status(Permission.Location, listOf(coarse), granted = setOf(coarse)))
    }

    @Test
    fun selectedPhotosAreLimited() {
        assertEquals(PermissionStatus.Granted, status(Permission.Photos, listOf(images, selected), granted = setOf(images)))
        assertEquals(PermissionStatus.Limited, status(Permission.Photos, listOf(images, selected), granted = setOf(selected)))
        assertEquals(PermissionStatus.Denied, status(Permission.Photos, listOf(images, selected)))
    }

    @Test
    fun nothingToAskMeansGranted() {
        assertEquals(PermissionStatus.Granted, status(Permission.Bluetooth, emptyList()))
    }

    @Test
    fun combinedStatusIsTheLeastAllowed() {
        assertEquals(PermissionStatus.Granted, combine(listOf(PermissionStatus.Granted, PermissionStatus.Granted)))
        assertEquals(PermissionStatus.Limited, combine(listOf(PermissionStatus.Granted, PermissionStatus.Limited)))
        assertEquals(PermissionStatus.Denied, combine(listOf(PermissionStatus.Limited, PermissionStatus.Denied)))
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            combine(listOf(PermissionStatus.Denied, PermissionStatus.PermanentlyDenied)),
        )
    }

    @Test
    fun limitedCountsAsGranted() {
        assertTrue(PermissionStatus.Granted.isGranted)
        assertTrue(PermissionStatus.Limited.isGranted)
        assertFalse(PermissionStatus.Denied.isGranted)
        assertFalse(PermissionStatus.PermanentlyDenied.isGranted)
    }

    // endregion
}
