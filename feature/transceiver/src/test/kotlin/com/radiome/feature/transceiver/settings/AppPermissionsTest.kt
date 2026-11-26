package com.radiome.feature.transceiver.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionsTest {
    private fun row(rows: List<PermissionRow>, p: AppPermission) = rows.single { it.permission == p }

    @Test
    fun grantedPermissionsOfferRevoke() {
        val rows = permissionRows(34, micGranted = true, notificationsEnabled = true, askedBefore = emptySet(), showRationale = emptySet())
        assertEquals(PermissionStatus.GRANTED, row(rows, AppPermission.MICROPHONE).status)
        assertEquals(PermissionAction.REVOKE, row(rows, AppPermission.MICROPHONE).action)
        assertEquals(PermissionAction.REVOKE, row(rows, AppPermission.NOTIFICATIONS).action)
        assertEquals(PermissionStatus.NOT_NEEDED, row(rows, AppPermission.PHOTOS).status)
        assertEquals(PermissionAction.NONE, row(rows, AppPermission.PHOTOS).action)
    }

    @Test
    fun deniedPermissionAsksUntilAndroidStopsShowingThePrompt() {
        fun mic(asked: Set<AppPermission>, rationale: Set<AppPermission>) =
            row(permissionRows(34, false, false, asked, rationale), AppPermission.MICROPHONE)

        assertEquals(PermissionAction.REQUEST, mic(emptySet(), emptySet()).action)
        // Denied once: the system still shows its prompt.
        assertEquals(PermissionAction.REQUEST, mic(setOf(AppPermission.MICROPHONE), setOf(AppPermission.MICROPHONE)).action)
        // Denied twice: only the settings page can grant it.
        val blocked = mic(setOf(AppPermission.MICROPHONE), emptySet())
        assertEquals(PermissionStatus.DENIED, blocked.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, blocked.action)
    }

    @Test
    fun notificationsBeforeAndroid13AreASettingsSwitch() {
        val on = row(permissionRows(30, true, true, emptySet(), emptySet()), AppPermission.NOTIFICATIONS)
        assertEquals(PermissionStatus.GRANTED, on.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, on.action)
        val off = row(permissionRows(30, true, false, emptySet(), emptySet()), AppPermission.NOTIFICATIONS)
        assertEquals(PermissionStatus.DENIED, off.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, off.action)
    }

    @Test
    fun approximateLocationOffersPrecise() {
        val none = row(permissionRows(34, true, true, emptySet(), emptySet()), AppPermission.LOCATION)
        assertEquals(PermissionStatus.DENIED, none.status)
        assertEquals(PermissionAction.REQUEST, none.action)
        val approx = row(
            permissionRows(34, true, true, setOf(AppPermission.LOCATION), setOf(AppPermission.LOCATION), locationGranted = true, preciseLocation = false),
            AppPermission.LOCATION,
        )
        assertEquals(PermissionAction.REQUEST, approx.action)
        val precise = row(permissionRows(34, true, true, emptySet(), emptySet(), locationGranted = true, preciseLocation = true), AppPermission.LOCATION)
        assertEquals(PermissionAction.REVOKE, precise.action)
    }

    @Test
    fun locationSwitchOffPointsToSettings() {
        val off = row(permissionRows(34, true, true, emptySet(), emptySet(), locationServicesOn = false), AppPermission.LOCATION_SERVICES)
        assertEquals(PermissionStatus.DENIED, off.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, off.action)
        assertEquals(PermissionAction.NONE, row(permissionRows(34, true, true, emptySet(), emptySet()), AppPermission.INTERNET).action)
    }
}
